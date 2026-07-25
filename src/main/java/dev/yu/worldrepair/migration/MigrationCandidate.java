package dev.yu.worldrepair.migration;

public record MigrationCandidate(
        String registryId,
        long amount,
        String semanticHash,
        String blobHash
) {
    public MigrationCandidate withBlobHash(String hash) {
        return new MigrationCandidate(registryId, amount, semanticHash, hash);
    }
}
