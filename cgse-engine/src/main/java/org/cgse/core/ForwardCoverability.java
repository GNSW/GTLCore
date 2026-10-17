// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Bounded explicit coverability with persistent sets, visibility and the DFS cycle proviso. */
final class ForwardCoverability<K> implements AutoCloseable {

    private static final class Stop extends RuntimeException {
        final boolean workLimit;

        Stop(boolean workLimit) {
            super(null, null, false, false);
            this.workLimit = workLimit;
        }
    }

    private static final class Frame {

        final List<BigInteger> stock;
        final PlanStep edge;
        final Frame parent;
        BitSet todo;
        BitSet enabled = new BitSet();
        int action, coordinate;
        int goalCoordinate;
        boolean belowGoal;
        List<BigInteger> next;
        List<BigInteger> nextGroup;
        int groupCoordinate;

        Frame(List<BigInteger> stock, PlanStep edge, Frame parent) {
            this.stock = stock;
            this.edge = edge;
            this.parent = parent;
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
    private long[] requiredCoordinates;
    private int indexedAction, indexedCoordinate;
    private long nonIncreasing, decreasing;
    private int dominanceCoordinate = -1;
    private final Map<List<BigInteger>, BigInteger> covered = new HashMap<>();
    private final List<BigInteger> initialGroup = new ArrayList<>();
    private int initialCoordinate;
    private boolean indexed;
    private long dominated;
    private long allowance;
    private long work, memory, omitted, cycleExpansions;
    private long sliceStarted;
    private boolean retained, paused, closed;
    private ExecutionProof.Certificate certificate;
    private ExecutionProof.ForwardCheck checking;
    private BackwardCoverability.Result result;
    private PlanStep witness;

    ForwardCoverability(List<GraphRecipe<K>> recipes, List<K> keys, List<BigInteger> initial, List<BigInteger> goal,
                        List<BackwardCoverability.Action<K>> actions, PlanningBudget budget, long maximumWork) {
        this(recipes, keys, initial, goal, actions, budget, maximumWork, true);
    }

    ForwardCoverability(List<GraphRecipe<K>> recipes, List<K> keys, List<BigInteger> initial, List<BigInteger> goal,
                        List<BackwardCoverability.Action<K>> actions, PlanningBudget budget, long maximumWork,
                        boolean reduce) {
        this.recipes = recipes;
        this.keys = keys;
        this.initial = initial;
        this.goal = goal;
        this.actions = List.copyOf(actions);
        this.budget = budget;
        allowance = Math.min(maximumWork, budget.remainingWork() / 32);
        order = reduce ? new PartialOrder<>(actions.stream().map(BackwardCoverability.Action::summary).toList(), budget) : null;
        if (keys.size() > 32 || actions.size() > 64 || allowance < 1024 || recipes.stream().anyMatch(GraphRecipe::batchSensitiveInputs)) {
            result = BackwardCoverability.Result.UNKNOWN;
            return;
        }
        try {
            // The bounded search has at most 32 coordinates. Index requirements
            // once; zero requirements cannot disable a nonnegative state.
            long bytes = 64L + actions.size() * 8L + recipes.size() * 64L;
            if (!budget.tryReserve(bytes)) throw new Stop(false);
            memory += bytes;
            requiredCoordinates = new long[actions.size()];
            nonIncreasing = (1L << keys.size()) - 1;
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
            indexRequirements();
            indexInitial();
            if (stack.isEmpty()) return closeReachability();
            Frame frame = stack.peek();
            while (!frame.belowGoal && frame.goalCoordinate < keys.size()) {
                charge();
                if (frame.stock.get(frame.goalCoordinate).compareTo(goal.get(frame.goalCoordinate)) < 0) frame.belowGoal = true;
                frame.goalCoordinate++;
            }
            if (!frame.belowGoal) {
                var path = new ArrayList<PlanStep>();
                for (Frame next = frame; next != null; next = next.parent)
                    if (next.edge != null) path.add(next.edge);
                Collections.reverse(path);
                witness = new PlanStep.Sequence(path);
                return finish(BackwardCoverability.Result.WITNESS);
            }
            if (frame.todo == null) frame.todo = order == null ? enabled(frame) : persistent(frame.stock);
            int id = frame.todo.nextSetBit(0);
            if (id < 0) {
                stack.pop();
                active.remove(frame.stock);
                return false;
            }
            if (frame.next == null) frame.next = new ArrayList<>(keys.size());
            while (frame.next.size() < keys.size()) {
                charge();
                int k = frame.next.size();
                frame.next.add(frame.stock.get(k).add(actions.get(id).summary().delta(keys.get(k))));
            }
            var next = List.copyOf(frame.next);
            if (!visited.contains(next)) {
                if (dominanceCoordinate < 0) push(next, actions.get(id).program());
                else {
                    if (frame.nextGroup == null) frame.nextGroup = new ArrayList<>(keys.size() - 1);
                    while (frame.groupCoordinate < keys.size()) {
                        charge();
                        int k = frame.groupCoordinate++;
                        if (k != dominanceCoordinate) frame.nextGroup.add(next.get(k));
                    }
                    charge();
                    var maximum = covered.get(frame.nextGroup);
                    var value = next.get(dominanceCoordinate);
                    if (maximum != null && maximum.compareTo(value) >= 0) dominated++;
                    else {
                        push(next, actions.get(id).program());
                        covered.put(List.copyOf(frame.nextGroup), value);
                    }
                }
            }
            // Do not consume an edge until its successor has been retained.
            // A local stop midway through construction must not omit a firing.
            frame.todo.clear(id);
            frame.next = null;
            frame.nextGroup = null;
            frame.groupCoordinate = 0;
            return false;
        } catch (Stop stopped) {
            paused = retained && stopped.workLimit;
            return finish(BackwardCoverability.Result.UNKNOWN);
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private void indexRequirements() {
        while (indexedAction < actions.size()) {
            while (indexedCoordinate < keys.size()) {
                charge();
                if (actions.get(indexedAction).summary().required(keys.get(indexedCoordinate)).signum() > 0)
                    requiredCoordinates[indexedAction] |= 1L << indexedCoordinate;
                int sign = actions.get(indexedAction).summary().delta(keys.get(indexedCoordinate)).signum();
                if (sign > 0) nonIncreasing &= ~(1L << indexedCoordinate);
                else if (sign < 0) decreasing |= 1L << indexedCoordinate;
                indexedCoordinate++;
            }
            indexedCoordinate = 0;
            indexedAction++;
        }
        if (!indexed) {
            // Equal control states with more of one consumable resource cover
            // states with less. Keep actual reachable states (never fabricate
            // an accelerated witness); replay proves the downward closure.
            long candidates = order == null ? nonIncreasing & decreasing : 0;
            if (candidates != 0) dominanceCoordinate = Long.numberOfTrailingZeros(candidates);
            indexed = true;
        }
    }

    private void indexInitial() {
        if (dominanceCoordinate < 0 || initialCoordinate > keys.size()) return;
        while (initialCoordinate < keys.size()) {
            charge();
            int k = initialCoordinate++;
            if (k != dominanceCoordinate) initialGroup.add(initial.get(k));
        }
        charge();
        covered.put(List.copyOf(initialGroup), initial.get(dominanceCoordinate));
        initialCoordinate++;
    }

    private BitSet enabled(Frame frame) {
        while (frame.action < actions.size()) {
            int coordinate = Long.numberOfTrailingZeros(requiredCoordinates[frame.action] & (-1L << frame.coordinate));
            if (coordinate >= keys.size()) {
                charge();
                frame.enabled.set(frame.action++);
                frame.coordinate = 0;
                continue;
            }
            charge();
            if (frame.stock.get(coordinate).compareTo(actions.get(frame.action).summary().required(keys.get(coordinate))) < 0) {
                frame.action++;
                frame.coordinate = 0;
            } else frame.coordinate = coordinate + 1;
        }
        return frame.enabled;
    }

    private BitSet persistent(List<BigInteger> stock) {
        var enabled = new BitSet();
        for (int a = 0; a < actions.size(); a++) {
            boolean can = true;
            long required = requiredCoordinates[a];
            while (required != 0) {
                charge();
                int k = Long.numberOfTrailingZeros(required);
                if (stock.get(k).compareTo(actions.get(a).summary().required(keys.get(k))) < 0) {
                    can = false;
                    break;
                }
                required &= required - 1;
            }
            charge();
            if (can) enabled.set(a);
        }
        // Full traversal is useful when looking for an independently replayable
        // closed boundary. A persistent-set traversal need not visit that boundary.
        if (order == null) return enabled;
        var all = new BitSet();
        all.set(0, actions.size());
        var held = new LinkedHashMap<K, BigInteger>();
        for (int k = 0; k < keys.size(); k++) held.put(keys.get(k), stock.get(k));
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
        if (visited.size() >= 4096) throw new Stop(false);
        long bytes = 384L + keys.size() * 128L + (order == null ? 256L + keys.size() * 32L : 0);
        if (!budget.tryReserve(bytes)) throw new Stop(false);
        memory += bytes;
        visited.add(stock);
        active.add(stock);
        var frame = new Frame(stock, edge, stack.peek());
        // Short prefixes tend to preserve more consumable stock. In the common
        // unit-fuel case, breadth-first discovery reaches each control state
        // with its maximum fuel first, avoiding a ladder of improved revisits.
        if (dominanceCoordinate >= 0) stack.addLast(frame);
        else stack.push(frame);
    }

    private boolean closeReachability() {
        if (checking == null) {
            var in = new ArrayList<List<BigInteger>>();
            var out = new ArrayList<List<BigInteger>>();
            for (var recipe : recipes) {
                in.add(keys.stream().map(key -> BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L))).toList());
                out.add(keys.stream().map(key -> BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L))).toList());
            }
            certificate = new ExecutionProof.Certificate(order == null ? "forward_original_closure" : "forward_por_original_closure",
                    dominanceCoordinate < 0 ? ExecutionProof.Kind.FORWARD_BOUNDARY : ExecutionProof.Kind.FORWARD_DOMINANCE,
                    initial, goal, in, out, List.copyOf(visited), Set.of());
            checking = new ExecutionProof.ForwardCheck(certificate);
        }
        // A reduced traversal alone is never an absence certificate. Require
        // explicit closure under all original transitions before exporting DEAD.
        long remaining = allowance - work - (budget.threadWork() - sliceStarted);
        if (remaining <= 0) throw new Stop(true);
        if (!checking.step(Math.min(1024, remaining), budget::charge)) return false;
        if (checking.result() != CountProof.Verdict.VERIFIED)
            return finish(BackwardCoverability.Result.UNKNOWN);
        if (budget.proofJournal() != null) budget.proofJournal().add(certificate);
        return finish(BackwardCoverability.Result.CLOSED);
    }

    private void charge() {
        if (work + budget.threadWork() - sliceStarted >= allowance) throw new Stop(true);
        budget.check();
    }

    private boolean finish(BackwardCoverability.Result value) {
        result = value;
        budget.note(order == null ? "forward_original" : "forward_por", "result=" + value + "; states=" + visited.size() + "; dominated=" + dominated + "; omitted=" + omitted + "; cycle_expansions=" + cycleExpansions + "; work=" + work + "; retained=" + paused + "; checking=" + (checking != null));
        return true;
    }

    BackwardCoverability.Result result() {
        return result;
    }

    PlanStep witness() {
        return witness;
    }

    ForwardCoverability<K> retained() {
        if (order != null) throw new IllegalStateException("Only full original-state traversal retains its frontier");
        retained = true;
        return this;
    }

    boolean paused() { return paused && !closed; }

    /** Only the independently checked, already finite boundary can earn a final grant. */
    long proofCompletionWork() {
        if (closed || checking == null || !stack.isEmpty()) return 0;
        // Conservative checker cost, bounded by the search's admitted dimensions
        // and state cap. This is a scheduling estimate, never proof evidence.
        return 16L + (2L + 2L * recipes.size()) * keys.size() +
                (long) visited.size() * (3L * keys.size() + 8L + recipes.size() * (3L * keys.size() + 5L));
    }

    void resume(long additionalWork) {
        if (!paused() || additionalWork <= 0) throw new IllegalStateException("No retained forward frontier");
        long extra = Math.min(additionalWork, budget.remainingWork());
        allowance = work + Math.min(extra, Long.MAX_VALUE - work);
        paused = false;
        result = null;
        budget.note("forward_resume", "states=" + visited.size() + "; work=" + work + "; allowance=" + allowance + "; checking=" + (checking != null));
    }

    @Override
    public void close() {
        closed = true;
        certificate = null;
        checking = null;
        requiredCoordinates = null;
        covered.clear();
        initialGroup.clear();
        stack.clear();
        visited.clear();
        active.clear();
        budget.release(memory);
        memory = 0;
    }
}
