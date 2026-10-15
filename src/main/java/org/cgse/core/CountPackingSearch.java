// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.Arrays;

/** Exact Boolean search inside one proposed packing core. The owner reserves
 * its bounded workspace; a closed core says nothing about the outside fixings. */
final class CountPackingSearch implements AutoCloseable {
    private static final int TAIL_BITS = 12;
    private final long[][] coefficients, thresholds, thresholdMasks, slacks, suffixMaximum;
    private final long[] undecided, selected, positive, negative;
    private final int[] choices;
    private int[] shifts;
    private int[][] buckets;
    private long indexMemory;
    private final boolean[] prepared;
    private final PlanningBudget budget;
    private final int size;
    private final long seed;
    private long pendingRows, forcedValues;
    private int depth;
    private boolean complete, feasible;
    private CountPackingTail tail;
    private long tailWork;
    private boolean tailAttempted, tailActive, tailQuery;

    /** Takes ownership of coefficients. All row activities must fit below 2^60. */
    CountPackingSearch(long[][] coefficients, long[] bounds, long seed, PlanningBudget budget) {
        if (coefficients.length == 0 || coefficients.length > 35 || coefficients.length != bounds.length)
            throw new IllegalArgumentException("Packing row count");
        size = coefficients[0].length;
        if (size < 1 || size > 64) throw new IllegalArgumentException("Packing core width");
        this.coefficients = coefficients;
        this.seed = seed;
        this.budget = budget;
        slacks = new long[size + 1][bounds.length];
        suffixMaximum = new long[bounds.length][size + 1];
        undecided = new long[size + 1];
        selected = new long[size + 1];
        choices = new int[size + 1];
        prepared = new boolean[size + 1];
        undecided[0] = size == 64 ? -1L : (1L << size) - 1;
        thresholds = new long[bounds.length][size];
        thresholdMasks = new long[bounds.length][size + 1];
        positive = new long[bounds.length];
        negative = new long[bounds.length];
        pendingRows = (1L << bounds.length) - 1;
        for (int r = 0; r < bounds.length; r++) {
            if (coefficients[r].length != size || bounds[r] <= -(1L << 60) || bounds[r] >= 1L << 60)
                throw new IllegalArgumentException("Packing row range");
            long magnitude = Math.abs(bounds[r]);
            slacks[0][r] = bounds[r];
            var sorted = new Integer[size];
            for (int i = 0; i < size; i++) {
                budget.check();
                long a = coefficients[r][i];
                if (a <= -(1L << 60) || a >= 1L << 60 || Math.abs(a) >= (1L << 60) - magnitude)
                    throw new IllegalArgumentException("Packing activity range");
                magnitude += Math.abs(a);
                slacks[0][r] -= Math.min(0, a);
                sorted[i] = i;
            }
            // Decisions consume a prefix of the core's compiled order. Forced
            // holes only make this maximum conservative, never too small.
            budget.operations(PlanningBudget.Operation.INTEGER, 64, size);
            for (int i = size - 1; i >= 0; i--)
                suffixMaximum[r][i] = Math.max(suffixMaximum[r][i + 1], Math.abs(coefficients[r][i]));
            final int row = r;
            Arrays.sort(sorted, (a, b) -> {
                budget.check();
                return Long.compare(Math.abs(coefficients[row][a]), Math.abs(coefficients[row][b]));
            });
            for (int i = size - 1; i >= 0; i--) {
                budget.check();
                int variable = sorted[i];
                thresholds[r][i] = Math.abs(coefficients[r][variable]);
                thresholdMasks[r][i] = thresholdMasks[r][i + 1] | (1L << variable);
                if (coefficients[r][variable] > 0) positive[r] |= 1L << variable;
                else if (coefficients[r][variable] < 0) negative[r] |= 1L << variable;
            }
        }
        buildIndex();
    }

    /** Bound the exact threshold search by its integer interval. The optional
     * table owns its reservation; declining it preserves the original search. */
    private void buildIndex() {
        long bytes = 64L + 576L * positive.length;
        if (!budget.tryReserve(bytes)) return;
        try {
            shifts = new int[positive.length];
            buckets = new int[positive.length][];
            for (int r = 0; r < positive.length; r++) {
                int shift = shifts[r] = Math.max(0, 64 - Long.numberOfLeadingZeros(thresholds[r][size - 1]) - 7);
                int count = (int) (thresholds[r][size - 1] >>> shift) + 1;
                var starts = buckets[r] = new int[count + 1];
                int at = 0;
                // At most 129 interval boundaries and 64 thresholds. Charge
                // this bounded pass once before filling the retained table.
                budget.operations(PlanningBudget.Operation.SCAN, 0, count + 1 + size);
                for (int b = 0; b <= count; b++) {
                    long limit = (long) b << shift;
                    while (at < size && thresholds[r][at] < limit) at++;
                    starts[b] = at;
                }
            }
            indexMemory = bytes;
        } catch (RuntimeException failure) {
            budget.release(bytes);
            throw failure;
        }
    }

    /** One retained node. Parent activities remain intact while its child propagates. */
    boolean step() {
        if (complete) return true;
        budget.checkpoint();
        boolean suffix = tail == null && !tailAttempted && depth >= 0 && size >= 2 * TAIL_BITS &&
                Long.numberOfTrailingZeros(undecided[depth]) >= size - TAIL_BITS;
        long before = suffix ? budget.threadWork() : 0;
        try { return advance(); }
        finally { if (tail == null && suffix) tailWork += budget.threadWork() - before; }
    }

    private boolean advance() {
        if (depth < 0) { complete = true; return true; }
        if (tailActive) {
            if (!tail.prepare()) return false;
            if (!tailQuery) {
                tail.begin(undecided[depth], selected[depth], slacks[depth]);
                tailQuery = true;
            }
            if (!tail.step()) return false;
            tailActive = tailQuery = false;
            if (tail.feasible()) {
                long prefix = (1L << (size - TAIL_BITS)) - 1;
                selected[depth] = (selected[depth] & prefix) | tail.assignment();
                feasible = complete = true;
                return true;
            }
            depth--;
            return false;
        }
        if (!prepared[depth]) {
            if (!propagate()) { depth--; return false; }
            prepared[depth] = true;
            if (undecided[depth] == 0) {
                feasible = complete = true;
                return true;
            }
            // Amortize a finite exact tail only after this same search has
            // already spent a measured warmup repeating suffix propagation.
            // Include the table depth in that estimate: admitting after just a
            // few repeats costs easy cores their low-budget witness. This is
            // a scheduling estimate; construction/query work is charged in full.
            if (size >= 2 * TAIL_BITS && Long.numberOfTrailingZeros(undecided[depth]) >= size - TAIL_BITS) {
                long warmup = (1L << TAIL_BITS) * positive.length * (TAIL_BITS + 1) * 2;
                if (!tailAttempted && tailWork >= warmup && budget.remainingWork() >= warmup) {
                    tailAttempted = true;
                    tail = CountPackingTail.create(coefficients, size - TAIL_BITS, budget);
                }
                if (tail != null) { tailActive = true; return false; }
            }
        }
        if (choices[depth] == 2) { depth--; return false; }
        int variable = Long.numberOfTrailingZeros(undecided[depth]);
        long bit = 1L << variable;
        boolean value = choices[depth]++ == 0 ? (seed & bit) != 0 : (seed & bit) == 0;
        pendingRows = 0;
        budget.operations(PlanningBudget.Operation.INTEGER, 64, positive.length);
        for (int r = 0; r < positive.length; r++) {
            long a = coefficients[r][variable];
            long loss = value ? Math.max(0, a) : Math.max(0, -a);
            slacks[depth + 1][r] = slacks[depth][r] - loss;
            if (loss != 0) pendingRows |= 1L << r;
        }
        undecided[depth + 1] = undecided[depth] & ~bit;
        selected[depth + 1] = selected[depth] | (value ? bit : 0);
        depth++;
        choices[depth] = 0;
        prepared[depth] = false;
        return false;
    }

    private boolean propagate() {
        for (;;) {
            long forceZero = 0, forceOne = 0;
            while (pendingRows != 0) {
                int r = Long.numberOfTrailingZeros(pendingRows);
                pendingRows &= pendingRows - 1;
                budget.operation(PlanningBudget.Operation.SCAN, 0);
                long slack = slacks[depth][r];
                if (slack < 0) return false;
                if (slack >= suffixMaximum[r][Math.min(size, Long.numberOfTrailingZeros(undecided[depth]))]) continue;
                int low = 0, high = size, scans = 0;
                if (buckets != null) {
                    int bucket = (int) (slack >>> shifts[r]);
                    high = buckets[r][bucket + 1];
                    // Unit-width intervals already identify the exact upper
                    // bound, including coefficients equal to the current slack.
                    low = shifts[r] == 0 ? high : buckets[r][bucket];
                    scans = shifts[r] == 0 ? 1 : 2;
                }
                while (low < high) {
                    scans++;
                    int mid = (low + high) >>> 1;
                    if (thresholds[r][mid] <= slack) low = mid + 1;
                    else high = mid;
                }
                budget.operations(PlanningBudget.Operation.SCAN, 0, scans);
                // All coefficients strictly larger than this row's slack
                // force their favorable value. Already assigned bits are gone.
                long forced = thresholdMasks[r][low] & undecided[depth];
                forceZero |= forced & positive[r];
                forceOne |= forced & negative[r];
                if ((forceZero & forceOne) != 0) return false;
            }
            long forced = forceZero | forceOne;
            if (forced == 0) return true;
            forcedValues += Long.bitCount(forced);
            budget.operations(PlanningBudget.Operation.SCAN, 0, positive.length);
            for (int r = 0; r < positive.length; r++) {
                long adverse = (forceOne & positive[r]) | (forceZero & negative[r]);
                if (adverse != 0) pendingRows |= 1L << r;
                if (adverse != 0) budget.operations(PlanningBudget.Operation.INTEGER, 64, Long.bitCount(adverse));
                while (adverse != 0) {
                    int variable = Long.numberOfTrailingZeros(adverse);
                    adverse &= adverse - 1;
                    slacks[depth][r] -= Math.abs(coefficients[r][variable]);
                }
            }
            selected[depth] |= forceOne;
            undecided[depth] &= ~forced;
        }
    }

    boolean feasible() { return feasible; }
    long assignment() {
        if (!feasible) throw new IllegalStateException("No packing witness");
        return selected[depth];
    }
    long forcedValues() { return forcedValues; }
    @Override public void close() {
        if (tail != null) tail.close();
        tail = null;
        buckets = null; shifts = null;
        budget.release(indexMemory); indexMemory = 0;
    }
}
