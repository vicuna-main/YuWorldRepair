package dev.yu.worldrepair.worldtool.job;

public record SourceFileRecord(
        String relativePath,
        long size,
        String preSha256,
        String backupRelativePath,
        String postApplySha256
) {
    public SourceFileRecord withPostApplySha256(String hash) {
        return new SourceFileRecord(relativePath, size, preSha256, backupRelativePath, hash);
    }
}
