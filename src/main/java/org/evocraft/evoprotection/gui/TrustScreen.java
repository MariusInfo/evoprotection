package org.evocraft.evoprotection.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.evocraft.evoprotection.manager.ProtectionConfig;
import org.evocraft.evoprotection.manager.LanguageManager;
import org.evocraft.evoprotection.network.PacketHandler;

import java.util.*;

public class TrustScreen extends Screen {
    private final Screen parent; private final Map<String, Map<UUID, String>> trustedPerClaim; private final List<String> myClaimNames;
    private int currentClaimIndex = 0; private EditBox nameInput; private final int imageWidth = 320; private final int imageHeight = 280; private final List<CustomButton> buttons = new ArrayList<>();

    public TrustScreen(Screen parent, Map<String, Map<UUID, String>> trusted, Set<String> allClaimNames) {
        super(Component.literal("Trust Manager")); this.parent = parent; this.trustedPerClaim = trusted; this.myClaimNames = new ArrayList<>(allClaimNames); Collections.sort(this.myClaimNames);
    }
    public void updateData(String json) {
        org.evocraft.evoprotection.manager.ClaimManager.SyncData data = new com.google.gson.Gson().fromJson(json, org.evocraft.evoprotection.manager.ClaimManager.SyncData.class);
        this.trustedPerClaim.clear(); if (data.trustedPerClaim != null) this.trustedPerClaim.putAll(data.trustedPerClaim);
        if (this.parent instanceof ClaimMapScreen cms) cms.updateData(json); this.init();
    }
    private float getScale() { double guiScale = this.minecraft != null ? this.minecraft.getWindow().getGuiScale() : 1.0; if (guiScale >= 4) return 0.65f; if (guiScale >= 3) return 0.85f; return 1.0f; }
    private void fillRounded(GuiGraphics g, int x, int y, int w, int h, int color) { g.fill(x + 1, y, x + w - 1, y + h, color); g.fill(x, y + 1, x + w, y + h - 1, color); }
    private void outlineRounded(GuiGraphics g, int x, int y, int w, int h, int color) { g.fill(x + 1, y, x + w - 1, y + 1, color); g.fill(x + 1, y + h - 1, x + w - 1, y + h, color); g.fill(x, y + 1, x + 1, y + h - 1, color); g.fill(x + w - 1, y + 1, x + w, y + h - 1, color); }

    @Override
    protected void init() {
        float scale = getScale(); int sw = (int) (this.width / scale); int sh = (int) (this.height / scale); int x = (sw - imageWidth) / 2; int y = (sh - imageHeight) / 2;
        this.clearWidgets(); buttons.clear();

        buttons.add(new CustomButton(LanguageManager.get("gui.button.back"), x + 10, y + imageHeight - 30, 120, 20, () -> this.minecraft.setScreen(parent)));
        if (myClaimNames.isEmpty()) return;
        buttons.add(new CustomButton("<", x + 20, y + 35, 20, 20, () -> { if (currentClaimIndex > 0) { currentClaimIndex--; this.init(); } }));
        buttons.add(new CustomButton(">", x + 280, y + 35, 20, 20, () -> { if (currentClaimIndex < myClaimNames.size() - 1) { currentClaimIndex++; this.init(); } }));

        nameInput = new EditBox(this.font, x + 20, y + 80, 160, 20, Component.literal("Player Name")); this.addRenderableWidget(nameInput);
        buttons.add(new CustomButton(LanguageManager.get("gui.trust.btn_add"), x + 190, y + 80, 110, 20, () -> {
            String name = nameInput.getValue(); String currentClaim = myClaimNames.get(currentClaimIndex);
            if (!name.isEmpty() && currentClaim != null) { PacketHandler.INSTANCE.sendToServer(new PacketHandler.C2S_ManageTrust(name, "", true, currentClaim)); }
        }));

        String currentClaim = myClaimNames.get(currentClaimIndex); Map<UUID, String> activeFriends = trustedPerClaim.getOrDefault(currentClaim, new HashMap<>());
        int listY = y + 125;
        for (Map.Entry<UUID, String> entry : activeFriends.entrySet()) {
            UUID uuid = entry.getKey();
            buttons.add(new CustomButton(LanguageManager.get("gui.button.delete"), x + 240, listY - 2, 60, 16, () -> { PacketHandler.INSTANCE.sendToServer(new PacketHandler.C2S_ManageTrust("", uuid.toString(), false, currentClaim)); }));
            listY += 25; if (listY > y + imageHeight - 40) break;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g); float scale = getScale(); g.pose().pushPose(); g.pose().scale(scale, scale, 1.0f);
        int smx = (int) (mouseX / scale); int smy = (int) (mouseY / scale); int sw = (int) (this.width / scale); int sh = (int) (this.height / scale); int x = (sw - imageWidth) / 2; int y = (sh - imageHeight) / 2;
        fillRounded(g, x, y, imageWidth, imageHeight, 0xEE0D140D); outlineRounded(g, x, y, imageWidth, imageHeight, 0xFF83B755);

        boolean isPlot = ProtectionConfig.get().isPlotMode; String title = isPlot ? LanguageManager.get("gui.trust.title_plot") : LanguageManager.get("gui.trust.title");
        g.drawCenteredString(this.font, title, sw/2, y + 12, 0xFF83B755); g.fill(x, y + 28, x + imageWidth, y + 29, 0xFF3A592D);

        if (myClaimNames.isEmpty()) {
            String emptyMsg = isPlot ? LanguageManager.get("gui.trust.no_plot") : LanguageManager.get("gui.trust.no_claim");
            g.drawCenteredString(this.font, emptyMsg, sw/2, y + 100, 0xFFFFFF);
        } else {
            String currentClaim = myClaimNames.get(currentClaimIndex); String claimLabel = isPlot ? LanguageManager.get("gui.trust.selected_plot") : LanguageManager.get("gui.trust.selected_claim");
            g.drawCenteredString(this.font, claimLabel + currentClaim, sw/2, y + 40, 0xFFFFFF);
            g.drawString(this.font, LanguageManager.get("gui.trust.add"), x + 20, y + 68, 0xAAAAAA, false);
            g.drawString(this.font, LanguageManager.get("gui.trust.list"), x + 20, y + 110, 0xAAAAAA, false);

            Map<UUID, String> activeFriends = trustedPerClaim.getOrDefault(currentClaim, new HashMap<>()); int listY = y + 125;
            if (activeFriends.isEmpty()) { g.drawString(this.font, LanguageManager.get("gui.trust.empty"), x + 25, listY, 0xFF777777, false);
            } else { for (String name : activeFriends.values()) { g.drawString(this.font, "• §f" + name, x + 25, listY + 2, 0xFFFFFF, false); listY += 25; if (listY > y + imageHeight - 40) break; } }
        }
        for (CustomButton b : buttons) b.render(g, smx, smy, this.font); super.render(g, smx, smy, partialTick); g.pose().popPose();
    }
    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) { float scale = getScale(); double smx = mouseX / scale; double smy = mouseY / scale; for (CustomButton b : buttons) { if (b.checkClick((int)smx, (int)smy)) return true; } return super.mouseClicked(smx, smy, button); }

    class CustomButton {
        String text; int x, y, w, h; Runnable action; public CustomButton(String text, int x, int y, int w, int h, Runnable action) { this.text = text; this.x = x; this.y = y; this.w = w; this.h = h; this.action = action; }
        public void render(GuiGraphics g, int mx, int my, net.minecraft.client.gui.Font font) { boolean hover = mx >= x && mx <= x + w && my >= y && my <= y + h; fillRounded(g, x, y, w, h, 0xAA141C14); outlineRounded(g, x, y, w, h, hover ? 0xFF6C9945 : 0xFF3A592D); g.pose().pushPose(); g.pose().translate(x + w / 2f, y + (h - 8) / 2f, 0); g.drawCenteredString(font, text, 0, 0, hover ? 0xFFFFFF : 0xFFDDDDDD); g.pose().popPose(); }
        public boolean checkClick(int mx, int my) { if (mx >= x && mx <= x + w && my >= y && my <= y + h) { action.run(); return true; } return false; }
    }
}