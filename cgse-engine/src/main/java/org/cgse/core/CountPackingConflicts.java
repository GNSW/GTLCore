// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

/**
 * Forbidden Boolean conjunctions from the same private packing face. Bit
 * incidence removes satisfied clauses together; each DFS level retains its own
 * eligible set, so backtracking needs neither rebuilding nor copying clauses.
 */
final class CountPackingConflicts implements AutoCloseable {
    private static final int CAPACITY = 64;
    private final PlanningBudget budget;
    private final long[] masks = new long[CAPACITY], ones = new long[CAPACITY];
    private final long[] rejectZero, rejectOne, eligible;
    private long memory, hits, forced;
    private int count;

    static CountPackingConflicts create(CountCdcl source, int size, int depth, long seed,
                                        long[] undecided, long[] selected, int[] choices,
                                        PlanningBudget budget) {
        // Includes the bounded clause-selection scratch and all incidence/trail
        // arrays. An optional refusal leaves the paid enumeration untouched.
        long bytes = 8192L + 48L * (size + 1);
        if (depth < 0 || !budget.tryReserve(bytes)) return null;
        CountPackingConflicts result = null;
        try {
            result = new CountPackingConflicts(size, budget, bytes);
            bytes = 0;
            result.count = source.copyBooleanConflicts(size, result.masks, result.ones);
            if (result.count == 0) return null;
            for (int c = 0; c < result.count; c++) {
                long pending = result.masks[c];
                while (pending != 0) {
                    budget.check();
                    int id = Long.numberOfTrailingZeros(pending);
                    pending &= pending - 1;
                    if ((result.ones[c] & (1L << id)) != 0) result.rejectZero[id] |= 1L << c;
                    else result.rejectOne[id] |= 1L << c;
                }
            }
            long variables = size == 64 ? -1L : (1L << size) - 1;
            long all = result.count == 64 ? -1L : (1L << result.count) - 1;
            long useful = 0;
            for (int d = 0; d <= depth; d++) {
                long live = all, fixed = variables & ~undecided[d];
                while (fixed != 0 && live != 0) {
                    budget.check();
                    int id = Long.numberOfTrailingZeros(fixed);
                    fixed &= fixed - 1;
                    live &= ~((selected[d] & (1L << id)) == 0 ? result.rejectZero[id] : result.rejectOne[id]);
                }
                result.eligible[d] = live;
                // The remaining frontier is the current subtree plus each
                // unvisited sibling. Clauses incompatible with all of those
                // boxes can never help this continuation; omit them entirely.
                if (choices[d] == 0) useful |= live;
                else if (choices[d] == 1 && undecided[d] != 0) {
                    int id = Long.numberOfTrailingZeros(undecided[d]);
                    useful |= live & ~((seed & (1L << id)) != 0 ? result.rejectZero[id] : result.rejectOne[id]);
                }
            }
            if (useful == 0) return null;
            for (int d = 0; d <= depth; d++) { budget.check(); result.eligible[d] &= useful; }
            budget.note("count_packing_conflicts", "retained=" + Long.bitCount(useful) + "; selected=" + result.count);
            var accepted = result;
            result = null;
            return accepted;
        } finally {
            if (result != null) result.close();
            budget.release(bytes);
        }
    }

    private CountPackingConflicts(int size, PlanningBudget budget, long memory) {
        this.budget = budget; this.memory = memory;
        rejectZero = new long[size]; rejectOne = new long[size]; eligible = new long[size + 1];
    }

    void branch(int depth, int variable, boolean value) {
        long live = eligible[depth];
        if (live != 0) budget.check();
        eligible[depth + 1] = live & ~(value ? rejectOne[variable] : rejectZero[variable]);
    }

    /** Returns false only for a proved conjunction; singletons force its opposite. */
    boolean propagate(int depth, long undecided, long[] forces) {
        long pending = eligible[depth];
        while (pending != 0) {
            budget.check();
            int c = Long.numberOfTrailingZeros(pending);
            pending &= pending - 1;
            long remaining = masks[c] & undecided;
            if (remaining == 0) { hits++; return false; }
            if ((remaining & (remaining - 1)) == 0) {
                forces[(ones[c] & remaining) == 0 ? 1 : 0] |= remaining;
                forced++;
            }
        }
        if ((forces[0] & forces[1]) != 0) { hits++; return false; }
        return true;
    }

    void assign(int depth, long zero, long one) {
        long pending = zero | one, live = eligible[depth];
        while (pending != 0 && live != 0) {
            budget.check();
            int id = Long.numberOfTrailingZeros(pending);
            pending &= pending - 1;
            live &= ~((one & (1L << id)) == 0 ? rejectZero[id] : rejectOne[id]);
        }
        eligible[depth] = live;
    }

    @Override public void close() {
        if (memory != 0 && (hits != 0 || forced != 0))
            budget.note("count_packing_conflicts", "conflicts=" + hits + "; forced=" + forced);
        budget.release(memory); memory = 0;
    }
}
