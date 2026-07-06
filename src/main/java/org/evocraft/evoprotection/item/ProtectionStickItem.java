package org.evocraft.evoprotection.item;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import org.evocraft.evoprotection.manager.ProtectionRoomManager;

public class ProtectionStickItem extends Item {
    public ProtectionStickItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (!(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }

        if (!player.hasPermissions(2)) {
            player.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] Only admins can use the Protection Stick."));
            return InteractionResult.FAIL;
        }

        int point = player.isShiftKeyDown() ? 2 : 1;
        ProtectionRoomManager.get().setSelectionPoint(player, context.getClickedPos(), point);
        return InteractionResult.SUCCESS;
    }
}
