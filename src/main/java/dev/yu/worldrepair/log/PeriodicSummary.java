package dev.yu.worldrepair.log;

import dev.yu.worldrepair.config.RuntimeConfig;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class PeriodicSummary implements AutoCloseable {
    private final GuardRuntime runtime;
    private final Supplier<RuntimeConfig> configSupplier;
    private final Logger logger;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public PeriodicSummary(GuardRuntime runtime, Supplier<RuntimeConfig> configSupplier, Logger logger) {
        this.runtime = Objects.requireNonNull(runtime);
        this.configSupplier = Objects.requireNonNull(configSupplier);
        this.logger = Objects.requireNonNull(logger);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "YuWorldRepair-Summary");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        };
        this.executor = Executors.newSingleThreadScheduledExecutor(factory);
    }

    public void start() {
        executor.schedule(this::runAndReschedule, intervalSeconds(), TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }

    private void runAndReschedule() {
        if (closed.get()) {
            return;
        }
        try {
            long windowSeconds = intervalSeconds();
            for (GuardSummary summary : runtime.drainSummaries()) {
                var signature = summary.signature();
                logger.warn(
                        "[YuWorldRepair] Suppressed {} duplicate errors in {}s; signature={}/{}, id={}, firstCaller={}, mode=guard",
                        summary.suppressed(),
                        windowSeconds,
                        signature.domain(),
                        signature.shortHash(),
                        signature.registryId(),
                        signature.callerFingerprint()
                );
            }
        } catch (Throwable failure) {
            logger.error("[YuWorldRepair] Periodic summary failed; guard remains fail-open", failure);
        } finally {
            if (!closed.get()) {
                executor.schedule(this::runAndReschedule, intervalSeconds(), TimeUnit.SECONDS);
            }
        }
    }

    private long intervalSeconds() {
        return Math.max(1L, Duration.ofNanos(configSupplier.get().summaryIntervalNanos()).toSeconds());
    }
}
