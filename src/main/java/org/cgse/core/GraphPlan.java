// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

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
    private final SeedOptimality seedOptimality;
    private java.util.List<GraphPlan<K>> alternatives = java.util.List.of();
    private volatile Map<K, Long> initialView, missingView;
    private volatile Map<String, BigInteger> exactTimes;
    private volatile ExecutionDependencies<K> executionDependencies;

    ExecutionDependencies<K> executionDependencies() {
        ExecutionDependencies<K> dependencies = executionDependencies;
        if (dependencies == null) executionDependencies = dependencies = new ExecutionDependencies<>(this);
        return dependencies;
    }

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

    /** Other material/operation objectives may remain unproven even when this is certified. */
    public record SeedOptimality(int lowerTypeBound, int types, boolean cardinalityProven,
                                 boolean quantitiesParetoProven, boolean fundedPreview, boolean baseMaterialTradeoff) {

        public SeedOptimality(int lowerTypeBound, int types, boolean cardinalityProven,
                              boolean quantitiesParetoProven, boolean fundedPreview) {
            this(lowerTypeBound, types, cardinalityProven, quantitiesParetoProven, fundedPreview, false);
        }

        public SeedOptimality {
            if (lowerTypeBound < 0 || lowerTypeBound > types) throw new IllegalArgumentException("Invalid seed bound");
        }
    }

    public SeedOptimality seedOptimality() {
        return seedOptimality;
    }

    public GraphPlan<K> withSeedOptimality(SeedOptimality proof) {
        if (proof == null) return this;
        GraphPlan<K> copy = new GraphPlan<>(target, amount, preserveSeeds, steps, recipes, initial, seeds, missing, result,
                searchNodes, planningNanos, proof);
        copy.alternatives = alternatives;
        return copy;
    }

    /** Verified incomparable material/seed choices. Entries never contain a recursive frontier. */
    public java.util.List<GraphPlan<K>> alternatives() {
        return alternatives;
    }

    GraphPlan<K> withAlternatives(java.util.List<GraphPlan<K>> options) {
        GraphPlan<K> copy = new GraphPlan<>(target, amount, preserveSeeds, steps, recipes, initial, seeds, missing, result,
                searchNodes, planningNanos, seedOptimality);
        copy.alternatives = java.util.List.copyOf(options);
        return copy;
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
        AMOUNT_LIMIT,
        /** The captured order is impossible; no verified refill program is available. */
        INFEASIBLE
    }

    public GraphPlan(K target, long amount, boolean preserveSeeds, PlanStep steps,
                     Map<String, GraphRecipe<K>> recipes, Map<K, ? extends Number> initial,
                     Map<K, Long> seeds, Map<K, ? extends Number> missing, Result result,
                     long searchNodes, long planningNanos) {
        this(target, amount, preserveSeeds, steps, recipes, initial, seeds, missing, result, searchNodes, planningNanos, null);
    }

    private GraphPlan(K target, long amount, boolean preserveSeeds, PlanStep steps,
                      Map<String, GraphRecipe<K>> recipes, Map<K, ? extends Number> initial,
                      Map<K, Long> seeds, Map<K, ? extends Number> missing, Result result,
                      long searchNodes, long planningNanos, SeedOptimality seedOptimality) {
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
        this.seedOptimality = seedOptimality;
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
        exactTimes = PlanCountComputation.of(steps);
        return exactTimes;
    }
}
