// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Searches execution with a restricted purchase boundary, then restores finite physical deficits. */
final class RefillPlanningSearch<K> implements AutoCloseable {
    private RefillFrontier.Comparison<K> comparing;
    private boolean preferCandidate;
    private int compareOption;
    private boolean retainTrades;
    private MissingMaterialExpansion<K> canonical;
    private RefillPreviewSelection<K> canonicalSelection;
    private RefillRescheduling<K> rescheduling;
    private RefillFrontier<K> canonicalFrontier;
    private Iterator<GraphPlan<K>> canonicalOptions;

    RefillPlanningSearch<K> retainTrades(boolean enabled) { retainTrades = enabled; return this; }
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
    private final List<K> boundaryKeys = new ArrayList<>();
    private Iterator<GraphRecipe<K>> recipes;
    private Iterator<Map.Entry<K, Long>> outputs;
    private GraphRecipe<K> producing;
    private Iterator<K> inputs, missing;
    private Iterator<List<K>> groups;
    private Set<K> supplied;
    private GraphPlanningWork<K> work;
    private GraphSupplyView<K> supplyView;
    private AllocationSearch.Candidate<K> restoring;
    private GraphPlan<K> result, proposal;
    private Map<K, BigInteger> deficits;
    private Iterator<Map.Entry<K, BigInteger>> funding;
    private int phase, trial, originalProduced, proposedProduced;
    private BigInteger proposedTotal = BigInteger.ZERO;
    private long memory, trialMemory, trialStarted, trialAllowance;
    private boolean done;
    private int coordinateTrials;
    private boolean coordinateTrial;
    private Map<K, Long> prefixStock;

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
            if (canonicalFrontier != null) {
                if (!canonicalFrontier.step()) return false;
                result = canonicalFrontier.result();
                if (canonicalOptions.hasNext()) {
                    canonicalFrontier.offer(canonicalOptions.next().withAlternatives(List.of()));
                } else {
                    canonicalFrontier = null; canonicalOptions = null;
                    rescheduling = new RefillRescheduling<>(result, stock, external, force, ignoreTargetStock, budget);
                }
                return false;
            }
            if (rescheduling != null) {
                if (!rescheduling.step()) return false;
                result = rescheduling.result();
                rescheduling.close(); rescheduling = null;
                // Rebuilding a cyclic bill is only another verified candidate.
                // The original terminal-boundary solver must still get its turn.
                return false;
            }
            if (canonical != null) {
                if (canonicalSelection == null) {
                    if (!canonical.step()) return false;
                    var next = canonical.result();
                    proposal = next.feasible() ? next : GraphPlan.ownedRefill(next, next.steps(), next.recipes(),
                            next.initialExact(), next.missingExact(), original.result(), budget.nodes()).withAlternatives(next.alternatives());
                    canonicalSelection = new RefillPreviewSelection<>(result, proposal, budget);
                }
                if (!canonicalSelection.step()) return false;
                boolean accept = canonicalSelection.result() == proposal;
                if (accept) {
                    memory += canonical.takeResultCharge();
                    reserve(128L + 32L * (1L + result.alternatives().size() + proposal.alternatives().size()));
                    var options = new ArrayList<GraphPlan<K>>();
                    options.add(result); options.addAll(result.alternatives()); options.addAll(proposal.alternatives());
                    result = proposal.withAlternatives(List.of());
                    canonicalFrontier = new RefillFrontier<>(result, budget);
                    canonicalOptions = options.iterator();
                    canonicalFrontier.offer(canonicalOptions.next().withAlternatives(List.of()));
                }
                canonical.close(); canonical = null; canonicalSelection = null; proposal = null;
                return false;
            }
            if (work != null) {
                if (budget.nodes() - trialStarted >= trialAllowance) { endTrial(); return false; }
                if (!work.step()) return false;
                proposal = work.result();
                if (!proposal.feasible()) { endTrial(); return false; }
                if (supplyView != null) {
                    PlanStep word = coordinateTrial ? new PlanStep.Sequence(List.of(proposal.steps(), result.steps())) : proposal.steps();
                    restoring = new AllocationSearch.Candidate<>(word, supplyView.originals(), original.target(), original.amount(),
                            trialStock, result.seeds(), supplied, original.preserveSeeds(), force, true, budget, System.nanoTime());
                    work.close(); work = null;
                    return false;
                }
            }
            if (restoring != null) {
                if (!restoring.step()) return false;
                proposal = restoring.plan; restoring.close(); restoring = null;
                if (proposal == null || !proposal.feasible()) { endTrial(); return false; }
            }
            if (proposal != null && phase == 3) {
                // The solver has checked order, state and forced production.
                // Its external inputs are only a proposal: restore every finite
                // purchase against the original stock before publication.
                reserve(512L + 128L * (proposal.initialExact().size() + proposal.recipes().size()));
                deficits = new LinkedHashMap<>(); funding = proposal.initialExact().entrySet().iterator();
                proposedProduced = 0; proposedTotal = BigInteger.ZERO; phase = 4;
                if (work != null) { work.close(); work = null; }
            }
            for (int i = 0; i < 64; i++) {
                budget.check();
                switch (phase) {
                    case 0 -> {
                        if (missing.hasNext()) {
                            K key = missing.next();
                            var node = order == null ? null : order.get(key);
                            // Returning a reusable item is not a producer of
                            // new stock and cannot justify another supply solve.
                            if (node != null && !node.sources.isEmpty()) {
                                originalProduced++;
                                if (node.cycle) {
                                    reserve(96);
                                    components.computeIfAbsent(node.componentId, ignored -> new ArrayList<>()).add(key);
                                    boundaryKeys.add(key);
                                }
                            }
                        } else if (originalProduced == 0) return finish();
                        else {
                            phase = 1;
                            if (retainTrades && !components.isEmpty()) {
                                // A solver word may have traded cheap startup
                                // stock for new feed purchases. Rebuild that
                                // tradeoff with the SAME backward constructor,
                                // then apply the existing funded-cycle refiners.
                                // Disable recursive source/boundary portfolios.
                                canonical = MissingMaterialExpansion.fromRequest(compiler, original.target(), original.amount(),
                                        stock, external, original.seeds(), excluded, original.preserveSeeds(), force, budget)
                                        .refineSources(false).refineCycles(true);
                                return false;
                            }
                        }
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
                        else {
                            recipes = null; inputs = null; phase = 2;
                            // A broad coupled graph may be hard with every
                            // purchase removed at once. Relax one completed
                            // cyclic boundary at a time, retaining the other
                            // physical purchases and every verified incumbent.
                            coordinateTrials = compiler.catalog().size() > 192 && boundaryKeys.size() > 1 ? Math.min(8, boundaryKeys.size()) : 0;
                        }
                    }
                    case 2 -> {
                        if (trial >= 4 + coordinateTrials || trial > 0 && components.isEmpty()) return finish();
                        coordinateTrial = trial < coordinateTrials;
                        if (coordinateTrial && !result.missingExact().containsKey(boundaryKeys.get(trial))) { trial++; break; }
                        // Public orders have long-sized targets, while a word's
                        // exact prefix can exceed that. Keep the whole-word
                        // boundary search for those coordinates; never clamp.
                        if (coordinateTrial && result.initialExact().getOrDefault(boundaryKeys.get(trial), BigInteger.ZERO)
                                .compareTo(ExactAmounts.LONG_MAX) > 0) { trial++; break; }
                        trialMemory = memory;
                        reserve(128L + 64L * ((long) external.size() + terminal.size()));
                        supplied = new LinkedHashSet<>(external); supplied.addAll(terminal);
                        if (coordinateTrial) for (K key : result.missingExact().keySet()) {
                            budget.check();
                            if (!key.equals(boundaryKeys.get(trial))) { reserve(64); supplied.add(key); }
                        }
                        groups = components.values().iterator(); phase = 3;
                    }
                    case 3 -> {
                        int boundaryTrial = trial - coordinateTrials;
                        if (!coordinateTrial && boundaryTrial > 0 && groups.hasNext()) {
                            var choices = groups.next();
                            reserve(64); supplied.add(choices.get((boundaryTrial - 1) % choices.size()));
                        } else {
                            if (supplied.equals(external)) { endTrial(); break; }
                            trialStarted = budget.nodes();
                            trialAllowance = Math.min(allowance - (trialStarted - started),
                                    coordinateTrial ? Math.max(65536, Math.min(524288, allowance / (2L * coordinateTrials))) :
                                            boundaryTrial == 0 ? allowance * 3 / 4 : Math.max(4096, allowance / 4));
                            supplyView = new GraphSupplyView<>(compiler, supplied, budget);
                            if (coordinateTrial) {
                                K key = boundaryKeys.get(trial);
                                reserve(256L + 160L * (trialStock.size() + result.initialExact().size()));
                                var available = new LinkedHashMap<K, BigInteger>();
                                for (K held : trialStock.keySet()) { budget.check(); available.put(held, PlanningInventory.exactAmount(trialStock, held)); }
                                // The suffix is already a verified execution.
                                // Reserve its finite inputs, then solve only
                                // the purchase being replaced. Other supplied
                                // ports remain exact purchases on restoration.
                                for (var need : result.initialExact().entrySet()) {
                                    budget.check();
                                    if (!supplied.contains(need.getKey()) && !key.equals(need.getKey()))
                                        available.compute(need.getKey(), (ignored, have) ->
                                                (have == null ? BigInteger.ZERO : have).subtract(need.getValue()).max(BigInteger.ZERO));
                                }
                                prefixStock = PlanningInventory.exactSnapshot(available);
                                var goal = new GraphPlan<K>(key, result.initialExact().get(key).longValueExact(), false,
                                        new PlanStep.Sequence(List.of()), Map.of(), Map.of(), Map.of(), Map.of(), GraphPlan.Result.UNKNOWN, 0, 0);
                                work = GraphPlanningWork.refillView(supplyView.compiler(), goal, prefixStock, supplied, excluded, false, budget);
                            } else work = GraphPlanningWork.refillView(supplyView.compiler(), original, trialStock, supplied, excluded, force, budget);
                            budget.note("refill_boundary_search", "trial=" + trial + "; terminal_keys=" + terminal.size() +
                                    "; cycle_anchors=" + (supplied.size() - external.size() - terminal.size()) + "; allowance=" + trialAllowance +
                                    (coordinateTrial ? "; removing=" + boundaryKeys.get(trial) : ""));
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
                            int currentProduced = 0; BigInteger currentTotal = BigInteger.ZERO;
                            for (var entry : result.missingExact().entrySet()) {
                                budget.check(); currentTotal = currentTotal.add(entry.getValue());
                                if (produced.contains(entry.getKey())) currentProduced++;
                            }
                            boolean concentrated = proposedProduced < currentProduced && deficits.size() <= result.missingExact().size();
                            boolean better = deficits.isEmpty() || proposedProduced == 0 ||
                                    concentrated && proposedTotal.compareTo(currentTotal) <= 0;
                            if (better || (concentrated || retainTrades) && result.alternatives().size() < 4) {
                                var next = new GraphPlan<>(original.target(), original.amount(), original.preserveSeeds(),
                                        proposal.steps(), proposal.recipes(), proposal.initialExact(), proposal.seeds(), deficits,
                                        deficits.isEmpty() ? GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL : original.result(),
                                        budget.nodes(), original.planningNanos());
                                proposal = next; preferCandidate = better; compareOption = -1;
                                comparing = new RefillFrontier.Comparison<>(proposal, result, budget);
                                phase = 6; return false;
                            }
                            endTrial();
                        }
                    }
                    case 6 -> {
                        if (!comparing.step()) return false;
                        if (comparing.rightDominates()) { endTrial(); break; }
                        if (compareOption < 0 && (preferCandidate || comparing.improvesMissing() || comparing.leftDominates())) {
                            var options = new ArrayList<GraphPlan<K>>();
                            if (!proposal.feasible() && !comparing.leftDominates()) options.add(result.withAlternatives(List.of()));
                            for (var old : result.alternatives()) if (options.size() < 4) options.add(old);
                            result = proposal.withAlternatives(options);
                            budget.note("refill_boundary_search", "verified; missing_keys=" + deficits.size() +
                                    "; produced_boundaries=" + proposedProduced + "; original_missing=" + original.missingExact().size());
                            if (coordinateTrial) { trialMemory = memory; endTrial(); return false; }
                            return finish();
                        }
                        if (++compareOption < result.alternatives().size()) {
                            comparing = new RefillFrontier.Comparison<>(proposal, result.alternatives().get(compareOption), budget);
                            return false;
                        }
                        var options = new ArrayList<>(result.alternatives()); options.add(proposal);
                        result = result.withAlternatives(options);
                        // The archived proposal stays live during later trials.
                        // Retain its conservative workspace charge until close.
                        trialMemory = memory; endTrial();
                    }
                    default -> throw new IllegalStateException("Refill search phase " + phase);
                }
            }
            return false;
        } catch (PlanningBudget.Exhausted exhausted) { budget.failureDetail(failure); return finish(); }
    }

    private void endTrial() {
        if (work != null) { work.close(); work = null; }
        if (restoring != null) { restoring.close(); restoring = null; }
        if (supplyView != null) { supplyView.close(); supplyView = null; }
        proposal = null; funding = null; deficits = null; supplied = null; comparing = null; prefixStock = null;
        budget.release(memory - trialMemory); memory = trialMemory;
        trial++; phase = 2;
    }
    private boolean finish() { done = true; if (work != null) { work.close(); work = null; } budget.failureDetail(failure); return true; }
    GraphPlan<K> result() { return rescheduling == null ? result : rescheduling.result(); }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    public void close() {
        if (work != null) work.close(); work = null;
        if (restoring != null) { restoring.close(); restoring = null; }
        if (supplyView != null) { supplyView.close(); supplyView = null; }
        if (canonical != null) canonical.close(); canonical = null; canonicalSelection = null;
        if (rescheduling != null) { rescheduling.close(); rescheduling = null; }
        canonicalFrontier = null; canonicalOptions = null;
        budget.release(memory); memory = 0;
    }
}
