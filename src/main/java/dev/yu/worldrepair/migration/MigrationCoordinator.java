package dev.yu.worldrepair.migration;

import dev.yu.worldrepair.integration.ae2.Ae2MigrationBridgeFactory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

public final class MigrationCoordinator {
    private final BooleanSupplier experimentalEnabled;
    private final Logger logger;
    private final ConfirmationTokens tokens = new ConfirmationTokens();

    public MigrationCoordinator(BooleanSupplier experimentalEnabled, Logger logger) {
        this.experimentalEnabled = experimentalEnabled;
        this.logger = logger;
    }

    public CoordinatorResult scanAe2(CommandSourceStack source, BlockPos position) {
        String refused = preflight(source);
        if (refused != null) {
            return CoordinatorResult.failure(refused);
        }
        try {
            SourceAdapterBridge adapter = adapter();
            if (adapter == null) {
                return CoordinatorResult.failure("AE2 19.2.17 migration adapter is unavailable or degraded");
            }
            MigrationJobRepository repository = repository(source.getServer());
            MigrationResult result = adapter.scanAndPrepare(
                    source,
                    position,
                    repository,
                    "console",
                    worldFingerprint(source.getServer())
            );
            if (!result.success() || result.manifest() == null) {
                return CoordinatorResult.from(result);
            }
            String token = tokens.issue(result.manifest().jobId(), ConfirmationTokens.Action.QUARANTINE);
            return new CoordinatorResult(
                    true,
                    result.message(),
                    result.manifest().jobId(),
                    token,
                    ConfirmationTokens.Action.QUARANTINE
            );
        } catch (Exception failure) {
            logger.warn("[YuWorldRepair] Migration scan failed safely", failure);
            return CoordinatorResult.failure("Migration scan failed: " + safeMessage(failure));
        }
    }

    public CoordinatorResult quarantine(CommandSourceStack source, String jobId, String token) {
        String refused = preflight(source);
        if (refused != null) {
            return CoordinatorResult.failure(refused);
        }
        if (!tokens.consume(token, jobId, ConfirmationTokens.Action.QUARANTINE)) {
            return CoordinatorResult.failure("Invalid, expired, or already-used quarantine token");
        }
        try {
            SourceAdapterBridge adapter = adapter();
            if (adapter == null) {
                return CoordinatorResult.failure("AE2 migration adapter unavailable");
            }
            MigrationJobRepository repository = repository(source.getServer());
            MigrationManifest manifest = repository.readManifest(jobId);
            String worldMismatch = validateWorld(source.getServer(), manifest);
            if (worldMismatch != null) {
                return CoordinatorResult.failure(worldMismatch);
            }
            MigrationResult result = adapter.quarantine(source.getServer(), manifest, repository);
            if (!result.success()) {
                return CoordinatorResult.from(result);
            }
            String restoreToken = tokens.issue(jobId, ConfirmationTokens.Action.RESTORE);
            return new CoordinatorResult(
                    true,
                    result.message(),
                    jobId,
                    restoreToken,
                    ConfirmationTokens.Action.RESTORE
            );
        } catch (Exception failure) {
            logger.error("[YuWorldRepair] Quarantine failed safely", failure);
            return CoordinatorResult.failure("Quarantine failed: " + safeMessage(failure));
        }
    }

    public CoordinatorResult restore(CommandSourceStack source, String jobId, String token) {
        String refused = preflight(source);
        if (refused != null) {
            return CoordinatorResult.failure(refused);
        }
        if (!tokens.consume(token, jobId, ConfirmationTokens.Action.RESTORE)) {
            return CoordinatorResult.failure("Invalid, expired, or already-used restore token");
        }
        try {
            SourceAdapterBridge adapter = adapter();
            if (adapter == null) {
                return CoordinatorResult.failure("AE2 migration adapter unavailable");
            }
            MigrationJobRepository repository = repository(source.getServer());
            MigrationManifest manifest = repository.readManifest(jobId);
            String worldMismatch = validateWorld(source.getServer(), manifest);
            if (worldMismatch != null) {
                return CoordinatorResult.failure(worldMismatch);
            }
            return CoordinatorResult.from(adapter.restore(source.getServer(), manifest, repository));
        } catch (Exception failure) {
            logger.error("[YuWorldRepair] Restore failed safely", failure);
            return CoordinatorResult.failure("Restore failed: " + safeMessage(failure));
        }
    }

    public CoordinatorResult verify(CommandSourceStack source, String jobId) {
        String refused = preflight(source);
        if (refused != null) {
            return CoordinatorResult.failure(refused);
        }
        try {
            SourceAdapterBridge adapter = adapter();
            if (adapter == null) {
                return CoordinatorResult.failure("AE2 migration adapter unavailable");
            }
            MigrationJobRepository repository = repository(source.getServer());
            MigrationManifest manifest = repository.readManifest(jobId);
            String worldMismatch = validateWorld(source.getServer(), manifest);
            if (worldMismatch != null) {
                return CoordinatorResult.failure(worldMismatch);
            }
            MigrationResult result = adapter.verify(source.getServer(), manifest, repository);
            ConfirmationTokens.Action action = switch (manifest.state()) {
                case PREPARED -> ConfirmationTokens.Action.QUARANTINE;
                case QUARANTINING, QUARANTINED, RESTORING -> ConfirmationTokens.Action.RESTORE;
                default -> null;
            };
            String nextToken = action == null ? null : tokens.issue(jobId, action);
            return new CoordinatorResult(
                    result.success(),
                    result.message(),
                    jobId,
                    nextToken,
                    action
            );
        } catch (Exception failure) {
            return CoordinatorResult.failure("Verification failed: " + safeMessage(failure));
        }
    }

    private String preflight(CommandSourceStack source) {
        if (!experimentalEnabled.getAsBoolean()) {
            return "Experimental migration is disabled by this build";
        }
        if (source.getEntity() != null) {
            return "Migration commands are console-only";
        }
        if (!source.getServer().isSameThread()) {
            return "Migration command is not on the server thread";
        }
        return null;
    }

    private SourceAdapterBridge adapter() {
        return Ae2MigrationBridgeFactory.create(logger);
    }

    private static MigrationJobRepository repository(MinecraftServer server) throws Exception {
        Path root = server.getWorldPath(LevelResource.ROOT);
        return new MigrationJobRepository(root);
    }

    private static String validateWorld(MinecraftServer server, MigrationManifest manifest) {
        if (!worldFingerprint(server).equals(manifest.worldFingerprint())) {
            return "World fingerprint does not match migration job";
        }
        if (!"ae2-network-missing-content/19.2.17".equals(manifest.adapter())) {
            return "Migration adapter does not match this build";
        }
        return null;
    }

    private static String worldFingerprint(MinecraftServer server) {
        return Hashing.textSha256(Long.toString(server.overworld().getSeed())).substring(0, 16);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message.length() <= 256 ? message : message.substring(0, 256);
    }

    public record CoordinatorResult(
            boolean success,
            String message,
            String jobId,
            String confirmationToken,
            ConfirmationTokens.Action confirmationAction
    ) {
        public static CoordinatorResult failure(String message) {
            return new CoordinatorResult(false, message, null, null, null);
        }

        public static CoordinatorResult from(MigrationResult result) {
            return new CoordinatorResult(
                    result.success(),
                    result.message(),
                    result.manifest() == null ? null : result.manifest().jobId(),
                    null,
                    null
            );
        }
    }
}
