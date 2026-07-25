package dev.yu.worldrepair.log;

import dev.yu.worldrepair.config.RuntimeConfig;
import dev.yu.worldrepair.diagnostics.CallSiteFingerprint;
import dev.yu.worldrepair.guard.BoundedSignatureTable;
import dev.yu.worldrepair.guard.ErrorSignature;
import dev.yu.worldrepair.guard.GuardDecision;
import dev.yu.worldrepair.guard.LogicalSide;
import dev.yu.worldrepair.guard.ResourceIdExtractor;
import dev.yu.worldrepair.guard.SignatureEntry;
import dev.yu.worldrepair.guard.SignatureHasher;
import dev.yu.worldrepair.metrics.GuardMetrics;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class GuardRuntime {
    private final Supplier<RuntimeConfig> configSupplier;
    private final GuardMetrics metrics;
    private final Clock clock;
    private final LongSupplier nanoClock;
    private final String environmentFingerprint;
    private final long environmentHash;
    private volatile BoundedSignatureTable table;
    private volatile int tableCapacity;
    private volatile long tableTtlNanos;

    public GuardRuntime(
            Supplier<RuntimeConfig> configSupplier,
            GuardMetrics metrics,
            String environmentFingerprint
    ) {
        this(configSupplier, metrics, environmentFingerprint, Clock.systemUTC(), System::nanoTime);
    }

    GuardRuntime(
            Supplier<RuntimeConfig> configSupplier,
            GuardMetrics metrics,
            String environmentFingerprint,
            Clock clock,
            LongSupplier nanoClock
    ) {
        this.configSupplier = Objects.requireNonNull(configSupplier);
        this.metrics = Objects.requireNonNull(metrics);
        this.environmentFingerprint = Objects.requireNonNull(environmentFingerprint);
        this.environmentHash = SignatureHasher.hashText(environmentFingerprint);
        this.clock = Objects.requireNonNull(clock);
        this.nanoClock = Objects.requireNonNull(nanoClock);
        RuntimeConfig config = configSupplier.get();
        this.table = new BoundedSignatureTable(config.maxSignatures(), config.signatureTtlNanos());
        this.tableCapacity = config.maxSignatures();
        this.tableTtlNanos = config.signatureTtlNanos();
    }

    public GuardDecision evaluate(
            String loggerName,
            String messageTemplate,
            Object[] parameters,
            LogicalSide side
    ) {
        KnownErrorRule rule = KnownErrorRule.match(loggerName, messageTemplate);
        if (rule == null) {
            return GuardDecision.PASS_UNRECOGNIZED;
        }
        return evaluate(rule, messageTemplate, parameters, side);
    }

    public GuardDecision evaluate(
            KnownErrorRule rule,
            String messageFormat,
            Object[] parameters,
            LogicalSide side
    ) {
        return evaluateArgument(rule, firstBoundedArgument(parameters, messageFormat), side);
    }

    public GuardDecision evaluate(
            KnownErrorRule rule,
            String messageFormat,
            Object parameter,
            LogicalSide side
    ) {
        return evaluateArgument(rule, boundedArgument(parameter, messageFormat), side);
    }

    private GuardDecision evaluateArgument(
            KnownErrorRule rule,
            CharSequence argument,
            LogicalSide side
    ) {
        RuntimeConfig config = configSupplier.get();
        if (config.maxSignatures() != tableCapacity || config.signatureTtlNanos() != tableTtlNanos) {
            ensureTableShape(config);
        }
        long now = nanoClock.getAsLong();
        long bounds = ResourceIdExtractor.findBounds(argument);
        long primary = SignatureHasher.primary(rule.ruleId(), side, argument, bounds, environmentHash);
        long secondary = SignatureHasher.secondary(rule.ruleId(), side, argument, bounds, environmentHash);

        BoundedSignatureTable currentTable = table;
        SignatureEntry entry = currentTable.find(primary, secondary, now);
        if (entry == null) {
            String id = ResourceIdExtractor.materialize(argument, bounds);
            String caller = config.sampleStackTraces() == 0
                    ? "disabled"
                    : CallSiteFingerprint.capture(config.maxStackFrames());
            ErrorSignature signature = new ErrorSignature(
                    side,
                    rule.domain(),
                    rule.loggerName(),
                    rule.template(),
                    id,
                    caller,
                    environmentFingerprint,
                    SignatureHasher.shortHex(primary, secondary),
                    Instant.now(clock)
            );
            SignatureEntry candidate = new SignatureEntry(
                    primary,
                    secondary,
                    signature,
                    config.burstPerSignature(),
                    config.windowNanos(),
                    now
            );
            entry = currentTable.insertOrGet(candidate, now);
        }

        GuardDecision decision = entry.evaluate(config.mode(), now);
        metrics.record(decision);
        return decision;
    }

    public void recordFailOpen() {
        metrics.recordFailOpen();
    }

    public List<SignatureEntry> signatures() {
        List<SignatureEntry> entries = new ArrayList<>(table.snapshot());
        entries.sort(Comparator.comparingLong(SignatureEntry::observed).reversed());
        return List.copyOf(entries);
    }

    public List<GuardSummary> drainSummaries() {
        List<GuardSummary> summaries = new ArrayList<>();
        for (SignatureEntry entry : table.snapshot()) {
            long suppressed = entry.drainSuppressedSinceSummary();
            if (suppressed > 0) {
                summaries.add(new GuardSummary(entry.signature(), suppressed));
            }
        }
        summaries.sort(Comparator.comparingLong(GuardSummary::suppressed).reversed());
        return List.copyOf(summaries);
    }

    public GuardMetrics metrics() {
        return metrics;
    }

    public int signatureCount() {
        return table.size();
    }

    public int signatureCapacity() {
        return table.capacity();
    }

    public long signatureEvictions() {
        return table.evictions();
    }

    private synchronized void ensureTableShape(RuntimeConfig config) {
        if (config.maxSignatures() == tableCapacity && config.signatureTtlNanos() == tableTtlNanos) {
            return;
        }
        table = new BoundedSignatureTable(config.maxSignatures(), config.signatureTtlNanos());
        tableCapacity = config.maxSignatures();
        tableTtlNanos = config.signatureTtlNanos();
    }

    private static CharSequence firstBoundedArgument(Object[] parameters, String renderedFallback) {
        if (parameters == null || parameters.length == 0 || parameters[0] == null) {
            return renderedFallback;
        }
        return boundedArgument(parameters[0], renderedFallback);
    }

    private static CharSequence boundedArgument(Object parameter, String renderedFallback) {
        if (parameter == null) {
            return renderedFallback;
        }
        if (parameter instanceof CharSequence sequence) {
            return sequence;
        }
        String className = parameter.getClass().getName();
        if ("net.minecraft.resources.ResourceLocation".equals(className)) {
            return parameter.toString();
        }
        return renderedFallback;
    }
}
