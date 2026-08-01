package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.adapter.LegacyChickenDataAdapter;
import dev.yu.worldrepair.worldtool.anvil.RegionFile;
import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;
import dev.yu.worldrepair.worldtool.nbt.Nbt;
import net.jpountz.lz4.LZ4BlockOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

final class WorldToolFixture {
    private WorldToolFixture() {
    }

    static Path createWorld(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve(WorldAccessPolicy.COPY_MARKER),
                WorldAccessPolicy.COPY_MARKER_CONTENT + "\n"
        );
        Files.write(directory.resolve("level.dat"), new byte[]{10, 0, 0, 0});
        return directory;
    }

    static Nbt.CompoundTag entity(
            String type,
            UUID uuid,
            boolean target,
            boolean sibling,
            List<Nbt.CompoundTag> passengers
    ) {
        Nbt.CompoundTag entity = new Nbt.CompoundTag();
        entity.put("id", new Nbt.StringTag(type));
        if (uuid != null) {
            entity.put("UUID", uuidTag(uuid));
        }
        entity.put("Health", new Nbt.FloatTag(4.0F));
        Nbt.CompoundTag attachments = new Nbt.CompoundTag();
        if (target) {
            Nbt.CompoundTag legacy = new Nbt.CompoundTag();
            legacy.put("ticksUntilNextEgg", new Nbt.IntTag(42));
            attachments.put(LegacyChickenDataAdapter.TARGET_KEY, legacy);
        }
        if (sibling) {
            Nbt.CompoundTag misc = new Nbt.CompoundTag();
            misc.put("frozen", new Nbt.ByteTag((byte) 1));
            attachments.put("iceandfire:misc_data", misc);
        }
        if (!attachments.isEmpty()) {
            entity.put(LegacyChickenDataAdapter.ATTACHMENTS_KEY, attachments);
        }
        if (!passengers.isEmpty()) {
            entity.put("Passengers", new Nbt.ListTag(Nbt.COMPOUND, passengers));
        }
        return entity;
    }

    static Path writeEntityRegion(
            Path world,
            String relativeDirectory,
            int chunkX,
            int chunkZ,
            int compression,
            List<Nbt.CompoundTag> entities
    ) throws IOException {
        int regionX = Math.floorDiv(chunkX, 32);
        int regionZ = Math.floorDiv(chunkZ, 32);
        int localX = Math.floorMod(chunkX, 32);
        int localZ = Math.floorMod(chunkZ, 32);
        int index = localX + localZ * 32;
        Path directory = world.resolve(relativeDirectory);
        Files.createDirectories(directory);
        Path path = directory.resolve("r." + regionX + "." + regionZ + ".mca");

        Nbt.CompoundTag root = new Nbt.CompoundTag();
        root.put("Position", new Nbt.IntArrayTag(new int[]{chunkX, chunkZ}));
        root.put("DataVersion", new Nbt.IntTag(3955));
        root.put("Entities", new Nbt.ListTag(Nbt.COMPOUND, entities));
        byte[] raw = Nbt.writeRootToBytes(new Nbt.Root("", root));
        byte[] compressed = compress(raw, compression);
        int recordLength = compressed.length + 1;
        int sectors = Math.toIntExact(
                (recordLength + 4L + RegionFile.SECTOR_BYTES - 1) / RegionFile.SECTOR_BYTES
        );
        byte[] file = new byte[(2 + sectors) * RegionFile.SECTOR_BYTES];
        ByteBuffer bytes = ByteBuffer.wrap(file);
        bytes.putInt(index * 4, (2 << 8) | sectors);
        bytes.putInt(RegionFile.SECTOR_BYTES + index * 4, 1_700_000_000);
        bytes.position(2 * RegionFile.SECTOR_BYTES);
        bytes.putInt(recordLength);
        bytes.put((byte) compression);
        bytes.put(compressed);
        Files.write(path, file);
        return path;
    }

    static Path writeExternalEntityRegion(
            Path world,
            String relativeDirectory,
            int chunkX,
            int chunkZ,
            int compression,
            List<Nbt.CompoundTag> entities
    ) throws IOException {
        int regionX = Math.floorDiv(chunkX, 32);
        int regionZ = Math.floorDiv(chunkZ, 32);
        int localX = Math.floorMod(chunkX, 32);
        int localZ = Math.floorMod(chunkZ, 32);
        int index = localX + localZ * 32;
        Path directory = world.resolve(relativeDirectory);
        Files.createDirectories(directory);
        Path path = directory.resolve("r." + regionX + "." + regionZ + ".mca");

        Nbt.CompoundTag root = new Nbt.CompoundTag();
        root.put("Position", new Nbt.IntArrayTag(new int[]{chunkX, chunkZ}));
        root.put("DataVersion", new Nbt.IntTag(3955));
        root.put("Entities", new Nbt.ListTag(Nbt.COMPOUND, entities));
        byte[] raw = Nbt.writeRootToBytes(new Nbt.Root("", root));
        byte[] compressed = compress(raw, compression);

        byte[] file = new byte[3 * RegionFile.SECTOR_BYTES];
        ByteBuffer bytes = ByteBuffer.wrap(file);
        bytes.putInt(index * 4, (2 << 8) | 1);
        bytes.putInt(RegionFile.SECTOR_BYTES + index * 4, 1_700_000_000);
        bytes.position(2 * RegionFile.SECTOR_BYTES);
        bytes.putInt(1);
        bytes.put((byte) (0x80 | compression));
        Files.write(path, file);
        Files.write(directory.resolve("c." + chunkX + "." + chunkZ + ".mcc"), compressed);
        return path;
    }

    static Path writeChunkRegion(
            Path world,
            String relativeDirectory,
            int chunkX,
            int chunkZ,
            int compression,
            Nbt.CompoundTag root
    ) throws IOException {
        int regionX = Math.floorDiv(chunkX, 32);
        int regionZ = Math.floorDiv(chunkZ, 32);
        int localX = Math.floorMod(chunkX, 32);
        int localZ = Math.floorMod(chunkZ, 32);
        int index = localX + localZ * 32;
        Path directory = world.resolve(relativeDirectory);
        Files.createDirectories(directory);
        Path path = directory.resolve("r." + regionX + "." + regionZ + ".mca");
        byte[] raw = Nbt.writeRootToBytes(new Nbt.Root("", root));
        byte[] compressed = compress(raw, compression);
        int recordLength = compressed.length + 1;
        int sectors = Math.toIntExact(
                (recordLength + 4L + RegionFile.SECTOR_BYTES - 1)
                        / RegionFile.SECTOR_BYTES
        );
        byte[] file = new byte[(2 + sectors) * RegionFile.SECTOR_BYTES];
        ByteBuffer bytes = ByteBuffer.wrap(file);
        bytes.putInt(index * 4, (2 << 8) | sectors);
        bytes.putInt(RegionFile.SECTOR_BYTES + index * 4, 1_700_000_000);
        bytes.position(2 * RegionFile.SECTOR_BYTES);
        bytes.putInt(recordLength);
        bytes.put((byte) compression);
        bytes.put(compressed);
        Files.write(path, file);
        return path;
    }

    static void addEntityChunk(
            Path region,
            int chunkX,
            int chunkZ,
            int compression,
            List<Nbt.CompoundTag> entities
    ) throws IOException {
        RegionFile.RegionCoordinates coordinates = RegionFile.coordinates(region);
        if (Math.floorDiv(chunkX, 32) != coordinates.x()
                || Math.floorDiv(chunkZ, 32) != coordinates.z()) {
            throw new IOException("Test chunk does not belong to region");
        }
        int index = Math.floorMod(chunkX, 32) + Math.floorMod(chunkZ, 32) * 32;
        byte[] existing = Files.readAllBytes(region);
        ByteBuffer old = ByteBuffer.wrap(existing);
        if (old.getInt(index * 4) != 0 || existing.length % RegionFile.SECTOR_BYTES != 0) {
            throw new IOException("Test region slot is occupied or malformed");
        }

        Nbt.CompoundTag root = new Nbt.CompoundTag();
        root.put("Position", new Nbt.IntArrayTag(new int[]{chunkX, chunkZ}));
        root.put("DataVersion", new Nbt.IntTag(3955));
        root.put("Entities", new Nbt.ListTag(Nbt.COMPOUND, entities));
        byte[] raw = Nbt.writeRootToBytes(new Nbt.Root("", root));
        byte[] compressed = compress(raw, compression);
        int recordLength = compressed.length + 1;
        int sectors = Math.toIntExact(
                (recordLength + 4L + RegionFile.SECTOR_BYTES - 1) / RegionFile.SECTOR_BYTES
        );
        int sectorOffset = existing.length / RegionFile.SECTOR_BYTES;
        byte[] expanded = Arrays.copyOf(
                existing,
                existing.length + sectors * RegionFile.SECTOR_BYTES
        );
        ByteBuffer bytes = ByteBuffer.wrap(expanded);
        bytes.putInt(index * 4, (sectorOffset << 8) | sectors);
        bytes.putInt(RegionFile.SECTOR_BYTES + index * 4, 1_700_000_001);
        bytes.position(sectorOffset * RegionFile.SECTOR_BYTES);
        bytes.putInt(recordLength);
        bytes.put((byte) compression);
        bytes.put(compressed);
        Files.write(region, expanded);
    }

    static Nbt.CompoundTag readChunkRoot(Path region, int chunkX, int chunkZ) throws IOException {
        int index = Math.floorMod(chunkX, 32) + Math.floorMod(chunkZ, 32) * 32;
        RegionFile.Chunk chunk = RegionFile.readChunk(region, index, Nbt.Limits.conservative());
        return (Nbt.CompoundTag) chunk.root().tag();
    }

    static Path writeGzipNbt(Path path, Nbt.CompoundTag root) throws IOException {
        Files.createDirectories(path.getParent());
        byte[] raw = Nbt.writeRootToBytes(new Nbt.Root("", root));
        Files.write(path, compress(raw, 1));
        return path;
    }

    static List<Nbt.CompoundTag> entityList(Nbt.CompoundTag root) {
        Nbt.ListTag entities = root.getList("Entities");
        ArrayList<Nbt.CompoundTag> result = new ArrayList<>();
        for (Nbt.Tag value : entities.values()) {
            result.add((Nbt.CompoundTag) value);
        }
        return result;
    }

    private static Nbt.IntArrayTag uuidTag(UUID uuid) {
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        return new Nbt.IntArrayTag(new int[]{
                (int) (most >>> 32),
                (int) most,
                (int) (least >>> 32),
                (int) least
        });
    }

    private static byte[] compress(byte[] raw, int compression) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        switch (compression) {
            case 1 -> {
                try (OutputStream encoded = new GZIPOutputStream(output)) {
                    encoded.write(raw);
                }
            }
            case 2 -> {
                try (OutputStream encoded = new DeflaterOutputStream(output)) {
                    encoded.write(raw);
                }
            }
            case 3 -> output.write(raw);
            case 4 -> {
                try (OutputStream encoded = new LZ4BlockOutputStream(output)) {
                    encoded.write(raw);
                }
            }
            default -> throw new IOException("Unsupported test compression");
        }
        return output.toByteArray();
    }
}
