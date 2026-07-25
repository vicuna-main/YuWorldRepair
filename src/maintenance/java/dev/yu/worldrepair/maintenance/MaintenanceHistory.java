package dev.yu.worldrepair.maintenance;

import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;
import dev.yu.worldrepair.worldtool.maintenance.MaintenanceFiles;
import dev.yu.worldrepair.worldtool.maintenance.MaintenanceRequest;
import dev.yu.worldrepair.worldtool.maintenance.MaintenanceResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

final class MaintenanceHistory {
    static final String ROOT_DIRECTORY = "yuworldrepair-maintenance";
    static final String REQUESTS_DIRECTORY = "requests";
    static final String JOBS_DIRECTORY = "jobs";

    private MaintenanceHistory() {
    }

    static Path root(Path gameDirectory) {
        return gameDirectory.resolve(ROOT_DIRECTORY).toAbsolutePath().normalize();
    }

    static Path requestsRoot(Path gameDirectory) {
        return root(gameDirectory).resolve(REQUESTS_DIRECTORY);
    }

    static Path jobsRoot(Path gameDirectory) {
        return root(gameDirectory).resolve(JOBS_DIRECTORY);
    }

    static Optional<Path> latestRequestDirectory(Path gameDirectory) throws IOException {
        Path requests = requestsRoot(gameDirectory);
        if (!Files.exists(requests, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        WorldAccessPolicy.rejectLinkChain(requests);
        try (Stream<Path> children = Files.list(requests)) {
            return children
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> Files.isRegularFile(
                            path.resolve(MaintenanceFiles.REQUEST_FILE),
                            LinkOption.NOFOLLOW_LINKS
                    ))
                    .max(Comparator.comparing(MaintenanceHistory::lastModifiedSafely));
        }
    }

    static Optional<MaintenanceResult> latestResult(Path gameDirectory) throws IOException {
        Optional<Path> directory = latestRequestDirectory(gameDirectory);
        if (directory.isEmpty()) {
            return Optional.empty();
        }
        Path result = directory.get().resolve(MaintenanceFiles.RESULT_FILE);
        if (!Files.isRegularFile(result, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(MaintenanceFiles.readResult(result));
    }

    static MaintenanceRequest latestRequest(Path gameDirectory) throws IOException {
        Path directory = latestRequestDirectory(gameDirectory)
                .orElseThrow(() -> new IOException("No maintenance request exists"));
        return MaintenanceFiles.readStoredRequest(directory.resolve(MaintenanceFiles.REQUEST_FILE));
    }

    static Optional<MaintenanceResult> latestRollbackCandidate(Path gameDirectory)
            throws IOException {
        Path requests = requestsRoot(gameDirectory);
        if (!Files.exists(requests, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        WorldAccessPolicy.rejectLinkChain(requests);
        try (Stream<Path> children = Files.list(requests)) {
            return children
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(path -> path.resolve(MaintenanceFiles.RESULT_FILE))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(MaintenanceHistory::readResultSafely)
                    .flatMap(Optional::stream)
                    .filter(MaintenanceResult::rollbackAvailable)
                    .filter(result -> result.jobPath() != null && !result.jobPath().isBlank())
                    .max(Comparator.comparing(MaintenanceResult::completedAt));
        }
    }

    private static FileTime lastModifiedSafely(Path path) {
        try {
            return Files.getLastModifiedTime(
                    path.resolve(MaintenanceFiles.REQUEST_FILE),
                    LinkOption.NOFOLLOW_LINKS
            );
        } catch (IOException ignored) {
            return FileTime.fromMillis(0);
        }
    }

    private static Optional<MaintenanceResult> readResultSafely(Path path) {
        try {
            return Optional.of(MaintenanceFiles.readResult(path));
        } catch (IOException invalid) {
            return Optional.empty();
        }
    }
}
