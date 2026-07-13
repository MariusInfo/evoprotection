package org.evocraft.evoprotection.events;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityMobGriefingEvent;
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

    private static boolean canUseMowzieItemAt(ServerPlayer player, ChunkPos targetChunk) {
        if (player.hasPermissions(2)) return true;
        String dim = player.level().dimension().location().toString();
        UUID owner = ClaimManager.get().getChunkOwner(targetChunk, dim);

        if (owner == null) return true; // Can use in wilderness
        if (ProtectionPermissions.canBypassClaim(player, owner)) return true;
        if (owner.equals(player.getUUID())) return true; // Can use in own claim

        String claimId = ClaimManager.get().getClaimId(targetChunk, dim);
        return ClaimManager.get().isTrusted(owner, player.getUUID(), claimId);
    }

    private static boolean canUseMowzieItem(ServerPlayer player) {
        return canUseMowzieItemAt(player, player.chunkPosition());
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
        ChunkPos chunkPos = new ChunkPos(entity.blockPosition());
        String dimension = entity.level().dimension().location().toString();
        UUID owner = ClaimManager.get().getChunkOwner(chunkPos, dimension);
        if (owner == null || isManualSpawn(spawnType)) return true;

        String claimId = ClaimManager.get().getClaimId(chunkPos, dimension);
        boolean fromSpawner = hasSpawner || spawnType == MobSpawnType.SPAWNER;
        boolean isMonster = entity instanceof Monster || entity.getType().getCategory() == MobCategory.MONSTER;

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

            ProtectionRoomManager.ProtectionRoom room = ProtectionRoomManager.get().getRoomAt(target.blockPosition(), dim);
            if (room != null) {
                if (target instanceof Player) {
                    event.setCanceled(true);
                    sendMsg(attacker);
                    return;
                }
                if (!ProtectionRoomManager.get().canPlayerAccessRoom(attacker, room)) {
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
                        if (!ClaimManager.get().getFlag(owner, claimId, "public_build")) {
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
                if (attackerPlayer.hasPermissions(2)) return;

                String dim = targetPlayer.level().dimension().location().toString();
                ChunkPos attackerChunk = attackerPlayer.chunkPosition();
                ChunkPos targetChunk = new ChunkPos(targetPlayer.blockPosition());

                UUID attackerZoneOwner = ClaimManager.get().getChunkOwner(attackerChunk, dim);
                UUID targetZoneOwner = ClaimManager.get().getChunkOwner(targetChunk, dim);

                if (attackerZoneOwner != null
                        && !ProtectionPermissions.canBypassClaim(attackerPlayer, attackerZoneOwner)) {
                    String claimId = ClaimManager.get().getClaimId(attackerChunk, dim);
                    if (!ClaimManager.get().getFlag(attackerZoneOwner, claimId, "pvp")) {
                        event.setCanceled(true);
                        return;
                    }
                }

                if (targetZoneOwner != null
                        && !ProtectionPermissions.canBypassClaim(attackerPlayer, targetZoneOwner)) {
                    String claimId = ClaimManager.get().getClaimId(targetChunk, dim);
                    if (!ClaimManager.get().getFlag(targetZoneOwner, claimId, "pvp")) {
                        event.setCanceled(true);
                        return;
                    }
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

        if (event.getEntity() instanceof net.minecraft.world.entity.LightningBolt ||
                event.getEntity() instanceof net.minecraft.world.entity.item.PrimedTnt) {
            ChunkPos pos = new ChunkPos(event.getEntity().blockPosition());
            String dim = event.getLevel().dimension().location().toString();
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
                if (!ProtectionRoomManager.get().canPlayerAccessRoom(player, room)) {
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

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;

        String dim = level.dimension().location().toString();

        event.getAffectedBlocks().removeIf(pos -> {
            ChunkPos chunkPos = new ChunkPos(pos);
            UUID owner = ClaimManager.get().getChunkOwner(chunkPos, dim);
            if (owner != null) {
                String claimId = ClaimManager.get().getClaimId(chunkPos, dim);
                return !ClaimManager.get().getFlag(owner, claimId, "explosions");
            }
            return false;
        });

        event.getAffectedEntities().removeIf(entity -> {
            if (entity instanceof Player || entity instanceof Monster) return false;

            ChunkPos chunkPos = new ChunkPos(entity.blockPosition());
            UUID owner = ClaimManager.get().getChunkOwner(chunkPos, dim);
            if (owner != null) {
                String claimId = ClaimManager.get().getClaimId(chunkPos, dim);
                return !ClaimManager.get().getFlag(owner, claimId, "explosions");
            }
            return false;
        });
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

            if (!canInteract(player, event.getPos(), false, event.getLevel().getBlockState(event.getPos()))) {
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
            if (!canInteract(player, event.getPos(), false, event.getPlacedBlock())) {
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
            ChunkPos chunkPos = new ChunkPos(pos);
            String dim = player.level().dimension().location().toString();
            UUID owner = ClaimManager.get().getChunkOwner(chunkPos, dim);

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
                if (!canUseMowzieItemAt(player, chunkPos)) {
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

            if (owner != null && !owner.equals(player.getUUID())
                    && !ProtectionPermissions.canBypassClaim(player, owner)) {
                String claimId = ClaimManager.get().getClaimId(chunkPos, dim);
                if (!ClaimManager.get().isTrusted(owner, player.getUUID(), claimId)) {

                    boolean isCarryOnAttempt = player.isCrouching() && player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty();
                    if (isCarryOnAttempt) {
                        if (!ClaimManager.get().getFlag(owner, claimId, "carry_on")) {
                            event.setCanceled(true);
                            event.setUseBlock(Event.Result.DENY);
                            event.setUseItem(Event.Result.DENY);
                            sendMsg(player, "§c[!] You do not have permission to use Carry On here!");
                            return;
                        }
                        return;
                    }
                }
            }

            if (!canInteract(player, pos, true, state)) {
                event.setCanceled(true);
                event.setUseBlock(Event.Result.DENY);
                event.setUseItem(Event.Result.DENY);
                sendMsg(player);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ChunkPos chunkPos = new ChunkPos(event.getPos());

            if (isForbiddenItem(player.getMainHandItem()) || isForbiddenItem(player.getOffhandItem())) {
                if (!canUseMowzieItemAt(player, chunkPos)) {
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
            if (!canInteract(player, event.getPos(), false, state)) {
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
                if (!ProtectionRoomManager.get().canPlayerAccessRoom(player, room)) {
                    event.setCanceled(true);
                    event.setResult(Event.Result.DENY);
                    sendMsg(player);
                }
                return;
            }

            if (isForbiddenItem(player.getMainHandItem()) || isForbiddenItem(player.getOffhandItem())) {
                if (!canUseMowzieItemAt(player, chunkPos)) {
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
                    boolean isCarryOnAttempt = player.isCrouching() && player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty();
                    if (isCarryOnAttempt) {
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
    public static void onInteractEntitySpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ChunkPos chunkPos = new ChunkPos(event.getTarget().blockPosition());
            String dim = player.level().dimension().location().toString();
            UUID owner = ClaimManager.get().getChunkOwner(chunkPos, dim);

            ProtectionRoomManager.ProtectionRoom room = ProtectionRoomManager.get().getRoomAt(event.getTarget().blockPosition(), dim);
            if (room != null) {
                if (!ProtectionRoomManager.get().canPlayerAccessRoom(player, room)) {
                    event.setCanceled(true);
                    event.setResult(Event.Result.DENY);
                    sendMsg(player);
                }
                return;
            }

            if (isForbiddenItem(player.getMainHandItem()) || isForbiddenItem(player.getOffhandItem())) {
                if (!canUseMowzieItemAt(player, chunkPos)) {
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
                    boolean isCarryOnAttempt = player.isCrouching() && player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty();
                    if (isCarryOnAttempt) {
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

                if (!canInteract(player, pos, false, null) || !canInteract(player, placePos, false, null)) {
                    event.setCanceled(true);
                    event.setResult(Event.Result.DENY);
                    sendMsg(player);
                }
            } else {
                if (!canInteract(player, player.blockPosition(), false, null)) {
                    event.setCanceled(true);
                    event.setResult(Event.Result.DENY);
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (!canInteract(player, event.getPos(), false, null)) {
                event.setCanceled(true);
            }
        }
    }

    private static boolean canInteract(ServerPlayer player, BlockPos pos, boolean isRightClick, BlockState state) {
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
            return ProtectionRoomManager.get().canPlayerAccessRoom(player, room);
        }

        if (ProtectionPermissions.canBypassClaim(player, owner)) return true;

        if (owner.equals(player.getUUID())) return true;

        String claimId = ClaimManager.get().getClaimId(chunkPos, dim);
        if (ClaimManager.get().isTrusted(owner, player.getUUID(), claimId)) return true;

        boolean publicBuild = ClaimManager.get().getFlag(owner, claimId, "public_build");
        if (!isRightClick) return publicBuild;

        if (state != null) {
            Block block = state.getBlock();

            boolean placingBlock = player.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem
                    || player.getOffhandItem().getItem() instanceof net.minecraft.world.item.BlockItem;
            if (publicBuild && placingBlock) return true;

            boolean isDoor = block instanceof net.minecraft.world.level.block.DoorBlock ||
                    block instanceof net.minecraft.world.level.block.TrapDoorBlock ||
                    block instanceof net.minecraft.world.level.block.FenceGateBlock ||
                    block instanceof net.minecraft.world.level.block.ButtonBlock ||
                    block instanceof net.minecraft.world.level.block.LeverBlock;
            if (isDoor) return ClaimManager.get().getFlag(owner, claimId, "doors");

            boolean isUse = block instanceof net.minecraft.world.level.block.CraftingTableBlock ||
                    block instanceof net.minecraft.world.level.block.AnvilBlock ||
                    block instanceof net.minecraft.world.level.block.EnchantmentTableBlock ||
                    block instanceof net.minecraft.world.level.block.LoomBlock ||
                    block instanceof net.minecraft.world.level.block.CartographyTableBlock ||
                    block instanceof net.minecraft.world.level.block.SmithingTableBlock ||
                    block instanceof net.minecraft.world.level.block.GrindstoneBlock ||
                    block instanceof net.minecraft.world.level.block.StonecutterBlock ||
                    block instanceof net.minecraft.world.level.block.LecternBlock ||
                    block instanceof net.minecraft.world.level.block.BedBlock ||
                    block instanceof net.minecraft.world.level.block.BellBlock;
            if (isUse) return ClaimManager.get().getFlag(owner, claimId, "use");

            boolean isContainer = state.getMenuProvider(player.level(), pos) != null
                    || block instanceof net.minecraft.world.level.block.AbstractChestBlock;
            if (isContainer) return ClaimManager.get().getFlag(owner, claimId, "chests");
        }

        return publicBuild;
    }
}
