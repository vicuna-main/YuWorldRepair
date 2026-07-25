package dev.yu.worldrepair.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.yu.worldrepair.config.YuWorldRepairConfig;
import dev.yu.worldrepair.diagnostics.DiagnosticReport;
import dev.yu.worldrepair.guard.ErrorDomain;
import dev.yu.worldrepair.guard.SignatureEntry;
import dev.yu.worldrepair.integration.ae2.Ae2Compatibility;
import dev.yu.worldrepair.integration.ae2.Ae2ReportAdapter;
import dev.yu.worldrepair.log.GuardRuntime;
import dev.yu.worldrepair.log.KnownErrorRule;
import dev.yu.worldrepair.log.YuWorldRepairLogFilter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.Locale;

public final class YuWorldRepairCommands {
    private final YuWorldRepairConfig config;
    private final GuardRuntime runtime;
    private final YuWorldRepairLogFilter filter;
    private final Path gameDirectory;

    public YuWorldRepairCommands(
            YuWorldRepairConfig config,
            GuardRuntime runtime,
            YuWorldRepairLogFilter filter,
            Path gameDirectory
    ) {
        this.config = config;
        this.runtime = runtime;
        this.filter = filter;
        this.gameDirectory = gameDirectory;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("yuworldrepair")
                .then(Commands.literal("status")
                        .requires(source -> YuWorldRepairPermissions.allowed(source, YuWorldRepairPermissions.STATUS))
                        .executes(context -> status(context.getSource())))
                .then(Commands.literal("report")
                        .requires(source -> YuWorldRepairPermissions.allowed(source, YuWorldRepairPermissions.REPORT))
                        .executes(context -> report(context.getSource(), 3_600))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 86_400))
                                .executes(context -> report(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "seconds")
                                ))))
                .then(Commands.literal("signatures")
                        .requires(source -> YuWorldRepairPermissions.allowed(source, YuWorldRepairPermissions.SIGNATURES))
                        .executes(context -> signatures(context.getSource(), null))
                        .then(Commands.argument("domain", StringArgumentType.word())
                                .executes(context -> signatures(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "domain")
                                ))))
                .then(Commands.literal("inspect")
                        .requires(source -> YuWorldRepairPermissions.allowed(source, YuWorldRepairPermissions.INSPECT))
                        .then(Commands.literal("ae2")
                                .executes(context -> inspectAe2(context.getSource()))))
                .then(Commands.literal("reload")
                        .requires(source -> YuWorldRepairPermissions.allowed(source, YuWorldRepairPermissions.RELOAD))
                        .executes(context -> reload(context.getSource())))
                .then(Commands.literal("selftest")
                        .requires(source -> YuWorldRepairPermissions.allowed(source, YuWorldRepairPermissions.SELFTEST))
                        .executes(context -> selftest(context.getSource()))));
    }

    private int status(CommandSourceStack source) {
        var active = config.current();
        source.sendSuccess(() -> Component.literal(
                "YuWorldRepair mode=" + active.mode().name().toLowerCase(Locale.ROOT)
                        + ", filter=" + (filter.isInstalled() ? "active" : "fail-open")
                        + ", signatures=" + runtime.signatureCount() + "/" + runtime.signatureCapacity()
                        + ", recognized=" + runtime.metrics().recognized()
                        + ", suppressed=" + runtime.metrics().suppressed()
                        + ", evicted=" + runtime.signatureEvictions()
                        + ", negativeCache=disabled"
        ), false);
        return 1;
    }

    private int report(CommandSourceStack source, int seconds) {
        try {
            Path report = DiagnosticReport.write(gameDirectory, runtime, config.current(), seconds);
            source.sendSuccess(() -> Component.literal("YuWorldRepair report written: " + report), false);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("YuWorldRepair report failed: " + failure.getMessage()));
            return 0;
        }
    }

    private int signatures(CommandSourceStack source, String requestedDomain) {
        ErrorDomain domain = null;
        if (requestedDomain != null) {
            try {
                domain = ErrorDomain.valueOf(requestedDomain.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                source.sendFailure(Component.literal("Unknown domain: " + requestedDomain));
                return 0;
            }
        }
        int shown = 0;
        for (SignatureEntry entry : runtime.signatures()) {
            if (domain != null && entry.signature().domain() != domain) {
                continue;
            }
            if (shown >= 20) {
                break;
            }
            source.sendSuccess(() -> Component.literal(formatEntry(entry)), false);
            shown++;
        }
        int result = shown;
        source.sendSuccess(() -> Component.literal("YuWorldRepair signatures shown: " + result + " (max 20)"), false);
        return shown;
    }

    private int inspectAe2(CommandSourceStack source) {
        Ae2Compatibility compatibility = Ae2Compatibility.detect();
        source.sendSuccess(() -> Component.literal(
                "AE2 status=" + compatibility.status()
                        + ", version=" + compatibility.version()
                        + ", detail=" + compatibility.detail()
        ), false);
        int shown = 0;
        for (SignatureEntry entry : Ae2ReportAdapter.observedMissingContent(runtime)) {
            if (shown >= 20) {
                break;
            }
            source.sendSuccess(() -> Component.literal(formatEntry(entry)), false);
            shown++;
        }
        return 1;
    }

    private int reload(CommandSourceStack source) {
        var loaded = config.load();
        source.sendSuccess(() -> Component.literal(
                "YuWorldRepair reloaded safely; mode=" + loaded.mode().name().toLowerCase(Locale.ROOT)
        ), true);
        return 1;
    }

    private int selftest(CommandSourceStack source) {
        var active = config.current();
        boolean safe = filter.isInstalled()
                && runtime.signatureCapacity() == active.maxSignatures()
                && KnownErrorRule.values().length == 4
                && !active.enableNegativeCache();
        Ae2Compatibility ae2 = Ae2Compatibility.detect();
        source.sendSuccess(() -> Component.literal(
                "YuWorldRepair selftest=" + (safe ? "PASS" : "DEGRADED")
                        + ", filter=" + filter.isInstalled()
                        + ", exactRules=" + KnownErrorRule.values().length
                        + ", ae2=" + ae2.status()
                        + ", originMixins=not-installed"
                        + ", dataMutation=false"
        ), false);
        return safe ? 1 : 0;
    }

    private static String formatEntry(SignatureEntry entry) {
        return entry.signature().domain() + "/" + entry.signature().shortHash()
                + " id=" + entry.signature().registryId()
                + " observed=" + entry.observed()
                + " suppressed=" + entry.suppressed()
                + " firstCaller=" + entry.signature().callerFingerprint();
    }
}
