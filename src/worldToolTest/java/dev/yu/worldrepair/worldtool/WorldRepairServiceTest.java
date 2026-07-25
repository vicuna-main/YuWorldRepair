package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.adapter.LegacyChickenDataAdapter;
import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.job.JobManifest;
import dev.yu.worldrepair.worldtool.job.JobState;
import dev.yu.worldrepair.worldtool.job.JobStore;
import dev.yu.worldrepair.worldtool.nbt.Nbt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldRepairServiceTest {
    @TempDir
    Path temporary;

    @Test
    void zeroByteEntityRegionIsReportedAsEmptyAndDoesNotAbortScan() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("empty-region-world"));
        Path entities = world.resolve("entities");
        Files.createDirectories(entities);
        Files.createFile(entities.resolve("r.9.9.mca"));
        Path jar = trustedJar("empty-region-trusted.jar");
        WorldRepairService service = new WorldRepairService(IoUtil.sha256(jar));

        WorldRepairService.CommandResult scan =
                service.scan(world, temporary.resolve("empty-region-jobs"), jar);
        assertEquals(0, ((Number) scan.metrics().get("targets")).intValue());
        assertEquals(1, ((Number) scan.metrics().get("emptyRegionsSkipped")).intValue());
    }

    @Test
    void fullApplyVerifyRollbackIsExact() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("world-copy"));
        UUID chickenId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID passengerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        Nbt.CompoundTag passenger = WorldToolFixture.entity(
                "minecraft:chicken",
                passengerId,
                true,
                false,
                List.of()
        );
        Nbt.CompoundTag chicken = WorldToolFixture.entity(
                "minecraft:chicken",
                chickenId,
                true,
                true,
                List.of(passenger)
        );
        Path region = WorldToolFixture.writeEntityRegion(
                world,
                "entities",
                -1,
                34,
                2,
                List.of(chicken)
        );
        WorldToolFixture.addEntityChunk(
                region,
                -2,
                35,
                2,
                List.of(WorldToolFixture.entity(
                        "minecraft:pig",
                        UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff"),
                        false,
                        false,
                        List.of()
                ))
        );
        String unaffectedSemanticHash = Nbt.semanticSha256(
                WorldToolFixture.readChunkRoot(region, -2, 35)
        );
        String originalRegionHash = IoUtil.sha256(region);
        String originalLevelHash = IoUtil.sha256(world.resolve("level.dat"));
        Map<String, String> worldBeforeScan = worldHashes(world);
        Path jar = temporary.resolve("iceandfire-test.jar");
        Files.write(jar, new byte[]{1, 2, 3, 4});
        String approvedTestHash = IoUtil.sha256(jar);
        WorldRepairService service = new WorldRepairService(approvedTestHash);

        WorldRepairService.CommandResult scan =
                service.scan(world, temporary.resolve("jobs"), jar);
        assertTrue(scan.success());
        assertEquals(2.0, ((Number) scan.metrics().get("targets")).doubleValue());
        assertEquals(originalRegionHash, IoUtil.sha256(region));
        assertEquals(originalLevelHash, IoUtil.sha256(world.resolve("level.dat")));
        assertEquals(worldBeforeScan, worldHashes(world));

        JobStore store = JobStore.open(Path.of(scan.job()));
        List<LegacyChickenDataAdapter.Target> targets = store.readTargets();
        assertEquals(2, targets.size());
        assertEquals("minecraft:overworld", targets.getFirst().dimension());
        assertEquals(-1, targets.getFirst().chunkX());
        assertEquals(34, targets.getFirst().chunkZ());
        assertTrue(targets.stream().allMatch(LegacyChickenDataAdapter.Target::addressable));
        assertTrue(targets.stream().anyMatch(target -> target.entityUuid().equals(chickenId.toString())));
        assertTrue(targets.stream().anyMatch(target -> target.entityUuid().equals(passengerId.toString())));

        WorldRepairService.CommandResult prepare = service.prepare(Path.of(scan.job()));
        assertNotNull(prepare.confirmationToken());
        Path backup = store.backupPath("entities/r.-1.1.mca");
        assertEquals(originalRegionHash, IoUtil.sha256(backup));

        service.apply(Path.of(scan.job()), prepare.confirmationToken());
        assertFalse(originalRegionHash.equals(IoUtil.sha256(region)));
        Nbt.CompoundTag repaired = WorldToolFixture.readChunkRoot(region, -1, 34);
        Nbt.CompoundTag repairedChicken = WorldToolFixture.entityList(repaired).getFirst();
        Nbt.CompoundTag remainingAttachments =
                repairedChicken.getCompound(LegacyChickenDataAdapter.ATTACHMENTS_KEY);
        assertNotNull(remainingAttachments);
        assertFalse(remainingAttachments.contains(LegacyChickenDataAdapter.TARGET_KEY));
        assertTrue(remainingAttachments.contains("iceandfire:misc_data"));
        Nbt.CompoundTag repairedPassenger =
                (Nbt.CompoundTag) repairedChicken.getList("Passengers").get(0);
        assertFalse(repairedPassenger.contains(LegacyChickenDataAdapter.ATTACHMENTS_KEY));
        assertEquals(
                unaffectedSemanticHash,
                Nbt.semanticSha256(WorldToolFixture.readChunkRoot(region, -2, 35))
        );

        WorldRepairService.CommandResult verify = service.verify(Path.of(scan.job()));
        assertNotNull(verify.confirmationToken());
        assertEquals(0, ((Number) verify.metrics().get("remainingTargets")).intValue());

        service.rollback(Path.of(scan.job()), verify.confirmationToken());
        service.verifyRollback(Path.of(scan.job()));
        assertEquals(originalRegionHash, IoUtil.sha256(region));
        assertEquals(originalLevelHash, IoUtil.sha256(world.resolve("level.dat")));
        assertTrue(Files.isRegularFile(Path.of(scan.job()).resolve("tool.log")));
    }

    @Test
    void externalChunkSidecarIsAppliedVerifiedAndRolledBackExactly() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("external-apply-world"));
        UUID chickenId = UUID.fromString("77777777-2222-3333-4444-555555555555");
        Path region = WorldToolFixture.writeExternalEntityRegion(
                world,
                "DIM1/entities",
                40,
                -5,
                2,
                List.of(WorldToolFixture.entity(
                        "minecraft:chicken",
                        chickenId,
                        true,
                        true,
                        List.of()
                ))
        );
        Path sidecar = region.resolveSibling("c.40.-5.mcc");
        String originalRegionHash = IoUtil.sha256(region);
        String originalSidecarHash = IoUtil.sha256(sidecar);
        Path jar = trustedJar("external-apply-trusted.jar");
        WorldRepairService service = new WorldRepairService(IoUtil.sha256(jar));

        WorldRepairService.CommandResult scan =
                service.scan(world, temporary.resolve("external-apply-jobs"), jar);
        JobStore store = JobStore.open(Path.of(scan.job()));
        LegacyChickenDataAdapter.Target target = store.readTargets().getFirst();
        assertTrue(target.addressable());
        assertTrue(target.externalChunk());
        assertEquals("DIM1/entities/c.40.-5.mcc", store.readSources().getFirst().relativePath());

        WorldRepairService.CommandResult prepare = service.prepare(Path.of(scan.job()));
        assertEquals(
                originalSidecarHash,
                IoUtil.sha256(store.backupPath("DIM1/entities/c.40.-5.mcc"))
        );
        service.apply(Path.of(scan.job()), prepare.confirmationToken());
        assertEquals(originalRegionHash, IoUtil.sha256(region));
        assertFalse(originalSidecarHash.equals(IoUtil.sha256(sidecar)));
        Nbt.CompoundTag repaired = WorldToolFixture.entityList(
                WorldToolFixture.readChunkRoot(region, 40, -5)
        ).getFirst();
        Nbt.CompoundTag attachments =
                repaired.getCompound(LegacyChickenDataAdapter.ATTACHMENTS_KEY);
        assertNotNull(attachments);
        assertFalse(attachments.contains(LegacyChickenDataAdapter.TARGET_KEY));
        assertTrue(attachments.contains("iceandfire:misc_data"));

        WorldRepairService.CommandResult verify = service.verify(Path.of(scan.job()));
        service.rollback(Path.of(scan.job()), verify.confirmationToken());
        service.verifyRollback(Path.of(scan.job()));
        assertEquals(originalRegionHash, IoUtil.sha256(region));
        assertEquals(originalSidecarHash, IoUtil.sha256(sidecar));
    }

    @Test
    void unknownJarAndUnexpectedHolderRemainReadOnly() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("blocked-world"));
        Nbt.CompoundTag entity = WorldToolFixture.entity(
                "minecraft:cow",
                UUID.randomUUID(),
                true,
                false,
                List.of()
        );
        WorldToolFixture.writeEntityRegion(world, "entities", 0, 0, 2, List.of(entity));
        Path jar = temporary.resolve("unknown.jar");
        Files.write(jar, new byte[]{9, 8, 7});

        WorldRepairService service = new WorldRepairService("not-the-test-hash");
        WorldRepairService.CommandResult scan =
                service.scan(world, temporary.resolve("blocked-jobs"), jar);
        JobStore store = JobStore.open(Path.of(scan.job()));
        LegacyChickenDataAdapter.Target target = store.readTargets().getFirst();
        assertFalse(target.addressable());
        assertEquals("unverified_iceandfire_jar", target.refusalReason());
        assertThrows(IOException.class, () -> service.prepare(Path.of(scan.job())));
    }

    @Test
    void malformedUuidBlocksApplyEvenWithTrustedJar() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("uuid-world"));
        Nbt.CompoundTag entity = WorldToolFixture.entity(
                "minecraft:chicken",
                null,
                true,
                false,
                List.of()
        );
        WorldToolFixture.writeEntityRegion(world, "entities", 0, 0, 1, List.of(entity));
        Path jar = temporary.resolve("trusted.jar");
        Files.write(jar, new byte[]{5, 5, 5});
        String hash = IoUtil.sha256(jar);
        WorldRepairService service = new WorldRepairService(hash);
        WorldRepairService.CommandResult scan =
                service.scan(world, temporary.resolve("uuid-jobs"), jar);

        LegacyChickenDataAdapter.Target target =
                JobStore.open(Path.of(scan.job())).readTargets().getFirst();
        assertFalse(target.addressable());
        assertEquals("missing_or_malformed_uuid", target.refusalReason());
        assertThrows(IOException.class, () -> service.prepare(Path.of(scan.job())));
    }

    @Test
    void trustedUnexpectedEntityBlocksJobWhileExternalChunkRemainsAddressable() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("external-world"));
        WorldToolFixture.writeEntityRegion(
                world,
                "entities",
                0,
                0,
                2,
                List.of(WorldToolFixture.entity(
                        "minecraft:cow",
                        UUID.randomUUID(),
                        true,
                        false,
                        List.of()
                ))
        );
        WorldToolFixture.writeExternalEntityRegion(
                world,
                "DIM1/entities",
                40,
                -5,
                2,
                List.of(WorldToolFixture.entity(
                        "minecraft:chicken",
                        UUID.randomUUID(),
                        true,
                        false,
                        List.of()
                ))
        );
        Path jar = trustedJar("external-trusted.jar");
        WorldRepairService service = new WorldRepairService(IoUtil.sha256(jar));

        WorldRepairService.CommandResult scan =
                service.scan(world, temporary.resolve("external-jobs"), jar);
        List<LegacyChickenDataAdapter.Target> targets =
                JobStore.open(Path.of(scan.job())).readTargets();
        assertEquals(2, targets.size());
        assertTrue(targets.stream().anyMatch(target ->
                "unexpected_entity_type".equals(target.refusalReason())));
        assertTrue(targets.stream().anyMatch(target ->
                target.externalChunk()
                        && target.addressable()
                        && target.refusalReason() == null));
        assertThrows(IOException.class, () -> service.prepare(Path.of(scan.job())));
    }

    @Test
    void duplicateUuidAcrossCustomDimensionIsBlocked() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("duplicate-world"));
        UUID duplicate = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        WorldToolFixture.writeEntityRegion(
                world,
                "entities",
                1,
                1,
                2,
                List.of(WorldToolFixture.entity(
                        "minecraft:chicken",
                        duplicate,
                        true,
                        false,
                        List.of()
                ))
        );
        WorldToolFixture.writeEntityRegion(
                world,
                "dimensions/example/sky/entities",
                -33,
                64,
                2,
                List.of(WorldToolFixture.entity(
                        "minecraft:chicken",
                        duplicate,
                        true,
                        false,
                        List.of()
                ))
        );
        Path jar = trustedJar("duplicate-trusted.jar");
        WorldRepairService service = new WorldRepairService(IoUtil.sha256(jar));
        WorldRepairService.CommandResult scan =
                service.scan(world, temporary.resolve("duplicate-jobs"), jar);
        List<LegacyChickenDataAdapter.Target> targets =
                JobStore.open(Path.of(scan.job())).readTargets();

        assertEquals(2, targets.size());
        assertTrue(targets.stream().allMatch(target ->
                !target.addressable() && "duplicate_uuid".equals(target.refusalReason())));
        assertTrue(targets.stream().anyMatch(target -> target.dimension().equals("example:sky")));
        assertThrows(IOException.class, () -> service.prepare(Path.of(scan.job())));
    }

    @Test
    void prepareRefusesWorldChangesAfterReadOnlyScan() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("changed-world"));
        Path region = WorldToolFixture.writeEntityRegion(
                world,
                "entities",
                0,
                0,
                2,
                List.of(WorldToolFixture.entity(
                        "minecraft:chicken",
                        UUID.randomUUID(),
                        true,
                        false,
                        List.of()
                ))
        );
        Path jar = trustedJar("changed-trusted.jar");
        WorldRepairService service = new WorldRepairService(IoUtil.sha256(jar));
        WorldRepairService.CommandResult scan =
                service.scan(world, temporary.resolve("changed-jobs"), jar);
        Files.write(region, new byte[]{1}, StandardOpenOption.APPEND);

        assertThrows(IOException.class, () -> service.prepare(Path.of(scan.job())));
    }

    @Test
    void heldSessionLockBlocksEvenReadOnlyScan() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("locked-world"));
        Path lockPath = world.resolve("session.lock");
        Path jar = trustedJar("locked-trusted.jar");
        WorldRepairService service = new WorldRepairService(IoUtil.sha256(jar));
        try (FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        ); FileLock lock = channel.lock()) {
            assertTrue(lock.isValid());
            assertThrows(
                    IOException.class,
                    () -> service.scan(world, temporary.resolve("locked-jobs"), jar)
            );
        }
    }

    @Test
    void confirmationTokenRejectsWrongValueAndReplay() throws Exception {
        Path world = singleTargetWorld("token-world");
        Path jar = trustedJar("token-trusted.jar");
        WorldRepairService service = new WorldRepairService(IoUtil.sha256(jar));
        WorldRepairService.CommandResult scan =
                service.scan(world, temporary.resolve("token-jobs"), jar);
        WorldRepairService.CommandResult prepare = service.prepare(Path.of(scan.job()));

        assertThrows(
                IOException.class,
                () -> service.apply(Path.of(scan.job()), "00000000000000000000000000000000")
        );
        service.apply(Path.of(scan.job()), prepare.confirmationToken());
        assertThrows(
                IOException.class,
                () -> service.apply(Path.of(scan.job()), prepare.confirmationToken())
        );
    }

    @Test
    void rollbackRefusesToOverwritePostRepairChanges() throws Exception {
        Path world = singleTargetWorld("newer-world");
        Path region = world.resolve("entities/r.0.0.mca");
        Path jar = trustedJar("newer-trusted.jar");
        WorldRepairService service = new WorldRepairService(IoUtil.sha256(jar));
        WorldRepairService.CommandResult scan =
                service.scan(world, temporary.resolve("newer-jobs"), jar);
        WorldRepairService.CommandResult prepare = service.prepare(Path.of(scan.job()));
        service.apply(Path.of(scan.job()), prepare.confirmationToken());
        WorldRepairService.CommandResult verify = service.verify(Path.of(scan.job()));
        Files.write(region, new byte[]{77}, StandardOpenOption.APPEND);
        String newerHash = IoUtil.sha256(region);

        assertThrows(
                IOException.class,
                () -> service.rollback(Path.of(scan.job()), verify.confirmationToken())
        );
        assertEquals(newerHash, IoUtil.sha256(region));
    }

    @Test
    void interruptedMultiRegionApplyIsClassifiedAndRolledBack() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("recovery-world"));
        WorldToolFixture.writeEntityRegion(
                world,
                "entities",
                0,
                0,
                2,
                List.of(WorldToolFixture.entity(
                        "minecraft:chicken",
                        UUID.randomUUID(),
                        true,
                        false,
                        List.of()
                ))
        );
        WorldToolFixture.writeEntityRegion(
                world,
                "DIM1/entities",
                64,
                64,
                2,
                List.of(WorldToolFixture.entity(
                        "minecraft:chicken",
                        UUID.randomUUID(),
                        true,
                        false,
                        List.of()
                ))
        );
        Map<String, String> originalHashes = worldHashes(world);
        Path jar = trustedJar("recovery-trusted.jar");
        WorldRepairService service = new WorldRepairService(IoUtil.sha256(jar));
        WorldRepairService.CommandResult scan =
                service.scan(world, temporary.resolve("recovery-jobs"), jar);
        Path job = Path.of(scan.job());
        JobStore store = JobStore.open(job);
        WorldRepairService.CommandResult prepare = service.prepare(job);
        service.apply(job, prepare.confirmationToken());

        // Model a crash after one of two atomic replacements: one file is already restored to
        // pre-hash while the other is still at the journaled post-hash, and state remained APPLYING.
        Path first = world.resolve("entities/r.0.0.mca");
        Files.copy(
                store.backupPath("entities/r.0.0.mca"),
                first,
                StandardCopyOption.REPLACE_EXISTING
        );
        JobManifest applied = store.readManifest();
        store.writeManifest(applied.withState(
                JobState.APPLYING,
                Instant.now().toString(),
                "simulated_interruption"
        ));

        WorldRepairService.CommandResult recovery = service.verify(job);
        assertEquals("verify-recovery", recovery.action());
        assertNotNull(recovery.confirmationToken());
        service.rollback(job, recovery.confirmationToken());
        service.verifyRollback(job);
        assertEquals(originalHashes, worldHashes(world));
    }

    @Test
    void cleanWorldProducesEmptyReadOnlyJobAndCannotPrepare() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("clean-world"));
        Map<String, String> before = worldHashes(world);
        Path jar = trustedJar("clean-trusted.jar");
        WorldRepairService service = new WorldRepairService(IoUtil.sha256(jar));
        WorldRepairService.CommandResult scan =
                service.scan(world, temporary.resolve("clean-jobs"), jar);

        assertEquals(0, ((Number) scan.metrics().get("targets")).intValue());
        assertEquals(before, worldHashes(world));
        assertThrows(IOException.class, () -> service.prepare(Path.of(scan.job())));
    }

    @Test
    void exactUnlockedServerWorldSupportsVerifiedRepairAndFreshRollbackAuthorization()
            throws Exception {
        Path world = singleTargetWorld("server-world");
        Files.delete(world.resolve(
                dev.yu.worldrepair.worldtool.io.WorldAccessPolicy.COPY_MARKER
        ));
        Path jobs = temporary.resolve("server-maintenance-jobs");
        Path jar = trustedJar("server-maintenance-iceandfire.jar");
        WorldRepairService service = WorldRepairService.forServerMaintenance(
                world,
                jobs,
                IoUtil.sha256(jar)
        );
        Path region = world.resolve("entities/r.0.0.mca");
        String original = IoUtil.sha256(region);

        WorldRepairService.CommandResult scan = service.scan(world, jobs, jar);
        WorldRepairService.CommandResult prepare = service.prepare(Path.of(scan.job()));
        service.apply(Path.of(scan.job()), prepare.confirmationToken());
        service.verify(Path.of(scan.job()));
        assertFalse(original.equals(IoUtil.sha256(region)));

        WorldRepairService.CommandResult rollback = service.authorizeRollback(
                Path.of(scan.job())
        );
        service.rollback(Path.of(scan.job()), rollback.confirmationToken());
        service.verifyRollback(Path.of(scan.job()));
        assertEquals(original, IoUtil.sha256(region));
    }

    private Path singleTargetWorld(String name) throws IOException {
        Path world = WorldToolFixture.createWorld(temporary.resolve(name));
        WorldToolFixture.writeEntityRegion(
                world,
                "entities",
                0,
                0,
                2,
                List.of(WorldToolFixture.entity(
                        "minecraft:chicken",
                        UUID.randomUUID(),
                        true,
                        false,
                        List.of()
                ))
        );
        return world;
    }

    private Path trustedJar(String name) throws IOException {
        Path jar = temporary.resolve(name);
        Files.write(jar, name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return jar;
    }

    private static Map<String, String> worldHashes(Path world) throws IOException {
        TreeMap<String, String> result = new TreeMap<>();
        try (var paths = Files.walk(world)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                result.put(world.relativize(path).toString().replace('\\', '/'), IoUtil.sha256(path));
            }
        }
        return Map.copyOf(result);
    }
}
