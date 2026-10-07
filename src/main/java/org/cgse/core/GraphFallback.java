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

    public static <K> GraphPlan<K> plan(GraphCompiler<K> compiler, K target, long amount,
                                        Map<K, Long> stock, Set<K> external, Map<K, Long> seeds,
                                        boolean preserve, boolean forceCraft, PlanningBudget budget) {
        try (var work = new Expansion<>(compiler, target, amount, stock, external, seeds, preserve, forceCraft, budget)) {
            while (!work.step()) { /* All traversal, arithmetic and verification share the fallback budget. */ }
            return work.result;
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
            return inventory.getOrDefault(key, BigInteger.valueOf(forceCraft && target.equals(key) ? 0 : stock.getOrDefault(key, 0L)));
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
                    if (!summary.step()) return false;
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
                        BigInteger have = BigInteger.valueOf(forceCraft && target.equals(key) ? 0 : stock.getOrDefault(key, 0L));
                        if (!external.contains(key) && need.compareTo(have) > 0) missing.put(key, need.subtract(have));
                    }
                    return false;
                }
                candidate = new GraphPlan<>(target, amount, preserve, program, recipes, initial, seeds, missing,
                        missing.isEmpty() ? GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL : GraphPlan.Result.MISSING_INPUT,
                        budget.nodes(), System.nanoTime() - started);
                var funded = new GraphPlan<>(target, amount, preserve, program, recipes, initial, seeds, Map.of(),
                        GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, budget.nodes(), System.nanoTime() - started);
                verification = new PlanVerification<>(funded, budget);
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
