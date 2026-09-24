package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.util.*;

/** Captured recipe values only. Sorting/indexing runs on a planner, never against the live world. */
public final class PreparedCatalog<K> {

    private final List<GraphRecipe<K>> recipes;
    private final Map<String, Integer> priorities;
    private volatile GraphCompiler<K> compiled;

    public PreparedCatalog(List<GraphRecipe<K>> recipes, Map<String, Integer> priorities) {
        this.recipes = List.copyOf(recipes);
        this.priorities = Map.copyOf(priorities);
    }

    public int size() {
        return recipes.size();
    }

    public Build build(PlanningBudget budget) {
        return new Build(budget);
    }

    public final class Build implements PlanningScheduler.Work<GraphCompiler<K>> {

        private final PlanningBudget budget;
        private final NavigableMap<Integer, List<GraphRecipe<K>>> buckets = new TreeMap<>(Comparator.reverseOrder());
        private final List<GraphRecipe<K>> sorted = new ArrayList<>();
        private int cursor;
        private Iterator<List<GraphRecipe<K>>> groups;
        private Iterator<GraphRecipe<K>> group;
        private CatalogIndex<K> indexing;
        private GraphCompiler<K> result;

        private Build(PlanningBudget budget) {
            this.budget = budget;
        }

        @Override
        public boolean advance(PlanningScheduler.Slice slice) {
            if (compiled != null) {
                result = compiled;
                return true;
            }
            budget.phase(PlanningBudget.Phase.BUILD);
            while (slice.next()) {
                for (int batch = 0; batch < 32; batch++) {
                    budget.check();
                    if (cursor < recipes.size()) {
                        var recipe = recipes.get(cursor++);
                        buckets.computeIfAbsent(priorities.getOrDefault(recipe.binding(), Integer.MIN_VALUE), ignored -> new ArrayList<>()).add(recipe);
                    } else if (indexing == null) {
                        if (groups == null) groups = buckets.values().iterator();
                        if (group != null && group.hasNext()) sorted.add(group.next());
                        else if (groups.hasNext()) group = groups.next().iterator();
                        else indexing = new CatalogIndex<>(sorted);
                    } else if (indexing.step(budget)) {
                        synchronized (PreparedCatalog.this) {
                            if (compiled == null) compiled = indexing.result();
                            result = compiled;
                        }
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public GraphCompiler<K> result() {
            if (result == null) throw new IllegalStateException("Catalog preparation incomplete");
            return result;
        }
    }
}
