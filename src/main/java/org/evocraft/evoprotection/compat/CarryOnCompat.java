package org.evocraft.evoprotection.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Method;

public final class CarryOnCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = "carryon";
    private static final State INACTIVE = new State(false, false, false);

    private static volatile boolean initialized;
    private static volatile boolean invocationWarningLogged;
    private static Access access;

    private CarryOnCompat() {
    }

    public static State getState(Player player) {
        if (player == null || !ModList.get().isLoaded(MOD_ID)) return INACTIVE;

        Access resolved = resolveAccess();
        if (resolved == null) return legacyState(player);

        try {
            Object carryData = resolved.getCarryData.invoke(null, player);
            if (carryData == null) return INACTIVE;
            boolean keyPressed = (boolean) resolved.isKeyPressed.invoke(carryData);
            boolean carrying = (boolean) resolved.isCarrying.invoke(carryData);
            boolean carryingEntity = (boolean) resolved.isCarryingType.invoke(carryData, resolved.entityType)
                    || (boolean) resolved.isCarryingType.invoke(carryData, resolved.playerType);
            return new State(keyPressed, carrying, carryingEntity);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!invocationWarningLogged) {
                invocationWarningLogged = true;
                LOGGER.warn("Failed to read Carry On state; falling back to legacy key detection", exception);
            }
            return legacyState(player);
        }
    }

    private static State legacyState(Player player) {
        boolean keyPressed = player.getMainHandItem().isEmpty()
                && player.getOffhandItem().isEmpty()
                && (player.isCrouching() || player.isShiftKeyDown());
        return new State(keyPressed, false, false);
    }

    private static Access resolveAccess() {
        if (initialized) return access;

        synchronized (CarryOnCompat.class) {
            if (initialized) return access;
            try {
                ClassLoader loader = CarryOnCompat.class.getClassLoader();
                Class<?> managerClass = Class.forName(
                        "tschipp.carryon.common.carry.CarryOnDataManager", false, loader);
                Class<?> dataClass = Class.forName(
                        "tschipp.carryon.common.carry.CarryOnData", false, loader);
                Class<?> carryTypeClass = Class.forName(
                        "tschipp.carryon.common.carry.CarryOnData$CarryType", false, loader);
                Method getCarryData = managerClass.getMethod("getCarryData", Player.class);
                Method isKeyPressed = dataClass.getMethod("isKeyPressed");
                Method isCarrying = dataClass.getMethod("isCarrying");
                Method isCarryingType = dataClass.getMethod("isCarrying", carryTypeClass);
                Object entityType = enumConstant(carryTypeClass, "ENTITY");
                Object playerType = enumConstant(carryTypeClass, "PLAYER");
                access = new Access(getCarryData, isKeyPressed, isCarrying,
                        isCarryingType, entityType, playerType);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
                LOGGER.warn("Carry On is installed but its 1.20 compatibility API could not be resolved", exception);
            } finally {
                initialized = true;
            }
            return access;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumConstant(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class), name);
    }

    private record Access(Method getCarryData, Method isKeyPressed, Method isCarrying,
                          Method isCarryingType, Object entityType, Object playerType) {
    }

    public record State(boolean keyPressed, boolean carrying, boolean carryingEntity) {
        public boolean isBlockInteractionAttempt() {
            return keyPressed || carrying;
        }

        public boolean isEntityInteractionAttempt() {
            return keyPressed || carryingEntity;
        }
    }
}
