package dev.yu.worldrepair.worldtool.scan;

import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class WorldLayout {
    private static final int MAX_DIMENSION_DIRECTORIES = 1_024;
    private static final int MAX_DIMENSION_DEPTH = 16;
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH_PART = Pattern.compile("[a-z0-9_.-]+");

    private WorldLayout() {
    }

    public record EntityDirectory(String dimension, Path path) {
    }

    public record RegionDirectory(
            String dimension,
            Path path,
            RegionDataKind kind
    ) {
    }

    public enum RegionDataKind {
        ENTITY,
        CHUNK
    }

    public static List<RegionDirectory> discoverRegionDirectories(Path worldRoot) throws IOException {
        ArrayList<RegionDirectory> result = new ArrayList<>();
        addRegionPair(result, "minecraft:overworld", worldRoot);
        addRegionPair(result, "minecraft:the_nether", worldRoot.resolve("DIM-1"));
        addRegionPair(result, "minecraft:the_end", worldRoot.resolve("DIM1"));

        Path dimensions = worldRoot.resolve("dimensions");
        if (Files.isDirectory(dimensions, LinkOption.NOFOLLOW_LINKS)) {
            WorldAccessPolicy.rejectLinkChain(dimensions);
            Map<Path, String> roots = new LinkedHashMap<>();
            try (var paths = Files.walk(dimensions, MAX_DIMENSION_DEPTH)) {
                for (Path path : paths
                        .filter(candidate -> candidate.getFileName() != null)
                        .filter(candidate -> {
                            String name = candidate.getFileName().toString();
                            return name.equals("region") || name.equals("entities");
                        })
                        .filter(candidate -> Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS))
                        .sorted()
                        .toList()) {
                    Path dimensionRoot = path.getParent();
                    roots.putIfAbsent(
                            dimensionRoot,
                            customDimensionId(dimensions, dimensionRoot)
                    );
                }
            }
            for (Map.Entry<Path, String> entry : roots.entrySet()) {
                if (result.size() >= MAX_DIMENSION_DIRECTORIES * 2) {
                    throw new IOException("World exceeds dimension directory hard limit");
                }
                addRegionPair(result, entry.getValue(), entry.getKey());
            }
        }
        result.sort(Comparator
                .comparing(RegionDirectory::dimension)
                .thenComparing(directory -> directory.kind().name()));
        return List.copyOf(result);
    }

    public static List<EntityDirectory> discoverEntityDirectories(Path worldRoot) throws IOException {
        ArrayList<EntityDirectory> result = new ArrayList<>();
        addIfDirectory(result, "minecraft:overworld", worldRoot.resolve("entities"));
        addIfDirectory(result, "minecraft:the_nether", worldRoot.resolve("DIM-1").resolve("entities"));
        addIfDirectory(result, "minecraft:the_end", worldRoot.resolve("DIM1").resolve("entities"));

        Path dimensions = worldRoot.resolve("dimensions");
        if (Files.isDirectory(dimensions, LinkOption.NOFOLLOW_LINKS)) {
            WorldAccessPolicy.rejectLinkChain(dimensions);
            try (var paths = Files.walk(dimensions, MAX_DIMENSION_DEPTH)) {
                paths.filter(path -> path.getFileName() != null
                                && path.getFileName().toString().equals("entities"))
                        .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        .sorted()
                        .forEach(path -> {
                            if (result.size() >= MAX_DIMENSION_DIRECTORIES) {
                                throw new TooManyDimensions();
                            }
                            Path relative = dimensions.relativize(path);
                            if (relative.getNameCount() < 3) {
                                throw new InvalidDimensionPath(path.toString());
                            }
                            String namespace = relative.getName(0).toString();
                            if (!NAMESPACE.matcher(namespace).matches()) {
                                throw new InvalidDimensionPath(path.toString());
                            }
                            StringBuilder dimensionPath = new StringBuilder();
                            for (int index = 1; index < relative.getNameCount() - 1; index++) {
                                String part = relative.getName(index).toString();
                                if (!PATH_PART.matcher(part).matches()) {
                                    throw new InvalidDimensionPath(path.toString());
                                }
                                if (!dimensionPath.isEmpty()) {
                                    dimensionPath.append('/');
                                }
                                dimensionPath.append(part);
                            }
                            try {
                                addIfDirectory(result, namespace + ":" + dimensionPath, path);
                            } catch (IOException failure) {
                                throw new WrappedIo(failure);
                            }
                        });
            } catch (WrappedIo wrapped) {
                throw wrapped.failure;
            } catch (TooManyDimensions tooMany) {
                throw new IOException("World exceeds dimension directory hard limit", tooMany);
            } catch (InvalidDimensionPath invalid) {
                throw new IOException("Cannot map custom dimension path: " + invalid.path, invalid);
            }
        }
        result.sort(Comparator.comparing(EntityDirectory::dimension));
        return List.copyOf(result);
    }

    public static List<Path> regionFiles(Path worldRoot, EntityDirectory directory) throws IOException {
        return regionFiles(worldRoot, directory.path());
    }

    public static List<Path> regionFiles(Path worldRoot, RegionDirectory directory) throws IOException {
        return regionFiles(worldRoot, directory.path());
    }

    private static List<Path> regionFiles(Path worldRoot, Path directory) throws IOException {
        WorldAccessPolicy.rejectLinkChain(directory);
        ArrayList<Path> result = new ArrayList<>();
        try (var files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            result.add(WorldAccessPolicy.requireContainedRegularFile(worldRoot, path));
                        } catch (IOException failure) {
                            throw new WrappedIo(failure);
                        }
                    });
        } catch (WrappedIo wrapped) {
            throw wrapped.failure;
        }
        return List.copyOf(result);
    }

    private static void addRegionPair(
            List<RegionDirectory> result,
            String dimension,
            Path dimensionRoot
    ) throws IOException {
        addRegionDirectory(
                result,
                dimension,
                dimensionRoot.resolve("entities"),
                RegionDataKind.ENTITY
        );
        addRegionDirectory(
                result,
                dimension,
                dimensionRoot.resolve("region"),
                RegionDataKind.CHUNK
        );
    }

    private static void addRegionDirectory(
            List<RegionDirectory> result,
            String dimension,
            Path path,
            RegionDataKind kind
    ) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            WorldAccessPolicy.rejectLinkChain(path);
            result.add(new RegionDirectory(dimension, path.toRealPath(), kind));
        }
    }

    private static String customDimensionId(Path dimensions, Path dimensionRoot) {
        Path relative = dimensions.relativize(dimensionRoot);
        if (relative.getNameCount() < 2) {
            throw new InvalidDimensionPath(dimensionRoot.toString());
        }
        String namespace = relative.getName(0).toString();
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new InvalidDimensionPath(dimensionRoot.toString());
        }
        StringBuilder path = new StringBuilder();
        for (int index = 1; index < relative.getNameCount(); index++) {
            String part = relative.getName(index).toString();
            if (!PATH_PART.matcher(part).matches()) {
                throw new InvalidDimensionPath(dimensionRoot.toString());
            }
            if (!path.isEmpty()) {
                path.append('/');
            }
            path.append(part);
        }
        return namespace + ":" + path;
    }

    private static void addIfDirectory(
            List<EntityDirectory> result,
            String dimension,
            Path path
    ) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            WorldAccessPolicy.rejectLinkChain(path);
            Path real = path.toRealPath();
            result.add(new EntityDirectory(dimension, real));
        }
    }

    private static final class WrappedIo extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final IOException failure;

        private WrappedIo(IOException failure) {
            this.failure = failure;
        }
    }

    private static final class TooManyDimensions extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class InvalidDimensionPath extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String path;

        private InvalidDimensionPath(String path) {
            this.path = path;
        }
    }
}
