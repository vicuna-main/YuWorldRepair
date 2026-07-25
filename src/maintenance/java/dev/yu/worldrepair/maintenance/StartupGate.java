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
        MaintenanceRequest request = MaintenanceFiles.readStoredRequest(requestPath);
        if (Files.isRegularFile(resultPath, LinkOption.NOFOLLOW_LINKS)) {
            MaintenanceResult result = MaintenanceFiles.readResult(resultPath);
            requireSafe(result);
            return Optional.of(result);
        }

        if ((request.state() == MaintenanceRequest.State.REQUESTED
                || request.state() == MaintenanceRequest.State.COUNTDOWN)
                && ProcessHandle.of(request.parentPid()).filter(ProcessHandle::isAlive).isEmpty()) {
            MaintenanceResult abandoned = MaintenanceResult.of(
                    request,
                    false,
                    MaintenanceRequest.State.COMPLETED,
                    "Previous server exited before worker handoff; world was not modified",
                    request.jobPath(),
                    false,
                    Map.of(),
                    false
            );
            MaintenanceFiles.writeResult(resultPath, abandoned);
            return Optional.of(abandoned);
        }

        Instant deadline = Instant.now().plus(Duration.ofSeconds(waitSeconds));
        while (Instant.now().isBefore(deadline)) {
            if (Files.isRegularFile(resultPath, LinkOption.NOFOLLOW_LINKS)) {
                MaintenanceResult result = MaintenanceFiles.readResult(resultPath);
                requireSafe(result);
                return Optional.of(result);
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
