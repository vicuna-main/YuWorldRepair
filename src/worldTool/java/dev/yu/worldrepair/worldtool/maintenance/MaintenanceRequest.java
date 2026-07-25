package dev.yu.worldrepair.worldtool.maintenance;

import dev.yu.worldrepair.worldtool.namespace.NamespacePolicy;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public record MaintenanceRequest(
        int schemaVersion,
        String requestId,
        String authorizationSha256,
        String bindingHmacSha256,
        String createdAt,
        String expiresAt,
        long parentPid,
        Operation operation,
        String serverRoot,
        String worldRoot,
        String jobsRoot,
        String iceAndFireJar,
        String jobPath,
        String namespace,
        NamespacePolicy.Mode namespaceMode,
        String registrySnapshotPath,
        String registrySnapshotSha256,
        String minecraftVersion,
        String neoForgeVersion,
        String youerVersion,
        RestartStrategy restartStrategy,
        List<String> restartCommand,
        State state,
        String detail
) {
    public static final int SCHEMA_VERSION = 3;

    public MaintenanceRequest {
        restartCommand = restartCommand == null ? List.of() : List.copyOf(restartCommand);
    }

    public MaintenanceRequest withState(State next, String nextDetail) {
        return new MaintenanceRequest(
                schemaVersion,
                requestId,
                authorizationSha256,
                bindingHmacSha256,
                createdAt,
                expiresAt,
                parentPid,
                operation,
                serverRoot,
                worldRoot,
                jobsRoot,
                iceAndFireJar,
                jobPath,
                namespace,
                namespaceMode,
                registrySnapshotPath,
                registrySnapshotSha256,
                minecraftVersion,
                neoForgeVersion,
                youerVersion,
                restartStrategy,
                restartCommand,
                next,
                nextDetail
        );
    }

    public MaintenanceRequest withJobPath(String nextJobPath) {
        return new MaintenanceRequest(
                schemaVersion,
                requestId,
                authorizationSha256,
                bindingHmacSha256,
                createdAt,
                expiresAt,
                parentPid,
                operation,
                serverRoot,
                worldRoot,
                jobsRoot,
                iceAndFireJar,
                nextJobPath,
                namespace,
                namespaceMode,
                registrySnapshotPath,
                registrySnapshotSha256,
                minecraftVersion,
                neoForgeVersion,
                youerVersion,
                restartStrategy,
                restartCommand,
                state,
                detail
        );
    }

    public MaintenanceRequest withBindingHmac(String nextBindingHmacSha256) {
        return new MaintenanceRequest(
                schemaVersion,
                requestId,
                authorizationSha256,
                nextBindingHmacSha256,
                createdAt,
                expiresAt,
                parentPid,
                operation,
                serverRoot,
                worldRoot,
                jobsRoot,
                iceAndFireJar,
                jobPath,
                namespace,
                namespaceMode,
                registrySnapshotPath,
                registrySnapshotSha256,
                minecraftVersion,
                neoForgeVersion,
                youerVersion,
                restartStrategy,
                restartCommand,
                state,
                detail
        );
    }

    public String computeBindingHmac(String authorizationSecret) {
        if (authorizationSecret == null || authorizationSecret.length() < 32) {
            throw new IllegalArgumentException("Maintenance authorization secret is invalid");
        }
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(
                    authorizationSecret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));
            return HexFormat.of().formatHex(
                    hmac.doFinal(bindingText().getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("HmacSHA256 unavailable", unavailable);
        } catch (InvalidKeyException invalid) {
            throw new IllegalArgumentException("Maintenance authorization key is invalid", invalid);
        }
    }

    public void validate() {
        validateStructure();
        Instant created = Instant.parse(createdAt);
        Instant expires = Instant.parse(expiresAt);
        Instant now = Instant.now();
        if (!expires.isAfter(created)
                || expires.isAfter(created.plusSeconds(3_600))
                || now.isAfter(expires)
                || created.isAfter(now.plusSeconds(60))) {
            throw new IllegalArgumentException("Maintenance request is expired or has invalid times");
        }
    }

    public void validateStored() {
        validateStructure();
        Instant created = Instant.parse(createdAt);
        Instant expires = Instant.parse(expiresAt);
        if (!expires.isAfter(created) || expires.isAfter(created.plusSeconds(3_600))) {
            throw new IllegalArgumentException("Maintenance request has invalid stored times");
        }
    }

    private void validateStructure() {
        if (schemaVersion != SCHEMA_VERSION
                || requestId == null
                || !requestId.matches("[0-9a-fA-F-]{36}")
                || authorizationSha256 == null
                || !authorizationSha256.matches("[0-9a-f]{64}")
                || bindingHmacSha256 == null
                || !bindingHmacSha256.matches("[0-9a-f]{64}")
                || parentPid <= 0
                || operation == null
                || state == null
                || restartStrategy == null) {
            throw new IllegalArgumentException("Maintenance request header is invalid");
        }
        requirePath(serverRoot, "serverRoot");
        requirePath(worldRoot, "worldRoot");
        requirePath(jobsRoot, "jobsRoot");
        if (operation == Operation.REPAIR) {
            requirePath(iceAndFireJar, "iceAndFireJar");
            if (jobPath != null) {
                requirePath(jobPath, "jobPath");
            }
            requireAbsentNamespaceFields();
        } else if (operation == Operation.NAMESPACE_REPAIR) {
            if (namespace == null
                    || !namespace.matches("[a-z0-9_.-]{1,64}")
                    || namespace.equals("minecraft")
                    || namespace.equals("neoforge")
                    || namespace.equals("forge")
                    || namespaceMode == null
                    || registrySnapshotSha256 == null
                    || !registrySnapshotSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Namespace repair authorization is invalid");
            }
            requirePath(registrySnapshotPath, "registrySnapshotPath");
            if (iceAndFireJar != null) {
                throw new IllegalArgumentException("Namespace repair cannot bind Ice and Fire jar");
            }
            if (jobPath != null) {
                requirePath(jobPath, "jobPath");
            }
        } else {
            requirePath(jobPath, "jobPath");
            requireAbsentNamespaceFields();
        }
        if (restartStrategy == RestartStrategy.SELF && restartCommand.isEmpty()) {
            throw new IllegalArgumentException("Self restart requires a command");
        }
        if (restartCommand.size() > 64
                || restartCommand.stream().anyMatch(value -> value == null
                || value.isBlank()
                || value.length() > 4_096
                || value.indexOf('\0') >= 0)) {
            throw new IllegalArgumentException("Restart command is invalid");
        }
    }

    private static void requirePath(String value, String name) {
        if (value == null
                || value.isBlank()
                || value.length() > 32_768
                || value.indexOf('\0') >= 0
                || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }

    private String bindingText() {
        StringBuilder binding = new StringBuilder(1_024);
        append(binding, Integer.toString(schemaVersion));
        append(binding, requestId);
        append(binding, authorizationSha256);
        append(binding, createdAt);
        append(binding, expiresAt);
        append(binding, Long.toString(parentPid));
        append(binding, operation == null ? null : operation.name());
        append(binding, serverRoot);
        append(binding, worldRoot);
        append(binding, jobsRoot);
        append(binding, iceAndFireJar);
        append(binding, operation == Operation.ROLLBACK ? jobPath : null);
        append(binding, namespace);
        append(binding, namespaceMode == null ? null : namespaceMode.name());
        append(binding, registrySnapshotPath);
        append(binding, registrySnapshotSha256);
        append(binding, minecraftVersion);
        append(binding, neoForgeVersion);
        append(binding, youerVersion);
        append(binding, restartStrategy == null ? null : restartStrategy.name());
        binding.append(restartCommand.size()).append(':');
        for (String argument : restartCommand) {
            append(binding, argument);
        }
        return binding.toString();
    }

    private static void append(StringBuilder target, String value) {
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.length()).append(':').append(value);
        }
    }

    private void requireAbsentNamespaceFields() {
        if (namespace != null
                || namespaceMode != null
                || registrySnapshotPath != null
                || registrySnapshotSha256 != null) {
            throw new IllegalArgumentException("Unexpected namespace repair fields");
        }
    }

    public enum Operation {
        REPAIR,
        NAMESPACE_REPAIR,
        ROLLBACK
    }

    public enum RestartStrategy {
        PANEL,
        SELF,
        NONE
    }

    public enum State {
        REQUESTED,
        COUNTDOWN,
        HANDOFF,
        WAITING_FOR_STOP,
        SCANNING,
        BACKING_UP,
        APPLYING,
        VERIFYING,
        ROLLING_BACK,
        COMPLETED,
        ROLLED_BACK,
        FAILED
    }
}
