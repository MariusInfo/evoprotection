package org.evocraft.evoprotection.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
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
    private EditBox salePriceBox;
    private EditBox rentPriceBox;

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
    protected void init() {
        int w = 270;
        int h = screenHeight();
        int x = (width - w) / 2;
        int y = (height - h) / 2;

        salePriceBox = new EditBox(font, x + 34, y + 91, 108, 18, Component.literal("Sale price"));
        salePriceBox.setMaxLength(15);
        salePriceBox.setFilter(this::isPriceText);
        salePriceBox.setValue(initialPrice(buyPrice));

        rentPriceBox = new EditBox(font, x + 34, y + 129, 108, 18, Component.literal("Rent price"));
        rentPriceBox.setMaxLength(15);
        rentPriceBox.setFilter(this::isPriceText);
        rentPriceBox.setValue(initialPrice(rentPrice));

        boolean manageBought = mode == PacketHandler.S2C_OpenRoomOffer.MODE_MANAGE_BOUGHT;
        salePriceBox.visible = manageBought;
        salePriceBox.active = manageBought;
        rentPriceBox.visible = manageBought;
        rentPriceBox.active = manageBought;
        addRenderableWidget(salePriceBox);
        addRenderableWidget(rentPriceBox);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        int w = 270;
        int h = screenHeight();
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
            Rect listSale = listSaleButton(x, y);
            Rect listRent = listRentButton(x, y);
            graphics.drawString(font, "Sale price", x + 34, y + 80, MUTED, false);
            graphics.drawString(font, "Rent / 24h", x + 34, y + 118, MUTED, false);
            drawButton(graphics, listSale, "SET SALE", true, listSale.contains(mouseX, mouseY));
            drawButton(graphics, listRent, "SET RENT", true, listRent.contains(mouseX, mouseY));
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
        int h = screenHeight();
        int x = (width - w) / 2;
        int y = (height - h) / 2;

        if (mode == PacketHandler.S2C_OpenRoomOffer.MODE_MANAGE_RENT) {
            Rect cancel = new Rect(x + 68, y + 82, 134, 25);
            if (cancel.contains(mouseX, mouseY)) {
                sendAction(ProtectionRoomManager.RoomOfferAction.CANCEL_RENT, 0.0D);
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (mode == PacketHandler.S2C_OpenRoomOffer.MODE_MANAGE_BOUGHT) {
            if (listSaleButton(x, y).contains(mouseX, mouseY)) {
                double price = parsePrice(salePriceBox);
                if (price >= 0.0D) {
                    sendAction(ProtectionRoomManager.RoomOfferAction.LIST_SELL, price);
                }
                return true;
            }
            if (listRentButton(x, y).contains(mouseX, mouseY)) {
                double price = parsePrice(rentPriceBox);
                if (price >= 0.0D) {
                    sendAction(ProtectionRoomManager.RoomOfferAction.LIST_RENT, price);
                }
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (canBuy && buyButton(x, y).contains(mouseX, mouseY)) {
            sendAction(ProtectionRoomManager.RoomOfferAction.BUY, 0.0D);
            return true;
        }

        if (canRent && rentButton(x, y).contains(mouseX, mouseY)) {
            sendAction(ProtectionRoomManager.RoomOfferAction.RENT, 0.0D);
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void sendAction(ProtectionRoomManager.RoomOfferAction action, double requestedPrice) {
        PacketHandler.INSTANCE.sendToServer(new PacketHandler.C2S_RoomOfferChoice(roomId, action, requestedPrice));
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

    private Rect listSaleButton(int x, int y) {
        return new Rect(x + 154, y + 89, 82, 22);
    }

    private Rect listRentButton(int x, int y) {
        return new Rect(x + 154, y + 127, 82, 22);
    }

    private int screenHeight() {
        return mode == PacketHandler.S2C_OpenRoomOffer.MODE_MANAGE_BOUGHT ? 178 : 150;
    }

    private void closeWithClick() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        onClose();
    }

    private String formatPrice(double price) {
        return price < 0.0D ? "Unavailable" : EvoCurrencyFormatter.formatWithCurrency(price);
    }

    private String initialPrice(double price) {
        if (!Double.isFinite(price) || price < 0.0D) return "";
        if (price == Math.rint(price)) return Long.toString((long) price);
        return Double.toString(price);
    }

    private boolean isPriceText(String value) {
        return value.matches("\\d{0,12}(\\.\\d{0,2})?");
    }

    private double parsePrice(EditBox box) {
        if (box == null || box.getValue().isBlank()) return -1.0D;
        try {
            double value = Double.parseDouble(box.getValue());
            return Double.isFinite(value) && value >= 0.0D ? value : -1.0D;
        } catch (NumberFormatException ignored) {
            return -1.0D;
        }
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
