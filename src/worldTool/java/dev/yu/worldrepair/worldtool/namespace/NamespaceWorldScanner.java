package dev.yu.worldrepair.worldtool.namespace;

import dev.yu.worldrepair.worldtool.anvil.RegionFile;
import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.nbt.Nbt;
import dev.yu.worldrepair.worldtool.nbt.NbtFile;
import dev.yu.worldrepair.worldtool.scan.WorldLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class NamespaceWorldScanner {
    public static final int MAX_REGIONS = 1_000_000;
    public static final int MAX_CHUNKS = 64_000_000;
    public static final int MAX_TARGETS = 262_144;
    public static final int MAX_COVERAGE_GAPS = 16_384;
    public static final int MAX_PLAYER_FILES = 100_000;
    public static final long MAX_SCAN_MILLIS = 24L * 60 * 60 * 1_000;

    private final NamespaceChunkAdapter adapter;
    private final Nbt.Limits nbtLimits;

    public NamespaceWorldScanner(NamespaceChunkAdapter adapter, Nbt.Limits nbtLimits) {
        this.adapter = adapter;
        this.nbtLimits = nbtLimits;
    }

    public record AffectedFile(String relativePath, long size, String sha256) {
    }

    public record CoverageGap(String relativePath, String reason) {
    }

    public record Result(
            List<NamespaceTarget> targets,
            List<AffectedFile> affectedFiles,
            List<CoverageGap> coverageGaps,
            List<String> warnings,
            int regionsScanned,
            int chunksScanned,
            long regionBytesScanned,
            int deferredTargets,
            List<NamespaceTarget> deferredTargetDetails,
            boolean regionDataIncluded,
            int scanWorkers,
            Map<NamespaceTarget.Action, Integer> targetsByAction
    ) {
    }

    @FunctionalInterface
    public interface ProgressListener {
        void update(Progress progress) throws IOException;
    }

    public record Progress(
            int regionFilesCompleted,
            int regionFilesTotal,
            long regionBytesCompleted,
            long regionBytesTotal,
            int chunksScanned,
            int targetsFound,
            int coverageGaps,
            long elapsedMillis
    ) {
    }

    public record Options(
            boolean scanRegionFiles,
            boolean allowQioTypeCleanup,
            int workers,
            Set<String> regionRelativeFilter,
            Set<String> standaloneRelativeFilter,
            boolean trustedWorldLock,
            ProgressListener progressListener
    ) {
        public Options {
            if (workers < 1 || workers > 16) {
                throw new IllegalArgumentException("Namespace scan workers must be 1..16");
            }
            regionRelativeFilter = regionRelativeFilter == null
                    ? null
                    : Set.copyOf(regionRelativeFilter);
            standaloneRelativeFilter = standaloneRelativeFilter == null
                    ? null
                    : Set.copyOf(standaloneRelativeFilter);
            progressListener = progressListener == null ? ignored -> { } : progressListener;
        }

        public static Options full(int workers, boolean allowQioTypeCleanup) {
            return new Options(
                    true,
                    allowQioTypeCleanup,
                    workers,
                    null,
                    null,
                    false,
                    null
            );
        }

        public static Options metadataOnly(int workers, boolean allowQioTypeCleanup) {
            return new Options(
                    false,
                    allowQioTypeCleanup,
                    workers,
                    Set.of(),
                    null,
                    false,
                    null
            );
        }

        public Options withProgressListener(ProgressListener listener) {
            return new Options(
                    scanRegionFiles,
                    allowQioTypeCleanup,
                    workers,
                    regionRelativeFilter,
                    standaloneRelativeFilter,
                    trustedWorldLock,
                    listener
            );
        }

        public Options withTrustedWorldLock(boolean trusted) {
            return new Options(
                    scanRegionFiles,
                    allowQioTypeCleanup,
                    workers,
                    regionRelativeFilter,
                    standaloneRelativeFilter,
                    trusted,
                    progressListener
            );
        }

        public Options selecting(
                Set<String> regionFiles,
                Set<String> standaloneFiles
        ) {
            return new Options(
                    scanRegionFiles,
                    allowQioTypeCleanup,
                    workers,
                    regionFiles,
                    standaloneFiles,
                    trustedWorldLock,
                    progressListener
            );
        }

        boolean acceptsRegion(String relative) {
            return scanRegionFiles
                    && (regionRelativeFilter == null
                    || regionRelativeFilter.contains(relative));
        }

        boolean acceptsStandalone(String relative) {
            return standaloneRelativeFilter == null
                    || standaloneRelativeFilter.contains(relative);
        }
    }

    private record RegionWork(
            WorldLayout.RegionDirectory directory,
            Path path,
            String relativePath,
            long size
    ) {
    }

    private record RegionScan(
            List<NamespaceTarget> targets,
            List<AffectedFile> affectedFiles,
            CoverageGap coverageGap,
            String warning,
            int regions,
            int chunks,
            long bytes
    ) {
    }

    private record RegionTotals(
            int files,
            int regions,
            int chunks,
            long bytes,
            long totalBytes
    ) {
    }

    public Result scan(Path worldRoot, NamespacePolicy policy) throws IOException {
        return scan(
                worldRoot,
                policy,
                OrphanItemIndex.load(worldRoot, policy, nbtLimits),
                Options.full(1, true)
        );
    }

    public Result scan(
            Path worldRoot,
            NamespacePolicy policy,
            OrphanItemIndex itemIndex
    ) throws IOException {
        return scan(worldRoot, policy, itemIndex, Options.full(1, true));
    }

    public Result scan(
            Path worldRoot,
            NamespacePolicy policy,
            OrphanItemIndex itemIndex,
            Options options
    ) throws IOException {
        long started = System.nanoTime();
        ArrayList<NamespaceTarget> targets = new ArrayList<>();
        LinkedHashMap<String, AffectedFile> affected = new LinkedHashMap<>();
        ArrayList<CoverageGap> gaps = new ArrayList<>();
        ArrayList<String> warnings = auditUnsupportedStores(worldRoot, policy);
        if (!options.scanRegionFiles()) {
            warnings.add("region_data_excluded_by_signed_scope");
        }
        RegionTotals regionTotals = scanRegions(
                worldRoot,
                policy,
                itemIndex,
                options,
                started,
                targets,
                affected,
                gaps,
                warnings
        );
        ArrayList<NamespaceTarget> deferredTargetDetails = new ArrayList<>();
        scanPlayerData(
                worldRoot,
                policy,
                itemIndex,
                options,
                started,
                targets,
                affected,
                gaps
        );
        scanSavedData(
                worldRoot,
                policy,
                itemIndex,
                options,
                started,
                targets,
                affected,
                gaps,
                warnings,
                deferredTargetDetails
        );
        options.progressListener().update(new Progress(
                regionTotals.files(),
                regionTotals.files(),
                regionTotals.bytes(),
                regionTotals.totalBytes(),
                regionTotals.chunks(),
                targets.size(),
                gaps.size(),
                elapsedMillis(started)
        ));

        targets.sort(Comparator
                .comparing(NamespaceTarget::dimension)
                .thenComparing(NamespaceTarget::regionRelativePath)
                .thenComparingInt(NamespaceTarget::chunkIndex)
                .thenComparing(NamespaceTarget::nbtPath)
                .thenComparing(target -> target.action().name()));
        deferredTargetDetails.sort(Comparator
                .comparing(NamespaceTarget::dimension)
                .thenComparing(NamespaceTarget::regionRelativePath)
                .thenComparingInt(NamespaceTarget::chunkIndex)
                .thenComparing(NamespaceTarget::nbtPath)
                .thenComparing(target -> target.action().name()));
        EnumMap<NamespaceTarget.Action, Integer> byAction =
                new EnumMap<>(NamespaceTarget.Action.class);
        for (NamespaceTarget target : targets) {
            byAction.merge(target.action(), 1, Integer::sum);
        }
        gaps.sort(Comparator
                .comparing(CoverageGap::relativePath)
                .thenComparing(CoverageGap::reason));
        warnings.sort(String::compareTo);
        ArrayList<AffectedFile> affectedFiles = new ArrayList<>(affected.values());
        affectedFiles.sort(Comparator.comparing(AffectedFile::relativePath));
        return new Result(
                List.copyOf(targets),
                List.copyOf(affectedFiles),
                List.copyOf(gaps),
                List.copyOf(warnings),
                regionTotals.regions(),
                regionTotals.chunks(),
                regionTotals.bytes(),
                deferredTargetDetails.size(),
                List.copyOf(deferredTargetDetails),
                options.scanRegionFiles(),
                options.workers(),
                Map.copyOf(byAction)
        );
    }

    private RegionTotals scanRegions(
            Path worldRoot,
            NamespacePolicy policy,
            OrphanItemIndex itemIndex,
            Options options,
            long started,
            List<NamespaceTarget> targets,
            Map<String, AffectedFile> affected,
            List<CoverageGap> gaps,
            List<String> warnings
    ) throws IOException {
        if (!options.scanRegionFiles()) {
            options.progressListener().update(new Progress(
                    0,
                    0,
                    0,
                    0,
                    0,
                    targets.size(),
                    gaps.size(),
                    elapsedMillis(started)
            ));
            return new RegionTotals(0, 0, 0, 0, 0);
        }

        ArrayList<RegionWork> work = new ArrayList<>();
        long totalBytes = 0;
        for (WorldLayout.RegionDirectory directory :
                WorldLayout.discoverRegionDirectories(worldRoot)) {
            for (Path region : WorldLayout.regionFiles(worldRoot, directory)) {
                requireBudget(started);
                String relative = normalize(worldRoot.relativize(region));
                if (!options.acceptsRegion(relative)) {
                    continue;
                }
                if (work.size() >= MAX_REGIONS) {
                    throw new ScanAbort("World exceeds namespace region hard limit");
                }
                long size = Files.size(region);
                try {
                    totalBytes = Math.addExact(totalBytes, size);
                } catch (ArithmeticException overflow) {
                    throw new ScanAbort("World region byte count overflow", overflow);
                }
                work.add(new RegionWork(directory, region, relative, size));
            }
        }

        options.progressListener().update(new Progress(
                0,
                work.size(),
                0,
                totalBytes,
                0,
                targets.size(),
                gaps.size(),
                elapsedMillis(started)
        ));
        if (work.isEmpty()) {
            return new RegionTotals(0, 0, 0, 0, 0);
        }

        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "yuworldrepair-namespace-scan-" + threadNumber.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(options.workers(), work.size()),
                threadFactory
        );
        CompletionService<RegionScan> completions =
                new ExecutorCompletionService<>(executor);
        int submitted = 0;
        int completed = 0;
        int inFlight = 0;
        int regions = 0;
        int chunks = 0;
        long completedBytes = 0;
        int maxInFlight = Math.max(1, options.workers() * 2);
        try {
            while (completed < work.size()) {
                while (submitted < work.size() && inFlight < maxInFlight) {
                    RegionWork next = work.get(submitted++);
                    completions.submit(() -> scanRegion(
                            worldRoot,
                            next,
                            policy,
                            itemIndex,
                            started
                    ));
                    inFlight++;
                }
                Future<RegionScan> future;
                try {
                    future = completions.take();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ScanAbort("Namespace scan was cancelled", interrupted);
                }
                RegionScan scanned;
                try {
                    scanned = future.get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ScanAbort("Namespace scan was cancelled", interrupted);
                } catch (ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof IOException io) {
                        throw io;
                    }
                    if (cause instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new IOException("Namespace region worker failed", cause);
                }
                completed++;
                inFlight--;
                completedBytes = Math.addExact(completedBytes, scanned.bytes());
                regions = Math.addExact(regions, scanned.regions());
                chunks = Math.addExact(chunks, scanned.chunks());
                if (chunks > MAX_CHUNKS) {
                    throw new ScanAbort("World exceeds namespace chunk hard limit");
                }
                if (scanned.warning() != null && warnings.size() < MAX_COVERAGE_GAPS) {
                    warnings.add(scanned.warning());
                }
                if (scanned.coverageGap() != null) {
                    if (gaps.size() >= MAX_COVERAGE_GAPS) {
                        throw new ScanAbort("World exceeds coverage-gap hard limit");
                    }
                    gaps.add(scanned.coverageGap());
                } else {
                    if (targets.size() > MAX_TARGETS - scanned.targets().size()) {
                        throw new ScanAbort("World exceeds namespace target hard limit");
                    }
                    targets.addAll(scanned.targets());
                    for (AffectedFile file : scanned.affectedFiles()) {
                        affected.putIfAbsent(file.relativePath(), file);
                    }
                }
                if ((completed & 63) == 0 || completed == work.size()) {
                    options.progressListener().update(new Progress(
                            completed,
                            work.size(),
                            completedBytes,
                            totalBytes,
                            chunks,
                            targets.size(),
                            gaps.size(),
                            elapsedMillis(started)
                    ));
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return new RegionTotals(
                work.size(),
                regions,
                chunks,
                completedBytes,
                totalBytes
        );
    }

    private RegionScan scanRegion(
            Path worldRoot,
            RegionWork work,
            NamespacePolicy policy,
            OrphanItemIndex itemIndex,
            long started
    ) throws IOException {
        requireBudget(started);
        if (work.size() == 0) {
            return new RegionScan(
                    List.of(),
                    List.of(),
                    null,
                    "empty_region_skipped:" + work.relativePath(),
                    0,
                    0,
                    0
            );
        }
        String beforeHash = IoUtil.sha256(work.path());
        ArrayList<NamespaceTarget> foundTargets = new ArrayList<>();
        int[] visited = {0};
        try {
            RegionFile.visitChunks(work.path(), nbtLimits, chunk -> {
                requireBudget(started);
                if (!(chunk.root().tag() instanceof Nbt.CompoundTag root)) {
                    throw new IOException("Chunk NBT root is not a compound");
                }
                NamespaceChunkAdapter.Context context =
                        new NamespaceChunkAdapter.Context(
                                work.directory().dimension(),
                                work.relativePath(),
                                chunk.chunkX(),
                                chunk.chunkZ(),
                                chunk.index(),
                                chunk.external(),
                                work.directory().kind() == WorldLayout.RegionDataKind.ENTITY
                                        ? NamespaceTarget.RegionKind.ENTITY
                                        : NamespaceTarget.RegionKind.CHUNK
                        );
                List<NamespaceTarget> found =
                        adapter.scan(root, context, policy, itemIndex);
                if (foundTargets.size() > MAX_TARGETS - found.size()) {
                    throw new ScanAbort("Region exceeds namespace target hard limit");
                }
                foundTargets.addAll(found);
                visited[0]++;
            });
        } catch (ScanAbort aborted) {
            throw aborted;
        } catch (IOException malformedOrUnsupported) {
            return new RegionScan(
                    List.of(),
                    List.of(),
                    new CoverageGap(
                            work.relativePath(),
                            oneLine(malformedOrUnsupported.getMessage())
                    ),
                    null,
                    1,
                    0,
                    work.size()
            );
        }
        String afterHash = IoUtil.sha256(work.path());
        if (!beforeHash.equals(afterHash)) {
            throw new ScanAbort(
                    "Region changed while namespace scanning: " + work.relativePath()
            );
        }
        LinkedHashMap<String, AffectedFile> foundFiles = new LinkedHashMap<>();
        addAffectedFiles(
                worldRoot,
                work.path(),
                work.relativePath(),
                beforeHash,
                foundTargets,
                foundFiles
        );
        return new RegionScan(
                List.copyOf(foundTargets),
                List.copyOf(foundFiles.values()),
                null,
                null,
                1,
                visited[0],
                work.size()
        );
    }

    private void scanPlayerData(
            Path worldRoot,
            NamespacePolicy policy,
            OrphanItemIndex itemIndex,
            Options options,
            long started,
            List<NamespaceTarget> targets,
            Map<String, AffectedFile> affected,
            List<CoverageGap> gaps
    ) throws IOException {
        Path playerData = worldRoot.resolve("playerdata");
        if (!Files.isDirectory(playerData, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var files = Files.list(playerData)) {
            List<Path> playerFiles = files
                    .filter(path -> path.getFileName().toString()
                            .matches("[0-9a-fA-F-]{36}\\.dat"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .limit(MAX_PLAYER_FILES + 1L)
                    .toList();
            if (playerFiles.size() > MAX_PLAYER_FILES) {
                throw new ScanAbort("World exceeds playerdata file hard limit");
            }
            for (Path playerFile : playerFiles) {
                requireBudget(started);
                String relative = normalize(worldRoot.relativize(playerFile));
                if (!options.acceptsStandalone(relative)) {
                    continue;
                }
                String before = IoUtil.sha256(playerFile);
                List<NamespaceTarget> found;
                try {
                    Nbt.Root nbt = NbtFile.readGzip(playerFile, nbtLimits);
                    if (!(nbt.tag() instanceof Nbt.CompoundTag root)) {
                        throw new IOException("Player NBT root is not a compound");
                    }
                    found = adapter.scan(
                            root,
                            new NamespaceChunkAdapter.Context(
                                    "minecraft:playerdata",
                                    relative,
                                    -1,
                                    -1,
                                    -1,
                                    false,
                                    NamespaceTarget.RegionKind.PLAYER
                            ),
                            policy,
                            itemIndex
                    );
                } catch (IOException malformedOrUnsupported) {
                    if (gaps.size() >= MAX_COVERAGE_GAPS) {
                        throw new IOException("World exceeds coverage-gap hard limit",
                                malformedOrUnsupported);
                    }
                    gaps.add(new CoverageGap(
                            relative,
                            oneLine(malformedOrUnsupported.getMessage())
                    ));
                    continue;
                }
                if (targets.size() > MAX_TARGETS - found.size()) {
                    throw new IOException("World exceeds namespace target hard limit");
                }
                String after = IoUtil.sha256(playerFile);
                if (!before.equals(after)) {
                    throw new IOException("Player data changed during namespace scan: " + relative);
                }
                targets.addAll(found);
                if (!found.isEmpty()) {
                    affected.put(
                            relative,
                            new AffectedFile(relative, Files.size(playerFile), before)
                    );
                }
            }
        }
    }

    private void scanSavedData(
            Path worldRoot,
            NamespacePolicy policy,
            OrphanItemIndex itemIndex,
            Options options,
            long started,
            List<NamespaceTarget> targets,
            Map<String, AffectedFile> affected,
            List<CoverageGap> gaps,
            List<String> warnings,
            List<NamespaceTarget> deferredTargetDetails
    ) throws IOException {
        if (!policy.isGlobalItemCleanup()) {
            return;
        }
        for (String relative : List.of(
                OrphanItemIndex.QIO_CACHE_PATH,
                "data/refinedstorage_storages.dat"
        )) {
            requireBudget(started);
            if (!options.acceptsStandalone(relative)) {
                continue;
            }
            Path supplied = worldRoot.resolve(
                    relative.replace('/', java.io.File.separatorChar)
            ).normalize();
            if (!Files.exists(supplied, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Path file;
            try {
                file = dev.yu.worldrepair.worldtool.io.WorldAccessPolicy
                        .requireContainedRegularFile(worldRoot, supplied);
            } catch (IOException invalid) {
                addGap(gaps, relative, invalid);
                continue;
            }
            String before = IoUtil.sha256(file);
            List<NamespaceTarget> found;
            try {
                Nbt.Root nbt = NbtFile.readGzip(file, nbtLimits);
                if (!(nbt.tag() instanceof Nbt.CompoundTag root)) {
                    throw new IOException("SavedData NBT root is not a compound");
                }
                found = adapter.scan(
                        root,
                        new NamespaceChunkAdapter.Context(
                                "minecraft:saved_data",
                                relative,
                                -1,
                                -1,
                                -1,
                                false,
                                NamespaceTarget.RegionKind.SAVED_DATA
                        ),
                        policy,
                        itemIndex
                );
            } catch (IOException malformedOrUnsupported) {
                addGap(gaps, relative, malformedOrUnsupported);
                continue;
            }
            String after = IoUtil.sha256(file);
            if (!before.equals(after)) {
                throw new IOException("SavedData changed during namespace scan: " + relative);
            }
            if (relative.equals(OrphanItemIndex.QIO_CACHE_PATH)
                    && !options.allowQioTypeCleanup()
                    && !found.isEmpty()) {
                if (deferredTargetDetails.size() > MAX_TARGETS - found.size()) {
                    throw new IOException("World exceeds deferred target hard limit");
                }
                deferredTargetDetails.addAll(found);
                warnings.add("qio_cache_cleanup_deferred_due_to_region_exclusions:"
                        + found.size());
                continue;
            }
            if (targets.size() > MAX_TARGETS - found.size()) {
                throw new IOException("World exceeds namespace target hard limit");
            }
            targets.addAll(found);
            if (!found.isEmpty()) {
                affected.put(
                        relative,
                        new AffectedFile(relative, Files.size(file), before)
                );
            }
        }
    }

    private static void addGap(
            List<CoverageGap> gaps,
            String relative,
            IOException failure
    ) throws IOException {
        if (gaps.size() >= MAX_COVERAGE_GAPS) {
            throw new IOException("World exceeds coverage-gap hard limit", failure);
        }
        gaps.add(new CoverageGap(relative, oneLine(failure.getMessage())));
    }

    private static void addAffectedFiles(
            Path worldRoot,
            Path region,
            String regionRelative,
            String regionHash,
            List<NamespaceTarget> regionTargets,
            Map<String, AffectedFile> affected
    ) throws IOException {
        boolean internal = false;
        for (NamespaceTarget target : regionTargets) {
            if (target.externalChunk()) {
                Path sidecar = RegionFile.externalSidecarPath(region, target.chunkIndex());
                String relative = normalize(worldRoot.relativize(sidecar));
                if (!affected.containsKey(relative)) {
                    affected.put(
                            relative,
                            new AffectedFile(relative, Files.size(sidecar), IoUtil.sha256(sidecar))
                    );
                }
            } else {
                internal = true;
            }
        }
        if (internal) {
            affected.put(
                    regionRelative,
                    new AffectedFile(regionRelative, Files.size(region), regionHash)
            );
        }
    }

    private static ArrayList<String> auditUnsupportedStores(
            Path worldRoot,
            NamespacePolicy policy
    ) throws IOException {
        ArrayList<String> warnings = new ArrayList<>();
        if (policy.isGlobalItemCleanup()) {
            warnings.add("item_cleanup_supported:vanilla_itemstacks");
            warnings.add("item_cleanup_supported:ae2_storage_cells");
            warnings.add("item_cleanup_supported:refinedstorage_item_repository");
            warnings.add("item_cleanup_supported:mekanism_qio");
            warnings.add("unknown_custom_saveddata_schemas_are_not_modified");
            return warnings;
        }
        Path selectedDimensions = worldRoot.resolve("dimensions").resolve(policy.namespace());
        if (Files.isDirectory(selectedDimensions, LinkOption.NOFOLLOW_LINKS)) {
            warnings.add("dimension_directory_not_deleted:"
                    + normalize(worldRoot.relativize(selectedDimensions)));
        }
        Path data = worldRoot.resolve("data");
        if (Files.isDirectory(data, LinkOption.NOFOLLOW_LINKS)) {
            try (var files = Files.list(data)) {
                String prefix = policy.namespace() + "_";
                files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.startsWith(prefix)
                                || name.startsWith(policy.namespace() + "."))
                        .sorted()
                        .limit(1_024)
                        .forEach(name -> warnings.add(
                                "saved_data_requires_adapter:data/" + name
                        ));
            }
        }
        warnings.add("player_itemstacks_use_vanilla_missing-item_cleanup");
        warnings.add("custom_saveddata_networks_and_ae2_storage_are_audit_only");
        return warnings;
    }

    private static void requireBudget(long started) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new ScanAbort("Namespace scan was cancelled");
        }
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        if (elapsed > MAX_SCAN_MILLIS) {
            throw new ScanAbort("Namespace scan exceeded hard time limit");
        }
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static String normalize(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static String oneLine(String value) {
        if (value == null || value.isBlank()) {
            return "unknown_region_read_failure";
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ');
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }

    private static final class ScanAbort extends IOException {
        private static final long serialVersionUID = 1L;

        private ScanAbort(String message) {
            super(message);
        }

        private ScanAbort(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
