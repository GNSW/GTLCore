/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.voxelregion.forge.runtime.ChunkMutationTracking;

/** Observe ordinary player, engine and mod writes without replacing native behavior. */
@Mixin(LevelChunk.class)
public abstract class ChunkMutationMixin implements org.voxelregion.forge.runtime.RuntimeBridge.MutationTracked,
        org.voxelregion.forge.NativeAccess.ChunkRevision, org.voxelregion.forge.NativeAccess.ChunkPostProcessing {
    @Unique private UpgradeData vre$processingUpgrade;
    @Unique private UpgradeData vre$completedUpgrade;
    @Unique private int vre$postProcessingDepth;

    @Override
    public long vre$mutationRevision() {
        return ChunkMutationTracking.revision((LevelChunk) (Object) this);
    }

    @Override
    public boolean vre$completedUpgrade(UpgradeData upgrade) {
        return vre$postProcessingDepth == 0 && upgrade != null && vre$completedUpgrade == upgrade;
    }

    @Inject(method = "postProcessGeneration", at = @At("HEAD"))
    private void voxelregion$beginPostProcessing(CallbackInfo callback) {
        if (vre$postProcessingDepth++ == 0)
            vre$processingUpgrade = ((LevelChunk) (Object) this).getUpgradeData();
        vre$completedUpgrade = null;
    }

    @Inject(method = "postProcessGeneration", at = @At("RETURN"))
    private void voxelregion$completePostProcessing(CallbackInfo callback) {
        if (--vre$postProcessingDepth != 0) return;
        UpgradeData current = ((LevelChunk) (Object) this).getUpgradeData();
        // An already-completed ticking future does not prove that subsequently installed data
        // was processed. Only this successful native call can publish the matching identity.
        vre$completedUpgrade = current == vre$processingUpgrade ? current : null;
        vre$processingUpgrade = null;
    }

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void voxelregion$beforeMutation(BlockPos position, BlockState requested, boolean moving,
                                             CallbackInfoReturnable<BlockState> result) {
        // An external onRemove callback may reenter saving before RETURN.
        ChunkMutationTracking.changing((LevelChunk) (Object) this);
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void voxelregion$recordMutation(BlockPos position, BlockState requested, boolean moving,
                                             CallbackInfoReturnable<BlockState> result) {
        BlockState old = result.getReturnValue();
        if (old != null && old != requested) ChunkMutationTracking.changed((LevelChunk) (Object) this);
    }
}
