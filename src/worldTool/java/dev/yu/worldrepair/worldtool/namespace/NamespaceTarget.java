package dev.yu.worldrepair.worldtool.namespace;

public record NamespaceTarget(
        String dimension,
        String regionRelativePath,
        int chunkX,
        int chunkZ,
        int chunkIndex,
        boolean externalChunk,
        RegionKind regionKind,
        Action action,
        String nbtPath,
        String resourceId
) {
    public enum RegionKind {
        ENTITY,
        CHUNK,
        PLAYER
    }

    public enum Action {
        REMOVE_ENTITY,
        REMOVE_ATTACHMENT,
        REPLACE_BLOCK_WITH_AIR,
        REMOVE_BLOCK_ENTITY,
        REMOVE_BLOCK_TICK,
        REMOVE_FLUID_TICK
    }
}
