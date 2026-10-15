// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

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
    final Set<K> external;
    final long incidences;
    private final Map<GraphRecipe<K>, Integer> columns;

    CountCatalog(RecipeCountModel<K> model, long entries, PlanningBudget budget) {
        recipes = List.copyOf(model.recipes);
        keys = List.copyOf(model.keys);
        ids = model.ids;
        external = Set.copyOf(model.external);
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
        var positions = new IdentityHashMap<GraphRecipe<K>, Integer>();
        for (int i = 0; i < recipes.size(); i++) {
            budget.checkpoint();
            positions.put(recipes.get(i), i);
        }
        columns = Collections.unmodifiableMap(positions);
    }

    long weight() {
        return incidences + recipes.size() + keys.size() + external.size();
    }

    long sparseBytes() {
        return 128L * weight();
    }

    /**
     * Match immutable recipe identities, then translate donor coefficients to
     * the current coordinates and patch added/removed recipes. Only materials
     * whose complete old row is present can use this path; other rows are built
     * from all current recipes. Stable rows retain their coefficient analysis.
     * No RHS, domain, source-completeness claim or proof crosses requests.
     */
    Map<K, Map<Integer, BigInteger>> unchangedRows(List<GraphRecipe<K>> current, List<K> currentKeys,
                                                Set<K> supplied, PlanningBudget budget) {
        int columns = Math.max(recipes.size(), current.size());
        // Only inspect a donor when enough work remains to profit from it.
        if (columns == 0 || incidences < 4L * columns || columns > budget.remainingWork() / 32) return Map.of();
        int[] mapped = new int[recipes.size()];
        Arrays.fill(mapped, -1);
        var added = new ArrayList<Integer>();
        int unchanged = 0;
        boolean moved = false;
        for (int i = 0; i < current.size(); i++) {
            budget.check();
            Integer old = this.columns.get(current.get(i));
            if (old == null) added.add(i);
            else {
                mapped[old] = i;
                unchanged++;
                moved |= old != i;
            }
        }
        if (unchanged * 4L < columns * 3L) return Map.of();
        Set<K> changed = new HashSet<>();
        for (int i = 0; i < recipes.size(); i++) {
            budget.check();
            if (mapped[i] < 0) {
                var recipe = recipes.get(i);
                for (K key : recipe.inputs().keySet()) { budget.check(); changed.add(key); }
                for (K key : recipe.outputs().keySet()) { budget.check(); changed.add(key); }
            }
        }
        long saved = 0;
        int rebuilt = 0;
        for (K key : currentKeys) {
            budget.check();
            if (supplied.contains(key)) continue;
            Integer id = ids.get(key);
            if (id == null || external.contains(key)) { rebuilt++; continue; }
            saved += terms.get(id).size();
        }
        // Reconstructing affected rows uses direct recipe lookups. If that
        // rectangular scan costs more than the sparse pass, keep the cold path.
        if (saved <= (long) rebuilt * current.size() + columns + currentKeys.size()) return Map.of();
        // Analyze only newly participating recipes. A new producer's exact
        // contribution joins the same material row; it cannot hide behind an
        // old row signature or a stale source-completeness claim.
        var additions = new HashMap<K, Map<Integer, BigInteger>>();
        for (int column : added) {
            var recipe = current.get(column);
            Set<K> used = new LinkedHashSet<>(recipe.inputs().keySet());
            used.addAll(recipe.outputs().keySet());
            for (K key : used) {
                budget.check();
                changed.add(key);
                if (supplied.contains(key) || external.contains(key) || !ids.containsKey(key)) continue;
                var value = RecipeCountModel.coefficient(recipe, key);
                if (value.signum() != 0) additions.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(column, value);
            }
        }
        var shared = new LinkedHashMap<K, Map<Integer, BigInteger>>();
        for (K key : currentKeys) {
            budget.check();
            if (supplied.contains(key) || external.contains(key)) continue;
            Integer id = ids.get(key);
            if (id == null) continue;
            var original = terms.get(id);
            if (!moved && !changed.contains(key)) shared.put(key, original);
            else {
                var row = new ExactLinearProgram.RowBuilder();
                for (var term : original.entrySet()) {
                    // Coordinate lookup/reference copy, not another exact
                    // material-delta calculation. Use the existing scan cost.
                    budget.operation(PlanningBudget.Operation.SCAN, 0);
                    int next = mapped[term.getKey()];
                    if (next >= 0) row.add(next, term.getValue());
                }
                var extra = additions.get(key);
                if (extra != null) for (var term : extra.entrySet()) {
                    budget.operation(PlanningBudget.Operation.SCAN, 0);
                    row.add(term.getKey(), term.getValue());
                }
                var translated = row.build(BigInteger.ZERO).terms();
                shared.put(key, translated.equals(original) ? original : translated);
            }
        }
        return shared;
    }
}
