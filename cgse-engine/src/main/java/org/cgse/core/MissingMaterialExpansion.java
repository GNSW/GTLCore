// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Refines a concrete refill witness by exact backward prefix requirements.
 * No forward inventory loans, depth cutoff, or recipe-count cutoff establish a leaf.
 * Prefix expansion retains physical bindings and seed obligations. An optional
 * verified whole-word pass can subsequently remove surplus cycle firings.
 */
final class MissingMaterialExpansion<K> implements AutoCloseable {
    private static final int OPERATIONS_PER_STEP = 64;
    private static final long MIX_WORK_LIMIT = 8192;
    private enum Phase { START, REQUIREMENTS, RECIPES, RANK, QUEUE, SELECT, SOURCE, PAIR, FUNDING, SUPPORT, COMPARE, APPLY, STEPS, DEFICITS, PUBLISH, VERIFY, PRUNE, DONE }
    private final GraphPlan<K> original;
    private final GraphCompiler<K> compiler;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final Set<String> excluded;
    private final boolean forceCraft, ignoreTargetStock;
    private final PlanningBudget budget;
    private final boolean allowMixed, borrowedOrder;
    private final BigInteger targetProduction;
    private final long startedWork;
    private final Map<K, BigInteger> required = new LinkedHashMap<>();
    private final Map<String, GraphRecipe<K>> recipes = new LinkedHashMap<>();
    private final List<PlanStep> prefix = new ArrayList<>();
    private final Set<K> queued = new HashSet<>();
    private RefillProducerOrder<K> order;
    private PriorityQueue<K> pending;
    private PlanVerification<K> verification;
    private GraphPlan<K> candidate, result;
    private Phase phase = Phase.START;
    private Iterator<Map.Entry<K, BigInteger>> entries;
    private Iterator<Map.Entry<String, GraphRecipe<K>>> bindings;
    private Iterator<K> keys;
    private Iterator<RefillProducerOrder<K>.Source> sources;
    private K expanding;
    private GraphRecipe<K> source;
    private BigInteger plateauRuns, fundedRuns;
    private Proposal selected, proposal;
    private boolean outputSupport, comparingSelected, strict, mayMix, mixing;
    private boolean partialOpportunity, mixtureOpportunity, comparingMixture;
    private MissingMaterialExpansion<K> improvement;
    private RefillWordPruning<K> pruning;
    private RefillSupplySearch<K> cycleSearch;
    private RefillSourceSearch<K> sourceSearch;
    private boolean sourceSearchDone, sourceSearchEnabled = true;
    private RefillSourceSearch<K> reallocation;
    private boolean reallocationDone;
    private RefillSourceSearch<K> jointSearch;
    private boolean jointSearchDone, pairTried;
    private RefillPairCounts.Choice<K> jointChoice;
    private RefillPairCounts<K> pair;
    private boolean simpleSourcesOnly;
    private boolean protectProductionCredit;
    private boolean goalConstruction;
    private final Set<K> crossedCycle = new HashSet<>();
    private Map<K, String> preferredSources = Map.of();
    private Set<K> usedSourcePreferences = Set.of();
    private RefillBoundaryDiagnostics<K> diagnostics;
    private RefillPlanningSearch<K> boundarySearch;
    private boolean boundarySearchDone, boundarySearchEnabled = true;
    private boolean cycleSearchEnabled, cycleSearchTried;
    private long improvementStart, improvementWork;
    private String retainedFailure;
    private List<PlanStep> steps;
    private Map<K, BigInteger> deficits;
    private int prefixIndex;
    private long memory;
    private BigInteger targetDelta = BigInteger.ZERO;
    private SequenceSummary<K> originalSummary;
    private long originalSummaryBytes;

    MissingMaterialExpansion(GraphCompiler<K> compiler, GraphPlan<K> original, Map<K, Long> stock,
                             Set<K> external, Set<String> excluded, boolean forceCraft, boolean ignoreTargetStock, PlanningBudget budget) {
        this(compiler, original, stock, external, excluded, forceCraft, ignoreTargetStock, budget, false, null, BigInteger.ZERO);
    }
    private MissingMaterialExpansion(GraphCompiler<K> compiler, GraphPlan<K> original, Map<K, Long> stock,
                                     Set<K> external, Set<String> excluded, boolean forceCraft, boolean ignoreTargetStock,
                                     PlanningBudget budget, boolean allowMixed, RefillProducerOrder<K> completedOrder, BigInteger targetProduction) {
        this.compiler = compiler; this.original = original; this.stock = stock; this.external = external;
        this.excluded = excluded; this.forceCraft = forceCraft; this.ignoreTargetStock = ignoreTargetStock; this.budget = budget;
        this.allowMixed = allowMixed; startedWork = budget.nodes();
        this.targetProduction = targetProduction;
        order = completedOrder; borrowedOrder = completedOrder != null;
        if (!allowMixed && !hasProducibleMissing(compiler, original)) {
            // Terminal-only bills already satisfy expansion's boundary contract.
            // Keep them available even if optional word refinement hits a limit.
            result = original; phase = Phase.PRUNE;
        }
    }
    static <K> boolean needed(GraphCompiler<K> compiler, GraphPlan<K> plan) {
        return needed(compiler, plan, true);
    }
    static <K> boolean needed(GraphCompiler<K> compiler, GraphPlan<K> plan, boolean sourceChoices) {
        if (plan.feasible() || plan.missingExact().isEmpty()) return false;
        return !(plan.steps() instanceof PlanStep.Batch) || sourceChoices && compiler.producers(plan.target()).size() > 1 ||
                hasProducibleMissing(compiler, plan);
    }
    /** Only after needed() declined: a nonempty bill then has no indexed producer. */
    static <K> GraphPlan<K> terminalResult(GraphPlan<K> plan) {
        return plan.feasible() || plan.missingExact().isEmpty() ? plan :
                plan.withMissingSources(GraphPlan.MissingSource.TERMINAL_UNDER_CURRENT_COVERAGE, Map.of());
    }
    /** The main planner may search a small cyclic prefix; ordinary fallback
     * keeps its existing expansion/word-reduction responsibilities. */
    MissingMaterialExpansion<K> refineCycles(boolean enabled) { cycleSearchEnabled = enabled; return this; }
    /** Bootstrap subrequests still owe their owner later search opportunities. */
    MissingMaterialExpansion<K> refineSources(boolean enabled) { sourceSearchEnabled = enabled; return this; }
    /** Ordinary fallback expands dependencies up to cycle boundaries. Sharing
     * the constructor must not recursively launch the primary cyclic solver. */
    MissingMaterialExpansion<K> ordinaryFallback() {
        cycleSearchEnabled = false;
        boundarySearchEnabled = false;
        return this;
    }
    /** Only the same completed fallback witness may lend its immutable suffix.
     * Prefix construction copies its bindings and refuses conflicting IDs.
     * Retained vectors have an optional reservation, owned by this expansion. */
    MissingMaterialExpansion<K> reuseSuffix(GraphPlan<K> verified, SequenceSummary<K> summary) {
        if (verified != original || phase != Phase.START || summary == null || originalSummary != null) return this;
        long bytes = 256L + 128L * ((long) summary.required().size() + summary.delta().size() + summary.peak().size());
        if (bytes > budget.availableBytes() / 16 || !budget.tryReserve(bytes)) return this;
        memory += bytes; originalSummaryBytes = bytes; originalSummary = summary;
        return this;
    }
    /** A funded empty word has no existing production credit. Forced fallback
     * goals must therefore establish the order's credit while adding a prefix;
     * ordinary refinement only preserves the credit of its verified suffix. */
    static <K> MissingMaterialExpansion<K> fromGoals(GraphCompiler<K> compiler, GraphPlan<K> boundary, Map<K, Long> stock,
                                                     Set<K> external, boolean force, PlanningBudget budget) {
        BigInteger production = force && !external.contains(boundary.target()) ? BigInteger.valueOf(boundary.amount()) : BigInteger.ZERO;
        var work = new MissingMaterialExpansion<>(compiler, boundary, stock, external, Set.of(), force, force, budget, false, null, production);
        work.goalConstruction = true;
        return work;
    }

    /** The single goal-based entry point shared by main planning and ordinary fallback. */
    static <K> MissingMaterialExpansion<K> fromRequest(GraphCompiler<K> compiler, K target, long amount,
            Map<K, Long> stock, Set<K> external, Map<K, Long> seeds, Set<String> excluded,
            boolean preserve, boolean force, PlanningBudget budget) {
        long charge = 0;
        try {
            Map<K, BigInteger> initial = new LinkedHashMap<>(), missing = new LinkedHashMap<>();
            for (var entry : seeds.entrySet()) {
                budget.check(); budget.reserve(384); charge += 384;
                initial.put(entry.getKey(), BigInteger.valueOf(entry.getValue()));
            }
            budget.check();
            if (!initial.containsKey(target)) { budget.reserve(384); charge += 384; }
            initial.merge(target, BigInteger.valueOf(amount), BigInteger::add);
            for (var entry : initial.entrySet()) {
                budget.check();
                BigInteger available = force && target.equals(entry.getKey()) ? BigInteger.ZERO : PlanningInventory.exactAmount(stock, entry.getKey());
                BigInteger gap = entry.getValue().subtract(available);
                if (gap.signum() > 0 && !external.contains(entry.getKey())) missing.put(entry.getKey(), gap);
            }
            var boundary = new GraphPlan<>(target, amount, preserve, new PlanStep.Sequence(List.of()), Map.of(), initial, seeds, missing,
                    missing.isEmpty() ? GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL : GraphPlan.Result.MISSING_INPUT, budget.nodes(), 0);
            BigInteger production = force && !external.contains(target) ? BigInteger.valueOf(amount) : BigInteger.ZERO;
            var result = new MissingMaterialExpansion<>(compiler, boundary, stock, external, excluded, force, force, budget, false, null, production);
            result.goalConstruction = true;
            result.memory += charge; charge = 0;
            return result;
        } finally { budget.release(charge); }
    }
    /** A bounded alternative reuses complete support, without recursively
     * searching more cyclic prefixes or publishing a nested frontier. */
    static <K> MissingMaterialExpansion<K> alternative(GraphCompiler<K> compiler, GraphPlan<K> plan, Map<K, Long> stock,
            Set<K> external, Set<String> excluded, boolean force, boolean ignoreTargetStock, PlanningBudget budget, RefillProducerOrder<K> order) {
        return new MissingMaterialExpansion<>(compiler, plan, stock, external, excluded, force, ignoreTargetStock, budget, true, order, BigInteger.ZERO);
    }
    /** Candidate-local source preference; it never excludes the old sources or
     * turns failure of this restricted trial into a full-model negative proof. */
    static <K> MissingMaterialExpansion<K> sourceAlternative(GraphCompiler<K> compiler, GraphPlan<K> goals, Map<K, Long> stock,
            Set<K> external, Set<String> excluded, boolean force, boolean ignoreTargetStock, PlanningBudget budget,
            RefillProducerOrder<K> order, BigInteger production, Map<K, String> preferred) {
        var work = new MissingMaterialExpansion<>(compiler, goals, stock, external, excluded, force, ignoreTargetStock,
                budget, true, order, production);
        work.preferredSources = preferred;
        work.usedSourcePreferences = new HashSet<>();
        work.simpleSourcesOnly = true;
        return work;
    }
    static <K> MissingMaterialExpansion<K> jointAlternative(GraphCompiler<K> compiler, GraphPlan<K> goals, Map<K, Long> stock,
            Set<K> external, Set<String> excluded, boolean force, boolean ignoreTargetStock, PlanningBudget budget,
            RefillProducerOrder<K> order, BigInteger production, RefillPairCounts.Choice<K> choice) {
        var work = sourceAlternative(compiler, goals, stock, external, excluded, force, ignoreTargetStock, budget,
                order, production, Map.of(choice.key(), choice.first().id()));
        work.jointChoice = choice;
        return work;
    }
    private static <K> boolean hasProducibleMissing(GraphCompiler<K> compiler, GraphPlan<K> plan) {
        // Only a cheap index lookup here. Source inspection belongs to the
        // budgeted continuation, including rejected/excluded alternatives.
        for (K key : plan.missingExact().keySet()) if (!compiler.producers(key).isEmpty()) return true;
        return false;
    }
    boolean step() {
        if (boundarySearch != null && !boundarySearchDone) {
            if (!boundarySearch.step()) return false;
            result = boundarySearch.result(); boundarySearchDone = true;
            return explainBoundary();
        }
        if (diagnostics != null) {
            if (!diagnostics.step()) return false;
            result = diagnostics.result(); diagnostics.close(); diagnostics = null; return true;
        }
        if (sourceSearch != null && !sourceSearchDone) {
            if (!sourceSearch.step()) return false;
            result = sourceSearch.result(); sourceSearchDone = true; return explainBoundary();
        }
        if (reallocation != null && !reallocationDone) {
            if (!reallocation.step()) return false;
            result = reallocation.result(); reallocationDone = true; return explainBoundary();
        }
        if (jointSearch != null && !jointSearchDone) {
            if (!jointSearch.step()) return false;
            result = jointSearch.result(); jointSearchDone = true; return explainBoundary();
        }
        if (cycleSearch != null) {
            if (!cycleSearch.step()) return false;
            result = cycleSearch.result(); cycleSearch.close(); cycleSearch = null; return explainBoundary();
        }
        if (phase == Phase.PRUNE) {
            phase = Phase.DONE;
            // This terminal bill was complete before entering this pass. Do
            // not spend a mandatory work unit just to decline optional work.
            pruning = new RefillWordPruning<>(result, stock, external, forceCraft, ignoreTargetStock, budget,
                    budget.remainingWork() >= 512 ? pruningAllowance() : 0);
        }
        if (pruning != null) {
            if (!pruning.step()) return false;
            result = pruning.result(); pruning.close(); pruning = null; return searchCycles();
        }
        if (improvement != null) return improve();
        // Do not put a complete child slice after this pass's own operations.
        if (phase == Phase.RANK) {
            if (!order.step()) return false;
            queueRequirements(); return false;
        }
        if (phase == Phase.PAIR) {
            if (!pair.step()) return false;
            var summary = pair.summary();
            if (summary == null) { pair.close(); pair = null; phase = Phase.SOURCE; return false; }
            BigInteger delta = targetDelta.add(summary.delta(original.target()));
            if (losesProtectedProduction(delta)) {
                pair.close(); pair = null; phase = Phase.SOURCE; return false;
            }
            BigInteger need = summary.required(expanding).max(required.getOrDefault(expanding, BigInteger.ZERO).subtract(summary.delta(expanding)));
            if (!restoringProduction() && deficit(expanding, need, delta)
                    .compareTo(deficit(expanding, required.getOrDefault(expanding, BigInteger.ZERO), targetDelta)) >= 0) {
                pair.close(); pair = null; phase = Phase.SOURCE; return false;
            }
            proposal = new Proposal(pair, delta); pair = null;
            keys = summary.delta().keySet().iterator(); outputSupport = true; phase = Phase.SUPPORT;
            return false;
        }
        if (phase == Phase.VERIFY) {
            if (!verification.step()) return false;
            result = candidate; phase = Phase.DONE;
            budget.note("missing_expansion", "backward_requirements; prefixes=" + prefix.size() + "; missing_keys=" + result.missingExact().size());
            if (!allowMixed && mixtureOpportunity && !result.feasible() && budget.remainingWork() >= 512) {
                // The ordinary expanded bill is already verified and remains
                // available at every optional-search/parent publication limit.
                verification.close(); verification = null;
                improvementStart = budget.nodes();
                improvementWork = Math.min(budget.remainingWork() / 4,
                        Math.min(MIX_WORK_LIMIT, Math.max(512, Math.min(MIX_WORK_LIMIT, improvementStart - startedWork) * 4)));
                retainedFailure = budget.failureDetail();
                // Rankings and source lists are complete and stock-independent.
                // This child has exactly the same roots/exclusions/compiler and
                // borrows them read-only; only the parent releases their charge.
                improvement = new MissingMaterialExpansion<>(compiler, original, stock, external, excluded, forceCraft, ignoreTargetStock, budget, true, order, targetProduction);
                return false;
            }
            return complete();
        }
        for (int i = 0; i < OPERATIONS_PER_STEP; i++) {
            budget.check();
            if (advance()) return true;
            if (phase == Phase.RANK || phase == Phase.PAIR || phase == Phase.VERIFY || pruning != null || cycleSearch != null ||
                    sourceSearch != null && !sourceSearchDone || reallocation != null && !reallocationDone ||
                    jointSearch != null && !jointSearchDone || boundarySearch != null && !boundarySearchDone || diagnostics != null) return false;
        }
        return false;
    }
    private boolean advance() {
        switch (phase) {
            case START -> {
                reserve(256L + 128L * original.initialExact().size() + 96L * original.recipes().size());
                entries = original.initialExact().entrySet().iterator(); phase = Phase.REQUIREMENTS;
            }
            case REQUIREMENTS -> {
                if (entries.hasNext()) { var entry = entries.next(); required.put(entry.getKey(), entry.getValue()); }
                else { entries = null; bindings = original.recipes().entrySet().iterator(); phase = Phase.RECIPES; }
            }
            case RECIPES -> {
                if (bindings.hasNext()) { var entry = bindings.next(); recipes.put(entry.getKey(), entry.getValue()); }
                else {
                    bindings = null;
                    if (order == null) {
                        order = new RefillProducerOrder<>(compiler, original.missingExact().keySet(), external, excluded, budget);
                        phase = Phase.RANK;
                    } else queueRequirements();
                }
            }
            case QUEUE -> {
                if (keys.hasNext()) enqueue(keys.next());
                else { keys = null; phase = Phase.SELECT; }
            }
            case SELECT -> {
                if (pending.isEmpty()) {
                    if (prefix.isEmpty()) { result = original; return complete(); }
                    if (forceCraft && targetDelta.signum() < 0) {
                        // A private rank-crossing trial may reach a closed
                        // turnover. Its owner rejects insufficient production;
                        // do not publish the partially expanded word or abort
                        // the already verified incumbent.
                        if (simpleSourcesOnly) { result = original; phase = Phase.DONE; return true; }
                        if (!protectProductionCredit) {
                            // Material ranks may stop at a cycle boundary, but
                            // supplying target stock cannot repay production
                            // spent by this prefix. Allow one target-production
                            // repair across that rank, then forbid reopening
                            // its debt. Its new cyclic inputs remain real loans.
                            protectProductionCredit = true;
                            enqueue(original.target());
                            if (!pending.isEmpty()) break;
                        }
                        throw new IllegalStateException("Refill lost verified production credit");
                    }
                    steps = new ArrayList<>(); prefixIndex = prefix.size(); phase = Phase.STEPS;
                } else {
                    expanding = pending.remove(); queued.remove(expanding);
                    if (deficit(expanding, required.getOrDefault(expanding, BigInteger.ZERO), targetDelta).signum() == 0) break;
                    var node = order.get(expanding);
                    if (node == null || node.sources.isEmpty()) break;
                    sources = node.sources.iterator(); mayMix = false; mixing = false; partialOpportunity = false; pairTried = false; phase = Phase.SOURCE;
                    if (node.sources.size() == 2) {
                        var a = node.sources.get(0).recipe; var b = node.sources.get(1).recipe;
                        if (!a.id().equals(b.id()) && RefillBatchCounts.ordinary(a, expanding) && RefillBatchCounts.ordinary(b, expanding) &&
                                a.inputs().keySet().equals(b.inputs().keySet()) &&
                                (recipes.get(a.id()) == null || recipes.get(a.id()).equals(a)) &&
                                (recipes.get(b.id()) == null || recipes.get(b.id()).equals(b))) {
                            pair = RefillPairCounts.sameInput(new RefillPairCounts.Choice<>(expanding, a, b),
                                    deficit(expanding, required.getOrDefault(expanding, BigInteger.ZERO), targetDelta), budget);
                            phase = Phase.PAIR;
                        }
                    }
                }
            }
            case SOURCE -> {
                // Evaluate both counts before the funded-source early exit.
                // A plateau must not skip its old count's useful coproducts.
                if (plateauRuns != null) {
                    BigInteger runs = plateauRuns; plateauRuns = null; beginProposal(runs);
                } else if (allowMixed && !mixing && mayMix && (selected == null || !selected.funded) &&
                        (selected == null || selected.pair == null || !selected.pair.exactSameInput()) && !sources.hasNext()) {
                    // Keep every whole-source proposal ahead of a partial one.
                    // In particular, a later fully funded source must still win.
                    // An exact same-input cover already minimizes the entire
                    // batch's input. Spending stock first on a more expensive
                    // partial source cannot improve its final refill bill.
                    mixing = true; sources = order.get(expanding).sources.iterator();
                } else if (selected != null && selected.funded || !sources.hasNext()) {
                    if ((selected == null || !selected.funded) && !pairTried && jointChoice != null &&
                            expanding.equals(jointChoice.key()) && preferred(expanding, jointChoice.first())) {
                        pairTried = true;
                        var first = jointChoice.first(); var second = jointChoice.second();
                        if (!excluded.contains(first.id()) && !excluded.contains(second.id()) &&
                                (recipes.get(first.id()) == null || recipes.get(first.id()).equals(first)) &&
                                (recipes.get(second.id()) == null || recipes.get(second.id()).equals(second))) {
                            pair = new RefillPairCounts<>(jointChoice,
                                    key -> deficit(key, required.getOrDefault(key, BigInteger.ZERO), targetDelta), budget);
                            phase = Phase.PAIR; break;
                        }
                    }
                    mixtureOpportunity |= partialOpportunity && (selected == null || !selected.funded);
                    sources = null; source = null;
                    if (selected == null) phase = Phase.SELECT;
                    else {
                        reserve(160L + 128L * selected.ports());
                        targetDelta = selected.delta; entries = selected.needs.entrySet().iterator(); phase = Phase.APPLY;
                    }
                } else {
                    source = sources.next().recipe;
                    if (simpleSourcesOnly && source.batchSensitiveInputs()) break;
                    GraphRecipe<K> existing = recipes.get(source.id());
                    if (existing != null && !existing.equals(source)) break;
                    BigInteger count = CheckedAmounts.ceilDiv(deficit(expanding, required.getOrDefault(expanding, BigInteger.ZERO), targetDelta), gain(source, expanding));
                    if (mixing) {
                        if (count.compareTo(BigInteger.ONE) <= 0) break;
                        fundedRuns = count.subtract(BigInteger.ONE);
                        keys = source.inputs().keySet().iterator(); phase = Phase.FUNDING;
                    } else {
                        mayMix |= count.compareTo(BigInteger.ONE) > 0 && !stock.isEmpty() && order.get(expanding).sources.size() > 1;
                        BigInteger plateau = plateau(source, expanding, count);
                        plateauRuns = plateau.equals(count) ? null : plateau;
                        beginProposal(count);
                    }
                }
            }
            case FUNDING -> {
                if (fundedRuns.signum() > 0 && keys.hasNext()) {
                    K key = keys.next();
                    fundedRuns = limitFunding(key, fundedRuns, deficit(key, required.getOrDefault(key, BigInteger.ZERO), targetDelta));
                }
                if (fundedRuns.signum() == 0 || !keys.hasNext()) {
                    keys = null; phase = Phase.SOURCE;
                    BigInteger runs = fundedRuns; fundedRuns = null;
                    if (runs.signum() > 0) beginProposal(runs);
                }
            }
            case SUPPORT -> inspectSupport();
            case COMPARE -> compare();
            case APPLY -> {
                // Only a completely evaluated/compared proposal can mutate the
                // retained suffix. No other proposal runs during partial apply.
                if (entries.hasNext()) {
                    var entry = entries.next();
                    if (entry.getValue().signum() == 0) required.remove(entry.getKey());
                    else required.put(entry.getKey(), entry.getValue());
                    enqueue(entry.getKey());
                } else {
                    if (selected.crossedCycle && !crossedCycle.contains(expanding)) {
                        reserve(64); crossedCycle.add(expanding);
                    }
                    if (preferred(expanding, selected.recipe)) {
                        reserve(64); usedSourcePreferences.add(expanding);
                    }
                    recipes.put(selected.recipe.id(), selected.recipe);
                    if (selected.pair != null) {
                        var second = selected.pair.choice().second();
                        recipes.put(second.id(), second); prefix.add(selected.pair.word());
                    } else prefix.add(PlanStep.batch(selected.recipe.id(), selected.runs));
                    selected.close(); selected = null; entries = null; phase = Phase.SELECT;
                }
            }
            case STEPS -> {
                if (prefixIndex > 0) steps.add(prefix.get(--prefixIndex));
                else {
                    steps.add(original.steps()); deficits = new LinkedHashMap<>();
                    entries = required.entrySet().iterator(); phase = Phase.DEFICITS;
                }
            }
            case DEFICITS -> {
                if (entries.hasNext()) {
                    var entry = entries.next(); BigInteger value = missing(entry.getKey(), entry.getValue());
                    if (value.signum() > 0) deficits.put(entry.getKey(), value);
                } else { entries = null; phase = Phase.PUBLISH; }
            }
            case PUBLISH -> {
                // These private builders are never mutated after transfer. The
                // public constructors still defensively copy caller collections.
                candidate = GraphPlan.ownedRefill(original, FrozenPlanSteps.owned(steps), recipes, required, deficits,
                        deficits.isEmpty() ? GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL : original.result(), budget.nodes());
                var funded = GraphPlan.ownedRefill(candidate, candidate.steps(), candidate.recipes(), candidate.initialExact(), Map.of(),
                        GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, budget.nodes());
                // Count/output totals are unused here. Skip them only after
                // mandatory expansion/fold work saturates later quality quotas;
                // small expansions keep their existing work-scaled scheduling.
                long floor = startedWork > Long.MAX_VALUE - MIX_WORK_LIMIT / 4 ? Long.MAX_VALUE : startedWork + MIX_WORK_LIMIT / 4;
                // A shorter fold must not shrink the existing word-quality
                // allowance. Borrow only after preparation itself saturates it.
                if (budget.nodes() < floor) releaseOriginalSummary();
                verification = originalSummary == null ? PlanVerification.refillAfterWork(funded, budget, floor) :
                        PlanVerification.refillAfterWork(funded, budget, floor, original.steps(), originalSummary);
                phase = Phase.VERIFY;
            }
            case DONE -> { return true; }
            default -> throw new IllegalStateException("Child continuation must use its own slice");
        }
        return false;
    }
    private boolean complete() {
        if (verification == null) releaseOriginalSummary();
        phase = Phase.DONE;
        if (!allowMixed && !result.feasible() && budget.remainingWork() >= 512) {
            // Only the exact candidate just verified can lend its fold. This
            // parent keeps its charged verifier alive through the child's work.
            var known = verification != null && candidate == result ? verification.summary() : null;
            pruning = new RefillWordPruning<>(result, stock, external, forceCraft, ignoreTargetStock, budget, pruningAllowance(), known);
            return false;
        }
        return searchCycles();
    }
    private boolean searchCycles() {
        if (!cycleSearchEnabled || cycleSearchTried || order == null || result.feasible() || budget.remainingWork() < 4096) return explainBoundary();
        cycleSearchTried = true;
        cycleSearch = new RefillSupplySearch<>(result, order, stock, external, forceCraft, ignoreTargetStock, budget).tradeoffs(compiler, excluded);
        return false;
    }
    private boolean explainBoundary() {
        if (allowMixed || result.feasible() || result.missingExact().isEmpty()) return true;
        if (sourceSearchEnabled && sourceSearch == null && result.alternatives().isEmpty() && budget.remainingWork() >= 512) {
            sourceSearch = new RefillSourceSearch<>(compiler, result, stock, external, excluded, forceCraft, ignoreTargetStock, budget);
            return false;
        }
        var diagnosticOrder = sourceSearch == null ? null : sourceSearch.completedOrder();
        if (sourceSearchEnabled && reallocation == null && sourceSearch != null && sourceSearch.mayReallocate() &&
                result.alternatives().isEmpty() && budget.remainingWork() >= 512) {
            reallocation = RefillSourceSearch.reallocate(compiler, result, stock, external, excluded,
                    forceCraft, ignoreTargetStock, budget, diagnosticOrder);
            return false;
        }
        if (reallocation != null && reallocation.completedOrder() != null) diagnosticOrder = reallocation.completedOrder();
        if (sourceSearchEnabled && jointSearch == null && sourceSearch != null &&
                (sourceSearch.mayJoint() || reallocation != null && reallocation.mayJoint()) &&
                result.alternatives().isEmpty() && budget.remainingWork() >= 512) {
            jointSearch = RefillSourceSearch.joint(compiler, result, stock, external, excluded,
                    forceCraft, ignoreTargetStock, budget, diagnosticOrder);
            return false;
        }
        if (jointSearch != null && jointSearch.completedOrder() != null) diagnosticOrder = jointSearch.completedOrder();
        if (boundarySearchEnabled && sourceSearchEnabled && boundarySearch == null && budget.remainingWork() >= 4096 && hasProducibleMissing(compiler, result)) {
            boundarySearch = new RefillPlanningSearch<>(compiler, result, diagnosticOrder == null ? order : diagnosticOrder,
                    stock, external, excluded, forceCraft, ignoreTargetStock, budget).retainTrades(cycleSearchEnabled);
            return false;
        }
        if (diagnosticOrder != null) {
            diagnostics = new RefillBoundaryDiagnostics<>(result, compiler, diagnosticOrder, budget);
            return false;
        }
        if (order == null) { result = terminalResult(result); return true; }
        diagnostics = new RefillBoundaryDiagnostics<>(result, compiler, order, budget);
        return false;
    }
    private long pruningAllowance() {
        return Math.min(budget.remainingWork() / 4,
                Math.min(MIX_WORK_LIMIT, Math.max(512, Math.min(MIX_WORK_LIMIT, budget.nodes() - startedWork) * 4)));
    }
    private void queueRequirements() {
        pending = new PriorityQueue<>(Comparator.<K>comparingInt(order::rank).reversed());
        keys = required.keySet().iterator(); phase = Phase.QUEUE;
    }
    private boolean improve() {
        try {
            budget.checkpoint();
            if (budget.nodes() - improvementStart >= improvementWork) return finishImprovement(false);
            if (!comparingMixture) {
                if (improvement.step()) {
                    candidate = improvement.result(); keys = candidate.missingExact().keySet().iterator();
                    strict = candidate.missingExact().size() < result.missingExact().size(); comparingMixture = true;
                }
                return false;
            }
            // Compare the completed supply bills, not local slack: partially
            // funded choices can steal stock from a different unfinished path.
            for (int i = 0; i < OPERATIONS_PER_STEP; i++) {
                if (!keys.hasNext()) return finishImprovement(strict);
                budget.check(); K key = keys.next();
                int comparison = candidate.missingExact().get(key).compareTo(result.missingExact().getOrDefault(key, BigInteger.ZERO));
                if (comparison > 0) return finishImprovement(false);
                strict |= comparison < 0;
            }
            return false;
        } catch (PlanningBudget.Exhausted limit) {
            // Optional quality work cannot erase a completed preview. A real
            // cancellation remains exceptional and is never swallowed here.
            budget.failureDetail(retainedFailure);
            return finishImprovement(false);
        }
    }
    private boolean finishImprovement(boolean accept) {
        if (accept) result = candidate;
        improvement.close(); improvement = null; keys = null; candidate = result;
        budget.note("missing_mix", (accept ? "accepted" : "retained_original") + "; work=" + (budget.nodes() - improvementStart));
        return complete();
    }
    private void beginProposal(BigInteger runs) {
        BigInteger nextDelta = targetDelta.add(gain(source, original.target()).multiply(runs));
        if (losesProtectedProduction(nextDelta)) return;
        BigInteger old = deficit(expanding, required.getOrDefault(expanding, BigInteger.ZERO), targetDelta);
        if (!restoringProduction() && deficit(expanding, before(source, expanding, runs), nextDelta).compareTo(old) >= 0) return;
        proposal = new Proposal(source, runs, nextDelta);
        long bytes = 192L + 128L * ((long) source.inputs().size() + source.outputs().size());
        budget.reserve(bytes); proposal.bytes = bytes;
        keys = source.inputs().keySet().iterator(); outputSupport = false; phase = Phase.SUPPORT;
    }
    private void inspectSupport() {
        if (!keys.hasNext() && !outputSupport) {
            keys = proposal.recipe.outputs().keySet().iterator(); outputSupport = true;
        }
        if (!keys.hasNext()) { finishSupport(); return; }
        K input = keys.next();
        // Scan the ordered union without allocating an unbounded temporary set.
        if (proposal.pair != null || !outputSupport || !proposal.recipe.inputs().containsKey(input)) {
            BigInteger need = proposal.pair == null ? before(proposal.recipe, input, proposal.runs) :
                    proposal.pair.summary().required(input).max(required.getOrDefault(input, BigInteger.ZERO).subtract(proposal.pair.summary().delta(input)));
            proposal.needs.put(input, need);
            BigInteger previous = deficit(input, required.getOrDefault(input, BigInteger.ZERO), targetDelta);
            BigInteger next = deficit(input, need, proposal.delta);
            if (next.compareTo(previous) > 0) {
                proposal.funded = false;
                if (proposal.partialRuns != null && proposal.partialRuns.signum() > 0)
                    proposal.partialRuns = limitFunding(input, proposal.partialRuns, previous);
                // First-ready ranks can hide a deeper, cheaper derivation.
                // A private trial may cross that order once per preferred key.
                // After applying it, every later expansion of that key again
                // obeys the old rank guard. Finite preferences cannot turn a
                // cyclic choice into an endlessly renewable rank exception.
                if (order.rank(input) >= order.rank(expanding) && !preferred(expanding, proposal.recipe) && !restoringProduction()) {
                    var from = order.get(expanding); var to = order.get(input);
                    // A goal word may enter a cycle once along its selected
                    // producer, stopping at a genuine feedback boundary rather
                    // than buying its downstream output. Reopening an already
                    // crossed material is forbidden; no budget cutoff defines it.
                    if (!goalConstruction || from == null || to == null || !from.cycle ||
                            from.componentId != to.componentId || input.equals(expanding) ||
                            crossedCycle.contains(expanding) || crossedCycle.contains(input)) {
                        finishComparison(false); return;
                    }
                    proposal.crossedCycle = true;
                }
            }
        }
        // Iterator handoffs need no extra arithmetic/work unit or scheduler turn.
        if (!keys.hasNext() && !outputSupport) {
            keys = proposal.recipe.outputs().keySet().iterator(); outputSupport = true;
        }
        if (!keys.hasNext()) finishSupport();
    }
    private void finishSupport() {
        if (!allowMixed && !proposal.funded && proposal.partialRuns != null && proposal.partialRuns.signum() > 0 &&
                deficit(expanding, before(source, expanding, proposal.partialRuns), targetDelta.add(gain(source, original.target()).multiply(proposal.partialRuns)))
                        .compareTo(deficit(expanding, required.getOrDefault(expanding, BigInteger.ZERO), targetDelta)) < 0)
            partialOpportunity = true;
        if (selected == null || proposal.funded && !selected.funded) finishComparison(true);
        else if (!selected.funded && proposal.pair != null && preferred(expanding, proposal.recipe)) finishComparison(true);
        else if (!selected.funded && preferred(expanding, proposal.recipe) != preferred(expanding, selected.recipe))
            finishComparison(preferred(expanding, proposal.recipe));
        else if (forceCraft && proposal.delta.compareTo(selected.delta) < 0) finishComparison(false);
        else {
            strict = false; comparingSelected = false;
            keys = proposal.needs.keySet().iterator(); phase = Phase.COMPARE;
        }
    }
    private void compare() {
        // Compare the full requirement vector and production credit, not a sum
        // of unlike missing items. Incomparable supplies retain source priority.
        if (!keys.hasNext() && !comparingSelected) {
            keys = selected.needs.keySet().iterator(); comparingSelected = true;
        }
        if (!keys.hasNext()) { finishComparison(strict); return; }
        K key = keys.next();
        if (!comparingSelected || !proposal.needs.containsKey(key)) {
            int comparison = proposal.need(key).compareTo(selected.need(key));
            if (comparison > 0) { finishComparison(false); return; }
            strict |= comparison < 0;
        }
        if (!keys.hasNext() && !comparingSelected) {
            keys = selected.needs.keySet().iterator(); comparingSelected = true;
        }
        if (!keys.hasNext()) finishComparison(strict);
    }
    private void finishComparison(boolean accept) {
        if (accept) {
            if (selected != null) selected.close();
            selected = proposal;
        } else proposal.close();
        proposal = null; keys = null; phase = Phase.SOURCE;
    }
    private boolean preferred(K key, GraphRecipe<K> recipe) {
        return recipe.id().equals(preferredSources.get(key)) && !usedSourcePreferences.contains(key);
    }
    private boolean restoringProduction() {
        return expanding.equals(original.target()) &&
                (protectProductionCredit && targetDelta.signum() < 0 || !simpleSourcesOnly && targetProduction.signum() > 0 && targetDelta.compareTo(targetProduction) < 0);
    }
    private boolean losesProtectedProduction(BigInteger next) {
        // A goal constructor has no suffix to preserve its delivery credit.
        // Once established, do not spend it around the same closed component
        // and repeatedly reopen the target. The remaining cycle input is a
        // real supply boundary; alternate executable cycles remain searchable.
        return protectProductionCredit && next.signum() < 0 || !simpleSourcesOnly && targetProduction.signum() > 0 &&
                targetDelta.compareTo(targetProduction) >= 0 && next.compareTo(targetProduction) < 0;
    }
    private void enqueue(K key) {
        var node = order.get(key);
        if (node != null && !node.sources.isEmpty() && deficit(key, required.getOrDefault(key, BigInteger.ZERO), targetDelta).signum() > 0 && queued.add(key))
            pending.add(key);
    }
    private BigInteger missing(K key, BigInteger need) {
        if (external.contains(key)) return BigInteger.ZERO;
        return need.subtract(available(key)).max(BigInteger.ZERO);
    }
    private BigInteger available(K key) {
        return ignoreTargetStock && original.target().equals(key) ? BigInteger.ZERO : PlanningInventory.exactAmount(stock, key);
    }
    private BigInteger deficit(K key, BigInteger need, BigInteger prefixDelta) {
        BigInteger value = missing(key, need);
        // A prefix may use target stock to prepare a cyclic input, but must
        // replace that consumption. Preserve the verified suffix's production
        // credit instead of treating existing finished goods as new crafting.
        return forceCraft && original.target().equals(key) ? value.max(targetProduction.subtract(prefixDelta)) : value;
    }
    private BigInteger gain(GraphRecipe<K> recipe, K key) {
        return BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L)).subtract(BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L)));
    }
    private BigInteger before(GraphRecipe<K> recipe, K key, BigInteger runs) {
        BigInteger delta = gain(recipe, key);
        BigInteger first = BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L))
                .add(delta.negate().max(BigInteger.ZERO).multiply(runs.subtract(BigInteger.ONE)));
        return first.max(required.getOrDefault(key, BigInteger.ZERO).subtract(delta.multiply(runs)));
    }
    /** Largest positive batch that introduces no additional supply deficit. */
    private BigInteger limitFunding(K key, BigInteger limit, BigInteger previous) {
        BigInteger delta = gain(source, key), loss = delta.negate();
        if (!external.contains(key)) {
            BigInteger ceiling = available(key).add(previous);
            BigInteger input = BigInteger.valueOf(source.inputs().getOrDefault(key, 0L));
            if (input.compareTo(ceiling) > 0) return BigInteger.ZERO;
            if (loss.signum() > 0) {
                limit = limit.min(ceiling.subtract(input).divide(loss).add(BigInteger.ONE))
                        .min(ceiling.subtract(required.getOrDefault(key, BigInteger.ZERO)).divide(loss));
            }
        }
        if (forceCraft && original.target().equals(key) && loss.signum() > 0)
            limit = limit.min(targetDelta.add(previous).subtract(targetProduction).divide(loss));
        return limit;
    }
    /** Keep the old count too: its extra firings may supply a useful coproduct. */
    private BigInteger plateau(GraphRecipe<K> recipe, K key, BigInteger old) {
        long input = recipe.inputs().getOrDefault(key, 0L);
        if (BigInteger.valueOf(input).compareTo(available(key)) <= 0) return old;
        BigInteger delta = gain(recipe, key);
        BigInteger gap = required.getOrDefault(key, BigInteger.ZERO).subtract(BigInteger.valueOf(input)).max(BigInteger.ZERO);
        BigInteger runs = CheckedAmounts.ceilDiv(gap, delta).max(BigInteger.ONE);
        if (forceCraft && original.target().equals(key))
            runs = runs.max(CheckedAmounts.ceilDiv(targetProduction.subtract(targetDelta).max(BigInteger.ZERO), delta));
        return runs;
    }

    private final class Proposal implements AutoCloseable {
        final GraphRecipe<K> recipe;
        final BigInteger runs, delta;
        final RefillPairCounts<K> pair;
        final Map<K, BigInteger> needs = new LinkedHashMap<>();
        boolean funded = true, crossedCycle;
        BigInteger partialRuns;
        long bytes;
        Proposal(GraphRecipe<K> recipe, BigInteger runs, BigInteger delta) {
            this.recipe = recipe; this.runs = runs; this.delta = delta; pair = null;
            if (!allowMixed && mayMix && runs.compareTo(BigInteger.ONE) > 0) partialRuns = runs.subtract(BigInteger.ONE);
        }
        Proposal(RefillPairCounts<K> pair, BigInteger delta) {
            this.pair = pair; recipe = pair.choice().first(); runs = pair.firstCount(); this.delta = delta;
        }
        long ports() {
            long size = (long) recipe.inputs().size() + recipe.outputs().size();
            return pair == null ? size : size + pair.choice().second().inputs().size() + pair.choice().second().outputs().size();
        }
        BigInteger need(K key) { return needs.getOrDefault(key, required.getOrDefault(key, BigInteger.ZERO)); }
        @Override public void close() { if (pair != null) pair.close(); budget.release(bytes); bytes = 0; }
    }

    GraphPlan<K> result() {
        if (boundarySearch != null && !boundarySearchDone) return boundarySearch.result();
        if (sourceSearch != null && !sourceSearchDone) return sourceSearch.result();
        if (reallocation != null && !reallocationDone) return reallocation.result();
        if (jointSearch != null && !jointSearchDone) return jointSearch.result();
        return cycleSearch == null ? result : cycleSearch.result();
    }
    BigInteger prefixProduction() { return targetDelta; }
    /** A parent retaining this completed alternative takes its conservative
     * builder charge; close still releases verifier/child scratch separately. */
    long takeResultCharge() {
        if (result == null) throw new IllegalStateException("No completed refill to retain");
        long charge = memory; memory = 0; return charge;
    }
    boolean diagnosing() { return diagnostics != null; }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    private void releaseOriginalSummary() {
        originalSummary = null;
        budget.release(originalSummaryBytes); memory -= originalSummaryBytes; originalSummaryBytes = 0;
    }

    @Override public void close() {
        releaseOriginalSummary();
        if (diagnostics != null) { diagnostics.close(); diagnostics = null; }
        if (boundarySearch != null) { boundarySearch.close(); boundarySearch = null; }
        if (pair != null) { pair.close(); pair = null; }
        if (jointSearch != null) { jointSearch.close(); jointSearch = null; }
        if (reallocation != null) { reallocation.close(); reallocation = null; }
        if (sourceSearch != null) { sourceSearch.close(); sourceSearch = null; }
        if (cycleSearch != null) { cycleSearch.close(); cycleSearch = null; }
        if (pruning != null) { pruning.close(); pruning = null; }
        if (improvement != null) { improvement.close(); improvement = null; }
        if (proposal != null) { proposal.close(); proposal = null; }
        if (selected != null) { selected.close(); selected = null; }
        keys = null; entries = null; bindings = null; sources = null; source = null;
        if (order != null && !borrowedOrder) order.close();
        if (verification != null) verification.close();
        budget.release(memory); memory = 0;
    }
}
