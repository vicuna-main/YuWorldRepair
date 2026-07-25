package dev.yu.worldrepair.worldtool.io;

import java.io.IOException;
import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Pattern;

public final class WorldAccessPolicy {
    public static final String COPY_MARKER = ".yuworldrepair-world-copy";
    public static final String COPY_MARKER_CONTENT = "YUWORLDREPAIR_WORLD_COPY_V1";
    public static final String PROTECTED_ROOTS_PROPERTY =
            "yuworldrepair.protectedRoots";

    private WorldAccessPolicy() {
    }

    public static Path requireOfflineCopy(Path supplied) throws IOException {
        if (!supplied.isAbsolute()) {
            throw new IOException("--world-copy must be an absolute path");
        }
        Path normalized = supplied.normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World copy is not a directory: " + normalized);
        }
        rejectProtectedRoots(normalized);
        rejectLinkChain(normalized);
        Path real = normalized.toRealPath();
        rejectProtectedRoots(real);

        Path marker = real.resolve(COPY_MARKER);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(marker)) {
            throw new IOException("World copy marker is missing: " + marker);
        }
        rejectLinkChain(marker);
        String markerText = Files.readString(marker).trim();
        if (!COPY_MARKER_CONTENT.equals(markerText)) {
            throw new IOException("World copy marker content is invalid");
        }
        Path levelDat = real.resolve("level.dat");
        if (!Files.isRegularFile(levelDat, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(levelDat)) {
            throw new IOException("World copy has no regular level.dat");
        }
        rejectLinkChain(levelDat);
        requireSessionLockAvailable(real.resolve("session.lock"));
        return real;
    }

    /**
     * Resolves one exact production world after the owning server process has released it.
     *
     * <p>This entry point intentionally does not accept a caller-selected directory. Both the
     * supplied path and the independently authorized path must resolve to the same real
     * directory. The standalone CLI never calls this method.</p>
     */
    public static Path requireExactUnlockedWorld(Path supplied, Path authorizedWorld)
            throws IOException {
        if (!supplied.isAbsolute() || !authorizedWorld.isAbsolute()) {
            throw new IOException("Maintenance world paths must be absolute");
        }
        Path normalizedAuthorized = authorizedWorld.toAbsolutePath().normalize();
        Path normalizedSupplied = supplied.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedAuthorized, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(normalizedSupplied, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Maintenance world is not a directory");
        }
        rejectLinkChain(normalizedAuthorized);
        rejectLinkChain(normalizedSupplied);
        Path realAuthorized = normalizedAuthorized.toRealPath();
        Path realSupplied = normalizedSupplied.toRealPath();
        if (!realAuthorized.equals(realSupplied)) {
            throw new IOException("World path does not match the signed maintenance request");
        }
        Path levelDat = realAuthorized.resolve("level.dat");
        if (!Files.isRegularFile(levelDat, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(levelDat)) {
            throw new IOException("Maintenance world has no regular level.dat");
        }
        rejectLinkChain(levelDat);
        requireSessionLockAvailable(realAuthorized.resolve("session.lock"));
        return realAuthorized;
    }

    public static Path requireExactExternalJobRoot(
            Path world,
            Path supplied,
            Path authorizedJobRoot
    ) throws IOException {
        if (!supplied.isAbsolute() || !authorizedJobRoot.isAbsolute()) {
            throw new IOException("Maintenance job paths must be absolute");
        }
        Path normalized = supplied.toAbsolutePath().normalize();
        Path authorized = authorizedJobRoot.toAbsolutePath().normalize();
        if (!normalized.equals(authorized)) {
            throw new IOException("Job root does not match the signed maintenance request");
        }
        rejectLinkChain(normalized);
        if (normalized.startsWith(world)) {
            throw new IOException("Job root must be outside the world");
        }
        return normalized;
    }

    public static Path requireContainedRegularFile(Path worldRoot, Path candidate) throws IOException {
        Path normalizedRoot = worldRoot.toRealPath();
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)) {
            throw new IOException("Path escaped world copy: " + candidate);
        }
        rejectLinkChain(normalized);
        Path real = normalized.toRealPath();
        if (!real.startsWith(normalizedRoot) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World file escaped or is not regular: " + candidate);
        }
        return real;
    }

    public static void rejectLink(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("Symbolic link, junction, or special path is not allowed: " + path);
        }
    }

    /**
     * Checks every existing path component without following the component itself. This is
     * required on Windows because checking only the leaf does not detect a junction higher in
     * the path.
     */
    public static void rejectLinkChain(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current == null) {
            throw new IOException("Path has no filesystem root: " + path);
        }
        for (Path component : absolute) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                rejectLink(current);
            }
        }
    }

    private static void requireSessionLockAvailable(Path lockPath) throws IOException {
        if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        rejectLink(lockPath);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
             FileLock ignored = channel.tryLock()) {
            if (ignored == null) {
                throw new IOException("World session.lock is held; the world may be active");
            }
        } catch (java.nio.channels.OverlappingFileLockException locked) {
            throw new IOException("World session.lock is held; the world may be active", locked);
        }
    }

    /**
     * Rejects caller-configured production roots without embedding operator-specific paths.
     *
     * <p>The standalone wrapper passes a platform-path-separated list through
     * {@value #PROTECTED_ROOTS_PROPERTY}. The maintenance Mod does not rely on this optional
     * defense: its production-world access is instead bound to a signed request and the exact
     * path supplied by the live server.</p>
     */
    public static void rejectProtectedRoots(Path path) throws IOException {
        String configured = System.getProperty(PROTECTED_ROOTS_PROPERTY, "");
        if (configured.isBlank()) {
            return;
        }
        Path candidate = path.toAbsolutePath().normalize();
        for (String value : configured.split(Pattern.quote(File.pathSeparator))) {
            if (value.isBlank()) {
                continue;
            }
            Path protectedRoot;
            try {
                protectedRoot = Path.of(value).toAbsolutePath().normalize();
            } catch (RuntimeException invalid) {
                throw new IOException("Protected root configuration is invalid", invalid);
            }
            if (candidate.equals(protectedRoot) || candidate.startsWith(protectedRoot)) {
                throw new IOException("Refusing path inside a configured protected root");
            }
        }
    }
}
