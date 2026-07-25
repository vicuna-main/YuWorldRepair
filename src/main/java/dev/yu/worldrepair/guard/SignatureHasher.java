package dev.yu.worldrepair.guard;

public final class SignatureHasher {
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final long PRIMARY_OFFSET = 0xcbf29ce484222325L;
    private static final long SECONDARY_OFFSET = 0x84222325cbf29ce4L;

    private SignatureHasher() {
    }

    public static long primary(int ruleId, LogicalSide side, CharSequence text, long bounds, long environmentHash) {
        long hash = mix(PRIMARY_OFFSET, ruleId);
        hash = mix(hash, side.ordinal());
        hash = mix(hash, environmentHash);
        return hashSlice(hash, text, bounds);
    }

    public static long secondary(int ruleId, LogicalSide side, CharSequence text, long bounds, long environmentHash) {
        long hash = mix(SECONDARY_OFFSET, Integer.rotateLeft(ruleId, 13));
        hash = mix(hash, side.ordinal() + 31L);
        hash = mix(hash, Long.rotateLeft(environmentHash, 29));
        return avalanche(hashSlice(hash, text, bounds));
    }

    public static long hashText(CharSequence text) {
        long hash = PRIMARY_OFFSET;
        for (int i = 0; i < text.length(); i++) {
            hash = mix(hash, text.charAt(i));
        }
        return avalanche(hash);
    }

    public static String shortHex(long primary, long secondary) {
        long combined = avalanche(primary ^ Long.rotateLeft(secondary, 23));
        return String.format("%08x", (int) (combined ^ combined >>> 32));
    }

    private static long hashSlice(long initial, CharSequence text, long bounds) {
        if (text == null || bounds == ResourceIdExtractor.NOT_FOUND) {
            return avalanche(mix(initial, 0x756e6b6e6f776eL));
        }
        int start = ResourceIdExtractor.start(bounds);
        int end = ResourceIdExtractor.end(bounds);
        long hash = initial;
        for (int i = start; i < end; i++) {
            hash = mix(hash, text.charAt(i));
        }
        return avalanche(hash);
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * FNV_PRIME;
    }

    private static long avalanche(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ value >>> 33;
    }
}
