// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Bounded structural count proposal with only true terminal purchases. The
 * existing bill remains live until the original physical word is verified. */
final class RefillTerminalSearch<K> implements AutoCloseable {
    private final GraphCompiler<K> compiler;
    private final GraphPlan<K> original;
    private final Map<K, Long> stock;
    private final Set<K> external, terminal;
    private final Set<String> excluded;
    private final boolean force;
    private final PlanningBudget budget;
    private final long allowance;
    private GraphSupplyView<K> view;
    private CountDemandSearch<K> search;
    private AllocationSearch.Candidate<K> restoring;
    private GraphPlan<K> proposal, result;
    private Set<K> supplied;
    private Map<K, BigInteger> deficits;
    private Iterator<Map.Entry<K, BigInteger>> funding;
    private long work, memory;
    private boolean done;
    private Map<K, Long> requestedSeeds;

    RefillTerminalSearch(GraphCompiler<K> compiler, GraphPlan<K> original, Map<K, Long> stock,
                         Set<K> external, Set<K> terminal, Set<String> excluded, boolean force,
                         PlanningBudget budget) {
        this.compiler = compiler; this.original = original; this.stock = stock;
        this.external = external; this.terminal = terminal; this.excluded = excluded;
        this.force = force; this.budget = budget;
        requestedSeeds = original.seeds();
        // This positive-only tail does not borrow the established quality
        // pass's share. Every stage still consumes the request's hard cap.
        allowance = Math.min(4_194_304, budget.remainingWork() / 4);
    }

    RefillTerminalSearch<K> requestedSeeds(Map<K, Long> seeds) { requestedSeeds = seeds; return this; }

    boolean step() {
        if (done) return true;
        long before = budget.threadWork(); String failure = budget.failureDetail();
        try {
            budget.checkpoint();
            if (work >= allowance || allowance < 32768) return finish();
            if (supplied == null) {
                long bytes = 1024L + 96L * (external.size() + terminal.size());
                if (!budget.tryReserve(bytes)) return finish();
                memory += bytes;
                supplied = new LinkedHashSet<>(external);
                for (K key : terminal) {
                    if (remaining(before) <= 0) return finish();
                    budget.check(); supplied.add(key);
                }
                view = GraphSupplyView.bounded(compiler, supplied, budget, remaining(before));
                if (view == null) return finish();
                search = new CountDemandSearch<>(view.compiler(), original.target(), original.amount(), stock,
                        supplied, requestedSeeds, excluded, original.preserveSeeds(), force, budget, System.nanoTime())
                        .structuralOnly(remaining(before));
                return false;
            }
            if (search != null) {
                if (!search.step()) return false;
                proposal = search.result(); search.close(); search = null;
                if (proposal == null || !proposal.feasible()) return finish();
                restoring = new AllocationSearch.Candidate<>(proposal.steps(), view.originals(), original.target(), original.amount(),
                        stock, requestedSeeds, supplied, original.preserveSeeds(), force, true, budget, System.nanoTime())
                        .startupCatalog(compiler.catalog(), excluded);
                return false;
            }
            if (restoring != null) {
                if (!restoring.step()) return false;
                proposal = restoring.plan; restoring.close(); restoring = null;
                if (proposal == null || !proposal.feasible()) return finish();
                long bytes = 512L + 160L * proposal.initialExact().size();
                if (!budget.tryReserve(bytes)) return finish();
                memory += bytes;
                deficits = new LinkedHashMap<>(); funding = proposal.initialExact().entrySet().iterator();
            }
            for (int scan = 0; scan < 32 && funding.hasNext(); scan++) {
                budget.check(); var entry = funding.next(); K key = entry.getKey();
                if (external.contains(key)) continue;
                BigInteger gap = entry.getValue().subtract(PlanningInventory.exactAmount(stock, key));
                if (gap.signum() > 0) {
                    // Projected ports do not exempt any physical purchase.
                    // No intermediate/cyclic missing key may escape this tail.
                    if (!terminal.contains(key)) return finish();
                    deficits.put(key, gap);
                }
            }
            if (funding.hasNext()) return false;
            result = new GraphPlan<>(original.target(), original.amount(), original.preserveSeeds(),
                    proposal.steps(), proposal.recipes(), proposal.initialExact(), proposal.seeds(), deficits,
                    deficits.isEmpty() ? GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL : original.result(),
                    budget.nodes(), original.planningNanos());
            return finish();
        } catch (PlanningBudget.Exhausted limit) {
            if (limit.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw limit;
            budget.failureDetail(failure);
            return finish();
        } finally { work += budget.threadWork() - before; }
    }

    private long remaining(long before) { return Math.max(0, allowance - work - (budget.threadWork() - before)); }

    private boolean finish() {
        done = true;
        budget.note("refill_terminal", "structural_witness=" + (result != null) + "; work=" + work);
        return true;
    }

    GraphPlan<K> result() { return result; }

    @Override public void close() {
        if (search != null) { search.close(); search = null; }
        if (restoring != null) { restoring.close(); restoring = null; }
        if (view != null) { view.close(); view = null; }
        budget.release(memory); memory = 0;
    }
}
