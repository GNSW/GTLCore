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
        SequenceSummary<K> summary = SequenceSummary.of(witness, recipes);
        Set<K> keys = new LinkedHashSet<>(summary.keys());
        keys.add(target);
        keys.addAll(seeds.keySet());
        Map<K, Long> initial = new LinkedHashMap<>();
        for (K key : keys) {
            BigInteger goal = BigInteger.valueOf(seeds.getOrDefault(key, 0L));
            if (key.equals(target)) goal = goal.add(BigInteger.valueOf(amount));
            long required = CheckedAmounts.amount(summary.required(key).max(goal.subtract(summary.delta(key))));
            if (required != 0) initial.put(key, required);
        }
        return GraphRecipe.amounts(initial);
    }

    public static <K> void verify(GraphPlan<K> plan) {
        if (!plan.feasible() || !plan.missing().isEmpty()) throw new IllegalArgumentException("Unverified plan");
        Map<K, Long> needed = initial(plan.steps(), plan.recipes(), plan.target(), plan.amount(), plan.seeds());
        needed.forEach((key, amount) -> {
            if (plan.initial().getOrDefault(key, 0L) < amount) throw new IllegalArgumentException("Unfunded prefix: " + key);
        });
        // Runtime aggregates must also fit, even when the summary used larger intermediates.
        Map<K, Long> totalOutputs = new LinkedHashMap<>();
        plan.patternTimes().forEach((id, count) -> plan.recipes().get(id).outputs().forEach((key, value) -> totalOutputs.merge(key, CheckedAmounts.multiply(count, value), CheckedAmounts::add)));
        SequenceSummary<K> summary = SequenceSummary.of(plan.steps(), plan.recipes());
        Set<K> keys = new LinkedHashSet<>(summary.keys());
        keys.addAll(plan.initial().keySet());
        for (K key : keys) {
            BigInteger initial = BigInteger.valueOf(plan.initial().getOrDefault(key, 0L));
            CheckedAmounts.amount(initial.add(summary.delta(key)));
            // A small terminal inventory does not prove that intermediate held
            // amounts fit the long ledger. Reject before extracting any material.
            CheckedAmounts.amount(initial.add(summary.peak(key)));
        }
    }
}
