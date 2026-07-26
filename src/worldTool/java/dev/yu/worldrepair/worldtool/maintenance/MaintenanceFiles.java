package dev.yu.worldrepair.worldtool.maintenance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class MaintenanceFiles {
    public static final String REQUEST_FILE = "request.json";
    public static final String RESULT_FILE = "result.json";
    public static final String HANDOFF_FILE = "handoff.json";
    public static final String JOB_GROUP_FILE = "job-group.json";
    public static final String SCAN_PROGRESS_FILE = "scan-progress.json";
    public static final long MAX_JSON_BYTES = 1_048_576;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final ConcurrentMap<Path, Object> LOCAL_RESULT_LOCKS =
            new ConcurrentHashMap<>();

    private MaintenanceFiles() {
    }

    public static MaintenanceRequest readRequest(Path path) throws IOException {
        MaintenanceRequest request = read(path, MaintenanceRequest.class);
        try {
            request.validate();
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Maintenance request validation failed: " + invalid.getMessage(), invalid);
        }
        return request;
    }

    public static MaintenanceResult readResult(Path path) throws IOException {
        return read(path, MaintenanceResult.class);
    }

    public static MaintenanceHandoff readHandoff(Path path) throws IOException {
        MaintenanceHandoff handoff = read(path, MaintenanceHandoff.class);
        try {
            handoff.validate();
        } catch (IllegalArgumentException invalid) {
            throw new IOException(
                    "Maintenance handoff validation failed: " + invalid.getMessage(),
                    invalid
            );
        }
        return handoff;
    }

    public static MaintenanceJobGroup readJobGroup(Path directory) throws IOException {
        MaintenanceJobGroup group = read(
                directory.resolve(JOB_GROUP_FILE),
                MaintenanceJobGroup.class
        );
        try {
            group.validate();
        } catch (IllegalArgumentException invalid) {
            throw new IOException(
                    "Maintenance job group validation failed: " + invalid.getMessage(),
                    invalid
            );
        }
        return group;
    }

    public static MaintenanceRequest readStoredRequest(Path path) throws IOException {
        MaintenanceRequest request = read(path, MaintenanceRequest.class);
        try {
            request.validateStored();
        } catch (IllegalArgumentException invalid) {
            throw new IOException(
                    "Stored maintenance request validation failed: " + invalid.getMessage(),
                    invalid
            );
        }
        return request;
    }

    public static void writeRequest(Path path, MaintenanceRequest request) throws IOException {
        request.validate();
        IoUtil.writeAtomicUtf8(path, JSON.toJson(request) + "\n");
    }

    public static void writeStoredRequest(Path path, MaintenanceRequest request) throws IOException {
        request.validateStored();
        IoUtil.writeAtomicUtf8(path, JSON.toJson(request) + "\n");
    }

    public static void writeStoredRequestIfResultAbsent(
            Path requestPath,
            MaintenanceRequest request
    ) throws IOException {
        Path resultPath = requestPath.resolveSibling(RESULT_FILE);
        withResultLock(resultPath, () -> {
            if (Files.exists(resultPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "Maintenance request already has a terminal result; refusing state rewrite"
                );
            }
            writeStoredRequest(requestPath, request);
            return null;
        });
    }

    public static void writeResult(Path path, MaintenanceResult result) throws IOException {
        IoUtil.writeAtomicUtf8(path, JSON.toJson(result) + "\n");
    }

    public static void writeScanProgress(
            Path requestDirectory,
            Map<String, ?> progress
    ) throws IOException {
        Path directory = requestDirectory.toAbsolutePath().normalize();
        WorldAccessPolicy.rejectLinkChain(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            throw new IOException("Maintenance request directory is missing or linked");
        }
        String json = JSON.toJson(progress) + "\n";
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IOException("Maintenance scan progress exceeds hard limit");
        }
        IoUtil.writeAtomicUtf8(directory.resolve(SCAN_PROGRESS_FILE), json);
    }

    public static MaintenanceResult writeResultIfAbsent(
            Path path,
            MaintenanceResult result
    ) throws IOException {
        return resolveResult(
                path,
                () -> Optional.of(result)
        ).orElseThrow(() -> new IOException("Maintenance result factory returned no result"))
                .result();
    }

    /**
     * Serializes terminal-result creation across threads and JVMs.
     *
     * <p>The factory runs while the per-request file lock is held, so callers may re-read the
     * persisted request and decline creation if its state changed. An existing terminal result is
     * always returned byte-for-byte instead of being replaced.</p>
     */
    public static Optional<ResultResolution> resolveResult(
            Path path,
            ResultFactory factory
    ) throws IOException {
        return withResultLock(path, () -> {
            Path absolute = path.toAbsolutePath().normalize();
            if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(new ResultResolution(readResult(absolute), false));
            }
            Optional<MaintenanceResult> proposed = factory.create();
            if (proposed.isEmpty()) {
                return Optional.empty();
            }
            MaintenanceResult result = proposed.get();
            writeResult(absolute, result);
            return Optional.of(new ResultResolution(result, true));
        });
    }

    private static <T> T withResultLock(
            Path path,
            LockedOperation<T> operation
    ) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("Maintenance result path has no parent");
        }
        Path lockPath = absolute.resolveSibling("." + absolute.getFileName() + ".lock");
        Object localLock = LOCAL_RESULT_LOCKS.computeIfAbsent(lockPath, ignored -> new Object());
        synchronized (localLock) {
            WorldAccessPolicy.rejectLinkChain(parent);
            Files.createDirectories(parent);
            WorldAccessPolicy.rejectLinkChain(parent);
            if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                WorldAccessPolicy.rejectLink(lockPath);
                if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Maintenance result lock is not a regular file");
                }
            }
            try (FileChannel channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            );
                 FileLock resultLock = channel.lock()) {
                if (!resultLock.isValid()) {
                    throw new IOException("Maintenance result lock could not be acquired");
                }
                return operation.run();
            }
        }
    }

    public static void writeHandoff(Path path, MaintenanceHandoff handoff) throws IOException {
        handoff.validate();
        IoUtil.writeAtomicUtf8(path, JSON.toJson(handoff) + "\n");
    }

    public static void writeJobGroup(Path directory, MaintenanceJobGroup group)
            throws IOException {
        group.validate();
        WorldAccessPolicy.rejectLinkChain(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Maintenance job group directory is missing");
        }
        IoUtil.writeAtomicUtf8(
                directory.resolve(JOB_GROUP_FILE),
                JSON.toJson(group) + "\n"
        );
    }

    public static boolean isJobGroup(Path directory) {
        return Files.isRegularFile(
                directory.resolve(JOB_GROUP_FILE),
                LinkOption.NOFOLLOW_LINKS
        );
    }

    private static <T> T read(Path path, Class<T> type) throws IOException {
        WorldAccessPolicy.rejectLinkChain(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || Files.size(path) > MAX_JSON_BYTES) {
            throw new IOException("Maintenance JSON is missing, linked, or oversized: " + path);
        }
        try {
            T result = JSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), type);
            if (result == null) {
                throw new IOException("Maintenance JSON is empty");
            }
            return result;
        } catch (JsonParseException malformed) {
            throw new IOException("Maintenance JSON is malformed", malformed);
        }
    }

    @FunctionalInterface
    public interface ResultFactory {
        Optional<MaintenanceResult> create() throws IOException;
    }

    @FunctionalInterface
    private interface LockedOperation<T> {
        T run() throws IOException;
    }

    public record ResultResolution(MaintenanceResult result, boolean created) {
    }
}
