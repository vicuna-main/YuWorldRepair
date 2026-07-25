package dev.yu.worldrepair.worldtool.maintenance;

import dev.yu.worldrepair.worldtool.io.IoUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MaintenanceWorkerSecurityTest {
    @TempDir
    Path temporary;

    @Test
    void validHandoffWithUnapprovedAdapterJarFailsBeforeWorldMutation() throws Exception {
        Path world = temporary.resolve("world");
        Files.createDirectories(world);
        Files.write(world.resolve("level.dat"), new byte[]{10, 0, 0, 0});
        Path iceJar = temporary.resolve("iceandfire.jar");
        Files.write(iceJar, new byte[]{1, 2, 3});
        String levelHash = IoUtil.sha256(world.resolve("level.dat"));
        String secret = "0123456789abcdef0123456789abcdef";
        Path requestPath = writeRequest(world, iceJar, secret);

        int exit = MaintenanceWorkerMain.run(
                new String[]{requestPath.toString()},
                secret
        );

        assertEquals(3, exit);
        assertEquals(levelHash, IoUtil.sha256(world.resolve("level.dat")));
        MaintenanceResult result = MaintenanceFiles.readResult(
                requestPath.resolveSibling(MaintenanceFiles.RESULT_FILE)
        );
        assertFalse(result.success());
        assertEquals(MaintenanceRequest.State.FAILED, result.state());
    }

    private Path writeRequest(Path world, Path iceJar, String secret) throws Exception {
        Path requestDirectory = temporary.resolve("requests")
                .resolve("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        Files.createDirectories(requestDirectory);
        Instant now = Instant.now();
        MaintenanceRequest request = new MaintenanceRequest(
                MaintenanceRequest.SCHEMA_VERSION,
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                IoUtil.sha256(secret.getBytes(StandardCharsets.UTF_8)),
                "0".repeat(64),
                now.toString(),
                now.plusSeconds(300).toString(),
                Long.MAX_VALUE,
                MaintenanceRequest.Operation.REPAIR,
                temporary.toString(),
                world.toString(),
                temporary.resolve("jobs").toString(),
                iceJar.toString(),
                null,
                null,
                null,
                null,
                null,
                "1.21.1",
                "21.1.241",
                "Youer",
                MaintenanceRequest.RestartStrategy.NONE,
                List.of(),
                MaintenanceRequest.State.HANDOFF,
                "test handoff"
        );
        request = request.withBindingHmac(request.computeBindingHmac(secret));
        Path requestPath = requestDirectory.resolve(MaintenanceFiles.REQUEST_FILE);
        MaintenanceFiles.writeRequest(requestPath, request);
        return requestPath;
    }
}
