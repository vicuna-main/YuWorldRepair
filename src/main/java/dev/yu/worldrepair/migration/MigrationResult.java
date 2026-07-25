package dev.yu.worldrepair.migration;

public record MigrationResult(boolean success, String message, MigrationManifest manifest) {
    public static MigrationResult success(String message, MigrationManifest manifest) {
        return new MigrationResult(true, message, manifest);
    }

    public static MigrationResult failure(String message, MigrationManifest manifest) {
        return new MigrationResult(false, message, manifest);
    }
}
