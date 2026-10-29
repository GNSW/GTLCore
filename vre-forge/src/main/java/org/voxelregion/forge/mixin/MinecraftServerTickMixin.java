/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.voxelregion.forge.ServerTickTimings;

@Mixin(MinecraftServer.class)
abstract class MinecraftServerTickMixin {
    @Unique private long vre$tickStart;
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void vre$start(BooleanSupplier running, CallbackInfo callback) {
        vre$tickStart = System.nanoTime(); ServerTickTimings.beginTick(vre$tickStart);
    }
    @Inject(method = "tickServer", at = @At("RETURN"))
    private void vre$finish(BooleanSupplier running, CallbackInfo callback) { ServerTickTimings.record(System.nanoTime() - vre$tickStart); }
}
