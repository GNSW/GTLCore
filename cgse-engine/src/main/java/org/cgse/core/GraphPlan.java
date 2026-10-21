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
    private MissingSource defaultMissingSource = MissingSource.UNASSESSED;
    private Map<K, MissingSource> missingSources = Map.of();
    private java.util.List<java.util.List<GraphRecipe<K>>> refillCycles = java.util.List.of();
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

    /** Facts about the captured source boundary, not material necessity or optimality proofs. */
    public enum MissingSource {
        /** Optional diagnostics were not completed for this key. */
        UNASSESSED,
        /** No producer exists in the catalog supplied to this planning view. */
        TERMINAL_UNDER_CURRENT_COVERAGE,
        /** Completed support analysis found a cycle containing this material. */
        CYCLE_BOUNDARY,
        /** All captured producers were excluded from this planning view. */
        EXCLUDED_SOURCES,
        /** Captured producers exist; no stronger explanation was established. */
        UNEXPANDED_SOURCE
    }

    /** A final seed obligation is independent of the source boundary and may coexist with a cycle. */
    public record MissingBoundary(MissingSource source, boolean explicitFinalSeed) {}

    /**
     * Optional explanation for a displayed deficit, or null if the key is not missing.
     * Coverage is always the input catalog of this planning view, including for
     * conditional previews. A terminal boundary never proves that the host has
     * no omitted producer. UNASSESSED is a valid result after diagnostic limits.
     */
    public MissingBoundary missingBoundary(K key) {
        return missing.containsKey(key) ? new MissingBoundary(missingSources.getOrDefault(key, defaultMissingSource),
                seeds.containsKey(key)) : null;
    }

    /** Transfer a private diagnostic map without retaining the compiler or support graph. */
    GraphPlan<K> withMissingSources(MissingSource fallback, Map<K, MissingSource> sources) {
        GraphPlan<K> copy = new GraphPlan<>(this);
        copy.defaultMissingSource = fallback;
        copy.missingSources = Collections.unmodifiableMap(sources);
        return copy;
    }

    /** Complete source cycles for display only; never part of the executable recipe set. */
    java.util.List<java.util.List<GraphRecipe<K>>> refillCycles() { return refillCycles; }

    /** Transfer private, completed paths without copying or retaining the support graph. */
    GraphPlan<K> withRefillCycles(java.util.List<java.util.List<GraphRecipe<K>>> cycles) {
        GraphPlan<K> copy = new GraphPlan<>(this);
        copy.refillCycles = Collections.unmodifiableList(cycles);
        return copy;
    }

    /** Only on unpublished copies retaining the same deficit keys and planning scope. */
    GraphPlan<K> withMissingSourcesFrom(GraphPlan<K> original) {
        defaultMissingSource = original.defaultMissingSource;
        missingSources = original.missingSources;
        refillCycles = original.refillCycles;
        return this;
    }

    private GraphPlan(GraphPlan<K> original) {
        target = original.target; amount = original.amount; preserveSeeds = original.preserveSeeds;
        steps = original.steps; recipes = original.recipes; initial = original.initial;
        seeds = original.seeds; missing = original.missing; result = original.result;
        searchNodes = original.searchNodes; planningNanos = original.planningNanos;
        seedOptimality = original.seedOptimality; alternatives = original.alternatives;
        initialView = original.initialView; missingView = original.missingView; exactTimes = original.exactTimes;
        executionDependencies = original.executionDependencies;
        withMissingSourcesFrom(original);
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
        return copy.withMissingSourcesFrom(this);
    }

    /** Verified incomparable material/seed choices. Entries never contain a recursive frontier. */
    public java.util.List<GraphPlan<K>> alternatives() {
        return alternatives;
    }

    GraphPlan<K> withAlternatives(java.util.List<GraphPlan<K>> options) {
        GraphPlan<K> copy = new GraphPlan<>(this);
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
        /** Captured alternatives are incomplete; a restricted failure is not a full-model proof. */
        INCOMPLETE_MODEL,
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

    /** Transfer validated private refill builders. Callers must never mutate
     * them again; the public constructor retains its defensive copies and
     * amount validation. Metadata proofs from the old word are not inherited. */
    static <K> GraphPlan<K> ownedRefill(GraphPlan<K> original, PlanStep steps,
                                      Map<String, GraphRecipe<K>> recipes, Map<K, BigInteger> initial,
                                      Map<K, BigInteger> missing, Result result, long searchNodes) {
        return new GraphPlan<>(original, steps, recipes, initial, missing, result, searchNodes);
    }

    private GraphPlan(GraphPlan<K> original, PlanStep steps, Map<String, GraphRecipe<K>> recipes,
                      Map<K, BigInteger> initial, Map<K, BigInteger> missing, Result result, long searchNodes) {
        this.target = original.target;
        this.amount = original.amount;
        this.preserveSeeds = original.preserveSeeds;
        this.steps = steps;
        this.recipes = Collections.unmodifiableMap(recipes);
        this.initial = Collections.unmodifiableMap(initial);
        this.seeds = original.seeds;
        this.missing = Collections.unmodifiableMap(missing);
        this.result = result;
        this.seedOptimality = null;
        this.searchNodes = searchNodes;
        this.planningNanos = original.planningNanos;
    }

    /**
     * Verified execution after supplying this plan's deficits, without a proof
     * that omitted alternatives cannot fulfill the original order. Never a
     * currently executable plan or a necessary/minimal-material certificate.
     */
    public boolean conditionalPreview() {
        return result == Result.INCOMPLETE_MODEL && !missing.isEmpty();
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
