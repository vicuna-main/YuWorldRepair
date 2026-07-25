package dev.yu.worldrepair.log;

import dev.yu.worldrepair.config.RuntimeConfig;
import dev.yu.worldrepair.guard.GuardDecision;
import dev.yu.worldrepair.guard.GuardMode;
import dev.yu.worldrepair.guard.LogicalSide;
import dev.yu.worldrepair.metrics.GuardMetrics;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardRuntimeTest {
    private static final String TEMPLATE = "Tried to load invalid item: '{}'";
    private static final String LOGGER = "net.minecraft.world.item.ItemStack";
    private static final String ERROR =
            "Unknown registry key in ResourceKey[minecraft:root / minecraft:item]: removedmod:test_item";

    @Test
    void millionEventStormSuppressesMoreThanNinetyNinePointNinePercent() {
        AtomicLong nanoTime = new AtomicLong();
        GuardRuntime runtime = runtime(GuardMode.GUARD, nanoTime);

        int suppressed = 0;
        for (int i = 0; i < 1_000_000; i++) {
            if (runtime.evaluate(
                    LOGGER,
                    TEMPLATE,
                    new Object[]{ERROR},
                    LogicalSide.SERVER
            ) == GuardDecision.SUPPRESS_DUPLICATE) {
                suppressed++;
            }
        }

        assertEquals(999_997, suppressed);
        assertEquals(1, runtime.signatureCount());
        assertEquals("removedmod:test_item", runtime.signatures().getFirst().signature().registryId());
        assertTrue(suppressed / 1_000_000.0 >= 0.999);
    }

    @Test
    void observeNeverSuppressesAndDifferentIdsNeverMerge() {
        AtomicLong nanoTime = new AtomicLong();
        GuardRuntime runtime = runtime(GuardMode.OBSERVE, nanoTime);

        for (int i = 0; i < 100; i++) {
            assertEquals(
                    GuardDecision.PASS_OBSERVE,
                    runtime.evaluate(LOGGER, TEMPLATE, new Object[]{ERROR}, LogicalSide.SERVER)
            );
        }
        runtime.evaluate(
                LOGGER,
                TEMPLATE,
                new Object[]{ERROR.replace("test_item", "other_item")},
                LogicalSide.SERVER
        );

        assertEquals(2, runtime.signatureCount());
        assertEquals(0, runtime.metrics().suppressed());
    }

    @Test
    void concurrentCountingIsExactAndBounded() {
        AtomicLong nanoTime = new AtomicLong();
        GuardRuntime runtime = runtime(GuardMode.GUARD, nanoTime);

        IntStream.range(0, 200_000).parallel().forEach(ignored -> runtime.evaluate(
                LOGGER,
                TEMPLATE,
                new Object[]{ERROR},
                LogicalSide.SERVER
        ));

        assertEquals(200_000, runtime.metrics().recognized());
        assertEquals(1, runtime.signatureCount());
        assertEquals(200_000, runtime.signatures().getFirst().observed());
    }

    @Test
    void unrecognizedErrorsPassWithoutEnteringTable() {
        GuardRuntime runtime = runtime(GuardMode.GUARD, new AtomicLong());

        assertEquals(
                GuardDecision.PASS_UNRECOGNIZED,
                runtime.evaluate("other.Logger", "Something broke: {}", new Object[]{"x"}, LogicalSide.SERVER)
        );
        assertEquals(0, runtime.signatureCount());
    }

    private static GuardRuntime runtime(GuardMode mode, AtomicLong nanoTime) {
        AtomicReference<RuntimeConfig> config = new AtomicReference<>(new RuntimeConfig(
                mode,
                64,
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
        ));
        return new GuardRuntime(
                config::get,
                new GuardMetrics(),
                "minecraft=1.21.1,neoforge=21.1.241",
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                nanoTime::get
        );
    }
}
