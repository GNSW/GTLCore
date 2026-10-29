package org.gtlcore.gtlcore.mixin.gtm.worldgen;

import org.gtlcore.gtlcore.mixin.mc.BulkSectionAccessAccessor;

import com.gregtechceu.gtceu.api.data.worldgen.generator.indicators.SurfaceIndicatorGenerator;
import com.gregtechceu.gtceu.api.data.worldgen.ores.OreIndicatorPlacer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

/** Backport the placement-context correction from GTCEu PR #4883 without changing its 1.4.4 ABI. */
@Mixin(value = SurfaceIndicatorGenerator.class, remap = false)
public abstract class SurfaceIndicatorGeneratorMixin {

    @WrapMethod(method = "createPlacer", remap = false)
    private OreIndicatorPlacer gtlcore$bindPlacementRegion(WorldGenLevel generatingRegion, List<BlockPos> positions,
                                                           BlockState state, Operation<OreIndicatorPlacer> original) {
        // The cache outlives the generating WorldGenRegion. Resolve the region from each call's
        // own BulkSectionAccess instead; do not retain a region or a mutable position in the closure.
        // https://github.com/GregTechCEu/GregTech-Modern/pull/4883
        List<BlockPos> stablePositions = positions.stream().map(BlockPos::immutable).toList();
        return access -> {
            if (!(((BulkSectionAccessAccessor) access).gtlcore$placementLevel() instanceof WorldGenLevel placementRegion)) {
                throw new IllegalStateException("GT ore indicators require the current world-generation region");
            }
            // Keep GT's resolver, survival checks, ordering, early exits and writes unchanged.
            original.call(placementRegion, stablePositions, state).placeIndicators(access);
        };
    }
}
