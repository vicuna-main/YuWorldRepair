package dev.yu.worldrepair.maintenance;

import dev.yu.worldrepair.worldtool.maintenance.RegistrySnapshot;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.time.Instant;
import java.util.List;

final class RegistrySnapshotFactory {
    private RegistrySnapshotFactory() {
    }

    static RegistrySnapshot capture(MinecraftServer server) {
        RegistrySnapshot snapshot = new RegistrySnapshot(
                RegistrySnapshot.SCHEMA_VERSION,
                Instant.now().toString(),
                server.getServerVersion(),
                ids(BuiltInRegistries.ITEM),
                ids(BuiltInRegistries.BLOCK),
                ids(BuiltInRegistries.FLUID),
                ids(BuiltInRegistries.ENTITY_TYPE),
                ids(BuiltInRegistries.BLOCK_ENTITY_TYPE),
                NeoForgeRegistries.ATTACHMENT_TYPES.keySet().stream()
                        .map(ResourceLocation::toString)
                        .sorted()
                        .toList(),
                server.levelKeys().stream()
                        .map(key -> key.location().toString())
                        .sorted()
                        .toList()
        );
        snapshot.validate();
        return snapshot;
    }

    private static List<String> ids(Registry<?> registry) {
        return registry.keySet().stream()
                .map(ResourceLocation::toString)
                .sorted()
                .toList();
    }
}
