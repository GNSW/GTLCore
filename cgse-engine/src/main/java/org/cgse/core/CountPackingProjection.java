// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

/** Exact two-row relaxations of an immutable point set. One common axis orders
 * every point; prefix minima answer all other row projections together. A miss
 * excludes this point set only, never the original model or a different face. */
final class CountPackingProjection implements AutoCloseable {
    private static final int BLOCK = 8;
    private final CountPackingValues values, minimum;
    private final long[] activity;
    private final PlanningBudget budget;
    private final int points, dimensions, axis;
    private int[] order, scratch;
    private int width = 1, start, a, b, middle, end, cursor, filled, preferred;
    private boolean merging, sorted, ready;
    private long memory;

    static CountPackingProjection create(CountPackingValues values, int points, int dimensions,
                                         int axis, boolean compact, PlanningBudget budget) {
        if (points < 1 || points > 4096 || dimensions < 1 || dimensions > 35 || axis < 0 || axis >= dimensions)
            throw new IllegalArgumentException("Packing projection shape");
        long bytes = 512L + 8L * dimensions + 8L * points + ((points + BLOCK - 1L) / BLOCK) * (compact ? 4L : 8L) * dimensions;
        if (!budget.tryReserve(bytes)) return null;
        try { return new CountPackingProjection(values, points, dimensions, axis, compact, budget, bytes); }
        catch (RuntimeException failure) { budget.release(bytes); throw failure; }
    }

    private CountPackingProjection(CountPackingValues values, int points, int dimensions, int axis,
                                   boolean compact, PlanningBudget budget, long memory) {
        this.values = values; this.points = points; this.dimensions = dimensions; this.axis = axis;
        this.budget = budget; this.memory = memory; preferred = axis;
        order = new int[points]; scratch = new int[points];
        minimum = new CountPackingValues(((points + BLOCK - 1) / BLOCK) * dimensions, compact);
        activity = new long[dimensions];
        budget.operations(PlanningBudget.Operation.SCAN, 0, points);
        for (int i = 0; i < points; i++) order[i] = i;
    }

    /** Sorting resumes after at most 128 moves; prefix construction after one point. */
    boolean prepare() {
        budget.checkpoint();
        if (ready) return true;
        if (!sorted) {
            if (width >= points) { sorted = true; return false; }
            if (!merging) {
                a = start; b = middle = Math.min(points, start + width);
                end = Math.min(points, middle + width); cursor = start; merging = true;
            }
            int moves = Math.min(128, end - cursor), comparisons = 0;
            for (int i = 0; i < moves; i++) {
                boolean first = b == end;
                if (a < middle && b < end) {
                    comparisons++;
                    first = values.get(order[a] * dimensions + axis) <= values.get(order[b] * dimensions + axis);
                }
                scratch[cursor++] = first ? order[a++] : order[b++];
            }
            // No sorted result is published until these exact moves/comparisons
            // are paid. Exhaustion cancels the private construction as a whole.
            budget.operations(PlanningBudget.Operation.SCAN, 0, moves + comparisons);
            if (cursor == end) {
                merging = false; start = end;
                if (start == points) {
                    var swap = order; order = scratch; scratch = swap;
                    width *= 2; start = 0;
                }
            }
            return false;
        }
        if (filled == points) return ready = true;
        budget.operations(PlanningBudget.Operation.INTEGER, 64, dimensions);
        int source = order[filled] * dimensions, destination = (filled / BLOCK) * dimensions;
        boolean boundary = (filled + 1) % BLOCK == 0 || filled + 1 == points;
        for (int r = 0; r < dimensions; r++) {
            long value = values.get(source + r);
            activity[r] = filled == 0 ? value : Math.min(value, activity[r]);
            if (boundary) minimum.set(destination + r, activity[r]);
        }
        filled++;
        return false;
    }

    /** Fixed coordinates may further restrict the caller. Ignoring them here
     * is a relaxation: a passing projection is never a feasibility witness. */
    boolean rejects(long[] query) {
        if (!ready || query.length != dimensions) throw new IllegalStateException("Unprepared packing projection");
        int low = 0, high = points, comparisons = 0;
        while (low < high) {
            comparisons++;
            int middle = (low + high) >>> 1;
            if (values.get(order[middle] * dimensions + axis) <= query[axis]) low = middle + 1;
            else high = middle;
        }
        budget.operations(PlanningBudget.Operation.SCAN, 0, comparisons);
        if (low == 0) return true;
        int rejected = low % BLOCK == 0 || low == points ?
                CountPackingQuery.reject(minimum, ((low - 1) / BLOCK) * dimensions, query, preferred, budget) :
                rejectPartial(query, low);
        if (rejected < 0) return false;
        preferred = rejected;
        return true;
    }

    /** Whole-block minima are exact. Only the final, incomplete block needs
     * point comparisons, and only for rows not already satisfied by an earlier
     * point. This preserves the complete two-row relaxation with less storage. */
    private int rejectPartial(long[] query, int end) {
        int block = end / BLOCK, first = block * BLOCK, checks = 0, rejected = -1;
        for (int at = dimensions; at >= 0; at--) {
            int r = at == dimensions ? preferred : at;
            if (at != dimensions && r == preferred) continue;
            boolean supported = false;
            if (block > 0) { checks++; supported = minimum.get((block - 1) * dimensions + r) <= query[r]; }
            if (!supported) for (int p = first; p < end; p++) {
                checks++;
                if (values.get(order[p] * dimensions + r) <= query[r]) { supported = true; break; }
            }
            if (!supported) { rejected = r; break; }
        }
        budget.operations(PlanningBudget.Operation.SCAN, 0, checks);
        return rejected;
    }

    @Override public void close() { budget.release(memory); memory = 0; }
}
