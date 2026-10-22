// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;

/** Exact same-input batch counts, used inside the common backward refill expansion. */
final class RefillBatchCounts<K> {
    private static final int MAX_PERIOD = 128;
    private final PlanningBudget budget;
    private BigInteger need, ao, bo, ai, bi, best, x, y;
    private int at, limit;
    private boolean swapped, done;

    RefillBatchCounts(RefillPairCounts.Choice<K> choice, BigInteger need, PlanningBudget budget) {
        this.budget = budget; this.need = need;
        var a = choice.first(); var b = choice.second(); K key = choice.key();
        if (!ordinary(a, key) || !ordinary(b, key) || !a.inputs().keySet().equals(b.inputs().keySet())) { done = true; return; }
        K input = a.inputs().keySet().iterator().next();
        ao = BigInteger.valueOf(a.outputs().get(key)); bo = BigInteger.valueOf(b.outputs().get(key));
        ai = BigInteger.valueOf(a.inputs().get(input)); bi = BigInteger.valueOf(b.inputs().get(input));
        int ratio = ai.multiply(bo).compareTo(bi.multiply(ao));
        if (ratio > 0 || ratio == 0 && (ao.compareTo(bo) > 0 || ao.equals(bo) && a.id().compareTo(b.id()) > 0)) {
            var n = ao; ao = bo; bo = n; n = ai; ai = bi; bi = n; swapped = true;
        }
        // Replace a full exchange period of the more expensive producer with
        // equal output from the cheaper producer. Only residues need inspection.
        BigInteger trials = ao.divide(ao.gcd(bo)).min(CheckedAmounts.ceilDiv(need, bo).add(BigInteger.ONE));
        if (trials.compareTo(BigInteger.valueOf(MAX_PERIOD)) > 0) { done = true; return; }
        limit = trials.intValueExact();
    }

    static boolean ordinary(GraphRecipe<?> recipe, Object key) {
        return recipe.configurationInputs().isEmpty() && recipe.inputs().size() == 1 && recipe.outputs().size() == 1 &&
                recipe.outputs().containsKey(key) && !recipe.inputs().containsKey(key);
    }

    boolean step() {
        if (done) return true;
        for (int i = 0; i < 32; i++) {
            budget.check();
            if (at == limit) { done = true; return true; }
            BigInteger second = BigInteger.valueOf(at++);
            BigInteger first = CheckedAmounts.ceilDiv(need.subtract(bo.multiply(second)).max(BigInteger.ZERO), ao);
            BigInteger cost = ai.multiply(first).add(bi.multiply(second));
            if (best == null || cost.compareTo(best) < 0) { best = cost; x = first; y = second; }
        }
        return false;
    }
    BigInteger firstCount() { return swapped ? y : x; }
    BigInteger secondCount() { return swapped ? x : y; }
}
