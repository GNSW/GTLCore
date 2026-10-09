// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;

/** Compact immutable input adjacency for inventory-dependent source heuristics. */
final class GraphSourceIndex<K> {

    final List<GraphRecipe<K>> recipes;
    final Map<K, int[]> consumers;
    final int[] required, unconditional;
    final long bytes, buildWork;

    private GraphSourceIndex(List<GraphRecipe<K>> recipes, Map<K, int[]> consumers, int[] required,
                             int[] unconditional, long bytes, long buildWork) {
        this.recipes = recipes;
        this.consumers = Collections.unmodifiableMap(consumers);
        this.required = required;
        this.unconditional = unconditional;
        this.bytes = bytes;
        this.buildWork = buildWork;
    }

    static <K> GraphSourceIndex<K> create(GraphCompiler<K> compiler, PlanningBudget budget, long maximumWork) {
        var known = compiler.sourceIndex();
        // Cache hits save real work, but do not admit a different heuristic
        // representation from the same structural preparation allowance.
        if (known != null) return known.buildWork <= maximumWork ? known : null;
        try (var builder = new Builder<K>(compiler.catalog(), budget, maximumWork)) {
            var result = builder.build();
            if (result != null) compiler.rememberSourceIndex(result);
            return result;
        }
    }

    private static final class Builder<K> implements AutoCloseable {

        private final List<GraphRecipe<K>> recipes;
        private final PlanningBudget budget;
        private final long maximumWork, maximumBytes;
        private long memory, buildTicks;

        Builder(List<GraphRecipe<K>> recipes, PlanningBudget budget, long maximumWork) {
            this.recipes = recipes;
            this.budget = budget;
            this.maximumWork = maximumWork;
            maximumBytes = Math.min(16L << 20, budget.availableBytes() / 4);
        }

        private boolean scan() {
            if (PlanningBudget.units(buildTicks) >= maximumWork) return false;
            buildTicks += budget.compilationScan();
            return true;
        }

        private boolean reserve(long bytes) {
            if (bytes > maximumBytes - memory || !budget.tryReserve(bytes)) return false;
            memory += bytes;
            return true;
        }

        GraphSourceIndex<K> build() {
            if (!reserve(1024L + 16L * recipes.size())) return null;
            var waiting = new HashMap<K, Ints>();
            var free = new Ints();
            int[] required = new int[recipes.size()];
            long entries = 0;
            for (int id = 0; id < recipes.size(); id++) {
                if (!scan()) return null;
                var recipe = recipes.get(id);
                required[id] = recipe.inputs().size();
                if (required[id] == 0) free.add(id);
                for (K key : recipe.inputs().keySet()) {
                    if (!scan() || !reserve(16)) return null;
                    var consumers = waiting.get(key);
                    if (consumers == null) {
                        if (!reserve(160)) return null;
                        consumers = new Ints();
                        waiting.put(key, consumers);
                    }
                    consumers.add(id);
                    entries++;
                }
            }
            var consumers = new HashMap<K, int[]>();
            for (var entry : waiting.entrySet()) {
                if (!scan()) return null;
                long work = (entry.getValue().size + 3L) / 4;
                if (work > maximumWork - PlanningBudget.units(buildTicks)) return null;
                budget.compilationCharge(work);
                buildTicks += work * PlanningBudget.WORK_SCALE;
                consumers.put(entry.getKey(), entry.getValue().array());
            }
            long bytes = 1024L + 4L * recipes.size() + 4L * free.size + 80L * consumers.size() + 4L * entries;
            return new GraphSourceIndex<>(recipes, consumers, required, free.array(), bytes, PlanningBudget.units(buildTicks));
        }

        @Override
        public void close() {
            budget.release(memory);
            memory = 0;
        }
    }

    private static final class Ints {

        private int[] values = new int[4];
        private int size;

        void add(int value) {
            if (size == values.length) values = Arrays.copyOf(values, Math.multiplyExact(size, 2));
            values[size++] = value;
        }

        int[] array() {
            return Arrays.copyOf(values, size);
        }
    }
}
