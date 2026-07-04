package org.evocraft.evoprotection.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.evocraft.evoprotection.manager.LanguageManager;
import org.evocraft.evoprotection.network.PacketHandler;

import java.util.*;

public class DeleteClaimScreen extends Screen {
    private final Screen parent;
    private final List<String> myClaimIds;
    private final Map<String, String> claimDisplayNames;
    private final int imageWidth = 320;
    private final int imageHeight = 280;
    private final List<CustomButton> buttons = new ArrayList<>();

    public DeleteClaimScreen(Screen parent, Set<String> allClaimNames, Map<String, String> claimDisplayNames) {
        super(Component.literal("Delete Protections"));
        this.parent = parent;
        this.myClaimIds = new ArrayList<>(allClaimNames);
        this.claimDisplayNames = new HashMap<>(claimDisplayNames != null ? claimDisplayNames : new HashMap<>());
        sortClaims();
    }

    public void updateData(String json) {
        org.evocraft.evoprotection.manager.ClaimManager.SyncData data = new com.google.gson.Gson().fromJson(json, org.evocraft.evoprotection.manager.ClaimManager.SyncData.class);
        this.myClaimIds.clear();
        if (data.allClaimNames != null) this.myClaimIds.addAll(data.allClaimNames);

        this.claimDisplayNames.clear();
        if (data.claimDisplayNames != null) this.claimDisplayNames.putAll(data.claimDisplayNames);
        sortClaims();

        if (this.parent instanceof ClaimMapScreen cms) cms.updateData(json);
        this.init();
    }

    private void sortClaims() {
        this.myClaimIds.sort(Comparator.comparing(this::getDisplayName).thenComparing(id -> id));
    }

    private String getDisplayName(String claimId) {
        return claimDisplayNames.getOrDefault(claimId, claimId);
    }

    private float getScale() {
        double guiScale = this.minecraft != null ? this.minecraft.getWindow().getGuiScale() : 1.0;
        if (guiScale >= 4) return 0.65f;
        if (guiScale >= 3) return 0.85f;
        return 1.0f;
    }

    private void fillRounded(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + w, y + h - 1, color);
    }

    private void outlineRounded(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x + 1, y, x + w - 1, y + 1, color);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    @Override
    protected void init() {
        float scale = getScale();
        int sw = (int) (this.width / scale);
        int sh = (int) (this.height / scale);
        int x = (sw - imageWidth) / 2;
        int y = (sh - imageHeight) / 2;

        this.clearWidgets();
        buttons.clear();

        buttons.add(new CustomButton(LanguageManager.get("gui.button.back"), x + 10, y + imageHeight - 30, 120, 20, () -> this.minecraft.setScreen(parent)));
        if (myClaimIds.isEmpty()) return;

        int listY = y + 50;
        for (String claimId : myClaimIds) {
            buttons.add(new CustomButton(LanguageManager.get("gui.button.delete"), x + imageWidth - 85, listY - 2, 70, 16, () -> {
                PacketHandler.INSTANCE.sendToServer(new PacketHandler.C2S_DeleteClaimByName(claimId));
            }));
            listY += 25;
            if (listY > y + imageHeight - 40) break;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        float scale = getScale();
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1.0f);

        int smx = (int) (mouseX / scale);
        int smy = (int) (mouseY / scale);
        int sw = (int) (this.width / scale);
        int sh = (int) (this.height / scale);
        int x = (sw - imageWidth) / 2;
        int y = (sh - imageHeight) / 2;

        fillRounded(g, x, y, imageWidth, imageHeight, 0xEE0D140D);
        outlineRounded(g, x, y, imageWidth, imageHeight, 0xFFFF3333);

        g.drawCenteredString(this.font, LanguageManager.get("gui.delete.title"), sw / 2, y + 12, 0xFFFF3333);
        g.fill(x, y + 28, x + imageWidth, y + 29, 0xFF7A1C1C);

        if (myClaimIds.isEmpty()) {
            g.drawCenteredString(this.font, LanguageManager.get("gui.delete.empty"), sw / 2, y + 100, 0xFFFFFF);
        } else {
            int listY = y + 50;
            for (String claimId : myClaimIds) {
                g.drawString(this.font, "§e" + getDisplayName(claimId), x + 25, listY + 2, 0xFFFFFF, false);
                listY += 25;
                if (listY > y + imageHeight - 40) break;
            }
        }

        for (CustomButton b : buttons) b.render(g, smx, smy, this.font);
        super.render(g, smx, smy, partialTick);
        g.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        float scale = getScale();
        double smx = mouseX / scale;
        double smy = mouseY / scale;
        for (CustomButton b : buttons) {
            if (b.checkClick((int) smx, (int) smy)) return true;
        }
        return super.mouseClicked(smx, smy, button);
    }

    class CustomButton {
        String text;
        int x, y, w, h;
        Runnable action;

        public CustomButton(String text, int x, int y, int w, int h, Runnable action) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.action = action;
        }

        public void render(GuiGraphics g, int mx, int my, net.minecraft.client.gui.Font font) {
            boolean hover = mx >= x && mx <= x + w && my >= y && my <= y + h;
            fillRounded(g, x, y, w, h, 0xAA141C14);
            outlineRounded(g, x, y, w, h, hover ? 0xFFFF5555 : 0xFF7A1C1C);
            g.pose().pushPose();
            g.pose().translate(x + w / 2f, y + (h - 8) / 2f, 0);
            g.drawCenteredString(font, text, 0, 0, hover ? 0xFFFFFF : 0xFFDDDDDD);
            g.pose().popPose();
        }

        public boolean checkClick(int mx, int my) {
            if (mx >= x && mx <= x + w && my >= y && my <= y + h) {
                action.run();
                return true;
            }
            return false;
        }
    }
}
