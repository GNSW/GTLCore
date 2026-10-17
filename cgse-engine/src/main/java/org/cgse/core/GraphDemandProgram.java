// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Immutable ordinary-region instructions. Counts, remainders and stock remain request-local. */
final class GraphDemandProgram<K> {

    record Ports(int[] inputs, BigInteger[] inputAmounts, int[] outputs, BigInteger[] outputAmounts) {}

    static final long MAX_BYTES = 4L << 20;
    final List<GraphCompiler.Region<K>> topology;
    final long weight, bytes;
    private final List<K> resources;
    private final Ports[] regions;

    private GraphDemandProgram(List<GraphCompiler.Region<K>> topology, List<K> resources, Ports[] regions, long weight, long bytes) {
        this.topology = topology;
        this.resources = resources;
        this.regions = regions;
        this.weight = weight;
        this.bytes = bytes;
    }

    K resource(int id) {
        return resources.get(id);
    }

    Ports region(int id) {
        return regions[id];
    }

    static <K> GraphDemandProgram<K> create(GraphCompiler.Compiled<K> graph, PlanningBudget budget) {
        int size = graph.regions().size();
        if (size == 0 || size > 8192) return null;
        // The owner admits at most one MAX_BYTES workspace. Only immutable
        // ports are prepared here; actual demand propagation remains solver work.
        long bytes = 256L + 32L * size;
        Map<K, Integer> keys = new LinkedHashMap<>();
        Ports[] ports = new Ports[size];
        long weight = size;
        int ordinary = 0;
        for (int i = 0; i < size; i++) {
            if (!budget.tryCacheScan(1)) return null;
            var region = graph.regions().get(i);
            if (region.cyclic() || region.recipes().size() != 1) continue;
            var recipe = region.recipes().get(0);
            long entries = recipe.inputs().size() + (long) recipe.outputs().size();
            long needed = 128L + 192L * entries;
            if (needed > MAX_BYTES - bytes || weight + entries > 32768) return null;
            if (!budget.tryCacheScan((int) entries)) return null;
            if (!Collections.disjoint(recipe.inputs().keySet(), recipe.outputs().keySet())) continue;
            bytes += needed;
            weight += entries;
            int[] inputs = new int[recipe.inputs().size()], outputs = new int[recipe.outputs().size()];
            BigInteger[] inputAmounts = new BigInteger[inputs.length], outputAmounts = new BigInteger[outputs.length];
            for (int phase = 0; phase < 2; phase++) {
                int at = 0;
                var values = phase == 0 ? recipe.inputs() : recipe.outputs();
                for (var entry : values.entrySet()) {
                    if (!budget.tryCacheScan(1)) return null;
                    int key = keys.computeIfAbsent(entry.getKey(), ignored -> keys.size());
                    (phase == 0 ? inputs : outputs)[at] = key;
                    (phase == 0 ? inputAmounts : outputAmounts)[at++] = BigInteger.valueOf(entry.getValue());
                }
            }
            ports[i] = new Ports(inputs, inputAmounts, outputs, outputAmounts);
            ordinary++;
        }
        budget.checkpoint();
        return ordinary == 0 ? null : new GraphDemandProgram<>(graph.regions(), List.copyOf(keys.keySet()), ports, weight, bytes);
    }
}
