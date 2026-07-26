package dev.yu.worldrepair.worldtool.job;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.yu.worldrepair.worldtool.adapter.LegacyChickenDataAdapter;
import dev.yu.worldrepair.worldtool.anvil.RegionFile;
import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class JobStore {
    public static final int MAX_TARGETS = 65_536;
    public static final int MAX_SOURCE_FILES = 8_192;
    public static final int MAX_JSON_LINE_BYTES = 65_536;
    public static final long MAX_TARGETS_FILE_BYTES = 64L * 1_024 * 1_024;
    public static final long MAX_JOURNAL_BYTES = 32L * 1_024 * 1_024;
    public static final long MAX_TOOL_LOG_BYTES = 4L * 1_024 * 1_024;
    public static final long MAX_BACKUP_BYTES = 512L * 1_024 * 1_024 * 1_024;

    private static final DateTimeFormatter JOB_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long TOKEN_TTL_SECONDS = Duration.ofMinutes(10).toSeconds();

    private final Path directory;

    private JobStore(Path directory) {
        this.directory = directory;
    }

    public static JobStore create(Path jobsRoot) throws IOException {
        if (!jobsRoot.isAbsolute()) {
            throw new IOException("--job-root must be absolute");
        }
        Path normalizedRoot = jobsRoot.normalize();
        WorldAccessPolicy.rejectProtectedRoots(normalizedRoot);
        WorldAccessPolicy.rejectLinkChain(normalizedRoot);
        Files.createDirectories(normalizedRoot);
        WorldAccessPolicy.rejectLinkChain(normalizedRoot);
        Path realRoot = normalizedRoot.toRealPath();
        String id = JOB_TIME.format(Instant.now()) + "-" + randomHex(8);
        Path directory = realRoot.resolve(id).normalize();
        if (!directory.startsWith(realRoot)) {
            throw new IOException("Job directory escaped job root");
        }
        Files.createDirectory(directory);
        Files.createDirectory(directory.resolve("backups"));
        IoUtil.writeAtomicUtf8(directory.resolve("changes.jsonl"), "");
        IoUtil.writeAtomicUtf8(directory.resolve("tool.log"), "");
        return new JobStore(directory);
    }

    public static JobStore open(Path supplied) throws IOException {
        if (!supplied.isAbsolute()) {
            throw new IOException("--job must be an absolute path");
        }
        Path normalized = supplied.normalize();
        WorldAccessPolicy.rejectProtectedRoots(normalized);
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Job path is not a directory");
        }
        WorldAccessPolicy.rejectLinkChain(normalized);
        Path real = normalized.toRealPath();
        Path manifest = real.resolve("manifest.json");
        WorldAccessPolicy.rejectLinkChain(manifest);
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Job manifest is missing");
        }
        return new JobStore(real);
    }

    public Path directory() {
        return directory;
    }

    public void writeManifest(JobManifest manifest) throws IOException {
        if (!directory.getFileName().toString().equals(manifest.jobId())) {
            throw new IOException("Manifest job ID does not match directory");
        }
        IoUtil.writeAtomicUtf8(directory.resolve("manifest.json"), PRETTY.toJson(manifest) + "\n");
    }

    public JobManifest readManifest() throws IOException {
        JobManifest manifest = readJson(directory.resolve("manifest.json"), JobManifest.class, 1_048_576);
        if (manifest.schemaVersion() != 3
                || !directory.getFileName().toString().equals(manifest.jobId())
                || manifest.state() == null
                || !LegacyChickenDataAdapter.ADAPTER_ID.equals(manifest.adapterId())
                || !validMetadata(manifest.toolVersion())
                || !validMetadata(manifest.javaVersion())
                || !validMetadata(manifest.minecraftVersion())
                || !validMetadata(manifest.neoForgeVersion())
                || !validMetadata(manifest.youerVersion())
                || !isSha256(manifest.worldFingerprint())
                || !isSha256(manifest.iceAndFireSha256())
                || manifest.regionCount() < 0
                || manifest.regionCount() > MAX_SOURCE_FILES
                || manifest.chunkCount() < 0
                || manifest.chunkCount() > 1_048_576
                || manifest.totalTargets() < 0
                || manifest.totalTargets() > MAX_TARGETS
                || manifest.addressableTargets() < 0
                || manifest.blockedTargets() < 0
                || manifest.addressableTargets() + manifest.blockedTargets() != manifest.totalTargets()
                || !validAbsolutePath(manifest.worldRoot())
                || !validInstant(manifest.createdAt())
                || !validInstant(manifest.updatedAt())
                || !validText(manifest.detail(), 1_024)) {
            throw new IOException("Invalid job manifest");
        }
        return manifest;
    }

    public void writeTargets(List<LegacyChickenDataAdapter.Target> targets) throws IOException {
        if (targets.size() > MAX_TARGETS) {
            throw new IOException("Target count exceeds hard limit");
        }
        Path target = directory.resolve("targets.jsonl");
        Path temporary = target.resolveSibling("targets.jsonl.tmp");
        long written = 0;
        try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                StandardOpenOption.DSYNC
        )) {
            for (LegacyChickenDataAdapter.Target record : targets) {
                byte[] line = (COMPACT.toJson(record) + "\n").getBytes(StandardCharsets.UTF_8);
                if (line.length > MAX_JSON_LINE_BYTES || written > MAX_TARGETS_FILE_BYTES - line.length) {
                    throw new IOException("Target JSONL exceeds hard limit");
                }
                ByteBuffer buffer = ByteBuffer.wrap(line);
                while (buffer.hasRemaining()) {
                    if (channel.write(buffer) <= 0) {
                        throw new IOException("Target file write made no progress");
                    }
                }
                written += line.length;
            }
            channel.force(true);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
        IoUtil.moveAtomic(temporary, target);
    }

    public List<LegacyChickenDataAdapter.Target> readTargets() throws IOException {
        Path path = directory.resolve("targets.jsonl");
        if (Files.size(path) > MAX_TARGETS_FILE_BYTES) {
            throw new IOException("Targets file exceeds hard byte limit");
        }
        ArrayList<LegacyChickenDataAdapter.Target> targets = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_LINE_BYTES) {
                    throw new IOException("Target JSONL line exceeds hard limit");
                }
                if (line.isBlank()) {
                    continue;
                }
                try {
                    LegacyChickenDataAdapter.Target target =
                            COMPACT.fromJson(line, LegacyChickenDataAdapter.Target.class);
                    validateTarget(target);
                    targets.add(target);
                } catch (JsonParseException invalid) {
                    throw new IOException("Invalid target JSONL", invalid);
                }
                if (targets.size() > MAX_TARGETS) {
                    throw new IOException("Target count exceeds hard limit");
                }
            }
        }
        return List.copyOf(targets);
    }

    public void writeSources(List<SourceFileRecord> sources) throws IOException {
        if (sources.size() > MAX_SOURCE_FILES) {
            throw new IOException("Source file count exceeds hard limit");
        }
        IoUtil.writeAtomicUtf8(
                directory.resolve("source-hashes.json"),
                PRETTY.toJson(Map.of("files", sources)) + "\n"
        );
    }

    public List<SourceFileRecord> readSources() throws IOException {
        SourceEnvelope envelope = readJson(
                directory.resolve("source-hashes.json"),
                SourceEnvelope.class,
                16L * 1_024 * 1_024
        );
        if (envelope.files == null || envelope.files.size() > MAX_SOURCE_FILES) {
            throw new IOException("Invalid source file manifest");
        }
        Set<String> paths = new HashSet<>();
        long totalBytes = 0;
        for (SourceFileRecord source : envelope.files) {
            if (source == null
                    || !validRelativePath(source.relativePath())
                    || !(source.relativePath().endsWith(".mca")
                    || source.relativePath().endsWith(".mcc"))
                    || !source.relativePath().equals(source.backupRelativePath())
                    || source.size() < (source.relativePath().endsWith(".mca") ? 8_192 : 1)
                    || source.size() > (source.relativePath().endsWith(".mca")
                    ? 2L * 1_024 * 1_024 * 1_024
                    : RegionFile.MAX_EXTERNAL_CHUNK_BYTES)
                    || !isSha256(source.preSha256())
                    || (source.postApplySha256() != null && !isSha256(source.postApplySha256()))
                    || !paths.add(source.relativePath())) {
                throw new IOException("Invalid source file record");
            }
            try {
                totalBytes = Math.addExact(totalBytes, source.size());
            } catch (ArithmeticException overflow) {
                throw new IOException("Source file byte total overflow", overflow);
            }
            if (totalBytes > MAX_BACKUP_BYTES) {
                throw new IOException("Source file bytes exceed backup hard limit");
            }
        }
        return List.copyOf(envelope.files);
    }

    public void appendJournal(Map<String, ?> event) throws IOException {
        String line = COMPACT.toJson(event) + "\n";
        if (line.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_LINE_BYTES) {
            throw new IOException("Journal entry exceeds line limit");
        }
        IoUtil.appendUtf8Dsync(directory.resolve("changes.jsonl"), line, MAX_JOURNAL_BYTES);
    }

    public void appendToolLog(String action, String detail) throws IOException {
        String safeAction = oneLine(action, 48);
        String safeDetail = oneLine(detail, 1_024);
        String line = Instant.now() + " action=" + safeAction + " detail=" + safeDetail + "\n";
        IoUtil.appendUtf8Dsync(directory.resolve("tool.log"), line, MAX_TOOL_LOG_BYTES);
    }

    public void writeReport(String filename, Object report) throws IOException {
        if (!filename.matches("[a-z-]{1,48}\\.json")) {
            throw new IOException("Invalid report filename");
        }
        IoUtil.writeAtomicUtf8(directory.resolve(filename), PRETTY.toJson(report) + "\n");
    }

    public String issueToken(String action, String bindingSha256) throws IOException {
        String normalizedAction = normalizeAction(action);
        String token = randomHex(16);
        Confirmation confirmation = new Confirmation(
                normalizedAction,
                bindingSha256,
                IoUtil.sha256(token.getBytes(StandardCharsets.UTF_8)),
                Instant.now().plusSeconds(TOKEN_TTL_SECONDS).toString(),
                false
        );
        IoUtil.writeAtomicUtf8(
                directory.resolve("confirmation.json"),
                PRETTY.toJson(confirmation) + "\n"
        );
        return token;
    }

    public void consumeToken(String action, String bindingSha256, String token) throws IOException {
        Confirmation confirmation = readJson(
                directory.resolve("confirmation.json"),
                Confirmation.class,
                65_536
        );
        String normalizedAction = normalizeAction(action);
        Instant expiry;
        try {
            expiry = Instant.parse(confirmation.expiresAt);
        } catch (NullPointerException | DateTimeParseException invalid) {
            throw new IOException("Confirmation token record is invalid", invalid);
        }
        if (confirmation.consumed
                || !isSha256(confirmation.bindingSha256)
                || !isSha256(confirmation.tokenSha256)
                || !normalizedAction.equals(confirmation.action)
                || !bindingSha256.equals(confirmation.bindingSha256)
                || Instant.now().isAfter(expiry)) {
            throw new IOException("Confirmation token is invalid, consumed, or expired");
        }
        byte[] expected = HexFormat.of().parseHex(confirmation.tokenSha256);
        byte[] actual = HexFormat.of().parseHex(IoUtil.sha256(token.getBytes(StandardCharsets.UTF_8)));
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IOException("Confirmation token is invalid");
        }
        Confirmation consumed = new Confirmation(
                confirmation.action,
                confirmation.bindingSha256,
                confirmation.tokenSha256,
                confirmation.expiresAt,
                true
        );
        IoUtil.writeAtomicUtf8(
                directory.resolve("confirmation.json"),
                PRETTY.toJson(consumed) + "\n"
        );
    }

    public String manifestSha256() throws IOException {
        return IoUtil.sha256(directory.resolve("manifest.json"));
    }

    public Path backupPath(String relativeWorldPath) throws IOException {
        if (relativeWorldPath == null
                || relativeWorldPath.isBlank()
                || relativeWorldPath.indexOf('\\') >= 0) {
            throw new IOException("Invalid relative backup path");
        }
        Path backups = directory.resolve("backups").normalize();
        Path path = backups.resolve(relativeWorldPath.replace('/', java.io.File.separatorChar)).normalize();
        if (!path.startsWith(backups)) {
            throw new IOException("Backup path escaped job directory");
        }
        WorldAccessPolicy.rejectLinkChain(path);
        return path;
    }

    private static String normalizeAction(String action) throws IOException {
        String value = action.toLowerCase(Locale.ROOT);
        if (!value.equals("apply") && !value.equals("rollback")) {
            throw new IOException("Invalid confirmation action");
        }
        return value;
    }

    private static <T> T readJson(Path path, Class<T> type, long maxBytes) throws IOException {
        WorldAccessPolicy.rejectLinkChain(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("JSON input is not a regular file: " + path);
        }
        long size = Files.size(path);
        if (size < 1 || size > maxBytes) {
            throw new IOException("JSON file size is outside hard limits: " + path);
        }
        try {
            T value = PRETTY.fromJson(Files.readString(path, StandardCharsets.UTF_8), type);
            if (value == null) {
                throw new IOException("JSON file decoded to null: " + path);
            }
            return value;
        } catch (JsonParseException invalid) {
            throw new IOException("Invalid JSON file: " + path, invalid);
        }
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private static void validateTarget(LegacyChickenDataAdapter.Target target) throws IOException {
        if (target == null
                || target.dimension() == null
                || !target.dimension().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || !validRelativePath(target.regionRelativePath())
                || !target.regionRelativePath().endsWith(".mca")
                || target.chunkIndex() < 0
                || target.chunkIndex() >= 1_024
                || target.nbtPath() == null
                || target.nbtPath().length() > 16_384
                || target.attachmentTagType() == null
                || !isSha256(target.attachmentSha256())
                || !isSha256(target.entityPreconditionSha256())
                || target.entityType() == null
                || target.entityType().length() > 512
                || target.refusalReason() != null && target.refusalReason().length() > 512) {
            throw new IOException("Invalid target record");
        }
        if (target.entityUuid() != null) {
            try {
                if (!UUID.fromString(target.entityUuid()).toString().equals(target.entityUuid())) {
                    throw new IOException("Target UUID is not canonical");
                }
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid target UUID", invalid);
            }
        }
        if (target.addressable()
                && (target.entityUuid() == null
                || target.refusalReason() != null
                || !LegacyChickenDataAdapter.EXPECTED_ENTITY_TYPE.equals(target.entityType()))) {
            throw new IOException("Addressable target violates adapter preconditions");
        }
    }

    private static boolean validRelativePath(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 16_384
                || value.startsWith("/")
                || value.indexOf('\\') >= 0
                || value.indexOf('\0') >= 0
                || value.indexOf(':') >= 0) {
            return false;
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean validMetadata(String value) {
        return validText(value, 256);
    }

    private static boolean validText(String value, int maxLength) {
        return value != null
                && !value.isBlank()
                && value.length() <= maxLength
                && value.indexOf('\0') < 0
                && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }

    private static boolean validAbsolutePath(String value) {
        try {
            return value != null && Path.of(value).isAbsolute();
        } catch (java.nio.file.InvalidPathException invalid) {
            return false;
        }
    }

    private static boolean validInstant(String value) {
        try {
            Instant.parse(value);
            return true;
        } catch (NullPointerException | DateTimeParseException invalid) {
            return false;
        }
    }

    private static String oneLine(String value, int maxCharacters) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ');
        return normalized.length() <= maxCharacters
                ? normalized
                : normalized.substring(0, maxCharacters);
    }

    private record Confirmation(
            String action,
            String bindingSha256,
            String tokenSha256,
            String expiresAt,
            boolean consumed
    ) {
    }

    private static final class SourceEnvelope {
        private List<SourceFileRecord> files;
    }
}
