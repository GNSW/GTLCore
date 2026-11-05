package org.gtlcore.gtlcore.mixin.mc;

import org.gtlcore.gtlcore.common.data.GTLPlushies;

import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.class)
public abstract class BlockRenderShapeMixin {

    @Inject(method = "getRenderShape", at = @At("HEAD"), cancellable = true)
    private void gtlcore$animatedExtendedFishbig(BlockState state, CallbackInfoReturnable<RenderShape> callback) {
        if (GTLPlushies.isExtendedFishbig(state.getBlock())) {
            callback.setReturnValue(RenderShape.ENTITYBLOCK_ANIMATED);
        }
    }
}
