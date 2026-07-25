package dev.yu.worldrepair.diagnostics;

import dev.yu.worldrepair.guard.SignatureHasher;

import java.lang.StackWalker.StackFrame;
import java.util.List;
import java.util.Set;

public final class CallSiteFingerprint {
    private static final Set<String> SKIPPED_PREFIXES = Set.of(
            "dev.yu.worldrepair.",
            "org.apache.logging.",
            "org.slf4j.",
            "java.lang.Thread"
    );

    private static final StackWalker WALKER = StackWalker.getInstance();

    private CallSiteFingerprint() {
    }

    public static String capture(int maxFrames) {
        List<StackFrame> frames = WALKER.walk(stream -> stream.limit((long) maxFrames + 32L).toList());
        for (StackFrame frame : frames) {
            if (!isSkipped(frame.getClassName())) {
                String source = frame.getClassName() + "/" + frame.getMethodName();
                long hash = SignatureHasher.hashText(source);
                return source + "#" + String.format("%08x", (int) (hash ^ hash >>> 32));
            }
        }
        return "unavailable";
    }

    private static boolean isSkipped(String className) {
        for (String prefix : SKIPPED_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
