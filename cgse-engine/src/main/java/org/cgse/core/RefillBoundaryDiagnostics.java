// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Optional final annotation. Never searches again or changes a completed refill bill. */
final class RefillBoundaryDiagnostics<K> implements AutoCloseable {
    private final GraphCompiler<K> compiler;
    private final RefillProducerOrder<K> order;
    private final PlanningBudget budget;
    private final Iterator<K> keys;
    private final String retainedFailure;
    private Map<K, GraphPlan.MissingSource> sources = new LinkedHashMap<>();
    private GraphPlan<K> result;
    private long memory;
    private boolean done;

    RefillBoundaryDiagnostics(GraphPlan<K> plan, GraphCompiler<K> compiler, RefillProducerOrder<K> completedOrder,
                              PlanningBudget budget) {
        this.result = plan; this.compiler = compiler; this.order = completedOrder; this.budget = budget;
        keys = plan.missingExact().keySet().iterator(); retainedFailure = budget.failureDetail();
    }

    boolean step() {
        if (done) return true;
        try {
            budget.checkpoint();
            for (int i = 0; i < 64; i++) {
                if (!keys.hasNext() || !budget.tryRefillDiagnostic()) return finish();
                K key = keys.next();
                GraphPlan.MissingSource source;
                var node = order == null ? null : order.get(key);
                if (compiler.producers(key).isEmpty()) source = GraphPlan.MissingSource.TERMINAL_UNDER_CURRENT_COVERAGE;
                else if (node != null && node.excludedSource && !node.allowedSource) source = GraphPlan.MissingSource.EXCLUDED_SOURCES;
                else if (node != null && node.cycle) source = GraphPlan.MissingSource.CYCLE_BOUNDARY;
                else source = GraphPlan.MissingSource.UNEXPANDED_SOURCE;
                if (!budget.tryReserve(96)) return finish();
                memory += 96; sources.put(key, source);
            }
            return false;
        } catch (PlanningBudget.Exhausted limit) {
            budget.failureDetail(retainedFailure);
            return finish();
        }
    }

    private boolean finish() {
        if (!sources.isEmpty()) result = result.withMissingSources(GraphPlan.MissingSource.UNASSESSED, sources);
        sources = null; done = true;
        return true;
    }

    GraphPlan<K> result() { return result; }
    @Override public void close() { sources = null; budget.release(memory); memory = 0; }
}
