package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GraphPlan<K> {

    private final K target;
    private final long amount, searchNodes, planningNanos;
    private final boolean preserveSeeds;
    private final PlanStep steps;
    private final Map<String, GraphRecipe<K>> recipes;
    private final Map<K, BigInteger> initial, missing;
    private final Map<K, Long> seeds;
    private final Result result;
    private volatile Map<K, Long> initialView, missingView;
    private volatile Map<String, BigInteger> exactTimes;

    public K target() {
        return target;
    }

    public long amount() {
        return amount;
    }

    public boolean preserveSeeds() {
        return preserveSeeds;
    }

    public PlanStep steps() {
        return steps;
    }

    public Map<String, GraphRecipe<K>> recipes() {
        return recipes;
    }

    public Map<K, BigInteger> initialExact() {
        return initial;
    }

    public Map<K, BigInteger> missingExact() {
        return missing;
    }

    /** Bounded compatibility views; accounting uses initialExact/missingExact. */
    public Map<K, Long> initial() {
        Map<K, Long> view = initialView;
        if (view == null) initialView = view = ExactAmounts.longView(initial);
        return view;
    }

    public Map<K, Long> missing() {
        Map<K, Long> view = missingView;
        if (view == null) missingView = view = ExactAmounts.longView(missing);
        return view;
    }

    public Map<K, Long> seeds() {
        return seeds;
    }

    public Result result() {
        return result;
    }

    public long searchNodes() {
        return searchNodes;
    }

    public long planningNanos() {
        return planningNanos;
    }

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

    public GraphPlan(K target, long amount, boolean preserveSeeds, PlanStep steps,
                     Map<String, GraphRecipe<K>> recipes, Map<K, ? extends Number> initial,
                     Map<K, Long> seeds, Map<K, ? extends Number> missing, Result result,
                     long searchNodes, long planningNanos) {
        if (amount <= 0) throw new IllegalArgumentException("Non-positive request");
        this.target = target;
        this.amount = amount;
        this.preserveSeeds = preserveSeeds;
        this.steps = steps;
        this.recipes = Collections.unmodifiableMap(new LinkedHashMap<>(recipes));
        this.initial = ExactAmounts.copy(initial);
        this.seeds = GraphRecipe.amounts(seeds);
        this.missing = ExactAmounts.copy(missing);
        this.result = result;
        this.searchNodes = searchNodes;
        this.planningNanos = planningNanos;
    }

    public boolean feasible() {
        return result == Result.FEASIBLE || result == Result.FEASIBLE_NOT_PROVEN_OPTIMAL;
    }

    public Map<String, Long> patternTimes() {
        return ExactAmounts.longView(patternTimesExact());
    }

    public Map<String, BigInteger> patternTimesExact() {
        Map<String, BigInteger> cached = exactTimes;
        if (cached != null) return cached;
        Map<String, BigInteger> counts = new LinkedHashMap<>();
        collect(steps, BigInteger.ONE, counts);
        exactTimes = Collections.unmodifiableMap(counts);
        return exactTimes;
    }

    private static void collect(PlanStep step, BigInteger multiplier, Map<String, BigInteger> counts) {
        if (step instanceof PlanStep.Batch batch) {
            BigInteger count = multiplier.multiply(BigInteger.valueOf(batch.runs()));
            if (count.signum() > 0) counts.merge(batch.recipe(), count, BigInteger::add);
        } else if (step instanceof PlanStep.Repeat repeat) {
            if (repeat.times() != 0) collect(repeat.body(), multiplier.multiply(BigInteger.valueOf(repeat.times())), counts);
        } else {
            for (PlanStep child : ((PlanStep.Sequence) step).children()) collect(child, multiplier, counts);
        }
    }
}
