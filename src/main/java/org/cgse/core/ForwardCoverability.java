// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Bounded explicit coverability with persistent sets, visibility and the DFS cycle proviso. */
final class ForwardCoverability<K> implements AutoCloseable {

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private static final class Frame {

        final List<BigInteger> stock;
        final PlanStep edge;
        BitSet todo;

        Frame(List<BigInteger> stock, PlanStep edge) {
            this.stock = stock;
            this.edge = edge;
        }
    }

    private final List<K> keys;
    private final List<GraphRecipe<K>> recipes;
    private final List<BackwardCoverability.Action<K>> actions;
    private final List<BigInteger> initial, goal;
    private final Deque<Frame> stack = new ArrayDeque<>();
    private final Set<List<BigInteger>> visited = new LinkedHashSet<>(), active = new HashSet<>();
    private final PlanningBudget budget;
    private final PartialOrder<K> order;
    private final long allowance;
    private long work, memory, omitted, cycleExpansions;
    private long sliceStarted;
    private BackwardCoverability.Result result;
    private PlanStep witness;

    ForwardCoverability(List<GraphRecipe<K>> recipes, List<K> keys, List<BigInteger> initial, List<BigInteger> goal,
                        List<BackwardCoverability.Action<K>> actions, PlanningBudget budget, long maximumWork) {
        this.recipes = recipes;
        this.keys = keys;
        this.initial = initial;
        this.goal = goal;
        this.actions = List.copyOf(actions);
        this.budget = budget;
        allowance = Math.min(maximumWork, budget.remainingWork() / 32);
        order = new PartialOrder<>(actions.stream().map(BackwardCoverability.Action::summary).toList(), budget);
        if (keys.size() > 32 || actions.size() > 64 || allowance < 1024 || recipes.stream().anyMatch(GraphRecipe::batchSensitiveInputs)) {
            result = BackwardCoverability.Result.UNKNOWN;
            return;
        }
        try {
            push(initial, null);
        } catch (Stop stopped) {
            result = BackwardCoverability.Result.UNKNOWN;
        }
    }

    boolean step() {
        if (result != null) return true;
        long before = budget.threadWork();
        sliceStarted = before;
        try {
            charge();
            if (stack.isEmpty()) return closeReachability();
            Frame frame = stack.peek();
            if (covers(frame.stock, goal)) {
                var path = new ArrayList<PlanStep>();
                var frames = stack.descendingIterator();
                while (frames.hasNext()) {
                    var next = frames.next();
                    if (next.edge != null) path.add(next.edge);
                }
                witness = new PlanStep.Sequence(path);
                return finish(BackwardCoverability.Result.WITNESS);
            }
            if (frame.todo == null) frame.todo = persistent(frame.stock);
            int id = frame.todo.nextSetBit(0);
            if (id < 0) {
                stack.pop();
                active.remove(frame.stock);
                return false;
            }
            frame.todo.clear(id);
            var next = successor(frame.stock, actions.get(id).summary());
            if (!visited.contains(next)) push(next, actions.get(id).program());
            return false;
        } catch (Stop stopped) {
            return finish(BackwardCoverability.Result.UNKNOWN);
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private BitSet persistent(List<BigInteger> stock) {
        var enabled = new BitSet();
        var all = new BitSet();
        all.set(0, actions.size());
        var held = new LinkedHashMap<K, BigInteger>();
        for (int k = 0; k < keys.size(); k++) held.put(keys.get(k), stock.get(k));
        for (int a = 0; a < actions.size(); a++) {
            boolean can = true;
            for (int k = 0; k < keys.size(); k++) {
                charge();
                if (stock.get(k).compareTo(actions.get(a).summary().required(keys.get(k))) < 0) {
                    can = false;
                    break;
                }
            }
            if (can) enabled.set(a);
        }
        BitSet reduced = order.persistent(all, enabled, held, Set.of());
        if (reduced.isEmpty() || reduced.equals(enabled)) return enabled;
        for (int a = reduced.nextSetBit(0); a >= 0; a = reduced.nextSetBit(a + 1)) {
            // Invisible transitions preserve the coverability predicate.
            for (int k = 0; k < keys.size(); k++) {
                charge();
                if (goal.get(k).signum() > 0 && actions.get(a).summary().delta(keys.get(k)).signum() != 0) return enabled;
            }
            if (active.contains(successor(stock, actions.get(a).summary()))) {
                cycleExpansions++;
                return enabled;
            }
        }
        omitted += enabled.cardinality() - reduced.cardinality();
        return reduced;
    }

    private List<BigInteger> successor(List<BigInteger> stock, SequenceSummary<K> action) {
        var next = new ArrayList<BigInteger>();
        for (int k = 0; k < keys.size(); k++) {
            charge();
            next.add(stock.get(k).add(action.delta(keys.get(k))));
        }
        return List.copyOf(next);
    }

    private void push(List<BigInteger> stock, PlanStep edge) {
        if (visited.size() >= 4096) throw new Stop();
        long bytes = 384L + keys.size() * 128L;
        if (!budget.tryReserve(bytes)) throw new Stop();
        memory += bytes;
        visited.add(stock);
        active.add(stock);
        stack.push(new Frame(stock, edge));
    }

    private boolean covers(List<BigInteger> stock, List<BigInteger> required) {
        for (int k = 0; k < keys.size(); k++) {
            charge();
            if (stock.get(k).compareTo(required.get(k)) < 0) return false;
        }
        return true;
    }

    private boolean closeReachability() {
        var in = new ArrayList<List<BigInteger>>();
        var out = new ArrayList<List<BigInteger>>();
        for (var recipe : recipes) {
            in.add(keys.stream().map(key -> BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L))).toList());
            out.add(keys.stream().map(key -> BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L))).toList());
        }
        var proof = new ExecutionProof.Certificate("forward_por_original_closure", ExecutionProof.Kind.FORWARD_BOUNDARY, initial, goal, in, out, List.copyOf(visited), Set.of());
        // A reduced traversal alone is never an absence certificate. Require
        // explicit closure under all original transitions before exporting DEAD.
        if (ExecutionProof.verify(proof, Math.max(1, allowance - work), budget::charge) != CountProof.Verdict.VERIFIED) return finish(BackwardCoverability.Result.UNKNOWN);
        if (budget.proofJournal() != null) budget.proofJournal().add(proof);
        return finish(BackwardCoverability.Result.CLOSED);
    }

    private void charge() {
        budget.check();
        if (work + budget.threadWork() - sliceStarted >= allowance) throw new Stop();
    }

    private boolean finish(BackwardCoverability.Result value) {
        result = value;
        budget.note("forward_por", "result=" + value + "; states=" + visited.size() + "; omitted=" + omitted + "; cycle_expansions=" + cycleExpansions + "; work=" + work);
        return true;
    }

    BackwardCoverability.Result result() {
        return result;
    }

    PlanStep witness() {
        return witness;
    }

    @Override
    public void close() {
        stack.clear();
        visited.clear();
        active.clear();
        budget.release(memory);
        memory = 0;
    }
}
