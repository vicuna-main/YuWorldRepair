package dev.yu.worldrepair.worldtool.maintenance;

import dev.yu.worldrepair.worldtool.WorldRepairService;
import dev.yu.worldrepair.worldtool.adapter.LegacyChickenDataAdapter;
import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;
import dev.yu.worldrepair.worldtool.namespace.NamespacePolicy;
import dev.yu.worldrepair.worldtool.namespace.NamespaceRepairService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class MaintenanceWorkerMain {
    public static final String AUTH_ENV = "YUWORLDREPAIR_MAINTENANCE_AUTH";
    private static final long LOCK_RETRY_MILLIS = 250;

    private MaintenanceWorkerMain() {
    }

    public static void main(String[] arguments) {
        int code = run(arguments, System.getenv(AUTH_ENV));
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] arguments, String authorizationSecret) {
        if (arguments.length != 1) {
            System.err.println("Usage: MaintenanceWorkerMain <absolute-request.json>");
            return 2;
        }
        Path requestPath;
        try {
            requestPath = Path.of(arguments[0]).toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            System.err.println("Invalid maintenance request path");
            return 2;
        }
        Path resultPath = requestPath.resolveSibling(MaintenanceFiles.RESULT_FILE);
        MaintenanceRequest request = null;
        try {
            request = MaintenanceFiles.readRequest(requestPath);
            verifyAuthorization(request, authorizationSecret);
            update(requestPath, request = request.withState(
                    MaintenanceRequest.State.WAITING_FOR_STOP,
                    "Waiting for the owning server process and world lock to be released"
            ));
            waitForParentExit(request);
            MaintenanceRequest latest = MaintenanceFiles.readStoredRequest(requestPath);
            verifyAuthorization(latest, authorizationSecret);
            if (latest.state() != MaintenanceRequest.State.WAITING_FOR_STOP) {
                return 0;
            }
            request = latest;
            waitForWorldUnlock(request);
            WorldRepairService service = WorldRepairService.forServerMaintenance(
                    Path.of(request.worldRoot()),
                    Path.of(request.jobsRoot())
            );
            MaintenanceResult result = switch (request.operation()) {
                case REPAIR -> repair(requestPath, request, service);
                case NAMESPACE_REPAIR -> namespaceRepair(requestPath, request);
                case ROLLBACK -> rollback(requestPath, request, service);
            };
            boolean restarted = restart(request);
            if (restarted) {
                result = new MaintenanceResult(
                        result.schemaVersion(),
                        result.requestId(),
                        result.success(),
                        result.state(),
                        result.completedAt(),
                        result.operation(),
                        result.detail(),
                        result.jobPath(),
                        result.rollbackAvailable(),
                        result.metrics(),
                        true
                );
            }
            MaintenanceFiles.writeResult(resultPath, result);
            return result.success() ? 0 : 3;
        } catch (Exception failure) {
            String detail = safeMessage(failure);
            if (request != null) {
                try {
                    MaintenanceRequest stored = MaintenanceFiles.readStoredRequest(requestPath);
                    if (stored.requestId().equals(request.requestId())) {
                        request = stored;
                    }
                } catch (IOException invalidStoredRequest) {
                    failure.addSuppressed(invalidStoredRequest);
                }
                try {
                    update(requestPath, request.withState(MaintenanceRequest.State.FAILED, detail));
                    MaintenanceFiles.writeResult(
                            resultPath,
                            MaintenanceResult.of(
                                    request,
                                    false,
                                    MaintenanceRequest.State.FAILED,
                                    detail,
                                    request.jobPath(),
                                    request.jobPath() != null,
                                    Map.of(),
                                    false
                            )
                    );
                } catch (Exception reportFailure) {
                    failure.addSuppressed(reportFailure);
                }
            }
            System.err.println("YuWorldRepair maintenance worker failed: " + detail);
            return 3;
        }
    }

    private static MaintenanceResult namespaceRepair(
            Path requestPath,
            MaintenanceRequest request
    ) throws IOException {
        Path snapshotPath = Path.of(request.registrySnapshotPath())
                .toAbsolutePath()
                .normalize();
        Path expectedSnapshot = requestPath.resolveSibling(RegistrySnapshot.FILE_NAME)
                .toAbsolutePath()
                .normalize();
        if (!snapshotPath.equals(expectedSnapshot)) {
            throw new IOException("Signed registry snapshot is outside its request directory");
        }
        RegistrySnapshot snapshot = RegistrySnapshot.read(
                snapshotPath,
                request.registrySnapshotSha256()
        );
        NamespacePolicy policy = new NamespacePolicy(
                request.namespace(),
                request.namespaceMode(),
                snapshot
        );
        update(requestPath, request.withState(
                MaintenanceRequest.State.SCANNING,
                "Scanning exact 1.21.1 namespace-owned region data read-only"
        ));
        NamespaceRepairService service = NamespaceRepairService.forServerMaintenance(
                Path.of(request.worldRoot()),
                Path.of(request.jobsRoot())
        );
        NamespaceRepairService.Result repaired = service.repair(
                policy,
                request.registrySnapshotSha256()
        );
        MaintenanceRequest completed = request.withJobPath(repaired.jobPath()).withState(
                repaired.success()
                        ? MaintenanceRequest.State.COMPLETED
                        : MaintenanceRequest.State.FAILED,
                repaired.detail()
        );
        update(requestPath, completed);
        return MaintenanceResult.of(
                completed,
                repaired.success(),
                completed.state(),
                repaired.detail(),
                repaired.jobPath(),
                repaired.rollbackAvailable(),
                repaired.metrics(),
                false
        );
    }

    private static MaintenanceResult repair(
            Path requestPath,
            MaintenanceRequest request,
            WorldRepairService service
    ) throws IOException {
        Path iceJar = Path.of(request.iceAndFireJar());
        String iceHash = IoUtil.sha256(iceJar);
        if (!LegacyChickenDataAdapter.VERIFIED_ICE_AND_FIRE_SHA256.equals(iceHash)) {
            throw new IOException(
                    "Ice and Fire jar hash is not approved for this exact adapter: " + iceHash
            );
        }
        update(requestPath, request.withState(
                MaintenanceRequest.State.SCANNING,
                "Scanning entity region files read-only"
        ));
        WorldRepairService.CommandResult scan = service.scan(
                Path.of(request.worldRoot()),
                Path.of(request.jobsRoot()),
                iceJar,
                new WorldRepairService.RuntimeMetadata(
                        request.minecraftVersion(),
                        request.neoForgeVersion(),
                        request.youerVersion()
                )
        );
        int targets = metric(scan.metrics(), "targets");
        int blocked = metric(scan.metrics(), "blocked");
        if (targets == 0) {
            update(requestPath, request.withState(
                    MaintenanceRequest.State.COMPLETED,
                    "Scan completed; no exact iceandfire:chicken_data targets were found"
            ));
            return MaintenanceResult.of(
                    request,
                    true,
                    MaintenanceRequest.State.COMPLETED,
                    "No matching legacy keys found; world was not modified",
                    scan.job(),
                    false,
                    scan.metrics(),
                    false
            );
        }
        if (blocked != 0) {
            throw new IOException(
                    "Scan found " + blocked + " blocked targets; no world files were modified"
            );
        }
        Path job = Path.of(scan.job());
        request = request.withJobPath(job.toString());
        update(requestPath, request);
        try {
        update(requestPath, request.withState(
                MaintenanceRequest.State.BACKING_UP,
                "Creating and hashing complete backups of every affected region"
        ));
        WorldRepairService.CommandResult prepared = service.prepare(job);
        update(requestPath, request.withState(
                MaintenanceRequest.State.APPLYING,
                "Applying exact copy-on-write region replacements"
        ));
        service.apply(job, prepared.confirmationToken());
        update(requestPath, request.withState(
                MaintenanceRequest.State.VERIFYING,
                "Verifying post-apply region hashes and zero remaining exact targets"
        ));
        WorldRepairService.CommandResult verified = service.verify(job);
        update(requestPath, request.withState(
                MaintenanceRequest.State.COMPLETED,
                "Repair verified; backups retained for a later controlled rollback"
        ));
        return MaintenanceResult.of(
                request,
                true,
                MaintenanceRequest.State.COMPLETED,
                "Removed and verified " + targets
                        + " exact iceandfire:chicken_data entries; backups retained",
                job.toString(),
                true,
                verified.metrics(),
                false
        );
        } catch (IOException | RuntimeException repairFailure) {
            RecoveryOutcome recovery = attemptAutomaticRollback(service, job, repairFailure);
            if (recovery == RecoveryOutcome.ROLLED_BACK) {
                update(requestPath, request.withState(
                        MaintenanceRequest.State.ROLLED_BACK,
                        "Repair failed; automatic rollback restored and verified all affected regions"
                ));
                return MaintenanceResult.of(
                        request,
                        false,
                        MaintenanceRequest.State.ROLLED_BACK,
                        "Repair failed, but automatic rollback completed and verified: "
                                + safeMessage(repairFailure),
                        job.toString(),
                        false,
                        Map.of(),
                        false
                );
            }
            if (recovery == RecoveryOutcome.ORIGINAL_UNCHANGED) {
                update(requestPath, request.withState(
                        MaintenanceRequest.State.COMPLETED,
                        "Repair failed before any region replacement; original hashes were verified"
                ));
                return MaintenanceResult.of(
                        request,
                        false,
                        MaintenanceRequest.State.COMPLETED,
                        "Repair failed before world replacement; original region data remains verified: "
                                + safeMessage(repairFailure),
                        job.toString(),
                        false,
                        Map.of(),
                        false
                );
            }
            throw new IOException(
                    "Repair failed before a verified automatic rollback was possible: "
                            + safeMessage(repairFailure),
                    repairFailure
            );
        }
    }

    private static MaintenanceResult rollback(
            Path requestPath,
            MaintenanceRequest request,
            WorldRepairService service
    ) throws IOException {
        Path job = Path.of(request.jobPath());
        if (NamespaceRepairService.isNamespaceJob(job)) {
            update(requestPath, request.withState(
                    MaintenanceRequest.State.ROLLING_BACK,
                    "Restoring byte-exact namespace repair backups"
            ));
            NamespaceRepairService namespaceService =
                    NamespaceRepairService.forServerMaintenance(
                            Path.of(request.worldRoot()),
                            Path.of(request.jobsRoot())
                    );
            NamespaceRepairService.Result result = namespaceService.rollback(job);
            update(requestPath, request.withState(
                    MaintenanceRequest.State.ROLLED_BACK,
                    "Namespace rollback restored and verified all affected files"
            ));
            return MaintenanceResult.of(
                    request,
                    true,
                    MaintenanceRequest.State.ROLLED_BACK,
                    result.detail(),
                    job.toString(),
                    false,
                    result.metrics(),
                    false
            );
        }
        update(requestPath, request.withState(
                MaintenanceRequest.State.ROLLING_BACK,
                "Validating current hashes and restoring retained region backups"
        ));
        WorldRepairService.CommandResult authorization = service.authorizeRollback(job);
        WorldRepairService.CommandResult rolledBack =
                service.rollback(job, authorization.confirmationToken());
        WorldRepairService.CommandResult verified = service.verifyRollback(job);
        update(requestPath, request.withState(
                MaintenanceRequest.State.ROLLED_BACK,
                "Rollback restored and byte-verified every affected region"
        ));
        return MaintenanceResult.of(
                request,
                true,
                MaintenanceRequest.State.ROLLED_BACK,
                rolledBack.detail(),
                job.toString(),
                false,
                verified.metrics(),
                false
        );
    }

    private static void waitForParentExit(MaintenanceRequest request) throws IOException {
        while (ProcessHandle.of(request.parentPid()).filter(ProcessHandle::isAlive).isPresent()) {
            ensureNotExpired(request);
            sleep();
        }
    }

    private static void waitForWorldUnlock(MaintenanceRequest request) throws IOException {
        Path world = Path.of(request.worldRoot());
        IOException last = null;
        while (true) {
            ensureNotExpired(request);
            try {
                WorldAccessPolicy.requireExactUnlockedWorld(world, world);
                return;
            } catch (IOException locked) {
                last = locked;
                sleep();
            }
            if (Instant.now().plusMillis(LOCK_RETRY_MILLIS).isAfter(Instant.parse(request.expiresAt()))) {
                throw new IOException("World lock was not released before request expiry", last);
            }
        }
    }

    private static void ensureNotExpired(MaintenanceRequest request) throws IOException {
        if (Instant.now().isAfter(Instant.parse(request.expiresAt()))) {
            throw new IOException("Maintenance request expired while waiting for shutdown");
        }
    }

    private static void sleep() throws IOException {
        try {
            TimeUnit.MILLISECONDS.sleep(LOCK_RETRY_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Maintenance worker interrupted", interrupted);
        }
    }

    private static boolean restart(MaintenanceRequest request) throws IOException {
        if (request.restartStrategy() != MaintenanceRequest.RestartStrategy.SELF) {
            return false;
        }
        new ProcessBuilder(request.restartCommand())
                .directory(Path.of(request.serverRoot()).toFile())
                .inheritIO()
                .start();
        return true;
    }

    private static RecoveryOutcome attemptAutomaticRollback(
            WorldRepairService service,
            Path job,
            Throwable repairFailure
    ) {
        try {
            WorldRepairService.CommandResult authorization;
            try {
                authorization = service.authorizeFailedVerificationRollback(
                        job,
                        safeMessage(repairFailure)
                );
            } catch (IOException notAppliedState) {
                WorldRepairService.CommandResult status = service.status(job);
                Object state = status.metrics().get("state");
                if ("SCANNED".equals(String.valueOf(state))) {
                    return RecoveryOutcome.ORIGINAL_UNCHANGED;
                }
                authorization = service.verify(job);
            }
            String token = authorization.confirmationToken();
            if ("resume-ready".equals(authorization.action())) {
                return RecoveryOutcome.ORIGINAL_UNCHANGED;
            }
            if (token == null) {
                return RecoveryOutcome.UNSAFE;
            }
            service.rollback(job, token);
            service.verifyRollback(job);
            return RecoveryOutcome.ROLLED_BACK;
        } catch (IOException | RuntimeException rollbackFailure) {
            repairFailure.addSuppressed(rollbackFailure);
            return RecoveryOutcome.UNSAFE;
        }
    }

    private static void verifyAuthorization(
            MaintenanceRequest request,
            String authorizationSecret
    ) throws IOException {
        if (authorizationSecret == null || authorizationSecret.length() < 32) {
            throw new IOException("Maintenance worker authorization is missing");
        }
        byte[] expected = HexFormat.of().parseHex(request.authorizationSha256());
        byte[] actual = HexFormat.of().parseHex(
                IoUtil.sha256(authorizationSecret.getBytes(StandardCharsets.UTF_8))
        );
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IOException("Maintenance worker authorization does not match the request");
        }
        byte[] expectedBinding = HexFormat.of().parseHex(request.bindingHmacSha256());
        byte[] actualBinding = HexFormat.of().parseHex(
                request.computeBindingHmac(authorizationSecret)
        );
        if (!MessageDigest.isEqual(expectedBinding, actualBinding)) {
            throw new IOException("Maintenance request fields do not match their HMAC binding");
        }
    }

    private static int metric(Map<String, ?> metrics, String key) throws IOException {
        Object value = metrics.get(key);
        if (!(value instanceof Number number)) {
            throw new IOException("Repair engine omitted metric: " + key);
        }
        return number.intValue();
    }

    private static void update(Path requestPath, MaintenanceRequest request) throws IOException {
        MaintenanceFiles.writeRequest(requestPath, request);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        String oneLine = message.replace('\r', ' ').replace('\n', ' ');
        return oneLine.length() <= 1_024 ? oneLine : oneLine.substring(0, 1_024);
    }

    private enum RecoveryOutcome {
        ROLLED_BACK,
        ORIGINAL_UNCHANGED,
        UNSAFE
    }
}
