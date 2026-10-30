package org.gtlcore.gtlcore.api.pattern;

import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Read-only view of the package-private data a {@link com.gregtechceu.gtceu.api.pattern.BlockPattern}
 * uses while matching a structure.
 *
 * <p>Implemented by {@code BlockPatternMixin}.  The socket preview uses the same offsets and the
 * same {@code setActualRelativeOffset} call as GregTech itself, so previews stay aligned for
 * flipped and non-Y-axis multiblocks.
 */
public interface BlockPatternAccessor {

    TraceabilityPredicate[][][] gtlcore$blockMatches();

    int[] gtlcore$centerOffset();

    BlockPos gtlcore$setActualRelativeOffset(int x, int y, int z,
                                             Direction facing,
                                             Direction upwardsFacing,
                                             boolean isFlipped);
}
