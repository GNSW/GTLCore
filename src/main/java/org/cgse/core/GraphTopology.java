package org.cgse.core;

import java.util.List;
import java.util.Map;

/** Exact ordered recipe identity, independent of source decisions and inventory. */
final class GraphTopology<K> {

    final List<GraphRecipe<K>> recipes;
    private final int hash;

    GraphTopology(Map<String, GraphRecipe<K>> recipes, PlanningBudget budget) {
        this.recipes = List.copyOf(recipes.values());
        int value = 1;
        for (var recipe : this.recipes) {
            budget.compilationScan();
            value = 31 * value + System.identityHashCode(recipe);
        }
        hash = value;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof GraphTopology<?> key) || hash != key.hash || recipes.size() != key.recipes.size()) return false;
        for (int i = 0; i < recipes.size(); i++) if (recipes.get(i) != key.recipes.get(i)) return false;
        return true;
    }
}
