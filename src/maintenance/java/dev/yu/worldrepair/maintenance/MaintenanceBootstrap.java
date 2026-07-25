package dev.yu.worldrepair.maintenance;

import dev.yu.worldrepair.YuWorldRepair;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MaintenanceBootstrap {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private MaintenanceBootstrap() {
    }

    public static void install() {
        if (FMLEnvironment.dist != Dist.DEDICATED_SERVER || !INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            MaintenanceConfig.Values config =
                    new MaintenanceConfig(FMLPaths.CONFIGDIR.get()).load();
            var previous = StartupGate.awaitSafeResult(
                    FMLPaths.GAMEDIR.get(),
                    config.startupWaitSeconds()
            );
            MaintenanceController controller = new MaintenanceController(
                    FMLPaths.GAMEDIR.get(),
                    config,
                    previous.orElse(null)
            );
            NeoForge.EVENT_BUS.register(controller);
            YuWorldRepair.LOGGER.info(
                    "YuWorldRepair maintenance edition installed: enabled={}, restartStrategy={}",
                    config.enabled(),
                    config.restartStrategy()
            );
        } catch (IOException unsafeStartup) {
            throw new IllegalStateException(
                    "YuWorldRepair maintenance startup gate refused to open the world",
                    unsafeStartup
            );
        }
    }
}
