// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;

/** Optional source ordering after the ordinary priority search has failed. */
final class GraphFallbackSources<K> implements AutoCloseable {

    private final GraphCompiler<K> compiler;
    private final PlanningBudget budget;
    private final Map<K, Integer> distance = new HashMap<>();
    private final Map<K, Double> cost = new HashMap<>();
    private final Map<K, List<GraphRecipe<K>>> near = new HashMap<>(), cheap = new HashMap<>();
    private long memory, started;

    private GraphFallbackSources(GraphCompiler<K> compiler, PlanningBudget budget) {
        this.compiler = compiler;
        this.budget = budget;
    }

    static <K> GraphFallbackSources<K> create(GraphCompiler<K> compiler, Map<K, Long> stock,
                                              Set<K> external, K target, boolean force, PlanningBudget budget) {
        if (compiler.catalog().size() > 2048 || budget.remainingWork() < 24_576) return null;
        var result = new GraphFallbackSources<>(compiler, budget);
        long bytes = 65_536L + 2048L * compiler.catalog().size();
        if (!budget.tryReserve(bytes)) return null;
        result.memory = bytes;
        result.started = budget.nodes();
        try {
            if (!result.reachability(stock, external, target, force)) {
                result.close();
                return null;
            }
            // Reachability is now complete. Cost relaxation only orders these
            // sources; incomplete cost estimates never exclude a candidate.
            for (int pass = 0; pass < 8; pass++) for (var recipe : compiler.catalog()) {
                double value = 0;
                for (var input : recipe.inputs().entrySet()) {
                    if (!result.tick()) return result;
                    value += input.getValue() * result.cost.getOrDefault(input.getKey(), Double.POSITIVE_INFINITY);
                }
                for (var output : recipe.outputs().entrySet()) {
                    if (!result.tick()) return result;
                    double unit = value / output.getValue();
                    if (unit < result.cost.getOrDefault(output.getKey(), Double.POSITIVE_INFINITY)) result.cost.put(output.getKey(), unit);
                }
            }
            return result;
        } catch (RuntimeException | Error failure) {
            result.close();
            throw failure;
        }
    }

    private boolean tick() {
        if (budget.nodes() - started >= 8192) return false;
        budget.check();
        return true;
    }

    private boolean reachability(Map<K, Long> stock, Set<K> external, K target, boolean force) {
        var catalog = compiler.catalog();
        long workspace = 256L + 16L * catalog.size();
        if (!budget.tryReserve(workspace)) return false;
        try {
            int[] remaining = new int[catalog.size()], depth = new int[catalog.size()];
            Map<K, List<Integer>> consumers = new HashMap<>();
            Deque<K> reached = new ArrayDeque<>();
            // Visit only catalog keys. Stock seeds of depth zero must all be
            // queued before the depth-one outputs of recipes without inputs.
            for (int i = 0; i < catalog.size(); i++) {
                var recipe = catalog.get(i);
                remaining[i] = recipe.inputs().size();
                for (K key : recipe.inputs().keySet()) {
                    if (!seed(key, stock, external, target, force, reached)) return false;
                    var waiting = consumers.get(key);
                    if (waiting == null) {
                        if (!budget.tryReserve(128)) return false;
                        workspace += 128;
                        waiting = new ArrayList<>();
                        consumers.put(key, waiting);
                    }
                    if (!budget.tryReserve(32)) return false;
                    workspace += 32;
                    waiting.add(i);
                }
                for (K key : recipe.outputs().keySet())
                    if (!seed(key, stock, external, target, force, reached)) return false;
            }
            for (int i = 0; i < catalog.size(); i++) if (remaining[i] == 0)
                for (K key : catalog.get(i).outputs().keySet())
                    if (!reach(key, 1, reached)) return false;
            // FIFO processes increasing depths. Each input key is announced
            // once, so the last input enables a recipe at its minimum depth.
            // A cutoff discards this whole pass; partial reachability cannot
            // justify excluding any of the original sources.
            while (!reached.isEmpty()) {
                K key = reached.removeFirst();
                for (int id : consumers.getOrDefault(key, List.of())) {
                    if (!tick()) return false;
                    depth[id] = Math.max(depth[id], distance.get(key));
                    if (--remaining[id] == 0)
                        for (K output : catalog.get(id).outputs().keySet())
                            if (!reach(output, depth[id] + 1, reached)) return false;
                }
            }
            return true;
        } finally {
            budget.release(workspace);
        }
    }

    private boolean reach(K key, int depth, Deque<K> reached) {
        if (!tick()) return false;
        if (!distance.containsKey(key)) {
            if (!reserveKey()) return false;
            distance.put(key, depth);
            reached.addLast(key);
        }
        return true;
    }

    private boolean seed(K key, Map<K, Long> stock, Set<K> external, K target, boolean force, Deque<K> reached) {
        if (!tick()) return false;
        long have = force && target.equals(key) ? 0 : stock.getOrDefault(key, 0L);
        if (external.contains(key) || have > 0) {
            if (!distance.containsKey(key)) {
                if (!reserveKey()) return false;
                distance.put(key, 0);
                reached.addLast(key);
            }
            cost.put(key, external.contains(key) ? 0 : 1.0 / have);
        }
        return true;
    }

    private boolean reserveKey() {
        if (!budget.tryReserve(256)) return false;
        memory += 256;
        return true;
    }

    List<GraphRecipe<K>> sources(K key, boolean byCost) {
        var cache = byCost ? cheap : near;
        var cached = cache.get(key);
        if (cached != null) return cached;
        var original = compiler.producers(key);
        long bytes = 128L + 128L * original.size();
        if (!budget.tryReserve(bytes)) return original;
        memory += bytes;
        var scored = new ArrayList<Source<K>>();
        for (var recipe : original) {
            budget.check();
            boolean live = true;
            double score = 0;
            for (var input : recipe.inputs().entrySet()) {
                budget.check();
                Integer d = distance.get(input.getKey());
                if (d == null) {
                    live = false;
                    break;
                }
                score += byCost ? input.getValue() * cost.getOrDefault(input.getKey(), Double.POSITIVE_INFINITY) : d;
            }
            if (live) scored.add(new Source<>(recipe, byCost ? score / recipe.outputs().getOrDefault(key, 1L) : score));
        }
        // Stable ties retain the provider's original priority order.
        scored.sort((a, b) -> {
            budget.check();
            return Double.compare(a.score(), b.score());
        });
        var result = scored.stream().map(Source::recipe).toList();
        cache.put(key, result);
        return result;
    }

    private record Source<K>(GraphRecipe<K> recipe, double score) {}

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
