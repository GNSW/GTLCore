package org.gtlcore.gtlcore.mixin.mc;

import org.gtlcore.gtlcore.config.ConfigHolder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Limit the final block-state progress, including blocks overriding the normal speed formula. */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockMiningTimeMixin {

    @Inject(method = "getDestroyProgress", at = @At("RETURN"), cancellable = true)
    private void gtlcore$limitMiningProgress(Player player, BlockGetter level, BlockPos pos,
                                             CallbackInfoReturnable<Float> cir) {
        int ticks = ConfigHolder.INSTANCE == null ? 0 : ConfigHolder.INSTANCE.gameplay.minimumBlockMiningTicks;
        if (ticks <= 0 || player.isCreative()) return;
        // The initial click counts towards progress; stay below 1 even for a one-tick setting.
        float limit = (float) Math.min(0.5, 1.0 / ticks);
        if (cir.getReturnValue() > limit) cir.setReturnValue(limit);
    }
}
