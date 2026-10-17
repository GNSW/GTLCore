// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;

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

    /** Rebind only the ordered physical dependency partition. Quantities,
     * bindings, slot semantics and all request-local selections stay current.
     * The caller owns the bounded temporary mapping and result reservation. */
    Rebinding<K> rebind(GraphTopology<K> donor, List<GraphCompiler.Region<K>> regions, PlanningBudget budget) {
        return new Rebinding<>(this, donor, regions, budget);
    }

    /** Retained matching cursor: changing sources cannot turn a scheduler step
     * into an unbounded scan of all ports in a large selected graph. */
    static final class Rebinding<K> {
        private final GraphTopology<K> current, donor;
        private final List<GraphCompiler.Region<K>> regions;
        private final PlanningBudget budget;
        private final Map<GraphRecipe<K>, GraphRecipe<K>> replacements = new IdentityHashMap<>();
        private final List<GraphCompiler.Region<K>> rebound = new ArrayList<>();
        private Iterator<K> left, right;
        private GraphRecipe<K> before, after;
        private List<GraphRecipe<K>> members;
        private List<GraphCompiler.Region<K>> result;
        private int recipe, region, member;
        private boolean physical, complete;

        private Rebinding(GraphTopology<K> current, GraphTopology<K> donor,
                          List<GraphCompiler.Region<K>> regions, PlanningBudget budget) {
            this.current = current; this.donor = donor; this.regions = regions; this.budget = budget;
            complete = current.recipes.size() != donor.recipes.size();
        }

        boolean step() {
            for (int operation = 0; operation < 32 && !complete; operation++) {
                budget.compilationScan();
                if (left != null) {
                    if (left.hasNext()) {
                        K a = left.next(), b = right.next();
                        // Output insertion keys influence HashMap hub order,
                        // including identity tie-breaks in collision tree bins.
                        // Inputs only perform ordered lookups.
                        if (physical ? a != b : !a.equals(b)) complete = true;
                    } else if (!physical) {
                        left = before.executionOutputs().keySet().iterator();
                        right = after.executionOutputs().keySet().iterator();
                        physical = true;
                    } else {
                        replacements.put(before, after);
                        left = right = null;
                        recipe++;
                    }
                } else if (recipe < current.recipes.size()) {
                    before = donor.recipes.get(recipe); after = current.recipes.get(recipe);
                    if (before == after) { recipe++; continue; }
                    if (before.inputs().size() != after.inputs().size() ||
                            before.executionOutputs().size() != after.executionOutputs().size()) {
                        complete = true;
                        continue;
                    }
                    left = before.inputs().keySet().iterator(); right = after.inputs().keySet().iterator();
                    physical = false;
                } else if (region < regions.size()) {
                    var source = regions.get(region);
                    if (member < source.recipes().size()) {
                        var replacement = replacements.get(source.recipes().get(member));
                        if (replacement != null) {
                            if (members == null) {
                                budget.compilationCharge((source.recipes().size() + 3L) / 4);
                                members = new ArrayList<>(source.recipes());
                            }
                            members.set(member, replacement);
                        }
                        member++;
                    } else {
                        rebound.add(members == null ? source : new GraphCompiler.Region<>(List.copyOf(members), source.cyclic()));
                        members = null; member = 0; region++;
                    }
                } else {
                    result = List.copyOf(rebound);
                    complete = true;
                }
            }
            return complete;
        }

        List<GraphCompiler.Region<K>> result() { return result; }
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
