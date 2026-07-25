package dev.yu.worldrepair.worldtool.maintenance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public final class MaintenanceFiles {
    public static final String REQUEST_FILE = "request.json";
    public static final String RESULT_FILE = "result.json";
    public static final long MAX_JSON_BYTES = 1_048_576;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private MaintenanceFiles() {
    }

    public static MaintenanceRequest readRequest(Path path) throws IOException {
        MaintenanceRequest request = read(path, MaintenanceRequest.class);
        try {
            request.validate();
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Maintenance request validation failed: " + invalid.getMessage(), invalid);
        }
        return request;
    }

    public static MaintenanceResult readResult(Path path) throws IOException {
        return read(path, MaintenanceResult.class);
    }

    public static MaintenanceRequest readStoredRequest(Path path) throws IOException {
        MaintenanceRequest request = read(path, MaintenanceRequest.class);
        try {
            request.validateStored();
        } catch (IllegalArgumentException invalid) {
            throw new IOException(
                    "Stored maintenance request validation failed: " + invalid.getMessage(),
                    invalid
            );
        }
        return request;
    }

    public static void writeRequest(Path path, MaintenanceRequest request) throws IOException {
        request.validate();
        IoUtil.writeAtomicUtf8(path, JSON.toJson(request) + "\n");
    }

    public static void writeResult(Path path, MaintenanceResult result) throws IOException {
        IoUtil.writeAtomicUtf8(path, JSON.toJson(result) + "\n");
    }

    private static <T> T read(Path path, Class<T> type) throws IOException {
        WorldAccessPolicy.rejectLinkChain(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || Files.size(path) > MAX_JSON_BYTES) {
            throw new IOException("Maintenance JSON is missing, linked, or oversized: " + path);
        }
        try {
            T result = JSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), type);
            if (result == null) {
                throw new IOException("Maintenance JSON is empty");
            }
            return result;
        } catch (JsonParseException malformed) {
            throw new IOException("Maintenance JSON is malformed", malformed);
        }
    }
}
