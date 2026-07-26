package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.anvil.RegionFile;
import dev.yu.worldrepair.worldtool.nbt.Nbt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Read-only, structure-agnostic audit for locating legacy identifiers in a real world copy.
 *
 * <p>The production scanners intentionally restrict themselves to data structures they can
 * mutate safely. This probe is broader: it walks every Anvil file (including POI), attempts every
 * standalone {@code .dat}/{@code .dat_old} file as gzip, zlib and raw NBT, checks compound keys
 * and string values recursively, and searches raw/byte-array payloads for the requested markers.
 * It never writes to the supplied world.</p>
 */
public final class RealWorldDeepNbtAuditMain {
    private static final Nbt.Limits LIMITS =
            new Nbt.Limits(64L * 1_024 * 1_024, 128, 4_000_000, 4 * 1_024 * 1_024);
    private static final List<String> NEEDLES = List.of(
            "iceandfire:chicken_data",
            "chicken_data",
            "iceandfire"
    );
    private static final long MAX_RAW_FILE_BYTES = 128L * 1_024 * 1_024;

    private RealWorldDeepNbtAuditMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected one absolute world path");
        }
        Path world = Path.of(arguments[0]).toAbsolutePath().normalize().toRealPath();
        Counters counters = new Counters();
        List<Path> files;
        try (var walked = Files.walk(world)) {
            files = walked
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(path -> normalize(world.relativize(path))))
                    .toList();
        }
        for (Path file : files) {
            counters.files++;
            String relative = normalize(world.relativize(file));
            String lowerName = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (lowerName.endsWith(".mca")) {
                scanRegion(file, relative, counters);
            } else if (lowerName.endsWith(".dat") || lowerName.endsWith(".dat_old")) {
                scanStandalone(file, relative, counters);
            }
            scanRawFile(file, relative, counters);
        }
        System.out.println(
                "COMPLETE|world=" + world
                        + "|files=" + counters.files
                        + "|regions=" + counters.regions
                        + "|chunks=" + counters.chunks
                        + "|standaloneNbt=" + counters.standaloneNbt
                        + "|tags=" + counters.tags
                        + "|matches=" + counters.matches
                        + "|exactAttachments=" + counters.exactAttachments
                        + "|emptyRegions=" + counters.emptyRegions
                        + "|regionGaps=" + counters.regionGaps
                        + "|datUnparsed=" + counters.datUnparsed
        );
    }

    private static void scanRegion(
            Path file,
            String relative,
            Counters counters
    ) {
        counters.regions++;
        try {
            if (Files.size(file) == 0) {
                counters.emptyRegions++;
                return;
            }
            RegionFile.visitChunks(file, LIMITS, chunk -> {
                counters.chunks++;
                walk(
                        chunk.root().tag(),
                        relative,
                        "chunk[" + chunk.chunkX() + "," + chunk.chunkZ() + "]",
                        "",
                        counters,
                        0
                );
            });
        } catch (Exception failure) {
            counters.regionGaps++;
            System.out.println("REGION_GAP|" + relative + "|" + oneLine(failure));
        }
    }

    private static void scanStandalone(
            Path file,
            String relative,
            Counters counters
    ) throws IOException {
        long size = Files.size(file);
        if (size < 1 || size > MAX_RAW_FILE_BYTES) {
            counters.datUnparsed++;
            System.out.println("DAT_UNPARSED|" + relative + "|size_outside_probe_limit");
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        ArrayList<Decoder> decoders = new ArrayList<>();
        if (isGzip(bytes)) {
            decoders.add(new Decoder("gzip", input -> new GZIPInputStream(input, 16 * 1_024)));
        } else {
            decoders.add(new Decoder("zlib", input -> new InflaterInputStream(input)));
            decoders.add(new Decoder("raw", input -> input));
        }
        Exception last = null;
        for (Decoder decoder : decoders) {
            try (InputStream raw = new ByteArrayInputStream(bytes);
                 InputStream decoded = decoder.open(raw)) {
                Nbt.Root root = Nbt.readRoot(decoded, LIMITS);
                if (decoded.read() != -1) {
                    throw new IOException("Trailing bytes after NBT root");
                }
                counters.standaloneNbt++;
                walk(root.tag(), relative, "root", "", counters, 0);
                return;
            } catch (Exception failure) {
                last = failure;
            }
        }
        counters.datUnparsed++;
        System.out.println(
                "DAT_UNPARSED|" + relative + "|"
                        + (last == null ? "unknown" : oneLine(last))
        );
    }

    private static void scanRawFile(
            Path file,
            String relative,
            Counters counters
    ) throws IOException {
        long size = Files.size(file);
        if (size < 1 || size > MAX_RAW_FILE_BYTES) {
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        scanBytes(bytes, relative, "raw-file", counters);
    }

    private static void walk(
            Nbt.Tag tag,
            String source,
            String path,
            String idContext,
            Counters counters,
            int depth
    ) throws IOException {
        if (depth > LIMITS.maxDepth()) {
            throw new IOException("Audit traversal exceeded depth limit");
        }
        counters.tags++;
        if (tag instanceof Nbt.CompoundTag compound) {
            String localId = compound.getString("id");
            String nestedContext = localId == null
                    ? idContext
                    : idContext.isEmpty() ? localId : idContext + " > " + localId;
            for (String key : compound.keys().stream().sorted().toList()) {
                Nbt.Tag child = compound.get(key);
                if (key.equals("iceandfire:chicken_data")) {
                    counters.exactAttachments++;
                    System.out.println(
                            "EXACT_ATTACHMENT|source=" + source
                                    + "|path=" + child(path, key)
                                    + "|tag=" + summarize(child)
                                    + "|ids=" + oneLine(nestedContext)
                    );
                }
                matchText(key, source, child(path, key) + "::<key>", counters);
                walk(
                        child,
                        source,
                        child(path, key),
                        nestedContext,
                        counters,
                        depth + 1
                );
            }
            return;
        }
        if (tag instanceof Nbt.ListTag list) {
            for (int index = 0; index < list.size(); index++) {
                walk(
                        list.get(index),
                        source,
                        path + "[" + index + "]",
                        idContext,
                        counters,
                        depth + 1
                );
            }
            return;
        }
        if (tag instanceof Nbt.StringTag string) {
            matchText(string.value(), source, path + "::<string>", counters);
            return;
        }
        if (tag instanceof Nbt.ByteArrayTag array) {
            scanBytes(array.value(), source, path + "::<byte-array>", counters);
        }
    }

    private static String summarize(Nbt.Tag tag) {
        if (tag instanceof Nbt.CompoundTag compound) {
            return "compound(keys=" + String.join(",", compound.keys().stream().sorted().toList())
                    + ")";
        }
        if (tag instanceof Nbt.ListTag list) {
            return "list(type=" + list.elementType() + ",size=" + list.size() + ")";
        }
        if (tag instanceof Nbt.StringTag string) {
            return "string(" + oneLine(string.value()) + ")";
        }
        if (tag instanceof Nbt.ByteTag value) {
            return "byte(" + value.value() + ")";
        }
        if (tag instanceof Nbt.ShortTag value) {
            return "short(" + value.value() + ")";
        }
        if (tag instanceof Nbt.IntTag value) {
            return "int(" + value.value() + ")";
        }
        if (tag instanceof Nbt.LongTag value) {
            return "long(" + value.value() + ")";
        }
        if (tag instanceof Nbt.ByteArrayTag value) {
            return "byte-array(size=" + value.value().length + ")";
        }
        if (tag instanceof Nbt.IntArrayTag value) {
            return "int-array(size=" + value.value().length + ")";
        }
        if (tag instanceof Nbt.LongArrayTag value) {
            return "long-array(size=" + value.value().length + ")";
        }
        return "tag(type=" + tag.type() + ")";
    }

    private static void matchText(
            String value,
            String source,
            String path,
            Counters counters
    ) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String needle : NEEDLES) {
            if (lower.contains(needle)) {
                counters.matches++;
                System.out.println(
                        "MATCH|needle=" + needle
                                + "|source=" + source
                                + "|path=" + path
                                + "|value=" + oneLine(value)
                );
                return;
            }
        }
    }

    private static void scanBytes(
            byte[] bytes,
            String source,
            String path,
            Counters counters
    ) {
        for (String needle : NEEDLES) {
            byte[] pattern = needle.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int offset = indexOf(bytes, pattern);
            if (offset >= 0) {
                counters.matches++;
                System.out.println(
                        "BYTE_MATCH|needle=" + needle
                                + "|source=" + source
                                + "|path=" + path
                                + "|offset=" + offset
                );
                return;
            }
        }
    }

    private static int indexOf(byte[] bytes, byte[] pattern) {
        if (pattern.length == 0 || pattern.length > bytes.length) {
            return -1;
        }
        outer:
        for (int offset = 0; offset <= bytes.length - pattern.length; offset++) {
            for (int index = 0; index < pattern.length; index++) {
                if (bytes[offset + index] != pattern[index]) {
                    continue outer;
                }
            }
            return offset;
        }
        return -1;
    }

    private static boolean isGzip(byte[] bytes) {
        return bytes.length >= 2
                && (bytes[0] & 0xff) == 0x1f
                && (bytes[1] & 0xff) == 0x8b;
    }

    private static String child(String parent, String key) {
        return parent == null || parent.isEmpty() ? key : parent + "." + key;
    }

    private static String normalize(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static String oneLine(Throwable failure) {
        String message = failure.getMessage();
        return oneLine(message == null ? failure.getClass().getSimpleName() : message);
    }

    private static String oneLine(String value) {
        String normalized = value.replace('\r', ' ').replace('\n', ' ');
        return normalized.length() <= 1_024
                ? normalized
                : normalized.substring(0, 1_024);
    }

    @FunctionalInterface
    private interface StreamDecoder {
        InputStream open(InputStream input) throws IOException;
    }

    private record Decoder(String name, StreamDecoder streamDecoder) {
        InputStream open(InputStream input) throws IOException {
            return streamDecoder.open(input);
        }
    }

    private static final class Counters {
        private long files;
        private long regions;
        private long chunks;
        private long standaloneNbt;
        private long tags;
        private long matches;
        private long exactAttachments;
        private long emptyRegions;
        private long regionGaps;
        private long datUnparsed;
    }
}
