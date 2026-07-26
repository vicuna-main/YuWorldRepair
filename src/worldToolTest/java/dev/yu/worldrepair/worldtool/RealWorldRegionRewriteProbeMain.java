package dev.yu.worldrepair.worldtool;

import dev.yu.worldrepair.worldtool.anvil.RegionFile;
import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.nbt.Nbt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Non-destructive compatibility probe for a real region file.
 *
 * <p>The source is read-only. A rewritten copy is created below the supplied
 * output directory and every unedited chunk is compared semantically.
 */
public final class RealWorldRegionRewriteProbeMain {
    private RealWorldRegionRewriteProbeMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: <source-region> <empty-output-directory>"
            );
        }
        Path source = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path outputDirectory = Path.of(arguments[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IOException("Source region is not a regular file: " + source);
        }
        Files.createDirectories(outputDirectory);
        Path rewritten = outputDirectory.resolve(source.getFileName());
        if (Files.exists(rewritten)) {
            throw new IOException("Probe output already exists: " + rewritten);
        }

        Nbt.Limits limits = Nbt.Limits.conservative();
        LinkedHashMap<Integer, String> originalHashes = new LinkedHashMap<>();
        int[] editedSlot = {-1};
        String sourceHash = IoUtil.sha256(source);
        RegionFile.visitChunks(source, limits, chunk -> {
            originalHashes.put(chunk.index(), Nbt.semanticSha256(chunk.root().tag()));
            if (!chunk.external() && editedSlot[0] < 0) {
                editedSlot[0] = chunk.index();
            }
        });
        if (editedSlot[0] < 0) {
            throw new IOException("No internal chunk is available for the rewrite probe");
        }

        RegionFile.rewrite(
                source,
                rewritten,
                Map.of(editedSlot[0], chunk -> {
                    if (!(chunk.root().tag() instanceof Nbt.CompoundTag root)) {
                        throw new IOException("Probe chunk root is not a compound");
                    }
                    root.put("yuworldrepair:compatibility_probe", new Nbt.ByteTag((byte) 1));
                    return new RegionFile.EditResult(
                            true,
                            1,
                            Nbt.semanticSha256(root)
                    );
                }),
                limits
        );

        LinkedHashMap<Integer, String> rewrittenHashes = new LinkedHashMap<>();
        RegionFile.visitChunks(
                rewritten,
                limits,
                chunk -> rewrittenHashes.put(
                        chunk.index(),
                        Nbt.semanticSha256(chunk.root().tag())
                )
        );
        if (!sourceHash.equals(IoUtil.sha256(source))) {
            throw new IOException("Probe modified the source region");
        }
        if (!originalHashes.keySet().equals(rewrittenHashes.keySet())) {
            throw new IOException("Rewritten region has a different chunk-slot set");
        }
        for (Map.Entry<Integer, String> entry : originalHashes.entrySet()) {
            if (entry.getKey() == editedSlot[0]) {
                continue;
            }
            if (!entry.getValue().equals(rewrittenHashes.get(entry.getKey()))) {
                throw new IOException(
                        "Unedited chunk changed semantically at slot " + entry.getKey()
                );
            }
        }

        System.out.println("source=" + source);
        System.out.println("sourceBytes=" + Files.size(source));
        System.out.println("sourceSha256=" + sourceHash);
        System.out.println("chunks=" + originalHashes.size());
        System.out.println("editedProbeSlot=" + editedSlot[0]);
        System.out.println("rewritten=" + rewritten);
        System.out.println("rewrittenBytes=" + Files.size(rewritten));
        System.out.println("uneditedChunksVerified=" + (originalHashes.size() - 1));
        System.out.println("result=PASS");
    }
}
