package dev.yu.worldrepair.worldtool.namespace;

import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;
import dev.yu.worldrepair.worldtool.nbt.Nbt;
import dev.yu.worldrepair.worldtool.nbt.NbtFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-file evidence needed by orphan-item cleanup.
 *
 * <p>Mekanism QIO drives store only UUID/count triples. The UUID to ItemStack mapping lives in
 * {@code data/mekanism_qio_type_cache.dat}, so the mapping must be captured before any source
 * file is rewritten and reused through post-apply verification.</p>
 */
public record OrphanItemIndex(Map<UUID, QioType> invalidQioTypes) {
    public static final OrphanItemIndex EMPTY = new OrphanItemIndex(Map.of());
    public static final String QIO_CACHE_PATH = "data/mekanism_qio_type_cache.dat";

    public OrphanItemIndex {
        invalidQioTypes = invalidQioTypes == null
                ? Map.of()
                : Map.copyOf(invalidQioTypes);
    }

    public static OrphanItemIndex load(
            Path worldRoot,
            NamespacePolicy policy,
            Nbt.Limits limits
    ) throws IOException {
        if (!policy.isGlobalItemCleanup()) {
            return EMPTY;
        }
        Path supplied = worldRoot.resolve(
                QIO_CACHE_PATH.replace('/', java.io.File.separatorChar)
        ).normalize();
        if (!Files.exists(supplied, LinkOption.NOFOLLOW_LINKS)) {
            return EMPTY;
        }
        Path file = WorldAccessPolicy.requireContainedRegularFile(worldRoot, supplied);
        Nbt.Root nbt = NbtFile.readGzip(file, limits);
        if (!(nbt.tag() instanceof Nbt.CompoundTag root)) {
            throw new IOException("Mekanism QIO type cache root is not a compound");
        }
        Nbt.CompoundTag payload = root.getCompound("data");
        if (payload == null) {
            throw new IOException("Mekanism QIO type cache has no data compound");
        }
        Nbt.CompoundTag items = payload.getCompound("items");
        if (items == null) {
            // Mekanism supports this legacy shape only when aliases are also absent.
            if (payload.getCompound("aliases") != null) {
                throw new IOException("Mekanism QIO type cache has aliases but no items");
            }
            items = payload;
        }

        LinkedHashMap<UUID, QioType> invalid = new LinkedHashMap<>();
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
            if (!(items.get(key) instanceof Nbt.CompoundTag stack)) {
                throw new IOException("Mekanism QIO type cache item is not a compound");
            }
            String cachedId = stack.getString("id");
            String issueId = OrphanItemNbtAdapter.invalidQioTypeResource(stack, policy);
            if (issueId != null) {
                invalid.put(uuid, new QioType(issueId, cachedId));
            }
        }
        return invalid.isEmpty() ? EMPTY : new OrphanItemIndex(invalid);
    }

    public QioType qioType(long mostSignificantBits, long leastSignificantBits) {
        return invalidQioTypes.get(new UUID(mostSignificantBits, leastSignificantBits));
    }

    public boolean contains(UUID uuid) {
        return invalidQioTypes.containsKey(uuid);
    }

    public record QioType(String issueResourceId, String cachedResourceId) {
    }
}
