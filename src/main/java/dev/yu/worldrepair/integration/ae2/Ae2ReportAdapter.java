package dev.yu.worldrepair.integration.ae2;

import dev.yu.worldrepair.guard.ErrorDomain;
import dev.yu.worldrepair.guard.SignatureEntry;
import dev.yu.worldrepair.log.GuardRuntime;

import java.util.List;

/**
 * Read-only report adapter. It summarizes only content already observed at AE2's own
 * missing-content boundaries and never traverses or rewrites a storage network.
 */
public final class Ae2ReportAdapter {
    private Ae2ReportAdapter() {
    }

    public static List<SignatureEntry> observedMissingContent(GuardRuntime runtime) {
        return runtime.signatures().stream()
                .filter(entry -> entry.signature().domain() == ErrorDomain.AE_KEY
                        || entry.signature().loggerName().equals("appeng.util.AECodecs"))
                .toList();
    }
}
