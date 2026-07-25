package dev.yu.worldrepair.migration;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

public interface SourceAdapterBridge {
    String adapterId();

    MigrationResult scanAndPrepare(
            CommandSourceStack source,
            BlockPos position,
            MigrationJobRepository repository,
            String operator,
            String worldFingerprint
    );

    MigrationResult quarantine(
            MinecraftServer server,
            MigrationManifest manifest,
            MigrationJobRepository repository
    );

    MigrationResult restore(
            MinecraftServer server,
            MigrationManifest manifest,
            MigrationJobRepository repository
    );

    MigrationResult verify(
            MinecraftServer server,
            MigrationManifest manifest,
            MigrationJobRepository repository
    );
}
