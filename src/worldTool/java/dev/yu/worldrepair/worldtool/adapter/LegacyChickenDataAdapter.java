package dev.yu.worldrepair.worldtool.adapter;

import dev.yu.worldrepair.worldtool.nbt.Nbt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Exact, version-bound transform for the removed Ice and Fire chicken attachment.
 */
public final class LegacyChickenDataAdapter {
    public static final String ADAPTER_ID = "iceandfire-chicken-data/2.0-beta.17";
    public static final String ATTACHMENTS_KEY = "neoforge:attachments";
    public static final String TARGET_KEY = "iceandfire:chicken_data";
    public static final String EXPECTED_ENTITY_TYPE = "minecraft:chicken";
    public static final String VERIFIED_ICE_AND_FIRE_SHA256 =
            "ee52349445615417e69ab64cf15dbb96b9332a9117225119d281695c5f8d90f0";
    public static final int MAX_ENTITIES_PER_CHUNK = 16_384;
    public static final int MAX_PASSENGER_DEPTH = 32;

    public record Context(
            String dimension,
            String regionRelativePath,
            int chunkX,
            int chunkZ,
            int chunkIndex,
            boolean external
    ) {
    }

    public record Target(
            String dimension,
            int chunkX,
            int chunkZ,
            int chunkIndex,
            String regionRelativePath,
            String entityUuid,
            String entityType,
            String nbtPath,
            String attachmentTagType,
            String attachmentSha256,
            String entityPreconditionSha256,
            boolean externalChunk,
            boolean addressable,
            String refusalReason
    ) {
        public Target withRefusal(String reason) {
            return new Target(
                    dimension,
                    chunkX,
                    chunkZ,
                    chunkIndex,
                    regionRelativePath,
                    entityUuid,
                    entityType,
                    nbtPath,
                    attachmentTagType,
                    attachmentSha256,
                    entityPreconditionSha256,
                    externalChunk,
                    false,
                    reason
            );
        }
    }

    public record Mutation(int removed, String postSemanticSha256) {
    }

    public List<Target> scan(Nbt.CompoundTag chunkRoot, Context context) throws IOException {
        Nbt.ListTag entities = chunkRoot.getList("Entities");
        if (entities == null) {
            return List.of();
        }
        if (entities.elementType() != Nbt.COMPOUND) {
            throw new IOException("Entity chunk has non-compound Entities list");
        }
        ArrayList<Target> targets = new ArrayList<>();
        int[] visited = {0};
        for (int index = 0; index < entities.size(); index++) {
            Nbt.Tag value = entities.get(index);
            if (!(value instanceof Nbt.CompoundTag entity)) {
                throw new IOException("Entity list contains a non-compound value");
            }
            scanEntity(entity, "Entities[" + index + "]", 0, context, targets, visited);
        }
        return List.copyOf(targets);
    }

    public Mutation removeExactTargets(
            Nbt.CompoundTag chunkRoot,
            Context context,
            List<Target> expectedTargets
    ) throws IOException {
        Map<String, Target> byUuid = new HashMap<>();
        for (Target target : expectedTargets) {
            if (!target.addressable() || target.entityUuid() == null) {
                throw new IOException("Job contains an unaddressable target");
            }
            Target duplicate = byUuid.put(target.entityUuid(), target);
            if (duplicate != null) {
                throw new IOException("Duplicate target UUID in one chunk");
            }
        }
        Set<String> removed = new HashSet<>();
        Nbt.ListTag entities = chunkRoot.getList("Entities");
        if (entities == null || entities.elementType() != Nbt.COMPOUND) {
            throw new IOException("Entity chunk no longer contains a valid Entities list");
        }
        int[] visited = {0};
        for (int index = 0; index < entities.size(); index++) {
            mutateEntity(
                    (Nbt.CompoundTag) entities.get(index),
                    "Entities[" + index + "]",
                    0,
                    context,
                    byUuid,
                    removed,
                    visited
            );
        }
        if (removed.size() != expectedTargets.size()) {
            Set<String> missing = new HashSet<>(byUuid.keySet());
            missing.removeAll(removed);
            throw new IOException("Expected target UUIDs were not found: " + missing);
        }
        return new Mutation(removed.size(), Nbt.semanticSha256(chunkRoot));
    }

    private static void scanEntity(
            Nbt.CompoundTag entity,
            String path,
            int depth,
            Context context,
            List<Target> targets,
            int[] visited
    ) throws IOException {
        guardTraversal(depth, visited);
        Nbt.CompoundTag attachments = entity.getCompound(ATTACHMENTS_KEY);
        if (attachments != null && attachments.contains(TARGET_KEY)) {
            Nbt.Tag attachment = attachments.get(TARGET_KEY);
            String uuid = uuid(entity);
            String entityType = entity.getString("id");
            boolean addressable = true;
            String refusal = null;
            if (uuid == null) {
                addressable = false;
                refusal = "missing_or_malformed_uuid";
            } else if (!EXPECTED_ENTITY_TYPE.equals(entityType)) {
                addressable = false;
                refusal = "unexpected_entity_type";
            }
            targets.add(new Target(
                    context.dimension(),
                    context.chunkX(),
                    context.chunkZ(),
                    context.chunkIndex(),
                    context.regionRelativePath(),
                    uuid,
                    entityType == null ? "unknown" : entityType,
                    path + "." + ATTACHMENTS_KEY + "." + TARGET_KEY,
                    typeName(attachment.type()),
                    Nbt.semanticSha256(attachment),
                    Nbt.semanticSha256(entity),
                    context.external(),
                    addressable,
                    refusal
            ));
        }
        Nbt.ListTag passengers = entity.getList("Passengers");
        if (passengers == null) {
            return;
        }
        if (passengers.elementType() != Nbt.COMPOUND) {
            throw new IOException("Passengers list is not a compound list at " + path);
        }
        for (int index = 0; index < passengers.size(); index++) {
            scanEntity(
                    (Nbt.CompoundTag) passengers.get(index),
                    path + ".Passengers[" + index + "]",
                    depth + 1,
                    context,
                    targets,
                    visited
            );
        }
    }

    private static void mutateEntity(
            Nbt.CompoundTag entity,
            String path,
            int depth,
            Context context,
            Map<String, Target> expected,
            Set<String> removed,
            int[] visited
    ) throws IOException {
        guardTraversal(depth, visited);
        Nbt.CompoundTag attachments = entity.getCompound(ATTACHMENTS_KEY);
        if (attachments != null && attachments.contains(TARGET_KEY)) {
            String uuid = uuid(entity);
            Target target = uuid == null ? null : expected.get(uuid);
            if (target == null) {
                throw new IOException("Unexpected legacy attachment encountered during apply at " + path);
            }
            if (!target.dimension().equals(context.dimension())
                    || target.chunkX() != context.chunkX()
                    || target.chunkZ() != context.chunkZ()
                    || target.chunkIndex() != context.chunkIndex()
                    || !target.regionRelativePath().equals(context.regionRelativePath())) {
                throw new IOException("Target location precondition changed for UUID " + uuid);
            }
            String entityType = entity.getString("id");
            if (!EXPECTED_ENTITY_TYPE.equals(entityType) || !entityType.equals(target.entityType())) {
                throw new IOException("Entity type precondition changed for UUID " + uuid);
            }
            if (!Nbt.semanticSha256(entity).equals(target.entityPreconditionSha256())) {
                throw new IOException("Entity NBT precondition changed for UUID " + uuid);
            }
            Nbt.Tag attachment = attachments.get(TARGET_KEY);
            if (!Nbt.semanticSha256(attachment).equals(target.attachmentSha256())) {
                throw new IOException("Attachment precondition changed for UUID " + uuid);
            }
            attachments.remove(TARGET_KEY);
            if (attachments.isEmpty()) {
                entity.remove(ATTACHMENTS_KEY);
            }
            if (!removed.add(uuid)) {
                throw new IOException("Target UUID occurred more than once during apply: " + uuid);
            }
        }
        Nbt.ListTag passengers = entity.getList("Passengers");
        if (passengers == null) {
            return;
        }
        if (passengers.elementType() != Nbt.COMPOUND) {
            throw new IOException("Passengers list is not a compound list at " + path);
        }
        for (int index = 0; index < passengers.size(); index++) {
            mutateEntity(
                    (Nbt.CompoundTag) passengers.get(index),
                    path + ".Passengers[" + index + "]",
                    depth + 1,
                    context,
                    expected,
                    removed,
                    visited
            );
        }
    }

    private static void guardTraversal(int depth, int[] visited) throws IOException {
        if (depth > MAX_PASSENGER_DEPTH) {
            throw new IOException("Passenger tree exceeds depth limit");
        }
        if (++visited[0] > MAX_ENTITIES_PER_CHUNK) {
            throw new IOException("Entity chunk exceeds entity traversal limit");
        }
    }

    private static String uuid(Nbt.CompoundTag entity) {
        Nbt.Tag tag = entity.get("UUID");
        if (tag instanceof Nbt.IntArrayTag uuidArray) {
            int[] values = uuidArray.value();
            if (values.length == 4) {
                long most = (Integer.toUnsignedLong(values[0]) << 32) | Integer.toUnsignedLong(values[1]);
                long least = (Integer.toUnsignedLong(values[2]) << 32) | Integer.toUnsignedLong(values[3]);
                return new UUID(most, least).toString();
            }
        }
        Nbt.Tag mostTag = entity.get("UUIDMost");
        Nbt.Tag leastTag = entity.get("UUIDLeast");
        if (mostTag instanceof Nbt.LongTag most && leastTag instanceof Nbt.LongTag least) {
            return new UUID(most.value(), least.value()).toString();
        }
        return null;
    }

    private static String typeName(byte type) {
        return switch (type) {
            case Nbt.BYTE -> "BYTE";
            case Nbt.SHORT -> "SHORT";
            case Nbt.INT -> "INT";
            case Nbt.LONG -> "LONG";
            case Nbt.FLOAT -> "FLOAT";
            case Nbt.DOUBLE -> "DOUBLE";
            case Nbt.BYTE_ARRAY -> "BYTE_ARRAY";
            case Nbt.STRING -> "STRING";
            case Nbt.LIST -> "LIST";
            case Nbt.COMPOUND -> "COMPOUND";
            case Nbt.INT_ARRAY -> "INT_ARRAY";
            case Nbt.LONG_ARRAY -> "LONG_ARRAY";
            default -> "UNKNOWN";
        };
    }
}
