package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorldAccessPolicyTest {
    @TempDir
    Path temporary;

    @Test
    void configuredProtectedRootsRejectTheRootAndEveryDescendant() {
        String property = WorldAccessPolicy.PROTECTED_ROOTS_PROPERTY;
        String previous = System.getProperty(property);
        Path protectedRoot = temporary.resolve("production").toAbsolutePath();
        Path sibling = temporary.resolve("copy").toAbsolutePath();
        try {
            System.setProperty(property, protectedRoot.toString());
            assertThrows(
                    java.io.IOException.class,
                    () -> WorldAccessPolicy.rejectProtectedRoots(protectedRoot)
            );
            assertThrows(
                    java.io.IOException.class,
                    () -> WorldAccessPolicy.rejectProtectedRoots(protectedRoot.resolve("world"))
            );
            assertDoesNotThrow(() -> WorldAccessPolicy.rejectProtectedRoots(sibling));
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void maintenanceHoldsEveryWorldLockUntilTheWholeSetCloses() throws Exception {
        Path first = WorldToolFixture.createWorld(temporary.resolve("first")).toRealPath();
        Path second = WorldToolFixture.createWorld(temporary.resolve("second")).toRealPath();
        Files.write(first.resolve("session.lock"), new byte[8]);
        Files.write(second.resolve("session.lock"), new byte[8]);

        try (WorldAccessPolicy.HeldWorldLocks held =
                     WorldAccessPolicy.acquireExactWorldLocks(List.of(
                             second.toString(),
                             first.toString()
                     ))) {
            assertDoesNotThrow(() ->
                    WorldAccessPolicy.requireExactUnlockedWorld(first, first));
            assertThrows(
                    java.io.IOException.class,
                    () -> WorldAccessPolicy.acquireExactWorldLocks(List.of(first.toString()))
            );
        }

        assertDoesNotThrow(() -> {
            try (WorldAccessPolicy.HeldWorldLocks ignored =
                         WorldAccessPolicy.acquireExactWorldLocks(List.of(first.toString()))) {
                assertFalse(ignored.worldRoots().isEmpty());
            }
        });
    }
}
