package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

/** Regroups a bounded loop body only when its exact inventory requirements permit it. */
final class LoopBatching {

    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private LoopBatching() {}

    static Map<String, BigInteger> counts(PlanStep body) {
        record Visit(PlanStep step, BigInteger count) {}
        var pending = new ArrayDeque<Visit>();
        var counts = new LinkedHashMap<String, BigInteger>();
        pending.push(new Visit(body, BigInteger.ONE));
        int visited = 0;
        while (!pending.isEmpty()) {
            if (++visited > 256) return Map.of();
            var visit = pending.pop();
            if (visit.count().signum() == 0) continue;
            if (visit.step() instanceof PlanStep.Batch batch) {
                BigInteger count = visit.count().multiply(BigInteger.valueOf(batch.runs()));
                if (count.signum() == 0) continue;
                counts.merge(batch.recipe(), count, BigInteger::add);
                if (counts.size() > PipelineScheduler.WINDOW || counts.get(batch.recipe()).compareTo(MAX) > 0) return Map.of();
            } else if (visit.step() instanceof PlanStep.Repeat repeat) {
                BigInteger count = visit.count().multiply(BigInteger.valueOf(repeat.times()));
                if (count.compareTo(MAX) > 0) return Map.of();
                pending.push(new Visit(repeat.body(), count));
            } else {
                var children = ((PlanStep.Sequence) visit.step()).children();
                for (int i = children.size() - 1; i >= 0; i--) pending.push(new Visit(children.get(i), visit.count()));
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    static boolean fixedPerRun(GraphRecipe<?> recipe) {
        return recipe.configurationInputs().equals(recipe.reusableInputs());
    }

    /** All constraints are affine in the number of regrouped iterations; no trial dispatches. */
    static <K> long iterations(Map<String, BigInteger> counts, long remaining,
                              Map<String, GraphRecipe<K>> recipes, Function<K, BigInteger> stock) {
        BigInteger lower = BigInteger.TWO, upper = BigInteger.valueOf(remaining);
        Map<K, BigInteger> prefix = new HashMap<>();
        for (var entry : counts.entrySet()) {
            GraphRecipe<K> recipe = recipes.get(entry.getKey());
            if (!fixedPerRun(recipe)) return 0;
            BigInteger copies = entry.getValue();
            upper = upper.min(MAX.divide(copies));
            var keys = new LinkedHashSet<>(recipe.inputs().keySet());
            keys.addAll(recipe.outputs().keySet());
            for (K key : keys) {
                BigInteger available = stock.apply(key);
                if (available.signum() < 0 || available.compareTo(MAX) > 0) return 0;
                BigInteger input = BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L));
                BigInteger delta = BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L)).subtract(input);
                BigInteger previous = prefix.getOrDefault(key, BigInteger.ZERO);
                BigInteger loss = delta.negate().max(BigInteger.ZERO);
                // required = input - loss + (copies * loss - previous) * n
                BigInteger coefficient = copies.multiply(loss).subtract(previous);
                BigInteger slack = available.subtract(input.subtract(loss));
                if (coefficient.signum() > 0) {
                    if (slack.signum() < 0) return 0;
                    upper = upper.min(slack.divide(coefficient));
                } else if (coefficient.signum() == 0) {
                    if (slack.signum() < 0) return 0;
                } else if (slack.signum() < 0) {
                    BigInteger divisor = coefficient.negate();
                    lower = lower.max(slack.negate().add(divisor).subtract(BigInteger.ONE).divide(divisor));
                }
                BigInteger peak = previous.add(copies.multiply(delta.max(BigInteger.ZERO)));
                if (peak.signum() > 0) upper = upper.min(MAX.subtract(available).divide(peak));
                if (upper.compareTo(lower) < 0) return 0;
                prefix.put(key, previous.add(copies.multiply(delta)));
            }
        }
        return upper.compareTo(lower) < 0 ? 0 : upper.longValueExact();
    }
}
