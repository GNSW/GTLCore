package org.gtlcore.gtlcore.mixin.mc;

import org.gtlcore.gtlcore.api.pattern.INewMultiblockWorldSavedData;
import org.gtlcore.gtlcore.common.item.manipulator.ManipulatorUndo;

import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import com.gregtechceu.gtceu.api.pattern.MultiblockWorldSavedData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMixin {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void gtlcore$captureManipulatorEdit(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerLevel level) ManipulatorUndo.captureBlockChange(level, pos);
    }

    @SuppressWarnings("ConstantValue")
    @Inject(method = "markAndNotifyBlock",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/level/Level;setBlocksDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V",
                     remap = true),
            remap = false)
    private void gtceu$updateChunkMultiBlocks(BlockPos pos, LevelChunk chunk,
                                              BlockState oldState, BlockState newState, int flags, int recursionLeft,
                                              CallbackInfo ci) {
        if (!(((Object) this) instanceof ServerLevel serverLevel)) return;

        MultiblockWorldSavedData mwsd = MultiblockWorldSavedData.getOrCreate(serverLevel);
        for (MultiblockState structure : ((INewMultiblockWorldSavedData) mwsd).getControllersInChunk(chunk.getPos())) {
            if (structure.getController() == null || !structure.getController().isFormed()) {
                continue;
            }
            if (structure.isPosInCache(pos)) {
                serverLevel.getServer().execute(() -> structure.onBlockStateChanged(pos, newState));
            }
        }
    }
}
