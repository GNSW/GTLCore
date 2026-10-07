package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Positive-only demand refinement over request-local compiled graphs. */
final class GraphStockViewWork<K> implements AutoCloseable {

    private final GraphCompiler<K> compiler;
    private final K target;
    private final long amount, started;
    private final Map<K, Long> stock, seeds;
    private final Set<K> external;
    private final Set<String> baseExcluded, excluded = new LinkedHashSet<>();
    private final Set<K> leaves = new HashSet<>();
    private final Map<K, Set<String>> triedSources = new LinkedHashMap<>();
    private final boolean preserve, force;
    private final CatalystPolicy policy;
    private final PlanningBudget budget;
    private GraphSourceRanking<K> ranking;
    private final java.util.function.Supplier<GraphSourceRanking<K>> rankingFactory;
    private GraphCompilation<K> compiling;
    private GraphCompiler.Compiled<K> graph;
    private GraphSolve<K> solving;
    private GraphPlan<K> result;
    private GraphPlan<K> repairCandidate;
    private GraphStockViewWork<K> fork;
    private boolean forkTried;
    private long memory, graphMemory, compileMemory, choiceMemory;
    private final int variant;
    private int rounds;
    private long work, stepStarted;
    private long completedWork, roundCost;
    private int missingKeys = -1, stalledRounds;
    private boolean initialized, done;
    private GraphSupportNeighborhood<K> neighborhood;

    GraphStockViewWork<K> neighborhood(GraphSupportNeighborhood<K> neighborhood) {
        this.neighborhood = neighborhood;
        return this;
    }

    GraphStockViewWork(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
                       Set<K> external, Map<K, Long> seeds, Set<String> excluded,
                       boolean preserve, boolean force, CatalystPolicy policy, PlanningBudget budget, long started,
                       int variant, java.util.function.Supplier<GraphSourceRanking<K>> rankingFactory) {
        this.compiler = compiler;
        this.target = target;
        this.amount = amount;
        this.stock = stock;
        this.external = external;
        this.seeds = seeds;
        this.baseExcluded = excluded;
        this.preserve = preserve;
        this.force = force;
        this.policy = policy;
        this.budget = budget;
        this.started = started;
        this.variant = variant;
        this.rankingFactory = rankingFactory;
    }

    private long usedWork() {
        return work + budget.threadSearchWork() - stepStarted;
    }

    private void check() {
        budget.checkpoint();
        budget.check();
    }

    private void reserve(long bytes) {
        if (!budget.tryReserve(bytes)) throw Stopped.INSTANCE;
        memory += bytes;
    }

    private void resetLeaves() {
        leaves.clear();
        for (var entry : stock.entrySet()) {
            check();
            if (entry.getValue() > 0 && !(force && target.equals(entry.getKey()))) leaves.add(entry.getKey());
        }
    }

    boolean step() {
        String previousFailure = budget.failureDetail();
        stepStarted = budget.threadSearchWork();
        try {
            if (done) return true;
            if (compiling != null) budget.compilationCheck();
            else check();
            if (!initialized) {
                // Admission precedes copying inventory boundaries or exclusions.
                // All of these sets are private to this positive proposal.
                reserve(1024L + 96L * stock.size() + 128L * baseExcluded.size());
                for (String id : baseExcluded) {
                    check();
                    excluded.add(id);
                }
                resetLeaves();
                if (variant != 0) {
                    ranking = rankingFactory.get();
                    if (ranking == null) throw Stopped.INSTANCE;
                }
                initialized = true;
            }
            return advance();
        } catch (Stopped ignored) {
            done = true;
            return true;
        } catch (PlanningBudget.Exhausted failure) {
            if (failure.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw failure;
            // Optional view admission cannot discard the retained full search.
            budget.failureDetail(previousFailure);
            budget.note("stock_view", "workspace_declined; original_frontier_retained");
            done = true;
            return true;
        } finally {
            work += budget.threadSearchWork() - stepStarted;
        }
    }

    private boolean advance() {
        if (compiling != null) {
            if (!compiling.step()) return false;
            graph = compiling.result();
            compiling.close();
            compiling = null;
            budget.release(compileMemory);
            compileMemory = 0;
            long bytes = graph.estimatedBytes();
            if (!budget.tryReserve(bytes)) throw Stopped.INSTANCE;
            graphMemory = bytes;
            solving = new GraphSolve<>(graph, target, amount, stock, external, seeds, preserve, force,
                    budget, started, policy, stock).program(compiler.demandProgram(graph, budget)).scoutRegions();
            return false;
        }
        GraphPlan<K> candidate = repairCandidate;
        repairCandidate = null;
        if (solving != null) {
            if (!solving.step()) return false;
            candidate = solving.result();
            solving.close();
            solving = null;
            long spent = Math.max(1, usedWork() - completedWork);
            completedWork = usedWork();
            roundCost = roundCost == 0 ? spent : (3 * roundCost + spent) / 4;
            int missing = candidate.missingExact().size();
            stalledRounds = missingKeys >= 0 && missing >= missingKeys ? stalledRounds + 1 : 0;
            missingKeys = missing;
            budget.note("stock_view", "round=" + rounds + "; variant=" + variant + "; recipes=" + graph.recipes().size() +
                    "; result=" + candidate.result() + "; missing_keys=" + candidate.missingExact().size());
            if (neighborhood != null) neighborhood.offer(graph, candidate);
            if (candidate.feasible()) {
                result = candidate;
                budget.note("stock_view", "witness; rounds=" + rounds + "; recipes=" + graph.recipes().size() +
                        "; variant=" + variant + "; work=" + usedWork());
                done = true;
                return true;
            }
        }
        if (candidate != null) {
            boolean refined = false;
            for (K key : candidate.missingExact().keySet()) {
                check();
                if (!compiler.producers(key).isEmpty()) refined |= leaves.remove(key);
            }
            if (!refined && candidate.result() == GraphPlan.Result.MISSING_INPUT && (variant < 6 || variant >= 8)) {
                if ((variant == 1 || variant == 2) && !forkTried) {
                    forkTried = true;
                    fork = forkConsumerRepair(candidate);
                }
                refined = changeMissingSources(candidate);
                if (!refined && fork != null) {
                    // No alternative producer: both children would take the
                    // same consumer repair, so keep only the existing view.
                    fork.close();
                    fork = null;
                    forkTried = false;
                }
            }
            if (!refined) {
                // A missing input only rejects this heuristic view. Trying a
                // different consumer never contributes a full-catalog nogood.
                for (var recipe : graph.recipes().values()) {
                    check();
                    if (excluded.contains(recipe.id())) continue;
                    if (recipe.executionOutputs().containsKey(target)) {
                        boolean alternative = false;
                        for (var source : compiler.producers(target)) {
                            check();
                            if (source != recipe && !excluded.contains(source.id())) {
                                alternative = true;
                                break;
                            }
                        }
                        if (!alternative) continue;
                    }
                    for (K key : recipe.inputs().keySet()) {
                        check();
                        if (candidate.missingExact().containsKey(key)) {
                            reserve(96);
                            refined |= excluded.add(recipe.id());
                            break;
                        }
                    }
                }
            }
            budget.release(graphMemory);
            graphMemory = 0;
            graph = null;
            if (!refined || rounds >= 64) {
                done = true;
                return true;
            }
        }
        Map<K, Integer> choices = choices();
        try {
            // GraphCompilation copies all three maps/sets. Retain their own
            // reservation until that compilation is finished or abandoned.
            long bytes = 512L + 160L * choices.size() + 96L * (leaves.size() + (long) excluded.size());
            if (!budget.tryReserve(bytes)) throw Stopped.INSTANCE;
            compileMemory = bytes;
            compiling = compiler.beginStockView(target, seeds.keySet(), choices, excluded, leaves, budget);
        } finally {
            budget.release(choiceMemory);
            choiceMemory = 0;
        }
        rounds++;
        return false;
    }

    /** Keep both repair choices without repeating their common expansion. */
    private GraphStockViewWork<K> forkConsumerRepair(GraphPlan<K> candidate) {
        var branch = new GraphStockViewWork<>(compiler, target, amount, stock, external, seeds, baseExcluded,
                preserve, force, policy, budget, started, variant == 2 ? 6 : 7, rankingFactory).neighborhood(neighborhood);
        try {
            branch.reserve(1024L + 96L * stock.size() + 128L * excluded.size());
            long copiedEntries = (long) leaves.size() + excluded.size();
            branch.leaves.addAll(leaves);
            branch.excluded.addAll(excluded);
            for (var entry : triedSources.entrySet()) {
                check();
                branch.reserve(160L + 96L * entry.getValue().size());
                branch.triedSources.put(entry.getKey(), new HashSet<>(entry.getValue()));
                copiedEntries += entry.getValue().size();
            }
            // SCAN's second argument is bit width, not an element count. Merge
            // the quarter-unit copy cost into one charge, plus constant work
            // for retaining the immutable graph and repair metadata below.
            budget.charge(1 + (copiedEntries + 3) / 4);
            long bytes = graph.estimatedBytes();
            if (!budget.tryReserve(bytes)) throw Stopped.INSTANCE;
            branch.graphMemory = bytes;
            branch.graph = graph;
            branch.repairCandidate = candidate;
            branch.ranking = ranking;
            branch.initialized = true;
            branch.rounds = rounds;
            branch.roundCost = roundCost;
            branch.missingKeys = missingKeys;
            branch.stalledRounds = stalledRounds;
            budget.note("stock_view", "fork_consumer_repair; round=" + rounds + "; reused_recipes=" + graph.recipes().size());
            return branch;
        } catch (Stopped ignored) {
            branch.close();
            return null;
        } catch (RuntimeException | Error failure) {
            branch.close();
            throw failure;
        }
    }

    GraphStockViewWork<K> takeFork() {
        var next = fork;
        fork = null;
        return next;
    }

    private boolean changeMissingSources(GraphPlan<K> candidate) {
        boolean changed = false;
        for (K key : candidate.missingExact().keySet()) {
            check();
            var source = graph.selected().get(key);
            if (source == null) continue;
            Set<String> tried = triedSources.get(key);
            for (var other : compiler.producers(key)) {
                check();
                if (other == source || excluded.contains(other.id()) || tried != null && tried.contains(other.id())) continue;
                if (tried == null) {
                    reserve(160);
                    tried = new HashSet<>();
                    triedSources.put(key, tried);
                }
                if (!tried.contains(source.id())) {
                    reserve(96);
                    tried.add(source.id());
                }
                changed = true;
                break;
            }
        }
        // An unfunded producer can be the wrong source, even when its consumers
        // are necessary. These per-key preferences belong only to this view;
        // they neither ban a recipe globally nor justify a catalog conflict.
        // Missing seed plans retain their existing consumer/order refinement.
        return changed;
    }

    private Map<K, Integer> choices() {
        boolean retained = false;
        try {
            var pending = new ArrayDeque<K>();
            Set<K> queued = new HashSet<>();
            Map<K, Integer> choices = new LinkedHashMap<>();
            Map<K, Long> demand = new LinkedHashMap<>();
            demand.put(target, amount);
            seeds.forEach((key, count) -> demand.merge(key, count, Math::max));
            enqueue(target, pending, queued);
            for (K seed : seeds.keySet()) {
                check();
                enqueue(seed, pending, queued);
            }
            while (!pending.isEmpty()) {
                check();
                K key = pending.removeFirst();
                if (leaves.contains(key) || external.contains(key)) continue;
                var sources = compiler.producers(key);
                long needed = Math.max(1, demand.getOrDefault(key, 1L) - (force && target.equals(key) ? 0 : stock.getOrDefault(key, 0L)));
                long rankingWork = Math.min(262_144, budget.remainingWork());
                var ordered = variant == 0 ? sources : variant == 3 ? ranking.sources(key, needed, rankingWork) :
                        ranking.sources(key, variant == 2 || variant == 4 || variant == 6 || variant == 9, rankingWork);
                GraphRecipe<K> chosen = null;
                for (var recipe : ordered) {
                    check();
                    if (!excluded.contains(recipe.id()) && !triedSources.getOrDefault(key, Set.of()).contains(recipe.id())) {
                        chosen = recipe;
                        break;
                    }
                }
                if (chosen == null) continue;
                int ordinal = 0;
                for (var recipe : sources) {
                    check();
                    if (recipe == chosen) break;
                    if (!excluded.contains(recipe.id())) ordinal++;
                }
                if (ordinal != 0) choices.put(key, ordinal);
                long output = Math.max(1, chosen.executionOutputs().getOrDefault(key, 0L) - chosen.inputs().getOrDefault(key, 0L));
                long runs = needed / output + (needed % output == 0 ? 0 : 1);
                for (var input : chosen.inputs().entrySet()) {
                    check();
                    // This saturation affects ranking only. Real demand and
                    // accepted execution still use exact graph arithmetic.
                    long estimate = runs > Long.MAX_VALUE / input.getValue() ? Long.MAX_VALUE : runs * input.getValue();
                    demand.merge(input.getKey(), estimate, Math::max);
                    enqueue(input.getKey(), pending, queued);
                }
            }
            retained = true;
            return choices;
        } finally {
            if (!retained) {
                budget.release(choiceMemory);
                choiceMemory = 0;
            }
        }
    }

    private void enqueue(K key, ArrayDeque<K> pending, Set<K> queued) {
        if (queued.contains(key)) return;
        if (!budget.tryReserve(320)) throw Stopped.INSTANCE;
        choiceMemory += 320;
        queued.add(key);
        pending.addLast(key);
    }

    GraphPlan<K> result() {
        return result;
    }

    boolean compilationActive() {
        return compiling != null;
    }

    boolean refined() {
        return rounds > 1;
    }

    boolean sampled() {
        return missingKeys >= 0 || work >= 131_072;
    }

    boolean quantitative() {
        return variant >= 8;
    }

    int missingKeys() {
        return missingKeys > 0 ? missingKeys : Integer.MAX_VALUE;
    }

    double estimatedRepairCost() {
        if (missingKeys < 0 || roundCost == 0) return Double.POSITIVE_INFINITY;
        long outstanding = Math.max(32_768, Math.max(roundCost, work - completedWork));
        return outstanding * (1.0 + missingKeys) * (1.0 + Math.min(8, stalledRounds));
    }

    private static final class Stopped extends RuntimeException {

        private static final Stopped INSTANCE = new Stopped();

        private Stopped() {
            super(null, null, false, false);
        }
    }

    @Override
    public void close() {
        if (fork != null) fork.close();
        fork = null;
        repairCandidate = null;
        if (compiling != null) compiling.close();
        compiling = null;
        ranking = null;
        budget.release(graphMemory + memory + compileMemory + choiceMemory);
        graphMemory = memory = compileMemory = choiceMemory = 0;
        graph = null;
        if (solving != null) solving.close();
        solving = null;
        leaves.clear();
        excluded.clear();
        triedSources.clear();
        done = true;
    }
}
