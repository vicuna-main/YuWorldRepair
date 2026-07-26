package dev.yu.worldrepair.worldtool.maintenance;

import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.nbt.Nbt;
import dev.yu.worldrepair.worldtool.nbt.NbtFile;
import dev.yu.worldrepair.worldtool.namespace.NamespacePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(MaintenanceRequest.State.COMPLETED, result.state());
        assertTrue(result.detail().contains("original world remains unmodified"));
        MaintenanceHandoff handoff = MaintenanceFiles.readHandoff(
                requestPath.resolveSibling(MaintenanceFiles.HANDOFF_FILE)
        );
        assertEquals(MaintenanceRequest.State.COMPLETED, handoff.state());
    }

    @Test
    void missingAuthorizationCannotRewriteRequestOrCreateResult() throws Exception {
        Path world = temporary.resolve("world");
        Files.createDirectories(world);
        Files.write(world.resolve("level.dat"), new byte[]{10, 0, 0, 0});
        Path iceJar = temporary.resolve("iceandfire.jar");
        Files.write(iceJar, new byte[]{1, 2, 3});
        String secret = "0123456789abcdef0123456789abcdef";
        Path requestPath = writeRequest(world, iceJar, secret);
        String before = Files.readString(requestPath, StandardCharsets.UTF_8);

        int exit = MaintenanceWorkerMain.run(
                new String[]{requestPath.toString()},
                null
        );

        assertEquals(3, exit);
        assertEquals(before, Files.readString(requestPath, StandardCharsets.UTF_8));
        assertFalse(Files.exists(
                requestPath.resolveSibling(MaintenanceFiles.RESULT_FILE)
        ));
        assertFalse(Files.exists(
                requestPath.resolveSibling(MaintenanceFiles.HANDOFF_FILE)
        ));
        assertTrue(Files.isRegularFile(world.resolve("level.dat")));
    }

    @Test
    void addingAnUnsignedLoadedWorldRootInvalidatesTheRequestHmac() throws Exception {
        Path world = temporary.resolve("signed-world");
        Files.createDirectories(world);
        Files.write(world.resolve("level.dat"), new byte[]{10, 0, 0, 0});
        Path secondWorld = temporary.resolve("unsigned-world");
        Files.createDirectories(secondWorld);
        Files.write(secondWorld.resolve("level.dat"), new byte[]{10, 0, 0, 0});
        Path iceJar = temporary.resolve("signed-iceandfire.jar");
        Files.write(iceJar, new byte[]{1, 2, 3});
        String secret = "0123456789abcdef0123456789abcdef";
        Path requestPath = writeRequest(world, iceJar, secret);
        MaintenanceRequest signed = MaintenanceFiles.readStoredRequest(requestPath);
        MaintenanceRequest tampered = signed.withWorldRoots(List.of(
                world.toString(),
                secondWorld.toString()
        ));
        MaintenanceFiles.writeStoredRequest(requestPath, tampered);

        assertEquals(
                3,
                MaintenanceWorkerMain.run(
                        new String[]{requestPath.toString()},
                        secret
                )
        );
        assertFalse(Files.exists(
                requestPath.resolveSibling(MaintenanceFiles.RESULT_FILE)
        ));
        assertFalse(Files.exists(
                requestPath.resolveSibling(MaintenanceFiles.HANDOFF_FILE)
        ));
    }

    @Test
    void panelRequestWithoutExecutableLauncherHandshakeFailsClosed() throws Exception {
        Path world = temporary.resolve("world");
        Files.createDirectories(world);
        Files.write(world.resolve("level.dat"), new byte[]{10, 0, 0, 0});
        Path iceJar = temporary.resolve("iceandfire.jar");
        Files.write(iceJar, new byte[]{1, 2, 3});
        String secret = "0123456789abcdef0123456789abcdef";
        Path requestPath = writeRequest(
                world,
                iceJar,
                secret,
                MaintenanceRequest.RestartStrategy.PANEL
        );
        String before = Files.readString(requestPath, StandardCharsets.UTF_8);

        int exit = MaintenanceWorkerMain.run(
                new String[]{requestPath.toString()},
                secret
        );

        assertEquals(3, exit);
        assertEquals(before, Files.readString(requestPath, StandardCharsets.UTF_8));
        assertFalse(Files.exists(
                requestPath.resolveSibling(MaintenanceFiles.RESULT_FILE)
        ));
        assertFalse(Files.exists(
                requestPath.resolveSibling(MaintenanceFiles.HANDOFF_FILE)
        ));
    }

    @Test
    void namespaceMaintenanceRepairsAndRollsBackEverySignedLoadedWorld()
            throws Exception {
        Path server = Files.createDirectory(temporary.resolve("multi-server"));
        Path main = createWorld(server.resolve("world"));
        Path playerWorld = createWorld(
                server.resolve("playerworld").resolve("Vicuna")
        );
        Path mainPlayer = writeIcePlayer(main, "10000000-0000-0000-0000-000000000001");
        Path mvPlayer = writeIcePlayer(
                playerWorld,
                "20000000-0000-0000-0000-000000000002"
        );
        String secret = "abcdef0123456789abcdef0123456789";
        Path repairRequest = writeNamespaceRequest(
                server,
                main,
                List.of(main, playerWorld),
                null,
                MaintenanceRequest.Operation.NAMESPACE_REPAIR,
                secret,
                "11111111-2222-3333-4444-555555555555"
        );

        assertEquals(
                0,
                MaintenanceWorkerMain.run(
                        new String[]{repairRequest.toString()},
                        secret
                )
        );
        assertFalse(hasIceAttachment(mainPlayer));
        assertFalse(hasIceAttachment(mvPlayer));
        MaintenanceResult repaired = MaintenanceFiles.readResult(
                repairRequest.resolveSibling(MaintenanceFiles.RESULT_FILE)
        );
        assertTrue(repaired.rollbackAvailable());
        assertEquals(2, ((Number) repaired.metrics().get("worlds")).intValue());
        Path groupPath = Path.of(repaired.jobPath());
        assertEquals(
                MaintenanceJobGroup.State.VERIFIED,
                MaintenanceFiles.readJobGroup(groupPath).state()
        );

        Path rollbackRequest = writeNamespaceRequest(
                server,
                main,
                List.of(main, playerWorld),
                groupPath,
                MaintenanceRequest.Operation.ROLLBACK,
                secret,
                "66666666-7777-8888-9999-aaaaaaaaaaaa"
        );
        assertEquals(
                0,
                MaintenanceWorkerMain.run(
                        new String[]{rollbackRequest.toString()},
                        secret
                )
        );
        assertTrue(hasIceAttachment(mainPlayer));
        assertTrue(hasIceAttachment(mvPlayer));
        assertEquals(
                MaintenanceJobGroup.State.ROLLED_BACK,
                MaintenanceFiles.readJobGroup(groupPath).state()
        );
    }

    private Path writeRequest(Path world, Path iceJar, String secret) throws Exception {
        return writeRequest(
                world,
                iceJar,
                secret,
                MaintenanceRequest.RestartStrategy.NONE
        );
    }

    private Path writeRequest(
            Path world,
            Path iceJar,
            String secret,
            MaintenanceRequest.RestartStrategy restartStrategy
    ) throws Exception {
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
                restartStrategy,
                List.of(),
                MaintenanceRequest.State.HANDOFF,
                "test handoff"
        );
        request = request.withBindingHmac(request.computeBindingHmac(secret));
        Path requestPath = requestDirectory.resolve(MaintenanceFiles.REQUEST_FILE);
        MaintenanceFiles.writeRequest(requestPath, request);
        return requestPath;
    }

    private Path writeNamespaceRequest(
            Path server,
            Path main,
            List<Path> worlds,
            Path groupPath,
            MaintenanceRequest.Operation operation,
            String secret,
            String requestId
    ) throws Exception {
        Path requestDirectory = server.resolve("yuworldrepair-maintenance")
                .resolve("requests")
                .resolve(requestId);
        Files.createDirectories(requestDirectory);
        Path jobs = server.resolve("yuworldrepair-maintenance").resolve("jobs");
        Instant now = Instant.now();
        RegistrySnapshot snapshot = null;
        Path snapshotPath = null;
        String snapshotHash = null;
        if (operation == MaintenanceRequest.Operation.NAMESPACE_REPAIR) {
            snapshot = new RegistrySnapshot(
                    RegistrySnapshot.SCHEMA_VERSION,
                    now.toString(),
                    "1.21.1",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of("minecraft:overworld")
            );
            snapshotPath = requestDirectory.resolve(RegistrySnapshot.FILE_NAME);
            RegistrySnapshot.write(snapshotPath, snapshot);
            snapshotHash = IoUtil.sha256(snapshotPath);
        }
        MaintenanceRequest request = new MaintenanceRequest(
                MaintenanceRequest.SCHEMA_VERSION,
                requestId,
                IoUtil.sha256(secret.getBytes(StandardCharsets.UTF_8)),
                "0".repeat(64),
                now.toString(),
                now.plusSeconds(300).toString(),
                Long.MAX_VALUE,
                operation,
                server.toString(),
                main.toString(),
                worlds.stream().map(Path::toString).toList(),
                jobs.toString(),
                null,
                groupPath == null ? null : groupPath.toString(),
                operation == MaintenanceRequest.Operation.NAMESPACE_REPAIR
                        ? "iceandfire"
                        : null,
                operation == MaintenanceRequest.Operation.NAMESPACE_REPAIR
                        ? NamespacePolicy.Mode.PREPARE_REMOVE
                        : null,
                snapshotPath == null ? null : snapshotPath.toString(),
                snapshotHash,
                "1.21.1",
                "21.1.241",
                "Youer",
                MaintenanceRequest.RestartStrategy.NONE,
                List.of(),
                MaintenanceRequest.State.HANDOFF,
                "test multi-world handoff"
        );
        request = request.withBindingHmac(request.computeBindingHmac(secret));
        Path requestPath = requestDirectory.resolve(MaintenanceFiles.REQUEST_FILE);
        MaintenanceFiles.writeRequest(requestPath, request);
        return requestPath;
    }

    private static Path createWorld(Path path) throws Exception {
        Files.createDirectories(path);
        Files.write(path.resolve("level.dat"), new byte[]{10, 0, 0, 0});
        return path.toAbsolutePath().normalize();
    }

    private static Path writeIcePlayer(Path world, String uuid) throws Exception {
        Nbt.CompoundTag root = new Nbt.CompoundTag();
        root.put("DataVersion", new Nbt.IntTag(3_955));
        Nbt.CompoundTag attachments = new Nbt.CompoundTag();
        attachments.put("iceandfire:misc_data", new Nbt.IntTag(7));
        root.put("neoforge:attachments", attachments);
        Path path = world.resolve("playerdata").resolve(uuid + ".dat");
        Files.createDirectories(path.getParent());
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(path))) {
            Nbt.writeRoot(new Nbt.Root("", root), output);
        }
        return path;
    }

    private static boolean hasIceAttachment(Path path) throws Exception {
        Nbt.CompoundTag root = (Nbt.CompoundTag)
                NbtFile.readGzip(path, Nbt.Limits.conservative()).tag();
        Nbt.CompoundTag attachments = root.getCompound("neoforge:attachments");
        return attachments != null && attachments.contains("iceandfire:misc_data");
    }
}
