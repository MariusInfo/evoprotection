package org.evocraft.evoprotection.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;
import org.evocraft.evocore.data.EconomyManager;
import org.evocraft.evocore.data.PlayerStatsManager;
import org.evocraft.evocore.database.DatabaseManager;
import org.evocraft.evoprotection.network.PacketHandler;
import org.evocraft.evoprotection.manager.LanguageManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ClaimManager {
    private static ClaimManager INSTANCE;
    private final Gson GSON = new GsonBuilder().create();

    private final Map<String, UUID> chunkOwners = new ConcurrentHashMap<>();
    private final Map<String, String> chunkCustomNames = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerNames = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> boughtSlots = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Set<UUID>>> trustedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Map<String, Boolean>>> claimFlags = new ConcurrentHashMap<>();

    // Dicționar pentru limba fiecărui jucător luată din Launcher (tabelul 'players')
    private final Map<UUID, String> playerLanguages = new ConcurrentHashMap<>();

    private int eventStock = 0;
    private double eventPrice = 0.0;

    private String getTblClaims() { return ProtectionConfig.get().isPlotMode ? "plot_claims" : "player_claims"; }
    private String getTblSettings() { return ProtectionConfig.get().isPlotMode ? "plot_player_settings" : "claim_player_settings"; }
    private String getTblFlags() { return ProtectionConfig.get().isPlotMode ? "plot_flags_data" : "claim_flags_data"; }
    private String getTblEvents() { return ProtectionConfig.get().isPlotMode ? "plot_global_events" : "claim_global_events"; }

    public static void initialize() {
        if (INSTANCE == null) {
            INSTANCE = new ClaimManager();
        }
    }

    public static ClaimManager get() {
        if (INSTANCE == null) {
            initialize();
        }
        return INSTANCE;
    }

    public ClaimManager() {
        loadFromDatabase();
    }

    // Funcția care interoghează baza de date "players" la conectare folosind NUMELE
    public void reloadPlayerLanguage(UUID uuid, String playerName) {
        String lang = "ro"; // Default
        synchronized (DatabaseManager.get()) {
            try {
                Connection conn = DatabaseManager.get().getConnection();
                // ATENȚIE: Dacă în tabel coloana pentru nume se numește 'name', modifică 'username' de mai jos în 'name'
                try (PreparedStatement stmt = conn.prepareStatement("SELECT language FROM players WHERE username = ?")) {
                    stmt.setString(1, playerName);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            String dbLang = rs.getString("language");
                            if (dbLang != null && !dbLang.isEmpty()) {
                                lang = dbLang;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        playerLanguages.put(uuid, lang);
    }

    public String getPlayerLanguage(UUID uuid) {
        return playerLanguages.getOrDefault(uuid, "ro");
    }

    public void loadFromDatabase() {
        chunkOwners.clear();
        chunkCustomNames.clear();
        playerNames.clear();
        boughtSlots.clear();
        trustedPlayers.clear();
        claimFlags.clear();
        playerLanguages.clear();

        synchronized (DatabaseManager.get()) {
            try {
                Connection conn = DatabaseManager.get().getConnection();
                try (java.sql.Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + getTblEvents() + " (" +
                            "id INT PRIMARY KEY, stock INT, price DOUBLE)");

                    stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + getTblSettings() + " (" +
                            "uuid VARCHAR(36) PRIMARY KEY, player_name VARCHAR(32), bought_slots INT, trusted_data TEXT)");

                    stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + getTblFlags() + " (" +
                            "uuid VARCHAR(36) PRIMARY KEY, flags_data TEXT)");

                    stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + getTblClaims() + " (" +
                            "chunk_key VARCHAR(64) PRIMARY KEY, owner_uuid VARCHAR(36), custom_name VARCHAR(64))");
                }
            } catch (Exception e) {
                System.err.println("[EvoProtection] Failed to initialize claim tables.");
                e.printStackTrace();
            }

            try {
                Connection conn = DatabaseManager.get().getConnection();
                try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + getTblEvents() + " WHERE id = 1");
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        eventStock = rs.getInt("stock");
                        eventPrice = rs.getDouble("price");
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }

            try {
                Connection conn = DatabaseManager.get().getConnection();
                try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + getTblSettings());
                     ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        playerNames.put(uuid, rs.getString("player_name"));
                        boughtSlots.put(uuid, rs.getInt("bought_slots"));

                        String trustedJson = rs.getString("trusted_data");
                        if (trustedJson != null && !trustedJson.isEmpty()) {
                            java.lang.reflect.Type type = new TypeToken<Map<String, Set<UUID>>>(){}.getType();
                            Map<String, Set<UUID>> trusted = GSON.fromJson(trustedJson, type);
                            trustedPlayers.put(uuid, trusted);
                        }
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }

            try {
                Connection conn = DatabaseManager.get().getConnection();
                try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + getTblFlags());
                     ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        String flagsJson = rs.getString("flags_data");
                        if (flagsJson != null && !flagsJson.isEmpty()) {
                            java.lang.reflect.Type type = new TypeToken<Map<String, Map<String, Boolean>>>(){}.getType();
                            Map<String, Map<String, Boolean>> flags = GSON.fromJson(flagsJson, type);
                            claimFlags.put(uuid, flags);
                        }
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }

            try {
                Connection conn = DatabaseManager.get().getConnection();
                try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + getTblClaims());
                     ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String chunkKey = rs.getString("chunk_key");
                        UUID ownerUuid = UUID.fromString(rs.getString("owner_uuid"));
                        String customName = rs.getString("custom_name");

                        chunkOwners.put(chunkKey, ownerUuid);
                        if (customName != null && !customName.isEmpty()) {
                            chunkCustomNames.put(chunkKey, customName);
                        }
                    }
                    System.out.println("[EvoProtection] Successfully loaded claims from MariaDB.");
                }
            } catch (Exception e) {
                System.err.println("[EvoProtection] Error loading claims from database.");
                e.printStackTrace();
            }
        }
    }

    private void saveGlobalEvent() {
        new Thread(() -> {
            synchronized (DatabaseManager.get()) {
                try {
                    Connection conn = DatabaseManager.get().getConnection();
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "INSERT INTO " + getTblEvents() + " (id, stock, price) VALUES (1, ?, ?) " +
                                    "ON DUPLICATE KEY UPDATE stock = ?, price = ?")) {
                        stmt.setInt(1, eventStock);
                        stmt.setDouble(2, eventPrice);
                        stmt.setInt(3, eventStock);
                        stmt.setDouble(4, eventPrice);
                        stmt.executeUpdate();
                    }
                } catch (Exception e) { e.printStackTrace(); }
            }
        }).start();
    }

    private void savePlayerSettings(UUID uuid) {
        if (uuid == null) return;
        final String name = playerNames.getOrDefault(uuid, "Unknown");
        final int slots = boughtSlots.getOrDefault(uuid, 1);
        final String trustedData = GSON.toJson(trustedPlayers.getOrDefault(uuid, new HashMap<>()));

        new Thread(() -> {
            synchronized (DatabaseManager.get()) {
                try {
                    Connection conn = DatabaseManager.get().getConnection();
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "INSERT INTO " + getTblSettings() + " (uuid, player_name, bought_slots, trusted_data) " +
                                    "VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE player_name = ?, bought_slots = ?, trusted_data = ?")) {
                        stmt.setString(1, uuid.toString());
                        stmt.setString(2, name);
                        stmt.setInt(3, slots);
                        stmt.setString(4, trustedData);
                        stmt.setString(5, name);
                        stmt.setInt(6, slots);
                        stmt.setString(7, trustedData);
                        stmt.executeUpdate();
                    }
                } catch (Exception e) { e.printStackTrace(); }
            }
        }).start();
    }

    private void savePlayerFlags(UUID uuid) {
        if (uuid == null) return;
        final String flagsData = GSON.toJson(claimFlags.getOrDefault(uuid, new HashMap<>()));
        new Thread(() -> {
            synchronized (DatabaseManager.get()) {
                try {
                    Connection conn = DatabaseManager.get().getConnection();
                    try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + getTblFlags() + " (uuid, flags_data) VALUES (?, ?) ON DUPLICATE KEY UPDATE flags_data = ?")) {
                        stmt.setString(1, uuid.toString());
                        stmt.setString(2, flagsData);
                        stmt.setString(3, flagsData);
                        stmt.executeUpdate();
                    }
                } catch (Exception e) { e.printStackTrace(); }
            }
        }).start();
    }

    private void saveClaim(String chunkKey, UUID ownerUuid, String customName) {
        new Thread(() -> {
            synchronized (DatabaseManager.get()) {
                try {
                    Connection conn = DatabaseManager.get().getConnection();
                    try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + getTblClaims() + " (chunk_key, owner_uuid, custom_name) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE owner_uuid = ?, custom_name = ?")) {
                        stmt.setString(1, chunkKey);
                        stmt.setString(2, ownerUuid.toString());
                        stmt.setString(3, customName != null ? customName : "");
                        stmt.setString(4, ownerUuid.toString());
                        stmt.setString(5, customName != null ? customName : "");
                        stmt.executeUpdate();
                    }
                } catch (Exception e) { e.printStackTrace(); }
            }
        }).start();
    }

    private void deleteClaimFromDB(String chunkKey) {
        new Thread(() -> {
            synchronized (DatabaseManager.get()) {
                try {
                    Connection conn = DatabaseManager.get().getConnection();
                    try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM " + getTblClaims() + " WHERE chunk_key = ?")) {
                        stmt.setString(1, chunkKey);
                        stmt.executeUpdate();
                    }
                } catch (Exception e) { e.printStackTrace(); }
            }
        }).start();
    }

    public ChunkPos getPlotBaseChunk(int plotX, int plotZ) {
        int cell = ProtectionConfig.get().plotSizeChunks + ProtectionConfig.get().roadSizeChunks;
        return new ChunkPos(plotX * cell, plotZ * cell);
    }

    public int[] getPlotCoordsFromChunk(ChunkPos pos) {
        int cell = ProtectionConfig.get().plotSizeChunks + ProtectionConfig.get().roadSizeChunks;
        int px = Math.floorDiv(pos.x, cell);
        int pz = Math.floorDiv(pos.z, cell);
        return new int[]{px, pz};
    }

    public boolean isRoadChunk(ChunkPos pos) {
        if (!ProtectionConfig.get().isPlotMode) return false;
        int P = ProtectionConfig.get().plotSizeChunks;
        int C = P + ProtectionConfig.get().roadSizeChunks;
        int localX = ((pos.x % C) + C) % C;
        int localZ = ((pos.z % C) + C) % C;
        return localX >= P || localZ >= P;
    }

    public int getUsedPlots(UUID p) {
        if (!ProtectionConfig.get().isPlotMode) return getUsedSlots(p);
        Set<String> uniquePlots = new HashSet<>();
        for (Map.Entry<String, UUID> entry : chunkOwners.entrySet()) {
            if (entry.getValue().equals(p)) {
                String[] parts = entry.getKey().split(";");
                int cx = Integer.parseInt(parts[0]);
                int cz = Integer.parseInt(parts[1]);
                int[] coords = getPlotCoordsFromChunk(new ChunkPos(cx, cz));
                uniquePlots.add(coords[0] + "," + coords[1]);
            }
        }
        return uniquePlots.size();
    }

    public void drawPlotAndRoads(ServerLevel level, int plotX, int plotZ) {
        if (!ProtectionConfig.get().isPlotMode) return;

        int P = ProtectionConfig.get().plotSizeChunks;
        int C = P + ProtectionConfig.get().roadSizeChunks;

        int startX = plotX * C * 16;
        int startZ = plotZ * C * 16;
        int cellMaxX = startX + C * 16 - 1;
        int cellMaxZ = startZ + C * 16 - 1;

        Block borderBlock = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(ProtectionConfig.get().borderBlock));
        Block roadBlock = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(ProtectionConfig.get().roadBlock));
        Block lineBlock = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(ProtectionConfig.get().lineBlock));

        if (borderBlock == null) borderBlock = net.minecraft.world.level.block.Blocks.SMOOTH_STONE_SLAB;
        if (roadBlock == null) roadBlock = net.minecraft.world.level.block.Blocks.CYAN_TERRACOTTA;
        if (lineBlock == null) lineBlock = net.minecraft.world.level.block.Blocks.YELLOW_CONCRETE;

        int yLevel;
        if (ProtectionConfig.get().useFixedYLevel) {
            yLevel = ProtectionConfig.get().plotYLevel;
        } else {
            yLevel = level.getHeight(Heightmap.Types.WORLD_SURFACE, startX + 8, startZ + 8) - 1;
            if (yLevel < 1) yLevel = 64;
        }

        for (int x = startX - 8; x <= cellMaxX + 8; x++) {
            for (int z = startZ - 8; z <= cellMaxZ + 8; z++) {
                int localX = ((x % (C*16)) + (C*16)) % (C*16);
                int localZ = ((z % (C*16)) + (C*16)) % (C*16);
                boolean isRoad = localX >= P*16 || localZ >= P*16;
                BlockPos pos = new BlockPos(x, yLevel, z);

                if (isRoad) {
                    if (localX == P*16 + 7 || localX == P*16 + 8 || localZ == P*16 + 7 || localZ == P*16 + 8) {
                        level.setBlock(pos, lineBlock.defaultBlockState(), 2);
                    } else {
                        level.setBlock(pos, roadBlock.defaultBlockState(), 2);
                    }
                    level.setBlock(pos.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                    level.setBlock(pos.above(2), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                    level.setBlock(pos.above(3), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                } else if (localX == 0 || localX == P*16 - 1 || localZ == 0 || localZ == P*16 - 1) {
                    level.setBlock(pos.above(), borderBlock.defaultBlockState(), 2);
                    level.setBlock(pos.above(2), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                    level.setBlock(pos.above(3), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    public boolean claimPlot(ServerPlayer player, int plotX, int plotZ, String customName) {
        String lang = getPlayerLanguage(player.getUUID());
        int P = ProtectionConfig.get().plotSizeChunks;
        ChunkPos base = getPlotBaseChunk(plotX, plotZ);
        String dim = player.level().dimension().location().toString();

        for(int i = 0; i < P; i++) {
            for(int j = 0; j < P; j++) {
                if (getChunkOwner(new ChunkPos(base.x + i, base.z + j), dim) != null) {
                    player.sendSystemMessage(Component.literal(LanguageManager.get(lang, "msg.plot.occupied")));
                    return false;
                }
            }
        }

        if (getUsedPlots(player.getUUID()) < getMaxSlots(player.getUUID())) {
            for(int i = 0; i < P; i++) {
                for(int j = 0; j < P; j++) {
                    ChunkPos cp = new ChunkPos(base.x + i, base.z + j);
                    String key = cp.x + ";" + cp.z + ";" + dim;
                    chunkOwners.put(key, player.getUUID());
                    chunkCustomNames.put(key, customName);
                    saveClaim(key, player.getUUID(), customName);
                }
            }
            playerNames.put(player.getUUID(), player.getGameProfile().getName());
            savePlayerSettings(player.getUUID());

            drawPlotAndRoads(player.serverLevel(), plotX, plotZ);

            syncToClient(player);
            PlayerStatsManager.get().updateClaims(player.getUUID(), player.getGameProfile().getName(), getUsedPlots(player.getUUID()));
            player.sendSystemMessage(Component.literal(LanguageManager.get(lang, "msg.plot.bought")));
            return true;
        } else {
            player.sendSystemMessage(Component.literal(LanguageManager.get(lang, "msg.plot.limit")));
        }
        return false;
    }

    public boolean unclaimPlot(ServerPlayer player, int plotX, int plotZ) {
        String lang = getPlayerLanguage(player.getUUID());
        int P = ProtectionConfig.get().plotSizeChunks;
        ChunkPos base = getPlotBaseChunk(plotX, plotZ);
        String dim = player.level().dimension().location().toString();
        boolean owned = false;

        for(int i = 0; i < P; i++) {
            for(int j = 0; j < P; j++) {
                ChunkPos cp = new ChunkPos(base.x + i, base.z + j);
                String key = cp.x + ";" + cp.z + ";" + dim;
                if (player.getUUID().equals(chunkOwners.get(key))) {
                    chunkOwners.remove(key);
                    chunkCustomNames.remove(key);
                    deleteClaimFromDB(key);
                    owned = true;
                }
            }
        }
        if (owned) {
            syncToClient(player);
            PlayerStatsManager.get().updateClaims(player.getUUID(), player.getGameProfile().getName(), getUsedPlots(player.getUUID()));
            player.sendSystemMessage(Component.literal(LanguageManager.get(lang, "msg.plot.abandoned")));
            return true;
        }
        return false;
    }

    public boolean unclaimByName(ServerPlayer player, String claimName) {
        String lang = getPlayerLanguage(player.getUUID());
        boolean removedAny = false;
        List<String> keysToRemove = new ArrayList<>();

        for (Map.Entry<String, UUID> entry : chunkOwners.entrySet()) {
            if (entry.getValue().equals(player.getUUID())) {
                String cName = chunkCustomNames.get(entry.getKey());
                if (claimName.equals(cName)) {
                    keysToRemove.add(entry.getKey());
                }
            }
        }

        for (String key : keysToRemove) {
            chunkOwners.remove(key);
            chunkCustomNames.remove(key);
            deleteClaimFromDB(key);
            removedAny = true;
        }

        if (removedAny) {
            syncToClient(player);
            int usedCount = ProtectionConfig.get().isPlotMode ? getUsedPlots(player.getUUID()) : getUsedSlots(player.getUUID());
            PlayerStatsManager.get().updateClaims(player.getUUID(), player.getGameProfile().getName(), usedCount);
            player.sendSystemMessage(Component.literal(LanguageManager.get(lang, "msg.claim.deleted", claimName)));
            return true;
        }
        return false;
    }

    public boolean claimChunk(ServerPlayer player, ChunkPos pos, String customName) {
        String lang = getPlayerLanguage(player.getUUID());
        if (ProtectionConfig.get().isPlotMode) {
            if (isRoadChunk(pos)) {
                player.sendSystemMessage(Component.literal(LanguageManager.get(lang, "msg.road.reserved")));
                return false;
            }
            int[] plotCoords = getPlotCoordsFromChunk(pos);
            return claimPlot(player, plotCoords[0], plotCoords[1], customName);
        }

        String key = pos.x + ";" + pos.z + ";" + player.level().dimension().location().toString();
        if (chunkOwners.containsKey(key)) return false;

        if (getUsedSlots(player.getUUID()) < getMaxSlots(player.getUUID())) {
            chunkOwners.put(key, player.getUUID());
            playerNames.put(player.getUUID(), player.getGameProfile().getName());
            chunkCustomNames.put(key, customName);
            saveClaim(key, player.getUUID(), customName);
            savePlayerSettings(player.getUUID());

            syncToClient(player);
            PlayerStatsManager.get().updateClaims(player.getUUID(), player.getGameProfile().getName(), getUsedSlots(player.getUUID()));
            return true;
        }
        return false;
    }

    public boolean unclaimChunk(ServerPlayer player, ChunkPos pos) {
        if (ProtectionConfig.get().isPlotMode) {
            int[] plotCoords = getPlotCoordsFromChunk(pos);
            return unclaimPlot(player, plotCoords[0], plotCoords[1]);
        }

        String key = pos.x + ";" + pos.z + ";" + player.level().dimension().location().toString();
        if (player.getUUID().equals(chunkOwners.get(key))) {
            chunkOwners.remove(key);
            chunkCustomNames.remove(key);
            deleteClaimFromDB(key);

            syncToClient(player);
            PlayerStatsManager.get().updateClaims(player.getUUID(), player.getGameProfile().getName(), getUsedSlots(player.getUUID()));
            return true;
        }
        return false;
    }

    public boolean isPlotFree(int plotX, int plotZ, String dim) {
        int P = ProtectionConfig.get().plotSizeChunks;
        ChunkPos base = getPlotBaseChunk(plotX, plotZ);
        for(int i = 0; i < P; i++) {
            for(int j = 0; j < P; j++) {
                if (getChunkOwner(new ChunkPos(base.x + i, base.z + j), dim) != null) return false;
            }
        }
        return true;
    }

    public void autoClaimPlot(ServerPlayer player) {
        String lang = getPlayerLanguage(player.getUUID());
        if (!ProtectionConfig.get().isPlotMode) {
            player.sendSystemMessage(Component.literal(LanguageManager.get(lang, "msg.plot.not_plot_mode")));
            return;
        }
        if (getUsedPlots(player.getUUID()) >= getMaxSlots(player.getUUID())) {
            player.sendSystemMessage(Component.literal(LanguageManager.get(lang, "msg.plot.limit")));
            return;
        }

        int radius = 0;
        int spawnR = ProtectionConfig.get().spawnRadiusPlots;
        String dim = player.level().dimension().location().toString();

        while (radius < 100) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) == radius || Math.abs(z) == radius) {

                        if (Math.abs(x) <= spawnR && Math.abs(z) <= spawnR) continue;

                        if (isPlotFree(x, z, dim)) {
                            claimPlot(player, x, z, "Plot " + player.getGameProfile().getName());

                            ChunkPos base = getPlotBaseChunk(x, z);
                            int tpX = base.x * 16 + (ProtectionConfig.get().plotSizeChunks * 8);
                            int tpZ = base.z * 16 + (ProtectionConfig.get().plotSizeChunks * 8);

                            int tpY;
                            if (ProtectionConfig.get().useFixedYLevel) {
                                tpY = ProtectionConfig.get().plotYLevel;
                            } else {
                                tpY = player.serverLevel().getHeight(Heightmap.Types.WORLD_SURFACE, tpX, tpZ);
                            }

                            player.teleportTo(tpX + 0.5, tpY + 1.0, tpZ + 0.5);
                            player.sendSystemMessage(Component.literal(LanguageManager.get(lang, "msg.plot.auto_received")));
                            return;
                        }
                    }
                }
            }
            radius++;
        }
    }

    public void teleportToHomePlot(ServerPlayer player) {
        String lang = getPlayerLanguage(player.getUUID());
        String dim = player.level().dimension().location().toString();

        for (Map.Entry<String, UUID> entry : chunkOwners.entrySet()) {
            if (entry.getValue().equals(player.getUUID()) && entry.getKey().endsWith(dim)) {
                String[] parts = entry.getKey().split(";");
                int cx = Integer.parseInt(parts[0]);
                int cz = Integer.parseInt(parts[1]);
                int[] plotCoords = getPlotCoordsFromChunk(new ChunkPos(cx, cz));

                ChunkPos base = getPlotBaseChunk(plotCoords[0], plotCoords[1]);
                int P = ProtectionConfig.get().plotSizeChunks;
                int tpX = base.x * 16 + (P * 8);
                int tpZ = base.z * 16 + (P * 8);

                int tpY;
                if (ProtectionConfig.get().useFixedYLevel) {
                    tpY = ProtectionConfig.get().plotYLevel;
                } else {
                    tpY = player.serverLevel().getHeight(Heightmap.Types.WORLD_SURFACE, tpX, tpZ);
                }

                player.teleportTo(tpX + 0.5, tpY + 1.0, tpZ + 0.5);
                player.sendSystemMessage(Component.literal(LanguageManager.get(lang, "msg.plot.tp_home")));
                return;
            }
        }
        player.sendSystemMessage(Component.literal(LanguageManager.get(lang, "msg.plot.no_plot")));
    }

    public List<BlockPos> getPlayerClaimLocations(UUID uuid) {
        List<BlockPos> locations = new ArrayList<>();
        for (Map.Entry<String, UUID> entry : chunkOwners.entrySet()) {
            if (entry.getValue().equals(uuid)) {
                String[] parts = entry.getKey().split(";");
                int x = Integer.parseInt(parts[0]) << 4;
                int z = Integer.parseInt(parts[1]) << 4;
                locations.add(new BlockPos(x + 8, 100, z + 8));
            }
        }
        return locations;
    }

    public void startEvent(int stock, double price) {
        eventStock = stock;
        eventPrice = price;
        saveGlobalEvent();
    }

    public void stopEvent() {
        eventStock = 0;
        eventPrice = 0.0;
        saveGlobalEvent();
    }

    public boolean removeAnyClaim(ChunkPos pos, String dim) {
        String key = pos.x + ";" + pos.z + ";" + dim;
        if (chunkOwners.containsKey(key)) {
            UUID owner = chunkOwners.get(key);
            chunkOwners.remove(key);
            chunkCustomNames.remove(key);
            deleteClaimFromDB(key);
            PlayerStatsManager.get().updateClaims(owner, playerNames.getOrDefault(owner, "Necunoscut"), getUsedSlots(owner));
            return true;
        }
        return false;
    }

    public void transferAllClaims(UUID fromId, UUID toId, String toName) {
        int fromSlots = boughtSlots.getOrDefault(fromId, 1);
        int currentToSlots = boughtSlots.getOrDefault(toId, 1);
        boughtSlots.put(toId, currentToSlots + fromSlots);
        boughtSlots.remove(fromId);

        for (Map.Entry<String, UUID> entry : chunkOwners.entrySet()) {
            if (entry.getValue().equals(fromId)) {
                String chunkKey = entry.getKey();
                entry.setValue(toId);
                saveClaim(chunkKey, toId, chunkCustomNames.getOrDefault(chunkKey, ""));
            }
        }
        playerNames.put(toId, toName);
        savePlayerSettings(fromId);
        savePlayerSettings(toId);

        PlayerStatsManager.get().updateClaims(fromId, "Necunoscut", 0);
        PlayerStatsManager.get().updateClaims(toId, toName, getUsedSlots(toId));
    }

    public void adminClaim(ChunkPos pos, String dim, String name) {
        String key = pos.x + ";" + pos.z + ";" + dim;
        UUID adminUUID = new UUID(0, 0);
        chunkOwners.put(key, adminUUID);
        playerNames.put(adminUUID, "§6" + name);
        chunkCustomNames.put(key, "Zona Admin");
        saveClaim(key, adminUUID, "Zona Admin");
        savePlayerSettings(adminUUID);
    }

    public double getNextSlotCost(UUID playerId) {
        if (eventStock > 0) return eventPrice;
        if (ProtectionConfig.get().isPlotMode) return 50000.0;
        int currentMax = getMaxSlots(playerId);

        if (currentMax < 100) {
            return (currentMax <= 24) ? 1000.0 : 5000.0;
        } else if (currentMax <= 1000) {
            return (currentMax / 50) * 5000.0;
        } else {
            return 100000.0;
        }
    }

    public boolean buySlot(ServerPlayer player) {
        String lang = getPlayerLanguage(player.getUUID());
        UUID id = player.getUUID();
        int currentMax = getMaxSlots(id);
        double price = getNextSlotCost(id);

        if (EconomyManager.get().getBalance(id) >= price) {
            EconomyManager.get().removeBalance(id, price);
            boughtSlots.put(id, currentMax + 1);

            if (eventStock > 0) {
                eventStock--;
                saveGlobalEvent();
                player.getServer().getPlayerList().broadcastSystemMessage(Component.literal(LanguageManager.get(lang, "msg.event.bought", player.getName().getString(), eventStock)), false);
            }
            savePlayerSettings(id);
            syncToClient(player);
            return true;
        }
        return false;
    }

    public void setFlag(UUID owner, String claimName, String flagName, boolean state) {
        if (claimName == null || claimName.isEmpty()) return;
        claimFlags.computeIfAbsent(owner, k -> new HashMap<>()).computeIfAbsent(claimName, k -> new HashMap<>()).put(flagName, state);
        savePlayerFlags(owner);
    }

    public boolean getFlag(UUID owner, String claimName, String flagName) {
        if (owner == null || claimName == null || claimName.isEmpty()) return false;
        Map<String, Map<String, Boolean>> playerFlags = claimFlags.get(owner);
        if (playerFlags != null && playerFlags.containsKey(claimName)) return playerFlags.get(claimName).getOrDefault(flagName, false);
        return false;
    }

    public void addTrust(ServerPlayer owner, UUID target, String claimName) {
        if (claimName == null || claimName.isEmpty()) return;
        trustedPlayers.computeIfAbsent(owner.getUUID(), k -> new HashMap<>()).computeIfAbsent(claimName, k -> new HashSet<>()).add(target);
        savePlayerSettings(owner.getUUID());
        syncToClient(owner);
    }

    public void removeTrust(ServerPlayer owner, UUID target, String claimName) {
        if (claimName == null || claimName.isEmpty()) return;
        if (trustedPlayers.containsKey(owner.getUUID())) {
            Map<String, Set<UUID>> claimsTrusts = trustedPlayers.get(owner.getUUID());
            if (claimsTrusts.containsKey(claimName)) {
                claimsTrusts.get(claimName).remove(target);
                savePlayerSettings(owner.getUUID());
                syncToClient(owner);
            }
        }
    }

    public boolean isTrusted(UUID owner, UUID visitor, String claimName) {
        if (owner == null || owner.equals(visitor)) return true;
        if (claimName == null || claimName.isEmpty()) return false;
        Map<String, Set<UUID>> claimsTrusts = trustedPlayers.get(owner);
        if (claimsTrusts != null && claimsTrusts.containsKey(claimName)) return claimsTrusts.get(claimName).contains(visitor);
        return false;
    }

    public UUID getChunkOwner(ChunkPos pos, String dim) {
        return chunkOwners.get(pos.x + ";" + pos.z + ";" + dim);
    }

    public String getOwnerName(UUID uuid) {
        if (uuid == null) return "Wilderness";
        String name = playerNames.get(uuid);
        return (name == null || name.isEmpty()) ? "Necunoscut" : name;
    }

    public String getCustomName(ChunkPos pos, String dim) {
        return chunkCustomNames.getOrDefault(pos.x + ";" + pos.z + ";" + dim, "");
    }

    public int getMaxSlots(UUID p) {
        return boughtSlots.getOrDefault(p, 1);
    }

    public int getUsedSlots(UUID p) {
        return (int) chunkOwners.values().stream().filter(id -> id.equals(p)).count();
    }

    public void syncToClient(ServerPlayer player) {
        syncDataInternal(player, false);
    }

    public void syncToAdminClient(ServerPlayer player) {
        syncDataInternal(player, true);
    }

    private void syncDataInternal(ServerPlayer player, boolean isAdminMap) {
        try {
            ChunkPos center = player.chunkPosition();
            String dim = player.level().dimension().location().toString();
            Map<String, ClientClaimInfo> localClaims = new HashMap<>();

            for (int x = -10; x <= 10; x++) {
                for (int z = -10; z <= 10; z++) {
                    ChunkPos p = new ChunkPos(center.x + x, center.z + z);
                    UUID ownerId = getChunkOwner(p, dim);
                    if (ownerId != null) {
                        localClaims.put(p.x + ";" + p.z, new ClientClaimInfo(ownerId, getOwnerName(ownerId), getCustomName(p, dim)));
                    }
                }
            }

            Map<String, Map<UUID, String>> trustedNamesPerClaim = new HashMap<>();
            Map<String, Set<UUID>> myTrusts = trustedPlayers.getOrDefault(player.getUUID(), new HashMap<>());

            for (Map.Entry<String, Set<UUID>> entry : myTrusts.entrySet()) {
                String cName = entry.getKey();
                if (cName == null) continue;

                Map<UUID, String> mappedNames = new HashMap<>();
                if (entry.getValue() != null) {
                    for (UUID id : entry.getValue()) {
                        if (id != null && player.getServer() != null) {
                            try {
                                player.getServer().getProfileCache().get(id).ifPresent(pr -> mappedNames.put(id, pr.getName()));
                            } catch (Exception ignored) { }
                        }
                    }
                }
                trustedNamesPerClaim.put(cName, mappedNames);
            }

            Set<String> allMyClaimNames = new HashSet<>();
            UUID adminUUID = new UUID(0, 0);

            for (Map.Entry<String, UUID> entry : chunkOwners.entrySet()) {
                if (entry.getValue() != null) {
                    boolean isMine = entry.getValue().equals(player.getUUID());
                    boolean isAdminClaim = entry.getValue().equals(adminUUID);

                    if (isMine || (isAdminMap && isAdminClaim)) {
                        String cName = chunkCustomNames.get(entry.getKey());
                        if (cName != null && !cName.trim().isEmpty()) {
                            allMyClaimNames.add(cName);
                        }
                    }
                }
            }

            Map<String, Map<String, Boolean>> myFlagsMap = new HashMap<>(claimFlags.getOrDefault(player.getUUID(), new HashMap<>()));
            if (isAdminMap) {
                Map<String, Map<String, Boolean>> adminFlags = claimFlags.getOrDefault(adminUUID, new HashMap<>());
                myFlagsMap.putAll(adminFlags);
            }

            SyncData data = new SyncData(localClaims, trustedNamesPerClaim, myFlagsMap, allMyClaimNames, getMaxSlots(player.getUUID()), getUsedPlots(player.getUUID()), getNextSlotCost(player.getUUID()));
            PacketHandler.sendToPlayer(new PacketHandler.S2C_SyncClaimData(GSON.toJson(data), isAdminMap), player);
        } catch (Exception e) {
            e.printStackTrace();
            player.sendSystemMessage(Component.literal("§c[Eroare] Harta a întâmpinat o problemă internă, dar serverul a fost protejat!"));
        }
    }

    public static class SyncData {
        public Map<String, ClientClaimInfo> map;
        public Map<String, Map<UUID, String>> trustedPerClaim;
        public Map<String, Map<String, Boolean>> myFlags;
        public Set<String> allClaimNames;
        public int maxSlots, usedSlots;
        public double nextSlotCost;

        public SyncData(Map<String, ClientClaimInfo> m, Map<String, Map<UUID, String>> t, Map<String, Map<String, Boolean>> flags, Set<String> names, int max, int used, double cost) {
            this.map = m;
            this.trustedPerClaim = t;
            this.myFlags = flags;
            this.allClaimNames = names;
            this.maxSlots = max;
            this.usedSlots = used;
            this.nextSlotCost = cost;
        }
    }

    public static class ClientClaimInfo {
        public UUID ownerUUID;
        public String ownerName, customName;

        public ClientClaimInfo(UUID u, String n, String c) {
            this.ownerUUID = u;
            this.ownerName = n;
            this.customName = c;
        }
    }

    public void save() { }
}