package dev.yu.worldrepair.worldtool.maintenance;

import java.time.Instant;

/**
 * Machine-readable lifecycle signal consumed by the external maintenance supervisor.
 *
 * <p>This file deliberately contains no authorization secret. The signed request remains the
 * worker's sole authorization source; the supervisor identifier only correlates one launcher
 * instance with the exact server and worker processes it owns.</p>
 */
public record MaintenanceHandoff(
        int schemaVersion,
        String requestId,
        String supervisorId,
        long serverPid,
        long workerPid,
        long workerStartedAtEpochMillis,
        MaintenanceRequest.State state,
        String updatedAt,
        long requestExpiresAtEpochMillis,
        String detail
) {
    public static final int SCHEMA_VERSION = 1;
    public static final String SUPERVISOR_ID_ENV = "YUWORLDREPAIR_SUPERVISOR_ID";

    public static MaintenanceHandoff of(
            MaintenanceRequest request,
            String supervisorId,
            MaintenanceRequest.State state,
            String detail
    ) {
        return new MaintenanceHandoff(
                SCHEMA_VERSION,
                request.requestId(),
                normalizeSupervisorId(supervisorId),
                request.parentPid(),
                ProcessHandle.current().pid(),
                ProcessHandle.current().info().startInstant()
                        .orElseGet(Instant::now)
                        .toEpochMilli(),
                state,
                Instant.now().toString(),
                Instant.parse(request.expiresAt()).toEpochMilli(),
                detail
        );
    }

    public MaintenanceHandoff withState(MaintenanceRequest.State next, String nextDetail) {
        return new MaintenanceHandoff(
                schemaVersion,
                requestId,
                supervisorId,
                serverPid,
                workerPid,
                workerStartedAtEpochMillis,
                next,
                Instant.now().toString(),
                requestExpiresAtEpochMillis,
                nextDetail
        );
    }

    public boolean hasWorldWorkEvidence() {
        return switch (state) {
            case SCANNING, BACKING_UP, APPLYING, VERIFYING, ROLLING_BACK,
                    COMPLETED, ROLLED_BACK, FAILED -> true;
            case REQUESTED, COUNTDOWN, HANDOFF, WAITING_FOR_STOP -> false;
        };
    }

    public void validate() {
        if (schemaVersion != SCHEMA_VERSION
                || requestId == null
                || !requestId.matches("[0-9a-fA-F-]{36}")
                || serverPid <= 0
                || workerPid <= 0
                || workerStartedAtEpochMillis <= 0
                || state == null
                || requestExpiresAtEpochMillis <= 0
                || detail == null
                || detail.length() > 4_096) {
            throw new IllegalArgumentException("Maintenance handoff fields are invalid");
        }
        Instant.parse(updatedAt);
        if (supervisorId != null && !supervisorId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Maintenance supervisor identifier is invalid");
        }
    }

    private static String normalizeSupervisorId(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
