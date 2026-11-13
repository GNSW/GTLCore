package org.gtlcore.gtlcore.api.pattern;

import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.jetbrains.annotations.Nullable;

/** Conservative bounds of every candidate layout, including cells not reached by a failed match. */
final class PatternRetryBounds {

    private PatternRetryBounds() {}

    /** Union of all fixed-layout candidates, not just the prefix visited by the last failed check. */
    @Nullable
    static LongSet fixedPositions(IMultiController controller, BlockPattern pattern) {
        if (pattern == null) return null;
        int[] repetitions = FixedPatternLayout.repetitions(pattern.aisleRepetitions);
        if (repetitions == null) return null;
        var accessor = (BlockPatternAccessor) pattern;
        var cells = accessor.gtlcore$blockMatches();
        int[] offset = accessor.gtlcore$centerOffset();
        if (repetitions.length != cells.length) return null;
        BlockPos origin = controller.self().getPos();
        LongSet positions = new LongOpenHashSet();
        positions.add(origin.asLong());
        Iterable<Direction> facings = controller.hasFrontFacing() ?
                java.util.List.of(controller.self().getFrontFacing()) : Direction.Plane.HORIZONTAL;
        for (Direction facing : facings) {
            for (int mirror = 0; mirror <= (controller.self().allowFlip() ? 1 : 0); mirror++) {
                for (int startZ = -offset[4]; startZ <= -offset[3]; startZ++) {
                    int z = startZ;
                    for (int aisle = 0; aisle < cells.length; aisle++) {
                        for (int repeat = 0; repeat < repetitions[aisle]; repeat++, z++) {
                            for (int y = 0; y < cells[aisle].length; y++) {
                                for (int x = 0; x < cells[aisle][y].length; x++) {
                                    if (cells[aisle][y][x].isAny()) continue;
                                    positions.add(origin.offset(accessor.gtlcore$setActualRelativeOffset(
                                            x - offset[0], y - offset[1], z, facing,
                                            controller.self().getUpwardsFacing(), mirror != 0)).asLong());
                                }
                            }
                        }
                    }
                }
            }
        }
        return positions;
    }

    static BoundingBox create(IMultiController controller, BlockPattern pattern) {
        BlockPos origin = controller.self().getPos();
        BoundingBox bounds = new BoundingBox(origin);
        // A controller may temporarily have no pattern while its mode/contents are being selected.
        if (pattern == null) return bounds;
        BlockPatternAccessor accessor = (BlockPatternAccessor) pattern;
        var cells = accessor.gtlcore$blockMatches();
        int[] offset = accessor.gtlcore$centerOffset();
        int maxDepth = 0;
        for (int[] repetition : pattern.aisleRepetitions) maxDepth += repetition[1];
        if (cells.length == 0 || cells[0].length == 0 || cells[0][0].length == 0 || maxDepth == 0) return bounds;
        int[] xs = { -offset[0], cells[0][0].length - 1 - offset[0] };
        int[] ys = { -offset[1], cells[0].length - 1 - offset[1] };
        int[] zs = { -offset[4], -offset[3] + maxDepth - 1 };
        Iterable<Direction> facings = controller.hasFrontFacing() ?
                java.util.List.of(controller.self().getFrontFacing()) : Direction.Plane.HORIZONTAL;
        for (Direction facing : facings) {
            for (int mirror = 0; mirror <= (controller.self().allowFlip() ? 1 : 0); mirror++) {
                for (int x : xs) {
                    for (int y : ys) {
                        for (int z : zs) {
                            bounds.encapsulate(origin.offset(accessor.gtlcore$setActualRelativeOffset(
                                    x, y, z, facing, controller.self().getUpwardsFacing(), mirror != 0)));
                        }
                    }
                }
            }
        }
        return bounds;
    }
}
