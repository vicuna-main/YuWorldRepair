package dev.yu.worldrepair.worldtool.maintenance;

import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.namespace.NamespacePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaintenanceRequestScopeSecurityTest {
    @TempDir
    Path temporary;

    @Test
    void schemaFiveBindsExcludedRegionRootsAndWorkerCountIntoHmac() {
        String secret = "scope-security-secret-".repeat(3);
        Path server = temporary.resolve("server").toAbsolutePath();
        Path main = server.resolve("world");
        Path secondary = server.resolve("playerworld").resolve("Vicuna");
        MaintenanceRequest original = request(
                secret,
                server,
                main,
                secondary,
                List.of(main.toString()),
                4,
                NamespacePolicy.Mode.ORPHANED_ITEMS
        );
        assertDoesNotThrow(original::validate);

        MaintenanceRequest changedWorkers = request(
                secret,
                server,
                main,
                secondary,
                List.of(main.toString()),
                8,
                NamespacePolicy.Mode.ORPHANED_ITEMS
        );
        MaintenanceRequest changedExclusions = request(
                secret,
                server,
                main,
                secondary,
                List.of(secondary.toString()),
                4,
                NamespacePolicy.Mode.ORPHANED_ITEMS
        );

        assertNotEquals(
                original.bindingHmacSha256(),
                changedWorkers.bindingHmacSha256()
        );
        assertNotEquals(
                original.bindingHmacSha256(),
                changedExclusions.bindingHmacSha256()
        );
    }

    @Test
    void regionExclusionIsRejectedOutsideGlobalOrphanItemMode() {
        String secret = "scope-security-secret-".repeat(3);
        Path server = temporary.resolve("invalid-server").toAbsolutePath();
        Path main = server.resolve("world");
        Path secondary = server.resolve("other");
        MaintenanceRequest invalid = request(
                secret,
                server,
                main,
                secondary,
                List.of(main.toString()),
                4,
                NamespacePolicy.Mode.ORPHANED_ONLY
        );
        assertThrows(IllegalArgumentException.class, invalid::validate);
    }

    private static MaintenanceRequest request(
            String secret,
            Path server,
            Path main,
            Path secondary,
            List<String> exclusions,
            int workers,
            NamespacePolicy.Mode mode
    ) {
        Instant now = Instant.now();
        String namespace = mode == NamespacePolicy.Mode.ORPHANED_ITEMS
                ? NamespacePolicy.ALL_ORPHANED_ITEMS
                : "oldmod";
        MaintenanceRequest unsigned = new MaintenanceRequest(
                MaintenanceRequest.SCHEMA_VERSION,
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                IoUtil.sha256(secret.getBytes(StandardCharsets.UTF_8)),
                "0".repeat(64),
                now.toString(),
                now.plusSeconds(300).toString(),
                12345,
                MaintenanceRequest.Operation.NAMESPACE_REPAIR,
                server.toString(),
                main.toString(),
                List.of(main.toString(), secondary.toString()),
                exclusions,
                workers,
                server.resolve("yuworldrepair-maintenance").resolve("jobs").toString(),
                null,
                null,
                namespace,
                mode,
                server.resolve("registry-snapshot.json").toString(),
                "1".repeat(64),
                "1.21.1",
                "21.1.241",
                "Youer",
                MaintenanceRequest.RestartStrategy.NONE,
                List.of(),
                MaintenanceRequest.State.HANDOFF,
                "scope security test"
        );
        return unsigned.withBindingHmac(unsigned.computeBindingHmac(secret));
    }
}
