package dev.yu.worldrepair.worldtool.namespace;

import dev.yu.worldrepair.worldtool.anvil.RegionFile;
import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;
import dev.yu.worldrepair.worldtool.job.JobState;
import dev.yu.worldrepair.worldtool.job.SourceFileRecord;
import dev.yu.worldrepair.worldtool.nbt.Nbt;
import dev.yu.worldrepair.worldtool.nbt.NbtFile;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class NamespaceRepairService {
    private static final long DISK_MARGIN_BYTES = 64L * 1_024 * 1_024;

    private final NamespaceChunkAdapter adapter = new NamespaceChunkAdapter();
    private final Nbt.Limits nbtLimits = Nbt.Limits.conservative();
    private final NamespaceWorldScanner scanner =
            new NamespaceWorldScanner(adapter, nbtLimits);
    private final Path authorizedWorld;
    private final Path authorizedJobsRoot;

    private NamespaceRepairService(Path authorizedWorld, Path authorizedJobsRoot) {
        this.authorizedWorld = authorizedWorld.toAbsolutePath().normalize();
        this.authorizedJobsRoot = authorizedJobsRoot.toAbsolutePath().normalize();
    }

    public static NamespaceRepairService forServerMaintenance(
            Path authorizedWorld,
            Path authorizedJobsRoot
    ) {
        return new NamespaceRepairService(authorizedWorld, authorizedJobsRoot);
    }

    public record Result(
            boolean success,
            boolean modified,
            String detail,
            String jobPath,
            boolean rollbackAvailable,
            Map<String, ?> metrics
    ) {
    }

    public Result repair(
            NamespacePolicy policy,
            String registrySnapshotSha256
    ) throws IOException {
        Result prepared = prepare(policy, registrySnapshotSha256);
        if (!prepared.success() || metric(prepared.metrics(), "targets") == 0) {
            return prepared;
        }
        return applyPrepared(
                Path.of(prepared.jobPath()),
                policy,
                registrySnapshotSha256
        );
    }

    /**
     * Scans one signed world and creates byte-verified backups without replacing world files.
     *
     * <p>Separating preparation from apply lets the maintenance worker prepare every loaded
     * Multiverse world first. A coverage gap in any world therefore refuses the entire world set
     * before the first replacement.</p>
     */
    public Result prepare(
            NamespacePolicy policy,
            String registrySnapshotSha256
    ) throws IOException {
        Path world = resolveWorld(authorizedWorld);
        return prepare(
                policy,
                registrySnapshotSha256,
                OrphanItemIndex.load(world, policy, nbtLimits)
        );
    }

    public Result prepare(
            NamespacePolicy policy,
            String registrySnapshotSha256,
            OrphanItemIndex itemIndex
    ) throws IOException {
        return prepare(
                policy,
                registrySnapshotSha256,
                itemIndex,
                NamespaceWorldScanner.Options.full(1, true)
        );
    }

    public Result prepare(
            NamespacePolicy policy,
            String registrySnapshotSha256,
            OrphanItemIndex itemIndex,
            NamespaceWorldScanner.Options options
    ) throws IOException {
        Path world = resolveWorld(authorizedWorld);
        if (options.trustedWorldLock()) {
            WorldAccessPolicy.requireWorldLockHeldByThisWorker(world);
        }
        Path jobsRoot = resolveJobsRoot(world, authorizedJobsRoot);
        String worldFingerprint = IoUtil.sha256(world.resolve("level.dat"));
        NamespaceJobStore store = NamespaceJobStore.create(jobsRoot);
        NamespaceWorldScanner.ProgressListener externalProgress =
                options.progressListener();
        NamespaceWorldScanner.Options trackedOptions = options.withProgressListener(
                progress -> {
                    externalProgress.update(progress);
                    store.writeScanProgress(progress);
                }
        );
        NamespaceWorldScanner.Result scan =
                scanner.scan(world, policy, itemIndex, trackedOptions);
        List<SourceFileRecord> sources = scan.affectedFiles().stream()
                .map(file -> new SourceFileRecord(
                        file.relativePath(),
                        file.size(),
                        file.sha256(),
                        file.relativePath(),
                        null
                ))
                .toList();
        String now = Instant.now().toString();
        NamespaceJobManifest manifest = new NamespaceJobManifest(
                NamespaceJobManifest.SCHEMA_VERSION,
                store.directory().getFileName().toString(),
                now,
                now,
                JobState.SCANNED,
                world.toString(),
                worldFingerprint,
                policy.namespace(),
                policy.mode(),
                registrySnapshotSha256,
                scan.regionsScanned(),
                scan.chunksScanned(),
                scan.targets().size(),
                scan.coverageGaps().size(),
                "namespace_scan_complete"
        );
        store.writeTargets(scan.targets());
        store.writeSources(sources);
        store.writeReport("scan-summary.json", scanReport(policy, scan, sources.size()));
        store.writeManifest(manifest);
        store.appendLog(
                "scan",
                "targets=" + scan.targets().size()
                        + " gaps=" + scan.coverageGaps().size()
                        + " files=" + sources.size()
        );

        if (!scan.coverageGaps().isEmpty()) {
            NamespaceJobManifest failed = manifest.withState(
                    JobState.FAILED,
                    Instant.now().toString(),
                    "coverage_gaps_refused_all_writes"
            );
            store.writeManifest(failed);
            return new Result(
                    false,
                    false,
                    "通用扫描存在 " + scan.coverageGaps().size()
                            + " 个未覆盖/不可解析 region；已拒绝全部写入",
                    store.directory().toString(),
                    false,
                    scanMetrics(scan, sources.size())
            );
        }
        if (scan.targets().isEmpty()) {
            NamespaceJobManifest verified = manifest.withState(
                    JobState.VERIFIED,
                    Instant.now().toString(),
                    "no_namespace_targets_found"
            );
            store.writeManifest(verified);
            return new Result(
                    true,
                    false,
                    "未发现符合策略的可安全修复对象；世界未修改",
                    store.directory().toString(),
                    false,
                    scanMetrics(scan, sources.size())
            );
        }

        requireDiskCapacity(world, store, sources);
        backupAll(world, store, sources);
        NamespaceJobManifest prepared = manifest.withState(
                JobState.PREPARED,
                Instant.now().toString(),
                "all_source_backups_hash_verified"
        );
        store.writeManifest(prepared);
        return new Result(
                true,
                false,
                "Namespace targets scanned and all source backups byte-verified",
                store.directory().toString(),
                false,
                scanMetrics(scan, sources.size())
        );
    }

    /**
     * Applies a previously prepared job after rechecking its exact target and source set.
     */
    public Result applyPrepared(
            Path suppliedJob,
            NamespacePolicy policy,
            String registrySnapshotSha256
    ) throws IOException {
        Path world = resolveWorld(authorizedWorld);
        return applyPrepared(
                suppliedJob,
                policy,
                registrySnapshotSha256,
                OrphanItemIndex.load(world, policy, nbtLimits)
        );
    }

    public Result applyPrepared(
            Path suppliedJob,
            NamespacePolicy policy,
            String registrySnapshotSha256,
            OrphanItemIndex itemIndex
    ) throws IOException {
        return applyPrepared(
                suppliedJob,
                policy,
                registrySnapshotSha256,
                itemIndex,
                NamespaceWorldScanner.Options.full(1, true)
        );
    }

    public Result applyPrepared(
            Path suppliedJob,
            NamespacePolicy policy,
            String registrySnapshotSha256,
            OrphanItemIndex itemIndex,
            NamespaceWorldScanner.Options options
    ) throws IOException {
        NamespaceJobStore store = NamespaceJobStore.open(suppliedJob);
        NamespaceJobManifest prepared = store.readManifest();
        if (prepared.state() != JobState.PREPARED) {
            throw new IOException("Namespace apply requires a PREPARED job");
        }
        if (!prepared.namespace().equals(policy.namespace())
                || prepared.mode() != policy.mode()
                || !prepared.registrySnapshotSha256().equals(registrySnapshotSha256)) {
            throw new IOException("Namespace prepared job policy does not match authorization");
        }
        Path world = resolveWorld(Path.of(prepared.worldRoot()));
        if (options.trustedWorldLock()) {
            WorldAccessPolicy.requireWorldLockHeldByThisWorker(world);
        }
        if (!IoUtil.sha256(world.resolve("level.dat")).equals(prepared.worldFingerprint())) {
            throw new IOException("Namespace prepared world fingerprint changed");
        }
        List<NamespaceTarget> expectedTargets = store.readTargets();
        List<SourceFileRecord> sources = store.readSources();
        if (!options.trustedWorldLock()) {
            NamespaceWorldScanner.Result current =
                    scanner.scan(world, policy, itemIndex, options);
            if (!current.coverageGaps().isEmpty()) {
                throw new IOException("Namespace coverage changed after preparation");
            }
            if (!expectedTargets.equals(current.targets())
                    || !sources.equals(sourceRecords(current))) {
                throw new IOException(
                        "Namespace targets or source hashes changed after preparation"
                );
            }
        }
        try {
            List<SourceFileRecord> appliedSources =
                    applyAll(
                            world,
                            store,
                            prepared,
                            policy,
                            itemIndex,
                            expectedTargets,
                            sources
                    );
            NamespaceWorldScanner.Options verificationOptions =
                    options.trustedWorldLock()
                            ? verificationOptions(options, expectedTargets)
                            : options;
            NamespaceWorldScanner.Result verification =
                    scanner.scan(world, policy, itemIndex, verificationOptions);
            verifyApplied(appliedSources, world, verification);
            NamespaceJobManifest verified = prepared.withState(
                    JobState.VERIFIED,
                    Instant.now().toString(),
                    "namespace_targets_zero_and_source_hashes_verified"
            );
            store.writeManifest(verified);
            store.writeReport("apply-verification.json", Map.of(
                    "passed", true,
                    "remainingTargets", 0,
                    "coverageGaps", 0,
                    "sourceFiles", appliedSources.size(),
                    "verifiedAt", Instant.now().toString()
            ));
            store.appendLog(
                    "verify",
                    "passed changed=" + expectedTargets.size()
                            + " files=" + appliedSources.size()
            );
            return new Result(
                    true,
                    true,
                    "安全命名空间修复完成并验证；备份可用于回滚",
                    store.directory().toString(),
                    true,
                    Map.of(
                            "changed", expectedTargets.size(),
                            "files", appliedSources.size(),
                            "byAction", targetsByAction(expectedTargets),
                            "byNamespace", targetsByNamespace(expectedTargets),
                            "byStore", targetsByStore(expectedTargets),
                            "amountByNamespace", amountByNamespace(expectedTargets),
                            "warnings", verification.warnings().size()
                    )
            );
        } catch (IOException | RuntimeException failure) {
            try {
                restoreAll(world, store, store.readSources(), true);
                NamespaceJobManifest rolledBack = store.readManifest().withState(
                        JobState.ROLLED_BACK,
                        Instant.now().toString(),
                        "automatic_rollback_after_namespace_failure"
                );
                store.writeManifest(rolledBack);
                store.appendLog("auto-rollback", "verified after failure: " + safe(failure));
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                NamespaceJobManifest failed = store.readManifest().withState(
                        JobState.FAILED,
                        Instant.now().toString(),
                        "namespace_failure_and_rollback_requires_operator"
                );
                store.writeManifest(failed);
            }
            throw failure;
        }
    }

    public Result rollback(Path suppliedJob) throws IOException {
        NamespaceJobStore store = NamespaceJobStore.open(suppliedJob);
        NamespaceJobManifest manifest = store.readManifest();
        if (manifest.state() != JobState.VERIFIED) {
            throw new IOException("Namespace rollback requires a VERIFIED job");
        }
        Path world = resolveWorld(Path.of(manifest.worldRoot()));
        if (!IoUtil.sha256(world.resolve("level.dat")).equals(manifest.worldFingerprint())) {
            throw new IOException("Namespace rollback world fingerprint changed");
        }
        List<SourceFileRecord> sources = store.readSources();
        restoreAll(world, store, sources, false);
        NamespaceJobManifest rolledBack = manifest.withState(
                JobState.ROLLED_BACK,
                Instant.now().toString(),
                "namespace_rollback_byte_verified"
        );
        store.writeManifest(rolledBack);
        store.writeReport("rollback-verification.json", Map.of(
                "passed", true,
                "restoredFiles", sources.size(),
                "verifiedAt", Instant.now().toString()
        ));
        return new Result(
                true,
                true,
                "命名空间修复已逐字节回滚并验证",
                store.directory().toString(),
                false,
                Map.of("restoredFiles", sources.size())
        );
    }

    public static boolean isNamespaceJob(Path job) {
        return Files.isRegularFile(
                job.resolve(NamespaceJobStore.MANIFEST_FILE),
                LinkOption.NOFOLLOW_LINKS
        );
    }

    private List<SourceFileRecord> applyAll(
            Path world,
            NamespaceJobStore store,
            NamespaceJobManifest manifest,
            NamespacePolicy policy,
            OrphanItemIndex itemIndex,
            List<NamespaceTarget> targets,
            List<SourceFileRecord> initialSources
    ) throws IOException {
        Map<String, Map<Integer, List<NamespaceTarget>>> grouped = groupTargets(targets);
        ArrayList<SourceFileRecord> sources = new ArrayList<>(initialSources);
        store.writeManifest(manifest.withState(
                JobState.APPLYING,
                Instant.now().toString(),
                "namespace_copy_on_write_apply_started"
        ));
        store.appendJournal(event("APPLY_BEGIN", null, null, null));
        int changed = 0;
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            SourceFileRecord source = sources.get(sourceIndex);
            Map<Integer, List<NamespaceTarget>> byChunk = grouped.get(source.relativePath());
            if (byChunk == null || byChunk.isEmpty()) {
                throw new IOException("Namespace source has no targets: " + source.relativePath());
            }
            Path worldFile = resolveWorldFile(world, source.relativePath());
            String currentHash = IoUtil.sha256(worldFile);
            if (!currentHash.equals(source.preSha256())) {
                throw new IOException("Namespace source changed before apply: "
                        + source.relativePath());
            }
            Path temporary = worldFile.resolveSibling(
                    "." + worldFile.getFileName() + ".yuworldrepair-"
                            + manifest.jobId() + ".tmp"
            );
            if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Stale namespace temporary file: " + temporary);
            }
            LinkedHashMap<Integer, RegionFile.ChunkEditor> editors = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<NamespaceTarget>> entry : byChunk.entrySet()) {
                List<NamespaceTarget> chunkTargets = entry.getValue();
                editors.put(entry.getKey(), chunk -> {
                    if (!(chunk.root().tag() instanceof Nbt.CompoundTag root)) {
                        throw new IOException("Namespace chunk root is not a compound");
                    }
                    NamespaceTarget first = chunkTargets.getFirst();
                    NamespaceChunkAdapter.Context context = new NamespaceChunkAdapter.Context(
                            first.dimension(),
                            first.regionRelativePath(),
                            chunk.chunkX(),
                            chunk.chunkZ(),
                            chunk.index(),
                            chunk.external(),
                            first.regionKind()
                    );
                    NamespaceChunkAdapter.Mutation mutation =
                            adapter.mutate(
                                    root,
                                    context,
                                    policy,
                                    itemIndex,
                                    chunkTargets
                            );
                    return new RegionFile.EditResult(
                            true,
                            mutation.changed(),
                            mutation.postSemanticSha256()
                    );
                });
            }
            Map<Integer, RegionFile.EditResult> edits;
            if (source.relativePath().endsWith(".dat")) {
                if (editors.size() != 1 || !editors.containsKey(-1)) {
                    throw new IOException("Namespace player source maps invalid targets");
                }
                List<NamespaceTarget> playerTargets = byChunk.get(-1);
                NamespaceTarget first = playerTargets.getFirst();
                NbtFile.EditResult result = NbtFile.rewriteGzip(
                        worldFile,
                        temporary,
                        root -> {
                            if (!(root.tag() instanceof Nbt.CompoundTag compound)) {
                                throw new IOException("Standalone NBT root is not a compound");
                            }
                            NamespaceChunkAdapter.Context context =
                                    new NamespaceChunkAdapter.Context(
                                            first.dimension(),
                                            first.regionRelativePath(),
                                            -1,
                                            -1,
                                            -1,
                                            false,
                                            first.regionKind()
                                    );
                            NamespaceChunkAdapter.Mutation mutation = adapter.mutate(
                                    compound,
                                    context,
                                    policy,
                                    itemIndex,
                                    playerTargets
                            );
                            return new NbtFile.EditResult(
                                    true,
                                    mutation.changed(),
                                    mutation.postSemanticSha256()
                            );
                        },
                        nbtLimits
                );
                edits = Map.of(
                        -1,
                        new RegionFile.EditResult(
                                true,
                                result.changed(),
                                result.postSemanticSha256()
                        )
                );
            } else if (source.relativePath().endsWith(".mcc")) {
                if (editors.size() != 1) {
                    throw new IOException("Namespace external source maps multiple chunks");
                }
                var editor = editors.entrySet().iterator().next();
                NamespaceTarget first = byChunk.values().iterator().next().getFirst();
                Path region = resolveWorldFile(world, first.regionRelativePath());
                RegionFile.EditResult result = RegionFile.rewriteExternalChunk(
                        region,
                        editor.getKey(),
                        worldFile,
                        temporary,
                        editor.getValue(),
                        nbtLimits
                );
                edits = Map.of(editor.getKey(), result);
            } else {
                edits = RegionFile.rewrite(worldFile, temporary, editors, nbtLimits);
            }
            String postHash = IoUtil.sha256(temporary);
            sources.set(sourceIndex, source.withPostApplySha256(postHash));
            store.writeSources(sources);
            store.appendJournal(event(
                    "FILE_REPLACE_INTENT",
                    source.relativePath(),
                    source.preSha256(),
                    postHash
            ));
            IoUtil.moveAtomic(temporary, worldFile);
            if (!postHash.equals(IoUtil.sha256(worldFile))) {
                throw new IOException("Namespace post-apply hash mismatch");
            }
            changed = Math.addExact(
                    changed,
                    edits.values().stream().mapToInt(RegionFile.EditResult::removed).sum()
            );
            store.appendJournal(event(
                    "FILE_REPLACE_DONE",
                    source.relativePath(),
                    source.preSha256(),
                    postHash
            ));
        }
        if (changed != targets.size()) {
            throw new IOException("Namespace total mutation count mismatch");
        }
        store.writeManifest(manifest.withState(
                JobState.APPLIED,
                Instant.now().toString(),
                "namespace_apply_complete_pending_verify"
        ));
        return List.copyOf(sources);
    }

    private static void backupAll(
            Path world,
            NamespaceJobStore store,
            List<SourceFileRecord> sources
    ) throws IOException {
        for (SourceFileRecord source : sources) {
            Path worldFile = resolveWorldFile(world, source.relativePath());
            if (!source.preSha256().equals(IoUtil.sha256(worldFile))) {
                throw new IOException("Namespace source changed before backup");
            }
            IoUtil.copyVerified(
                    worldFile,
                    store.backupPath(source.backupRelativePath()),
                    source.preSha256()
            );
        }
    }

    private static void restoreAll(
            Path world,
            NamespaceJobStore store,
            List<SourceFileRecord> sources,
            boolean automatic
    ) throws IOException {
        for (SourceFileRecord source : sources) {
            Path worldFile = resolveWorldFile(world, source.relativePath());
            String current = IoUtil.sha256(worldFile);
            if (current.equals(source.preSha256())) {
                continue;
            }
            if (source.postApplySha256() == null
                    || !current.equals(source.postApplySha256())) {
                throw new IOException("Namespace rollback refuses newer/unknown file: "
                        + source.relativePath());
            }
            Path backup = store.backupPath(source.backupRelativePath());
            if (!source.preSha256().equals(IoUtil.sha256(backup))) {
                throw new IOException("Namespace rollback backup hash mismatch");
            }
            Path temporary = worldFile.resolveSibling(
                    "." + worldFile.getFileName() + ".namespace-rollback.tmp"
            );
            if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Stale namespace rollback temporary file");
            }
            IoUtil.copyVerified(backup, temporary, source.preSha256());
            IoUtil.moveAtomic(temporary, worldFile);
            if (!source.preSha256().equals(IoUtil.sha256(worldFile))) {
                throw new IOException("Namespace rollback verification failed");
            }
            store.appendJournal(event(
                    automatic ? "AUTO_ROLLBACK_FILE_DONE" : "ROLLBACK_FILE_DONE",
                    source.relativePath(),
                    current,
                    source.preSha256()
            ));
        }
        for (SourceFileRecord source : sources) {
            if (!source.preSha256().equals(
                    IoUtil.sha256(resolveWorldFile(world, source.relativePath()))
            )) {
                throw new IOException("Namespace rollback final verification failed");
            }
        }
    }

    private static void verifyApplied(
            List<SourceFileRecord> sources,
            Path world,
            NamespaceWorldScanner.Result verification
    ) throws IOException {
        for (SourceFileRecord source : sources) {
            if (source.postApplySha256() == null
                    || !source.postApplySha256().equals(
                    IoUtil.sha256(resolveWorldFile(world, source.relativePath()))
            )) {
                throw new IOException("Namespace source post hash verification failed");
            }
        }
        if (!verification.coverageGaps().isEmpty()) {
            throw new IOException("Namespace verification introduced coverage gaps");
        }
        if (!verification.targets().isEmpty()) {
            throw new IOException("Namespace verification found remaining targets: "
                    + verification.targets().size());
        }
    }

    private Path resolveWorld(Path supplied) throws IOException {
        return WorldAccessPolicy.requireExactUnlockedWorld(supplied, authorizedWorld);
    }

    private Path resolveJobsRoot(Path world, Path supplied) throws IOException {
        return WorldAccessPolicy.requireExactExternalJobRoot(
                world,
                supplied,
                authorizedJobsRoot
        );
    }

    private static Path resolveWorldFile(Path world, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.indexOf('\\') >= 0) {
            throw new IOException("Invalid namespace relative world path");
        }
        Path file = world.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        return WorldAccessPolicy.requireContainedRegularFile(world, file);
    }

    private static void requireDiskCapacity(
            Path world,
            NamespaceJobStore store,
            List<SourceFileRecord> sources
    ) throws IOException {
        long total = 0;
        long largest = 0;
        for (SourceFileRecord source : sources) {
            total = Math.addExact(total, source.size());
            largest = Math.max(largest, source.size());
        }
        if (total > NamespaceJobStore.MAX_BACKUP_BYTES) {
            throw new IOException("Namespace backups exceed hard limit");
        }
        FileStore jobDisk = Files.getFileStore(store.directory());
        FileStore worldDisk = Files.getFileStore(world);
        if (jobDisk.equals(worldDisk)) {
            long required = Math.addExact(
                    Math.addExact(total, largest),
                    DISK_MARGIN_BYTES
            );
            if (jobDisk.getUsableSpace() < required) {
                throw new IOException("Insufficient shared disk for namespace repair");
            }
        } else {
            if (jobDisk.getUsableSpace() < Math.addExact(total, DISK_MARGIN_BYTES)
                    || worldDisk.getUsableSpace() < Math.addExact(largest, DISK_MARGIN_BYTES)) {
                throw new IOException("Insufficient disk for namespace repair");
            }
        }
    }

    private static Map<String, Map<Integer, List<NamespaceTarget>>> groupTargets(
            List<NamespaceTarget> targets
    ) {
        LinkedHashMap<String, Map<Integer, List<NamespaceTarget>>> grouped =
                new LinkedHashMap<>();
        targets.stream()
                .sorted(Comparator
                        .comparing(NamespaceRepairService::storageRelativePath)
                        .thenComparingInt(NamespaceTarget::chunkIndex)
                        .thenComparing(NamespaceTarget::nbtPath))
                .forEach(target -> grouped
                        .computeIfAbsent(storageRelativePath(target),
                                ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(target.chunkIndex(), ignored -> new ArrayList<>())
                        .add(target));
        return grouped;
    }

    private static String storageRelativePath(NamespaceTarget target) {
        if (target.regionKind() == NamespaceTarget.RegionKind.PLAYER
                || target.regionKind() == NamespaceTarget.RegionKind.SAVED_DATA) {
            return target.regionRelativePath();
        }
        if (!target.externalChunk()) {
            return target.regionRelativePath();
        }
        int separator = target.regionRelativePath().lastIndexOf('/');
        String parent = separator < 0
                ? ""
                : target.regionRelativePath().substring(0, separator + 1);
        return parent + "c." + target.chunkX() + "." + target.chunkZ() + ".mcc";
    }

    private static Map<String, ?> scanReport(
            NamespacePolicy policy,
            NamespaceWorldScanner.Result scan,
            int sourceFiles
    ) {
        List<NamespaceTarget> detected = detectedTargets(scan);
        LinkedHashMap<String, Object> report = new LinkedHashMap<>();
        report.put("namespace", policy.namespace());
        report.put("mode", policy.mode());
        report.put("regionsScanned", scan.regionsScanned());
        report.put("chunksScanned", scan.chunksScanned());
        report.put("regionBytesScanned", scan.regionBytesScanned());
        report.put("regionDataIncluded", scan.regionDataIncluded());
        report.put("scanWorkers", scan.scanWorkers());
        report.put("deferredTargets", scan.deferredTargets());
        report.put(
                "scopeComplete",
                scan.regionDataIncluded() && scan.deferredTargets() == 0
        );
        report.put("targets", scan.targets().size());
        report.put("detectedTargets", detected.size());
        report.put("sourceFiles", sourceFiles);
        report.put("coverageGaps", scan.coverageGaps());
        report.put("warnings", scan.warnings());
        report.put("byAction", scan.targetsByAction());
        if (policy.isGlobalItemCleanup()) {
            report.put("byNamespace", targetsByNamespace(scan.targets()));
            report.put("byStore", targetsByStore(scan.targets()));
            report.put("amountByNamespace", amountByNamespace(scan.targets()));
            report.put(
                    "deferredByNamespace",
                    targetsByNamespace(scan.deferredTargetDetails())
            );
            report.put(
                    "deferredByStore",
                    targetsByStore(scan.deferredTargetDetails())
            );
            report.put(
                    "deferredAmountByNamespace",
                    amountByNamespace(scan.deferredTargetDetails())
            );
            report.put("detectedByNamespace", targetsByNamespace(detected));
            report.put("detectedByStore", targetsByStore(detected));
            report.put("detectedAmountByNamespace", amountByNamespace(detected));
        }
        report.put("unknownPrivateSchemasWereNotModified", true);
        return report;
    }

    private static Map<String, Object> scanMetrics(
            NamespaceWorldScanner.Result scan,
            int sourceFiles
    ) {
        List<NamespaceTarget> detected = detectedTargets(scan);
        LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("targets", scan.targets().size());
        metrics.put("detectedTargets", detected.size());
        metrics.put("files", sourceFiles);
        metrics.put("regions", scan.regionsScanned());
        metrics.put("chunks", scan.chunksScanned());
        metrics.put("regionBytes", scan.regionBytesScanned());
        metrics.put("deferredTargets", scan.deferredTargets());
        metrics.put("regionDataIncluded", scan.regionDataIncluded());
        metrics.put("scanWorkers", scan.scanWorkers());
        metrics.put("coverageGaps", scan.coverageGaps().size());
        metrics.put("warnings", scan.warnings().size());
        metrics.put("byNamespace", targetsByNamespace(scan.targets()));
        metrics.put("byStore", targetsByStore(scan.targets()));
        metrics.put("amountByNamespace", amountByNamespace(scan.targets()));
        metrics.put(
                "deferredByNamespace",
                targetsByNamespace(scan.deferredTargetDetails())
        );
        metrics.put(
                "deferredByStore",
                targetsByStore(scan.deferredTargetDetails())
        );
        metrics.put(
                "deferredAmountByNamespace",
                amountByNamespace(scan.deferredTargetDetails())
        );
        metrics.put("detectedByNamespace", targetsByNamespace(detected));
        metrics.put("detectedByStore", targetsByStore(detected));
        metrics.put("detectedAmountByNamespace", amountByNamespace(detected));
        return Map.copyOf(metrics);
    }

    private static List<NamespaceTarget> detectedTargets(
            NamespaceWorldScanner.Result scan
    ) {
        ArrayList<NamespaceTarget> detected = new ArrayList<>(
                scan.targets().size() + scan.deferredTargetDetails().size()
        );
        detected.addAll(scan.targets());
        detected.addAll(scan.deferredTargetDetails());
        return List.copyOf(detected);
    }

    private static Map<String, Integer> targetsByAction(
            List<NamespaceTarget> targets
    ) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        targets.stream()
                .map(target -> target.action().name())
                .sorted()
                .forEach(action -> result.merge(action, 1, Integer::sum));
        return Map.copyOf(result);
    }

    private static Map<String, Integer> targetsByNamespace(
            List<NamespaceTarget> targets
    ) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        targets.stream()
                .map(NamespaceTarget::resourceId)
                .map(id -> id.substring(0, id.indexOf(':')))
                .sorted()
                .forEach(namespace -> result.merge(namespace, 1, Integer::sum));
        return Map.copyOf(result);
    }

    private static Map<String, Integer> targetsByStore(List<NamespaceTarget> targets) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        targets.stream()
                .map(target -> target.store().name())
                .sorted()
                .forEach(store -> result.merge(store, 1, Integer::sum));
        return Map.copyOf(result);
    }

    private static Map<String, Long> amountByNamespace(
            List<NamespaceTarget> targets
    ) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        targets.stream()
                .filter(target -> target.amount() > 0)
                .sorted(Comparator.comparing(NamespaceTarget::resourceId))
                .forEach(target -> {
                    String id = target.resourceId();
                    String namespace = id.substring(0, id.indexOf(':'));
                    result.merge(namespace, target.amount(), Math::addExact);
                });
        return Map.copyOf(result);
    }

    private static List<SourceFileRecord> sourceRecords(
            NamespaceWorldScanner.Result scan
    ) {
        return scan.affectedFiles().stream()
                .map(file -> new SourceFileRecord(
                        file.relativePath(),
                        file.size(),
                        file.sha256(),
                        file.relativePath(),
                        null
                ))
                .toList();
    }

    private static NamespaceWorldScanner.Options verificationOptions(
            NamespaceWorldScanner.Options options,
            List<NamespaceTarget> targets
    ) {
        Set<String> regionFiles = new HashSet<>();
        Set<String> standaloneFiles = new HashSet<>();
        for (NamespaceTarget target : targets) {
            if (target.regionKind() == NamespaceTarget.RegionKind.PLAYER
                    || target.regionKind() == NamespaceTarget.RegionKind.SAVED_DATA) {
                standaloneFiles.add(target.regionRelativePath());
            } else {
                regionFiles.add(target.regionRelativePath());
            }
        }
        return options.selecting(regionFiles, standaloneFiles);
    }

    private static int metric(Map<String, ?> metrics, String name) {
        Object value = metrics.get(name);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Map<String, Object> event(
            String type,
            String path,
            String before,
            String after
    ) {
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("time", Instant.now().toString());
        event.put("type", type);
        if (path != null) {
            event.put("path", path);
        }
        if (before != null) {
            event.put("beforeSha256", before);
        }
        if (after != null) {
            event.put("afterSha256", after);
        }
        return event;
    }

    private static String safe(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        String oneLine = message.replace('\r', ' ').replace('\n', ' ');
        return oneLine.length() <= 1_024 ? oneLine : oneLine.substring(0, 1_024);
    }
}
