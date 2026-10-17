// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;

/** Positive source hints for a missing seed inside a selected cyclic region. */
final class GraphSeedEntries<K> implements AutoCloseable {
    record Entry<K>(K key, GraphRecipe<K> recipe) {}

    private final GraphCompiler<K> compiler;
    private final GraphCompiler.Compiled<K> graph;
    private final Set<K> missing, external;
    private final Map<K, Long> stock;
    private final Set<String> excluded;
    private final Map<K, Set<String>> tried;
    private final PlanningBudget budget;
    private final long allowance;
    private final List<Entry<K>> entries = new ArrayList<>();
    private Set<K> outputs = new HashSet<>();
    private Iterator<GraphCompiler.Region<K>> regions;
    private GraphCompiler.Region<K> region;
    private Iterator<GraphRecipe<K>> recipes, alternatives;
    private Iterator<K> keys;
    private Iterator<Map.Entry<K, Long>> inputs;
    private GraphRecipe<K> source, other, best;
    private K key, bestKey;
    private double score, bestScore;
    private long work, memory, regionMemory;
    private int phase;
    private boolean relevant, complete;

    GraphSeedEntries(GraphCompiler<K> compiler, GraphCompiler.Compiled<K> graph, Set<K> missing,
                     Map<K, Long> stock, Set<K> external, Set<String> excluded, Map<K, Set<String>> tried,
                     PlanningBudget budget, long allowance) {
        this.compiler = compiler;
        this.graph = graph;
        this.missing = missing;
        this.stock = stock;
        this.external = external;
        this.excluded = excluded;
        this.tried = tried;
        this.budget = budget;
        this.allowance = Math.max(0, allowance);
        if (this.allowance == 0 || !reserve(1024)) complete = true;
        else regions = graph.regions().iterator();
    }

    /** Retain the region/provider/input cursors across coordinator turns. */
    boolean step() {
        budget.checkpoint();
        if (complete) return true;
        if (work >= allowance) return complete = true;
        long before = budget.threadSearchWork();
        try {
            budget.check();
            switch (phase) {
                case 0 -> {
                    if (!regions.hasNext()) return complete = true;
                    region = regions.next();
                    if (!region.cyclic()) return false;
                    recipes = region.recipes().iterator();
                    keys = Collections.emptyIterator();
                    relevant = false;
                    best = null;
                    bestScore = Double.POSITIVE_INFINITY;
                    phase = 1;
                }
                case 1 -> {
                    if (keys.hasNext()) {
                        K output = keys.next();
                        if (!outputs.contains(output)) {
                            if (!reserve(96)) return complete = true;
                            regionMemory += 96;
                            outputs.add(output);
                        }
                        relevant |= missing.contains(output);
                    } else if (recipes.hasNext()) keys = recipes.next().executionOutputs().keySet().iterator();
                    else if (!relevant) finishRegion();
                    else {
                        recipes = region.recipes().iterator();
                        phase = 2;
                    }
                }
                case 2 -> {
                    if (keys.hasNext()) {
                        key = keys.next();
                        if (graph.selected().get(key) == source) {
                            alternatives = compiler.producers(key).iterator();
                            phase = 3;
                        }
                    } else if (recipes.hasNext()) {
                        source = recipes.next();
                        keys = source.executionOutputs().keySet().iterator();
                    } else {
                        if (best != null) {
                            if (!reserve(160)) return complete = true;
                            entries.add(new Entry<>(bestKey, best));
                        }
                        finishRegion();
                    }
                }
                case 3 -> {
                    if (!alternatives.hasNext()) phase = 2;
                    else {
                        other = alternatives.next();
                        if (other == source || other.executionOutputs().getOrDefault(key, 0L) == 0 ||
                                excluded.contains(other.id()) || tried.getOrDefault(key, Set.of()).contains(other.id())) return false;
                        score = 0;
                        inputs = other.inputs().entrySet().iterator();
                        phase = 4;
                    }
                }
                case 4 -> {
                    if (inputs.hasNext()) {
                        var input = inputs.next();
                        K resource = input.getKey();
                        long available = stock.getOrDefault(resource, 0L);
                        if (outputs.contains(resource) || !external.contains(resource) && available < input.getValue()) phase = 3;
                        else if (!external.contains(resource)) score += input.getValue() / (double) available;
                    } else {
                        // Prefer ample captured supply over repeatedly trying
                        // a different scrap item that only funds one small lot.
                        // This dimensionless stock fraction is a ranking only;
                        // it proves neither sufficient quantity nor execution.
                        score /= other.executionOutputs().get(key);
                        if (score < bestScore) {
                            best = other;
                            bestKey = key;
                            bestScore = score;
                        }
                        phase = 3;
                    }
                }
                default -> throw new IllegalStateException("Unknown seed-entry phase");
            }
            return false;
        } finally {
            work += budget.threadSearchWork() - before;
        }
    }

    private boolean reserve(long bytes) {
        if (!budget.tryReserve(bytes)) return false;
        memory += bytes;
        return true;
    }

    private void finishRegion() {
        // Drop the backing table before releasing its reservation. Clearing a
        // HashSet retains its peak capacity across subsequent small regions.
        outputs = new HashSet<>();
        keys = null;
        inputs = null;
        budget.release(regionMemory);
        memory -= regionMemory;
        regionMemory = 0;
        phase = 0;
    }

    List<Entry<K>> entries() {
        if (!complete) throw new IllegalStateException("Entry scan incomplete");
        return Collections.unmodifiableList(entries);
    }

    @Override
    public void close() {
        complete = true;
        entries.clear();
        outputs.clear();
        regions = null;
        region = null;
        recipes = alternatives = null;
        keys = null;
        inputs = null;
        source = other = best = null;
        key = bestKey = null;
        budget.release(memory);
        memory = regionMemory = 0;
    }
}
