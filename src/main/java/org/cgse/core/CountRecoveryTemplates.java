// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;

/** Completed private-seam programs owned by one immutable catalog. Never caches a solve result. */
final class CountRecoveryTemplates<K> {

    private static final long MAX_WEIGHT = 32768;
    private static final long PREPARATION_BYTES = 128L * MAX_WEIGHT;

    static final class Template<K> {

        final List<GraphRecipe<K>> sources, recipes, retained;
        final Map<String, PlanStep> bodies;
        final Set<K> goals, external;
        final long weight;
        final GraphCompiler<K> completeCompiler, retainedCompiler;

        Template(RecipeCountModel<K> model, List<GraphRecipe<K>> recipes, List<GraphRecipe<K>> retained,
                 Map<String, PlanStep> bodies, Set<K> goals, long weight) {
            sources = List.copyOf(model.recipes);
            this.recipes = List.copyOf(recipes);
            this.retained = List.copyOf(retained);
            this.bodies = Collections.unmodifiableMap(new LinkedHashMap<>(bodies));
            this.goals = goals;
            external = Set.copyOf(model.external);
            this.weight = weight;
            completeCompiler = new GraphCompiler<>(this.recipes);
            retainedCompiler = this.retained.equals(this.recipes) ? completeCompiler : new GraphCompiler<>(this.retained);
        }

        GraphCompiler<K> compiler(Collection<GraphRecipe<K>> view) {
            return view == recipes ? completeCompiler : view == retained ? retainedCompiler : null;
        }
    }

    private final List<Template<K>> templates = new ArrayList<>();
    private boolean requested, reusable, preparing;
    private long weight;
    private long hits, misses, evictions;

    record CacheMetrics(GraphCompiler.CacheEntry entry, GraphCompiler.CacheEntry preparation,
                        List<GraphCompiler<?>> compilers) {}

    synchronized CacheMetrics cacheMetrics() {
        List<GraphCompiler<?>> compilers = new ArrayList<>();
        for (var template : templates) {
            compilers.add(template.completeCompiler);
            compilers.add(template.retainedCompiler);
        }
        return new CacheMetrics(new GraphCompiler.CacheEntry(templates.size(), weight, 128L * weight, hits, misses, evictions),
                new GraphCompiler.CacheEntry(preparing ? 1 : 0, 0, preparing ? PREPARATION_BYTES : 0, 0, 0, 0), List.copyOf(compilers));
    }

    Template<K> reuse(RecipeCountModel<K> model) {
        List<Template<K>> snapshot;
        synchronized (this) {
            reusable |= requested;
            requested = true;
            if (templates.isEmpty()) { misses++; return null; }
            snapshot = List.copyOf(templates);
        }
        // Match the current order outside the catalog monitor. In particular,
        // budget callbacks may wait or cancel without blocking another request.
        Set<K> goals = boundaries(model);
        for (var value : snapshot) {
            if (value.sources.size() != model.recipes.size() || !value.goals.equals(goals) || !value.external.equals(model.external)) continue;
            boolean same = true;
            for (int i = 0; i < value.sources.size(); i++) {
                model.budget.check();
                // Keep order, bindings, slots and configuration semantics. Net
                // columns or recipe ids alone cannot identify an execution.
                if (value.sources.get(i) != model.recipes.get(i)) {
                    same = false;
                    break;
                }
            }
            if (!same) continue;
            synchronized (this) {
                // An evicted immutable snapshot remains valid for this caller,
                // but must not be reinserted over newer resident entries.
                if (templates.remove(value)) templates.add(0, value);
                hits++;
            }
            return value;
        }
        synchronized (this) { misses++; }
        return null;
    }

    Template<K> remember(RecipeCountModel<K> model, Collection<GraphRecipe<K>> recipes,
                         Collection<GraphRecipe<K>> retained, Map<String, PlanStep> bodies) {
        if (recipes.isEmpty() || recipes.size() > 2048 || retained.size() > 2048 ||
                model.recipes.size() > 2048 || bodies.size() > 4096 || model.external.size() > 256) return null;
        synchronized (this) {
            if (!reusable || preparing) return null;
            preparing = true;
        }
        Template<K> value = null;
        try {
            value = prepare(model, recipes, retained, bodies);
            return value;
        } finally {
            synchronized (this) {
                preparing = false;
                if (value != null) {
                    templates.add(0, value);
                    weight += value.weight;
                    while (templates.size() > 8 || weight > MAX_WEIGHT) {
                        weight -= templates.remove(templates.size() - 1).weight;
                        evictions++;
                    }
                }
            }
        }
    }

    private Template<K> prepare(RecipeCountModel<K> model, Collection<GraphRecipe<K>> recipes,
                                Collection<GraphRecipe<K>> retained, Map<String, PlanStep> bodies) {
        // One bounded independent workspace per catalog. Discovery and current
        // source matching still use solver work; these copies only benefit reuse.
        long size = model.recipes.size() + recipes.size() + retained.size() + 4L * bodies.size()
                + model.goals.size() + model.external.size();
        if (size > MAX_WEIGHT || !model.budget.tryCacheScan((int) size)) return null;
        for (var recipe : recipes) {
            long ports = recipe.inputs().size() + (long) recipe.outputs().size();
            size += ports;
            if (size > MAX_WEIGHT || !model.budget.tryCacheScan(1 + (int) ports * 2)) return null;
        }
        var complete = List.copyOf(recipes);
        var kept = List.copyOf(retained);
        if (!complete.equals(kept)) for (var recipe : kept) {
            int outputs = recipe.executionOutputs().size();
            size += outputs;
            if (size > MAX_WEIGHT || !model.budget.tryCacheScan(1 + outputs * 2)) return null;
        }
        Set<K> goals = new HashSet<>();
        for (var goal : model.goals.entrySet()) if (goal.getValue().signum() != 0) goals.add(goal.getKey());
        if (goals.size() > 256) return null;
        var value = new Template<>(model, complete, kept, bodies, Set.copyOf(goals), size);
        model.budget.checkpoint();
        return value;
    }

    private Set<K> boundaries(RecipeCountModel<K> model) {
        Set<K> goals = new HashSet<>();
        model.goals.forEach((key, amount) -> {
            model.budget.check();
            if (amount.signum() != 0) goals.add(key);
        });
        return Set.copyOf(goals);
    }
}
