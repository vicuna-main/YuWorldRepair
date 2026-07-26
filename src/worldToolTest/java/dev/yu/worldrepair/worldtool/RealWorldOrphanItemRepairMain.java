package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;
import dev.yu.worldrepair.worldtool.namespace.NamespaceChunkAdapter;
import dev.yu.worldrepair.worldtool.namespace.NamespacePolicy;
import dev.yu.worldrepair.worldtool.namespace.NamespaceRepairService;
import dev.yu.worldrepair.worldtool.namespace.NamespaceWorldScanner;
import dev.yu.worldrepair.worldtool.namespace.OrphanItemIndex;
import dev.yu.worldrepair.worldtool.nbt.Nbt;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manual apply/verify/byte-rollback regression for isolated real-world copies.
 */
public final class RealWorldOrphanItemRepairMain {
    private RealWorldOrphanItemRepairMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 3) {
            throw new IllegalArgumentException(
                    "Expected <absolute-jobs-root> <removed-namespace,...> <absolute-world>..."
            );
        }
        Path jobs = Path.of(arguments[0]).toAbsolutePath().normalize();
        Set<String> removed = new HashSet<>(Arrays.asList(arguments[1].split(",")));
        String[] worlds = Arrays.copyOfRange(arguments, 2, arguments.length);
        List<String> presentItems = RealWorldItemAuditMain.discoverItemIds(worlds).stream()
                .filter(id -> !removed.contains(id.substring(0, id.indexOf(':'))))
                .toList();
        List<String> presentAttachments =
                RealWorldRegistryProbe.discoverAttachmentIds(worlds, presentItems).stream()
                        .filter(id -> !id.equals("iceandfire:chicken_data"))
                        .filter(id -> !removed.contains(id.substring(0, id.indexOf(':'))))
                        .toList();
        NamespacePolicy policy =
                RealWorldRegistryProbe.policy(presentItems, presentAttachments);
        OrphanItemIndex itemIndex = OrphanItemIndex.load(
                Path.of(worlds[0]).toAbsolutePath().normalize(),
                policy,
                Nbt.Limits.conservative()
        );
        String snapshotHash = "3".repeat(64);
        ArrayList<Prepared> prepared = new ArrayList<>();
        ArrayList<Prepared> applied = new ArrayList<>();
        int targets = 0;
        NamespaceWorldScanner.Options options =
                NamespaceWorldScanner.Options.full(4, true)
                        .withTrustedWorldLock(true);
        try (WorldAccessPolicy.HeldWorldLocks ignored =
                     WorldAccessPolicy.acquireExactWorldLocks(List.of(worlds))) {
            for (String supplied : worlds) {
                Path world = Path.of(supplied).toAbsolutePath().normalize();
                NamespaceRepairService service =
                        NamespaceRepairService.forServerMaintenance(world, jobs);
                NamespaceRepairService.Result result =
                        service.prepare(policy, snapshotHash, itemIndex, options);
                if (!result.success()) {
                    throw new IllegalStateException(
                            "Preparation failed for " + world + ": " + result.detail()
                    );
                }
                int worldTargets = number(result.metrics().get("targets"));
                targets = Math.addExact(targets, worldTargets);
                prepared.add(new Prepared(
                        world,
                        Path.of(result.jobPath()),
                        worldTargets
                ));
            }
            for (Prepared entry : prepared) {
                if (entry.targets() == 0) {
                    continue;
                }
                NamespaceRepairService service =
                        NamespaceRepairService.forServerMaintenance(entry.world(), jobs);
                NamespaceRepairService.Result result = service.applyPrepared(
                        entry.job(),
                        policy,
                        snapshotHash,
                        itemIndex,
                        options
                );
                if (!result.success()
                        || number(result.metrics().get("changed")) != entry.targets()) {
                    throw new IllegalStateException("Apply count mismatch for " + entry.world());
                }
                applied.add(entry);
            }
            NamespaceWorldScanner scanner = new NamespaceWorldScanner(
                    new NamespaceChunkAdapter(),
                    Nbt.Limits.conservative()
            );
            for (Prepared entry : prepared) {
                NamespaceWorldScanner.Result verification = scanner.scan(
                        entry.world(),
                        policy,
                        itemIndex,
                        NamespaceWorldScanner.Options.full(4, true)
                );
                if (!verification.coverageGaps().isEmpty()
                        || !verification.targets().isEmpty()) {
                    throw new IllegalStateException(
                            "Post-apply verification failed for " + entry.world()
                    );
                }
            }
            System.out.println(
                    "APPLY_VERIFIED|worlds=" + prepared.size() + "|targets=" + targets
            );
        } finally {
            for (int index = applied.size() - 1; index >= 0; index--) {
                Prepared entry = applied.get(index);
                NamespaceRepairService.forServerMaintenance(
                        entry.world(),
                        jobs
                ).rollback(entry.job());
            }
        }
        System.out.println(
                "ROLLBACK_VERIFIED|worlds=" + applied.size() + "|targets=" + targets
        );
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private record Prepared(Path world, Path job, int targets) {
    }
}
