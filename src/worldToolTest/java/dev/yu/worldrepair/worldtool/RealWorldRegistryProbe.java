package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.maintenance.RegistrySnapshot;
import dev.yu.worldrepair.worldtool.namespace.NamespaceChunkAdapter;
import dev.yu.worldrepair.worldtool.namespace.NamespacePolicy;
import dev.yu.worldrepair.worldtool.namespace.NamespaceTarget;
import dev.yu.worldrepair.worldtool.namespace.NamespaceWorldScanner;
import dev.yu.worldrepair.worldtool.namespace.OrphanItemIndex;
import dev.yu.worldrepair.worldtool.nbt.Nbt;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.TreeSet;

/**
 * Read-only registry simulator for isolated real-world fixtures.
 *
 * <p>Production never uses this class: the maintenance mod captures the live registries. A fixture
 * has no running NeoForge registry, so attachment IDs are discovered to a fixed point. Each round
 * treats previously observed outer attachments as present, allowing the normal recursive scanner
 * to reach attachment containers nested inside their payloads.</p>
 */
final class RealWorldRegistryProbe {
    private static final int MAX_ATTACHMENT_ROUNDS = 128;

    private RealWorldRegistryProbe() {
    }

    static List<String> discoverAttachmentIds(
            String[] worlds,
            List<String> presentItems
    ) throws Exception {
        TreeSet<String> observed = new TreeSet<>();
        NamespaceWorldScanner scanner = new NamespaceWorldScanner(
                new NamespaceChunkAdapter(),
                Nbt.Limits.conservative()
        );
        for (int round = 1; round <= MAX_ATTACHMENT_ROUNDS; round++) {
            NamespacePolicy policy = policy(presentItems, List.copyOf(observed));
            OrphanItemIndex itemIndex = OrphanItemIndex.load(
                    Path.of(worlds[0]).toAbsolutePath().normalize(),
                    policy,
                    Nbt.Limits.conservative()
            );
            TreeSet<String> discoveredThisRound = new TreeSet<>();
            for (String supplied : worlds) {
                NamespaceWorldScanner.Result result = scanner.scan(
                        Path.of(supplied).toAbsolutePath().normalize(),
                        policy,
                        itemIndex,
                        NamespaceWorldScanner.Options.full(4, true)
                );
                if (!result.coverageGaps().isEmpty()) {
                    throw new IllegalStateException(
                            "Attachment discovery has coverage gaps in " + supplied
                    );
                }
                result.targets().stream()
                        .filter(target -> target.action()
                                == NamespaceTarget.Action.REMOVE_ATTACHMENT)
                        .map(NamespaceTarget::resourceId)
                        .forEach(discoveredThisRound::add);
            }
            discoveredThisRound.removeAll(observed);
            if (discoveredThisRound.isEmpty()) {
                return List.copyOf(observed);
            }
            observed.addAll(discoveredThisRound);
            System.out.println(
                    "ATTACHMENT_DISCOVERY|round=" + round
                            + "|new=" + discoveredThisRound.size()
                            + "|total=" + observed.size()
                            + "|ids=" + String.join(",", discoveredThisRound)
            );
        }
        throw new IllegalStateException("Attachment discovery did not converge");
    }

    static NamespacePolicy policy(
            List<String> presentItems,
            List<String> presentAttachments
    ) {
        RegistrySnapshot snapshot = new RegistrySnapshot(
                RegistrySnapshot.SCHEMA_VERSION,
                Instant.now().toString(),
                "1.21.1",
                presentItems,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                presentAttachments,
                List.of()
        );
        return new NamespacePolicy(
                NamespacePolicy.ALL_ORPHANED_ITEMS,
                NamespacePolicy.Mode.ORPHANED_ITEMS,
                snapshot
        );
    }
}
