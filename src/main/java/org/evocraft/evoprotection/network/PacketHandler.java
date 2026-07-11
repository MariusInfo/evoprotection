package org.evocraft.evoprotection.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.evocraft.evoprotection.EvoProtection;
import org.evocraft.evoprotection.manager.ClaimEnvironmentManager;
import org.evocraft.evoprotection.manager.ClaimManager;
import org.evocraft.evoprotection.manager.LanguageManager;
import org.evocraft.evoprotection.manager.ProtectionRoomManager;

import java.util.UUID;
import java.util.function.Supplier;

public class PacketHandler {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(EvoProtection.MODID, "main"),
            () -> PROTOCOL_VERSION, s -> true, s -> true
    );

    private static int packetId = 0;
    private static int nextId() { return packetId++; }

    public static void register() {
        INSTANCE.registerMessage(nextId(), S2C_SyncClaimData.class, S2C_SyncClaimData::toBytes, S2C_SyncClaimData::new, S2C_SyncClaimData::handle);
        INSTANCE.registerMessage(nextId(), C2S_BuyClaimSlot.class, C2S_BuyClaimSlot::toBytes, C2S_BuyClaimSlot::new, C2S_BuyClaimSlot::handle);
        INSTANCE.registerMessage(nextId(), C2S_ClaimAction.class, C2S_ClaimAction::toBytes, C2S_ClaimAction::new, C2S_ClaimAction::handle);
        INSTANCE.registerMessage(nextId(), C2S_AdminClaimAction.class, C2S_AdminClaimAction::toBytes, C2S_AdminClaimAction::new, C2S_AdminClaimAction::handle);
        INSTANCE.registerMessage(nextId(), C2S_ManageTrust.class, C2S_ManageTrust::toBytes, C2S_ManageTrust::new, C2S_ManageTrust::handle);
        INSTANCE.registerMessage(nextId(), S2C_ChunkEnter.class, S2C_ChunkEnter::toBytes, S2C_ChunkEnter::new, S2C_ChunkEnter::handle);
        INSTANCE.registerMessage(nextId(), C2S_UpdateFlag.class, C2S_UpdateFlag::toBytes, C2S_UpdateFlag::new, C2S_UpdateFlag::handle);
        INSTANCE.registerMessage(nextId(), S2C_UpdateProtectionMode.class, S2C_UpdateProtectionMode::toBytes, S2C_UpdateProtectionMode::new, S2C_UpdateProtectionMode::handle);
        INSTANCE.registerMessage(nextId(), C2S_DeleteClaimByName.class, C2S_DeleteClaimByName::toBytes, C2S_DeleteClaimByName::new, C2S_DeleteClaimByName::handle);
        INSTANCE.registerMessage(nextId(), S2C_SyncPayDay.class, S2C_SyncPayDay::toBytes, S2C_SyncPayDay::new, S2C_SyncPayDay::handle);

        INSTANCE.registerMessage(nextId(), S2C_SyncLanguage.class, S2C_SyncLanguage::toBytes, S2C_SyncLanguage::new, S2C_SyncLanguage::handle);
        INSTANCE.registerMessage(nextId(), S2C_EnvironmentOverride.class, S2C_EnvironmentOverride::toBytes, S2C_EnvironmentOverride::new, S2C_EnvironmentOverride::handle);
        INSTANCE.registerMessage(nextId(), S2C_OpenRoomOffer.class, S2C_OpenRoomOffer::toBytes, S2C_OpenRoomOffer::new, S2C_OpenRoomOffer::handle);
        INSTANCE.registerMessage(nextId(), C2S_RoomOfferChoice.class, C2S_RoomOfferChoice::toBytes, C2S_RoomOfferChoice::new, C2S_RoomOfferChoice::handle);
    }

    public static <MSG> void sendToPlayer(MSG message, ServerPlayer player) {
        if(player != null) {
            INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), message);
        }
    }

    @net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = EvoProtection.MODID, bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.FORGE)
    public static class ServerEvents {
        @net.minecraftforge.eventbus.api.SubscribeEvent
        public static void onPlayerJoin(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
            if (!event.getEntity().level().isClientSide && event.getEntity() instanceof ServerPlayer player) {
                boolean isPlot = org.evocraft.evoprotection.manager.ProtectionConfig.get().isPlotMode;
                int pSize = org.evocraft.evoprotection.manager.ProtectionConfig.get().plotSizeChunks;
                int rSize = org.evocraft.evoprotection.manager.ProtectionConfig.get().roadSizeChunks;
                INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new S2C_UpdateProtectionMode(isPlot, pSize, rSize));

                // Reload and send fresh language from launcher (searching by NAME this time)
                String playerName = player.getGameProfile().getName();
                ClaimManager.get().reloadPlayerLanguage(player.getUUID(), playerName);
                String lang = ClaimManager.get().getPlayerLanguage(player.getUUID());

                INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new S2C_SyncLanguage(lang));
            }
        }
    }

    public static class S2C_SyncLanguage {
        public final String lang;
        public S2C_SyncLanguage(String lang) {
            this.lang = lang;
        }
        public S2C_SyncLanguage(FriendlyByteBuf buf) {
            this.lang = buf.readUtf();
        }
        public void toBytes(FriendlyByteBuf buf) {
            buf.writeUtf(lang);
        }
        public boolean handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                ClientPacketHandler.handleSyncLanguage(lang);
            }));
            ctx.get().setPacketHandled(true);
            return true;
        }
    }

    public static class S2C_SyncPayDay {
        public final int secondsLeft;
        public final boolean isIdle;
        public S2C_SyncPayDay(int secondsLeft, boolean isIdle) {
            this.secondsLeft = secondsLeft;
            this.isIdle = isIdle;
        }
        public S2C_SyncPayDay(FriendlyByteBuf buf) {
            this.secondsLeft = buf.readInt();
            this.isIdle = buf.readBoolean();
        }
        public void toBytes(FriendlyByteBuf buf) {
            buf.writeInt(secondsLeft);
            buf.writeBoolean(isIdle);
        }
        public boolean handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.handleSyncPayDay(secondsLeft, isIdle)));
            ctx.get().setPacketHandled(true);
            return true;
        }
    }

    public static class S2C_EnvironmentOverride {
        public static final int MODE_NORMAL = -1;
        public static final int TIME_DAY = 0;
        public static final int TIME_NIGHT = 1;
        public static final int WEATHER_CLEAR = 0;
        public static final int WEATHER_RAIN = 1;

        public final int timeMode;
        public final int weatherMode;
        public final long serverDayTime;
        public final boolean serverRaining;
        public final float serverRainLevel;
        public final float serverThunderLevel;

        public S2C_EnvironmentOverride(int timeMode, int weatherMode, long serverDayTime, boolean serverRaining, float serverRainLevel, float serverThunderLevel) {
            this.timeMode = timeMode;
            this.weatherMode = weatherMode;
            this.serverDayTime = serverDayTime;
            this.serverRaining = serverRaining;
            this.serverRainLevel = serverRainLevel;
            this.serverThunderLevel = serverThunderLevel;
        }

        public S2C_EnvironmentOverride(FriendlyByteBuf buf) {
            this.timeMode = buf.readInt();
            this.weatherMode = buf.readInt();
            this.serverDayTime = buf.readLong();
            this.serverRaining = buf.readBoolean();
            this.serverRainLevel = buf.readFloat();
            this.serverThunderLevel = buf.readFloat();
        }

        public void toBytes(FriendlyByteBuf buf) {
            buf.writeInt(timeMode);
            buf.writeInt(weatherMode);
            buf.writeLong(serverDayTime);
            buf.writeBoolean(serverRaining);
            buf.writeFloat(serverRainLevel);
            buf.writeFloat(serverThunderLevel);
        }

        public boolean handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.handleEnvironmentOverride(timeMode, weatherMode, serverDayTime, serverRaining, serverRainLevel, serverThunderLevel)));
            ctx.get().setPacketHandled(true);
            return true;
        }
    }

    public static class S2C_OpenRoomOffer {
        public final String roomId;
        public final String roomName;
        public final double buyPrice;
        public final double rentPrice;
        public final int mode;
        public final boolean canBuy;
        public final boolean canRent;

        public static final int MODE_OFFER = 0;
        public static final int MODE_MANAGE_RENT = 1;
        public static final int MODE_MANAGE_BOUGHT = 2;

        public S2C_OpenRoomOffer(String roomId, String roomName, double buyPrice, double rentPrice,
                                 int mode, boolean canBuy, boolean canRent) {
            this.roomId = roomId == null ? "" : roomId;
            this.roomName = roomName == null ? "" : roomName;
            this.buyPrice = buyPrice;
            this.rentPrice = rentPrice;
            this.mode = mode;
            this.canBuy = canBuy;
            this.canRent = canRent;
        }

        public S2C_OpenRoomOffer(FriendlyByteBuf buf) {
            this.roomId = buf.readUtf(80);
            this.roomName = buf.readUtf(80);
            this.buyPrice = buf.readDouble();
            this.rentPrice = buf.readDouble();
            this.mode = buf.readInt();
            this.canBuy = buf.readBoolean();
            this.canRent = buf.readBoolean();
        }

        public void toBytes(FriendlyByteBuf buf) {
            buf.writeUtf(roomId, 80);
            buf.writeUtf(roomName, 80);
            buf.writeDouble(buyPrice);
            buf.writeDouble(rentPrice);
            buf.writeInt(mode);
            buf.writeBoolean(canBuy);
            buf.writeBoolean(canRent);
        }

        public boolean handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ClientPacketHandler.handleOpenRoomOffer(roomId, roomName, buyPrice, rentPrice, mode, canBuy, canRent)));
            ctx.get().setPacketHandled(true);
            return true;
        }
    }

    public static class C2S_RoomOfferChoice {
        public final String roomId;
        public final ProtectionRoomManager.RoomOfferAction action;
        public final double requestedPrice;

        public C2S_RoomOfferChoice(String roomId, ProtectionRoomManager.RoomOfferAction action, double requestedPrice) {
            this.roomId = roomId == null ? "" : roomId;
            this.action = action == null ? ProtectionRoomManager.RoomOfferAction.BUY : action;
            this.requestedPrice = requestedPrice;
        }

        public C2S_RoomOfferChoice(FriendlyByteBuf buf) {
            this.roomId = buf.readUtf(80);
            this.action = readAction(buf.readUtf(32));
            this.requestedPrice = buf.readDouble();
        }

        public void toBytes(FriendlyByteBuf buf) {
            buf.writeUtf(roomId, 80);
            buf.writeUtf(action.name(), 32);
            buf.writeDouble(requestedPrice);
        }

        public boolean handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player != null) {
                    ProtectionRoomManager.get().handleRoomOfferAction(player, roomId, action, requestedPrice);
                }
            });
            ctx.get().setPacketHandled(true);
            return true;
        }

        private ProtectionRoomManager.RoomOfferAction readAction(String value) {
            try {
                return ProtectionRoomManager.RoomOfferAction.valueOf(value);
            } catch (Exception ignored) {
                return ProtectionRoomManager.RoomOfferAction.BUY;
            }
        }
    }

    public static class S2C_UpdateProtectionMode {
        public final boolean isPlotMode;
        public final int plotSize;
        public final int roadSize;
        public S2C_UpdateProtectionMode(boolean isPlotMode, int plotSize, int roadSize) {
            this.isPlotMode = isPlotMode;
            this.plotSize = plotSize;
            this.roadSize = roadSize;
        }
        public S2C_UpdateProtectionMode(FriendlyByteBuf buf) {
            this.isPlotMode = buf.readBoolean();
            this.plotSize = buf.readInt();
            this.roadSize = buf.readInt();
        }
        public void toBytes(FriendlyByteBuf buf) {
            buf.writeBoolean(isPlotMode);
            buf.writeInt(plotSize);
            buf.writeInt(roadSize);
        }
        public boolean handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                org.evocraft.evoprotection.gui.ClaimMapScreen.currentServerIsPlot = isPlotMode;
                org.evocraft.evoprotection.gui.ClaimMapScreen.currentPlotSize = plotSize;
                org.evocraft.evoprotection.gui.ClaimMapScreen.currentRoadSize = roadSize;
                org.evocraft.evoprotection.client.ClientPayDayData.isActive = isPlotMode;
            }));
            ctx.get().setPacketHandled(true);
            return true;
        }
    }

    public static class S2C_ChunkEnter {
        public final String name;
        public S2C_ChunkEnter(String name) {
            this.name = name;
        }
        public S2C_ChunkEnter(FriendlyByteBuf buf) {
            this.name = buf.readUtf();
        }
        public void toBytes(FriendlyByteBuf buf) {
            buf.writeUtf(name);
        }
        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> org.evocraft.evoprotection.client.ChunkNotificationHandler.onEnterNewChunk(name)));
            ctx.get().setPacketHandled(true);
        }
    }

    public static class S2C_SyncClaimData {
        public final String json;
        public final boolean isAdminMap;
        public S2C_SyncClaimData(String json, boolean isAdminMap) {
            this.json = json;
            this.isAdminMap = isAdminMap;
        }
        public S2C_SyncClaimData(FriendlyByteBuf buf) {
            this.json = buf.readUtf(262144);
            this.isAdminMap = buf.readBoolean();
        }
        public void toBytes(FriendlyByteBuf buf) {
            buf.writeUtf(json, 262144);
            buf.writeBoolean(isAdminMap);
        }
        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.handleSyncClaimData(json, isAdminMap)));
            ctx.get().setPacketHandled(true);
        }
    }

    public static class C2S_BuyClaimSlot {
        public C2S_BuyClaimSlot() {}
        public C2S_BuyClaimSlot(FriendlyByteBuf buf) {}
        public void toBytes(FriendlyByteBuf buf) {}
        public boolean handle(Supplier<NetworkEvent.Context> supplier) {
            supplier.get().enqueueWork(() -> {
                ServerPlayer player = supplier.get().getSender();
                if (player != null) {
                    ClaimManager.get().buySlot(player);
                }
            });
            supplier.get().setPacketHandled(true);
            return true;
        }
    }

    public static class C2S_DeleteClaimByName {
        private final String claimName;
        public C2S_DeleteClaimByName(String claimName) {
            this.claimName = claimName;
        }
        public C2S_DeleteClaimByName(FriendlyByteBuf buf) {
            this.claimName = buf.readUtf();
        }
        public void toBytes(FriendlyByteBuf buf) {
            buf.writeUtf(claimName);
        }
        public boolean handle(Supplier<NetworkEvent.Context> supplier) {
            supplier.get().enqueueWork(() -> {
                ServerPlayer player = supplier.get().getSender();
                if (player != null) {
                    if (ClaimManager.get().unclaimById(player, claimName)) {
                        ClaimEnvironmentManager.get().refreshAllPlayers(player.getServer());
                    }
                }
            });
            supplier.get().setPacketHandled(true);
            return true;
        }
    }

    public static class C2S_UpdateFlag {
        private final String claimName;
        private final String flagName;
        private final boolean state;
        private final boolean isAdmin;

        public C2S_UpdateFlag(String claimName, String flagName, boolean state, boolean isAdmin) {
            this.claimName = claimName;
            this.flagName = flagName;
            this.state = state;
            this.isAdmin = isAdmin;
        }
        public C2S_UpdateFlag(FriendlyByteBuf buf) {
            this.claimName = buf.readUtf();
            this.flagName = buf.readUtf();
            this.state = buf.readBoolean();
            this.isAdmin = buf.readBoolean();
        }
        public void toBytes(FriendlyByteBuf buf) {
            buf.writeUtf(claimName);
            buf.writeUtf(flagName);
            buf.writeBoolean(state);
            buf.writeBoolean(isAdmin);
        }
        public boolean handle(Supplier<NetworkEvent.Context> supplier) {
            supplier.get().enqueueWork(() -> {
                ServerPlayer player = supplier.get().getSender();
                if (player != null) {
                    String lang = ClaimManager.get().getPlayerLanguage(player.getUUID());
                    boolean adminAction = isAdmin && player.hasPermissions(2);
                    if (isAdmin && !adminAction) return;

                    UUID targetUUID = adminAction ? new UUID(0, 0) : ClaimManager.get().getClaimOwner(claimName);
                    if (targetUUID == null) return;
                    if (!adminAction && !ClaimManager.get().canEditFlag(targetUUID, player.getUUID(), claimName, flagName)) {
                        player.sendSystemMessage(Component.literal("§cYou do not have permission to change this flag for your role."));
                        return;
                    }
                    if (!ClaimManager.get().setFlag(targetUUID, claimName, flagName, state)) return;
                    if (ClaimEnvironmentManager.isEnvironmentFlag(flagName)) {
                        ClaimEnvironmentManager.get().refreshClaimPlayers(player.getServer(), targetUUID, claimName);
                    }

                    if (adminAction) {
                        ClaimManager.get().syncToAdminClient(player);
                    } else {
                        ClaimManager.get().syncToClient(player);
                    }

                    String statusStr = state ? LanguageManager.get(lang, "gui.status.on") : LanguageManager.get(lang, "gui.status.off");
                    String displayName = ClaimManager.get().getClaimDisplayName(claimName);
                    player.sendSystemMessage(Component.literal(LanguageManager.get(lang, "msg.flag.updated", flagName, displayName, statusStr)));
                }
            });
            supplier.get().setPacketHandled(true);
            return true;
        }
    }

    public static class C2S_ClaimAction {
        private final int chunkX, chunkZ;
        private final boolean isClaiming;
        private final String customName;

        public C2S_ClaimAction(int chunkX, int chunkZ, boolean isClaiming, String customName) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.isClaiming = isClaiming;
            this.customName = customName;
        }
        public C2S_ClaimAction(FriendlyByteBuf buf) {
            this.chunkX = buf.readInt();
            this.chunkZ = buf.readInt();
            this.isClaiming = buf.readBoolean();
            this.customName = buf.readUtf();
        }
        public void toBytes(FriendlyByteBuf buf) {
            buf.writeInt(chunkX);
            buf.writeInt(chunkZ);
            buf.writeBoolean(isClaiming);
            buf.writeUtf(customName);
        }
        public boolean handle(Supplier<NetworkEvent.Context> supplier) {
            supplier.get().enqueueWork(() -> {
                ServerPlayer player = supplier.get().getSender();
                if (player != null) {
                    ChunkPos target = new ChunkPos(chunkX, chunkZ);
                    if (!ClaimManager.get().isChunkInsideClientMap(player, target)) return;
                    boolean changed;
                    if (isClaiming) {
                        changed = ClaimManager.get().claimChunk(player, target, customName);
                    } else {
                        changed = ClaimManager.get().unclaimChunk(player, target);
                    }
                    if (changed) {
                        ClaimEnvironmentManager.get().refreshAllPlayers(player.getServer());
                    }
                }
            });
            supplier.get().setPacketHandled(true);
            return true;
        }
    }

    public static class C2S_AdminClaimAction {
        private final int chunkX, chunkZ;
        private final boolean isClaiming;
        private final String customName;

        public C2S_AdminClaimAction(int chunkX, int chunkZ, boolean isClaiming, String customName) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.isClaiming = isClaiming;
            this.customName = customName;
        }
        public C2S_AdminClaimAction(FriendlyByteBuf buf) {
            this.chunkX = buf.readInt();
            this.chunkZ = buf.readInt();
            this.isClaiming = buf.readBoolean();
            this.customName = buf.readUtf();
        }
        public void toBytes(FriendlyByteBuf buf) {
            buf.writeInt(chunkX);
            buf.writeInt(chunkZ);
            buf.writeBoolean(isClaiming);
            buf.writeUtf(customName);
        }
        public boolean handle(Supplier<NetworkEvent.Context> supplier) {
            supplier.get().enqueueWork(() -> {
                ServerPlayer player = supplier.get().getSender();
                if (player != null && player.hasPermissions(2)) {
                    ChunkPos target = new ChunkPos(chunkX, chunkZ);
                    if (!ClaimManager.get().isChunkInsideClientMap(player, target)) return;
                    String dim = player.level().dimension().location().toString();
                    if (isClaiming) {
                        ClaimManager.get().adminClaim(target, dim, customName);
                        ClaimEnvironmentManager.get().refreshAllPlayers(player.getServer());
                    } else {
                        if (ClaimManager.get().removeAnyClaim(target, dim)) {
                            ClaimEnvironmentManager.get().refreshAllPlayers(player.getServer());
                        }
                    }
                    ClaimManager.get().syncToAdminClient(player);
                }
            });
            supplier.get().setPacketHandled(true);
            return true;
        }
    }

    public static class C2S_ManageTrust {
        private final String targetName;
        private final String targetUuidStr;
        private final boolean isAdding;
        private final String claimName;
        private final String role;

        public C2S_ManageTrust(String targetName, String targetUuidStr, boolean isAdding, String claimName, String role) {
            this.targetName = targetName;
            this.targetUuidStr = targetUuidStr;
            this.isAdding = isAdding;
            this.claimName = claimName;
            this.role = ClaimManager.normalizeTrustRole(role);
        }
        public C2S_ManageTrust(FriendlyByteBuf buf) {
            this.targetName = buf.readUtf();
            this.targetUuidStr = buf.readUtf();
            this.isAdding = buf.readBoolean();
            this.claimName = buf.readUtf();
            this.role = ClaimManager.normalizeTrustRole(buf.readUtf());
        }
        public void toBytes(FriendlyByteBuf buf) {
            buf.writeUtf(targetName);
            buf.writeUtf(targetUuidStr);
            buf.writeBoolean(isAdding);
            buf.writeUtf(claimName);
            buf.writeUtf(role);
        }
        public boolean handle(Supplier<NetworkEvent.Context> supplier) {
            supplier.get().enqueueWork(() -> {
                ServerPlayer player = supplier.get().getSender();
                if (player != null) {
                    String lang = ClaimManager.get().getPlayerLanguage(player.getUUID());
                    if (isAdding) {
                        UUID targetUuid = null;
                        String targetDisplay = targetName;
                        if (targetUuidStr != null && !targetUuidStr.isEmpty()) {
                            try {
                                targetUuid = UUID.fromString(targetUuidStr);
                            } catch (Exception ignored) { }
                        }

                        if (targetUuid == null) {
                            ServerPlayer target = player.getServer().getPlayerList().getPlayerByName(targetName);
                            if (target != null) {
                                targetUuid = target.getUUID();
                                targetDisplay = target.getName().getString();
                            }
                        }

                        if (targetUuid != null) {
                            if (ClaimManager.get().addTrust(player, targetUuid, claimName, role)) {
                                String displayName = ClaimManager.get().getClaimDisplayName(claimName);
                                String roleLabel = LanguageManager.get(lang, "gui.trust.role." + ClaimManager.normalizeTrustRole(role));
                                player.sendSystemMessage(Component.literal(LanguageManager.get(lang, "msg.trust.role_set", targetDisplay, roleLabel, displayName)));
                            }
                        } else {
                            player.sendSystemMessage(Component.literal(LanguageManager.get(lang, "msg.trust.offline")));
                        }
                    } else {
                        try {
                            if (ClaimManager.get().removeTrust(player, UUID.fromString(targetUuidStr), claimName)) {
                                String displayName = ClaimManager.get().getClaimDisplayName(claimName);
                                player.sendSystemMessage(Component.literal(LanguageManager.get(lang, "msg.trust.removed", displayName)));
                            }
                        } catch (Exception ignored) {}
                    }
                }
            });
            supplier.get().setPacketHandled(true);
            return true;
        }
    }
}
