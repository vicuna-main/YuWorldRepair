package dev.yu.worldrepair.worldtool.namespace;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.yu.worldrepair.worldtool.anvil.RegionFile;
import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;
import dev.yu.worldrepair.worldtool.job.SourceFileRecord;
import dev.yu.worldrepair.worldtool.nbt.NbtFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class NamespaceJobStore {
    public static final String MANIFEST_FILE = "namespace-manifest.json";
    public static final long MAX_BACKUP_BYTES = 64L * 1_024 * 1_024 * 1_024;
    private static final long MAX_JSON_BYTES = 64L * 1_024 * 1_024;
    private static final long MAX_LOG_BYTES = 32L * 1_024 * 1_024;
    private static final int MAX_SOURCE_FILES = 32_768;
    private static final int MAX_JSON_LINE_BYTES = 65_536;
    private static final DateTimeFormatter JOB_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Gson PRETTY =
            new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Gson COMPACT =
            new GsonBuilder().disableHtmlEscaping().create();

    private final Path directory;

    private NamespaceJobStore(Path directory) {
        this.directory = directory;
    }

    public static NamespaceJobStore create(Path jobsRoot) throws IOException {
        if (!jobsRoot.isAbsolute()) {
            throw new IOException("Namespace jobs root must be absolute");
        }
        Path normalized = jobsRoot.toAbsolutePath().normalize();
        WorldAccessPolicy.rejectProtectedRoots(normalized);
        WorldAccessPolicy.rejectLinkChain(normalized);
        Files.createDirectories(normalized);
        Path real = normalized.toRealPath();
        String id = "namespace-" + JOB_TIME.format(Instant.now()) + "-"
                + randomHex(8);
        Path directory = real.resolve(id);
        Files.createDirectory(directory);
        Files.createDirectory(directory.resolve("backups"));
        IoUtil.writeAtomicUtf8(directory.resolve("changes.jsonl"), "");
        IoUtil.writeAtomicUtf8(directory.resolve("tool.log"), "");
        return new NamespaceJobStore(directory);
    }

    public static NamespaceJobStore open(Path supplied) throws IOException {
        if (!supplied.isAbsolute()) {
            throw new IOException("Namespace job path must be absolute");
        }
        Path normalized = supplied.toAbsolutePath().normalize();
        WorldAccessPolicy.rejectProtectedRoots(normalized);
        WorldAccessPolicy.rejectLinkChain(normalized);
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalized)) {
            throw new IOException("Namespace job directory is missing or linked");
        }
        return new NamespaceJobStore(normalized.toRealPath());
    }

    public Path directory() {
        return directory;
    }

    public void writeManifest(NamespaceJobManifest manifest) throws IOException {
        validateManifest(manifest);
        IoUtil.writeAtomicUtf8(
                directory.resolve(MANIFEST_FILE),
                PRETTY.toJson(manifest) + "\n"
        );
    }

    public NamespaceJobManifest readManifest() throws IOException {
        NamespaceJobManifest manifest = readJson(
                directory.resolve(MANIFEST_FILE),
                NamespaceJobManifest.class
        );
        validateManifest(manifest);
        return manifest;
    }

    public void writeTargets(List<NamespaceTarget> targets) throws IOException {
        if (targets.size() > NamespaceWorldScanner.MAX_TARGETS) {
            throw new IOException("Namespace target count exceeds hard limit");
        }
        StringBuilder output = new StringBuilder(Math.min(
                targets.size() * 256,
                16 * 1_024 * 1_024
        ));
        for (NamespaceTarget target : targets) {
            validateTarget(target);
            String line = COMPACT.toJson(target);
            if (line.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_LINE_BYTES) {
                throw new IOException("Namespace target JSON line is oversized");
            }
            output.append(line).append('\n');
            if (output.length() > MAX_JSON_BYTES) {
                throw new IOException("Namespace target file exceeds hard limit");
            }
        }
        IoUtil.writeAtomicUtf8(directory.resolve("namespace-targets.jsonl"), output.toString());
    }

    public List<NamespaceTarget> readTargets() throws IOException {
        Path path = directory.resolve("namespace-targets.jsonl");
        requireRegular(path, MAX_JSON_BYTES);
        ArrayList<NamespaceTarget> targets = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (line.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_LINE_BYTES) {
                    throw new IOException("Namespace target JSON line is oversized");
                }
                try {
                    NamespaceTarget target = COMPACT.fromJson(line, NamespaceTarget.class);
                    validateTarget(target);
                    targets.add(target);
                } catch (JsonParseException invalid) {
                    throw new IOException("Namespace target JSON is malformed", invalid);
                }
                if (targets.size() > NamespaceWorldScanner.MAX_TARGETS) {
                    throw new IOException("Namespace target count exceeds hard limit");
                }
            }
        }
        return List.copyOf(targets);
    }

    public void writeSources(List<SourceFileRecord> sources) throws IOException {
        validateSources(sources);
        IoUtil.writeAtomicUtf8(
                directory.resolve("source-hashes.json"),
                PRETTY.toJson(Map.of("files", sources)) + "\n"
        );
    }

    public List<SourceFileRecord> readSources() throws IOException {
        SourceEnvelope envelope = readJson(
                directory.resolve("source-hashes.json"),
                SourceEnvelope.class
        );
        if (envelope.files == null) {
            throw new IOException("Namespace source manifest is empty");
        }
        validateSources(envelope.files);
        return List.copyOf(envelope.files);
    }

    public Path backupPath(String relative) throws IOException {
        if (!validRelative(relative)) {
            throw new IOException("Invalid namespace backup relative path");
        }
        Path root = directory.resolve("backups").toRealPath();
        Path result = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!result.startsWith(root)) {
            throw new IOException("Namespace backup path escaped job");
        }
        return result;
    }

    public void writeReport(String name, Object report) throws IOException {
        if (!name.matches("[a-z-]{1,48}\\.json")) {
            throw new IOException("Invalid namespace report name");
        }
        IoUtil.writeAtomicUtf8(directory.resolve(name), PRETTY.toJson(report) + "\n");
    }

    public void appendJournal(Map<String, ?> event) throws IOException {
        String line = COMPACT.toJson(event) + "\n";
        if (line.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_LINE_BYTES) {
            throw new IOException("Namespace journal event is oversized");
        }
        IoUtil.appendUtf8Dsync(directory.resolve("changes.jsonl"), line, MAX_LOG_BYTES);
    }

    public void appendLog(String action, String detail) throws IOException {
        String normalized = detail == null
                ? ""
                : detail.replace('\r', ' ').replace('\n', ' ');
        if (normalized.length() > 2_048) {
            normalized = normalized.substring(0, 2_048);
        }
        IoUtil.appendUtf8Dsync(
                directory.resolve("tool.log"),
                Instant.now() + " action=" + action + " detail=" + normalized + "\n",
                MAX_LOG_BYTES
        );
    }

    private static void validateManifest(NamespaceJobManifest manifest) throws IOException {
        if (manifest == null
                || manifest.schemaVersion() != NamespaceJobManifest.SCHEMA_VERSION
                || manifest.jobId() == null
                || !manifest.jobId().matches("namespace-[a-zA-Z0-9-]{16,80}")
                || manifest.state() == null
                || manifest.worldRoot() == null
                || manifest.worldFingerprint() == null
                || !manifest.worldFingerprint().matches("[0-9a-f]{64}")
                || manifest.namespace() == null
                || !manifest.namespace().matches("[a-z0-9_.-]{1,64}")
                || manifest.mode() == null
                || manifest.registrySnapshotSha256() == null
                || !manifest.registrySnapshotSha256().matches("[0-9a-f]{64}")
                || manifest.regionsScanned() < 0
                || manifest.chunksScanned() < 0
                || manifest.totalTargets() < 0
                || manifest.totalTargets() > NamespaceWorldScanner.MAX_TARGETS
                || manifest.coverageGaps() < 0
                || manifest.coverageGaps() > NamespaceWorldScanner.MAX_COVERAGE_GAPS) {
            throw new IOException("Invalid namespace job manifest");
        }
    }

    private static void validateSources(List<SourceFileRecord> sources) throws IOException {
        if (sources == null || sources.size() > MAX_SOURCE_FILES) {
            throw new IOException("Invalid namespace source file list");
        }
        Set<String> paths = new HashSet<>();
        long bytes = 0;
        for (SourceFileRecord source : sources) {
            boolean region = source != null && source.relativePath().endsWith(".mca");
            boolean sidecar = source != null && source.relativePath().endsWith(".mcc");
            boolean player = source != null
                    && source.relativePath().matches("playerdata/[0-9a-fA-F-]{36}\\.dat");
            if (source == null
                    || !validRelative(source.relativePath())
                    || !(region || sidecar || player)
                    || !source.relativePath().equals(source.backupRelativePath())
                    || source.size() < (region ? 8_192 : 1)
                    || source.size() > (region
                    ? 2L * 1_024 * 1_024 * 1_024
                    : player
                    ? NbtFile.MAX_COMPRESSED_BYTES
                    : RegionFile.MAX_EXTERNAL_CHUNK_BYTES)
                    || !sha256(source.preSha256())
                    || source.postApplySha256() != null
                    && !sha256(source.postApplySha256())
                    || !paths.add(source.relativePath())) {
                throw new IOException("Invalid namespace source file");
            }
            try {
                bytes = Math.addExact(bytes, source.size());
            } catch (ArithmeticException overflow) {
                throw new IOException("Namespace source byte total overflow", overflow);
            }
            if (bytes > MAX_BACKUP_BYTES) {
                throw new IOException("Namespace source bytes exceed backup hard limit");
            }
        }
    }

    private static void validateTarget(NamespaceTarget target) throws IOException {
        if (target == null
                || target.dimension() == null
                || !target.dimension().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || !validRelative(target.regionRelativePath())
                || target.regionKind() == NamespaceTarget.RegionKind.PLAYER
                && !target.regionRelativePath()
                .matches("playerdata/[0-9a-fA-F-]{36}\\.dat")
                || target.regionKind() != NamespaceTarget.RegionKind.PLAYER
                && !target.regionRelativePath().endsWith(".mca")
                || target.regionKind() == NamespaceTarget.RegionKind.PLAYER
                && target.chunkIndex() != -1
                || target.regionKind() != NamespaceTarget.RegionKind.PLAYER
                && (target.chunkIndex() < 0
                || target.chunkIndex() >= RegionFile.CHUNK_SLOTS)
                || target.regionKind() == null
                || target.action() == null
                || target.nbtPath() == null
                || target.nbtPath().length() > 16_384
                || target.resourceId() == null
                || !target.resourceId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IOException("Invalid namespace target");
        }
    }

    private static <T> T readJson(Path path, Class<T> type) throws IOException {
        requireRegular(path, MAX_JSON_BYTES);
        try {
            T value = PRETTY.fromJson(Files.readString(path, StandardCharsets.UTF_8), type);
            if (value == null) {
                throw new IOException("Namespace JSON is empty");
            }
            return value;
        } catch (JsonParseException invalid) {
            throw new IOException("Namespace JSON is malformed", invalid);
        }
    }

    private static void requireRegular(Path path, long maxBytes) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || Files.size(path) > maxBytes) {
            throw new IOException("Namespace job file is missing, linked, or oversized");
        }
    }

    private static boolean validRelative(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 16_384
                || value.startsWith("/")
                || value.indexOf('\\') >= 0
                || value.indexOf(':') >= 0
                || value.indexOf('\0') >= 0) {
            return false;
        }
        for (String part : value.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static boolean sha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private static final class SourceEnvelope {
        private List<SourceFileRecord> files;
    }
}
