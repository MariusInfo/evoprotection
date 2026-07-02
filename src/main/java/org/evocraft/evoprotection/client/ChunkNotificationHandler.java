package org.evocraft.evoprotection.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.evocraft.evoprotection.EvoProtection;

@Mod.EventBusSubscriber(modid = EvoProtection.MODID, value = Dist.CLIENT)
public class ChunkNotificationHandler {
    private static String displayMessage = "";
    private static String subMessage = "";
    private static boolean isWilderness = true;
    private static int animationTick = 0;

    private static final int FADE_IN = 15;
    private static final int STAY = 60;
    private static final int FADE_OUT = 15;

    public static void onEnterNewChunk(String ownerName) {
        String newMessage;
        String newSub;
        boolean wild = false;

        if (ownerName.equals("Wilderness")) {
            newMessage = "Wilderness";
            newSub = "Teritoriu Liber (Fără protecție)";
            wild = true;
        } else {
            newMessage = ownerName;
            newSub = "Zonă Protejată";
        }

        if (displayMessage.equals(newMessage)) return;

        displayMessage = newMessage;
        subMessage = newSub;
        isWilderness = wild;

        // Resetăm animația
        animationTick = FADE_IN + STAY + FADE_OUT;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && animationTick > 0) {
            animationTick--;
        }
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        // --- FIX-UL PENTRU LAG: Randează O SINGURĂ DATĂ PER FRAME pe Hotbar! ---
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;

        if (animationTick > 0 && !displayMessage.isEmpty()) {
            GuiGraphics g = event.getGuiGraphics();
            Minecraft mc = Minecraft.getInstance();
            int w = event.getWindow().getGuiScaledWidth();

            // Calculare Transparență și Efect de Slide (Lerp & Pow)
            float alpha = 1.0f;
            if (animationTick > (STAY + FADE_OUT)) {
                alpha = 1.0f - ((float)(animationTick - (STAY + FADE_OUT)) / FADE_IN);
            } else if (animationTick < FADE_OUT) {
                alpha = (float)animationTick / FADE_OUT;
            }

            float easeAlpha = 1.0f - (float) Math.pow(1.0f - alpha, 3);
            int alphaInt = (int)(easeAlpha * 255) << 24;
            int bgAlpha = (int)(easeAlpha * 200) << 24;

            int boxWidth = Math.max((int)(mc.font.width(displayMessage) * 1.2f), mc.font.width(subMessage)) + 60;
            int boxHeight = 35;
            int x = (w - boxWidth) / 2;
            int y = (int) (20 * easeAlpha); // Slide in de sus

            RenderSystem.enableBlend();

            // Liniile Neon (Verde EvoHub pt natura, Roșu aprins pt claim)
            int borderColor = isWilderness ? (0xFF83B755 & 0x00FFFFFF | alphaInt) : (0xFFFF3333 & 0x00FFFFFF | alphaInt);

            // Simulam colturile rotunjite ale EvoHub
            g.fill(x + 1, y, x + boxWidth - 1, y + boxHeight, 0x000000 | bgAlpha);
            g.fill(x, y + 1, x + boxWidth, y + boxHeight - 1, 0x000000 | bgAlpha);

            g.fill(x + 1, y, x + boxWidth - 1, y + 1, borderColor); // Sus
            g.fill(x + 1, y + boxHeight - 1, x + boxWidth - 1, y + boxHeight, borderColor); // Jos
            g.fill(x, y + 1, x + 1, y + boxHeight - 1, borderColor); // Stanga
            g.fill(x + boxWidth - 1, y + 1, x + boxWidth, y + boxHeight - 1, borderColor); // Dreapta

            // Titlul (Scalat)
            g.pose().pushPose();
            g.pose().translate(w / 2f, y + 8, 0);
            g.pose().scale(1.2f, 1.2f, 1.2f);
            String titleColor = isWilderness ? "§a§l" : "§c§l";
            g.drawCenteredString(mc.font, titleColor + displayMessage, 0, 0, 0xFFFFFF | alphaInt);
            g.pose().popPose();

            // Subtitlul
            g.drawCenteredString(mc.font, subMessage, w / 2, y + 22, 0xAAAAAA | alphaInt);

            RenderSystem.disableBlend();
        }
    }
}