package org.evocraft.evoprotection.gui;

import com.google.gson.Gson;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import org.evocraft.evocore.client.ClientBalanceData;
import org.evocraft.evocore.util.EvoCurrencyFormatter;
import org.evocraft.evoprotection.manager.ClaimManager;
import org.evocraft.evoprotection.manager.LanguageManager;
import org.evocraft.evoprotection.network.PacketHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ClaimMapScreen extends Screen {

    public static boolean currentServerIsPlot = false;
    public static int currentPlotSize = 4;
    public static int currentRoadSize = 1;

    private static final int BORDER_COLOR = 0xFF83B755;
    private static final int BG_COLOR = 0xEE0D140D;

    private float currentW = 550, currentH = 330;
    private final int targetW = 620, targetH = 370;

    private int radius = 4;
    private int pixelScale = 2;
    private int chunkSize = 32;
    private int mapSize = 288;

    private ClaimManager.SyncData cachedData;
    private ChunkPos centerChunk;
    private EditBox nameField;
    private final List<CustomButton> buttons = new ArrayList<>();

    private String guiMsg = "";
    private int msgTicks = 0;
    private int msgColor = 0xFF5555;

    private DynamicTexture mapTexture;
    private ResourceLocation mapTextureLocation;

    public ClaimMapScreen() {
        super(Component.literal("Protections Map"));
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

    private void showMsg(String text, int color, int duration) {
        this.guiMsg = text;
        this.msgColor = color;
        this.msgTicks = duration;
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();
        buttons.clear();
        if (this.minecraft == null || this.minecraft.player == null) return;
        this.centerChunk = this.minecraft.player.chunkPosition();

        float scale = getScale();
        int sw = (int) (this.width / scale);
        int sh = (int) (this.height / scale);

        this.radius = 4;
        this.pixelScale = 2;
        this.chunkSize = 16 * this.pixelScale;
        this.mapSize = (this.radius * 2 + 1) * this.chunkSize;

        int finalX = (sw - targetW) / 2;
        int finalY = (sh - targetH) / 2;

        boolean isPlot = currentServerIsPlot;

        String fieldText = isPlot ? LanguageManager.get("gui.map.plot_name") : LanguageManager.get("gui.map.chunk_name");
        nameField = new EditBox(this.font, finalX + 20, finalY + targetH - 30, 110, 20, Component.literal(fieldText));
        nameField.setMaxLength(20);
        nameField.visible = false;
        this.addRenderableWidget(nameField);

        // The language button was REMOVED as requested

        String upgradeBtnText = isPlot ? LanguageManager.get("gui.button.buy_plot") : LanguageManager.get("gui.button.buy_chunk");
        buttons.add(new CustomButton(upgradeBtnText, finalX + 135, finalY + targetH - 30, 115, 20, () -> {
            if (cachedData != null) {
                if (ClientBalanceData.getBalance() >= cachedData.nextSlotCost) {
                    PacketHandler.INSTANCE.sendToServer(new PacketHandler.C2S_BuyClaimSlot());
                    showMsg(LanguageManager.get("msg.buy.success"), 0xFF55FF55, 60);
                } else {
                    showMsg(LanguageManager.get("msg.buy.no_money"), 0xFFFF5555, 60);
                }
            }
        }));

        if (isPlot) {
            buttons.add(new CustomButton(LanguageManager.get("gui.button.friends"), finalX + 255, finalY + targetH - 30, 80, 20, () -> {
                if (cachedData != null) {
                    Set<String> trustClaims = cachedData.trustClaimNames != null ? cachedData.trustClaimNames : cachedData.allClaimNames;
                    this.minecraft.setScreen(new TrustScreen(this, cachedData.trustedPerClaim, cachedData.trustedRolesPerClaim, trustClaims, cachedData.claimDisplayNames));
                }
            }));
            buttons.add(new CustomButton(LanguageManager.get("gui.button.delete"), finalX + 340, finalY + targetH - 30, 80, 20, () -> {
                if (cachedData != null) this.minecraft.setScreen(new DeleteClaimScreen(this, cachedData.allClaimNames, cachedData.claimDisplayNames));
            }));
        } else {
            buttons.add(new CustomButton(LanguageManager.get("gui.button.friends"), finalX + 255, finalY + targetH - 30, 75, 20, () -> {
                if (cachedData != null) {
                    Set<String> trustClaims = cachedData.trustClaimNames != null ? cachedData.trustClaimNames : cachedData.allClaimNames;
                    this.minecraft.setScreen(new TrustScreen(this, cachedData.trustedPerClaim, cachedData.trustedRolesPerClaim, trustClaims, cachedData.claimDisplayNames));
                }
            }));
            buttons.add(new CustomButton(LanguageManager.get("gui.button.settings"), finalX + 335, finalY + targetH - 30, 75, 20, () -> {
                if (cachedData != null) {
                    Set<String> flagClaims = cachedData.flagClaimNames != null ? cachedData.flagClaimNames : cachedData.allClaimNames;
                    this.minecraft.setScreen(new FlagScreen(this, cachedData.myFlags, flagClaims, cachedData.claimDisplayNames));
                }
            }));
            buttons.add(new CustomButton(LanguageManager.get("gui.button.delete"), finalX + 415, finalY + targetH - 30, 70, 20, () -> {
                if (cachedData != null) this.minecraft.setScreen(new DeleteClaimScreen(this, cachedData.allClaimNames, cachedData.claimDisplayNames));
            }));
        }
        generateMapTexture();
    }

    public void updateData(String json) {
        this.cachedData = new Gson().fromJson(json, ClaimManager.SyncData.class);
    }

    private void generateMapTexture() {
        int mapDiameterChunks = this.radius * 2 + 1;
        int mapBlocks = mapDiameterChunks * 16;
        int finalImageSize = mapBlocks * this.pixelScale;
        NativeImage image = new NativeImage(finalImageSize, finalImageSize, false);

        if (this.minecraft.level != null) {
            for (int cx = -this.radius; cx <= this.radius; cx++) {
                for (int cz = -this.radius; cz <= this.radius; cz++) {
                    int worldChunkX = centerChunk.x + cx;
                    int worldChunkZ = centerChunk.z + cz;
                    int imgBaseX = (cx + this.radius) * 16;
                    int imgBaseZ = (cz + this.radius) * 16;

                    for (int bx = 0; bx < 16; bx++) {
                        for (int bz = 0; bz < 16; bz++) {
                            int worldX = worldChunkX * 16 + bx;
                            int worldZ = worldChunkZ * 16 + bz;
                            int worldY = this.minecraft.level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
                            BlockPos pos = new BlockPos(worldX, worldY - 1, worldZ);

                            int northY = this.minecraft.level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ - 1);
                            int deltaY = worldY - northY;
                            int color = this.minecraft.level.getBlockState(pos).getMapColor(this.minecraft.level, pos).col;

                            double shade = 1.0;
                            if (deltaY > 0) shade = 1.20;
                            else if (deltaY < 0) shade = 0.80;

                            int r = Math.min(255, Math.max(0, (int) (((color >> 16) & 0xFF) * shade)));
                            int g = Math.min(255, Math.max(0, (int) (((color >> 8) & 0xFF) * shade)));
                            int b = Math.min(255, Math.max(0, (int) ((color & 0xFF) * shade)));

                            for (int sx = 0; sx < this.pixelScale; sx++) {
                                for (int sz = 0; sz < this.pixelScale; sz++) {
                                    int imgFinalX = (imgBaseX + bx) * this.pixelScale + sx;
                                    int imgFinalZ = (imgBaseZ + bz) * this.pixelScale + sz;

                                    double pixelShade = (this.pixelScale >= 2 && (sx == 0 || sz == 0)) ? 0.85 : 1.0;
                                    int pixelAbgr = (0xFF << 24) | (Math.min(255, Math.max(0, (int)(b * pixelShade))) << 16) |
                                            (Math.min(255, Math.max(0, (int)(g * pixelShade))) << 8) |
                                            Math.min(255, Math.max(0, (int)(r * pixelShade)));

                                    image.setPixelRGBA(imgFinalX, imgFinalZ, pixelAbgr);
                                }
                            }
                        }
                    }
                }
            }
        }
        if (mapTexture != null) mapTexture.close();
        mapTexture = new DynamicTexture(image);
        mapTextureLocation = this.minecraft.getTextureManager().register("evo_claim_map", mapTexture);
    }

    private boolean isRoad(int chunkX, int chunkZ) {
        if (!currentServerIsPlot) return false;
        int P = currentPlotSize;
        int C = P + currentRoadSize;
        int localX = ((chunkX % C) + C) % C;
        int localZ = ((chunkZ % C) + C) % C;
        return localX >= P || localZ >= P;
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

        currentW += (targetW - currentW) * 0.15f;
        currentH += (targetH - currentH) * 0.15f;

        int w = (int) currentW;
        int h = (int) currentH;
        int x = (sw - w) / 2;
        int y = (sh - h) / 2;

        fillRounded(g, x, y, w, h, BG_COLOR);
        outlineRounded(g, x, y, w, h, BORDER_COLOR);

        boolean isPlot = currentServerIsPlot;
        String title = isPlot ? LanguageManager.get("gui.map.title_plot") : LanguageManager.get("gui.map.title_chunk");
        String limitText = isPlot ? LanguageManager.get("gui.map.limit_plot") : LanguageManager.get("gui.map.limit_chunk");

        g.drawCenteredString(this.font, title, sw / 2, y + 12, BORDER_COLOR);
        g.fill(x, y + 28, x + w, y + 29, 0xFF3A592D);

        boolean isAnimDone = currentW > targetW - 10;
        if (nameField != null) nameField.visible = isAnimDone;

        if (isAnimDone) {
            int mapX = x + targetW - this.mapSize - 25;
            int mapY = y + 35;

            g.fill(mapX, mapY, mapX + this.mapSize, mapY + this.mapSize, 0xFF1A1A1A);
            g.renderOutline(mapX - 1, mapY - 1, this.mapSize + 2, this.mapSize + 2, 0xFFFFFFFF);

            if (mapTextureLocation != null) g.blit(mapTextureLocation, mapX, mapY, 0, 0, this.mapSize, this.mapSize, this.mapSize, this.mapSize);
            drawClaimsOverlay(g, smx, smy, mapX, mapY);

            int infoX = x + 25;
            int infoY = y + 50;
            g.drawString(this.font, LanguageManager.get("gui.map.info"), infoX, infoY, 0xFFFFFF, false);
            if (cachedData != null) {
                g.drawString(this.font, limitText + " §f" + cachedData.usedSlots + " / " + cachedData.maxSlots, infoX, infoY + 25, 0xAAAAAA, false);
                String bal = EvoCurrencyFormatter.formatWithCurrency(ClientBalanceData.getBalance());
                g.drawString(this.font, LanguageManager.get("gui.map.money", bal), infoX, infoY + 45, 0xAAAAAA, false);
                g.drawString(this.font, LanguageManager.get("gui.map.price", EvoCurrencyFormatter.formatWithCurrency(cachedData.nextSlotCost)), infoX, infoY + 65, 0xAAAAAA, false);
            }
            g.drawString(this.font, LanguageManager.get("gui.map.click_info"), infoX, infoY + 110, 0xAAAAAA, false);
            g.drawString(this.font, isPlot ? LanguageManager.get("gui.map.click_plot") : LanguageManager.get("gui.map.click_chunk"), infoX, infoY + 125, 0xAAAAAA, false);
            g.drawString(this.font, LanguageManager.get("gui.map.click_delete"), infoX, infoY + 140, 0xAAAAAA, false);
            for (CustomButton b : buttons) b.render(g, smx, smy, this.font);
        }

        super.render(g, smx, smy, partialTick);

        if (msgTicks > 0) {
            g.fill(sw/2 - 120, sh/2 - 15, sw/2 + 120, sh/2 + 15, 0xDD000000);
            g.renderOutline(sw/2 - 120, sh/2 - 15, 240, 30, msgColor);
            g.drawCenteredString(this.font, guiMsg, sw/2, sh/2 - 4, msgColor);
            msgTicks--;
        }
        g.pose().popPose();
    }

    private void drawClaimsOverlay(GuiGraphics g, int mx, int my, int startX, int startY) {
        String tooltipL1 = null;
        String tooltipL2 = null;
        boolean isPlot = currentServerIsPlot;

        for (int cx = -this.radius; cx <= this.radius; cx++) {
            for (int cz = -this.radius; cz <= this.radius; cz++) {
                int drawX = startX + (cx + this.radius) * this.chunkSize;
                int drawY = startY + (cz + this.radius) * this.chunkSize;
                int rChunkX = centerChunk.x + cx;
                int rChunkZ = centerChunk.z + cz;
                String key = rChunkX + ";" + rChunkZ;

                g.renderOutline(drawX, drawY, this.chunkSize, this.chunkSize, 0x2AFFFFFF);

                if (isRoad(rChunkX, rChunkZ)) {
                    g.fill(drawX, drawY, drawX + this.chunkSize, drawY + this.chunkSize, 0x77555555);
                    if (mx >= drawX && mx <= drawX + this.chunkSize && my >= drawY && my <= drawY + this.chunkSize) {
                        tooltipL1 = "§7[Public Road]";
                        g.fill(drawX, drawY, drawX + this.chunkSize, drawY + this.chunkSize, 0x33FFFFFF);
                    }
                    continue;
                }

                ClaimManager.ClientClaimInfo info = cachedData != null ? cachedData.map.get(key) : null;

                if (info != null) {
                    boolean isMine = info.ownerUUID != null && Minecraft.getInstance().player != null && info.ownerUUID.equals(Minecraft.getInstance().player.getUUID());
                    boolean isAdmin = (info.ownerName != null && info.ownerName.contains("Admin")) || (info.ownerUUID != null && info.ownerUUID.getMostSignificantBits() == 0);

                    int fillColor = isAdmin ? 0x44FF5500 : (isMine ? 0x6600FFFF : 0x55FF0000);
                    int borderColor = isAdmin ? 0xFFFF5500 : (isMine ? 0xFF00FFFF : 0xFFFF0000);

                    g.fill(drawX, drawY, drawX + this.chunkSize, drawY + this.chunkSize, fillColor);
                    g.renderOutline(drawX, drawY, this.chunkSize, this.chunkSize, borderColor);
                }

                if (cx == 0 && cz == 0) {
                    int c_x = drawX + this.chunkSize / 2;
                    int c_y = drawY + this.chunkSize / 2;
                    g.fill(c_x - 2, c_y - 2, c_x + 2, c_y + 2, 0xFFFFFF00);
                }

                if (mx >= drawX && mx <= drawX + this.chunkSize && my >= drawY && my <= drawY + this.chunkSize) {
                    g.fill(drawX, drawY, drawX + this.chunkSize, drawY + this.chunkSize, 0x33FFFFFF);
                    if (info != null) {
                        String prefix = isPlot ? "Plot" : "Chunk";
                        tooltipL1 = info.ownerUUID != null && Minecraft.getInstance().player != null && info.ownerUUID.equals(Minecraft.getInstance().player.getUUID()) ? "§bYour " + prefix : "§cLand occupied by " + info.ownerName;
                        if (info.customName != null && !info.customName.isEmpty()) tooltipL2 = "§8(§e" + info.customName + "§8)";
                    } else {
                        tooltipL1 = isPlot ? "§aFree Plot (Click to buy)" : "§aFree Chunk (Click to buy)";
                    }
                }
            }
        }
        if (tooltipL1 != null) {
            if (tooltipL2 != null) g.renderTooltip(this.font, java.util.List.of(Component.literal(tooltipL1), Component.literal(tooltipL2)), java.util.Optional.empty(), mx, my);
            else g.renderTooltip(this.font, Component.literal(tooltipL1), mx, my);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        float scale = getScale();
        double smx = mouseX / scale;
        double smy = mouseY / scale;

        if (currentW > targetW - 10) {
            for (CustomButton b : buttons) {
                if (b.checkClick((int)smx, (int)smy)) return true;
            }

            int sw = (int) (this.width / scale);
            int sh = (int) (this.height / scale);
            int x = (sw - targetW) / 2;
            int y = (sh - targetH) / 2;
            int mapX = x + targetW - this.mapSize - 25;
            int mapY = y + 35;

            for (int cx = -this.radius; cx <= this.radius; cx++) {
                for (int cz = -this.radius; cz <= this.radius; cz++) {
                    int drawX = mapX + (cx + this.radius) * this.chunkSize;
                    int drawY = mapY + (cz + this.radius) * this.chunkSize;

                    if (smx >= drawX && smx <= drawX + this.chunkSize && smy >= drawY && smy <= drawY + this.chunkSize) {
                        int rChunkX = centerChunk.x + cx;
                        int rChunkZ = centerChunk.z + cz;

                        if (isRoad(rChunkX, rChunkZ)) {
                            showMsg(LanguageManager.get("gui.map.road"), 0xFFFF5555, 60);
                            return true;
                        }

                        String key = rChunkX + ";" + rChunkZ;

                        ClaimManager.ClientClaimInfo info = cachedData != null ? cachedData.map.get(key) : null;
                        boolean isMine = info != null && Minecraft.getInstance().player != null && info.ownerUUID.equals(Minecraft.getInstance().player.getUUID());

                        if (info == null) {
                            String claimName = nameField != null ? nameField.getValue().trim() : "";
                            if (claimName.isEmpty() || claimName.equals(LanguageManager.get("gui.map.plot_name")) || claimName.equals(LanguageManager.get("gui.map.chunk_name"))) {
                                showMsg(LanguageManager.get("gui.map.name_req"), 0xFFFF5555, 80);
                                return true;
                            }
                            PacketHandler.INSTANCE.sendToServer(new PacketHandler.C2S_ClaimAction(rChunkX, rChunkZ, true, claimName));
                        } else if (isMine) {
                            PacketHandler.INSTANCE.sendToServer(new PacketHandler.C2S_ClaimAction(rChunkX, rChunkZ, false, ""));
                        } else {
                            showMsg(LanguageManager.get("gui.map.owned_other"), 0xFFFF5555, 60);
                        }
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(smx, smy, button);
    }

    @Override
    public void removed() {
        super.removed();
        if (mapTexture != null) mapTexture.close();
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
            outlineRounded(g, x, y, w, h, hover ? 0xFF6C9945 : 0xFF3A592D);
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
