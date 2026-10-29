package org.gtlcore.gtlcore.mixin.mc.worldgen;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

/** Vanilla jungle temples and strongholds share mutable block selectors between placements. */
@Mixin(StructurePiece.class)
public abstract class StructureSelectorMixin {

    @WrapMethod(method = "generateBox(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;IIIIIIZLnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/structure/StructurePiece$BlockSelector;)V")
    private void gtlcore$keepSelectorResultWithItsPlacement(WorldGenLevel level, BoundingBox bounds,
                                                            int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                                            boolean skipAir, RandomSource random,
                                                            StructurePiece.BlockSelector selector,
                                                            Operation<Void> original) {
        // Lock the next/getNext pair through its original loop, without consuming extra random values.
        synchronized (selector) {
            original.call(level, bounds, minX, minY, minZ, maxX, maxY, maxZ, skipAir, random, selector);
        }
    }
}
