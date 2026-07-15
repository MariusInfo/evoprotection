package org.evocraft.evoprotection.manager;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import org.evocraft.evocore.data.EconomyManager;
import org.evocraft.evocore.database.DatabaseManager;
import org.evocraft.evocore.util.EvoCurrencyFormatter;
import org.evocraft.evoprotection.network.PacketHandler;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ProtectionRoomManager {
    private static ProtectionRoomManager INSTANCE;
    private static final UUID ADMIN_UUID = new UUID(0, 0);
    private static final String ROOM_TABLE = "evoprotection_rooms";
    private static final String CLIENT_ROOM_PREFIX = "room:";
    private static final String CONTRACT_TAG = "EvoProtectionRoomContract";
    private static final String CONTRACT_ROOM_ID = "EvoProtectionRoomId";
    private static final String CONTRACT_TOKEN = "EvoProtectionRoomToken";
    private static final int MAX_SELECTION_CHUNKS = 4096;
    private static final long RENT_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final long RENT_SWEEP_INTERVAL_MS = 60L * 1000L;
    private static final double MAX_SIGN_INTERACTION_DISTANCE_SQR = 64.0D;
    private static final Gson GSON = new Gson();

    private final Map<UUID, RoomSelection> selections = new ConcurrentHashMap<>();
    private final Map<UUID, PendingRoomSign> pendingSigns = new ConcurrentHashMap<>();
    private final Map<String, ProtectionRoom> rooms = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> roomIdsByChunk = new ConcurrentHashMap<>();
    private volatile long lastRentSweepAt = 0L;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "EvoProtection-Rooms-DB");
        thread.setDaemon(true);
        return thread;
    });

    public static void initialize() {
        if (INSTANCE == null) {
            INSTANCE = new ProtectionRoomManager();
        }
    }

    public static ProtectionRoomManager get() {
        if (INSTANCE == null) {
            initialize();
        }
        return INSTANCE;
    }

    private ProtectionRoomManager() {
        loadFromDatabase();
    }

    public void setSelectionPoint(ServerPlayer player, BlockPos pos, int point) {
        String dimension = player.level().dimension().location().toString();
        RoomSelection selection = selections.computeIfAbsent(player.getUUID(), id -> new RoomSelection(dimension));
        if (!dimension.equals(selection.dimension)) {
            selection = new RoomSelection(dimension);
            selections.put(player.getUUID(), selection);
        }

        if (point == 2) {
            selection.pos2 = pos.immutable();
        } else {
            selection.pos1 = pos.immutable();
        }

        player.sendSystemMessage(Component.literal("\u00A7a[EvoProtection] Room point " + point + " set at " + formatPos(pos) + "."));
        if (selection.pos1 != null && selection.pos2 != null) {
            player.sendSystemMessage(Component.literal("\u00A77Selection ready: \u00A7f" + getSelectionSize(selection) + "\u00A77 blocks."));
        } else {
            player.sendSystemMessage(Component.literal("\u00A77Use \u00A7fShift + Right Click\u00A77 for point 2."));
        }
    }

    public boolean createRoomContract(ServerPlayer admin, RoomOfferType offerType, double price, String requestedName) {
        if (!admin.hasPermissions(2)) {
            admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] Only admins can create room contracts."));
            return false;
        }

        if (!Double.isFinite(price) || price < 0.0D) {
            admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] Invalid price."));
            return false;
        }

        RoomSelection selection = selections.get(admin.getUUID());
        if (selection == null || selection.pos1 == null || selection.pos2 == null) {
            admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] Select two room points with the Protection Stick first."));
            return false;
        }

        ProtectionRoom room = buildRoomFromSelection(admin, selection, offerType, price, requestedName);
        if (room == null) {
            return false;
        }

        ProtectionRoom overlap = findOverlappingRoom(room);
        if (overlap != null) {
            admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] This selection overlaps room \u00A7e" + overlap.name + "\u00A7c."));
            return false;
        }

        rooms.put(room.roomId, room);
        indexRoom(room);
        saveRoom(room);
        ItemStack contract = createContractPaper(room);
        if (!admin.getInventory().add(contract)) {
            admin.drop(contract, false);
        }

        admin.sendSystemMessage(Component.literal("\u00A7a[EvoProtection] Contract created for \u00A7e" + room.name + "\u00A7a at \u00A7e" + EvoCurrencyFormatter.formatWithCurrency(room.price) + "\u00A7a."));
        return true;
    }

    public boolean prepareRoomSign(ServerPlayer admin, double buyPrice, double rentPrice, String requestedName) {
        if (!admin.hasPermissions(2)) {
            admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] Only admins can create room signs."));
            return false;
        }

        if (!Double.isFinite(buyPrice) || !Double.isFinite(rentPrice) || buyPrice < 0.0D || rentPrice < 0.0D) {
            admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] Invalid buy/rent price."));
            return false;
        }

        RoomSelection selection = selections.get(admin.getUUID());
        if (selection == null || selection.pos1 == null || selection.pos2 == null) {
            admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] Select two room points with the Protection Stick first."));
            return false;
        }

        pendingSigns.put(admin.getUUID(), new PendingRoomSign(buyPrice, rentPrice, sanitizeRoomName(requestedName)));
        admin.sendSystemMessage(Component.literal("\u00A7a[EvoProtection] Room sign armed."));
        admin.sendSystemMessage(Component.literal("\u00A77Right click a wall block with an empty main hand to place the Buy/Rent sign."));
        return true;
    }

    public boolean handlePendingRoomSignPlacement(ServerPlayer admin, BlockPos clickedPos, Direction face) {
        PendingRoomSign pending = pendingSigns.get(admin.getUUID());
        if (pending == null) return false;
        if (!admin.hasPermissions(2)) return false;

        if (face == null || face.getAxis().isVertical()) {
            admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] Click the side of a block so the sign can attach to a wall."));
            return true;
        }

        if (!admin.getMainHandItem().isEmpty()) {
            admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] Use an empty main hand to place the room sign."));
            return true;
        }

        RoomSelection selection = selections.get(admin.getUUID());
        if (selection == null || selection.pos1 == null || selection.pos2 == null) {
            admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] Select two room points with the Protection Stick first."));
            pendingSigns.remove(admin.getUUID());
            return true;
        }

        BlockPos signPos = clickedPos.relative(face);
        if (!admin.level().getBlockState(signPos).canBeReplaced()) {
            admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] The sign position is blocked."));
            return true;
        }

        ProtectionRoom room = buildRoomFromSelection(admin, selection, pending.buyPrice, pending.rentPrice, pending.name);
        if (room == null) return true;

        ProtectionRoom overlap = findOverlappingRoom(room);
        if (overlap != null) {
            admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] This selection overlaps room \u00A7e" + overlap.name + "\u00A7c."));
            return true;
        }

        if (!placeRoomSign(admin, room, clickedPos, face)) {
            return true;
        }

        rooms.put(room.roomId, room);
        indexRoom(room);
        saveRoom(room);
        pendingSigns.remove(admin.getUUID());

        admin.sendSystemMessage(Component.literal("\u00A7a[EvoProtection] Room sign placed for \u00A7e" + room.name + "\u00A7a."));
        admin.sendSystemMessage(Component.literal("\u00A77Buy: \u00A7e" + EvoCurrencyFormatter.formatWithCurrency(room.buyPrice)
                + "\u00A77, Rent: \u00A7e" + EvoCurrencyFormatter.formatWithCurrency(room.rentPrice)));
        return true;
    }

    public boolean handleRoomSignInteract(ServerPlayer player, BlockPos pos) {
        ProtectionRoom room = getRoomBySign(pos, player.level().dimension().location().toString());
        if (room == null) return false;

        if (room.ownerUuid != null) {
            if (room.ownerUuid.equals(player.getUUID())) {
                PacketHandler.sendToPlayer(new PacketHandler.S2C_OpenRoomOffer(
                        room.roomId,
                        room.name,
                        room.buyPrice,
                        room.rentPrice,
                        room.offerType == RoomOfferType.RENT
                                ? PacketHandler.S2C_OpenRoomOffer.MODE_MANAGE_RENT
                                : PacketHandler.S2C_OpenRoomOffer.MODE_MANAGE_BOUGHT,
                        false,
                        false
                ), player);
                return true;
            }

            player.sendSystemMessage(Component.literal("\u00A7e[EvoProtection] This room is already "
                    + claimedLabel(room) + " by \u00A7f" + room.ownerName + "\u00A7e."));
            return true;
        }

        if (room.sellerUuid != null && room.sellerUuid.equals(player.getUUID())) {
            PacketHandler.sendToPlayer(new PacketHandler.S2C_OpenRoomOffer(
                    room.roomId,
                    room.name,
                    room.buyPrice,
                    room.rentPrice,
                    PacketHandler.S2C_OpenRoomOffer.MODE_MANAGE_BOUGHT,
                    false,
                    false
            ), player);
            return true;
        }

        PacketHandler.sendToPlayer(new PacketHandler.S2C_OpenRoomOffer(
                room.roomId,
                room.name,
                room.buyPrice,
                room.rentPrice,
                PacketHandler.S2C_OpenRoomOffer.MODE_OFFER,
                room.canBuy(),
                room.canRent()
        ), player);
        return true;
    }

    public boolean handleRoomOfferAction(ServerPlayer player, String roomId, RoomOfferAction action, double requestedPrice) {
        ProtectionRoom room = rooms.get(roomId);
        if (room == null) {
            player.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] This room sign is no longer valid."));
            return false;
        }
        if (!canUseRoomSign(player, room)) {
            player.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] Stay near the valid room sign to use this menu."));
            return false;
        }

        return switch (action) {
            case BUY -> claimRoomFromSign(player, room, RoomOfferType.SELL);
            case RENT -> claimRoomFromSign(player, room, RoomOfferType.RENT);
            case CANCEL_RENT -> cancelRent(player, room);
            case LIST_SELL -> listBoughtRoom(player, room, RoomListingMode.SELL_ONLY, requestedPrice);
            case LIST_RENT -> listBoughtRoom(player, room, RoomListingMode.RENT_ONLY, requestedPrice);
        };
    }

    private boolean claimRoomFromSign(ServerPlayer buyer, ProtectionRoom room, RoomOfferType choice) {
        if (room.ownerUuid != null) {
            buyer.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] This room already has an owner."));
            updateRoomSign(room);
            return false;
        }

        if (choice == RoomOfferType.SELL && !room.canBuy()) {
            buyer.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] This room is not listed for sale."));
            return false;
        }

        if (choice == RoomOfferType.RENT && !room.canRent()) {
            buyer.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] This room is not listed for rent."));
            return false;
        }

        double price = choice == RoomOfferType.RENT ? room.rentPrice : room.buyPrice;
        if (!Double.isFinite(price) || price < 0.0D) {
            buyer.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] This option is not available."));
            return false;
        }

        double balance = EconomyManager.get().getBalance(buyer.getUUID());
        if (balance < price) {
            buyer.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] You need \u00A7e"
                    + EvoCurrencyFormatter.formatWithCurrency(price) + "\u00A7c."));
            return false;
        }

        UUID previousSellerUuid = room.sellerUuid;
        String previousSellerName = room.sellerName;

        if (price > 0.0D) {
            EconomyManager.get().removeBalance(buyer.getUUID(), price);
            EconomyManager.get().addBalance(previousSellerUuid, price);
        }

        room.ownerUuid = buyer.getUUID();
        room.ownerName = buyer.getGameProfile().getName();
        room.flags.clear();
        room.trustedRoles.clear();
        room.offerType = choice;
        room.price = price;
        room.contractToken = "";
        room.lastRentChargeAt = choice == RoomOfferType.RENT ? System.currentTimeMillis() : 0L;
        if (choice == RoomOfferType.SELL) {
            room.sellerUuid = buyer.getUUID();
            room.sellerName = buyer.getGameProfile().getName();
        }
        room.updatedAt = System.currentTimeMillis();
        saveRoom(room);
        updateRoomSign(room);
        ClaimManager.get().syncToClient(buyer);
        ClaimEnvironmentManager.get().refreshRoomPlayers(buyer.getServer(), room.roomId);

        buyer.sendSystemMessage(Component.literal("\u00A7a[EvoProtection] You "
                + (choice == RoomOfferType.RENT ? "rented " : "bought ")
                + "\u00A7e" + room.name + "\u00A7a for \u00A7e" + EvoCurrencyFormatter.formatWithCurrency(price) + "\u00A7a."));

        ServerPlayer seller = buyer.getServer() == null ? null : buyer.getServer().getPlayerList().getPlayer(previousSellerUuid);
        if (seller != null) {
            seller.sendSystemMessage(Component.literal("\u00A7a[EvoProtection] \u00A7e"
                    + buyer.getGameProfile().getName() + "\u00A7a "
                    + (choice == RoomOfferType.RENT ? "rented " : "bought ")
                    + "\u00A7e" + room.name + "\u00A7a for \u00A7e" + EvoCurrencyFormatter.formatWithCurrency(price) + "\u00A7a."));
        } else if (previousSellerName != null && choice == RoomOfferType.SELL) {
            // Seller balance is updated even when offline; this keeps notification best-effort only.
        }
        return true;
    }

    private boolean cancelRent(ServerPlayer player, ProtectionRoom room) {
        if (room.ownerUuid == null || !room.ownerUuid.equals(player.getUUID()) || room.offerType != RoomOfferType.RENT) {
            player.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] You are not renting this room."));
            return false;
        }

        String roomName = room.name;
        clearRoomOwner(room);
        room.lastRentChargeAt = 0L;
        room.updatedAt = System.currentTimeMillis();
        saveRoom(room);
        updateRoomSign(room);
        ClaimManager.get().syncToClient(player);
        ClaimEnvironmentManager.get().refreshRoomPlayers(player.getServer(), room.roomId);

        player.sendSystemMessage(Component.literal("\u00A7a[EvoProtection] Rent cancelled for \u00A7e" + roomName + "\u00A7a."));
        player.sendSystemMessage(Component.literal("\u00A77The room returned to \u00A7f" + room.sellerName + "\u00A77."));
        return true;
    }

    private boolean listBoughtRoom(ServerPlayer player, ProtectionRoom room, RoomListingMode listingMode, double requestedPrice) {
        boolean ownsBoughtRoom = room.ownerUuid != null
                && room.ownerUuid.equals(player.getUUID())
                && room.offerType == RoomOfferType.SELL;
        boolean managesAvailableRoom = room.ownerUuid == null
                && room.sellerUuid != null
                && room.sellerUuid.equals(player.getUUID());
        if (!ownsBoughtRoom && !managesAvailableRoom) {
            player.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] You must own this room to list it."));
            return false;
        }

        if (!Double.isFinite(requestedPrice) || requestedPrice < 0.0D) {
            player.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] Enter a valid price."));
            return false;
        }

        room.sellerUuid = player.getUUID();
        room.sellerName = player.getGameProfile().getName();
        clearRoomOwner(room);
        room.listingMode = listingMode;
        if (listingMode == RoomListingMode.RENT_ONLY) {
            room.rentPrice = requestedPrice;
            room.price = requestedPrice;
        } else {
            room.buyPrice = requestedPrice;
            room.price = requestedPrice;
        }
        room.lastRentChargeAt = 0L;
        room.updatedAt = System.currentTimeMillis();
        saveRoom(room);
        updateRoomSign(room);
        ClaimManager.get().syncToClient(player);
        ClaimEnvironmentManager.get().refreshRoomPlayers(player.getServer(), room.roomId);

        player.sendSystemMessage(Component.literal("\u00A7a[EvoProtection] Room listed for "
                + (listingMode == RoomListingMode.RENT_ONLY ? "rent" : "sale")
                + " at \u00A7e" + EvoCurrencyFormatter.formatWithCurrency(requestedPrice) + "\u00A7a."));
        return true;
    }

    public boolean placeBackupSignForPlayer(ServerPlayer admin, String playerName, BlockPos clickedPos, Direction face) {
        if (!admin.hasPermissions(2)) {
            admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] Only admins can restore room signs."));
            return false;
        }

        ProtectionRoom room = findRoomForPlayer(playerName, admin.level().dimension().location().toString(), admin.blockPosition());
        if (room == null) {
            admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] No room found for player \u00A7e" + playerName + "\u00A7c."));
            return false;
        }

        if (!placeRoomSign(admin, room, clickedPos, face)) {
            return false;
        }

        saveRoom(room);
        admin.sendSystemMessage(Component.literal("\u00A7a[EvoProtection] Restored room sign for \u00A7e" + room.name + "\u00A7a."));
        return true;
    }

    public boolean isRoomSign(BlockPos pos, String dimension) {
        return getRoomBySign(pos, dimension) != null;
    }

    public boolean isRoomContract(ItemStack stack) {
        return !stack.isEmpty() && stack.hasTag() && stack.getTag() != null && stack.getTag().getBoolean(CONTRACT_TAG);
    }

    public boolean redeemContract(ServerPlayer buyer, ItemStack stack) {
        if (!isRoomContract(stack) || stack.getTag() == null) {
            return false;
        }

        CompoundTag tag = stack.getTag();
        String roomId = tag.getString(CONTRACT_ROOM_ID);
        String token = tag.getString(CONTRACT_TOKEN);
        ProtectionRoom room = rooms.get(roomId);

        if (room == null || room.contractToken == null || room.contractToken.isEmpty() || !room.contractToken.equals(token)) {
            buyer.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] This room contract is no longer valid."));
            return false;
        }

        if (room.ownerUuid != null) {
            buyer.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] This room already has an owner."));
            return false;
        }

        double balance = EconomyManager.get().getBalance(buyer.getUUID());
        if (balance < room.price) {
            buyer.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] You need \u00A7e" + EvoCurrencyFormatter.formatWithCurrency(room.price) + "\u00A7c to claim this room."));
            return false;
        }

        UUID previousSellerUuid = room.sellerUuid;

        if (room.price > 0.0D) {
            EconomyManager.get().removeBalance(buyer.getUUID(), room.price);
            EconomyManager.get().addBalance(previousSellerUuid, room.price);
        }

        room.ownerUuid = buyer.getUUID();
        room.ownerName = buyer.getGameProfile().getName();
        room.flags.clear();
        room.trustedRoles.clear();
        if (room.offerType == RoomOfferType.SELL) {
            room.sellerUuid = buyer.getUUID();
            room.sellerName = buyer.getGameProfile().getName();
        }
        room.contractToken = "";
        room.updatedAt = System.currentTimeMillis();
        saveRoom(room);
        stack.shrink(1);
        ClaimManager.get().syncToClient(buyer);
        ClaimEnvironmentManager.get().refreshRoomPlayers(buyer.getServer(), room.roomId);

        buyer.sendSystemMessage(Component.literal("\u00A7a[EvoProtection] You now own room \u00A7e" + room.name + "\u00A7a."));
        ServerPlayer seller = buyer.getServer() == null ? null : buyer.getServer().getPlayerList().getPlayer(previousSellerUuid);
        if (seller != null) {
            seller.sendSystemMessage(Component.literal("\u00A7a[EvoProtection] \u00A7e" + buyer.getGameProfile().getName() + "\u00A7a claimed \u00A7e" + room.name + "\u00A7a for \u00A7e" + EvoCurrencyFormatter.formatWithCurrency(room.price) + "\u00A7a."));
        }
        return true;
    }

    public ProtectionRoom getRoomAt(BlockPos pos, String dimension) {
        if (pos == null || dimension == null) return null;
        Set<String> roomIds = roomIdsByChunk.get(roomChunkKey(pos.getX() >> 4, pos.getZ() >> 4, dimension));
        if (roomIds == null || roomIds.isEmpty()) return null;
        for (String roomId : roomIds) {
            ProtectionRoom room = rooms.get(roomId);
            if (room == null) continue;
            if (room.contains(pos, dimension)) {
                return room;
            }
        }
        return null;
    }

    public boolean canPlayerAccessRoom(ServerPlayer player, ProtectionRoom room) {
        if (ProtectionPermissions.hasAdminAccess(player)) return true;
        if (room == null) return false;
        if (room.ownerUuid != null) {
            if (room.ownerUuid.equals(player.getUUID())) return true;
            return ClaimManager.roleCanBuild(getRoomTrustRole(room, player.getUUID()));
        }
        return room.sellerUuid != null && room.sellerUuid.equals(player.getUUID()) && !ADMIN_UUID.equals(room.sellerUuid);
    }

    public boolean canPlayerUseRoomFlag(ServerPlayer player, ProtectionRoom room, String flagName) {
        return canPlayerAccessRoom(player, room) || getRoomFlag(room, flagName);
    }

    public boolean getRoomFlag(ProtectionRoom room, String flagName) {
        if (room == null) return false;
        String normalized = ClaimManager.normalizeFlagName(flagName);
        if (!ClaimManager.isSupportedFlagName(normalized)) return false;
        return room.flags.getOrDefault(normalized, false);
    }

    public List<ProtectionRoom> getOwnedRooms(UUID ownerUuid) {
        if (ownerUuid == null) return List.of();
        List<ProtectionRoom> ownedRooms = new ArrayList<>();
        for (ProtectionRoom room : rooms.values()) {
            if (ownerUuid.equals(room.ownerUuid)) {
                ownedRooms.add(room);
            }
        }
        ownedRooms.sort(Comparator
                .comparing((ProtectionRoom room) -> room.name == null ? "" : room.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(room -> room.roomId));
        return ownedRooms;
    }

    public List<ProtectionRoom> getFlagEditableRooms(UUID playerUuid) {
        if (playerUuid == null) return List.of();
        List<ProtectionRoom> editableRooms = new ArrayList<>();
        for (ProtectionRoom room : rooms.values()) {
            if (room.ownerUuid == null) continue;
            if (room.ownerUuid.equals(playerUuid)
                    || ClaimManager.roleCanEditAnyFlag(getRoomTrustRole(room, playerUuid))) {
                editableRooms.add(room);
            }
        }
        editableRooms.sort(Comparator
                .comparing((ProtectionRoom room) -> room.name == null ? "" : room.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(room -> room.roomId));
        return editableRooms;
    }

    public String getClientRoomId(ProtectionRoom room) {
        return room == null ? "" : CLIENT_ROOM_PREFIX + room.roomId;
    }

    public boolean isClientRoomId(String value) {
        return value != null && value.startsWith(CLIENT_ROOM_PREFIX);
    }

    public Map<String, Boolean> getRoomFlagsByClientId(String clientRoomId) {
        ProtectionRoom room = getRoomByClientId(clientRoomId);
        return room == null ? Map.of() : new HashMap<>(room.flags);
    }

    public Map<UUID, String> getRoomTrustRolesByClientId(String clientRoomId) {
        ProtectionRoom room = getRoomByClientId(clientRoomId);
        return room == null ? Map.of() : new HashMap<>(room.trustedRoles);
    }

    public String getRoomTrustRole(ProtectionRoom room, UUID playerUuid) {
        if (room == null || playerUuid == null) return ClaimManager.ROLE_VISITOR;
        if (playerUuid.equals(room.ownerUuid)) return ClaimManager.ROLE_COOWNER;
        String role = room.trustedRoles.get(playerUuid);
        return role == null ? ClaimManager.ROLE_VISITOR : ClaimManager.normalizeTrustRole(role);
    }

    public String getRoomDisplayName(ProtectionRoom room) {
        if (room == null) return "Room";
        String ownership = room.offerType == RoomOfferType.RENT ? "Rented" : "Owned";
        return "Room: " + room.name + " (" + ownership + ")";
    }

    public boolean setRoomFlag(ServerPlayer player, String clientRoomId, String flagName, boolean state) {
        if (player == null || !isClientRoomId(clientRoomId)) return false;
        ProtectionRoom room = getRoomByClientId(clientRoomId);
        String normalized = ClaimManager.normalizeFlagName(flagName);
        if (room == null || room.ownerUuid == null) return false;
        if (!ClaimManager.isSupportedFlagName(normalized)) return false;
        if (!room.ownerUuid.equals(player.getUUID())
                && !ClaimManager.roleCanEditFlag(getRoomTrustRole(room, player.getUUID()), normalized)) {
            return false;
        }

        room.flags.put(normalized, state);
        if (state) {
            if (ClaimEnvironmentManager.FLAG_ALWAYS_MIDDLE_DAY.equals(normalized)) {
                room.flags.put(ClaimEnvironmentManager.FLAG_ALWAYS_MIDDLE_NIGHT, false);
            } else if (ClaimEnvironmentManager.FLAG_ALWAYS_MIDDLE_NIGHT.equals(normalized)) {
                room.flags.put(ClaimEnvironmentManager.FLAG_ALWAYS_MIDDLE_DAY, false);
            } else if (ClaimEnvironmentManager.FLAG_ALWAYS_SHINY.equals(normalized)) {
                room.flags.put(ClaimEnvironmentManager.FLAG_ALWAYS_RAIN, false);
            } else if (ClaimEnvironmentManager.FLAG_ALWAYS_RAIN.equals(normalized)) {
                room.flags.put(ClaimEnvironmentManager.FLAG_ALWAYS_SHINY, false);
            }
        }
        room.updatedAt = System.currentTimeMillis();
        saveRoom(room);
        return true;
    }

    public boolean addRoomTrust(ServerPlayer owner, UUID targetUuid, String clientRoomId, String role) {
        if (owner == null || targetUuid == null) return false;
        ProtectionRoom room = getRoomByClientId(clientRoomId);
        if (room == null || room.ownerUuid == null || !room.ownerUuid.equals(owner.getUUID())) return false;
        if (targetUuid.equals(owner.getUUID())) return false;

        room.trustedRoles.put(targetUuid, ClaimManager.normalizeTrustRole(role));
        room.updatedAt = System.currentTimeMillis();
        saveRoom(room);
        ClaimManager.get().syncToClient(owner);
        ServerPlayer target = owner.getServer() == null ? null : owner.getServer().getPlayerList().getPlayer(targetUuid);
        if (target != null) ClaimManager.get().syncToClient(target);
        return true;
    }

    public boolean removeRoomTrust(ServerPlayer owner, UUID targetUuid, String clientRoomId) {
        if (owner == null || targetUuid == null) return false;
        ProtectionRoom room = getRoomByClientId(clientRoomId);
        if (room == null || room.ownerUuid == null || !room.ownerUuid.equals(owner.getUUID())) return false;
        if (room.trustedRoles.remove(targetUuid) == null) return false;

        room.updatedAt = System.currentTimeMillis();
        saveRoom(room);
        ClaimManager.get().syncToClient(owner);
        ServerPlayer target = owner.getServer() == null ? null : owner.getServer().getPlayerList().getPlayer(targetUuid);
        if (target != null) ClaimManager.get().syncToClient(target);
        return true;
    }

    public ProtectionRoom getRoomByClientId(String clientRoomId) {
        if (!isClientRoomId(clientRoomId)) return null;
        return rooms.get(clientRoomId.substring(CLIENT_ROOM_PREFIX.length()));
    }

    public void tickRentPayments(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now - lastRentSweepAt < RENT_SWEEP_INTERVAL_MS) return;
        lastRentSweepAt = now;

        for (ProtectionRoom room : rooms.values()) {
            if (room.ownerUuid == null || room.offerType != RoomOfferType.RENT || room.rentPrice <= 0.0D) continue;

            long lastCharge = room.lastRentChargeAt <= 0L ? room.updatedAt : room.lastRentChargeAt;
            if (now - lastCharge < RENT_INTERVAL_MS) continue;

            ServerPlayer renter = server == null ? null : server.getPlayerList().getPlayer(room.ownerUuid);
            ServerPlayer seller = server == null ? null : server.getPlayerList().getPlayer(room.sellerUuid);
            double balance = EconomyManager.get().getBalance(room.ownerUuid);

            if (balance + 0.0001D < room.rentPrice) {
                String renterName = room.ownerName == null ? "The renter" : room.ownerName;
                clearRoomOwner(room);
                room.lastRentChargeAt = 0L;
                room.updatedAt = now;
                saveRoom(room);
                updateRoomSign(room);
                ClaimEnvironmentManager.get().refreshRoomPlayers(server, room.roomId);

                if (renter != null) {
                    ClaimManager.get().syncToClient(renter);
                    renter.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] Rent cancelled for \u00A7e"
                            + room.name + "\u00A7c because you do not have enough money."));
                }
                if (seller != null) {
                    seller.sendSystemMessage(Component.literal("\u00A7e[EvoProtection] Rent for \u00A7f"
                            + room.name + "\u00A7e was cancelled because \u00A7f" + renterName + "\u00A7e could not pay."));
                }
                continue;
            }

            EconomyManager.get().removeBalance(room.ownerUuid, room.rentPrice);
            EconomyManager.get().addBalance(room.sellerUuid, room.rentPrice);
            room.lastRentChargeAt = now;
            room.updatedAt = now;
            saveRoom(room);

            if (renter != null) {
                renter.sendSystemMessage(Component.literal("\u00A7a[EvoProtection] Paid rent for \u00A7e"
                        + room.name + "\u00A7a: \u00A7e" + EvoCurrencyFormatter.formatWithCurrency(room.rentPrice)));
            }
            if (seller != null) {
                seller.sendSystemMessage(Component.literal("\u00A7a[EvoProtection] Received rent for \u00A7e"
                        + room.name + "\u00A7a: \u00A7e" + EvoCurrencyFormatter.formatWithCurrency(room.rentPrice)));
            }
        }
    }

    public boolean removeRoomByName(ServerPlayer admin, String requestedName) {
        if (!admin.hasPermissions(2)) {
            admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] Only admins can remove rooms."));
            return false;
        }

        ProtectionRoom room = findRoomByName(requestedName);
        if (room == null) {
            admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] No room found with name \u00A7e" + requestedName + "\u00A7c."));
            return false;
        }

        double refund = 0.0D;
        UUID refundTarget = null;
        String refundName = "";
        if (room.ownerUuid != null && room.offerType == RoomOfferType.SELL && room.price > 0.0D) {
            refund = room.price;
            refundTarget = room.ownerUuid;
            refundName = room.ownerName == null ? "player" : room.ownerName;
            EconomyManager.get().addBalance(refundTarget, refund);
        }

        removeRoomSignBlock(room);
        unindexRoom(room);
        rooms.remove(room.roomId);
        deleteRoom(room.roomId);
        ClaimEnvironmentManager.get().refreshAllPlayers(admin.getServer());

        admin.sendSystemMessage(Component.literal("\u00A7a[EvoProtection] Removed room \u00A7e" + room.name + "\u00A7a."));
        if (refundTarget != null) {
            admin.sendSystemMessage(Component.literal("\u00A77Refunded \u00A7e" + EvoCurrencyFormatter.formatWithCurrency(refund)
                    + "\u00A77 to \u00A7f" + refundName + "\u00A77."));
            ServerPlayer refunded = admin.getServer() == null ? null : admin.getServer().getPlayerList().getPlayer(refundTarget);
            if (refunded != null) {
                ClaimManager.get().syncToClient(refunded);
                refunded.sendSystemMessage(Component.literal("\u00A7a[EvoProtection] Room \u00A7e" + room.name
                        + "\u00A7a was removed. You received \u00A7e" + EvoCurrencyFormatter.formatWithCurrency(refund) + "\u00A7a back."));
            }
        } else if (room.offerType == RoomOfferType.RENT) {
            admin.sendSystemMessage(Component.literal("\u00A77Any rent billing for this room has been stopped."));
        }
        return true;
    }

    public void save() {
        try {
            Future<?> flush = dbExecutor.submit(() -> { });
            flush.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("[EvoProtection] Timed out while flushing queued room database writes.");
            e.printStackTrace();
        }
    }

    private ProtectionRoom buildRoomFromSelection(ServerPlayer admin, RoomSelection selection, RoomOfferType offerType, double price, String requestedName) {
        ProtectionRoom room = buildRoomFromSelection(admin, selection,
                offerType == RoomOfferType.RENT ? -1.0D : price,
                offerType == RoomOfferType.RENT ? price : -1.0D,
                requestedName);
        if (room != null) {
            room.offerType = offerType;
            room.price = price;
            room.contractToken = UUID.randomUUID().toString();
        }
        return room;
    }

    private ProtectionRoom buildRoomFromSelection(ServerPlayer admin, RoomSelection selection, double buyPrice, double rentPrice, String requestedName) {
        int minX = Math.min(selection.pos1.getX(), selection.pos2.getX());
        int minY = Math.min(selection.pos1.getY(), selection.pos2.getY());
        int minZ = Math.min(selection.pos1.getZ(), selection.pos2.getZ());
        int maxX = Math.max(selection.pos1.getX(), selection.pos2.getX());
        int maxY = Math.max(selection.pos1.getY(), selection.pos2.getY());
        int maxZ = Math.max(selection.pos1.getZ(), selection.pos2.getZ());

        int minChunkX = Math.floorDiv(minX, 16);
        int maxChunkX = Math.floorDiv(maxX, 16);
        int minChunkZ = Math.floorDiv(minZ, 16);
        int maxChunkZ = Math.floorDiv(maxZ, 16);
        long chunkCount = (long) (maxChunkX - minChunkX + 1) * (long) (maxChunkZ - minChunkZ + 1);
        if (chunkCount > MAX_SELECTION_CHUNKS) {
            admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] Selection is too large."));
            return null;
        }

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                UUID owner = ClaimManager.get().getChunkOwner(new ChunkPos(cx, cz), selection.dimension);
                if (!ADMIN_UUID.equals(owner)) {
                    admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] Rooms must stay fully inside ADMIN protection chunks."));
                    return null;
                }
            }
        }

        String roomId = UUID.randomUUID().toString();
        String cleanName = sanitizeRoomName(requestedName);
        if (cleanName.isEmpty()) {
            cleanName = "Room " + roomId.substring(0, 8);
        }

        String parentClaimId = ClaimManager.get().getClaimId(new ChunkPos(minChunkX, minChunkZ), selection.dimension);
        long now = System.currentTimeMillis();
        ProtectionRoom room = new ProtectionRoom();
        room.roomId = roomId;
        room.dimension = selection.dimension;
        room.parentClaimId = parentClaimId;
        room.name = cleanName;
        room.minX = minX;
        room.minY = minY;
        room.minZ = minZ;
        room.maxX = maxX;
        room.maxY = maxY;
        room.maxZ = maxZ;
        room.offerType = RoomOfferType.SELL;
        room.price = buyPrice >= 0.0D ? buyPrice : Math.max(0.0D, rentPrice);
        room.buyPrice = buyPrice;
        room.rentPrice = rentPrice;
        room.sellerUuid = admin.getUUID();
        room.sellerName = admin.getGameProfile().getName();
        room.contractToken = "";
        room.createdAt = now;
        room.updatedAt = now;
        return room;
    }

    private boolean placeRoomSign(ServerPlayer admin, ProtectionRoom room, BlockPos clickedPos, Direction face) {
        if (face == null || face.getAxis().isVertical()) {
            admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] Click the side of a block so the sign can attach to a wall."));
            return false;
        }

        BlockPos signPos = clickedPos.relative(face);
        if (!admin.level().getBlockState(signPos).canBeReplaced()) {
            admin.sendSystemMessage(Component.literal("\u00A7c[EvoProtection] The sign position is blocked."));
            return false;
        }

        if (!(admin.level() instanceof ServerLevel level)) {
            return false;
        }

        BlockState state = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, face);
        level.setBlock(signPos, state, 3);

        room.signDimension = level.dimension().location().toString();
        room.signX = signPos.getX();
        room.signY = signPos.getY();
        room.signZ = signPos.getZ();
        writeRoomSignText(level, signPos, room);
        return true;
    }

    private void updateRoomSign(ProtectionRoom room) {
        ServerLevel level = getServerLevel(room.signDimension);
        if (level == null) return;

        BlockPos pos = room.getSignPos();
        if (pos == null) return;

        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity)) return;
        writeRoomSignText(level, pos, room);
    }

    private void writeRoomSignText(ServerLevel level, BlockPos pos, ProtectionRoom room) {
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return;

        SignText text = sign.getFrontText();
        if (room.ownerUuid == null) {
            text = text.setMessage(0, Component.literal("Evo Room"));
            text = text.setMessage(1, Component.literal(room.canBuy() ? "Buy " + EvoCurrencyFormatter.formatWithCurrency(room.buyPrice) : ""));
            text = text.setMessage(2, Component.literal(room.canRent() ? "Rent " + EvoCurrencyFormatter.formatWithCurrency(room.rentPrice) : ""));
            text = text.setMessage(3, Component.literal("Right click"));
        } else {
            text = text.setMessage(0, Component.literal("Evo Room"));
            text = text.setMessage(1, Component.literal((room.offerType == RoomOfferType.RENT ? "Rent" : "Buy") + " by"));
            text = text.setMessage(2, Component.literal(trimSignLine(room.ownerName)));
            text = text.setMessage(3, Component.literal(trimSignLine(room.name)));
        }

        sign.setText(text, true);
        sign.setChanged();
        BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, 3);
    }

    private ServerLevel getServerLevel(String dimension) {
        if (dimension == null || dimension.isBlank()) return null;
        net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        try {
            return server.getLevel(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    new net.minecraft.resources.ResourceLocation(dimension)));
        } catch (Exception ignored) {
            return null;
        }
    }

    private ProtectionRoom getRoomBySign(BlockPos pos, String dimension) {
        if (pos == null || dimension == null) return null;
        for (ProtectionRoom room : rooms.values()) {
            if (!dimension.equals(room.signDimension)) continue;
            BlockPos signPos = room.getSignPos();
            if (pos.equals(signPos)) return room;
        }
        return null;
    }

    private boolean canUseRoomSign(ServerPlayer player, ProtectionRoom room) {
        if (player == null || room == null) return false;
        String dimension = player.level().dimension().location().toString();
        if (!dimension.equals(room.signDimension)) return false;
        BlockPos signPos = room.getSignPos();
        if (signPos == null || !(player.level().getBlockEntity(signPos) instanceof SignBlockEntity)) return false;
        return player.distanceToSqr(
                signPos.getX() + 0.5D,
                signPos.getY() + 0.5D,
                signPos.getZ() + 0.5D
        ) <= MAX_SIGN_INTERACTION_DISTANCE_SQR;
    }

    private String roomChunkKey(int chunkX, int chunkZ, String dimension) {
        return dimension + "|" + ChunkPos.asLong(chunkX, chunkZ);
    }

    private void indexRoom(ProtectionRoom room) {
        if (room == null || room.dimension == null) return;
        int minChunkX = room.minX >> 4;
        int maxChunkX = room.maxX >> 4;
        int minChunkZ = room.minZ >> 4;
        int maxChunkZ = room.maxZ >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                roomIdsByChunk.computeIfAbsent(roomChunkKey(chunkX, chunkZ, room.dimension), key -> ConcurrentHashMap.newKeySet())
                        .add(room.roomId);
            }
        }
    }

    private void unindexRoom(ProtectionRoom room) {
        if (room == null || room.dimension == null) return;
        int minChunkX = room.minX >> 4;
        int maxChunkX = room.maxX >> 4;
        int minChunkZ = room.minZ >> 4;
        int maxChunkZ = room.maxZ >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                String key = roomChunkKey(chunkX, chunkZ, room.dimension);
                Set<String> roomIds = roomIdsByChunk.get(key);
                if (roomIds == null) continue;
                roomIds.remove(room.roomId);
                if (roomIds.isEmpty()) roomIdsByChunk.remove(key, roomIds);
            }
        }
    }

    private ProtectionRoom findRoomForPlayer(String playerName, String dimension, BlockPos adminPos) {
        if (playerName == null || playerName.isBlank()) return null;
        String needle = playerName.trim();

        for (ProtectionRoom room : rooms.values()) {
            if (matchesPlayer(room, needle) && room.contains(adminPos, dimension)) {
                return room;
            }
        }

        for (ProtectionRoom room : rooms.values()) {
            if (matchesPlayer(room, needle)) {
                return room;
            }
        }
        return null;
    }

    private ProtectionRoom findRoomByName(String requestedName) {
        if (requestedName == null || requestedName.isBlank()) return null;
        String needle = requestedName.trim();

        for (ProtectionRoom room : rooms.values()) {
            if (room.name != null && room.name.equalsIgnoreCase(needle)) return room;
        }

        ProtectionRoom match = null;
        for (ProtectionRoom room : rooms.values()) {
            if (room.name != null && room.name.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
                if (match != null) return null;
                match = room;
            }
        }
        return match;
    }

    private boolean matchesPlayer(ProtectionRoom room, String playerName) {
        return (room.ownerName != null && room.ownerName.equalsIgnoreCase(playerName))
                || (room.sellerName != null && room.sellerName.equalsIgnoreCase(playerName));
    }

    private void clearRoomOwner(ProtectionRoom room) {
        room.ownerUuid = null;
        room.ownerName = null;
        room.flags.clear();
        room.trustedRoles.clear();
    }

    private void removeRoomSignBlock(ProtectionRoom room) {
        ServerLevel level = getServerLevel(room.signDimension);
        BlockPos pos = room.getSignPos();
        if (level == null || pos == null) return;
        if (level.getBlockEntity(pos) instanceof SignBlockEntity) {
            level.removeBlock(pos, false);
        }
    }

    private String claimedLabel(ProtectionRoom room) {
        return room.offerType == RoomOfferType.RENT ? "rented" : "bought";
    }

    private String trimSignLine(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return clean.length() > 15 ? clean.substring(0, 15) : clean;
    }

    private ProtectionRoom findOverlappingRoom(ProtectionRoom target) {
        for (ProtectionRoom room : rooms.values()) {
            if (!room.dimension.equals(target.dimension)) continue;
            if (target.maxX < room.minX || target.minX > room.maxX) continue;
            if (target.maxY < room.minY || target.minY > room.maxY) continue;
            if (target.maxZ < room.minZ || target.minZ > room.maxZ) continue;
            return room;
        }
        return null;
    }

    private ItemStack createContractPaper(ProtectionRoom room) {
        ItemStack stack = new ItemStack(Items.PAPER);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putBoolean(CONTRACT_TAG, true);
        tag.putString(CONTRACT_ROOM_ID, room.roomId);
        tag.putString(CONTRACT_TOKEN, room.contractToken);

        String typeLabel = room.offerType == RoomOfferType.RENT ? "Rent" : "Sale";
        stack.setHoverName(Component.literal((room.offerType == RoomOfferType.RENT ? "\u00A7b" : "\u00A7a") + "Room " + typeLabel + " Contract"));

        CompoundTag display = stack.getOrCreateTagElement("display");
        ListTag lore = new ListTag();
        lore.add(StringTag.valueOf(loreJson("Room: " + room.name, "gray")));
        lore.add(StringTag.valueOf(loreJson("Type: " + typeLabel, "gray")));
        lore.add(StringTag.valueOf(loreJson("Price: " + EvoCurrencyFormatter.formatWithCurrency(room.price), "gold")));
        lore.add(StringTag.valueOf(loreJson("Right click to claim this room.", "green")));
        display.put("Lore", lore);
        return stack;
    }

    private void loadFromDatabase() {
        rooms.clear();
        roomIdsByChunk.clear();
        synchronized (DatabaseManager.get()) {
            try {
                Connection conn = DatabaseManager.get().getConnection();
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + ROOM_TABLE + " (" +
                            "room_id VARCHAR(64) PRIMARY KEY, " +
                            "dimension VARCHAR(128), " +
                            "parent_claim_id VARCHAR(160), " +
                            "name VARCHAR(64), " +
                            "min_x INT, min_y INT, min_z INT, " +
                            "max_x INT, max_y INT, max_z INT, " +
                            "offer_type VARCHAR(16), " +
                            "price DOUBLE, " +
                            "buy_price DOUBLE DEFAULT -1, " +
                            "rent_price DOUBLE DEFAULT -1, " +
                            "listing_mode VARCHAR(16) DEFAULT 'BOTH', " +
                            "seller_uuid VARCHAR(36), " +
                            "seller_name VARCHAR(32), " +
                            "owner_uuid VARCHAR(36), " +
                            "owner_name VARCHAR(32), " +
                            "contract_token VARCHAR(64), " +
                            "last_rent_charge_at BIGINT DEFAULT 0, " +
                            "sign_dimension VARCHAR(128), " +
                            "sign_x INT DEFAULT 0, " +
                            "sign_y INT DEFAULT 0, " +
                            "sign_z INT DEFAULT 0, " +
                            "flags_json TEXT, " +
                            "trust_json TEXT, " +
                            "created_at BIGINT, " +
                            "updated_at BIGINT)");
                }

                ensureColumn(conn, "buy_price", "DOUBLE DEFAULT -1");
                ensureColumn(conn, "rent_price", "DOUBLE DEFAULT -1");
                ensureColumn(conn, "listing_mode", "VARCHAR(16) DEFAULT 'BOTH'");
                ensureColumn(conn, "last_rent_charge_at", "BIGINT DEFAULT 0");
                ensureColumn(conn, "sign_dimension", "VARCHAR(128)");
                ensureColumn(conn, "sign_x", "INT DEFAULT 0");
                ensureColumn(conn, "sign_y", "INT DEFAULT 0");
                ensureColumn(conn, "sign_z", "INT DEFAULT 0");
                ensureColumn(conn, "flags_json", "TEXT");
                ensureColumn(conn, "trust_json", "TEXT");

                try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + ROOM_TABLE);
                     ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        ProtectionRoom room = readRoom(rs);
                        rooms.put(room.roomId, room);
                        indexRoom(room);
                    }
                }
                System.out.println("[EvoProtection] Loaded " + rooms.size() + " protection rooms.");
            } catch (Exception e) {
                System.err.println("[EvoProtection] Failed to load protection rooms.");
                throw new IllegalStateException("EvoProtection cannot start safely because protection rooms are unavailable.", e);
            }
        }
    }

    private void saveRoom(ProtectionRoom room) {
        ProtectionRoom snapshot = room.copy();
        dbExecutor.execute(() -> {
            synchronized (DatabaseManager.get()) {
                try {
                    Connection conn = DatabaseManager.get().getConnection();
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "INSERT INTO " + ROOM_TABLE + " (" +
                                    "room_id, dimension, parent_claim_id, name, min_x, min_y, min_z, max_x, max_y, max_z, " +
                                    "offer_type, price, buy_price, rent_price, listing_mode, seller_uuid, seller_name, owner_uuid, owner_name, contract_token, " +
                                    "last_rent_charge_at, sign_dimension, sign_x, sign_y, sign_z, flags_json, trust_json, created_at, updated_at" +
                                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                                    "ON DUPLICATE KEY UPDATE " +
                                    "dimension=VALUES(dimension), parent_claim_id=VALUES(parent_claim_id), name=VALUES(name), " +
                                    "min_x=VALUES(min_x), min_y=VALUES(min_y), min_z=VALUES(min_z), " +
                                    "max_x=VALUES(max_x), max_y=VALUES(max_y), max_z=VALUES(max_z), " +
                                    "offer_type=VALUES(offer_type), price=VALUES(price), buy_price=VALUES(buy_price), rent_price=VALUES(rent_price), " +
                                    "listing_mode=VALUES(listing_mode), seller_uuid=VALUES(seller_uuid), " +
                                    "seller_name=VALUES(seller_name), owner_uuid=VALUES(owner_uuid), owner_name=VALUES(owner_name), " +
                                    "contract_token=VALUES(contract_token), last_rent_charge_at=VALUES(last_rent_charge_at), " +
                                    "sign_dimension=VALUES(sign_dimension), sign_x=VALUES(sign_x), " +
                                    "sign_y=VALUES(sign_y), sign_z=VALUES(sign_z), flags_json=VALUES(flags_json), trust_json=VALUES(trust_json), " +
                                    "created_at=VALUES(created_at), updated_at=VALUES(updated_at)")) {
                        bindRoom(stmt, snapshot);
                        stmt.executeUpdate();
                    }
                } catch (Exception e) {
                    System.err.println("[EvoProtection] Failed to save protection room " + snapshot.roomId + ".");
                    e.printStackTrace();
                }
            }
        });
    }

    private void deleteRoom(String roomId) {
        dbExecutor.execute(() -> {
            synchronized (DatabaseManager.get()) {
                try {
                    Connection conn = DatabaseManager.get().getConnection();
                    try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM " + ROOM_TABLE + " WHERE room_id = ?")) {
                        stmt.setString(1, roomId);
                        stmt.executeUpdate();
                    }
                } catch (Exception e) {
                    System.err.println("[EvoProtection] Failed to delete protection room " + roomId + ".");
                    e.printStackTrace();
                }
            }
        });
    }

    private void bindRoom(PreparedStatement stmt, ProtectionRoom room) throws Exception {
        stmt.setString(1, room.roomId);
        stmt.setString(2, room.dimension);
        stmt.setString(3, room.parentClaimId);
        stmt.setString(4, room.name);
        stmt.setInt(5, room.minX);
        stmt.setInt(6, room.minY);
        stmt.setInt(7, room.minZ);
        stmt.setInt(8, room.maxX);
        stmt.setInt(9, room.maxY);
        stmt.setInt(10, room.maxZ);
        stmt.setString(11, room.offerType.name());
        stmt.setDouble(12, room.price);
        stmt.setDouble(13, room.buyPrice);
        stmt.setDouble(14, room.rentPrice);
        stmt.setString(15, room.listingMode.name());
        stmt.setString(16, room.sellerUuid.toString());
        stmt.setString(17, room.sellerName);
        stmt.setString(18, room.ownerUuid == null ? null : room.ownerUuid.toString());
        stmt.setString(19, room.ownerName);
        stmt.setString(20, room.contractToken);
        stmt.setLong(21, room.lastRentChargeAt);
        stmt.setString(22, room.signDimension);
        stmt.setInt(23, room.signX);
        stmt.setInt(24, room.signY);
        stmt.setInt(25, room.signZ);
        stmt.setString(26, GSON.toJson(room.flags));
        stmt.setString(27, GSON.toJson(room.trustedRoles));
        stmt.setLong(28, room.createdAt);
        stmt.setLong(29, room.updatedAt);
    }

    private ProtectionRoom readRoom(ResultSet rs) throws Exception {
        ProtectionRoom room = new ProtectionRoom();
        room.roomId = rs.getString("room_id");
        room.dimension = rs.getString("dimension");
        room.parentClaimId = rs.getString("parent_claim_id");
        room.name = rs.getString("name");
        room.minX = rs.getInt("min_x");
        room.minY = rs.getInt("min_y");
        room.minZ = rs.getInt("min_z");
        room.maxX = rs.getInt("max_x");
        room.maxY = rs.getInt("max_y");
        room.maxZ = rs.getInt("max_z");
        room.offerType = parseOfferType(rs.getString("offer_type"));
        room.price = rs.getDouble("price");
        room.buyPrice = readDouble(rs, "buy_price", room.offerType == RoomOfferType.SELL ? room.price : -1.0D);
        room.rentPrice = readDouble(rs, "rent_price", room.offerType == RoomOfferType.RENT ? room.price : -1.0D);
        room.listingMode = parseListingMode(readString(rs, "listing_mode", "BOTH"));
        room.sellerUuid = UUID.fromString(rs.getString("seller_uuid"));
        room.sellerName = rs.getString("seller_name");
        String ownerUuid = rs.getString("owner_uuid");
        room.ownerUuid = ownerUuid == null || ownerUuid.isEmpty() ? null : UUID.fromString(ownerUuid);
        room.ownerName = rs.getString("owner_name");
        room.contractToken = rs.getString("contract_token");
        room.lastRentChargeAt = readLong(rs, "last_rent_charge_at", 0L);
        room.signDimension = readString(rs, "sign_dimension", "");
        room.signX = readInt(rs, "sign_x", 0);
        room.signY = readInt(rs, "sign_y", 0);
        room.signZ = readInt(rs, "sign_z", 0);
        room.flags.putAll(parseRoomFlags(readString(rs, "flags_json", "")));
        room.trustedRoles.putAll(parseRoomTrustRoles(readString(rs, "trust_json", "")));
        room.createdAt = rs.getLong("created_at");
        room.updatedAt = rs.getLong("updated_at");
        return room;
    }

    private Map<String, Boolean> parseRoomFlags(String json) {
        Map<String, Boolean> flags = new HashMap<>();
        if (json == null || json.isBlank()) return flags;
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                String normalized = ClaimManager.normalizeFlagName(entry.getKey());
                if (ClaimManager.isSupportedFlagName(normalized)
                        && entry.getValue().isJsonPrimitive()
                        && entry.getValue().getAsJsonPrimitive().isBoolean()) {
                    flags.put(normalized, entry.getValue().getAsBoolean());
                }
            }
        } catch (Exception ignored) {
        }
        return flags;
    }

    private Map<UUID, String> parseRoomTrustRoles(String json) {
        Map<UUID, String> roles = new HashMap<>();
        if (json == null || json.isBlank()) return roles;
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) continue;
                UUID playerUuid = UUID.fromString(entry.getKey());
                String role = ClaimManager.normalizeTrustRole(entry.getValue().getAsString());
                if (!ClaimManager.ROLE_VISITOR.equals(role)) {
                    roles.put(playerUuid, role);
                }
            }
        } catch (Exception ignored) {
        }
        return roles;
    }

    private void ensureColumn(Connection conn, String column, String definition) {
        try {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet columns = meta.getColumns(null, null, ROOM_TABLE, column)) {
                if (columns.next()) return;
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("ALTER TABLE " + ROOM_TABLE + " ADD COLUMN " + column + " " + definition);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean hasColumn(ResultSet rs, String column) {
        try {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                if (column.equalsIgnoreCase(meta.getColumnName(i))) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private double readDouble(ResultSet rs, String column, double fallback) {
        try {
            return hasColumn(rs, column) ? rs.getDouble(column) : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int readInt(ResultSet rs, String column, int fallback) {
        try {
            return hasColumn(rs, column) ? rs.getInt(column) : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private long readLong(ResultSet rs, String column, long fallback) {
        try {
            return hasColumn(rs, column) ? rs.getLong(column) : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String readString(ResultSet rs, String column, String fallback) {
        try {
            if (!hasColumn(rs, column)) return fallback;
            String value = rs.getString(column);
            return value == null ? fallback : value;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private RoomOfferType parseOfferType(String value) {
        if (value == null) return RoomOfferType.SELL;
        try {
            return RoomOfferType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return RoomOfferType.SELL;
        }
    }

    private RoomListingMode parseListingMode(String value) {
        if (value == null) return RoomListingMode.BOTH;
        try {
            return RoomListingMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return RoomListingMode.BOTH;
        }
    }

    private String sanitizeRoomName(String value) {
        if (value == null) return "";
        String clean = value.trim().replaceAll("[\\r\\n\\t]", " ");
        return clean.length() > 48 ? clean.substring(0, 48) : clean;
    }

    private String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private String getSelectionSize(RoomSelection selection) {
        int sizeX = Math.abs(selection.pos1.getX() - selection.pos2.getX()) + 1;
        int sizeY = Math.abs(selection.pos1.getY() - selection.pos2.getY()) + 1;
        int sizeZ = Math.abs(selection.pos1.getZ() - selection.pos2.getZ()) + 1;
        return sizeX + " x " + sizeY + " x " + sizeZ;
    }

    private String loreJson(String text, String color) {
        return "{\"text\":\"" + escapeJson(text) + "\",\"color\":\"" + color + "\",\"italic\":false}";
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public enum RoomOfferType {
        SELL,
        RENT
    }

    public enum RoomListingMode {
        BOTH,
        SELL_ONLY,
        RENT_ONLY
    }

    public enum RoomOfferAction {
        BUY,
        RENT,
        CANCEL_RENT,
        LIST_SELL,
        LIST_RENT
    }

    private static class RoomSelection {
        final String dimension;
        BlockPos pos1;
        BlockPos pos2;

        RoomSelection(String dimension) {
            this.dimension = dimension;
        }
    }

    private static class PendingRoomSign {
        final double buyPrice;
        final double rentPrice;
        final String name;

        PendingRoomSign(double buyPrice, double rentPrice, String name) {
            this.buyPrice = buyPrice;
            this.rentPrice = rentPrice;
            this.name = name;
        }
    }

    public static class ProtectionRoom {
        public String roomId;
        public String dimension;
        public String parentClaimId;
        public String name;
        public int minX;
        public int minY;
        public int minZ;
        public int maxX;
        public int maxY;
        public int maxZ;
        public RoomOfferType offerType;
        public double price;
        public double buyPrice = -1.0D;
        public double rentPrice = -1.0D;
        public RoomListingMode listingMode = RoomListingMode.BOTH;
        public UUID sellerUuid;
        public String sellerName;
        public UUID ownerUuid;
        public String ownerName;
        public String contractToken;
        public long lastRentChargeAt;
        public String signDimension = "";
        public int signX;
        public int signY;
        public int signZ;
        public long createdAt;
        public long updatedAt;
        public final Map<String, Boolean> flags = new ConcurrentHashMap<>();
        public final Map<UUID, String> trustedRoles = new ConcurrentHashMap<>();

        public boolean contains(BlockPos pos, String targetDimension) {
            if (!dimension.equals(targetDimension)) return false;
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        public BlockPos getSignPos() {
            if (signDimension == null || signDimension.isBlank()) return null;
            return new BlockPos(signX, signY, signZ);
        }

        public boolean canBuy() {
            return buyPrice >= 0.0D && (listingMode == RoomListingMode.BOTH || listingMode == RoomListingMode.SELL_ONLY);
        }

        public boolean canRent() {
            return rentPrice >= 0.0D && (listingMode == RoomListingMode.BOTH || listingMode == RoomListingMode.RENT_ONLY);
        }

        private ProtectionRoom copy() {
            ProtectionRoom copy = new ProtectionRoom();
            copy.roomId = roomId;
            copy.dimension = dimension;
            copy.parentClaimId = parentClaimId;
            copy.name = name;
            copy.minX = minX;
            copy.minY = minY;
            copy.minZ = minZ;
            copy.maxX = maxX;
            copy.maxY = maxY;
            copy.maxZ = maxZ;
            copy.offerType = offerType;
            copy.price = price;
            copy.buyPrice = buyPrice;
            copy.rentPrice = rentPrice;
            copy.listingMode = listingMode;
            copy.sellerUuid = sellerUuid;
            copy.sellerName = sellerName;
            copy.ownerUuid = ownerUuid;
            copy.ownerName = ownerName;
            copy.contractToken = contractToken;
            copy.lastRentChargeAt = lastRentChargeAt;
            copy.signDimension = signDimension;
            copy.signX = signX;
            copy.signY = signY;
            copy.signZ = signZ;
            copy.createdAt = createdAt;
            copy.updatedAt = updatedAt;
            copy.flags.putAll(flags);
            copy.trustedRoles.putAll(trustedRoles);
            return copy;
        }
    }
}
