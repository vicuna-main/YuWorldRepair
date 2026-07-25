package dev.yu.worldrepair.integration.ae2;

import dev.yu.worldrepair.migration.SourceAdapterBridge;
import org.slf4j.Logger;

public final class Ae2MigrationBridgeFactory {
    private static final String IMPLEMENTATION =
            "dev.yu.worldrepair.integration.ae2.internal.Ae2NetworkMigrationAdapter";

    private Ae2MigrationBridgeFactory() {
    }

    public static SourceAdapterBridge create(Logger logger) {
        Ae2Compatibility compatibility = Ae2Compatibility.detect();
        if (compatibility.status() != Ae2Compatibility.Status.VALIDATED_READ_ONLY) {
            return null;
        }
        try {
            Class<?> type = Class.forName(IMPLEMENTATION, true, Ae2MigrationBridgeFactory.class.getClassLoader());
            return (SourceAdapterBridge) type.getConstructor(Logger.class).newInstance(logger);
        } catch (Throwable failure) {
            logger.warn(
                    "[YuWorldRepair] AE2 migration adapter failed self-check and is disabled; core guard remains active",
                    failure
            );
            return null;
        }
    }
}
