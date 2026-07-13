package org.evocraft.evoprotection.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.minecraft.ChatFormatting;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ClaimManager {
    private static ClaimManager INSTANCE;
    private static final UUID ADMIN_UUID = new UUID(0, 0);
    private static final String ADMIN_CLAIM_NAME = "ADMIN";
    private static final int CLIENT_MAP_RADIUS = 4;
    public static final String ROLE_COOWNER = "coowner";
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_FRIEND = "friend";
    public static final String ROLE_VISITOR = "visitor";
    private static final Set<String> BUILD_ROLES = Set.of(ROLE_COOWNER, ROLE_ADMIN, ROLE_FRIEND);
    private static final int MAX_CLAIM_NAME_LENGTH = 64;
    private static final Set<String> SUPPORTED_FLAGS = Set.of(
            "pvp", "doors", "use", "interact_entities", "item_pickup",
            "natural_animals", "spawner_animals", "explosions", "chests",
            "public_build", "carry_on", "hurt_animals", "natural_monsters",
            "spawner_monsters", ClaimEnvironmentManager.FLAG_ALWAYS_MIDDLE_DAY,
            ClaimEnvironmentManager.FLAG_ALWAYS_MIDDLE_NIGHT,
            ClaimEnvironmentManager.FLAG_ALWAYS_SHINY,
            ClaimEnvironmentManager.FLAG_ALWAYS_RAIN
    );
    private final Gson GSON = new GsonBuilder().create();

    public static boolean isAdminClaimOwner(UUID owner) {
        return ADMIN_UUID.equals(owner);
    }

    private final Map<String, UUID> chunkOwners = new ConcurrentHashMap<>();
    private final Map<String, String> chunkCustomNames = new ConcurrentHashMap<>();
    private final Map<String, String> chunkClaimIds = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> claimChunkKeys = new ConcurrentHashMap<>();
    private final Map<String, UUID> claimOwners = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> ownerClaimIds = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> ownerChunkKeys = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerNames = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> boughtSlots = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Map<UUID, String>>> trustedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Map<String, Boolean>>> claimFlags = new ConcurrentHashMap<>();
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "EvoProtection-DB");
        thread.setDaemon(true);
        return thread;
    });

    // Dicționar pentru limba fiecărui jucător luată din Launcher (tabelul 'players')
    private final Map<UUID, String> playerLanguages = new ConcurrentHashMap<>();

    private int eventStock = 0;
    private double eventPrice = 0.0;

    private String getTblClaims() { return ProtectionConfig.get().isPlotMode ? "plot_claims" : "player_claims"; }
    private String getTblSettings() { return ProtectionConfig.get().isPlotMode ? "plot_player_settings" : "claim_player_settings"; }
    private String getTblFlags() { return ProtectionConfig.get().isPlotMode ? "plot_flags_data" : "claim_flags_data"; }
    private String getTblEvents() { return ProtectionConfig.get().isPlotMode ? "plot_global_events" : "claim_global_events"; }

    private void queueDbWrite(Runnable task) {
        dbExecutor.execute(() -> {
            synchronized (DatabaseManager.get()) {
                try {
                    task.run();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private String makeChunkKey(ChunkPos pos, String dim) {
        return pos.x + ";" + pos.z + ";" + dim;
    }

    private String makeChunkClaimId(ChunkPos pos, String dim) {
        return "chunk;" + makeChunkKey(pos, dim);
    }

    private String makePlotClaimId(int plotX, int plotZ, String dim) {
        return "plot;" + plotX + ";" + plotZ + ";" + dim;
    }

    public String getClaimId(ChunkPos pos, String dim) {
        UUID owner = getChunkOwner(pos, dim);
        if (ProtectionConfig.get().isPlotMode && owner != null && !ADMIN_UUID.equals(owner)) {
            int[] plotCoords = getPlotCoordsFromChunk(pos);
            return makePlotClaimId(plotCoords[0], plotCoords[1], dim);
        }
        return makeChunkClaimId(pos, dim);
    }

    private String computeClaimIdFromChunkKey(String chunkKey, UUID owner) {
        String[] parts = chunkKey.split(";", 3);
        if (parts.length < 3) return "chunk;" + chunkKey;

        try {
            if (ProtectionConfig.get().isPlotMode && owner != null && !ADMIN_UUID.equals(owner)) {
                int[] plotCoords = getPlotCoordsFromChunk(new ChunkPos(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1])
                ));
                return makePlotClaimId(plotCoords[0], plotCoords[1], parts[2]);
            }
        } catch (NumberFormatException ignored) {
            return "chunk;" + chunkKey;
        }

        return "chunk;" + chunkKey;
    }

    private String getClaimIdFromChunkKey(String chunkKey) {
        String cachedClaimId = chunkClaimIds.get(chunkKey);
        if (cachedClaimId != null) return cachedClaimId;

        UUID owner = chunkOwners.get(chunkKey);
        String claimId = computeClaimIdFromChunkKey(chunkKey, owner);
        if (owner != null) indexClaimChunk(chunkKey, owner, claimId);
        return claimId;
    }

    private boolean claimIdMatchesChunkKey(String claimId, String chunkKey) {
        return claimId != null && claimId.equals(getClaimIdFromChunkKey(chunkKey));
    }

    private void clearClaimIndexes() {
        chunkClaimIds.clear();
        claimChunkKeys.clear();
        claimOwners.clear();
        ownerClaimIds.clear();
        ownerChunkKeys.clear();
    }

    private void rebuildClaimIndexes() {
        clearClaimIndexes();
        for (Map.Entry<String, UUID> entry : chunkOwners.entrySet()) {
            indexClaimChunk(entry.getKey(), entry.getValue(), computeClaimIdFromChunkKey(entry.getKey(), entry.getValue()));
        }
    }

    private void indexClaimChunk(String chunkKey, UUID owner, String claimId) {
        if (chunkKey == null || owner == null || claimId == null || claimId.isEmpty()) return;

        chunkClaimIds.put(chunkKey, claimId);
        claimChunkKeys.computeIfAbsent(claimId, ignored -> ConcurrentHashMap.newKeySet()).add(chunkKey);
        claimOwners.put(claimId, owner);
        ownerClaimIds.computeIfAbsent(owner, ignored -> ConcurrentHashMap.newKeySet()).add(claimId);
        ownerChunkKeys.computeIfAbsent(owner, ignored -> ConcurrentHashMap.newKeySet()).add(chunkKey);
    }

    private void removeClaimChunkFromIndexes(String chunkKey) {
        UUID owner = chunkOwners.get(chunkKey);
        String claimId = chunkClaimIds.remove(chunkKey);

        if (owner != null) {
            Set<String> chunks = ownerChunkKeys.get(owner);
            if (chunks != null) {
                chunks.remove(chunkKey);
                if (chunks.isEmpty()) ownerChunkKeys.remove(owner, chunks);
            }
        }

        if (claimId == null) return;
        Set<String> claimChunks = claimChunkKeys.get(claimId);
        if (claimChunks == null) return;

        claimChunks.remove(chunkKey);
        if (claimChunks.isEmpty()) {
            claimChunkKeys.remove(claimId, claimChunks);
            UUID claimOwner = claimOwners.remove(claimId);
            if (claimOwner != null) {
                Set<String> claims = ownerClaimIds.get(claimOwner);
                if (claims != null) {
                    claims.remove(claimId);
                    if (claims.isEmpty()) ownerClaimIds.remove(claimOwner, claims);
                }
            }
        }
    }

    private void putClaimInMemory(String chunkKey, UUID owner) {
        removeClaimChunkFromIndexes(chunkKey);
        chunkOwners.put(chunkKey, owner);
        indexClaimChunk(chunkKey, owner, computeClaimIdFromChunkKey(chunkKey, owner));
    }

    private void removeClaimFromMemory(String chunkKey) {
        removeClaimChunkFromIndexes(chunkKey);
        chunkOwners.remove(chunkKey);
        chunkCustomNames.remove(chunkKey);
    }

    private List<String> getChunkKeysForClaimId(String claimId, UUID expectedOwner) {
        if (claimId == null || claimId.isEmpty()) return Collections.emptyList();
        if (expectedOwner != null && !expectedOwner.equals(claimOwners.get(claimId))) return Collections.emptyList();

        Set<String> keys = claimChunkKeys.get(claimId);
        return keys == null || keys.isEmpty() ? Collections.emptyList() : new ArrayList<>(keys);
    }

    public boolean ownsClaim(UUID owner, String claimId) {
        return owner != null && !getChunkKeysForClaimId(claimId, owner).isEmpty();
    }

    public boolean isChunkInsideClientMap(ServerPlayer player, ChunkPos target) {
        if (player == null || target == null) return false;
        ChunkPos center = player.chunkPosition();
        return Math.abs(target.x - center.x) <= CLIENT_MAP_RADIUS && Math.abs(target.z - center.z) <= CLIENT_MAP_RADIUS;
    }

    public String getClaimDisplayName(String claimId) {
        if (claimId == null || claimId.isEmpty()) return "";

        for (String key : getChunkKeysForClaimId(claimId, null)) {
            String name = chunkCustomNames.get(key);
            if (name != null && !name.trim().isEmpty()) return name;

            if (ADMIN_UUID.equals(chunkOwners.get(key))) return ADMIN_CLAIM_NAME;
        }

        String[] parts = claimId.split(";", 4);
        if (parts.length >= 3) {
            if ("plot".equals(parts[0])) return "Plot " + parts[1] + "," + parts[2];
            if ("chunk".equals(parts[0])) return "Chunk " + parts[1] + "," + parts[2];
        }
        return claimId;
    }

    public static String normalizeTrustRole(String role) {
        if (role == null) return ROLE_FRIEND;
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case ROLE_COOWNER, ROLE_ADMIN, ROLE_FRIEND, ROLE_VISITOR -> normalized;
            case "prieten" -> ROLE_FRIEND;
            case "vizitator" -> ROLE_VISITOR;
            default -> ROLE_FRIEND;
        };
    }

    public static boolean roleCanBuild(String role) {
        return BUILD_ROLES.contains(normalizeTrustRole(role));
    }

    public static String[] getAvailableTrustRoles() {
        return new String[]{ROLE_COOWNER, ROLE_ADMIN, ROLE_FRIEND};
    }

    public static boolean roleCanEditFlag(String role, String flagName) {
        if (role == null) return false;
        String normalizedRole = normalizeTrustRole(role);
        String normalizedFlag = normalizeFlagName(flagName);
        if (!SUPPORTED_FLAGS.contains(normalizedFlag)) return false;

        if (ROLE_COOWNER.equals(normalizedRole)) return true;

        if (ROLE_ADMIN.equals(normalizedRole)) {
            return !Set.of(
                    "explosions",
                    "pvp",
                    "doors",
                    "interact_entities",
                    "carry_on"
            ).contains(normalizedFlag);
        }

        if (ROLE_FRIEND.equals(normalizedRole)) {
            return Set.of("spawner_animals", "spawner_monsters").contains(normalizedFlag);
        }

        return false;
    }

    public static boolean roleCanEditAnyFlag(String role) {
        if (role == null) return false;
        String normalizedRole = normalizeTrustRole(role);
        return ROLE_COOWNER.equals(normalizedRole) || ROLE_ADMIN.equals(normalizedRole) || ROLE_FRIEND.equals(normalizedRole);
    }

    public static String normalizeFlagName(String flagName) {
        if (flagName == null) return "";
        String normalized = flagName.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "always_day", "always_middle_day" -> ClaimEnvironmentManager.FLAG_ALWAYS_MIDDLE_DAY;
            case "always_night", "always_middle_night" -> ClaimEnvironmentManager.FLAG_ALWAYS_MIDDLE_NIGHT;
            case "always_shiny" -> ClaimEnvironmentManager.FLAG_ALWAYS_SHINY;
            case "always_rain" -> ClaimEnvironmentManager.FLAG_ALWAYS_RAIN;
            default -> normalized;
        };
    }

    public static boolean isSupportedFlagName(String flagName) {
        return SUPPORTED_FLAGS.contains(normalizeFlagName(flagName));
    }

    private static String sanitizeClaimName(String customName) {
        if (customName == null) return "";
        String clean = ChatFormatting.stripFormatting(customName);
        if (clean == null) return "";
        clean = clean.replaceAll("[\\r\\n\\t\\x00-\\x1F\\x7F]", " ").trim();
        return clean.length() <= MAX_CLAIM_NAME_LENGTH ? clean : clean.substring(0, MAX_CLAIM_NAME_LENGTH);
    }

    private static String getLegacyFlagName(String normalizedFlagName) {
        return switch (normalizedFlagName) {
            case ClaimEnvironmentManager.FLAG_ALWAYS_MIDDLE_DAY -> "always_day";
            case ClaimEnvironmentManager.FLAG_ALWAYS_MIDDLE_NIGHT -> "always_night";
            default -> null;
        };
    }

    private static void putFlagValue(Map<String, Boolean> flags, String flagName, boolean state) {
        String normalized = normalizeFlagName(flagName);
        flags.put(normalized, state);

        String legacyName = getLegacyFlagName(normalized);
        if (legacyName != null) {
            flags.remove(legacyName);
        }
    }

    private static Map<String, Boolean> normalizeFlagMap(Map<String, Boolean> flags) {
        if (flags == null || flags.isEmpty()) return Collections.emptyMap();

        Map<String, Boolean> normalized = new HashMap<>(flags);
        for (Map.Entry<String, Boolean> entry : flags.entrySet()) {
            String normalizedName = normalizeFlagName(entry.getKey());
            normalized.putIfAbsent(normalizedName, entry.getValue());
        }
        return normalized;
    }

    private Map<UUID, String> getTrustRoles(UUID owner, String claimId) {
        Map<String, Map<UUID, String>> trusts = trustedPlayers.get(owner);
        if (trusts == null) return Collections.emptyMap();

        Map<UUID, String> exact = trusts.get(claimId);
        if (exact != null) return exact;

        Map<UUID, String> legacy = trusts.get(getClaimDisplayName(claimId));
        return legacy != null ? legacy : Collections.emptyMap();
    }

    public String getTrustRole(UUID owner, UUID visitor, String claimId) {
        if (owner == null || visitor == null || claimId == null || claimId.isEmpty()) return ROLE_VISITOR;
        if (owner.equals(visitor)) return ROLE_COOWNER;
        String role = getTrustRoles(owner, claimId).get(visitor);
        return role == null ? ROLE_VISITOR : normalizeTrustRole(role);
    }

    public boolean canEditFlag(UUID owner, UUID actor, String claimId, String flagName) {
        if (owner == null || actor == null || claimId == null || claimId.isEmpty()) return false;
        if (!isSupportedFlagName(flagName)) return false;
        if (!ownsClaim(owner, claimId)) return false;
        if (owner.equals(actor)) return true;
        return roleCanEditFlag(getTrustRole(owner, actor, claimId), flagName);
    }

    public UUID getClaimOwner(String claimId) {
        if (claimId == null || claimId.isEmpty()) return null;
        return claimOwners.get(claimId);
    }

    private String getFlagStorageKey(UUID owner, String claimId) {
        if (owner == null || claimId == null || claimId.isEmpty()) return claimId;

        String displayName = getClaimDisplayName(claimId);
        if (displayName == null || displayName.trim().isEmpty()) return claimId;
        return displayName.trim();
    }

    private Set<String> getClaimIdsWithDisplayName(UUID owner, String displayName) {
        Set<String> claimIds = new TreeSet<>();
        if (owner == null || displayName == null || displayName.trim().isEmpty()) return claimIds;

        String targetName = displayName.trim();
        for (String claimId : ownerClaimIds.getOrDefault(owner, Collections.emptySet())) {
            String currentName = getClaimDisplayName(claimId);
            if (currentName != null && targetName.equals(currentName.trim())) {
                claimIds.add(claimId);
            }
        }
        return claimIds;
    }

    private boolean hasOtherClaimWithDisplayName(UUID owner, String displayName, String removedClaimId) {
        for (String claimId : getClaimIdsWithDisplayName(owner, displayName)) {
            if (!claimId.equals(removedClaimId)) return true;
        }
        return false;
    }

    private Map<String, Boolean> getFlagsForClaim(UUID owner, String claimId) {
        if (owner == null || claimId == null || claimId.isEmpty()) return Collections.emptyMap();

        Map<String, Map<String, Boolean>> flags = claimFlags.get(owner);
        if (flags == null) return Collections.emptyMap();

        String storageKey = getFlagStorageKey(owner, claimId);
        Map<String, Boolean> grouped = flags.get(storageKey);
        if (grouped != null) return normalizeFlagMap(grouped);

        String displayName = getClaimDisplayName(claimId);
        if (displayName != null && !storageKey.equals(displayName)) {
            Map<String, Boolean> legacyDisplay = flags.get(displayName);
            if (legacyDisplay != null) return normalizeFlagMap(legacyDisplay);
        }

        Map<String, Boolean> merged = new HashMap<>();
        for (String siblingClaimId : getClaimIdsWithDisplayName(owner, storageKey)) {
            Map<String, Boolean> siblingFlags = flags.get(siblingClaimId);
            if (siblingFlags != null) {
                merged.putAll(normalizeFlagMap(siblingFlags));
            }
        }

        Map<String, Boolean> exact = flags.get(claimId);
        if (exact != null) {
            merged.putAll(normalizeFlagMap(exact));
        }

        return merged.isEmpty() ? Collections.emptyMap() : merged;
    }

    private void removeClaimMetadata(UUID owner, String claimId) {
        if (owner == null || claimId == null || claimId.isEmpty()) return;
        String legacyName = getClaimDisplayName(claimId);
        String flagStorageKey = getFlagStorageKey(owner, claimId);

        Map<String, Map<UUID, String>> trusts = trustedPlayers.get(owner);
        if (trusts != null) {
            trusts.remove(claimId);
            trusts.remove(legacyName);
            savePlayerSettings(owner);
        }

        Map<String, Map<String, Boolean>> flags = claimFlags.get(owner);
        if (flags != null) {
            boolean hasOtherNamedClaim = hasOtherClaimWithDisplayName(owner, flagStorageKey, claimId);
            if (hasOtherNamedClaim) {
                Map<String, Boolean> sharedFlags = new HashMap<>(getFlagsForClaim(owner, claimId));
                if (!sharedFlags.isEmpty()) {
                    flags.put(flagStorageKey, sharedFlags);
                }
                if (!flagStorageKey.equals(claimId)) {
                    flags.remove(claimId);
                }
            } else {
                flags.remove(claimId);
                flags.remove(flagStorageKey);
                flags.remove(legacyName);
            }
            savePlayerFlags(owner);
        }
    }

    private Map<String, Map<UUID, String>> parseTrustedData(String trustedJson) {
        Map<String, Map<UUID, String>> parsed = new HashMap<>();
        if (trustedJson == null || trustedJson.isEmpty()) return parsed;

        try {
            JsonObject root = GSON.fromJson(trustedJson, JsonObject.class);
            if (root == null) return parsed;

            for (Map.Entry<String, JsonElement> claimEntry : root.entrySet()) {
                Map<UUID, String> roles = new HashMap<>();
                JsonElement value = claimEntry.getValue();

                if (value != null && value.isJsonArray()) {
                    for (JsonElement uuidElement : value.getAsJsonArray()) {
                        roles.put(UUID.fromString(uuidElement.getAsString()), ROLE_FRIEND);
                    }
                } else if (value != null && value.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> roleEntry : value.getAsJsonObject().entrySet()) {
                        roles.put(UUID.fromString(roleEntry.getKey()), normalizeTrustRole(roleEntry.getValue().getAsString()));
                    }
                }

                parsed.put(claimEntry.getKey(), roles);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return parsed;
    }

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
        clearClaimIndexes();
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
                throw new IllegalStateException("EvoProtection cannot start safely because the claim tables are unavailable.", e);
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
            } catch (Exception e) {
                throw new IllegalStateException("EvoProtection cannot load global claim data safely.", e);
            }

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
                            trustedPlayers.put(uuid, parseTrustedData(trustedJson));
                        }
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("EvoProtection cannot load claim settings and trust data safely.", e);
            }

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
            } catch (Exception e) {
                throw new IllegalStateException("EvoProtection cannot load claim flags safely.", e);
            }

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
                    rebuildClaimIndexes();
                    migrateTrustsToClaimGroups();
                    System.out.println("[EvoProtection] Successfully loaded claims from MariaDB.");
                }
            } catch (Exception e) {
                System.err.println("[EvoProtection] Error loading claims from database.");
                throw new IllegalStateException("EvoProtection stopped startup to avoid running with unprotected claims.", e);
            }
        }
    }

    private void saveGlobalEvent() {
        final int stock = eventStock;
        final double price = eventPrice;
        final String table = getTblEvents();
        queueDbWrite(() -> {
            try {
                Connection conn = DatabaseManager.get().getConnection();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO " + table + " (id, stock, price) VALUES (1, ?, ?) " +
                                "ON DUPLICATE KEY UPDATE stock = ?, price = ?")) {
                    stmt.setInt(1, stock);
                    stmt.setDouble(2, price);
                    stmt.setInt(3, stock);
                    stmt.setDouble(4, price);
                    stmt.executeUpdate();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void savePlayerSettings(UUID uuid) {
        if (uuid == null) return;
        final String name = playerNames.getOrDefault(uuid, "Unknown");
        final int slots = boughtSlots.getOrDefault(uuid, 1);
        final String trustedData = GSON.toJson(new HashMap<>(trustedPlayers.getOrDefault(uuid, new HashMap<>())));
        final String table = getTblSettings();

        queueDbWrite(() -> {
            try {
                Connection conn = DatabaseManager.get().getConnection();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO " + table + " (uuid, player_name, bought_slots, trusted_data) " +
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
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void savePlayerFlags(UUID uuid) {
        if (uuid == null) return;
        final String flagsData = GSON.toJson(new HashMap<>(claimFlags.getOrDefault(uuid, new HashMap<>())));
        final String table = getTblFlags();
        queueDbWrite(() -> {
            try {
                Connection conn = DatabaseManager.get().getConnection();
                try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + table + " (uuid, flags_data) VALUES (?, ?) ON DUPLICATE KEY UPDATE flags_data = ?")) {
                    stmt.setString(1, uuid.toString());
                    stmt.setString(2, flagsData);
                    stmt.setString(3, flagsData);
                    stmt.executeUpdate();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void saveClaim(String chunkKey, UUID ownerUuid, String customName) {
        final String name = customName != null ? customName : "";
        final String table = getTblClaims();
        queueDbWrite(() -> {
            try {
                Connection conn = DatabaseManager.get().getConnection();
                try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + table + " (chunk_key, owner_uuid, custom_name) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE owner_uuid = ?, custom_name = ?")) {
                    stmt.setString(1, chunkKey);
                    stmt.setString(2, ownerUuid.toString());
                    stmt.setString(3, name);
                    stmt.setString(4, ownerUuid.toString());
                    stmt.setString(5, name);
                    stmt.executeUpdate();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void deleteClaimFromDB(String chunkKey) {
        final String table = getTblClaims();
        queueDbWrite(() -> {
            try {
                Connection conn = DatabaseManager.get().getConnection();
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM " + table + " WHERE chunk_key = ?")) {
                    stmt.setString(1, chunkKey);
                    stmt.executeUpdate();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
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
        Set<String> claims = ownerClaimIds.get(p);
        if (claims == null) return 0;
        return (int) claims.stream().filter(claimId -> claimId.startsWith("plot;")).count();
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
        customName = sanitizeClaimName(customName);
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
                    String key = makeChunkKey(cp, dim);
                    putClaimInMemory(key, player.getUUID());
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
        List<String> keysToRemove = new ArrayList<>();

        for(int i = 0; i < P; i++) {
            for(int j = 0; j < P; j++) {
                ChunkPos cp = new ChunkPos(base.x + i, base.z + j);
                String key = makeChunkKey(cp, dim);
                if (player.getUUID().equals(chunkOwners.get(key))) {
                    keysToRemove.add(key);
                }
            }
        }
        if (!keysToRemove.isEmpty()) {
            String claimId = makePlotClaimId(plotX, plotZ, dim);
            removeClaimMetadata(player.getUUID(), claimId);
            for (String key : keysToRemove) {
                removeClaimFromMemory(key);
                deleteClaimFromDB(key);
            }
            syncToClient(player);
            PlayerStatsManager.get().updateClaims(player.getUUID(), player.getGameProfile().getName(), getUsedPlots(player.getUUID()));
            player.sendSystemMessage(Component.literal(LanguageManager.get(lang, "msg.plot.abandoned")));
            return true;
        }
        return false;
    }

    public boolean unclaimById(ServerPlayer player, String claimId) {
        String lang = getPlayerLanguage(player.getUUID());
        List<String> keysToRemove = getChunkKeysForClaimId(claimId, player.getUUID());

        if (keysToRemove.isEmpty()) return false;

        String displayName = getClaimDisplayName(claimId);
        removeClaimMetadata(player.getUUID(), claimId);
        for (String key : keysToRemove) {
            removeClaimFromMemory(key);
            deleteClaimFromDB(key);
        }

        syncToClient(player);
        PlayerStatsManager.get().updateClaims(player.getUUID(), player.getGameProfile().getName(), getUsedClaimCount(player.getUUID()));
        player.sendSystemMessage(Component.literal(LanguageManager.get(lang, "msg.claim.deleted", displayName)));
        return true;
    }

    public boolean unclaimGroupById(ServerPlayer player, String claimId) {
        UUID owner = player.getUUID();
        if (!ownsClaim(owner, claimId)) return false;

        String displayName = getClaimDisplayName(claimId);
        Set<String> groupedClaimIds = getClaimIdsWithDisplayName(owner, displayName);
        if (groupedClaimIds.isEmpty()) groupedClaimIds = Set.of(claimId);

        Set<String> keysToRemove = new LinkedHashSet<>();
        for (String groupedClaimId : groupedClaimIds) {
            keysToRemove.addAll(getChunkKeysForClaimId(groupedClaimId, owner));
        }
        if (keysToRemove.isEmpty()) return false;

        Map<String, Map<UUID, String>> trusts = trustedPlayers.get(owner);
        if (trusts != null) {
            trusts.remove(displayName);
            for (String groupedClaimId : groupedClaimIds) trusts.remove(groupedClaimId);
            savePlayerSettings(owner);
        }

        Map<String, Map<String, Boolean>> flags = claimFlags.get(owner);
        if (flags != null) {
            flags.remove(displayName);
            for (String groupedClaimId : groupedClaimIds) flags.remove(groupedClaimId);
            savePlayerFlags(owner);
        }

        for (String key : keysToRemove) {
            removeClaimFromMemory(key);
            deleteClaimFromDB(key);
        }

        syncToClient(player);
        PlayerStatsManager.get().updateClaims(owner, player.getGameProfile().getName(), getUsedClaimCount(owner));
        player.sendSystemMessage(Component.literal(LanguageManager.get(getPlayerLanguage(owner), "msg.claim.deleted", displayName)));
        return true;
    }

    public boolean unclaimByName(ServerPlayer player, String claimName) {
        for (Map.Entry<String, UUID> entry : chunkOwners.entrySet()) {
            if (entry.getValue().equals(player.getUUID())) {
                String claimId = getClaimIdFromChunkKey(entry.getKey());
                if (claimName.equals(getClaimDisplayName(claimId))) {
                    return unclaimGroupById(player, claimId);
                }
            }
        }
        return false;
    }

    public boolean claimChunk(ServerPlayer player, ChunkPos pos, String customName) {
        String lang = getPlayerLanguage(player.getUUID());
        customName = sanitizeClaimName(customName);
        if (ProtectionConfig.get().isPlotMode) {
            if (isRoadChunk(pos)) {
                player.sendSystemMessage(Component.literal(LanguageManager.get(lang, "msg.road.reserved")));
                return false;
            }
            int[] plotCoords = getPlotCoordsFromChunk(pos);
            return claimPlot(player, plotCoords[0], plotCoords[1], customName);
        }

        String key = makeChunkKey(pos, player.level().dimension().location().toString());
        if (chunkOwners.containsKey(key)) return false;

        if (getUsedSlots(player.getUUID()) < getMaxSlots(player.getUUID())) {
            putClaimInMemory(key, player.getUUID());
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

        String dim = player.level().dimension().location().toString();
        String key = makeChunkKey(pos, dim);
        if (player.getUUID().equals(chunkOwners.get(key))) {
            removeClaimMetadata(player.getUUID(), getClaimId(pos, dim));
            removeClaimFromMemory(key);
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
                String[] parts = entry.getKey().split(";", 3);
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
                String[] parts = entry.getKey().split(";", 3);
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
        String key = makeChunkKey(pos, dim);
        UUID owner = chunkOwners.get(key);
        if (owner != null) {
            String claimId = getClaimId(pos, dim);
            List<String> keysToRemove = getChunkKeysForClaimId(claimId, owner);
            removeClaimMetadata(owner, claimId);

            for (String removeKey : keysToRemove) {
                removeClaimFromMemory(removeKey);
                deleteClaimFromDB(removeKey);
            }
            PlayerStatsManager.get().updateClaims(owner, playerNames.getOrDefault(owner, "Necunoscut"), getUsedClaimCount(owner));
            return true;
        }
        return false;
    }

    public void transferAllClaims(UUID fromId, UUID toId, String toName) {
        int fromSlots = boughtSlots.getOrDefault(fromId, 1);
        int currentToSlots = boughtSlots.getOrDefault(toId, 1);
        boughtSlots.put(toId, currentToSlots + fromSlots);
        boughtSlots.remove(fromId);

        List<String> transferredChunkKeys = new ArrayList<>(ownerChunkKeys.getOrDefault(fromId, Collections.emptySet()));
        for (String chunkKey : transferredChunkKeys) {
            chunkOwners.put(chunkKey, toId);
            saveClaim(chunkKey, toId, chunkCustomNames.getOrDefault(chunkKey, ""));
        }
        rebuildClaimIndexes();
        Map<String, Map<UUID, String>> fromTrusts = trustedPlayers.remove(fromId);
        if (fromTrusts != null) {
            trustedPlayers.computeIfAbsent(toId, k -> new HashMap<>()).putAll(fromTrusts);
        }

        Map<String, Map<String, Boolean>> fromFlags = claimFlags.remove(fromId);
        if (fromFlags != null) {
            claimFlags.computeIfAbsent(toId, k -> new HashMap<>()).putAll(fromFlags);
            savePlayerFlags(fromId);
            savePlayerFlags(toId);
        }

        playerNames.put(toId, toName);
        savePlayerSettings(fromId);
        savePlayerSettings(toId);

        PlayerStatsManager.get().updateClaims(fromId, "Necunoscut", 0);
        PlayerStatsManager.get().updateClaims(toId, toName, getUsedClaimCount(toId));
    }

    public void adminClaim(ChunkPos pos, String dim, String name) {
        String key = makeChunkKey(pos, dim);
        String cleanName = sanitizeClaimName(name);
        String displayTitle = cleanName.isEmpty() ? ADMIN_CLAIM_NAME : cleanName;
        putClaimInMemory(key, ADMIN_UUID);
        playerNames.put(ADMIN_UUID, "§6" + ADMIN_CLAIM_NAME);
        chunkCustomNames.put(key, displayTitle);
        saveClaim(key, ADMIN_UUID, displayTitle);
        savePlayerSettings(ADMIN_UUID);
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

    public boolean setFlag(UUID owner, String claimId, String flagName, boolean state) {
        flagName = normalizeFlagName(flagName);
        if (!SUPPORTED_FLAGS.contains(flagName)) return false;
        if (claimId == null || claimId.isEmpty() || !ownsClaim(owner, claimId)) return false;
        Map<String, Map<String, Boolean>> ownerFlags = claimFlags.computeIfAbsent(owner, k -> new HashMap<>());
        String storageKey = getFlagStorageKey(owner, claimId);
        Map<String, Boolean> flags = ownerFlags.get(storageKey);
        if (flags == null) {
            flags = new HashMap<>(getFlagsForClaim(owner, claimId));
        }

        for (String siblingClaimId : getClaimIdsWithDisplayName(owner, storageKey)) {
            if (!storageKey.equals(siblingClaimId)) {
                ownerFlags.remove(siblingClaimId);
            }
        }

        String legacyDisplayName = getClaimDisplayName(claimId);
        if (legacyDisplayName != null && !storageKey.equals(legacyDisplayName)) {
            ownerFlags.remove(legacyDisplayName);
        }
        ownerFlags.put(storageKey, flags);

        putFlagValue(flags, flagName, state);
        if (state) {
            if (ClaimEnvironmentManager.FLAG_ALWAYS_MIDDLE_DAY.equals(flagName)) {
                putFlagValue(flags, ClaimEnvironmentManager.FLAG_ALWAYS_MIDDLE_NIGHT, false);
            } else if (ClaimEnvironmentManager.FLAG_ALWAYS_MIDDLE_NIGHT.equals(flagName)) {
                putFlagValue(flags, ClaimEnvironmentManager.FLAG_ALWAYS_MIDDLE_DAY, false);
            } else if (ClaimEnvironmentManager.FLAG_ALWAYS_SHINY.equals(flagName)) {
                putFlagValue(flags, ClaimEnvironmentManager.FLAG_ALWAYS_RAIN, false);
            } else if (ClaimEnvironmentManager.FLAG_ALWAYS_RAIN.equals(flagName)) {
                putFlagValue(flags, ClaimEnvironmentManager.FLAG_ALWAYS_SHINY, false);
            }
        }
        savePlayerFlags(owner);
        return true;
    }

    public boolean getFlag(UUID owner, String claimId, String flagName) {
        if (owner == null || claimId == null || claimId.isEmpty()) return false;
        String normalized = normalizeFlagName(flagName);
        if (!SUPPORTED_FLAGS.contains(normalized)) return false;
        Map<String, Boolean> flags = getFlagsForClaim(owner, claimId);
        if (flags.containsKey(normalized)) return flags.getOrDefault(normalized, false);

        String legacyName = getLegacyFlagName(normalized);
        return legacyName != null && flags.getOrDefault(legacyName, false);
    }

    public boolean addTrust(ServerPlayer owner, UUID target, String claimId, String role) {
        if (claimId == null || claimId.isEmpty() || !ownsClaim(owner.getUUID(), claimId)) return false;
        UUID ownerId = owner.getUUID();
        String groupKey = getClaimDisplayName(claimId);
        Map<String, Map<UUID, String>> ownerTrusts = trustedPlayers.computeIfAbsent(ownerId, k -> new HashMap<>());
        Map<UUID, String> trusts = collectAndRemoveGroupTrusts(ownerId, groupKey, ownerTrusts);
        trusts.put(target, normalizeTrustRole(role));
        ownerTrusts.put(groupKey, trusts);
        savePlayerSettings(ownerId);
        syncToClient(owner);
        return true;
    }

    public boolean removeTrust(ServerPlayer owner, UUID target, String claimId) {
        if (claimId == null || claimId.isEmpty() || !ownsClaim(owner.getUUID(), claimId)) return false;
        UUID ownerId = owner.getUUID();
        Map<String, Map<UUID, String>> ownerTrusts = trustedPlayers.get(ownerId);
        if (ownerTrusts == null) return false;

        String groupKey = getClaimDisplayName(claimId);
        Map<UUID, String> trusts = collectAndRemoveGroupTrusts(ownerId, groupKey, ownerTrusts);
        boolean removed = trusts.remove(target) != null;
        if (trusts.isEmpty()) ownerTrusts.remove(groupKey);
        else ownerTrusts.put(groupKey, trusts);

        savePlayerSettings(ownerId);
        syncToClient(owner);
        return removed;
    }

    private Map<UUID, String> collectAndRemoveGroupTrusts(UUID owner, String groupKey,
                                                           Map<String, Map<UUID, String>> ownerTrusts) {
        Map<UUID, String> merged = new HashMap<>();
        Map<UUID, String> groupedTrusts = ownerTrusts.remove(groupKey);
        if (groupedTrusts != null) merged.putAll(groupedTrusts);

        for (String groupedClaimId : getClaimIdsWithDisplayName(owner, groupKey)) {
            Map<UUID, String> perClaimTrusts = ownerTrusts.remove(groupedClaimId);
            if (perClaimTrusts != null) merged.putAll(perClaimTrusts);
        }
        return merged;
    }

    private void migrateTrustsToClaimGroups() {
        for (Map.Entry<UUID, Map<String, Map<UUID, String>>> ownerEntry : new ArrayList<>(trustedPlayers.entrySet())) {
            UUID owner = ownerEntry.getKey();
            Map<String, Map<UUID, String>> original = ownerEntry.getValue();
            if (original == null || original.isEmpty()) continue;

            Map<String, Map<UUID, String>> grouped = new HashMap<>();
            for (Map.Entry<String, Map<UUID, String>> trustEntry : original.entrySet()) {
                String storedKey = trustEntry.getKey();
                String groupKey = ownsClaim(owner, storedKey) ? getClaimDisplayName(storedKey) : storedKey;
                grouped.computeIfAbsent(groupKey, ignored -> new HashMap<>()).putAll(trustEntry.getValue());
            }

            if (!grouped.equals(original)) {
                trustedPlayers.put(owner, grouped);
                savePlayerSettings(owner);
            }
        }
    }

    public boolean isTrusted(UUID owner, UUID visitor, String claimId) {
        if (owner == null || owner.equals(visitor)) return true;
        if (claimId == null || claimId.isEmpty()) return false;
        return roleCanBuild(getTrustRole(owner, visitor, claimId));
    }

    public UUID getChunkOwner(ChunkPos pos, String dim) {
        return chunkOwners.get(makeChunkKey(pos, dim));
    }

    public String getOwnerName(UUID uuid) {
        if (uuid == null) return "Wilderness";
        if (ADMIN_UUID.equals(uuid)) return "§6" + ADMIN_CLAIM_NAME;
        String name = playerNames.get(uuid);
        return (name == null || name.isEmpty()) ? "Necunoscut" : name;
    }

    public String getCustomName(ChunkPos pos, String dim) {
        String key = makeChunkKey(pos, dim);
        return chunkCustomNames.getOrDefault(key, "");
    }

    public String getClaimEnterName(ChunkPos pos, String dim) {
        UUID owner = getChunkOwner(pos, dim);
        if (owner == null) return getOwnerName(null);

        String customName = getCustomName(pos, dim);
        if (ADMIN_UUID.equals(owner) && customName != null && !customName.trim().isEmpty()) {
            return customName.trim();
        }
        return getOwnerName(owner);
    }

    public int getMaxSlots(UUID p) {
        return boughtSlots.getOrDefault(p, 1);
    }

    public int getUsedSlots(UUID p) {
        Set<String> chunks = ownerChunkKeys.get(p);
        return chunks == null ? 0 : chunks.size();
    }

    public int getUsedClaimCount(UUID p) {
        if (ADMIN_UUID.equals(p)) return getUsedSlots(p);
        return ProtectionConfig.get().isPlotMode ? getUsedPlots(p) : getUsedSlots(p);
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

            Set<String> allMyClaimIds = new HashSet<>();
            Set<String> flagClaimIds = new HashSet<>();
            Map<String, String> claimDisplayNames = new HashMap<>();
            UUID visibleOwner = isAdminMap ? ADMIN_UUID : player.getUUID();
            Set<String> processedClaimIds = new HashSet<>();

            for (Map.Entry<String, UUID> entry : chunkOwners.entrySet()) {
                if (entry.getValue() != null) {
                    String claimId = getClaimIdFromChunkKey(entry.getKey());
                    if (!processedClaimIds.add(claimId)) continue;
                    boolean visibleClaim = entry.getValue().equals(visibleOwner);

                    if (visibleClaim) {
                        allMyClaimIds.add(claimId);
                        flagClaimIds.add(claimId);
                        claimDisplayNames.put(claimId, getClaimDisplayName(claimId));
                    } else if (!isAdminMap) {
                        String role = getTrustRole(entry.getValue(), player.getUUID(), claimId);
                        if (roleCanEditAnyFlag(role)) {
                            flagClaimIds.add(claimId);
                            claimDisplayNames.put(claimId, getClaimDisplayName(claimId));
                        }
                    }
                }
            }

            Map<String, Map<UUID, String>> trustedNamesPerClaim = new HashMap<>();
            Map<String, Map<UUID, String>> trustedRolesPerClaim = new HashMap<>();
            List<String> sortedOwnedClaimIds = new ArrayList<>(allMyClaimIds);
            sortedOwnedClaimIds.sort(Comparator.comparing((String id) -> claimDisplayNames.getOrDefault(id, id)).thenComparing(id -> id));
            Set<String> processedTrustGroups = new HashSet<>();
            for (String claimId : sortedOwnedClaimIds) {
                String displayName = claimDisplayNames.getOrDefault(claimId, claimId);
                if (!processedTrustGroups.add(displayName)) continue;
                Map<UUID, String> mappedNames = new HashMap<>();
                Map<UUID, String> roles = new HashMap<>(getTrustRoles(player.getUUID(), claimId));
                if (roles.isEmpty()) continue;
                for (UUID id : roles.keySet()) {
                    if (id != null && player.getServer() != null) {
                        try {
                            player.getServer().getProfileCache().get(id).ifPresent(pr -> mappedNames.put(id, pr.getName()));
                        } catch (Exception ignored) { }
                    }
                }
                trustedNamesPerClaim.put(claimId, mappedNames);
                trustedRolesPerClaim.put(claimId, roles);
            }

            Map<String, Map<String, Boolean>> myFlagsMap = new HashMap<>();
            for (String claimId : flagClaimIds) {
                UUID flagOwner = isAdminMap ? ADMIN_UUID : getClaimOwner(claimId);
                if (flagOwner != null) {
                    Map<String, Boolean> flags = new HashMap<>(getFlagsForClaim(flagOwner, claimId));
                    if (!flags.isEmpty()) myFlagsMap.put(claimId, flags);
                    claimDisplayNames.put(claimId, getClaimDisplayName(claimId));
                }
            }

            SyncData data = new SyncData(localClaims, trustedNamesPerClaim, trustedRolesPerClaim, myFlagsMap, allMyClaimIds, flagClaimIds, claimDisplayNames, getMaxSlots(player.getUUID()), getUsedClaimCount(player.getUUID()), getNextSlotCost(player.getUUID()));
            PacketHandler.sendToPlayer(new PacketHandler.S2C_SyncClaimData(GSON.toJson(data), isAdminMap), player);
        } catch (Exception e) {
            e.printStackTrace();
            player.sendSystemMessage(Component.literal("§c[Error] The map encountered an internal problem, but the server was protected!"));
        }
    }

    public static class SyncData {
        public Map<String, ClientClaimInfo> map;
        public Map<String, Map<UUID, String>> trustedPerClaim;
        public Map<String, Map<UUID, String>> trustedRolesPerClaim;
        public Map<String, Map<String, Boolean>> myFlags;
        public Set<String> allClaimNames;
        public Set<String> flagClaimNames;
        public Map<String, String> claimDisplayNames;
        public int maxSlots, usedSlots;
        public double nextSlotCost;

        public SyncData(Map<String, ClientClaimInfo> m, Map<String, Map<UUID, String>> t, Map<String, Map<UUID, String>> roles, Map<String, Map<String, Boolean>> flags, Set<String> names, Set<String> flagNames, Map<String, String> displayNames, int max, int used, double cost) {
            this.map = m;
            this.trustedPerClaim = t;
            this.trustedRolesPerClaim = roles;
            this.myFlags = flags;
            this.allClaimNames = names;
            this.flagClaimNames = flagNames;
            this.claimDisplayNames = displayNames;
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

    public void save() {
        try {
            Future<?> flush = dbExecutor.submit(() -> { });
            flush.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("[EvoProtection] Timed out while flushing queued database writes.");
            e.printStackTrace();
        }
    }
}
