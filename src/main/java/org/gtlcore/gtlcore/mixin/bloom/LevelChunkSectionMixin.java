package org.gtlcore.gtlcore.mixin.bloom;

import org.gtlcore.gtlcore.client.bloom.SectionRevision;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionMixin implements SectionRevision {

    @Unique
    private volatile long gtlcore$bloomRevision;

    @Override
    public long gtlcore$bloomRevision() {
        return gtlcore$bloomRevision;
    }

    @Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"))
    private void gtlcore$bloom$changed(int x, int y, int z, BlockState state, boolean lock,
                                       CallbackInfoReturnable<BlockState> ci) {
        if (ci.getReturnValue() != state) gtlcore$bloomRevision++;
    }

    @Inject(method = "read", at = @At("RETURN"))
    private void gtlcore$bloom$read(CallbackInfo ci) {
        gtlcore$bloomRevision++;
    }
}
