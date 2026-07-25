package dev.yu.worldrepair.metrics;

import dev.yu.worldrepair.guard.GuardDecision;

import java.util.concurrent.atomic.LongAdder;

public final class GuardMetrics {
    private final LongAdder recognized = new LongAdder();
    private final LongAdder passed = new LongAdder();
    private final LongAdder suppressed = new LongAdder();
    private final LongAdder failuresOpen = new LongAdder();

    public void record(GuardDecision decision) {
        if (decision == GuardDecision.PASS_UNRECOGNIZED) {
            return;
        }
        recognized.increment();
        if (decision == GuardDecision.SUPPRESS_DUPLICATE) {
            suppressed.increment();
        } else {
            passed.increment();
        }
    }

    public void recordFailOpen() {
        failuresOpen.increment();
    }

    public long recognized() {
        return recognized.sum();
    }

    public long passed() {
        return passed.sum();
    }

    public long suppressed() {
        return suppressed.sum();
    }

    public long failuresOpen() {
        return failuresOpen.sum();
    }
}
