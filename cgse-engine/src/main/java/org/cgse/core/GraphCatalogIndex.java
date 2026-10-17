// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Immutable catalog coordinates and packed ports. Quantities keep exact long/BigInteger semantics. */
final class GraphCatalogIndex<K> {

    /** Internal read-only arrays; masks, stock and requirements never live here. */
    record Ports(int[] inputs, long[] inputAmounts, long[] configurationAmounts, long[] reusableAmounts,
                 int[] outputs, long[] outputAmounts, int[] physicalOutputs, int[] changed, BigInteger[] changes) {}

    private static final int[] EMPTY = new int[0];
    private final List<GraphRecipe<K>> catalog;
    private final List<K> resources;
    private final Map<K, Integer> resourceIds;
    private final Map<GraphRecipe<K>, Ports> ports;
    private final int[][] consumers, producers;

    private GraphCatalogIndex(List<GraphRecipe<K>> catalog, Map<K, Integer> resources,
                              Map<GraphRecipe<K>, Ports> ports, int[][] consumers, int[][] producers) {
        this.catalog = catalog;
        this.resources = List.copyOf(resources.keySet());
        resourceIds = Collections.unmodifiableMap(resources);
        this.ports = Collections.unmodifiableMap(ports);
        this.consumers = consumers;
        this.producers = producers;
    }

    boolean belongsTo(List<GraphRecipe<K>> owner) {
        return catalog == owner;
    }

    int resourceCount() {
        return resources.size();
    }

    long estimatedBytes() {
        long bytes = 256L + 160L * resources.size() + 64L * ports.size();
        for (var port : ports.values()) bytes += 192L + 28L * port.inputs.length + 12L * port.outputs.length +
                4L * port.physicalOutputs.length + 64L * port.changed.length;
        for (int[] row : consumers) bytes += 24L + 4L * row.length;
        for (int[] row : producers) bytes += 24L + 4L * row.length;
        return bytes;
    }

    K resource(int id) {
        return resources.get(id);
    }

    int resourceId(K key) {
        return resourceIds.get(key);
    }

    Ports ports(GraphRecipe<K> recipe) {
        return ports.get(recipe);
    }

    int[] consumers(K key) {
        Integer id = resourceIds.get(key);
        return id == null ? EMPTY : consumers[id];
    }

    /** Physical producer adjacency, independent of source-priority ordering. */
    int[] producers(K key) {
        Integer id = resourceIds.get(key);
        return id == null ? EMPTY : producers[id];
    }

    static final class Builder<K> implements AutoCloseable {

        private final List<GraphRecipe<K>> catalog;
        private final PlanningBudget budget;
        private final long allowance;
        private final long workAllowance;
        private Map<K, Integer> resources = new LinkedHashMap<>();
        private Map<GraphRecipe<K>, Ports> ports = new IdentityHashMap<>();
        private final List<List<Integer>> consumers = new ArrayList<>(), producers = new ArrayList<>();
        private int[][] frozenConsumers, frozenProducers;
        private GraphCatalogIndex<K> result;
        private int recipes, entries, freezing;
        private long memory, work, operationStarted;
        private boolean declined;

        Builder(List<GraphRecipe<K>> catalog, PlanningBudget budget) {
            this.catalog = catalog;
            this.budget = budget;
            long before = budget.threadWork();
            try {
                workAllowance = Math.min(262144, budget.remainingWork() / 16);
                allowance = Math.min(8L << 20, budget.availableBytes() / 8);
                if (catalog.size() > 8192 || workAllowance < 1024 || !reserve(256L + 32L * catalog.size())) decline();
            } finally {
                work += budget.threadWork() - before;
            }
        }

        boolean declined() {
            return declined;
        }

        private boolean scan() {
            if (work + budget.threadWork() - operationStarted >= workAllowance) {
                decline();
                return false;
            }
            budget.compilationCheck();
            return true;
        }

        private int resource(K key) {
            Integer old = resources.get(key);
            if (old != null) return old;
            if (resources.size() >= 8192 || !reserve(192)) {
                decline();
                return -1;
            }
            int id = resources.size();
            resources.put(key, id);
            consumers.add(new ArrayList<>());
            producers.add(new ArrayList<>());
            return id;
        }

        void add(GraphRecipe<K> recipe, int id) {
            operationStarted = budget.threadWork();
            try {
                append(recipe, id);
            } finally {
                work += budget.threadWork() - operationStarted;
            }
        }

        private void append(GraphRecipe<K> recipe, int id) {
            if (declined) return;
            if (id != recipes || recipe != catalog.get(id)) throw new IllegalArgumentException("Catalog indexing order changed");
            recipes++;
            int n = recipe.inputs().size(), m = recipe.outputs().size();
            entries += n + m;
            if (entries > 65536 || !reserve(384L + 128L * (n + m))) {
                decline();
                return;
            }
            int[] inputs = new int[n], outputs = new int[m], physical = new int[recipe.executionOutputs().size()];
            long[] inputAmounts = new long[n], outputAmounts = new long[m], configurations = new long[n], reusable = new long[n];
            Map<Integer, BigInteger> changes = new LinkedHashMap<>();
            int at = 0;
            for (var entry : recipe.inputs().entrySet()) {
                if (!scan()) return;
                int key = resource(entry.getKey());
                if (declined) return;
                inputs[at] = key;
                inputAmounts[at] = entry.getValue();
                configurations[at] = recipe.configurationInputs().getOrDefault(entry.getKey(), 0L);
                reusable[at] = recipe.reusableInputs().getOrDefault(entry.getKey(), 0L);
                changes.put(key, BigInteger.valueOf(entry.getValue()).subtract(BigInteger.valueOf(configurations[at])).add(BigInteger.valueOf(reusable[at])).negate());
                consumers.get(key).add(id);
                at++;
            }
            at = 0;
            for (var entry : recipe.outputs().entrySet()) {
                if (!scan()) return;
                int key = resource(entry.getKey());
                if (declined) return;
                outputs[at] = key;
                outputAmounts[at++] = entry.getValue();
                changes.merge(key, BigInteger.valueOf(entry.getValue()), BigInteger::add);
            }
            at = 0;
            for (K key : recipe.executionOutputs().keySet()) {
                if (!scan()) return;
                int index = resources.get(key);
                physical[at++] = index;
                producers.get(index).add(id);
            }
            changes.values().removeIf(value -> value.signum() == 0);
            ports.put(recipe, new Ports(inputs, inputAmounts, configurations, reusable, outputs, outputAmounts, physical,
                    changes.keySet().stream().mapToInt(Integer::intValue).toArray(), changes.values().toArray(BigInteger[]::new)));
        }

        boolean step() {
            // A compilation can be parked while other source views run, or
            // resume on another worker. Only this call's work belongs to the
            // builder; nested resource/freeze scans share this same interval.
            operationStarted = budget.threadWork();
            try {
                return advance();
            } finally {
                work += budget.threadWork() - operationStarted;
            }
        }

        private boolean advance() {
            if (declined || result != null) return true;
            if (!scan()) return true;
            if (recipes != catalog.size()) throw new IllegalStateException("Incomplete catalog index");
            if (frozenConsumers == null) {
                if (!reserve(64L + 32L * resources.size() + 8L * entries)) {
                    decline();
                    return true;
                }
                frozenConsumers = new int[resources.size()][];
                frozenProducers = new int[resources.size()][];
            }
            if (freezing < resources.size()) {
                frozenConsumers[freezing] = freeze(consumers.get(freezing));
                if (declined) return true;
                frozenProducers[freezing] = freeze(producers.get(freezing));
                if (declined) return true;
                freezing++;
                return false;
            }
            result = new GraphCatalogIndex<>(catalog, resources, ports, frozenConsumers, frozenProducers);
            resources = null;
            ports = null;
            close();
            return true;
        }

        private int[] freeze(List<Integer> source) {
            int[] result = new int[source.size()];
            for (int i = 0; i < result.length; i++) {
                if (!scan()) return null;
                result[i] = source.get(i);
            }
            return result;
        }

        GraphCatalogIndex<K> result() {
            return result;
        }

        private boolean reserve(long bytes) {
            if (bytes > allowance - memory || !budget.tryReserve(bytes)) return false;
            memory += bytes;
            return true;
        }

        private void decline() {
            declined = true;
            close();
        }

        @Override
        public void close() {
            if (resources != null) resources.clear();
            if (ports != null) ports.clear();
            consumers.clear();
            producers.clear();
            frozenConsumers = frozenProducers = null;
            budget.release(memory);
            memory = 0;
        }
    }
}
