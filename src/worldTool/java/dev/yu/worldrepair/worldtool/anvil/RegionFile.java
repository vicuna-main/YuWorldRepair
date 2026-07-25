package dev.yu.worldrepair.worldtool.anvil;

import dev.yu.worldrepair.worldtool.nbt.Nbt;
import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.DeflaterOutputStream;

/**
 * Strict streaming reader and copy-on-write rewriter for Anvil region files.
 */
public final class RegionFile {
    public static final int SECTOR_BYTES = 4_096;
    public static final int HEADER_BYTES = 8_192;
    public static final int CHUNK_SLOTS = 1_024;
    public static final long MAX_REGION_BYTES = 2L * 1_024 * 1_024 * 1_024;
    public static final long MAX_EXTERNAL_CHUNK_BYTES = 64L * 1_024 * 1_024;

    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private RegionFile() {
    }

    public record RegionCoordinates(int x, int z) {
    }

    public record Chunk(
            int index,
            int chunkX,
            int chunkZ,
            int timestamp,
            int compression,
            boolean external,
            Nbt.Root root
    ) {
    }

    @FunctionalInterface
    public interface ChunkVisitor {
        void visit(Chunk chunk) throws IOException;
    }

    @FunctionalInterface
    public interface ChunkEditor {
        EditResult edit(Chunk chunk) throws IOException;
    }

    public record EditResult(boolean modified, int removed, String postSemanticSha256) {
    }

    public static RegionCoordinates coordinates(Path regionPath) throws IOException {
        Matcher matcher = REGION_NAME.matcher(regionPath.getFileName().toString());
        if (!matcher.matches()) {
            throw new IOException("Invalid region filename: " + regionPath.getFileName());
        }
        try {
            return new RegionCoordinates(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        } catch (NumberFormatException invalid) {
            throw new IOException("Region coordinate is out of range", invalid);
        }
    }

    public static void visitChunks(Path regionPath, Nbt.Limits limits, ChunkVisitor visitor) throws IOException {
        RegionCoordinates region = coordinates(regionPath);
        try (FileChannel channel = FileChannel.open(regionPath, StandardOpenOption.READ)) {
            Header header = Header.read(channel);
            for (int index = 0; index < CHUNK_SLOTS; index++) {
                if (header.locations[index] == 0) {
                    continue;
                }
                visitor.visit(readChunk(channel, regionPath, region, header, index, limits));
            }
        }
    }

    public static Chunk readChunk(Path regionPath, int index, Nbt.Limits limits) throws IOException {
        if (index < 0 || index >= CHUNK_SLOTS) {
            throw new IOException("Chunk index out of range");
        }
        RegionCoordinates region = coordinates(regionPath);
        try (FileChannel channel = FileChannel.open(regionPath, StandardOpenOption.READ)) {
            Header header = Header.read(channel);
            if (header.locations[index] == 0) {
                throw new IOException("Chunk slot is empty");
            }
            return readChunk(channel, regionPath, region, header, index, limits);
        }
    }

    public static Path externalSidecarPath(Path regionPath, int index) throws IOException {
        if (index < 0 || index >= CHUNK_SLOTS) {
            throw new IOException("Chunk index out of range");
        }
        RegionCoordinates region = coordinates(regionPath);
        int chunkX = region.x() * 32 + (index & 31);
        int chunkZ = region.z() * 32 + (index >>> 5);
        return regionPath.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
    }

    public static Map<Integer, EditResult> rewrite(
            Path source,
            Path temporary,
            Map<Integer, ChunkEditor> editors,
            Nbt.Limits limits
    ) throws IOException {
        if (editors.isEmpty()) {
            throw new IOException("No chunk editors supplied");
        }
        RegionCoordinates region = coordinates(source);
        java.util.LinkedHashMap<Integer, EditResult> results = new java.util.LinkedHashMap<>();
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel output = FileChannel.open(
                     temporary,
                     StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.READ,
                     StandardOpenOption.WRITE
             )) {
            Header header = Header.read(input);
            writeFully(output, ByteBuffer.allocate(HEADER_BYTES), 0);
            int nextSector = 2;
            int[] rewrittenLocations = new int[CHUNK_SLOTS];

            for (int index = 0; index < CHUNK_SLOTS; index++) {
                int location = header.locations[index];
                if (location == 0) {
                    continue;
                }
                int oldOffset = location >>> 8;
                int oldSectors = location & 0xFF;
                ChunkEditor editor = editors.get(index);
                byte[] allocated;
                if (editor == null) {
                    allocated = readAllocatedSectors(input, oldOffset, oldSectors);
                } else {
                    Chunk chunk = readChunk(input, source, region, header, index, limits);
                    if (chunk.external()) {
                        throw new IOException("Target chunk uses external .mcc storage; refusing apply");
                    }
                    EditResult result = editor.edit(chunk);
                    if (!result.modified() || result.removed() < 1) {
                        throw new IOException("Target chunk edit did not remove the expected attachment");
                    }
                    results.put(index, result);
                    byte[] rawNbt = Nbt.writeRootToBytes(chunk.root());
                    byte[] compressed = compress(rawNbt, chunk.compression());
                    int recordLength = compressed.length + 1;
                    int sectors = Math.toIntExact((recordLength + 4L + SECTOR_BYTES - 1) / SECTOR_BYTES);
                    if (sectors < 1 || sectors > 255) {
                        throw new IOException("Edited chunk does not fit internal Anvil storage");
                    }
                    allocated = new byte[sectors * SECTOR_BYTES];
                    ByteBuffer record = ByteBuffer.wrap(allocated);
                    record.putInt(recordLength);
                    record.put((byte) chunk.compression());
                    record.put(compressed);
                }

                int newSectors = allocated.length / SECTOR_BYTES;
                if (nextSector > 0xFF_FFFF || newSectors > 255) {
                    throw new IOException("Rewritten region exceeds Anvil location limits");
                }
                rewrittenLocations[index] = (nextSector << 8) | newSectors;
                writeFully(output, ByteBuffer.wrap(allocated), (long) nextSector * SECTOR_BYTES);
                nextSector += newSectors;
            }

            ByteBuffer newHeader = ByteBuffer.allocate(HEADER_BYTES);
            for (int location : rewrittenLocations) {
                newHeader.putInt(location);
            }
            for (int timestamp : header.timestamps) {
                newHeader.putInt(timestamp);
            }
            newHeader.flip();
            writeFully(output, newHeader, 0);
            output.truncate((long) nextSector * SECTOR_BYTES);
            output.force(true);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }

        if (results.size() != editors.size()) {
            Files.deleteIfExists(temporary);
            throw new IOException("Not every requested chunk was present in the region");
        }
        // Re-open every edited chunk from the completed temporary file before replacement.
        try (FileChannel verification = FileChannel.open(temporary, StandardOpenOption.READ)) {
            Header verificationHeader = Header.read(verification);
            for (Map.Entry<Integer, EditResult> entry : results.entrySet()) {
                Chunk reread = readChunk(
                        verification,
                        temporary,
                        region,
                        verificationHeader,
                        entry.getKey(),
                        limits
                );
                String actual = Nbt.semanticSha256(reread.root().tag());
                if (!actual.equals(entry.getValue().postSemanticSha256())) {
                    Files.deleteIfExists(temporary);
                    throw new IOException("Edited chunk failed semantic reread verification");
                }
            }
        }
        return Map.copyOf(results);
    }

    /**
     * Rewrites one external chunk sidecar without changing its region-file marker.
     * The caller must hash, journal, and atomically replace {@code expectedSidecar}.
     */
    public static EditResult rewriteExternalChunk(
            Path regionPath,
            int index,
            Path expectedSidecar,
            Path temporary,
            ChunkEditor editor,
            Nbt.Limits limits
    ) throws IOException {
        Path actualSidecar = externalSidecarPath(regionPath, index).toAbsolutePath().normalize();
        if (!actualSidecar.equals(expectedSidecar.toAbsolutePath().normalize())) {
            throw new IOException("External chunk sidecar path does not match its region slot");
        }
        Chunk chunk = readChunk(regionPath, index, limits);
        if (!chunk.external()) {
            throw new IOException("Requested chunk is no longer externally stored");
        }
        EditResult result = editor.edit(chunk);
        if (!result.modified() || result.removed() < 1) {
            throw new IOException("Target external chunk edit did not remove the expected attachment");
        }

        byte[] rawNbt = Nbt.writeRootToBytes(chunk.root());
        byte[] compressed = compress(rawNbt, chunk.compression());
        if (compressed.length < 1 || compressed.length > MAX_EXTERNAL_CHUNK_BYTES) {
            throw new IOException("Edited external chunk size is outside hard limits");
        }
        try (FileChannel output = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        )) {
            writeFully(output, ByteBuffer.wrap(compressed), 0);
            output.force(true);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }

        try {
            Nbt.Root reread = readCompressedRoot(temporary, chunk.compression(), limits);
            String actual = Nbt.semanticSha256(reread.tag());
            if (!actual.equals(result.postSemanticSha256())) {
                throw new IOException("Edited external chunk failed semantic reread verification");
            }
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
        return result;
    }

    private static Chunk readChunk(
            FileChannel channel,
            Path regionPath,
            RegionCoordinates region,
            Header header,
            int index,
            Nbt.Limits limits
    ) throws IOException {
        int location = header.locations[index];
        int sectorOffset = location >>> 8;
        int sectorCount = location & 0xFF;
        long position = (long) sectorOffset * SECTOR_BYTES;
        ByteBuffer prefix = ByteBuffer.allocate(5);
        readFully(channel, prefix, position);
        prefix.flip();
        int length = prefix.getInt();
        int compressionByte = Byte.toUnsignedInt(prefix.get());
        boolean external = (compressionByte & 0x80) != 0;
        int compression = compressionByte & 0x7F;
        validateCompression(compression);
        if (length < 1 || length > (long) sectorCount * SECTOR_BYTES - 4) {
            throw new IOException("Invalid chunk length in " + regionPath + " slot " + index);
        }
        if (external && length != 1) {
            throw new IOException("External chunk marker has invalid length in slot " + index);
        }

        byte[] compressed;
        if (external) {
            Path sidecar = externalSidecarPath(regionPath, index);
            WorldAccessPolicy.rejectLinkChain(sidecar);
            if (!Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("External chunk sidecar is missing or not regular: " + sidecar);
            }
            long size = Files.size(sidecar);
            if (size < 1 || size > MAX_EXTERNAL_CHUNK_BYTES) {
                throw new IOException("External chunk size is outside hard limits: " + sidecar);
            }
            compressed = Files.readAllBytes(sidecar);
        } else {
            compressed = new byte[length - 1];
            readFully(channel, ByteBuffer.wrap(compressed), position + 5);
        }

        Nbt.Root root = readCompressedRoot(compressed, compression, limits);
        int chunkX = region.x() * 32 + (index & 31);
        int chunkZ = region.z() * 32 + (index >>> 5);
        return new Chunk(index, chunkX, chunkZ, header.timestamps[index], compression, external, root);
    }

    private static Nbt.Root readCompressedRoot(
            Path compressedPath,
            int compression,
            Nbt.Limits limits
    ) throws IOException {
        long size = Files.size(compressedPath);
        if (size < 1 || size > MAX_EXTERNAL_CHUNK_BYTES) {
            throw new IOException("External chunk size is outside hard limits: " + compressedPath);
        }
        return readCompressedRoot(Files.readAllBytes(compressedPath), compression, limits);
    }

    private static Nbt.Root readCompressedRoot(
            byte[] compressed,
            int compression,
            Nbt.Limits limits
    ) throws IOException {
        try (InputStream decoded = decompress(compressed, compression)) {
            Nbt.Root root = Nbt.readRoot(decoded, limits);
            if (decoded.read() != -1) {
                throw new IOException("Trailing decoded bytes after NBT root");
            }
            return root;
        }
    }

    private static InputStream decompress(byte[] compressed, int compression) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(compressed);
        return switch (compression) {
            case 1 -> new GZIPInputStream(input, 8_192);
            case 2 -> new InflaterInputStream(input);
            case 3 -> input;
            default -> throw new IOException("Unsupported Anvil compression type " + compression);
        };
    }

    private static byte[] compress(byte[] raw, int compression) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(raw.length, 1_048_576));
        switch (compression) {
            case 1 -> {
                try (OutputStream compressed = new GZIPOutputStream(output, 8_192)) {
                    compressed.write(raw);
                }
            }
            case 2 -> {
                try (OutputStream compressed = new DeflaterOutputStream(output)) {
                    compressed.write(raw);
                }
            }
            case 3 -> output.write(raw);
            default -> throw new IOException("Unsupported Anvil compression type " + compression);
        }
        return output.toByteArray();
    }

    private static byte[] readAllocatedSectors(FileChannel channel, int offset, int sectors) throws IOException {
        byte[] bytes = new byte[Math.multiplyExact(sectors, SECTOR_BYTES)];
        readFully(channel, ByteBuffer.wrap(bytes), (long) offset * SECTOR_BYTES);
        return bytes;
    }

    private static void validateCompression(int compression) throws IOException {
        if (compression < 1 || compression > 3) {
            throw new IOException("Unsupported Anvil compression type " + compression);
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                throw new IOException("Unexpected end of region file");
            }
            if (read == 0) {
                throw new IOException("Region read made no progress");
            }
            position += read;
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer, position);
            if (written <= 0) {
                throw new IOException("Region write made no progress");
            }
            position += written;
        }
    }

    private static final class Header {
        private final int[] locations;
        private final int[] timestamps;

        private Header(int[] locations, int[] timestamps) {
            this.locations = locations;
            this.timestamps = timestamps;
        }

        private static Header read(FileChannel channel) throws IOException {
            long size = channel.size();
            if (size < HEADER_BYTES || size > MAX_REGION_BYTES) {
                throw new IOException("Region file size is outside hard limits: " + size);
            }
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            readFully(channel, header, 0);
            header.flip();
            int[] locations = new int[CHUNK_SLOTS];
            int[] timestamps = new int[CHUNK_SLOTS];
            BitSet usedSectors = new BitSet();
            usedSectors.set(0, 2);
            long availableSectors = (size + SECTOR_BYTES - 1) / SECTOR_BYTES;
            for (int index = 0; index < CHUNK_SLOTS; index++) {
                int location = header.getInt();
                locations[index] = location;
                if (location == 0) {
                    continue;
                }
                int offset = location >>> 8;
                int count = location & 0xFF;
                if (offset < 2 || count < 1 || (long) offset + count > availableSectors) {
                    throw new IOException("Invalid Anvil location entry at slot " + index);
                }
                int overlap = usedSectors.nextSetBit(offset);
                if (overlap >= offset && overlap < offset + count) {
                    throw new IOException("Overlapping Anvil sectors at slot " + index);
                }
                usedSectors.set(offset, offset + count);
            }
            for (int index = 0; index < CHUNK_SLOTS; index++) {
                timestamps[index] = header.getInt();
            }
            return new Header(locations, timestamps);
        }
    }
}
