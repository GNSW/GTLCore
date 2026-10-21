// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Searches execution with a restricted purchase boundary, then restores finite physical deficits. */
final class RefillPlanningSearch<K> implements AutoCloseable {
    private final GraphCompiler<K> compiler;
    private final GraphPlan<K> original;
    private final RefillProducerOrder<K> order;
    private final Map<K, Long> stock, trialStock;
    private final Set<K> external;
    private final Set<String> excluded;
    private final boolean force, ignoreTargetStock;
    private final PlanningBudget budget;
    private final long started, allowance;
    private final String failure;
    private final Set<K> terminal = new LinkedHashSet<>();
    private final Set<K> produced = new HashSet<>();
    private final Map<Integer, List<K>> components = new LinkedHashMap<>();
    private Iterator<GraphRecipe<K>> recipes;
    private Iterator<Map.Entry<K, Long>> outputs;
    private GraphRecipe<K> producing;
    private Iterator<K> inputs, missing;
    private Iterator<List<K>> groups;
    private Set<K> supplied;
    private GraphPlanningWork<K> work;
    private GraphPlan<K> result, proposal;
    private Map<K, BigInteger> deficits;
    private Iterator<Map.Entry<K, BigInteger>> funding;
    private int phase, trial, originalProduced, proposedProduced;
    private BigInteger originalTotal = BigInteger.ZERO, proposedTotal = BigInteger.ZERO;
    private long memory, trialMemory, trialStarted, trialAllowance;
    private boolean done;

    RefillPlanningSearch(GraphCompiler<K> compiler, GraphPlan<K> original, RefillProducerOrder<K> order,
            Map<K, Long> stock, Set<K> external, Set<String> excluded, boolean force, boolean ignoreTargetStock,
            PlanningBudget budget) {
        this.compiler = compiler; this.original = result = original; this.order = order; this.stock = stock;
        this.external = external; this.excluded = excluded; this.force = force; this.ignoreTargetStock = ignoreTargetStock;
        this.budget = budget; started = budget.nodes(); failure = budget.failureDetail();
        // This is an optional quality pass over an already verified bill.
        // Scale it to the work that found that bill and the catalog size:
        // a full-catalog solve needs room even when the first bill was cheap.
        // Granting every small proven-infeasible order eight million more
        // operations overwhelms the fast state-closure proofs. Use a quarter
        // of completed work; large catalogs retain the full search allowance.
        long modelAllowance = Math.max(32768, 2048L * compiler.catalog().size());
        allowance = Math.min(Math.min(8_000_000, Math.max(modelAllowance, started / 4)), budget.remainingWork() / 2);
        trialStock = ignoreTargetStock && stock.containsKey(original.target()) ?
                PlanningInventory.withAmount(stock, original.target(), BigInteger.ZERO) : stock;
        recipes = compiler.catalog().iterator(); missing = original.missingExact().keySet().iterator();
    }

    boolean step() {
        if (done) return true;
        try {
            budget.checkpoint();
            if (budget.nodes() - started >= allowance) return finish();
            if (work != null) {
                if (budget.nodes() - trialStarted >= trialAllowance) { endTrial(); return false; }
                if (!work.step()) return false;
                proposal = work.result();
                if (!proposal.feasible()) { endTrial(); return false; }
                // The solver has checked order, state and forced production.
                // Its external inputs are only a proposal: restore every finite
                // purchase against the original stock before publication.
                reserve(512L + 128L * (proposal.initialExact().size() + proposal.recipes().size()));
                deficits = new LinkedHashMap<>(); funding = proposal.initialExact().entrySet().iterator();
                proposedProduced = 0; proposedTotal = BigInteger.ZERO; phase = 4; work.close(); work = null;
            }
            for (int i = 0; i < 64; i++) {
                budget.check();
                switch (phase) {
                    case 0 -> {
                        if (missing.hasNext()) {
                            K key = missing.next();
                            originalTotal = originalTotal.add(original.missingExact().get(key));
                            var node = order == null ? null : order.get(key);
                            // Returning a reusable item is not a producer of
                            // new stock and cannot justify another supply solve.
                            if (node != null && !node.sources.isEmpty()) {
                                originalProduced++;
                                if (node.cycle) {
                                    reserve(96);
                                    components.computeIfAbsent(node.componentId, ignored -> new ArrayList<>()).add(key);
                                }
                            }
                        } else if (originalProduced == 0) return finish();
                        else phase = 1;
                    }
                    case 1 -> {
                        // Returning a tool does not manufacture another tool.
                        // Index positive net outputs once, with bounded steps,
                        // including excluded sources so they are not mislabelled
                        // as true terminal materials.
                        if (outputs != null && outputs.hasNext()) {
                            var entry = outputs.next();
                            if (entry.getValue() > producing.inputs().getOrDefault(entry.getKey(), 0L) && !produced.contains(entry.getKey())) {
                                reserve(64); produced.add(entry.getKey());
                            }
                        } else if (recipes.hasNext()) {
                            producing = recipes.next(); outputs = producing.outputs().entrySet().iterator();
                        } else {
                            producing = null; outputs = null; recipes = compiler.catalog().iterator(); phase = 5;
                        }
                    }
                    case 5 -> {
                        if (inputs != null && inputs.hasNext()) {
                            K key = inputs.next();
                            if (!external.contains(key) && !terminal.contains(key) && !produced.contains(key)) {
                                reserve(96); terminal.add(key);
                            }
                        } else if (recipes.hasNext()) inputs = recipes.next().inputs().keySet().iterator();
                        else { recipes = null; inputs = null; phase = 2; }
                    }
                    case 2 -> {
                        if (trial >= 4 || trial > 0 && components.isEmpty()) return finish();
                        trialMemory = memory;
                        reserve(128L + 64L * ((long) external.size() + terminal.size()));
                        supplied = new LinkedHashSet<>(external); supplied.addAll(terminal);
                        groups = components.values().iterator(); phase = 3;
                    }
                    case 3 -> {
                        if (trial > 0 && groups.hasNext()) {
                            var choices = groups.next();
                            reserve(64); supplied.add(choices.get((trial - 1) % choices.size()));
                        } else {
                            if (supplied.equals(external)) { endTrial(); break; }
                            trialStarted = budget.nodes();
                            trialAllowance = Math.min(allowance - (trialStarted - started),
                                    trial == 0 ? allowance * 3 / 4 : Math.max(4096, allowance / 4));
                            work = GraphPlanningWork.refillView(compiler, original, trialStock, supplied, excluded, force, budget);
                            budget.note("refill_boundary_search", "trial=" + trial + "; terminal_keys=" + terminal.size() +
                                    "; cycle_anchors=" + (supplied.size() - external.size() - terminal.size()) + "; allowance=" + trialAllowance);
                            return false;
                        }
                    }
                    case 4 -> {
                        if (funding.hasNext()) {
                            var entry = funding.next(); K key = entry.getKey();
                            if (external.contains(key)) break;
                            BigInteger have = ignoreTargetStock && original.target().equals(key) ? BigInteger.ZERO : PlanningInventory.exactAmount(stock, key);
                            BigInteger gap = entry.getValue().subtract(have);
                            if (gap.signum() > 0) {
                                if (!supplied.contains(key)) throw new IllegalStateException("Unfunded refill boundary witness");
                                deficits.put(key, gap);
                                proposedTotal = proposedTotal.add(gap);
                                if (produced.contains(key)) proposedProduced++;
                            }
                        } else {
                            // Prefer full expansion to purchased leaves. A cyclic
                            // alternative must reduce both the produced boundary
                            // count and the complete number of requested items.
                            boolean concentrated = proposedProduced < originalProduced && deficits.size() <= original.missingExact().size();
                            boolean better = deficits.isEmpty() || proposedProduced == 0 ||
                                    concentrated && proposedTotal.compareTo(originalTotal) <= 0;
                            if (better || concentrated && result.alternatives().size() < 4) {
                                var next = new GraphPlan<>(original.target(), original.amount(), original.preserveSeeds(),
                                        proposal.steps(), proposal.recipes(), proposal.initialExact(), original.seeds(), deficits,
                                        deficits.isEmpty() ? GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL : original.result(),
                                        budget.nodes(), original.planningNanos());
                                if (!better) {
                                    var options = new ArrayList<>(result.alternatives()); options.add(next);
                                    result = result.withAlternatives(options);
                                    endTrial(); break;
                                }
                                if (!deficits.isEmpty()) {
                                    List<GraphPlan<K>> options = new ArrayList<>();
                                    options.add(original.withAlternatives(List.of()));
                                    for (var old : result.alternatives()) if (options.size() < 4) options.add(old);
                                    next = next.withAlternatives(options);
                                }
                                result = next;
                                budget.note("refill_boundary_search", "verified; missing_keys=" + deficits.size() +
                                        "; produced_boundaries=" + proposedProduced + "; original_missing=" + original.missingExact().size());
                                return finish();
                            }
                            endTrial();
                        }
                    }
                    default -> throw new IllegalStateException("Refill search phase " + phase);
                }
            }
            return false;
        } catch (PlanningBudget.Exhausted exhausted) { budget.failureDetail(failure); return finish(); }
    }

    private void endTrial() {
        if (work != null) { work.close(); work = null; }
        proposal = null; funding = null; deficits = null; supplied = null;
        budget.release(memory - trialMemory); memory = trialMemory;
        trial++; phase = 2;
    }
    private boolean finish() { done = true; if (work != null) { work.close(); work = null; } budget.failureDetail(failure); return true; }
    GraphPlan<K> result() { return result; }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    public void close() { if (work != null) work.close(); work = null; budget.release(memory); memory = 0; }
}
