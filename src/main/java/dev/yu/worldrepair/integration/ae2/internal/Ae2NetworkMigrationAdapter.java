package dev.yu.worldrepair.integration.ae2.internal;

import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import com.google.gson.JsonObject;
import dev.yu.worldrepair.integration.EnvironmentFingerprint;
import dev.yu.worldrepair.migration.Hashing;
import dev.yu.worldrepair.migration.MigrationCandidate;
import dev.yu.worldrepair.migration.MigrationJobRepository;
import dev.yu.worldrepair.migration.MigrationManifest;
import dev.yu.worldrepair.migration.MigrationResult;
import dev.yu.worldrepair.migration.MigrationState;
import dev.yu.worldrepair.migration.SourceAdapterBridge;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.core.SectionPos;
import org.slf4j.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exact-version AE2 19.2.17 adapter. All storage mutations use AE2's public MEStorage API on the
 * server thread. Original AEKey NBT is backed up before extraction and decoded by AE2 on restore.
 */
public final class Ae2NetworkMigrationAdapter implements SourceAdapterBridge {
    private static final String ADAPTER_ID = "ae2-network-missing-content/19.2.17";
    private static final ResourceLocation MISSING_CONTENT =
            ResourceLocation.fromNamespaceAndPath("ae2", "missing_content");
    private static final int MAX_CANDIDATES = 1_024;
    private static final long MAX_TOTAL_BACKUP_BYTES = 64L * 1_024 * 1_024;

    private final Logger logger;

    public Ae2NetworkMigrationAdapter(Logger logger) {
        this.logger = logger;
    }

    @Override
    public String adapterId() {
        return ADAPTER_ID;
    }

    @Override
    public MigrationResult scanAndPrepare(
            CommandSourceStack source,
            BlockPos position,
            MigrationJobRepository repository,
            String operator,
            String worldFingerprint
    ) {
        try {
            requireServerThread(source.getServer());
            ServerLevel level = source.getLevel();
            StorageHandle handle = storageAt(level, position);
            Snapshot first = snapshotMissing(handle);
            if (first.candidates.isEmpty()) {
                return MigrationResult.failure("No AE2 missing content found at the targeted grid", null);
            }
            if (first.candidates.size() > MAX_CANDIDATES) {
                return MigrationResult.failure("Candidate count exceeds hard limit " + MAX_CANDIDATES, null);
            }

            String jobId = repository.newJobId();
            List<MigrationCandidate> persisted = new ArrayList<>(first.candidates.size());
            for (LiveCandidate candidate : first.candidates) {
                repository.writeBlob(jobId, candidate.semanticHash, candidate.tag);
                persisted.add(new MigrationCandidate(
                        candidate.registryId,
                        candidate.amount,
                        candidate.semanticHash,
                        candidate.semanticHash
                ));
            }

            Snapshot second = snapshotMissing(handle);
            if (!first.fingerprint.equals(second.fingerprint)) {
                return MigrationResult.failure(
                        "AE2 grid changed between scan and dry-run; no job was activated",
                        null
                );
            }

            Instant now = Instant.now();
            MigrationManifest manifest = new MigrationManifest(
                    1,
                    jobId,
                    now.toString(),
                    now.toString(),
                    operator,
                    ADAPTER_ID,
                    MigrationState.PREPARED,
                    worldFingerprint,
                    level.dimension().location().toString(),
                    position.getX(),
                    position.getY(),
                    position.getZ(),
                    first.fingerprint,
                    Map.of(
                            "minecraft", EnvironmentFingerprint.version("minecraft"),
                            "neoforge", EnvironmentFingerprint.version("neoforge"),
                            "ae2", EnvironmentFingerprint.version("ae2"),
                            "yuworldrepair", EnvironmentFingerprint.version("yuworldrepair")
                    ),
                    List.copyOf(persisted),
                    ""
            );
            repository.create(manifest);
            repository.writeVerification(jobId, Map.of(
                    "at", Instant.now().toString(),
                    "operation", "dry-run",
                    "result", "pass",
                    "sourceFingerprint", first.fingerprint,
                    "candidateCount", persisted.size()
            ));
            return MigrationResult.success(
                    "Prepared " + persisted.size() + " AE2 missing-content records with verified backups",
                    manifest
            );
        } catch (Exception failure) {
            logger.warn("[YuWorldRepair] AE2 scan/dry-run refused", failure);
            return MigrationResult.failure("AE2 scan refused: " + safeMessage(failure), null);
        }
    }

    @Override
    public MigrationResult quarantine(
            MinecraftServer server,
            MigrationManifest manifest,
            MigrationJobRepository repository
    ) {
        if (manifest.state() != MigrationState.PREPARED
                && manifest.state() != MigrationState.QUARANTINING) {
            return MigrationResult.failure("Job is not prepared for quarantine: " + manifest.state(), manifest);
        }
        try {
            requireServerThread(server);
            StorageHandle handle = storageFor(server, manifest);
            Snapshot snapshot = snapshotMissing(handle);
            Journal journal = journal(repository, manifest.jobId());

            if (manifest.state() == MigrationState.PREPARED
                    && !manifest.sourceFingerprint().equals(snapshot.fingerprint)) {
                return MigrationResult.failure("Source fingerprint changed; quarantine refused", manifest);
            }
            verifyBackups(repository, manifest);
            MigrationManifest active = manifest;
            if (manifest.state() == MigrationState.PREPARED) {
                active = manifest.withState(MigrationState.QUARANTINING, Instant.now().toString(), "");
                repository.writeManifest(active);
            }

            Map<String, LiveCandidate> liveByHash = snapshot.byHash();
            for (MigrationCandidate candidate : active.candidates()) {
                if (journal.quarantineDone.contains(candidate.semanticHash())) {
                    continue;
                }
                LiveCandidate live = liveByHash.get(candidate.semanticHash());
                Long intendedPre = journal.quarantineIntentPre.get(candidate.semanticHash());
                if (intendedPre != null && live == null) {
                    repository.appendChange(active.jobId(), change(
                            "Q_DONE_RECOVERED",
                            candidate.semanticHash(),
                            candidate.amount(),
                            0L
                    ));
                    continue;
                }
                if (live == null || live.amount != candidate.amount()) {
                    return failActive(
                            repository,
                            active,
                            "Candidate amount changed; quarantine refused for " + candidate.registryId()
                    );
                }
                long simulated = handle.storage.extract(
                        live.key,
                        candidate.amount(),
                        Actionable.SIMULATE,
                        IActionSource.empty()
                );
                if (simulated != candidate.amount()) {
                    return failActive(repository, active, "AE2 simulation rejected exact extraction");
                }
                if (intendedPre == null) {
                    repository.appendChange(active.jobId(), change(
                            "Q_INTENT",
                            candidate.semanticHash(),
                            candidate.amount(),
                            candidate.amount()
                    ));
                }
                long extracted = handle.storage.extract(
                        live.key,
                        candidate.amount(),
                        Actionable.MODULATE,
                        IActionSource.empty()
                );
                if (extracted != candidate.amount()) {
                    if (extracted > 0) {
                        handle.storage.insert(live.key, extracted, Actionable.MODULATE, IActionSource.empty());
                    }
                    return failActive(repository, active, "AE2 returned a partial extraction; rolled back");
                }
                repository.appendChange(active.jobId(), change(
                        "Q_DONE",
                        candidate.semanticHash(),
                        candidate.amount(),
                        0L
                ));
            }

            Snapshot after = snapshotMissing(handle);
            Set<String> remaining = after.byHash().keySet();
            for (MigrationCandidate candidate : active.candidates()) {
                if (remaining.contains(candidate.semanticHash())) {
                    return failActive(repository, active, "Post-quarantine verification found remaining content");
                }
            }
            MigrationManifest completed = active.withState(
                    MigrationState.QUARANTINED,
                    Instant.now().toString(),
                    ""
            );
            repository.writeManifest(completed);
            repository.writeVerification(completed.jobId(), Map.of(
                    "at", Instant.now().toString(),
                    "operation", "quarantine",
                    "result", "pass",
                    "candidateCount", completed.candidates().size()
            ));
            return MigrationResult.success("AE2 missing content quarantined and verified", completed);
        } catch (Exception failure) {
            logger.error("[YuWorldRepair] AE2 quarantine failed", failure);
            return MigrationResult.failure("AE2 quarantine failed safely: " + safeMessage(failure), manifest);
        }
    }

    @Override
    public MigrationResult restore(
            MinecraftServer server,
            MigrationManifest manifest,
            MigrationJobRepository repository
    ) {
        if (manifest.state() != MigrationState.QUARANTINED
                && manifest.state() != MigrationState.QUARANTINING
                && manifest.state() != MigrationState.RESTORING) {
            return MigrationResult.failure("Job is not restorable: " + manifest.state(), manifest);
        }
        try {
            requireServerThread(server);
            StorageHandle handle = storageFor(server, manifest);
            verifyBackups(repository, manifest);
            Journal journal = journal(repository, manifest.jobId());
            boolean recoveryToOriginal = manifest.state() == MigrationState.QUARANTINING;
            MigrationManifest active = manifest;

            if (manifest.state() != MigrationState.RESTORING) {
                repository.appendChange(manifest.jobId(), Map.of(
                        "at", Instant.now().toString(),
                        "event", "R_BEGIN",
                        "mode", recoveryToOriginal ? "RECOVER_TO_ORIGINAL" : "ADD_BACKUP"
                ));
                active = manifest.withState(MigrationState.RESTORING, Instant.now().toString(), "");
                repository.writeManifest(active);
                journal = journal(repository, manifest.jobId());
            } else {
                recoveryToOriginal = journal.restoreRecoveryMode;
            }

            for (MigrationCandidate candidate : active.candidates()) {
                CompoundTag tag = repository.readBlob(active.jobId(), candidate.blobHash());
                AEKey key = AEKey.fromTagGeneric(handle.level.registryAccess(), tag);
                if (key == null) {
                    return failActive(repository, active, "AE2 could not decode backup " + candidate.semanticHash());
                }
                if (journal.restoreDone.contains(candidate.semanticHash())) {
                    continue;
                }

                RestoreIntent intent = journal.restoreIntents.get(candidate.semanticHash());
                long current = available(handle.storage, key);
                if (intent == null) {
                    long delta;
                    long expected;
                    if (recoveryToOriginal) {
                        if (current > candidate.amount()) {
                            return failActive(repository, active, "Recovery is ambiguous: live amount exceeds backup");
                        }
                        delta = candidate.amount() - current;
                        expected = candidate.amount();
                    } else {
                        delta = candidate.amount();
                        expected = Math.addExact(current, delta);
                    }
                    intent = new RestoreIntent(current, delta, expected);
                    repository.appendChange(active.jobId(), Map.of(
                            "at", Instant.now().toString(),
                            "event", "R_INTENT",
                            "hash", candidate.semanticHash(),
                            "preAmount", current,
                            "delta", delta,
                            "expected", expected
                    ));
                } else if (current == intent.expected) {
                    repository.appendChange(active.jobId(), change(
                            "R_DONE_RECOVERED",
                            candidate.semanticHash(),
                            intent.delta,
                            current
                    ));
                    continue;
                } else if (current != intent.preAmount) {
                    return failActive(repository, active, "Restore recovery is ambiguous; live amount changed");
                }

                if (intent.delta > 0) {
                    long accepted = handle.storage.insert(
                            key,
                            intent.delta,
                            Actionable.SIMULATE,
                            IActionSource.empty()
                    );
                    if (accepted != intent.delta) {
                        return failActive(repository, active, "AE2 has insufficient capacity for exact restore");
                    }
                    long inserted = handle.storage.insert(
                            key,
                            intent.delta,
                            Actionable.MODULATE,
                            IActionSource.empty()
                    );
                    if (inserted != intent.delta) {
                        if (inserted > 0) {
                            handle.storage.extract(key, inserted, Actionable.MODULATE, IActionSource.empty());
                        }
                        return failActive(repository, active, "AE2 returned a partial restore; rolled back");
                    }
                }
                long after = available(handle.storage, key);
                if (after != intent.expected) {
                    return failActive(repository, active, "Post-restore amount verification failed");
                }
                repository.appendChange(active.jobId(), change(
                        "R_DONE",
                        candidate.semanticHash(),
                        intent.delta,
                        after
                ));
            }

            MigrationManifest completed = active.withState(MigrationState.RESTORED, Instant.now().toString(), "");
            repository.writeManifest(completed);
            repository.writeVerification(completed.jobId(), Map.of(
                    "at", Instant.now().toString(),
                    "operation", "restore",
                    "result", "pass",
                    "candidateCount", completed.candidates().size()
            ));
            return MigrationResult.success("AE2 backup restored and verified", completed);
        } catch (Exception failure) {
            logger.error("[YuWorldRepair] AE2 restore failed", failure);
            return MigrationResult.failure("AE2 restore failed safely: " + safeMessage(failure), manifest);
        }
    }

    @Override
    public MigrationResult verify(
            MinecraftServer server,
            MigrationManifest manifest,
            MigrationJobRepository repository
    ) {
        try {
            requireServerThread(server);
            StorageHandle handle = storageFor(server, manifest);
            verifyBackups(repository, manifest);
            boolean passed;
            String detail;
            if (manifest.state() == MigrationState.PREPARED) {
                passed = manifest.sourceFingerprint().equals(snapshotMissing(handle).fingerprint);
                detail = passed ? "prepared source fingerprint matches" : "prepared source changed";
            } else if (manifest.state() == MigrationState.QUARANTINED) {
                Set<String> present = snapshotMissing(handle).byHash().keySet();
                passed = manifest.candidates().stream().noneMatch(c -> present.contains(c.semanticHash()));
                detail = passed ? "quarantine absence verified" : "quarantined content is present";
            } else if (manifest.state() == MigrationState.RESTORED) {
                Journal journal = journal(repository, manifest.jobId());
                passed = true;
                for (MigrationCandidate candidate : manifest.candidates()) {
                    RestoreIntent intent = journal.restoreIntents.get(candidate.semanticHash());
                    if (intent == null) {
                        passed = false;
                        break;
                    }
                    AEKey key = AEKey.fromTagGeneric(
                            handle.level.registryAccess(),
                            repository.readBlob(manifest.jobId(), candidate.blobHash())
                    );
                    if (key == null || available(handle.storage, key) < intent.expected) {
                        passed = false;
                        break;
                    }
                }
                detail = passed ? "restored amounts verified" : "restored amount mismatch";
            } else {
                passed = false;
                detail = "job is incomplete and requires explicit recovery: " + manifest.state();
            }
            repository.writeVerification(manifest.jobId(), Map.of(
                    "at", Instant.now().toString(),
                    "operation", "verify",
                    "result", passed ? "pass" : "fail",
                    "detail", detail
            ));
            return passed
                    ? MigrationResult.success(detail, manifest)
                    : MigrationResult.failure(detail, manifest);
        } catch (Exception failure) {
            return MigrationResult.failure("Verification failed: " + safeMessage(failure), manifest);
        }
    }

    private static StorageHandle storageFor(MinecraftServer server, MigrationManifest manifest) throws IOException {
        ResourceLocation dimensionId = ResourceLocation.tryParse(manifest.dimension());
        if (dimensionId == null) {
            throw new IOException("Invalid dimension in manifest");
        }
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dimensionId);
        ServerLevel level = server.getLevel(key);
        if (level == null) {
            throw new IOException("Target dimension is not loaded");
        }
        return storageAt(level, new BlockPos(manifest.x(), manifest.y(), manifest.z()));
    }

    private static StorageHandle storageAt(ServerLevel level, BlockPos position) throws IOException {
        if (!level.hasChunk(
                SectionPos.blockToSectionCoord(position.getX()),
                SectionPos.blockToSectionCoord(position.getZ())
        )) {
            throw new IOException("Target chunk is not loaded; refusing forced chunk load");
        }
        IInWorldGridNodeHost host = GridHelper.getNodeHost(level, position);
        if (host == null) {
            throw new IOException("No AE2 grid host at target position");
        }
        IGridNode node = host.getGridNode(null);
        if (node == null) {
            for (Direction direction : Direction.values()) {
                node = host.getGridNode(direction);
                if (node != null) {
                    break;
                }
            }
        }
        if (node == null || node.getGrid() == null) {
            throw new IOException("AE2 node is not attached to a grid");
        }
        IGrid grid = node.getGrid();
        return new StorageHandle(level, grid.getStorageService().getInventory());
    }

    private static Snapshot snapshotMissing(StorageHandle handle) throws IOException {
        List<LiveCandidate> candidates = new ArrayList<>();
        long totalBackupBytes = 0;
        for (Object2LongMap.Entry<AEKey> entry : handle.storage.getAvailableStacks()) {
            AEKey key = entry.getKey();
            long amount = entry.getLongValue();
            if (amount <= 0 || !MISSING_CONTENT.equals(key.getId())) {
                continue;
            }
            CompoundTag tag = key.toTagGeneric(handle.level.registryAccess());
            Hashing.NbtDigest digest = Hashing.nbtDigest(tag);
            if (totalBackupBytes > MAX_TOTAL_BACKUP_BYTES - digest.encodedBytes()) {
                throw new IOException("AE2 missing-content backup exceeds hard limit of "
                        + MAX_TOTAL_BACKUP_BYTES + " bytes");
            }
            totalBackupBytes += digest.encodedBytes();
            String semanticHash = digest.sha256();
            String id = originalRegistryId(tag);
            candidates.add(new LiveCandidate(key, amount, tag, semanticHash, id));
            if (candidates.size() > MAX_CANDIDATES) {
                throw new IOException("AE2 missing-content count exceeds hard limit");
            }
        }
        candidates.sort(Comparator.comparing(candidate -> candidate.semanticHash));
        StringBuilder fingerprintInput = new StringBuilder(candidates.size() * 96);
        for (LiveCandidate candidate : candidates) {
            fingerprintInput.append(candidate.semanticHash)
                    .append(':')
                    .append(candidate.amount)
                    .append('\n');
        }
        return new Snapshot(List.copyOf(candidates), Hashing.textSha256(fingerprintInput.toString()));
    }

    private static String originalRegistryId(CompoundTag tag) {
        String id = tag.getString("id");
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        return parsed == null ? "unknown" : parsed.toString();
    }

    private static long available(MEStorage storage, AEKey key) {
        return storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, IActionSource.empty());
    }

    private static void verifyBackups(
            MigrationJobRepository repository,
            MigrationManifest manifest
    ) throws IOException {
        for (MigrationCandidate candidate : manifest.candidates()) {
            CompoundTag tag = repository.readBlob(manifest.jobId(), candidate.blobHash());
            String actual = Hashing.nbtSemanticHash(tag);
            if (!candidate.semanticHash().equals(actual)) {
                throw new IOException("Backup hash mismatch for " + candidate.registryId());
            }
        }
    }

    private static Journal journal(MigrationJobRepository repository, String jobId) throws IOException {
        Set<String> quarantineDone = new HashSet<>();
        Map<String, Long> quarantineIntentPre = new HashMap<>();
        Set<String> restoreDone = new HashSet<>();
        Map<String, RestoreIntent> restoreIntents = new LinkedHashMap<>();
        boolean recovery = false;
        for (JsonObject change : repository.readChanges(jobId)) {
            String event = string(change, "event");
            String hash = string(change, "hash");
            switch (event) {
                case "Q_INTENT" -> quarantineIntentPre.put(hash, longValue(change, "preAmount"));
                case "Q_DONE", "Q_DONE_RECOVERED" -> quarantineDone.add(hash);
                case "R_BEGIN" -> recovery = "RECOVER_TO_ORIGINAL".equals(string(change, "mode"));
                case "R_INTENT" -> restoreIntents.put(hash, new RestoreIntent(
                        longValue(change, "preAmount"),
                        longValue(change, "delta"),
                        longValue(change, "expected")
                ));
                case "R_DONE", "R_DONE_RECOVERED" -> restoreDone.add(hash);
                default -> {
                }
            }
        }
        return new Journal(
                quarantineDone,
                quarantineIntentPre,
                restoreDone,
                restoreIntents,
                recovery
        );
    }

    private static Map<String, Object> change(String event, String hash, long amount, long after) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("at", Instant.now().toString());
        change.put("event", event);
        change.put("hash", hash);
        change.put("amount", amount);
        change.put("after", after);
        return change;
    }

    private static MigrationResult failActive(
            MigrationJobRepository repository,
            MigrationManifest active,
            String message
    ) throws IOException {
        MigrationManifest failed = active.withState(active.state(), Instant.now().toString(), message);
        repository.writeManifest(failed);
        repository.writeVerification(active.jobId(), Map.of(
                "at", Instant.now().toString(),
                "result", "fail",
                "detail", message
        ));
        return MigrationResult.failure(message, failed);
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : "";
    }

    private static long longValue(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsLong() : 0L;
    }

    private static void requireServerThread(MinecraftServer server) throws IOException {
        if (!server.isSameThread()) {
            throw new IOException("AE2 migration must run on the server thread");
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message.length() <= 256 ? message : message.substring(0, 256);
    }

    private record StorageHandle(ServerLevel level, MEStorage storage) {
    }

    private record LiveCandidate(
            AEKey key,
            long amount,
            CompoundTag tag,
            String semanticHash,
            String registryId
    ) {
    }

    private record Snapshot(List<LiveCandidate> candidates, String fingerprint) {
        private Map<String, LiveCandidate> byHash() {
            Map<String, LiveCandidate> result = new HashMap<>();
            for (LiveCandidate candidate : candidates) {
                result.put(candidate.semanticHash, candidate);
            }
            return result;
        }
    }

    private record RestoreIntent(long preAmount, long delta, long expected) {
    }

    private record Journal(
            Set<String> quarantineDone,
            Map<String, Long> quarantineIntentPre,
            Set<String> restoreDone,
            Map<String, RestoreIntent> restoreIntents,
            boolean restoreRecoveryMode
    ) {
    }
}
