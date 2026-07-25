package dev.yu.worldrepair.maintenance;

import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.maintenance.MaintenanceFiles;
import dev.yu.worldrepair.worldtool.maintenance.MaintenanceRequest;
import dev.yu.worldrepair.worldtool.maintenance.MaintenanceResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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

    private Path writeRequest(MaintenanceRequest.State state, long parentPid) throws Exception {
        Path requestDirectory = MaintenanceHistory.requestsRoot(temporary)
                .resolve("11111111-2222-3333-4444-555555555555");
        Files.createDirectories(requestDirectory);
        Instant now = Instant.now();
        String secret = "0123456789abcdef0123456789abcdef";
        MaintenanceRequest request = new MaintenanceRequest(
                MaintenanceRequest.SCHEMA_VERSION,
                "11111111-2222-3333-4444-555555555555",
                IoUtil.sha256(secret.getBytes(StandardCharsets.UTF_8)),
                "0".repeat(64),
                now.toString(),
                now.plusSeconds(1_800).toString(),
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
        MaintenanceFiles.writeRequest(requestPath, request);
        return requestPath;
    }
}
