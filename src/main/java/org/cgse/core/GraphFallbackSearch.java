// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Transactional ordinary-recipe witness search; exhaustion proves nothing.
 * Batch-size probes are bounded heuristics, not an integer count search.
 */
final class GraphFallbackSearch<K> implements AutoCloseable {

    private static final int MAX_DEPTH = 1024, MAX_CHOICES = 4096;
    private static final long MAX_MEMORY = 4L << 20;
    private final GraphCompiler<K> compiler;
    private final K target;
    private final Map<K, Long> stock;
    private final Map<K, Long> seeds;
    private final long amount;
    private final Set<K> external;
    private final boolean force;
    private final PlanningBudget budget;
    private final long started, allowance;
    private final Map<K, BigInteger> inventory = new LinkedHashMap<>();
    private final List<Change> changes = new ArrayList<>();
    private final Deque<Choice> choices = new ArrayDeque<>();
    private final List<Firing> firings = new ArrayList<>();
    private Task pending;
    private long memory, workLimit;
    private int attempts;
    private boolean partial;
    private GraphFallbackSources<K> sourceOrder;
    private boolean byCost;

    GraphFallbackSearch(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
                        Set<K> external, Map<K, Long> seeds, boolean force, PlanningBudget budget) {
        this.compiler = compiler;
        this.target = target;
        this.stock = stock;
        this.seeds = seeds;
        this.amount = amount;
        this.external = external;
        this.force = force;
        this.budget = budget;
        started = budget.nodes();
        allowance = Math.min(43_690, budget.remainingWork() / 3);
    }

    boolean find() {
        // Prefer a later source that funds the whole request before exploring
        // mixtures of an earlier, resource-starved source. Both passes share
        // the same total work/choice limits and release their failed workspace.
        workLimit = allowance / 2;
        if (search()) return true;
        reset();
        partial = true;
        workLimit = allowance;
        if (search()) return true;
        // Preserve both original priority passes and their work allowance.
        // Only failed searches use relaxed reachability and stable rankings.
        reset();
        sourceOrder = GraphFallbackSources.create(compiler, stock, external, target, force, budget);
        if (sourceOrder == null) return false;
        for (int pass = 0; pass < 2; pass++) {
            long room = budget.remainingWork() - 24_576;
            if (room < 4096) return false;
            workLimit = budget.nodes() - started + Math.min(24_576, room);
            attempts = 0;
            byCost = pass == 1;
            if (search()) return true;
            reset();
        }
        return false;
    }

    /** One optional funded-witness attempt, charging all work to the caller. */
    boolean findByCost(long maximumWork) {
        budget.checkpoint();
        if (maximumWork <= 0) return false;
        workLimit = Math.min(maximumWork, budget.remainingWork());
        sourceOrder = GraphFallbackSources.create(compiler, stock, external, target, force, budget);
        if (sourceOrder == null || budget.nodes() - started >= workLimit) return false;
        byCost = true;
        return search();
    }

    private void reset() {
        budget.release(memory);
        memory = 0;
        inventory.clear();
        changes.clear();
        choices.clear();
        firings.clear();
        pending = null;
    }

    private boolean search() {
        try {
            // Root reservations coexist, just like all inputs of one firing.
            // Restoring another seed must not consume an already promised delivery.
            for (var seed : seeds.entrySet()) if (!target.equals(seed.getKey())) {
                check();
                pending = new Task(seed.getKey(), BigInteger.valueOf(seed.getValue()), null, pending, true, 0);
            }
            pending = new Task(target, BigInteger.valueOf(amount).add(BigInteger.valueOf(seeds.getOrDefault(target, 0L))), null, pending, true, 0);
            while (pending != null) {
                check();
                Task task = pending;
                if (task.recipe != null) {
                    if (fire(task)) pending = task.next;
                } else if (available(task.key).compareTo(task.quantity) >= 0) {
                    if (task.claim) put(task.key, available(task.key).subtract(task.quantity));
                    pending = task.next;
                } else if (external.contains(task.key)) {
                    // The summary records the exact external supply consumed.
                    put(task.key, task.claim ? BigInteger.ZERO : task.quantity);
                    pending = task.next;
                } else if (contains(task.path, task.key) || task.depth >= MAX_DEPTH) {
                    if (!backtrack()) return false;
                } else {
                    reserve(192);
                    var choice = new Choice(task);
                    choices.push(choice);
                    if (!choose(choice) && !backtrack()) return false;
                }
            }
            return true;
        } catch (Stopped ignored) {
            return false;
        }
    }

    private void check() {
        if (budget.nodes() - started >= workLimit) throw Stopped.INSTANCE;
        budget.check();
    }

    private void reserve(long bytes) {
        if (memory + bytes > MAX_MEMORY || !budget.tryReserve(bytes)) throw Stopped.INSTANCE;
        memory += bytes;
    }

    private BigInteger available(K key) {
        return inventory.getOrDefault(key, BigInteger.valueOf(force && target.equals(key) ? 0 : stock.getOrDefault(key, 0L)));
    }

    private void put(K key, BigInteger value) {
        if (value.signum() < 0) throw new IllegalStateException("Unfunded fallback search");
        reserve(160);
        changes.add(new Change(key, inventory.put(key, value)));
    }

    private boolean contains(Task path, K key) {
        for (Task parent = path; parent != null; parent = parent.path) {
            check();
            if (key.equals(parent.key)) return true;
        }
        return false;
    }

    private BigInteger required(GraphRecipe<K> recipe, K key, long amount, BigInteger runs) {
        long loss = Math.max(0, amount - recipe.outputs().getOrDefault(key, 0L));
        return BigInteger.valueOf(amount).add(BigInteger.valueOf(loss).multiply(runs.subtract(BigInteger.ONE)));
    }

    private boolean choose(Choice choice) {
        while (true) {
            check();
            if (++attempts > (partial ? MAX_CHOICES : MAX_CHOICES / 2)) throw Stopped.INSTANCE;
            if (choice.hint != null) {
                choice.runs = choice.hint;
                choice.hint = null;
            }
            if (choice.runs == null || choice.runs.signum() == 0) {
                List<GraphRecipe<K>> sources = sourceOrder == null ? compiler.producers(choice.task.key) : sourceOrder.sources(choice.task.key, byCost);
                if (choice.source == sources.size()) return false;
                choice.recipe = sources.get(choice.source++);
                long gain = choice.recipe.outputs().getOrDefault(choice.task.key, 0L) - choice.recipe.inputs().getOrDefault(choice.task.key, 0L);
                if (gain <= 0) continue;
                choice.runs = CheckedAmounts.ceilDiv(choice.task.quantity.subtract(available(choice.task.key)), BigInteger.valueOf(gain));
                BigInteger requested = choice.runs;
                // A terminal input gives an exact batch cap without probing one
                // unit at a time. Returned containers/tools fund successive runs.
                for (var input : choice.recipe.inputs().entrySet()) {
                    check();
                    K key = input.getKey();
                    if (external.contains(key) || !compiler.producers(key).isEmpty() &&
                            !key.equals(choice.task.key) && !contains(choice.task.path, key))
                        continue;
                    BigInteger have = available(key), first = BigInteger.valueOf(input.getValue());
                    if (have.compareTo(first) < 0) {
                        choice.runs = BigInteger.ZERO;
                        break;
                    }
                    long loss = input.getValue() - choice.recipe.outputs().getOrDefault(key, 0L);
                    if (loss > 0) choice.runs = choice.runs.min(have.subtract(first).divide(BigInteger.valueOf(loss)).add(BigInteger.ONE));
                }
                if (!partial && choice.runs.compareTo(requested) < 0) choice.runs = BigInteger.ZERO;
                if (choice.runs.signum() == 0) continue;
            }
            BigInteger runs = choice.runs;
            choice.attempted = runs;
            // If an indirect prerequisite cannot fund the full batch, try a
            // smaller chunk and leave the remainder open to other sources.
            choice.runs = partial ? runs.shiftRight(1) : BigInteger.ZERO;
            Task continuation = new Task(choice.task.key, choice.task.quantity, choice.task.path,
                    choice.task.next, choice.task.claim, choice.source);
            Task next = new Task(choice.recipe, runs, choice.task, continuation);
            var inputs = new ArrayList<>(choice.recipe.inputs().entrySet());
            for (int i = inputs.size() - 1; i >= 0; i--) {
                check();
                var input = inputs.get(i);
                next = new Task(input.getKey(), required(choice.recipe, input.getKey(), input.getValue(), runs), choice.task, next, false, 0);
            }
            pending = next;
            return true;
        }
    }

    private boolean backtrack() {
        rememberPartial();
        while (!choices.isEmpty()) {
            check();
            Choice choice = choices.peek();
            while (changes.size() > choice.undo) {
                check();
                var change = changes.remove(changes.size() - 1);
                if (change.previous == null) inventory.remove(change.key);
                else inventory.put(change.key, change.previous);
            }
            while (firings.size() > choice.steps) {
                check();
                firings.remove(firings.size() - 1);
            }
            if (choose(choice)) return true;
            choices.pop();
        }
        return false;
    }

    private void rememberPartial() {
        if (!partial) return;
        Task failed = pending;
        if (failed.path == null) return;
        for (Choice choice : choices) {
            check();
            if (choice.task != failed.path || choice.attempted == null) continue;
            long input = choice.recipe.inputs().getOrDefault(failed.key, 0L);
            long loss = input - choice.recipe.outputs().getOrDefault(failed.key, 0L);
            BigInteger have = available(failed.key);
            if (loss > 0 && have.compareTo(BigInteger.valueOf(input)) >= 0) {
                BigInteger supported = have.subtract(BigInteger.valueOf(input)).divide(BigInteger.valueOf(loss)).add(BigInteger.ONE);
                if (supported.compareTo(choice.attempted) < 0)
                    choice.hint = choice.hint == null ? supported : choice.hint.max(supported);
            }
            return;
        }
    }

    private boolean fire(Task task) {
        // A prerequisite may have jointly supplied the original goal already.
        if (available(task.path.key).compareTo(task.path.quantity) >= 0) return true;
        // A later prerequisite may consume an earlier one, or borrow a tool
        // that the parent will consume. Recheck the whole firing and actually
        // replenish deficits; never synthesize stock or lock a tool prematurely.
        for (var input : task.recipe.inputs().entrySet()) {
            check();
            BigInteger need = required(task.recipe, input.getKey(), input.getValue(), task.quantity);
            if (available(input.getKey()).compareTo(need) < 0) {
                pending = new Task(input.getKey(), need, task.path, task, false, 0);
                return false;
            }
        }
        for (var input : task.recipe.inputs().entrySet()) {
            check();
            BigInteger change = BigInteger.valueOf(task.recipe.outputs().getOrDefault(input.getKey(), 0L))
                    .subtract(BigInteger.valueOf(input.getValue())).multiply(task.quantity);
            put(input.getKey(), available(input.getKey()).add(change));
        }
        for (var output : task.recipe.outputs().entrySet()) if (!task.recipe.inputs().containsKey(output.getKey())) {
            check();
            put(output.getKey(), available(output.getKey()).add(BigInteger.valueOf(output.getValue()).multiply(task.quantity)));
        }
        reserve(128);
        firings.add(new Firing(task.recipe, task.quantity));
        return true;
    }

    void copyTo(List<PlanStep> steps, Map<String, GraphRecipe<K>> recipes) {
        for (Firing firing : firings) {
            budget.check();
            steps.add(PlanStep.batch(firing.recipe.id(), firing.runs));
            recipes.put(firing.recipe.id(), firing.recipe);
        }
    }

    int size() {
        return firings.size();
    }

    private final class Task {

        final K key;
        final BigInteger quantity;
        final GraphRecipe<K> recipe;
        final Task path, next;
        final int depth, source;
        final boolean claim;

        Task(K key, BigInteger quantity, Task path, Task next, boolean claim, int source) {
            reserve(128);
            this.key = key;
            this.quantity = quantity;
            this.recipe = null;
            this.path = path;
            this.next = next;
            this.claim = claim;
            this.source = source;
            depth = path == null ? 0 : path.depth + 1;
        }

        Task(GraphRecipe<K> recipe, BigInteger runs, Task path, Task next) {
            reserve(128);
            this.key = null;
            this.quantity = runs;
            this.recipe = recipe;
            this.path = path;
            this.next = next;
            claim = false;
            source = 0;
            depth = 0;
        }
    }

    private final class Choice {

        final Task task;
        final int undo = changes.size(), steps = firings.size();
        int source;
        GraphRecipe<K> recipe;
        BigInteger runs, attempted, hint;

        Choice(Task task) {
            this.task = task;
            source = task.source;
        }
    }

    private final class Change {

        final K key;
        final BigInteger previous;

        Change(K key, BigInteger previous) {
            this.key = key;
            this.previous = previous;
        }
    }

    private final class Firing {

        final GraphRecipe<K> recipe;
        final BigInteger runs;

        Firing(GraphRecipe<K> recipe, BigInteger runs) {
            this.recipe = recipe;
            this.runs = runs;
        }
    }

    private static final class Stopped extends RuntimeException {

        private static final Stopped INSTANCE = new Stopped();

        private Stopped() {
            super(null, null, false, false);
        }
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
        if (sourceOrder != null) sourceOrder.close();
    }
}
