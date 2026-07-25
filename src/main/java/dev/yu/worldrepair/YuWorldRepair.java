package dev.yu.worldrepair;

import dev.yu.worldrepair.command.YuWorldRepairCommands;
import dev.yu.worldrepair.command.YuWorldRepairPermissions;
import dev.yu.worldrepair.config.YuWorldRepairConfig;
import dev.yu.worldrepair.guard.LogicalSide;
import dev.yu.worldrepair.integration.EnvironmentFingerprint;
import dev.yu.worldrepair.log.GuardRuntime;
import dev.yu.worldrepair.log.PeriodicSummary;
import dev.yu.worldrepair.log.YuWorldRepairLogFilter;
import dev.yu.worldrepair.metrics.GuardMetrics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;

@Mod(YuWorldRepair.MOD_ID)
public final class YuWorldRepair {
    public static final String MOD_ID = "yuworldrepair";
    public static final Logger LOGGER = LoggerFactory.getLogger("YuWorldRepair");

    private final YuWorldRepairConfig config;
    private final GuardRuntime runtime;
    private final YuWorldRepairLogFilter filter;
    private final PeriodicSummary summary;
    private final YuWorldRepairCommands commands;

    public YuWorldRepair() {
        installMaintenanceEditionIfPresent();
        config = new YuWorldRepairConfig(FMLPaths.CONFIGDIR.get(), LOGGER);
        config.load();
        runtime = new GuardRuntime(config::current, new GuardMetrics(), EnvironmentFingerprint.capture());
        LogicalSide physicalSide = FMLEnvironment.dist == Dist.CLIENT
                ? LogicalSide.CLIENT
                : LogicalSide.SERVER;
        filter = new YuWorldRepairLogFilter(runtime, physicalSide);
        boolean installed = filter.install();
        summary = new PeriodicSummary(runtime, config::current, LOGGER);
        summary.start();
        commands = new YuWorldRepairCommands(
                config,
                runtime,
                filter,
                FMLPaths.GAMEDIR.get()
        );
        NeoForge.EVENT_BUS.register(this);

        if (installed) {
            LOGGER.info(
                    "YuWorldRepair loaded in {} mode with {} bounded signature slots; data mutation and negative cache are disabled",
                    config.current().mode().name().toLowerCase(),
                    config.current().maxSignatures()
            );
        } else {
            LOGGER.warn("YuWorldRepair Log4j hook unavailable; continuing fail-open without suppression");
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        commands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onPermissionNodes(PermissionGatherEvent.Nodes event) {
        YuWorldRepairPermissions.register(event);
    }

    private static void installMaintenanceEditionIfPresent() {
        try {
            Class<?> bootstrap = Class.forName(
                    "dev.yu.worldrepair.maintenance.MaintenanceBootstrap",
                    true,
                    YuWorldRepair.class.getClassLoader()
            );
            bootstrap.getMethod("install").invoke(null);
        } catch (ClassNotFoundException observationEdition) {
            // The 1.0 observation-only artifact deliberately does not package maintenance code.
        } catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Maintenance bootstrap failed", cause);
        } catch (ReflectiveOperationException failed) {
            throw new IllegalStateException("Maintenance bootstrap is present but invalid", failed);
        }
    }
}
