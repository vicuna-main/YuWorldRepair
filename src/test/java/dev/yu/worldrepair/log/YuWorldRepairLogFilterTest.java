package dev.yu.worldrepair.log;

import dev.yu.worldrepair.config.RuntimeConfig;
import dev.yu.worldrepair.guard.GuardMode;
import dev.yu.worldrepair.guard.LogicalSide;
import dev.yu.worldrepair.metrics.GuardMetrics;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YuWorldRepairLogFilterTest {
    @Test
    void suppressesOnlyExactErrorFamily() {
        AtomicReference<RuntimeConfig> config = new AtomicReference<>(guardConfig());
        GuardRuntime runtime = new GuardRuntime(
                config::get,
                new GuardMetrics(),
                "test"
        );
        YuWorldRepairLogFilter filter = new YuWorldRepairLogFilter(runtime, LogicalSide.SERVER);

        LogEvent target = event(
                "net.minecraft.world.item.ItemStack",
                "Tried to load invalid item: '{}'",
                "Unknown registry key in ResourceKey[minecraft:root / minecraft:item]: removed:item"
        );
        assertEquals(AbstractFilter.Result.NEUTRAL, filter.filter(target));
        assertEquals(AbstractFilter.Result.NEUTRAL, filter.filter(target));
        assertEquals(AbstractFilter.Result.NEUTRAL, filter.filter(target));
        assertEquals(AbstractFilter.Result.DENY, filter.filter(target));

        LogEvent sameTextWrongLogger = event(
                "third.party.Logger",
                "Tried to load invalid item: '{}'",
                "removed:item"
        );
        LogEvent sameLoggerWrongTemplate = event(
                "net.minecraft.world.item.ItemStack",
                "Tried to load invalid block: '{}'",
                "removed:item"
        );
        assertEquals(AbstractFilter.Result.NEUTRAL, filter.filter(sameTextWrongLogger));
        assertEquals(AbstractFilter.Result.NEUTRAL, filter.filter(sameLoggerWrongTemplate));
    }

    @Test
    void nonErrorLevelsAlwaysPass() {
        LogEvent event = Log4jLogEvent.newBuilder()
                .setLoggerName("appeng.api.stacks.AEKey")
                .setLevel(Level.WARN)
                .setMessage(new ParameterizedMessage("Failed to deserialize AE key: {}", "removed:item"))
                .build();
        YuWorldRepairLogFilter filter = new YuWorldRepairLogFilter(
                new GuardRuntime(() -> guardConfig(), new GuardMetrics(), "test"),
                LogicalSide.SERVER
        );

        assertEquals(AbstractFilter.Result.NEUTRAL, filter.filter(event));
    }

    @Test
    void realSlf4jBridgePreservesTemplateUntilFilter() {
        GuardRuntime runtime = new GuardRuntime(() -> guardConfig(), new GuardMetrics(), "test");
        YuWorldRepairLogFilter filter = new YuWorldRepairLogFilter(runtime, LogicalSide.SERVER);
        assertTrue(filter.install());
        try {
            LoggerFactory.getLogger("net.minecraft.world.item.ItemStack").error(
                    "Tried to load invalid item: '{}'",
                    "Unknown registry key in ResourceKey[minecraft:root / minecraft:item]: removed:bridge_test"
            );
            assertEquals(1, runtime.metrics().recognized());
        } finally {
            filter.uninstall();
        }
    }

    private static LogEvent event(String logger, String template, String argument) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName(logger)
                .setLevel(Level.ERROR)
                .setThreadName("Server thread")
                .setMessage(new ParameterizedMessage(template, argument))
                .build();
    }

    private static RuntimeConfig guardConfig() {
        return new RuntimeConfig(
                GuardMode.GUARD,
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
        );
    }
}
