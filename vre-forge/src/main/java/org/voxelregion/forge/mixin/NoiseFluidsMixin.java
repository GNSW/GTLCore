/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import java.util.function.Supplier;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.voxelregion.forge.NativeAccess;

@Mixin(NoiseBasedChunkGenerator.class)
abstract class NoiseFluidsMixin implements NativeAccess.NoiseFluids {
    @Shadow @Final private Supplier<Aquifer.FluidPicker> globalFluidPicker;
    @Override public Aquifer.FluidPicker vre$fluidPicker() { return globalFluidPicker.get(); }
}
