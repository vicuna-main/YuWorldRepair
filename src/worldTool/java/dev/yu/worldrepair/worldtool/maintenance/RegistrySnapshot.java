package dev.yu.worldrepair.worldtool.maintenance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable registry evidence captured on the server thread immediately before a maintenance
 * request is signed. The offline worker never guesses whether a resource ID still exists.
 */
public record RegistrySnapshot(
        int schemaVersion,
        String capturedAt,
        String minecraftVersion,
        List<String> items,
        List<String> blocks,
        List<String> fluids,
        List<String> entityTypes,
        List<String> blockEntityTypes,
        List<String> attachmentTypes,
        List<String> dimensions
) {
    public static final int SCHEMA_VERSION = 2;
    public static final String FILE_NAME = "registry-snapshot.json";
    public static final long MAX_BYTES = 16L * 1_024 * 1_024;
    private static final int MAX_IDS_PER_REGISTRY = 1_048_576;
    private static final Pattern RESOURCE_ID =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Gson JSON =
            new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public RegistrySnapshot {
        items = immutable(items);
        blocks = immutable(blocks);
        fluids = immutable(fluids);
        entityTypes = immutable(entityTypes);
        blockEntityTypes = immutable(blockEntityTypes);
        attachmentTypes = immutable(attachmentTypes);
        dimensions = immutable(dimensions);
    }

    public void validate() {
        if (schemaVersion != SCHEMA_VERSION
                || capturedAt == null
                || minecraftVersion == null
                || minecraftVersion.isBlank()
                || minecraftVersion.length() > 256) {
            throw new IllegalArgumentException("Registry snapshot header is invalid");
        }
        Instant.parse(capturedAt);
        validateIds(items, "items");
        validateIds(blocks, "blocks");
        validateIds(fluids, "fluids");
        validateIds(entityTypes, "entityTypes");
        validateIds(blockEntityTypes, "blockEntityTypes");
        validateIds(attachmentTypes, "attachmentTypes");
        validateIds(dimensions, "dimensions");
    }

    public boolean contains(Category category, String resourceId) {
        return switch (category) {
            case ITEM -> java.util.Collections.binarySearch(items, resourceId) >= 0;
            case BLOCK -> java.util.Collections.binarySearch(blocks, resourceId) >= 0;
            case FLUID -> java.util.Collections.binarySearch(fluids, resourceId) >= 0;
            case ENTITY_TYPE -> java.util.Collections.binarySearch(entityTypes, resourceId) >= 0;
            case BLOCK_ENTITY_TYPE ->
                    java.util.Collections.binarySearch(blockEntityTypes, resourceId) >= 0;
            case ATTACHMENT_TYPE ->
                    java.util.Collections.binarySearch(attachmentTypes, resourceId) >= 0;
            case DIMENSION -> java.util.Collections.binarySearch(dimensions, resourceId) >= 0;
        };
    }

    public int countNamespace(Category category, String namespace) {
        List<String> ids = switch (category) {
            case ITEM -> items;
            case BLOCK -> blocks;
            case FLUID -> fluids;
            case ENTITY_TYPE -> entityTypes;
            case BLOCK_ENTITY_TYPE -> blockEntityTypes;
            case ATTACHMENT_TYPE -> attachmentTypes;
            case DIMENSION -> dimensions;
        };
        String prefix = namespace + ":";
        return Math.toIntExact(ids.stream().filter(value -> value.startsWith(prefix)).count());
    }

    public static RegistrySnapshot read(Path path, String expectedSha256) throws IOException {
        WorldAccessPolicy.rejectLinkChain(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || Files.size(path) < 1
                || Files.size(path) > MAX_BYTES) {
            throw new IOException("Registry snapshot is missing, linked, or oversized");
        }
        String actualHash = IoUtil.sha256(path);
        if (!actualHash.equals(expectedSha256)) {
            throw new IOException("Registry snapshot hash does not match signed request");
        }
        try {
            RegistrySnapshot snapshot = JSON.fromJson(
                    Files.readString(path, StandardCharsets.UTF_8),
                    RegistrySnapshot.class
            );
            if (snapshot == null) {
                throw new IOException("Registry snapshot is empty");
            }
            snapshot.validate();
            return snapshot;
        } catch (JsonParseException | IllegalArgumentException invalid) {
            throw new IOException("Registry snapshot is malformed", invalid);
        }
    }

    public static void write(Path path, RegistrySnapshot snapshot) throws IOException {
        snapshot.validate();
        IoUtil.writeAtomicUtf8(path, JSON.toJson(snapshot) + "\n");
        if (Files.size(path) > MAX_BYTES) {
            Files.deleteIfExists(path);
            throw new IOException("Registry snapshot exceeds hard size limit");
        }
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static void validateIds(List<String> ids, String name) {
        if (ids.size() > MAX_IDS_PER_REGISTRY) {
            throw new IllegalArgumentException(name + " exceeds hard entry limit");
        }
        Set<String> unique = new HashSet<>();
        String previous = null;
        for (String id : ids) {
            if (id == null
                    || !RESOURCE_ID.matcher(id).matches()
                    || !unique.add(id)
                    || previous != null && previous.compareTo(id) >= 0) {
                throw new IllegalArgumentException(name + " is not sorted unique resource IDs");
            }
            previous = id;
        }
    }

    public enum Category {
        ITEM,
        BLOCK,
        FLUID,
        ENTITY_TYPE,
        BLOCK_ENTITY_TYPE,
        ATTACHMENT_TYPE,
        DIMENSION
    }
}
