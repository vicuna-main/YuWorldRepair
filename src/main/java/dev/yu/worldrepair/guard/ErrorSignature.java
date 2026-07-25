package dev.yu.worldrepair.guard;

import java.time.Instant;

public record ErrorSignature(
        LogicalSide side,
        ErrorDomain domain,
        String loggerName,
        String normalizedTemplate,
        String registryId,
        String callerFingerprint,
        String environmentFingerprint,
        String shortHash,
        Instant firstSeen
) {
}
