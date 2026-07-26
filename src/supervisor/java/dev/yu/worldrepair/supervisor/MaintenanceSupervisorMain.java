package dev.yu.worldrepair.supervisor;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Panel-owned launcher that keeps the container/process group alive during offline maintenance.
 */
public final class MaintenanceSupervisorMain {
    static final String SUPERVISOR_ID_ENV = "YUWORLDREPAIR_SUPERVISOR_ID";
    private static final String MAINTENANCE_ROOT = "yuworldrepair-maintenance";
    private static final String REQUESTS_DIRECTORY = "requests";
    private static final String HANDOFF_FILE = "handoff.json";
    private static final String RESULT_FILE = "result.json";
    private static final long MAX_JSON_BYTES = 1_048_576;
    private static final long DEFAULT_WORKER_WARNING_SECONDS = 600;
    private static final long POLL_MILLIS = 250;
    private static final long PROCESS_START_TOLERANCE_MILLIS = 2_000;
    private static final Set<String> HANDOFF_STATES = Set.of(
            "REQUESTED",
            "COUNTDOWN",
            "HANDOFF",
            "WAITING_FOR_STOP",
            "SCANNING",
            "BACKING_UP",
            "APPLYING",
            "VERIFYING",
            "ROLLING_BACK",
            "COMPLETED",
            "ROLLED_BACK",
            "FAILED"
    );
    private static final Set<String> TERMINAL_STATES =
            Set.of("COMPLETED", "ROLLED_BACK", "FAILED");
    private static final SecureRandom RANDOM = new SecureRandom();

    private MaintenanceSupervisorMain() {
    }

    public static void main(String[] arguments) {
        int code = run(arguments, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] arguments, PrintStream output, PrintStream error) {
        try {
            Options options = Options.parse(arguments);
            return supervise(options, output, error);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            error.println("[YuWorldRepair supervisor] " + safeMessage(failure));
            return 4;
        }
    }

    private static int supervise(
            Options options,
            PrintStream output,
            PrintStream error
    ) throws IOException, InterruptedException {
        String supervisorId = randomHex(32);
        ProcessBuilder serverBuilder = new ProcessBuilder(options.serverCommand());
        serverBuilder.directory(options.serverRoot().toFile());
        serverBuilder.inheritIO();
        serverBuilder.environment().put(SUPERVISOR_ID_ENV, supervisorId);
        Process server = serverBuilder.start();
        long serverPid = server.pid();
        output.println(
                "[YuWorldRepair supervisor] server_started pid=" + serverPid
                        + " root=" + options.serverRoot()
        );
        Thread shutdownHook = new Thread(
                () -> {
                    if (server.isAlive()) {
                        error.println(
                                "[YuWorldRepair supervisor] forwarding_shutdown pid=" + serverPid
                        );
                        server.destroy();
                    }
                },
                "yuworldrepair-supervisor-shutdown"
        );
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        int serverExit;
        try {
            serverExit = server.waitFor();
        } catch (InterruptedException interrupted) {
            server.destroy();
            throw interrupted;
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException shutdownInProgress) {
                // The hook is already forwarding an external panel stop.
            }
        }

        Path requestsRoot = options.serverRoot()
                .resolve(MAINTENANCE_ROOT)
                .resolve(REQUESTS_DIRECTORY);
        HandoffMatch match = findHandoff(requestsRoot, supervisorId, serverPid);
        if (match == null) {
            output.println(
                    "[YuWorldRepair supervisor] server_exited pid=" + serverPid
                            + " code=" + serverExit + " maintenance=false"
            );
            return serverExit;
        }

        Handoff handoff = match.handoff();
        output.println(
                "[YuWorldRepair supervisor] maintenance_handoff request="
                        + handoff.requestId()
                        + " workerPid=" + handoff.workerPid()
                        + " state=" + handoff.state()
        );
        Instant nextWarning = Instant.now().plusSeconds(options.workerWarningSeconds());
        Path resultPath = match.requestDirectory().resolve(RESULT_FILE);
        while (true) {
            TerminalResult result = readResultIfPresent(resultPath, handoff.requestId());
            boolean workerAlive = isExpectedProcessAlive(
                    handoff.workerPid(),
                    handoff.workerStartedAtEpochMillis()
            );
            if (result != null && !workerAlive) {
                output.println(
                        "[YuWorldRepair supervisor] maintenance_complete request="
                                + result.requestId()
                                + " state=" + result.state()
                                + " success=" + result.success()
                );
                return 0;
            }
            if (!workerAlive
                    && result == null
                    && System.currentTimeMillis()
                    > handoff.requestExpiresAtEpochMillis() + TimeUnit.SECONDS.toMillis(5)) {
                error.println(
                        "[YuWorldRepair supervisor] worker_exited_without_result request="
                                + handoff.requestId()
                );
                return 4;
            }
            if (!Instant.now().isBefore(nextWarning)) {
                error.println(
                        "[YuWorldRepair supervisor] worker_still_running request="
                                + handoff.requestId()
                                + " warningSeconds=" + options.workerWarningSeconds()
                                + " continuingToSupervise=true"
                );
                nextWarning = Instant.now().plusSeconds(options.workerWarningSeconds());
            }
            TimeUnit.MILLISECONDS.sleep(POLL_MILLIS);
        }
    }

    private static HandoffMatch findHandoff(
            Path requestsRoot,
            String supervisorId,
            long serverPid
    ) throws IOException {
        if (!Files.exists(requestsRoot, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        requireDirectory(requestsRoot, "maintenance requests root");
        ArrayList<HandoffMatch> matches = new ArrayList<>();
        try (Stream<Path> children = Files.list(requestsRoot)) {
            for (Path requestDirectory : children.toList()) {
                if (!Files.isDirectory(requestDirectory, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(requestDirectory)) {
                    continue;
                }
                Path handoffPath = requestDirectory.resolve(HANDOFF_FILE);
                if (!Files.isRegularFile(handoffPath, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(handoffPath)) {
                    continue;
                }
                Handoff handoff;
                try {
                    handoff = readHandoff(handoffPath);
                } catch (IOException malformedUnrelatedHandoff) {
                    continue;
                }
                if (handoff != null && handoff.matches(
                        requestDirectory.getFileName().toString(),
                        supervisorId,
                        serverPid
                )) {
                    matches.add(new HandoffMatch(requestDirectory, handoff));
                }
            }
        }
        if (matches.size() > 1) {
            throw new IOException("Multiple maintenance handoffs matched one server process");
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static TerminalResult readResultIfPresent(
            Path resultPath,
            String requestId
    ) throws IOException {
        if (!Files.exists(resultPath, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(resultPath, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(resultPath)) {
            throw new IOException("Maintenance result is not a regular file");
        }
        TerminalResult result = readTerminalResult(resultPath);
        if (result.schemaVersion() != 1
                || !requestId.equals(result.requestId())
                || result.state() == null
                || !TERMINAL_STATES.contains(result.state())) {
            throw new IOException("Maintenance result does not match the supervised request");
        }
        return result;
    }

    private static Handoff readHandoff(Path path) throws IOException {
        String json = readControlJson(path);
        return new Handoff(
                integerField(json, "schemaVersion"),
                stringField(json, "requestId", "[0-9a-fA-F-]{36}"),
                stringField(json, "supervisorId", "[0-9a-f]{64}"),
                longField(json, "serverPid"),
                longField(json, "workerPid"),
                longField(json, "workerStartedAtEpochMillis"),
                stringField(json, "state", "[A-Z_]{1,32}"),
                stringField(json, "updatedAt", "[^\"\\r\\n]{1,128}"),
                longField(json, "requestExpiresAtEpochMillis")
        );
    }

    private static TerminalResult readTerminalResult(Path path) throws IOException {
        String json = readControlJson(path);
        return new TerminalResult(
                integerField(json, "schemaVersion"),
                stringField(json, "requestId", "[0-9a-fA-F-]{36}"),
                booleanField(json, "success"),
                stringField(json, "state", "[A-Z_]{1,32}")
        );
    }

    private static String readControlJson(Path path) throws IOException {
        if (Files.size(path) > MAX_JSON_BYTES) {
            throw new IOException("Maintenance control JSON exceeds the byte limit");
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String stringField(
            String json,
            String field,
            String allowedValue
    ) throws IOException {
        return uniqueField(
                json,
                field,
                "\"(" + allowedValue + ")\"",
                1
        );
    }

    private static long longField(String json, String field) throws IOException {
        String value = uniqueField(json, field, "([0-9]{1,19})", 1);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new IOException("Maintenance control JSON has an invalid " + field, invalid);
        }
    }

    private static int integerField(String json, String field) throws IOException {
        long value = longField(json, field);
        if (value > Integer.MAX_VALUE) {
            throw new IOException("Maintenance control JSON has an oversized " + field);
        }
        return (int) value;
    }

    private static boolean booleanField(String json, String field) throws IOException {
        return Boolean.parseBoolean(uniqueField(json, field, "(true|false)", 1));
    }

    private static String uniqueField(
            String json,
            String field,
            String valuePattern,
            int valueGroup
    ) throws IOException {
        Pattern pattern = Pattern.compile(
                "(?m)^  \"" + Pattern.quote(field)
                        + "\"[ \\t]*:[ \\t]*" + valuePattern + "[ \\t]*,?[ \\t]*$"
        );
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new IOException("Maintenance control JSON is missing " + field);
        }
        String value = matcher.group(valueGroup);
        if (matcher.find()) {
            throw new IOException("Maintenance control JSON duplicates " + field);
        }
        return value;
    }

    private static boolean isExpectedProcessAlive(long pid, long expectedStartMillis) {
        return ProcessHandle.of(pid)
                .filter(ProcessHandle::isAlive)
                .flatMap(handle -> handle.info().startInstant())
                .filter(started -> Math.abs(
                        started.toEpochMilli() - expectedStartMillis
                ) <= PROCESS_START_TOLERANCE_MILLIS)
                .isPresent();
    }

    private static void requireDirectory(Path path, String description) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)) {
            throw new IOException(description + " is linked or not a directory");
        }
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        String oneLine = message.replace('\r', ' ').replace('\n', ' ');
        return oneLine.length() <= 1_024 ? oneLine : oneLine.substring(0, 1_024);
    }

    private record HandoffMatch(Path requestDirectory, Handoff handoff) {
    }

    private record Handoff(
            int schemaVersion,
            String requestId,
            String supervisorId,
            long serverPid,
            long workerPid,
            long workerStartedAtEpochMillis,
            String state,
            String updatedAt,
            long requestExpiresAtEpochMillis
    ) {
        boolean matches(String directoryName, String expectedSupervisorId, long expectedServerPid) {
            if (schemaVersion != 1
                    || requestId == null
                    || !requestId.equals(directoryName)
                    || !requestId.matches("[0-9a-fA-F-]{36}")
                    || !expectedSupervisorId.equals(supervisorId)
                    || serverPid != expectedServerPid
                    || workerPid <= 0
                    || workerStartedAtEpochMillis <= 0
                    || requestExpiresAtEpochMillis <= 0
                    || state == null
                    || updatedAt == null
                    || !HANDOFF_STATES.contains(state)) {
                return false;
            }
            try {
                Instant.parse(updatedAt);
                return true;
            } catch (RuntimeException invalid) {
                return false;
            }
        }
    }

    private record TerminalResult(
            int schemaVersion,
            String requestId,
            boolean success,
            String state
    ) {
    }

    private record Options(
            Path serverRoot,
            long workerWarningSeconds,
            List<String> serverCommand
    ) {
        static Options parse(String[] arguments) {
            Path serverRoot = Path.of("").toAbsolutePath().normalize();
            long warning = DEFAULT_WORKER_WARNING_SECONDS;
            int index = 0;
            while (index < arguments.length && !"--".equals(arguments[index])) {
                String option = arguments[index++];
                if ("--server-root".equals(option) && index < arguments.length) {
                    serverRoot = Path.of(arguments[index++]).toAbsolutePath().normalize();
                } else if ("--worker-warning-seconds".equals(option)
                        && index < arguments.length) {
                    warning = Long.parseLong(arguments[index++]);
                } else {
                    throw new IllegalArgumentException("Unknown or incomplete option: " + option);
                }
            }
            if (index >= arguments.length || !"--".equals(arguments[index])) {
                throw new IllegalArgumentException(
                        "Usage: supervisor [options] -- <server command and arguments>"
                );
            }
            index++;
            ArrayList<String> command = new ArrayList<>();
            while (index < arguments.length) {
                command.add(arguments[index++]);
            }
            if (command.isEmpty()) {
                throw new IllegalArgumentException("Server command is required after --");
            }
            if (warning < 1 || warning > Duration.ofDays(7).toSeconds()) {
                throw new IllegalArgumentException(
                        "worker warning interval must be between 1 second and 7 days"
                );
            }
            if (!Files.isDirectory(serverRoot, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(serverRoot)) {
                throw new IllegalArgumentException("Server root is linked or not a directory");
            }
            return new Options(serverRoot, warning, List.copyOf(command));
        }
    }
}
