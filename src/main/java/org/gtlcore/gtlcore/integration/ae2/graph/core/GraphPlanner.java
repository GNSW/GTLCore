package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.util.Map;
import java.util.Set;

/** Native planner; synchronous callers and background workers share one continuation. */
public final class GraphPlanner<K> {

    private final GraphCompiler<K> compiler;

    public GraphPlanner(GraphCompiler<K> compiler) {
        this.compiler = compiler;
    }

    public GraphPlan<K> plan(K target, long amount, Map<K, Long> stock, boolean preserve,
                             boolean forceCraft, PlanningBudget budget) {
        GraphPlanningWork<K> work = begin(target, amount, stock, preserve, forceCraft, budget);
        while (!work.step()) { /* Drive the same continuation used by background workers. */ }
        return work.result();
    }

    public GraphPlanningWork<K> begin(K target, long amount, Map<K, Long> stock, boolean preserve,
                                      boolean forceCraft, PlanningBudget budget) {
        return new GraphPlanningWork<>(compiler, target, amount, stock, preserve, forceCraft, budget);
    }

    public GraphPlanningWork<K> begin(K target, long amount, Map<K, Long> stock, Set<K> external,
                                      boolean preserve, boolean forceCraft, PlanningBudget budget) {
        return new GraphPlanningWork<>(compiler, target, amount, stock, external, preserve, forceCraft, budget);
    }
}
