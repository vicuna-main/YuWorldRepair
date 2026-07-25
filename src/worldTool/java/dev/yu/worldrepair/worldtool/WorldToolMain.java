package dev.yu.worldrepair.worldtool;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class WorldToolMain {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();

    private WorldToolMain() {
    }

    public static void main(String[] arguments) {
        int exitCode = run(arguments);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] arguments) {
        if (arguments.length == 0 || isHelp(arguments[0])) {
            printUsage();
            return arguments.length == 0 ? 2 : 0;
        }
        String command = arguments[0];
        try {
            Arguments options = Arguments.parse(arguments, 1);
            WorldRepairService service = new WorldRepairService();
            WorldRepairService.CommandResult result = switch (command) {
                case "scan" -> {
                    options.requireOnly(
                            Set.of(
                                    "--world-copy",
                                    "--job-root",
                                    "--iceandfire-jar",
                                    "--adapter",
                                    "--minecraft-version",
                                    "--neoforge-version",
                                    "--youer-version"
                            ),
                            Set.of("--dry-run")
                    );
                    String adapter = options.optional("--adapter", "iceandfire-chicken-data");
                    if (!adapter.equals("iceandfire-chicken-data")) {
                        throw new UsageException("Unsupported adapter: " + adapter);
                    }
                    yield service.scan(
                            Path.of(options.required("--world-copy")),
                            Path.of(options.required("--job-root")),
                            Path.of(options.required("--iceandfire-jar")),
                            new WorldRepairService.RuntimeMetadata(
                                    options.optional("--minecraft-version", null),
                                    options.optional("--neoforge-version", null),
                                    options.optional("--youer-version", null)
                            )
                    );
                }
                case "prepare" -> {
                    options.requireOnly(Set.of("--job"), Set.of());
                    yield service.prepare(Path.of(options.required("--job")));
                }
                case "apply" -> {
                    options.requireOnly(Set.of("--job", "--confirm"), Set.of());
                    yield service.apply(
                            Path.of(options.required("--job")),
                            options.required("--confirm")
                    );
                }
                case "verify" -> {
                    options.requireOnly(Set.of("--job"), Set.of());
                    yield service.verify(Path.of(options.required("--job")));
                }
                case "rollback" -> {
                    options.requireOnly(Set.of("--job", "--confirm"), Set.of());
                    yield service.rollback(
                            Path.of(options.required("--job")),
                            options.required("--confirm")
                    );
                }
                case "verify-rollback" -> {
                    options.requireOnly(Set.of("--job"), Set.of());
                    yield service.verifyRollback(Path.of(options.required("--job")));
                }
                case "status" -> {
                    options.requireOnly(Set.of("--job"), Set.of());
                    yield service.status(Path.of(options.required("--job")));
                }
                default -> throw new UsageException("Unknown command: " + command);
            };
            System.out.println(JSON.toJson(result));
            return result.success() ? 0 : 3;
        } catch (UsageException invalid) {
            System.err.println(JSON.toJson(Map.of(
                    "success", false,
                    "category", "usage",
                    "error", invalid.getMessage()
            )));
            printUsage();
            return 2;
        } catch (IOException safetyOrIo) {
            System.err.println(JSON.toJson(Map.of(
                    "success", false,
                    "category", "safety_or_io",
                    "error", safeMessage(safetyOrIo)
            )));
            return 3;
        } catch (RuntimeException unexpected) {
            System.err.println(JSON.toJson(Map.of(
                    "success", false,
                    "category", "unexpected",
                    "error", unexpected.getClass().getSimpleName() + ": " + safeMessage(unexpected)
            )));
            return 4;
        }
    }

    private static boolean isHelp(String argument) {
        return argument.equals("help") || argument.equals("--help") || argument.equals("-h");
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message.length() <= 1_024 ? message : message.substring(0, 1_024);
    }

    private static void printUsage() {
        System.err.println("""
                YuWorldRepair Offline World Tool 1.1.0-experimental
                The tool only operates on an offline world copy containing .yuworldrepair-world-copy.

                scan --world-copy <absolute> --job-root <absolute> --iceandfire-jar <absolute>
                     [--adapter iceandfire-chicken-data] [--dry-run]
                     [--minecraft-version <version>] [--neoforge-version <version>]
                     [--youer-version <build-or-none>]
                prepare --job <absolute-job-directory>
                apply --job <absolute-job-directory> --confirm <one-time-token>
                verify --job <absolute-job-directory>
                rollback --job <absolute-job-directory> --confirm <one-time-token>
                verify-rollback --job <absolute-job-directory>
                status --job <absolute-job-directory>
                """);
    }

    private static final class Arguments {
        private final LinkedHashMap<String, String> values;
        private final Set<String> flags;

        private Arguments(LinkedHashMap<String, String> values, Set<String> flags) {
            this.values = values;
            this.flags = flags;
        }

        private static Arguments parse(String[] arguments, int start) throws UsageException {
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            java.util.LinkedHashSet<String> flags = new java.util.LinkedHashSet<>();
            int index = start;
            while (index < arguments.length) {
                String option = arguments[index];
                if (!option.startsWith("--") || option.length() < 3) {
                    throw new UsageException("Expected an option, found: " + option);
                }
                if (option.equals("--dry-run")) {
                    if (!flags.add(option)) {
                        throw new UsageException("Duplicate option: " + option);
                    }
                    index++;
                    continue;
                }
                if (index + 1 >= arguments.length || arguments[index + 1].startsWith("--")) {
                    throw new UsageException("Missing value for " + option);
                }
                if (values.putIfAbsent(option, arguments[index + 1]) != null) {
                    throw new UsageException("Duplicate option: " + option);
                }
                index += 2;
            }
            return new Arguments(values, Set.copyOf(flags));
        }

        private String required(String option) throws UsageException {
            String value = values.get(option);
            if (value == null || value.isBlank()) {
                throw new UsageException("Missing required option " + option);
            }
            return value;
        }

        private String optional(String option, String fallback) {
            return values.getOrDefault(option, fallback);
        }

        private void requireOnly(Set<String> allowedValues, Set<String> allowedFlags)
                throws UsageException {
            for (String option : values.keySet()) {
                if (!allowedValues.contains(option)) {
                    throw new UsageException("Unknown option: " + option);
                }
            }
            for (String flag : flags) {
                if (!allowedFlags.contains(flag)) {
                    throw new UsageException("Unknown flag: " + flag);
                }
            }
        }
    }

    private static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        private UsageException(String message) {
            super(message);
        }
    }
}
