package dev.yu.worldrepair.config;

import dev.yu.worldrepair.guard.GuardMode;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

public final class ConfigValidator {
    private ConfigValidator() {
    }

    public static RuntimeConfig validate(Map<String, String> values, Consumer<String> warningSink) {
        RuntimeConfig defaults = RuntimeConfig.safeDefaults();
        GuardMode mode = parseMode(values.get("mode"), defaults.mode(), warningSink);
        int maxSignatures = parseInt(values, "maxSignatures", defaults.maxSignatures(), 64, 65_536, warningSink);
        long ttl = seconds(values, "signatureTtlSeconds", defaults.signatureTtlNanos(), 30, 86_400, warningSink);
        int burst = parseInt(values, "burstPerSignature", defaults.burstPerSignature(), 1, 100, warningSink);
        long window = seconds(values, "windowSeconds", defaults.windowNanos(), 1, 3_600, warningSink);
        long summary = seconds(values, "summaryIntervalSeconds", defaults.summaryIntervalNanos(), 5, 3_600, warningSink);
        int samples = parseInt(values, "sampleStackTraces", defaults.sampleStackTraces(), 0, 16, warningSink);
        int frames = parseInt(values, "maxStackFrames", defaults.maxStackFrames(), 1, 128, warningSink);
        int evidenceBytes = parseInt(values, "maxEvidenceBytes", defaults.maxEvidenceBytes(), 0, 4 * 1_024 * 1_024, warningSink);

        return new RuntimeConfig(
                mode,
                maxSignatures,
                ttl,
                burst,
                window,
                summary,
                samples,
                frames,
                evidenceBytes,
                parseBoolean(values, "includePlayerIdentity", defaults.includePlayerIdentity(), warningSink),
                parseBoolean(values, "enableOriginMixins", defaults.enableOriginMixins(), warningSink),
                parseBoolean(values, "enableNegativeCache", defaults.enableNegativeCache(), warningSink)
        );
    }

    private static GuardMode parseMode(String raw, GuardMode fallback, Consumer<String> warnings) {
        if (raw == null) {
            return fallback;
        }
        String normalized = unquote(raw);
        if ("observe".equalsIgnoreCase(normalized)) {
            return GuardMode.OBSERVE;
        }
        if ("guard".equalsIgnoreCase(normalized)) {
            return GuardMode.GUARD;
        }
        warnings.accept("Invalid mode; using observe");
        return fallback;
    }

    private static int parseInt(
            Map<String, String> values,
            String key,
            int fallback,
            int min,
            int max,
            Consumer<String> warnings
    ) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(unquote(raw));
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // The safe fallback below is intentional.
        }
        warnings.accept("Invalid " + key + "; using " + fallback);
        return fallback;
    }

    private static long seconds(
            Map<String, String> values,
            String key,
            long fallbackNanos,
            int minSeconds,
            int maxSeconds,
            Consumer<String> warnings
    ) {
        int fallbackSeconds = Math.toIntExact(Duration.ofNanos(fallbackNanos).toSeconds());
        return Duration.ofSeconds(parseInt(values, key, fallbackSeconds, minSeconds, maxSeconds, warnings)).toNanos();
    }

    private static boolean parseBoolean(
            Map<String, String> values,
            String key,
            boolean fallback,
            Consumer<String> warnings
    ) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        String normalized = unquote(raw);
        if ("true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return false;
        }
        warnings.accept("Invalid " + key + "; using " + fallback);
        return fallback;
    }

    static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '"' && trimmed.charAt(trimmed.length() - 1) == '"') {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
