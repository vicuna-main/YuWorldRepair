package dev.yu.worldrepair.config;

import dev.yu.worldrepair.guard.GuardMode;

public record RuntimeConfig(
        GuardMode mode,
        int maxSignatures,
        long signatureTtlNanos,
        int burstPerSignature,
        long windowNanos,
        long summaryIntervalNanos,
        int sampleStackTraces,
        int maxStackFrames,
        int maxEvidenceBytes,
        boolean includePlayerIdentity,
        boolean enableOriginMixins,
        boolean enableNegativeCache
) {
    public static RuntimeConfig safeDefaults() {
        return new RuntimeConfig(
                GuardMode.OBSERVE,
                1_024,
                900_000_000_000L,
                3,
                60_000_000_000L,
                60_000_000_000L,
                2,
                24,
                65_536,
                false,
                false,
                false
        );
    }
}
