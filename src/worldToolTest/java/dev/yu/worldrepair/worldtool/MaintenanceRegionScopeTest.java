package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.maintenance.MaintenanceRegionScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaintenanceRegionScopeTest {
    @TempDir
    Path temporary;

    @Test
    void exceptAndOnlyResolveServerRelativeOrUniqueLeafWorldNames() throws Exception {
        Path server = Files.createDirectory(temporary.resolve("server")).toRealPath();
        Path main = WorldToolFixture.createWorld(server.resolve("world")).toRealPath();
        Path nested = WorldToolFixture.createWorld(
                server.resolve("playerworld").resolve("Vicuna")
        ).toRealPath();
        List<Path> worlds = List.of(main, nested);

        MaintenanceRegionScope.Selection except = MaintenanceRegionScope.resolve(
                server,
                worlds,
                MaintenanceRegionScope.Mode.EXCEPT,
                "world"
        );
        assertEquals(List.of(main), except.excludedRegionRoots());
        assertEquals(List.of("world"), except.excludedLabels());

        MaintenanceRegionScope.Selection only = MaintenanceRegionScope.resolve(
                server,
                worlds,
                MaintenanceRegionScope.Mode.ONLY,
                "playerworld/Vicuna"
        );
        assertEquals(List.of(main), only.excludedRegionRoots());
    }

    @Test
    void ambiguousLeafRequiresTheServerRelativeWorldLabel() throws Exception {
        Path server = Files.createDirectory(temporary.resolve("ambiguous-server")).toRealPath();
        Path first = WorldToolFixture.createWorld(
                server.resolve("one").resolve("arena")
        ).toRealPath();
        Path second = WorldToolFixture.createWorld(
                server.resolve("two").resolve("arena")
        ).toRealPath();

        assertThrows(
                IOException.class,
                () -> MaintenanceRegionScope.resolve(
                        server,
                        List.of(first, second),
                        MaintenanceRegionScope.Mode.EXCEPT,
                        "arena"
                )
        );
        assertEquals(
                List.of(first),
                MaintenanceRegionScope.resolve(
                        server,
                        List.of(first, second),
                        MaintenanceRegionScope.Mode.EXCEPT,
                        "one/arena"
                ).excludedRegionRoots()
        );
    }
}
