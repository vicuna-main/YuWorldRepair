package dev.yu.worldrepair.maintenance;

import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.maintenance.MaintenanceFiles;
import dev.yu.worldrepair.worldtool.maintenance.MaintenanceHandoff;
import dev.yu.worldrepair.worldtool.maintenance.MaintenanceRequest;
import dev.yu.worldrepair.worldtool.maintenance.MaintenanceResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupGateTest {
    @TempDir
    Path temporary;

    @Test
    void noHistoryOpensImmediately() throws Exception {
        assertTrue(StartupGate.awaitSafeResult(temporary, 60).isEmpty());
    }

    @Test
    void abandonedPreHandoffRequestIsRecordedAsWorldUntouched() throws Exception {
        Path requestPath = writeRequest(MaintenanceRequest.State.REQUESTED, Long.MAX_VALUE);
        MaintenanceResult result = StartupGate.awaitSafeResult(temporary, 60).orElseThrow();

        assertFalse(result.success());
        assertEquals(MaintenanceRequest.State.COMPLETED, result.state());
        assertTrue(Files.isRegularFile(
                requestPath.resolveSibling(MaintenanceFiles.RESULT_FILE)
        ));
    }

    @Test
    void expiredWaitingRequestWithDeadParentClosesImmediatelyWithoutWorldAccess()
            throws Exception {
        Path world = temporary.resolve("world");
        Files.createDirectories(world);
        Path canary = world.resolve("region-canary.bin");
        Files.write(canary, new byte[]{1, 3, 3, 7});
        String before = IoUtil.sha256(canary);
        Path requestPath = writeRequest(
                MaintenanceRequest.State.WAITING_FOR_STOP,
                Long.MAX_VALUE,
                Instant.now().minusSeconds(1_900),
                Instant.now().minusSeconds(100)
        );

        long started = System.nanoTime();
        MaintenanceResult result = StartupGate.awaitSafeResult(temporary, 60).orElseThrow();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 2_000, "stale request should not wait for startupWaitSeconds");
        assertFalse(result.success());
        assertEquals(MaintenanceRequest.State.COMPLETED, result.state());
        assertFalse(result.rollbackAvailable());
        assertFalse(result.restartAttempted());
        assertTrue(result.detail().contains("no persisted evidence"));
        assertEquals(before, IoUtil.sha256(canary));
        assertFalse(Files.exists(temporary.resolve("jobs")));
        assertEquals(
                MaintenanceRequest.State.COMPLETED,
                MaintenanceFiles.readStoredRequest(requestPath).state()
        );
    }

    @Test
    void unexpiredWaitingRequestRetainsWorkerHandoffWindow() throws Exception {
        Path requestPath = writeRequest(
                MaintenanceRequest.State.WAITING_FOR_STOP,
                Long.MAX_VALUE
        );

        assertThrows(
                IOException.class,
                () -> StartupGate.awaitSafeResult(temporary, 1)
        );
        assertFalse(Files.exists(
                requestPath.resolveSibling(MaintenanceFiles.RESULT_FILE)
        ));
        assertEquals(
                MaintenanceRequest.State.WAITING_FOR_STOP,
                MaintenanceFiles.readStoredRequest(requestPath).state()
        );
    }

    @Test
    void liveParentPreventsExpiredWaitingRequestCleanup() throws Exception {
        Path requestPath = writeRequest(
                MaintenanceRequest.State.WAITING_FOR_STOP,
                ProcessHandle.current().pid(),
                Instant.now().minusSeconds(1_900),
                Instant.now().minusSeconds(100)
        );

        assertThrows(
                IOException.class,
                () -> StartupGate.awaitSafeResult(temporary, 1)
        );
        assertFalse(Files.exists(
                requestPath.resolveSibling(MaintenanceFiles.RESULT_FILE)
        ));
    }

    @ParameterizedTest
    @EnumSource(
            value = MaintenanceRequest.State.class,
            names = {"SCANNING", "APPLYING", "VERIFYING"}
    )
    void expiredWorldWorkStateIsNeverReclassifiedAsUnstarted(
            MaintenanceRequest.State state
    ) throws Exception {
        Path requestPath = writeRequest(
                state,
                Long.MAX_VALUE,
                Instant.now().minusSeconds(1_900),
                Instant.now().minusSeconds(100)
        );

        assertThrows(
                IOException.class,
                () -> StartupGate.awaitSafeResult(temporary, 1)
        );
        assertFalse(Files.exists(
                requestPath.resolveSibling(MaintenanceFiles.RESULT_FILE)
        ));
    }

    @Test
    void advancedHandoffEvidencePreventsWaitingRequestCleanup() throws Exception {
        Path requestPath = writeRequest(
                MaintenanceRequest.State.WAITING_FOR_STOP,
                Long.MAX_VALUE,
                Instant.now().minusSeconds(1_900),
                Instant.now().minusSeconds(100)
        );
        MaintenanceRequest request = MaintenanceFiles.readStoredRequest(requestPath);
        MaintenanceFiles.writeHandoff(
                requestPath.resolveSibling(MaintenanceFiles.HANDOFF_FILE),
                MaintenanceHandoff.of(
                        request,
                        null,
                        MaintenanceRequest.State.APPLYING,
                        "test evidence"
                )
        );

        assertThrows(
                IOException.class,
                () -> StartupGate.awaitSafeResult(temporary, 1)
        );
        assertFalse(Files.exists(
                requestPath.resolveSibling(MaintenanceFiles.RESULT_FILE)
        ));
    }

    @Test
    void concurrentStartupAttemptsCreateOneIdempotentStaleResult() throws Exception {
        Path requestPath = writeRequest(
                MaintenanceRequest.State.WAITING_FOR_STOP,
                Long.MAX_VALUE,
                Instant.now().minusSeconds(1_900),
                Instant.now().minusSeconds(100)
        );
        int attempts = 8;
        CountDownLatch start = new CountDownLatch(1);
        ArrayList<Future<MaintenanceResult>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            for (int index = 0; index < attempts; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return StartupGate.awaitSafeResult(temporary, 5).orElseThrow();
                }));
            }
            start.countDown();
            Set<String> completionTimes = new java.util.HashSet<>();
            for (Future<MaintenanceResult> future : futures) {
                MaintenanceResult result = future.get(10, TimeUnit.SECONDS);
                assertEquals(MaintenanceRequest.State.COMPLETED, result.state());
                completionTimes.add(result.completedAt());
            }
            assertEquals(1, completionTimes.size());
        }
        assertTrue(Files.isRegularFile(
                requestPath.resolveSibling(MaintenanceFiles.RESULT_FILE)
        ));
        assertFalse(Files.exists(
                requestPath.resolveSibling(MaintenanceFiles.RESULT_FILE + ".tmp")
        ));
    }

    @Test
    void failedPostHandoffResultRefusesStartup() throws Exception {
        Path requestPath = writeRequest(MaintenanceRequest.State.WAITING_FOR_STOP, 999_999_999L);
        MaintenanceRequest request = MaintenanceFiles.readStoredRequest(requestPath);
        MaintenanceFiles.writeResult(
                requestPath.resolveSibling(MaintenanceFiles.RESULT_FILE),
                MaintenanceResult.of(
                        request,
                        false,
                        MaintenanceRequest.State.FAILED,
                        "ambiguous region hash",
                        null,
                        true,
                        Map.of(),
                        false
                )
        );

        assertThrows(
                IOException.class,
                () -> StartupGate.awaitSafeResult(temporary, 60)
        );
    }

    @Test
    void verifiedRollbackResultAllowsStartup() throws Exception {
        Path requestPath = writeRequest(MaintenanceRequest.State.ROLLED_BACK, 999_999_999L);
        MaintenanceRequest request = MaintenanceFiles.readStoredRequest(requestPath);
        MaintenanceFiles.writeResult(
                requestPath.resolveSibling(MaintenanceFiles.RESULT_FILE),
                MaintenanceResult.of(
                        request,
                        false,
                        MaintenanceRequest.State.ROLLED_BACK,
                        "automatic rollback verified",
                        "job",
                        false,
                        Map.of("files", 2),
                        false
                )
        );

        MaintenanceResult result = StartupGate.awaitSafeResult(temporary, 60).orElseThrow();
        assertEquals(MaintenanceRequest.State.ROLLED_BACK, result.state());
    }

    @Test
    void defaultConfigIsEnabledButUsesPanelRestart() throws Exception {
        MaintenanceConfig.Values values =
                new MaintenanceConfig(temporary.resolve("config")).load();

        assertTrue(values.enabled());
        assertEquals(MaintenanceRequest.RestartStrategy.PANEL, values.restartStrategy());
        assertTrue(values.restartCommand().isEmpty());
    }

    @Test
    void immutablePathTamperingBreaksRequestHmac() throws Exception {
        Path requestPath = writeRequest(MaintenanceRequest.State.REQUESTED, Long.MAX_VALUE);
        String originalJobs = temporary.resolve("jobs").toString();
        String changedJobs = temporary.resolve("different-jobs").toString();
        String json = Files.readString(requestPath, StandardCharsets.UTF_8)
                .replace(originalJobs.replace("\\", "\\\\"), changedJobs.replace("\\", "\\\\"));
        Files.writeString(requestPath, json, StandardCharsets.UTF_8);
        MaintenanceRequest tampered = MaintenanceFiles.readStoredRequest(requestPath);

        assertNotEquals(
                tampered.bindingHmacSha256(),
                tampered.computeBindingHmac("0123456789abcdef0123456789abcdef")
        );
    }

    @Test
    void schemaThreeRequestWithoutWorldRootsRemainsReadable() throws Exception {
        Instant now = Instant.now();
        Path requestDirectory = MaintenanceHistory.requestsRoot(temporary)
                .resolve("11111111-2222-3333-4444-555555555555");
        Files.createDirectories(requestDirectory);
        String secret = "0123456789abcdef0123456789abcdef";
        MaintenanceRequest request = new MaintenanceRequest(
                3,
                "11111111-2222-3333-4444-555555555555",
                IoUtil.sha256(secret.getBytes(StandardCharsets.UTF_8)),
                "0".repeat(64),
                now.toString(),
                now.plusSeconds(1_800).toString(),
                Long.MAX_VALUE,
                MaintenanceRequest.Operation.REPAIR,
                temporary.toString(),
                temporary.resolve("world").toString(),
                temporary.resolve("jobs").toString(),
                temporary.resolve("iceandfire.jar").toString(),
                null,
                null,
                null,
                null,
                null,
                "1.21.1",
                "21.1.241",
                "Youer",
                MaintenanceRequest.RestartStrategy.PANEL,
                List.of(),
                MaintenanceRequest.State.REQUESTED,
                "legacy test"
        );
        request = request.withBindingHmac(request.computeBindingHmac(secret));
        Path requestPath = requestDirectory.resolve(MaintenanceFiles.REQUEST_FILE);
        MaintenanceFiles.writeStoredRequest(requestPath, request);
        String oldJson = Files.readString(requestPath, StandardCharsets.UTF_8)
                .replaceFirst("(?s)\\s*\"worldRoots\"\\s*:\\s*\\[.*?]\\s*,", "");
        Files.writeString(requestPath, oldJson, StandardCharsets.UTF_8);

        MaintenanceRequest stored = MaintenanceFiles.readStoredRequest(requestPath);
        stored.validateStored();
        assertEquals(List.of(stored.worldRoot()), stored.worldRoots());
        assertEquals(stored.bindingHmacSha256(), stored.computeBindingHmac(secret));

        MaintenanceResult result = StartupGate.awaitSafeResult(temporary, 60).orElseThrow();
        assertEquals(MaintenanceRequest.State.COMPLETED, result.state());
    }

    private Path writeRequest(MaintenanceRequest.State state, long parentPid) throws Exception {
        Instant now = Instant.now();
        return writeRequest(state, parentPid, now, now.plusSeconds(1_800));
    }

    private Path writeRequest(
            MaintenanceRequest.State state,
            long parentPid,
            Instant createdAt,
            Instant expiresAt
    ) throws Exception {
        Path requestDirectory = MaintenanceHistory.requestsRoot(temporary)
                .resolve("11111111-2222-3333-4444-555555555555");
        Files.createDirectories(requestDirectory);
        String secret = "0123456789abcdef0123456789abcdef";
        MaintenanceRequest request = new MaintenanceRequest(
                MaintenanceRequest.SCHEMA_VERSION,
                "11111111-2222-3333-4444-555555555555",
                IoUtil.sha256(secret.getBytes(StandardCharsets.UTF_8)),
                "0".repeat(64),
                createdAt.toString(),
                expiresAt.toString(),
                parentPid,
                MaintenanceRequest.Operation.REPAIR,
                temporary.toString(),
                temporary.resolve("world").toString(),
                temporary.resolve("jobs").toString(),
                temporary.resolve("iceandfire.jar").toString(),
                null,
                null,
                null,
                null,
                null,
                "1.21.1",
                "21.1.241",
                "Youer",
                MaintenanceRequest.RestartStrategy.PANEL,
                List.of(),
                state,
                "test"
        );
        request = request.withBindingHmac(request.computeBindingHmac(secret));
        Path requestPath = requestDirectory.resolve(MaintenanceFiles.REQUEST_FILE);
        MaintenanceFiles.writeStoredRequest(requestPath, request);
        return requestPath;
    }
}
