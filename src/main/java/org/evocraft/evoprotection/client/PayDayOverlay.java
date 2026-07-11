package org.evocraft.evoprotection.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.evocraft.evoprotection.EvoProtection;

@Mod.EventBusSubscriber(modid = EvoProtection.MODID, value = Dist.CLIENT)
public class PayDayOverlay {

    @SubscribeEvent
    public static void onRender(RenderGuiOverlayEvent.Post event) {
        // Renderizăm deasupra de Hotbar
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;

        // Dacă nu suntem pe modul Plot, ascunde panoul definitiv
        if (!ClientPayDayData.isActive) return;

        Minecraft mc = Minecraft.getInstance();
        // Nu randăm dacă playerul are meniul de F3 deschis (debug)
        if (mc.options.renderDebug) return;

        GuiGraphics g = event.getGuiGraphics();
        int screenWidth = event.getWindow().getGuiScaledWidth();
        int screenHeight = event.getWindow().getGuiScaledHeight();

        // Dimensiunile căsuței din dreapta jos
        int boxWidth = 90;
        int boxHeight = 30;
        int padding = 5;

        int x = screenWidth - boxWidth - padding;
        int y = screenHeight - boxHeight - padding;

        // Desenăm background negru-transparent
        g.fill(x, y, x + boxWidth, y + boxHeight, 0xAA0D140D);

        // Desenăm conturul: Portocaliu dacă e în AFK, Verde dacă e normal
        int outlineColor = ClientPayDayData.isIdle ? 0xFFFFAA00 : 0xFF83B755;
        g.renderOutline(x, y, boxWidth, boxHeight, outlineColor);

        // Titlul Sus
        String title = "§lPayDay";
        g.drawString(mc.font, title, x + (boxWidth - mc.font.width(title)) / 2, y + 4, 0xFFFFFF, false);

        // Timpul Jos
        String status;
        if (ClientPayDayData.isIdle) {
            status = "§e§lPAUSED";
        } else {
            status = "§a" + formatTime(ClientPayDayData.secondsLeft);
        }

        g.drawString(mc.font, status, x + (boxWidth - mc.font.width(status)) / 2, y + 17, 0xFFFFFF, false);
    }

    private static String formatTime(int seconds) {
        int m = Math.max(0, seconds / 60);
        int s = Math.max(0, seconds % 60);
        return String.format("%02d:%02d", m, s);
    }
}
