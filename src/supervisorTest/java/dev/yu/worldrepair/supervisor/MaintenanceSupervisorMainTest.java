package dev.yu.worldrepair.supervisor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenanceSupervisorMainTest {
    @TempDir
    Path temporary;

    @Test
    void supervisorStaysAliveUntilWorkerReleasesLockAndWritesTerminalResult()
            throws Exception {
        String java = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        ).toString();
        String classPath = System.getProperty("java.class.path");
        ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
        long started = System.nanoTime();

        int code;
        try (PrintStream output = new PrintStream(outputBytes, true, StandardCharsets.UTF_8);
             PrintStream error = new PrintStream(errorBytes, true, StandardCharsets.UTF_8)) {
            code = MaintenanceSupervisorMain.run(
                    new String[]{
                            "--server-root",
                            temporary.toString(),
                            "--worker-warning-seconds",
                            "1",
                            "--",
                            java,
                            "-cp",
                            classPath,
                            SupervisorLifecycleFixtureMain.class.getName(),
                            "server",
                            temporary.toString()
                    },
                    output,
                    error
            );
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(
                0,
                code,
                () -> errorBytes.toString(StandardCharsets.UTF_8)
        );
        assertTrue(elapsedMillis >= 1_000, "supervisor exited before the worker completed");
        Path requestDirectory = temporary
                .resolve("yuworldrepair-maintenance")
                .resolve("requests")
                .resolve(SupervisorLifecycleFixtureMain.REQUEST_ID);
        assertTrue(Files.isRegularFile(requestDirectory.resolve("backup.done")));
        assertTrue(Files.isRegularFile(requestDirectory.resolve("repair.done")));
        assertTrue(Files.isRegularFile(requestDirectory.resolve("verify.done")));
        assertTrue(Files.isRegularFile(requestDirectory.resolve("result.json")));
        String outputText = outputBytes.toString(StandardCharsets.UTF_8);
        assertTrue(outputText.contains("maintenance_handoff"));
        assertTrue(outputText.contains("maintenance_complete"));
        assertTrue(
                errorBytes.toString(StandardCharsets.UTF_8)
                        .contains("continuingToSupervise=true")
        );
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}
