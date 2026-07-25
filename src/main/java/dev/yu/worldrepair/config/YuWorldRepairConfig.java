package dev.yu.worldrepair.config;

import dev.yu.worldrepair.guard.GuardMode;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class YuWorldRepairConfig {
    public static final String COMMON_FILE = "yuworldrepair-common.toml";
    public static final String CLIENT_FILE = "yuworldrepair-client.toml";

    private static final String DEFAULT_COMMON = """
            # YuWorldRepair never changes game data in observe or guard mode.
            mode = "observe"
            maxSignatures = 1024
            signatureTtlSeconds = 900
            burstPerSignature = 3
            windowSeconds = 60
            summaryIntervalSeconds = 60
            sampleStackTraces = 2
            maxStackFrames = 24
            maxEvidenceBytes = 65536
            includePlayerIdentity = false
            enableOriginMixins = false
            enableNegativeCache = false
            """;

    private static final String DEFAULT_CLIENT = """
            # Reserved for client-only presentation settings.
            # Guard policy is intentionally shared through yuworldrepair-common.toml.
            """;

    private final Path configDirectory;
    private final Logger logger;
    private final AtomicReference<RuntimeConfig> current = new AtomicReference<>(RuntimeConfig.safeDefaults());

    public YuWorldRepairConfig(Path configDirectory, Logger logger) {
        this.configDirectory = configDirectory.toAbsolutePath().normalize();
        this.logger = logger;
    }

    public RuntimeConfig current() {
        return current.get();
    }

    public synchronized RuntimeConfig load() {
        Path common = configDirectory.resolve(COMMON_FILE).normalize();
        Path client = configDirectory.resolve(CLIENT_FILE).normalize();
        if (!common.startsWith(configDirectory) || !client.startsWith(configDirectory)) {
            logger.error("YuWorldRepair config path validation failed; retaining safe defaults");
            RuntimeConfig safe = RuntimeConfig.safeDefaults();
            current.set(safe);
            return safe;
        }

        try {
            Files.createDirectories(configDirectory);
            writeDefaultIfMissing(common, DEFAULT_COMMON);
            writeDefaultIfMissing(client, DEFAULT_CLIENT);
            Map<String, String> parsed = parse(Files.readString(common, StandardCharsets.UTF_8));
            RuntimeConfig validated = ConfigValidator.validate(parsed, message -> logger.warn("[YuWorldRepair] {}", message));
            current.set(validated);
            return validated;
        } catch (Exception exception) {
            logger.error("[YuWorldRepair] Config load failed; using safe observe defaults: {}", exception.toString());
            RuntimeConfig safe = RuntimeConfig.safeDefaults();
            current.set(safe);
            return safe;
        }
    }

    public synchronized RuntimeConfig setMode(GuardMode mode) {
        RuntimeConfig before = current.get();
        RuntimeConfig after = new RuntimeConfig(
                mode,
                before.maxSignatures(),
                before.signatureTtlNanos(),
                before.burstPerSignature(),
                before.windowNanos(),
                before.summaryIntervalNanos(),
                before.sampleStackTraces(),
                before.maxStackFrames(),
                before.maxEvidenceBytes(),
                before.includePlayerIdentity(),
                before.enableOriginMixins(),
                before.enableNegativeCache()
        );
        current.set(after);
        return after;
    }

    static Map<String, String> parse(String text) {
        Map<String, String> values = new HashMap<>();
        int lineStart = 0;
        while (lineStart < text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            String line = stripComment(text.substring(lineStart, lineEnd)).trim();
            if (!line.isEmpty() && line.charAt(0) != '[') {
                int separator = line.indexOf('=');
                if (separator > 0) {
                    String key = line.substring(0, separator).trim();
                    String value = line.substring(separator + 1).trim();
                    if (!key.isEmpty() && !value.isEmpty()) {
                        values.put(key, value);
                    }
                }
            }
            lineStart = lineEnd + 1;
        }
        return values;
    }

    private static String stripComment(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (character == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (character == '#' && !quoted) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static void writeDefaultIfMissing(Path target, String contents) throws IOException {
        if (Files.exists(target)) {
            return;
        }
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(
                temporary,
                contents,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException noAtomicMove) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
