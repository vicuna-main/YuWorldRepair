package dev.yu.worldrepair.supervisor;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

public final class SupervisorLifecycleFixtureMain {
    static final String REQUEST_ID = "12345678-1234-1234-1234-123456789abc";

    private SupervisorLifecycleFixtureMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("fixture mode and server root are required");
        }
        Path serverRoot = Path.of(arguments[1]).toAbsolutePath().normalize();
        if ("server".equals(arguments[0])) {
            runServer(serverRoot);
        } else if ("worker".equals(arguments[0])) {
            runWorker(serverRoot);
        } else {
            throw new IllegalArgumentException("unknown fixture mode");
        }
    }

    private static void runServer(Path serverRoot) throws Exception {
        String supervisorId = requireSupervisorId();
        Path requestDirectory = requestDirectory(serverRoot);
        Path world = serverRoot.resolve("world");
        Files.createDirectories(requestDirectory);
        Files.createDirectories(world);
        Path lockPath = world.resolve("session.lock");
        try (FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        );
             FileLock serverLock = channel.lock()) {
            if (!serverLock.isValid()) {
                throw new IOException("fixture could not acquire session.lock");
            }
            String java = Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    isWindows() ? "java.exe" : "java"
            ).toString();
            Process worker = new ProcessBuilder(
                    java,
                    "-cp",
                    System.getProperty("java.class.path"),
                    SupervisorLifecycleFixtureMain.class.getName(),
                    "worker",
                    serverRoot.toString()
            ).inheritIO().start();
            long workerStarted = worker.info().startInstant()
                    .orElseGet(Instant::now)
                    .toEpochMilli();
            long expires = Instant.now().plusSeconds(8).toEpochMilli();
            String json = """
                    {
                      "schemaVersion": 1,
                      "requestId": "%s",
                      "supervisorId": "%s",
                      "serverPid": %d,
                      "workerPid": %d,
                      "workerStartedAtEpochMillis": %d,
                      "state": "WAITING_FOR_STOP",
                      "updatedAt": "%s",
                      "requestExpiresAtEpochMillis": %d,
                      "detail": "fixture worker is waiting for session.lock"
                    }
                    """.formatted(
                    REQUEST_ID,
                    supervisorId,
                    ProcessHandle.current().pid(),
                    worker.pid(),
                    workerStarted,
                    Instant.now(),
                    expires
            );
            writeAtomic(requestDirectory.resolve("handoff.json"), json);
        }
    }

    private static void runWorker(Path serverRoot) throws Exception {
        requireSupervisorId();
        Path requestDirectory = requestDirectory(serverRoot);
        Path lockPath = serverRoot.resolve("world").resolve("session.lock");
        waitForLock(lockPath);
        TimeUnit.MILLISECONDS.sleep(1_200);
        Files.writeString(
                requestDirectory.resolve("backup.done"),
                "complete",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                requestDirectory.resolve("repair.done"),
                "complete",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                requestDirectory.resolve("verify.done"),
                "complete",
                StandardCharsets.UTF_8
        );
        String result = """
                {
                  "schemaVersion": 1,
                  "requestId": "%s",
                  "success": true,
                  "state": "COMPLETED"
                }
                """.formatted(REQUEST_ID);
        writeAtomic(requestDirectory.resolve("result.json"), result);
    }

    private static void waitForLock(Path lockPath) throws Exception {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            try (FileChannel channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.WRITE
            );
                 FileLock lock = channel.tryLock()) {
                if (lock != null) {
                    return;
                }
            } catch (IOException | OverlappingFileLockException locked) {
                // The server fixture still owns session.lock.
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new IOException("fixture session.lock was not released");
    }

    private static void writeAtomic(Path target, String value) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(
                temporary,
                value,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
        Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE
        );
    }

    private static Path requestDirectory(Path serverRoot) {
        return serverRoot
                .resolve("yuworldrepair-maintenance")
                .resolve("requests")
                .resolve(REQUEST_ID);
    }

    private static String requireSupervisorId() {
        String value = System.getenv(MaintenanceSupervisorMain.SUPERVISOR_ID_ENV);
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("fixture did not inherit supervisor identifier");
        }
        return value;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}
