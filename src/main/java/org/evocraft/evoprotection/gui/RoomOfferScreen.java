package org.evocraft.evoprotection.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.evocraft.evocore.util.EvoCurrencyFormatter;
import org.evocraft.evoprotection.manager.ProtectionRoomManager;
import org.evocraft.evoprotection.network.PacketHandler;

public class RoomOfferScreen extends Screen {
    private static final int BG = 0xF0040907;
    private static final int PANEL = 0xE8091B14;
    private static final int PANEL_2 = 0xAA0E2A20;
    private static final int GREEN = 0xFF23F29B;
    private static final int GREEN_DARK = 0xFF08472F;
    private static final int TEXT = 0xFFEAFBF2;
    private static final int MUTED = 0xFF8EA99D;
    private static final int WARN = 0xFFFFD166;

    private final String roomId;
    private final String roomName;
    private final double buyPrice;
    private final double rentPrice;
    private final int mode;
    private final boolean canBuy;
    private final boolean canRent;

    public RoomOfferScreen(String roomId, String roomName, double buyPrice, double rentPrice,
                           int mode, boolean canBuy, boolean canRent) {
        super(Component.literal("Room Offer"));
        this.roomId = roomId == null ? "" : roomId;
        this.roomName = roomName == null || roomName.isBlank() ? "Room" : roomName;
        this.buyPrice = buyPrice;
        this.rentPrice = rentPrice;
        this.mode = mode;
        this.canBuy = canBuy;
        this.canRent = canRent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        int w = 270;
        int h = 150;
        int x = (width - w) / 2;
        int y = (height - h) / 2;

        graphics.fillGradient(x, y, x + w, y + h, BG, 0xF00B1712);
        frame(graphics, x, y, w, h, 0xCC23F29B);
        panel(graphics, x + 14, y + 14, w - 28, h - 28, "ROOM OFFER");

        graphics.drawCenteredString(font, trim(roomName, 190), x + w / 2, y + 40, TEXT);
        graphics.drawCenteredString(font, subtitle(), x + w / 2, y + 56, MUTED);

        Rect buy = buyButton(x, y);
        Rect rent = rentButton(x, y);
        if (mode == PacketHandler.S2C_OpenRoomOffer.MODE_MANAGE_RENT) {
            Rect cancel = new Rect(x + 68, y + 82, 134, 25);
            drawButton(graphics, cancel, "CANCEL RENT", true, cancel.contains(mouseX, mouseY));
            graphics.drawCenteredString(font, "Stops future 24h payments", x + w / 2, cancel.y + 31, WARN);
        } else if (mode == PacketHandler.S2C_OpenRoomOffer.MODE_MANAGE_BOUGHT) {
            drawButton(graphics, buy, "SELL", buyPrice >= 0.0D, buy.contains(mouseX, mouseY));
            drawButton(graphics, rent, "RENT", rentPrice >= 0.0D, rent.contains(mouseX, mouseY));
            graphics.drawCenteredString(font, formatPrice(buyPrice), buy.x + buy.w / 2, buy.y + 31, WARN);
            graphics.drawCenteredString(font, formatPrice(rentPrice), rent.x + rent.w / 2, rent.y + 31, WARN);
        } else {
            drawButton(graphics, buy, "BUY", canBuy, buy.contains(mouseX, mouseY));
            drawButton(graphics, rent, "RENT", canRent, rent.contains(mouseX, mouseY));
            graphics.drawCenteredString(font, canBuy ? formatPrice(buyPrice) : "Unavailable", buy.x + buy.w / 2, buy.y + 31, WARN);
            graphics.drawCenteredString(font, canRent ? formatPrice(rentPrice) : "Unavailable", rent.x + rent.w / 2, rent.y + 31, WARN);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        int w = 270;
        int h = 150;
        int x = (width - w) / 2;
        int y = (height - h) / 2;

        if (mode == PacketHandler.S2C_OpenRoomOffer.MODE_MANAGE_RENT) {
            Rect cancel = new Rect(x + 68, y + 82, 134, 25);
            if (cancel.contains(mouseX, mouseY)) {
                sendAction(ProtectionRoomManager.RoomOfferAction.CANCEL_RENT);
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (mode == PacketHandler.S2C_OpenRoomOffer.MODE_MANAGE_BOUGHT) {
            if (buyPrice >= 0.0D && buyButton(x, y).contains(mouseX, mouseY)) {
                sendAction(ProtectionRoomManager.RoomOfferAction.LIST_SELL);
                return true;
            }
            if (rentPrice >= 0.0D && rentButton(x, y).contains(mouseX, mouseY)) {
                sendAction(ProtectionRoomManager.RoomOfferAction.LIST_RENT);
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (canBuy && buyButton(x, y).contains(mouseX, mouseY)) {
            sendAction(ProtectionRoomManager.RoomOfferAction.BUY);
            return true;
        }

        if (canRent && rentButton(x, y).contains(mouseX, mouseY)) {
            sendAction(ProtectionRoomManager.RoomOfferAction.RENT);
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void sendAction(ProtectionRoomManager.RoomOfferAction action) {
        PacketHandler.INSTANCE.sendToServer(new PacketHandler.C2S_RoomOfferChoice(roomId, action));
        closeWithClick();
    }

    private String subtitle() {
        if (mode == PacketHandler.S2C_OpenRoomOffer.MODE_MANAGE_RENT) {
            return "Manage your active rental";
        }
        if (mode == PacketHandler.S2C_OpenRoomOffer.MODE_MANAGE_BOUGHT) {
            return "List this room for sale or rent";
        }
        return "Choose an option for this room";
    }

    private Rect buyButton(int x, int y) {
        return new Rect(x + 36, y + 82, 86, 25);
    }

    private Rect rentButton(int x, int y) {
        return new Rect(x + 148, y + 82, 86, 25);
    }

    private void closeWithClick() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        onClose();
    }

    private String formatPrice(double price) {
        return price < 0.0D ? "Unavailable" : EvoCurrencyFormatter.formatWithCurrency(price);
    }

    private void panel(GuiGraphics graphics, int x, int y, int w, int h, String label) {
        graphics.fill(x, y, x + w, y + h, PANEL);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL_2);
        graphics.fill(x, y, x + w, y + 1, 0xCC23F29B);
        graphics.drawString(font, label, x + 8, y + 7, 0xFF6EF4B7, false);
    }

    private void frame(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y, x + 1, y + h, color);
        graphics.fill(x + w - 1, y, x + w, y + h, color);
    }

    private void drawButton(GuiGraphics graphics, Rect rect, String label, boolean active, boolean hover) {
        int border = active ? (hover ? 0xFF8DFFD0 : GREEN) : 0xFF365346;
        int top = active ? (hover ? 0xFF147D55 : 0xFF0E5D40) : 0xFF1A2A22;
        int bottom = active ? (hover ? 0xFF0C5239 : GREEN_DARK) : 0xFF111A16;
        graphics.fillGradient(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h, top, bottom);
        frame(graphics, rect.x, rect.y, rect.w, rect.h, border);
        graphics.drawCenteredString(font, label, rect.x + rect.w / 2, rect.y + 8, active ? TEXT : MUTED);
    }

    private String trim(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        String ellipsis = "...";
        while (!value.isEmpty() && font.width(value + ellipsis) > maxWidth) {
            value = value.substring(0, value.length() - 1);
        }
        return value + ellipsis;
    }

    private record Rect(int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && my >= y && mx < x + w && my < y + h;
        }
    }
}
