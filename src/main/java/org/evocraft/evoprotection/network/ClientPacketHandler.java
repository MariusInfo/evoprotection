package org.evocraft.evoprotection.network;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.ModList;
import org.evocraft.evoprotection.gui.AdminClaimMapScreen;
import org.evocraft.evoprotection.gui.ClaimMapScreen;
import org.evocraft.evoprotection.gui.DeleteClaimScreen;
import org.evocraft.evoprotection.gui.FlagScreen;
import org.evocraft.evoprotection.gui.TrustScreen;
import org.evocraft.evoprotection.manager.LanguageManager;

public class ClientPacketHandler {

    public static void handleSyncLanguage(String lang) {
        LanguageManager.clientLang = lang;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            mc.screen.init(mc, mc.screen.width, mc.screen.height);
        }
    }

    public static void handleSyncPayDay(int secondsLeft, boolean isIdle) {
        org.evocraft.evoprotection.client.ClientPayDayData.secondsLeft = secondsLeft;
        org.evocraft.evoprotection.client.ClientPayDayData.isIdle = isIdle;
    }

    public static void handleSyncClaimData(String jsonMapData, boolean isAdminMap) {
        Minecraft mc = Minecraft.getInstance();

        if (ModList.get().isLoaded("evohub") && !isAdminMap) {
            if (mc.screen instanceof ClaimMapScreen screen) {
                screen.updateData(jsonMapData);
            } else if (mc.screen instanceof FlagScreen fScreen) {
                fScreen.updateData(jsonMapData);
            } else if (mc.screen instanceof TrustScreen tScreen) {
                tScreen.updateData(jsonMapData);
            } else if (mc.screen instanceof DeleteClaimScreen dScreen) {
                dScreen.updateData(jsonMapData);
            } else {
                ClaimMapScreen newScreen = new ClaimMapScreen();
                mc.setScreen(newScreen);
                newScreen.updateData(jsonMapData);
            }
        } else {
            if (isAdminMap) {
                if (mc.screen instanceof AdminClaimMapScreen screen) {
                    screen.updateData(jsonMapData);
                } else {
                    AdminClaimMapScreen newScreen = new AdminClaimMapScreen();
                    mc.setScreen(newScreen);
                    newScreen.updateData(jsonMapData);
                }
            } else {
                if (mc.screen instanceof ClaimMapScreen screen) {
                    screen.updateData(jsonMapData);
                } else if (mc.screen instanceof FlagScreen fScreen) {
                    fScreen.updateData(jsonMapData);
                } else if (mc.screen instanceof TrustScreen tScreen) {
                    tScreen.updateData(jsonMapData);
                } else if (mc.screen instanceof DeleteClaimScreen dScreen) {
                    dScreen.updateData(jsonMapData);
                } else {
                    ClaimMapScreen newScreen = new ClaimMapScreen();
                    mc.setScreen(newScreen);
                    newScreen.updateData(jsonMapData);
                }
            }
        }
    }
}