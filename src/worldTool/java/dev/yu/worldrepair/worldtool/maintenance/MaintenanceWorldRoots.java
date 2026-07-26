package dev.yu.worldrepair.worldtool.maintenance;

import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Captures the exact on-disk roots of worlds loaded through Bukkit-compatible hybrid servers.
 */
public final class MaintenanceWorldRoots {
    public static final int MAX_WORLD_ROOTS = 256;

    private MaintenanceWorldRoots() {
    }

    /**
     * Uses Youer/CraftBukkit's public {@code ServerLevel#getWorld()->getWorldFolder()} bridge
     * without linking the maintenance Mod to Bukkit classes.
     */
    public static List<Path> capture(
            Path serverRoot,
            Path mainWorld,
            Iterable<?> loadedLevels
    ) throws IOException {
        ArrayList<Path> candidates = new ArrayList<>();
        for (Object level : loadedLevels) {
            Path folder = bukkitWorldFolder(level, serverRoot);
            if (folder != null) {
                candidates.add(folder);
            }
        }
        return normalize(serverRoot, mainWorld, candidates);
    }

    public static List<Path> normalize(
            Path serverRoot,
            Path mainWorld,
            Iterable<Path> candidates
    ) throws IOException {
        Path server = requireDirectory(serverRoot, "server root");
        Path main = requireWorldRoot(server, mainWorld);
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.add(main);
        for (Path candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            Path normalized = candidate.isAbsolute()
                    ? candidate.toAbsolutePath().normalize()
                    : server.resolve(candidate).normalize();
            if (!normalized.startsWith(server)) {
                throw new IOException("Loaded world root is outside the server: " + normalized);
            }
            if (!Files.isRegularFile(
                    normalized.resolve("level.dat"),
                    LinkOption.NOFOLLOW_LINKS
            )) {
                // Bukkit may expose DIM-1/DIM1 as level folders. Their data is already covered
                // by the owning root and they intentionally have no separate level.dat.
                continue;
            }
            roots.add(requireWorldRoot(server, normalized));
            if (roots.size() > MAX_WORLD_ROOTS) {
                throw new IOException("Loaded world count exceeds hard limit "
                        + MAX_WORLD_ROOTS);
            }
        }
        ArrayList<Path> sorted = new ArrayList<>(roots);
        sorted.sort(Comparator.comparing(Path::toString));
        sorted.remove(main);
        sorted.addFirst(main);
        return List.copyOf(sorted);
    }

    private static Path bukkitWorldFolder(Object level, Path serverRoot) throws IOException {
        if (level == null) {
            return null;
        }
        Method getWorld;
        try {
            getWorld = level.getClass().getMethod("getWorld");
        } catch (NoSuchMethodException notHybrid) {
            return null;
        }
        try {
            Object world = getWorld.invoke(level);
            if (world == null) {
                throw new IOException("Loaded Bukkit world bridge returned null");
            }
            Method getWorldFolder = world.getClass().getMethod("getWorldFolder");
            Object value = getWorldFolder.invoke(world);
            if (value instanceof File file) {
                return file.toPath();
            }
            if (value instanceof Path path) {
                return path;
            }
            if (value instanceof String string && !string.isBlank()) {
                return serverRoot.resolve(string);
            }
            throw new IOException("Bukkit getWorldFolder returned an unsupported value");
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException
                 | RuntimeException reflectionFailure) {
            throw new IOException("Cannot capture loaded Bukkit world folder", reflectionFailure);
        }
    }

    private static Path requireDirectory(Path path, String description) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Maintenance " + description + " is not a directory");
        }
        WorldAccessPolicy.rejectLinkChain(normalized);
        return normalized.toRealPath();
    }

    private static Path requireWorldRoot(Path serverRoot, Path supplied) throws IOException {
        Path normalized = supplied.toAbsolutePath().normalize();
        if (!normalized.startsWith(serverRoot)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Loaded world root is outside the server or missing");
        }
        WorldAccessPolicy.rejectLinkChain(normalized);
        Path real = normalized.toRealPath();
        if (!real.startsWith(serverRoot)) {
            throw new IOException("Loaded world root escaped the server");
        }
        Path levelDat = real.resolve("level.dat");
        if (!Files.isRegularFile(levelDat, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(levelDat)) {
            throw new IOException("Loaded world root has no regular level.dat: " + real);
        }
        WorldAccessPolicy.rejectLinkChain(levelDat);
        return real;
    }
}
