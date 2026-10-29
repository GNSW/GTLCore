/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.voxelregion.forge.NoiseHashMemo;

/** Structural equals/hash semantics stay native. This does not replace the noise graph's deduplication map. */
@Mixin(targets = {"net.minecraft.util.CubicSpline$Multipoint", "net.minecraft.world.level.levelgen.DensityFunctions$MulOrAdd",
        "net.minecraft.world.level.levelgen.DensityFunctions$Spline$Coordinate"})
abstract class NoiseHashMemoMixin {
    @Inject(method = "hashCode", remap = false, at = @At("HEAD"), cancellable = true)
    private void vre$reusePrivateHash(CallbackInfoReturnable<Integer> callback) {
        Integer cached = NoiseHashMemo.lookup(this);
        if (cached != null) callback.setReturnValue(cached);
    }

    @Inject(method = "hashCode", remap = false, at = @At("RETURN"))
    private void vre$rememberPrivateHash(CallbackInfoReturnable<Integer> callback) {
        NoiseHashMemo.remember(this, callback.getReturnValueI());
    }
}
