package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.adapter.LegacyChickenDataAdapter;
import dev.yu.worldrepair.worldtool.anvil.RegionFile;
import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;
import dev.yu.worldrepair.worldtool.job.JobManifest;
import dev.yu.worldrepair.worldtool.job.JobState;
import dev.yu.worldrepair.worldtool.job.JobStore;
import dev.yu.worldrepair.worldtool.job.SourceFileRecord;
import dev.yu.worldrepair.worldtool.nbt.Nbt;
import dev.yu.worldrepair.worldtool.scan.WorldScanner;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorldRepairService {
    private static final long DISK_MARGIN_BYTES = 64L * 1_024 * 1_024;
    private static final String TOOL_VERSION = "1.1.0-experimental+mc1.21.1";

    private final LegacyChickenDataAdapter adapter = new LegacyChickenDataAdapter();
    private final Nbt.Limits nbtLimits = Nbt.Limits.conservative();
    private final WorldScanner scanner = new WorldScanner(adapter, nbtLimits);
    private final String trustedIceAndFireSha256;
    private final WorldResolver worldResolver;
    private final JobRootResolver jobRootResolver;

    public WorldRepairService() {
        this(LegacyChickenDataAdapter.VERIFIED_ICE_AND_FIRE_SHA256);
    }

    WorldRepairService(String trustedIceAndFireSha256) {
        this(
                trustedIceAndFireSha256,
                WorldAccessPolicy::requireOfflineCopy,
                WorldRepairService::requireExternalJobRoot
        );
    }

    private WorldRepairService(
            String trustedIceAndFireSha256,
            WorldResolver worldResolver,
            JobRootResolver jobRootResolver
    ) {
        this.trustedIceAndFireSha256 = trustedIceAndFireSha256;
        this.worldResolver = worldResolver;
        this.jobRootResolver = jobRootResolver;
    }

    /**
     * Creates the production-world service used only by the post-shutdown maintenance worker.
     * Every operation remains bound to the two exact paths captured in the signed request.
     */
    public static WorldRepairService forServerMaintenance(
            Path authorizedWorld,
            Path authorizedJobRoot
    ) {
        return forServerMaintenance(
                authorizedWorld,
                authorizedJobRoot,
                LegacyChickenDataAdapter.VERIFIED_ICE_AND_FIRE_SHA256
        );
    }

    static WorldRepairService forServerMaintenance(
            Path authorizedWorld,
            Path authorizedJobRoot,
            String trustedHash
    ) {
        Path world = authorizedWorld.toAbsolutePath().normalize();
        Path jobs = authorizedJobRoot.toAbsolutePath().normalize();
        return new WorldRepairService(
                trustedHash,
                supplied -> WorldAccessPolicy.requireExactUnlockedWorld(supplied, world),
                (resolvedWorld, supplied) -> WorldAccessPolicy.requireExactExternalJobRoot(
                        resolvedWorld,
                        supplied,
                        jobs
                )
        );
    }

    public record CommandResult(
            boolean success,
            String action,
            String detail,
            String job,
            String confirmationToken,
            Map<String, ?> metrics
    ) {
    }

    public record RuntimeMetadata(
            String minecraftVersion,
            String neoForgeVersion,
            String youerVersion
    ) {
        public RuntimeMetadata {
            minecraftVersion = normalizeMetadata(minecraftVersion);
            neoForgeVersion = normalizeMetadata(neoForgeVersion);
            youerVersion = normalizeMetadata(youerVersion);
        }

        public static RuntimeMetadata unknown() {
            return new RuntimeMetadata(null, null, null);
        }

        private static String normalizeMetadata(String value) {
            if (value == null || value.isBlank()) {
                return "unknown-not-provided";
            }
            String normalized = value.trim();
            if (normalized.length() > 256
                    || normalized.indexOf('\0') >= 0
                    || normalized.indexOf('\r') >= 0
                    || normalized.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Invalid runtime version metadata");
            }
            return normalized;
        }
    }

    public CommandResult scan(Path suppliedWorld, Path jobsRoot, Path iceAndFireJar) throws IOException {
        return scan(suppliedWorld, jobsRoot, iceAndFireJar, RuntimeMetadata.unknown());
    }

    public CommandResult scan(
            Path suppliedWorld,
            Path jobsRoot,
            Path iceAndFireJar,
            RuntimeMetadata runtime
    ) throws IOException {
        Path world = worldResolver.resolve(suppliedWorld);
        Path normalizedJobsRoot = jobRootResolver.resolve(world, jobsRoot);
        Path jar = requireRegularFile(iceAndFireJar, "Ice and Fire jar");
        String jarHash = IoUtil.sha256(jar);
        boolean trusted = trustedIceAndFireSha256.equals(jarHash);
        String worldFingerprint = IoUtil.sha256(world.resolve("level.dat"));

        long started = System.nanoTime();
        WorldScanner.Result scan = scanner.scan(world, trusted);
        JobStore store = JobStore.create(normalizedJobsRoot);
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
        JobManifest manifest = new JobManifest(
                3,
                store.directory().getFileName().toString(),
                now,
                now,
                JobState.SCANNED,
                world.toString(),
                worldFingerprint,
                LegacyChickenDataAdapter.ADAPTER_ID,
                TOOL_VERSION,
                System.getProperty("java.version", "unknown"),
                runtime.minecraftVersion(),
                runtime.neoForgeVersion(),
                runtime.youerVersion(),
                jarHash,
                scan.regionsScanned(),
                scan.chunksScanned(),
                scan.targets().size(),
                scan.addressableTargets(),
                scan.blockedTargets(),
                trusted ? "read_only_scan_complete" : "unverified_iceandfire_jar_read_only_only"
        );
        store.writeSources(sources);
        store.writeTargets(scan.targets());
        LinkedHashMap<String, Object> scanReport = new LinkedHashMap<>();
        scanReport.put("worldCopy", world.toString());
        scanReport.put("worldFingerprint", worldFingerprint);
        scanReport.put("iceAndFireSha256", jarHash);
        scanReport.put("toolVersion", TOOL_VERSION);
        scanReport.put("javaVersion", System.getProperty("java.version", "unknown"));
        scanReport.put("minecraftVersion", runtime.minecraftVersion());
        scanReport.put("neoForgeVersion", runtime.neoForgeVersion());
        scanReport.put("youerVersion", runtime.youerVersion());
        scanReport.put("trustedIceAndFireVersion", trusted);
        scanReport.put("regionsScanned", scan.regionsScanned());
        scanReport.put("chunksScanned", scan.chunksScanned());
        scanReport.put("emptyRegionsSkipped", scan.emptyRegionsSkipped());
        scanReport.put("affectedRegions", sources.size());
        scanReport.put("targets", scan.targets().size());
        scanReport.put("uniqueEntityUuids", scan.uniqueEntityUuids());
        scanReport.put("uniqueAttachments", scan.uniqueAttachments());
        scanReport.put("addressableTargets", scan.addressableTargets());
        scanReport.put("blockedTargets", scan.blockedTargets());
        scanReport.put("byDimension", scan.targetsByDimension());
        scanReport.put("byEntityType", scan.targetsByEntityType());
        scanReport.put("byRegion", scan.targetsByRegion());
        scanReport.put("byChunk", scan.targetsByChunk());
        scanReport.put("logOccurrencesAreNotEntityCount", true);
        scanReport.put("elapsedMillis", elapsedMillis(started));
        store.writeReport("scan-summary.json", scanReport);
        store.writeManifest(manifest);
        store.appendToolLog(
                "scan",
                "complete targets=" + scan.targets().size()
                        + " addressable=" + scan.addressableTargets()
                        + " blocked=" + scan.blockedTargets()
        );
        return new CommandResult(
                true,
                "scan",
                "Read-only scan complete",
                store.directory().toString(),
                null,
                Map.of(
                        "targets", scan.targets().size(),
                        "addressable", scan.addressableTargets(),
                        "blocked", scan.blockedTargets(),
                        "regions", scan.regionsScanned(),
                        "chunks", scan.chunksScanned(),
                        "emptyRegionsSkipped", scan.emptyRegionsSkipped(),
                        "uniqueEntityUuids", scan.uniqueEntityUuids()
                )
        );
    }

    public CommandResult prepare(Path jobPath) throws IOException {
        JobStore store = JobStore.open(jobPath);
        JobManifest manifest = store.readManifest();
        requireState(manifest, JobState.SCANNED);
        if (manifest.totalTargets() == 0) {
            throw new IOException("Job has no repair targets");
        }
        if (manifest.blockedTargets() != 0 || manifest.addressableTargets() != manifest.totalTargets()) {
            throw new IOException("Job contains blocked or unaddressable targets");
        }
        if (!trustedIceAndFireSha256.equals(manifest.iceAndFireSha256())) {
            throw new IOException("Ice and Fire jar fingerprint is not approved for apply");
        }

        Path world = validateWorld(manifest);
        List<LegacyChickenDataAdapter.Target> expectedTargets = store.readTargets();
        List<SourceFileRecord> sources = store.readSources();
        WorldScanner.Result current = scanner.scan(world, true);
        if (!expectedTargets.equals(current.targets())) {
            throw new IOException("World targets changed after scan");
        }
        compareSources(sources, current.affectedFiles());
        requireDiskCapacity(world, store, sources);

        long backupBytes = 0;
        for (SourceFileRecord source : sources) {
            Path worldFile = resolveWorldFile(world, source.relativePath());
            if (!source.preSha256().equals(IoUtil.sha256(worldFile))) {
                throw new IOException("Source changed before backup: " + source.relativePath());
            }
            Path backup = store.backupPath(source.backupRelativePath());
            IoUtil.copyVerified(worldFile, backup, source.preSha256());
            backupBytes = Math.addExact(backupBytes, source.size());
            if (backupBytes > JobStore.MAX_BACKUP_BYTES) {
                throw new IOException("Backup bytes exceed hard limit");
            }
        }

        JobManifest prepared = manifest.withState(
                JobState.PREPARED,
                Instant.now().toString(),
                "backups_verified"
        );
        store.writeManifest(prepared);
        String token = store.issueToken("apply", store.manifestSha256());
        store.writeReport("prepare-summary.json", Map.of(
                "affectedFiles", sources.size(),
                "backupBytes", backupBytes,
                "allBackupsVerified", true
        ));
        store.appendToolLog(
                "prepare",
                "complete affectedFiles=" + sources.size() + " backupBytes=" + backupBytes
        );
        return new CommandResult(
                true,
                "prepare",
                "Backups verified; apply requires the one-time token",
                store.directory().toString(),
                token,
                Map.of("files", sources.size(), "backupBytes", backupBytes)
        );
    }

    public CommandResult apply(Path jobPath, String token) throws IOException {
        JobStore store = JobStore.open(jobPath);
        JobManifest manifest = store.readManifest();
        requireState(manifest, JobState.PREPARED);
        Path world = validateWorld(manifest);
        store.consumeToken("apply", store.manifestSha256(), token);
        JobManifest applying = manifest.withState(JobState.APPLYING, Instant.now().toString(), "apply_started");
        store.writeManifest(applying);
        store.appendJournal(event("APPLY_BEGIN", null, null, null));
        store.appendToolLog("apply", "started");

        List<LegacyChickenDataAdapter.Target> targets = store.readTargets();
        if (targets.stream().anyMatch(target -> !target.addressable())) {
            fail(store, applying, "Apply refused because the job contains an unaddressable target");
            throw new IOException("Job contains an unaddressable target");
        }
        Map<String, Map<Integer, List<LegacyChickenDataAdapter.Target>>> grouped = groupTargets(targets);
        ArrayList<SourceFileRecord> sources = new ArrayList<>(store.readSources());
        int removed = 0;

        try {
            for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
                SourceFileRecord source = sources.get(sourceIndex);
                Map<Integer, List<LegacyChickenDataAdapter.Target>> byChunk =
                        grouped.get(source.relativePath());
                if (byChunk == null || byChunk.isEmpty()) {
                    throw new IOException("Source file has no targets: " + source.relativePath());
                }
                Path worldFile = resolveWorldFile(world, source.relativePath());
                String currentHash = IoUtil.sha256(worldFile);
                if (!source.preSha256().equals(currentHash)) {
                    throw new IOException("Source changed before apply: " + source.relativePath());
                }
                if (Files.getFileStore(worldFile).getUsableSpace()
                        < source.size() + DISK_MARGIN_BYTES) {
                    throw new IOException(
                            "Insufficient disk space for copy-on-write region: " + source.relativePath()
                    );
                }
                Path temporary = worldFile.resolveSibling(
                        "." + worldFile.getFileName() + ".yuworldrepair-" + manifest.jobId() + ".tmp"
                );
                if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Stale apply temporary file requires manual inspection: " + temporary);
                }
                store.appendJournal(event("FILE_BUILD_BEGIN", source.relativePath(), currentHash, null));

                Map<Integer, RegionFile.ChunkEditor> editors = new LinkedHashMap<>();
                for (Map.Entry<Integer, List<LegacyChickenDataAdapter.Target>> entry : byChunk.entrySet()) {
                    List<LegacyChickenDataAdapter.Target> chunkTargets = entry.getValue();
                    editors.put(entry.getKey(), chunk -> {
                        if (!(chunk.root().tag() instanceof Nbt.CompoundTag root)) {
                            throw new IOException("Entity chunk root is not a compound");
                        }
                        LegacyChickenDataAdapter.Target first = chunkTargets.getFirst();
                        LegacyChickenDataAdapter.Context context = new LegacyChickenDataAdapter.Context(
                                first.dimension(),
                                first.regionRelativePath(),
                                chunk.chunkX(),
                                chunk.chunkZ(),
                                chunk.index(),
                                chunk.external()
                        );
                        LegacyChickenDataAdapter.Mutation mutation =
                                adapter.removeExactTargets(root, context, chunkTargets);
                        return new RegionFile.EditResult(
                                true,
                                mutation.removed(),
                                mutation.postSemanticSha256()
                        );
                    });
                }
                Map<Integer, RegionFile.EditResult> edits;
                if (source.relativePath().endsWith(".mcc")) {
                    if (byChunk.size() != 1) {
                        throw new IOException("External sidecar mapped to more than one chunk");
                    }
                    Map.Entry<Integer, RegionFile.ChunkEditor> external = editors.entrySet()
                            .iterator()
                            .next();
                    LegacyChickenDataAdapter.Target first = byChunk.values()
                            .iterator()
                            .next()
                            .getFirst();
                    if (!first.externalChunk()) {
                        throw new IOException("Internal target mapped to an external sidecar");
                    }
                    Path regionFile = resolveWorldFile(world, first.regionRelativePath());
                    RegionFile.EditResult result = RegionFile.rewriteExternalChunk(
                            regionFile,
                            external.getKey(),
                            worldFile,
                            temporary,
                            external.getValue(),
                            nbtLimits
                    );
                    edits = Map.of(external.getKey(), result);
                } else {
                    if (byChunk.values().stream()
                            .flatMap(List::stream)
                            .anyMatch(LegacyChickenDataAdapter.Target::externalChunk)) {
                        throw new IOException("External target mapped to an internal region file");
                    }
                    edits = RegionFile.rewrite(worldFile, temporary, editors, nbtLimits);
                }
                String postHash = IoUtil.sha256(temporary);
                SourceFileRecord withPost = source.withPostApplySha256(postHash);
                sources.set(sourceIndex, withPost);
                store.writeSources(sources);
                store.appendJournal(event(
                        "FILE_REPLACE_INTENT",
                        source.relativePath(),
                        source.preSha256(),
                        postHash
                ));
                IoUtil.moveAtomic(temporary, worldFile);
                if (!postHash.equals(IoUtil.sha256(worldFile))) {
                    throw new IOException("Post-apply region hash mismatch: " + source.relativePath());
                }
                int fileRemoved = edits.values().stream().mapToInt(RegionFile.EditResult::removed).sum();
                removed = Math.addExact(removed, fileRemoved);
                store.appendJournal(event(
                        "FILE_REPLACE_DONE",
                        source.relativePath(),
                        source.preSha256(),
                        postHash
                ));
            }
            if (removed != targets.size()) {
                throw new IOException("Removed target count does not match manifest");
            }
            JobManifest applied = applying.withState(
                    JobState.APPLIED,
                    Instant.now().toString(),
                    "apply_complete_pending_verify"
            );
            store.writeManifest(applied);
            store.appendJournal(event("APPLY_DONE", null, null, null));
            store.appendToolLog("apply", "complete removed=" + removed + " files=" + sources.size());
            return new CommandResult(
                    true,
                    "apply",
                    "Apply completed; run verify before using the repaired copy",
                    store.directory().toString(),
                    null,
                    Map.of("removed", removed, "files", sources.size())
            );
        } catch (IOException | RuntimeException failure) {
            fail(store, applying, "Apply interrupted: " + safeMessage(failure));
            throw failure;
        }
    }

    public CommandResult verify(Path jobPath) throws IOException {
        JobStore store = JobStore.open(jobPath);
        JobManifest manifest = store.readManifest();
        Path world = validateWorld(manifest);
        if (manifest.state() == JobState.PREPARED
                || manifest.state() == JobState.APPLYING
                || manifest.state() == JobState.ROLLING_BACK
                || manifest.state() == JobState.FAILED) {
            return verifyRecovery(store, manifest, world);
        }
        if (manifest.state() != JobState.APPLIED && manifest.state() != JobState.VERIFIED) {
            throw new IOException(
                    "Verify requires PREPARED, APPLYING, APPLIED, VERIFIED, ROLLING_BACK, or FAILED state"
            );
        }
        List<SourceFileRecord> sources = store.readSources();
        for (SourceFileRecord source : sources) {
            if (source.postApplySha256() == null) {
                throw new IOException("Missing post-apply hash for " + source.relativePath());
            }
            String actual = IoUtil.sha256(resolveWorldFile(world, source.relativePath()));
            if (!source.postApplySha256().equals(actual)) {
                throw new IOException("Post-apply source hash mismatch: " + source.relativePath());
            }
        }
        WorldScanner.Result current = scanner.scan(world, true);
        if (!current.targets().isEmpty()) {
            throw new IOException("Verification found " + current.targets().size() + " remaining legacy attachments");
        }
        JobManifest verified = manifest.withState(
                JobState.VERIFIED,
                Instant.now().toString(),
                "target_attachment_count_zero"
        );
        store.writeManifest(verified);
        store.writeReport("apply-verification.json", Map.of(
                "passed", true,
                "remainingTargets", 0,
                "sourceFiles", sources.size(),
                "verifiedAt", Instant.now().toString()
        ));
        String rollbackToken = store.issueToken("rollback", store.manifestSha256());
        store.appendToolLog("verify", "passed remainingTargets=0 files=" + sources.size());
        return new CommandResult(
                true,
                "verify",
                "Apply verified; target attachment count is zero",
                store.directory().toString(),
                rollbackToken,
                Map.of("remainingTargets", 0, "files", sources.size())
        );
    }

    public CommandResult rollback(Path jobPath, String token) throws IOException {
        JobStore store = JobStore.open(jobPath);
        JobManifest manifest = store.readManifest();
        if (manifest.state() != JobState.VERIFIED && manifest.state() != JobState.FAILED) {
            throw new IOException("Rollback requires VERIFIED or recovery-verified FAILED state");
        }
        Path world = validateWorld(manifest);
        store.consumeToken("rollback", store.manifestSha256(), token);
        JobManifest rollingBack = manifest.withState(
                JobState.ROLLING_BACK,
                Instant.now().toString(),
                "rollback_started"
        );
        store.writeManifest(rollingBack);
        store.appendJournal(event("ROLLBACK_BEGIN", null, null, null));
        store.appendToolLog("rollback", "started");

        List<SourceFileRecord> sources = store.readSources();
        int restored = 0;
        try {
            for (SourceFileRecord source : sources) {
                Path worldFile = resolveWorldFile(world, source.relativePath());
                String current = IoUtil.sha256(worldFile);
                if (source.preSha256().equals(current)) {
                    store.appendJournal(event(
                            "ROLLBACK_FILE_ALREADY_ORIGINAL",
                            source.relativePath(),
                            current,
                            source.preSha256()
                    ));
                    continue;
                }
                if (source.postApplySha256() == null || !source.postApplySha256().equals(current)) {
                    throw new IOException("Rollback would overwrite newer or unknown data: " + source.relativePath());
                }
                Path backup = store.backupPath(source.backupRelativePath());
                if (!source.preSha256().equals(IoUtil.sha256(backup))) {
                    throw new IOException("Backup hash mismatch: " + source.relativePath());
                }
                Path temporary = worldFile.resolveSibling(
                        "." + worldFile.getFileName() + ".yuworldrepair-" + manifest.jobId() + ".rollback.tmp"
                );
                if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Stale rollback temporary file requires manual inspection: " + temporary);
                }
                store.appendJournal(event(
                        "ROLLBACK_FILE_INTENT",
                        source.relativePath(),
                        current,
                        source.preSha256()
                ));
                IoUtil.copyVerified(backup, temporary, source.preSha256());
                IoUtil.moveAtomic(temporary, worldFile);
                if (!source.preSha256().equals(IoUtil.sha256(worldFile))) {
                    throw new IOException("Rollback verification failed: " + source.relativePath());
                }
                restored++;
                store.appendJournal(event(
                        "ROLLBACK_FILE_DONE",
                        source.relativePath(),
                        current,
                        source.preSha256()
                ));
            }
            JobManifest rolledBack = rollingBack.withState(
                    JobState.ROLLED_BACK,
                    Instant.now().toString(),
                    "rollback_complete_pending_verify"
            );
            store.writeManifest(rolledBack);
            store.appendJournal(event("ROLLBACK_DONE", null, null, null));
            store.appendToolLog(
                    "rollback",
                    "complete restoredFiles=" + restored + " files=" + sources.size()
            );
            return new CommandResult(
                    true,
                    "rollback",
                    "Rollback completed; run verify-rollback",
                    store.directory().toString(),
                    null,
                    Map.of("restoredFiles", restored, "files", sources.size())
            );
        } catch (IOException | RuntimeException failure) {
            fail(store, rollingBack, "Rollback interrupted: " + safeMessage(failure));
            throw failure;
        }
    }

    /**
     * Issues a fresh, short-lived rollback token after shutdown. This is intentionally not
     * exposed by {@link WorldToolMain}; it lets a later maintenance request roll back a retained
     * verified backup without persisting a reusable plaintext token.
     */
    public CommandResult authorizeRollback(Path jobPath) throws IOException {
        JobStore store = JobStore.open(jobPath);
        JobManifest manifest = store.readManifest();
        if (manifest.state() != JobState.VERIFIED) {
            throw new IOException("Fresh rollback authorization requires a VERIFIED job");
        }
        Path world = validateWorld(manifest);
        List<SourceFileRecord> sources = store.readSources();
        verifyBackups(store, sources);
        for (SourceFileRecord source : sources) {
            if (source.postApplySha256() == null) {
                throw new IOException("Rollback job has no post-apply hash: " + source.relativePath());
            }
            String current = IoUtil.sha256(resolveWorldFile(world, source.relativePath()));
            if (!source.postApplySha256().equals(current)
                    && !source.preSha256().equals(current)) {
                throw new IOException(
                        "Rollback would overwrite newer or unknown data: " + source.relativePath()
                );
            }
        }
        String token = store.issueToken("rollback", store.manifestSha256());
        return new CommandResult(
                true,
                "authorize-rollback",
                "Fresh one-time rollback token issued after shutdown validation",
                store.directory().toString(),
                token,
                Map.of("files", sources.size())
        );
    }

    /**
     * Converts a post-apply verification failure into an explicitly rollback-authorized FAILED
     * state. Hash and backup checks prevent this path from authorizing an overwrite of data that
     * changed after apply.
     */
    public CommandResult authorizeFailedVerificationRollback(
            Path jobPath,
            String verificationFailure
    ) throws IOException {
        JobStore store = JobStore.open(jobPath);
        JobManifest manifest = store.readManifest();
        requireState(manifest, JobState.APPLIED);
        Path world = validateWorld(manifest);
        List<SourceFileRecord> sources = store.readSources();
        verifyBackups(store, sources);
        for (SourceFileRecord source : sources) {
            if (source.postApplySha256() == null
                    || !source.postApplySha256().equals(
                    IoUtil.sha256(resolveWorldFile(world, source.relativePath()))
            )) {
                throw new IOException(
                        "Failed verification cannot authorize rollback because current hashes are ambiguous"
                );
            }
        }
        JobManifest failed = manifest.withState(
                JobState.FAILED,
                Instant.now().toString(),
                "Post-apply verification failed: " + safeMessage(
                        new IOException(verificationFailure)
                )
        );
        store.writeManifest(failed);
        store.appendJournal(event("VERIFY_FAILED_ROLLBACK_AUTHORIZED", null, null, null));
        String token = store.issueToken("rollback", store.manifestSha256());
        return new CommandResult(
                true,
                "authorize-failed-verification-rollback",
                "Verification failed; current files and backups are hash-classified for rollback",
                store.directory().toString(),
                token,
                Map.of("files", sources.size())
        );
    }

    public CommandResult verifyRollback(Path jobPath) throws IOException {
        JobStore store = JobStore.open(jobPath);
        JobManifest manifest = store.readManifest();
        requireState(manifest, JobState.ROLLED_BACK);
        Path world = validateWorld(manifest);
        List<SourceFileRecord> sources = store.readSources();
        for (SourceFileRecord source : sources) {
            if (!source.preSha256().equals(IoUtil.sha256(resolveWorldFile(world, source.relativePath())))) {
                throw new IOException("Rollback source hash mismatch: " + source.relativePath());
            }
            if (!source.preSha256().equals(IoUtil.sha256(store.backupPath(source.backupRelativePath())))) {
                throw new IOException("Rollback backup hash mismatch: " + source.relativePath());
            }
        }
        List<LegacyChickenDataAdapter.Target> expected = store.readTargets();
        WorldScanner.Result current = scanner.scan(world, true);
        if (!expected.equals(current.targets())) {
            throw new IOException("Rollback did not restore the original target set");
        }
        store.writeReport("rollback-verification.json", Map.of(
                "passed", true,
                "restoredTargets", current.targets().size(),
                "sourceFiles", sources.size(),
                "verifiedAt", Instant.now().toString()
        ));
        store.appendToolLog(
                "verify-rollback",
                "passed restoredTargets=" + current.targets().size() + " files=" + sources.size()
        );
        return new CommandResult(
                true,
                "verify-rollback",
                "Rollback verified byte-for-byte for all affected region files",
                store.directory().toString(),
                null,
                Map.of("targets", current.targets().size(), "files", sources.size())
        );
    }

    public CommandResult status(Path jobPath) throws IOException {
        JobStore store = JobStore.open(jobPath);
        JobManifest manifest = store.readManifest();
        return new CommandResult(
                true,
                "status",
                manifest.detail(),
                store.directory().toString(),
                null,
                Map.of(
                        "state", manifest.state(),
                        "targets", manifest.totalTargets(),
                        "addressable", manifest.addressableTargets(),
                        "blocked", manifest.blockedTargets()
                )
        );
    }

    private CommandResult verifyRecovery(JobStore store, JobManifest manifest, Path world) throws IOException {
        List<SourceFileRecord> sources = store.readSources();
        if (sources.isEmpty()) {
            throw new IOException("Recovery job has no affected source files");
        }
        int original = 0;
        int applied = 0;
        for (SourceFileRecord source : sources) {
            String current = IoUtil.sha256(resolveWorldFile(world, source.relativePath()));
            if (source.preSha256().equals(current)) {
                original++;
            } else if (source.postApplySha256() != null && source.postApplySha256().equals(current)) {
                applied++;
            } else {
                throw new IOException(
                        "Recovery is ambiguous; current file matches neither pre nor recorded post hash: "
                                + source.relativePath()
                );
            }
        }
        verifyBackups(store, sources);
        boolean rollbackRecovery = manifest.state() == JobState.ROLLING_BACK
                || manifest.detail() != null && manifest.detail().startsWith("Rollback interrupted");

        if (original == sources.size()) {
            if (rollbackRecovery) {
                JobManifest rolledBack = manifest.withState(
                        JobState.ROLLED_BACK,
                        Instant.now().toString(),
                        "rollback_recovery_all_files_original"
                );
                store.writeManifest(rolledBack);
                store.writeReport("rollback-verification.json", Map.of(
                        "passed", true,
                        "recoveredAfterInterruption", true,
                        "sourceFiles", sources.size(),
                        "verifiedAt", Instant.now().toString()
                ));
                store.appendToolLog(
                        "verify-recovery",
                        "rollback complete; all files match original hashes"
                );
                return new CommandResult(
                        true,
                        "verify-rollback-recovery",
                        "Interrupted rollback recovered; all affected files are original",
                        store.directory().toString(),
                        null,
                        Map.of("originalFiles", original, "appliedFiles", 0)
                );
            }
            WorldScanner.Result current = scanner.scan(world, true);
            List<LegacyChickenDataAdapter.Target> expected = store.readTargets();
            if (!expected.equals(current.targets())) {
                throw new IOException("Recovery cannot resume because the original target set changed");
            }
            compareSources(sources, current.affectedFiles());
            JobManifest prepared = manifest.withState(
                    JobState.PREPARED,
                    Instant.now().toString(),
                    "recovery_verified_safe_to_resume_apply"
            );
            store.writeManifest(prepared);
            String applyToken = store.issueToken("apply", store.manifestSha256());
            store.writeReport("apply-verification.json", Map.of(
                    "passed", false,
                    "recoverySafeToResumeApply", true,
                    "filesStillOriginal", original,
                    "filesApplied", 0,
                    "verifiedAt", Instant.now().toString()
            ));
            store.appendToolLog(
                    "verify-recovery",
                    "resume-ready originalFiles=" + original + " appliedFiles=0"
            );
            return new CommandResult(
                    true,
                    "resume-ready",
                    "Interrupted apply made no replacements; a fresh one-time apply token was issued",
                    store.directory().toString(),
                    applyToken,
                    Map.of("originalFiles", original, "appliedFiles", 0)
            );
        }

        if (!rollbackRecovery && applied == sources.size()) {
            WorldScanner.Result current = scanner.scan(world, true);
            if (!current.targets().isEmpty()) {
                throw new IOException("Recovered apply still contains legacy attachments");
            }
            JobManifest verified = manifest.withState(
                    JobState.VERIFIED,
                    Instant.now().toString(),
                    "apply_recovery_all_files_verified"
            );
            store.writeManifest(verified);
            store.writeReport("apply-verification.json", Map.of(
                    "passed", true,
                    "recoveredAfterInterruption", true,
                    "remainingTargets", 0,
                    "sourceFiles", sources.size(),
                    "verifiedAt", Instant.now().toString()
            ));
            String rollbackToken = store.issueToken("rollback", store.manifestSha256());
            store.appendToolLog(
                    "verify-recovery",
                    "apply complete; all files match post hashes and remainingTargets=0"
            );
            return new CommandResult(
                    true,
                    "verify-recovered-apply",
                    "Interrupted apply had completed all replacements and is now verified",
                    store.directory().toString(),
                    rollbackToken,
                    Map.of("originalFiles", 0, "appliedFiles", applied, "remainingTargets", 0)
            );
        }

        JobManifest recoverable = manifest.withState(
                JobState.FAILED,
                Instant.now().toString(),
                "recovery_verified_safe_for_rollback"
        );
        store.writeManifest(recoverable);
        store.writeReport("apply-verification.json", Map.of(
                "passed", false,
                "recoverySafeForRollback", true,
                "filesStillOriginal", original,
                "filesApplied", applied,
                "verifiedAt", Instant.now().toString()
        ));
        String token = store.issueToken("rollback", store.manifestSha256());
        store.appendToolLog(
                "verify-recovery",
                "safe originalFiles=" + original + " appliedFiles=" + applied
        );
        return new CommandResult(
                true,
                "verify-recovery",
                "Interrupted apply is hash-classified and safe for rollback",
                store.directory().toString(),
                token,
                Map.of("originalFiles", original, "appliedFiles", applied)
        );
    }

    private static void verifyBackups(
            JobStore store,
            List<SourceFileRecord> sources
    ) throws IOException {
        for (SourceFileRecord source : sources) {
            Path backup = store.backupPath(source.backupRelativePath());
            if (!source.preSha256().equals(IoUtil.sha256(backup))) {
                throw new IOException("Recovery backup hash mismatch: " + source.relativePath());
            }
        }
    }

    private Path validateWorld(JobManifest manifest) throws IOException {
        Path world = worldResolver.resolve(Path.of(manifest.worldRoot()));
        String fingerprint = IoUtil.sha256(world.resolve("level.dat"));
        if (!manifest.worldFingerprint().equals(fingerprint)) {
            throw new IOException("World fingerprint does not match job");
        }
        return world;
    }

    private static Path requireExternalJobRoot(Path world, Path jobsRoot) throws IOException {
        if (!jobsRoot.isAbsolute()) {
            throw new IOException("--job-root must be absolute");
        }
        Path normalized = jobsRoot.normalize().toAbsolutePath();
        WorldAccessPolicy.rejectProtectedRoots(normalized);
        if (normalized.startsWith(world)) {
            throw new IOException("Job root must be outside the world copy");
        }
        return normalized;
    }

    private static Path requireRegularFile(Path supplied, String description) throws IOException {
        if (!supplied.isAbsolute()) {
            throw new IOException(description + " path must be absolute");
        }
        Path normalized = supplied.normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw new IOException(description + " is not a regular file");
        }
        return normalized.toRealPath();
    }

    private static Path resolveWorldFile(Path world, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.indexOf('\\') >= 0) {
            throw new IOException("Invalid relative world path in job");
        }
        Path resolved = world.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        return WorldAccessPolicy.requireContainedRegularFile(world, resolved);
    }

    private static void compareSources(
            List<SourceFileRecord> expected,
            List<WorldScanner.AffectedFile> actual
    ) throws IOException {
        Map<String, WorldScanner.AffectedFile> byPath = new HashMap<>();
        for (WorldScanner.AffectedFile file : actual) {
            byPath.put(file.relativePath(), file);
        }
        if (expected.size() != actual.size()) {
            throw new IOException("Affected source file set changed after scan");
        }
        for (SourceFileRecord source : expected) {
            WorldScanner.AffectedFile file = byPath.get(source.relativePath());
            if (file == null
                    || file.size() != source.size()
                    || !file.sha256().equals(source.preSha256())) {
                throw new IOException("Affected source changed after scan: " + source.relativePath());
            }
        }
    }

    private static void requireDiskCapacity(
            Path world,
            JobStore store,
            List<SourceFileRecord> sources
    ) throws IOException {
        long total = 0;
        long largest = 0;
        for (SourceFileRecord source : sources) {
            total = Math.addExact(total, source.size());
            largest = Math.max(largest, source.size());
        }
        if (total > JobStore.MAX_BACKUP_BYTES) {
            throw new IOException("Backup total exceeds hard byte limit");
        }
        FileStore jobDisk = Files.getFileStore(store.directory());
        FileStore worldDisk = Files.getFileStore(world);
        try {
            if (jobDisk.equals(worldDisk)) {
                long required = Math.addExact(
                        Math.addExact(total, largest),
                        DISK_MARGIN_BYTES
                );
                if (jobDisk.getUsableSpace() < required) {
                    throw new IOException(
                            "Insufficient shared disk space for backups and copy-on-write region"
                    );
                }
            } else {
                if (jobDisk.getUsableSpace() < Math.addExact(total, DISK_MARGIN_BYTES)) {
                    throw new IOException("Insufficient disk space for verified backups");
                }
                if (worldDisk.getUsableSpace() < Math.addExact(largest, DISK_MARGIN_BYTES)) {
                    throw new IOException("Insufficient world disk space for copy-on-write region");
                }
            }
        } catch (ArithmeticException overflow) {
            throw new IOException("Required disk space calculation overflow", overflow);
        }
    }

    private static Map<String, Map<Integer, List<LegacyChickenDataAdapter.Target>>> groupTargets(
            List<LegacyChickenDataAdapter.Target> targets
    ) {
        LinkedHashMap<String, Map<Integer, List<LegacyChickenDataAdapter.Target>>> byFile =
                new LinkedHashMap<>();
        targets.stream()
                .sorted(Comparator
                        .comparing(WorldRepairService::storageRelativePath)
                        .thenComparingInt(LegacyChickenDataAdapter.Target::chunkIndex)
                        .thenComparing(LegacyChickenDataAdapter.Target::entityUuid))
                .forEach(target -> byFile
                        .computeIfAbsent(storageRelativePath(target), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(target.chunkIndex(), ignored -> new ArrayList<>())
                        .add(target));
        return byFile;
    }

    private static String storageRelativePath(LegacyChickenDataAdapter.Target target) {
        if (!target.externalChunk()) {
            return target.regionRelativePath();
        }
        int separator = target.regionRelativePath().lastIndexOf('/');
        String parent = separator < 0
                ? ""
                : target.regionRelativePath().substring(0, separator + 1);
        return parent + "c." + target.chunkX() + "." + target.chunkZ() + ".mcc";
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

    private static void requireState(JobManifest manifest, JobState expected) throws IOException {
        if (manifest.state() != expected) {
            throw new IOException("Job state is " + manifest.state() + "; expected " + expected);
        }
    }

    private static void fail(JobStore store, JobManifest manifest, String detail) throws IOException {
        JobManifest failed = manifest.withState(JobState.FAILED, Instant.now().toString(), detail);
        store.writeManifest(failed);
        store.appendJournal(event("FAILED", null, null, null));
        store.appendToolLog("failed", detail);
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message.length() <= 512 ? message : message.substring(0, 512);
    }

    @FunctionalInterface
    private interface WorldResolver {
        Path resolve(Path supplied) throws IOException;
    }

    @FunctionalInterface
    private interface JobRootResolver {
        Path resolve(Path world, Path supplied) throws IOException;
    }
}
