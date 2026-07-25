package dev.yu.worldrepair.worldtool.namespace;

import dev.yu.worldrepair.worldtool.maintenance.RegistrySnapshot;

import java.util.Locale;
import java.util.regex.Pattern;

public record NamespacePolicy(
        String namespace,
        Mode mode,
        RegistrySnapshot registrySnapshot
) {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern RESOURCE_ID =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public NamespacePolicy {
        namespace = namespace == null ? null : namespace.toLowerCase(Locale.ROOT);
        if (namespace == null
                || !NAMESPACE.matcher(namespace).matches()
                || namespace.equals("minecraft")
                || namespace.equals("neoforge")
                || namespace.equals("forge")
                || mode == null
                || registrySnapshot == null) {
            throw new IllegalArgumentException("Unsafe namespace repair policy");
        }
        registrySnapshot.validate();
    }

    public boolean targets(RegistrySnapshot.Category category, String resourceId) {
        if (resourceId == null
                || !RESOURCE_ID.matcher(resourceId).matches()
                || !resourceId.startsWith(namespace + ":")) {
            return false;
        }
        return mode == Mode.PREPARE_REMOVE
                || !registrySnapshot.contains(category, resourceId);
    }

    public enum Mode {
        ORPHANED_ONLY,
        PREPARE_REMOVE
    }
}
