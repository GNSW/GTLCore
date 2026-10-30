package com.wcwen.gtmbsockets.mixin;

import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Read-only access to the immutable parts of a GregTech {@link BlockPattern}.
 *
 * <p>{@code blockMatches} and {@code centerOffset} are protected/private implementation details,
 * but the world-space mapping itself is the exact same private method used by
 * {@code BlockPattern#checkPatternAt}.  Calling it instead of copying the facing/upside-down
 * arithmetic keeps the preview aligned with GregTech even for flipped and non-Y-axis machines.
 */
@Mixin(value = BlockPattern.class, remap = false)
public interface BlockPatternAccessor {

    @Accessor(value = "blockMatches", remap = false)
    TraceabilityPredicate[][][] gtmbsockets$blockMatches();

    @Accessor(value = "centerOffset", remap = false)
    int[] gtmbsockets$centerOffset();

    @Invoker(value = "setActualRelativeOffset", remap = false)
    BlockPos gtmbsockets$setActualRelativeOffset(int x, int y, int z,
                                                 Direction facing,
                                                 Direction upwardsFacing,
                                                 boolean isFlipped);
}
