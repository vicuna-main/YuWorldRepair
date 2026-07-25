package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.maintenance.RegistrySnapshot;
import dev.yu.worldrepair.worldtool.namespace.NamespaceChunkAdapter;
import dev.yu.worldrepair.worldtool.namespace.NamespacePolicy;
import dev.yu.worldrepair.worldtool.namespace.NamespaceWorldScanner;
import dev.yu.worldrepair.worldtool.nbt.Nbt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamespacePerformanceTest {
    private static final int REGIONS = 256;

    @TempDir
    Path temporary;

    @Test
    void scansHundredsOfRegionsWithinRegressionBudget() throws Exception {
        Path world = WorldToolFixture.createWorld(temporary.resolve("namespace-perf-world"));
        for (int index = 0; index < REGIONS; index++) {
            WorldToolFixture.writeEntityRegion(
                    world,
                    "entities",
                    index * 32,
                    0,
                    2,
                    List.of(WorldToolFixture.entity(
                            "oldmod:entity_" + index,
                            new UUID(0x1234L, index + 1L),
                            false,
                            false,
                            List.of()
                    ))
            );
        }
        RegistrySnapshot snapshot = new RegistrySnapshot(
                RegistrySnapshot.SCHEMA_VERSION,
                Instant.now().toString(),
                "1.21.1",
                List.of("minecraft:air"),
                List.of("minecraft:air"),
                List.of("minecraft:empty"),
                List.of("minecraft:chicken"),
                List.of("minecraft:furnace"),
                List.of(),
                List.of("minecraft:overworld")
        );
        NamespaceWorldScanner scanner = new NamespaceWorldScanner(
                new NamespaceChunkAdapter(),
                Nbt.Limits.conservative()
        );

        long started = System.nanoTime();
        NamespaceWorldScanner.Result result = scanner.scan(
                world,
                new NamespacePolicy(
                        "oldmod",
                        NamespacePolicy.Mode.ORPHANED_ONLY,
                        snapshot
                )
        );
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        System.out.println(
                "namespace-scan regions=" + REGIONS
                        + " targets=" + result.targets().size()
                        + " elapsedMillis=" + elapsedMillis
                        + " regionsPerSecond="
                        + Math.round(REGIONS * 1_000.0 / Math.max(1, elapsedMillis))
        );
        assertEquals(REGIONS, result.regionsScanned());
        assertEquals(REGIONS, result.chunksScanned());
        assertEquals(REGIONS, result.targets().size());
        assertTrue(
                elapsedMillis < 15_000,
                "Namespace scan exceeded 15s regression budget: " + elapsedMillis + "ms"
        );
    }
}
