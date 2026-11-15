package org.evocraft.evoprotection.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
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

    private String getTblClaims() { return "player_claims"; }
    private String getTblSettings() { return "claim_player_settings"; }
    private String getTblFlags() { return "claim_flags_data"; }
    private String getTblEvents() { return "claim_global_events"; }

    private void queueDbWrite(Runnable task) {
        Runnable wrapped = () -> {
            synchronized (DatabaseManager.get()) {
                try {
                    task.run();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
        if (dbExecutor.isShutdown()) {
            wrapped.run();
            return;
        }
        try {
            dbExecutor.execute(wrapped);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            wrapped.run();
        }
    }

    private String makeChunkKey(ChunkPos pos, String dim) {
        return pos.x + ";" + pos.z + ";" + dim;
    }

    private String makeChunkClaimId(ChunkPos pos, String dim) {
        return "chunk;" + makeChunkKey(pos, dim);
    }

    public String getClaimId(ChunkPos pos, String dim) {
        return makeChunkClaimId(pos, dim);
    }

    private String computeClaimIdFromChunkKey(String chunkKey) {
        return "chunk;" + chunkKey;
    }

    private String getClaimIdFromChunkKey(String chunkKey) {
        String cachedClaimId = chunkClaimIds.get(chunkKey);
        if (cachedClaimId != null) return cachedClaimId;

        UUID owner = chunkOwners.get(chunkKey);
        String claimId = computeClaimIdFromChunkKey(chunkKey);
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
            indexClaimChunk(entry.getKey(), entry.getValue(), computeClaimIdFromChunkKey(entry.getKey()));
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
        indexClaimChunk(chunkKey, owner, computeClaimIdFromChunkKey(chunkKey));
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
                    "use",
                    "chests",
                    "public_build",
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

        String groupKey = getClaimDisplayName(claimId);
        Map<UUID, String> merged = new HashMap<>();
        for (String siblingClaimId : getClaimIdsWithDisplayName(owner, groupKey)) {
            Map<UUID, String> legacyClaimTrusts = trusts.get(siblingClaimId);
            if (legacyClaimTrusts != null) merged.putAll(legacyClaimTrusts);
        }

        Map<UUID, String> groupedTrusts = trusts.get(groupKey);
        if (groupedTrusts != null) merged.putAll(groupedTrusts);
        return merged.isEmpty() ? Collections.emptyMap() : merged;
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
        boolean hasOtherNamedClaim = hasOtherClaimWithDisplayName(owner, flagStorageKey, claimId);

        Map<String, Map<UUID, String>> trusts = trustedPlayers.get(owner);
        if (trusts != null) {
            if (hasOtherNamedClaim) {
                Map<UUID, String> sharedTrusts = new HashMap<>(getTrustRoles(owner, claimId));
                if (!sharedTrusts.isEmpty() && legacyName != null && !legacyName.isEmpty()) {
                    trusts.put(legacyName, sharedTrusts);
                }
                if (legacyName == null || !legacyName.equals(claimId)) {
                    trusts.remove(claimId);
                }
            } else {
                trusts.remove(claimId);
                trusts.remove(legacyName);
            }
            savePlayerSettings(owner);
        }

        Map<String, Map<String, Boolean>> flags = claimFlags.get(owner);
        if (flags != null) {
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

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
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
                        if (uuidElement == null || uuidElement.isJsonNull()) continue;
                        UUID memberId = parseUuid(uuidElement.getAsString());
                        if (memberId != null) {
                            roles.put(memberId, ROLE_FRIEND);
                        }
                    }
                } else if (value != null && value.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> roleEntry : value.getAsJsonObject().entrySet()) {
                        if (roleEntry.getValue() == null || roleEntry.getValue().isJsonNull()) continue;
                        UUID memberId = parseUuid(roleEntry.getKey());
                        if (memberId != null) {
                            roles.put(memberId, normalizeTrustRole(roleEntry.getValue().getAsString()));
                        }
                    }
                }

                parsed.put(claimEntry.getKey(), roles);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return parsed;
    }

    public static synchronized void initialize() {
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

    public static void saveIfInitialized() {
        ClaimManager inst = INSTANCE;
        if (inst != null) {
            inst.save();
        }
    }

    public static synchronized void shutdown() {
        ClaimManager inst = INSTANCE;
        INSTANCE = null;
        if (inst != null) {
            inst.dbExecutor.shutdown();
            try {
                if (!inst.dbExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    inst.dbExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                inst.dbExecutor.shutdownNow();
            }
        }
    }

    public ClaimManager() {
        loadFromDatabase();
    }

    private static boolean tableExists(Connection conn, String tableName) {
        if (conn == null || tableName == null) return false;
        try {
            if (DatabaseManager.get().isSqlite()) {
                try (PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
                    stmt.setString(1, tableName);
                    try (ResultSet rs = stmt.executeQuery()) {
                        return rs.next();
                    }
                }
            } else {
                try (PreparedStatement stmt = conn.prepareStatement("SHOW TABLES LIKE ?")) {
                    stmt.setString(1, tableName);
                    try (ResultSet rs = stmt.executeQuery()) {
                        return rs.next();
                    }
                }
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    // Funcția care interoghează baza de date "players" la conectare folosind NUMELE
    public void reloadPlayerLanguage(UUID uuid, String playerName) {
        String lang = ProtectionConfig.get().language;
        if (lang == null || (!lang.equals("en") && !lang.equals("ro"))) {
            lang = "ro";
        }
        synchronized (DatabaseManager.get()) {
            try {
                Connection conn = DatabaseManager.get().getConnection();
                if (conn != null && tableExists(conn, "players")) {
                    // ATENȚIE: Dacă în tabel coloana pentru nume se numește 'name', modifică 'username' de mai jos în 'name'
                    try (PreparedStatement stmt = conn.prepareStatement("SELECT language FROM players WHERE username = ?")) {
                        stmt.setString(1, playerName);
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                String dbLang = rs.getString("language");
                                if (dbLang != null && (dbLang.equalsIgnoreCase("en") || dbLang.equalsIgnoreCase("ro"))) {
                                    lang = dbLang.toLowerCase(Locale.ROOT);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        playerLanguages.put(uuid, lang);
    }

    public String getPlayerLanguage(UUID uuid) {
        String fallback = ProtectionConfig.get().language;
        if (fallback == null || (!fallback.equals("en") && !fallback.equals("ro"))) {
            fallback = "ro";
        }
        return playerLanguages.getOrDefault(uuid, fallback);
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
                if (conn == null) {
                    throw new IllegalStateException("Database connection is unavailable.");
                }
                try (java.sql.Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + getTblEvents() + " (" +
                            "id INT PRIMARY KEY, stock INT, price DOUBLE)");

                    stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + getTblSettings() + " (" +
                            "uuid VARCHAR(36) PRIMARY KEY, player_name VARCHAR(32), bought_slots INT, trusted_data TEXT)");

                    stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + getTblFlags() + " (" +
                            "uuid VARCHAR(36) PRIMARY KEY, flags_data TEXT)");

                    stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + getTblClaims() + " (" +
                            "chunk_key VARCHAR(100) PRIMARY KEY, owner_uuid VARCHAR(36), custom_name VARCHAR(64))");
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
                        UUID uuid = parseUuid(rs.getString("uuid"));
                        if (uuid == null) continue;
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
                        UUID uuid = parseUuid(rs.getString("uuid"));
                        if (uuid == null) continue;
                        String flagsJson = rs.getString("flags_data");
                        if (flagsJson != null && !flagsJson.isEmpty()) {
                            java.lang.reflect.Type type = new TypeToken<Map<String, Map<String, Boolean>>>(){}.getType();
                            Map<String, Map<String, Boolean>> flags = GSON.fromJson(flagsJson, type);
                            if (flags != null) {
                                flags.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
                                flags.values().forEach(inner -> inner.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null));
                                claimFlags.put(uuid, flags);
                            }
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
                        UUID ownerUuid = parseUuid(rs.getString("owner_uuid"));
                        if (chunkKey == null || chunkKey.isBlank() || ownerUuid == null) continue;
                        String customName = rs.getString("custom_name");

                        chunkOwners.put(chunkKey, ownerUuid);
                        if (customName != null && !customName.isEmpty()) {
                            chunkCustomNames.put(chunkKey, customName);
                        }
                    }
                    rebuildClaimIndexes();
                    migrateTrustsToClaimGroups();
                    System.out.println("[EvoProtection] Successfully loaded " + chunkOwners.size() + " claims from " + DatabaseManager.get().type + ".");
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
                if (conn == null) return;
                boolean sqlite = DatabaseManager.get().isSqlite();
                String sql = sqlite
                        ? "INSERT INTO " + table + " (id, stock, price) VALUES (1, ?, ?) ON CONFLICT(id) DO UPDATE SET stock = excluded.stock, price = excluded.price"
                        : "INSERT INTO " + table + " (id, stock, price) VALUES (1, ?, ?) ON DUPLICATE KEY UPDATE stock = ?, price = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, stock);
                    stmt.setDouble(2, price);
                    if (!sqlite) {
                        stmt.setInt(3, stock);
                        stmt.setDouble(4, price);
                    }
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
                if (conn == null) return;
                boolean sqlite = DatabaseManager.get().isSqlite();
                String sql = sqlite
                        ? "INSERT INTO " + table + " (uuid, player_name, bought_slots, trusted_data) VALUES (?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET player_name = excluded.player_name, bought_slots = excluded.bought_slots, trusted_data = excluded.trusted_data"
                        : "INSERT INTO " + table + " (uuid, player_name, bought_slots, trusted_data) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE player_name = ?, bought_slots = ?, trusted_data = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, uuid.toString());
                    stmt.setString(2, name);
                    stmt.setInt(3, slots);
                    stmt.setString(4, trustedData);
                    if (!sqlite) {
                        stmt.setString(5, name);
                        stmt.setInt(6, slots);
                        stmt.setString(7, trustedData);
                    }
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
                if (conn == null) return;
                boolean sqlite = DatabaseManager.get().isSqlite();
                String sql = sqlite
                        ? "INSERT INTO " + table + " (uuid, flags_data) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET flags_data = excluded.flags_data"
                        : "INSERT INTO " + table + " (uuid, flags_data) VALUES (?, ?) ON DUPLICATE KEY UPDATE flags_data = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, uuid.toString());
                    stmt.setString(2, flagsData);
                    if (!sqlite) {
                        stmt.setString(3, flagsData);
                    }
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
                if (conn == null) return;
                boolean sqlite = DatabaseManager.get().isSqlite();
                String sql = sqlite
                        ? "INSERT INTO " + table + " (chunk_key, owner_uuid, custom_name) VALUES (?, ?, ?) ON CONFLICT(chunk_key) DO UPDATE SET owner_uuid = excluded.owner_uuid, custom_name = excluded.custom_name"
                        : "INSERT INTO " + table + " (chunk_key, owner_uuid, custom_name) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE owner_uuid = ?, custom_name = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, chunkKey);
                    stmt.setString(2, ownerUuid.toString());
                    stmt.setString(3, name);
                    if (!sqlite) {
                        stmt.setString(4, ownerUuid.toString());
                        stmt.setString(5, name);
                    }
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
                if (conn == null) return;
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM " + table + " WHERE chunk_key = ?")) {
                    stmt.setString(1, chunkKey);
                    stmt.executeUpdate();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
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
        if (player == null || claimName == null || claimName.isEmpty()) return false;
        for (Map.Entry<String, UUID> entry : chunkOwners.entrySet()) {
            if (player.getUUID().equals(entry.getValue())) {
                String claimId = getClaimIdFromChunkKey(entry.getKey());
                if (claimName.equals(getClaimDisplayName(claimId))) {
                    return unclaimGroupById(player, claimId);
                }
            }
        }
        return false;
    }

    public boolean claimChunk(ServerPlayer player, ChunkPos pos, String customName) {
        customName = sanitizeClaimName(customName);

        String key = makeChunkKey(pos, player.level().dimension().location().toString());
        if (chunkOwners.containsKey(key)) return false;

        if (getUsedSlots(player.getUUID()) < getMaxSlots(player.getUUID())) {
            putClaimInMemory(key, player.getUUID());
            playerNames.put(player.getUUID(), player.getGameProfile().getName());
            chunkCustomNames.put(key, customName);
            canonicalizeTrustsForClaimGroup(player.getUUID(), getClaimIdFromChunkKey(key));
            saveClaim(key, player.getUUID(), customName);
            savePlayerSettings(player.getUUID());

            syncToClient(player);
            PlayerStatsManager.get().updateClaims(player.getUUID(), player.getGameProfile().getName(), getUsedSlots(player.getUUID()));
            return true;
        }
        return false;
    }

    public boolean unclaimChunk(ServerPlayer player, ChunkPos pos) {
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

    public List<BlockPos> getPlayerClaimLocations(UUID uuid) {
        List<BlockPos> locations = new ArrayList<>();
        if (uuid == null) return locations;
        for (Map.Entry<String, UUID> entry : chunkOwners.entrySet()) {
            if (uuid.equals(entry.getValue())) {
                String[] parts = entry.getKey().split(";", 3);
                if (parts.length < 2) continue;
                try {
                    int x = Integer.parseInt(parts[0]) << 4;
                    int z = Integer.parseInt(parts[1]) << 4;
                    locations.add(new BlockPos(x + 8, 100, z + 8));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return locations;
    }

    public void startEvent(int stock, double price) {
        eventStock = Math.max(0, stock);
        eventPrice = (Double.isFinite(price) && price >= 0.0) ? price : 0.0;
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
        if (fromId == null || toId == null || fromId.equals(toId)) return;
        String safeToName = (toName == null || toName.isBlank()) ? "Unknown" : toName;
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
            Map<String, Map<UUID, String>> toTrusts = trustedPlayers.computeIfAbsent(toId, k -> new HashMap<>());
            for (Map.Entry<String, Map<UUID, String>> entry : fromTrusts.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    Map<UUID, String> mergedRoles = toTrusts.computeIfAbsent(entry.getKey(), k -> new HashMap<>());
                    mergedRoles.putAll(entry.getValue());
                    mergedRoles.remove(toId);
                }
            }
        }

        Map<String, Map<String, Boolean>> fromFlags = claimFlags.remove(fromId);
        if (fromFlags != null) {
            Map<String, Map<String, Boolean>> toFlags = claimFlags.computeIfAbsent(toId, k -> new HashMap<>());
            for (Map.Entry<String, Map<String, Boolean>> entry : fromFlags.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    toFlags.computeIfAbsent(entry.getKey(), k -> new HashMap<>()).putAll(entry.getValue());
                }
            }
            savePlayerFlags(fromId);
            savePlayerFlags(toId);
        }

        playerNames.put(toId, safeToName);
        savePlayerSettings(fromId);
        savePlayerSettings(toId);

        PlayerStatsManager.get().updateClaims(fromId, playerNames.getOrDefault(fromId, "Necunoscut"), 0);
        PlayerStatsManager.get().updateClaims(toId, safeToName, getUsedClaimCount(toId));
    }

    public void adminClaim(ChunkPos pos, String dim, String name) {
        if (pos == null || dim == null || dim.isBlank()) return;
        String key = makeChunkKey(pos, dim);
        String cleanName = sanitizeClaimName(name);
        String displayTitle = cleanName.isEmpty() ? ADMIN_CLAIM_NAME : cleanName;
        putClaimInMemory(key, ADMIN_UUID);
        playerNames.put(ADMIN_UUID, "§6" + ADMIN_CLAIM_NAME);
        chunkCustomNames.put(key, displayTitle);
        canonicalizeTrustsForClaimGroup(ADMIN_UUID, getClaimIdFromChunkKey(key));
        saveClaim(key, ADMIN_UUID, displayTitle);
        savePlayerSettings(ADMIN_UUID);
    }

    public double getNextSlotCost(UUID playerId) {
        if (eventStock > 0) return (Double.isFinite(eventPrice) && eventPrice >= 0.0) ? eventPrice : 0.0;
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
        if (player == null) return false;
        String lang = getPlayerLanguage(player.getUUID());
        UUID id = player.getUUID();
        int currentMax = getMaxSlots(id);
        if (currentMax >= Integer.MAX_VALUE - 1) return false;
        double price = getNextSlotCost(id);
        if (!Double.isFinite(price) || price < 0.0) return false;

        if (EconomyManager.get().tryWithdraw(id, price)) {
            boughtSlots.put(id, currentMax + 1);

            if (eventStock > 0) {
                eventStock--;
                saveGlobalEvent();
                if (player.getServer() != null) {
                    player.getServer().getPlayerList().broadcastSystemMessage(Component.literal(LanguageManager.get(lang, "msg.event.bought", player.getName().getString(), eventStock)), false);
                }
            }
            savePlayerSettings(id);
            syncToClient(player);
            return true;
        } else {
            player.sendSystemMessage(Component.literal(LanguageManager.get(lang, "msg.buy.no_money")));
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
        if (flags.containsKey(normalized)) {
            return Boolean.TRUE.equals(flags.get(normalized));
        }

        String legacyName = getLegacyFlagName(normalized);
        return legacyName != null && Boolean.TRUE.equals(flags.get(legacyName));
    }

    public boolean addTrust(ServerPlayer owner, UUID target, String claimId, String role) {
        if (claimId == null || claimId.isEmpty() || !ownsClaim(owner.getUUID(), claimId)) return false;
        return addTrustForOwner(owner, owner.getUUID(), target, claimId, role, false);
    }

    public boolean addAdminTrust(ServerPlayer admin, UUID target, String claimId, String role) {
        if (!ProtectionPermissions.hasAdminAccess(admin)
                || !isAdminClaimOwner(getClaimOwner(claimId))) {
            return false;
        }
        return addTrustForOwner(admin, ADMIN_UUID, target, claimId, role, true);
    }

    private boolean addTrustForOwner(ServerPlayer actor, UUID ownerId, UUID target, String claimId,
                                     String role, boolean adminMap) {
        if (target == null || !ownsClaim(ownerId, claimId)) return false;
        String groupKey = getClaimDisplayName(claimId);
        Map<String, Map<UUID, String>> ownerTrusts = trustedPlayers.computeIfAbsent(ownerId, k -> new HashMap<>());
        Map<UUID, String> trusts = collectAndRemoveGroupTrusts(ownerId, groupKey, ownerTrusts);
        trusts.put(target, normalizeTrustRole(role));
        ownerTrusts.put(groupKey, trusts);
        savePlayerSettings(ownerId);
        if (adminMap) syncToAdminClient(actor);
        else syncToClient(actor);
        if (!adminMap && actor != null && actor.getServer() != null && !target.equals(actor.getUUID())) {
            ServerPlayer targetPlayer = actor.getServer().getPlayerList().getPlayer(target);
            if (targetPlayer != null) syncToClient(targetPlayer);
        }
        return true;
    }

    public boolean removeTrust(ServerPlayer owner, UUID target, String claimId) {
        if (claimId == null || claimId.isEmpty() || !ownsClaim(owner.getUUID(), claimId)) return false;
        return removeTrustForOwner(owner, owner.getUUID(), target, claimId, false);
    }

    public boolean removeAdminTrust(ServerPlayer admin, UUID target, String claimId) {
        if (!ProtectionPermissions.hasAdminAccess(admin)
                || !isAdminClaimOwner(getClaimOwner(claimId))) {
            return false;
        }
        return removeTrustForOwner(admin, ADMIN_UUID, target, claimId, true);
    }

    private boolean removeTrustForOwner(ServerPlayer actor, UUID ownerId, UUID target, String claimId,
                                        boolean adminMap) {
        if (target == null || !ownsClaim(ownerId, claimId)) return false;
        Map<String, Map<UUID, String>> ownerTrusts = trustedPlayers.get(ownerId);
        if (ownerTrusts == null) return false;

        String groupKey = getClaimDisplayName(claimId);
        Map<UUID, String> trusts = collectAndRemoveGroupTrusts(ownerId, groupKey, ownerTrusts);
        boolean removed = trusts.remove(target) != null;
        if (trusts.isEmpty()) ownerTrusts.remove(groupKey);
        else ownerTrusts.put(groupKey, trusts);

        if (removed) {
            savePlayerSettings(ownerId);
        }
        if (adminMap) syncToAdminClient(actor);
        else syncToClient(actor);
        if (removed && !adminMap && actor != null && actor.getServer() != null && !target.equals(actor.getUUID())) {
            ServerPlayer targetPlayer = actor.getServer().getPlayerList().getPlayer(target);
            if (targetPlayer != null) syncToClient(targetPlayer);
        }
        return removed;
    }

    public boolean leaveTrust(ServerPlayer visitor, String claimId) {
        if (visitor == null || claimId == null || claimId.isEmpty()) return false;
        UUID ownerId = getClaimOwner(claimId);
        UUID visitorId = visitor.getUUID();
        if (ownerId == null || ownerId.equals(visitorId)) return false;

        Map<String, Map<UUID, String>> ownerTrusts = trustedPlayers.get(ownerId);
        if (ownerTrusts == null) return false;

        String groupKey = getClaimDisplayName(claimId);
        Map<UUID, String> trusts = collectAndRemoveGroupTrusts(ownerId, groupKey, ownerTrusts);
        boolean removed = trusts.remove(visitorId) != null;
        if (!removed) {
            if (!trusts.isEmpty()) ownerTrusts.put(groupKey, trusts);
            return false;
        }

        if (trusts.isEmpty()) ownerTrusts.remove(groupKey);
        else ownerTrusts.put(groupKey, trusts);
        savePlayerSettings(ownerId);
        syncToClient(visitor);

        ServerPlayer owner = visitor.getServer() == null
                ? null
                : visitor.getServer().getPlayerList().getPlayer(ownerId);
        if (owner != null) syncToClient(owner);
        return true;
    }

    private Map<UUID, String> collectAndRemoveGroupTrusts(UUID owner, String groupKey,
                                                           Map<String, Map<UUID, String>> ownerTrusts) {
        Map<UUID, String> merged = new HashMap<>();
        Map<UUID, String> groupedTrusts = ownerTrusts.remove(groupKey);

        for (String groupedClaimId : getClaimIdsWithDisplayName(owner, groupKey)) {
            Map<UUID, String> perClaimTrusts = ownerTrusts.remove(groupedClaimId);
            if (perClaimTrusts != null) merged.putAll(perClaimTrusts);
        }
        if (groupedTrusts != null) merged.putAll(groupedTrusts);
        return merged;
    }

    private void canonicalizeTrustsForClaimGroup(UUID owner, String claimId) {
        Map<String, Map<UUID, String>> ownerTrusts = trustedPlayers.get(owner);
        if (ownerTrusts == null || ownerTrusts.isEmpty()) return;

        String groupKey = getClaimDisplayName(claimId);
        Map<UUID, String> merged = collectAndRemoveGroupTrusts(owner, groupKey, ownerTrusts);
        if (!merged.isEmpty()) ownerTrusts.put(groupKey, merged);
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
        return getUsedSlots(p);
    }

    public void syncToClient(ServerPlayer player) {
        syncDataInternal(player, false);
    }

    public void syncToAdminClient(ServerPlayer player) {
        syncDataInternal(player, true);
    }

    private static boolean isBlankOrUnknownName(String name) {
        if (name == null) return true;
        String trimmed = name.trim();
        return trimmed.isEmpty() || "Unknown".equalsIgnoreCase(trimmed) || "Necunoscut".equalsIgnoreCase(trimmed);
    }

    private void syncDataInternal(ServerPlayer player, boolean isAdminMap) {
        if (player == null) return;
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
            Set<String> trustClaimIds = new HashSet<>();
            Set<String> flagClaimIds = new HashSet<>();
            Map<String, String> claimDisplayNames = new HashMap<>();
            Map<String, String> claimOwnerNames = new HashMap<>();
            Map<String, String> viewerRoles = new HashMap<>();
            Set<String> leaveableClaimIds = new HashSet<>();
            String playerLang = getPlayerLanguage(player.getUUID());
            UUID visibleOwner = isAdminMap ? ADMIN_UUID : player.getUUID();
            Set<String> processedClaimIds = new HashSet<>();

            for (Map.Entry<String, UUID> entry : chunkOwners.entrySet()) {
                if (entry.getValue() != null) {
                    String claimId = getClaimIdFromChunkKey(entry.getKey());
                    if (!processedClaimIds.add(claimId)) continue;
                    boolean visibleClaim = entry.getValue().equals(visibleOwner);

                    if (visibleClaim) {
                        allMyClaimIds.add(claimId);
                        trustClaimIds.add(claimId);
                        flagClaimIds.add(claimId);
                        claimDisplayNames.put(claimId, getClaimDisplayName(claimId));
                        claimOwnerNames.put(claimId,
                                isAdminClaimOwner(entry.getValue()) ? ADMIN_CLAIM_NAME : getOwnerName(entry.getValue()));
                        viewerRoles.put(claimId, ROLE_COOWNER);
                    } else if (!isAdminMap) {
                        String role = getTrustRole(entry.getValue(), player.getUUID(), claimId);
                        if (roleCanEditAnyFlag(role)) {
                            flagClaimIds.add(claimId);
                            String roleLabel = LanguageManager.get(playerLang, "gui.trust.role." + role);
                            claimDisplayNames.put(claimId, getClaimDisplayName(claimId) + " (" + roleLabel + ")");
                            claimOwnerNames.put(claimId,
                                    isAdminClaimOwner(entry.getValue()) ? ADMIN_CLAIM_NAME : getOwnerName(entry.getValue()));
                            viewerRoles.put(claimId, role);
                            leaveableClaimIds.add(claimId);
                        }
                    }
                }
            }

            if (!isAdminMap) {
                for (ProtectionRoomManager.ProtectionRoom room : ProtectionRoomManager.get().getOwnedRooms(player.getUUID())) {
                    String roomClientId = ProtectionRoomManager.get().getClientRoomId(room);
                    trustClaimIds.add(roomClientId);
                    claimDisplayNames.put(roomClientId, ProtectionRoomManager.get().getRoomDisplayName(room));
                    claimOwnerNames.put(roomClientId, ProtectionRoomManager.get().getRoomOwnerName(room));
                    viewerRoles.put(roomClientId, ROLE_COOWNER);
                }
                for (ProtectionRoomManager.ProtectionRoom room : ProtectionRoomManager.get().getFlagEditableRooms(player.getUUID())) {
                    String roomClientId = ProtectionRoomManager.get().getClientRoomId(room);
                    flagClaimIds.add(roomClientId);
                    boolean ownedByViewer = player.getUUID().equals(room.ownerUuid);
                    String role = ownedByViewer
                            ? ROLE_COOWNER
                            : ProtectionRoomManager.get().getRoomTrustRole(room, player.getUUID());
                    if (ownedByViewer) {
                        claimDisplayNames.put(roomClientId, ProtectionRoomManager.get().getRoomDisplayName(room));
                    } else {
                        String roleLabel = LanguageManager.get(playerLang, "gui.trust.role." + role);
                        claimDisplayNames.put(roomClientId,
                                ProtectionRoomManager.get().getRoomBaseDisplayName(room) + " (" + roleLabel + ")");
                        leaveableClaimIds.add(roomClientId);
                    }
                    claimOwnerNames.put(roomClientId, ProtectionRoomManager.get().getRoomOwnerName(room));
                    viewerRoles.put(roomClientId, role);
                }
            }

            Map<String, Map<UUID, String>> trustedNamesPerClaim = new HashMap<>();
            Map<String, Map<UUID, String>> trustedRolesPerClaim = new HashMap<>();
            List<String> sortedTrustClaimIds = new ArrayList<>(trustClaimIds);
            sortedTrustClaimIds.sort(Comparator.comparing((String id) -> claimDisplayNames.getOrDefault(id, id)).thenComparing(id -> id));
            Set<String> processedTrustGroups = new HashSet<>();
            for (String claimId : sortedTrustClaimIds) {
                String displayName = claimDisplayNames.getOrDefault(claimId, claimId);
                if (!ProtectionRoomManager.get().isClientRoomId(claimId) && !processedTrustGroups.add(displayName)) continue;
                Map<UUID, String> mappedNames = new HashMap<>();
                Map<UUID, String> roles = ProtectionRoomManager.get().isClientRoomId(claimId)
                        ? new HashMap<>(ProtectionRoomManager.get().getRoomTrustRolesByClientId(claimId))
                        : new HashMap<>(getTrustRoles(getClaimOwner(claimId), claimId));
                if (roles.isEmpty()) continue;
                for (UUID id : roles.keySet()) {
                    if (id == null) continue;
                    if (player.getServer() != null && player.getServer().getProfileCache() != null) {
                        try {
                            player.getServer().getProfileCache().get(id).ifPresent(pr -> {
                                String profileName = pr.getName();
                                if (!isBlankOrUnknownName(profileName)) {
                                    mappedNames.put(id, profileName);
                                }
                            });
                        } catch (Exception ignored) { }
                    }
                    if (!mappedNames.containsKey(id)) {
                        String resolved = playerNames.get(id);
                        if (isBlankOrUnknownName(resolved)) {
                            PlayerStatsManager.PlayerStats stats = PlayerStatsManager.get().getAllStats().get(id);
                            if (stats != null) {
                                synchronized (stats) {
                                    resolved = stats.name;
                                }
                            }
                        }
                        if (isBlankOrUnknownName(resolved)) {
                            resolved = id.toString().substring(0, 8);
                        }
                        mappedNames.put(id, resolved);
                    }
                }
                trustedNamesPerClaim.put(claimId, mappedNames);
                trustedRolesPerClaim.put(claimId, roles);
            }

            Map<String, Map<String, Boolean>> myFlagsMap = new HashMap<>();
            for (String claimId : flagClaimIds) {
                if (ProtectionRoomManager.get().isClientRoomId(claimId)) {
                    myFlagsMap.put(claimId, ProtectionRoomManager.get().getRoomFlagsByClientId(claimId));
                    continue;
                }
                UUID flagOwner = isAdminMap ? ADMIN_UUID : getClaimOwner(claimId);
                if (flagOwner != null) {
                    Map<String, Boolean> flags = new HashMap<>(getFlagsForClaim(flagOwner, claimId));
                    if (!flags.isEmpty()) myFlagsMap.put(claimId, flags);
                    claimDisplayNames.putIfAbsent(claimId, getClaimDisplayName(claimId));
                }
            }

            SyncData data = new SyncData(localClaims, trustedNamesPerClaim, trustedRolesPerClaim, myFlagsMap,
                    allMyClaimIds, trustClaimIds, flagClaimIds, claimDisplayNames,
                    claimOwnerNames, viewerRoles, leaveableClaimIds,
                    getMaxSlots(player.getUUID()), getUsedClaimCount(player.getUUID()), getNextSlotCost(player.getUUID()));
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
        public Set<String> trustClaimNames;
        public Set<String> flagClaimNames;
        public Map<String, String> claimDisplayNames;
        public Map<String, String> claimOwnerNames;
        public Map<String, String> viewerRoles;
        public Set<String> leaveableClaimNames;
        public int maxSlots, usedSlots;
        public double nextSlotCost;

        public SyncData(Map<String, ClientClaimInfo> m, Map<String, Map<UUID, String>> t,
                        Map<String, Map<UUID, String>> roles, Map<String, Map<String, Boolean>> flags,
                        Set<String> names, Set<String> trustNames, Set<String> flagNames,
                        Map<String, String> displayNames, Map<String, String> ownerNames,
                        Map<String, String> rolesForViewer, Set<String> leaveableNames,
                        int max, int used, double cost) {
            this.map = m;
            this.trustedPerClaim = t;
            this.trustedRolesPerClaim = roles;
            this.myFlags = flags;
            this.allClaimNames = names;
            this.trustClaimNames = trustNames;
            this.flagClaimNames = flagNames;
            this.claimDisplayNames = displayNames;
            this.claimOwnerNames = ownerNames;
            this.viewerRoles = rolesForViewer;
            this.leaveableClaimNames = leaveableNames;
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
        if (dbExecutor.isShutdown()) return;
        try {
            Future<?> flush = dbExecutor.submit(() -> { });
            flush.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        } catch (Exception e) {
            System.err.println("[EvoProtection] Timed out while flushing queued database writes.");
            e.printStackTrace();
        }
    }
}
