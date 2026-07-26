package dev.yu.worldrepair.maintenance;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.yu.worldrepair.YuWorldRepair;
import dev.yu.worldrepair.worldtool.adapter.LegacyChickenDataAdapter;
import dev.yu.worldrepair.worldtool.io.IoUtil;
import dev.yu.worldrepair.worldtool.io.WorldAccessPolicy;
import dev.yu.worldrepair.worldtool.maintenance.MaintenanceFiles;
import dev.yu.worldrepair.worldtool.maintenance.MaintenanceHandoff;
import dev.yu.worldrepair.worldtool.maintenance.MaintenanceRequest;
import dev.yu.worldrepair.worldtool.maintenance.MaintenanceRegionScope;
import dev.yu.worldrepair.worldtool.maintenance.MaintenanceResult;
import dev.yu.worldrepair.worldtool.maintenance.MaintenanceWorldRoots;
import dev.yu.worldrepair.worldtool.maintenance.RegistrySnapshot;
import dev.yu.worldrepair.worldtool.namespace.NamespacePolicy;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.security.DigestInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;

final class MaintenanceController {
    private static final String WORKER_CLASS =
            "dev.yu.worldrepair.worldtool.maintenance.MaintenanceWorkerMain";
    private static final String MAINTENANCE_CLASS_ENTRY =
            "dev/yu/worldrepair/maintenance/MaintenanceBootstrap.class";
    private static final String WORKER_RESOURCE_ENTRY = "META-INF/yuworldrepair/worker.jar";
    private static final String WORKER_AUTH_ENV = "YUWORLDREPAIR_MAINTENANCE_AUTH";
    private static final long MAX_EMBEDDED_WORKER_BYTES = 16L * 1_024 * 1_024;
    private static final long CONFIRM_TTL_MILLIS = TimeUnit.MINUTES.toMillis(2);
    private static final long WORKER_READY_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path gameDirectory;
    private final MaintenanceConfig.Values config;
    private final MaintenanceResult startupResult;
    private Pending pending;

    MaintenanceController(
            Path gameDirectory,
            MaintenanceConfig.Values config,
            MaintenanceResult startupResult
    ) {
        this.gameDirectory = gameDirectory.toAbsolutePath().normalize();
        this.config = config;
        this.startupResult = startupResult;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (startupResult == null) {
            return;
        }
        Component message = Component.literal(
                "[YuWorldRepair] 上一次维护结果: " + startupResult.state()
                        + "；" + startupResult.detail()
                        + (startupResult.jobPath() == null
                        ? ""
                        : "；job=" + startupResult.jobPath())
        );
        event.getServer().sendSystemMessage(message);
        String metrics = formatItemMetrics(startupResult.metrics());
        if (!metrics.isEmpty()) {
            event.getServer().sendSystemMessage(Component.literal(
                    "[YuWorldRepair] 问题物品统计: " + metrics
            ));
        }
        YuWorldRepair.LOGGER.info(
                "Maintenance result request={} state={} success={} detail={} job={}",
                startupResult.requestId(),
                startupResult.state(),
                startupResult.success(),
                startupResult.detail(),
                startupResult.jobPath()
        );
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        Pending active = pending;
        if (active == null || active.deadlineMillis() == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        int remaining = (int) Math.max(0, (active.deadlineMillis() - now + 999) / 1_000);
        if (remaining != active.lastAnnounced()
                && (remaining <= 5 || remaining % 5 == 0)) {
            broadcast(
                    event.getServer(),
                    Component.literal(
                            "[YuWorldRepair] " + remaining
                                    + " 秒后进入离线维护，世界将保存并断开玩家。"
                    ).withStyle(ChatFormatting.YELLOW)
            );
            pending = active = active.withLastAnnounced(remaining);
        }
        if (now < active.deadlineMillis()) {
            return;
        }
        pending = null;
        beginHandoff(event.getServer(), active);
    }

    private void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("yuworldrepair")
                .then(Commands.literal("repair")
                        .requires(source -> source.hasPermission(4))
                        .then(Commands.literal("iceandfire")
                                .executes(context -> requestNamespaceRepair(
                                         context.getSource(),
                                         "iceandfire",
                                         NamespacePolicy.Mode.PREPARE_REMOVE,
                                         MaintenanceRegionScope.Mode.ALL,
                                         null
                                 )))
                        .then(Commands.literal("orphaned-items")
                                .executes(context -> requestNamespaceRepair(
                                        context.getSource(),
                                        NamespacePolicy.ALL_ORPHANED_ITEMS,
                                        NamespacePolicy.Mode.ORPHANED_ITEMS,
                                        MaintenanceRegionScope.Mode.ALL,
                                        null
                                ))
                                .then(Commands.literal("only")
                                        .then(Commands.argument(
                                                        "worlds",
                                                        StringArgumentType.greedyString()
                                                )
                                                .executes(context -> requestNamespaceRepair(
                                                        context.getSource(),
                                                        NamespacePolicy.ALL_ORPHANED_ITEMS,
                                                        NamespacePolicy.Mode.ORPHANED_ITEMS,
                                                        MaintenanceRegionScope.Mode.ONLY,
                                                        StringArgumentType.getString(
                                                                context,
                                                                "worlds"
                                                        )
                                                ))))
                                .then(Commands.literal("except")
                                        .then(Commands.argument(
                                                        "worlds",
                                                        StringArgumentType.greedyString()
                                                )
                                                .executes(context -> requestNamespaceRepair(
                                                        context.getSource(),
                                                        NamespacePolicy.ALL_ORPHANED_ITEMS,
                                                        NamespacePolicy.Mode.ORPHANED_ITEMS,
                                                        MaintenanceRegionScope.Mode.EXCEPT,
                                                        StringArgumentType.getString(
                                                                context,
                                                                "worlds"
                                                        )
                                                )))))
                        .then(Commands.literal("orphaned")
                                .then(Commands.argument("namespace", StringArgumentType.word())
                                        .executes(context -> requestNamespaceRepair(
                                         context.getSource(),
                                         StringArgumentType.getString(
                                                        context,
                                                        "namespace"
                                         ),
                                                 NamespacePolicy.Mode.ORPHANED_ONLY,
                                                 MaintenanceRegionScope.Mode.ALL,
                                                 null
                                        ))))
                        .then(Commands.literal("prepare-remove")
                                .then(Commands.argument("namespace", StringArgumentType.word())
                                        .executes(context -> requestNamespaceRepair(
                                                context.getSource(),
                                                StringArgumentType.getString(
                                                        context,
                                                        "namespace"
                                                 ),
                                                 NamespacePolicy.Mode.PREPARE_REMOVE,
                                                 MaintenanceRegionScope.Mode.ALL,
                                                 null
                                        ))))
                        .then(Commands.literal("rollback")
                                .executes(context -> requestRollback(context.getSource())))
                        .then(Commands.literal("confirm")
                                .then(Commands.argument("token", StringArgumentType.word())
                                        .executes(context -> confirm(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "token")
                                        ))))
                        .then(Commands.literal("cancel")
                                .executes(context -> cancel(context.getSource())))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))));
    }

    private int requestRepair(CommandSourceStack source) {
        if (!preflightSource(source)) {
            return 0;
        }
        try {
            Path iceJar = findApprovedIceAndFireJar();
            return createPending(
                    source,
                    MaintenanceRequest.Operation.REPAIR,
                    iceJar,
                    null,
                    null,
                    null,
                    null,
                    MaintenanceRegionScope.Mode.ALL,
                    null
            );
        } catch (IOException failure) {
            source.sendFailure(Component.literal(
                    "YuWorldRepair 维护预检失败，未停服、未修改世界: " + safeMessage(failure)
            ));
            return 0;
        }
    }

    private int requestNamespaceRepair(
            CommandSourceStack source,
            String suppliedNamespace,
            NamespacePolicy.Mode mode,
            MaintenanceRegionScope.Mode scopeMode,
            String scopeNames
    ) {
        if (!preflightSource(source)) {
            return 0;
        }
        String namespace = suppliedNamespace.toLowerCase(Locale.ROOT);
        if (!namespace.matches("[a-z0-9_.-]{1,64}")
                || namespace.equals("minecraft")
                || namespace.equals("neoforge")
                || namespace.equals("forge")) {
            source.sendFailure(Component.literal(
                    "命名空间无效，且禁止清理 minecraft/neoforge/forge"
            ));
            return 0;
        }
        try {
            RegistrySnapshot snapshot = RegistrySnapshotFactory.capture(source.getServer());
            return createPending(
                    source,
                    MaintenanceRequest.Operation.NAMESPACE_REPAIR,
                    null,
                    null,
                    namespace,
                    mode,
                    snapshot,
                    scopeMode,
                    scopeNames
            );
        } catch (IOException | IllegalArgumentException failure) {
            source.sendFailure(Component.literal(
                    "YuWorldRepair 命名空间维护预检失败；服务器与世界均未修改: "
                            + safeMessage(failure)
            ));
            return 0;
        }
    }

    private int requestRollback(CommandSourceStack source) {
        if (!preflightSource(source)) {
            return 0;
        }
        try {
            MaintenanceResult candidate = MaintenanceHistory.latestRollbackCandidate(gameDirectory)
                    .orElseThrow(() -> new IOException("没有可回滚的已验证备份"));
            return createPending(
                    source,
                    MaintenanceRequest.Operation.ROLLBACK,
                    null,
                    candidate.jobPath(),
                    null,
                    null,
                    null,
                    MaintenanceRegionScope.Mode.ALL,
                    null
            );
        } catch (IOException failure) {
            source.sendFailure(Component.literal("YuWorldRepair 回滚预检失败: " + safeMessage(failure)));
            return 0;
        }
    }

    private boolean preflightSource(CommandSourceStack source) {
        if (!config.enabled()) {
            source.sendFailure(Component.literal(
                    "维护功能已在 config/yuworldrepair-maintenance.json 中禁用"
            ));
            return false;
        }
        if (!source.getServer().isDedicatedServer()) {
            source.sendFailure(Component.literal("维护 worker 仅支持独立服务端"));
            return false;
        }
        if (pending != null) {
            source.sendFailure(Component.literal("已有待确认或倒计时中的维护请求"));
            return false;
        }
        if ((config.restartStrategy() == MaintenanceRequest.RestartStrategy.PANEL
                || config.restartStrategy() == MaintenanceRequest.RestartStrategy.SUPERVISOR)
                && !hasSupervisorEnvironment()) {
            source.sendFailure(Component.literal(
                    "Panel maintenance is unavailable because the server was not started through "
                            + "the executable YuWorldRepair maintenance JAR"
            ));
            return false;
        }
        return true;
    }

    private int createPending(
            CommandSourceStack source,
            MaintenanceRequest.Operation operation,
            Path iceJar,
            String jobPath,
            String namespace,
            NamespacePolicy.Mode namespaceMode,
            RegistrySnapshot registrySnapshot,
            MaintenanceRegionScope.Mode scopeMode,
            String scopeNames
    ) throws IOException {
        MinecraftServer server = source.getServer();
        Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        List<Path> loadedWorldRoots = MaintenanceWorldRoots.capture(
                gameDirectory,
                world,
                server.getAllLevels()
        );
        world = loadedWorldRoots.getFirst();
        MaintenanceRegionScope.Selection regionScope = MaintenanceRegionScope.resolve(
                gameDirectory,
                loadedWorldRoots,
                scopeMode,
                scopeNames
        );
        Path jobs = MaintenanceHistory.jobsRoot(gameDirectory);
        Path workerJar = findMaintenanceJar();
        requireOutsideWorld(loadedWorldRoots, jobs);
        String requestId = UUID.randomUUID().toString();
        String secret = randomHex(32);
        String confirmToken = randomHex(16);
        Instant created = Instant.now();
        Path requestsRoot = MaintenanceHistory.requestsRoot(gameDirectory);
        WorldAccessPolicy.rejectLinkChain(requestsRoot);
        Files.createDirectories(requestsRoot);
        WorldAccessPolicy.rejectLinkChain(requestsRoot);
        Path requestDirectory = requestsRoot.resolve(requestId);
        Files.createDirectory(requestDirectory);
        Path registrySnapshotPath = null;
        String registrySnapshotSha256 = null;
        if (registrySnapshot != null) {
            registrySnapshotPath = requestDirectory.resolve(RegistrySnapshot.FILE_NAME);
            RegistrySnapshot.write(registrySnapshotPath, registrySnapshot);
            registrySnapshotSha256 = IoUtil.sha256(registrySnapshotPath);
        }
        MaintenanceRequest request = new MaintenanceRequest(
                MaintenanceRequest.SCHEMA_VERSION,
                requestId,
                IoUtil.sha256(secret.getBytes(StandardCharsets.UTF_8)),
                "0".repeat(64),
                created.toString(),
                created.plusSeconds(1_800).toString(),
                ProcessHandle.current().pid(),
                operation,
                gameDirectory.toString(),
                world.toString(),
                loadedWorldRoots.stream().map(Path::toString).toList(),
                regionScope.excludedRegionRoots().stream().map(Path::toString).toList(),
                config.effectiveScanWorkers(),
                jobs.toString(),
                iceJar == null ? null : iceJar.toString(),
                jobPath,
                namespace,
                namespaceMode,
                registrySnapshotPath == null ? null : registrySnapshotPath.toString(),
                registrySnapshotSha256,
                server.getServerVersion(),
                "21.1.x",
                server.getServerModName(),
                config.restartStrategy(),
                config.restartCommand(),
                MaintenanceRequest.State.REQUESTED,
                "Awaiting explicit in-game confirmation"
        );
        request = request.withBindingHmac(request.computeBindingHmac(secret));
        request.validate();
        Path requestPath = requestDirectory.resolve(MaintenanceFiles.REQUEST_FILE);
        MaintenanceFiles.writeRequest(requestPath, request);
        pending = new Pending(
                request,
                requestPath,
                workerJar,
                secret,
                IoUtil.sha256(confirmToken.getBytes(StandardCharsets.UTF_8)),
                sourceIdentity(source),
                System.currentTimeMillis() + CONFIRM_TTL_MILLIS,
                0,
                Integer.MIN_VALUE
        );
        String confirmCommand = "/yuworldrepair repair confirm " + confirmToken;
        Component clickable = Component.literal("[点击确认停服维护]")
                .withStyle(style -> style
                        .withColor(ChatFormatting.RED)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(
                                ClickEvent.Action.RUN_COMMAND,
                                confirmCommand
                        )));
        source.sendSuccess(() -> Component.literal(
                "已创建 " + operation + " 请求。确认后将保存世界、断开玩家并停服；"
                        + "已绑定 " + loadedWorldRoots.size() + " 个已加载世界目录；"
                        + (regionScope.excludedLabels().isEmpty()
                        ? "全部世界区块都会扫描；"
                        : "只跳过这些世界的区块文件 " + regionScope.excludedLabels()
                        + "，其 playerdata/SavedData 仍扫描；")
                        + "扫描线程=" + config.effectiveScanWorkers() + "；"
                        + "所有写入只会在 session.lock 释放后进行。"
        ), false);
        source.sendSuccess(() -> clickable, false);
        source.sendSuccess(() -> Component.literal(
                "也可手动输入: " + confirmCommand + "（2 分钟内一次有效）"
        ), false);
        return 1;
    }

    private int confirm(CommandSourceStack source, String token) {
        Pending active = pending;
        if (active == null) {
            source.sendFailure(Component.literal("没有待确认的维护请求"));
            return 0;
        }
        if (System.currentTimeMillis() > active.confirmExpiresMillis()
                || !sourceIdentity(source).equals(active.initiator())
                || !constantTimeTokenEquals(active.confirmTokenSha256(), token)) {
            source.sendFailure(Component.literal("确认令牌无效、已过期或不属于当前发起者"));
            return 0;
        }
        try {
            MaintenanceRequest countdown = active.request().withState(
                    MaintenanceRequest.State.COUNTDOWN,
                    "Confirmed; shutdown countdown is active"
            );
            MaintenanceFiles.writeRequest(active.requestPath(), countdown);
            long deadline = System.currentTimeMillis()
                    + TimeUnit.SECONDS.toMillis(config.countdownSeconds());
            pending = new Pending(
                    countdown,
                    active.requestPath(),
                    active.workerJar(),
                    active.authorizationSecret(),
                    active.confirmTokenSha256(),
                    active.initiator(),
                    active.confirmExpiresMillis(),
                    deadline,
                    Integer.MIN_VALUE
            );
            broadcast(
                    source.getServer(),
                    Component.literal(
                            "[YuWorldRepair] 已确认维护；" + config.countdownSeconds()
                                    + " 秒后保存世界并停服。"
                    ).withStyle(ChatFormatting.RED)
            );
            return 1;
        } catch (IOException failure) {
            source.sendFailure(Component.literal("无法持久化确认，未开始停服: " + safeMessage(failure)));
            return 0;
        }
    }

    private int cancel(CommandSourceStack source) {
        Pending active = pending;
        if (active == null) {
            source.sendFailure(Component.literal("没有可取消的维护请求"));
            return 0;
        }
        pending = null;
        try {
            MaintenanceRequest cancelled = active.request().withState(
                    MaintenanceRequest.State.COMPLETED,
                    "Cancelled before worker handoff; world was not modified"
            );
            MaintenanceFiles.writeRequest(active.requestPath(), cancelled);
            MaintenanceFiles.writeResult(
                    active.requestPath().resolveSibling(MaintenanceFiles.RESULT_FILE),
                    MaintenanceResult.of(
                            cancelled,
                            false,
                            MaintenanceRequest.State.COMPLETED,
                            "管理员在停服交接前取消；世界未修改",
                            cancelled.jobPath(),
                            false,
                            java.util.Map.of(),
                            false
                    )
            );
            source.sendSuccess(() -> Component.literal("维护请求已取消；世界未修改"), true);
            return 1;
        } catch (IOException failure) {
            source.sendFailure(Component.literal("取消状态写入失败: " + safeMessage(failure)));
            return 0;
        }
    }

    private int status(CommandSourceStack source) {
        Pending active = pending;
        if (active != null) {
            source.sendSuccess(() -> Component.literal(
                    "当前请求=" + active.request().requestId()
                            + ", operation=" + active.request().operation()
                            + ", state=" + active.request().state()
            ), false);
            return 1;
        }
        try {
            Optional<MaintenanceResult> latest = MaintenanceHistory.latestResult(gameDirectory);
            if (latest.isEmpty()) {
                source.sendSuccess(() -> Component.literal("没有维护历史"), false);
                return 1;
            }
            MaintenanceResult result = latest.get();
            source.sendSuccess(() -> Component.literal(
                    "最近请求=" + result.requestId()
                            + ", state=" + result.state()
                            + ", success=" + result.success()
                            + ", rollbackAvailable=" + result.rollbackAvailable()
                            + ", detail=" + result.detail()
                            + formatItemMetricsSuffix(result.metrics())
            ), false);
            return 1;
        } catch (IOException failure) {
            source.sendFailure(Component.literal("读取维护状态失败: " + safeMessage(failure)));
            return 0;
        }
    }

    private void beginHandoff(MinecraftServer server, Pending active) {
        Path resultPath = active.requestPath().resolveSibling(MaintenanceFiles.RESULT_FILE);
        try {
            boolean saved = server.saveEverything(false, true, true);
            if (!saved) {
                throw new IOException("Minecraft reported that the forced world save failed");
            }
            MaintenanceRequest handoff = active.request().withState(
                    MaintenanceRequest.State.HANDOFF,
                    "World saved; worker launched and waiting for server process exit"
            );
            MaintenanceFiles.writeRequest(active.requestPath(), handoff);
            startWorker(active.withRequest(handoff));
            broadcast(
                    server,
                    Component.literal(
                            "[YuWorldRepair] 世界已保存。服务端即将退出，离线 worker "
                                    + "将在锁释放后执行 " + handoff.operation() + "。"
                    ).withStyle(ChatFormatting.RED)
            );
            Component kick = Component.literal(
                    "YuWorldRepair 正在进行已授权的离线世界维护，请等待服务器重启。"
            );
            List<ServerPlayer> players = List.copyOf(server.getPlayerList().getPlayers());
            for (ServerPlayer player : players) {
                player.connection.disconnect(kick);
            }
            server.halt(false);
        } catch (Exception failure) {
            YuWorldRepair.LOGGER.error(
                    "Maintenance handoff failed before server shutdown; world remains live and untouched",
                    failure
            );
            try {
                MaintenanceRequest safe = active.request().withState(
                        MaintenanceRequest.State.COMPLETED,
                        "Worker handoff failed before shutdown; no offline write began"
                );
                MaintenanceFiles.writeRequest(active.requestPath(), safe);
                MaintenanceFiles.writeResult(
                        resultPath,
                        MaintenanceResult.of(
                                safe,
                                false,
                                MaintenanceRequest.State.COMPLETED,
                                "停服交接失败；服务器保持运行，世界未由 worker 修改: "
                                        + safeMessage(failure),
                                safe.jobPath(),
                                false,
                                java.util.Map.of(),
                                false
                        )
                );
            } catch (IOException reportFailure) {
                failure.addSuppressed(reportFailure);
            }
            server.sendSystemMessage(Component.literal(
                    "[YuWorldRepair] 维护交接失败，已取消停服；世界未由 worker 修改: "
                            + safeMessage(failure)
            ));
        }
    }

    private void startWorker(Pending active) throws IOException {
        Path java = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        ).toAbsolutePath().normalize();
        if (!Files.isRegularFile(java, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cannot locate Java worker executable: " + java);
        }
        Path workerJar = extractWorker(active);
        Path workerLog = active.requestPath().resolveSibling("worker.log");
        ProcessBuilder builder = new ProcessBuilder(
                java.toString(),
                "-Xms64m",
                "-Xmx768m",
                "-Dfile.encoding=UTF-8",
                "-cp",
                workerJar.toString(),
                WORKER_CLASS,
                active.requestPath().toString()
        );
        builder.directory(gameDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(workerLog.toFile()));
        builder.environment().put(
                WORKER_AUTH_ENV,
                active.authorizationSecret()
        );
        Process worker = builder.start();
        try {
            awaitWorkerReady(active, worker);
        } catch (IOException failure) {
            worker.destroy();
            try {
                if (!worker.waitFor(2, TimeUnit.SECONDS)) {
                    worker.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure.addSuppressed(interrupted);
                worker.destroyForcibly();
            }
            throw failure;
        }
        YuWorldRepair.LOGGER.info(
                "Maintenance worker ready pid={} request={} operation={} restartStrategy={}",
                worker.pid(),
                active.request().requestId(),
                active.request().operation(),
                active.request().restartStrategy()
        );
    }

    private static void awaitWorkerReady(Pending active, Process worker) throws IOException {
        Path handoffPath = active.requestPath().resolveSibling(MaintenanceFiles.HANDOFF_FILE);
        Path resultPath = active.requestPath().resolveSibling(MaintenanceFiles.RESULT_FILE);
        long deadline = System.currentTimeMillis() + WORKER_READY_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(handoffPath, LinkOption.NOFOLLOW_LINKS)) {
                MaintenanceHandoff handoff = MaintenanceFiles.readHandoff(handoffPath);
                String expectedSupervisor = System.getenv(
                        MaintenanceHandoff.SUPERVISOR_ID_ENV
                );
                boolean supervisorMismatch =
                        (active.request().restartStrategy()
                                == MaintenanceRequest.RestartStrategy.PANEL
                                || active.request().restartStrategy()
                                == MaintenanceRequest.RestartStrategy.SUPERVISOR)
                                && !java.util.Objects.equals(
                                handoff.supervisorId(),
                                expectedSupervisor
                        );
                if (!handoff.requestId().equals(active.request().requestId())
                        || handoff.serverPid() != active.request().parentPid()
                        || handoff.workerPid() != worker.pid()
                        || handoff.state() != MaintenanceRequest.State.WAITING_FOR_STOP
                        || supervisorMismatch) {
                    throw new IOException("Maintenance worker readiness handoff is inconsistent");
                }
                return;
            }
            if (Files.isRegularFile(resultPath, LinkOption.NOFOLLOW_LINKS)) {
                MaintenanceResult result = MaintenanceFiles.readResult(resultPath);
                throw new IOException(
                        "Maintenance worker failed before shutdown readiness: " + result.detail()
                );
            }
            if (!worker.isAlive()) {
                throw new IOException("Maintenance worker exited before shutdown readiness");
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(
                        "Interrupted while waiting for maintenance worker readiness",
                        interrupted
                );
            }
        }
        throw new IOException("Maintenance worker did not acknowledge shutdown readiness");
    }

    private static boolean hasSupervisorEnvironment() {
        String supervisorId = System.getenv(MaintenanceHandoff.SUPERVISOR_ID_ENV);
        return supervisorId != null && supervisorId.matches("[0-9a-f]{64}");
    }

    private static Path extractWorker(Pending active) throws IOException {
        Path target = active.requestPath().resolveSibling("worker.jar");
        Path temporary = active.requestPath().resolveSibling("worker.jar.tmp");
        WorldAccessPolicy.rejectLinkChain(target.getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Worker extraction target already exists");
        }
        try (JarFile mod = new JarFile(active.workerJar().toFile(), false)) {
            var entry = mod.getJarEntry(WORKER_RESOURCE_ENTRY);
            if (entry == null
                    || entry.isDirectory()
                    || entry.getSize() < 1
                    || entry.getSize() > MAX_EMBEDDED_WORKER_BYTES) {
                throw new IOException("Embedded maintenance worker is missing or oversized");
            }
            MessageDigest digest = sha256Digest();
            try (var input = new DigestInputStream(mod.getInputStream(entry), digest)) {
                Files.copy(input, temporary);
            }
            if (Files.size(temporary) != entry.getSize()) {
                Files.deleteIfExists(temporary);
                throw new IOException("Extracted worker size does not match Mod resource");
            }
            String expectedHash = HexFormat.of().formatHex(digest.digest());
            if (!expectedHash.equals(IoUtil.sha256(temporary))) {
                Files.deleteIfExists(temporary);
                throw new IOException("Extracted worker hash does not match Mod resource");
            }
            IoUtil.moveAtomic(temporary, target);
            return target;
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
    }

    private Path findApprovedIceAndFireJar() throws IOException {
        Path mods = gameDirectory.resolve("mods");
        WorldAccessPolicy.rejectLinkChain(mods);
        if (!Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("mods directory does not exist");
        }
        ArrayList<Path> approved = new ArrayList<>();
        try (Stream<Path> entries = Files.list(mods)) {
            for (Path candidate : entries
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).contains("iceandfire"))
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .toList()) {
                if (LegacyChickenDataAdapter.VERIFIED_ICE_AND_FIRE_SHA256.equals(
                        IoUtil.sha256(candidate)
                )) {
                    approved.add(candidate.toRealPath());
                }
            }
        }
        if (approved.size() != 1) {
            throw new IOException(
                    "需要且只能有一个已核验的 Ice and Fire 2.0-beta.17 JAR；找到 "
                            + approved.size() + " 个"
            );
        }
        return approved.getFirst();
    }

    private Path findMaintenanceJar() throws IOException {
        try {
            URI location = MaintenanceController.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI();
            Path candidate = Path.of(location).toAbsolutePath().normalize();
            if (isMaintenanceJar(candidate)) {
                return candidate.toRealPath();
            }
        } catch (Exception ignored) {
            // Production fallback below resolves the actual file in mods.
        }
        Path mods = gameDirectory.resolve("mods");
        ArrayList<Path> candidates = new ArrayList<>();
        try (Stream<Path> entries = Files.list(mods)) {
            for (Path candidate : entries
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .toList()) {
                if (isMaintenanceJar(candidate)) {
                    candidates.add(candidate.toRealPath());
                }
            }
        }
        if (candidates.size() != 1) {
            throw new IOException(
                    "无法唯一定位包含 worker 的 YuWorldRepair 维护版 JAR；找到 "
                            + candidates.size() + " 个"
            );
        }
        return candidates.getFirst();
    }

    private static boolean isMaintenanceJar(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)) {
            return false;
        }
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            return jar.getJarEntry(MAINTENANCE_CLASS_ENTRY) != null
                    && jar.getJarEntry(WORKER_RESOURCE_ENTRY) != null;
        } catch (IOException invalid) {
            return false;
        }
    }

    private static void requireOutsideWorld(List<Path> worlds, Path jobs) throws IOException {
        for (Path world : worlds) {
            if (jobs.startsWith(world)) {
                throw new IOException("维护 job/备份目录必须位于世界目录之外");
            }
        }
    }

    private static void broadcast(MinecraftServer server, Component message) {
        server.sendSystemMessage(message);
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    private static String sourceIdentity(CommandSourceStack source) {
        if (source.getEntity() != null) {
            return source.getEntity().getUUID().toString();
        }
        return "console:" + source.getTextName();
    }

    private static boolean constantTimeTokenEquals(String expectedSha256, String token) {
        try {
            byte[] expected = HexFormat.of().parseHex(expectedSha256);
            byte[] actual = HexFormat.of().parseHex(
                    IoUtil.sha256(token.getBytes(StandardCharsets.UTF_8))
            );
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        String oneLine = message.replace('\r', ' ').replace('\n', ' ');
        return oneLine.length() <= 1_024 ? oneLine : oneLine.substring(0, 1_024);
    }

    private static String formatItemMetricsSuffix(java.util.Map<String, ?> metrics) {
        String formatted = formatItemMetrics(metrics);
        return formatted.isEmpty() ? "" : ", itemStats=" + formatted;
    }

    private static String formatItemMetrics(java.util.Map<String, ?> metrics) {
        if (metrics == null || !metrics.containsKey("byNamespace")) {
            return "";
        }
        if (metrics.containsKey("detectedByNamespace")) {
            String value = "detectedEntries=" + metrics.get("detectedTargets")
                    + ", detectedByMod=" + metrics.get("detectedByNamespace")
                    + ", removedEntries=" + metrics.get("removedTargets")
                    + ", removedByMod=" + metrics.get("byNamespace")
                    + ", removedByStore=" + metrics.get("byStore")
                    + ", removedAmounts=" + metrics.get("amountByNamespace")
                    + ", deferredEntries=" + metrics.get("deferredTargets")
                    + ", deferredByMod=" + metrics.get("deferredByNamespace")
                    + ", deferredByStore=" + metrics.get("deferredByStore")
                    + ", deferredAmounts=" + metrics.get("deferredAmountByNamespace")
                    + ", cleanupComplete=" + metrics.get("cleanupComplete")
                    + ", regionScopeComplete=" + metrics.get("regionScopeComplete")
                    + ", excludedWorldRegions=" + metrics.get("regionExcludedWorlds")
                    + ", scanWorkers=" + metrics.get("scanWorkers");
            return value.length() <= 2_048 ? value : value.substring(0, 2_048);
        }
        String value = "entries=" + metrics.get("targets")
                + ", byMod=" + metrics.get("byNamespace")
                + ", byStore=" + metrics.get("byStore")
                + ", amounts=" + metrics.get("amountByNamespace")
                + (metrics.containsKey("regionScopeComplete")
                ? ", regionScopeComplete=" + metrics.get("regionScopeComplete")
                 + ", excludedWorldRegions=" + metrics.get("regionExcludedWorlds")
                 + ", qioDeferred=" + metrics.get("deferredTargets")
                 + ", deferredByMod=" + metrics.get("deferredByNamespace")
                 + ", deferredByStore=" + metrics.get("deferredByStore")
                 + ", deferredAmounts=" + metrics.get("deferredAmountByNamespace")
                 + ", scanWorkers=" + metrics.get("scanWorkers")
                : "");
        return value.length() <= 2_048 ? value : value.substring(0, 2_048);
    }

    private record Pending(
            MaintenanceRequest request,
            Path requestPath,
            Path workerJar,
            String authorizationSecret,
            String confirmTokenSha256,
            String initiator,
            long confirmExpiresMillis,
            long deadlineMillis,
            int lastAnnounced
    ) {
        Pending withLastAnnounced(int value) {
            return new Pending(
                    request,
                    requestPath,
                    workerJar,
                    authorizationSecret,
                    confirmTokenSha256,
                    initiator,
                    confirmExpiresMillis,
                    deadlineMillis,
                    value
            );
        }

        Pending withRequest(MaintenanceRequest next) {
            return new Pending(
                    next,
                    requestPath,
                    workerJar,
                    authorizationSecret,
                    confirmTokenSha256,
                    initiator,
                    confirmExpiresMillis,
                    deadlineMillis,
                    lastAnnounced
            );
        }
    }
}
