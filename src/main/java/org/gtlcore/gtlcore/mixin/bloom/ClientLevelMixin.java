package org.gtlcore.gtlcore.mixin.bloom;

import org.gtlcore.gtlcore.client.bloom.BloomClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Track received block-state changes independently of renderer/backend dirty hooks. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void gtlcore$bloom$state(BlockPos pos, BlockState state, int flags, int recursion, CallbackInfoReturnable<Boolean> ci) {
        if ((Object) this == Minecraft.getInstance().level && ci.getReturnValueZ())
            BloomClient.MESHES.dirtyBlock(pos.getX(), pos.getY(), pos.getZ());
    }
}
