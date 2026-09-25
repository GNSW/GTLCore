package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
    private Map<K, Integer> choices;
    private GraphCompilation<K> compiling;
    private GraphCompiler.Compiled<K> graph;
    private GraphSolve<K> solving;
    private Bootstrap bootstrap;
    private PlanVerification<K> verifying;
    private AllocationSearch<K> allocating;
    private IntegerCountSearch<K> countSearch;
    private MissingStockAnalysis<K> missingAnalysis;
    private QuantityAnalysis<K> quantities;
    private boolean quantityDeferred;
    private long quickSearchStarted, quickSearchAllowance;
    private int quantityResumePhase = -1;
    private Boolean quantityBlocked;
    private Boolean stockBlocked;
    private boolean allocationAttempted, countAttempted, frontierTruncated;
    private Iterator<K> alternatives;
    private GraphPlan<K> candidate, best, verified, result;
    private int phase;
    private CatalystPolicy catalystPolicy = CatalystPolicy.STOCK;

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
        this.excluded = Set.copyOf(excluded);
        this.nesting = nesting;
        pending.add(Map.of());
        budget.note("source_search", "target=" + target + "; amount=" + amount + "; nesting=" + nesting + "; sources=" + compiler.producers(target).size());
    }

    @Override
    public boolean advance(PlanningScheduler.Slice slice) {
        while (slice.next()) {
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
        try {
            budget.check();
            if (quantityDeferred && phase != 4 && phase != 8 && phase != 10 &&
                    !(phase == 3 && candidate.feasible()) &&
                    (phase == 9 || budget.nodes() - quickSearchStarted >= quickSearchAllowance)) {
                resumeQuantityAnalysis(phase);
            }
            switch (phase) {
                case 0 -> {
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
                    if (!allocationAttempted && best != null &&
                            (pending.isEmpty() || seen.size() >= 8) && !provenMissing(best)) {
                        allocationAttempted = true;
                        allocating = new AllocationSearch<>(compiler, target, amount, stock, external, requiredSeeds,
                                preserve, forceCraft, excluded, budget, started);
                        phase = 6;
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
                    if (!seen.add(choices)) return false;
                    graph = compiler.cached(target, requiredSeeds.keySet(), choices, excluded);
                    if (graph == null) compiling = compiler.begin(target, requiredSeeds.keySet(), choices, excluded, budget);
                    phase = 1;
                }
                case 1 -> {
                    if (compiling != null) {
                        if (!(slice == null ? compiling.step() : compiling.advance(slice))) return false;
                        graph = compiling.result();
                        compiler.publish(target, requiredSeeds.keySet(), choices, excluded, graph);
                        compiling = null;
                    }
                    solving = new GraphSolve<>(graph, target, amount, stock, external, requiredSeeds, preserve, forceCraft, budget, started, catalystPolicy, stock);
                    budget.note("compile", "choice=" + seen.size() + "; recipes=" + graph.recipes().size() + "; regions=" + graph.regions().size() +
                            "; cyclic=" + graph.regions().stream().filter(GraphCompiler.Region::cyclic).count());
                    phase = 2;
                }
                case 2 -> {
                    if (!solving.step()) return false;
                    candidate = solving.result();
                    budget.note("region_solve", candidate.result() + "; missing_keys=" + candidate.missingExact().size());
                    solving = null;
                    afterSolve();
                }
                case 3 -> {
                    if (bootstrap != null) {
                        if (!bootstrap.step(slice)) return false;
                        candidate = bootstrap.result;
                        bootstrap = null;
                    }
                    if (candidate.feasible()) {
                        verifying = new PlanVerification<>(candidate, budget);
                        phase = 4;
                    } else {
                        if (best == null || (!candidate.missing().isEmpty() &&
                                (best.missing().isEmpty() || candidate.missing().size() < best.missing().size())))
                            best = candidate;
                        alternatives = graph.selected().keySet().iterator();
                        phase = 5;
                    }
                }
                case 4 -> {
                    if (!verifying.step()) return false;
                    verified = candidate;
                    result = candidate;
                    discardQuantityAnalysis();
                    budget.phase(PlanningBudget.Phase.COMPLETE);
                    phase = 8;
                }
                case 5 -> {
                    if (!alternatives.hasNext()) {
                        phase = 0;
                        return false;
                    }
                    K key = alternatives.next();
                    int next = choices.getOrDefault(key, 0) + 1;
                    long count = compiler.producers(key).stream().filter(recipe -> !excluded.contains(recipe.id())).count();
                    if (next < count) {
                        Map<K, Integer> changed = new LinkedHashMap<>(choices);
                        changed.put(key, next);
                        if (pending.size() >= 4096) {
                            frontierTruncated = true;
                            return false;
                        }
                        budget.reserve(128L + 48L * changed.size());
                        pending.add(Map.copyOf(changed));
                    }
                }
                case 6 -> {
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
                        verifying = new PlanVerification<>(candidate, budget);
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
                    result = best;
                    discardQuantityAnalysis();
                    phase = 8;
                }
                case 11 -> {
                    if (!missingAnalysis.step()) return false;
                    stockBlocked = missingAnalysis.blocked();
                    missingAnalysis = null;
                    afterSolve();
                }
                case 12 -> {
                    if (quantityResumePhase < 0 && quantities.readyForHeavyAnalysis()) {
                        quantityDeferred = true;
                        quickSearchStarted = budget.nodes();
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
                        if (allocating != null) allocating.discard();
                        allocating = new AllocationSearch<>(compiler, target, amount, stock, external, requiredSeeds,
                                preserve, forceCraft, excluded, budget, started, true);
                        phase = 13;
                    } else if (quantityResumePhase >= 0) {
                        phase = quantityResumePhase;
                        quantityResumePhase = -1;
                    } else afterSolve();
                }
                case 13 -> {
                    if (!allocating.step()) return false;
                    GraphPlan<K> preview = allocating.result();
                    allocating = null;
                    if (preview != null && !preview.missing().isEmpty()) best = preview;
                    else if (!candidate.missing().isEmpty()) best = candidate;
                    if (best != null && !best.missing().isEmpty()) verifyMissing();
                    else {
                        budget.failureDetail("MISSING_PREVIEW_UNAVAILABLE: quantity infeasible, no funded witness constructed");
                        result = failure(GraphPlan.Result.UNKNOWN);
                    }
                }
                case 14 -> {
                    if (!countSearch.step(slice)) return false;
                    GraphPlan<K> counted = countSearch.result();
                    boolean proved = countSearch.infeasible();
                    budget.note("integer_counts", "witness=" + (counted != null) + "; proven_infeasible=" + proved);
                    countSearch = null;
                    if (counted != null) {
                        if (allocating != null) allocating.discard();
                        allocating = null;
                        candidate = counted;
                        verifying = new PlanVerification<>(candidate, budget);
                        phase = 4;
                    } else if (proved && best != null && !best.missing().isEmpty()) {
                        if (allocating != null) allocating.discard();
                        quantityBlocked = true;
                        candidate = best;
                        allocating = new AllocationSearch<>(compiler, target, amount, stock, external, requiredSeeds,
                                preserve, forceCraft, excluded, budget, started, true);
                        phase = 13;
                    } else {
                        phase = allocating == null ? 0 : 6;
                    }
                }
                case 15 -> startCountSearch();
                default -> {
                    return true;
                }
            }
        } catch (PlanningBudget.Exhausted limit) {
            result = limited(limit);
        } catch (ArithmeticException overflow) {
            budget.failureDetail("arithmetic: " + overflow + " at " + (overflow.getStackTrace().length == 0 ? "unknown" : overflow.getStackTrace()[0]));
            result = failure(GraphPlan.Result.AMOUNT_LIMIT);
        }
        return result != null;
    }

    private void startCountSearch() {
        if (quantityDeferred) {
            resumeQuantityAnalysis(15);
            return;
        }
        countAttempted = true;
        budget.note("integer_counts", "start");
        countSearch = new IntegerCountSearch<>(compiler, target, amount, stock, requiredSeeds, external,
                excluded, preserve, forceCraft, budget, started);
        phase = 14;
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
            // Ordinary DAG propagation already accounts for every selected
            // input. Recursively crafting its missing leaves cannot improve
            // that source selection; compile the alternatives instead.
            if (graph.regions().stream().anyMatch(GraphCompiler.Region::cyclic)) bootstrap = new Bootstrap(candidate);
        }
        phase = 3;
    }

    private void resumeQuantityAnalysis(int resumePhase) {
        quantityDeferred = false;
        quantityResumePhase = resumePhase;
        budget.note("quantity", "resume_exact_analysis; quick_work=" + (budget.nodes() - quickSearchStarted));
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
        if (countSearch != null) {
            GraphPlan<K> counted = countSearch.result();
            if (counted != null) verified = counted;
            countSearch.close();
        }
        discardQuantityAnalysis();
        if (verified != null) return new GraphPlan<>(verified.target(), verified.amount(), verified.preserveSeeds(), verified.steps(),
                verified.recipes(), verified.initialExact(), verified.seeds(), Map.of(), GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL,
                budget.nodes(), System.nanoTime() - started);
        return failure(GraphPlan.Result.valueOf(limit.limit().name()));
    }

    private GraphPlan<K> failure(GraphPlan.Result reason) {
        if (countSearch != null) countSearch.close();
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
        if (countSearch != null) countSearch.close();
        if (bootstrap != null && bootstrap.seedWork != null) bootstrap.seedWork.close();
        discardQuantityAnalysis();
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

    private final class Bootstrap {

        private final GraphPlan<K> original;
        private final long searchStarted = budget.nodes();
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
            if (budget.nodes() - searchStarted > 32_768L + 128L * graph.recipes().size()) {
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
                    available.put(key, CheckedAmounts.amount(BigInteger.valueOf(available.getOrDefault(key, 0L)).add(summary.result().delta(key))));
                } else {
                    summary = null;
                    updating = null;
                }
                return false;
            }
            if (seedWork != null) {
                if (!(slice == null ? seedWork.step() : seedWork.advance(slice))) return false;
                GraphPlan<K> seed = seedWork.result();
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
                restarted = new GraphSolve<>(graph, target, amount, available, external, requiredSeeds, preserve, forceCraft, budget, started, catalystPolicy, stock);
                return false;
            }
            var deficit = deficits.next();
            var owner = graph.regions().stream().filter(region -> region.cyclic() &&
                    region.recipes().stream().anyMatch(recipe -> recipe.outputs().containsKey(deficit.getKey()))).findFirst().orElse(null);
            if (owner == null) return false;
            Set<String> banned = new HashSet<>(excluded);
            owner.recipes().forEach(recipe -> banned.add(recipe.id()));
            if (compiler.producers(deficit.getKey()).stream().noneMatch(recipe -> !banned.contains(recipe.id()))) return false;
            seedWork = new GraphPlanningWork<>(compiler, deficit.getKey(), deficit.getValue(), available, external, Map.of(), false, true, budget, banned, nesting + 1);
            return false;
        }
    }
}
