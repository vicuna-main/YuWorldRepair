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
        String resourceId,
        Store store,
        long amount
) {
    public NamespaceTarget {
        if (store == null) {
            store = inferStore(action);
        }
    }

    public NamespaceTarget(
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
        this(
                dimension,
                regionRelativePath,
                chunkX,
                chunkZ,
                chunkIndex,
                externalChunk,
                regionKind,
                action,
                nbtPath,
                resourceId,
                inferStore(action),
                0
        );
    }

    public enum RegionKind {
        ENTITY,
        CHUNK,
        PLAYER,
        SAVED_DATA
    }

    public enum Action {
        REMOVE_ENTITY,
        REMOVE_ATTACHMENT,
        REPLACE_BLOCK_WITH_AIR,
        REMOVE_BLOCK_ENTITY,
        REMOVE_BLOCK_TICK,
        REMOVE_FLUID_TICK,
        REMOVE_ITEM_ENTITY,
        REMOVE_ITEM_STACK,
        CLEAR_ITEM_STACK,
        REMOVE_ITEM_FIELD,
        REMOVE_AE2_ENTRY,
        REMOVE_AE2_CONFIG_ENTRY,
        REMOVE_RS_ENTRY,
        REMOVE_QIO_TYPE,
        REMOVE_QIO_ALIAS,
        REMOVE_QIO_DRIVE_ENTRY
    }

    public enum Store {
        NAMESPACE,
        ITEM_STACK,
        AE2,
        REFINED_STORAGE,
        QIO
    }

    private static Store inferStore(Action action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case REMOVE_AE2_ENTRY, REMOVE_AE2_CONFIG_ENTRY -> Store.AE2;
            case REMOVE_RS_ENTRY -> Store.REFINED_STORAGE;
            case REMOVE_QIO_TYPE, REMOVE_QIO_ALIAS, REMOVE_QIO_DRIVE_ENTRY -> Store.QIO;
            case REMOVE_ITEM_ENTITY, REMOVE_ITEM_STACK, CLEAR_ITEM_STACK, REMOVE_ITEM_FIELD ->
                    Store.ITEM_STACK;
            default -> Store.NAMESPACE;
        };
    }
}
