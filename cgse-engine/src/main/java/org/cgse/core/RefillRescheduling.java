// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Optional executable reordering of a finite bill, never a source of cutoff deficits. */
final class RefillRescheduling<K> implements AutoCloseable {
    private final GraphPlan<K> original;
    private GraphPlan<K> result;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final boolean force;
    private final PlanningBudget budget;
    private final String failure;
    private final long allowance;
    private long work, memory;
    private Iterator<K> missing;
    private Map<K, BigInteger> held, goals;
    private DemandExpansion<K> searching;
    private AllocationSearch.Candidate<K> assembling;
    private RefillFrontier.Comparison<K> comparing;
    private RefillFrontier<K> frontier;
    private Iterator<GraphPlan<K>> options;
    private GraphPlan<K> proposal;
    private boolean done;
    private int attempts;

    RefillRescheduling(GraphPlan<K> plan, Map<K, Long> stock, Set<K> external,
                       boolean force, boolean ignoreTargetStock, PlanningBudget budget) {
        original = result = plan;
        this.stock = ignoreTargetStock && stock.containsKey(plan.target()) ?
                PlanningInventory.withAmount(stock, plan.target(), BigInteger.ZERO) : stock;
        this.external = external; this.force = force; this.budget = budget;
        failure = budget.failureDetail();
        allowance = Math.min(16_384, budget.remainingWork() / 8);
        missing = plan.missingExact().keySet().iterator();
        // This pass repairs final-seed rounding. Larger domains retain their
        // completed bill and their other existing search opportunities.
        done = plan.seeds().isEmpty() || plan.recipes().size() > 256 || allowance < 512;
    }

    boolean step() {
        if (done) return true;
        long before = budget.threadWork();
        try {
            budget.checkpoint();
            if (work >= allowance) return finish();
            if (frontier != null) {
                if (!frontier.step()) return false;
                result = frontier.result();
                if (options.hasNext()) { frontier.offer(options.next().withAlternatives(List.of())); return false; }
                return finish();
            }
            if (comparing != null) {
                if (!comparing.step()) return false;
                if (comparing.improvesMissing()) {
                    result = GraphPlan.ownedRefill(proposal, proposal.steps(), proposal.recipes(), proposal.initialExact(),
                            proposal.missingExact(), proposal.feasible() ? proposal.result() : original.result(), budget.nodes());
                    frontier = new RefillFrontier<>(result, budget);
                    options = original.alternatives().iterator();
                    frontier.offer(original.withAlternatives(List.of()));
                    budget.note("refill_reschedule", "verified_finite_bill; reduced_seed_rounding");
                    return false;
                }
                comparing = null; proposal = null;
            }
            if (assembling != null) {
                if (!assembling.step()) return false;
                proposal = assembling.plan; assembling.close(); assembling = null;
                if (proposal != null && proposal.seeds().equals(original.seeds()))
                    comparing = new RefillFrontier.Comparison<>(proposal, original, budget);
                else proposal = null;
                return false;
            }
            if (searching != null) {
                if (!searching.step()) return false;
                PlanStep word = searching.result(); searching.close(); searching = null;
                if (word != null)
                    assembling = new AllocationSearch.Candidate<>(word, original.recipes(), original.target(), original.amount(),
                            stock, original.seeds(), external, original.preserveSeeds(), force, true, budget, System.nanoTime());
                return false;
            }
            while (missing.hasNext() && attempts < 4) {
                budget.check(); K key = missing.next();
                // One less unit is a concrete finite hypothesis. No input can
                // be borrowed during execution, and failure retains the bill.
                if (original.missingExact().get(key).compareTo(BigInteger.ONE) <= 0) continue;
                if (held == null && !prepare()) return finish();
                attempts++;
                BigInteger available = held.getOrDefault(key, BigInteger.ZERO);
                held.put(key, available.subtract(BigInteger.ONE));
                try { searching = new DemandExpansion<>(original.recipes(), held, goals, budget).externallySupplied(external).protectFinalGoals(); }
                finally { held.put(key, available); }
                return false;
            }
            return finish();
        } catch (PlanningBudget.Exhausted limit) {
            budget.failureDetail(failure); return finish();
        } finally { work += budget.threadWork() - before; }
    }

    private boolean prepare() {
        long support = original.seeds().size() + 1L;
        for (var recipe : original.recipes().values()) {
            budget.check();
            if (recipe.batchSensitiveInputs()) return false;
            support += recipe.inputs().size();
        }
        long bytes = 512 + support * 384;
        if (!budget.tryReserve(bytes)) return false;
        memory += bytes;
        held = new LinkedHashMap<>(); goals = new LinkedHashMap<>();
        for (var recipe : original.recipes().values()) for (K key : recipe.inputs().keySet()) fund(key);
        for (var seed : original.seeds().entrySet()) {
            fund(seed.getKey()); goals.put(seed.getKey(), BigInteger.valueOf(seed.getValue()));
        }
        fund(original.target());
        BigInteger reserve = goals.getOrDefault(original.target(), BigInteger.ZERO);
        if (force) reserve = reserve.max(PlanningInventory.exactAmount(stock, original.target()));
        goals.put(original.target(), reserve.add(BigInteger.valueOf(original.amount())));
        return true;
    }

    private void fund(K key) {
        budget.check();
        if (held.containsKey(key)) return;
        held.put(key, external.contains(key) ? original.initialExact().getOrDefault(key, BigInteger.ZERO) :
                PlanningInventory.exactAmount(stock, key).add(original.missingExact().getOrDefault(key, BigInteger.ZERO)));
    }
    private boolean finish() { done = true; return true; }
    GraphPlan<K> result() { return result; }
    @Override public void close() {
        if (searching != null) { searching.close(); searching = null; }
        if (assembling != null) { assembling.close(); assembling = null; }
        held = goals = null; comparing = null; proposal = null; frontier = null; options = null;
        budget.release(memory); memory = 0;
    }
}
