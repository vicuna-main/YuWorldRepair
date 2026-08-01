package dev.yu.worldrepair.worldtool.maintenance;

import dev.yu.worldrepair.worldtool.WorldRepairService;
import dev.yu.worldrepair.worldtool.adapter.LegacyChickenDataAdapter;
import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;
import dev.yu.worldrepair.worldtool.namespace.NamespacePolicy;
import dev.yu.worldrepair.worldtool.namespace.NamespaceRepairService;
import dev.yu.worldrepair.worldtool.namespace.NamespaceWorldScanner;
import dev.yu.worldrepair.worldtool.namespace.OrphanItemIndex;
import dev.yu.worldrepair.worldtool.nbt.Nbt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        boolean authorized = false;
        try {
            request = MaintenanceFiles.readRequest(requestPath);
            verifyAuthorization(request, authorizationSecret);
            verifySupervisor(request);
            if (request.state() != MaintenanceRequest.State.HANDOFF) {
                throw new IOException("Maintenance worker requires an explicit HANDOFF state");
            }
            authorized = true;
            update(requestPath, request = request.withState(
                    MaintenanceRequest.State.WAITING_FOR_STOP,
                    "Waiting for the owning server process and world lock to be released"
            ));
            waitForParentExit(request);
            MaintenanceRequest latest = MaintenanceFiles.readStoredRequest(requestPath);
            verifyAuthorization(latest, authorizationSecret);
            if (Files.isRegularFile(resultPath, LinkOption.NOFOLLOW_LINKS)) {
                MaintenanceResult existing = MaintenanceFiles.readResult(resultPath);
                return existing.success() ? 0 : 3;
            }
            if (latest.state() != MaintenanceRequest.State.WAITING_FOR_STOP) {
                return 0;
            }
            request = latest;
            waitForWorldUnlock(request);
            MaintenanceResult result;
            try (WorldAccessPolicy.HeldWorldLocks ignored =
                         WorldAccessPolicy.acquireExactWorldLocks(request.worldRoots())) {
                WorldRepairService service = WorldRepairService.forServerMaintenance(
                        Path.of(request.worldRoot()),
                        Path.of(request.jobsRoot())
                );
                result = switch (request.operation()) {
                    case REPAIR -> repairWorldSet(requestPath, request);
                    case NAMESPACE_REPAIR -> namespaceRepair(requestPath, request);
                    case ROLLBACK -> rollback(requestPath, request, service);
                };
            }
            boolean restartPlanned =
                    request.restartStrategy() == MaintenanceRequest.RestartStrategy.SELF;
            if (restartPlanned) {
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
            MaintenanceResult persisted =
                    MaintenanceFiles.writeResultIfAbsent(resultPath, result);
            if (restartPlanned) {
                restart(request);
            }
            return persisted.success() ? 0 : 3;
        } catch (Exception failure) {
            String detail = safeMessage(failure);
            if (request != null && authorized) {
                try {
                    MaintenanceRequest stored = MaintenanceFiles.readStoredRequest(requestPath);
                    if (stored.requestId().equals(request.requestId())) {
                        request = stored;
                    }
                } catch (IOException invalidStoredRequest) {
                    failure.addSuppressed(invalidStoredRequest);
                }
                boolean originalUnchanged = isProvablyBeforeWorldReplacement(request);
                MaintenanceRequest.State terminalState = originalUnchanged
                        ? MaintenanceRequest.State.COMPLETED
                        : MaintenanceRequest.State.FAILED;
                String terminalDetail = originalUnchanged
                        ? "Worker failed before any world replacement; original world remains "
                        + "unmodified: " + detail
                        : detail;
                MaintenanceRequest failed = request.withState(
                        terminalState,
                        terminalDetail
                );
                try {
                    update(requestPath, failed);
                } catch (Exception stateFailure) {
                    failure.addSuppressed(stateFailure);
                }
                try {
                    MaintenanceFiles.writeResultIfAbsent(
                            resultPath,
                            MaintenanceResult.of(
                                    failed,
                                    false,
                                    terminalState,
                                    terminalDetail,
                                    failed.jobPath(),
                                    !originalUnchanged && failed.jobPath() != null,
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

    private static MaintenanceResult repairWorldSet(
            Path requestPath,
            MaintenanceRequest request
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
                "Scanning exact chicken attachment data in every signed loaded world"
        ));
        Path groupDirectory = requestPath.getParent();
        String now = Instant.now().toString();
        MaintenanceJobGroup group = new MaintenanceJobGroup(
                MaintenanceJobGroup.SCHEMA_VERSION,
                request.requestId(),
                now,
                now,
                MaintenanceJobGroup.State.PREPARING,
                List.of(),
                "Scanning every loaded world before any replacement"
        );
        MaintenanceFiles.writeJobGroup(groupDirectory, group);
        request = request.withJobPath(groupDirectory.toString());
        update(requestPath, request);

        ArrayList<MaintenanceJobGroup.Entry> entries = new ArrayList<>();
        int totalTargets = 0;
        for (String root : request.worldRoots()) {
            WorldRepairService service = WorldRepairService.forServerMaintenance(
                    Path.of(root),
                    Path.of(request.jobsRoot())
            );
            WorldRepairService.CommandResult scan = service.scan(
                    Path.of(root),
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
            totalTargets = Math.addExact(totalTargets, targets);
            entries.add(new MaintenanceJobGroup.Entry(
                    MaintenanceJobGroup.Kind.LEGACY_ICEANDFIRE_CHICKEN_DATA,
                    root,
                    scan.job(),
                    targets,
                    false,
                    false
            ));
            if (blocked != 0) {
                group = group.withEntries(
                        entries,
                        MaintenanceJobGroup.State.FAILED,
                        "A loaded world contains blocked exact targets; no files were replaced"
                );
                MaintenanceFiles.writeJobGroup(groupDirectory, group);
                MaintenanceRequest failed = request.withState(
                        MaintenanceRequest.State.FAILED,
                        group.detail()
                );
                update(requestPath, failed);
                return MaintenanceResult.of(
                        failed,
                        false,
                        failed.state(),
                        group.detail(),
                        groupDirectory.toString(),
                        false,
                        Map.of(
                                "worlds", request.worldRoots().size(),
                                "targets", totalTargets,
                                "blocked", blocked
                        ),
                        false
                );
            }
        }

        LinkedHashMap<String, String> applyTokens = new LinkedHashMap<>();
        update(requestPath, request.withState(
                MaintenanceRequest.State.BACKING_UP,
                "Backing up all affected loaded worlds before the first apply"
        ));
        for (MaintenanceJobGroup.Entry entry : entries) {
            if (entry.targets() == 0) {
                continue;
            }
            WorldRepairService service = WorldRepairService.forServerMaintenance(
                    Path.of(entry.worldRoot()),
                    Path.of(request.jobsRoot())
            );
            WorldRepairService.CommandResult prepared =
                    service.prepare(Path.of(entry.jobPath()));
            applyTokens.put(entry.jobPath(), prepared.confirmationToken());
        }
        group = group.withEntries(
                entries,
                MaintenanceJobGroup.State.PREPARED,
                "Every affected loaded world has a byte-verified backup"
        );
        MaintenanceFiles.writeJobGroup(groupDirectory, group);

        update(requestPath, request.withState(
                MaintenanceRequest.State.APPLYING,
                "Applying exact repairs across all loaded worlds"
        ));
        MaintenanceJobGroup.Entry applying = null;
        try {
            for (int index = 0; index < entries.size(); index++) {
                MaintenanceJobGroup.Entry entry = entries.get(index);
                if (entry.targets() == 0) {
                    continue;
                }
                applying = entry;
                WorldRepairService service = WorldRepairService.forServerMaintenance(
                        Path.of(entry.worldRoot()),
                        Path.of(request.jobsRoot())
                );
                service.apply(
                        Path.of(entry.jobPath()),
                        applyTokens.get(entry.jobPath())
                );
                service.verify(Path.of(entry.jobPath()));
                entries.set(index, entry.applied(true, true));
                applying = null;
                group = group.withEntries(
                        entries,
                        MaintenanceJobGroup.State.APPLYING,
                        "Verified loaded world " + (index + 1) + "/" + entries.size()
                );
                MaintenanceFiles.writeJobGroup(groupDirectory, group);
            }
        } catch (IOException | RuntimeException applyFailure) {
            if (applying != null) {
                WorldRepairService current = WorldRepairService.forServerMaintenance(
                        Path.of(applying.worldRoot()),
                        Path.of(request.jobsRoot())
                );
                RecoveryOutcome recovery = attemptAutomaticRollback(
                        current,
                        Path.of(applying.jobPath()),
                        applyFailure
                );
                if (recovery == RecoveryOutcome.UNSAFE) {
                    group = group.withEntries(
                            entries,
                            MaintenanceJobGroup.State.FAILED,
                            "Current loaded world could not be restored automatically"
                    );
                    MaintenanceFiles.writeJobGroup(groupDirectory, group);
                    throw applyFailure;
                }
            }
            IOException rollbackFailure = rollbackGroupEntries(
                    entries,
                    request.jobsRoot()
            );
            if (rollbackFailure != null) {
                applyFailure.addSuppressed(rollbackFailure);
                group = group.withEntries(
                        entries,
                        MaintenanceJobGroup.State.FAILED,
                        "Multi-world exact repair rollback requires operator attention"
                );
                MaintenanceFiles.writeJobGroup(groupDirectory, group);
                throw applyFailure;
            }
            group = group.withEntries(
                    entries,
                    MaintenanceJobGroup.State.ROLLED_BACK,
                    "Exact repair failed; every replaced world was byte-restored"
            );
            MaintenanceFiles.writeJobGroup(groupDirectory, group);
            MaintenanceRequest rolledBack = request.withState(
                    MaintenanceRequest.State.ROLLED_BACK,
                    group.detail()
            );
            update(requestPath, rolledBack);
            return MaintenanceResult.of(
                    rolledBack,
                    false,
                    rolledBack.state(),
                    group.detail() + ": " + safeMessage(applyFailure),
                    groupDirectory.toString(),
                    false,
                    Map.of("worlds", entries.size(), "targets", totalTargets),
                    false
            );
        }

        boolean rollbackAvailable = entries.stream()
                .anyMatch(MaintenanceJobGroup.Entry::rollbackAvailable);
        group = group.withEntries(
                entries,
                MaintenanceJobGroup.State.VERIFIED,
                "Every loaded world was scanned and all exact repairs were verified"
        );
        MaintenanceFiles.writeJobGroup(groupDirectory, group);
        MaintenanceRequest completed = request.withState(
                MaintenanceRequest.State.COMPLETED,
                group.detail()
        );
        update(requestPath, completed);
        return MaintenanceResult.of(
                completed,
                true,
                completed.state(),
                totalTargets == 0
                        ? "No exact iceandfire:chicken_data targets were found in any loaded world"
                        : "Removed and verified " + totalTargets
                        + " exact targets across " + entries.size() + " loaded worlds",
                groupDirectory.toString(),
                rollbackAvailable,
                Map.of("worlds", entries.size(), "targets", totalTargets),
                false
        );
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
        OrphanItemIndex sharedItemIndex = OrphanItemIndex.load(
                Path.of(request.worldRoot()),
                policy,
                Nbt.Limits.conservative()
        );
        update(requestPath, request.withState(
                MaintenanceRequest.State.SCANNING,
                "Scanning every signed loaded world read-only"
        ));
        Path groupDirectory = requestPath.getParent();
        String now = Instant.now().toString();
        MaintenanceJobGroup group = new MaintenanceJobGroup(
                MaintenanceJobGroup.SCHEMA_VERSION,
                request.requestId(),
                now,
                now,
                MaintenanceJobGroup.State.PREPARING,
                List.of(),
                "Preparing all loaded worlds before any replacement"
        );
        MaintenanceFiles.writeJobGroup(groupDirectory, group);
        request = request.withJobPath(groupDirectory.toString());
        update(requestPath, request);

        ArrayList<MaintenanceJobGroup.Entry> entries = new ArrayList<>();
        int totalTargets = 0;
        int totalFiles = 0;
        int totalCoverageGaps = 0;
        int totalDeferredTargets = 0;
        long totalRegionBytes = 0;
        boolean partialRegionScope = !request.regionExcludedWorldRoots().isEmpty();
        boolean allowQioTypeCleanup = !partialRegionScope;
        int signedWorldCount = request.worldRoots().size();
        LinkedHashMap<String, Long> targetsByNamespace = new LinkedHashMap<>();
        LinkedHashMap<String, Long> targetsByStore = new LinkedHashMap<>();
        LinkedHashMap<String, Long> amountByNamespace = new LinkedHashMap<>();
        LinkedHashMap<String, Long> deferredByNamespace = new LinkedHashMap<>();
        LinkedHashMap<String, Long> deferredByStore = new LinkedHashMap<>();
        LinkedHashMap<String, Long> deferredAmountByNamespace = new LinkedHashMap<>();
        for (String root : request.worldRoots()) {
            int worldOrdinal = entries.size() + 1;
            NamespaceRepairService service = NamespaceRepairService.forServerMaintenance(
                    Path.of(root),
                    Path.of(request.jobsRoot())
            );
            NamespaceWorldScanner.Options scanOptions = namespaceScanOptions(
                    request,
                    root,
                    allowQioTypeCleanup
            ).withProgressListener(progress -> {
                LinkedHashMap<String, Object> report = new LinkedHashMap<>();
                report.put("worldRoot", root);
                report.put("worldOrdinal", worldOrdinal);
                report.put("worldsTotal", signedWorldCount);
                report.put("regionFilesCompleted", progress.regionFilesCompleted());
                report.put("regionFilesTotal", progress.regionFilesTotal());
                report.put("regionBytesCompleted", progress.regionBytesCompleted());
                report.put("regionBytesTotal", progress.regionBytesTotal());
                report.put("chunksScanned", progress.chunksScanned());
                report.put("targetsFound", progress.targetsFound());
                report.put("coverageGaps", progress.coverageGaps());
                report.put("elapsedMillis", progress.elapsedMillis());
                report.put("updatedAt", Instant.now().toString());
                MaintenanceFiles.writeScanProgress(groupDirectory, report);
            });
            NamespaceRepairService.Result prepared = service.prepare(
                    policy,
                    request.registrySnapshotSha256(),
                    sharedItemIndex,
                    scanOptions
            );
            int targets = metric(prepared.metrics(), "targets");
            totalTargets = Math.addExact(totalTargets, targets);
            totalFiles = Math.addExact(
                    totalFiles,
                    optionalMetric(prepared.metrics(), "files")
            );
            totalCoverageGaps = Math.addExact(
                    totalCoverageGaps,
                    optionalMetric(prepared.metrics(), "coverageGaps")
            );
            totalDeferredTargets = Math.addExact(
                    totalDeferredTargets,
                    optionalMetric(prepared.metrics(), "deferredTargets")
            );
            totalRegionBytes = Math.addExact(
                    totalRegionBytes,
                    optionalLongMetric(prepared.metrics(), "regionBytes")
            );
            mergeMetricMap(
                    targetsByNamespace,
                    prepared.metrics().get("byNamespace")
            );
            mergeMetricMap(targetsByStore, prepared.metrics().get("byStore"));
            mergeMetricMap(
                    amountByNamespace,
                    prepared.metrics().get("amountByNamespace")
            );
            mergeMetricMap(
                    deferredByNamespace,
                    prepared.metrics().get("deferredByNamespace")
            );
            mergeMetricMap(
                    deferredByStore,
                    prepared.metrics().get("deferredByStore")
            );
            mergeMetricMap(
                    deferredAmountByNamespace,
                    prepared.metrics().get("deferredAmountByNamespace")
            );
            entries.add(new MaintenanceJobGroup.Entry(
                    MaintenanceJobGroup.Kind.NAMESPACE,
                    root,
                    prepared.jobPath(),
                    targets,
                    false,
                    false
            ));
            group = group.withEntries(
                    entries,
                    prepared.success()
                            ? MaintenanceJobGroup.State.PREPARING
                            : MaintenanceJobGroup.State.FAILED,
                    prepared.detail()
            );
            MaintenanceFiles.writeJobGroup(groupDirectory, group);
            if (!prepared.success()) {
                MaintenanceRequest completed = request.withState(
                        MaintenanceRequest.State.COMPLETED,
                        prepared.detail()
                );
                update(requestPath, completed);
                return MaintenanceResult.of(
                        completed,
                        false,
                        completed.state(),
                        prepared.detail(),
                        groupDirectory.toString(),
                        false,
                        Map.of(
                                "worlds", request.worldRoots().size(),
                                "preparedWorlds", entries.size(),
                                "targets", totalTargets,
                                "coverageGaps", totalCoverageGaps
                        ),
                        false
                );
            }
        }

        group = group.withEntries(
                entries,
                MaintenanceJobGroup.State.PREPARED,
                "Every loaded world was scanned and backed up before apply"
        );
        MaintenanceFiles.writeJobGroup(groupDirectory, group);
        update(requestPath, request.withState(
                MaintenanceRequest.State.APPLYING,
                "Applying prepared repairs across all loaded worlds"
        ));
        int changed = 0;
        try {
            for (int index = 0; index < entries.size(); index++) {
                MaintenanceJobGroup.Entry entry = entries.get(index);
                if (entry.targets() == 0) {
                    continue;
                }
                group = group.withEntries(
                        entries,
                        MaintenanceJobGroup.State.APPLYING,
                        "Applying loaded world " + (index + 1) + "/" + entries.size()
                );
                MaintenanceFiles.writeJobGroup(groupDirectory, group);
                NamespaceRepairService service = NamespaceRepairService.forServerMaintenance(
                        Path.of(entry.worldRoot()),
                        Path.of(request.jobsRoot())
                );
                NamespaceRepairService.Result applied = service.applyPrepared(
                        Path.of(entry.jobPath()),
                        policy,
                        request.registrySnapshotSha256(),
                        sharedItemIndex,
                        namespaceScanOptions(
                                request,
                                entry.worldRoot(),
                                allowQioTypeCleanup
                        )
                );
                changed = Math.addExact(changed, metric(applied.metrics(), "changed"));
                entries.set(index, entry.applied(
                        applied.modified(),
                        applied.rollbackAvailable()
                ));
                MaintenanceFiles.writeJobGroup(
                        groupDirectory,
                        group.withEntries(
                                entries,
                                MaintenanceJobGroup.State.APPLYING,
                                applied.detail()
                        )
                );
            }
        } catch (IOException | RuntimeException applyFailure) {
            IOException rollbackFailure = rollbackGroupEntries(
                    entries,
                    request.jobsRoot()
            );
            if (rollbackFailure != null) {
                applyFailure.addSuppressed(rollbackFailure);
                group = group.withEntries(
                        entries,
                        MaintenanceJobGroup.State.FAILED,
                        "Multi-world apply failed and rollback requires operator attention"
                );
                MaintenanceFiles.writeJobGroup(groupDirectory, group);
                throw applyFailure;
            }
            group = group.withEntries(
                    entries,
                    MaintenanceJobGroup.State.ROLLED_BACK,
                    "Multi-world apply failed; every replaced world was byte-restored"
            );
            MaintenanceFiles.writeJobGroup(groupDirectory, group);
            MaintenanceRequest rolledBack = request.withState(
                    MaintenanceRequest.State.ROLLED_BACK,
                    group.detail()
            );
            update(requestPath, rolledBack);
            return MaintenanceResult.of(
                    rolledBack,
                    false,
                    rolledBack.state(),
                    group.detail() + ": " + safeMessage(applyFailure),
                    groupDirectory.toString(),
                    false,
                    Map.of("worlds", entries.size(), "targets", totalTargets),
                    false
            );
        }

        boolean rollbackAvailable = entries.stream()
                .anyMatch(MaintenanceJobGroup.Entry::rollbackAvailable);
        boolean cleanupComplete = !partialRegionScope
                && totalDeferredTargets == 0
                && totalCoverageGaps == 0;
        group = group.withEntries(
                entries,
                MaintenanceJobGroup.State.VERIFIED,
                partialRegionScope
                        ? "Selected region scope verified; excluded region files were not scanned"
                        + (totalDeferredTargets == 0
                        ? ""
                        : "; " + totalDeferredTargets
                        + " QIO targets were detected but not removed")
                        : policy.isGlobalItemCleanup()
                        ? "All loaded worlds verified with zero remaining orphan item targets"
                        : "All loaded worlds verified with zero remaining namespace targets"
        );
        MaintenanceFiles.writeJobGroup(groupDirectory, group);
        MaintenanceRequest completed = request.withState(
                MaintenanceRequest.State.COMPLETED,
                group.detail()
        );
        update(requestPath, completed);
        String completionDetail;
        if (partialRegionScope) {
            completionDetail = totalTargets == 0
                    ? "No removable targets were found in the selected scope; "
                    + request.regionExcludedWorldRoots().size()
                    + " world region roots were not scanned"
                    : "Removed and verified " + totalTargets
                    + " targets in the selected scope; "
                    + request.regionExcludedWorldRoots().size()
                    + " world region roots were not scanned";
            if (totalDeferredTargets > 0) {
                completionDetail += "; detected " + totalDeferredTargets
                        + " additional QIO targets but intentionally did not remove them";
            }
            completionDetail += "; cleanupComplete=false";
        } else if (totalTargets == 0) {
            completionDetail = policy.isGlobalItemCleanup()
                    ? "No orphan mod item targets were found in any loaded world"
                    : "No matching namespace targets were found in any loaded world";
        } else {
            completionDetail = policy.isGlobalItemCleanup()
                    ? "Removed and verified " + totalTargets
                    + " orphan mod item entries across " + entries.size()
                    + " loaded worlds"
                    : "Repaired and verified " + totalTargets
                    + " namespace targets across " + entries.size()
                    + " loaded worlds";
        }
        return MaintenanceResult.of(
                completed,
                true,
                completed.state(),
                completionDetail,
                groupDirectory.toString(),
                rollbackAvailable,
                namespaceMetrics(
                        entries.size(),
                        totalTargets,
                        changed,
                        totalFiles,
                        totalCoverageGaps,
                        targetsByNamespace,
                        targetsByStore,
                        amountByNamespace,
                        deferredByNamespace,
                        deferredByStore,
                        deferredAmountByNamespace,
                        policy.isGlobalItemCleanup(),
                        request.regionExcludedWorldRoots().size(),
                        totalDeferredTargets,
                        totalRegionBytes,
                        request.scanWorkers(),
                        !partialRegionScope,
                        cleanupComplete
                ),
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

    private static IOException rollbackGroupEntries(
            List<MaintenanceJobGroup.Entry> entries,
            String jobsRoot
    ) {
        IOException combined = null;
        for (int index = entries.size() - 1; index >= 0; index--) {
            MaintenanceJobGroup.Entry entry = entries.get(index);
            if (!entry.rollbackAvailable()) {
                continue;
            }
            try {
                if (entry.kind() == MaintenanceJobGroup.Kind.NAMESPACE) {
                    NamespaceRepairService.forServerMaintenance(
                            Path.of(entry.worldRoot()),
                            Path.of(jobsRoot)
                    ).rollback(Path.of(entry.jobPath()));
                } else {
                    WorldRepairService service = WorldRepairService.forServerMaintenance(
                            Path.of(entry.worldRoot()),
                            Path.of(jobsRoot)
                    );
                    Path job = Path.of(entry.jobPath());
                    WorldRepairService.CommandResult authorization =
                            service.authorizeRollback(job);
                    service.rollback(job, authorization.confirmationToken());
                    service.verifyRollback(job);
                }
                entries.set(index, entry.applied(false, false));
            } catch (IOException failure) {
                if (combined == null) {
                    combined = new IOException(
                            "One or more loaded worlds could not be rolled back"
                    );
                }
                combined.addSuppressed(failure);
            }
        }
        return combined;
    }

    private static MaintenanceResult rollback(
            Path requestPath,
            MaintenanceRequest request,
            WorldRepairService service
    ) throws IOException {
        Path job = Path.of(request.jobPath());
        if (MaintenanceFiles.isJobGroup(job)) {
            MaintenanceJobGroup group = MaintenanceFiles.readJobGroup(job);
            validateGroupAuthorization(request, job, group);
            if (group.state() != MaintenanceJobGroup.State.VERIFIED) {
                throw new IOException("Multi-world rollback requires a VERIFIED job group");
            }
            update(requestPath, request.withState(
                    MaintenanceRequest.State.ROLLING_BACK,
                    "Restoring every world in the verified maintenance group"
            ));
            ArrayList<MaintenanceJobGroup.Entry> entries =
                    new ArrayList<>(group.entries());
            IOException failure = rollbackGroupEntries(entries, request.jobsRoot());
            if (failure != null) {
                MaintenanceFiles.writeJobGroup(
                        job,
                        group.withEntries(
                                entries,
                                MaintenanceJobGroup.State.FAILED,
                                "Multi-world rollback requires operator attention"
                        )
                );
                throw failure;
            }
            group = group.withEntries(
                    entries,
                    MaintenanceJobGroup.State.ROLLED_BACK,
                    "Every changed loaded world was byte-restored and verified"
            );
            MaintenanceFiles.writeJobGroup(job, group);
            update(requestPath, request.withState(
                    MaintenanceRequest.State.ROLLED_BACK,
                    group.detail()
            ));
            return MaintenanceResult.of(
                    request,
                    true,
                    MaintenanceRequest.State.ROLLED_BACK,
                    group.detail(),
                    job.toString(),
                    false,
                    Map.of("worlds", entries.size()),
                    false
            );
        }
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

    private static void validateGroupAuthorization(
            MaintenanceRequest request,
            Path groupDirectory,
            MaintenanceJobGroup group
    ) throws IOException {
        Path server = Path.of(request.serverRoot()).toAbsolutePath().normalize();
        Path expectedRequests = server.resolve("yuworldrepair-maintenance")
                .resolve("requests")
                .normalize();
        Path normalizedGroup = groupDirectory.toAbsolutePath().normalize();
        if (!normalizedGroup.startsWith(expectedRequests)
                || !normalizedGroup.getParent().equals(expectedRequests)) {
            throw new IOException("Maintenance job group is outside the request root");
        }
        List<String> groupedRoots =
                group.entries().stream().map(MaintenanceJobGroup.Entry::worldRoot).toList();
        if (!new java.util.HashSet<>(request.worldRoots()).containsAll(groupedRoots)) {
            throw new IOException("A maintenance job-group world is no longer loaded");
        }
        Path jobs = Path.of(request.jobsRoot()).toAbsolutePath().normalize();
        for (MaintenanceJobGroup.Entry entry : group.entries()) {
            Path childJob = Path.of(entry.jobPath()).toAbsolutePath().normalize();
            if (!jobs.equals(childJob.getParent())) {
                throw new IOException("Maintenance child job is outside the authorized job root");
            }
        }
    }

    private static void waitForParentExit(MaintenanceRequest request) throws IOException {
        while (ProcessHandle.of(request.parentPid()).filter(ProcessHandle::isAlive).isPresent()) {
            ensureNotExpired(request);
            sleep();
        }
    }

    private static void waitForWorldUnlock(MaintenanceRequest request) throws IOException {
        IOException last = null;
        while (true) {
            ensureNotExpired(request);
            try {
                for (String root : request.worldRoots()) {
                    Path world = Path.of(root);
                    WorldAccessPolicy.requireExactUnlockedWorld(world, world);
                }
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

    private static void verifySupervisor(MaintenanceRequest request) throws IOException {
        if (request.restartStrategy() != MaintenanceRequest.RestartStrategy.PANEL
                && request.restartStrategy() != MaintenanceRequest.RestartStrategy.SUPERVISOR) {
            return;
        }
        String supervisorId = System.getenv(MaintenanceHandoff.SUPERVISOR_ID_ENV);
        if (supervisorId == null || !supervisorId.matches("[0-9a-f]{64}")) {
            throw new IOException(
                    "Panel maintenance requires the executable launcher environment"
            );
        }
    }

    private static boolean isProvablyBeforeWorldReplacement(MaintenanceRequest request) {
        if (request.operation() == MaintenanceRequest.Operation.ROLLBACK) {
            return false;
        }
        return switch (request.state()) {
            case HANDOFF, WAITING_FOR_STOP, SCANNING, BACKING_UP -> true;
            case REQUESTED, COUNTDOWN, APPLYING, VERIFYING, ROLLING_BACK,
                    COMPLETED, ROLLED_BACK, FAILED -> false;
        };
    }

    private static int metric(Map<String, ?> metrics, String key) throws IOException {
        Object value = metrics.get(key);
        if (!(value instanceof Number number)) {
            throw new IOException("Repair engine omitted metric: " + key);
        }
        return number.intValue();
    }

    private static int optionalMetric(Map<String, ?> metrics, String key) {
        Object value = metrics.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static long optionalLongMetric(Map<String, ?> metrics, String key) {
        Object value = metrics.get(key);
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static NamespaceWorldScanner.Options namespaceScanOptions(
            MaintenanceRequest request,
            String root,
            boolean allowQioTypeCleanup
    ) {
        Path normalizedRoot = Path.of(root).toAbsolutePath().normalize();
        boolean scanRegions = request.regionExcludedWorldRoots().stream()
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .noneMatch(normalizedRoot::equals);
        return (
                scanRegions
                        ? NamespaceWorldScanner.Options.full(
                        request.scanWorkers(),
                        allowQioTypeCleanup
                )
                        : NamespaceWorldScanner.Options.metadataOnly(
                        request.scanWorkers(),
                        allowQioTypeCleanup
                )
        ).withTrustedWorldLock(true);
    }

    private static void mergeMetricMap(
            Map<String, Long> target,
            Object supplied
    ) {
        if (!(supplied instanceof Map<?, ?> values)) {
            return;
        }
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (entry.getKey() instanceof String key
                    && entry.getValue() instanceof Number number) {
                target.merge(key, number.longValue(), Math::addExact);
            }
        }
    }

    private static Map<String, Object> namespaceMetrics(
            int worlds,
            int targets,
            int changed,
            int files,
            int coverageGaps,
            Map<String, Long> byNamespace,
            Map<String, Long> byStore,
            Map<String, Long> amountByNamespace,
            Map<String, Long> deferredByNamespace,
            Map<String, Long> deferredByStore,
            Map<String, Long> deferredAmountByNamespace,
            boolean includeItemBreakdown,
            int regionExcludedWorlds,
            int deferredTargets,
            long regionBytes,
            int scanWorkers,
            boolean regionScopeComplete,
            boolean cleanupComplete
    ) {
        LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("worlds", worlds);
        metrics.put("targets", targets);
        metrics.put("changed", changed);
        metrics.put("files", files);
        metrics.put("coverageGaps", coverageGaps);
        metrics.put("regionExcludedWorlds", regionExcludedWorlds);
        metrics.put("deferredTargets", deferredTargets);
        metrics.put("regionBytes", regionBytes);
        metrics.put("scanWorkers", scanWorkers);
        metrics.put("regionScopeComplete", regionScopeComplete);
        metrics.put("cleanupComplete", cleanupComplete);
        if (includeItemBreakdown) {
            LinkedHashMap<String, Long> detectedByNamespace =
                    combinedMetricMaps(byNamespace, deferredByNamespace);
            LinkedHashMap<String, Long> detectedByStore =
                    combinedMetricMaps(byStore, deferredByStore);
            LinkedHashMap<String, Long> detectedAmountByNamespace =
                    combinedMetricMaps(
                            amountByNamespace,
                            deferredAmountByNamespace
                    );
            metrics.put("detectedTargets", Math.addExact(targets, deferredTargets));
            metrics.put("removedTargets", changed);
            metrics.put("detectedByNamespace", Map.copyOf(detectedByNamespace));
            metrics.put("detectedByStore", Map.copyOf(detectedByStore));
            metrics.put(
                    "detectedAmountByNamespace",
                    Map.copyOf(detectedAmountByNamespace)
            );
            metrics.put("byNamespace", Map.copyOf(byNamespace));
            metrics.put("byStore", Map.copyOf(byStore));
            metrics.put("amountByNamespace", Map.copyOf(amountByNamespace));
            metrics.put("deferredByNamespace", Map.copyOf(deferredByNamespace));
            metrics.put("deferredByStore", Map.copyOf(deferredByStore));
            metrics.put(
                    "deferredAmountByNamespace",
                    Map.copyOf(deferredAmountByNamespace)
            );
        }
        return Map.copyOf(metrics);
    }

    private static LinkedHashMap<String, Long> combinedMetricMaps(
            Map<String, Long> first,
            Map<String, Long> second
    ) {
        LinkedHashMap<String, Long> combined = new LinkedHashMap<>();
        first.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> combined.put(entry.getKey(), entry.getValue()));
        second.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> combined.merge(
                        entry.getKey(),
                        entry.getValue(),
                        Math::addExact
                ));
        return combined;
    }

    private static void update(Path requestPath, MaintenanceRequest request) throws IOException {
        MaintenanceFiles.writeStoredRequestIfResultAbsent(requestPath, request);
        Path handoffPath = requestPath.resolveSibling(MaintenanceFiles.HANDOFF_FILE);
        String supervisorId = System.getenv(MaintenanceHandoff.SUPERVISOR_ID_ENV);
        MaintenanceFiles.writeHandoff(
                handoffPath,
                MaintenanceHandoff.of(
                        request,
                        supervisorId,
                        request.state(),
                        request.detail()
                )
        );
        System.out.println(
                "[YuWorldRepair worker] transition request=" + request.requestId()
                        + " state=" + request.state()
                        + " detail=" + safeText(request.detail())
        );
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return safeText(message);
    }

    private static String safeText(String value) {
        String oneLine = value.replace('\r', ' ').replace('\n', ' ');
        return oneLine.length() <= 1_024 ? oneLine : oneLine.substring(0, 1_024);
    }

    private enum RecoveryOutcome {
        ROLLED_BACK,
        ORIGINAL_UNCHANGED,
        UNSAFE
    }
}
