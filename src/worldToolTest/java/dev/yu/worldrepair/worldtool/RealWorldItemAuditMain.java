package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.anvil.RegionFile;
import dev.yu.worldrepair.worldtool.nbt.Nbt;
import dev.yu.worldrepair.worldtool.nbt.NbtFile;
import dev.yu.worldrepair.worldtool.scan.WorldLayout;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Manual, read-only inventory-format probe for isolated real-world fixtures.
 *
 * <p>This intentionally reports candidate resource IDs and their NBT paths. It does not decide
 * that an ID is orphaned; that decision requires a signed live registry snapshot.</p>
 */
public final class RealWorldItemAuditMain {
    private static final Pattern RESOURCE_ID =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final int MAX_DEPTH = 128;
    private static final int MAX_PATH_SAMPLES = 8;
    private static final long MAX_SAVED_DATA_BYTES = 64L * 1_024 * 1_024;

    private RealWorldItemAuditMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 0) {
            throw new IllegalArgumentException("Expected one or more absolute world paths");
        }
        Map<String, Finding> findings = new LinkedHashMap<>();
        long[] visited = {0};
        for (String argument : arguments) {
            Path world = Path.of(argument).toAbsolutePath().normalize();
            scanRegions(world, findings, visited);
            scanPlayerData(world, findings, visited);
            scanSavedData(world, findings, visited);
        }
        findings.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> System.out.println(
                        "RESOURCE|" + entry.getKey()
                                + "|strings=" + entry.getValue().strings
                                + "|itemStacks=" + entry.getValue().itemStacks
                                + "|ae2Entries=" + entry.getValue().ae2Entries
                                + "|rsEntries=" + entry.getValue().rsEntries
                                + "|amount=" + entry.getValue().amount
                                + "|samples=" + String.join(";", entry.getValue().samples)
                ));
        System.out.println("COMPLETE|resources=" + findings.size() + "|tags=" + visited[0]);
    }

    static List<String> discoverItemIds(String[] arguments) throws Exception {
        Map<String, Finding> findings = new LinkedHashMap<>();
        long[] visited = {0};
        for (String argument : arguments) {
            Path world = Path.of(argument).toAbsolutePath().normalize();
            scanRegions(world, findings, visited);
            scanPlayerData(world, findings, visited);
            scanSavedData(world, findings, visited);
        }
        return findings.keySet().stream()
                .sorted()
                .toList();
    }

    private static void scanRegions(
            Path world,
            Map<String, Finding> findings,
            long[] visited
    ) throws Exception {
        for (WorldLayout.RegionDirectory directory :
                WorldLayout.discoverRegionDirectories(world)) {
            for (Path region : WorldLayout.regionFiles(world, directory)) {
                if (Files.size(region) == 0) {
                    continue;
                }
                String relative = normalize(world.relativize(region));
                RegionFile.visitChunks(region, Nbt.Limits.conservative(), chunk -> {
                    if (!(chunk.root().tag() instanceof Nbt.CompoundTag root)) {
                        return;
                    }
                    String listName = directory.kind() == WorldLayout.RegionDataKind.ENTITY
                            ? "Entities"
                            : "block_entities";
                    Nbt.Tag holders = root.get(listName);
                    if (holders != null) {
                        walk(
                                holders,
                                relative + "#slot=" + chunk.index(),
                                listName,
                                0,
                                findings,
                                visited
                        );
                    }
                });
            }
        }
    }

    private static void scanPlayerData(
            Path world,
            Map<String, Finding> findings,
            long[] visited
    ) throws Exception {
        Path directory = world.resolve("playerdata");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var files = Files.list(directory)) {
            for (Path file : files
                    .filter(path -> path.getFileName().toString()
                            .matches("[0-9a-fA-F-]{36}\\.dat"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .toList()) {
                walk(
                        NbtFile.readGzip(file, Nbt.Limits.conservative()).tag(),
                        normalize(world.relativize(file)),
                        "",
                        0,
                        findings,
                        visited
                );
            }
        }
    }

    private static void scanSavedData(
            Path world,
            Map<String, Finding> findings,
            long[] visited
    ) throws Exception {
        Path directory = world.resolve("data");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var files = Files.list(directory)) {
            for (Path file : files
                    .filter(path -> path.getFileName().toString().endsWith(".dat"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> {
                        try {
                            return Files.size(path) <= MAX_SAVED_DATA_BYTES;
                        } catch (Exception ignored) {
                            return false;
                        }
                    })
                    .sorted()
                    .toList()) {
                try {
                    walk(
                            NbtFile.readGzip(file, Nbt.Limits.conservative()).tag(),
                            normalize(world.relativize(file)),
                            "",
                            0,
                            findings,
                            visited
                    );
                } catch (Exception failure) {
                    System.out.println(
                            "GAP|" + normalize(world.relativize(file))
                                    + "|" + oneLine(failure.getMessage())
                    );
                }
            }
        }
    }

    private static void walk(
            Nbt.Tag tag,
            String source,
            String path,
            int depth,
            Map<String, Finding> findings,
            long[] visited
    ) {
        if (depth > MAX_DEPTH || ++visited[0] > 100_000_000L) {
            throw new IllegalStateException("NBT audit traversal exceeds hard limits");
        }
        if (tag instanceof Nbt.StringTag string) {
            if (RESOURCE_ID.matcher(string.value()).matches()) {
                recordString(findings, string.value(), source + ":" + path);
            }
            return;
        }
        if (tag instanceof Nbt.ListTag list) {
            for (int index = 0; index < list.size(); index++) {
                walk(
                        list.get(index),
                        source,
                        path + "[" + index + "]",
                        depth + 1,
                        findings,
                        visited
                );
            }
            return;
        }
        if (!(tag instanceof Nbt.CompoundTag compound)) {
            return;
        }

        String id = compound.getString("id");
        long count = numeric(compound.get("count"));
        if (RESOURCE_ID.matcher(id == null ? "" : id).matches() && count > 0) {
            recordItem(findings, id, count, source + ":" + path, Store.ITEM_STACK);
        }

        String ae2Type = compound.getString("#t");
        long ae2Amount = numeric(compound.get("#"));
        if ("ae2:i".equals(ae2Type)
                && RESOURCE_ID.matcher(id == null ? "" : id).matches()
                && ae2Amount > 0) {
            recordItem(findings, id, ae2Amount, source + ":" + path, Store.AE2);
        }

        Nbt.CompoundTag rsResource = compound.getCompound("resource");
        String rsItem = rsResource == null ? null : rsResource.getString("item");
        long rsAmount = numeric(compound.get("amount"));
        if (source.endsWith("data/refinedstorage_storages.dat")
                && RESOURCE_ID.matcher(rsItem == null ? "" : rsItem).matches()
                && rsAmount > 0) {
            recordItem(findings, rsItem, rsAmount, source + ":" + path, Store.RS);
        }

        List<String> keys = new ArrayList<>(compound.keys());
        keys.sort(Comparator.naturalOrder());
        for (String key : keys) {
            walk(
                    compound.get(key),
                    source,
                    path.isEmpty() ? key : path + "." + key,
                    depth + 1,
                    findings,
                    visited
            );
        }
    }

    private static void recordString(
            Map<String, Finding> findings,
            String id,
            String sample
    ) {
        Finding finding = findings.computeIfAbsent(id, ignored -> new Finding());
        finding.strings++;
        finding.addSample(sample);
    }

    private static void recordItem(
            Map<String, Finding> findings,
            String id,
            long amount,
            String sample,
            Store store
    ) {
        Finding finding = findings.computeIfAbsent(id, ignored -> new Finding());
        switch (store) {
            case ITEM_STACK -> finding.itemStacks++;
            case AE2 -> finding.ae2Entries++;
            case RS -> finding.rsEntries++;
        }
        finding.amount = Math.addExact(finding.amount, amount);
        finding.addSample(sample);
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

    private static String normalize(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static String oneLine(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private enum Store {
        ITEM_STACK,
        AE2,
        RS
    }

    private static final class Finding {
        private long strings;
        private long itemStacks;
        private long ae2Entries;
        private long rsEntries;
        private long amount;
        private final List<String> samples = new ArrayList<>();

        private void addSample(String sample) {
            if (samples.size() < MAX_PATH_SAMPLES && !samples.contains(sample)) {
                samples.add(sample);
            }
        }
    }
}
