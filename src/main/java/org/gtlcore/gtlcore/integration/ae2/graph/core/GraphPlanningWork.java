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
    private MissingStockAnalysis<K> missingAnalysis;
    private boolean allocationAttempted;
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
            switch (phase) {
                case 0 -> {
                    if (pending.isEmpty()) {
                        if (best == null || best.missing().isEmpty()) {
                            result = failure(GraphPlan.Result.UNKNOWN);
                            return true;
                        }
                        missingAnalysis = new MissingStockAnalysis<>(compiler, target, stock, external,
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
                    phase = 2;
                }
                case 2 -> {
                    if (!solving.step()) return false;
                    candidate = solving.result();
                    solving = null;
                    if (!candidate.feasible() && !candidate.missing().isEmpty()) bootstrap = new Bootstrap(candidate);
                    phase = 3;
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
                        if (!allocationAttempted && !provenMissing(candidate)) {
                            allocationAttempted = true;
                            allocating = new AllocationSearch<>(compiler, target, amount, stock, external, requiredSeeds, preserve, forceCraft, excluded, budget, started);
                            phase = 6;
                            return false;
                        }
                        alternatives = graph.selected().keySet().iterator();
                        phase = 5;
                    }
                }
                case 4 -> {
                    if (!verifying.step()) return false;
                    verified = candidate;
                    result = candidate;
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
                        if (pending.size() >= 4096) throw budget.exhausted(PlanningBudget.Limit.SEARCH_LIMIT, "candidate_frontier=4096");
                        budget.reserve(128L + 48L * changed.size());
                        pending.add(Map.copyOf(changed));
                    }
                }
                case 6 -> {
                    if (!allocating.step()) return false;
                    GraphPlan<K> allocated = allocating.result();
                    allocating = null;
                    if (allocated != null) {
                        candidate = allocated;
                        verifying = new PlanVerification<>(candidate, budget);
                        phase = 4;
                    } else {
                        alternatives = graph.selected().keySet().iterator();
                        phase = 5;
                    }
                }
                case 9 -> {
                    if (!missingAnalysis.step()) return false;
                    boolean blocked = missingAnalysis.blocked();
                    missingAnalysis = null;
                    if (!blocked && !provenMissing(best)) {
                        result = failure(GraphPlan.Result.UNKNOWN);
                        return true;
                    }
                    // Prove the proposed missing-material preview really reaches
                    // the goal when funded. It remains non-executable until its
                    // exact deficits are supplied and a new request is planned.
                    verifying = new PlanVerification<>(new GraphPlan<>(best.target(), best.amount(), best.preserveSeeds(),
                            best.steps(), best.recipes(), best.initialExact(), best.seeds(), Map.of(),
                            GraphPlan.Result.FEASIBLE, best.searchNodes(), best.planningNanos()), budget);
                    phase = 10;
                }
                case 10 -> {
                    if (!verifying.step()) return false;
                    result = best;
                    phase = 8;
                }
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

    @Override
    public CompletableFuture<?> waitingFor() {
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
        if (verified != null) return new GraphPlan<>(verified.target(), verified.amount(), verified.preserveSeeds(), verified.steps(),
                verified.recipes(), verified.initialExact(), verified.seeds(), Map.of(), GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL,
                budget.nodes(), System.nanoTime() - started);
        return failure(GraphPlan.Result.valueOf(limit.limit().name()));
    }

    private GraphPlan<K> failure(GraphPlan.Result reason) {
        return new GraphPlan<>(target, amount, preserve, new PlanStep.Sequence(List.of()), Map.of(), Map.of(), Map.of(), Map.of(),
                reason, budget.nodes(), System.nanoTime() - started);
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
                } else if (seed.result() == GraphPlan.Result.TIMEOUT || seed.result() == GraphPlan.Result.SEARCH_LIMIT ||
                        seed.result() == GraphPlan.Result.MEMORY_LIMIT || seed.result() == GraphPlan.Result.GRAPH_LIMIT) {
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
