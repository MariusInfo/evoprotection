package org.evocraft.evoprotection.manager;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;
import org.evocraft.evoprotection.EvoProtection;

import java.util.UUID;

@Mod.EventBusSubscriber(modid = EvoProtection.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ProtectionPermissions {
    public static final PermissionNode<Boolean> ADMIN_ACCESS = new PermissionNode<>(
            new ResourceLocation(EvoProtection.MODID, "admin.access"),
            PermissionTypes.BOOLEAN,
            (player, playerId, contexts) -> false
    );

    private ProtectionPermissions() {
    }

    @SubscribeEvent
    public static void onPermissionGather(PermissionGatherEvent.Nodes event) {
        event.addNodes(ADMIN_ACCESS);
    }

    public static boolean hasAdminAccess(ServerPlayer player) {
        return player != null
                && (player.hasPermissions(2) || PermissionAPI.getPermission(player, ADMIN_ACCESS));
    }

    public static boolean canBypassClaim(ServerPlayer player, UUID claimOwner) {
        if (player == null) return false;
        if (player.hasPermissions(2)) return true;
        return ClaimManager.isAdminClaimOwner(claimOwner)
                && PermissionAPI.getPermission(player, ADMIN_ACCESS);
    }
}
