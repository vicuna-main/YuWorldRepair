package dev.yu.worldrepair.migration;

import java.util.List;
import java.util.Map;

public record MigrationManifest(
        int schema,
        String jobId,
        String createdAt,
        String updatedAt,
        String operator,
        String adapter,
        MigrationState state,
        String worldFingerprint,
        String dimension,
        int x,
        int y,
        int z,
        String sourceFingerprint,
        Map<String, String> versions,
        List<MigrationCandidate> candidates,
        String lastError
) {
    public MigrationManifest withState(MigrationState newState, String timestamp, String error) {
        return new MigrationManifest(
                schema,
                jobId,
                createdAt,
                timestamp,
                operator,
                adapter,
                newState,
                worldFingerprint,
                dimension,
                x,
                y,
                z,
                sourceFingerprint,
                versions,
                candidates,
                error
        );
    }

    public MigrationManifest withCandidates(List<MigrationCandidate> updated, String timestamp) {
        return new MigrationManifest(
                schema,
                jobId,
                createdAt,
                timestamp,
                operator,
                adapter,
                state,
                worldFingerprint,
                dimension,
                x,
                y,
                z,
                sourceFingerprint,
                versions,
                List.copyOf(updated),
                lastError
        );
    }
}
