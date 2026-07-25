package dev.yu.worldrepair.integration.ae2;

import dev.yu.worldrepair.integration.EnvironmentFingerprint;
import net.neoforged.fml.ModList;

public record Ae2Compatibility(Status status, String version, String detail) {
    public static final String VALIDATED_VERSION = "19.2.17";

    public enum Status {
        ABSENT,
        VALIDATED_READ_ONLY,
        DEGRADED_VERSION_MISMATCH
    }

    public static Ae2Compatibility detect() {
        if (!ModList.get().isLoaded("ae2")) {
            return new Ae2Compatibility(Status.ABSENT, "absent", "Core guard remains fully available");
        }
        String version = EnvironmentFingerprint.version("ae2");
        if (VALIDATED_VERSION.equals(version)) {
            return new Ae2Compatibility(
                    Status.VALIDATED_READ_ONLY,
                    version,
                    "Passive missing-content inspection enabled; no AE2 data is modified"
            );
        }
        return new Ae2Compatibility(
                Status.DEGRADED_VERSION_MISMATCH,
                version,
                "AE2-specific inspection disabled; precise Log4j rules remain fail-open"
        );
    }
}
