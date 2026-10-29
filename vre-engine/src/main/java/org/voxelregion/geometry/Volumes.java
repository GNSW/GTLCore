/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion.geometry;

import java.util.Objects;

/**
 * Immutable binary set operations on the voxel selections of immutable Volumes.
 * Inputs keep their own exact coordinate and clipping semantics. Classifiers
 * provide sound INSIDE/OUTSIDE proofs; PARTIAL can be conservative for combined
 * shapes. No bounds-sized bitmap, voxel list or list of emitted ranges is built.
 *
 * <p>Range emission works with arbitrary input range ordering. It streams the
 * first volume and filters a selected column by classifying its Y intervals.
 * Boundary intervals split until classification resolves them, with contains
 * as the exact single-voxel fallback. Auxiliary recursion is bounded by the
 * supported world height (at most 26 subdivisions per column), in addition to
 * the caller's composition depth. A classifier that always returns PARTIAL may
 * require voxel checks; this API does not imply constant runtime for arbitrary
 * user-defined volumes. Adjacent emitted runs need not be merged.
 */
public final class Volumes {
    private Volumes() { }

    /** Selects either input, emitting every selected voxel exactly once. */
    public static Volume union(Volume first, Volume second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first == second || second.bounds().isEmpty()) return first;
        if (first.bounds().isEmpty()) return second;
        IntBox a = first.bounds(), b = second.bounds();
        return new Combination(first, second, Operation.UNION,
                new IntBox(Math.min(a.minX(), b.minX()), Math.min(a.minY(), b.minY()), Math.min(a.minZ(), b.minZ()),
                        Math.max(a.maxX(), b.maxX()), Math.max(a.maxY(), b.maxY()), Math.max(a.maxZ(), b.maxZ())));
    }

    /** Selects voxels present in both inputs. */
    public static Volume intersection(Volume first, Volume second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first == second) return first;
        IntBox bounds = first.bounds().intersection(second.bounds());
        return bounds.isEmpty() ? new BoxVolume(bounds) : new Combination(first, second, Operation.INTERSECTION, bounds);
    }

    /** Selects the first input with every voxel of the second input removed. */
    public static Volume difference(Volume first, Volume second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        IntBox bounds = first.bounds();
        if (first == second) {
            return new BoxVolume(new IntBox(bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.minX(), bounds.minY(), bounds.minZ()));
        }
        if (!bounds.intersects(second.bounds())) return first;
        return new Combination(first, second, Operation.DIFFERENCE, bounds);
    }

    private enum Operation { UNION, INTERSECTION, DIFFERENCE }

    private static final class Combination implements Volume {
        private final Volume first;
        private final Volume second;
        private final Operation operation;
        private final IntBox bounds;

        private Combination(Volume first, Volume second, Operation operation, IntBox bounds) {
            this.first = first;
            this.second = second;
            this.operation = operation;
            this.bounds = bounds;
        }

        @Override public IntBox bounds() { return bounds; }

        @Override
        public boolean contains(int x, int y, int z) {
            if (!bounds.contains(x, y, z)) return false;
            return switch (operation) {
                case UNION -> first.contains(x, y, z) || second.contains(x, y, z);
                case INTERSECTION -> first.contains(x, y, z) && second.contains(x, y, z);
                case DIFFERENCE -> first.contains(x, y, z) && !second.contains(x, y, z);
            };
        }

        @Override
        public Coverage classify(IntBox box) {
            Objects.requireNonNull(box, "box");
            if (!bounds.intersects(box)) return Coverage.OUTSIDE;
            Coverage a = first.classify(box);
            if (operation == Operation.UNION && a == Coverage.INSIDE) return Coverage.INSIDE;
            if (operation != Operation.UNION && a == Coverage.OUTSIDE) return Coverage.OUTSIDE;
            Coverage b = second.classify(box);
            return switch (operation) {
                case UNION -> a == Coverage.INSIDE || b == Coverage.INSIDE ? Coverage.INSIDE
                        : a == Coverage.OUTSIDE && b == Coverage.OUTSIDE ? Coverage.OUTSIDE : Coverage.PARTIAL;
                case INTERSECTION -> a == Coverage.OUTSIDE || b == Coverage.OUTSIDE ? Coverage.OUTSIDE
                        : a == Coverage.INSIDE && b == Coverage.INSIDE ? Coverage.INSIDE : Coverage.PARTIAL;
                case DIFFERENCE -> a == Coverage.OUTSIDE || b == Coverage.INSIDE ? Coverage.OUTSIDE
                        : a == Coverage.INSIDE && b == Coverage.OUTSIDE ? Coverage.INSIDE : Coverage.PARTIAL;
            };
        }

        @Override
        public void emitRanges(IntBox clip, RangeSink sink) {
            Objects.requireNonNull(sink, "sink");
            IntBox clipped = bounds.intersection(Objects.requireNonNull(clip, "clip"));
            if (clipped.isEmpty()) return;
            Coverage coverage = classify(clipped);
            if (coverage == Coverage.OUTSIDE) return;
            if (coverage == Coverage.INSIDE) {
                ExactGeometry.emitBox(clipped, sink);
                return;
            }
            switch (operation) {
                case UNION -> {
                    first.emitRanges(clipped, sink);
                    second.emitRanges(clipped,
                            (x, z, lo, hi) -> emitFiltered(first, false, x, z, lo, hi, sink));
                }
                case INTERSECTION -> first.emitRanges(clipped,
                        (x, z, lo, hi) -> emitFiltered(second, true, x, z, lo, hi, sink));
                case DIFFERENCE -> first.emitRanges(clipped,
                        (x, z, lo, hi) -> emitFiltered(second, false, x, z, lo, hi, sink));
            }
        }
    }

    private static void emitFiltered(Volume tester, boolean keepInside, int x, int z,
            int lo, int hi, RangeSink sink) {
        if (lo >= hi) return;
        Coverage coverage = tester.classify(new IntBox(x, lo, z, x + 1, hi, z + 1));
        if (coverage != Coverage.PARTIAL) {
            if ((coverage == Coverage.INSIDE) == keepInside) sink.accept(x, z, lo, hi);
            return;
        }
        if (hi - lo == 1) {
            if (tester.contains(x, lo, z) == keepInside) sink.accept(x, z, lo, hi);
            return;
        }
        int middle = lo + (hi - lo) / 2;
        emitFiltered(tester, keepInside, x, z, lo, middle, sink);
        emitFiltered(tester, keepInside, x, z, middle, hi, sink);
    }
}
