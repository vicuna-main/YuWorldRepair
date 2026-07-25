package dev.yu.worldrepair.worldtool.namespace;

import dev.yu.worldrepair.worldtool.maintenance.RegistrySnapshot;
import dev.yu.worldrepair.worldtool.nbt.Nbt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Strict 1.21/1.21.1 structural adapter. It only mutates holder fields whose on-disk ownership is
 * explicit in NBT. Unknown SavedData/network schemas are deliberately outside this adapter.
 */
public final class NamespaceChunkAdapter {
    private static final String ATTACHMENTS = "neoforge:attachments";
    private static final int MIN_DATA_VERSION = 3_953;
    private static final int MAX_DATA_VERSION = 3_955;
    private static final int MAX_VISITED_ENTITIES = 1_048_576;
    private static final int MAX_ENTITY_DEPTH = 64;

    public record Context(
            String dimension,
            String regionRelativePath,
            int chunkX,
            int chunkZ,
            int chunkIndex,
            boolean external,
            NamespaceTarget.RegionKind regionKind
    ) {
    }

    public record Mutation(int changed, String postSemanticSha256) {
    }

    public List<NamespaceTarget> scan(
            Nbt.CompoundTag root,
            Context context,
            NamespacePolicy policy
    ) throws IOException {
        requireSupportedDataVersion(root);
        ArrayList<NamespaceTarget> targets = new ArrayList<>();
        if (context.regionKind() == NamespaceTarget.RegionKind.ENTITY) {
            scanEntityChunk(root, context, policy, targets);
        } else if (context.regionKind() == NamespaceTarget.RegionKind.CHUNK) {
            scanBlockChunk(root, context, policy, targets);
        } else {
            scanAttachments(root, "", context, policy, targets);
        }
        targets.sort(Comparator
                .comparing(NamespaceTarget::nbtPath)
                .thenComparing(target -> target.action().name())
                .thenComparing(NamespaceTarget::resourceId));
        return List.copyOf(targets);
    }

    public Mutation mutate(
            Nbt.CompoundTag root,
            Context context,
            NamespacePolicy policy,
            List<NamespaceTarget> expected
    ) throws IOException {
        List<NamespaceTarget> actual = scan(root, context, policy);
        if (!actual.equals(expected)) {
            throw new IOException("Namespace target set changed after scan for "
                    + context.regionRelativePath() + " slot " + context.chunkIndex());
        }
        int changed;
        if (context.regionKind() == NamespaceTarget.RegionKind.ENTITY) {
            changed = mutateEntityChunk(root, policy);
        } else if (context.regionKind() == NamespaceTarget.RegionKind.CHUNK) {
            changed = mutateBlockChunk(root, policy);
        } else {
            changed = mutateAttachments(root, policy);
        }
        if (changed != expected.size()) {
            throw new IOException("Namespace mutation count does not match exact target set");
        }
        return new Mutation(changed, Nbt.semanticSha256(root));
    }

    private static void scanEntityChunk(
            Nbt.CompoundTag root,
            Context context,
            NamespacePolicy policy,
            List<NamespaceTarget> targets
    ) throws IOException {
        Nbt.ListTag entities = requireCompoundList(root, "Entities", false);
        if (entities == null) {
            return;
        }
        int[] visited = {0};
        for (int index = 0; index < entities.size(); index++) {
            scanEntity(
                    (Nbt.CompoundTag) entities.get(index),
                    "Entities[" + index + "]",
                    0,
                    context,
                    policy,
                    targets,
                    visited
            );
        }
    }

    private static void scanEntity(
            Nbt.CompoundTag entity,
            String path,
            int depth,
            Context context,
            NamespacePolicy policy,
            List<NamespaceTarget> targets,
            int[] visited
    ) throws IOException {
        guardEntityTraversal(depth, visited);
        String entityId = entity.getString("id");
        if (policy.targets(RegistrySnapshot.Category.ENTITY_TYPE, entityId)) {
            targets.add(target(
                    context,
                    NamespaceTarget.Action.REMOVE_ENTITY,
                    path,
                    entityId
            ));
            return;
        }
        scanAttachments(entity, path, context, policy, targets);
        Nbt.ListTag passengers = requireCompoundList(entity, "Passengers", false);
        if (passengers == null) {
            return;
        }
        for (int index = 0; index < passengers.size(); index++) {
            scanEntity(
                    (Nbt.CompoundTag) passengers.get(index),
                    path + ".Passengers[" + index + "]",
                    depth + 1,
                    context,
                    policy,
                    targets,
                    visited
            );
        }
    }

    private static void scanBlockChunk(
            Nbt.CompoundTag root,
            Context context,
            NamespacePolicy policy,
            List<NamespaceTarget> targets
    ) throws IOException {
        scanAttachments(root, "", context, policy, targets);
        Nbt.ListTag sections = requireCompoundList(root, "sections", false);
        if (sections != null) {
            for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
                Nbt.CompoundTag section = (Nbt.CompoundTag) sections.get(sectionIndex);
                Nbt.CompoundTag blockStates = section.getCompound("block_states");
                if (blockStates == null) {
                    continue;
                }
                Nbt.ListTag palette = requireCompoundList(blockStates, "palette", true);
                for (int paletteIndex = 0; paletteIndex < palette.size(); paletteIndex++) {
                    Nbt.CompoundTag state = (Nbt.CompoundTag) palette.get(paletteIndex);
                    String blockId = state.getString("Name");
                    if (policy.targets(RegistrySnapshot.Category.BLOCK, blockId)) {
                        targets.add(target(
                                context,
                                NamespaceTarget.Action.REPLACE_BLOCK_WITH_AIR,
                                "sections[" + sectionIndex + "].block_states.palette["
                                        + paletteIndex + "]",
                                blockId
                        ));
                    }
                }
            }
        }
        scanIdList(
                root,
                "block_entities",
                "id",
                RegistrySnapshot.Category.BLOCK_ENTITY_TYPE,
                NamespaceTarget.Action.REMOVE_BLOCK_ENTITY,
                context,
                policy,
                targets
        );
        scanIdList(
                root,
                "block_ticks",
                "i",
                RegistrySnapshot.Category.BLOCK,
                NamespaceTarget.Action.REMOVE_BLOCK_TICK,
                context,
                policy,
                targets
        );
        scanIdList(
                root,
                "fluid_ticks",
                "i",
                RegistrySnapshot.Category.FLUID,
                NamespaceTarget.Action.REMOVE_FLUID_TICK,
                context,
                policy,
                targets
        );
    }

    private static void scanAttachments(
            Nbt.CompoundTag holder,
            String holderPath,
            Context context,
            NamespacePolicy policy,
            List<NamespaceTarget> targets
    ) {
        Nbt.CompoundTag attachments = holder.getCompound(ATTACHMENTS);
        if (attachments == null) {
            return;
        }
        attachments.keys().stream().sorted().forEach(key -> {
            if (policy.targets(RegistrySnapshot.Category.ATTACHMENT_TYPE, key)) {
                String prefix = holderPath.isEmpty() ? "" : holderPath + ".";
                targets.add(target(
                        context,
                        NamespaceTarget.Action.REMOVE_ATTACHMENT,
                        prefix + ATTACHMENTS + "." + key,
                        key
                ));
            }
        });
    }

    private static void scanIdList(
            Nbt.CompoundTag root,
            String listName,
            String idKey,
            RegistrySnapshot.Category category,
            NamespaceTarget.Action action,
            Context context,
            NamespacePolicy policy,
            List<NamespaceTarget> targets
    ) throws IOException {
        Nbt.ListTag list = requireCompoundList(root, listName, false);
        if (list == null) {
            return;
        }
        for (int index = 0; index < list.size(); index++) {
            Nbt.CompoundTag entry = (Nbt.CompoundTag) list.get(index);
            String id = entry.getString(idKey);
            if (policy.targets(category, id)) {
                targets.add(target(context, action, listName + "[" + index + "]", id));
            }
        }
    }

    private static int mutateEntityChunk(
            Nbt.CompoundTag root,
            NamespacePolicy policy
    ) throws IOException {
        Nbt.ListTag entities = requireCompoundList(root, "Entities", false);
        if (entities == null) {
            return 0;
        }
        int[] changed = {0};
        int[] visited = {0};
        for (int index = entities.size() - 1; index >= 0; index--) {
            Nbt.CompoundTag entity = (Nbt.CompoundTag) entities.get(index);
            if (mutateEntity(entity, 0, policy, changed, visited)) {
                entities.remove(index);
                changed[0]++;
            }
        }
        return changed[0];
    }

    private static boolean mutateEntity(
            Nbt.CompoundTag entity,
            int depth,
            NamespacePolicy policy,
            int[] changed,
            int[] visited
    ) throws IOException {
        guardEntityTraversal(depth, visited);
        if (policy.targets(
                RegistrySnapshot.Category.ENTITY_TYPE,
                entity.getString("id")
        )) {
            return true;
        }
        changed[0] += mutateAttachments(entity, policy);
        Nbt.ListTag passengers = requireCompoundList(entity, "Passengers", false);
        if (passengers != null) {
            for (int index = passengers.size() - 1; index >= 0; index--) {
                Nbt.CompoundTag passenger = (Nbt.CompoundTag) passengers.get(index);
                if (mutateEntity(passenger, depth + 1, policy, changed, visited)) {
                    passengers.remove(index);
                    changed[0]++;
                }
            }
        }
        return false;
    }

    private static int mutateBlockChunk(
            Nbt.CompoundTag root,
            NamespacePolicy policy
    ) throws IOException {
        int changed = mutateAttachments(root, policy);
        Nbt.ListTag sections = requireCompoundList(root, "sections", false);
        if (sections != null) {
            for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
                Nbt.CompoundTag section = (Nbt.CompoundTag) sections.get(sectionIndex);
                Nbt.CompoundTag blockStates = section.getCompound("block_states");
                if (blockStates == null) {
                    continue;
                }
                Nbt.ListTag palette = requireCompoundList(blockStates, "palette", true);
                for (int paletteIndex = 0; paletteIndex < palette.size(); paletteIndex++) {
                    Nbt.CompoundTag state = (Nbt.CompoundTag) palette.get(paletteIndex);
                    if (policy.targets(
                            RegistrySnapshot.Category.BLOCK,
                            state.getString("Name")
                    )) {
                        state.put("Name", new Nbt.StringTag("minecraft:air"));
                        state.remove("Properties");
                        changed++;
                    }
                }
            }
        }
        changed += mutateIdList(
                root,
                "block_entities",
                "id",
                RegistrySnapshot.Category.BLOCK_ENTITY_TYPE,
                policy
        );
        changed += mutateIdList(
                root,
                "block_ticks",
                "i",
                RegistrySnapshot.Category.BLOCK,
                policy
        );
        changed += mutateIdList(
                root,
                "fluid_ticks",
                "i",
                RegistrySnapshot.Category.FLUID,
                policy
        );
        return changed;
    }

    private static int mutateAttachments(Nbt.CompoundTag holder, NamespacePolicy policy) {
        Nbt.CompoundTag attachments = holder.getCompound(ATTACHMENTS);
        if (attachments == null) {
            return 0;
        }
        List<String> removals = attachments.keys().stream()
                .filter(key -> policy.targets(RegistrySnapshot.Category.ATTACHMENT_TYPE, key))
                .toList();
        removals.forEach(attachments::remove);
        if (attachments.isEmpty()) {
            holder.remove(ATTACHMENTS);
        }
        return removals.size();
    }

    private static int mutateIdList(
            Nbt.CompoundTag root,
            String listName,
            String idKey,
            RegistrySnapshot.Category category,
            NamespacePolicy policy
    ) throws IOException {
        Nbt.ListTag list = requireCompoundList(root, listName, false);
        if (list == null) {
            return 0;
        }
        int removed = 0;
        for (int index = list.size() - 1; index >= 0; index--) {
            Nbt.CompoundTag entry = (Nbt.CompoundTag) list.get(index);
            if (policy.targets(category, entry.getString(idKey))) {
                list.remove(index);
                removed++;
            }
        }
        return removed;
    }

    private static Nbt.ListTag requireCompoundList(
            Nbt.CompoundTag holder,
            String key,
            boolean required
    ) throws IOException {
        Nbt.Tag raw = holder.get(key);
        if (raw == null) {
            if (required) {
                throw new IOException("Required compound list is missing: " + key);
            }
            return null;
        }
        if (!(raw instanceof Nbt.ListTag list)
                || list.elementType() != Nbt.COMPOUND
                && !(list.elementType() == Nbt.END && list.size() == 0)) {
            throw new IOException("NBT field is not a compound list: " + key);
        }
        return list;
    }

    private static void requireSupportedDataVersion(Nbt.CompoundTag root) throws IOException {
        Nbt.Tag raw = root.get("DataVersion");
        if (!(raw instanceof Nbt.IntTag version)
                || version.value() < MIN_DATA_VERSION
                || version.value() > MAX_DATA_VERSION) {
            throw new IOException("Namespace adapter only supports DataVersion "
                    + MIN_DATA_VERSION + ".." + MAX_DATA_VERSION);
        }
    }

    private static void guardEntityTraversal(int depth, int[] visited) throws IOException {
        if (depth > MAX_ENTITY_DEPTH || ++visited[0] > MAX_VISITED_ENTITIES) {
            throw new IOException("Entity tree exceeds namespace adapter hard limits");
        }
    }

    private static NamespaceTarget target(
            Context context,
            NamespaceTarget.Action action,
            String path,
            String resourceId
    ) {
        return new NamespaceTarget(
                context.dimension(),
                context.regionRelativePath(),
                context.chunkX(),
                context.chunkZ(),
                context.chunkIndex(),
                context.external(),
                context.regionKind(),
                action,
                path,
                resourceId
        );
    }
}
