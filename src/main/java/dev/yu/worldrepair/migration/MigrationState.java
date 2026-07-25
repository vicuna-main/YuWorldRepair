package dev.yu.worldrepair.migration;

public enum MigrationState {
    PREPARED,
    QUARANTINING,
    QUARANTINED,
    RESTORING,
    RESTORED,
    FAILED
}
