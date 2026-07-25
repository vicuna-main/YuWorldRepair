package dev.yu.worldrepair.guard;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedSignatureTableTest {
    @Test
    void neverExceedsHardCapacity() {
        BoundedSignatureTable table = new BoundedSignatureTable(64, 1_000_000);
        for (int i = 0; i < 10_000; i++) {
            table.insertOrGet(entry(i, i * 31L, i), i);
        }

        assertEquals(64, table.size());
        assertEquals(64, table.snapshot().size());
        assertTrue(table.evictions() > 0);
    }

    @Test
    void evictsLeastRecentlyUsedEntryWithinBucket() {
        BoundedSignatureTable table = new BoundedSignatureTable(8, 10_000);
        for (int i = 0; i < 8; i++) {
            table.insertOrGet(entry(i, i + 100, i), i);
        }
        SignatureEntry touched = table.find(0, 100, 100);
        assertNotNull(touched);
        touched.evaluate(GuardMode.OBSERVE, 100);

        table.insertOrGet(entry(8, 108, 101), 101);

        assertNotNull(table.find(0, 100, 101));
        assertNull(table.find(1, 101, 101));
    }

    @Test
    void expiresAtTtlWithoutSleeping() {
        BoundedSignatureTable table = new BoundedSignatureTable(8, 100);
        table.insertOrGet(entry(1, 2, 0), 0);

        assertNotNull(table.find(1, 2, 99));
        assertNull(table.find(1, 2, 100));
    }

    private static SignatureEntry entry(long primary, long secondary, long now) {
        return new SignatureEntry(
                primary,
                secondary,
                new ErrorSignature(
                        LogicalSide.SERVER,
                        ErrorDomain.ITEM_STACK,
                        "logger",
                        "template",
                        "test:item",
                        "caller",
                        "environment",
                        Long.toHexString(primary),
                        Instant.EPOCH
                ),
                3,
                60,
                now
        );
    }
}
