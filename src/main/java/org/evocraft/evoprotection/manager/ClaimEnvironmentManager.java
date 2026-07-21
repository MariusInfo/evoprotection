package org.evocraft.evoprotection.manager;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import org.evocraft.evoprotection.EvoProtection;
import org.evocraft.evoprotection.network.PacketHandler;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClaimEnvironmentManager {
    public static final String FLAG_ALWAYS_MIDDLE_DAY = "always_middle_day";
    public static final String FLAG_ALWAYS_MIDDLE_NIGHT = "always_middle_night";
    public static final String FLAG_ALWAYS_SHINY = "always_shiny";
    public static final String FLAG_ALWAYS_RAIN = "always_rain";
    private static final String PIPELINE_HANDLER_NAME = EvoProtection.MODID + "_claim_time_interceptor";
    private static final boolean DEBUG_CLAIM_TIME = Boolean.getBoolean("evoprotection.debugClaimTime");

    private static final ClaimEnvironmentManager INSTANCE = new ClaimEnvironmentManager();

    private final Map<UUID, EnvironmentState> playerStates = new ConcurrentHashMap<>();
    private final Map<UUID, TimeOverride> activeTimeOverrides = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastLocationKeys = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastInterceptDebugLog = new ConcurrentHashMap<>();

    public static ClaimEnvironmentManager get() {
        return INSTANCE;
    }

    public static boolean isEnvironmentFlag(String flagName) {
        String normalized = ClaimManager.normalizeFlagName(flagName);
        return FLAG_ALWAYS_MIDDLE_DAY.equals(normalized)
                || FLAG_ALWAYS_MIDDLE_NIGHT.equals(normalized)
                || FLAG_ALWAYS_SHINY.equals(normalized)
                || FLAG_ALWAYS_RAIN.equals(normalized);
    }

    public void handlePlayerLocation(ServerPlayer player) {
        refreshPlayer(player, false);
    }

    public void installInterceptor(ServerPlayer player) {
        if (player == null || player.connection == null || player.connection.connection == null) return;
        Channel channel = player.connection.connection.channel();
        if (channel == null) return;

        UUID playerId = player.getUUID();
        channel.eventLoop().execute(() -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(PIPELINE_HANDLER_NAME) != null) return;

            ClaimEnvironmentPacketInterceptor interceptor = new ClaimEnvironmentPacketInterceptor(playerId);
            if (pipeline.get("encoder") != null) {
                pipeline.addAfter("encoder", PIPELINE_HANDLER_NAME, interceptor);
            } else {
                pipeline.addLast(PIPELINE_HANDLER_NAME, interceptor);
            }
        });
    }

    public void removeInterceptor(ServerPlayer player) {
        if (player == null || player.connection == null || player.connection.connection == null) return;
        Channel channel = player.connection.connection.channel();
        if (channel == null) return;

        channel.eventLoop().execute(() -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(PIPELINE_HANDLER_NAME) != null) {
                pipeline.remove(PIPELINE_HANDLER_NAME);
            }
        });
    }

    public void forceRefreshPlayer(ServerPlayer player) {
        if (player == null) return;
        lastLocationKeys.remove(player.getUUID());
        refreshPlayer(player, true);
    }

    public void refreshAllPlayers(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            forceRefreshPlayer(player);
        }
    }

    public void refreshClaimPlayers(MinecraftServer server, UUID owner, String claimId) {
        if (server == null || owner == null || claimId == null || claimId.isEmpty()) return;

        String targetDisplayName = ClaimManager.get().getClaimDisplayName(claimId);
        if (targetDisplayName == null || targetDisplayName.trim().isEmpty()) return;
        targetDisplayName = targetDisplayName.trim();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String dim = player.level().dimension().location().toString();
            ChunkPos current = player.chunkPosition();
            UUID currentOwner = ClaimManager.get().getChunkOwner(current, dim);
            String currentClaimId = ClaimManager.get().getClaimId(current, dim);
            String currentDisplayName = ClaimManager.get().getClaimDisplayName(currentClaimId);
            if (owner.equals(currentOwner) && currentDisplayName != null && targetDisplayName.equals(currentDisplayName.trim())) {
                forceRefreshPlayer(player);
            }
        }
    }

    public void refreshRoomPlayers(MinecraftServer server, String roomId) {
        if (server == null || roomId == null || roomId.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String dim = player.level().dimension().location().toString();
            ProtectionRoomManager.ProtectionRoom room = ProtectionRoomManager.get().getRoomAt(player.blockPosition(), dim);
            if (room != null && roomId.equals(room.roomId)) {
                forceRefreshPlayer(player);
            }
        }
    }

    public void clearPlayer(ServerPlayer player) {
        if (player == null) return;
        removeInterceptor(player);
        clearPlayer(player.getUUID());
    }

    public void clearPlayer(UUID playerId) {
        if (playerId == null) return;
        playerStates.remove(playerId);
        activeTimeOverrides.remove(playerId);
        lastLocationKeys.remove(playerId);
        lastInterceptDebugLog.remove(playerId);
    }

    public void clearRuntime(MinecraftServer server) {
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                removeInterceptor(player);
            }
        }
        playerStates.clear();
        activeTimeOverrides.clear();
        lastLocationKeys.clear();
        lastInterceptDebugLog.clear();
    }

    public Packet<?> replaceOutgoingTimePacket(UUID playerId, ClientboundSetTimePacket originalPacket) {
        TimeOverride override = activeTimeOverrides.get(playerId);
        if (override == null) return originalPacket;

        ClientboundSetTimePacket replacement = createFrozenTimePacket(originalPacket.getGameTime(), override.fixedTime);
        debugIntercept(playerId, originalPacket, replacement, override);
        return replacement;
    }

    public Packet<?> replaceOutgoingWeatherPacket(UUID playerId, ClientboundGameEventPacket originalPacket) {
        EnvironmentState state = playerStates.get(playerId);
        if (state == null || state.weatherMode == PacketHandler.S2C_EnvironmentOverride.MODE_NORMAL) {
            return originalPacket;
        }

        ClientboundGameEventPacket.Type event = originalPacket.getEvent();
        boolean weatherEvent = event == ClientboundGameEventPacket.START_RAINING
                || event == ClientboundGameEventPacket.STOP_RAINING
                || event == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE
                || event == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE;
        if (!weatherEvent) return originalPacket;

        boolean forceRain = state.weatherMode == PacketHandler.S2C_EnvironmentOverride.WEATHER_RAIN;
        if (event == ClientboundGameEventPacket.START_RAINING
                || event == ClientboundGameEventPacket.STOP_RAINING) {
            return new ClientboundGameEventPacket(
                    forceRain ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING,
                    0.0F);
        }
        if (event == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE) {
            return new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, forceRain ? 1.0F : 0.0F);
        }
        return new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0.0F);
    }

    private void refreshPlayer(ServerPlayer player, boolean force) {
        if (player == null) return;

        String dim = player.level().dimension().location().toString();
        ChunkPos current = player.chunkPosition();
        ProtectionRoomManager.ProtectionRoom room = ProtectionRoomManager.get().getRoomAt(player.blockPosition(), dim);
        String roomId = room == null ? "" : room.roomId;
        String locationKey = current.x + ";" + current.z + ";" + dim + ";" + roomId;

        if (!force && locationKey.equals(lastLocationKeys.get(player.getUUID()))) {
            return;
        }

        lastLocationKeys.put(player.getUUID(), locationKey);
        EnvironmentState next = resolveEnvironment(player, current, dim, room);
        EnvironmentState previous = playerStates.get(player.getUUID());
        boolean shouldApply = force || previous != null || next.hasOverride();

        if (shouldApply && (force || !Objects.equals(previous, next))) {
            updatePlayerState(player.getUUID(), next);
            applyEnvironment(player, next);
        } else {
            updatePlayerState(player.getUUID(), next);
        }
    }

    private void updatePlayerState(UUID playerId, EnvironmentState state) {
        if (state.hasOverride()) {
            playerStates.put(playerId, state);
        } else {
            playerStates.remove(playerId);
        }
        updateActiveTimeOverride(playerId, state);
    }

    private EnvironmentState resolveEnvironment(ServerPlayer player, ChunkPos current, String dim,
                                                ProtectionRoomManager.ProtectionRoom room) {
        if (room != null && room.ownerUuid != null) {
            int roomTimeMode = PacketHandler.S2C_EnvironmentOverride.MODE_NORMAL;
            int roomWeatherMode = PacketHandler.S2C_EnvironmentOverride.MODE_NORMAL;

            if (ProtectionRoomManager.get().getRoomFlag(room, FLAG_ALWAYS_MIDDLE_DAY)) {
                roomTimeMode = PacketHandler.S2C_EnvironmentOverride.TIME_DAY;
            } else if (ProtectionRoomManager.get().getRoomFlag(room, FLAG_ALWAYS_MIDDLE_NIGHT)) {
                roomTimeMode = PacketHandler.S2C_EnvironmentOverride.TIME_NIGHT;
            }

            if (ProtectionRoomManager.get().getRoomFlag(room, FLAG_ALWAYS_SHINY)) {
                roomWeatherMode = PacketHandler.S2C_EnvironmentOverride.WEATHER_CLEAR;
            } else if (ProtectionRoomManager.get().getRoomFlag(room, FLAG_ALWAYS_RAIN)) {
                roomWeatherMode = PacketHandler.S2C_EnvironmentOverride.WEATHER_RAIN;
            }

            return new EnvironmentState(room.ownerUuid, ProtectionRoomManager.get().getClientRoomId(room), roomTimeMode, roomWeatherMode);
        }

        UUID owner = ClaimManager.get().getChunkOwner(current, dim);
        String claimId = owner != null ? ClaimManager.get().getClaimId(current, dim) : "";

        int timeMode = PacketHandler.S2C_EnvironmentOverride.MODE_NORMAL;
        int weatherMode = PacketHandler.S2C_EnvironmentOverride.MODE_NORMAL;

        if (owner != null && !claimId.isEmpty()) {
            if (ClaimManager.get().getFlag(owner, claimId, FLAG_ALWAYS_MIDDLE_DAY)) {
                timeMode = PacketHandler.S2C_EnvironmentOverride.TIME_DAY;
            } else if (ClaimManager.get().getFlag(owner, claimId, FLAG_ALWAYS_MIDDLE_NIGHT)) {
                timeMode = PacketHandler.S2C_EnvironmentOverride.TIME_NIGHT;
            }

            if (ClaimManager.get().getFlag(owner, claimId, FLAG_ALWAYS_SHINY)) {
                weatherMode = PacketHandler.S2C_EnvironmentOverride.WEATHER_CLEAR;
            } else if (ClaimManager.get().getFlag(owner, claimId, FLAG_ALWAYS_RAIN)) {
                weatherMode = PacketHandler.S2C_EnvironmentOverride.WEATHER_RAIN;
            }
        }

        return new EnvironmentState(owner, claimId, timeMode, weatherMode);
    }

    private void applyEnvironment(ServerPlayer player, EnvironmentState state) {
        applyTimeOverride(player, state.timeMode);
        applyWeatherOverride(player, state.weatherMode);

        PacketHandler.sendToPlayer(new PacketHandler.S2C_EnvironmentOverride(
                state.timeMode,
                state.weatherMode,
                player.level().getDayTime(),
                player.level().isRaining(),
                player.level().getRainLevel(1.0F),
                player.level().getThunderLevel(1.0F)
        ), player);
    }

    private void applyTimeOverride(ServerPlayer player, int timeMode) {
        long dayTime = player.level().getDayTime();
        boolean daylightCycle = player.level().getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);

        if (timeMode == PacketHandler.S2C_EnvironmentOverride.TIME_DAY) {
            dayTime = 6000L;
            daylightCycle = false;
        } else if (timeMode == PacketHandler.S2C_EnvironmentOverride.TIME_NIGHT) {
            dayTime = 18000L;
            daylightCycle = false;
        }

        player.connection.send(new ClientboundSetTimePacket(player.level().getGameTime(), dayTime, daylightCycle));
    }

    private ClientboundSetTimePacket createFrozenTimePacket(long gameTime, long fixedTime) {
        // daylightCycle=false makes ClientboundSetTimePacket serialize dayTime as negative, which freezes client time.
        return new ClientboundSetTimePacket(gameTime, fixedTime, false);
    }

    private void applyWeatherOverride(ServerPlayer player, int weatherMode) {
        if (weatherMode == PacketHandler.S2C_EnvironmentOverride.WEATHER_CLEAR) {
            sendWeather(player, false, 0.0F, 0.0F);
        } else if (weatherMode == PacketHandler.S2C_EnvironmentOverride.WEATHER_RAIN) {
            sendWeather(player, true, 1.0F, 0.0F);
        } else {
            sendWeather(player, player.level().isRaining(), player.level().getRainLevel(1.0F), player.level().getThunderLevel(1.0F));
        }
    }

    private void sendWeather(ServerPlayer player, boolean raining, float rainLevel, float thunderLevel) {
        player.connection.send(new ClientboundGameEventPacket(raining ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING, 0.0F));
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, rainLevel));
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, thunderLevel));
    }

    private void updateActiveTimeOverride(UUID playerId, EnvironmentState state) {
        long fixedTime = fixedTimeForMode(state.timeMode);
        if (fixedTime >= 0L) {
            TimeOverride next = new TimeOverride(state.owner, state.claimId, state.timeMode, fixedTime);
            TimeOverride previous = activeTimeOverrides.put(playerId, next);
            if (!next.equals(previous)) {
                debugOverrideState("START", playerId, state.claimId, fixedTime);
            }
        } else {
            TimeOverride removed = activeTimeOverrides.remove(playerId);
            if (removed != null) {
                debugOverrideState("CLEAR", playerId, removed.claimId, removed.fixedTime);
            }
        }
    }

    private static long fixedTimeForMode(int timeMode) {
        if (timeMode == PacketHandler.S2C_EnvironmentOverride.TIME_DAY) return 6000L;
        if (timeMode == PacketHandler.S2C_EnvironmentOverride.TIME_NIGHT) return 18000L;
        return -1L;
    }

    private void debugIntercept(UUID playerId, ClientboundSetTimePacket original, ClientboundSetTimePacket replacement, TimeOverride override) {
        if (!DEBUG_CLAIM_TIME) return;
        long now = System.currentTimeMillis();
        long lastLog = lastInterceptDebugLog.getOrDefault(playerId, 0L);
        if (now - lastLog < 5000L) return;

        lastInterceptDebugLog.put(playerId, now);
        System.out.println("[ClaimTime] INTERCEPT player=" + playerId
                + " claim=" + override.claimId
                + " originalDayTime=" + original.getDayTime()
                + " replacementDayTime=" + replacement.getDayTime()
                + " fixedTime=" + override.fixedTime
                + " frozen=true");
    }

    private static void debugOverrideState(String action, UUID playerId, String claimId, long fixedTime) {
        if (!DEBUG_CLAIM_TIME) return;
        System.out.println("[ClaimTime] " + action
                + " player=" + playerId
                + " claim=" + claimId
                + " fixedTime=" + fixedTime);
    }

    private static class ClaimEnvironmentPacketInterceptor extends ChannelDuplexHandler {
        private final UUID playerId;

        private ClaimEnvironmentPacketInterceptor(UUID playerId) {
            this.playerId = playerId;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof ClientboundSetTimePacket packet) {
                Packet<?> replacement = ClaimEnvironmentManager.get().replaceOutgoingTimePacket(playerId, packet);
                if (replacement != packet) {
                    ctx.write(replacement, promise);
                    return;
                }
            }
            if (msg instanceof ClientboundGameEventPacket packet) {
                Packet<?> replacement = ClaimEnvironmentManager.get().replaceOutgoingWeatherPacket(playerId, packet);
                if (replacement != packet) {
                    ctx.write(replacement, promise);
                    return;
                }
            }
            ctx.write(msg, promise);
        }
    }

    private static class TimeOverride {
        private final UUID owner;
        private final String claimId;
        private final int timeMode;
        private final long fixedTime;

        private TimeOverride(UUID owner, String claimId, int timeMode, long fixedTime) {
            this.owner = owner;
            this.claimId = claimId != null ? claimId : "";
            this.timeMode = timeMode;
            this.fixedTime = fixedTime;
        }

        private boolean matches(EnvironmentState state) {
            return state != null
                    && state.timeMode == timeMode
                    && Objects.equals(owner, state.owner)
                    && Objects.equals(claimId, state.claimId);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof TimeOverride other)) return false;
            return timeMode == other.timeMode
                    && fixedTime == other.fixedTime
                    && Objects.equals(owner, other.owner)
                    && Objects.equals(claimId, other.claimId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(owner, claimId, timeMode, fixedTime);
        }
    }

    private static class EnvironmentState {
        private final UUID owner;
        private final String claimId;
        private final int timeMode;
        private final int weatherMode;

        private EnvironmentState(UUID owner, String claimId, int timeMode, int weatherMode) {
            this.owner = owner;
            this.claimId = claimId != null ? claimId : "";
            this.timeMode = timeMode;
            this.weatherMode = weatherMode;
        }

        private boolean hasOverride() {
            return timeMode != PacketHandler.S2C_EnvironmentOverride.MODE_NORMAL
                    || weatherMode != PacketHandler.S2C_EnvironmentOverride.MODE_NORMAL;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof EnvironmentState other)) return false;
            return timeMode == other.timeMode
                    && weatherMode == other.weatherMode
                    && Objects.equals(owner, other.owner)
                    && Objects.equals(claimId, other.claimId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(owner, claimId, timeMode, weatherMode);
        }
    }
}
