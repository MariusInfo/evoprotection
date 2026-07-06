package org.evocraft.evoprotection.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.evocraft.evoprotection.item.ModItems;
import org.evocraft.evoprotection.manager.ClaimManager;
import org.evocraft.evoprotection.manager.ProtectionRoomManager;

import java.util.List;

public class ClaimCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {

        // ==============================
        // 🏗️ SISTEMUL NOU DE PLOT-URI
        // ==============================
        dispatcher.register(Commands.literal("plot")
                .then(Commands.literal("auto").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    ClaimManager.get().autoClaimPlot(player);
                    return 1;
                }))
                .then(Commands.literal("home").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    ClaimManager.get().teleportToHomePlot(player);
                    return 1;
                }))
        );

        // ==============================
        // COMANDA DE ADMIN
        // ==============================
        dispatcher.register(Commands.literal("evo")
                .requires(s -> s.hasPermission(2))

                .then(Commands.literal("adminclaim")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            ClaimManager.get().syncToAdminClient(p);
                            return 1;
                        })
                )

                .then(Commands.literal("unadmin").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    if (ClaimManager.get().removeAnyClaim(p.chunkPosition(), p.level().dimension().location().toString())) {
                        p.sendSystemMessage(Component.literal("§a[Admin] Protecție ștearsă din acest chunk!"));
                        ClaimManager.get().syncToClient(p);
                    } else {
                        p.sendSystemMessage(Component.literal("§cNu există protecție aici."));
                    }
                    return 1;
                }))

                .then(Commands.literal("transfer")
                        .then(Commands.argument("de_la", EntityArgument.player())
                                .then(Commands.argument("catre", EntityArgument.player())
                                        .executes(ctx -> {
                                            ServerPlayer from = EntityArgument.getPlayer(ctx, "de_la");
                                            ServerPlayer to = EntityArgument.getPlayer(ctx, "catre");
                                            ClaimManager.get().transferAllClaims(from.getUUID(), to.getUUID(), to.getGameProfile().getName());
                                            ctx.getSource().sendSuccess(() -> Component.literal("§aTransfer complet! §e" + from.getGameProfile().getName() + " -> " + to.getGameProfile().getName()), true);
                                            ClaimManager.get().syncToClient(to);
                                            return 1;
                                        }))))

                .then(Commands.literal("room")
                        .then(Commands.literal("stick")
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    ItemStack stack = new ItemStack(ModItems.PROTECTION_STICK.get());
                                    if (!p.getInventory().add(stack)) {
                                        p.drop(stack, false);
                                    }
                                    p.sendSystemMessage(Component.literal("\u00A7a[EvoProtection] Protection Stick received. Right click point 1, Shift + Right Click point 2."));
                                    p.sendSystemMessage(Component.literal("\u00A77Then use \u00A7f/evo room buy <price> rent <price>\u00A77 and right click a wall with an empty hand."));
                                    return 1;
                                }))
                        .then(Commands.literal("buy")
                                .then(Commands.argument("buy_price", DoubleArgumentType.doubleArg(0.0D))
                                        .then(Commands.literal("rent")
                                                .then(Commands.argument("rent_price", DoubleArgumentType.doubleArg(0.0D))
                                                        .executes(ctx -> {
                                                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                                                            double buyPrice = DoubleArgumentType.getDouble(ctx, "buy_price");
                                                            double rentPrice = DoubleArgumentType.getDouble(ctx, "rent_price");
                                                            return ProtectionRoomManager.get().prepareRoomSign(p, buyPrice, rentPrice, "") ? 1 : 0;
                                                        })
                                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                                .executes(ctx -> {
                                                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                                                    double buyPrice = DoubleArgumentType.getDouble(ctx, "buy_price");
                                                                    double rentPrice = DoubleArgumentType.getDouble(ctx, "rent_price");
                                                                    String name = StringArgumentType.getString(ctx, "name");
                                                                    return ProtectionRoomManager.get().prepareRoomSign(p, buyPrice, rentPrice, name) ? 1 : 0;
                                                                }))))))
                        .then(Commands.literal("place")
                                .then(Commands.literal("sign")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                                    TargetBlock target = getLookedAtBlock(p);
                                                    if (target == null) {
                                                        p.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] Look at the side of a block within 6 blocks."));
                                                        return 0;
                                                    }
                                                    String playerName = StringArgumentType.getString(ctx, "player");
                                                    return ProtectionRoomManager.get().placeBackupSignForPlayer(p, playerName, target.pos(), target.face()) ? 1 : 0;
                                                })))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                                            String name = StringArgumentType.getString(ctx, "name");
                                            return ProtectionRoomManager.get().removeRoomByName(p, name) ? 1 : 0;
                                        })))
        );

        // ==============================
        // COMANDA PRINCIPALĂ MAPĂ
        // ==============================
        dispatcher.register(Commands.literal("claim")
                .executes(ctx -> {
                    ClaimManager.get().syncToClient(ctx.getSource().getPlayerOrException());
                    return 1;
                })
        );

        dispatcher.register(Commands.literal("harta").executes(ctx -> {
            ClaimManager.get().syncToClient(ctx.getSource().getPlayerOrException());
            return 1;
        }));
    }

    private static TargetBlock getLookedAtBlock(ServerPlayer player) {
        HitResult result = player.pick(6.0D, 0.0F, false);
        if (result.getType() != HitResult.Type.BLOCK) return null;

        BlockHitResult blockHit = (BlockHitResult) result;
        Direction face = blockHit.getDirection();
        if (face.getAxis().isVertical()) return null;
        return new TargetBlock(blockHit.getBlockPos(), face);
    }

    private record TargetBlock(BlockPos pos, Direction face) {
    }
}
