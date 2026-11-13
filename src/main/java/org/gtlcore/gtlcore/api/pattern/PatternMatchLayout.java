package org.gtlcore.gtlcore.api.pattern;

import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;

import net.minecraft.core.BlockPos;

/** World-independent, row-ordered cells of a built pattern, shared by its controllers. */
public final class PatternMatchLayout {

    private final Slice[] slices;

    public PatternMatchLayout(TraceabilityPredicate[][][] matches, int width, int height, int[] centerOffset) {
        slices = new Slice[matches.length];
        for (int aisle = 0; aisle < matches.length; aisle++) {
            int size = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (!matches[aisle][y][x].isAny()) size++;
                }
            }
            int[] xs = new int[size];
            int[] ys = new int[size];
            TraceabilityPredicate[] predicates = new TraceabilityPredicate[size];
            int index = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    TraceabilityPredicate predicate = matches[aisle][y][x];
                    if (predicate.isAny()) continue;
                    xs[index] = x - centerOffset[0];
                    ys[index] = y - centerOffset[1];
                    predicates[index++] = predicate;
                }
            }
            // The old loop also cleared errors when visiting trailing ANY cells, including an all-ANY slice.
            boolean trailingAny = height > 0 && width > 0 && matches[aisle][height - 1][width - 1].isAny();
            slices[aisle] = new Slice(xs, ys, predicates, trailingAny);
        }
    }

    public record Slice(int[] xs, int[] ys, TraceabilityPredicate[] predicates, boolean trailingAny) {

        public int size() {
            return predicates.length;
        }
    }

    /** Basis vectors come from GTCEu's transform once per orientation, including extended facing and flip. */
    public record Traversal(PatternMatchLayout layout, BlockPos origin, BlockPos basisX, BlockPos basisY,
                            BlockPos basisZ) {

        public Slice slice(int aisle) {
            return layout.slices[aisle];
        }

        public BlockPos position(int x, int y, int z) {
            return new BlockPos(
                    origin.getX() + basisX.getX() * x + basisY.getX() * y + basisZ.getX() * z,
                    origin.getY() + basisX.getY() * x + basisY.getY() * y + basisZ.getY() * z,
                    origin.getZ() + basisX.getZ() * x + basisY.getZ() * y + basisZ.getZ() * z);
        }
    }
}
