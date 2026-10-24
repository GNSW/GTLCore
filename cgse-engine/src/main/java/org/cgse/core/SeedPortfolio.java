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
    private DemandExpansion<K> rescheduling;
    private AllocationSearch.Candidate<K> assembling;
    private long rescheduleWork, rescheduleCharge;
    private long rescheduleAllowance;
    private Map<K, BigInteger> rescheduleStock, rescheduleGoals;
    private K terminalProbe;
    private boolean multipleTerminals, probeTried;
    private boolean rescheduleTried;

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
        if (!rescheduleTried) {
            rescheduleTried = true;
            boolean missingSeed = !best.feasible() && best.seeds().keySet().stream().anyMatch(best.missingExact()::containsKey);
            if (missingSeed && best.recipes().size() <= 256) beginReschedule();
        }
        if (rescheduling != null) {
            long before = budget.threadWork();
            boolean done = rescheduleWork >= rescheduleAllowance || rescheduling.step();
            rescheduleWork += budget.threadWork() - before;
            if (!done) return false;
            var word = rescheduling.result();
            rescheduling.close(); rescheduling = null;
            if (word != null)
                assembling = new AllocationSearch.Candidate<>(word, best.recipes(), best.target(), best.amount(), stock,
                        mandatory, external, best.preserveSeeds(), force, true, budget, System.nanoTime())
                        .startupCatalog(compiler.catalog(), excluded);
            else if (!probeTried && !multipleTerminals && terminalProbe != null &&
                    !best.missingExact().containsKey(terminalProbe) && rescheduleWork < rescheduleAllowance) {
                // A finite one-unit terminal hypothesis retains the old nested
                // execution capability without forward loans. Search must finish
                // and Candidate must derive every actual deficit from its word.
                probeTried = true;
                rescheduleStock.merge(terminalProbe, BigInteger.ONE, BigInteger::add);
                rescheduling = new DemandExpansion<>(best.recipes(), rescheduleStock, rescheduleGoals, budget).externallySupplied(external);
                return false;
            }
            releaseReschedule();
            return false;
        }
        if (assembling != null) {
            if (!assembling.step()) return false;
            var proposal = assembling.plan;
            assembling.close(); assembling = null;
            if (proposal != null && (dominates(proposal, best) || terminalReplacement(proposal))) {
                retain(proposal); best = proposal;
                search.close(); search = new SeedOptimization<>(compiler, best, stock, mandatory, external, excluded, force, budget);
            }
            return false;
        }
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

    private void beginReschedule() {
        // Reuse only the solver's bound support, with its finite nonseed
        // deficit funded. Do not copy an unrelated, potentially huge inventory
        // or allocate scratch before reserving its conservative upper bound.
        long keys = (long) mandatory.size() + 1;
        for (var recipe : best.recipes().values()) { budget.check(); keys += recipe.inputs().size(); }
        long bytes = 256L + 192L * keys + 128L * ((long) mandatory.size() + 1);
        if (!budget.tryReserve(bytes)) return;
        rescheduleCharge = bytes;
        rescheduleAllowance = Math.min(262_144, budget.remainingWork() / 8);
        var held = rescheduleStock = new LinkedHashMap<K, BigInteger>();
        for (var recipe : best.recipes().values()) for (K key : recipe.inputs().keySet()) fundRescheduleKey(key, held);
        var goals = rescheduleGoals = new LinkedHashMap<K, BigInteger>();
        for (var entry : mandatory.entrySet()) {
            fundRescheduleKey(entry.getKey(), held);
            goals.put(entry.getKey(), BigInteger.valueOf(entry.getValue()));
        }
        fundRescheduleKey(best.target(), held);
        BigInteger reserve = goals.getOrDefault(best.target(), BigInteger.ZERO);
        goals.put(best.target(), BigInteger.valueOf(best.amount()).add(force ? reserve.max(PlanningInventory.exactAmount(stock, best.target())) : reserve));
        rescheduling = new DemandExpansion<>(best.recipes(), held, goals, budget).externallySupplied(external);
    }

    private void fundRescheduleKey(K key, Map<K, BigInteger> held) {
        budget.check();
        if (held.containsKey(key)) return;
        BigInteger have = PlanningInventory.exactAmount(stock, key);
        if (!best.seeds().containsKey(key) && compiler.producers(key).isEmpty())
            have = have.add(best.missingExact().getOrDefault(key, BigInteger.ZERO));
        held.put(key, have);
        if (!external.contains(key) && compiler.producers(key).isEmpty() && !mandatory.containsKey(key) && !key.equals(best.target())) {
            if (terminalProbe == null) terminalProbe = key;
            else if (!terminalProbe.equals(key)) multipleTerminals = true;
        }
    }

    private boolean terminalReplacement(GraphPlan<K> proposal) {
        // A verified terminal bill may replace artificial intermediate/cycle
        // seeds. This is a boundary preference, never a price between materials.
        for (K key : proposal.missingExact().keySet()) {
            budget.check();
            if (!compiler.producers(key).isEmpty()) return false;
        }
        for (K key : best.missingExact().keySet()) {
            budget.check();
            if (!compiler.producers(key).isEmpty()) return true;
        }
        return false;
    }

    private void releaseReschedule() {
        rescheduleStock = rescheduleGoals = null;
        budget.release(rescheduleCharge); rescheduleCharge = 0;
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
        if (rescheduling != null) { rescheduling.close(); rescheduling = null; }
        if (assembling != null) { assembling.close(); assembling = null; }
        releaseReschedule();
        if (search != null) search.close();
        search = null;
    }
}
