package dev.yu.worldrepair.worldtool.io;

import java.io.IOException;
import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class WorldAccessPolicy {
    public static final String COPY_MARKER = ".yuworldrepair-world-copy";
    public static final String COPY_MARKER_CONTENT = "YUWORLDREPAIR_WORLD_COPY_V1";
    public static final String PROTECTED_ROOTS_PROPERTY =
            "yuworldrepair.protectedRoots";
    private static final Set<Path> HELD_WORLD_LOCKS = new HashSet<>();

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
        Path realAuthorized = requireExactWorld(supplied, authorizedWorld);
        synchronized (HELD_WORLD_LOCKS) {
            if (HELD_WORLD_LOCKS.contains(realAuthorized)) {
                return realAuthorized;
            }
        }
        requireSessionLockAvailable(realAuthorized.resolve("session.lock"));
        return realAuthorized;
    }

    /**
     * Acquires every signed world lock as one all-or-nothing set and holds it until close.
     */
    public static HeldWorldLocks acquireExactWorldLocks(List<String> signedWorldRoots)
            throws IOException {
        if (signedWorldRoots == null || signedWorldRoots.isEmpty()) {
            throw new IOException("No signed world roots were supplied for locking");
        }
        ArrayList<Path> roots = new ArrayList<>();
        for (String value : signedWorldRoots) {
            Path supplied;
            try {
                supplied = Path.of(value).toAbsolutePath().normalize();
            } catch (RuntimeException invalid) {
                throw new IOException("Signed world root is invalid", invalid);
            }
            Path real = requireExactWorld(supplied, supplied);
            if (roots.contains(real)) {
                throw new IOException("Signed world lock set contains a duplicate root");
            }
            roots.add(real);
        }
        roots.sort(Path::compareTo);

        ArrayList<HeldLock> acquired = new ArrayList<>();
        try {
            for (Path root : roots) {
                Path lockPath = root.resolve("session.lock");
                rejectLinkChain(lockPath.getParent());
                boolean created = false;
                FileChannel channel;
                if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                    rejectLink(lockPath);
                    channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
                } else {
                    channel = FileChannel.open(
                            lockPath,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE
                    );
                    created = true;
                }
                try {
                    FileLock lock = channel.tryLock();
                    if (lock == null) {
                        throw new IOException(
                                "World session.lock is held; the world may be active: " + root
                        );
                    }
                    acquired.add(new HeldLock(root, lockPath, channel, lock, created));
                } catch (OverlappingFileLockException overlapping) {
                    channel.close();
                    if (created) {
                        Files.deleteIfExists(lockPath);
                    }
                    throw new IOException(
                            "World session.lock is already held by this worker: " + root,
                            overlapping
                    );
                } catch (IOException | RuntimeException failure) {
                    channel.close();
                    if (created) {
                        Files.deleteIfExists(lockPath);
                    }
                    throw failure;
                }
            }
            synchronized (HELD_WORLD_LOCKS) {
                for (HeldLock held : acquired) {
                    if (!HELD_WORLD_LOCKS.add(held.worldRoot())) {
                        throw new IOException("World lock is already held by this worker");
                    }
                }
            }
            return new HeldWorldLocks(acquired);
        } catch (IOException | RuntimeException failure) {
            try {
                releaseLocks(acquired);
            } catch (IOException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    public static void requireWorldLockHeldByThisWorker(Path worldRoot)
            throws IOException {
        Path normalized = worldRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Maintenance world is not a directory");
        }
        rejectLinkChain(normalized);
        Path real = normalized.toRealPath();
        synchronized (HELD_WORLD_LOCKS) {
            if (!HELD_WORLD_LOCKS.contains(real)) {
                throw new IOException(
                        "Trusted maintenance scan requires this worker to hold session.lock"
                );
            }
        }
    }

    private static Path requireExactWorld(Path supplied, Path authorizedWorld)
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

    private static void releaseLocks(List<HeldLock> locks) throws IOException {
        IOException combined = null;
        for (int index = locks.size() - 1; index >= 0; index--) {
            HeldLock held = locks.get(index);
            synchronized (HELD_WORLD_LOCKS) {
                HELD_WORLD_LOCKS.remove(held.worldRoot());
            }
            try {
                held.lock().release();
            } catch (IOException failure) {
                combined = combine(combined, failure);
            }
            try {
                held.channel().close();
            } catch (IOException failure) {
                combined = combine(combined, failure);
            }
            if (held.created()) {
                try {
                    Files.deleteIfExists(held.lockPath());
                } catch (IOException failure) {
                    combined = combine(combined, failure);
                }
            }
        }
        if (combined != null) {
            throw combined;
        }
    }

    private static IOException combine(IOException current, IOException addition) {
        if (current == null) {
            return addition;
        }
        current.addSuppressed(addition);
        return current;
    }

    public static final class HeldWorldLocks implements AutoCloseable {
        private final List<HeldLock> locks;
        private boolean closed;

        private HeldWorldLocks(List<HeldLock> locks) {
            this.locks = List.copyOf(locks);
        }

        public List<Path> worldRoots() {
            return locks.stream().map(HeldLock::worldRoot).toList();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            releaseLocks(locks);
        }
    }

    private record HeldLock(
            Path worldRoot,
            Path lockPath,
            FileChannel channel,
            FileLock lock,
            boolean created
    ) {
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
