package org.evocraft.evoprotection.mixin;

import com.google.common.collect.Multiset;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.evocraft.evoprotection.events.ProtectionEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.mehvahdjukaar.supplementaries.common.misc.explosion.CannonBallExplosion", remap = false)
public abstract class CannonBallExplosionMixin {
    @Inject(method = "destroyBlockNoEffects", at = @At("HEAD"), cancellable = true, remap = false)
    private void evoprotection$protectCannonImpact(BlockPos pos, Level level, Entity source, int updateFlags,
                                                   Multiset<SoundEvent> sounds,
                                                   CallbackInfoReturnable<Boolean> callback) {
        if (!ProtectionEvents.isExplosionBlockDamageAllowed(level, pos)) {
            callback.setReturnValue(false);
        }
    }
}
