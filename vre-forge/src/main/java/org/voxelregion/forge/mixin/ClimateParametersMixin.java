/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.*;
import org.spongepowered.asm.mixin.*;
import org.voxelregion.forge.NativeAccess;

@Mixin(MultiNoiseBiomeSource.class)
abstract class ClimateParametersMixin implements NativeAccess.ClimateParameters {
    @Shadow private Climate.ParameterList<Holder<Biome>> parameters() { throw new AssertionError(); }
    @Override public Climate.ParameterList<Holder<Biome>> vre$climateParameters() { return parameters(); }
}
