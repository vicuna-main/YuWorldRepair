package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.maintenance.RegistrySnapshot;
import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;
import dev.yu.worldrepair.worldtool.namespace.NamespaceChunkAdapter;
import dev.yu.worldrepair.worldtool.namespace.NamespacePolicy;
import dev.yu.worldrepair.worldtool.namespace.NamespaceRepairService;
import dev.yu.worldrepair.worldtool.namespace.NamespaceTarget;
import dev.yu.worldrepair.worldtool.namespace.NamespaceWorldScanner;
import dev.yu.worldrepair.worldtool.namespace.OrphanItemIndex;
import dev.yu.worldrepair.worldtool.nbt.Nbt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamespaceRegionScopeTest {
    @TempDir
    Path temporary;

    @Test
    void excludedRegionsStillScanPlayerAndRsButDeferQioCacheDeletion()
            throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("scope-world"));
        Path region = WorldToolFixture.writeEntityRegion(
                world,
                "entities",
                0,
                0,
                2,
                List.of(WorldToolFixture.entity(
                        "oldmod:region_entity",
                        UUID.randomUUID(),
                        false,
                        false,
                        List.of()
                ))
        );

        Nbt.CompoundTag player = new Nbt.CompoundTag();
        player.put("DataVersion", new Nbt.IntTag(3_955));
        player.put("Inventory", new Nbt.ListTag(
                Nbt.COMPOUND,
                List.of(item("oldmod:player_item", 2))
        ));
        Path playerFile = WorldToolFixture.writeGzipNbt(
                world.resolve("playerdata")
                        .resolve("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.dat"),
                player
        );

        UUID qioType = UUID.fromString("11111111-2222-3333-4444-555555555555");
        Nbt.CompoundTag qioRoot = new Nbt.CompoundTag();
        Nbt.CompoundTag qioData = new Nbt.CompoundTag();
        Nbt.CompoundTag qioItems = new Nbt.CompoundTag();
        qioItems.put(qioType.toString(), item("oldmod:qio_item", 1));
        qioData.put("items", qioItems);
        qioRoot.put("data", qioData);
        Path qioFile = WorldToolFixture.writeGzipNbt(
                world.resolve("data").resolve("mekanism_qio_type_cache.dat"),
                qioRoot
        );

        Nbt.CompoundTag rsRoot = new Nbt.CompoundTag();
        Nbt.CompoundTag rsData = new Nbt.CompoundTag();
        Nbt.CompoundTag rsStorage = new Nbt.CompoundTag();
        rsStorage.put("type", new Nbt.StringTag("refinedstorage:item"));
        Nbt.CompoundTag resource = new Nbt.CompoundTag();
        resource.put("item", new Nbt.StringTag("oldmod:rs_item"));
        Nbt.CompoundTag wrapper = new Nbt.CompoundTag();
        wrapper.put("resource", resource);
        wrapper.put("amount", new Nbt.LongTag(9));
        rsStorage.put("resources", new Nbt.ListTag(Nbt.COMPOUND, List.of(wrapper)));
        rsData.put("66666666-7777-8888-9999-aaaaaaaaaaaa", rsStorage);
        rsRoot.put("data", rsData);
        Path rsFile = WorldToolFixture.writeGzipNbt(
                world.resolve("data").resolve("refinedstorage_storages.dat"),
                rsRoot
        );

        NamespacePolicy policy = orphanItemPolicy();
        OrphanItemIndex index = OrphanItemIndex.load(
                world,
                policy,
                Nbt.Limits.conservative()
        );
        NamespaceWorldScanner scanner = new NamespaceWorldScanner(
                new NamespaceChunkAdapter(),
                Nbt.Limits.conservative()
        );
        NamespaceWorldScanner.Result result = scanner.scan(
                world,
                policy,
                index,
                NamespaceWorldScanner.Options.metadataOnly(4, false)
        );

        assertFalse(result.regionDataIncluded());
        assertEquals(0, result.regionsScanned());
        assertEquals(0, result.chunksScanned());
        assertEquals(2, result.targets().size());
        assertEquals(1, result.deferredTargets());
        assertEquals(1, result.deferredTargetDetails().size());
        assertEquals(
                "oldmod:qio_item",
                result.deferredTargetDetails().getFirst().resourceId()
        );
        assertEquals(
                NamespaceTarget.Store.QIO,
                result.deferredTargetDetails().getFirst().store()
        );
        assertTrue(result.targets().stream().anyMatch(target ->
                target.regionKind() == NamespaceTarget.RegionKind.PLAYER));
        assertTrue(result.targets().stream().anyMatch(target ->
                target.action() == NamespaceTarget.Action.REMOVE_RS_ENTRY));
        assertFalse(result.targets().stream().anyMatch(target ->
                target.action() == NamespaceTarget.Action.REMOVE_QIO_TYPE));
        assertTrue(result.warnings().stream().anyMatch(warning ->
                warning.startsWith(
                        "qio_cache_cleanup_deferred_due_to_region_exclusions:"
                )));

        String regionBefore = IoUtil.sha256(region);
        String playerBefore = IoUtil.sha256(playerFile);
        String qioBefore = IoUtil.sha256(qioFile);
        String rsBefore = IoUtil.sha256(rsFile);
        NamespaceRepairService service = NamespaceRepairService.forServerMaintenance(
                world.toAbsolutePath(),
                temporary.resolve("scope-jobs").toAbsolutePath()
        );
        NamespaceWorldScanner.Options options =
                NamespaceWorldScanner.Options.metadataOnly(4, false)
                        .withTrustedWorldLock(true);
        NamespaceRepairService.Result applied;
        try (WorldAccessPolicy.HeldWorldLocks ignored =
                     WorldAccessPolicy.acquireExactWorldLocks(
                             List.of(world.toString())
                     )) {
            NamespaceRepairService.Result prepared = service.prepare(
                    policy,
                    "5".repeat(64),
                    index,
                    options
            );
            assertTrue(prepared.success());
            assertEquals(2, ((Number) prepared.metrics().get("targets")).intValue());
            assertEquals(
                    1,
                    ((Number) prepared.metrics().get("deferredTargets")).intValue()
            );
            assertEquals(
                    3,
                    ((Number) prepared.metrics().get("detectedTargets")).intValue()
            );
            assertEquals(
                    3,
                    ((Number) ((java.util.Map<?, ?>) prepared.metrics()
                            .get("detectedByNamespace")).get("oldmod")).intValue()
            );
            assertEquals(
                    1,
                    ((Number) ((java.util.Map<?, ?>) prepared.metrics()
                            .get("deferredByNamespace")).get("oldmod")).intValue()
            );
            assertEquals(
                    1,
                    ((Number) ((java.util.Map<?, ?>) prepared.metrics()
                            .get("deferredByStore")).get("QIO")).intValue()
            );
            applied = service.applyPrepared(
                    Path.of(prepared.jobPath()),
                    policy,
                    "5".repeat(64),
                    index,
                    options
            );
        }
        assertTrue(applied.success());
        assertEquals(2, ((Number) applied.metrics().get("changed")).intValue());
        assertEquals(regionBefore, IoUtil.sha256(region));
        assertEquals(qioBefore, IoUtil.sha256(qioFile));
        assertFalse(playerBefore.equals(IoUtil.sha256(playerFile)));
        assertFalse(rsBefore.equals(IoUtil.sha256(rsFile)));

        service.rollback(Path.of(applied.jobPath()));
        assertEquals(regionBefore, IoUtil.sha256(region));
        assertEquals(playerBefore, IoUtil.sha256(playerFile));
        assertEquals(qioBefore, IoUtil.sha256(qioFile));
        assertEquals(rsBefore, IoUtil.sha256(rsFile));
    }

    @Test
    void parallelRegionScanIsDeterministicAndReportsFinalProgress() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("parallel-world"));
        for (int index = 0; index < 64; index++) {
            WorldToolFixture.writeEntityRegion(
                    world,
                    "entities",
                    index * 32,
                    0,
                    2,
                    List.of(WorldToolFixture.entity(
                            "oldmod:entity_" + index,
                            new UUID(7, index + 1L),
                            false,
                            false,
                            List.of()
                    ))
            );
        }
        NamespacePolicy policy = new NamespacePolicy(
                "oldmod",
                NamespacePolicy.Mode.ORPHANED_ONLY,
                namespaceSnapshot()
        );
        NamespaceWorldScanner scanner = new NamespaceWorldScanner(
                new NamespaceChunkAdapter(),
                Nbt.Limits.conservative()
        );
        NamespaceWorldScanner.Result serial = scanner.scan(world, policy);
        NamespaceWorldScanner.Progress[] last = {null};
        NamespaceWorldScanner.Result parallel = scanner.scan(
                world,
                policy,
                OrphanItemIndex.EMPTY,
                NamespaceWorldScanner.Options.full(4, true)
                        .withProgressListener(progress -> last[0] = progress)
        );

        assertEquals(serial.targets(), parallel.targets());
        assertEquals(serial.affectedFiles(), parallel.affectedFiles());
        assertEquals(4, parallel.scanWorkers());
        assertEquals(64, last[0].regionFilesCompleted());
        assertEquals(64, last[0].regionFilesTotal());
        assertEquals(parallel.regionBytesScanned(), last[0].regionBytesCompleted());
    }

    private static Nbt.CompoundTag item(String id, int count) {
        Nbt.CompoundTag item = new Nbt.CompoundTag();
        item.put("id", new Nbt.StringTag(id));
        item.put("count", new Nbt.IntTag(count));
        return item;
    }

    private static NamespacePolicy orphanItemPolicy() {
        RegistrySnapshot snapshot = new RegistrySnapshot(
                RegistrySnapshot.SCHEMA_VERSION,
                Instant.now().toString(),
                "1.21.1",
                List.of("minecraft:air"),
                List.of(),
                List.of(),
                List.of("minecraft:chicken"),
                List.of(),
                List.of(),
                List.of()
        );
        return new NamespacePolicy(
                NamespacePolicy.ALL_ORPHANED_ITEMS,
                NamespacePolicy.Mode.ORPHANED_ITEMS,
                snapshot
        );
    }

    private static RegistrySnapshot namespaceSnapshot() {
        return new RegistrySnapshot(
                RegistrySnapshot.SCHEMA_VERSION,
                Instant.now().toString(),
                "1.21.1",
                List.of("minecraft:air"),
                List.of("minecraft:air"),
                List.of("minecraft:empty"),
                List.of("minecraft:chicken"),
                List.of("minecraft:furnace"),
                List.of(),
                List.of("minecraft:overworld")
        );
    }
}
