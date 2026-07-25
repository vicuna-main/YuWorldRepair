package dev.yu.worldrepair.diagnostics;

import dev.yu.worldrepair.config.RuntimeConfig;
import dev.yu.worldrepair.guard.SignatureEntry;
import dev.yu.worldrepair.log.GuardRuntime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class DiagnosticReport {
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private DiagnosticReport() {
    }

    public static Path write(
            Path gameDirectory,
            GuardRuntime runtime,
            RuntimeConfig config,
            long lookbackSeconds
    ) throws IOException {
        Path reportRoot = gameDirectory.toAbsolutePath().normalize()
                .resolve("logs")
                .resolve("yuworldrepair")
                .resolve("reports")
                .normalize();
        if (!reportRoot.startsWith(gameDirectory.toAbsolutePath().normalize())) {
            throw new IOException("Report path escaped game directory");
        }
        Files.createDirectories(reportRoot);
        Instant now = Instant.now();
        Path target = reportRoot.resolve("report-" + FILE_TIME.format(now) + ".json").normalize();
        if (!target.startsWith(reportRoot)) {
            throw new IOException("Invalid report target");
        }

        String payload = render(runtime, config, lookbackSeconds, now);
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        int maximum = Math.max(1_024, config.maxEvidenceBytes());
        if (bytes.length > maximum) {
            payload = renderTruncated(runtime, config, lookbackSeconds, now, maximum);
        }

        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(
                temporary,
                payload,
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
        return target;
    }

    private static String render(
            GuardRuntime runtime,
            RuntimeConfig config,
            long lookbackSeconds,
            Instant now
    ) {
        StringBuilder json = header(runtime, config, lookbackSeconds, now);
        boolean first = true;
        for (SignatureEntry entry : runtime.signatures()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            appendEntry(json, entry);
        }
        return json.append("]}\n").toString();
    }

    private static String renderTruncated(
            GuardRuntime runtime,
            RuntimeConfig config,
            long lookbackSeconds,
            Instant now,
            int maximumBytes
    ) {
        StringBuilder json = header(runtime, config, lookbackSeconds, now);
        boolean first = true;
        int conservativeLimit = Math.max(512, maximumBytes - 128);
        for (SignatureEntry entry : runtime.signatures()) {
            int before = json.length();
            if (!first) {
                json.append(',');
            }
            appendEntry(json, entry);
            if (json.length() > conservativeLimit) {
                json.setLength(before);
                break;
            }
            first = false;
        }
        return json.append("],\"truncated\":true}\n").toString();
    }

    private static StringBuilder header(
            GuardRuntime runtime,
            RuntimeConfig config,
            long lookbackSeconds,
            Instant now
    ) {
        return new StringBuilder(4_096)
                .append("{\"schema\":1,\"generatedAt\":\"").append(now)
                .append("\",\"lookbackSeconds\":").append(lookbackSeconds)
                .append(",\"mode\":\"").append(config.mode().name().toLowerCase())
                .append("\",\"recognized\":").append(runtime.metrics().recognized())
                .append(",\"passed\":").append(runtime.metrics().passed())
                .append(",\"suppressed\":").append(runtime.metrics().suppressed())
                .append(",\"failOpen\":").append(runtime.metrics().failuresOpen())
                .append(",\"signatureEvicted\":").append(runtime.signatureEvictions())
                .append(",\"signatures\":[");
    }

    private static void appendEntry(StringBuilder json, SignatureEntry entry) {
        var signature = entry.signature();
        json.append("{\"hash\":\"").append(escape(signature.shortHash()))
                .append("\",\"side\":\"").append(signature.side())
                .append("\",\"domain\":\"").append(signature.domain())
                .append("\",\"logger\":\"").append(escape(signature.loggerName()))
                .append("\",\"template\":\"").append(escape(signature.normalizedTemplate()))
                .append("\",\"id\":\"").append(escape(signature.registryId()))
                .append("\",\"firstCaller\":\"").append(escape(signature.callerFingerprint()))
                .append("\",\"environment\":\"").append(escape(signature.environmentFingerprint()))
                .append("\",\"firstSeen\":\"").append(signature.firstSeen())
                .append("\",\"observed\":").append(entry.observed())
                .append(",\"passed\":").append(entry.allowed())
                .append(",\"suppressed\":").append(entry.suppressed())
                .append('}');
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append("\\u").append(String.format("%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
