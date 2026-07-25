package dev.yu.worldrepair.worldtool.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class IoUtil {
    private static final int COPY_BUFFER_BYTES = 64 * 1_024;

    private IoUtil() {
    }

    public static String sha256(Path path) throws IOException {
        requireRegularFile(path, "Hash source");
        MessageDigest digest = sha256Digest();
        try (DigestInputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    public static void writeAtomicUtf8(Path target, String contents) throws IOException {
        WorldAccessPolicy.rejectLinkChain(target.getParent());
        Files.createDirectories(target.getParent());
        WorldAccessPolicy.rejectLinkChain(target.getParent());
        rejectExistingLink(target);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        rejectExistingLink(temporary);
        byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                StandardOpenOption.DSYNC
        )) {
            writeFully(channel, ByteBuffer.wrap(bytes));
            channel.force(true);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
        moveAtomic(temporary, target);
    }

    public static void appendUtf8Dsync(Path target, String line, long maxBytes) throws IOException {
        WorldAccessPolicy.rejectLinkChain(target.getParent());
        rejectExistingLink(target);
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        long before = Files.exists(target, LinkOption.NOFOLLOW_LINKS) ? Files.size(target) : 0;
        if (bytes.length > maxBytes || before > maxBytes - bytes.length) {
            throw new IOException("Journal exceeds hard byte limit");
        }
        try (FileChannel channel = FileChannel.open(
                target,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                StandardOpenOption.DSYNC
        )) {
            writeFully(channel, ByteBuffer.wrap(bytes));
            channel.force(true);
        }
    }

    public static void copyVerified(Path source, Path target, String expectedSha256) throws IOException {
        requireRegularFile(source, "Backup source");
        WorldAccessPolicy.rejectLinkChain(target.getParent());
        Files.createDirectories(target.getParent());
        WorldAccessPolicy.rejectLinkChain(target.getParent());
        rejectExistingLink(target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(target, "Existing backup");
            if (!expectedSha256.equals(sha256(target))) {
                throw new IOException("Existing backup hash mismatch: " + target);
            }
            return;
        }
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        rejectExistingLink(temporary);
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel output = FileChannel.open(
                     temporary,
                     StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE,
                     StandardOpenOption.DSYNC
             )) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES);
            while (true) {
                int count = input.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    throw new IOException("Backup read made no progress");
                }
                buffer.flip();
                writeFully(output, buffer);
                buffer.clear();
            }
            output.force(true);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
        if (!expectedSha256.equals(sha256(temporary))) {
            Files.deleteIfExists(temporary);
            throw new IOException("Backup verification failed: " + source);
        }
        moveAtomic(temporary, target);
    }

    public static void moveAtomic(Path source, Path target) throws IOException {
        requireRegularFile(source, "Atomic move source");
        WorldAccessPolicy.rejectLinkChain(target.getParent());
        rejectExistingLink(target);
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException unsupportedOrFailed) {
            throw new IOException(
                    "Atomic replacement is unavailable; refusing unsafe fallback for " + target,
                    unsupportedOrFailed
            );
        }
    }

    private static void rejectExistingLink(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            WorldAccessPolicy.rejectLink(path);
        }
    }

    private static void requireRegularFile(Path path, String description) throws IOException {
        WorldAccessPolicy.rejectLinkChain(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " is not a regular file: " + path);
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) <= 0) {
                throw new IOException("File write made no progress");
            }
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
