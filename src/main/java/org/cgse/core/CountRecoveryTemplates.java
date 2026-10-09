// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;

/** Completed private-seam programs owned by one immutable catalog. Never caches a solve result. */
final class CountRecoveryTemplates<K> {

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
            retainedCompiler = retained.size() == recipes.size() ? completeCompiler : new GraphCompiler<>(this.retained);
        }

        GraphCompiler<K> compiler(Collection<GraphRecipe<K>> view) {
            return view == recipes ? completeCompiler : view == retained ? retainedCompiler : null;
        }
    }

    private final List<Template<K>> templates = new ArrayList<>();
    private boolean requested, reusable;
    private long weight;
    private long hits, misses, evictions;

    record CacheMetrics(GraphCompiler.CacheEntry entry, List<GraphCompiler<?>> compilers) {}

    synchronized CacheMetrics cacheMetrics() {
        List<GraphCompiler<?>> compilers = new ArrayList<>();
        for (var template : templates) {
            compilers.add(template.completeCompiler);
            compilers.add(template.retainedCompiler);
        }
        return new CacheMetrics(new GraphCompiler.CacheEntry(templates.size(), weight, 128L * weight, hits, misses, evictions), List.copyOf(compilers));
    }

    synchronized Template<K> reuse(RecipeCountModel<K> model) {
        var result = reuseChecked(model);
        if (result == null) misses++; else hits++;
        return result;
    }

    private Template<K> reuseChecked(RecipeCountModel<K> model) {
        reusable |= requested;
        requested = true;
        if (templates.isEmpty()) return null;
        Set<K> goals = boundaries(model);
        for (int at = 0; at < templates.size(); at++) {
            Template<K> value = templates.get(at);
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
            templates.remove(at);
            templates.add(0, value);
            return value;
        }
        return null;
    }

    synchronized Template<K> remember(RecipeCountModel<K> model, Collection<GraphRecipe<K>> recipes,
                                      Collection<GraphRecipe<K>> retained, Map<String, PlanStep> bodies) {
        if (!reusable || recipes.isEmpty() || recipes.size() > 2048 || model.recipes.size() > 2048 || bodies.size() > 4096) return null;
        long size = model.recipes.size() + recipes.size() + retained.size() + 4L * bodies.size();
        for (var recipe : recipes) {
            model.budget.check();
            size += recipe.inputs().size() + recipe.outputs().size();
            if (size > 32768) return null;
        }
        Set<K> goals = boundaries(model);
        if (goals.size() > 256 || model.external.size() > 256) return null;
        var value = new Template<>(model, List.copyOf(recipes), List.copyOf(retained), bodies, goals, size);
        templates.add(0, value);
        weight += size;
        while (templates.size() > 8 || weight > 32768) {
            weight -= templates.remove(templates.size() - 1).weight;
            evictions++;
        }
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
