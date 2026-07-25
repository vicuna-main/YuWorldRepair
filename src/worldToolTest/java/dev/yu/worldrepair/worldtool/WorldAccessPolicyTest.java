package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
}
