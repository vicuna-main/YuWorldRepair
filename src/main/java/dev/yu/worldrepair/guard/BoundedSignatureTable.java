package dev.yu.worldrepair.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Fixed-capacity, 8-way set-associative LRU table.
 *
 * <p>Existing-signature lookups are lock-free and allocate nothing. A lock is taken only for a
 * previously unseen signature. Eviction is LRU within the selected hash bucket, which keeps miss
 * work bounded even under adversarial multi-signature input.</p>
 */
public final class BoundedSignatureTable {
    private static final int WAYS = 8;

    private final int capacity;
    private final int bucketCount;
    private final long ttlNanos;
    private final AtomicReferenceArray<SignatureEntry> entries;
    private final Object[] bucketLocks;
    private final AtomicInteger size = new AtomicInteger();
    private final LongAdder evictions = new LongAdder();

    public BoundedSignatureTable(int capacity, long ttlNanos) {
        if (capacity < WAYS || ttlNanos < 1) {
            throw new IllegalArgumentException("capacity must be >= 8 and ttl must be positive");
        }
        this.capacity = capacity;
        this.bucketCount = Math.ceilDiv(capacity, WAYS);
        this.ttlNanos = ttlNanos;
        this.entries = new AtomicReferenceArray<>(capacity);
        this.bucketLocks = new Object[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            bucketLocks[i] = new Object();
        }
    }

    public SignatureEntry find(long primaryHash, long secondaryHash, long nowNanos) {
        int bucket = bucket(primaryHash);
        int start = start(bucket);
        int end = end(start);
        for (int i = start; i < end; i++) {
            SignatureEntry entry = entries.get(i);
            if (entry != null && entry.matches(primaryHash, secondaryHash)) {
                if (isExpired(entry, nowNanos)) {
                    return null;
                }
                return entry;
            }
        }
        return null;
    }

    public SignatureEntry insertOrGet(SignatureEntry candidate, long nowNanos) {
        long primary = candidate.primaryHashForTable();
        long secondary = candidate.secondaryHashForTable();
        int bucket = bucket(primary);
        synchronized (bucketLocks[bucket]) {
            int start = start(bucket);
            int end = end(start);
            int emptyIndex = -1;
            int expiredIndex = -1;
            int oldestIndex = -1;
            long oldestSeen = Long.MAX_VALUE;

            for (int i = start; i < end; i++) {
                SignatureEntry current = entries.get(i);
                if (current == null) {
                    if (emptyIndex < 0) {
                        emptyIndex = i;
                    }
                    continue;
                }
                if (current.matches(primary, secondary) && !isExpired(current, nowNanos)) {
                    return current;
                }
                if (isExpired(current, nowNanos)) {
                    if (expiredIndex < 0) {
                        expiredIndex = i;
                    }
                    continue;
                }
                long seen = current.lastSeenNanos();
                if (seen < oldestSeen) {
                    oldestSeen = seen;
                    oldestIndex = i;
                }
            }

            int replacementIndex = emptyIndex >= 0 ? emptyIndex : expiredIndex >= 0 ? expiredIndex : oldestIndex;
            SignatureEntry replaced = entries.getAndSet(replacementIndex, candidate);
            if (replaced == null) {
                size.incrementAndGet();
            } else {
                evictions.increment();
            }
            return candidate;
        }
    }

    public List<SignatureEntry> snapshot() {
        List<SignatureEntry> snapshot = new ArrayList<>(size.get());
        for (int i = 0; i < capacity; i++) {
            SignatureEntry entry = entries.get(i);
            if (entry != null) {
                snapshot.add(entry);
            }
        }
        return List.copyOf(snapshot);
    }

    public int size() {
        return size.get();
    }

    public int capacity() {
        return capacity;
    }

    public long evictions() {
        return evictions.sum();
    }

    private int bucket(long hash) {
        return (int) Long.remainderUnsigned(hash, bucketCount);
    }

    private int start(int bucket) {
        return bucket * WAYS;
    }

    private int end(int start) {
        return Math.min(start + WAYS, capacity);
    }

    private boolean isExpired(SignatureEntry entry, long nowNanos) {
        long elapsed = nowNanos - entry.lastSeenNanos();
        return elapsed >= ttlNanos && elapsed >= 0;
    }
}
