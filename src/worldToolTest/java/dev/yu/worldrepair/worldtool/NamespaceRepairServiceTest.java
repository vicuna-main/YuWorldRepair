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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                4,
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
    void prepareBacksUpWithoutReplacingAndApplyPreparedPerformsTheMutation()
            throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("staged-world"));
        Path region = WorldToolFixture.writeEntityRegion(
                world,
                "entities",
                0,
                0,
                2,
                List.of(WorldToolFixture.entity(
                        "oldmod:beast",
                        UUID.fromString("50000000-0000-0000-0000-000000000005"),
                        false,
                        false,
                        List.of()
                ))
        );
        String original = IoUtil.sha256(region);
        NamespacePolicy policy = new NamespacePolicy(
                "oldmod",
                NamespacePolicy.Mode.ORPHANED_ONLY,
                snapshot()
        );
        NamespaceRepairService service = NamespaceRepairService.forServerMaintenance(
                world.toAbsolutePath(),
                temporary.resolve("staged-jobs").toAbsolutePath()
        );

        NamespaceRepairService.Result prepared = service.prepare(policy, "e".repeat(64));
        assertTrue(prepared.success());
        assertFalse(prepared.modified());
        assertEquals(original, IoUtil.sha256(region));

        NamespaceRepairService.Result applied = service.applyPrepared(
                Path.of(prepared.jobPath()),
                policy,
                "e".repeat(64)
        );
        assertTrue(applied.success());
        assertTrue(applied.modified());
        assertFalse(original.equals(IoUtil.sha256(region)));
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
    void globalItemModeRequiresItsInternalSentinelAndProtectsPlatformNamespaces() {
        NamespacePolicy policy = orphanItemPolicy();
        assertTrue(policy.targets(RegistrySnapshot.Category.ITEM, "oldmod:missing"));
        assertFalse(policy.targets(RegistrySnapshot.Category.ITEM, "installed:keep"));
        assertFalse(policy.targets(RegistrySnapshot.Category.ITEM, "minecraft:missing"));
        assertTrue(policy.targets(
                RegistrySnapshot.Category.ATTACHMENT_TYPE,
                "iceandfire:chicken_data"
        ));
        assertFalse(policy.targets(
                RegistrySnapshot.Category.ATTACHMENT_TYPE,
                "iceandfire:misc_data"
        ));
        assertFalse(policy.targets(
                RegistrySnapshot.Category.ATTACHMENT_TYPE,
                "minecraft:missing"
        ));
        assertFalse(policy.targets(RegistrySnapshot.Category.BLOCK, "oldmod:missing"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NamespacePolicy(
                        "oldmod",
                        NamespacePolicy.Mode.ORPHANED_ITEMS,
                        snapshot()
                )
        );
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

    @Test
    void globalOrphanItemsRepairVanillaAe2RefinedStorageAndQioAndRollback()
            throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("orphan-items-world"));
        UUID qioType = UUID.fromString("11111111-2222-3333-4444-555555555555");

        Nbt.CompoundTag player = new Nbt.CompoundTag();
        player.put("DataVersion", new Nbt.IntTag(3_955));
        player.put("Inventory", new Nbt.ListTag(
                Nbt.COMPOUND,
                List.of(
                        item("oldmod:player_item", 5),
                        item("installed:keep", 2)
                )
        ));
        Path playerFile = WorldToolFixture.writeGzipNbt(
                world.resolve("playerdata")
                        .resolve("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.dat"),
                player
        );

        Nbt.CompoundTag ae2Cell = item("ae2:item_storage_cell_1k", 1);
        Nbt.CompoundTag ae2Entry = new Nbt.CompoundTag();
        ae2Entry.put("#t", new Nbt.StringTag("ae2:i"));
        ae2Entry.put("#", new Nbt.LongTag(37));
        ae2Entry.put("id", new Nbt.StringTag("oldmod:ae2_item"));
        Nbt.CompoundTag ae2Components = new Nbt.CompoundTag();
        ae2Components.put(
                "ae2:storage_cell_inv",
                new Nbt.ListTag(Nbt.COMPOUND, List.of(ae2Entry))
        );
        ae2Cell.put("components", ae2Components);
        Nbt.CompoundTag chest = new Nbt.CompoundTag();
        chest.put("id", new Nbt.StringTag("minecraft:chest"));
        chest.put("Items", new Nbt.ListTag(Nbt.COMPOUND, List.of(ae2Cell)));

        Nbt.CompoundTag drive = item("mekanism:qio_drive_base", 1);
        Nbt.CompoundTag driveComponents = new Nbt.CompoundTag();
        driveComponents.put("mekanism:drive_contents", new Nbt.LongArrayTag(new long[]{
                qioType.getMostSignificantBits(),
                qioType.getLeastSignificantBits(),
                99
        }));
        Nbt.CompoundTag driveMetadata = new Nbt.CompoundTag();
        driveMetadata.put("count", new Nbt.LongTag(99));
        driveMetadata.put("types", new Nbt.IntTag(1));
        driveComponents.put("mekanism:drive_metadata", driveMetadata);
        drive.put("components", driveComponents);
        Nbt.CompoundTag driveWrapper = new Nbt.CompoundTag();
        driveWrapper.put("item", drive);
        Nbt.CompoundTag driveArray = new Nbt.CompoundTag();
        driveArray.put("id", new Nbt.StringTag("mekanism:qio_drive_array"));
        driveArray.put("items", new Nbt.ListTag(Nbt.COMPOUND, List.of(driveWrapper)));

        Nbt.CompoundTag chunk = emptyChunk();
        chunk.put(
                "block_entities",
                new Nbt.ListTag(Nbt.COMPOUND, List.of(chest, driveArray))
        );
        Nbt.CompoundTag attachmentInventory = new Nbt.CompoundTag();
        attachmentInventory.put(
                "Items",
                new Nbt.ListTag(
                        Nbt.COMPOUND,
                        List.of(item("oldmod:chunk_attachment_item", 7))
                )
        );
        Nbt.CompoundTag chunkAttachments = new Nbt.CompoundTag();
        chunkAttachments.put("installed:inventory", attachmentInventory);
        chunk.put("neoforge:attachments", chunkAttachments);
        Path region = WorldToolFixture.writeChunkRegion(
                world,
                "region",
                0,
                0,
                2,
                chunk
        );

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
        Nbt.CompoundTag rsResource = new Nbt.CompoundTag();
        rsResource.put("item", new Nbt.StringTag("oldmod:rs_item"));
        Nbt.CompoundTag rsWrapper = new Nbt.CompoundTag();
        rsWrapper.put("resource", rsResource);
        rsWrapper.put("amount", new Nbt.LongTag(123));
        rsStorage.put(
                "resources",
                new Nbt.ListTag(Nbt.COMPOUND, List.of(rsWrapper))
        );
        rsData.put("66666666-7777-8888-9999-aaaaaaaaaaaa", rsStorage);
        rsRoot.put("data", rsData);
        Path rsFile = WorldToolFixture.writeGzipNbt(
                world.resolve("data").resolve("refinedstorage_storages.dat"),
                rsRoot
        );

        List<Path> sources = List.of(playerFile, region, qioFile, rsFile);
        List<String> before = sources.stream().map(path -> {
            try {
                return IoUtil.sha256(path);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }).toList();
        NamespaceRepairService service = NamespaceRepairService.forServerMaintenance(
                world.toAbsolutePath(),
                temporary.resolve("orphan-item-jobs").toAbsolutePath()
        );
        NamespaceRepairService.Result result = service.repair(
                orphanItemPolicy(),
                "1".repeat(64)
        );

        assertTrue(result.success());
        assertEquals(6, ((Number) result.metrics().get("changed")).intValue());
        Nbt.CompoundTag repairedPlayer = (Nbt.CompoundTag)
                NbtFile.readGzip(playerFile, Nbt.Limits.conservative()).tag();
        assertEquals(1, repairedPlayer.getList("Inventory").size());
        assertEquals(
                "installed:keep",
                ((Nbt.CompoundTag) repairedPlayer.getList("Inventory").get(0))
                        .getString("id")
        );
        Nbt.CompoundTag repairedChunk = WorldToolFixture.readChunkRoot(region, 0, 0);
        assertEquals(
                0,
                repairedChunk.getCompound("neoforge:attachments")
                        .getCompound("installed:inventory")
                        .getList("Items").size()
        );
        Nbt.ListTag repairedBlockEntities = repairedChunk.getList("block_entities");
        Nbt.CompoundTag repairedChest = (Nbt.CompoundTag) repairedBlockEntities.get(0);
        Nbt.CompoundTag repairedCell =
                (Nbt.CompoundTag) repairedChest.getList("Items").get(0);
        assertEquals(
                0,
                repairedCell.getCompound("components")
                        .getList("ae2:storage_cell_inv")
                        .size()
        );
        Nbt.CompoundTag repairedDriveArray =
                (Nbt.CompoundTag) repairedBlockEntities.get(1);
        Nbt.CompoundTag repairedDrive = ((Nbt.CompoundTag)
                repairedDriveArray.getList("items").get(0)).getCompound("item");
        Nbt.CompoundTag repairedDriveComponents = repairedDrive.getCompound("components");
        assertEquals(
                0,
                ((Nbt.LongArrayTag) repairedDriveComponents
                        .get("mekanism:drive_contents")).value().length
        );
        assertEquals(
                0,
                ((Nbt.LongTag) repairedDriveComponents
                        .getCompound("mekanism:drive_metadata")
                        .get("count")).value()
        );
        assertEquals(
                0,
                ((Nbt.IntTag) repairedDriveComponents
                        .getCompound("mekanism:drive_metadata")
                        .get("types")).value()
        );
        assertTrue(((Nbt.CompoundTag)
                NbtFile.readGzip(qioFile, Nbt.Limits.conservative()).tag())
                .getCompound("data").getCompound("items").isEmpty());
        Nbt.CompoundTag repairedRs = (Nbt.CompoundTag)
                NbtFile.readGzip(rsFile, Nbt.Limits.conservative()).tag();
        assertEquals(
                0,
                repairedRs.getCompound("data")
                        .getCompound("66666666-7777-8888-9999-aaaaaaaaaaaa")
                        .getList("resources").size()
        );

        service.rollback(Path.of(result.jobPath()));
        for (int index = 0; index < sources.size(); index++) {
            assertEquals(before.get(index), IoUtil.sha256(sources.get(index)));
        }
    }

    @Test
    void globalOrphanItemsRemovesNestedOrphanAttachmentsAtomicallyAndRollsBack()
            throws Exception {
        Path world = WorldToolFixture.createWorld(
                temporary.resolve("nested-orphan-attachments-world")
        );

        Nbt.CompoundTag chickenData = new Nbt.CompoundTag();
        chickenData.put("timeUntilNextEgg", new Nbt.IntTag(1_234));
        Nbt.CompoundTag miscData = new Nbt.CompoundTag();
        miscData.put("persistent", new Nbt.ByteTag((byte) 1));
        Nbt.CompoundTag hornAttachments = new Nbt.CompoundTag();
        hornAttachments.put("iceandfire:chicken_data", chickenData);
        hornAttachments.put("iceandfire:misc_data", miscData);
        Nbt.CompoundTag hornEntityData = new Nbt.CompoundTag();
        hornEntityData.put("neoforge:attachments", hornAttachments);
        Nbt.CompoundTag hornComponent = new Nbt.CompoundTag();
        hornComponent.put("entityData", hornEntityData);
        Nbt.CompoundTag hornComponents = new Nbt.CompoundTag();
        hornComponents.put("iceandfire:dragon_horn", hornComponent);
        Nbt.CompoundTag horn = item("iceandfire:dragon_horn", 1);
        horn.put("components", hornComponents);

        Nbt.CompoundTag removedMaidAttachment = new Nbt.CompoundTag();
        removedMaidAttachment.put("payload", item("oldmod:hidden_item", 64));
        Nbt.CompoundTag maidAttachments = new Nbt.CompoundTag();
        maidAttachments.put("oldmod:legacy_maid_data", removedMaidAttachment);
        Nbt.CompoundTag maidInfo = new Nbt.CompoundTag();
        maidInfo.put("neoforge:attachments", maidAttachments);
        Nbt.CompoundTag maidComponents = new Nbt.CompoundTag();
        maidComponents.put("touhou_little_maid:maid_info", maidInfo);
        Nbt.CompoundTag garageKit = item("touhou_little_maid:garage_kit", 1);
        garageKit.put("components", maidComponents);

        Nbt.CompoundTag extraMaidAttachments = new Nbt.CompoundTag();
        extraMaidAttachments.put("iceandfire:chicken_data", chickenData.deepCopy());
        Nbt.CompoundTag extraMaidData = new Nbt.CompoundTag();
        extraMaidData.put("neoforge:attachments", extraMaidAttachments);
        Nbt.CompoundTag neoForgeData = new Nbt.CompoundTag();
        neoForgeData.put("ExtraMaidData", extraMaidData);

        Nbt.CompoundTag chest = new Nbt.CompoundTag();
        chest.put("id", new Nbt.StringTag("minecraft:chest"));
        chest.put("Items", new Nbt.ListTag(Nbt.COMPOUND, List.of(horn, garageKit)));
        chest.put("NeoForgeData", neoForgeData);
        Nbt.CompoundTag chunk = emptyChunk();
        chunk.put("block_entities", new Nbt.ListTag(Nbt.COMPOUND, List.of(chest)));
        Path region = WorldToolFixture.writeChunkRegion(world, "region", 0, 0, 2, chunk);
        String before = IoUtil.sha256(region);

        NamespaceRepairService service = NamespaceRepairService.forServerMaintenance(
                world.toAbsolutePath(),
                temporary.resolve("nested-orphan-attachments-jobs").toAbsolutePath()
        );
        NamespaceRepairService.Result result = service.repair(
                orphanItemPolicy(),
                "3".repeat(64)
        );

        assertTrue(result.success());
        assertEquals(3, ((Number) result.metrics().get("changed")).intValue());
        assertEquals(
                3.0,
                ((Number) ((java.util.Map<?, ?>) result.metrics().get("byStore"))
                        .get("NAMESPACE")).doubleValue()
        );
        Nbt.CompoundTag repaired = WorldToolFixture.readChunkRoot(region, 0, 0);
        Nbt.CompoundTag repairedChest =
                (Nbt.CompoundTag) repaired.getList("block_entities").get(0);
        Nbt.ListTag repairedItems = repairedChest.getList("Items");
        Nbt.CompoundTag repairedHorn = (Nbt.CompoundTag) repairedItems.get(0);
        Nbt.CompoundTag repairedHornAttachments = repairedHorn
                .getCompound("components")
                .getCompound("iceandfire:dragon_horn")
                .getCompound("entityData")
                .getCompound("neoforge:attachments");
        assertFalse(repairedHornAttachments.contains("iceandfire:chicken_data"));
        assertTrue(repairedHornAttachments.contains("iceandfire:misc_data"));
        Nbt.CompoundTag repairedKit = (Nbt.CompoundTag) repairedItems.get(1);
        assertFalse(repairedKit
                .getCompound("components")
                .getCompound("touhou_little_maid:maid_info")
                .contains("neoforge:attachments"));
        assertFalse(repairedChest
                .getCompound("NeoForgeData")
                .getCompound("ExtraMaidData")
                .contains("neoforge:attachments"));

        service.rollback(Path.of(result.jobPath()));
        assertEquals(before, IoUtil.sha256(region));
    }

    @Test
    void sharedMainWorldQioIndexFindsDriveInAnotherMultiverseWorld()
            throws Exception {
        Path primary = WorldToolFixture.createWorld(temporary.resolve("primary"));
        Path secondary = WorldToolFixture.createWorld(temporary.resolve("secondary"));
        UUID type = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
        Nbt.CompoundTag qioRoot = new Nbt.CompoundTag();
        Nbt.CompoundTag data = new Nbt.CompoundTag();
        Nbt.CompoundTag items = new Nbt.CompoundTag();
        items.put(type.toString(), item("oldmod:remote_qio_item", 1));
        data.put("items", items);
        qioRoot.put("data", data);
        WorldToolFixture.writeGzipNbt(
                primary.resolve("data").resolve("mekanism_qio_type_cache.dat"),
                qioRoot
        );

        Nbt.CompoundTag drive = item("mekanism:qio_drive_base", 1);
        Nbt.CompoundTag components = new Nbt.CompoundTag();
        components.put("mekanism:drive_contents", new Nbt.LongArrayTag(new long[]{
                type.getMostSignificantBits(),
                type.getLeastSignificantBits(),
                42
        }));
        drive.put("components", components);
        Nbt.CompoundTag wrapper = new Nbt.CompoundTag();
        wrapper.put("item", drive);
        Nbt.CompoundTag holder = new Nbt.CompoundTag();
        holder.put("id", new Nbt.StringTag("mekanism:qio_drive_array"));
        holder.put("items", new Nbt.ListTag(Nbt.COMPOUND, List.of(wrapper)));
        Nbt.CompoundTag chunk = emptyChunk();
        chunk.put("block_entities", new Nbt.ListTag(Nbt.COMPOUND, List.of(holder)));
        WorldToolFixture.writeChunkRegion(secondary, "region", 0, 0, 2, chunk);

        NamespacePolicy policy = orphanItemPolicy();
        dev.yu.worldrepair.worldtool.namespace.OrphanItemIndex shared =
                dev.yu.worldrepair.worldtool.namespace.OrphanItemIndex.load(
                        primary,
                        policy,
                        Nbt.Limits.conservative()
                );
        NamespaceRepairService service = NamespaceRepairService.forServerMaintenance(
                secondary.toAbsolutePath(),
                temporary.resolve("shared-qio-jobs").toAbsolutePath()
        );
        NamespaceRepairService.Result prepared =
                service.prepare(policy, "2".repeat(64), shared);
        assertTrue(prepared.success());
        assertEquals(1, ((Number) prepared.metrics().get("targets")).intValue());
    }

    private static Nbt.CompoundTag idEntry(String id, String key) {
        Nbt.CompoundTag entry = new Nbt.CompoundTag();
        entry.put(key, new Nbt.StringTag(id));
        return entry;
    }

    private static Nbt.CompoundTag item(String id, int count) {
        Nbt.CompoundTag item = new Nbt.CompoundTag();
        item.put("id", new Nbt.StringTag(id));
        item.put("count", new Nbt.IntTag(count));
        return item;
    }

    private static Nbt.CompoundTag emptyChunk() {
        Nbt.CompoundTag root = new Nbt.CompoundTag();
        root.put("DataVersion", new Nbt.IntTag(3_955));
        root.put("xPos", new Nbt.IntTag(0));
        root.put("zPos", new Nbt.IntTag(0));
        root.put("sections", new Nbt.ListTag(Nbt.END, List.of()));
        root.put("block_entities", new Nbt.ListTag(Nbt.END, List.of()));
        root.put("block_ticks", new Nbt.ListTag(Nbt.END, List.of()));
        root.put("fluid_ticks", new Nbt.ListTag(Nbt.END, List.of()));
        return root;
    }

    private static NamespacePolicy orphanItemPolicy() {
        RegistrySnapshot snapshot = new RegistrySnapshot(
                RegistrySnapshot.SCHEMA_VERSION,
                Instant.now().toString(),
                "1.21.1",
                List.of(
                        "ae2:item_storage_cell_1k",
                        "iceandfire:dragon_horn",
                        "installed:keep",
                        "mekanism:qio_drive_base",
                        "touhou_little_maid:garage_kit"
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("iceandfire:misc_data", "installed:inventory"),
                List.of()
        );
        return new NamespacePolicy(
                NamespacePolicy.ALL_ORPHANED_ITEMS,
                NamespacePolicy.Mode.ORPHANED_ITEMS,
                snapshot
        );
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
