package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.adapter.LegacyChickenDataAdapter;
import dev.yu.worldrepair.worldtool.maintenance.RegistrySnapshot;
import dev.yu.worldrepair.worldtool.namespace.NamespaceChunkAdapter;
import dev.yu.worldrepair.worldtool.namespace.NamespacePolicy;
import dev.yu.worldrepair.worldtool.namespace.NamespaceWorldScanner;
import dev.yu.worldrepair.worldtool.nbt.Nbt;
import dev.yu.worldrepair.worldtool.scan.WorldScanner;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Manual read-only Ice and Fire coverage probe for isolated real-world fixtures.
 */
public final class RealWorldIceAndFireAuditMain {
    private RealWorldIceAndFireAuditMain() {
    }

    public static void main(String[] arguments) {
        if (arguments.length == 0) {
            throw new IllegalArgumentException("Expected one or more absolute world paths");
        }
        WorldScanner scanner = new WorldScanner(
                new LegacyChickenDataAdapter(),
                Nbt.Limits.conservative()
        );
        NamespaceWorldScanner namespaceScanner = new NamespaceWorldScanner(
                new NamespaceChunkAdapter(),
                Nbt.Limits.conservative()
        );
        RegistrySnapshot snapshot = new RegistrySnapshot(
                RegistrySnapshot.SCHEMA_VERSION,
                Instant.now().toString(),
                "1.21.1",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        NamespacePolicy namespacePolicy = new NamespacePolicy(
                "iceandfire",
                NamespacePolicy.Mode.PREPARE_REMOVE,
                snapshot
        );
        for (String argument : arguments) {
            Path world = Path.of(argument).toAbsolutePath().normalize();
            long started = System.nanoTime();
            try {
                WorldScanner.Result result = scanner.scan(world, true);
                System.out.println(
                        "OK|" + world
                                + "|regions=" + result.regionsScanned()
                                + "|chunks=" + result.chunksScanned()
                                + "|targets=" + result.targets().size()
                                + "|blocked=" + result.blockedTargets()
                                + "|elapsedMillis=" + elapsedMillis(started)
                );
                result.targets().stream().limit(20).forEach(target -> System.out.println(
                        "TARGET|" + world
                                + "|" + target.regionRelativePath()
                                + "|" + target.chunkX() + "," + target.chunkZ()
                                + "|" + target.entityType()
                                + "|" + target.refusalReason()
                ));
            } catch (Exception failure) {
                System.out.println(
                        "FAIL|" + world
                                + "|" + failure.getClass().getName()
                                + "|" + failure.getMessage()
                                + "|elapsedMillis=" + elapsedMillis(started)
                );
            }
            started = System.nanoTime();
            try {
                NamespaceWorldScanner.Result result =
                        namespaceScanner.scan(world, namespacePolicy);
                System.out.println(
                        "NAMESPACE_OK|" + world
                                + "|regions=" + result.regionsScanned()
                                + "|chunks=" + result.chunksScanned()
                                + "|targets=" + result.targets().size()
                                + "|coverageGaps=" + result.coverageGaps().size()
                                + "|elapsedMillis=" + elapsedMillis(started)
                );
                result.targets().stream().limit(5).forEach(target -> System.out.println(
                        "NAMESPACE_TARGET|" + world
                                + "|" + target.regionRelativePath()
                                + "|" + target.action()
                                + "|" + target.resourceId()
                                + "|" + target.nbtPath()
                ));
                result.coverageGaps().stream().limit(20).forEach(gap -> System.out.println(
                        "NAMESPACE_GAP|" + world
                                + "|" + gap.relativePath()
                                + "|" + gap.reason()
                ));
            } catch (Exception failure) {
                System.out.println(
                        "NAMESPACE_FAIL|" + world
                                + "|" + failure.getClass().getName()
                                + "|" + failure.getMessage()
                                + "|elapsedMillis=" + elapsedMillis(started)
                );
            }
        }
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
