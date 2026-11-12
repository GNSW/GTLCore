// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
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

    private GraphCompiler<K> compiler;
    private final GraphCompiler<K> searchOwner;
    private GraphMode graphMode;
    private GraphDomainCompilation<K> domainCompilation;
    private boolean domainPrepared;
    private boolean sharedCountsTried, sharedCountsShared;
    private int sharedCountsResume;
    private long sharedCountsMemory, sharedCountsModelBytes, sharedCountsStarted, sharedCountsAllowance;
    private GraphPlan<K> sharedCountsBase, sharedCountsProposal;
    private Iterator<GraphRecipe<K>> sharedCountsRecipes;
    private Iterator<K> sharedCountsPorts, sharedCountsInitial;
    private final Set<K> sharedCountsOutputs = new HashSet<>(), sharedCountsTerminals = new LinkedHashSet<>();
    private RefillTerminalSearch<K> sharedCounts;
    private RefillFrontier.Comparison<K> sharedCountsComparison;
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
    private GraphCompiler.Region<K> unresolvedRegion;
    private GraphStockViewPortfolio<K> stockView;
    private GraphOutputView<K> outputView;
    private boolean outputViewTried;
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
    private GraphPlan<K> productionVerified, productionExportVerified, alternativesVerified, alternativesExportVerified;
    private PlanVerification<K> finalVerification;
    private ForceCraftProof<K> finalProductionProof;
    private GraphPlan<K> finalProductionPlan;
    private boolean finalPrimaryDone;
    private int finalAlternativeIndex;
    private List<GraphPlan<K>> finalApprovedAlternatives;
    private Iterator<Map.Entry<K, Long>> finalMandatorySeeds;
    private boolean finalSeedsChecked;
    private AllocationSearch<K> allocating;
    private IntegerCountSearch<K> countSearch;
    private IntegerCountSearch<K> parkedCounts;
    private boolean packingCompletionAttempted;
    private CountPackingCore packingCompletionOwner;
    private long packingCompletionUntil;
    private long countPausedAt;
    private long stockCountWork, stockCountStarted;
    private boolean stockCountTurn;
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
    private MissingMaterialExpansion<K> missingExpansion;
    private boolean refillBoundaryPending, missingExpansionRequiresDeficits;
    private MissingMaterialExpansion<K> missingExpansionRetained;
    private RefillDeficitNormalization<K> missingDeficits, positiveRefillDeficits;
    private long refillDeficitMemory;
    private MissingMaterialExpansion<K> positiveRefill, positiveRefillRetained;
    private GraphPlan<K> positiveRefillVerified, refillChoice, refillCompared;
    private RefillFrontier.Comparison<K> refillComparison;
    private boolean positiveRefillTried;
    private long positiveRefillStarted, positiveRefillAllowance;
    private String positiveRefillFailure;
    private QuantityAnalysis<K> quantities;
    private boolean quantityDeferred;
    private boolean countBeforeQuantity;
    private long quickSearchStarted, quickSearchAllowance;
    private long pausedSearchWork;
    private boolean missingPreview = true;
    private int quantityResumePhase = -1;
    private Boolean quantityBlocked;
    private Boolean stockBlocked;
    private boolean allocationAttempted, countAttempted, frontierTruncated;
    private boolean bootstrapTooLarge;
    private boolean triedTargetSeedConsumption;
    private boolean sourceScoutTried, initialSourcesChecked;
    private Iterator<K> alternatives;
    private Set<K> preferredAlternatives = Set.of();
    private long alternativeMemory;
    private GraphPlan<K> candidate, best, verified, result;
    private RefillPreviewSelection<K> previewSelection;
    private int phase;
    private int sourceAttempts, allocationAfterSources = 8;
    private CountDemandSearch<K> countDemand;
    private CountCyclicStockSearch<K> cyclicStockCounts;
    private boolean countDemandTried, cyclicStockPending;
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
        if (graphMode == GraphMode.ACYCLIC) return this;
        if (reuseVerification != null) reuseVerification.close();
        reuseVerification = previous == null ? null : new PlanReuseVerification<>(previous, compiler, target, amount,
                stock, external, requiredSeeds, preserve, forceCraft, budget, started);
        return this;
    }

    public GraphPlanningWork<K> catalysts(CatalystPolicy policy) {
        if (phase != 0) throw new IllegalStateException("Planning already started");
        catalystPolicy = graphMode == GraphMode.ACYCLIC ? CatalystPolicy.MINIMAL : policy;
        return this;
    }

    /** A restricted view owner may discard refill previews but still needs local proofs. */
    GraphPlanningWork<K> missingPreview(boolean enabled) {
        if (startedWork) throw new IllegalStateException("Planning already started");
        missingPreview = enabled;
        return this;
    }

    /** Positive witness search for a proposed refill boundary; no recursive previews. */
    static <K> GraphPlanningWork<K> refillView(GraphCompiler<K> compiler, GraphPlan<K> original, Map<K, Long> stock,
            Set<K> supply, Set<String> excluded, boolean force, PlanningBudget budget) {
        var work = new GraphPlanningWork<>(compiler, original.target(), original.amount(), stock, supply, original.seeds(),
                original.preserveSeeds(), force, budget, excluded, 1).missingPreview(false);
        // Try the compiled supply graph first; if it remains cyclic, admit
        // executable allocation before enumerating many source combinations.
        work.allocationAfterSources = 1;
        return work;
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

    public GraphPlanningWork(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock, Set<K> external,
                             Map<K, Long> requiredSeeds, boolean preserve, boolean forceCraft, PlanningBudget budget, GraphMode mode) {
        this(requestedCompiler(compiler, mode), target, amount, stock, external, requiredSeeds, preserve, forceCraft, budget, Set.of(), 0);
        graphMode = mode;
        if (mode == GraphMode.ACYCLIC) catalystPolicy = CatalystPolicy.MINIMAL;
        domainPrepared = mode == compiler.mode();
    }

    private static <K> GraphCompiler<K> requestedCompiler(GraphCompiler<K> compiler, GraphMode mode) {
        java.util.Objects.requireNonNull(mode);
        if (mode == GraphMode.FULL && compiler.mode() != GraphMode.FULL)
            throw new IllegalArgumentException("A restricted compiler cannot recover the FULL search model");
        return compiler;
    }

    private GraphPlanningWork(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock, Set<K> external, Map<K, Long> requiredSeeds,
                              boolean preserve, boolean forceCraft, PlanningBudget budget, Set<String> excluded, int nesting) {
        if (amount <= 0) throw new IllegalArgumentException("Non-positive order");
        if (nesting > 64) throw new PlanningBudget.Exhausted(PlanningBudget.Limit.GRAPH_LIMIT);
        this.compiler = compiler;
        this.searchOwner = compiler;
        this.graphMode = compiler.mode();
        if (graphMode == GraphMode.ACYCLIC) catalystPolicy = CatalystPolicy.MINIMAL;
        this.domainPrepared = true;
        this.target = target;
        this.amount = amount;
        this.stock = PlanningInventory.snapshot(stock);
        this.requiredSeeds = PlanningInventory.snapshot(requiredSeeds);
        this.external = Set.copyOf(external);
        this.preserve = preserve;
        this.forceCraft = forceCraft;
        this.budget = budget;
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
            // The optional annotation owns its cancellation/time check and
            // preserves the completed refill's failure detail on timeout.
            if (!(phase == 21 && missingExpansion.diagnosing())) {
                if (compilationActive()) budget.compilationCheck();
                else budget.check();
            }
            // Prepare the recipe domain before any reuse, count model, allocation or refill path.
            if (!domainPrepared) {
                stepDomainPreparation();
                return false;
            }
            if (result != null) return finishResult();
            if (reuseVerification != null) {
                if (!stepReuseVerification()) return false;
                if (result != null) return true;
            }
            if (!initialSourcesChecked && phase == 0) {
                initialSourcesChecked = true;
                if (coupledTargetSources()) scoutSources();
            }
            updatePackingCompletion();
            // A cheap ordinary alternative gets a bounded turn, not ownership
            // of the remaining order budget. Keep its partial compile/solve
            // alive while stock views run, then resume the same state.
            if (!optionalQuantityTurn() && stockViewDelayed && frontierWork() - stockDelayStarted >= stockDelayAllowance &&
                    phase != 4 && phase != 8 && phase != 10 && phase != 17 && phase != 20 && phase != 22 &&
                    !(phase == 3 && candidate.feasible())) {
                int resume = phase;
                if (startStockView()) {
                    stockResumePhase = resume;
                    budget.note("stock_view", "ordinary_source_headstart_limit; work=" + (frontierWork() - stockDelayStarted));
                }
            }
            if (!optionalQuantityTurn() && stockView != null && stockView.paused()) {
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
                        // A restricted neighborhood must yield to admission of
                        // the complete source/count frontier at least once.
                        // With no queued sources the immediate-resume shortcut
                        // otherwise preempts this handoff on every single step.
                        !(phase == 0 && pending.isEmpty() && !countAttempted) &&
                        // At the final handoff both retained frontiers remain
                        // eligible. Run the older one rather than letting this
                        // earlier hook continually preempt integer search.
                        !(parkedCounts != null && (phase == 9 || phase == 0 && pending.isEmpty()) &&
                                stockPausedAt >= countPausedAt) &&
                        // A fixed wait larger than the remaining budget would
                        // permanently strand this continuation near the limit.
                        (frontierWork() - stockPausedAt >= stockResumeWait() || phase == 0 && pending.isEmpty() || phase == 9)) {
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
            // Stock-view repair is a retained frontier too. A long repair turn
            // must not prevent a complete-model continuation from receiving its
            // already-earned turn; it resumes the same view after handing back.
            if (!optionalQuantityTurn() && parkedCounts != null && (phase == 0 || phase == 5 || phase == 6 || phase == 9 ||
                    phase == 19 && stockCountCredit() >= 32_768 ||
                    phase == 12 && quantities != null && quantities.heavyAnalysisActive() &&
                            (parkedCounts.hasScaledProgress() || parkedCounts.hasRetainedViews())) &&
                    (frontierWork() - countPausedAt >= Math.min(
                            phase == 12 ? 131_072 : phase == 6 || phase == 19 || !pending.isEmpty() ? 1_048_576 : 32_768,
                            Math.max(4096, budget.remainingWork() / 8)) || phase == 0 && pending.isEmpty() || phase == 9)) {
                countResumePhase = phase;
                countSearch = parkedCounts;
                parkedCounts = null;
                if (phase == 19) {
                    // Extra visits earn at most a quarter of the active view's
                    // work. A broad count frontier can keep excluding dead
                    // multisets without an executable candidate; more service
                    // here displaces source/view progress and changes subsequent
                    // branch handoffs. Full-model proofs keep their bounded turn.
                    stockCountTurn = true;
                    stockCountStarted = budget.searchWork();
                    countSearch.considerExecutionScout();
                    countSearch.resumeBounded(stockCountCredit());
                } else countSearch.resume();
                phase = 14;
            }
            // Deferred analysis also counts work spent in stock views. Excluding
            // that phase let a small complete-model proof wait behind millions of
            // restricted repair steps. An active count turn, however, must finish
            // or yield before deferred quantity analysis can resume.
            if (quantityDeferred && phase != 4 && phase != 8 && phase != 10 && phase != 14 && phase != 18 && phase != 20 &&
                    !(phase == 3 && candidate.feasible()) &&
                    (phase == 9 || frontierWork() - quickSearchStarted >= quickSearchAllowance)) {
                if (!countAttempted && quantities.scoutCountsBeforeHeavyAnalysis() &&
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
                        allocating = newAllocation().proofs(proofs);
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
                    if (sourceAttempts == 0) firstGraphStarted = frontierWork();
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
                    solving = new GraphSolve<>(graph, target, amount, stock, external, requiredSeeds, preserve, forceCraft, budget, started, catalystPolicy, stock, graphMode)
                            .startupCatalog(compiler.mode() == GraphMode.FULL ? compiler.verificationCatalog() : compiler.catalog(), excluded)
                            .program(compiler.demandProgram(graph, budget));
                    budget.note("compile", "choice=" + seen.size() + "; recipes=" + graph.recipes().size() + "; regions=" + graph.regions().size() +
                            "; cyclic=" + graph.regions().stream().filter(GraphCompiler.Region::cyclic).count());
                    phase = 2;
                }
                case 2 -> {
                    if (!solving.step()) return false;
                    candidate = solving.result();
                    unresolvedRegion = solving.unresolvedRegion();
                    candidateOrigin = PlanningBudget.CandidateOrigin.SOURCE_GRAPH;
                    if (sourceAttempts == 1) firstGraphWork = frontierWork() - firstGraphStarted;
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
                    if (verifying.summary().delta(target).compareTo(BigInteger.valueOf(amount)) >= 0)
                        candidate.markNetProductionVerified();
                    verified = productionVerified = candidate;
                    if (validatingStockProposal) {
                        if (stockView != null) stockView.feedback(CandidateFeedback.ACCEPTED);
                        stockView = null;
                        stockInterruptedCandidate = null;
                        stockInterruptedOrigin = null;
                        validatingStockProposal = false;
                    }
                    if (startSharedCounts(candidate, 4)) return false;
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
                            phase = 0;
                        }
                        return false;
                    }
                    if (!alternatives.hasNext()) {
                        clearAlternativeOrder();
                        scoutSources();
                        phase = 0;
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
                    if (!pending.isEmpty() && allocating.searchWork() - allocationTurnStarted >= allocationTurnAllowance) {
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
                        beginVerifiedProposal(allocated, PlanningBudget.CandidateOrigin.ALLOCATION);
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
                    if (verifying.summary().delta(target).compareTo(BigInteger.valueOf(amount)) >= 0) {
                        best.markNetProductionVerified(); verified = productionVerified = best;
                        if (startSharedCounts(best, 10)) return false;
                    }
                    if (optimizeSeeds(best)) return false;
                    if (expandMissing(best)) return false;
                    result = missingPreview ? MissingMaterialExpansion.terminalResult(best) : best;
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
                        quickSearchStarted = frontierWork();
                        quickSearchAllowance = quantities.quickSearchAllowance();
                        budget.note("quantity", "bounds_inconclusive; deferred_exact_analysis");
                        afterSolve();
                        return false;
                    }
                    boolean done;
                    boolean optional = quantities.sparsePotential();
                    long before = optional ? budget.threadSearchWork() : 0;
                    try { done = quantities.step(); }
                    finally { if (optional) suspendFrontierClocks(budget.threadSearchWork() - before); }
                    if (!done) return false;
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
                case 14 -> {
                    if (!countSearch.step(slice)) return false;
                    if (countSearch.paused() && budget.nodes() < packingCompletionUntil &&
                            countSearch.activePackingCompletion() == packingCompletionOwner) {
                        countSearch.resumeBounded(packingCompletionUntil - budget.nodes());
                        return false;
                    }
                    if (stockCountTurn) {
                        stockCountWork += budget.searchWork() - stockCountStarted;
                        stockCountTurn = false;
                    }
                    GraphPlan<K> counted = countSearch.result();
                    boolean proved = countSearch.infeasible();
                    budget.note("integer_counts", "witness=" + (counted != null) + "; proven_infeasible=" + proved);
                    if (countSearch.paused()) {
                        parkedCounts = countSearch;
                        countPausedAt = frontierWork();
                    }
                    countSearch = null;
                    if (counted != null) {
                        if (allocating != null) allocating.discard();
                        allocating = null;
                        beginVerifiedProposal(counted, PlanningBudget.CandidateOrigin.INTEGER_COUNTS);
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
                case 17 -> { if (!stepSeedOptimization()) return false; }
                case 18 -> {
                    if (frontierWork() - allocationScoutStarted < allocationScoutAllowance) {
                        if (!allocating.step()) return false;
                        var allocated = allocating.result();
                        allocating = null;
                        if (allocated != null) {
                            budget.note("allocation_scout", "witness; work=" + (frontierWork() - allocationScoutStarted));
                            beginVerifiedProposal(allocated, PlanningBudget.CandidateOrigin.ALLOCATION);
                            phase = 4;
                            return false;
                        }
                    }
                    budget.note("allocation_scout", "handoff; work=" + (frontierWork() - allocationScoutStarted) + "; frontier_retained=" + (allocating != null));
                    phase = allocationResumePhase == 6 && allocating == null ? 0 : allocationResumePhase;
                }
                case 19 -> { if (!stepStockView()) return false; }
                case 20 -> { if (!stepOutputView()) return false; }
                case 21 -> { if (!stepMissingExpansion()) return false; }
                case 22 -> selectMissingPreview();
                case 23 -> stepCountDemand();
                case 24 -> stepCyclicStockCounts();
                case 26 -> stepSharedCounts();
                case 27 -> stepPositiveRefill();
                default -> {
                    return true;
                }
            }
            if (result != null) return finishResult();
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

    private boolean stepReuseVerification() {
        if (!reuseVerification.step()) return false;
        GraphPlan<K> reused = reuseVerification.result();
        reuseVerification.close();
        reuseVerification = null;
        if (reused != null) {
            result = verified = productionVerified = reused;
            budget.phase(PlanningBudget.Phase.COMPLETE);
            phase = 8;
        }
        return true;
    }

    private void stepDomainPreparation() {
        if (domainCompilation == null) domainCompilation = compiler.beginDomain(target, requiredSeeds.keySet(), graphMode, budget);
        if (!domainCompilation.step()) return;
        compiler = domainCompilation.result();
        // Its retained domain/index reservation lives until this solve closes.
        domainPrepared = true;
    }

    private boolean stepSeedOptimization() {
        if (!seedOptimization.step()) return false;
        result = seedOptimization.result();
        seedOptimization.close();
        seedOptimization = null;
        if (expandMissing(result)) { result = null; return false; }
        if (missingPreview) result = MissingMaterialExpansion.terminalResult(result);
        if (result.feasible()) verified = result;
        discardQuantityAnalysis();
        budget.phase(PlanningBudget.Phase.COMPLETE);
        phase = 8;
        return true;
    }

    private boolean stepStockView() {
        if (!stockView.step()) return false;
        var proposal = stockView.result();
        if (stockView.paused()) stockPausedAt = frontierWork();
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
        return true;
    }

    private boolean stepOutputView() {
        if (!outputView.step()) return false;
        var proposal = outputView.result();
        outputView.close();
        outputView = null;
        if (proposal != null) {
            beginVerifiedProposal(proposal, PlanningBudget.CandidateOrigin.SOURCE_GRAPH);
            phase = 4;
        } else afterSolve();
        return true;
    }

    /** The goal builder differs only by refusing stored target stock. No
     * rebasing is needed when that stock is absent or the complete frontier
     * has no target startup requirement. Archives are checked by the resumable
     * normalizer rather than scanned at a publication limit. */
    private boolean targetStockRebaseRequired(GraphPlan<K> plan) {
        return plan != null && forceCraft && !external.contains(target) && PlanningInventory.exactAmount(stock, target).signum() > 0 &&
                (plan.initialExact().getOrDefault(target, BigInteger.ZERO).signum() > 0 || !plan.alternatives().isEmpty());
    }

    private boolean stepMissingExpansion() {
        if (missingDeficits == null) {
            if (!missingExpansion.step()) return false;
            var completed = missingExpansion.result();
            if (missingExpansionRequiresDeficits && targetStockRebaseRequired(completed))
                missingDeficits = new RefillDeficitNormalization<>(completed, stock, external, false, budget);
            else result = completed;
        }
        if (missingDeficits != null) {
            if (!missingDeficits.step()) return false;
            result = missingDeficits.result();
            refillDeficitMemory += missingDeficits.takeResultCharge();
            missingDeficits.close(); missingDeficits = null;
            // Keep the imported physical word's builder charged until close.
            missingExpansionRetained = missingExpansion;
        }
        if (result != null) refillBoundaryPending = false;
        if (missingExpansion.productionVerifiedResult(result)) productionVerified = result;
        if (missingExpansion != missingExpansionRetained) missingExpansion.close();
        missingExpansion = null;
        discardQuantityAnalysis();
        phase = 8;
        return true;
    }

    /** Shared coproducts need joint counts; the graph remains the ordinary DAG. */
    private boolean startSharedCounts(GraphPlan<K> plan, int resume) {
        if (sharedCountsTried || graph == null || graph.recipes().size() < 2 || graph.recipes().size() > 8192 ||
                budget.remainingWork() < 98_304 || !productionApproved(plan) ||
                graph.regions().stream().anyMatch(GraphCompiler.Region::cyclic)) return false;
        sharedCountsTried = true;
        sharedCountsResume = resume; sharedCountsBase = plan;
        sharedCountsRecipes = graph.recipes().values().iterator();
        sharedCountsModelBytes = 4096L + 256L * graph.recipes().size();
        sharedCountsStarted = budget.threadSearchWork();
        sharedCountsAllowance = Math.min(8192, budget.remainingWork() / 8);
        phase = 26;
        return true;
    }

    private void stepSharedCounts() {
        if (sharedCountsComparison != null) {
            if (!sharedCountsComparison.step()) return;
            boolean accept = sharedCountsComparison.leftDominates();
            var proposal = sharedCountsProposal;
            finishSharedCounts();
            if (accept) {
                if (verifying != null) verifying.close(); verifying = null;
                if (sharedCountsResume == 4) beginVerifiedProposal(proposal, PlanningBudget.CandidateOrigin.INTEGER_COUNTS);
                else { best = proposal; verified = productionVerified = proposal; verifyMissing(); }
                budget.note("shared_counts", "componentwise_improvement");
            }
            return;
        }
        if (sharedCounts != null) {
            if (!sharedCounts.step()) return;
            sharedCountsProposal = sharedCounts.result(); sharedCounts.close(); sharedCounts = null;
            if (sharedCountsProposal == null) { finishSharedCounts(); return; }
            sharedCountsComparison = new RefillFrontier.Comparison<>(sharedCountsProposal, sharedCountsBase, budget);
            return;
        }
        // This bounded discovery only reads selected DAG outputs and terminal
        // inputs. It does not inspect original cycles or infer startup reserves.
        for (int operation = 0; operation < 32; operation++) {
            if (budget.threadSearchWork() - sharedCountsStarted >= sharedCountsAllowance) { finishSharedCounts(); return; }
            budget.check();
            if (sharedCountsInitial == null) {
                if (sharedCountsPorts != null && sharedCountsPorts.hasNext()) {
                    K key = sharedCountsPorts.next();
                    sharedCountsModelBytes += 128;
                    if (sharedCountsOutputs.contains(key)) sharedCountsShared = true;
                    else {
                        if (!budget.tryReserve(96)) { finishSharedCounts(); return; }
                        sharedCountsMemory += 96; sharedCountsOutputs.add(key);
                    }
                } else if (sharedCountsRecipes.hasNext()) sharedCountsPorts = sharedCountsRecipes.next().executionOutputs().keySet().iterator();
                else {
                    if (!sharedCountsShared) { finishSharedCounts(); return; }
                    sharedCountsInitial = sharedCountsBase.initialExact().keySet().iterator();
                    sharedCountsOutputs.clear(); budget.release(sharedCountsMemory); sharedCountsMemory = 0;
                }
            } else if (sharedCountsInitial.hasNext()) {
                K key = sharedCountsInitial.next();
                if (compiler.producers(key).isEmpty() && !external.contains(key)) {
                    if (!budget.tryReserve(96)) { finishSharedCounts(); return; }
                    sharedCountsMemory += 96; sharedCountsTerminals.add(key);
                }
            } else {
                if (sharedCountsTerminals.isEmpty()) { finishSharedCounts(); return; }
                // Joint allocation owns only the selected ordinary recipes.
                // Full-catalog cycle alternatives remain in the normal solver.
                if (!budget.tryReserve(sharedCountsModelBytes)) { finishSharedCounts(); return; }
                sharedCountsMemory += sharedCountsModelBytes;
                var selected = new GraphCompiler<>(List.copyOf(graph.recipes().values()), compiler.mode(), compiler.verificationCatalog());
                sharedCounts = new RefillTerminalSearch<>(selected, sharedCountsBase, stock, external,
                        Set.copyOf(sharedCountsTerminals), excluded, forceCraft, budget,
                        Math.min(65_536, budget.remainingWork() / 3)).requestedSeeds(requiredSeeds);
                return;
            }
        }
    }

    private void finishSharedCounts() {
        if (sharedCounts != null) sharedCounts.close(); sharedCounts = null;
        sharedCountsComparison = null; sharedCountsProposal = null; sharedCountsBase = null;
        sharedCountsRecipes = null; sharedCountsPorts = sharedCountsInitial = null;
        sharedCountsOutputs.clear(); sharedCountsTerminals.clear();
        budget.release(sharedCountsMemory); sharedCountsMemory = 0;
        phase = sharedCountsResume;
    }

    private boolean productionApproved(GraphPlan<K> plan) {
        return plan == productionVerified || plan == productionExportVerified;
    }

    private boolean alternativesApproved(GraphPlan<K> plan) {
        return plan == alternativesVerified || plan == alternativesExportVerified;
    }

    private boolean conditionalRefillMayReplace(GraphPlan<K> plan) {
        return switch (plan.result()) {
            case UNKNOWN, INCOMPLETE_MODEL, TIMEOUT, SEARCH_LIMIT, MEMORY_LIMIT, GRAPH_LIMIT, QUEUE_LIMIT -> true;
            default -> false;
        };
    }

    private boolean refillBoundaryReady(GraphPlan<K> plan) {
        return !refillBoundaryPending || plan.feasible() || plan == positiveRefillVerified;
    }

    private void promotePositiveRefill() {
        if (!result.feasible() && result.missingExact().isEmpty() && conditionalRefillMayReplace(result)) {
            if (verified != null && productionApproved(verified) && refillBoundaryReady(verified)) result = verified;
            else if (positiveRefillVerified != null) result = productionVerified = positiveRefillVerified;
        }
    }

    private boolean needsProductionProof(GraphPlan<K> plan) {
        return forceCraft && !external.contains(target) && (plan.feasible() || !plan.missingExact().isEmpty());
    }

    /** Delivery funded by supplied target stock is not a manufacturing witness.
     * Optional material choices carry the same full request obligations. */
    private boolean finishResult() {
        if (!verifyFinalProduction()) return false;
        if (result.feasible() || refillChoice == null || result.missingExact().isEmpty() || result == refillChoice) return true;
        if (refillComparison == null || refillCompared != result) {
            refillCompared = result;
            refillComparison = new RefillFrontier.Comparison<>(result, refillChoice, budget);
        }
        if (!refillComparison.step()) return false;
        if (refillComparison.leftDominates()) refillChoice = result;
        refillComparison = null;
        result = productionVerified = refillChoice;
        return verifyFinalProduction();
    }

    private boolean verifyFinalProduction() {
        promotePositiveRefill();
        if (!needsProductionProof(result)) return true;
        if (finalProductionPlan == null) {
            if (productionApproved(result) && (result.alternatives().isEmpty() || alternativesApproved(result))) return true;
            finalProductionPlan = result;
            finalPrimaryDone = productionApproved(result);
            finalAlternativeIndex = 0;
            finalSeedsChecked = false; finalMandatorySeeds = null;
            finalApprovedAlternatives = new ArrayList<>();
            if (finalPrimaryDone) verified = productionVerified = result.withAlternatives(List.of());
        }
        if (finalPrimaryDone && finalAlternativeIndex == finalProductionPlan.alternatives().size()) {
            result = verified.withAlternatives(finalApprovedAlternatives);
            verified = productionVerified = alternativesVerified = result;
            finalProductionPlan = null; finalApprovedAlternatives = null;
            return true;
        }
        var subject = finalPrimaryDone ? finalProductionPlan.alternatives().get(finalAlternativeIndex) : finalProductionPlan;
        if (!subject.target().equals(target) || subject.amount() != amount || subject.preserveSeeds() != preserve ||
                !needsProductionProof(subject)) return rejectFinalSubject("request_scope");
        if (!finalSeedsChecked) {
            if (finalMandatorySeeds == null) finalMandatorySeeds = requiredSeeds.entrySet().iterator();
            for (int inspected = 0; inspected < 32 && finalMandatorySeeds.hasNext(); inspected++) {
                budget.check();
                var seed = finalMandatorySeeds.next();
                if (subject.seeds().getOrDefault(seed.getKey(), 0L) < seed.getValue())
                    return rejectFinalSubject("mandatory_seed");
            }
            if (finalMandatorySeeds.hasNext()) return false;
            finalMandatorySeeds = null; finalSeedsChecked = true;
        }
        if (finalVerification == null) {
            var funded = new GraphPlan<>(subject.target(), subject.amount(), subject.preserveSeeds(), subject.steps(),
                    subject.recipes(), subject.initialExact(), subject.seeds(), Map.of(), GraphPlan.Result.FEASIBLE,
                    subject.searchNodes(), subject.planningNanos());
            finalVerification = new PlanVerification<>(funded, budget);
        }
        try {
            if (!finalVerification.step()) return false;
        } catch (IllegalArgumentException invalid) {
            if (!finalPrimaryDone) throw invalid;
            finalVerification.close(); finalVerification = null;
            return rejectFinalSubject("physical_verification");
        }
        if (finalProductionProof == null) finalProductionProof = new ForceCraftProof<>(subject, finalVerification, requiredSeeds, budget);
        if (!finalProductionProof.step()) return false;
        boolean productive = finalProductionProof.proved();
        var outcome = finalProductionProof.outcome();
        if (productive && finalVerification.summary().delta(target).compareTo(BigInteger.valueOf(amount)) >= 0)
            subject.markNetProductionVerified();
        finalProductionProof.close(); finalProductionProof = null;
        finalVerification.close(); finalVerification = null;
        if (!finalPrimaryDone) {
            if (!productive) {
                budget.note("force_craft", "final_refill_rejected; reason=" + outcome);
                result = failure(graphMode == GraphMode.ACYCLIC ? GraphPlan.Result.INCOMPLETE_MODEL : GraphPlan.Result.UNKNOWN);
                finalProductionPlan = null; finalApprovedAlternatives = null;
                promotePositiveRefill();
                return true;
            }
            // The main manufacturing word is already safe even when optional
            // alternative validation reaches the remaining budget boundary.
            verified = productionVerified = subject.withAlternatives(List.of());
            finalPrimaryDone = true;
            finalSeedsChecked = false;
        } else {
            if (productive) finalApprovedAlternatives.add(subject.withAlternatives(List.of()));
            else budget.note("force_craft", "alternative_rejected; reason=" + outcome);
            finalAlternativeIndex++;
            finalSeedsChecked = false;
        }
        return false;
    }

    private boolean rejectFinalSubject(String reason) {
        budget.note("force_craft", (finalPrimaryDone ? "alternative_rejected; reason=" : "final_refill_rejected; reason=") + reason);
        finalMandatorySeeds = null; finalSeedsChecked = false;
        if (finalPrimaryDone) { finalAlternativeIndex++; return false; }
        result = failure(graphMode == GraphMode.ACYCLIC ? GraphPlan.Result.INCOMPLETE_MODEL : GraphPlan.Result.UNKNOWN);
        finalProductionPlan = null; finalApprovedAlternatives = null;
        promotePositiveRefill();
        return true;
    }

    // Keep these cold preview phases out of the coordinator's hot method. Its
    // bytecode must stay small enough for ordinary JVM compilation policies.
    private void selectMissingPreview() {
        if (previewSelection != null) {
            if (!previewSelection.step()) return;
            best = previewSelection.result();
            previewSelection = null;
        }
        // Comparison never makes a preview executable. The chosen word still
        // passes funded verification and base-material expansion before publication.
        verifyMissing();
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

    /** These internal producers already checked the original request's funded
     * word, seed obligations and forced production. Retain their incumbent
     * before releasing the producer's slot: a deadline during the coordinator's
     * repeated verification cannot erase it. Ordinary source/stock-view
     * proposals still require that verification before becoming incumbents. */
    private void beginVerifiedProposal(GraphPlan<K> proposal, PlanningBudget.CandidateOrigin origin) {
        candidate = proposal;
        if (proposal.feasible() && proposal.missingExact().isEmpty()) verified = productionVerified = proposal;
        beginVerification(origin);
    }

    private PlanningCostTrace.Origin costOrigin() {
        return switch (phase) {
            case 0, 1, 2, 5, 16 -> PlanningCostTrace.Origin.SOURCE_GRAPH;
            case 3 -> bootstrap == null ? PlanningCostTrace.Origin.SOURCE_GRAPH : PlanningCostTrace.Origin.BOOTSTRAP;
            case 4 -> PlanningCostTrace.Origin.valueOf(candidateOrigin.name());
            case 6, 13, 18 -> PlanningCostTrace.Origin.ALLOCATION;
            case 14, 23 -> PlanningCostTrace.Origin.INTEGER_COUNTS;
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
        // Stock views and proof/count strategies may interrupt this turn. They
        // consume the shared request budget, not the paused allocator's quota.
        // Otherwise a long interruption repeatedly hands back an unadvanced
        // frontier before it can admit the complete count model.
        allocationTurnStarted = allocating.searchWork();
        allocationTurnAllowance = Math.min(1_048_576, budget.remainingWork() / 8);
        phase = 6;
    }

    /** Keep this rare handoff out of the coordinator's hot bytecode body. */
    private void updatePackingCompletion() {
        if (packingCompletionOwner != null && (budget.nodes() >= packingCompletionUntil ||
                packingCompletionOwner.expandedCompletionWork() == 0 ||
                (countSearch == null || !countSearch.ownsPackingCompletion(packingCompletionOwner)) &&
                (parkedCounts == null || !parkedCounts.ownsPackingCompletion(packingCompletionOwner)))) {
            if (countSearch != null) countSearch.clearPackingCompletion();
            if (parkedCounts != null) parkedCounts.clearPackingCompletion();
            packingCompletionOwner = null;
            packingCompletionUntil = 0;
        }
        if (!packingCompletionAttempted && budget.nodes() >= 16_777_216 &&
                (phase == 14 || parkedCounts != null && (phase == 0 || phase == 5 || phase == 6 || phase == 9 || phase == 19))) {
            var retained = phase == 14 ? countSearch : parkedCounts;
            var owner = retained.activePackingCompletion();
            if (owner != null) {
                packingCompletionAttempted = true;
                long remaining = budget.remainingWork();
                long grant = Math.min(owner.expandedCompletionWork(), Math.min(8_388_608, remaining - remaining / 8));
                long until = budget.nodes() + grant;
                if (grant > 0 && retained.preferPackingCompletion(owner, until)) {
                    packingCompletionOwner = owner;
                    packingCompletionUntil = until;
                    if (stockCountTurn) {
                        stockCountWork += budget.searchWork() - stockCountStarted;
                        stockCountTurn = false;
                    }
                    budget.note("packing_outer_completion", "grant=" + grant + "; until=" + packingCompletionUntil);
                    if (phase != 14) {
                        countResumePhase = phase;
                        countSearch = parkedCounts;
                        parkedCounts = null;
                        countSearch.resumeBounded(grant);
                        phase = 14;
                    }
                }
            }
        }
    }

    private boolean compilationActive() {
        return !domainPrepared || phase == 1 && compiling != null || phase == 19 && stockView != null && stockView.compilationActive() ||
                phase == 20 && outputView != null && outputView.compilationActive();
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
            countSearch = stockView == null ? null : stockView.takeFullSearch(compiler, target, amount, stock, requiredSeeds,
                    external, excluded, preserve, forceCraft, budget, started, proofs);
            if (countSearch == null) countSearch = new IntegerCountSearch<>(compiler, target, amount, stock, requiredSeeds,
                    external, excluded, preserve, forceCraft, budget, started, proofs);
        }
        phase = 14;
    }

    private void scoutAllocation() {
        // An unfinished count strategy must not repeatedly regain the budget
        // before inventory search has explored its own executable prefixes.
        // Keep both frontiers; all work remains charged to the same order.
        allocationAttempted = true;
        allocating = newAllocation().proofs(proofs);
        allocationResumePhase = phase;
        allocationScoutStarted = frontierWork();
        allocationScoutAllowance = Math.min(1_048_576, budget.remainingWork() / 8);
        budget.note("allocation_scout", "start; allowance=" + allocationScoutAllowance);
        phase = 18;
    }

    private void beginMissingPreview() {
        discardQuantityAnalysis();
        countBeforeQuantity = false;
        if (allocating != null) allocating.discard();
        allocating = null;
        if (!missingPreview) {
            budget.note("missing_preview", "omitted_by_view_owner; local_proof_retained");
            // The owner interprets this local result. Do not publish a global
            // failure detail from a restricted coordinate/coverage view.
            result = failure(GraphPlan.Result.INFEASIBLE);
            return;
        }
        if (candidate != null && !candidate.missingExact().isEmpty()) {
            if (best != null && best != candidate && !best.missingExact().isEmpty())
                previewSelection = new RefillPreviewSelection<>(best, candidate, budget);
            else best = candidate;
        }
        if (best != null && !best.missingExact().isEmpty()) {
            budget.note("missing_preview", "order_proven_infeasible; reuse_solver_witness; backward_requirements");
            phase = 22;
        } else {
            refillBoundaryPending = true;
            missingExpansionRequiresDeficits = true;
            missingExpansion = MissingMaterialExpansion.fromRequest(compiler, target, amount, stock, external,
                    requiredSeeds, excluded, preserve, forceCraft, budget).refineCycles(nesting == 0 && graphMode == GraphMode.FULL).refineSources(nesting == 0);
            budget.note("missing_preview", "order_proven_infeasible; from_goals; backward_requirements");
            phase = 21;
        }
    }

    private GraphPlan<K> withoutMissingPreview(String detail) {
        budget.failureDetail("PROVEN_INFEASIBLE: captured inventory cannot fulfill order; MISSING_PREVIEW_UNAVAILABLE: " + detail);
        return failure(GraphPlan.Result.INFEASIBLE);
    }

    private AllocationSearch<K> newAllocation() {
        try (var costs = budget.trace(nesting == 0 ? PlanningCostTrace.Origin.ALLOCATION : null, PlanningCostTrace.Stage.PREPARE)) {
            return new AllocationSearch<>(compiler, target, amount, stock, external, requiredSeeds,
                    preserve, forceCraft, excluded, budget, started);
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
        if (graphMode != GraphMode.FULL || seedAttempted || nesting != 0 || !preserve || plan.seeds().isEmpty()) return false;
        if (catalystPolicy.parallelism() != 1) {
            if (plan.feasible()) return false;
            boolean unfundedParallelSeed = false;
            for (var entry : plan.seeds().entrySet()) {
                budget.check();
                if (entry.getValue() > requiredSeeds.getOrDefault(entry.getKey(), 0L) &&
                        plan.missingExact().containsKey(entry.getKey())) { unfundedParallelSeed = true; break; }
            }
            if (!unfundedParallelSeed) return false;
        }
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
        if (candidate == null) return;
        boolean unresolved = candidate.missingExact().isEmpty() && unresolvedRegion != null;
        if (candidate.missingExact().isEmpty() && !unresolved) return;
        long started = budget.threadWork();
        long allowance = Math.min(262_144, budget.remainingWork() / 16);
        long scratch = 0;
        try {
            Map<K, List<K>> consumers = new LinkedHashMap<>();
            var counts = unresolved ? null : candidate.patternTimesExact();
            for (var entry : graph.selected().entrySet()) {
                if (budget.threadWork() - started >= allowance) return;
                budget.check();
                var recipe = entry.getValue();
                if (counts != null && !counts.containsKey(recipe.id())) continue;
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
            // An unknown local solve has no missing bill. Its blocked SCC is
            // still a useful repair hint: first change its selected producers
            // or the consumers that impose its demand. No source is excluded,
            // and this local failure is never installed as a negative proof.
            if (unresolved) for (var recipe : unresolvedRegion.recipes()) for (K key : recipe.outputs().keySet()) {
                if (budget.threadWork() - started >= allowance) return;
                budget.check();
                if (visited.add(key)) {
                    if (!budget.tryReserve(128)) return;
                    scratch += 128;
                    affected.addLast(key);
                }
            }
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
            budget.note("source_order", (unresolved ? "unresolved_region_related=" : "shortage_related=") + preferred.size() + "; selected=" + graph.selected().size());
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

    /** Only an actual coproduct dependency needs ranking before the first
     * ordinary source. Independent final-output alternatives keep that turn. */
    private boolean coupledTargetSources() {
        if (nesting != 0 || !excluded.isEmpty() || compiler.producers(target).size() < 2) return false;
        long started = budget.nodes(), allowance = Math.min(8192, budget.remainingWork() / 32), memory = 0;
        var coproducts = new HashMap<K, GraphRecipe<K>>();
        try {
            for (var recipe : compiler.producers(target)) {
                if (budget.nodes() - started >= allowance) return false;
                budget.check();
                for (K output : recipe.executionOutputs().keySet()) {
                    if (budget.nodes() - started >= allowance) return false;
                    budget.check();
                    if (!output.equals(target)) {
                        if (!coproducts.containsKey(output)) {
                            if (!budget.tryReserve(96)) return false;
                            memory += 96; coproducts.put(output, recipe);
                        } else if (coproducts.get(output) != recipe) coproducts.put(output, null);
                    }
                }
            }
            if (coproducts.isEmpty()) return false;
            for (var recipe : compiler.producers(target)) for (K input : recipe.inputs().keySet()) {
                if (budget.nodes() - started >= allowance) return false;
                budget.check();
                if (coproducts.containsKey(input) && coproducts.get(input) != recipe) return true;
            }
            return false;
        } finally { budget.release(memory); }
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
        if (graphMode != GraphMode.FULL || triedTargetSeedConsumption || preserve || !forceCraft || external.contains(target) || graph == null ||
                graph.regions().stream().noneMatch(region -> region.cyclic() && region.recipes().size() > 1 &&
                        region.recipes().stream().anyMatch(recipe -> recipe.executionOutputs().containsKey(target))))
            return false;
        // Retain the original net-production attempt, including its bootstrap,
        // before trying a smaller productive cycle that consumes a target seed.
        // Otherwise a cheaper but unprovable gross-output candidate can hide a
        // perfectly executable net-output plan using exactly the same sources.
        triedTargetSeedConsumption = true;
        budget.note("region_solve", "retry_with_target_seed; same_sources");
        solving = new GraphSolve<>(graph, target, amount, stock, external, requiredSeeds, preserve, forceCraft, budget, started, catalystPolicy, stock, graphMode)
                .startupCatalog(compiler.mode() == GraphMode.FULL ? compiler.verificationCatalog() : compiler.catalog(), excluded)
                .program(compiler.demandProgram(graph, budget))
                .allowTargetSeedConsumption(true);
        phase = 2;
        return true;
    }

    private void afterSolve() {
        if (!positiveRefillTried && nesting == 0 && catalystPolicy.parallelism() == 1 && missingPreview && !candidate.feasible() &&
                !candidate.missingExact().isEmpty() && budget.remainingWork() >= 8192) {
            positiveRefillTried = true;
            positiveRefillFailure = budget.failureDetail();
            positiveRefillStarted = budget.nodes();
            positiveRefillAllowance = Math.min(8192, budget.remainingWork() / 16);
            try {
                positiveRefill = MissingMaterialExpansion.fromRequest(compiler, target, amount, stock, external,
                        requiredSeeds, excluded, preserve, forceCraft, budget).refineSources(false).refineCycles(false);
                phase = 27;
                return;
            } catch (PlanningBudget.Exhausted limit) {
                if (limit.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw limit;
                budget.failureDetail(positiveRefillFailure);
            }
        }
        if (!candidate.feasible() && !countDemandTried && graph != null &&
                graph.regions().stream().anyMatch(region -> region.cyclic() && region.recipes().size() > 192)) {
            countDemandTried = true;
            countDemand = new CountDemandSearch<>(compiler, target, amount, stock, external, requiredSeeds, excluded,
                    preserve, forceCraft, budget, started);
            phase = 23;
            return;
        }
        if (!candidate.feasible() && quantityBlocked == null && quantities == null) {
            quantities = new QuantityAnalysis<>(compiler, target, amount, stock, external, requiredSeeds, excluded, budget, forceCraft);
            phase = 12;
            return;
        }
        if (cyclicStockPending && graphMode == GraphMode.FULL) {
            cyclicStockPending = false;
            // Let the existing cheap supply bounds reject genuinely unfunded
            // requests before projecting another cyclic candidate. An
            // inconclusive/deferred analysis still leaves this proposal a turn.
            if (!candidate.feasible() && !Boolean.TRUE.equals(quantityBlocked) && !Boolean.TRUE.equals(stockBlocked)) {
                cyclicStockCounts = new CountCyclicStockSearch<>(compiler, target, amount, stock, external,
                        requiredSeeds, excluded, preserve, forceCraft, budget, started);
                phase = 24;
                return;
            }
        }
        if (!candidate.feasible() && !candidate.missing().isEmpty()) {
            if (Boolean.TRUE.equals(quantityBlocked)) {
                // Proof completion does not rank source words. Compare this
                // bill with the retained one before verifying/publishing it.
                beginMissingPreview();
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
                beginMissingPreview();
                return;
            }
            if (graphMode == GraphMode.FULL && !outputViewTried) {
                // A chosen parallel-seed contract already has a bootstrap
                // path and an extra-copy cap. Preserve that path instead of
                // replacing its missing seed loan by an unrelated bulk plan.
                if (candidate.seeds().entrySet().stream().allMatch(e ->
                        external.contains(e.getKey()) || e.getValue() <= stock.getOrDefault(e.getKey(), 0L)) &&
                        GraphOutputView.eligible(graph, budget)) {
                    // An acyclic or unfunded source choice has not tried a
                    // coproduct startup view. Keep it available for later SCCs.
                    outputViewTried = true;
                    outputView = new GraphOutputView<>(compiler, graph, target, amount, stock, requiredSeeds, external,
                            excluded, preserve, forceCraft, catalystPolicy, budget, started);
                    phase = 20;
                    return;
                }
            }
            if (startStockView()) return;
            // Ordinary DAG propagation already accounts for every selected
            // input. Recursively crafting its missing leaves cannot improve
            // that source selection; compile the alternatives instead.
            if (graphMode == GraphMode.FULL && graph.regions().stream().anyMatch(GraphCompiler.Region::cyclic)) bootstrap = new Bootstrap(candidate);
        }
        if (!candidate.feasible() && candidate.missing().isEmpty() && startStockView()) return;
        phase = 3;
    }

    /** A conditional supply witness needs no negative inventory proof and
     * leaves the primary frontiers alive. A complete feasible word ends the
     * request just like any other verified primary witness. */
    private void stepPositiveRefill() {
        boolean done = false;
        try {
            if (positiveRefillDeficits == null) {
                done = positiveRefill.step();
                var proposal = positiveRefill.result();
                if (positiveRefill.productionVerifiedResult(proposal)) {
                    if (targetStockRebaseRequired(proposal)) {
                        positiveRefillDeficits = new RefillDeficitNormalization<>(proposal, stock, external, false, budget);
                        done = false;
                    } else {
                        retainPositiveRefill(proposal);
                        done = true;
                    }
                }
            } else if (positiveRefillDeficits.step()) {
                retainNormalizedPositiveRefill();
                done = true;
            }
        } catch (PlanningBudget.Exhausted limit) {
            if (limit.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw limit;
            done = true;
        }
        if (done || budget.nodes() - positiveRefillStarted >= positiveRefillAllowance) {
            if (positiveRefillDeficits != null) { positiveRefillDeficits.close(); positiveRefillDeficits = null; }
            if (positiveRefill != positiveRefillRetained) positiveRefill.close();
            positiveRefill = null;
            budget.failureDetail(positiveRefillFailure);
            if (result == null) afterSolve();
        }
    }

    private void retainNormalizedPositiveRefill() {
        var normalized = positiveRefillDeficits.result();
        refillDeficitMemory += positiveRefillDeficits.takeResultCharge();
        positiveRefillDeficits.close(); positiveRefillDeficits = null;
        retainPositiveRefill(normalized);
    }

    private void retainPositiveRefill(GraphPlan<K> normalized) {
        var previous = positiveRefillVerified;
        positiveRefillVerified = GraphPlan.ownedRefill(normalized, normalized.steps(), normalized.recipes(),
                normalized.initialExact(), normalized.missingExact(), normalized.feasible() ?
                        GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL : GraphPlan.Result.INCOMPLETE_MODEL, budget.nodes())
                .withMissingSourcesFrom(normalized).withAlternatives(normalized.alternatives());
        if (refillChoice == null || refillChoice == previous) refillChoice = positiveRefillVerified;
        // Keep physical storage charged alongside the normalized output maps.
        positiveRefillRetained = positiveRefill;
        budget.note("positive_refill", normalized.feasible() ? "verified_feasible_witness" : "conditional_manufacturing_witness");
        if (normalized.feasible()) {
            result = verified != null && verified.feasible() && productionApproved(verified) ? verified : positiveRefillVerified;
            verified = productionVerified = result;
            phase = 8;
            budget.phase(PlanningBudget.Phase.COMPLETE);
        }
    }

    private void stepCountDemand() {
        if (!countDemand.step()) return;
        GraphPlan<K> proposal = countDemand.result();
        countDemand.close(); countDemand = null;
        if (proposal != null) {
            beginVerifiedProposal(proposal, PlanningBudget.CandidateOrigin.INTEGER_COUNTS);
            phase = 4;
        } else {
            // Preservation changes the accepted seed contract, not which
            // funded count strategies an ordinary crafting request can use.
            cyclicStockPending = !stock.isEmpty();
            afterSolve();
        }
    }

    private void stepCyclicStockCounts() {
        if (!cyclicStockCounts.step()) return;
        GraphPlan<K> proposal = cyclicStockCounts.result();
        cyclicStockCounts.close(); cyclicStockCounts = null;
        if (proposal != null) {
            beginVerifiedProposal(proposal, PlanningBudget.CandidateOrigin.INTEGER_COUNTS);
            phase = 4;
        } else afterSolve();
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
                        stockDelayStarted = frontierWork();
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

    private long frontierWork() { return budget.searchWork() - pausedSearchWork; }

    private long stockCountCredit() {
        return stockView == null ? 0 : Math.max(0, stockView.searchWork() / 4 - stockCountWork);
    }

    private boolean optionalQuantityTurn() {
        return phase == 12 && quantities != null && quantities.sparsePotential();
    }

    private void suspendFrontierClocks(long work) {
        // The proof still consumes the shared request budget. It does not spend
        // a suspended bootstrap/source turn or make a parked view due again.
        // Otherwise a small optional analysis can evict a useful continuation
        // and cause many millions of units of repeated source exploration.
        pausedSearchWork += work;
        if (bootstrap != null && bootstrap.seedWork != null) bootstrap.seedWork.suspendFrontierClocks(work);
    }

    private void resumeQuantityAnalysis(int resumePhase) {
        quantityDeferred = false;
        quantityResumePhase = resumePhase;
        budget.note("quantity", "resume_exact_analysis; quick_work=" + (frontierWork() - quickSearchStarted));
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

    private boolean expandMissing(GraphPlan<K> plan) {
        if (!missingPreview || !MissingMaterialExpansion.needed(compiler, plan, nesting == 0)) return false;
        if (verifying != null) { verifying.close(); verifying = null; }
        refillBoundaryPending = true;
        missingExpansionRequiresDeficits = false;
        missingExpansion = new MissingMaterialExpansion<>(compiler, plan, stock, external, excluded, forceCraft, false, budget)
                .refineCycles(nesting == 0 && graphMode == GraphMode.FULL).refineSources(nesting == 0).requestedSeeds(requiredSeeds);
        budget.note("missing_expansion", "start; verified_program_retained; initial_keys=" + plan.missingExact().size());
        phase = 21;
        return true;
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
        if (result == null || finalProductionPlan != null || refillComparison != null || needsProductionProof(result) &&
                (!productionApproved(result) || !result.alternatives().isEmpty() && !alternativesApproved(result)))
            throw new IllegalStateException("Search is incomplete");
        return publishDomainResult(result);
    }

    private GraphPlan<K> publishDomainResult(GraphPlan<K> plan) {
        if (graphMode != GraphMode.ACYCLIC) return plan;
        if (budget.failureDetail().startsWith("PROVEN_INFEASIBLE:"))
            budget.failureDetail("RESTRICTED_PROOF_NOT_TRANSFERRED: ACYCLIC recipe domain");
        var reason = plan.feasible() ? GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL : GraphPlan.Result.INCOMPLETE_MODEL;
        return GraphPlan.ownedRefill(plan, plan.steps(), plan.recipes(), plan.initialExact(), plan.missingExact(), reason, plan.searchNodes())
                .withMissingSourcesFrom(plan)
                .withMissingSources(GraphPlan.MissingSource.UNASSESSED, domainMissingSources(plan))
                .withAlternatives(plan.alternatives().stream().map(this::publishDomainResult).toList());
    }

    private Map<K, GraphPlan.MissingSource> domainMissingSources(GraphPlan<K> plan) {
        var sources = new LinkedHashMap<K, GraphPlan.MissingSource>();
        for (K key : plan.missingExact().keySet()) {
            var source = plan.missingBoundary(key).source();
            if (compiler.producers(key).isEmpty()) {
                if (compiler.verificationProducerIndexAvailable()) {
                    if (compiler.hasVerificationProducer(key)) source = GraphPlan.MissingSource.EXCLUDED_SOURCES;
                } else if (source == GraphPlan.MissingSource.TERMINAL_UNDER_CURRENT_COVERAGE) {
                    source = GraphPlan.MissingSource.UNASSESSED;
                }
            }
            sources.put(key, source);
        }
        return sources;
    }

    @Override
    public GraphPlan<K> limited(PlanningBudget.Exhausted limit) {
        // Read-only harvesting of an already completed normalization does not
        // continue search or spend another work unit after the owner limit.
        if (positiveRefillDeficits != null && positiveRefillDeficits.result() != null) {
            retainNormalizedPositiveRefill();
            positiveRefill = null;
        }
        var retained = limitedInDomain(limit);
        if (needsProductionProof(retained) && !productionApproved(retained)) {
            // An exhausted account cannot finish a new physical manufacturing
            // proof. Retain only the incumbent whose full request was checked.
            if (verified != null && productionApproved(verified) && refillBoundaryReady(verified)) retained = verified;
            else retained = failure(GraphPlan.Result.valueOf(limit.limit().name()));
        }
        if (!retained.feasible() && retained.missingExact().isEmpty() && conditionalRefillMayReplace(retained)) {
            if (verified != null && productionApproved(verified) && refillBoundaryReady(verified)) retained = verified;
            else if (positiveRefillVerified != null) retained = productionVerified = positiveRefillVerified;
        }
        if (refillChoice != null && !retained.feasible() && retained.result() != GraphPlan.Result.INFEASIBLE &&
                (!retained.missingExact().isEmpty() || conditionalRefillMayReplace(retained)))
            retained = productionVerified = refillChoice;
        refillComparison = null;
        refillCompared = null;
        abandonFinalProduction();
        boolean proved = productionApproved(retained);
        boolean checkedAlternatives = alternativesApproved(retained);
        if (needsProductionProof(retained) && proved && !checkedAlternatives && !retained.alternatives().isEmpty())
            retained = retained.withAlternatives(List.of());
        var answer = publishDomainResult(retained);
        if (proved) productionExportVerified = answer;
        if (checkedAlternatives) alternativesExportVerified = answer;
        return answer;
    }

    private void abandonFinalProduction() {
        if (finalProductionProof != null) finalProductionProof.close();
        finalProductionProof = null;
        if (finalVerification != null) finalVerification.close();
        finalVerification = null;
        finalProductionPlan = null; finalApprovedAlternatives = null; finalMandatorySeeds = null;
        finalSeedsChecked = false;
    }

    private GraphPlan<K> limitedInDomain(PlanningBudget.Exhausted limit) {
        // A completed, verified refill is just as stable at the publication
        // boundary as a completed feasible plan. Do not replace it with an
        // empty failure when the owner reaches its limit before harvesting it.
        if (result != null) return result;
        if (missingExpansion != null) {
            // A goal builder uses a target-stock-free convention internally.
            // Its raw result is never a publishable bill in this owner.
            GraphPlan<K> raw = missingExpansion.result();
            boolean requiresRebase = missingExpansionRequiresDeficits && raw != null && targetStockRebaseRequired(raw);
            GraphPlan<K> completed = requiresRebase ? missingDeficits == null ? null : missingDeficits.result() : raw;
            if (requiresRebase && completed != null) {
                refillDeficitMemory += missingDeficits.takeResultCharge();
                missingExpansionRetained = missingExpansion;
            }
            if (missingExpansion.productionVerifiedResult(completed)) productionVerified = completed;
            // The witness is funded, but its displayed supply boundary has not
            // completed expansion. Do not publish a truncated intermediate bill.
            if (missingDeficits != null) { missingDeficits.close(); missingDeficits = null; }
            if (missingExpansion != missingExpansionRetained) missingExpansion.close();
            missingExpansion = null;
            if (completed != null) { refillBoundaryPending = false; return result = completed; }
            return incompleteMissingExpansion(limit);
        }
        if (seedOptimization != null) {
            GraphPlan<K> retained = seedOptimization.result();
            seedOptimization.close();
            seedOptimization = null;
            discardQuantityAnalysis();
            if (missingPreview && MissingMaterialExpansion.needed(compiler, retained, nesting == 0)) {
                return incompleteMissingExpansion(limit);
            }
            return missingPreview ? MissingMaterialExpansion.terminalResult(retained) : retained;
        }
        if (countSearch != null) {
            GraphPlan<K> counted = countSearch.result();
            if (counted != null) verified = productionVerified = counted;
            // A completed root proof can be harvested alongside a sibling that
            // exhausted the order budget. Retain that conclusion just as we
            // retain an already verified witness; optional recovery views never
            // enter this original-domain countSearch slot.
            if (countSearch.infeasible()) quantityBlocked = true;
            countSearch.close();
        }
        discardQuantityAnalysis();
        if (verified != null && refillBoundaryReady(verified)) return verified;
        if (Boolean.TRUE.equals(quantityBlocked)) return missingPreview ? withoutMissingPreview("preview stopped by " + limit.limit())
                : failure(GraphPlan.Result.INFEASIBLE);
        return failure(GraphPlan.Result.valueOf(limit.limit().name()));
    }

    private GraphPlan<K> incompleteMissingExpansion(PlanningBudget.Exhausted limit) {
        refillBoundaryPending = true;
        String detail = "MISSING_EXPANSION_INCOMPLETE: " + limit.limit();
        // Only this original-domain proof slot can establish infeasibility.
        // A funded suffix, rank refusal or failed restricted view cannot.
        if (Boolean.TRUE.equals(quantityBlocked)) return withoutMissingPreview(detail);
        budget.failureDetail(detail);
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
        packingCompletionOwner = null;
        packingCompletionUntil = 0;
        if (countDemand != null) countDemand.close();
        countDemand = null;
        if (cyclicStockCounts != null) cyclicStockCounts.close();
        cyclicStockCounts = null;
        try (var costs = budget.trace(nesting == 0 ? PlanningCostTrace.Origin.COORDINATOR : null, PlanningCostTrace.Stage.CLEANUP)) {
            closeMeasured();
        }
    }

    private void closeMeasured() {
        previewSelection = null;
        finishSharedCounts();
        abandonFinalProduction();
        if (reuseVerification != null) reuseVerification.close();
        reuseVerification = null;
        if (domainCompilation != null) domainCompilation.close();
        domainCompilation = null;
        if (registeredSearch.compareAndSet(true, false)) searchOwner.searchClosed();
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
        if (outputView != null) outputView.close();
        outputView = null;
        stockInterruptedCandidate = null;
        stockInterruptedOrigin = null;
        validatingStockProposal = false;
        if (missingAnalysis != null) missingAnalysis.close();
        missingAnalysis = null;
        if (missingDeficits != null) { missingDeficits.close(); missingDeficits = null; }
        if (positiveRefillDeficits != null) { positiveRefillDeficits.close(); positiveRefillDeficits = null; }
        if (missingExpansionRetained != null) { missingExpansionRetained.close(); missingExpansionRetained = null; }
        budget.release(refillDeficitMemory); refillDeficitMemory = 0;
        if (positiveRefill != null) positiveRefill.close();
        positiveRefill = null;
        if (positiveRefillRetained != null) positiveRefillRetained.close();
        positiveRefillRetained = null;
        if (missingExpansion != null) missingExpansion.close();
        missingExpansion = null;
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
        private final long searchStarted = frontierWork();
        private final Iterator<Map.Entry<K, Long>> deficits;
        private final List<PlanStep> prefix = new ArrayList<>();
        private final Map<String, GraphRecipe<K>> recipes;
        private final Map<K, BigInteger> available = new LinkedHashMap<>(PlanningInventory.exactAmounts(stock));
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
            if (frontierWork() - searchStarted > 32_768L + 128L * graph.recipes().size()) {
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
                        tail.seeds(), stock, external, graph, budget, started, catalystPolicy, graphMode).startupCatalog(compiler.mode() == GraphMode.FULL ? compiler.verificationCatalog() : compiler.catalog(), excluded);
                return false;
            }
            if (summary != null) {
                if (updating == null) {
                    if (!summary.step()) return false;
                    updating = summary.result().delta().keySet().iterator();
                }
                if (updating.hasNext()) {
                    K key = updating.next();
                    BigInteger next = available.getOrDefault(key, BigInteger.ZERO).add(summary.result().delta(key));
                    if (next.signum() < 0) {
                        // This speculative prefix cannot fit the bootstrap's
                        // inventory representation. It says nothing about an
                        // interleaved count witness or another source choice.
                        budget.note("bootstrap", "prefix_inventory_out_of_range; resource=" + key + "; exact=" + next + "; continuing_other_strategies");
                        bootstrapTooLarge = true;
                        result = original;
                        return true;
                    }
                    available.put(key, next);
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
                    seed.initialExact().forEach((key, count) -> {
                        if (external.contains(key)) available.merge(key, count, BigInteger::max);
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
                restarted = new GraphSolve<>(graph, target, amount, PlanningInventory.exactSnapshot(available), external, requiredSeeds, preserve, forceCraft, budget, started, catalystPolicy, stock, graphMode)
                        .startupCatalog(compiler.mode() == GraphMode.FULL ? compiler.verificationCatalog() : compiler.catalog(), excluded)
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
            seedWork = new GraphPlanningWork<>(compiler, deficit.getKey(), deficit.getValue(), PlanningInventory.exactSnapshot(available), external, Map.of(), false, true, budget, banned, nesting + 1);
            seedWork.missingPreview(missingPreview);
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
