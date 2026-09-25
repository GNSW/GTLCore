package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Verifies a concrete serial witness, not just its steady-state material balance. */
public final class PlanVerifier {

    private PlanVerifier() {}

    public static <K> Map<K, Long> initial(PlanStep witness, Map<String, GraphRecipe<K>> recipes,
                                           K target, long amount, Map<K, Long> seeds) {
        Map<K, Long> result = new LinkedHashMap<>();
        initialExact(witness, recipes, target, amount, seeds).forEach((key, count) -> result.put(key, CheckedAmounts.amount(count)));
        return GraphRecipe.amounts(result);
    }

    public static <K> Map<K, BigInteger> initialExact(PlanStep witness, Map<String, GraphRecipe<K>> recipes,
                                                      K target, long amount, Map<K, Long> seeds) {
        SequenceSummary<K> summary = SequenceSummary.of(witness, recipes);
        Set<K> keys = new LinkedHashSet<>(summary.keys());
        keys.add(target);
        keys.addAll(seeds.keySet());
        Map<K, BigInteger> initial = new LinkedHashMap<>();
        for (K key : keys) {
            BigInteger goal = BigInteger.valueOf(seeds.getOrDefault(key, 0L));
            if (key.equals(target)) goal = goal.add(BigInteger.valueOf(amount));
            BigInteger required = summary.required(key).max(goal.subtract(summary.delta(key)));
            if (required.signum() != 0) initial.put(key, required);
        }
        return ExactAmounts.copy(initial);
    }

    public static <K> void verify(GraphPlan<K> plan) {
        if (!plan.feasible() || !plan.missing().isEmpty()) throw new IllegalArgumentException("Unverified plan");
        Map<K, BigInteger> needed = initialExact(plan.steps(), plan.recipes(), plan.target(), plan.amount(), plan.seeds());
        needed.forEach((key, amount) -> {
            if (plan.initialExact().getOrDefault(key, BigInteger.ZERO).compareTo(amount) < 0) throw new IllegalArgumentException("Unfunded prefix: " + key);
        });
    }

    /**
     * AE's physical CPU inventory stores a long per key. Only the initial
     * reservation must fit simultaneously. Execution reserves output headroom
     * per batch and can deliver/refund surplus between batches, so a serial
     * whole-plan peak is not a submission limit. Never truncate ownership.
     */
    public static <K> void verifyRuntimeInventory(GraphPlan<K> plan) {
        verify(plan);
        plan.initialExact().values().forEach(CheckedAmounts::amount);
    }
}
