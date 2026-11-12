// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.Map;
import java.util.Set;

/** Compatibility facade for the primary solver with an acyclic recipe domain. */
@Deprecated
public final class GraphFallback {
    private GraphFallback() {}

    public static <K> GraphPlan<K> plan(GraphCompiler<K> compiler, K target, long amount,
                                        Map<K, Long> stock, Set<K> external, Map<K, Long> seeds,
                                        boolean preserve, boolean forceCraft, PlanningBudget budget) {
        var work = new GraphPlanningWork<>(compiler, target, amount, stock, external, seeds,
                preserve, forceCraft, budget, GraphMode.ACYCLIC);
        try {
            while (!work.step()) { /* Drive the shared resumable primary solver. */ }
            return work.result();
        } finally { work.close(); }
    }
}
