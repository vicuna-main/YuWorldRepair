package dev.yu.worldrepair.benchmark;

import dev.yu.worldrepair.config.RuntimeConfig;
import dev.yu.worldrepair.guard.GuardMode;
import dev.yu.worldrepair.guard.LogicalSide;
import dev.yu.worldrepair.log.GuardRuntime;
import dev.yu.worldrepair.metrics.GuardMetrics;

public final class GuardBenchmark {
    private static final String LOGGER = "net.minecraft.world.item.ItemStack";
    private static final String TEMPLATE = "Tried to load invalid item: '{}'";
    private static final Object[] PARAMETERS = {
            "Unknown registry key in ResourceKey[minecraft:root / minecraft:item]: removedmod:test_item"
    };

    private GuardBenchmark() {
    }

    public static void main(String[] arguments) {
        RuntimeConfig config = new RuntimeConfig(
                GuardMode.GUARD,
                1_024,
                900_000_000_000L,
                3,
                60_000_000_000L,
                60_000_000_000L,
                0,
                24,
                65_536,
                false,
                false,
                false
        );
        GuardRuntime runtime = new GuardRuntime(
                () -> config,
                new GuardMetrics(),
                "minecraft=1.21.1,neoforge=21.1.241"
        );
        run(runtime, 1_000_000);
        int operations = 5_000_000;
        long start = System.nanoTime();
        run(runtime, operations);
        long elapsed = System.nanoTime() - start;
        double nanosPerOperation = elapsed / (double) operations;
        System.out.printf(
                "guard matched-path: %,d ops, %.1f ns/op, suppressed=%,d, signatures=%d%n",
                operations,
                nanosPerOperation,
                runtime.metrics().suppressed(),
                runtime.signatureCount()
        );
    }

    private static void run(GuardRuntime runtime, int operations) {
        for (int i = 0; i < operations; i++) {
            runtime.evaluate(LOGGER, TEMPLATE, PARAMETERS, LogicalSide.SERVER);
        }
    }
}
