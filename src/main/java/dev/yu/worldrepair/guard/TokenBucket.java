package dev.yu.worldrepair.guard;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Allocation-free Generic Cell Rate Algorithm representation of a token bucket.
 */
public final class TokenBucket {
    private final long intervalNanos;
    private final long burstToleranceNanos;
    private final AtomicLong theoreticalArrivalNanos = new AtomicLong(Long.MIN_VALUE);

    public TokenBucket(int burst, long refillWindowNanos) {
        if (burst < 1 || refillWindowNanos < 1) {
            throw new IllegalArgumentException("burst and refill window must be positive");
        }
        this.intervalNanos = Math.max(1L, Math.ceilDiv(refillWindowNanos, burst));
        this.burstToleranceNanos = saturatedMultiply(intervalNanos, burst - 1L);
    }

    public boolean tryAcquire(long nowNanos) {
        while (true) {
            long previous = theoreticalArrivalNanos.get();
            if (previous == Long.MIN_VALUE) {
                long first = saturatedAdd(nowNanos, intervalNanos);
                if (theoreticalArrivalNanos.compareAndSet(previous, first)) {
                    return true;
                }
                continue;
            }
            long earliest = saturatedSubtract(previous, burstToleranceNanos);
            if (nowNanos < earliest) {
                return false;
            }
            long baseline = Math.max(previous, nowNanos);
            long next = saturatedAdd(baseline, intervalNanos);
            if (theoreticalArrivalNanos.compareAndSet(previous, next)) {
                return true;
            }
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long saturatedSubtract(long left, long right) {
        if (right > 0 && left < Long.MIN_VALUE + right) {
            return Long.MIN_VALUE;
        }
        return left - right;
    }

    private static long saturatedMultiply(long left, long right) {
        if (left == 0L || right == 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }
}
