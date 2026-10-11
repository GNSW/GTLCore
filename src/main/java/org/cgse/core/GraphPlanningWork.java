// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** One order's retained search frontier, graph traversal, solve and final verification. */
public final class GraphPlanningWork<K> implements PlanningScheduler.Work<GraphPlan<K>> {

    private final GraphCompiler<K> compiler;
    private final K target;
    private final long amount, started = System.nanoTime();
    private final Map<K, Long> stock;
    private final Map<K, Long> requiredSeeds;
    private final Set<K> external;
    private final boolean preserve, forceCraft;
    private final PlanningBudget budget;
    private final Set<String> excluded;
    private final int nesting;
    private final Deque<Map<K, Integer>> pending = new ArrayDeque<>();
    private final Set<Map<K, Integer>> seen = new HashSet<>();
    private final Set<Map<K, Integer>> queued = new HashSet<>();
    private long frontierMemory;
    private Map<K, Integer> choices;
    private GraphCompilation<K> compiling;
    private GraphCompiler.Compiled<K> graph;
    private long graphMemory;
    private GraphSolve<K> solving;
    private GraphStockViewPortfolio<K> stockView;
    private GraphPlan<K> stockInterruptedCandidate;
    private PlanningBudget.CandidateOrigin stockInterruptedOrigin;
    private boolean validatingStockProposal;
    private boolean stockViewTried, stockViewDelayed;
    private Boolean cheapCountView;
    private long firstGraphStarted, firstGraphWork, stockDelayStarted, stockDelayAllowance;
    private long stockPausedAt, stockSpareMemory;
    private int stockResumePhase = -1;
    private Bootstrap bootstrap;
    private PlanVerification<K> verifying;
    private PlanningBudget.CandidateObservation candidateObservation;
    private boolean validationPending;
    private PlanningBudget.CandidateOrigin candidateOrigin = PlanningBudget.CandidateOrigin.SOURCE_GRAPH;
    private ForceCraftProof<K> productionProof;
    private AllocationSearch<K> allocating;
    private IntegerCountSearch<K> countSearch;
    private IntegerCountSearch<K> parkedCounts;
    private long countPausedAt;
    private int countResumePhase = -1;
    private int allocationResumePhase;
    private long allocationScoutStarted, allocationScoutAllowance;
    private OrderProofs<K> proofs;
    private boolean proofAttempted;
    private SourceExplanation<K> explaining;
    private Map<K, Integer> sourceCore;
    private Iterator<Map<K, Integer>> repairs;
    private SeedPortfolio<K> seedOptimization;
    private boolean seedAttempted;
    private MissingStockAnalysis<K> missingAnalysis;
    private QuantityAnalysis<K> quantities;
    private boolean quantityDeferred;
    private boolean countBeforeQuantity;
    private long quickSearchStarted, quickSearchAllowance;
    private long previewStarted, previewAllowance;
    private int quantityResumePhase = -1;
    private Boolean quantityBlocked;
    private Boolean stockBlocked;
    private boolean allocationAttempted, countAttempted, frontierTruncated;
    private boolean bootstrapTooLarge;
    private boolean triedTargetSeedConsumption;
    private boolean stockWitnessTried;
    private boolean sourceScoutTried;
    private final long stockWitnessAfter;
    private Iterator<K> alternatives;
    private Set<K> preferredAlternatives = Set.of();
    private long alternativeMemory;
    private GraphPlan<K> candidate, best, verified, result;
    private int phase;
    private int sourceAttempts, allocationAfterSources = 8;
    private long allocationTurnStarted, allocationTurnAllowance;
    private CatalystPolicy catalystPolicy = CatalystPolicy.STOCK;
    private PlanReuseVerification<K> reuseVerification;
    private boolean startedWork;
    private final java.util.concurrent.atomic.AtomicBoolean registeredSearch = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Recheck one prior complete execution witness before searching. The caller
     * must retain the actual program, not just counts; failed proposals never
     * become cached conclusions. Recipe bindings and current request obligations
     * are checked again, and an accepted plan carries no old optimality claim.
     */
    public GraphPlanningWork<K> reuseCandidate(GraphPlan<K> previous) {
        if (startedWork || phase != 0) throw new IllegalStateException("Planning already started");
        if (reuseVerification != null) reuseVerification.close();
        reuseVerification = previous == null ? null : new PlanReuseVerification<>(previous, compiler, target, amount,
                stock, external, requiredSeeds, preserve, forceCraft, budget, started);
        return this;
    }

    public GraphPlanningWork<K> catalysts(CatalystPolicy policy) {
        if (phase != 0) throw new IllegalStateException("Planning already started");
        catalystPolicy = policy;
        return this;
    }

    public GraphPlanningWork(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
                             boolean preserve, boolean forceCraft, PlanningBudget budget) {
        this(compiler, target, amount, stock, Set.of(), Map.of(), preserve, forceCraft, budget, Set.of(), 0);
    }

    public GraphPlanningWork(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock, Set<K> external,
                             boolean preserve, boolean forceCraft, PlanningBudget budget) {
        this(compiler, target, amount, stock, external, Map.of(), preserve, forceCraft, budget, Set.of(), 0);
    }

    public GraphPlanningWork(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock, Set<K> external,
                             Map<K, Long> requiredSeeds, boolean preserve, boolean forceCraft, PlanningBudget budget) {
        this(compiler, target, amount, stock, external, requiredSeeds, preserve, forceCraft, budget, Set.of(), 0);
    }

    private GraphPlanningWork(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock, Set<K> external, Map<K, Long> requiredSeeds,
                              boolean preserve, boolean forceCraft, PlanningBudget budget, Set<String> excluded, int nesting) {
        if (amount <= 0) throw new IllegalArgumentException("Non-positive order");
        if (nesting > 64) throw new PlanningBudget.Exhausted(PlanningBudget.Limit.GRAPH_LIMIT);
        this.compiler = compiler;
        this.target = target;
        this.amount = amount;
        this.stock = stock;
        this.requiredSeeds = GraphRecipe.amounts(requiredSeeds);
        this.external = Set.copyOf(external);
        this.preserve = preserve;
        this.forceCraft = forceCraft;
        this.budget = budget;
        stockWitnessAfter = budget.searchWork() + Math.min(16384, budget.remainingWork() / 8);
        this.excluded = Set.copyOf(excluded);
        this.nesting = nesting;
        pending.add(Map.of());
        budget.note("source_search", "target=" + target + "; amount=" + amount + "; nesting=" + nesting + "; sources=" + compiler.producers(target).size());
        compiler.searchOpened();
        registeredSearch.set(true);
    }

    @Override
    public boolean advance(PlanningScheduler.Slice slice) {
        // step() owns its search/compilation charge, including synchronous
        // callers. Scheduling the same operation must not charge it twice.
        while (slice.nextUncharged()) {
            if (step(slice)) return true;
            if (waitingFor() != null) return false;
        }
        return false;
    }

    /** Synchronous callers drive the exact same retained state machine. */
    public boolean step() {
        return step(null);
    }

    private boolean step(PlanningScheduler.Slice slice) {
        try (var costs = budget.trace(nesting == 0 ? PlanningCostTrace.Origin.COORDINATOR : null, PlanningCostTrace.Stage.OTHER)) {
            return step(slice, costs);
        }
    }

    private boolean step(PlanningScheduler.Slice slice, PlanningCostTrace.Scope costs) {
        startedWork = true;
        var observation = phase == 4 ? candidateObservation : null;
        long validationWork = observation == null ? 0 : budget.threadWork();
        long validationNanos = observation == null ? 0 : System.nanoTime();
        var validationOwner = phase == 4 && validatingStockProposal ? stockView : null;
        int validationView = validationOwner == null ? -1 : validationOwner.proposalView();
        long validationSearch = validationOwner == null ? 0 : budget.threadSearchWork();
        String failureBeforeStep = budget.failureDetail();
        try {
            if (compilationActive()) budget.compilationCheck();
            else budget.check();
            if (reuseVerification != null) {
                if (!reuseVerification.step()) return false;
                GraphPlan<K> reused = reuseVerification.result();
                reuseVerification.close();
                reuseVerification = null;
                if (reused != null) {
                    result = verified = reused;
                    budget.phase(PlanningBudget.Phase.COMPLETE);
                    phase = 8;
                    return true;
                }
            }
            // A cheap ordinary alternative gets a bounded turn, not ownership
            // of the remaining order budget. Keep its partial compile/solve
            // alive while stock views run, then resume the same state.
            if (stockViewDelayed && budget.searchWork() - stockDelayStarted >= stockDelayAllowance &&
                    phase != 4 && phase != 8 && phase != 10 && phase != 17 &&
                    !(phase == 3 && candidate.feasible())) {
                int resume = phase;
                if (startStockView()) {
                    stockResumePhase = resume;
                    budget.note("stock_view", "ordinary_source_headstart_limit; work=" + (budget.searchWork() - stockDelayStarted));
                }
            }
            if (stockView != null && stockView.paused()) {
                // Optional retained proposals cannot crowd out the full model.
                // Evict before the next primary step, never retry a partially
                // mutated search after a memory-limit exception.
                if (budget.availableBytes() < stockSpareMemory) {
                    stockView.close();
                    stockView = null;
                    budget.note("stock_view", "evicted_parked_frontier; memory_pressure");
                } else if (!Boolean.TRUE.equals(quantityBlocked) && !Boolean.TRUE.equals(stockBlocked) &&
                        // Recursive seed repair retains its continuation too.
                        // It must not own the rest of an order while a stock
                        // view waits. Finish an ordinary region candidate before
                        // switching so source choices retain their fair turn.
                        (phase == 0 || phase == 3 && !candidate.feasible() ||
                                phase == 5 || phase == 6 || phase == 9 || phase == 12) &&
                        // At the final handoff both retained frontiers remain
                        // eligible. Run the older one rather than letting this
                        // earlier hook continually preempt integer search.
                        !(parkedCounts != null && (phase == 9 || phase == 0 && pending.isEmpty()) &&
                                stockPausedAt >= countPausedAt) &&
                        // A fixed wait larger than the remaining budget would
                        // permanently strand this continuation near the limit.
                        (budget.searchWork() - stockPausedAt >= stockResumeWait() || phase == 0 && pending.isEmpty() || phase == 9)) {
                            stockResumePhase = phase;
                            stockView.resume(Math.max(1, Math.min(2_097_152, budget.remainingWork() / 4)));
                            phase = 19;
                        }
            }
            // Retained integer views can finish without the expensive rational
            // precheck. Interleave the two continuations instead of requiring
            // the relaxation to finish before another integer-search slice.
            // Queued source alternatives need the same turn as allocation:
            // repeatedly resuming counts after a few proof-pruned choices can
            // fill memory before a cheap, still-pending source gets examined.
            if (parkedCounts != null && (phase == 0 || phase == 5 || phase == 6 || phase == 9 ||
                    phase == 12 && quantities != null && quantities.heavyAnalysisActive() &&
                            (parkedCounts.hasScaledProgress() || parkedCounts.hasRetainedViews())) &&
                    (budget.searchWork() - countPausedAt >= Math.min(
                            phase == 12 ? 131_072 : phase == 6 || !pending.isEmpty() ? 1_048_576 : 32_768,
                            Math.max(4096, budget.remainingWork() / 8)) || phase == 0 && pending.isEmpty() || phase == 9)) {
                countResumePhase = phase;
                countSearch = parkedCounts;
                parkedCounts = null;
                countSearch.resume();
                phase = 14;
            }
            if (quantityDeferred && phase != 4 && phase != 8 && phase != 10 && phase != 18 && phase != 19 &&
                    !(phase == 3 && candidate.feasible()) &&
                    (phase == 9 || budget.searchWork() - quickSearchStarted >= quickSearchAllowance)) {
                if (!countAttempted && quantities.readyForHeavyAnalysis() &&
                        (catalystPolicy.maxExtraCopies() == 0 || candidate.seeds().isEmpty())) {
                    // Feasibility scouts also help medium and unbounded count
                    // domains. Magnitude is not an admission criterion. Retain
                    // both frontiers when the bounded scout hands back control.
                    // Extra-catalyst trials first keep their acceleration goal;
                    // an early minimal witness would prematurely finish the
                    // outer policy's search for affordable parallel seeds.
                    quantityDeferred = false;
                    quantityResumePhase = phase;
                    countBeforeQuantity = true;
                    budget.note("quantity", "scout_exact_counts_before_relaxation");
                    startCountSearch();
                    countSearch.scout(524_288);
                } else resumeQuantityAnalysis(phase);
            }
            if (costs != null) costs.select(nesting == 0 ? costOrigin() : null,
                    phase == 0 || phase == 1 ? PlanningCostTrace.Stage.PREPARE : PlanningCostTrace.Stage.SEARCH);
            switch (phase) {
                case 0 -> {
                    if (stockViewDelayed && pending.isEmpty()) {
                        if (startStockView()) {
                            stockResumePhase = 0;
                            return false;
                        }
                    }
                    if (bootstrapTooLarge && !countAttempted) {
                        budget.note("source_dispatch", "bootstrap_range_limit; trying_exact_counts");
                        startCountSearch();
                        return false;
                    }
                    if (forceCraft && !external.contains(target) &&
                            compiler.producers(target).stream().noneMatch(r -> !excluded.contains(r.id()))) {
                        // An AE crafting request asks for newly produced units,
                        // even when the target is already stored. With no source
                        // this is a missing-target preview, not a failed search.
                        // Keep the whole requested amount missing; otherwise a
                        // requester could keep "crafting" its existing stock.
                        Map<K, BigInteger> initial = new LinkedHashMap<>(), missing = new LinkedHashMap<>();
                        requiredSeeds.forEach((key, count) -> {
                            initial.put(key, BigInteger.valueOf(count));
                            long deficit = Math.max(0, count - stock.getOrDefault(key, 0L));
                            if (deficit != 0 && !external.contains(key)) missing.put(key, BigInteger.valueOf(deficit));
                        });
                        initial.merge(target, BigInteger.valueOf(amount), BigInteger::add);
                        missing.merge(target, BigInteger.valueOf(amount), BigInteger::add);
                        budget.note("target", "no_pattern; returning missing preview; requested=" + amount);
                        best = new GraphPlan<>(target, amount, preserve, new PlanStep.Sequence(List.of()), Map.of(),
                                initial, requiredSeeds, missing, GraphPlan.Result.MISSING_INPUT, budget.nodes(), System.nanoTime() - started);
                        verifyMissing();
                        return false;
                    }
                    // Give alternate compiled source graphs a small head start.
                    // One bad source must not send a large DAG straight into
                    // per-batch allocation search. Mixed sources still get their
                    // own attempt before enumerating the remaining combinations.
                    if (allocating != null && (pending.isEmpty() || sourceAttempts >= allocationAfterSources)) {
                        beginAllocationTurn();
                        return false;
                    }
                    if (!allocationAttempted && best != null &&
                            (pending.isEmpty() || sourceAttempts >= allocationAfterSources) && !provenMissing(best)) {
                        allocationAttempted = true;
                        allocating = newAllocation(false).proofs(proofs);
                        beginAllocationTurn();
                        return false;
                    }
                    if (pending.isEmpty()) {
                        if (best == null || best.missing().isEmpty()) {
                            budget.failureDetail(compiler.producers(target).stream().noneMatch(r -> !excluded.contains(r.id())) && forceCraft ?
                                    "NO_TARGET_PATTERN: " + target : "NO_EXECUTABLE_WITNESS: source_choices=" + seen.size());
                            result = failure(GraphPlan.Result.UNKNOWN);
                            return true;
                        }
                        if (stockBlocked == null) missingAnalysis = new MissingStockAnalysis<>(compiler, target, stock, external,
                                requiredSeeds.keySet(), excluded, forceCraft, budget);
                        phase = 9;
                        return false;
                    }
                    choices = pending.removeFirst();
                    queued.remove(choices);
                    if (!seen.add(choices)) return false;
                    // Source explanations were checked with all unmentioned
                    // choices free. Reusing one does not require rebuilding the
                    // same rejected dependency graph first.
                    sourceCore = proofs == null || best == null ? null : proofs.sourceConflict(choices, true);
                    if (sourceCore != null) {
                        prepareAlternatives();
                        budget.note("source_backjump", "before_compile; relevant_decisions=" + sourceCore.size());
                        phase = 5;
                        return false;
                    }
                    budget.release(graphMemory);
                    graphMemory = 0;
                    if (sourceAttempts == 0) firstGraphStarted = budget.searchWork();
                    graph = compiler.cached(target, requiredSeeds.keySet(), choices, excluded);
                    if (graph == null) compiling = compiler.begin(target, requiredSeeds.keySet(), choices, excluded, budget);
                    phase = 1;
                }
                case 1 -> {
                    if (compiling != null) {
                        // The outer coordinator admitted this operation. A
                        // second slice gate can starve compilation at quantum 1.
                        if (!compiling.step(slice)) return false;
                        graph = compiling.result();
                        compiling.close();
                        compiler.publish(target, requiredSeeds.keySet(), choices, excluded, graph);
                        compiling = null;
                    }
                    long bytes = graph.estimatedBytes();
                    budget.reserve(bytes);
                    graphMemory = bytes;
                    sourceCore = proofs == null ? null : proofs.sourceConflict(assignment());
                    if (sourceCore != null && best != null) {
                        prepareAlternatives();
                        phase = 5;
                        return false;
                    }
                    // Proven conflicts skipped above are not source attempts.
                    sourceAttempts++;
                    triedTargetSeedConsumption = false;
                    solving = new GraphSolve<>(graph, target, amount, stock, external, requiredSeeds, preserve, forceCraft, budget, started, catalystPolicy, stock)
                            .program(compiler.demandProgram(graph, budget));
                    budget.note("compile", "choice=" + seen.size() + "; recipes=" + graph.recipes().size() + "; regions=" + graph.regions().size() +
                            "; cyclic=" + graph.regions().stream().filter(GraphCompiler.Region::cyclic).count());
                    phase = 2;
                }
                case 2 -> {
                    if (!solving.step()) return false;
                    candidate = solving.result();
                    candidateOrigin = PlanningBudget.CandidateOrigin.SOURCE_GRAPH;
                    if (sourceAttempts == 1) firstGraphWork = budget.searchWork() - firstGraphStarted;
                    budget.note("region_solve", candidate.result() + "; missing_keys=" + candidate.missingExact().size());
                    solving.close();
                    solving = null;
                    if (!candidate.feasible() && !stockViewTried && !stock.isEmpty() && !cheapCountView()) {
                        if (startStockView()) return false;
                    }
                    if (seen.size() >= 2 && !candidate.feasible() && graph.selected().keySet().stream().anyMatch(key -> compiler.producers(key).size() > 1)) {
                        ensureProofs();
                        if (proofs != null && proofs.model != null) {
                            explaining = new SourceExplanation<>(proofs, compiler, target, requiredSeeds.keySet(), excluded, graph, choices, budget);
                            phase = 16;
                            return false;
                        }
                    }
                    afterSolve();
                }
                case 3 -> {
                    if (bootstrap != null) {
                        if (!bootstrap.step(slice)) return false;
                        candidate = bootstrap.result;
                        candidateOrigin = PlanningBudget.CandidateOrigin.BOOTSTRAP;
                        bootstrap.close();
                        bootstrap = null;
                    }
                    if (candidate.feasible()) {
                        beginVerification(candidateOrigin);
                        phase = 4;
                    } else {
                        if (best == null || (!candidate.missing().isEmpty() &&
                                (best.missing().isEmpty() || candidate.missing().size() < best.missing().size())))
                            best = candidate;
                        if (retryWithTargetSeed()) return false;
                        prepareAlternatives();
                        phase = 5;
                    }
                }
                case 4 -> {
                    if (!verifying.step()) return false;
                    if (forceCraft && !external.contains(target)) {
                        if (productionProof == null) productionProof = new ForceCraftProof<>(candidate, verifying, requiredSeeds, budget);
                        if (!productionProof.step()) return false;
                        boolean productive = productionProof.proved();
                        CandidateFeedback feedback = productionProof.feedback();
                        var outcome = productionProof.outcome();
                        productionProof.close();
                        productionProof = null;
                        if (outcome == ForceCraftProof.Outcome.MEMORY_LIMIT && validatingStockProposal && stockView != null) {
                            retireStockFrontier();
                            return false;
                        }
                        if (!productive) {
                            budget.note("force_craft", "candidate_rejected; reason=" + outcome);
                            finishObservation(feedback == CandidateFeedback.REJECTED ? PlanningBudget.CandidateOutcome.REJECTED :
                                    PlanningBudget.CandidateOutcome.INCONCLUSIVE);
                            verifying.close();
                            verifying = null;
                            if (validatingStockProposal) {
                                if (stockView != null) stockView.feedback(feedback);
                                candidate = stockInterruptedCandidate;
                                candidateOrigin = stockInterruptedOrigin;
                                stockInterruptedCandidate = null;
                                stockInterruptedOrigin = null;
                                validatingStockProposal = false;
                                if (stockView != null) phase = 19;
                                else if (stockResumePhase >= 0) {
                                    phase = stockResumePhase;
                                    stockResumePhase = -1;
                                } else afterSolve();
                                return false;
                            }
                            if (retryWithTargetSeed()) return false;
                            // Reject this witness, not the remaining allocation
                            // and count searches. Keep an unresolved placeholder
                            // without closing their retained frontiers.
                            if (best == null) best = new GraphPlan<>(target, amount, preserve, new PlanStep.Sequence(List.of()),
                                    Map.of(), Map.of(), Map.of(), Map.of(), GraphPlan.Result.UNKNOWN, budget.nodes(), System.nanoTime() - started);
                            prepareAlternatives();
                            phase = 5;
                            return false;
                        }
                    }
                    finishObservation(PlanningBudget.CandidateOutcome.ACCEPTED);
                    verified = candidate;
                    if (validatingStockProposal) {
                        if (stockView != null) stockView.feedback(CandidateFeedback.ACCEPTED);
                        stockView = null;
                        stockInterruptedCandidate = null;
                        stockInterruptedOrigin = null;
                        validatingStockProposal = false;
                    }
                    if (optimizeSeeds(candidate)) return false;
                    result = candidate;
                    discardQuantityAnalysis();
                    budget.phase(PlanningBudget.Phase.COMPLETE);
                    phase = 8;
                }
                case 5 -> {
                    if (repairs != null) {
                        if (repairs.hasNext()) queueChoice(repairs.next());
                        else {
                            repairs = null;
                            scoutSources();
                            phase = stockWitness() ? 4 : 0;
                        }
                        return false;
                    }
                    if (!alternatives.hasNext()) {
                        clearAlternativeOrder();
                        scoutSources();
                        phase = stockWitness() ? 4 : 0;
                        return false;
                    }
                    K key = alternatives.next();
                    int next = choices.getOrDefault(key, 0) + 1;
                    long count = compiler.producers(key).stream().filter(recipe -> !excluded.contains(recipe.id())).count();
                    if (next < count) {
                        queueChoice(GraphSourceChoices.changed(choices, key, next), preferredAlternatives.contains(key));
                    }
                }
                case 6 -> {
                    // Allocation/count search retains its frontier, but must
                    // also return to untried source combinations. Otherwise an
                    // unlucky first batch can consume the entire order budget
                    // while a cheap executable source graph is still queued.
                    if (!pending.isEmpty() && budget.searchWork() - allocationTurnStarted >= allocationTurnAllowance) {
                        budget.note("allocation_handoff", "pending_sources=" + pending.size() + "; seen=" + seen.size());
                        phase = 0;
                        return false;
                    }
                    if (!allocating.step()) {
                        // Compile and reuse nested programs before solving global
                        // counts. A count witness may need enormous buffers where
                        // the existing hierarchical program only needs one seed.
                        if (!countAttempted && allocating.readyForCountSearch()) startCountSearch();
                        return false;
                    }
                    GraphPlan<K> allocated = allocating.result();
                    allocating = null;
                    if (allocated != null) {
                        budget.note("allocation", "witness_found");
                        candidate = allocated;
                        beginVerification(PlanningBudget.CandidateOrigin.ALLOCATION);
                        phase = 4;
                    } else {
                        budget.note("allocation", "no_witness; continuing alternatives");
                        if (!countAttempted) startCountSearch();
                        else phase = 0;
                    }
                }
                case 9 -> {
                    if (stockBlocked == null) {
                        if (!missingAnalysis.step()) return false;
                        stockBlocked = missingAnalysis.blocked();
                        missingAnalysis.close();
                        missingAnalysis = null;
                    }
                    if (!stockBlocked && !provenMissing(best)) {
                        budget.failureDetail("UNPROVEN_MISSING: witness_needs=" + best.missingExact().size() + "; alternatives may still be feasible");
                        result = failure(GraphPlan.Result.UNKNOWN);
                        return true;
                    }
                    // Prove the proposed missing-material preview really reaches
                    // the goal when funded. It remains non-executable until its
                    // exact deficits are supplied and a new request is planned.
                    verifyMissing();
                }
                case 10 -> {
                    if (!verifying.step()) return false;
                    if (optimizeSeeds(best)) return false;
                    result = best;
                    discardQuantityAnalysis();
                    phase = 8;
                }
                case 11 -> {
                    if (!missingAnalysis.step()) return false;
                    stockBlocked = missingAnalysis.blocked();
                    missingAnalysis.close();
                    missingAnalysis = null;
                    afterSolve();
                }
                case 12 -> {
                    if (!countAttempted && quantities.hasBinaryChoices()) {
                        // Bounded integer choices can be solved directly before
                        // paying for a relaxation that admits fractional choices.
                        countBeforeQuantity = true;
                        budget.note("quantity", "binary_choices; try_integer_preprocessing");
                        startCountSearch();
                        return false;
                    }
                    if (quantityResumePhase < 0 && quantities.readyForHeavyAnalysis()) {
                        quantityDeferred = true;
                        quickSearchStarted = budget.searchWork();
                        quickSearchAllowance = Math.min(16_384L, budget.remainingWork() / 8);
                        budget.note("quantity", "bounds_inconclusive; deferred_exact_analysis");
                        afterSolve();
                        return false;
                    }
                    if (!quantities.step()) return false;
                    quantityBlocked = quantities.blocked();
                    budget.note("quantity", "proven_blocked=" + quantityBlocked);
                    quantities = null;
                    if (quantityBlocked) {
                        beginMissingPreview();
                    } else if (quantityResumePhase >= 0) {
                        phase = quantityResumePhase;
                        quantityResumePhase = -1;
                    } else afterSolve();
                }
                case 13 -> {
                    GraphPlan<K> preview = null;
                    if (budget.searchWork() - previewStarted < previewAllowance) {
                        if (!allocating.step()) return false;
                        preview = allocating.result();
                    } else {
                        budget.note("missing_preview", "local_limit; infeasibility_proof_retained");
                        allocating.discard();
                    }
                    allocating = null;
                    if (preview != null && !preview.missing().isEmpty()) best = preview;
                    else if (candidate != null && !candidate.missing().isEmpty()) best = candidate;
                    if (best != null && !best.missing().isEmpty()) verifyMissing();
                    else result = withoutMissingPreview("no funded witness constructed");
                }
                case 14 -> {
                    if (!countSearch.step(slice)) return false;
                    GraphPlan<K> counted = countSearch.result();
                    boolean proved = countSearch.infeasible();
                    budget.note("integer_counts", "witness=" + (counted != null) + "; proven_infeasible=" + proved);
                    if (countSearch.paused()) {
                        parkedCounts = countSearch;
                        countPausedAt = budget.searchWork();
                    }
                    countSearch = null;
                    if (counted != null) {
                        if (allocating != null) allocating.discard();
                        allocating = null;
                        candidate = counted;
                        beginVerification(PlanningBudget.CandidateOrigin.INTEGER_COUNTS);
                        phase = 4;
                    } else if (proved) {
                        // This proof concerns every allowed source and the
                        // captured inventory. UI preview availability cannot
                        // send an already closed order back into source search.
                        quantityBlocked = true;
                        if (best != null && !best.missing().isEmpty()) candidate = best;
                        beginMissingPreview();
                    } else {
                        if (countResumePhase >= 0) {
                            phase = countResumePhase;
                            countResumePhase = -1;
                        } else if (countBeforeQuantity) {
                            countBeforeQuantity = false;
                            phase = 12;
                        } else phase = allocating == null ? 0 : 6;
                        if (!allocationAttempted) scoutAllocation();
                    }
                }
                case 15 -> startCountSearch();
                case 16 -> {
                    if (!explaining.step()) return false;
                    sourceCore = explaining.result();
                    explaining.close();
                    explaining = null;
                    afterSolve();
                }
                case 17 -> {
                    if (!seedOptimization.step()) return false;
                    result = seedOptimization.result();
                    seedOptimization.close();
                    seedOptimization = null;
                    if (result.feasible()) verified = result;
                    discardQuantityAnalysis();
                    budget.phase(PlanningBudget.Phase.COMPLETE);
                    phase = 8;
                }
                case 18 -> {
                    if (budget.searchWork() - allocationScoutStarted < allocationScoutAllowance) {
                        if (!allocating.step()) return false;
                        var allocated = allocating.result();
                        allocating = null;
                        if (allocated != null) {
                            budget.note("allocation_scout", "witness; work=" + (budget.searchWork() - allocationScoutStarted));
                            candidate = allocated;
                            beginVerification(PlanningBudget.CandidateOrigin.ALLOCATION);
                            phase = 4;
                            return false;
                        }
                    }
                    budget.note("allocation_scout", "handoff; work=" + (budget.searchWork() - allocationScoutStarted) + "; frontier_retained=" + (allocating != null));
                    phase = allocationResumePhase == 6 && allocating == null ? 0 : allocationResumePhase;
                }
                case 19 -> {
                    if (!stockView.step()) return false;
                    var proposal = stockView.result();
                    if (stockView.paused()) stockPausedAt = budget.searchWork();
                    else if (proposal == null) {
                        stockView.close();
                        stockView = null;
                    }
                    if (proposal != null) {
                        stockInterruptedCandidate = candidate;
                        stockInterruptedOrigin = candidateOrigin;
                        validatingStockProposal = true;
                        candidate = proposal;
                        beginVerification(stockView.supportProposal() ? PlanningBudget.CandidateOrigin.SUPPORT_NEIGHBORHOOD :
                                PlanningBudget.CandidateOrigin.STOCK_VIEW);
                        phase = 4;
                    } else if (stockResumePhase >= 0) {
                        phase = stockResumePhase;
                        stockResumePhase = -1;
                    } else afterSolve();
                }
                default -> {
                    return true;
                }
            }
        } catch (PlanningBudget.Exhausted limit) {
            if (limit.limit() == PlanningBudget.Limit.MEMORY_LIMIT && validatingStockProposal && stockView != null) {
                // Retaining optional search state must not crowd out the final
                // verifier. Rebuild that verifier after releasing the views;
                // never resume its partially mutated, memory-failed state.
                if (productionProof != null) productionProof.close();
                productionProof = null;
                if (verifying != null) verifying.close();
                retireStockFrontier();
                budget.failureDetail(failureBeforeStep);
                verifying = new PlanVerification<>(candidate, budget);
                phase = 4;
                return false;
            }
            finishObservation(PlanningBudget.CandidateOutcome.INCONCLUSIVE);
            result = limited(limit);
        } catch (ArithmeticException overflow) {
            finishObservation(PlanningBudget.CandidateOutcome.INCONCLUSIVE);
            budget.failureDetail("arithmetic: " + overflow + " at " + (overflow.getStackTrace().length == 0 ? "unknown" : overflow.getStackTrace()[0]));
            result = failure(GraphPlan.Result.AMOUNT_LIMIT);
        } catch (IllegalArgumentException invalid) {
            finishObservation(PlanningBudget.CandidateOutcome.INVALID);
            throw invalid;
        } finally {
            if (observation != null) observation.step(validationWork, validationNanos);
            if (validationOwner != null) validationOwner.validationWork(validationView, budget.threadSearchWork() - validationSearch);
        }
        return result != null;
    }

    private void beginVerification(PlanningBudget.CandidateOrigin origin) {
        candidateOrigin = origin;
        if (validationPending) throw new IllegalStateException("Previous candidate has no validation outcome");
        validationPending = true;
        // A recursively manufactured seed is not the requested final witness.
        candidateObservation = nesting == 0 ? budget.observeCandidate(origin) : null;
        budget.note("candidate", "proposed; origin=" + origin);
        long work = candidateObservation == null ? 0 : budget.threadWork();
        long nanos = candidateObservation == null ? 0 : System.nanoTime();
        try (var costs = budget.trace(nesting == 0 ? PlanningCostTrace.Origin.valueOf(origin.name()) : null,
                PlanningCostTrace.Stage.VERIFY)) {
            verifying = new PlanVerification<>(candidate, budget);
        } finally {
            if (candidateObservation != null) candidateObservation.step(work, nanos);
        }
    }

    private PlanningCostTrace.Origin costOrigin() {
        return switch (phase) {
            case 0, 1, 2, 5, 16 -> PlanningCostTrace.Origin.SOURCE_GRAPH;
            case 3 -> bootstrap == null ? PlanningCostTrace.Origin.SOURCE_GRAPH : PlanningCostTrace.Origin.BOOTSTRAP;
            case 4 -> PlanningCostTrace.Origin.valueOf(candidateOrigin.name());
            case 6, 13, 18 -> PlanningCostTrace.Origin.ALLOCATION;
            case 14 -> PlanningCostTrace.Origin.INTEGER_COUNTS;
            case 17 -> PlanningCostTrace.Origin.SEED_OPTIMIZATION;
            case 19 -> PlanningCostTrace.Origin.STOCK_VIEW;
            default -> PlanningCostTrace.Origin.COORDINATOR;
        };
    }

    private void retireStockFrontier() {
        stockView.close();
        stockView = null;
        budget.note("stock_view", "released_for_final_validation; memory_pressure");
    }

    private void finishObservation(PlanningBudget.CandidateOutcome outcome) {
        if (validationPending) budget.note("candidate", "outcome=" + outcome + "; origin=" + candidateOrigin);
        validationPending = false;
        if (candidateObservation != null) candidateObservation.finish(outcome);
        candidateObservation = null;
    }

    private void beginAllocationTurn() {
        allocationAfterSources = sourceAttempts + 8;
        allocationTurnStarted = budget.searchWork();
        allocationTurnAllowance = Math.min(1_048_576, budget.remainingWork() / 8);
        phase = 6;
    }

    private boolean compilationActive() {
        return phase == 1 && compiling != null || phase == 19 && stockView != null && stockView.compilationActive();
    }

    private long stockResumeWait() {
        int currentMissing = candidate == null || candidate.missingExact().isEmpty() ?
                Integer.MAX_VALUE : candidate.missingExact().size();
        // Residuals only guide scheduling. A promising ordinary route must
        // keep its turn too: unconditional aggressive tail resumption merely
        // moves starvation from stock views to the source frontier.
        int share = stockView.missingKeys() < currentMissing ? 8 : 2;
        return Math.min(1_048_576, Math.max(4096, budget.remainingWork() / share));
    }

    private void startCountSearch() {
        if (quantityDeferred) {
            if (bootstrapTooLarge) {
                // Exact counts can avoid the oversized speculative seed and
                // exploit a common order multiplier. Keep the deferred quantity
                // analysis for fallback instead of spending it before this try.
                quantityDeferred = false;
                quantityResumePhase = 0;
                countBeforeQuantity = true;
                budget.note("quantity", "bootstrap_range_limit; exact_counts_before_relaxation");
            } else {
                resumeQuantityAnalysis(15);
                return;
            }
        }
        countAttempted = true;
        // The count strategy can transfer its immutable model and explanations
        // without rebuilding a second closure before a cheap DP/Boolean hit.
        if (proofs == null) proofs = new OrderProofs<>(null, budget);
        if (allocating != null) allocating.proofs(proofs);
        budget.note("integer_counts", "start");
        try (var costs = budget.trace(nesting == 0 ? PlanningCostTrace.Origin.INTEGER_COUNTS : null, PlanningCostTrace.Stage.PREPARE)) {
            countSearch = new IntegerCountSearch<>(compiler, target, amount, stock, requiredSeeds, external,
                    excluded, preserve, forceCraft, budget, started, proofs);
        }
        phase = 14;
    }

    private void scoutAllocation() {
        // An unfinished count strategy must not repeatedly regain the budget
        // before inventory search has explored its own executable prefixes.
        // Keep both frontiers; all work remains charged to the same order.
        allocationAttempted = true;
        allocating = newAllocation(false).proofs(proofs);
        allocationResumePhase = phase;
        allocationScoutStarted = budget.searchWork();
        allocationScoutAllowance = Math.min(1_048_576, budget.remainingWork() / 8);
        budget.note("allocation_scout", "start; allowance=" + allocationScoutAllowance);
        phase = 18;
    }

    private void beginMissingPreview() {
        discardQuantityAnalysis();
        countBeforeQuantity = false;
        if (allocating != null) allocating.discard();
        previewStarted = budget.searchWork();
        previewAllowance = Math.min(262_144, budget.remainingWork() / 8);
        allocating = newAllocation(true);
        budget.note("missing_preview", "order_proven_infeasible; allowance=" + previewAllowance);
        phase = 13;
    }

    private GraphPlan<K> withoutMissingPreview(String detail) {
        budget.failureDetail("PROVEN_INFEASIBLE: captured inventory cannot fulfill order; MISSING_PREVIEW_UNAVAILABLE: " + detail);
        return failure(GraphPlan.Result.INFEASIBLE);
    }

    private AllocationSearch<K> newAllocation(boolean preview) {
        try (var costs = budget.trace(nesting == 0 ? PlanningCostTrace.Origin.ALLOCATION : null, PlanningCostTrace.Stage.PREPARE)) {
            return new AllocationSearch<>(compiler, target, amount, stock, external, requiredSeeds,
                    preserve, forceCraft, excluded, budget, started, preview);
        }
    }

    private void ensureProofs() {
        if (proofAttempted || proofs != null && proofs.model != null) return;
        proofAttempted = true;
        var model = RecipeCountModel.forProofs(compiler, target, amount, stock, requiredSeeds, external, excluded, forceCraft, budget);
        if (model != null) {
            if (proofs == null) proofs = new OrderProofs<>(model, budget);
            else proofs.adopt(model);
        }
    }

    private boolean optimizeSeeds(GraphPlan<K> plan) {
        if (seedAttempted || nesting != 0 || !preserve || catalystPolicy.parallelism() != 1 || plan.seeds().isEmpty()) return false;
        seedAttempted = true;
        seedOptimization = new SeedPortfolio<>(compiler, plan, stock, requiredSeeds, external, excluded, forceCraft, budget);
        phase = 17;
        return true;
    }

    private Map<K, Integer> assignment() {
        Map<K, Integer> result = new LinkedHashMap<>();
        graph.selected().keySet().forEach(key -> result.put(key, choices.getOrDefault(key, 0)));
        return result;
    }

    private void prepareAlternatives() {
        clearAlternativeOrder();
        if (sourceCore == null) {
            repairs = null;
            orderAlternatives();
            return;
        }
        // A solution must differ on at least one explained decision. Directly
        // revisit those levels, including lower indexed alternatives; jumping
        // only forward would silently lose valid source combinations.
        repairs = sourceCore.keySet().stream().flatMap(key -> java.util.stream.IntStream.range(0,
                (int) compiler.producers(key).stream().filter(r -> !excluded.contains(r.id())).count())
                .filter(i -> i != choices.getOrDefault(key, 0)).mapToObj(i -> {
                    budget.check();
                    return GraphSourceChoices.changed(choices, key, i);
                })).iterator();
    }

    private void orderAlternatives() {
        alternatives = graph.selected().keySet().iterator();
        if (candidate == null || candidate.missingExact().isEmpty()) return;
        long started = budget.threadWork();
        long allowance = Math.min(262_144, budget.remainingWork() / 16);
        long scratch = 0;
        try {
            Map<K, List<K>> consumers = new LinkedHashMap<>();
            var counts = candidate.patternTimesExact();
            for (var entry : graph.selected().entrySet()) {
                if (budget.threadWork() - started >= allowance) return;
                budget.check();
                var recipe = entry.getValue();
                if (!counts.containsKey(recipe.id())) continue;
                for (K input : recipe.inputs().keySet()) {
                    if (budget.threadWork() - started >= allowance) return;
                    budget.check();
                    if (!budget.tryReserve(128)) return;
                    scratch += 128;
                    consumers.computeIfAbsent(input, key -> new ArrayList<>()).add(entry.getKey());
                }
            }
            Set<K> visited = new HashSet<>();
            Deque<K> affected = new ArrayDeque<>();
            List<K> preferred = new ArrayList<>();
            for (K key : candidate.missingExact().keySet()) {
                if (budget.threadWork() - started >= allowance) return;
                budget.check();
                if (!budget.tryReserve(128)) return;
                scratch += 128;
                visited.add(key);
                affected.add(key);
            }
            while (!affected.isEmpty()) {
                if (budget.threadWork() - started >= allowance) return;
                budget.check();
                K key = affected.removeFirst();
                if (graph.selected().containsKey(key) && compiler.producers(key).stream()
                        .filter(recipe -> !excluded.contains(recipe.id())).limit(2).count() > 1)
                    preferred.add(key);
                for (K output : consumers.getOrDefault(key, List.of())) {
                    if (budget.threadWork() - started >= allowance) return;
                    budget.check();
                    if (visited.add(output)) {
                        if (!budget.tryReserve(128)) return;
                        scratch += 128;
                        affected.addLast(output);
                    }
                }
            }
            if (preferred.isEmpty()) return;
            long retained = 128L * graph.selected().size();
            // Ranking is optional. Retain the original traversal if its small
            // workspace or allowance cannot fit in the remaining order budget.
            if (!budget.tryReserve(retained)) return;
            alternativeMemory = retained;
            preferredAlternatives = Set.copyOf(preferred);
            // These choices go to the front of the frontier. Reverse insertion
            // keeps the nearest consumers of a deficit first, including repairs
            // to a previous repair when several source choices must change.
            Collections.reverse(preferred);
            Set<K> ordered = new LinkedHashSet<>(preferred);
            ordered.addAll(graph.selected().keySet());
            alternatives = ordered.iterator();
            // A shortage is a scheduling hint, not an infeasibility proof:
            // shared inventory and byproducts can require unrelated choices.
            budget.note("source_order", "shortage_related=" + preferred.size() + "; selected=" + graph.selected().size());
        } finally {
            budget.release(scratch);
        }
    }

    private void clearAlternativeOrder() {
        alternatives = null;
        preferredAlternatives = Set.of();
        budget.release(alternativeMemory);
        alternativeMemory = 0;
    }

    private void scoutSources() {
        if (sourceScoutTried || nesting != 0 || !excluded.isEmpty()) return;
        sourceScoutTried = true;
        // One-at-a-time repairs can require thousands of intermediate graphs
        // when several stock-backed sources need to change together. These
        // assignments only propose candidates: the original frontier remains,
        // and every proposal uses the same cycle solve and exact verification.
        var proposals = GraphSourceScout.choices(compiler, target, stock, external, requiredSeeds.keySet(), forceCraft, budget);
        for (var proposal : proposals) queueChoice(proposal, true);
        budget.note("source_scout", "grouped_assignments=" + proposals.size());
    }

    private boolean stockWitness() {
        if (stockWitnessTried || budget.searchWork() < stockWitnessAfter || !excluded.isEmpty() || graph == null || candidate == null || candidate.missingExact().isEmpty() ||
                graph.regions().stream().anyMatch(GraphCompiler.Region::cyclic))
            return false;
        stockWitnessTried = true;
        long allowance = Math.min(32768, budget.remainingWork() / 8);
        if (allowance < 8192) return false;
        GraphPlan<K> proposal;
        try (var costs = budget.trace(nesting == 0 ? PlanningCostTrace.Origin.STOCK_WITNESS : null, PlanningCostTrace.Stage.SEARCH)) {
            proposal = GraphFallback.witnessByCost(compiler, target, amount, stock, external, requiredSeeds,
                    preserve, forceCraft, budget, allowance);
        }
        budget.note("source_witness", "stock_cost; verified=" + (proposal != null) + "; original_frontier_retained");
        if (proposal == null) return false;
        candidate = proposal;
        beginVerification(PlanningBudget.CandidateOrigin.STOCK_WITNESS);
        return true;
    }

    private void queueChoice(Map<K, Integer> changed) {
        queueChoice(changed, false);
    }

    private void queueChoice(Map<K, Integer> changed, boolean preferred) {
        if (seen.contains(changed) || queued.contains(changed)) return;
        if (pending.size() >= 4096) {
            frontierTruncated = true;
            return;
        }
        var retained = GraphSourceChoices.retain(changed);
        long bytes = retained.retainedBytes();
        if (!budget.tryReserve(bytes)) {
            frontierTruncated = true;
            return;
        }
        frontierMemory += bytes;
        queued.add(retained);
        if (preferred) pending.addFirst(retained);
        else pending.addLast(retained);
    }

    private boolean retryWithTargetSeed() {
        if (triedTargetSeedConsumption || preserve || !forceCraft || external.contains(target) || graph == null ||
                graph.regions().stream().noneMatch(region -> region.cyclic() && region.recipes().size() > 1 &&
                        region.recipes().stream().anyMatch(recipe -> recipe.executionOutputs().containsKey(target))))
            return false;
        // Retain the original net-production attempt, including its bootstrap,
        // before trying a smaller productive cycle that consumes a target seed.
        // Otherwise a cheaper but unprovable gross-output candidate can hide a
        // perfectly executable net-output plan using exactly the same sources.
        triedTargetSeedConsumption = true;
        budget.note("region_solve", "retry_with_target_seed; same_sources");
        solving = new GraphSolve<>(graph, target, amount, stock, external, requiredSeeds, preserve, forceCraft, budget, started, catalystPolicy, stock)
                .program(compiler.demandProgram(graph, budget))
                .allowTargetSeedConsumption(true);
        phase = 2;
        return true;
    }

    private void afterSolve() {
        if (!candidate.feasible() && quantityBlocked == null && quantities == null) {
            quantities = new QuantityAnalysis<>(compiler, target, amount, stock, external, requiredSeeds, excluded, budget, forceCraft);
            phase = 12;
            return;
        }
        if (!candidate.feasible() && !candidate.missing().isEmpty()) {
            if (Boolean.TRUE.equals(quantityBlocked)) {
                best = candidate;
                verifyMissing();
                return;
            }
            if (stockBlocked == null) {
                // Check every allowed source before spending search on seed
                // bootstrapping. Failure in this optimistic model is a proof;
                // an arbitrary failed ordering or exhausted budget is not.
                missingAnalysis = new MissingStockAnalysis<>(compiler, target, stock, external,
                        requiredSeeds.keySet(), excluded, forceCraft, budget);
                phase = 11;
                return;
            }
            if (stockBlocked) {
                best = candidate;
                verifyMissing();
                return;
            }
            if (startStockView()) return;
            // Ordinary DAG propagation already accounts for every selected
            // input. Recursively crafting its missing leaves cannot improve
            // that source selection; compile the alternatives instead.
            if (graph.regions().stream().anyMatch(GraphCompiler.Region::cyclic)) bootstrap = new Bootstrap(candidate);
        }
        if (!candidate.feasible() && candidate.missing().isEmpty() && startStockView()) return;
        phase = 3;
    }

    private boolean cheapCountView() {
        if (cheapCountView != null) return cheapCountView;
        // Reuse the bound model's actual supported closure, rather than gate
        // on the whole catalog's size. If it cannot be built, demand views
        // keep their early turn; this is no infeasibility conclusion.
        long workspace = 524_288;
        if (!budget.tryReserve(workspace)) return cheapCountView = false;
        try {
            cheapCountView = compiler.countClosure(target, requiredSeeds.keySet(), excluded, 8192, 8192, budget) != null;
            return cheapCountView;
        } finally {
            budget.release(workspace);
        }
    }

    private boolean startStockView() {
        if (stockViewTried || stock.isEmpty() || Boolean.TRUE.equals(quantityBlocked) || Boolean.TRUE.equals(stockBlocked)) return false;
        if (Boolean.TRUE.equals(cheapCountView) && sourceAttempts == 1 && !stockViewDelayed) {
            for (K key : graph.selected().keySet()) {
                budget.check();
                int alternatives = 0;
                for (var source : compiler.producers(key)) {
                    budget.check();
                    if (!excluded.contains(source.id()) && ++alternatives >= 2) {
                        stockViewDelayed = true;
                        stockDelayStarted = budget.searchWork();
                        stockDelayAllowance = Math.max(1, Math.min(2_097_152,
                                Math.min(Math.max(32_768, Math.min(1_048_576, firstGraphWork) * 2), budget.remainingWork() / 8)));
                        budget.note("stock_view", "ordinary_source_headstart; allowance=" + stockDelayAllowance);
                        return false;
                    }
                }
            }
        }
        stockViewDelayed = false;
        stockViewTried = true;
        stockSpareMemory = Math.min(33_554_432, budget.availableBytes() / 4);
        try (var costs = budget.trace(nesting == 0 ? PlanningCostTrace.Origin.STOCK_VIEW : null, PlanningCostTrace.Stage.PREPARE)) {
            stockView = new GraphStockViewPortfolio<>(compiler, target, amount, stock, external, requiredSeeds,
                    excluded, preserve, forceCraft, catalystPolicy, budget, started);
        }
        phase = 19;
        return true;
    }

    private void resumeQuantityAnalysis(int resumePhase) {
        quantityDeferred = false;
        quantityResumePhase = resumePhase;
        budget.note("quantity", "resume_exact_analysis; quick_work=" + (budget.searchWork() - quickSearchStarted));
        phase = 12;
    }

    private void discardQuantityAnalysis() {
        if (quantities != null) quantities.discard();
        quantities = null;
        quantityDeferred = false;
    }

    private void verifyMissing() {
        verifying = new PlanVerification<>(new GraphPlan<>(best.target(), best.amount(), best.preserveSeeds(),
                best.steps(), best.recipes(), best.initialExact(), best.seeds(), Map.of(),
                GraphPlan.Result.FEASIBLE, best.searchNodes(), best.planningNanos()), budget);
        phase = 10;
    }

    @Override
    public CompletableFuture<?> waitingFor() {
        if (countSearch != null && countSearch.waitingFor() != null) return countSearch.waitingFor();
        if (compiling != null && compiling.waitingFor() != null) return compiling.waitingFor();
        if (bootstrap != null && bootstrap.seedWork != null) return bootstrap.seedWork.waitingFor();
        return null;
    }

    @Override
    public GraphPlan<K> result() {
        if (result == null) throw new IllegalStateException("Search is incomplete");
        return result;
    }

    @Override
    public GraphPlan<K> limited(PlanningBudget.Exhausted limit) {
        if (seedOptimization != null) {
            GraphPlan<K> retained = seedOptimization.result();
            seedOptimization.close();
            seedOptimization = null;
            discardQuantityAnalysis();
            return retained;
        }
        if (countSearch != null) {
            GraphPlan<K> counted = countSearch.result();
            if (counted != null) verified = counted;
            // A completed root proof can be harvested alongside a sibling that
            // exhausted the order budget. Retain that conclusion just as we
            // retain an already verified witness; optional recovery views never
            // enter this original-domain countSearch slot.
            if (countSearch.infeasible()) quantityBlocked = true;
            countSearch.close();
        }
        discardQuantityAnalysis();
        if (verified != null) return new GraphPlan<>(verified.target(), verified.amount(), verified.preserveSeeds(), verified.steps(),
                verified.recipes(), verified.initialExact(), verified.seeds(), Map.of(), GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL,
                budget.nodes(), System.nanoTime() - started);
        if (Boolean.TRUE.equals(quantityBlocked)) return withoutMissingPreview("preview stopped by " + limit.limit());
        return failure(GraphPlan.Result.valueOf(limit.limit().name()));
    }

    private GraphPlan<K> failure(GraphPlan.Result reason) {
        if (countSearch != null) countSearch.close();
        if (parkedCounts != null) parkedCounts.close();
        discardQuantityAnalysis();
        if (reason == GraphPlan.Result.UNKNOWN && frontierTruncated) {
            budget.failureDetail("candidate_frontier=4096; remaining strategies exhausted");
            reason = GraphPlan.Result.SEARCH_LIMIT;
        }
        budget.note("planning_result", reason + "; phase=" + phase + "; choices=" + seen.size() + "; pending=" + pending.size() +
                "; allocation_tried=" + allocationAttempted + "; counts_tried=" + countAttempted);
        return new GraphPlan<>(target, amount, preserve, new PlanStep.Sequence(List.of()), Map.of(), Map.of(), Map.of(), Map.of(),
                reason, budget.nodes(), System.nanoTime() - started);
    }

    @Override
    public void close() {
        try (var costs = budget.trace(nesting == 0 ? PlanningCostTrace.Origin.COORDINATOR : null, PlanningCostTrace.Stage.CLEANUP)) {
            closeMeasured();
        }
    }

    private void closeMeasured() {
        if (reuseVerification != null) reuseVerification.close();
        reuseVerification = null;
        if (registeredSearch.compareAndSet(true, false)) compiler.searchClosed();
        finishObservation(PlanningBudget.CandidateOutcome.ABANDONED);
        budget.release(graphMemory);
        graphMemory = 0;
        graph = null;
        pending.clear();
        queued.clear();
        seen.clear();
        budget.release(frontierMemory);
        frontierMemory = 0;
        if (compiling != null) compiling.close();
        compiling = null;
        if (solving != null) solving.close();
        solving = null;
        if (stockView != null) stockView.close();
        stockView = null;
        stockInterruptedCandidate = null;
        stockInterruptedOrigin = null;
        validatingStockProposal = false;
        if (missingAnalysis != null) missingAnalysis.close();
        missingAnalysis = null;
        if (productionProof != null) productionProof.close();
        productionProof = null;
        clearAlternativeOrder();
        if (countSearch != null) countSearch.close();
        if (parkedCounts != null) parkedCounts.close();
        if (allocating != null) allocating.discard();
        if (bootstrap != null) bootstrap.close();
        if (verifying != null) verifying.close();
        discardQuantityAnalysis();
        if (explaining != null) explaining.close();
        explaining = null;
        if (proofs != null) proofs.close();
        proofs = null;
        if (seedOptimization != null) seedOptimization.close();
        seedOptimization = null;
    }

    /** A failed bounded sequence search does not by itself prove missing stock. */
    private boolean provenMissing(GraphPlan<K> plan) {
        if (plan.missing().isEmpty() || graph == null) return false;
        for (K key : graph.selected().keySet())
            if (compiler.producers(key).stream().filter(recipe -> !excluded.contains(recipe.id())).count() > 1) return false;
        // Unique DAG and single-recipe regions have exact quantity propagation.
        if (graph.regions().stream().allMatch(region -> !region.cyclic() || region.recipes().size() == 1)) return true;
        for (var region : graph.regions()) {
            if (!region.cyclic()) continue;
            Set<K> internal = new HashSet<>();
            region.recipes().forEach(recipe -> internal.addAll(recipe.outputs().keySet()));
            boolean needed = internal.stream().anyMatch(plan.missing()::containsKey);
            boolean canStart = region.recipes().stream().anyMatch(recipe -> recipe.inputs().entrySet().stream()
                    .filter(input -> internal.contains(input.getKey()))
                    .allMatch(input -> external.contains(input.getKey()) || stock.getOrDefault(input.getKey(), 0L) >= input.getValue()));
            // Every recipe requires an unavailable internal input, and each such
            // input has only its selected producer: no internal first step exists.
            if (needed && !canStart) return true;
        }
        return false;
    }

    private final class Bootstrap implements AutoCloseable {

        private final GraphPlan<K> original;
        private final long searchStarted = budget.searchWork();
        private final Iterator<Map.Entry<K, Long>> deficits;
        private final List<PlanStep> prefix = new ArrayList<>();
        private final Map<String, GraphRecipe<K>> recipes;
        private final Map<K, Long> available = new LinkedHashMap<>(stock);
        private GraphPlanningWork<K> seedWork;
        private SummaryComputation<K> summary;
        private Iterator<K> updating;
        private GraphSolve<K> restarted;
        private PlanAssembly<K> assembly;
        private GraphPlan<K> result;

        private Bootstrap(GraphPlan<K> original) {
            this.original = original;
            deficits = original.missing().entrySet().iterator();
            recipes = new LinkedHashMap<>(original.recipes());
        }

        private boolean step(PlanningScheduler.Slice slice) {
            budget.check();
            // A provisional ordering can ask for a large amount of a returned
            // intermediate as if it were an external seed. Do not spend the
            // whole request manufacturing that speculative prefix before the
            // allocator can construct and verify a different execution order.
            if (budget.searchWork() - searchStarted > 32_768L + 128L * graph.recipes().size()) {
                result = original;
                return true;
            }
            if (nesting >= 64) {
                result = original;
                return true;
            }
            if (assembly != null) {
                if (assembly.step()) result = assembly.result();
                return result != null;
            }
            if (restarted != null) {
                if (!restarted.step()) return false;
                GraphPlan<K> tail = restarted.result();
                restarted.close();
                restarted = null;
                recipes.putAll(tail.recipes());
                prefix.add(tail.steps());
                assembly = new PlanAssembly<>(target, amount, preserve, new PlanStep.Sequence(prefix), recipes,
                        tail.seeds(), stock, external, graph, budget, started, catalystPolicy);
                return false;
            }
            if (summary != null) {
                if (updating == null) {
                    if (!summary.step()) return false;
                    updating = summary.result().delta().keySet().iterator();
                }
                if (updating.hasNext()) {
                    K key = updating.next();
                    BigInteger next = BigInteger.valueOf(available.getOrDefault(key, 0L)).add(summary.result().delta(key));
                    if (next.signum() < 0 || next.compareTo(ExactAmounts.LONG_MAX) > 0) {
                        // This speculative prefix cannot fit the bootstrap's
                        // inventory representation. It says nothing about an
                        // interleaved count witness or another source choice.
                        budget.note("bootstrap", "prefix_inventory_out_of_range; resource=" + key + "; exact=" + next + "; continuing_other_strategies");
                        bootstrapTooLarge = true;
                        result = original;
                        return true;
                    }
                    available.put(key, next.longValueExact());
                } else {
                    summary.close();
                    summary = null;
                    updating = null;
                }
                return false;
            }
            if (seedWork != null) {
                if (!(slice == null ? seedWork.step() : seedWork.advance(slice))) return false;
                GraphPlan<K> seed = seedWork.result();
                seedWork.close();
                seedWork = null;
                if (seed.feasible()) {
                    prefix.add(seed.steps());
                    recipes.putAll(seed.recipes());
                    seed.initial().forEach((key, count) -> {
                        if (external.contains(key)) available.merge(key, count, Math::max);
                    });
                    summary = new SummaryComputation<>(seed.steps(), seed.recipes(), budget);
                } else if (seed.result() == GraphPlan.Result.SEARCH_LIMIT || seed.result() == GraphPlan.Result.GRAPH_LIMIT) {
                    // A seed subproblem's local source frontier is not the
                    // order's budget. This check still propagates a genuinely
                    // exhausted cumulative budget before another strategy runs.
                    budget.check();
                    result = original;
                    return true;
                } else if (seed.result() == GraphPlan.Result.TIMEOUT || seed.result() == GraphPlan.Result.MEMORY_LIMIT) {
                    throw new PlanningBudget.Exhausted(PlanningBudget.Limit.valueOf(seed.result().name()));
                }
                return false;
            }
            if (!deficits.hasNext()) {
                if (prefix.isEmpty()) {
                    result = original;
                    return true;
                }
                // Bootstrapping material is newly manufactured, not new stock that
                // grants another allowance of extra catalysts during the tail solve.
                restarted = new GraphSolve<>(graph, target, amount, available, external, requiredSeeds, preserve, forceCraft, budget, started, catalystPolicy, stock)
                        .program(compiler.demandProgram(graph, budget))
                        .allowTargetSeedConsumption(triedTargetSeedConsumption);
                return false;
            }
            var deficit = deficits.next();
            if (original.missingExact().get(deficit.getKey()).compareTo(ExactAmounts.LONG_MAX) > 0) {
                budget.note("bootstrap", "seed_request_out_of_range; continuing_other_strategies");
                bootstrapTooLarge = true;
                result = original;
                return true;
            }
            var owner = graph.regions().stream().filter(region -> region.cyclic() &&
                    region.recipes().stream().anyMatch(recipe -> recipe.outputs().containsKey(deficit.getKey()))).findFirst().orElse(null);
            if (owner == null) return false;
            Set<String> banned = new HashSet<>(excluded);
            owner.recipes().forEach(recipe -> banned.add(recipe.id()));
            if (compiler.producers(deficit.getKey()).stream().noneMatch(recipe -> !banned.contains(recipe.id()))) return false;
            seedWork = new GraphPlanningWork<>(compiler, deficit.getKey(), deficit.getValue(), available, external, Map.of(), false, true, budget, banned, nesting + 1);
            return false;
        }

        @Override
        public void close() {
            if (seedWork != null) seedWork.close();
            seedWork = null;
            if (summary != null) summary.close();
            summary = null;
            if (restarted != null) restarted.close();
            restarted = null;
            if (assembly != null) assembly.close();
            assembly = null;
        }
    }
}
