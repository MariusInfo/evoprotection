package org.evocraft.evoprotection.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.evocraft.evoprotection.manager.ClaimManager;
import org.evocraft.evoprotection.manager.LanguageManager;
import org.evocraft.evoprotection.network.PacketHandler;

import java.util.*;

public class FlagScreen extends Screen {
    private final Screen parent;
    private final Map<String, Map<String, Boolean>> myFlags;
    private final List<String> myClaimIds;
    private final Map<String, String> claimDisplayNames;
    private final Map<String, String> claimOwnerNames;
    private final Map<String, String> viewerRoles;
    private final Set<String> leaveableClaimIds;

    private int currentClaimIndex = 0;
    private final int imageWidth = 380;
    private final int imageHeight = 335;
    private final List<CustomButton> buttons = new ArrayList<>();

    public FlagScreen(Screen parent, Map<String, Map<String, Boolean>> flags,
                      Set<String> allClaimNames, Map<String, String> claimDisplayNames,
                      Map<String, String> claimOwnerNames, Map<String, String> viewerRoles,
                      Set<String> leaveableClaimIds) {
        super(Component.literal("Flags Manager"));
        this.parent = parent;
        this.myFlags = flags;
        this.myClaimIds = new ArrayList<>(allClaimNames);
        this.claimDisplayNames = new HashMap<>(claimDisplayNames != null ? claimDisplayNames : new HashMap<>());
        this.claimOwnerNames = new HashMap<>(claimOwnerNames != null ? claimOwnerNames : new HashMap<>());
        this.viewerRoles = new HashMap<>(viewerRoles != null ? viewerRoles : new HashMap<>());
        this.leaveableClaimIds = new HashSet<>(leaveableClaimIds != null ? leaveableClaimIds : Set.of());
        sortClaims();
    }

    public void updateData(String json) {
        org.evocraft.evoprotection.manager.ClaimManager.SyncData data = new com.google.gson.Gson().fromJson(json, org.evocraft.evoprotection.manager.ClaimManager.SyncData.class);
        this.myFlags.clear();
        if (data.myFlags != null) this.myFlags.putAll(data.myFlags);
        this.myClaimIds.clear();
        Set<String> flagClaims = data.flagClaimNames != null ? data.flagClaimNames : data.allClaimNames;
        if (flagClaims != null) this.myClaimIds.addAll(flagClaims);
        this.claimDisplayNames.clear();
        if (data.claimDisplayNames != null) this.claimDisplayNames.putAll(data.claimDisplayNames);
        this.claimOwnerNames.clear();
        if (data.claimOwnerNames != null) this.claimOwnerNames.putAll(data.claimOwnerNames);
        this.viewerRoles.clear();
        if (data.viewerRoles != null) this.viewerRoles.putAll(data.viewerRoles);
        this.leaveableClaimIds.clear();
        if (data.leaveableClaimNames != null) this.leaveableClaimIds.addAll(data.leaveableClaimNames);
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
        Set<String> seenDisplayNames = new HashSet<>();
        this.myClaimIds.removeIf(id -> !id.startsWith("room:") && !seenDisplayNames.add(getDisplayName(id)));
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

        String currentClaimId = myClaimIds.get(currentClaimIndex);
        if (leaveableClaimIds.contains(currentClaimId)) {
            buttons.add(new CustomButton(LanguageManager.get("gui.flags.leave"),
                    x + imageWidth - 90, y + imageHeight - 30, 80, 20,
                    () -> PacketHandler.INSTANCE.sendToServer(
                            new PacketHandler.C2S_LeaveProtection(currentClaimId)), true, true));
        }

        buttons.add(new CustomButton("<", x + 20, y + 35, 20, 20, () -> {
            if (currentClaimIndex > 0) { currentClaimIndex--; this.init(); }
        }));
        buttons.add(new CustomButton(">", x + 340, y + 35, 20, 20, () -> {
            if (currentClaimIndex < myClaimIds.size() - 1) { currentClaimIndex++; this.init(); }
        }));

        int startY = y + 70;

        // Left Column
        addFlagButton("gui.flags.pvp", "pvp", x + 15, startY);
        addFlagButton("gui.flags.doors", "doors", x + 15, startY + 25);
        addFlagButton("gui.flags.use", "use", x + 15, startY + 50);
        addFlagButton("gui.flags.interact", "interact_entities", x + 15, startY + 75);
        addFlagButton("gui.flags.pickup", "item_pickup", x + 15, startY + 100);
        addFlagButton("gui.flags.natural_animals", "natural_animals", x + 15, startY + 125);
        addFlagButton("gui.flags.spawner_animals", "spawner_animals", x + 15, startY + 150);
        addFlagButton("gui.flags.always_middle_day", "always_middle_day", x + 15, startY + 175);
        addFlagButton("gui.flags.always_shiny", "always_shiny", x + 15, startY + 200);

        // Right Column
        addFlagButton("gui.flags.explosions", "explosions", x + 195, startY);
        addFlagButton("gui.flags.chests", "chests", x + 195, startY + 25);
        addFlagButton("gui.flags.public_build", "public_build", x + 195, startY + 50);
        addFlagButton("gui.flags.carry_on", "carry_on", x + 195, startY + 75);
        addFlagButton("gui.flags.hurt_animals", "hurt_animals", x + 195, startY + 100);
        addFlagButton("gui.flags.natural_monsters", "natural_monsters", x + 195, startY + 125);
        addFlagButton("gui.flags.spawner_monsters", "spawner_monsters", x + 195, startY + 150);
        addFlagButton("gui.flags.always_middle_night", "always_middle_night", x + 195, startY + 175);
        addFlagButton("gui.flags.always_rain", "always_rain", x + 195, startY + 200);
    }

    private void addFlagButton(String labelKey, String flag, int x, int y) {
        buttons.add(new CustomButton(LanguageManager.get(labelKey) + getStatus(flag),
                x, y, 170, 20, () -> toggleFlag(flag), canEditCurrentFlag(flag), false));
    }

    private boolean canEditCurrentFlag(String flag) {
        if (myClaimIds.isEmpty()) return false;
        if (this.parent instanceof AdminClaimMapScreen) return true;
        String claimId = myClaimIds.get(currentClaimIndex);
        return ClaimManager.roleCanEditFlag(viewerRoles.get(claimId), flag);
    }

    private String getStatus(String flag) {
        if (myClaimIds.isEmpty()) return LanguageManager.get("gui.status.off");
        String currentClaimId = myClaimIds.get(currentClaimIndex);
        boolean status = myFlags.getOrDefault(currentClaimId, new HashMap<>()).getOrDefault(flag, false);
        return status ? LanguageManager.get("gui.status.on") : LanguageManager.get("gui.status.off");
    }

    private void toggleFlag(String flag) {
        if (myClaimIds.isEmpty() || !canEditCurrentFlag(flag)) return;
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
            String ownerName = claimOwnerNames.getOrDefault(currentClaimId, "Unknown");
            g.drawCenteredString(this.font, LanguageManager.get("gui.flags.owner", ownerName), sw / 2, y + 54, 0xFFAAAAAA);
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
        boolean enabled;
        boolean danger;

        public CustomButton(String text, int x, int y, int w, int h, Runnable action) {
            this(text, x, y, w, h, action, true, false);
        }

        public CustomButton(String text, int x, int y, int w, int h, Runnable action,
                            boolean enabled, boolean danger) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.action = action;
            this.enabled = enabled;
            this.danger = danger;
        }

        public void render(GuiGraphics g, int mx, int my, net.minecraft.client.gui.Font font) {
            boolean hover = enabled && mx >= x && mx <= x + w && my >= y && my <= y + h;
            int background = danger ? 0xAA241010 : 0xAA141C14;
            int border = danger
                    ? (hover ? 0xFFFF5555 : 0xFFAA3333)
                    : (hover ? 0xFF45996C : 0xFF2D5947);
            if (!enabled) {
                background = 0xCC111411;
                border = 0xFF343A34;
            }
            fillRounded(g, x, y, w, h, background);
            outlineRounded(g, x, y, w, h, border);
            if (!enabled) g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0x66000000);

            String renderedText = enabled ? text : net.minecraft.ChatFormatting.stripFormatting(text);
            if (renderedText == null) renderedText = "";
            int textWidth = Math.max(1, font.width(renderedText));
            float textScale = Math.min(1.0f, (w - 8.0f) / textWidth);
            g.pose().pushPose();
            g.pose().translate(x + w / 2f, y + (h - 8 * textScale) / 2f, 0);
            g.pose().scale(textScale, textScale, 1.0f);
            int textColor = !enabled ? 0xFF777777
                    : danger ? (hover ? 0xFFFFAAAA : 0xFFFF7777)
                    : (hover ? 0xFFFFFF : 0xFFDDDDDD);
            g.drawCenteredString(font, renderedText, 0, 0, textColor);
            g.pose().popPose();
        }

        public boolean checkClick(int mx, int my) {
            if (mx >= x && mx <= x + w && my >= y && my <= y + h) {
                if (enabled) action.run();
                return true;
            }
            return false;
        }
    }
}
