package dev.yu.worldrepair.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketTest {
    @Test
    void enforcesBurstAndRefillsAtExactBoundary() {
        TokenBucket bucket = new TokenBucket(3, 60);

        assertTrue(bucket.tryAcquire(0));
        assertTrue(bucket.tryAcquire(0));
        assertTrue(bucket.tryAcquire(0));
        assertFalse(bucket.tryAcquire(0));
        assertFalse(bucket.tryAcquire(19));
        assertTrue(bucket.tryAcquire(20));
    }

    @Test
    void worksWithNegativeNanoTimeOrigins() {
        TokenBucket bucket = new TokenBucket(2, 100);
        long origin = -5_000_000_000L;

        assertTrue(bucket.tryAcquire(origin));
        assertTrue(bucket.tryAcquire(origin));
        assertFalse(bucket.tryAcquire(origin));
        assertTrue(bucket.tryAcquire(origin + 50));
    }
}
