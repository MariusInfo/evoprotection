package org.evocraft.evoprotection.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.evocraft.evoprotection.EvoProtection;
import org.evocraft.evoprotection.network.PacketHandler;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = EvoProtection.MODID, value = Dist.CLIENT)
public class ClientEnvironmentManager {
    private static int timeMode = PacketHandler.S2C_EnvironmentOverride.MODE_NORMAL;
    private static int weatherMode = PacketHandler.S2C_EnvironmentOverride.MODE_NORMAL;

    public static void setOverride(int newTimeMode, int newWeatherMode, long serverDayTime, boolean serverRaining, float serverRainLevel, float serverThunderLevel) {
        timeMode = newTimeMode;
        weatherMode = newWeatherMode;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        if (timeMode == PacketHandler.S2C_EnvironmentOverride.MODE_NORMAL) {
            level.setDayTime(serverDayTime);
        }
        if (weatherMode == PacketHandler.S2C_EnvironmentOverride.MODE_NORMAL) {
            level.getLevelData().setRaining(serverRaining);
            level.setRainLevel(serverRainLevel);
            level.setThunderLevel(serverThunderLevel);
        }
        applyActiveOverrides(level);
    }

    private static void reset() {
        timeMode = PacketHandler.S2C_EnvironmentOverride.MODE_NORMAL;
        weatherMode = PacketHandler.S2C_EnvironmentOverride.MODE_NORMAL;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            reset();
            return;
        }

        applyActiveOverrides(level);
    }

    private static void applyActiveOverrides(ClientLevel level) {
        // Negative dayTime tells the vanilla client to store the absolute value and stop local daylight progression.
        if (timeMode == PacketHandler.S2C_EnvironmentOverride.TIME_DAY) {
            level.setDayTime(-6000L);
        } else if (timeMode == PacketHandler.S2C_EnvironmentOverride.TIME_NIGHT) {
            level.setDayTime(-18000L);
        }

        if (weatherMode == PacketHandler.S2C_EnvironmentOverride.WEATHER_CLEAR) {
            level.getLevelData().setRaining(false);
            level.setRainLevel(0.0F);
            level.setThunderLevel(0.0F);
            level.setSkyFlashTime(0);
            removeTrackedLightning(level);
        } else if (weatherMode == PacketHandler.S2C_EnvironmentOverride.WEATHER_RAIN) {
            level.getLevelData().setRaining(true);
            level.setRainLevel(1.0F);
            level.setThunderLevel(0.0F);
        }
    }

    private static void removeTrackedLightning(ClientLevel level) {
        List<Integer> lightningIds = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof LightningBolt) {
                lightningIds.add(entity.getId());
            }
        }
        for (int entityId : lightningIds) {
            level.removeEntity(entityId, Entity.RemovalReason.DISCARDED);
        }
    }
}
