package dev.yu.worldrepair.maintenance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;
import dev.yu.worldrepair.worldtool.maintenance.MaintenanceRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

final class MaintenanceConfig {
    private static final long MAX_BYTES = 262_144;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path path;

    MaintenanceConfig(Path configDirectory) {
        path = configDirectory.resolve("yuworldrepair-maintenance.json");
    }

    Values load() throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Values defaults = Values.defaults();
            IoUtil.writeAtomicUtf8(path, JSON.toJson(defaults) + "\n");
            return defaults;
        }
        WorldAccessPolicy.rejectLinkChain(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || Files.size(path) > MAX_BYTES) {
            throw new IOException("Maintenance config is linked, missing, or oversized");
        }
        try {
            Values values = JSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Values.class);
            if (values == null) {
                throw new IOException("Maintenance config is empty");
            }
            values.validate();
            return values.normalized();
        } catch (JsonParseException | IllegalArgumentException invalid) {
            throw new IOException("Invalid maintenance config: " + invalid.getMessage(), invalid);
        }
    }

    record Values(
            boolean enabled,
            int countdownSeconds,
            int startupWaitSeconds,
            int scanWorkers,
            MaintenanceRequest.RestartStrategy restartStrategy,
            List<String> restartCommand
    ) {
        Values {
            restartCommand = restartCommand == null ? List.of() : List.copyOf(restartCommand);
        }

        static Values defaults() {
            return new Values(
                    true,
                    10,
                    1_800,
                    0,
                    MaintenanceRequest.RestartStrategy.PANEL,
                    List.of()
            );
        }

        Values normalized() {
            return new Values(
                    enabled,
                    countdownSeconds,
                    startupWaitSeconds,
                    scanWorkers,
                    restartStrategy,
                    restartCommand
            );
        }

        int effectiveScanWorkers() {
            if (scanWorkers > 0) {
                return scanWorkers;
            }
            int processors = Runtime.getRuntime().availableProcessors();
            return Math.max(1, Math.min(8, (processors + 1) / 2));
        }

        void validate() {
            if (countdownSeconds < 5 || countdownSeconds > 300) {
                throw new IllegalArgumentException("countdownSeconds must be 5..300");
            }
            if (startupWaitSeconds < 60 || startupWaitSeconds > 3_600) {
                throw new IllegalArgumentException("startupWaitSeconds must be 60..3600");
            }
            if (scanWorkers < 0 || scanWorkers > 16) {
                throw new IllegalArgumentException("scanWorkers must be 0(auto)..16");
            }
            if (restartStrategy == null) {
                throw new IllegalArgumentException("restartStrategy is required");
            }
            if (restartStrategy == MaintenanceRequest.RestartStrategy.SELF
                    && restartCommand.isEmpty()) {
                throw new IllegalArgumentException("SELF restart requires restartCommand");
            }
            if (restartCommand.size() > 64
                    || restartCommand.stream().anyMatch(value -> value == null
                    || value.isBlank()
                    || value.length() > 4_096
                    || value.indexOf('\0') >= 0)) {
                throw new IllegalArgumentException("restartCommand is invalid");
            }
        }
    }
}
