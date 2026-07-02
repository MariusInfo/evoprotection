package org.evocraft.evoprotection.events;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
import org.evocraft.evoprotection.manager.ClaimManager;
import org.evocraft.evoprotection.network.PacketHandler;
import org.evocraft.evoprotection.manager.ProtectionConfig;
import org.evocraft.evocore.data.EconomyManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@SuppressWarnings({"resource", "BooleanMethodIsAlwaysInverted"})
@Mod.EventBusSubscriber(modid = EvoProtection.MODID)
public class ProtectionEvents {

    private static final Map<UUID, String> lastChunkOwnerMap = new HashMap<>();

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
        if (owner.equals(player.getUUID())) return true; // Can use in own claim

        String claimName = ClaimManager.get().getCustomName(targetChunk, dim);
        return ClaimManager.get().isTrusted(owner, player.getUUID(), claimName);
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
                ChunkPos attackerChunk = attacker.chunkPosition();
                ChunkPos targetChunk = new ChunkPos(target.blockPosition());

                UUID attackerZoneOwner = ClaimManager.get().getChunkOwner(attackerChunk, dim);
                UUID targetZoneOwner = ClaimManager.get().getChunkOwner(targetChunk, dim);

                if (attackerZoneOwner != null && !attacker.hasPermissions(2)) {
                    if (attackerZoneOwner.getMostSignificantBits() == 0 && attackerZoneOwner.getLeastSignificantBits() == 0) {
                        event.setCanceled(true); sendMsg(attacker); return;
                    }
                    String claimName = ClaimManager.get().getCustomName(attackerChunk, dim);
                    if (!ClaimManager.get().getFlag(attackerZoneOwner, claimName, "pvp")) {
                        event.setCanceled(true);
                        attacker.displayClientMessage(Component.literal("§c[!] PVP is disabled in the area you are attacking from!"), true);
                        return;
                    }
                }

                if (targetZoneOwner != null && !attacker.hasPermissions(2)) {
                    if (targetZoneOwner.getMostSignificantBits() == 0 && targetZoneOwner.getLeastSignificantBits() == 0) {
                        event.setCanceled(true); sendMsg(attacker); return;
                    }
                    String claimName = ClaimManager.get().getCustomName(targetChunk, dim);
                    if (!ClaimManager.get().getFlag(targetZoneOwner, claimName, "pvp")) {
                        event.setCanceled(true);
                        attacker.displayClientMessage(Component.literal("§c[!] PVP is disabled in that protection!"), true);
                        return;
                    }
                }
                return;
            }

            ChunkPos chunkPos = new ChunkPos(target.blockPosition());
            UUID owner = ClaimManager.get().getChunkOwner(chunkPos, dim);

            if (owner != null && !owner.equals(attacker.getUUID()) && !attacker.hasPermissions(2)) {
                String claimName = ClaimManager.get().getCustomName(chunkPos, dim);
                if (!ClaimManager.get().isTrusted(owner, attacker.getUUID(), claimName)) {

                    if (target instanceof net.minecraft.world.entity.animal.Animal) {
                        if (!ClaimManager.get().getFlag(owner, claimName, "hurt_animals")) {
                            event.setCanceled(true);
                            sendMsg(attacker);
                        }
                    } else if (!(target instanceof Monster)) {
                        if (!ClaimManager.get().getFlag(owner, claimName, "public_build")) {
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

                if (attackerZoneOwner != null) {
                    if (attackerZoneOwner.getMostSignificantBits() == 0 && attackerZoneOwner.getLeastSignificantBits() == 0) {
                        event.setCanceled(true); return;
                    }
                    String claimName = ClaimManager.get().getCustomName(attackerChunk, dim);
                    if (!ClaimManager.get().getFlag(attackerZoneOwner, claimName, "pvp")) {
                        event.setCanceled(true);
                        return;
                    }
                }

                if (targetZoneOwner != null) {
                    if (targetZoneOwner.getMostSignificantBits() == 0 && targetZoneOwner.getLeastSignificantBits() == 0) {
                        event.setCanceled(true); return;
                    }
                    String claimName = ClaimManager.get().getCustomName(targetChunk, dim);
                    if (!ClaimManager.get().getFlag(targetZoneOwner, claimName, "pvp")) {
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
    public static void onPlayerMove(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide()) return;

        if (event.player instanceof ServerPlayer player) {

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
                            player.sendSystemMessage(Component.literal("§8[§aPayDay§8] §fYou received §e" + REWARD_AMOUNT + " Lei §ffor your activity!"));
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

                UUID owner = ClaimManager.get().getChunkOwner(current, dim);
                String name = ClaimManager.get().getOwnerName(owner);
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
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof net.minecraft.world.entity.LightningBolt ||
                event.getEntity() instanceof net.minecraft.world.entity.item.PrimedTnt) {
            ChunkPos pos = new ChunkPos(event.getEntity().blockPosition());
            String dim = event.getLevel().dimension().location().toString();
            UUID owner = ClaimManager.get().getChunkOwner(pos, dim);

            if (owner != null && owner.getMostSignificantBits() == 0 && owner.getLeastSignificantBits() == 0) {
                event.setCanceled(true);
            }
        }
    }

    // ==========================================
    // 🧬 SPAWN CONTROL (ANIMALS & MONSTERS)
    // ==========================================
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMobSpawn(net.minecraftforge.event.entity.living.MobSpawnEvent.FinalizeSpawn event) {
        net.minecraft.world.entity.Mob entity = event.getEntity();
        ChunkPos pos = new ChunkPos(entity.blockPosition());
        String dim = entity.level().dimension().location().toString();
        UUID owner = ClaimManager.get().getChunkOwner(pos, dim);

        if (owner != null) {
            if (owner.getMostSignificantBits() == 0 && owner.getLeastSignificantBits() == 0) {
                event.setResult(Event.Result.DENY);
                event.setCanceled(true);
                return;
            }

            String claimName = ClaimManager.get().getCustomName(pos, dim);
            net.minecraft.world.entity.MobSpawnType spawnType = event.getSpawnType();

            // Always allow Breeding, Spawn Eggs, Buckets, and Commands to not block manual interaction
            if (spawnType == net.minecraft.world.entity.MobSpawnType.BREEDING ||
                    spawnType == net.minecraft.world.entity.MobSpawnType.SPAWN_EGG ||
                    spawnType == net.minecraft.world.entity.MobSpawnType.BUCKET ||
                    spawnType == net.minecraft.world.entity.MobSpawnType.COMMAND) {
                return;
            }

            boolean isMonster = entity instanceof net.minecraft.world.entity.monster.Monster;
            boolean isAnimal = entity instanceof net.minecraft.world.entity.animal.Animal || entity instanceof net.minecraft.world.entity.animal.WaterAnimal;
            boolean isSpawner = (spawnType == net.minecraft.world.entity.MobSpawnType.SPAWNER);

            if (isMonster) {
                if (isSpawner) {
                    if (!ClaimManager.get().getFlag(owner, claimName, "spawner_monsters")) {
                        event.setResult(Event.Result.DENY);
                        event.setCanceled(true);
                    }
                } else {
                    if (!ClaimManager.get().getFlag(owner, claimName, "natural_monsters")) {
                        event.setResult(Event.Result.DENY);
                        event.setCanceled(true);
                    }
                }
            } else if (isAnimal) {
                if (isSpawner) {
                    if (!ClaimManager.get().getFlag(owner, claimName, "spawner_animals")) {
                        event.setResult(Event.Result.DENY);
                        event.setCanceled(true);
                    }
                } else {
                    if (!ClaimManager.get().getFlag(owner, claimName, "natural_animals")) {
                        event.setResult(Event.Result.DENY);
                        event.setCanceled(true);
                    }
                }
            }
        }
    }


    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onItemPickup(net.minecraftforge.event.entity.player.EntityItemPickupEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ChunkPos chunkPos = new ChunkPos(event.getItem().blockPosition());
            String dim = player.level().dimension().location().toString();
            UUID owner = ClaimManager.get().getChunkOwner(chunkPos, dim);

            if (owner != null && !owner.equals(player.getUUID()) && !player.hasPermissions(2)) {
                String claimName = ClaimManager.get().getCustomName(chunkPos, dim);
                if (!ClaimManager.get().isTrusted(owner, player.getUUID(), claimName)) {
                    if (!ClaimManager.get().getFlag(owner, claimName, "item_pickup")) {
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
                if (owner.getMostSignificantBits() == 0 && owner.getLeastSignificantBits() == 0) return true;
                String claimName = ClaimManager.get().getCustomName(chunkPos, dim);
                return !ClaimManager.get().getFlag(owner, claimName, "explosions");
            }
            return false;
        });

        event.getAffectedEntities().removeIf(entity -> {
            if (entity instanceof Player || entity instanceof Monster) return false;

            ChunkPos chunkPos = new ChunkPos(entity.blockPosition());
            UUID owner = ClaimManager.get().getChunkOwner(chunkPos, dim);
            if (owner != null) {
                if (owner.getMostSignificantBits() == 0 && owner.getLeastSignificantBits() == 0) return true;
                String claimName = ClaimManager.get().getCustomName(chunkPos, dim);
                return !ClaimManager.get().getFlag(owner, claimName, "explosions");
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

            if (owner != null && !owner.equals(player.getUUID()) && !player.hasPermissions(2)) {
                String claimName = ClaimManager.get().getCustomName(chunkPos, dim);
                if (!ClaimManager.get().isTrusted(owner, player.getUUID(), claimName)) {

                    boolean isCarryOnAttempt = player.isCrouching() && player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty();
                    if (isCarryOnAttempt) {
                        if (!ClaimManager.get().getFlag(owner, claimName, "carry_on")) {
                            event.setCanceled(true);
                            event.setUseBlock(Event.Result.DENY);
                            event.setUseItem(Event.Result.DENY);
                            sendMsg(player, "§c[!] You do not have permission to use Carry On here!");
                            return;
                        }
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
            if (!canInteract(player, event.getPos(), true, state)) {
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

            if (isForbiddenItem(player.getMainHandItem()) || isForbiddenItem(player.getOffhandItem())) {
                if (!canUseMowzieItemAt(player, chunkPos)) {
                    event.setCanceled(true);
                    event.setResult(Event.Result.DENY);
                    player.stopUsingItem();
                    sendMsg(player, "§c[!] You cannot use items from Mowzie's Mobs on entities here!");
                    return;
                }
            }

            if (owner != null && !owner.equals(player.getUUID()) && !player.hasPermissions(2)) {
                String claimName = ClaimManager.get().getCustomName(chunkPos, dim);
                if (!ClaimManager.get().isTrusted(owner, player.getUUID(), claimName)) {
                    if (ClaimManager.get().getFlag(owner, claimName, "public_build")) return;

                    boolean isCarryOnAttempt = player.isCrouching() && player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty();
                    if (isCarryOnAttempt) {
                        if (!ClaimManager.get().getFlag(owner, claimName, "carry_on")) {
                            event.setCanceled(true);
                            sendMsg(player, "§c[!] You do not have permission to use Carry On here!");
                            return;
                        }
                    }

                    if (!ClaimManager.get().getFlag(owner, claimName, "interact_entities")) {
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

            if (isForbiddenItem(player.getMainHandItem()) || isForbiddenItem(player.getOffhandItem())) {
                if (!canUseMowzieItemAt(player, chunkPos)) {
                    event.setCanceled(true);
                    event.setResult(Event.Result.DENY);
                    player.stopUsingItem();
                    sendMsg(player, "§c[!] You cannot use items from Mowzie's Mobs on entities here!");
                    return;
                }
            }

            if (owner != null && !owner.equals(player.getUUID()) && !player.hasPermissions(2)) {
                String claimName = ClaimManager.get().getCustomName(chunkPos, dim);
                if (!ClaimManager.get().isTrusted(owner, player.getUUID(), claimName)) {
                    if (ClaimManager.get().getFlag(owner, claimName, "public_build")) return;

                    boolean isCarryOnAttempt = player.isCrouching() && player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty();
                    if (isCarryOnAttempt) {
                        if (!ClaimManager.get().getFlag(owner, claimName, "carry_on")) {
                            event.setCanceled(true);
                            sendMsg(player, "§c[!] You do not have permission to use Carry On here!");
                            return;
                        }
                    }

                    if (!ClaimManager.get().getFlag(owner, claimName, "interact_entities")) {
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
        if (owner.equals(player.getUUID())) return true;

        if (owner.getMostSignificantBits() == 0 && owner.getLeastSignificantBits() == 0) {
            return isRightClick;
        }

        String claimName = ClaimManager.get().getCustomName(chunkPos, dim);
        if (ClaimManager.get().isTrusted(owner, player.getUUID(), claimName)) return true;

        if (ClaimManager.get().getFlag(owner, claimName, "public_build")) return true;

        if (isRightClick && state != null) {
            Block block = state.getBlock();

            boolean isDoor = block instanceof net.minecraft.world.level.block.DoorBlock ||
                    block instanceof net.minecraft.world.level.block.TrapDoorBlock ||
                    block instanceof net.minecraft.world.level.block.FenceGateBlock ||
                    block instanceof net.minecraft.world.level.block.ButtonBlock ||
                    block instanceof net.minecraft.world.level.block.LeverBlock;
            if (isDoor && ClaimManager.get().getFlag(owner, claimName, "doors")) return true;

            boolean isContainer = state.hasBlockEntity() || block instanceof net.minecraft.world.level.block.AbstractChestBlock;
            if (isContainer && ClaimManager.get().getFlag(owner, claimName, "chests")) return true;

            boolean isUse = block instanceof net.minecraft.world.level.block.CraftingTableBlock ||
                    block instanceof net.minecraft.world.level.block.AnvilBlock ||
                    block instanceof net.minecraft.world.level.block.EnchantmentTableBlock ||
                    block instanceof net.minecraft.world.level.block.LoomBlock ||
                    block instanceof net.minecraft.world.level.block.CartographyTableBlock ||
                    block instanceof net.minecraft.world.level.block.SmithingTableBlock ||
                    block instanceof net.minecraft.world.level.block.GrindstoneBlock ||
                    block instanceof net.minecraft.world.level.block.BedBlock ||
                    block instanceof net.minecraft.world.level.block.BellBlock;
            if (isUse && ClaimManager.get().getFlag(owner, claimName, "use")) return true;
        }

        return false;
    }
}