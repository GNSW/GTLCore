/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.voxelregion.forge.NativeAccess;

@Mixin(NoiseChunk.class)
abstract class NoiseSamplesMixin implements NativeAccess.NoiseSamples {
    @org.spongepowered.asm.mixin.injection.ModifyArg(method = "<init>", index = 1,
            at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target =
                    "Lnet/minecraft/world/level/levelgen/Aquifer;create(Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/levelgen/NoiseRouter;Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;IILnet/minecraft/world/level/levelgen/Aquifer$FluidPicker;)Lnet/minecraft/world/level/levelgen/Aquifer;"))
    private net.minecraft.world.level.ChunkPos vre$privateBatchBounds(net.minecraft.world.level.ChunkPos original) {
        return org.voxelregion.forge.NoiseBatchScope.aquiferBounds(original);
    }

    @Shadow protected abstract BlockState getInterpolatedState();
    @Shadow protected abstract net.minecraft.world.level.biome.Climate.Sampler cachedClimateSampler(
            net.minecraft.world.level.levelgen.NoiseRouter router, java.util.List<net.minecraft.world.level.biome.Climate.ParameterPoint> targets);
    @Override public BlockState vre$interpolatedState() { return getInterpolatedState(); }
    @Override public net.minecraft.world.level.biome.Climate.Sampler vre$climateSampler(
            net.minecraft.world.level.levelgen.NoiseRouter router, java.util.List<net.minecraft.world.level.biome.Climate.ParameterPoint> targets) {
        return cachedClimateSampler(router, targets);
    }
}
