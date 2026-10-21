// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

/** Request-local neighborhood coverage and capacity coupling. These observations
 * choose service order only; they neither remove values nor certify a conflict. */
final class CountPackingSchedule {
    record Expansion(boolean rotate, int leftPoints, int rightPoints) {}

    /** One extra ordinary change, concentrated on the streamed half. The
     * smaller combinatorial orientation is advice, never a restriction on
     * another frontier or an original-model proof. */
    static Expansion expansion(int size, long flexible, PlanningBudget budget) {
        if (size < 2 || size > 40 || flexible == 0 || (flexible >>> size) != 0 || Long.bitCount(flexible) > 10) return null;
        int split = size / 2;
        long mask = (1L << size) - 1, left = (1L << split) - 1;
        long rotated = ((flexible >>> split) | (flexible << (size - split))) & mask;
        int lf = Long.bitCount(flexible & left), rf = Long.bitCount(flexible >>> split);
        int rlf = Long.bitCount(rotated & left), rrf = Long.bitCount(rotated >>> split);
        int a = CountPackingExchange.combinations(split, 1, lf, budget);
        int b = CountPackingExchange.combinations(size - split, 4, rf, budget);
        int c = CountPackingExchange.combinations(split, 1, rlf, budget);
        int d = CountPackingExchange.combinations(size - split, 4, rrf, budget);
        boolean first = a <= 65536 && b <= 65536, second = c <= 65536 && d <= 65536;
        if (!first && !second) return null;
        boolean rotate = second && (!first || (long) c + d < (long) a + b);
        return rotate ? new Expansion(true, c, d) : new Expansion(false, a, b);
    }

    record Analysis(boolean admitted, int fractional, int coupled, long covered, long roundings,
                    int leftPoints, int rightPoints, long resumeWork) {}

    static Analysis analyze(long[][] a, long[] b, int capacities, long seed, long flexible,
                            long maximumResume, PlanningBudget budget) {
        int size = a[0].length, split = size / 2, fractional = Long.bitCount(flexible);
        int cap = CountPackingExchange.combinations(size - split, 4, 0, budget);
        int lf = fractional / 2, rf = fractional - lf;
        if (fractional == 0 || CountPackingExchange.combinations(split, 0, lf, budget) > cap ||
                CountPackingExchange.combinations(size - split, 0, rf, budget) > cap)
            return new Analysis(false, fractional, 0, 0, 0, 0, 0, 0);
        // The admitted all-roundings domain has at most 30 flexible bits.
        long roundings = 1L << fractional;
        int oldLeft = Long.bitCount(flexible & ((1L << split) - 1));
        long covered = (long) CountPackingExchange.combinations(oldLeft, 3, 0, budget) *
                CountPackingExchange.combinations(fractional - oldLeft, 3, 0, budget);
        int radius = 3, left, right;
        do {
            left = CountPackingExchange.combinations(split, radius, lf, budget);
            right = CountPackingExchange.combinations(size - split, radius, rf, budget);
        } while ((left > cap || right > cap) && --radius >= 0);

        // Capacity rows whose maximum fractional activity already fits cannot
        // couple these roundings with the integer seed held fixed. The demand
        // and aggregate rows remain in the actual search, not in this hint.
        long[] components = new long[size];
        for (int i = 0; i < size; i++) components[i] = (1L << i) & flexible;
        for (int r = 0; r < capacities; r++) {
            long maximum = 0, support = 0;
            for (int i = 0; i < size; i++) {
                budget.operation(PlanningBudget.Operation.SCAN, 0);
                long bit = 1L << i;
                if ((flexible & bit) != 0) {
                    maximum += a[r][i];
                    if (a[r][i] != 0) support |= bit;
                } else if ((seed & bit) != 0) maximum += a[r][i];
                budget.operation(PlanningBudget.Operation.INTEGER, 64);
            }
            if (maximum <= b[r]) continue;
            long joined = support;
            for (long bits = support; bits != 0; bits &= bits - 1) {
                budget.operation(PlanningBudget.Operation.SCAN, 0);
                joined |= components[Long.numberOfTrailingZeros(bits)];
            }
            for (long bits = joined; bits != 0; bits &= bits - 1) {
                budget.operation(PlanningBudget.Operation.SCAN, 0);
                components[Long.numberOfTrailingZeros(bits)] = joined;
            }
        }
        int coupled = 0;
        for (long component : components) {
            budget.operation(PlanningBudget.Operation.SCAN, 0);
            coupled = Math.max(coupled, Long.bitCount(component));
        }
        // When the original sparse trial already covers most fractional-only
        // roundings, or the capacity components individually fit its radius,
        // give the retained exact frontier a bounded second sample first.
        // Otherwise the new domain adds coverage early. No case names, recipe
        // ids, global cache state, or elapsed wall time enter this decision.
        long resume = 0;
        if (coupled <= 3) resume = maximumResume;
        else if (covered >= roundings - covered)
            resume = (maximumResume * covered + roundings - 1) / roundings;
        budget.operations(PlanningBudget.Operation.INTEGER, 64, 5);
        return new Analysis(true, fractional, coupled, covered, roundings, left, right, resume);
    }
}
