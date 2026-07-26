package dev.yu.worldrepair.worldtool.maintenance;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves operator-facing world names into an exact, signed set of world roots whose region
 * files are excluded. Player data and SavedData are deliberately outside this scope.
 */
public final class MaintenanceRegionScope {
    private MaintenanceRegionScope() {
    }

    public static Selection resolve(
            Path serverRoot,
            List<Path> worldRoots,
            Mode mode,
            String suppliedNames
    ) throws IOException {
        if (worldRoots == null || worldRoots.isEmpty()) {
            throw new IOException("No maintenance world roots were captured");
        }
        Path server = serverRoot.toAbsolutePath().normalize();
        LinkedHashMap<String, Path> byLabel = new LinkedHashMap<>();
        HashMap<String, List<Path>> byLeaf = new HashMap<>();
        for (Path supplied : worldRoots) {
            Path root = supplied.toAbsolutePath().normalize();
            if (!root.startsWith(server)) {
                throw new IOException("Maintenance world root is outside the server");
            }
            String label = normalize(server.relativize(root));
            if (label.isBlank() || byLabel.putIfAbsent(fold(label), root) != null) {
                throw new IOException("Maintenance world labels are empty or ambiguous");
            }
            byLeaf.computeIfAbsent(
                    fold(root.getFileName().toString()),
                    ignored -> new ArrayList<>()
            ).add(root);
        }

        if (mode == Mode.ALL) {
            return new Selection(List.of(), List.copyOf(labels(server, worldRoots)));
        }
        List<String> names = parseNames(suppliedNames);
        if (names.isEmpty()) {
            throw new IOException("World scope requires at least one world name");
        }
        HashSet<Path> selected = new HashSet<>();
        for (String name : names) {
            Path exact = byLabel.get(fold(normalize(Path.of(name))));
            if (exact != null) {
                selected.add(exact);
                continue;
            }
            List<Path> leafMatches = byLeaf.getOrDefault(fold(name), List.of());
            if (leafMatches.size() != 1) {
                throw new IOException(
                        leafMatches.isEmpty()
                                ? "Unknown maintenance world: " + name
                                : "Ambiguous maintenance world name; use its server-relative path: "
                                + name
                );
            }
            selected.add(leafMatches.getFirst());
        }

        ArrayList<Path> excluded = new ArrayList<>();
        for (Path root : worldRoots) {
            boolean chosen = selected.contains(root.toAbsolutePath().normalize());
            if (mode == Mode.EXCEPT ? chosen : !chosen) {
                excluded.add(root.toAbsolutePath().normalize());
            }
        }
        return new Selection(
                List.copyOf(excluded),
                List.copyOf(labels(server, excluded))
        );
    }

    public static List<String> availableLabels(Path serverRoot, List<Path> worldRoots) {
        return List.copyOf(labels(serverRoot.toAbsolutePath().normalize(), worldRoots));
    }

    private static List<String> parseNames(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return List.of();
        }
        ArrayList<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String raw : supplied.split(",")) {
            String name = raw.trim();
            if (!name.isEmpty() && unique.add(fold(name))) {
                result.add(name);
            }
        }
        return List.copyOf(result);
    }

    private static List<String> labels(Path server, Iterable<Path> roots) {
        ArrayList<String> result = new ArrayList<>();
        for (Path supplied : roots) {
            Path root = supplied.toAbsolutePath().normalize();
            result.add(root.startsWith(server)
                    ? normalize(server.relativize(root))
                    : root.toString());
        }
        return result;
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String fold(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    public enum Mode {
        ALL,
        ONLY,
        EXCEPT
    }

    public record Selection(List<Path> excludedRegionRoots, List<String> excludedLabels) {
        public Selection {
            excludedRegionRoots = List.copyOf(excludedRegionRoots);
            excludedLabels = List.copyOf(excludedLabels);
        }
    }
}
