// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Exact prefix requirements, including inputs returned by the very same operation. */
public record SequenceSummary<K>(Map<K, BigInteger> required, Map<K, BigInteger> delta,
                                 Map<K, BigInteger> peak) {

    public SequenceSummary {
        required = Collections.unmodifiableMap(new LinkedHashMap<>(required));
        delta = Collections.unmodifiableMap(new LinkedHashMap<>(delta));
        peak = Collections.unmodifiableMap(new LinkedHashMap<>(peak));
    }

    public static <K> SequenceSummary<K> empty() {
        return new SequenceSummary<>(Map.of(), Map.of(), Map.of());
    }

    public static <K> SequenceSummary<K> recipe(GraphRecipe<K> recipe) {
        Map<K, BigInteger> required = new LinkedHashMap<>(), delta = new LinkedHashMap<>();
        recipe.inputs().forEach((key, value) -> {
            required.put(key, BigInteger.valueOf(value));
            delta.put(key, BigInteger.valueOf(value).negate());
        });
        recipe.outputs().forEach((key, value) -> delta.merge(key, BigInteger.valueOf(value), BigInteger::add));
        Map<K, BigInteger> peak = new LinkedHashMap<>();
        delta.forEach((key, value) -> peak.put(key, value.max(BigInteger.ZERO)));
        return new SequenceSummary<>(required, delta, peak);
    }

    public SequenceSummary<K> then(SequenceSummary<K> next) {
        Map<K, BigInteger> need = new LinkedHashMap<>(), change = new LinkedHashMap<>(), maximum = new LinkedHashMap<>();
        Set<K> keys = keys();
        keys.addAll(next.keys());
        for (K key : keys) {
            need.put(key, required(key).max(next.required(key).subtract(delta(key))));
            change.put(key, delta(key).add(next.delta(key)));
            maximum.put(key, peak(key).max(delta(key).add(next.peak(key))));
        }
        return new SequenceSummary<>(need, change, maximum);
    }

    public SequenceSummary<K> repeat(long count) {
        CheckedAmounts.nonNegative(count);
        if (count == 0) return empty();
        BigInteger n = BigInteger.valueOf(count);
        Map<K, BigInteger> need = new LinkedHashMap<>(), change = new LinkedHashMap<>(), maximum = new LinkedHashMap<>();
        for (K key : keys()) {
            need.put(key, required(key).add(delta(key).negate().max(BigInteger.ZERO)
                    .multiply(n.subtract(BigInteger.ONE))));
            change.put(key, delta(key).multiply(n));
            maximum.put(key, peak(key).add(delta(key).max(BigInteger.ZERO).multiply(n.subtract(BigInteger.ONE))));
        }
        return new SequenceSummary<>(need, change, maximum);
    }

    public BigInteger required(K key) {
        return required.getOrDefault(key, BigInteger.ZERO);
    }

    public BigInteger delta(K key) {
        return delta.getOrDefault(key, BigInteger.ZERO);
    }

    public BigInteger peak(K key) {
        return peak.getOrDefault(key, BigInteger.ZERO);
    }

    public Set<K> keys() {
        Set<K> keys = new LinkedHashSet<>(required.keySet());
        keys.addAll(delta.keySet());
        return keys;
    }

    public static <K> SequenceSummary<K> of(PlanStep step, Map<String, GraphRecipe<K>> recipes) {
        Map<PlanStep, SequenceSummary<K>> shared = new IdentityHashMap<>();
        var pending = new ArrayList<SummaryFrame>();
        pending.add(new SummaryFrame(step));
        while (!pending.isEmpty()) {
            SummaryFrame frame = pending.get(pending.size() - 1);
            PlanStep child = null;
            if (frame.step instanceof PlanStep.Sequence sequence && frame.child < sequence.children().size())
                child = sequence.children().get(frame.child++);
            else if (frame.step instanceof PlanStep.Repeat repeat && frame.child++ == 0) child = repeat.body();
            if (child == null) {
                shared.put(frame.step, calculate(frame.step, recipes, shared));
                pending.remove(pending.size() - 1);
            } else if (!shared.containsKey(child)) pending.add(new SummaryFrame(child));
        }
        return shared.get(step);
    }

    private static <K> SequenceSummary<K> calculate(PlanStep step, Map<String, GraphRecipe<K>> recipes,
                                                    Map<PlanStep, SequenceSummary<K>> shared) {
        if (step instanceof PlanStep.Batch batch) {
            GraphRecipe<K> recipe = recipes.get(batch.recipe());
            if (recipe == null) throw new IllegalArgumentException("Unknown recipe " + batch.recipe());
            return recipe(recipe).repeat(batch.runs());
        }
        if (step instanceof PlanStep.Repeat repeat) return shared.get(repeat.body()).repeat(repeat.times());
        Map<K, BigInteger> need = new LinkedHashMap<>(), change = new LinkedHashMap<>(), maximum = new LinkedHashMap<>();
        for (PlanStep child : ((PlanStep.Sequence) step).children()) {
            SequenceSummary<K> next = shared.get(child);
            // Only visit the new child's resources. Copying the accumulated prefix
            // at every aisle/step would turn an ordinary chain into quadratic work.
            for (K key : next.keys()) {
                BigInteger previous = change.getOrDefault(key, BigInteger.ZERO);
                need.put(key, need.getOrDefault(key, BigInteger.ZERO).max(next.required(key).subtract(previous)));
                maximum.put(key, maximum.getOrDefault(key, BigInteger.ZERO).max(previous.add(next.peak(key))));
                change.put(key, previous.add(next.delta(key)));
            }
        }
        return new SequenceSummary<>(need, change, maximum);
    }

    private static final class SummaryFrame {

        final PlanStep step;
        int child;

        SummaryFrame(PlanStep step) {
            this.step = step;
        }
    }
}
