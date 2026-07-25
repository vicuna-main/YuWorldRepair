package dev.yu.worldrepair.integration;

import net.neoforged.fml.ModList;

import java.util.List;

public final class EnvironmentFingerprint {
    private static final List<String> TRACKED_MODS = List.of("minecraft", "neoforge", "ae2");

    private EnvironmentFingerprint() {
    }

    public static String capture() {
        StringBuilder result = new StringBuilder(128);
        for (String modId : TRACKED_MODS) {
            if (!result.isEmpty()) {
                result.append(',');
            }
            result.append(modId).append('=').append(version(modId));
        }
        String youer = System.getProperty("youer.version");
        if (youer != null && !youer.isBlank()) {
            result.append(",youer=").append(bounded(youer));
        }
        result.append(",java=").append(bounded(System.getProperty("java.version", "unknown")));
        return result.toString();
    }

    public static String version(String modId) {
        return ModList.get()
                .getModContainerById(modId)
                .map(container -> bounded(container.getModInfo().getVersion().toString()))
                .orElse("absent");
    }

    private static String bounded(String value) {
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
