package dev.yu.worldrepair.worldtool.maintenance;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/**
 * Durable index for one all-loaded-world maintenance transaction.
 */
public record MaintenanceJobGroup(
        int schemaVersion,
        String originRequestId,
        String createdAt,
        String updatedAt,
        State state,
        List<Entry> entries,
        String detail
) {
    public static final int SCHEMA_VERSION = 1;

    public MaintenanceJobGroup {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public MaintenanceJobGroup withEntries(
            List<Entry> nextEntries,
            State nextState,
            String nextDetail
    ) {
        return new MaintenanceJobGroup(
                schemaVersion,
                originRequestId,
                createdAt,
                Instant.now().toString(),
                nextState,
                nextEntries,
                nextDetail
        );
    }

    public void validate() {
        if (schemaVersion != SCHEMA_VERSION
                || originRequestId == null
                || !originRequestId.matches("[0-9a-fA-F-]{36}")
                || state == null
                || detail == null
                || detail.length() > 2_048
                || entries.size() > MaintenanceWorldRoots.MAX_WORLD_ROOTS) {
            throw new IllegalArgumentException("Maintenance job group header is invalid");
        }
        Instant.parse(createdAt);
        Instant.parse(updatedAt);
        HashSet<String> roots = new HashSet<>();
        HashSet<String> jobs = new HashSet<>();
        for (Entry entry : entries) {
            if (entry == null
                    || entry.kind() == null
                    || !validAbsolutePath(entry.worldRoot())
                    || !validAbsolutePath(entry.jobPath())
                    || !roots.add(entry.worldRoot())
                    || !jobs.add(entry.jobPath())
                    || entry.targets() < 0) {
                throw new IllegalArgumentException("Maintenance job group entry is invalid");
            }
        }
    }

    private static boolean validAbsolutePath(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 32_768
                || value.indexOf('\0') >= 0
                || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            return false;
        }
        try {
            return Path.of(value).isAbsolute();
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public record Entry(
            Kind kind,
            String worldRoot,
            String jobPath,
            int targets,
            boolean modified,
            boolean rollbackAvailable
    ) {
        public Entry applied(boolean changed, boolean canRollback) {
            return new Entry(
                    kind,
                    worldRoot,
                    jobPath,
                    targets,
                    changed,
                    canRollback
            );
        }
    }

    public enum Kind {
        LEGACY_ICEANDFIRE_CHICKEN_DATA,
        NAMESPACE
    }

    public enum State {
        PREPARING,
        PREPARED,
        APPLYING,
        VERIFIED,
        ROLLED_BACK,
        FAILED
    }
}
