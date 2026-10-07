package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** Immutable ordinary-region instructions. Counts, remainders and stock remain request-local. */
final class GraphDemandProgram<K> {

    record Ports(int[] inputs, BigInteger[] inputAmounts, int[] outputs, BigInteger[] outputAmounts) {}

    final GraphCompiler.Compiled<K> graph;
    final long weight;
    private final List<K> resources;
    private final Ports[] regions;

    private GraphDemandProgram(GraphCompiler.Compiled<K> graph, List<K> resources, Ports[] regions, long weight) {
        this.graph = graph;
        this.resources = resources;
        this.regions = regions;
        this.weight = weight;
    }

    K resource(int id) {
        return resources.get(id);
    }

    Ports region(int id) {
        return regions[id];
    }

    GraphDemandProgram<K> forGraph(GraphCompiler.Compiled<K> next) {
        if (next == graph) return this;
        if (next.regions() != graph.regions()) throw new IllegalArgumentException("Different demand topology");
        // A shared immutable partition contains the same recipes, quantities
        // and instruction order. Selected sources and every numeric demand
        // remain owned by the new graph/request, never by these port arrays.
        return new GraphDemandProgram<>(next, resources, regions, weight);
    }

    static <K> GraphDemandProgram<K> create(GraphCompiler.Compiled<K> graph, PlanningBudget budget) {
        int size = graph.regions().size();
        if (size == 0 || size > 8192) return null;
        long started = budget.threadWork(), allowance = Math.min(131072, budget.remainingWork() / 16);
        long bytes = 256L + 32L * size, cap = Math.min(4L << 20, budget.availableBytes() / 8);
        if (allowance < 1024 || bytes > cap || !budget.tryReserve(bytes)) return null;
        try {
            Map<K, Integer> keys = new LinkedHashMap<>();
            Ports[] ports = new Ports[size];
            long weight = size;
            int ordinary = 0;
            for (int i = 0; i < size; i++) {
                if (budget.threadWork() - started >= allowance) return null;
                budget.compilationCheck();
                var region = graph.regions().get(i);
                if (region.cyclic() || region.recipes().size() != 1) continue;
                var recipe = region.recipes().get(0);
                if (!Collections.disjoint(recipe.inputs().keySet(), recipe.outputs().keySet())) continue;
                long entries = recipe.inputs().size() + (long) recipe.outputs().size();
                long needed = 128L + 192L * entries;
                if (needed > cap - bytes || weight + entries > 32768 || !budget.tryReserve(needed)) return null;
                bytes += needed;
                weight += entries;
                int[] inputs = new int[recipe.inputs().size()], outputs = new int[recipe.outputs().size()];
                BigInteger[] inputAmounts = new BigInteger[inputs.length], outputAmounts = new BigInteger[outputs.length];
                for (int phase = 0; phase < 2; phase++) {
                    int at = 0;
                    var values = phase == 0 ? recipe.inputs() : recipe.outputs();
                    for (var entry : values.entrySet()) {
                        if (budget.threadWork() - started >= allowance) return null;
                        budget.compilationCheck();
                        int key = keys.computeIfAbsent(entry.getKey(), ignored -> keys.size());
                        (phase == 0 ? inputs : outputs)[at] = key;
                        (phase == 0 ? inputAmounts : outputAmounts)[at++] = BigInteger.valueOf(entry.getValue());
                    }
                }
                ports[i] = new Ports(inputs, inputAmounts, outputs, outputAmounts);
                ordinary++;
            }
            return ordinary == 0 ? null : new GraphDemandProgram<>(graph, List.copyOf(keys.keySet()), ports, weight);
        } finally {
            budget.release(bytes);
        }
    }
}
