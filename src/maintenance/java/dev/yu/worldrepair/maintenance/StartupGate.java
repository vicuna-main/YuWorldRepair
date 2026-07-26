package dev.yu.worldrepair.maintenance;

import dev.yu.worldrepair.worldtool.maintenance.MaintenanceFiles;
import dev.yu.worldrepair.worldtool.maintenance.MaintenanceRequest;
import dev.yu.worldrepair.worldtool.maintenance.MaintenanceResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

final class StartupGate {
    private static final System.Logger LOGGER =
            System.getLogger(StartupGate.class.getName());
    private static final String PRE_HANDOFF_DETAIL =
            "Previous server exited before worker handoff; world was not modified";
    private static final String STALE_WAITING_DETAIL =
            "Previous server exited before worker handoff completed; no persisted evidence "
                    + "shows that offline repair began, so the world was not modified";

    private StartupGate() {
    }

    static Optional<MaintenanceResult> awaitSafeResult(
            Path gameDirectory,
            int waitSeconds
    ) throws IOException {
        Optional<Path> latest = MaintenanceHistory.latestRequestDirectory(gameDirectory);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        Path requestPath = latest.get().resolve(MaintenanceFiles.REQUEST_FILE);
        Path resultPath = latest.get().resolve(MaintenanceFiles.RESULT_FILE);

        Instant deadline = Instant.now().plus(Duration.ofSeconds(waitSeconds));
        while (true) {
            if (Files.isRegularFile(resultPath, LinkOption.NOFOLLOW_LINKS)) {
                MaintenanceResult result = MaintenanceFiles.readResult(resultPath);
                requireSafe(result);
                return Optional.of(result);
            }
            Optional<MaintenanceResult> abandoned = resolveAbandonedRequest(
                    latest.get(),
                    requestPath,
                    resultPath
            );
            if (abandoned.isPresent()) {
                requireSafe(abandoned.get());
                return abandoned;
            }
            if (!Instant.now().isBefore(deadline)) {
                break;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for maintenance worker", interrupted);
            }
        }
        throw new IOException(
                "Maintenance worker did not produce a safe terminal result within "
                        + waitSeconds + " seconds; refusing to open the world"
        );
    }

    private static Optional<MaintenanceResult> resolveAbandonedRequest(
            Path requestDirectory,
            Path requestPath,
            Path resultPath
    ) throws IOException {
        Optional<MaintenanceFiles.ResultResolution> resolution =
                MaintenanceFiles.resolveResult(resultPath, () -> {
                    MaintenanceRequest current = MaintenanceFiles.readStoredRequest(requestPath);
                    Optional<String> abandonedDetail = abandonedDetail(
                            requestDirectory,
                            current
                    );
                    if (abandonedDetail.isEmpty()) {
                        return Optional.empty();
                    }
                    MaintenanceRequest completed = current.withState(
                            MaintenanceRequest.State.COMPLETED,
                            abandonedDetail.get()
                    );
                    MaintenanceFiles.writeStoredRequest(requestPath, completed);
                    return Optional.of(MaintenanceResult.of(
                            completed,
                            false,
                            MaintenanceRequest.State.COMPLETED,
                            abandonedDetail.get(),
                            completed.jobPath(),
                            false,
                            Map.of(),
                            false
                    ));
                });
        if (resolution.isEmpty()) {
            return Optional.empty();
        }
        MaintenanceFiles.ResultResolution resolved = resolution.get();
        if (resolved.created()) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Closed abandoned maintenance request {0}: {1}",
                    resolved.result().requestId(),
                    resolved.result().detail()
            );
        }
        return Optional.of(resolved.result());
    }

    private static Optional<String> abandonedDetail(
            Path requestDirectory,
            MaintenanceRequest request
    ) throws IOException {
        if (request.state() == MaintenanceRequest.State.COMPLETED
                && (PRE_HANDOFF_DETAIL.equals(request.detail())
                || STALE_WAITING_DETAIL.equals(request.detail()))) {
            return Optional.of(request.detail());
        }
        if (isAlive(request.parentPid())) {
            return Optional.empty();
        }
        if (request.state() == MaintenanceRequest.State.REQUESTED
                || request.state() == MaintenanceRequest.State.COUNTDOWN) {
            return Optional.of(PRE_HANDOFF_DETAIL);
        }
        if (request.state() != MaintenanceRequest.State.WAITING_FOR_STOP
                || !Instant.now().isAfter(Instant.parse(request.expiresAt()))
                || hasWorldWorkEvidence(requestDirectory, request)) {
            return Optional.empty();
        }
        return Optional.of(STALE_WAITING_DETAIL);
    }

    private static boolean hasWorldWorkEvidence(
            Path requestDirectory,
            MaintenanceRequest request
    ) throws IOException {
        if (request.operation() != MaintenanceRequest.Operation.ROLLBACK
                && request.jobPath() != null) {
            return true;
        }
        Path handoffPath = requestDirectory.resolve(MaintenanceFiles.HANDOFF_FILE);
        if (!Files.exists(handoffPath, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        return MaintenanceFiles.readHandoff(handoffPath).hasWorldWorkEvidence();
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent();
    }

    private static void requireSafe(MaintenanceResult result) throws IOException {
        if (result.state() != MaintenanceRequest.State.COMPLETED
                && result.state() != MaintenanceRequest.State.ROLLED_BACK) {
            throw new IOException(
                    "Latest maintenance result is " + result.state()
                            + "; refusing to open the world: " + result.detail()
            );
        }
    }
}
