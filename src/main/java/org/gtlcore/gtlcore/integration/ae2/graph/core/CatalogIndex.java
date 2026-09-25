package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.util.*;

/** Resumable producer indexing, including immutable publication of large catalogs. */
public final class CatalogIndex<K> {

    private final List<GraphRecipe<K>> catalog;
    private final Map<K, List<GraphRecipe<K>>> producers = new LinkedHashMap<>();
    private int cursor;
    private Iterator<Map.Entry<K, List<GraphRecipe<K>>>> freezing;
    private GraphCompiler<K> result;

    public CatalogIndex(List<GraphRecipe<K>> catalog) {
        this.catalog = List.copyOf(catalog);
    }

    public boolean step(PlanningBudget budget) {
        budget.check();
        if (cursor < catalog.size()) {
            GraphRecipe<K> recipe = catalog.get(cursor++);
            for (K output : recipe.executionOutputs().keySet()) producers.computeIfAbsent(output, ignored -> new ArrayList<>()).add(recipe);
        } else {
            if (freezing == null) freezing = producers.entrySet().iterator();
            if (freezing.hasNext()) {
                var entry = freezing.next();
                entry.setValue(List.copyOf(entry.getValue()));
            } else {
                result = new GraphCompiler<>(catalog, Collections.unmodifiableMap(producers));
                return true;
            }
        }
        return false;
    }

    public GraphCompiler<K> result() {
        if (result == null) throw new IllegalStateException("Catalog index incomplete");
        return result;
    }
}
