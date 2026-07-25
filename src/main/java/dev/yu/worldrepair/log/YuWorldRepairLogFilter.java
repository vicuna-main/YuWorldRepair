package dev.yu.worldrepair.log;

import dev.yu.worldrepair.guard.GuardDecision;
import dev.yu.worldrepair.guard.LogicalSide;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.status.StatusLogger;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class YuWorldRepairLogFilter extends AbstractFilter {
    private final GuardRuntime runtime;
    private final LogicalSide physicalSide;
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private volatile LoggerContext installedContext;
    private volatile List<LoggerConfig> installedLoggerConfigs = List.of();

    public YuWorldRepairLogFilter(GuardRuntime runtime, LogicalSide physicalSide) {
        this.runtime = Objects.requireNonNull(runtime);
        this.physicalSide = Objects.requireNonNull(physicalSide);
    }

    @Override
    public Result filter(LogEvent event) {
        if (event == null || event.getLevel() != Level.ERROR) {
            return Result.NEUTRAL;
        }
        Message message = event.getMessage();
        if (message == null) {
            return Result.NEUTRAL;
        }
        String template = message.getFormat();
        KnownErrorRule rule = KnownErrorRule.match(event.getLoggerName(), template);
        if (rule == null) {
            return Result.NEUTRAL;
        }
        return decide(rule, template, message.getParameters(), resolveSide(event.getThreadName()));
    }

    @Override
    public Result filter(
            Logger logger,
            Level level,
            Marker marker,
            String message,
            Object parameter
    ) {
        if (level != Level.ERROR) {
            return Result.NEUTRAL;
        }
        KnownErrorRule rule = KnownErrorRule.match(logger.getName(), message);
        if (rule == null) {
            return Result.NEUTRAL;
        }
        try {
            GuardDecision decision = runtime.evaluate(
                    rule,
                    message,
                    parameter,
                    resolveSide(Thread.currentThread().getName())
            );
            return result(decision);
        } catch (Throwable failure) {
            return failOpen(failure);
        }
    }

    @Override
    public Result filter(
            Logger logger,
            Level level,
            Marker marker,
            String message,
            Object... parameters
    ) {
        if (level != Level.ERROR) {
            return Result.NEUTRAL;
        }
        KnownErrorRule rule = KnownErrorRule.match(logger.getName(), message);
        if (rule == null) {
            return Result.NEUTRAL;
        }
        return decide(
                rule,
                message,
                parameters,
                resolveSide(Thread.currentThread().getName())
        );
    }

    @Override
    public Result filter(
            Logger logger,
            Level level,
            Marker marker,
            Message message,
            Throwable throwable
    ) {
        if (level != Level.ERROR || message == null) {
            return Result.NEUTRAL;
        }
        String format = message.getFormat();
        KnownErrorRule rule = KnownErrorRule.match(logger.getName(), format);
        if (rule == null) {
            return Result.NEUTRAL;
        }
        return decide(
                rule,
                format,
                message.getParameters(),
                resolveSide(Thread.currentThread().getName())
        );
    }

    public synchronized boolean install() {
        if (installedContext != null) {
            return true;
        }
        if (!(LogManager.getContext(false) instanceof LoggerContext context)) {
            return false;
        }
        Configuration configuration = context.getConfiguration();
        start();
        List<LoggerConfig> loggerConfigs = new ArrayList<>(KnownErrorRule.values().length);
        try {
            for (KnownErrorRule rule : KnownErrorRule.values()) {
                Logger logger = context.getLogger(rule.loggerName());
                logger.addFilter(this);
                LoggerConfig loggerConfig = configuration.getLoggerConfig(rule.loggerName());
                if (!loggerConfigs.contains(loggerConfig)) {
                    loggerConfigs.add(loggerConfig);
                }
            }
            context.updateLoggers();
            installedLoggerConfigs = List.copyOf(loggerConfigs);
            installedContext = context;
            return true;
        } catch (Throwable failure) {
            for (LoggerConfig loggerConfig : loggerConfigs) {
                loggerConfig.removeFilter(this);
            }
            context.updateLoggers();
            stop();
            installedLoggerConfigs = List.of();
            return false;
        }
    }

    public synchronized void uninstall() {
        LoggerContext context = installedContext;
        if (context == null) {
            return;
        }
        for (LoggerConfig loggerConfig : installedLoggerConfigs) {
            loggerConfig.removeFilter(this);
        }
        context.updateLoggers();
        stop();
        installedLoggerConfigs = List.of();
        installedContext = null;
    }

    public boolean isInstalled() {
        return installedContext != null && isStarted();
    }

    private Result decide(
            KnownErrorRule rule,
            String message,
            Object[] parameters,
            LogicalSide side
    ) {
        try {
            return result(runtime.evaluate(rule, message, parameters, side));
        } catch (Throwable failure) {
            return failOpen(failure);
        }
    }

    private static Result result(GuardDecision decision) {
        return decision == GuardDecision.SUPPRESS_DUPLICATE ? Result.DENY : Result.NEUTRAL;
    }

    private Result failOpen(Throwable failure) {
        runtime.recordFailOpen();
        if (failureReported.compareAndSet(false, true)) {
            StatusLogger.getLogger().error(
                    "YuWorldRepair filter failed open; target logs will pass unchanged",
                    failure
            );
        }
        return Result.NEUTRAL;
    }

    private LogicalSide resolveSide(String threadName) {
        if (threadName != null && (
                threadName.equals("Server thread")
                        || threadName.startsWith("Server-")
                        || threadName.contains("Netty Server")
        )) {
            return LogicalSide.SERVER;
        }
        return physicalSide;
    }
}
