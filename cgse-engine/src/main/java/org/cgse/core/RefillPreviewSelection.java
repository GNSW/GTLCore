// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.Iterator;
import java.util.Map;

/** Compares completed bills within one request; no search state or inventory is cached. */
final class RefillPreviewSelection<K> {
    private final GraphPlan<K> retained, proposed;
    private final PlanningBudget budget;
    private Iterator<Map.Entry<K, BigInteger>> entries;
    private boolean reverse, less, more, retainedTerminal = true, proposedTerminal = true;
    private GraphPlan<K> result;

    RefillPreviewSelection(GraphPlan<K> retained, GraphPlan<K> proposed, PlanningBudget budget) {
        this.retained = retained; this.proposed = proposed; this.budget = budget;
        entries = proposed.missingExact().entrySet().iterator();
    }

    boolean step() {
        if (result != null) return true;
        for (int i = 0; i < 64; i++) {
            budget.check();
            if (entries.hasNext()) {
                var entry = entries.next();
                var plan = reverse ? retained : proposed;
                boolean terminal = plan.missingBoundary(entry.getKey()).source() ==
                        GraphPlan.MissingSource.TERMINAL_UNDER_CURRENT_COVERAGE;
                if (reverse) retainedTerminal &= terminal;
                else proposedTerminal &= terminal;
                if (reverse && proposed.missingExact().containsKey(entry.getKey())) continue;
                int comparison = reverse ? -1 : entry.getValue().compareTo(
                        retained.missingExact().getOrDefault(entry.getKey(), BigInteger.ZERO));
                less |= comparison < 0;
                more |= comparison > 0;
            } else if (!reverse) {
                reverse = true;
                entries = retained.missingExact().entrySet().iterator();
            } else {
                // Prefer complete expansion to raw supplies over a cyclic or
                // unexpanded loan. Within either class compare exact per-key
                // deficits, never a sum of unrelated item/fluid quantities.
                // Equal or incomparable bills keep the established policy
                // order; only a demonstrated regression selects the old bill.
                result = proposedTerminal != retainedTerminal ? (proposedTerminal ? proposed : retained) :
                        more && !less ? retained : proposed;
                return true;
            }
        }
        return false;
    }

    GraphPlan<K> result() { return result; }
}
