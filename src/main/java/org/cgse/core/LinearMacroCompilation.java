package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Optional exact contraction of ratio chains; every witness expands to real recipes. */
final class LinearMacroCompilation<K> implements AutoCloseable {

    private record Firing<K>(GraphRecipe<K> recipe, BigInteger runs) {}

    private final Map<String, GraphRecipe<K>> original;
    private final PlanningBudget budget;
    private final Set<K> goals;
    private final Map<K, List<GraphRecipe<K>>> producers = new HashMap<>(), consumers = new HashMap<>();
    private final Map<String, List<Firing<K>>> bodies = new LinkedHashMap<>();
    private final Map<String, GraphRecipe<K>> combined = new LinkedHashMap<>();
    private Iterator<GraphRecipe<K>> iterator;
    private final List<GraphRecipe<K>> chain = new ArrayList<>();
    private final Set<K> visited = new HashSet<>();
    private K input, output;
    private int phase, visits;
    private long memory, expandedBytes;

    LinearMacroCompilation(Map<String, GraphRecipe<K>> recipes, K target, Set<K> seeds, PlanningBudget budget) {
        original = new LinkedHashMap<>(recipes);
        this.budget = budget;
        goals = new HashSet<>(seeds);
        goals.add(target);
        iterator = original.values().iterator();
        reserve(128L + 64L * recipes.size());
    }

    boolean step() {
        budget.check();
        if (phase == 0) {
            if (iterator.hasNext()) {
                GraphRecipe<K> recipe = iterator.next();
                for (K key : recipe.inputs().keySet()) consumers.computeIfAbsent(key, k -> new ArrayList<>()).add(recipe);
                for (K key : recipe.outputs().keySet()) producers.computeIfAbsent(key, k -> new ArrayList<>()).add(recipe);
                reserve(80L * (recipe.inputs().size() + recipe.outputs().size()));
                return false;
            }
            iterator = original.values().iterator();
            phase = 1;
        }
        if (phase == 1) {
            if (++visits >= 32_768 || !iterator.hasNext() && chain.isEmpty()) {
                combined.putAll(original);
                phase = 2;
                return true;
            }
            if (chain.isEmpty()) {
                GraphRecipe<K> end = iterator.next();
                if (!linear(end)) return false;
                output = end.outputs().keySet().iterator().next();
                var next = consumers.getOrDefault(output, List.of());
                if (!goals.contains(output) && producers.get(output).size() == 1 && next.size() == 1 && linear(next.get(0))) return false;
                input = end.inputs().keySet().iterator().next();
                visited.clear();
                visited.add(output);
                chain.add(end);
            }
            if (!visited.add(input)) {
                chain.clear();
                return false;
            }
            var prior = producers.getOrDefault(input, List.of());
            if (chain.size() < 2048 && prior.size() == 1 && linear(prior.get(0))) {
                GraphRecipe<K> recipe = prior.get(0);
                chain.add(recipe);
                input = recipe.inputs().keySet().iterator().next();
                return false;
            }
            if (chain.size() > 1) {
                Collections.reverse(chain);
                compileChain();
            }
            chain.clear();
            return false;
        }
        return true;
    }

    private static boolean linear(GraphRecipe<?> recipe) {
        return recipe.configurationInputs().isEmpty() && recipe.inputs().size() == 1 && recipe.outputs().size() == 1;
    }

    private void compileChain() {
        // Match adjacent rates by their least integer common throughput. Keep
        // the inverse multiplicities: rounding intermediate batches would
        // silently introduce an extra-stock dependency or lose coproducts.
        BigInteger[] counts = new BigInteger[chain.size()];
        counts[0] = BigInteger.ONE;
        BigInteger incoming = BigInteger.valueOf(chain.get(0).inputs().values().iterator().next());
        BigInteger outgoing = BigInteger.valueOf(chain.get(0).outputs().values().iterator().next());
        for (int i = 1; i < chain.size(); i++) {
            budget.check();
            var recipe = chain.get(i);
            BigInteger need = BigInteger.valueOf(recipe.inputs().values().iterator().next());
            BigInteger common = outgoing.gcd(need);
            BigInteger scale = need.divide(common);
            counts[i] = outgoing.divide(common);
            incoming = incoming.multiply(scale);
            outgoing = BigInteger.valueOf(recipe.outputs().values().iterator().next()).multiply(counts[i]);
            if (incoming.compareTo(ExactAmounts.LONG_MAX) > 0 || outgoing.compareTo(ExactAmounts.LONG_MAX) > 0) return;
            for (int j = 0; !scale.equals(BigInteger.ONE) && j < i; j++) {
                budget.check();
                counts[j] = counts[j].multiply(scale);
                if (counts[j].compareTo(ExactAmounts.LONG_MAX) > 0) return;
            }
        }
        String id = "@linear/" + bodies.size();
        while (original.containsKey(id)) id += "/";
        long bytes = 192L + 96L * chain.size();
        if (!budget.tryReserve(bytes)) return;
        memory += bytes;
        var body = new ArrayList<Firing<K>>();
        for (int i = 0; i < chain.size(); i++) body.add(new Firing<>(chain.get(i), counts[i]));
        bodies.put(id, List.copyOf(body));
        combined.put(id, new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>(input, incoming.longValueExact())), Map.of(output, outgoing.longValueExact())));
        budget.note("linear_macro", "length=" + chain.size() + "; input=" + incoming + "; output=" + outgoing + "; inverse_counts_retained");
    }

    Map<String, GraphRecipe<K>> recipes() {
        return combined;
    }

    Map<String, BigInteger> counts(String id) {
        var body = bodies.get(id);
        if (body == null) return Map.of(id, BigInteger.ONE);
        Map<String, BigInteger> result = new LinkedHashMap<>();
        for (var firing : body) {
            budget.check();
            result.merge(firing.recipe().id(), firing.runs(), BigInteger::add);
        }
        return Map.copyOf(result);
    }

    PlanStep expand(PlanStep step) {
        // The allocation search has discarded its previous candidate before
        // expanding another one. Do not charge abandoned programs forever.
        budget.release(expandedBytes);
        expandedBytes = 0;
        long[] retained = { 0 };
        boolean complete = false;
        try {
            PlanStep result = PlanRewrite.batches(step, batch -> {
                if (!bodies.containsKey(batch.recipe())) return batch;
                var children = new ArrayList<PlanStep>();
                for (var firing : bodies.get(batch.recipe())) {
                    budget.check();
                    children.add(PlanStep.batch(firing.recipe().id(), firing.runs().multiply(BigInteger.valueOf(batch.runs()))));
                }
                long bytes = 64L + 64L * children.size();
                budget.reserve(bytes);
                retained[0] += bytes;
                return new PlanStep.Sequence(children);
            }, budget, bytes -> retained[0] += bytes);
            expandedBytes = retained[0];
            complete = true;
            return result;
        } finally {
            if (!complete) budget.release(retained[0]);
        }
    }

    private void reserve(long bytes) {
        budget.reserve(bytes);
        memory += bytes;
    }

    @Override
    public void close() {
        budget.release(memory + expandedBytes);
        memory = expandedBytes = 0;
        original.clear();
        producers.clear();
        consumers.clear();
        bodies.clear();
        combined.clear();
        chain.clear();
        visited.clear();
    }
}
