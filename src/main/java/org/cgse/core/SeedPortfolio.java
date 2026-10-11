// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Inventory-constrained seed search followed by optional base-material/seed tradeoffs. */
final class SeedPortfolio<K> implements AutoCloseable {

    private final GraphCompiler<K> compiler;
    private final Map<K, Long> stock, mandatory;
    private final Set<K> external;
    private final Set<String> excluded;
    private final boolean force;
    private final PlanningBudget budget;
    private SeedOptimization<K> search;
    private GraphPlan<K> best;
    private final List<GraphPlan<K>> frontier = new ArrayList<>();
    private boolean flexible, complete;

    SeedPortfolio(GraphCompiler<K> compiler, GraphPlan<K> plan, Map<K, Long> stock, Map<K, Long> mandatory,
                  Set<K> external, Set<String> excluded, boolean force, PlanningBudget budget) {
        try (var costs = budget.trace(PlanningCostTrace.Origin.SEED_OPTIMIZATION, PlanningCostTrace.Stage.PREPARE)) {
            this.compiler = compiler;
            best = plan;
            this.stock = stock;
            this.mandatory = mandatory;
            this.external = external;
            this.excluded = excluded;
            this.force = force;
            this.budget = budget;
            frontier.add(plan);
            search = new SeedOptimization<>(compiler, plan, stock, mandatory, external, excluded, force, budget);
        }
    }

    boolean step() {
        try (var costs = budget.trace(PlanningCostTrace.Origin.SEED_OPTIMIZATION, PlanningCostTrace.Stage.SEARCH)) {
            return stepMeasured();
        }
    }

    private boolean stepMeasured() {
        if (complete) return true;
        if (!search.step()) return false;
        GraphPlan<K> found = search.result();
        for (var option : search.options()) retain(option);
        retain(found);
        best = found;
        search.close();
        search = null;
        // Never turn a runnable order into a refill request. Fixed-material
        // search already considers all actual inventory on feasible orders.
        if (!flexible && !best.feasible() && !best.seeds().isEmpty() && budget.remainingWork() >= 8192) {
            flexible = true;
            search = new SeedOptimization<>(compiler, best, stock, mandatory, external, excluded, force, budget, true);
            return false;
        }
        complete = true;
        budget.note("seed_material_tradeoffs", "verified_options=" + frontier.size() + "; base_material_search=" + flexible);
        return true;
    }

    private void retain(GraphPlan<K> plan) {
        if (frontier.stream().anyMatch(old -> dominates(old, plan))) return;
        frontier.removeIf(old -> dominates(plan, old));
        // A bounded Pareto archive is an incumbent cache, never an optimality proof.
        if (frontier.size() < 32) frontier.add(plan);
    }

    static <K> boolean dominates(GraphPlan<K> left, GraphPlan<K> right) {
        if (right.feasible() && !left.feasible()) return false;
        for (var e : left.seeds().entrySet()) if (e.getValue() > right.seeds().getOrDefault(e.getKey(), 0L)) return false;
        for (var e : left.missingExact().entrySet()) if (e.getValue().compareTo(right.missingExact().getOrDefault(e.getKey(), BigInteger.ZERO)) > 0) return false;
        // Initial inventory use includes startup loans and paid materials.
        // Comparing each key separately avoids inventing a price for fluids/items.
        for (var e : left.initialExact().entrySet()) if (e.getValue().compareTo(right.initialExact().getOrDefault(e.getKey(), BigInteger.ZERO)) > 0) return false;
        return true;
    }

    GraphPlan<K> result() {
        GraphPlan<K> retained = search == null ? best : search.result();
        List<GraphPlan<K>> options = new ArrayList<>(frontier);
        options.removeIf(option -> option == retained || dominates(option, retained) && dominates(retained, option));
        return retained.withAlternatives(options);
    }

    @Override
    public void close() {
        if (search != null) search.close();
        search = null;
    }
}
