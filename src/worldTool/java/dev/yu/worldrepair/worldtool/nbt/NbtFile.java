package dev.yu.worldrepair.worldtool.nbt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Strict copy-on-write reader for Minecraft's standalone GZIP NBT files (for example
 * playerdata/*.dat).
 */
public final class NbtFile {
    public static final long MAX_COMPRESSED_BYTES = 64L * 1_024 * 1_024;

    private NbtFile() {
    }

    @FunctionalInterface
    public interface Editor {
        EditResult edit(Nbt.Root root) throws IOException;
    }

    public record EditResult(boolean modified, int changed, String postSemanticSha256) {
    }

    public static Nbt.Root readGzip(Path path, Nbt.Limits limits) throws IOException {
        long size = Files.size(path);
        if (size < 1 || size > MAX_COMPRESSED_BYTES) {
            throw new IOException("Standalone NBT file size is outside hard limits");
        }
        try (InputStream file = Files.newInputStream(path);
             InputStream decoded = new GZIPInputStream(file, 16 * 1_024)) {
            Nbt.Root root = Nbt.readRoot(decoded, limits);
            if (decoded.read() != -1) {
                throw new IOException("Trailing decoded bytes after standalone NBT root");
            }
            return root;
        }
    }

    public static EditResult rewriteGzip(
            Path source,
            Path temporary,
            Editor editor,
            Nbt.Limits limits
    ) throws IOException {
        Nbt.Root root = readGzip(source, limits);
        EditResult result = editor.edit(root);
        if (!result.modified() || result.changed() < 1) {
            throw new IOException("Standalone NBT edit made no expected change");
        }
        try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        )) {
            try (OutputStream raw = new ChannelOutputStream(channel);
                 OutputStream gzip = new GZIPOutputStream(raw, 16 * 1_024)) {
                Nbt.writeRoot(root, gzip);
            }
            channel.force(true);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
        try {
            Nbt.Root reread = readGzip(temporary, limits);
            if (!Nbt.semanticSha256(reread.tag()).equals(result.postSemanticSha256())) {
                throw new IOException("Standalone NBT semantic reread verification failed");
            }
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
        return result;
    }

    private static final class ChannelOutputStream extends OutputStream {
        private final FileChannel channel;

        private ChannelOutputStream(FileChannel channel) {
            this.channel = channel;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value});
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) <= 0) {
                    throw new IOException("Standalone NBT write made no progress");
                }
            }
        }
    }
}
