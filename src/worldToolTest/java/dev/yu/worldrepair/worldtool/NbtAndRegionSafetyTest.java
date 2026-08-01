package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.anvil.RegionFile;
import dev.yu.worldrepair.worldtool.nbt.Nbt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NbtAndRegionSafetyTest {
    @TempDir
    Path temporary;

    @Test
    void nbtRoundTripPreservesAllSupportedTypes() throws Exception {
        Nbt.CompoundTag compound = new Nbt.CompoundTag();
        compound.put("byte", new Nbt.ByteTag((byte) 1));
        compound.put("short", new Nbt.ShortTag((short) 2));
        compound.put("int", new Nbt.IntTag(3));
        compound.put("long", new Nbt.LongTag(4));
        compound.put("float", new Nbt.FloatTag(5.5F));
        compound.put("double", new Nbt.DoubleTag(6.5));
        compound.put("bytes", new Nbt.ByteArrayTag(new byte[]{7, 8}));
        compound.put("string", new Nbt.StringTag("宇钛"));
        compound.put("list", new Nbt.ListTag(Nbt.INT, List.of(new Nbt.IntTag(9))));
        compound.put("ints", new Nbt.IntArrayTag(new int[]{10, 11}));
        compound.put("longs", new Nbt.LongArrayTag(new long[]{12, 13}));
        Nbt.Root root = new Nbt.Root("root", compound);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Nbt.writeRoot(root, output);
        Nbt.Root reread = Nbt.readRoot(
                new ByteArrayInputStream(output.toByteArray()),
                Nbt.Limits.conservative()
        );
        assertEquals("root", reread.name());
        assertEquals(Nbt.semanticSha256(compound), Nbt.semanticSha256(reread.tag()));
    }

    @Test
    void minecraftModifiedUtf8StringsRoundTripIncludingNullAndSupplementaryCharacters()
            throws Exception {
        String value = "player\u0000\uD83D\uDE80";
        ByteArrayOutputStream minecraftBytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(minecraftBytes)) {
            data.writeByte(Nbt.STRING);
            data.writeUTF("");
            data.writeUTF(value);
        }

        Nbt.Root decoded = Nbt.readRoot(
                new ByteArrayInputStream(minecraftBytes.toByteArray()),
                Nbt.Limits.conservative()
        );
        assertEquals(value, ((Nbt.StringTag) decoded.tag()).value());
        assertArrayEquals(
                minecraftBytes.toByteArray(),
                Nbt.writeRootToBytes(decoded)
        );
    }

    @Test
    void nbtDepthLimitFailsClosed() throws Exception {
        Nbt.CompoundTag root = new Nbt.CompoundTag();
        Nbt.CompoundTag cursor = root;
        for (int depth = 0; depth < 12; depth++) {
            Nbt.CompoundTag child = new Nbt.CompoundTag();
            cursor.put("x", child);
            cursor = child;
        }
        byte[] bytes = Nbt.writeRootToBytes(new Nbt.Root("", root));
        assertThrows(
                IOException.class,
                () -> Nbt.readRoot(new ByteArrayInputStream(bytes), new Nbt.Limits(1_000_000, 4, 100, 100))
        );
    }

    @Test
    void trailingNbtBytesAreRejectedByRegionReader() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("trailing-world"));
        Path region = WorldToolFixture.writeEntityRegion(
                world,
                "entities",
                0,
                0,
                3,
                List.of()
        );
        byte[] bytes = Files.readAllBytes(region);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int length = buffer.getInt(2 * RegionFile.SECTOR_BYTES);
        int trailingOffset = 2 * RegionFile.SECTOR_BYTES + 4 + length;
        bytes[trailingOffset] = 99;
        buffer.putInt(2 * RegionFile.SECTOR_BYTES, length + 1);
        Files.write(region, bytes);
        assertThrows(
                IOException.class,
                () -> RegionFile.readChunk(region, 0, Nbt.Limits.conservative())
        );
    }

    @Test
    void allInternalCompressionTypesAreReadable() throws Exception {
        for (int compression = 1; compression <= 4; compression++) {
            Path world = WorldToolFixture.createWorld(temporary.resolve("world-" + compression));
            Path region = WorldToolFixture.writeEntityRegion(
                    world,
                    "entities",
                    compression,
                    -compression,
                    compression,
                    List.of(WorldToolFixture.entity(
                            "minecraft:chicken",
                            UUID.randomUUID(),
                            true,
                            false,
                            List.of()
                    ))
            );
            int index = Math.floorMod(compression, 32) + Math.floorMod(-compression, 32) * 32;
            RegionFile.Chunk chunk = RegionFile.readChunk(region, index, Nbt.Limits.conservative());
            assertEquals(compression, chunk.compression());
        }
    }

    @Test
    void rewritePadsMissingFinalSectorTailAfterValidChunkPayload() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("short-sector-world"));
        Path region = WorldToolFixture.writeEntityRegion(
                world,
                "entities",
                0,
                0,
                2,
                List.of(WorldToolFixture.entity(
                        "minecraft:chicken",
                        UUID.randomUUID(),
                        false,
                        false,
                        List.of()
                ))
        );
        WorldToolFixture.addEntityChunk(
                region,
                1,
                0,
                2,
                List.of(WorldToolFixture.entity(
                        "minecraft:cow",
                        UUID.randomUUID(),
                        false,
                        false,
                        List.of()
                ))
        );
        String untouchedSemanticHash = Nbt.semanticSha256(
                RegionFile.readChunk(region, 1, Nbt.Limits.conservative()).root().tag()
        );
        truncateToDeclaredChunkEnd(region, 1, 0);
        assertTrue(Files.size(region) % RegionFile.SECTOR_BYTES != 0);

        Path rewrittenDirectory = temporary.resolve("short-sector-rewritten");
        Files.createDirectories(rewrittenDirectory);
        Path rewritten = rewrittenDirectory.resolve("r.0.0.mca");
        Map<Integer, RegionFile.EditResult> edits = RegionFile.rewrite(
                region,
                rewritten,
                Map.of(0, chunk -> {
                    Nbt.CompoundTag root = (Nbt.CompoundTag) chunk.root().tag();
                    root.put("yuworldrepair:compatibility_probe", new Nbt.IntTag(1));
                    return new RegionFile.EditResult(
                            true,
                            1,
                            Nbt.semanticSha256(root)
                    );
                }),
                Nbt.Limits.conservative()
        );

        assertEquals(1, edits.size());
        assertEquals(0, Files.size(rewritten) % RegionFile.SECTOR_BYTES);
        assertEquals(
                1,
                ((Nbt.IntTag) ((Nbt.CompoundTag) RegionFile.readChunk(
                                rewritten,
                                0,
                                Nbt.Limits.conservative()
                        ).root().tag())
                        .get("yuworldrepair:compatibility_probe")).value()
        );
        assertEquals(
                untouchedSemanticHash,
                Nbt.semanticSha256(
                        RegionFile.readChunk(
                                rewritten,
                                1,
                                Nbt.Limits.conservative()
                        ).root().tag()
                )
        );
    }

    @Test
    void rewriteStillRejectsChunkWithTruncatedDeclaredPayload() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("truncated-payload-world"));
        Path region = WorldToolFixture.writeEntityRegion(
                world,
                "entities",
                0,
                0,
                2,
                List.of()
        );
        WorldToolFixture.addEntityChunk(region, 1, 0, 2, List.of());
        truncateToDeclaredChunkEnd(region, 1, -1);
        Path rewrittenDirectory = temporary.resolve("truncated-payload-rewritten");
        Files.createDirectories(rewrittenDirectory);
        Path rewritten = rewrittenDirectory.resolve("r.0.0.mca");

        assertThrows(
                IOException.class,
                () -> RegionFile.rewrite(
                        region,
                        rewritten,
                        Map.of(0, chunk -> {
                            Nbt.CompoundTag root = (Nbt.CompoundTag) chunk.root().tag();
                            root.put("yuworldrepair:compatibility_probe", new Nbt.IntTag(1));
                            return new RegionFile.EditResult(
                                    true,
                                    1,
                                    Nbt.semanticSha256(root)
                            );
                        }),
                        Nbt.Limits.conservative()
                )
        );
        assertTrue(Files.notExists(rewritten));
    }

    @Test
    void overlappingRegionSectorsAreRejected() throws Exception {
        Path region = temporary.resolve("r.0.0.mca");
        byte[] bytes = new byte[3 * RegionFile.SECTOR_BYTES];
        ByteBuffer header = ByteBuffer.wrap(bytes);
        header.putInt(0, (2 << 8) | 1);
        header.putInt(4, (2 << 8) | 1);
        Files.write(region, bytes);
        assertThrows(
                IOException.class,
                () -> RegionFile.visitChunks(region, Nbt.Limits.conservative(), chunk -> {
                })
        );
    }

    @Test
    void truncatedAndUnsupportedCompressionAreRejected() throws Exception {
        Path truncated = temporary.resolve("r.1.1.mca");
        Files.write(truncated, new byte[RegionFile.HEADER_BYTES - 1]);
        assertThrows(
                IOException.class,
                () -> RegionFile.visitChunks(truncated, Nbt.Limits.conservative(), chunk -> {
                })
        );

        Path world = WorldToolFixture.createWorld(temporary.resolve("compression-world"));
        Path invalid = WorldToolFixture.writeEntityRegion(
                world,
                "entities",
                0,
                0,
                3,
                List.of()
        );
        byte[] bytes = Files.readAllBytes(invalid);
        bytes[2 * RegionFile.SECTOR_BYTES + 4] = 5;
        Files.write(invalid, bytes);
        IOException failure = assertThrows(
                IOException.class,
                () -> RegionFile.readChunk(invalid, 0, Nbt.Limits.conservative())
        );
        assertTrue(failure.getMessage().contains("compression"));
    }

    private static void truncateToDeclaredChunkEnd(
            Path region,
            int chunkX,
            int delta
    ) throws IOException {
        byte[] headerAndRecords = Files.readAllBytes(region);
        ByteBuffer bytes = ByteBuffer.wrap(headerAndRecords);
        int location = bytes.getInt(chunkX * 4);
        int sectorOffset = location >>> 8;
        int length = bytes.getInt(sectorOffset * RegionFile.SECTOR_BYTES);
        long declaredEnd = (long) sectorOffset * RegionFile.SECTOR_BYTES + 4L + length;
        try (FileChannel channel = FileChannel.open(region, StandardOpenOption.WRITE)) {
            channel.truncate(declaredEnd + delta);
        }
    }
}
