package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.maintenance.MaintenanceWorldRoots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaintenanceWorldRootsTest {
    @TempDir
    Path temporary;

    @Test
    void capturesMainAndSiblingMultiverseWorldRootsWithoutTreatingDimensionsAsWorlds()
            throws Exception {
        Path server = Files.createDirectory(temporary.resolve("server"));
        Path main = WorldToolFixture.createWorld(server.resolve("world"));
        Path player = WorldToolFixture.createWorld(
                server.resolve("playerworld").resolve("Vicuna")
        );
        Path dimension = Files.createDirectories(main.resolve("DIM-1"));

        List<Path> roots = MaintenanceWorldRoots.capture(
                server,
                main,
                List.of(
                        new FakeLevel(new FakeWorld(player.toFile())),
                        new FakeLevel(new FakeWorld(dimension.toFile())),
                        new FakeLevel(new FakeWorld(main.toFile()))
                )
        );

        assertEquals(List.of(main.toRealPath(), player.toRealPath()), roots);
    }

    @Test
    void refusesLoadedWorldOutsideSignedServerRoot() throws Exception {
        Path server = Files.createDirectory(temporary.resolve("server-outside"));
        Path main = WorldToolFixture.createWorld(server.resolve("world"));
        Path outside = WorldToolFixture.createWorld(temporary.resolve("outside-world"));

        assertThrows(
                IOException.class,
                () -> MaintenanceWorldRoots.normalize(server, main, List.of(outside))
        );
    }

    public static final class FakeLevel {
        private final FakeWorld world;

        public FakeLevel(FakeWorld world) {
            this.world = world;
        }

        public FakeWorld getWorld() {
            return world;
        }
    }

    public static final class FakeWorld {
        private final File folder;

        public FakeWorld(File folder) {
            this.folder = folder;
        }

        public File getWorldFolder() {
            return folder;
        }
    }
}
