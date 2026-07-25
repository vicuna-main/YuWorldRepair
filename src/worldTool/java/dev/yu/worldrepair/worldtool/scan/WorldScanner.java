package dev.yu.worldrepair.worldtool.scan;

import dev.yu.worldrepair.worldtool.adapter.LegacyChickenDataAdapter;
import dev.yu.worldrepair.worldtool.anvil.RegionFile;
import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.nbt.Nbt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

public final class WorldScanner {
    public static final int MAX_REGIONS = 8_192;
    public static final int MAX_CHUNKS = 1_048_576;
    public static final long MAX_SCAN_MILLIS = 30L * 60 * 1_000;

    private final LegacyChickenDataAdapter adapter;
    private final Nbt.Limits nbtLimits;

    public WorldScanner(LegacyChickenDataAdapter adapter, Nbt.Limits nbtLimits) {
        this.adapter = adapter;
        this.nbtLimits = nbtLimits;
    }

    public record AffectedFile(String relativePath, long size, String sha256) {
    }

    public record Result(
            List<LegacyChickenDataAdapter.Target> targets,
            List<AffectedFile> affectedFiles,
            int regionsScanned,
            int chunksScanned,
            int emptyRegionsSkipped,
            Map<String, Integer> targetsByDimension,
            Map<String, Integer> targetsByEntityType,
            Map<String, Integer> targetsByRegion,
            Map<String, Integer> targetsByChunk
    ) {
        public int addressableTargets() {
            return Math.toIntExact(targets.stream().filter(LegacyChickenDataAdapter.Target::addressable).count());
        }

        public int blockedTargets() {
            return targets.size() - addressableTargets();
        }

        public int uniqueEntityUuids() {
            Set<String> unique = new HashSet<>();
            for (LegacyChickenDataAdapter.Target target : targets) {
                if (target.entityUuid() != null) {
                    unique.add(target.entityUuid());
                }
            }
            return unique.size();
        }

        public int uniqueAttachments() {
            Set<String> unique = new HashSet<>();
            for (LegacyChickenDataAdapter.Target target : targets) {
                unique.add(target.regionRelativePath() + '\0'
                        + target.chunkIndex() + '\0' + target.nbtPath());
            }
            return unique.size();
        }
    }

    public Result scan(Path worldRoot, boolean trustedIceAndFireVersion) throws IOException {
        long startedNanos = System.nanoTime();
        ArrayList<LegacyChickenDataAdapter.Target> targets = new ArrayList<>();
        LinkedHashMap<String, AffectedFile> affected = new LinkedHashMap<>();
        int regions = 0;
        int chunks = 0;
        int emptyRegions = 0;

        for (WorldLayout.EntityDirectory entityDirectory : WorldLayout.discoverEntityDirectories(worldRoot)) {
            for (Path region : WorldLayout.regionFiles(worldRoot, entityDirectory)) {
                requireWithinBudget(startedNanos);
                if (Files.size(region) == 0) {
                    emptyRegions++;
                    continue;
                }
                if (++regions > MAX_REGIONS) {
                    throw new IOException("World exceeds region hard limit " + MAX_REGIONS);
                }
                String relative = normalizeRelative(worldRoot.relativize(region));
                String beforeHash = IoUtil.sha256(region);
                int targetStart = targets.size();
                int[] visitedChunks = {0};
                RegionFile.visitChunks(region, nbtLimits, chunk -> {
                    requireWithinBudget(startedNanos);
                    if (!(chunk.root().tag() instanceof Nbt.CompoundTag compound)) {
                        throw new IOException("Entity chunk root is not a compound");
                    }
                    LegacyChickenDataAdapter.Context context = new LegacyChickenDataAdapter.Context(
                            entityDirectory.dimension(),
                            relative,
                            chunk.chunkX(),
                            chunk.chunkZ(),
                            chunk.index(),
                            chunk.external()
                    );
                    List<LegacyChickenDataAdapter.Target> found = adapter.scan(compound, context);
                    if (targets.size() > JobLimits.MAX_TARGETS - found.size()) {
                        throw new IOException("World exceeds target hard limit " + JobLimits.MAX_TARGETS);
                    }
                    targets.addAll(found);
                    visitedChunks[0]++;
                });
                chunks = Math.addExact(chunks, visitedChunks[0]);
                if (chunks > MAX_CHUNKS) {
                    throw new IOException("World exceeds chunk hard limit " + MAX_CHUNKS);
                }
                String afterHash = IoUtil.sha256(region);
                if (!beforeHash.equals(afterHash)) {
                    throw new IOException("Region changed while scanning: " + relative);
                }
                if (targets.size() > targetStart) {
                    boolean internalTarget = false;
                    for (int index = targetStart; index < targets.size(); index++) {
                        LegacyChickenDataAdapter.Target target = targets.get(index);
                        if (target.externalChunk()) {
                            Path sidecar = RegionFile.externalSidecarPath(region, target.chunkIndex());
                            String sidecarRelative = normalizeRelative(worldRoot.relativize(sidecar));
                            if (!affected.containsKey(sidecarRelative)) {
                                affected.put(
                                        sidecarRelative,
                                        new AffectedFile(
                                            sidecarRelative,
                                            Files.size(sidecar),
                                            IoUtil.sha256(sidecar)
                                        )
                                );
                            }
                        } else {
                            internalTarget = true;
                        }
                    }
                    if (internalTarget) {
                        affected.put(relative, new AffectedFile(relative, Files.size(region), beforeHash));
                    }
                }
            }
        }

        Map<String, Integer> uuidCounts = new HashMap<>();
        for (LegacyChickenDataAdapter.Target target : targets) {
            if (target.entityUuid() != null) {
                uuidCounts.merge(target.entityUuid(), 1, Integer::sum);
            }
        }
        ArrayList<LegacyChickenDataAdapter.Target> normalizedTargets = new ArrayList<>(targets.size());
        for (LegacyChickenDataAdapter.Target target : targets) {
            LegacyChickenDataAdapter.Target normalized = target;
            if (target.entityUuid() != null && uuidCounts.getOrDefault(target.entityUuid(), 0) > 1) {
                normalized = target.withRefusal("duplicate_uuid");
            } else if (!trustedIceAndFireVersion) {
                normalized = target.withRefusal("unverified_iceandfire_jar");
            }
            normalizedTargets.add(normalized);
        }
        normalizedTargets.sort(Comparator
                .comparing(LegacyChickenDataAdapter.Target::dimension)
                .thenComparingInt(LegacyChickenDataAdapter.Target::chunkX)
                .thenComparingInt(LegacyChickenDataAdapter.Target::chunkZ)
                .thenComparing(target -> target.entityUuid() == null ? "" : target.entityUuid())
                .thenComparing(LegacyChickenDataAdapter.Target::nbtPath));

        LinkedHashMap<String, Integer> byDimension = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> byEntityType = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> byRegion = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> byChunk = new LinkedHashMap<>();
        for (LegacyChickenDataAdapter.Target target : normalizedTargets) {
            byDimension.merge(target.dimension(), 1, Integer::sum);
            byEntityType.merge(target.entityType(), 1, Integer::sum);
            byRegion.merge(target.regionRelativePath(), 1, Integer::sum);
            byChunk.merge(
                    target.dimension() + "@" + target.chunkX() + "," + target.chunkZ(),
                    1,
                    Integer::sum
            );
        }
        return new Result(
                List.copyOf(normalizedTargets),
                List.copyOf(affected.values()),
                regions,
                chunks,
                emptyRegions,
                Map.copyOf(byDimension),
                Map.copyOf(byEntityType),
                Map.copyOf(byRegion),
                Map.copyOf(byChunk)
        );
    }

    private static void requireWithinBudget(long startedNanos) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("World scan was cancelled");
        }
        long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
        if (elapsedMillis > MAX_SCAN_MILLIS) {
            throw new IOException("World scan exceeded hard time limit " + MAX_SCAN_MILLIS + " ms");
        }
    }

    private static String normalizeRelative(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    /**
     * Avoids coupling the scanner to the JSON store while sharing its target bound.
     */
    private static final class JobLimits {
        private static final int MAX_TARGETS = 65_536;
    }
}
