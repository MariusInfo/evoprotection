package org.evocraft.evoprotection.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.evocraft.evoprotection.manager.ClaimManager;

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
}