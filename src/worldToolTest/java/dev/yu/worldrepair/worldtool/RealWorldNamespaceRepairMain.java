package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.maintenance.RegistrySnapshot;
import dev.yu.worldrepair.worldtool.namespace.NamespacePolicy;
import dev.yu.worldrepair.worldtool.namespace.NamespaceRepairService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual all-or-nothing write probe for isolated real-world fixtures.
 */
public final class RealWorldNamespaceRepairMain {
    private RealWorldNamespaceRepairMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2) {
            throw new IllegalArgumentException(
                    "Expected <absolute-jobs-root> <absolute-world>..."
            );
        }
        Path jobs = Path.of(arguments[0]).toAbsolutePath().normalize();
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
        NamespacePolicy policy = new NamespacePolicy(
                "iceandfire",
                NamespacePolicy.Mode.PREPARE_REMOVE,
                snapshot
        );
        String snapshotHash = "f".repeat(64);
        ArrayList<PreparedWorld> prepared = new ArrayList<>();
        int totalTargets = 0;
        for (int index = 1; index < arguments.length; index++) {
            Path world = Path.of(arguments[index]).toAbsolutePath().normalize();
            NamespaceRepairService service =
                    NamespaceRepairService.forServerMaintenance(world, jobs);
            NamespaceRepairService.Result result =
                    service.prepare(policy, snapshotHash);
            int targets = metric(result, "targets");
            System.out.println(
                    "PREPARE|" + world
                            + "|success=" + result.success()
                            + "|targets=" + targets
                            + "|job=" + result.jobPath()
            );
            if (!result.success()) {
                throw new IllegalStateException(
                        "Preparation refused all writes for " + world + ": "
                                + result.detail()
                );
            }
            totalTargets = Math.addExact(totalTargets, targets);
            prepared.add(new PreparedWorld(world, Path.of(result.jobPath()), targets));
        }

        ArrayList<PreparedWorld> applied = new ArrayList<>();
        try {
            for (PreparedWorld preparedWorld : prepared) {
                if (preparedWorld.targets() == 0) {
                    continue;
                }
                NamespaceRepairService service =
                        NamespaceRepairService.forServerMaintenance(
                                preparedWorld.world(),
                                jobs
                        );
                NamespaceRepairService.Result result = service.applyPrepared(
                        preparedWorld.job(),
                        policy,
                        snapshotHash
                );
                applied.add(preparedWorld);
                System.out.println(
                        "APPLY|" + preparedWorld.world()
                                + "|changed=" + metric(result, "changed")
                                + "|rollback=" + result.rollbackAvailable()
                );
            }
        } catch (Exception failure) {
            for (int index = applied.size() - 1; index >= 0; index--) {
                PreparedWorld appliedWorld = applied.get(index);
                NamespaceRepairService.forServerMaintenance(
                        appliedWorld.world(),
                        jobs
                ).rollback(appliedWorld.job());
            }
            throw failure;
        }
        System.out.println(
                "COMPLETE|worlds=" + prepared.size()
                        + "|changed=" + totalTargets
                        + "|jobsRoot=" + jobs
        );
    }

    private static int metric(NamespaceRepairService.Result result, String key) {
        Object value = result.metrics().get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private record PreparedWorld(Path world, Path job, int targets) {
    }
}
