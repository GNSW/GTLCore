/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.lighting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic exact covering of dirty columns by disjoint output rectangles.
 * Contiguous row runs are split at the width limit; matching runs on consecutive
 * rows are extended up to the depth limit. No alignment padding or clean output
 * columns are introduced. This is a bounded heuristic, not a minimum-cover solver.
 *
 * <p>The caller chooses limits to fit its height, working budget and parallelism.
 * Every rectangle samples a one-column halo, which may overlap other jobs' read
 * domains. Only the output rectangles are disjoint. Terrain lifetime, revision
 * validation, publication and cache reuse remain the host's responsibility.
 */
public final class LightBatchPlanner {
    public record Column(int x, int z) { }

    /** Output origin and extent in columns, excluding its one-column sampling halo. */
    public record Rect(int x, int z, int width, int depth) {
        public Rect {
            dimensions(width, depth);
            if (x == Integer.MIN_VALUE || z == Integer.MIN_VALUE
                    || (long)x + width > Integer.MAX_VALUE || (long)z + depth > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Rectangle sampling halo exceeds signed coordinates");
        }
        public int sampleMinX() { return x - 1; }
        public int sampleMinZ() { return z - 1; }
        public int sampleWidth() { return width + 2; }
        public int sampleDepth() { return depth + 2; }

        /**
         * Conservative field working estimate including the halo, in bytes, using
         * GlobalLightField's shared bound, including bounded queues and spill storage.
         * This estimate does not assert that the solver's dimension/cell limits
         * admit the domain, or that simultaneous jobs fit a shared memory budget.
         */
        public long estimatedWorkingBytes(int height) {
            if (height <= 0 || height % 16 != 0)
                throw new IllegalArgumentException("Height must be a positive multiple of 16");
            try {
                return GlobalLightField.estimatedWorkingBytes(
                        Math.multiplyExact((long)sampleWidth(), 16L), height,
                        Math.multiplyExact((long)sampleDepth(), 16L));
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Optical working estimate exceeds signed bytes", overflow);
            }
        }
    }

    private record Span(int x, int width) { }
    private static final class OpenRect {
        final int x, z, width;
        int depth = 1;
        OpenRect(Span span, int z) { x = span.x; width = span.width; this.z = z; }
        Rect finish() { return new Rect(x, z, width, depth); }
    }

    private LightBatchPlanner() { }

    /**
     * Returns an immutable list ordered by output origin z, then x. Duplicate
     * inputs are ignored. A column whose halo cannot be expressed is rejected;
     * the planner never silently skips an input or wraps a coordinate.
     */
    public static List<Rect> plan(Collection<Column> dirty, int maxWidth, int maxDepth) {
        Objects.requireNonNull(dirty, "dirty");
        dimensions(maxWidth, maxDepth);
        TreeMap<Integer, TreeSet<Integer>> rows = new TreeMap<>();
        for (Column column : dirty) {
            Objects.requireNonNull(column, "dirty column");
            if (column.x == Integer.MIN_VALUE || column.x == Integer.MAX_VALUE
                    || column.z == Integer.MIN_VALUE || column.z == Integer.MAX_VALUE)
                throw new IllegalArgumentException("Column sampling halo exceeds signed coordinates");
            rows.computeIfAbsent(column.z, ignored -> new TreeSet<>()).add(column.x);
        }
        List<Rect> result = new ArrayList<>();
        Map<Span, OpenRect> active = new HashMap<>();
        long previousZ = Long.MIN_VALUE;
        for (var row : rows.entrySet()) {
            int z = row.getKey();
            if (previousZ + 1L != z) {
                for (OpenRect rectangle : active.values()) result.add(rectangle.finish());
                active.clear();
            }
            Map<Span, OpenRect> next = new HashMap<>();
            for (Span span : spans(row.getValue(), maxWidth)) {
                OpenRect rectangle = active.remove(span);
                if (rectangle == null) rectangle = new OpenRect(span, z);
                else if (rectangle.depth < maxDepth) rectangle.depth++;
                else {
                    result.add(rectangle.finish());
                    rectangle = new OpenRect(span, z);
                }
                next.put(span, rectangle);
            }
            for (OpenRect rectangle : active.values()) result.add(rectangle.finish());
            active = next;
            previousZ = z;
        }
        for (OpenRect rectangle : active.values()) result.add(rectangle.finish());
        result.sort(Comparator.comparingInt(Rect::z).thenComparingInt(Rect::x));
        return List.copyOf(result);
    }

    private static List<Span> spans(TreeSet<Integer> row, int maxWidth) {
        List<Span> result = new ArrayList<>();
        int start = 0, previous = 0, width = 0;
        for (int x : row) {
            if (width != 0 && ((long)previous + 1L != x || width == maxWidth)) {
                result.add(new Span(start, width));
                width = 0;
            }
            if (width == 0) start = x;
            width++;
            previous = x;
        }
        if (width != 0) result.add(new Span(start, width));
        return result;
    }

    private static void dimensions(int width, int depth) {
        if (width <= 0 || depth <= 0 || width > Integer.MAX_VALUE - 2 || depth > Integer.MAX_VALUE - 2)
            throw new IllegalArgumentException("Positive dimensions with a representable halo are required");
    }
}
