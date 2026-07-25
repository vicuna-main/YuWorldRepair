package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.maintenance.RegistrySnapshot;
import dev.yu.worldrepair.worldtool.namespace.NamespaceChunkAdapter;
import dev.yu.worldrepair.worldtool.namespace.NamespacePolicy;
import dev.yu.worldrepair.worldtool.namespace.NamespaceWorldScanner;
import dev.yu.worldrepair.worldtool.nbt.Nbt;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Manual read-only structural coverage probe for an isolated world copy.
 */
public final class RealWorldNamespaceAuditMain {
    private RealWorldNamespaceAuditMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected one absolute world-copy path");
        }
        RegistrySnapshot snapshot = new RegistrySnapshot(
                RegistrySnapshot.SCHEMA_VERSION,
                Instant.now().toString(),
                "1.21.1",
                List.of("minecraft:air"),
                List.of("minecraft:air"),
                List.of("minecraft:empty"),
                List.of("minecraft:pig"),
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
                Path.of(arguments[0]).toAbsolutePath().normalize(),
                new NamespacePolicy(
                        "yuworldrepair_probe",
                        NamespacePolicy.Mode.ORPHANED_ONLY,
                        snapshot
                )
        );
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        System.out.println("regions=" + result.regionsScanned());
        System.out.println("chunks=" + result.chunksScanned());
        System.out.println("targets=" + result.targets().size());
        System.out.println("coverageGaps=" + result.coverageGaps().size());
        System.out.println("warnings=" + result.warnings().size());
        System.out.println("elapsedMillis=" + elapsedMillis);
        result.coverageGaps().stream().limit(50)
                .forEach(gap -> System.out.println(
                        "gap=" + gap.relativePath() + "|" + gap.reason()
                ));
    }
}
