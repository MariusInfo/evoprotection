package org.evocraft.evoprotection.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.evocraft.evoprotection.manager.LanguageManager;
import org.evocraft.evoprotection.network.PacketHandler;

import java.util.*;

public class FlagScreen extends Screen {
    private final Screen parent;
    private final Map<String, Map<String, Boolean>> myFlags;
    private final List<String> myClaimIds;
    private final Map<String, String> claimDisplayNames;

    private int currentClaimIndex = 0;
    private final int imageWidth = 320;
    private final int imageHeight = 285;
    private final List<CustomButton> buttons = new ArrayList<>();

    public FlagScreen(Screen parent, Map<String, Map<String, Boolean>> flags, Set<String> allClaimNames, Map<String, String> claimDisplayNames) {
        super(Component.literal("Flags Manager"));
        this.parent = parent;
        this.myFlags = flags;
        this.myClaimIds = new ArrayList<>(allClaimNames);
        this.claimDisplayNames = new HashMap<>(claimDisplayNames != null ? claimDisplayNames : new HashMap<>());
        sortClaims();
    }

    public void updateData(String json) {
        org.evocraft.evoprotection.manager.ClaimManager.SyncData data = new com.google.gson.Gson().fromJson(json, org.evocraft.evoprotection.manager.ClaimManager.SyncData.class);
        this.myFlags.clear();
        if (data.myFlags != null) this.myFlags.putAll(data.myFlags);
        this.myClaimIds.clear();
        if (data.allClaimNames != null) this.myClaimIds.addAll(data.allClaimNames);
        this.claimDisplayNames.clear();
        if (data.claimDisplayNames != null) this.claimDisplayNames.putAll(data.claimDisplayNames);
        sortClaims();

        if (this.parent instanceof ClaimMapScreen cms) {
            cms.updateData(json);
        } else if (this.parent instanceof AdminClaimMapScreen acms) {
            acms.updateData(json);
        }
        this.init();
    }

    private void sortClaims() {
        this.myClaimIds.sort(Comparator.comparing(this::getDisplayName).thenComparing(id -> id));
        if (currentClaimIndex >= myClaimIds.size()) {
            currentClaimIndex = Math.max(0, myClaimIds.size() - 1);
        }
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

        buttons.add(new CustomButton("<", x + 20, y + 35, 20, 20, () -> {
            if (currentClaimIndex > 0) { currentClaimIndex--; this.init(); }
        }));
        buttons.add(new CustomButton(">", x + 280, y + 35, 20, 20, () -> {
            if (currentClaimIndex < myClaimIds.size() - 1) { currentClaimIndex++; this.init(); }
        }));

        int startY = y + 70;

        // Left Column
        buttons.add(new CustomButton(LanguageManager.get("gui.flags.pvp") + getStatus("pvp"), x + 15, startY, 140, 20, () -> toggleFlag("pvp")));
        buttons.add(new CustomButton(LanguageManager.get("gui.flags.doors") + getStatus("doors"), x + 15, startY + 25, 140, 20, () -> toggleFlag("doors")));
        buttons.add(new CustomButton(LanguageManager.get("gui.flags.use") + getStatus("use"), x + 15, startY + 50, 140, 20, () -> toggleFlag("use")));
        buttons.add(new CustomButton(LanguageManager.get("gui.flags.interact") + getStatus("interact_entities"), x + 15, startY + 75, 140, 20, () -> toggleFlag("interact_entities")));
        buttons.add(new CustomButton(LanguageManager.get("gui.flags.pickup") + getStatus("item_pickup"), x + 15, startY + 100, 140, 20, () -> toggleFlag("item_pickup")));
        buttons.add(new CustomButton(LanguageManager.get("gui.flags.natural_animals") + getStatus("natural_animals"), x + 15, startY + 125, 140, 20, () -> toggleFlag("natural_animals")));
        buttons.add(new CustomButton(LanguageManager.get("gui.flags.spawner_animals") + getStatus("spawner_animals"), x + 15, startY + 150, 140, 20, () -> toggleFlag("spawner_animals")));

        // Right Column
        buttons.add(new CustomButton(LanguageManager.get("gui.flags.explosions") + getStatus("explosions"), x + 165, startY, 140, 20, () -> toggleFlag("explosions")));
        buttons.add(new CustomButton(LanguageManager.get("gui.flags.chests") + getStatus("chests"), x + 165, startY + 25, 140, 20, () -> toggleFlag("chests")));
        buttons.add(new CustomButton(LanguageManager.get("gui.flags.public_build") + getStatus("public_build"), x + 165, startY + 50, 140, 20, () -> toggleFlag("public_build")));
        buttons.add(new CustomButton(LanguageManager.get("gui.flags.carry_on") + getStatus("carry_on"), x + 165, startY + 75, 140, 20, () -> toggleFlag("carry_on")));
        buttons.add(new CustomButton(LanguageManager.get("gui.flags.hurt_animals") + getStatus("hurt_animals"), x + 165, startY + 100, 140, 20, () -> toggleFlag("hurt_animals")));
        buttons.add(new CustomButton(LanguageManager.get("gui.flags.natural_monsters") + getStatus("natural_monsters"), x + 165, startY + 125, 140, 20, () -> toggleFlag("natural_monsters")));
        buttons.add(new CustomButton(LanguageManager.get("gui.flags.spawner_monsters") + getStatus("spawner_monsters"), x + 165, startY + 150, 140, 20, () -> toggleFlag("spawner_monsters")));
    }

    private String getStatus(String flag) {
        if (myClaimIds.isEmpty()) return LanguageManager.get("gui.status.off");
        String currentClaimId = myClaimIds.get(currentClaimIndex);
        boolean status = myFlags.getOrDefault(currentClaimId, new HashMap<>()).getOrDefault(flag, false);
        return status ? LanguageManager.get("gui.status.on") : LanguageManager.get("gui.status.off");
    }

    private void toggleFlag(String flag) {
        if (myClaimIds.isEmpty()) return;
        String currentClaimId = myClaimIds.get(currentClaimIndex);
        boolean currentState = myFlags.getOrDefault(currentClaimId, new HashMap<>()).getOrDefault(flag, false);
        boolean isAdminMode = (this.parent instanceof AdminClaimMapScreen);

        PacketHandler.INSTANCE.sendToServer(new PacketHandler.C2S_UpdateFlag(currentClaimId, flag, !currentState, isAdminMode));
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
        outlineRounded(g, x, y, imageWidth, imageHeight, 0xFF55B783);

        g.drawCenteredString(this.font, LanguageManager.get("gui.flags.title"), sw/2, y + 12, 0xFF55B783);
        g.fill(x, y + 28, x + imageWidth, y + 29, 0xFF2D5947);

        if (myClaimIds.isEmpty()) {
            g.drawCenteredString(this.font, LanguageManager.get("gui.trust.no_claim"), sw/2, y + 100, 0xFFFFFF);
        } else {
            String currentClaimId = myClaimIds.get(currentClaimIndex);
            g.drawCenteredString(this.font, LanguageManager.get("gui.flags.desc1") + getDisplayName(currentClaimId), sw/2, y + 40, 0xFFFFFF);
            g.drawCenteredString(this.font, LanguageManager.get("gui.flags.desc2"), sw/2, y + imageHeight - 55, 0xAAAAAA);
        }

        for (CustomButton b : buttons) {
            b.render(g, smx, smy, this.font);
        }

        super.render(g, smx, smy, partialTick);
        g.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        float scale = getScale();
        double smx = mouseX / scale;
        double smy = mouseY / scale;

        for (CustomButton b : buttons) {
            if (b.checkClick((int)smx, (int)smy)) return true;
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
            outlineRounded(g, x, y, w, h, hover ? 0xFF45996C : 0xFF2D5947);
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
