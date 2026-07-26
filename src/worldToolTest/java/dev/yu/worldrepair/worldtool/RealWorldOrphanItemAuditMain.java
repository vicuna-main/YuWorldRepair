package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.namespace.NamespaceChunkAdapter;
import dev.yu.worldrepair.worldtool.namespace.NamespacePolicy;
import dev.yu.worldrepair.worldtool.namespace.NamespaceTarget;
import dev.yu.worldrepair.worldtool.namespace.NamespaceWorldScanner;
import dev.yu.worldrepair.worldtool.namespace.OrphanItemIndex;
import dev.yu.worldrepair.worldtool.nbt.Nbt;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manual read-only regression probe. The first argument is a comma-separated set of namespaces
 * to simulate as absent; every other ItemStack ID observed in the fixture is treated as present.
 */
public final class RealWorldOrphanItemAuditMain {
    private RealWorldOrphanItemAuditMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2) {
            throw new IllegalArgumentException(
                    "Expected <removed-namespace,...> <absolute-world>..."
            );
        }
        Set<String> removed = new HashSet<>(Arrays.asList(arguments[0].split(",")));
        String[] worlds = Arrays.copyOfRange(arguments, 1, arguments.length);
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
        NamespaceWorldScanner scanner = new NamespaceWorldScanner(
                new NamespaceChunkAdapter(),
                Nbt.Limits.conservative()
        );
        OrphanItemIndex itemIndex = OrphanItemIndex.load(
                Path.of(worlds[0]).toAbsolutePath().normalize(),
                policy,
                Nbt.Limits.conservative()
        );
        int total = 0;
        for (String supplied : worlds) {
            Path world = Path.of(supplied).toAbsolutePath().normalize();
            NamespaceWorldScanner.Result result =
                    scanner.scan(
                            world,
                            policy,
                            itemIndex,
                            NamespaceWorldScanner.Options.full(4, true)
                    );
            total = Math.addExact(total, result.targets().size());
            System.out.println(
                    "WORLD|" + world
                            + "|targets=" + result.targets().size()
                            + "|gaps=" + result.coverageGaps().size()
                            + "|workers=" + result.scanWorkers()
                            + "|regionBytes=" + result.regionBytesScanned()
            );
            for (NamespaceTarget target : result.targets()) {
                System.out.println(
                        "TARGET|" + target.resourceId()
                                + "|store=" + target.store()
                                + "|action=" + target.action()
                                + "|amount=" + target.amount()
                                + "|source=" + target.regionRelativePath()
                                + "|path=" + target.nbtPath()
                );
            }
        }
        System.out.println("COMPLETE|targets=" + total);
    }
}
