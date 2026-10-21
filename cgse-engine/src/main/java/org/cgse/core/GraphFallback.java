// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

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

/**
 * Bounded ordinary-recipe search, followed by a refill preview if no funded
 * witness was found. This path never invokes a cycle or count solver.
 * Missing inputs describe this witness only, not infeasibility of the catalog.
 */
public final class GraphFallback {

    private GraphFallback() {}

    /** Routing reference for the unshared two-fold/count pipeline. Retain its
     * compact-goal decisions when a completed tree can reuse its first fold.
     * This is not a lower bound for the accelerated path. Each emitted step is
     * a fresh, positive batch (possibly a compact Repeat). */
    static long unsharedFinishingWorkLowerBound(GraphRecipe<?> recipe) {
        long ports = (long) recipe.inputs().size() + recipe.outputs().size();
        if (!recipe.hasInputReturns()) return 4 + 2 * ports;
        // Returned-input batches cannot use the fused port loop. Each port and
        // each scale/merge key takes a separate fold step: Expansion+fold charge
        // twice, and Expansion+verifier+fold three times. max bounds the union.
        // A singleton word now forwards the batch fold directly: its material
        // keys are scaled, but need not be merged into another sequence frame.
        long keys = Math.max(recipe.inputs().size(), recipe.outputs().size());
        return 4 + 5 * (ports + keys);
    }

    public static <K> GraphPlan<K> plan(GraphCompiler<K> compiler, K target, long amount,
                                        Map<K, Long> stock, Set<K> external, Map<K, Long> seeds,
                                        boolean preserve, boolean forceCraft, PlanningBudget budget) {
        GraphPlan<K> candidate;
        SequenceSummary<K> completed = null;
        try (var work = new Expansion<>(compiler, target, amount, stock, external, seeds, preserve, forceCraft, budget)) {
            while (!work.step()) { /* All traversal, arithmetic and verification share the fallback budget. */ }
            candidate = work.result;
            if (candidate != null && work.reusedFold) completed = work.verification.summary();
        }
        if (candidate == null) return refillGoals(compiler, target, amount, stock, external, seeds, preserve, forceCraft, budget);
        if (!MissingMaterialExpansion.needed(compiler, candidate)) return MissingMaterialExpansion.terminalResult(candidate);
        try (var expansion = new MissingMaterialExpansion<>(compiler, candidate, stock, external, Set.of(), forceCraft, forceCraft, budget)
                .reuseSuffix(candidate, completed)) {
            while (!expansion.step()) { /* A cutoff is a budget exit, never a terminal material. */ }
            return expansion.result();
        }
    }

    /** The old material word cannot fit its mandatory finishing work. Start a
     * compact backward bill instead, using only the unchanged remaining budget.
     * The empty word is funded by its explicit goals, not by invented production. */
    private static <K> GraphPlan<K> refillGoals(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
                                               Set<K> external, Map<K, Long> seeds, boolean preserve, boolean force,
                                               PlanningBudget budget) {
        long memory = 0;
        try {
            Map<K, BigInteger> initial = new LinkedHashMap<>(), missing = new LinkedHashMap<>();
            for (var e : seeds.entrySet()) {
                budget.check(); budget.reserve(384); memory += 384;
                initial.put(e.getKey(), BigInteger.valueOf(e.getValue()));
            }
            budget.check();
            if (!initial.containsKey(target)) { budget.reserve(384); memory += 384; }
            initial.merge(target, BigInteger.valueOf(amount), BigInteger::add);
            for (var e : initial.entrySet()) {
                budget.check();
                BigInteger have = force && target.equals(e.getKey()) ? BigInteger.ZERO : PlanningInventory.exactAmount(stock, e.getKey());
                BigInteger gap = e.getValue().subtract(have);
                if (gap.signum() > 0 && !external.contains(e.getKey())) missing.put(e.getKey(), gap);
            }
            var boundary = new GraphPlan<>(target, amount, preserve, new PlanStep.Sequence(List.of()), Map.of(), initial, seeds, missing,
                    missing.isEmpty() ? GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL : GraphPlan.Result.MISSING_INPUT, budget.nodes(), 0);
            if (missing.isEmpty()) return boundary;
            try (var expansion = MissingMaterialExpansion.fromGoals(compiler, boundary, stock, external, force, budget)) {
                while (!expansion.step()) { /* No old tree, no replenishment loans, no extra allowance. */ }
                return expansion.result();
            }
        } finally {
            budget.release(memory);
        }
    }

    /** A bounded positive proposal. Failure never produces a missing-material claim. */
    static <K> GraphPlan<K> witnessByCost(GraphCompiler<K> compiler, K target, long amount,
                                          Map<K, Long> stock, Set<K> external, Map<K, Long> seeds,
                                          boolean preserve, boolean forceCraft, PlanningBudget budget, long maximumWork) {
        budget.checkpoint();
        if (maximumWork <= 0) return null;
        long start = budget.nodes();
        String previousFailure = budget.failureDetail();
        var previousPhase = budget.phase();
        try (var work = new Expansion<>(compiler, target, amount, stock, external, seeds, preserve, forceCraft, budget)) {
            work.witnessAllowance = Math.min(maximumWork, budget.remainingWork());
            if (work.witnessAllowance == 0) budget.check();
            while (budget.nodes() - start < maximumWork) {
                if (work.step()) return work.result != null && work.result.feasible() ? work.result : null;
            }
            return null;
        } catch (PlanningBudget.Exhausted failure) {
            if (failure.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw failure;
            // An optional witness must not abort the retained search just
            // because its independent workspace or final summary did not fit.
            budget.failureDetail(previousFailure);
            budget.note("source_witness", "workspace_declined; original_frontier_retained");
            return null;
        } finally {
            budget.phase(previousPhase);
        }
    }

    private static final class Expansion<K> implements AutoCloseable {

        private static final int MAX_FRAMES = 1024, MAX_EXPANSIONS = 4096, MAX_SOURCES = 16;
        private final GraphCompiler<K> compiler;
        private final K target;
        private final long amount, started = System.nanoTime();
        private final Map<K, Long> stock, seeds;
        private final Set<K> external;
        private final boolean preserve, forceCraft;
        private final PlanningBudget budget;
        private final Map<K, BigInteger> inventory = new LinkedHashMap<>();
        private final Map<String, GraphRecipe<K>> recipes = new LinkedHashMap<>();
        private final List<PlanStep> steps = new ArrayList<>();
        private final Deque<Frame> pending = new ArrayDeque<>();
        private final Set<K> ancestors = new HashSet<>();
        private Iterator<Map.Entry<K, Long>> seedGoals;
        private final Map<K, BigInteger> initial = new LinkedHashMap<>(), missing = new LinkedHashMap<>();
        private SummaryComputation<K> summary;
        private PlanVerification<K> verification;
        private PlanStep program;
        private Iterator<K> keys;
        private GraphPlan<K> candidate, result;
        private int expansions;
        private long memory;
        private boolean rootsDone, searched;
        private long witnessAllowance;
        private boolean materialExpansion, measureSummaryReplay, reusedFold;
        private long finishingWorkLowerBound, summaryReplayWork;

        Expansion(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock, Set<K> external,
                  Map<K, Long> seeds, boolean preserve, boolean forceCraft, PlanningBudget budget) {
            if (amount <= 0) throw new IllegalArgumentException("Non-positive crafting request");
            this.compiler = compiler;
            this.target = target;
            this.amount = amount;
            this.stock = stock;
            this.external = external;
            this.seeds = seeds;
            this.preserve = preserve;
            this.forceCraft = forceCraft;
            this.budget = budget;
            seedGoals = seeds.entrySet().iterator();
            push(target, BigInteger.valueOf(amount).add(BigInteger.valueOf(seeds.getOrDefault(target, 0L))));
        }

        private BigInteger available(K key) {
            return inventory.getOrDefault(key, forceCraft && target.equals(key) ? BigInteger.ZERO : PlanningInventory.exactAmount(stock, key));
        }

        private void reserve(long bytes) {
            budget.reserve(bytes);
            memory += bytes;
        }

        private void put(K key, BigInteger value) {
            if (value.signum() < 0) throw new IllegalStateException("Unfunded fallback operation");
            if (!inventory.containsKey(key)) reserve(128);
            inventory.put(key, value);
        }

        private void push(K key, BigInteger quantity) {
            reserve(256);
            pending.push(new Frame(key, quantity));
            ancestors.add(key);
        }

        private void pop() {
            ancestors.remove(pending.pop().key);
            budget.release(256);
            memory -= 256;
        }

        private void boundary(Frame frame) {
            put(frame.key, available(frame.key).max(frame.quantity));
            pop();
        }

        private boolean step() {
            // Each emitted positive step owns at least one distinct batch. The
            // two unshared folds each visit all its recipe ports; count indexing
            // and propagation visit its edge/leaf at least four more times. Even
            // ignoring all other work, the unshared pipeline cannot finish.
            // Keep its compact-goal routing even when completed trees can reuse
            // their folds. This never classifies feasibility or interrupts a
            // funded witness, nor claims the accelerated path could not finish.
            if (materialExpansion && summary == null && finishingWorkLowerBound > budget.remainingWork()) {
                budget.checkpoint();
                budget.note("fallback_refill", "compact_goals; emitted_steps=" + steps.size() +
                        "; unshared_finish_lower_bound=" + finishingWorkLowerBound + "; remaining=" + budget.remainingWork());
                return true;
            }
            budget.check();
            if (!searched) {
                searched = true;
                try (var search = new GraphFallbackSearch<>(compiler, target, amount, stock, external, seeds, forceCraft, budget)) {
                    if (witnessAllowance > 0 ? search.findByCost(witnessAllowance) : search.find()) {
                        reserve(256L * search.size());
                        search.copyTo(steps, recipes);
                        while (!pending.isEmpty()) pop();
                        rootsDone = true;
                        seedGoals = java.util.Collections.emptyIterator();
                    } else if (witnessAllowance > 0) return true;
                    else materialExpansion = true;
                }
                return false;
            }
            if (result != null) return true;
            if (verification != null) {
                if (verification.step()) result = candidate;
                return result != null;
            }
            if (summary != null) {
                if (keys == null) {
                    long before = measureSummaryReplay ? budget.threadSearchWork() : 0;
                    boolean complete = summary.step();
                    // The unshared word's second fold repeats exactly this
                    // work, with Expansion and verifier checkpoints per call.
                    // Use the calling thread's clock, never another worker's work.
                    if (measureSummaryReplay) summaryReplayWork += budget.threadSearchWork() - before + 2;
                    if (!complete) return false;
                    long verificationFloor = summaryReplayWork + 4L * steps.size();
                    if (measureSummaryReplay && verificationFloor > budget.remainingWork()) {
                        budget.note("fallback_refill", "compact_goals; measured_fold; emitted_steps=" + steps.size() +
                                "; unshared_finish_lower_bound=" + verificationFloor + "; remaining=" + budget.remainingWork());
                        return true;
                    }
                    var all = new java.util.LinkedHashSet<>(summary.result().keys());
                    all.add(target);
                    all.addAll(seeds.keySet());
                    keys = all.iterator();
                }
                if (keys.hasNext()) {
                    K key = keys.next();
                    BigInteger goal = BigInteger.valueOf(seeds.getOrDefault(key, 0L));
                    if (target.equals(key)) goal = goal.add(BigInteger.valueOf(amount));
                    BigInteger need = summary.result().required(key).max(goal.subtract(summary.result().delta(key)));
                    if (need.signum() > 0) {
                        reserve(192);
                        initial.put(key, need);
                        BigInteger have = forceCraft && target.equals(key) ? BigInteger.ZERO : PlanningInventory.exactAmount(stock, key);
                        if (!external.contains(key) && need.compareTo(have) > 0) missing.put(key, need.subtract(have));
                    }
                    return false;
                }
                candidate = new GraphPlan<>(target, amount, preserve, program, recipes, initial, seeds, missing,
                        missing.isEmpty() ? GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL : GraphPlan.Result.MISSING_INPUT,
                        budget.nodes(), System.nanoTime() - started);
                var funded = new GraphPlan<>(target, amount, preserve, program, recipes, initial, seeds, Map.of(),
                        GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, budget.nodes(), System.nanoTime() - started);
                // Small previews keep their work-scaled refinement scheduling.
                // Large material words already own this exact immutable fold;
                // no source/recipe/stock query is reused across requests.
                reusedFold = materialExpansion && steps.size() >= 256;
                // This funded material preview consumes only the checked
                // summary. Gross output/count metadata was never read here;
                // force/source guards and the final refill checks stay intact.
                verification = reusedFold ? PlanVerification.refillAfterWork(funded, budget, 0, program, summary.result()) :
                        new PlanVerification<>(funded, budget);
                return false;
            }
            if (!pending.isEmpty()) {
                pending.peek().step();
                return false;
            }
            if (!rootsDone) {
                // Reserve delivery before checking restoration goals. They must
                // not spend the same inventory a second time on another root.
                put(target, available(target).subtract(BigInteger.valueOf(amount)));
                rootsDone = true;
            }
            if (seedGoals.hasNext()) {
                var seed = seedGoals.next();
                if (!target.equals(seed.getKey())) push(seed.getKey(), BigInteger.valueOf(seed.getValue()));
                return false;
            }
            program = new PlanStep.Sequence(steps);
            // Enable the per-thread work clock only when finishing this tree is
            // a material fraction of the remaining quota. Tracking every later
            // budget charge slows small previews. This gate only decides whether
            // to measure; the actual admission decision still needs a lower bound.
            measureSummaryReplay = materialExpansion && finishingWorkLowerBound >= budget.remainingWork() / 4;
            summary = new SummaryComputation<>(program, recipes, budget);
            return false;
        }

        private final class Frame {

            final K key;
            final BigInteger quantity;
            GraphRecipe<K> recipe;
            BigInteger runs;
            Iterator<Map.Entry<K, Long>> entries;
            int source, phase;

            Frame(K key, BigInteger quantity) {
                this.key = key;
                this.quantity = quantity;
            }

            private BigInteger required(Map.Entry<K, Long> input) {
                long consumed = input.getValue(), returned = recipe.outputs().getOrDefault(input.getKey(), 0L);
                return BigInteger.valueOf(consumed).add(BigInteger.valueOf(Math.max(0, consumed - returned)).multiply(runs.subtract(BigInteger.ONE)));
            }

            void step() {
                if (phase == 0) {
                    if (available(key).compareTo(quantity) >= 0) {
                        pop();
                        return;
                    }
                    List<GraphRecipe<K>> choices = compiler.producers(key);
                    if (external.contains(key) || pending.size() >= MAX_FRAMES || expansions >= MAX_EXPANSIONS ||
                            source >= Math.min(MAX_SOURCES, choices.size())) {
                        boundary(this);
                        return;
                    }
                    var next = choices.get(source++);
                    // Cut before the closing recipe, so a lossy A -> B -> A
                    // round trip does not become the fallback's production plan.
                    for (K input : next.inputs().keySet()) {
                        budget.check();
                        if (ancestors.contains(input) || forceCraft && target.equals(input)) return;
                    }
                    recipe = next;
                    runs = CheckedAmounts.ceilDiv(quantity.subtract(available(key)), BigInteger.valueOf(recipe.outputs().get(key)));
                    entries = recipe.inputs().entrySet().iterator();
                    expansions++;
                    phase = 1;
                } else if (phase == 1) {
                    if (entries.hasNext()) {
                        var input = entries.next();
                        BigInteger need = required(input);
                        if (available(input.getKey()).compareTo(need) < 0) push(input.getKey(), need);
                    } else {
                        // A prerequisite may jointly produce the requested item.
                        // Do not insist on running the tentative parent source.
                        runs = CheckedAmounts.ceilDiv(quantity.subtract(available(key)), BigInteger.valueOf(recipe.outputs().get(key)));
                        if (runs.signum() == 0) {
                            pop();
                            return;
                        }
                        entries = recipe.inputs().entrySet().iterator();
                        phase = 2;
                    }
                } else if (phase == 2) {
                    if (entries.hasNext()) {
                        var input = entries.next();
                        K resource = input.getKey();
                        // Shared prerequisites can consume an earlier sibling's
                        // input. Refill it once; do not restart source search.
                        BigInteger have = available(resource).max(required(input));
                        BigInteger delta = BigInteger.valueOf(recipe.outputs().getOrDefault(resource, 0L))
                                .subtract(BigInteger.valueOf(input.getValue())).multiply(runs);
                        put(resource, have.add(delta));
                    } else {
                        entries = recipe.outputs().entrySet().iterator();
                        phase = 3;
                    }
                } else if (entries.hasNext()) {
                    var output = entries.next();
                    if (!recipe.inputs().containsKey(output.getKey()))
                        put(output.getKey(), available(output.getKey()).add(BigInteger.valueOf(output.getValue()).multiply(runs)));
                } else {
                    reserve(256);
                    recipes.put(recipe.id(), recipe);
                    steps.add(PlanStep.batch(recipe.id(), runs));
                    finishingWorkLowerBound += GraphFallback.unsharedFinishingWorkLowerBound(recipe);
                    pop();
                }
            }
        }

        @Override
        public void close() {
            if (summary != null) summary.close();
            if (verification != null) verification.close();
            budget.release(memory);
            memory = 0;
        }
    }
}
