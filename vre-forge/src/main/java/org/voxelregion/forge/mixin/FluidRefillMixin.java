/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.voxelregion.forge.runtime.RuntimeBridge;

/** Only an active clearFluids/no-physics operation owns this temporary exclusion. */
@Mixin(FlowingFluid.class)
abstract class FluidRefillMixin {
    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void vre$keepCompletedCellsClear(LevelAccessor world, BlockPos position, BlockState old,
            Direction direction, FluidState incoming, CallbackInfo callback) {
        if (world instanceof ServerLevel level && RuntimeBridge.suppressFluidRefill(level, position)) callback.cancel();
    }
}
