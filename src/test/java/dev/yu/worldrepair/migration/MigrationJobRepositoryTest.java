package dev.yu.worldrepair.migration;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationJobRepositoryTest {
    @TempDir
    Path temporary;

    @Test
    void writesAndVerifiesRequiredRollbackArtifacts() throws Exception {
        MigrationJobRepository repository = new MigrationJobRepository(temporary);
        CompoundTag original = new CompoundTag();
        original.putString("#t", "ae2:i");
        original.putString("id", "removedmod:test_item");
        String hash = Hashing.nbtSemanticHash(original);
        String jobId = repository.newJobId();
        repository.writeBlob(jobId, hash, original);

        MigrationManifest manifest = manifest(jobId, hash);
        repository.create(manifest);
        repository.appendChange(jobId, Map.of("event", "TEST", "hash", hash));
        repository.writeVerification(jobId, Map.of("result", "pass"));

        MigrationManifest loaded = repository.readManifest(jobId);
        CompoundTag restored = repository.readBlob(jobId, hash);
        Path job = repository.jobDirectory(jobId);
        assertEquals(manifest, loaded);
        assertEquals("removedmod:test_item", restored.getString("id"));
        assertTrue(Files.exists(job.resolve("manifest.json")));
        assertTrue(Files.exists(job.resolve("source-hashes.json")));
        assertTrue(Files.exists(job.resolve("blobs").resolve(hash + ".nbt.gz")));
        assertTrue(Files.exists(job.resolve("changes.jsonl")));
        assertTrue(Files.exists(job.resolve("verification.json")));
        assertEquals(1, repository.readChanges(jobId).size());
    }

    @Test
    void rejectsPathTraversalJobIds() throws Exception {
        MigrationJobRepository repository = new MigrationJobRepository(temporary);
        assertThrows(IOException.class, () -> repository.jobDirectory("../world"));
        assertThrows(IOException.class, () -> repository.readManifest(".."));
    }

    private static MigrationManifest manifest(String jobId, String hash) {
        String now = Instant.EPOCH.toString();
        return new MigrationManifest(
                1,
                jobId,
                now,
                now,
                "console",
                "ae2-network-missing-content/19.2.17",
                MigrationState.PREPARED,
                "world",
                "minecraft:overworld",
                0,
                64,
                0,
                "source",
                Map.of("ae2", "19.2.17"),
                List.of(new MigrationCandidate("removedmod:test_item", 42, hash, hash)),
                ""
        );
    }
}
