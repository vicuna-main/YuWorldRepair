package dev.yu.worldrepair.migration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class MigrationJobRepository {
    private static final long MAX_JOURNAL_BYTES = 16L * 1_024 * 1_024;
    private static final DateTimeFormatter JOB_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Gson COMPACT_GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path worldRoot;
    private final Path jobsRoot;

    public MigrationJobRepository(Path worldRoot) throws IOException {
        this.worldRoot = worldRoot.toAbsolutePath().normalize();
        this.jobsRoot = this.worldRoot.resolve("yuworldrepair").resolve("jobs").normalize();
        if (!jobsRoot.startsWith(this.worldRoot)) {
            throw new IOException("Migration jobs path escaped world root");
        }
        Files.createDirectories(jobsRoot);
    }

    public String newJobId() {
        long random = ThreadLocalRandom.current().nextLong();
        return JOB_TIME.format(Instant.now()) + "-" + Long.toUnsignedString(random, 16);
    }

    public void create(MigrationManifest manifest) throws IOException {
        Path directory = jobDirectory(manifest.jobId());
        Files.createDirectories(directory.resolve("blobs"));
        writeManifest(manifest);
        writeAtomic(
                directory.resolve("source-hashes.json"),
                GSON.toJson(Map.of(
                        "adapter", manifest.adapter(),
                        "source", manifest.dimension() + "@" + manifest.x() + "," + manifest.y() + "," + manifest.z(),
                        "semanticSha256", manifest.sourceFingerprint()
                )) + "\n"
        );
        writeAtomic(directory.resolve("changes.jsonl"), "");
    }

    public MigrationManifest readManifest(String jobId) throws IOException {
        Path path = jobDirectory(jobId).resolve("manifest.json");
        MigrationManifest manifest = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), MigrationManifest.class);
        if (manifest == null || !jobId.equals(manifest.jobId())) {
            throw new IOException("Invalid migration manifest");
        }
        return manifest;
    }

    public void writeManifest(MigrationManifest manifest) throws IOException {
        writeAtomic(jobDirectory(manifest.jobId()).resolve("manifest.json"), GSON.toJson(manifest) + "\n");
    }

    public String writeBlob(String jobId, String semanticHash, CompoundTag tag) throws IOException {
        if (!semanticHash.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid semantic blob hash");
        }
        Path blobs = jobDirectory(jobId).resolve("blobs");
        Files.createDirectories(blobs);
        Path target = blobs.resolve(semanticHash + ".nbt.gz").normalize();
        if (!target.startsWith(blobs)) {
            throw new IOException("Blob path escaped job directory");
        }
        if (Files.exists(target)) {
            CompoundTag existing = readBlob(jobId, semanticHash);
            if (!semanticHash.equals(Hashing.nbtSemanticHash(existing))) {
                throw new IOException("Existing blob hash mismatch");
            }
            return semanticHash;
        }
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        NbtIo.writeCompressed(tag, temporary);
        CompoundTag written = NbtIo.readCompressed(
                temporary,
                NbtAccounter.create(Hashing.MAX_BLOB_BYTES)
        );
        if (!semanticHash.equals(Hashing.nbtSemanticHash(written))) {
            throw new IOException("Written blob failed semantic verification");
        }
        atomicMove(temporary, target);
        return semanticHash;
    }

    public CompoundTag readBlob(String jobId, String semanticHash) throws IOException {
        if (!semanticHash.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid semantic blob hash");
        }
        Path blobs = jobDirectory(jobId).resolve("blobs");
        Path path = blobs.resolve(semanticHash + ".nbt.gz").normalize();
        if (!path.startsWith(blobs)) {
            throw new IOException("Blob path escaped job directory");
        }
        CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.create(Hashing.MAX_BLOB_BYTES));
        if (!semanticHash.equals(Hashing.nbtSemanticHash(tag))) {
            throw new IOException("Backup blob hash mismatch");
        }
        return tag;
    }

    public void appendChange(String jobId, Map<String, ?> change) throws IOException {
        Path path = jobDirectory(jobId).resolve("changes.jsonl");
        byte[] bytes = (COMPACT_GSON.toJson(change) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                StandardOpenOption.DSYNC
        )) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    public List<JsonObject> readChanges(String jobId) throws IOException {
        Path path = jobDirectory(jobId).resolve("changes.jsonl");
        if (!Files.exists(path)) {
            return List.of();
        }
        if (Files.size(path) > MAX_JOURNAL_BYTES) {
            throw new IOException("Migration journal exceeds hard size limit");
        }
        List<JsonObject> changes = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                changes.add(JsonParser.parseString(line).getAsJsonObject());
            }
        }
        return List.copyOf(changes);
    }

    public void writeVerification(String jobId, Map<String, ?> verification) throws IOException {
        writeAtomic(
                jobDirectory(jobId).resolve("verification.json"),
                GSON.toJson(verification) + "\n"
        );
    }

    public Path jobDirectory(String jobId) throws IOException {
        if (jobId == null || !jobId.matches("[A-Za-z0-9_-]{1,96}")) {
            throw new IOException("Invalid job id");
        }
        Path directory = jobsRoot.resolve(jobId).normalize();
        if (!directory.startsWith(jobsRoot)) {
            throw new IOException("Job path escaped jobs root");
        }
        return directory;
    }

    private static void writeAtomic(Path target, String contents) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                StandardOpenOption.DSYNC
        )) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        atomicMove(temporary, target);
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException noAtomicMove) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
