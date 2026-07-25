package dev.yu.worldrepair.worldtool.maintenance;

import java.time.Instant;
import java.util.Map;

public record MaintenanceResult(
        int schemaVersion,
        String requestId,
        boolean success,
        MaintenanceRequest.State state,
        String completedAt,
        String operation,
        String detail,
        String jobPath,
        boolean rollbackAvailable,
        Map<String, ?> metrics,
        boolean restartAttempted
) {
    public static MaintenanceResult of(
            MaintenanceRequest request,
            boolean success,
            MaintenanceRequest.State state,
            String detail,
            String jobPath,
            boolean rollbackAvailable,
            Map<String, ?> metrics,
            boolean restartAttempted
    ) {
        return new MaintenanceResult(
                1,
                request.requestId(),
                success,
                state,
                Instant.now().toString(),
                request.operation().name(),
                detail,
                jobPath,
                rollbackAvailable,
                metrics == null ? Map.of() : Map.copyOf(metrics),
                restartAttempted
        );
    }
}
