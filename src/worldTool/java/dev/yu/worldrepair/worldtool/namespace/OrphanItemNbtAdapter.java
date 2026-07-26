package dev.yu.worldrepair.worldtool.namespace;

import dev.yu.worldrepair.worldtool.maintenance.RegistrySnapshot;
import dev.yu.worldrepair.worldtool.nbt.Nbt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Explicit 1.21 item-storage adapters used by the global orphan-item maintenance mode.
 *
 * <p>The adapter recognizes vanilla ItemStack compounds, AE2 cell inventories, Refined Storage
 * item repositories, Mekanism QIO UUID/count components, and registered NeoForge attachment
 * containers at any traversed depth. It never treats arbitrary resource strings as items or
 * attachment identifiers.</p>
 */
final class OrphanItemNbtAdapter {
    private static final String ATTACHMENTS = "neoforge:attachments";
    private static final Pattern RESOURCE_ID =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Set<String> FIXED_ITEM_LISTS = Set.of(
            "armoritems",
            "handitems",
            "armor_items",
            "hand_items"
    );
    private static final Set<String> MUTABLE_NESTED_STORES = Set.of(
            "ae2:storage_cell_inv",
            "ae2:storage_cell_config_inv",
            "minecraft:container",
            "minecraft:bundle_contents",
            "minecraft:charged_projectiles"
    );
    private static final int MAX_DEPTH = 128;
    private static final int MAX_VISITED_TAGS = 10_000_000;

    List<NamespaceTarget> scanHolder(
            Nbt.CompoundTag holder,
            String path,
            NamespaceChunkAdapter.Context context,
            NamespacePolicy policy,
            OrphanItemIndex index
    ) throws IOException {
        if (!policy.isGlobalItemCleanup()) {
            return List.of();
        }
        Traversal traversal = new Traversal(context, policy, index, false);
        traversal.walkCompound(holder, path, 0);
        return traversal.result();
    }

    List<NamespaceTarget> scanAttachmentPayloads(
            Nbt.CompoundTag attachments,
            String path,
            NamespaceChunkAdapter.Context context,
            NamespacePolicy policy,
            OrphanItemIndex index
    ) throws IOException {
        if (!policy.isGlobalItemCleanup()) {
            return List.of();
        }
        Traversal traversal = new Traversal(context, policy, index, false);
        traversal.walkAttachmentPayloads(attachments, path, 0);
        return traversal.result();
    }

    int mutateHolder(
            Nbt.CompoundTag holder,
            NamespacePolicy policy,
            OrphanItemIndex index
    ) throws IOException {
        if (!policy.isGlobalItemCleanup()) {
            return 0;
        }
        Traversal traversal = new Traversal(null, policy, index, true);
        traversal.walkCompound(holder, "", 0);
        return traversal.changed;
    }

    ItemProblem itemEntityProblem(
            Nbt.CompoundTag entity,
            NamespacePolicy policy
    ) throws IOException {
        if (!policy.isGlobalItemCleanup()
                || !"minecraft:item".equals(entity.getString("id"))) {
            return null;
        }
        Nbt.CompoundTag stack = entity.getCompound("Item");
        return stack == null ? null : ordinaryItemProblem(stack, policy);
    }

    List<NamespaceTarget> scanSavedData(
            Nbt.CompoundTag root,
            NamespaceChunkAdapter.Context context,
            NamespacePolicy policy,
            OrphanItemIndex index
    ) throws IOException {
        if (!policy.isGlobalItemCleanup()) {
            return List.of();
        }
        Traversal traversal = new Traversal(context, policy, index, false);
        if (OrphanItemIndex.QIO_CACHE_PATH.equals(context.regionRelativePath())) {
            traversal.qioTypeCache(root);
        } else if ("data/refinedstorage_storages.dat"
                .equals(context.regionRelativePath())) {
            traversal.refinedStorage(root);
        }
        return traversal.result();
    }

    int mutateSavedData(
            Nbt.CompoundTag root,
            NamespaceChunkAdapter.Context context,
            NamespacePolicy policy,
            OrphanItemIndex index
    ) throws IOException {
        if (!policy.isGlobalItemCleanup()) {
            return 0;
        }
        Traversal traversal = new Traversal(context, policy, index, true);
        if (OrphanItemIndex.QIO_CACHE_PATH.equals(context.regionRelativePath())) {
            traversal.qioTypeCache(root);
        } else if ("data/refinedstorage_storages.dat"
                .equals(context.regionRelativePath())) {
            traversal.refinedStorage(root);
        }
        return traversal.changed;
    }

    static String invalidQioTypeResource(
            Nbt.CompoundTag stack,
            NamespacePolicy policy
    ) throws IOException {
        String own = orphanStackId(stack, policy);
        if (own != null) {
            return own;
        }
        Nbt.CompoundTag components = stack.getCompound("components");
        return components == null
                ? null
                : findNestedOrphan(components, policy, false, 0, new int[]{0});
    }

    private static ItemProblem ordinaryItemProblem(
            Nbt.CompoundTag stack,
            NamespacePolicy policy
    ) throws IOException {
        String own = orphanStackId(stack, policy);
        if (own != null) {
            return new ItemProblem(own, positiveAmount(stack.get("count")));
        }
        Nbt.CompoundTag components = stack.getCompound("components");
        String nested = components == null
                ? null
                : findNestedOrphan(components, policy, true, 0, new int[]{0});
        return nested == null
                ? null
                : new ItemProblem(nested, positiveAmount(stack.get("count")));
    }

    private static String keyedStorageProblem(
            Nbt.CompoundTag stack,
            NamespacePolicy policy
    ) throws IOException {
        String own = orphanStackId(stack, policy);
        if (own != null) {
            return own;
        }
        Nbt.CompoundTag components = stack.getCompound("components");
        return components == null
                ? null
                : findNestedOrphan(components, policy, false, 0, new int[]{0});
    }

    private static String orphanStackId(
            Nbt.CompoundTag stack,
            NamespacePolicy policy
    ) {
        String id = stack.getString("id");
        return isItemStack(stack)
                && policy.targets(RegistrySnapshot.Category.ITEM, id)
                ? id
                : null;
    }

    private static String findNestedOrphan(
            Nbt.Tag tag,
            NamespacePolicy policy,
            boolean skipMutableStores,
            int depth,
            int[] visited
    ) throws IOException {
        guard(depth, visited);
        if (tag instanceof Nbt.CompoundTag compound) {
            String own = orphanStackId(compound, policy);
            if (own != null) {
                return own;
            }
            for (String key : compound.keys().stream().sorted().toList()) {
                if (skipMutableStores && MUTABLE_NESTED_STORES.contains(key)) {
                    continue;
                }
                if (key.equals(ATTACHMENTS)) {
                    Nbt.Tag rawAttachments = compound.get(key);
                    if (!(rawAttachments instanceof Nbt.CompoundTag attachments)) {
                        throw new IOException("NeoForge attachments field is not a compound");
                    }
                    for (String attachmentId :
                            attachments.keys().stream().sorted().toList()) {
                        if (policy.targets(
                                RegistrySnapshot.Category.ATTACHMENT_TYPE,
                                attachmentId
                        )) {
                            continue;
                        }
                        String nested = findNestedOrphan(
                                attachments.get(attachmentId),
                                policy,
                                skipMutableStores,
                                depth + 1,
                                visited
                        );
                        if (nested != null) {
                            return nested;
                        }
                    }
                    continue;
                }
                String nested = findNestedOrphan(
                        compound.get(key),
                        policy,
                        skipMutableStores,
                        depth + 1,
                        visited
                );
                if (nested != null) {
                    return nested;
                }
            }
        } else if (tag instanceof Nbt.ListTag list) {
            for (int index = 0; index < list.size(); index++) {
                String nested = findNestedOrphan(
                        list.get(index),
                        policy,
                        skipMutableStores,
                        depth + 1,
                        visited
                );
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static boolean isItemStack(Nbt.CompoundTag compound) {
        String id = compound.getString("id");
        return id != null
                && RESOURCE_ID.matcher(id).matches()
                && positiveAmount(compound.get("count")) > 0;
    }

    private static long positiveAmount(Nbt.Tag tag) {
        long value = numeric(tag);
        return value > 0 ? value : 0;
    }

    private static long numeric(Nbt.Tag tag) {
        if (tag instanceof Nbt.ByteTag number) {
            return number.value();
        }
        if (tag instanceof Nbt.ShortTag number) {
            return number.value();
        }
        if (tag instanceof Nbt.IntTag number) {
            return number.value();
        }
        if (tag instanceof Nbt.LongTag number) {
            return number.value();
        }
        return -1;
    }

    private static void guard(int depth, int[] visited) throws IOException {
        if (depth > MAX_DEPTH || ++visited[0] > MAX_VISITED_TAGS) {
            throw new IOException("Item NBT traversal exceeds hard limits");
        }
    }

    record ItemProblem(String resourceId, long amount) {
    }

    private static final class Traversal {
        private final NamespaceChunkAdapter.Context context;
        private final NamespacePolicy policy;
        private final OrphanItemIndex index;
        private final boolean mutate;
        private final ArrayList<NamespaceTarget> targets = new ArrayList<>();
        private final int[] visited = {0};
        private int changed;

        private Traversal(
                NamespaceChunkAdapter.Context context,
                NamespacePolicy policy,
                OrphanItemIndex index,
                boolean mutate
        ) {
            this.context = context;
            this.policy = policy;
            this.index = index;
            this.mutate = mutate;
        }

        private List<NamespaceTarget> result() {
            targets.sort(Comparator
                    .comparing(NamespaceTarget::nbtPath)
                    .thenComparing(target -> target.action().name())
                    .thenComparing(NamespaceTarget::resourceId));
            return List.copyOf(targets);
        }

        private void walkCompound(
                Nbt.CompoundTag compound,
                String path,
                int depth
        ) throws IOException {
            guard(depth, visited);
            List<String> keys = compound.keys().stream().sorted().toList();
            for (String key : keys) {
                // Entity passengers are visited by NamespaceChunkAdapter with their own stable
                // entity paths, so descending here would duplicate every passenger item target.
                if (key.equals("Passengers")) {
                    continue;
                }
                Nbt.Tag raw = compound.get(key);
                if (raw == null) {
                    continue;
                }
                String childPath = child(path, key);
                if (key.equals(ATTACHMENTS)) {
                    if (!(raw instanceof Nbt.CompoundTag attachments)) {
                        throw new IOException("NeoForge attachments field is not a compound");
                    }
                    orphanAttachments(compound, attachments, childPath, depth + 1);
                } else if (raw instanceof Nbt.ListTag list
                        && (key.equals("ae2:storage_cell_inv")
                        || key.equals("ae2:storage_cell_config_inv"))) {
                    ae2List(list, childPath, key.endsWith("config_inv"), depth + 1);
                } else if (raw instanceof Nbt.LongArrayTag array
                        && key.equals("mekanism:drive_contents")) {
                    qioDrive(compound, key, array, childPath);
                } else if (raw instanceof Nbt.CompoundTag child
                        && isItemStack(child)) {
                    ItemProblem problem = ordinaryItemProblem(child, policy);
                    if (problem != null) {
                        target(
                                NamespaceTarget.Action.REMOVE_ITEM_FIELD,
                                childPath,
                                problem.resourceId(),
                                NamespaceTarget.Store.ITEM_STACK,
                                problem.amount()
                        );
                        if (mutate) {
                            compound.remove(key);
                        }
                    } else {
                        walkCompound(child, childPath, depth + 1);
                    }
                } else if (raw instanceof Nbt.ListTag list) {
                    itemList(list, key, childPath, depth + 1);
                } else if (raw instanceof Nbt.CompoundTag child) {
                    walkCompound(child, childPath, depth + 1);
                }
            }
        }

        private void orphanAttachments(
                Nbt.CompoundTag owner,
                Nbt.CompoundTag attachments,
                String path,
                int depth
        ) throws IOException {
            guard(depth, visited);
            Set<String> removals = new HashSet<>();
            for (String attachmentId : attachments.keys().stream().sorted().toList()) {
                if (policy.targets(
                        RegistrySnapshot.Category.ATTACHMENT_TYPE,
                        attachmentId
                )) {
                    removals.add(attachmentId);
                    target(
                            NamespaceTarget.Action.REMOVE_ATTACHMENT,
                            child(path, attachmentId),
                            attachmentId,
                            NamespaceTarget.Store.NAMESPACE,
                            0
                    );
                    if (mutate) {
                        attachments.remove(attachmentId);
                    }
                }
            }
            if (mutate && attachments.isEmpty()) {
                owner.remove(ATTACHMENTS);
                return;
            }
            for (String attachmentId : attachments.keys().stream().sorted().toList()) {
                // In scan mode the target is still present in the in-memory tree. Treat removal
                // as atomic and do not register child targets that mutation can never reach.
                if (!removals.contains(attachmentId)) {
                    walkTag(
                            attachments.get(attachmentId),
                            child(path, attachmentId),
                            depth + 1
                    );
                }
            }
        }

        private void walkAttachmentPayloads(
                Nbt.CompoundTag attachments,
                String path,
                int depth
        ) throws IOException {
            guard(depth, visited);
            for (String attachmentId : attachments.keys().stream().sorted().toList()) {
                // The owning NamespaceChunkAdapter has already registered these root attachment
                // removals. Only descend through payloads that will survive that mutation.
                if (!policy.targets(
                        RegistrySnapshot.Category.ATTACHMENT_TYPE,
                        attachmentId
                )) {
                    walkTag(
                            attachments.get(attachmentId),
                            child(path, attachmentId),
                            depth + 1
                    );
                }
            }
        }

        private void itemList(
                Nbt.ListTag list,
                String listName,
                String path,
                int depth
        ) throws IOException {
            guard(depth, visited);
            boolean fixed = FIXED_ITEM_LISTS.contains(
                    listName.toLowerCase(Locale.ROOT)
            );
            if (mutate) {
                for (int listIndex = list.size() - 1; listIndex >= 0; listIndex--) {
                    itemListEntry(list, listIndex, listName, path, fixed, depth);
                }
            } else {
                for (int listIndex = 0; listIndex < list.size(); listIndex++) {
                    itemListEntry(list, listIndex, listName, path, fixed, depth);
                }
            }
        }

        private void itemListEntry(
                Nbt.ListTag list,
                int listIndex,
                String listName,
                String path,
                boolean fixed,
                int depth
        ) throws IOException {
            Nbt.Tag raw = list.get(listIndex);
            String entryPath = path + "[" + listIndex + "]";
            if (!(raw instanceof Nbt.CompoundTag entry)) {
                walkTag(raw, entryPath, depth + 1);
                return;
            }
            if (isItemStack(entry)) {
                ItemProblem problem = ordinaryItemProblem(entry, policy);
                if (problem != null) {
                    NamespaceTarget.Action action = fixed
                            ? NamespaceTarget.Action.CLEAR_ITEM_STACK
                            : NamespaceTarget.Action.REMOVE_ITEM_STACK;
                    target(
                            action,
                            entryPath,
                            problem.resourceId(),
                            NamespaceTarget.Store.ITEM_STACK,
                            problem.amount()
                    );
                    if (mutate) {
                        if (fixed) {
                            list.set(listIndex, new Nbt.CompoundTag());
                        } else {
                            list.remove(listIndex);
                        }
                    }
                } else {
                    walkCompound(entry, entryPath, depth + 1);
                }
                return;
            }
            String stackKey = wrapperStackKey(entry);
            Nbt.CompoundTag stack = stackKey == null ? null : entry.getCompound(stackKey);
            if (stack != null) {
                ItemProblem problem = ordinaryItemProblem(stack, policy);
                if (problem != null) {
                    target(
                            NamespaceTarget.Action.REMOVE_ITEM_STACK,
                            entryPath,
                            problem.resourceId(),
                            NamespaceTarget.Store.ITEM_STACK,
                            problem.amount()
                    );
                    if (mutate) {
                        list.remove(listIndex);
                    }
                    return;
                }
                walkCompound(stack, child(entryPath, stackKey), depth + 1);
                walkCompoundExcept(entry, entryPath, stackKey, depth + 1);
                return;
            }
            walkCompound(entry, entryPath, depth + 1);
        }

        private void walkCompoundExcept(
                Nbt.CompoundTag compound,
                String path,
                String excluded,
                int depth
        ) throws IOException {
            guard(depth, visited);
            for (String key : compound.keys().stream().sorted().toList()) {
                if (!key.equals(excluded)) {
                    walkTag(compound.get(key), child(path, key), depth + 1);
                }
            }
        }

        private void walkTag(Nbt.Tag tag, String path, int depth) throws IOException {
            guard(depth, visited);
            if (tag instanceof Nbt.CompoundTag compound) {
                walkCompound(compound, path, depth + 1);
            } else if (tag instanceof Nbt.ListTag list) {
                itemList(list, "", path, depth + 1);
            }
        }

        private void ae2List(
                Nbt.ListTag list,
                String path,
                boolean config,
                int depth
        ) throws IOException {
            guard(depth, visited);
            if (list.elementType() != Nbt.COMPOUND
                    && !(list.elementType() == Nbt.END && list.size() == 0)) {
                throw new IOException("AE2 storage component is not a compound list");
            }
            if (mutate) {
                for (int itemIndex = list.size() - 1; itemIndex >= 0; itemIndex--) {
                    ae2Entry(list, itemIndex, path, config);
                }
            } else {
                for (int itemIndex = 0; itemIndex < list.size(); itemIndex++) {
                    ae2Entry(list, itemIndex, path, config);
                }
            }
        }

        private void ae2Entry(
                Nbt.ListTag list,
                int itemIndex,
                String path,
                boolean config
        ) throws IOException {
            Nbt.CompoundTag entry = (Nbt.CompoundTag) list.get(itemIndex);
            if (!"ae2:i".equals(entry.getString("#t"))) {
                return;
            }
            String id = entry.getString("id");
            String problem = policy.targets(RegistrySnapshot.Category.ITEM, id)
                    ? id
                    : findNestedOrphan(entry.get("components"), policy, false, 0, new int[]{0});
            if (problem == null) {
                return;
            }
            target(
                    config
                            ? NamespaceTarget.Action.REMOVE_AE2_CONFIG_ENTRY
                            : NamespaceTarget.Action.REMOVE_AE2_ENTRY,
                    path + "[" + itemIndex + "]",
                    problem,
                    NamespaceTarget.Store.AE2,
                    config ? 0 : positiveAmount(entry.get("#"))
            );
            if (mutate) {
                list.remove(itemIndex);
            }
        }

        private void qioDrive(
                Nbt.CompoundTag components,
                String key,
                Nbt.LongArrayTag contents,
                String path
        ) throws IOException {
            long[] values = contents.value();
            if (values.length % 3 != 0) {
                throw new IOException("Mekanism QIO drive contents length is invalid");
            }
            boolean[] remove = new boolean[values.length / 3];
            long removedAmount = 0;
            int removedTypes = 0;
            for (int offset = 0; offset < values.length; offset += 3) {
                OrphanItemIndex.QioType type = index.qioType(
                        values[offset],
                        values[offset + 1]
                );
                if (type == null) {
                    continue;
                }
                long amount = values[offset + 2];
                if (amount <= 0) {
                    throw new IOException("Mekanism QIO drive contains a non-positive amount");
                }
                remove[offset / 3] = true;
                removedAmount = Math.addExact(removedAmount, amount);
                removedTypes++;
                target(
                        NamespaceTarget.Action.REMOVE_QIO_DRIVE_ENTRY,
                        path + "[" + offset / 3 + "]",
                        type.issueResourceId(),
                        NamespaceTarget.Store.QIO,
                        amount
                );
            }
            if (!mutate || removedTypes == 0) {
                return;
            }
            long[] replacement = new long[values.length - removedTypes * 3];
            int targetOffset = 0;
            for (int entry = 0; entry < remove.length; entry++) {
                if (!remove[entry]) {
                    System.arraycopy(values, entry * 3, replacement, targetOffset, 3);
                    targetOffset += 3;
                }
            }
            components.put(key, new Nbt.LongArrayTag(replacement));
            updateQioMetadata(components, removedAmount, removedTypes);
        }

        private static void updateQioMetadata(
                Nbt.CompoundTag components,
                long removedAmount,
                int removedTypes
        ) throws IOException {
            Nbt.CompoundTag metadata = components.getCompound("mekanism:drive_metadata");
            if (metadata == null) {
                return;
            }
            long count = numeric(metadata.get("count"));
            long types = numeric(metadata.get("types"));
            if (count < removedAmount || types < removedTypes) {
                throw new IOException("Mekanism QIO drive metadata is inconsistent");
            }
            metadata.put("count", new Nbt.LongTag(count - removedAmount));
            metadata.put("types", new Nbt.IntTag(Math.toIntExact(types - removedTypes)));
        }

        private void qioTypeCache(Nbt.CompoundTag root) throws IOException {
            Nbt.CompoundTag payload = root.getCompound("data");
            if (payload == null) {
                throw new IOException("Mekanism QIO type cache has no data compound");
            }
            Nbt.CompoundTag items = payload.getCompound("items");
            if (items == null) {
                items = payload;
            }
            for (String key : items.keys().stream().sorted().toList()) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException notUuid) {
                    if (items == payload
                            && (key.equals("DataVersion") || key.equals("aliases"))) {
                        continue;
                    }
                    throw new IOException("Mekanism QIO type cache contains an invalid UUID");
                }
                OrphanItemIndex.QioType type = index.invalidQioTypes().get(uuid);
                if (type != null) {
                    target(
                            NamespaceTarget.Action.REMOVE_QIO_TYPE,
                            "data.items." + key,
                            type.issueResourceId(),
                            NamespaceTarget.Store.QIO,
                            0
                    );
                    if (mutate) {
                        items.remove(key);
                    }
                }
            }
            Nbt.CompoundTag aliases = payload.getCompound("aliases");
            if (aliases == null) {
                return;
            }
            for (String key : aliases.keys().stream().sorted().toList()) {
                UUID loser;
                try {
                    loser = UUID.fromString(key);
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Mekanism QIO alias contains an invalid UUID");
                }
                UUID winner = uuid(aliases.get(key));
                OrphanItemIndex.QioType type = index.invalidQioTypes().get(loser);
                if (type == null) {
                    type = index.invalidQioTypes().get(winner);
                }
                if (type != null) {
                    target(
                            NamespaceTarget.Action.REMOVE_QIO_ALIAS,
                            "data.aliases." + key,
                            type.issueResourceId(),
                            NamespaceTarget.Store.QIO,
                            0
                    );
                    if (mutate) {
                        aliases.remove(key);
                    }
                }
            }
        }

        private void refinedStorage(Nbt.CompoundTag root) throws IOException {
            Nbt.CompoundTag data = root.getCompound("data");
            if (data == null) {
                throw new IOException("Refined Storage repository has no data compound");
            }
            for (String storageId : data.keys().stream().sorted().toList()) {
                Nbt.CompoundTag storage = data.getCompound(storageId);
                if (storage == null
                        || !"refinedstorage:item".equals(storage.getString("type"))) {
                    continue;
                }
                Nbt.ListTag resources = storage.getList("resources");
                if (resources == null) {
                    throw new IOException("Refined Storage item storage has no resource list");
                }
                if (resources.elementType() != Nbt.COMPOUND
                        && !(resources.elementType() == Nbt.END
                        && resources.size() == 0)) {
                    throw new IOException("Refined Storage resources are not compounds");
                }
                if (mutate) {
                    for (int resourceIndex = resources.size() - 1;
                         resourceIndex >= 0;
                         resourceIndex--) {
                        refinedStorageEntry(
                                resources,
                                resourceIndex,
                                storageId
                        );
                    }
                } else {
                    for (int resourceIndex = 0;
                         resourceIndex < resources.size();
                         resourceIndex++) {
                        refinedStorageEntry(
                                resources,
                                resourceIndex,
                                storageId
                        );
                    }
                }
            }
        }

        private void refinedStorageEntry(
                Nbt.ListTag resources,
                int resourceIndex,
                String storageId
        ) throws IOException {
            Nbt.CompoundTag wrapper = (Nbt.CompoundTag) resources.get(resourceIndex);
            Nbt.CompoundTag resource = wrapper.getCompound("resource");
            if (resource == null) {
                throw new IOException("Refined Storage item resource is missing");
            }
            String id = resource.getString("item");
            String problem = policy.targets(RegistrySnapshot.Category.ITEM, id)
                    ? id
                    : findNestedOrphan(
                            resource.get("components"),
                            policy,
                            false,
                            0,
                            new int[]{0}
                    );
            if (problem == null) {
                return;
            }
            long amount = positiveAmount(wrapper.get("amount"));
            if (amount == 0) {
                throw new IOException("Refined Storage resource amount is not positive");
            }
            target(
                    NamespaceTarget.Action.REMOVE_RS_ENTRY,
                    "data." + storageId + ".resources[" + resourceIndex + "]",
                    problem,
                    NamespaceTarget.Store.REFINED_STORAGE,
                    amount
            );
            if (mutate) {
                resources.remove(resourceIndex);
            }
        }

        private void target(
                NamespaceTarget.Action action,
                String path,
                String resourceId,
                NamespaceTarget.Store store,
                long amount
        ) {
            if (mutate) {
                changed++;
                return;
            }
            targets.add(new NamespaceTarget(
                    context.dimension(),
                    context.regionRelativePath(),
                    context.chunkX(),
                    context.chunkZ(),
                    context.chunkIndex(),
                    context.external(),
                    context.regionKind(),
                    action,
                    path,
                    resourceId,
                    store,
                    amount
            ));
        }

        private static String wrapperStackKey(Nbt.CompoundTag wrapper) {
            for (String key : List.of("item", "Item", "stack", "Stack")) {
                Nbt.CompoundTag candidate = wrapper.getCompound(key);
                if (candidate != null && isItemStack(candidate)) {
                    return key;
                }
            }
            return null;
        }

        private static UUID uuid(Nbt.Tag tag) throws IOException {
            if (!(tag instanceof Nbt.IntArrayTag array)) {
                throw new IOException("Mekanism QIO alias target is not a UUID");
            }
            int[] value = array.value();
            if (value.length != 4) {
                throw new IOException("Mekanism QIO alias UUID length is invalid");
            }
            long most = (long) value[0] << 32 | value[1] & 0xffff_ffffL;
            long least = (long) value[2] << 32 | value[3] & 0xffff_ffffL;
            return new UUID(most, least);
        }

        private static String child(String parent, String key) {
            return parent == null || parent.isEmpty() ? key : parent + "." + key;
        }
    }
}
