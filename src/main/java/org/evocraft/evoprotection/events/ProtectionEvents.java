package org.evocraft.evoprotection.events;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.MoveToBlockGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityMobGriefingEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.PistonEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;
import org.evocraft.evoprotection.EvoProtection;
import org.evocraft.evoprotection.compat.CarryOnCompat;
import org.evocraft.evoprotection.manager.ClaimEnvironmentManager;
import org.evocraft.evoprotection.manager.ClaimManager;
import org.evocraft.evoprotection.manager.ProtectionRoomManager;
import org.evocraft.evoprotection.network.PacketHandler;
import org.evocraft.evoprotection.manager.ProtectionConfig;
import org.evocraft.evoprotection.manager.ProtectionPermissions;
import org.evocraft.evocore.data.EconomyManager;
import org.evocraft.evocore.util.EvoCurrencyFormatter;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@SuppressWarnings({"resource", "BooleanMethodIsAlwaysInverted"})
@Mod.EventBusSubscriber(modid = EvoProtection.MODID)
public class ProtectionEvents {

    private static final Map<UUID, String> lastChunkOwnerMap = new HashMap<>();
    private static final String SPAWN_RULE_CHECKED_TAG = "EvoProtectionSpawnRuleChecked";

    private enum ClaimAction {
        BLOCK_BREAK,
        BLOCK_PLACE,
        BLOCK_INTERACT,
        WORLD_MODIFY,
        FARMLAND_TRAMPLE
    }

    // ==========================================
    // ⏳ PAYDAY & ANTI-AFK SYSTEM (PLOT/CREATIVE ONLY)
    // ==========================================
    private static class PlayerActivity {
        double lastX, lastY, lastZ;
        float lastYaw, lastPitch;
        int afkTicks = 0;
        int activeTicks = 0;
    }

    private static final Map<UUID, PlayerActivity> activityMap = new HashMap<>();

    private static final int REWARD_INTERVAL_TICKS = 20 * 60 * 10; // 10 minutes
    private static final double REWARD_AMOUNT = 5000.0;
    private static final int AFK_THRESHOLD_TICKS = 20 * 60 * 1; // 1 minute AFK = Pause

    // ==========================================
    // UTILITIES & SYSTEM MESSAGES
    // ==========================================

    private static boolean isForbiddenItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation registryName = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return registryName != null && registryName.getNamespace().equals("mowziesmobs");
    }

    private static boolean canUseMowzieItemAt(ServerPlayer player, BlockPos targetPos) {
        if (player.hasPermissions(2)) return true;
        String dim = player.level().dimension().location().toString();
        ProtectionRoomManager.ProtectionRoom room = ProtectionRoomManager.get().getRoomAt(targetPos, dim);
        if (room != null) return ProtectionRoomManager.get().canPlayerAccessRoom(player, room);

        ChunkPos targetChunk = new ChunkPos(targetPos);
        UUID owner = ClaimManager.get().getChunkOwner(targetChunk, dim);

        if (owner == null) return true; // Can use in wilderness
        if (ProtectionPermissions.canBypassClaim(player, owner)) return true;
        if (owner.equals(player.getUUID())) return true; // Can use in own claim

        String claimId = ClaimManager.get().getClaimId(targetChunk, dim);
        return ClaimManager.get().isTrusted(owner, player.getUUID(), claimId);
    }

    private static boolean canUseMowzieItem(ServerPlayer player) {
        return canUseMowzieItemAt(player, player.blockPosition());
    }

    private static void sendMsg(ServerPlayer player) {
        player.displayClientMessage(Component.literal("§c[!] You do not have permission to interact here!"), true);
    }

    private static void sendMsg(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal(message), true);
    }

    private static boolean isAnimalMob(net.minecraft.world.entity.Mob entity) {
        MobCategory category = entity.getType().getCategory();
        return entity instanceof net.minecraft.world.entity.animal.Animal
                || entity instanceof net.minecraft.world.entity.animal.WaterAnimal
                || category == MobCategory.CREATURE
                || category == MobCategory.AMBIENT
                || category == MobCategory.WATER_CREATURE
                || category == MobCategory.WATER_AMBIENT
                || category == MobCategory.AXOLOTLS;
    }

    private static boolean isManualSpawn(MobSpawnType spawnType) {
        return spawnType == MobSpawnType.BREEDING
                || spawnType == MobSpawnType.SPAWN_EGG
                || spawnType == MobSpawnType.BUCKET
                || spawnType == MobSpawnType.COMMAND;
    }

    private static boolean isMobSpawnAllowed(net.minecraft.world.entity.Mob entity,
                                             MobSpawnType spawnType,
                                             boolean hasSpawner) {
        if (spawnType == MobSpawnType.EVENT && CarryOnCompat.isCarriedEntityPlacement(entity)) {
            return true;
        }
        if (isManualSpawn(spawnType)) return true;

        ChunkPos chunkPos = new ChunkPos(entity.blockPosition());
        String dimension = entity.level().dimension().location().toString();
        boolean fromSpawner = hasSpawner || spawnType == MobSpawnType.SPAWNER;
        boolean isMonster = entity instanceof Monster || entity.getType().getCategory() == MobCategory.MONSTER;
        ProtectionRoomManager.ProtectionRoom room = ProtectionRoomManager.get().getRoomAt(entity.blockPosition(), dimension);
        if (room != null && room.ownerUuid != null) {
            if (isMonster) {
                return ProtectionRoomManager.get().getRoomFlag(room,
                        fromSpawner ? "spawner_monsters" : "natural_monsters");
            }
            if (isAnimalMob(entity)) {
                return ProtectionRoomManager.get().getRoomFlag(room,
                        fromSpawner ? "spawner_animals" : "natural_animals");
            }
            return true;
        }

        UUID owner = ClaimManager.get().getChunkOwner(chunkPos, dimension);
        if (owner == null) return true;

        String claimId = ClaimManager.get().getClaimId(chunkPos, dimension);

        if (isMonster) {
            return ClaimManager.get().getFlag(owner, claimId,
                    fromSpawner ? "spawner_monsters" : "natural_monsters");
        }
        if (isAnimalMob(entity)) {
            return ClaimManager.get().getFlag(owner, claimId,
                    fromSpawner ? "spawner_animals" : "natural_animals");
        }
        return true;
    }

    // ==========================================
    // 🛡️ ANTI-GRIEFING SYSTEM (Pistons, Liquids, Fire)
    // ==========================================

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPistonMove(PistonEvent.Pre event) {
        if (event.getLevel() instanceof Level level && !level.isClientSide()) {
            String dim = level.dimension().location().toString();
            BlockPos pistonPos = event.getPos();
            ChunkPos pistonChunk = new ChunkPos(pistonPos);
            UUID pistonOwner = ClaimManager.get().getChunkOwner(pistonChunk, dim);

            PistonStructureResolver resolver = event.getStructureHelper();
            if (resolver == null) {
                boolean isExtend = (event.getPistonMoveType() == PistonEvent.PistonMoveType.EXTEND);
                resolver = new PistonStructureResolver(level, pistonPos, event.getDirection(), isExtend);
            }

            if (resolver.resolve()) {
                net.minecraft.core.Direction moveDir = event.getPistonMoveType() == PistonEvent.PistonMoveType.EXTEND ? event.getDirection() : event.getDirection().getOpposite();

                for (BlockPos pos : resolver.getToPush()) {
                    ChunkPos fromChunk = new ChunkPos(pos);
                    ChunkPos toChunk = new ChunkPos(pos.relative(moveDir));

                    UUID fromOwner = ClaimManager.get().getChunkOwner(fromChunk, dim);
                    UUID toOwner = ClaimManager.get().getChunkOwner(toChunk, dim);

                    if (toOwner != null && !Objects.equals(pistonOwner, toOwner)) {
                        event.setCanceled(true); return;
                    }
                    if (fromOwner != null && !Objects.equals(pistonOwner, fromOwner)) {
                        event.setCanceled(true); return;
                    }
                }

                for (BlockPos pos : resolver.getToDestroy()) {
                    ChunkPos chunk = new ChunkPos(pos);
                    UUID owner = ClaimManager.get().getChunkOwner(chunk, dim);
                    if (owner != null && !Objects.equals(pistonOwner, owner)) {
                        event.setCanceled(true); return;
                    }
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onFluidPlaceBlock(BlockEvent.FluidPlaceBlockEvent event) {
        if (event.getLevel() instanceof Level level && !level.isClientSide()) {
            ChunkPos targetChunk = new ChunkPos(event.getPos());
            String dim = level.dimension().location().toString();
            UUID targetOwner = ClaimManager.get().getChunkOwner(targetChunk, dim);

            BlockPos liquidSourcePos = event.getLiquidPos();
            ChunkPos sourceChunk = new ChunkPos(liquidSourcePos);
            UUID sourceOwner = ClaimManager.get().getChunkOwner(sourceChunk, dim);

            if (targetOwner != null && !Objects.equals(targetOwner, sourceOwner)) {
                event.setCanceled(true);
                event.setNewState(event.getOriginalState());
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof Level level) || level.isClientSide()) return;

        BlockPos pos = event.getPos();
        BlockState state = event.getState();
        ChunkPos chunk = new ChunkPos(pos);
        String dim = level.dimension().location().toString();
        UUID owner = ClaimManager.get().getChunkOwner(chunk, dim);

        if (owner != null) {
            // Anti-Liquids
            if (!state.getFluidState().isEmpty()) {
                boolean cameFromOutside = false;
                for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                    BlockPos neighborPos = pos.relative(dir);
                    ChunkPos neighborChunk = new ChunkPos(neighborPos);
                    UUID neighborOwner = ClaimManager.get().getChunkOwner(neighborChunk, dim);

                    if (!Objects.equals(owner, neighborOwner)) {
                        BlockState nState = level.getBlockState(neighborPos);
                        if (!nState.getFluidState().isEmpty() && nState.getFluidState().getType().isSame(state.getFluidState().getType())) {
                            cameFromOutside = true; break;
                        }
                        if (nState.getBlock() instanceof net.minecraft.world.level.block.DispenserBlock) {
                            if (nState.getValue(net.minecraft.world.level.block.DispenserBlock.FACING) == dir.getOpposite()) {
                                cameFromOutside = true; break;
                            }
                        }
                    }
                }
                if (cameFromOutside) {
                    level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                    return;
                }
            }

            // Anti-Fire
            if (state.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock) {
                boolean cameFromOutside = false;
                for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                    BlockPos neighborPos = pos.relative(dir);
                    ChunkPos neighborChunk = new ChunkPos(neighborPos);
                    UUID neighborOwner = ClaimManager.get().getChunkOwner(neighborChunk, dim);

                    if (!Objects.equals(owner, neighborOwner)) {
                        BlockState nState = level.getBlockState(neighborPos);
                        if (nState.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock || nState.isFlammable(level, neighborPos, dir.getOpposite())) {
                            cameFromOutside = true; break;
                        }
                    }
                }
                if (cameFromOutside) {
                    level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                    return;
                }
            }
        }
    }

    // ==========================================
    // ⚔️ COMBAT EVENTS (BIDIRECTIONAL PVP & PVE)
    // ==========================================

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttackEntity(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer attacker) {
            net.minecraft.world.entity.Entity target = event.getTarget();
            String dim = attacker.level().dimension().location().toString();

            if (target instanceof Player) {
                if (!isPvpAllowedAt(attacker, attacker.blockPosition())
                        || !isPvpAllowedAt(attacker, target.blockPosition())) {
                    event.setCanceled(true);
                    sendMsg(attacker);
                }
                return;
            }

            ProtectionRoomManager.ProtectionRoom room = ProtectionRoomManager.get().getRoomAt(target.blockPosition(), dim);
            if (room != null) {
                if (target instanceof net.minecraft.world.entity.Mob mob && isAnimalMob(mob)) {
                    if (!ProtectionRoomManager.get().canPlayerUseRoomFlag(attacker, room, "hurt_animals")) {
                        event.setCanceled(true);
                        sendMsg(attacker);
                    }
                } else if (!(target instanceof Monster)
                        && !ProtectionRoomManager.get().canPlayerUseRoomFlag(attacker, room, "interact_entities")) {
                    event.setCanceled(true);
                    sendMsg(attacker);
                }
                return;
            }

            if (target instanceof Player) {
                ChunkPos attackerChunk = attacker.chunkPosition();
                ChunkPos targetChunk = new ChunkPos(target.blockPosition());

                UUID attackerZoneOwner = ClaimManager.get().getChunkOwner(attackerChunk, dim);
                UUID targetZoneOwner = ClaimManager.get().getChunkOwner(targetChunk, dim);

                if (attackerZoneOwner != null && !ProtectionPermissions.canBypassClaim(attacker, attackerZoneOwner)) {
                    String claimId = ClaimManager.get().getClaimId(attackerChunk, dim);
                    if (!ClaimManager.get().getFlag(attackerZoneOwner, claimId, "pvp")) {
                        event.setCanceled(true);
                        attacker.displayClientMessage(Component.literal("§c[!] PVP is disabled in the area you are attacking from!"), true);
                        return;
                    }
                }

                if (targetZoneOwner != null && !ProtectionPermissions.canBypassClaim(attacker, targetZoneOwner)) {
                    String claimId = ClaimManager.get().getClaimId(targetChunk, dim);
                    if (!ClaimManager.get().getFlag(targetZoneOwner, claimId, "pvp")) {
                        event.setCanceled(true);
                        attacker.displayClientMessage(Component.literal("§c[!] PVP is disabled in that protection!"), true);
                        return;
                    }
                }
                return;
            }

            ChunkPos chunkPos = new ChunkPos(target.blockPosition());
            UUID owner = ClaimManager.get().getChunkOwner(chunkPos, dim);

            if (owner != null && !owner.equals(attacker.getUUID())
                    && !ProtectionPermissions.canBypassClaim(attacker, owner)) {
                String claimId = ClaimManager.get().getClaimId(chunkPos, dim);
                if (!ClaimManager.get().isTrusted(owner, attacker.getUUID(), claimId)) {

                    if (target instanceof net.minecraft.world.entity.Mob mob && isAnimalMob(mob)) {
                        if (!ClaimManager.get().getFlag(owner, claimId, "hurt_animals")) {
                            event.setCanceled(true);
                            sendMsg(attacker);
                        }
                    } else if (!(target instanceof Monster)) {
                        if (!ClaimManager.get().getFlag(owner, claimId, "interact_entities")) {
                            event.setCanceled(true);
                            sendMsg(attacker);
                        }
                    }
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingAttack(LivingAttackEvent event) {
        if (event.getEntity() instanceof ServerPlayer targetPlayer) {
            if (event.getSource().getEntity() instanceof ServerPlayer attackerPlayer) {
                if (!isPvpAllowedAt(attackerPlayer, attackerPlayer.blockPosition())
                        || !isPvpAllowedAt(attackerPlayer, targetPlayer.blockPosition())) {
                    event.setCanceled(true);
                }
            }
        }
    }

    // ==========================================
    // 🌍 GENERAL INTERACTION EVENTS & ANTI-AFK
    // ==========================================

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ProtectionRoomManager.get().tickRentPayments(event.getServer());
    }

    @SubscribeEvent
    public static void onPlayerMove(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide()) return;

        if (event.player instanceof ServerPlayer player) {
            ClaimEnvironmentManager.get().handlePlayerLocation(player);

            // --- NEW PAYDAY LOGIC ONLY IF IT IS PLOT MODE ---
            if (ProtectionConfig.get().isPlotMode) {
                PlayerActivity activity = activityMap.computeIfAbsent(player.getUUID(), k -> new PlayerActivity());

                boolean moved = player.getX() != activity.lastX || player.getY() != activity.lastY || player.getZ() != activity.lastZ ||
                        player.getYRot() != activity.lastYaw || player.getXRot() != activity.lastPitch;

                if (moved) {
                    if (activity.afkTicks >= AFK_THRESHOLD_TICKS) {
                        player.sendSystemMessage(Component.literal("§a[!] You returned! The PayDay time is ticking again."));
                    }
                    activity.afkTicks = 0;
                    activity.lastX = player.getX();
                    activity.lastY = player.getY();
                    activity.lastZ = player.getZ();
                    activity.lastYaw = player.getYRot();
                    activity.lastPitch = player.getXRot();
                } else {
                    activity.afkTicks++;
                }

                boolean isIdle = activity.afkTicks >= AFK_THRESHOLD_TICKS;

                if (!isIdle) {
                    activity.activeTicks++;
                    if (activity.activeTicks >= REWARD_INTERVAL_TICKS) {
                        activity.activeTicks = 0; // RESET ONLY AFTER RECEIVING THE MONEY
                        try {
                            EconomyManager.get().addBalance(player.getUUID(), REWARD_AMOUNT);
                            player.sendSystemMessage(Component.literal("§8[§aPayDay§8] §fYou received §e" + EvoCurrencyFormatter.formatWithCurrency(REWARD_AMOUNT) + " §ffor your activity!"));
                        } catch (Exception e) {}
                    }
                } else if (activity.afkTicks == AFK_THRESHOLD_TICKS) {
                    player.sendSystemMessage(Component.literal("§c[!] You entered AFK state! The PayDay time has been paused."));
                }

                // Sends visual update to the client every second
                if (player.tickCount % 20 == 0) {
                    int secondsLeft = (REWARD_INTERVAL_TICKS - activity.activeTicks) / 20;
                    PacketHandler.sendToPlayer(new PacketHandler.S2C_SyncPayDay(secondsLeft, isIdle), player);
                }
            }
            // ---------------------------------

            // Existing Chunk Enter logic (notifications)
            if (player.tickCount % 10 == 0) {
                ChunkPos current = player.chunkPosition();
                String dim = player.level().dimension().location().toString();

                String name = ClaimManager.get().getClaimEnterName(current, dim);
                String lastOwnerName = lastChunkOwnerMap.get(player.getUUID());

                if (!name.equals(lastOwnerName)) {
                    lastChunkOwnerMap.put(player.getUUID(), name);
                    PacketHandler.sendToPlayer(new PacketHandler.S2C_ChunkEnter(name), player);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogOut(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        lastChunkOwnerMap.remove(event.getEntity().getUUID());
        activityMap.remove(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer player) {
            ClaimEnvironmentManager.get().clearPlayer(player);
        } else {
            ClaimEnvironmentManager.get().clearPlayer(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public static void onPlayerLogIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ClaimEnvironmentManager.get().installInterceptor(player);
            ClaimEnvironmentManager.get().forceRefreshPlayer(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ClaimEnvironmentManager.get().installInterceptor(player);
            ClaimEnvironmentManager.get().forceRefreshPlayer(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ClaimEnvironmentManager.get().installInterceptor(player);
            ClaimEnvironmentManager.get().forceRefreshPlayer(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;

        if (event.getEntity() instanceof Zombie zombie) {
            ensureProtectedTurtleEggGoal(zombie);
        }

        if (event.getEntity() instanceof net.minecraft.world.entity.LightningBolt) {
            BlockPos lightningPos = event.getEntity().blockPosition();
            String dimension = event.getLevel().dimension().location().toString();
            if (ClaimEnvironmentManager.get().isAlwaysShinyAt(lightningPos, dimension)) {
                event.setCanceled(true);
                return;
            }
        }

        if (event.getEntity() instanceof net.minecraft.world.entity.LightningBolt ||
                event.getEntity() instanceof net.minecraft.world.entity.item.PrimedTnt) {
            BlockPos blockPos = event.getEntity().blockPosition();
            ChunkPos pos = new ChunkPos(blockPos);
            String dim = event.getLevel().dimension().location().toString();
            ProtectionRoomManager.ProtectionRoom room = ProtectionRoomManager.get().getRoomAt(blockPos, dim);
            if (room != null && room.ownerUuid != null) {
                if (!ProtectionRoomManager.get().getRoomFlag(room, "explosions")) {
                    event.setCanceled(true);
                }
                return;
            }
            UUID owner = ClaimManager.get().getChunkOwner(pos, dim);

            if (owner != null) {
                String claimId = ClaimManager.get().getClaimId(pos, dim);
                if (!ClaimManager.get().getFlag(owner, claimId, "explosions")) {
                    event.setCanceled(true);
                }
            }
            return;
        }

        if (event.loadedFromDisk() || !(event.getEntity() instanceof net.minecraft.world.entity.Mob mob)) {
            return;
        }

        if (mob.getPersistentData().getBoolean(SPAWN_RULE_CHECKED_TAG)) {
            mob.getPersistentData().remove(SPAWN_RULE_CHECKED_TAG);
            return;
        }

        // Carry On restores the exact saved entity directly, without a Forge spawn event.
        if (CarryOnCompat.isCarriedEntityPlacement(mob)) {
            return;
        }

        // Some mods insert entities directly and never fire Forge's normal spawn flow.
        if (!isMobSpawnAllowed(mob, MobSpawnType.NATURAL, false)) {
            event.setCanceled(true);
        }
    }

    // ==========================================
    // 🧬 SPAWN CONTROL (ANIMALS & MONSTERS)
    // ==========================================
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMobPositionCheck(net.minecraftforge.event.entity.living.MobSpawnEvent.PositionCheck event) {
        if (!isMobSpawnAllowed(event.getEntity(), event.getSpawnType(), event.getSpawner() != null)) {
            event.setResult(Event.Result.DENY);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMobSpawn(net.minecraftforge.event.entity.living.MobSpawnEvent.FinalizeSpawn event) {
        net.minecraft.world.entity.Mob entity = event.getEntity();
        if (!isMobSpawnAllowed(entity, event.getSpawnType(), event.getSpawner() != null)) {
            // Canceling this event only skips initialization; this Forge flag blocks world insertion.
            event.setSpawnCancelled(true);
            return;
        }
        entity.getPersistentData().putBoolean(SPAWN_RULE_CHECKED_TAG, true);
    }


    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onItemPickup(net.minecraftforge.event.entity.player.EntityItemPickupEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ChunkPos chunkPos = new ChunkPos(event.getItem().blockPosition());
            String dim = player.level().dimension().location().toString();
            UUID owner = ClaimManager.get().getChunkOwner(chunkPos, dim);

            ProtectionRoomManager.ProtectionRoom room = ProtectionRoomManager.get().getRoomAt(event.getItem().blockPosition(), dim);
            if (room != null) {
                if (!ProtectionRoomManager.get().canPlayerUseRoomFlag(player, room, "item_pickup")) {
                    event.setCanceled(true);
                }
                return;
            }

            if (owner != null && !owner.equals(player.getUUID())
                    && !ProtectionPermissions.canBypassClaim(player, owner)) {
                String claimId = ClaimManager.get().getClaimId(chunkPos, dim);
                if (!ClaimManager.get().isTrusted(owner, player.getUUID(), claimId)) {
                    if (!ClaimManager.get().getFlag(owner, claimId, "item_pickup")) {
                        event.setCanceled(true);
                    }
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMobGriefing(EntityMobGriefingEvent event) {
        if (event.getEntity() != null) {

            // EXCEPTION FOR VILLAGERS (TO ALLOW FARMING AND BREEDING)
            if (event.getEntity() instanceof net.minecraft.world.entity.npc.Villager) {
                return;
            }

            ChunkPos pos = new ChunkPos(event.getEntity().blockPosition());
            String dim = event.getEntity().level().dimension().location().toString();
            UUID owner = ClaimManager.get().getChunkOwner(pos, dim);

            if (owner != null) {
                event.setResult(Event.Result.DENY);
            }
        }
    }

    private static void ensureProtectedTurtleEggGoal(Zombie zombie) {
        boolean alreadyRegistered = zombie.goalSelector.getAvailableGoals().stream()
                .anyMatch(wrappedGoal -> wrappedGoal.getGoal() instanceof ProtectedTurtleEggAttractionGoal);
        if (!alreadyRegistered) {
            zombie.goalSelector.addGoal(4, new ProtectedTurtleEggAttractionGoal(zombie));
        }
    }

    private static boolean isProtectedPosition(Level level, BlockPos pos) {
        String dimension = level.dimension().location().toString();
        if (ProtectionRoomManager.get().getRoomAt(pos, dimension) != null) {
            return true;
        }
        return ClaimManager.get().getChunkOwner(new ChunkPos(pos), dimension) != null;
    }

    private static final class ProtectedTurtleEggAttractionGoal extends MoveToBlockGoal {
        private final Zombie zombie;

        private ProtectedTurtleEggAttractionGoal(Zombie zombie) {
            super(zombie, 1.0D, 24, 3);
            this.zombie = zombie;
        }

        @Override
        protected boolean isValidTarget(LevelReader level, BlockPos pos) {
            return level.getBlockState(pos).is(Blocks.TURTLE_EGG)
                    && isProtectedPosition(zombie.level(), pos);
        }

        @Override
        public double acceptedDistance() {
            return 1.14D;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;

        String dim = level.dimension().location().toString();

        event.getAffectedBlocks().removeIf(pos -> !isExplosionBlockDamageAllowed(level, pos));

        event.getAffectedEntities().removeIf(entity -> {
            if (entity instanceof Player || entity instanceof Monster) return false;

            ProtectionRoomManager.ProtectionRoom room = ProtectionRoomManager.get().getRoomAt(entity.blockPosition(), dim);
            if (room != null && room.ownerUuid != null) {
                return !ProtectionRoomManager.get().getRoomFlag(room, "explosions");
            }
            ChunkPos chunkPos = new ChunkPos(entity.blockPosition());
            UUID owner = ClaimManager.get().getChunkOwner(chunkPos, dim);
            if (owner != null) {
                String claimId = ClaimManager.get().getClaimId(chunkPos, dim);
                return !ClaimManager.get().getFlag(owner, claimId, "explosions");
            }
            return false;
        });
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onSupplementariesCannonImpact(ProjectileImpactEvent event) {
        Level level = event.getProjectile().level();
        if (level.isClientSide()) return;

        ResourceLocation projectileId = ForgeRegistries.ENTITY_TYPES.getKey(event.getProjectile().getType());
        if (projectileId == null
                || !"supplementaries".equals(projectileId.getNamespace())
                || !"cannonball".equals(projectileId.getPath())) {
            return;
        }

        if (event.getRayTraceResult() instanceof BlockHitResult hit
                && !isExplosionBlockDamageAllowed(level, hit.getBlockPos())) {
            event.setImpactResult(ProjectileImpactEvent.ImpactResult.STOP_AT_CURRENT_NO_DAMAGE);
            event.getProjectile().discard();
        }
    }

    public static boolean isExplosionBlockDamageAllowed(Level level, BlockPos pos) {
        if (level == null || pos == null || level.isClientSide()) return true;

        String dimension = level.dimension().location().toString();
        ProtectionRoomManager.ProtectionRoom room = ProtectionRoomManager.get().getRoomAt(pos, dimension);
        if (room != null && room.ownerUuid != null) {
            return ProtectionRoomManager.get().getRoomFlag(room, "explosions");
        }

        ChunkPos chunkPos = new ChunkPos(pos);
        UUID owner = ClaimManager.get().getChunkOwner(chunkPos, dimension);
        if (owner == null) return true;

        String claimId = ClaimManager.get().getClaimId(chunkPos, dimension);
        return ClaimManager.get().getFlag(owner, claimId, "explosions");
    }

    // ==========================================
    // MAXIMUM INTERCEPTION FOR MOWZIE'S MOBS
    // ==========================================

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onUseItemStart(LivingEntityUseItemEvent.Start event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (isForbiddenItem(event.getItem()) && !canUseMowzieItem(player)) {
                event.setCanceled(true);
                event.setDuration(-1);
                player.stopUsingItem();
                sendMsg(player, "§c[!] You cannot use this ability here!");
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onUseItemTick(LivingEntityUseItemEvent.Tick event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (isForbiddenItem(event.getItem()) && !canUseMowzieItem(player)) {
                event.setCanceled(true);
                event.setDuration(-1);
                player.stopUsingItem();
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (ProtectionRoomManager.get().isRoomContract(event.getItemStack())) {
                ProtectionRoomManager.get().redeemContract(player, event.getItemStack());
                event.setCanceled(true);
                event.setResult(Event.Result.DENY);
                return;
            }

            if (player.hasPermissions(2)) return;

            if (isForbiddenItem(event.getItemStack())) {
                if (!canUseMowzieItem(player)) {
                    event.setCanceled(true);
                    event.setResult(Event.Result.DENY);
                    player.stopUsingItem();
                    sendMsg(player, "§c[!] You cannot use items from Mowzie's Mobs in this protection!");
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            String dim = player.level().dimension().location().toString();
            if (ProtectionRoomManager.get().isRoomSign(event.getPos(), dim) && !player.hasPermissions(2)) {
                event.setCanceled(true);
                sendMsg(player, "\u00A7c[!] You cannot break this room sign.");
                return;
            }

            if (isCarryOnAttempt(player)) {
                if (canUseCarryOnAt(player, event.getPos())) return;
                event.setCanceled(true);
                sendCarryOnDenied(player);
                if (event.getLevel() instanceof Level level) {
                    BlockState state = level.getBlockState(event.getPos());
                    level.sendBlockUpdated(event.getPos(), state, state, 3);
                }
                return;
            }

            if (!canInteract(player, event.getPos(), ClaimAction.BLOCK_BREAK, event.getLevel().getBlockState(event.getPos()))) {
                event.setCanceled(true);
                sendMsg(player);

                // THE ULTIMATE SOLUTION FOR GHOST BLOCKS (Mowzie Desync)
                if (event.getLevel() instanceof Level level) {
                    BlockState state = level.getBlockState(event.getPos());
                    level.sendBlockUpdated(event.getPos(), state, state, 3);
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (isCarryOnAttempt(player)) {
                if (canUseCarryOnAt(player, event.getPos())) return;
                event.setCanceled(true);
                sendCarryOnDenied(player);
                if (event.getLevel() instanceof Level level) {
                    BlockState state = level.getBlockState(event.getPos());
                    level.sendBlockUpdated(event.getPos(), state, state, 3);
                }
                return;
            }

            if (!canInteract(player, event.getPos(), ClaimAction.BLOCK_PLACE, event.getPlacedBlock())) {
                event.setCanceled(true);
                sendMsg(player);

                // THE ULTIMATE SOLUTION FOR GHOST BLOCKS (Mowzie Desync)
                if (event.getLevel() instanceof Level level) {
                    BlockState state = level.getBlockState(event.getPos());
                    level.sendBlockUpdated(event.getPos(), state, state, 3);
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteractBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BlockPos pos = event.getPos();
            BlockState state = player.level().getBlockState(pos);
            String dim = player.level().dimension().location().toString();
            ProtectionRoomManager.ProtectionRoom room = ProtectionRoomManager.get().getRoomAt(pos, dim);

            if (ProtectionRoomManager.get().handleRoomSignInteract(player, pos)) {
                event.setCanceled(true);
                event.setUseBlock(Event.Result.DENY);
                event.setUseItem(Event.Result.DENY);
                return;
            }

            if (player.getMainHandItem().isEmpty()
                    && ProtectionRoomManager.get().handlePendingRoomSignPlacement(player, pos, event.getFace())) {
                event.setCanceled(true);
                event.setUseBlock(Event.Result.DENY);
                event.setUseItem(Event.Result.DENY);
                return;
            }

            if (ProtectionRoomManager.get().isRoomContract(event.getItemStack())) {
                ProtectionRoomManager.get().redeemContract(player, event.getItemStack());
                event.setCanceled(true);
                event.setUseBlock(Event.Result.DENY);
                event.setUseItem(Event.Result.DENY);
                return;
            }

            // THE ULTIMATE MOWZIE'S MOBS BARRIER (TOTALLY STOPS THE PHYSICAL CLICK EVENT)
            if (isForbiddenItem(player.getMainHandItem()) || isForbiddenItem(player.getOffhandItem())) {
                if (!canUseMowzieItemAt(player, pos)) {
                    event.setCanceled(true);
                    event.setUseBlock(Event.Result.DENY);
                    event.setUseItem(Event.Result.DENY);
                    player.stopUsingItem();
                    sendMsg(player, "§c[!] You cannot use the Glove on this land!");

                    // Prevents Ghost Blocks by forcing block update back to the client
                    Level level = player.level();
                    level.sendBlockUpdated(pos, state, state, 3);
                    return;
                }
            }

            if (isCarryOnAttempt(player)) {
                if (canUseCarryOnAt(player, pos)) return;
                event.setCanceled(true);
                event.setUseBlock(Event.Result.DENY);
                event.setUseItem(Event.Result.DENY);
                sendCarryOnDenied(player);
                return;
            }

            if (!canInteract(player, pos, ClaimAction.BLOCK_INTERACT, state)) {
                if (canPlaceHeldBlockInAccessibleRoom(event, player, state, dim)) {
                    event.setUseBlock(Event.Result.DENY);
                    event.setUseItem(Event.Result.ALLOW);
                    return;
                }
                event.setCanceled(true);
                event.setUseBlock(Event.Result.DENY);
                event.setUseItem(Event.Result.DENY);
                sendMsg(player);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onInteractBlockAfterCarryOn(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)
                || !isCarryOnAttempt(player)) {
            return;
        }

        BlockPos pos = event.getPos();
        BlockState state = player.level().getBlockState(pos);
        if (!canInteract(player, pos, ClaimAction.BLOCK_INTERACT, state)) {
            event.setCanceled(true);
            event.setUseBlock(Event.Result.DENY);
            event.setUseItem(Event.Result.DENY);
            sendMsg(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ChunkPos chunkPos = new ChunkPos(event.getPos());

            if (isForbiddenItem(player.getMainHandItem()) || isForbiddenItem(player.getOffhandItem())) {
                if (!canUseMowzieItemAt(player, event.getPos())) {
                    event.setCanceled(true);
                    player.stopUsingItem();
                    sendMsg(player, "§c[!] You cannot use the Glove on this land!");

                    Level level = player.level();
                    BlockPos pos = event.getPos();
                    BlockState state = level.getBlockState(pos);
                    level.sendBlockUpdated(pos, state, state, 3);
                    return;
                }
            }

            BlockState state = player.level().getBlockState(event.getPos());
            if (!canInteract(player, event.getPos(), ClaimAction.BLOCK_BREAK, state)) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteractEntity(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ChunkPos chunkPos = new ChunkPos(event.getTarget().blockPosition());
            String dim = player.level().dimension().location().toString();
            UUID owner = ClaimManager.get().getChunkOwner(chunkPos, dim);

            ProtectionRoomManager.ProtectionRoom room = ProtectionRoomManager.get().getRoomAt(event.getTarget().blockPosition(), dim);
            if (room != null) {
                if (!canInteractWithRoomEntity(player, room)) {
                    event.setCanceled(true);
                    event.setResult(Event.Result.DENY);
                    sendMsg(player);
                }
                return;
            }

            if (isForbiddenItem(player.getMainHandItem()) || isForbiddenItem(player.getOffhandItem())) {
                if (!canUseMowzieItemAt(player, event.getTarget().blockPosition())) {
                    event.setCanceled(true);
                    event.setResult(Event.Result.DENY);
                    player.stopUsingItem();
                    sendMsg(player, "§c[!] You cannot use items from Mowzie's Mobs on entities here!");
                    return;
                }
            }

            if (owner != null && !owner.equals(player.getUUID())
                    && !ProtectionPermissions.canBypassClaim(player, owner)) {
                String claimId = ClaimManager.get().getClaimId(chunkPos, dim);
                if (!ClaimManager.get().isTrusted(owner, player.getUUID(), claimId)) {
                    if (isCarryOnEntityAttempt(player)) {
                        if (isFlyingCarryOnAttempt(player)) {
                            event.setCanceled(true);
                            event.setResult(Event.Result.DENY);
                            sendMsg(player, "§c[!] You cannot use Carry On while flying here!");
                            return;
                        }
                        if (!ClaimManager.get().getFlag(owner, claimId, "carry_on")) {
                            event.setCanceled(true);
                            sendMsg(player, "§c[!] You do not have permission to use Carry On here!");
                            return;
                        }
                        return;
                    }

                    if (!ClaimManager.get().getFlag(owner, claimId, "interact_entities")) {
                        event.setCanceled(true);
                        sendMsg(player);
                    }
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onInteractEntityAfterCarryOn(PlayerInteractEvent.EntityInteract event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)
                || !isCarryOnEntityAttempt(player)) {
            return;
        }

        if (!canInteractWithEntityNormally(player, event.getTarget().blockPosition())) {
            event.setCanceled(true);
            event.setResult(Event.Result.DENY);
            sendMsg(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteractEntitySpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ChunkPos chunkPos = new ChunkPos(event.getTarget().blockPosition());
            String dim = player.level().dimension().location().toString();
            UUID owner = ClaimManager.get().getChunkOwner(chunkPos, dim);

            ProtectionRoomManager.ProtectionRoom room = ProtectionRoomManager.get().getRoomAt(event.getTarget().blockPosition(), dim);
            if (room != null) {
                if (!canInteractWithRoomEntity(player, room)) {
                    event.setCanceled(true);
                    event.setResult(Event.Result.DENY);
                    sendMsg(player);
                }
                return;
            }

            if (isForbiddenItem(player.getMainHandItem()) || isForbiddenItem(player.getOffhandItem())) {
                if (!canUseMowzieItemAt(player, event.getTarget().blockPosition())) {
                    event.setCanceled(true);
                    event.setResult(Event.Result.DENY);
                    player.stopUsingItem();
                    sendMsg(player, "§c[!] You cannot use items from Mowzie's Mobs on entities here!");
                    return;
                }
            }

            if (owner != null && !owner.equals(player.getUUID())
                    && !ProtectionPermissions.canBypassClaim(player, owner)) {
                String claimId = ClaimManager.get().getClaimId(chunkPos, dim);
                if (!ClaimManager.get().isTrusted(owner, player.getUUID(), claimId)) {
                    if (isCarryOnEntityAttempt(player)) {
                        if (isFlyingCarryOnAttempt(player)) {
                            event.setCanceled(true);
                            event.setResult(Event.Result.DENY);
                            sendMsg(player, "§c[!] You cannot use Carry On while flying here!");
                            return;
                        }
                        if (!ClaimManager.get().getFlag(owner, claimId, "carry_on")) {
                            event.setCanceled(true);
                            sendMsg(player, "§c[!] You do not have permission to use Carry On here!");
                            return;
                        }
                        return;
                    }

                    if (!ClaimManager.get().getFlag(owner, claimId, "interact_entities")) {
                        event.setCanceled(true);
                        sendMsg(player);
                    }
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onFillBucket(FillBucketEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            HitResult target = event.getTarget();
            if (target instanceof BlockHitResult blockHit) {
                BlockPos pos = blockHit.getBlockPos();
                BlockPos placePos = pos.relative(blockHit.getDirection());

                if (!canInteract(player, pos, ClaimAction.WORLD_MODIFY, null)
                        || !canInteract(player, placePos, ClaimAction.WORLD_MODIFY, null)) {
                    event.setCanceled(true);
                    event.setResult(Event.Result.DENY);
                    sendMsg(player);
                }
            } else {
                if (!canInteract(player, player.blockPosition(), ClaimAction.WORLD_MODIFY, null)) {
                    event.setCanceled(true);
                    event.setResult(Event.Result.DENY);
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (!canInteract(player, event.getPos(), ClaimAction.FARMLAND_TRAMPLE, null)) {
                event.setCanceled(true);
            }
        }
    }

    private static boolean canInteract(ServerPlayer player, BlockPos pos, ClaimAction action, BlockState state) {
        if (player.hasPermissions(2)) return true;

        ChunkPos chunkPos = new ChunkPos(pos);
        String dim = player.level().dimension().location().toString();

        UUID owner = ClaimManager.get().getChunkOwner(chunkPos, dim);

        if (ProtectionConfig.get().isPlotMode) {
            if (owner == null) {
                return false;
            }
        }

        if (owner == null) return true;

        ProtectionRoomManager.ProtectionRoom room = ProtectionRoomManager.get().getRoomAt(pos, dim);
        if (room != null) {
            if (ProtectionRoomManager.get().canPlayerAccessRoom(player, room)) return true;

            boolean roomPublicBuild = ProtectionRoomManager.get().getRoomFlag(room, "public_build");
            return switch (action) {
                case BLOCK_BREAK -> roomPublicBuild && !isInventoryBlock(player, pos, state);
                case BLOCK_PLACE, WORLD_MODIFY -> roomPublicBuild;
                case FARMLAND_TRAMPLE -> false;
                case BLOCK_INTERACT -> canUseRoomBlock(player, pos, state, room, roomPublicBuild);
            };
        }

        if (ProtectionPermissions.canBypassClaim(player, owner)) return true;

        if (owner.equals(player.getUUID())) return true;

        String claimId = ClaimManager.get().getClaimId(chunkPos, dim);
        if (ClaimManager.get().isTrusted(owner, player.getUUID(), claimId)) return true;

        boolean publicBuild = ClaimManager.get().getFlag(owner, claimId, "public_build");
        return switch (action) {
            case BLOCK_BREAK -> publicBuild && !isInventoryBlock(player, pos, state);
            case BLOCK_PLACE, WORLD_MODIFY -> publicBuild;
            case FARMLAND_TRAMPLE -> false;
            case BLOCK_INTERACT -> canUseBlock(player, pos, state, owner, claimId, publicBuild);
        };
    }

    private static boolean canPlaceHeldBlockInAccessibleRoom(PlayerInteractEvent.RightClickBlock event,
                                                              ServerPlayer player, BlockState clickedState,
                                                              String dimension) {
        if (!(event.getItemStack().getItem() instanceof BlockItem)) return false;

        BlockPos placementPos = clickedState.canBeReplaced()
                ? event.getPos()
                : event.getPos().relative(event.getFace());
        ProtectionRoomManager.ProtectionRoom targetRoom = ProtectionRoomManager.get().getRoomAt(placementPos, dimension);
        return targetRoom != null && ProtectionRoomManager.get().canPlayerAccessRoom(player, targetRoom);
    }

    private static boolean isPvpAllowedAt(ServerPlayer player, BlockPos pos) {
        if (player.hasPermissions(2)) return true;
        String dim = player.level().dimension().location().toString();
        ProtectionRoomManager.ProtectionRoom room = ProtectionRoomManager.get().getRoomAt(pos, dim);
        if (room != null && room.ownerUuid != null) {
            return ProtectionRoomManager.get().getRoomFlag(room, "pvp");
        }

        ChunkPos chunkPos = new ChunkPos(pos);
        UUID owner = ClaimManager.get().getChunkOwner(chunkPos, dim);
        if (owner == null || ProtectionPermissions.canBypassClaim(player, owner)) return true;
        String claimId = ClaimManager.get().getClaimId(chunkPos, dim);
        return ClaimManager.get().getFlag(owner, claimId, "pvp");
    }

    private static boolean canInteractWithRoomEntity(ServerPlayer player, ProtectionRoomManager.ProtectionRoom room) {
        if (ProtectionRoomManager.get().canPlayerAccessRoom(player, room)) return true;
        if (isCarryOnEntityAttempt(player)) {
            return !isFlyingCarryOnAttempt(player)
                    && ProtectionRoomManager.get().getRoomFlag(room, "carry_on");
        }
        return ProtectionRoomManager.get().getRoomFlag(room, "interact_entities");
    }

    private static boolean canInteractWithEntityNormally(ServerPlayer player, BlockPos pos) {
        String dimension = player.level().dimension().location().toString();
        ProtectionRoomManager.ProtectionRoom room = ProtectionRoomManager.get().getRoomAt(pos, dimension);
        if (room != null) {
            return ProtectionRoomManager.get().canPlayerAccessRoom(player, room)
                    || ProtectionRoomManager.get().getRoomFlag(room, "interact_entities");
        }

        ChunkPos chunkPos = new ChunkPos(pos);
        UUID owner = ClaimManager.get().getChunkOwner(chunkPos, dimension);
        if (owner == null
                || owner.equals(player.getUUID())
                || ProtectionPermissions.canBypassClaim(player, owner)) {
            return true;
        }

        String claimId = ClaimManager.get().getClaimId(chunkPos, dimension);
        return ClaimManager.get().isTrusted(owner, player.getUUID(), claimId)
                || ClaimManager.get().getFlag(owner, claimId, "interact_entities");
    }

    private static boolean isCarryOnAttempt(ServerPlayer player) {
        return CarryOnCompat.getState(player).isBlockInteractionAttempt();
    }

    private static boolean isCarryOnEntityAttempt(ServerPlayer player) {
        return CarryOnCompat.getState(player).isEntityInteractionAttempt();
    }

    private static boolean isFlyingCarryOnAttempt(ServerPlayer player) {
        return player.getAbilities().flying;
    }

    private static boolean canUseCarryOnAt(ServerPlayer player, BlockPos pos) {
        String dimension = player.level().dimension().location().toString();
        ProtectionRoomManager.ProtectionRoom room = ProtectionRoomManager.get().getRoomAt(pos, dimension);
        if (room != null) {
            if (ProtectionRoomManager.get().canPlayerAccessRoom(player, room)) return true;
            return !isFlyingCarryOnAttempt(player)
                    && ProtectionRoomManager.get().getRoomFlag(room, "carry_on");
        }

        ChunkPos chunkPos = new ChunkPos(pos);
        UUID owner = ClaimManager.get().getChunkOwner(chunkPos, dimension);
        if (owner == null
                || owner.equals(player.getUUID())
                || ProtectionPermissions.canBypassClaim(player, owner)) {
            return true;
        }

        String claimId = ClaimManager.get().getClaimId(chunkPos, dimension);
        if (ClaimManager.get().isTrusted(owner, player.getUUID(), claimId)) return true;
        return !isFlyingCarryOnAttempt(player)
                && ClaimManager.get().getFlag(owner, claimId, "carry_on");
    }

    private static void sendCarryOnDenied(ServerPlayer player) {
        if (isFlyingCarryOnAttempt(player)) {
            sendMsg(player, "\u00A7c[!] You cannot use Carry On while flying here!");
        } else {
            sendMsg(player, "\u00A7c[!] You do not have permission to use Carry On here!");
        }
    }

    private static boolean canUseBlock(ServerPlayer player, BlockPos pos, BlockState state, UUID owner, String claimId, boolean publicBuild) {
        if (state == null) return false;

        String flag = getBlockInteractionFlag(player, pos, state);
        if (!flag.isEmpty()) {
            return ClaimManager.get().getFlag(owner, claimId, flag);
        }

        return publicBuild && isHoldingBlock(player);
    }

    private static boolean canUseRoomBlock(ServerPlayer player, BlockPos pos, BlockState state,
                                           ProtectionRoomManager.ProtectionRoom room, boolean publicBuild) {
        if (state == null) return false;

        String flag = getBlockInteractionFlag(player, pos, state);
        if (!flag.isEmpty()) {
            return ProtectionRoomManager.get().getRoomFlag(room, flag);
        }

        return publicBuild && isHoldingBlock(player);
    }

    private static String getBlockInteractionFlag(ServerPlayer player, BlockPos pos, BlockState state) {
        if (state == null) return "";

        Block block = state.getBlock();
        if (isDoorControl(block)) return "doors";
        if (isUtilityBlock(block)) return "use";
        if (isContainerAccessBlock(player, pos, state)) return "chests";
        return "";
    }

    private static boolean isHoldingBlock(ServerPlayer player) {
        return player.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem
                || player.getOffhandItem().getItem() instanceof net.minecraft.world.item.BlockItem;
    }

    private static boolean isDoorControl(Block block) {
        return block instanceof net.minecraft.world.level.block.DoorBlock
                || block instanceof net.minecraft.world.level.block.TrapDoorBlock
                || block instanceof net.minecraft.world.level.block.FenceGateBlock
                || block instanceof net.minecraft.world.level.block.ButtonBlock
                || block instanceof net.minecraft.world.level.block.LeverBlock;
    }

    private static boolean isUtilityBlock(Block block) {
        return isAppleCratesBlock(block)
                || block instanceof net.minecraft.world.level.block.CraftingTableBlock
                || block instanceof net.minecraft.world.level.block.AnvilBlock
                || block instanceof net.minecraft.world.level.block.EnchantmentTableBlock
                || block instanceof net.minecraft.world.level.block.LoomBlock
                || block instanceof net.minecraft.world.level.block.CartographyTableBlock
                || block instanceof net.minecraft.world.level.block.SmithingTableBlock
                || block instanceof net.minecraft.world.level.block.GrindstoneBlock
                || block instanceof net.minecraft.world.level.block.StonecutterBlock
                || block instanceof net.minecraft.world.level.block.LecternBlock
                || block instanceof net.minecraft.world.level.block.BedBlock
                || block instanceof net.minecraft.world.level.block.BellBlock;
    }

    private static boolean isAppleCratesBlock(Block block) {
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(block);
        return blockId != null
                && "applecrates".equals(blockId.getNamespace())
                && blockId.getPath().endsWith("_crate");
    }

    private static boolean isContainerAccessBlock(ServerPlayer player, BlockPos pos, BlockState state) {
        return state.getMenuProvider(player.level(), pos) != null
                || state.getBlock() instanceof net.minecraft.world.level.block.AbstractChestBlock;
    }

    private static boolean isInventoryBlock(ServerPlayer player, BlockPos pos, BlockState state) {
        if (state == null) return false;
        return player.level().getBlockEntity(pos) instanceof net.minecraft.world.Container
                || state.getBlock() instanceof net.minecraft.world.level.block.AbstractChestBlock;
    }
}
