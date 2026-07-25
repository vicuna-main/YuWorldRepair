package dev.yu.worldrepair.guard;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class SignatureEntry {
    private final long primaryHash;
    private final long secondaryHash;
    private final ErrorSignature signature;
    private final TokenBucket tokenBucket;
    private final LongAdder observed = new LongAdder();
    private final LongAdder allowed = new LongAdder();
    private final LongAdder suppressed = new LongAdder();
    private final AtomicLong suppressedSinceSummary = new AtomicLong();
    private final AtomicLong lastSeenNanos;

    public SignatureEntry(
            long primaryHash,
            long secondaryHash,
            ErrorSignature signature,
            int burst,
            long windowNanos,
            long createdNanos
    ) {
        this.primaryHash = primaryHash;
        this.secondaryHash = secondaryHash;
        this.signature = signature;
        this.tokenBucket = new TokenBucket(burst, windowNanos);
        this.lastSeenNanos = new AtomicLong(createdNanos);
    }

    public GuardDecision evaluate(GuardMode mode, long nowNanos) {
        observed.increment();
        lastSeenNanos.lazySet(nowNanos);
        if (mode == GuardMode.OBSERVE) {
            allowed.increment();
            return GuardDecision.PASS_OBSERVE;
        }
        if (tokenBucket.tryAcquire(nowNanos)) {
            allowed.increment();
            return GuardDecision.PASS_ALLOWED;
        }
        suppressed.increment();
        suppressedSinceSummary.incrementAndGet();
        return GuardDecision.SUPPRESS_DUPLICATE;
    }

    public boolean matches(long primary, long secondary) {
        return primaryHash == primary && secondaryHash == secondary;
    }

    long primaryHashForTable() {
        return primaryHash;
    }

    long secondaryHashForTable() {
        return secondaryHash;
    }

    public ErrorSignature signature() {
        return signature;
    }

    public long observed() {
        return observed.sum();
    }

    public long allowed() {
        return allowed.sum();
    }

    public long suppressed() {
        return suppressed.sum();
    }

    public long drainSuppressedSinceSummary() {
        return suppressedSinceSummary.getAndSet(0L);
    }

    public long lastSeenNanos() {
        return lastSeenNanos.get();
    }
}
