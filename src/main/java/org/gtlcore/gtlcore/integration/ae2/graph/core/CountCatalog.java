package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Immutable all-source closure in catalog recipe/material coordinates. Only
 * incidence and exact coefficients are retained; inventory, goals and branch
 * assumptions are overlays owned by the current request. The external supply
 * projection is part of the cache key, never inferred from current stock.
 * This must never be replaced by a selected-source compilation.
 */
final class CountCatalog<K> {

    final List<GraphRecipe<K>> recipes;
    final List<K> keys;
    final Map<K, Integer> ids;
    final List<Map<Integer, BigInteger>> terms;
    final long incidences;

    CountCatalog(RecipeCountModel<K> model, long entries, PlanningBudget budget) {
        recipes = List.copyOf(model.recipes);
        keys = List.copyOf(model.keys);
        ids = model.ids;
        var rows = new ArrayList<Map<Integer, BigInteger>>(Collections.nCopies(keys.size(), Map.of()));
        // Borrow only the immutable material coefficients already built by
        // the first request. Never retain its RHS, production obligation or
        // mutable constraint list, and do not rebuild a second cold matrix.
        for (int i = 0; i < model.rowKeys.size(); i++) {
            budget.checkpoint();
            rows.set(ids.get(model.rowKeys.get(i)), model.constraints.get(i).terms());
        }
        terms = List.copyOf(rows);
        incidences = entries;
    }

    long weight() {
        return incidences + recipes.size() + keys.size();
    }

    long sparseBytes() {
        return 128L * weight();
    }
}
