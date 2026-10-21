// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Small verified supply archive. Incomparable prices never choose the primary bill. */
final class RefillFrontier<K> {
    private final PlanningBudget budget;
    private GraphPlan<K> primary, published, candidate;
    private final List<GraphPlan<K>> options = new ArrayList<>();
    private final boolean[] removed = new boolean[5];
    private Comparison<K> comparison;
    private int at;
    private boolean promote;

    RefillFrontier(GraphPlan<K> primary, PlanningBudget budget) {
        this.primary = published = primary; this.budget = budget;
        if (!primary.alternatives().isEmpty()) throw new IllegalArgumentException("Existing frontier must be retained by its owner");
    }
    void offer(GraphPlan<K> plan) {
        if (!plan.alternatives().isEmpty()) throw new IllegalArgumentException("Nested refill frontier");
        candidate = plan; at = -1; promote = false; Arrays.fill(removed, false);
        comparison = new Comparison<>(candidate, primary, budget);
    }
    boolean step() {
        if (candidate == null) return true;
        if (!comparison.step()) return false;
        if (comparison.rightDominates()) { candidate = null; comparison = null; return true; }
        removed[at + 1] = comparison.leftDominates();
        if (at < 0) promote = comparison.leftDominates() || comparison.improvesMissing() || candidate.feasible() && !primary.feasible();
        if (++at < options.size()) { comparison = new Comparison<>(candidate, options.get(at), budget); return false; }
        // At most four options: this is a constant-sized publication, after all
        // comparisons have completed. Interrupted comparisons cannot mutate it.
        for (int i = options.size() - 1; i >= 0; i--) if (removed[i + 1]) options.remove(i);
        if (promote) {
            if (!removed[0] && options.size() < 4) options.add(primary);
            primary = candidate;
        } else if (options.size() < 4) options.add(candidate);
        published = options.isEmpty() ? primary : primary.withAlternatives(options);
        candidate = null; comparison = null;
        return true;
    }
    GraphPlan<K> primary() { return primary; }
    GraphPlan<K> result() { return published; }

    /** Same per-key initial/missing/seed order as SeedPortfolio.dominates,
     * with resumable union traversal and a separate missing-only objective. */
    static final class Comparison<K> {
        private final GraphPlan<K> left, right;
        private final PlanningBudget budget;
        private Map<K, ? extends Number> a, b;
        private Iterator<? extends Map.Entry<K, ? extends Number>> entries;
        private int dimension;
        private boolean reverse, leftBetter, rightBetter, missingLess, missingMore, done;
        Comparison(GraphPlan<K> left, GraphPlan<K> right, PlanningBudget budget) {
            this.left = left; this.right = right; this.budget = budget;
            leftBetter = left.feasible() && !right.feasible(); rightBetter = right.feasible() && !left.feasible();
            a = left.missingExact(); b = right.missingExact(); entries = a.entrySet().iterator();
        }
        boolean step() {
            if (done) return true;
            for (int i = 0; i < 64; i++) {
                budget.check();
                if (entries.hasNext()) {
                    var e = entries.next(); if (reverse && a.containsKey(e.getKey())) continue;
                    Number other = (reverse ? a : b).get(e.getKey());
                    int sign = amount(e.getValue()).compareTo(other == null ? BigInteger.ZERO : amount(other));
                    if (reverse) sign = -sign;
                    leftBetter |= sign < 0; rightBetter |= sign > 0;
                    if (dimension == 0) { missingLess |= sign < 0; missingMore |= sign > 0; }
                } else if (!reverse) { reverse = true; entries = b.entrySet().iterator(); }
                else {
                    if (++dimension == 3 || leftBetter && rightBetter) { done = true; return true; }
                    a = dimension == 1 ? left.initialExact() : left.seeds();
                    b = dimension == 1 ? right.initialExact() : right.seeds();
                    reverse = false; entries = a.entrySet().iterator();
                }
            }
            return false;
        }
        private static BigInteger amount(Number number) {
            return number instanceof BigInteger exact ? exact : BigInteger.valueOf(number.longValue());
        }
        boolean leftDominates() { return !rightBetter; }
        boolean rightDominates() { return !leftBetter; }
        boolean improvesMissing() { return missingLess && !missingMore; }
    }
}
