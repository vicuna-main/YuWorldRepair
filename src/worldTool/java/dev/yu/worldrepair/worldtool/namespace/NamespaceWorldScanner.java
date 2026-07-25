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

public final class NamespaceWorldScanner {
    public static final int MAX_REGIONS = 16_384;
    public static final int MAX_CHUNKS = 2_097_152;
    public static final int MAX_TARGETS = 262_144;
    public static final int MAX_COVERAGE_GAPS = 16_384;
    public static final long MAX_SCAN_MILLIS = 60L * 60 * 1_000;

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
            Map<NamespaceTarget.Action, Integer> targetsByAction
    ) {
    }

    public Result scan(Path worldRoot, NamespacePolicy policy) throws IOException {
        long started = System.nanoTime();
        ArrayList<NamespaceTarget> targets = new ArrayList<>();
        LinkedHashMap<String, AffectedFile> affected = new LinkedHashMap<>();
        ArrayList<CoverageGap> gaps = new ArrayList<>();
        ArrayList<String> warnings = auditUnsupportedStores(worldRoot, policy);
        int regions = 0;
        int chunks = 0;

        for (WorldLayout.RegionDirectory directory :
                WorldLayout.discoverRegionDirectories(worldRoot)) {
            for (Path region : WorldLayout.regionFiles(worldRoot, directory)) {
                requireBudget(started);
                if (Files.size(region) == 0) {
                    if (warnings.size() < MAX_COVERAGE_GAPS) {
                        warnings.add("empty_region_skipped:"
                                + normalize(worldRoot.relativize(region)));
                    }
                    continue;
                }
                if (++regions > MAX_REGIONS) {
                    throw new IOException("World exceeds namespace region hard limit");
                }
                String regionRelative = normalize(worldRoot.relativize(region));
                String beforeHash = IoUtil.sha256(region);
                int targetStart = targets.size();
                int[] visited = {0};
                try {
                    RegionFile.visitChunks(region, nbtLimits, chunk -> {
                        requireBudget(started);
                        if (!(chunk.root().tag() instanceof Nbt.CompoundTag root)) {
                            throw new IOException("Chunk NBT root is not a compound");
                        }
                        NamespaceChunkAdapter.Context context =
                                new NamespaceChunkAdapter.Context(
                                        directory.dimension(),
                                        regionRelative,
                                        chunk.chunkX(),
                                        chunk.chunkZ(),
                                        chunk.index(),
                                        chunk.external(),
                                        directory.kind() == WorldLayout.RegionDataKind.ENTITY
                                                ? NamespaceTarget.RegionKind.ENTITY
                                                : NamespaceTarget.RegionKind.CHUNK
                                );
                        List<NamespaceTarget> found = adapter.scan(root, context, policy);
                        if (targets.size() > MAX_TARGETS - found.size()) {
                            throw new IOException("World exceeds namespace target hard limit");
                        }
                        targets.addAll(found);
                        visited[0]++;
                    });
                } catch (IOException malformedOrUnsupported) {
                    if (gaps.size() >= MAX_COVERAGE_GAPS) {
                        throw new IOException("World exceeds coverage-gap hard limit",
                                malformedOrUnsupported);
                    }
                    gaps.add(new CoverageGap(
                            regionRelative,
                            oneLine(malformedOrUnsupported.getMessage())
                    ));
                    // Targets from a partly scanned file are not actionable because the rest of
                    // that file was not proven clean.
                    while (targets.size() > targetStart) {
                        targets.removeLast();
                    }
                    continue;
                }
                chunks = Math.addExact(chunks, visited[0]);
                if (chunks > MAX_CHUNKS) {
                    throw new IOException("World exceeds namespace chunk hard limit");
                }
                String afterHash = IoUtil.sha256(region);
                if (!beforeHash.equals(afterHash)) {
                    throw new IOException("Region changed while namespace scanning: "
                            + regionRelative);
                }
                addAffectedFiles(
                        worldRoot,
                        region,
                        regionRelative,
                        beforeHash,
                        targets.subList(targetStart, targets.size()),
                        affected
                );
            }
        }
        scanPlayerData(worldRoot, policy, started, targets, affected, gaps);

        targets.sort(Comparator
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
        return new Result(
                List.copyOf(targets),
                List.copyOf(affected.values()),
                List.copyOf(gaps),
                List.copyOf(warnings),
                regions,
                chunks,
                Map.copyOf(byAction)
        );
    }

    private void scanPlayerData(
            Path worldRoot,
            NamespacePolicy policy,
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
            for (Path playerFile : files
                    .filter(path -> path.getFileName().toString()
                            .matches("[0-9a-fA-F-]{36}\\.dat"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .limit(100_000)
                    .toList()) {
                requireBudget(started);
                String relative = normalize(worldRoot.relativize(playerFile));
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
                            policy
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
            throw new IOException("Namespace scan was cancelled");
        }
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        if (elapsed > MAX_SCAN_MILLIS) {
            throw new IOException("Namespace scan exceeded hard time limit");
        }
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
}
