/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.voxelregion.forge.ClearedGeneration;

/** Native neighbor decoration cannot regrow terrain after the materialization commit. Normal gameplay is untouched. */
@Mixin(WorldGenRegion.class)
abstract class ClearedTerrainDecorationMixin {
    @Shadow public abstract ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required);
    @Inject(method = "setBlock", at = @At("HEAD"), cancellable = true)
    private void vre$skipDecoration(BlockPos position, BlockState state, int flags, int recursion,
            CallbackInfoReturnable<Boolean> callback) {
        var chunk = getChunk(position.getX() >> 4, position.getZ() >> 4, ChunkStatus.EMPTY, false);
        if (chunk != null && ClearedGeneration.isCleared(chunk)) callback.setReturnValue(false);
    }
}
