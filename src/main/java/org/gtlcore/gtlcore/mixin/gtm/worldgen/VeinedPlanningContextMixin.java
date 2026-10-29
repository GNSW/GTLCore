package org.gtlcore.gtlcore.mixin.gtm.worldgen;

import org.gtlcore.gtlcore.common.worldgen.ParallelOrePlanning;

import com.gregtechceu.gtceu.api.data.worldgen.generator.veins.VeinedVeinGenerator;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.levelgen.RandomState;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = VeinedVeinGenerator.class, remap = false)
public abstract class VeinedPlanningContextMixin {

    @WrapOperation(method = "generate",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/server/level/ServerChunkCache;randomState()Lnet/minecraft/world/level/levelgen/RandomState;",
                            remap = true),
                   require = 1)
    private RandomState gtlcore$capturedNoiseState(ServerChunkCache cache, Operation<RandomState> original) {
        RandomState captured = ParallelOrePlanning.capturedRandomState();
        return captured == null ? original.call(cache) : captured;
    }
}
