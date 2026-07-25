package dev.yu.worldrepair.command;

import dev.yu.worldrepair.YuWorldRepair;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

public final class YuWorldRepairPermissions {
    public static final PermissionNode<Boolean> STATUS = node("admin.status");
    public static final PermissionNode<Boolean> REPORT = node("admin.report");
    public static final PermissionNode<Boolean> SIGNATURES = node("admin.signatures");
    public static final PermissionNode<Boolean> INSPECT = node("admin.inspect");
    public static final PermissionNode<Boolean> RELOAD = node("admin.reload");
    public static final PermissionNode<Boolean> SELFTEST = node("admin.selftest");

    private YuWorldRepairPermissions() {
    }

    public static void register(PermissionGatherEvent.Nodes event) {
        event.addNodes(STATUS, REPORT, SIGNATURES, INSPECT, RELOAD, SELFTEST);
    }

    public static boolean allowed(CommandSourceStack source, PermissionNode<Boolean> node) {
        if (source.getEntity() == null) {
            return source.hasPermission(4);
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return source.hasPermission(4);
        }
        try {
            return source.hasPermission(4) || PermissionAPI.getPermission(player, node);
        } catch (RuntimeException permissionUnavailable) {
            return source.hasPermission(4);
        }
    }

    private static PermissionNode<Boolean> node(String name) {
        return new PermissionNode<>(
                YuWorldRepair.MOD_ID,
                name,
                PermissionTypes.BOOLEAN,
                (player, playerUuid, context) -> player != null && player.hasPermissions(4)
        );
    }
}
