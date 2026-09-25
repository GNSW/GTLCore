package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Supplier;

/** Costs retain their resource units. Only verified, equally specified goals are comparable. */
final class PlanPreference<K> {

    private final List<Map<K, BigInteger>> materials;
    private final Supplier<Details<K>> deferred;
    private volatile Details<K> details;

    private record Details<K>(Map<K, BigInteger> consumed, Map<K, BigInteger> peaks, BigInteger executions) {

        private Details {
            consumed = Map.copyOf(consumed);
            peaks = Map.copyOf(peaks);
        }
    }

    private PlanPreference(Map<K, BigInteger> missing, Map<K, BigInteger> consumed,
                           Map<K, BigInteger> required, Map<K, BigInteger> seeds,
                           Map<K, BigInteger> peaks, BigInteger executions) {
        materials = List.of(Map.copyOf(missing), Map.copyOf(required), Map.copyOf(seeds));
        deferred = null;
        details = new Details<>(consumed, peaks, executions);
    }

    private PlanPreference(Map<K, BigInteger> missing, Map<K, BigInteger> required, Map<K, BigInteger> seeds,
                           Supplier<Details<K>> deferred) {
        materials = List.of(Map.copyOf(missing), Map.copyOf(required), Map.copyOf(seeds));
        this.deferred = deferred;
    }

    private Details<K> details() {
        Details<K> value = details;
        if (value == null) details = value = deferred.get();
        return value;
    }

    static <K> PlanPreference<K> of(GraphPlan<K> plan, SequenceSummary<K> summary, BigInteger executions) {
        Map<K, BigInteger> consumed = new LinkedHashMap<>(), peaks = new LinkedHashMap<>();
        Set<K> keys = summary.keys();
        keys.addAll(plan.initialExact().keySet());
        for (K key : keys) {
            put(consumed, key, summary.delta(key).negate().max(BigInteger.ZERO));
            put(peaks, key, plan.initialExact().getOrDefault(key, BigInteger.ZERO).add(summary.peak(key)));
        }
        return new PlanPreference<>(plan.missingExact(), consumed, plan.initialExact(), ExactAmounts.copy(plan.seeds()), peaks, executions);
    }

    static <K> PlanPreference<K> lowerBound(RecipeCountModel<K> model, BigInteger[] lower, Map<K, Long> seeds,
                                            BigInteger executions, PlanningBudget budget) {
        Set<K> produced = new HashSet<>();
        model.recipes.forEach(recipe -> produced.addAll(recipe.outputs().keySet()));
        Map<K, BigInteger> consumed = new LinkedHashMap<>();
        for (int i = 0; i < model.recipes.size(); i++) {
            var recipe = model.recipes.get(i);
            for (var input : recipe.inputs().entrySet()) {
                budget.check();
                if (!produced.contains(input.getKey()) && !recipe.configurationInputs().containsKey(input.getKey()))
                    consumed.merge(input.getKey(), lower[i].multiply(BigInteger.valueOf(input.getValue())), BigInteger::add);
            }
        }
        Map<K, BigInteger> required = new LinkedHashMap<>(consumed);
        // Final reserves may be produced during the program; they are not a
        // lower bound on initial inventory.
        return new PlanPreference<>(Map.of(), consumed, required, ExactAmounts.copy(seeds), required, executions);
    }

    /** Used only for proven componentwise lower bounds, never for heuristic scores. */
    boolean cannotImprove(PlanPreference<K> incumbent) {
        for (int i = 0; i < materials.size(); i++) {
            int comparison = compare(materials.get(i), incumbent.materials.get(i));
            if (comparison == 2 || comparison < 0) return false;
            if (comparison > 0) return true;
        }
        Details<K> left = details(), right = incumbent.details();
        int work = left.executions().compareTo(right.executions());
        if (work != 0) return work > 0;
        int use = compare(left.consumed(), right.consumed());
        if (use == 2 || use < 0) return false;
        if (use > 0) return true;
        int peak = compare(left.peaks(), right.peaks());
        return peak == 0 || peak == 1;
    }

    static <K> PlanPreference<K> of(RegionSelection.Choice<K> choice, Set<K> produced, Map<K, BigInteger> demand,
                                    Map<K, Long> stock, Set<K> external, PlanningBudget budget) {
        Map<K, BigInteger> required = new LinkedHashMap<>(), missing = new LinkedHashMap<>();
        Set<K> keys = choice.summary().keys();
        keys.addAll(produced);
        BigInteger runs = choice.runs(), preceding = runs.subtract(BigInteger.ONE).max(BigInteger.ZERO);
        for (K key : keys) {
            budget.check();
            BigInteger delta = choice.summary().delta(key), net = delta.multiply(runs);
            BigInteger prefix = runs.signum() == 0 ? BigInteger.ZERO : choice.summary().required(key)
                    .add(delta.negate().max(BigInteger.ZERO).multiply(preceding));
            BigInteger goal = demand.getOrDefault(key, BigInteger.ZERO).add(BigInteger.valueOf(choice.seeds().getOrDefault(key, 0L)));
            BigInteger initial = prefix.max(goal.subtract(net)).max(BigInteger.ZERO);
            put(required, key, initial);
            if (!external.contains(key)) put(missing, key, initial.subtract(BigInteger.valueOf(stock.getOrDefault(key, 0L))).max(BigInteger.ZERO));
        }
        // Most local orderings already differ in startup needs. Only traverse
        // their compressed programs and compute peaks when those costs tie.
        return new PlanPreference<>(missing, required, ExactAmounts.copy(choice.seeds()), () -> {
            Map<K, BigInteger> consumed = new LinkedHashMap<>(), peaks = new LinkedHashMap<>();
            for (K key : keys) {
                budget.check();
                BigInteger delta = choice.summary().delta(key);
                put(consumed, key, delta.multiply(runs).negate().max(BigInteger.ZERO));
                put(peaks, key, required.getOrDefault(key, BigInteger.ZERO).add(runs.signum() == 0 ? BigInteger.ZERO :
                        choice.summary().peak(key).add(delta.max(BigInteger.ZERO).multiply(preceding))));
            }
            var counting = new PlanCountComputation(choice.body());
            while (!counting.step(budget)) {}
            BigInteger executions = counting.result().values().stream().reduce(BigInteger.ZERO, BigInteger::add).multiply(runs);
            return new Details<>(consumed, peaks, executions);
        });
    }

    /** Pareto dominance: no material, seed, peak or operation cost may increase. */
    boolean dominates(PlanPreference<K> other) {
        boolean strict = false;
        for (int i = 0; i < materials.size(); i++) {
            int comparison = compare(materials.get(i), other.materials.get(i));
            if (comparison > 0) return false;
            strict |= comparison < 0;
        }
        Details<K> left = details(), right = other.details();
        int peak = compare(left.peaks(), right.peaks()), work = left.executions().compareTo(right.executions());
        int use = compare(left.consumed(), right.consumed());
        return peak <= 0 && work <= 0 && use <= 0 && (strict || peak < 0 || work < 0 || use < 0);
    }

    /** Deficits, committed inventory, seeds, operations, then net use and peaks; never sum unlike units. */
    boolean preferredTo(PlanPreference<K> other) {
        for (int i = 0; i < materials.size(); i++) {
            int comparison = compare(materials.get(i), other.materials.get(i));
            if (comparison != 0) return comparison < 0;
        }
        Details<K> left = details(), right = other.details();
        int work = left.executions().compareTo(right.executions());
        if (work != 0) return work < 0;
        // Do not recycle a surplus merely to lower net use: with identical
        // input requirements, keeping the surplus and doing less work wins.
        int use = compare(left.consumed(), right.consumed());
        return use < 0 || use == 0 && compare(left.peaks(), right.peaks()) < 0;
    }

    int absentRequirements(Set<K> regional, Map<K, Long> stock) {
        int count = 0;
        for (K key : regional)
            if (stock.getOrDefault(key, 0L) == 0 && materials.get(0).getOrDefault(key, BigInteger.ZERO).signum() > 0) count++;
        return count;
    }

    BigInteger missing(K key) {
        return materials.get(0).getOrDefault(key, BigInteger.ZERO);
    }

    /** -1 / 0 / 1 mean smaller / equal / larger; 2 means incomparable. */
    private static <K> int compare(Map<K, BigInteger> left, Map<K, BigInteger> right) {
        boolean smaller = false, larger = false;
        Set<K> keys = new HashSet<>(left.keySet());
        keys.addAll(right.keySet());
        for (K key : keys) {
            int comparison = left.getOrDefault(key, BigInteger.ZERO).compareTo(right.getOrDefault(key, BigInteger.ZERO));
            smaller |= comparison < 0;
            larger |= comparison > 0;
            if (smaller && larger) return 2;
        }
        return smaller ? -1 : larger ? 1 : 0;
    }

    private static <K> void put(Map<K, BigInteger> values, K key, BigInteger amount) {
        if (amount.signum() != 0) values.put(key, amount);
    }
}
