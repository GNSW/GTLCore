// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

/** Exact short-circuit queries over packing faces (at most 35 rows). Charge
 * every visited coordinate together, before the caller publishes a hint,
 * witness or subtree decision. This keeps the same work and query order
 * without an atomic budget update for every coordinate. */
final class CountPackingQuery {
    private CountPackingQuery() {}

    /** Exact child bounds and their strict differences from the parent. A child
     * is visited only after its parent passed the SAME query, so unchanged
     * coordinates need no second comparison. This is not a cached query result. */
    static void join(long[] minimum, long[] stricter, int dimensions, int parent, int left, int right,
                     PlanningBudget budget) {
        budget.operations(PlanningBudget.Operation.INTEGER, 64, 3 * dimensions);
        long leftRows = 0, rightRows = 0;
        for (int r = 0; r < dimensions; r++) {
            long a = minimum[left * dimensions + r], b = minimum[right * dimensions + r];
            int order = Long.compare(a, b);
            minimum[parent * dimensions + r] = order < 0 ? a : b;
            if (order > 0) leftRows |= 1L << r;
            else if (order < 0) rightRows |= 1L << r;
        }
        stricter[left] = leftRows; stricter[right] = rightRows;
    }

    static int reject(long[] values, int base, long[] query, int first, long rows, PlanningBudget budget) {
        int checks = 0, rejected = -1;
        long preferred = 1L << first;
        if ((rows & preferred) != 0) {
            checks++; rows ^= preferred;
            if (values[base + first] > query[first]) rejected = first;
        }
        if (rejected < 0) {
            // Sparse masks avoid probing unchanged rows; dense masks avoid a
            // bit-position lookup for almost every row. Both paths visit the
            // same descending coordinates and charge the same comparisons.
            if (3 * Long.bitCount(rows) <= 2 * query.length) {
                while (rows != 0) {
                    int r = 63 - Long.numberOfLeadingZeros(rows);
                    rows ^= 1L << r; checks++;
                    if (values[base + r] > query[r]) { rejected = r; break; }
                }
            } else for (int r = query.length - 1; r >= 0; r--) {
                if ((rows & (1L << r)) == 0) continue;
                checks++;
                if (values[base + r] > query[r]) { rejected = r; break; }
            }
        }
        budget.operations(PlanningBudget.Operation.SCAN, 0, checks);
        return rejected;
    }

    static int reject(long[] values, int base, long[] query, int first, PlanningBudget budget) {
        int checks = 1, rejected = values[base + first] > query[first] ? first : -1;
        if (rejected < 0) for (int r = query.length - 1; r >= 0; r--) {
            if (r == first) continue;
            checks++;
            if (values[base + r] > query[r]) { rejected = r; break; }
        }
        budget.operations(PlanningBudget.Operation.SCAN, 0, checks);
        return rejected;
    }

    static int reject(CountPackingValues values, int base, long[] query, int first, PlanningBudget budget) {
        int checks = 1, rejected = values.get(base + first) > query[first] ? first : -1;
        if (rejected < 0) for (int r = query.length - 1; r >= 0; r--) {
            if (r == first) continue;
            checks++;
            if (values.get(base + r) > query[r]) { rejected = r; break; }
        }
        budget.operations(PlanningBudget.Operation.SCAN, 0, checks);
        return rejected;
    }
}
