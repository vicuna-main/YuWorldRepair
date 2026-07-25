package dev.yu.worldrepair.migration;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {
    public static final long MAX_BLOB_BYTES = 16L * 1_024 * 1_024;

    private Hashing() {
    }

    public static String nbtSemanticHash(CompoundTag tag) throws IOException {
        return nbtDigest(tag).sha256();
    }

    public static NbtDigest nbtDigest(CompoundTag tag) throws IOException {
        MessageDigest digest = sha256();
        LimitedOutputStream limited = new LimitedOutputStream(
                new DigestOutputStream(OutputStream.nullOutputStream(), digest),
                MAX_BLOB_BYTES
        );
        try (DataOutputStream output = new DataOutputStream(limited)) {
            NbtIo.write(tag, output);
        }
        return new NbtDigest(HexFormat.of().formatHex(digest.digest()), limited.bytesWritten());
    }

    public static String fileSha256(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (var input = Files.newInputStream(path);
             var digested = new java.security.DigestInputStream(input, digest)) {
            digested.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String textSha256(String value) {
        MessageDigest digest = sha256();
        return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record NbtDigest(String sha256, long encodedBytes) {
    }

    private static final class LimitedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final long limit;
        private long written;

        private LimitedOutputStream(OutputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            delegate.write(value);
            written++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureCapacity(length);
            delegate.write(bytes, offset, length);
            written += length;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        private void ensureCapacity(int requested) throws IOException {
            if (requested < 0 || written > limit - requested) {
                throw new IOException("NBT blob exceeds hard limit of " + limit + " bytes");
            }
        }

        private long bytesWritten() {
            return written;
        }
    }
}
