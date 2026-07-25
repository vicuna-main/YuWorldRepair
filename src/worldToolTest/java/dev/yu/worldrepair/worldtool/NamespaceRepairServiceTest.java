package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.maintenance.RegistrySnapshot;
import dev.yu.worldrepair.worldtool.namespace.NamespacePolicy;
import dev.yu.worldrepair.worldtool.namespace.NamespaceRepairService;
import dev.yu.worldrepair.worldtool.namespace.NamespaceChunkAdapter;
import dev.yu.worldrepair.worldtool.namespace.NamespaceTarget;
import dev.yu.worldrepair.worldtool.nbt.Nbt;
import dev.yu.worldrepair.worldtool.nbt.NbtFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamespaceRepairServiceTest {
    @TempDir
    Path temporary;

    @Test
    void orphanEntitiesAndAttachmentsRepairAndRollbackByteExactly() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("namespace-entity-world"));
        Nbt.CompoundTag removedEntity = WorldToolFixture.entity(
                "oldmod:beast",
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                false,
                false,
                List.of()
        );
        Nbt.CompoundTag survivor = WorldToolFixture.entity(
                "minecraft:chicken",
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                false,
                false,
                List.of()
        );
        Nbt.CompoundTag attachments = new Nbt.CompoundTag();
        attachments.put("oldmod:player_state", new Nbt.IntTag(7));
        attachments.put("othermod:keep", new Nbt.StringTag("yes"));
        survivor.put("neoforge:attachments", attachments);
        Path region = WorldToolFixture.writeEntityRegion(
                world,
                "entities",
                3,
                4,
                2,
                List.of(removedEntity, survivor)
        );
        String original = IoUtil.sha256(region);
        Nbt.CompoundTag player = new Nbt.CompoundTag();
        player.put("DataVersion", new Nbt.IntTag(3_955));
        Nbt.CompoundTag playerAttachments = new Nbt.CompoundTag();
        playerAttachments.put("oldmod:player_capability", new Nbt.LongTag(44L));
        playerAttachments.put("othermod:keep", new Nbt.ByteTag((byte) 1));
        player.put("neoforge:attachments", playerAttachments);
        Path playerFile = WorldToolFixture.writeGzipNbt(
                world.resolve("playerdata")
                        .resolve("30000000-0000-0000-0000-000000000003.dat"),
                player
        );
        String originalPlayer = IoUtil.sha256(playerFile);
        RegistrySnapshot snapshot = snapshot();
        NamespacePolicy policy = new NamespacePolicy(
                "oldmod",
                NamespacePolicy.Mode.ORPHANED_ONLY,
                snapshot
        );
        Path jobs = temporary.resolve("namespace-jobs").toAbsolutePath();
        NamespaceRepairService service = NamespaceRepairService.forServerMaintenance(
                world.toAbsolutePath(),
                jobs
        );

        NamespaceRepairService.Result result = service.repair(policy, "a".repeat(64));
        assertTrue(result.success());
        assertTrue(result.modified());
        assertTrue(result.rollbackAvailable());
        Nbt.CompoundTag root = WorldToolFixture.readChunkRoot(region, 3, 4);
        List<Nbt.CompoundTag> entities = WorldToolFixture.entityList(root);
        assertEquals(1, entities.size());
        assertEquals("minecraft:chicken", entities.getFirst().getString("id"));
        Nbt.CompoundTag remaining = entities.getFirst().getCompound("neoforge:attachments");
        assertNotNull(remaining);
        assertFalse(remaining.contains("oldmod:player_state"));
        assertTrue(remaining.contains("othermod:keep"));
        Nbt.CompoundTag repairedPlayer = (Nbt.CompoundTag)
                NbtFile.readGzip(playerFile, Nbt.Limits.conservative()).tag();
        Nbt.CompoundTag repairedPlayerAttachments =
                repairedPlayer.getCompound("neoforge:attachments");
        assertNotNull(repairedPlayerAttachments);
        assertFalse(repairedPlayerAttachments.contains("oldmod:player_capability"));
        assertTrue(repairedPlayerAttachments.contains("othermod:keep"));

        NamespaceRepairService.Result rollback =
                service.rollback(Path.of(result.jobPath()));
        assertTrue(rollback.success());
        assertEquals(original, IoUtil.sha256(region));
        assertEquals(originalPlayer, IoUtil.sha256(playerFile));
    }

    @Test
    void blockPaletteBlockEntityTicksAndChunkAttachmentAreRepaired() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("namespace-block-world"));
        Nbt.CompoundTag root = new Nbt.CompoundTag();
        root.put("DataVersion", new Nbt.IntTag(3_955));
        root.put("xPos", new Nbt.IntTag(0));
        root.put("zPos", new Nbt.IntTag(0));

        Nbt.CompoundTag removedState = new Nbt.CompoundTag();
        removedState.put("Name", new Nbt.StringTag("oldmod:machine"));
        Nbt.CompoundTag properties = new Nbt.CompoundTag();
        properties.put("facing", new Nbt.StringTag("north"));
        removedState.put("Properties", properties);
        Nbt.CompoundTag stone = new Nbt.CompoundTag();
        stone.put("Name", new Nbt.StringTag("minecraft:stone"));
        Nbt.CompoundTag blockStates = new Nbt.CompoundTag();
        blockStates.put(
                "palette",
                new Nbt.ListTag(Nbt.COMPOUND, List.of(removedState, stone))
        );
        blockStates.put("data", new Nbt.LongArrayTag(new long[256]));
        Nbt.CompoundTag section = new Nbt.CompoundTag();
        section.put("Y", new Nbt.ByteTag((byte) 0));
        section.put("block_states", blockStates);
        root.put("sections", new Nbt.ListTag(Nbt.COMPOUND, List.of(section)));

        root.put("block_entities", new Nbt.ListTag(
                Nbt.COMPOUND,
                List.of(idEntry("oldmod:machine", "id"))
        ));
        root.put("block_ticks", new Nbt.ListTag(
                Nbt.COMPOUND,
                List.of(idEntry("oldmod:machine", "i"))
        ));
        root.put("fluid_ticks", new Nbt.ListTag(
                Nbt.COMPOUND,
                List.of(idEntry("oldmod:oil", "i"))
        ));
        Nbt.CompoundTag chunkAttachments = new Nbt.CompoundTag();
        chunkAttachments.put("oldmod:chunk_state", new Nbt.LongTag(9L));
        root.put("neoforge:attachments", chunkAttachments);
        Path region = WorldToolFixture.writeChunkRegion(
                world,
                "region",
                0,
                0,
                2,
                root
        );

        NamespaceRepairService service = NamespaceRepairService.forServerMaintenance(
                world.toAbsolutePath(),
                temporary.resolve("block-jobs").toAbsolutePath()
        );
        NamespaceRepairService.Result result = service.repair(
                new NamespacePolicy(
                        "oldmod",
                        NamespacePolicy.Mode.ORPHANED_ONLY,
                        snapshot()
                ),
                "b".repeat(64)
        );
        assertTrue(result.success());
        assertEquals(5, ((Number) result.metrics().get("changed")).intValue());
        Nbt.CompoundTag repaired = WorldToolFixture.readChunkRoot(region, 0, 0);
        Nbt.CompoundTag repairedState = (Nbt.CompoundTag) ((Nbt.CompoundTag)
                repaired.getList("sections").get(0))
                .getCompound("block_states")
                .getList("palette")
                .get(0);
        assertEquals("minecraft:air", repairedState.getString("Name"));
        assertFalse(repairedState.contains("Properties"));
        assertEquals(0, repaired.getList("block_entities").size());
        assertEquals(0, repaired.getList("block_ticks").size());
        assertEquals(0, repaired.getList("fluid_ticks").size());
        assertFalse(repaired.contains("neoforge:attachments"));
    }

    @Test
    void presentRegistryIdIsNotRemovedInOrphanedOnlyMode() throws Exception {
        RegistrySnapshot snapshot = new RegistrySnapshot(
                RegistrySnapshot.SCHEMA_VERSION,
                Instant.now().toString(),
                "1.21.1",
                List.of("minecraft:air"),
                List.of("minecraft:air", "oldmod:machine"),
                List.of("minecraft:empty"),
                List.of("minecraft:chicken"),
                List.of("minecraft:furnace"),
                List.of(),
                List.of("minecraft:overworld")
        );
        NamespacePolicy policy = new NamespacePolicy(
                "oldmod",
                NamespacePolicy.Mode.ORPHANED_ONLY,
                snapshot
        );
        assertFalse(policy.targets(RegistrySnapshot.Category.BLOCK, "oldmod:machine"));
        assertTrue(new NamespacePolicy(
                "oldmod",
                NamespacePolicy.Mode.PREPARE_REMOVE,
                snapshot
        ).targets(RegistrySnapshot.Category.BLOCK, "oldmod:machine"));
    }

    @Test
    void emptyEndTypedMinecraftListsAreAccepted() throws Exception {
        Nbt.CompoundTag root = new Nbt.CompoundTag();
        root.put("DataVersion", new Nbt.IntTag(3_955));
        root.put("sections", new Nbt.ListTag(Nbt.END, List.of()));
        root.put("block_entities", new Nbt.ListTag(Nbt.END, List.of()));
        root.put("block_ticks", new Nbt.ListTag(Nbt.END, List.of()));
        root.put("fluid_ticks", new Nbt.ListTag(Nbt.END, List.of()));
        NamespaceChunkAdapter adapter = new NamespaceChunkAdapter();

        assertTrue(adapter.scan(
                root,
                new NamespaceChunkAdapter.Context(
                        "minecraft:overworld",
                        "region/r.0.0.mca",
                        0,
                        0,
                        0,
                        false,
                        NamespaceTarget.RegionKind.CHUNK
                ),
                new NamespacePolicy(
                        "oldmod",
                        NamespacePolicy.Mode.ORPHANED_ONLY,
                        snapshot()
                )
        ).isEmpty());
    }

    @Test
    void externalNamespaceChunkKeepsRegionMarkerAndRollsBackSidecarExactly() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("namespace-external-world"));
        Path region = WorldToolFixture.writeExternalEntityRegion(
                world,
                "DIM1/entities",
                45,
                8,
                2,
                List.of(WorldToolFixture.entity(
                        "oldmod:giant",
                        UUID.fromString("40000000-0000-0000-0000-000000000004"),
                        false,
                        false,
                        List.of()
                ))
        );
        Path sidecar = region.resolveSibling("c.45.8.mcc");
        String regionBefore = IoUtil.sha256(region);
        String sidecarBefore = IoUtil.sha256(sidecar);
        NamespaceRepairService service = NamespaceRepairService.forServerMaintenance(
                world.toAbsolutePath(),
                temporary.resolve("external-namespace-jobs").toAbsolutePath()
        );

        NamespaceRepairService.Result result = service.repair(
                new NamespacePolicy(
                        "oldmod",
                        NamespacePolicy.Mode.ORPHANED_ONLY,
                        snapshot()
                ),
                "c".repeat(64)
        );
        assertTrue(result.success());
        assertEquals(regionBefore, IoUtil.sha256(region));
        assertFalse(sidecarBefore.equals(IoUtil.sha256(sidecar)));
        assertEquals(0, WorldToolFixture.entityList(
                WorldToolFixture.readChunkRoot(region, 45, 8)
        ).size());

        service.rollback(Path.of(result.jobPath()));
        assertEquals(regionBefore, IoUtil.sha256(region));
        assertEquals(sidecarBefore, IoUtil.sha256(sidecar));
    }

    @Test
    void unsupportedDataVersionCreatesCoverageGapAndRefusesEveryWrite() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("namespace-gap-world"));
        Nbt.CompoundTag root = new Nbt.CompoundTag();
        root.put("DataVersion", new Nbt.IntTag(9_999));
        root.put("sections", new Nbt.ListTag(Nbt.COMPOUND, List.of()));
        Path region = WorldToolFixture.writeChunkRegion(
                world,
                "region",
                0,
                0,
                2,
                root
        );
        String before = IoUtil.sha256(region);
        NamespaceRepairService service = NamespaceRepairService.forServerMaintenance(
                world.toAbsolutePath(),
                temporary.resolve("gap-jobs").toAbsolutePath()
        );

        NamespaceRepairService.Result result = service.repair(
                new NamespacePolicy(
                        "oldmod",
                        NamespacePolicy.Mode.ORPHANED_ONLY,
                        snapshot()
                ),
                "d".repeat(64)
        );
        assertFalse(result.success());
        assertFalse(result.modified());
        assertFalse(result.rollbackAvailable());
        assertEquals(1, ((Number) result.metrics().get("coverageGaps")).intValue());
        assertEquals(before, IoUtil.sha256(region));
    }

    private static Nbt.CompoundTag idEntry(String id, String key) {
        Nbt.CompoundTag entry = new Nbt.CompoundTag();
        entry.put(key, new Nbt.StringTag(id));
        return entry;
    }

    private static RegistrySnapshot snapshot() {
        return new RegistrySnapshot(
                RegistrySnapshot.SCHEMA_VERSION,
                Instant.now().toString(),
                "1.21.1",
                List.of("minecraft:air"),
                List.of("minecraft:air", "minecraft:stone"),
                List.of("minecraft:empty", "minecraft:water"),
                List.of("minecraft:chicken"),
                List.of("minecraft:furnace"),
                List.of("othermod:keep"),
                List.of("minecraft:overworld")
        );
    }
}
