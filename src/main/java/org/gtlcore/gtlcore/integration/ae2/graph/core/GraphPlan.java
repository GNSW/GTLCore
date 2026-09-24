package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record GraphPlan<K>(K target, long amount, boolean preserveSeeds, PlanStep steps,
                           Map<String, GraphRecipe<K>> recipes, Map<K, Long> initial,
                           Map<K, Long> seeds, Map<K, Long> missing, Result result,
                           long searchNodes, long planningNanos) {

    public enum Result {
        FEASIBLE,
        FEASIBLE_NOT_PROVEN_OPTIMAL,
        MISSING_INPUT,
        MISSING_SEED,
        TIMEOUT,
        SEARCH_LIMIT,
        MEMORY_LIMIT,
        GRAPH_LIMIT,
        QUEUE_LIMIT,
        UNKNOWN,
        UNSUPPORTED_PATTERN_SEMANTICS,
        AMOUNT_LIMIT
    }

    public GraphPlan {
        if (amount <= 0) throw new IllegalArgumentException("Non-positive request");
        recipes = Collections.unmodifiableMap(new LinkedHashMap<>(recipes));
        initial = GraphRecipe.amounts(initial);
        seeds = GraphRecipe.amounts(seeds);
        missing = GraphRecipe.amounts(missing);
    }

    public boolean feasible() {
        return result == Result.FEASIBLE || result == Result.FEASIBLE_NOT_PROVEN_OPTIMAL;
    }

    public Map<String, Long> patternTimes() {
        Map<String, Long> counts = new LinkedHashMap<>();
        collect(steps, 1, counts);
        return Collections.unmodifiableMap(counts);
    }

    private static void collect(PlanStep step, long multiplier, Map<String, Long> counts) {
        if (step instanceof PlanStep.Batch batch) {
            long count = CheckedAmounts.multiply(batch.runs(), multiplier);
            if (count > 0) counts.merge(batch.recipe(), count, CheckedAmounts::add);
        } else if (step instanceof PlanStep.Repeat repeat) {
            if (repeat.times() != 0) collect(repeat.body(), CheckedAmounts.multiply(multiplier, repeat.times()), counts);
        } else {
            for (PlanStep child : ((PlanStep.Sequence) step).children()) collect(child, multiplier, counts);
        }
    }
}
