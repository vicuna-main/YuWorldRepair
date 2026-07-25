package dev.yu.worldrepair.guard;

/**
 * Finds a bounded resource-location slice without regexes or temporary strings.
 */
public final class ResourceIdExtractor {
    public static final int MAX_SCAN_CHARS = 8_192;
    public static final int MAX_ID_CHARS = 256;
    public static final long NOT_FOUND = -1L;

    private static final String REGISTRY_MARKER = "]: ";

    private ResourceIdExtractor() {
    }

    public static long findBounds(CharSequence text) {
        if (text == null || text.isEmpty()) {
            return NOT_FOUND;
        }
        int limit = Math.min(text.length(), MAX_SCAN_CHARS);
        int marker = indexOf(text, REGISTRY_MARKER, 0, limit);
        if (marker >= 0) {
            long marked = findFirst(text, marker + REGISTRY_MARKER.length(), limit);
            if (marked != NOT_FOUND) {
                return marked;
            }
        }
        return findFirst(text, 0, limit);
    }

    public static String materialize(CharSequence text, long bounds) {
        if (text == null || bounds == NOT_FOUND) {
            return "unknown";
        }
        int start = start(bounds);
        int end = end(bounds);
        if (start < 0 || end <= start || end > text.length() || end - start > MAX_ID_CHARS) {
            return "unknown";
        }
        return text.subSequence(start, end).toString();
    }

    public static int start(long bounds) {
        return (int) (bounds >>> 32);
    }

    public static int end(long bounds) {
        return (int) bounds;
    }

    private static long findFirst(CharSequence text, int from, int limit) {
        for (int colon = from + 1; colon < limit - 1; colon++) {
            if (text.charAt(colon) != ':') {
                continue;
            }
            int start = colon - 1;
            while (start >= from && isNamespaceCharacter(text.charAt(start))) {
                start--;
            }
            start++;
            if (start == colon || colon - start > 64) {
                continue;
            }
            int end = colon + 1;
            while (end < limit && end - start <= MAX_ID_CHARS && isPathCharacter(text.charAt(end))) {
                end++;
            }
            if (end == colon + 1 || end - start > MAX_ID_CHARS) {
                continue;
            }
            return ((long) start << 32) | (end & 0xffff_ffffL);
        }
        return NOT_FOUND;
    }

    private static int indexOf(CharSequence text, String needle, int from, int limit) {
        int last = limit - needle.length();
        outer:
        for (int i = from; i <= last; i++) {
            for (int j = 0; j < needle.length(); j++) {
                if (text.charAt(i + j) != needle.charAt(j)) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static boolean isNamespaceCharacter(char character) {
        return character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9'
                || character == '_'
                || character == '-'
                || character == '.';
    }

    private static boolean isPathCharacter(char character) {
        return isNamespaceCharacter(character) || character == '/';
    }
}
