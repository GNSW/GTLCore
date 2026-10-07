package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * IC3/PDR for monotone material coverability. A frame is a downward-closed
 * overapproximation, represented by excluded upward cones. Minimal predecessor
 * queries are exact for ordinary Petri transitions, including unbounded stocks.
 */
final class PdrCoverability<K> implements AutoCloseable {

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private static final class Obligation {

        final List<BigInteger> cube;
        final int level;
        final Obligation next;
        final PlanStep edge;
        int action;

        Obligation(List<BigInteger> cube, int level, Obligation next, PlanStep edge) {
            this.cube = cube;
            this.level = level;
            this.next = next;
            this.edge = edge;
        }
    }

    private final List<K> keys;
    private final List<GraphRecipe<K>> recipes;
    private final List<BackwardCoverability.Action<K>> actions = new ArrayList<>();
    private final List<List<List<BigInteger>>> frames = new ArrayList<>();
    private final Deque<Obligation> obligations = new ArrayDeque<>();
    private final List<BigInteger> initial, goal;
    private final PlanningBudget budget;
    private final long allowance;
    private long work, memory;
    private int depth = 1, pushFrame = 1, pushIndex, lemmas, generalized;
    private List<List<BigInteger>> pushing;
    private boolean propagating;
    private BackwardCoverability.Result result;
    private PlanStep witness;

    PdrCoverability(List<GraphRecipe<K>> recipes, List<K> keys, List<BigInteger> initial, List<BigInteger> goal,
                    Collection<BackwardCoverability.Action<K>> macros, PlanningBudget budget, long maximumWork) {
        this.recipes = recipes;
        this.keys = keys;
        this.initial = initial;
        this.goal = goal;
        this.budget = budget;
        allowance = Math.min(maximumWork, budget.remainingWork() / 16);
        if (keys.size() > 32 || recipes.size() > 64 || allowance < 1024 || recipes.stream().anyMatch(GraphRecipe::batchSensitiveInputs)) {
            result = BackwardCoverability.Result.UNKNOWN;
            return;
        }
        long bytes = 2048L + 512L * keys.size() * (recipes.size() + macros.size());
        if (!budget.tryReserve(bytes)) {
            result = BackwardCoverability.Result.UNKNOWN;
            return;
        }
        memory = bytes;
        actions.addAll(macros);
        Set<String> supplied = new HashSet<>();
        for (var macro : macros) if (macro.program() instanceof PlanStep.Batch batch && batch.runs() == 1) supplied.add(batch.recipe());
        for (var recipe : recipes) if (!supplied.contains(recipe.id()))
            actions.add(new BackwardCoverability.Action<>(new PlanStep.Batch(recipe.id(), 1), SequenceSummary.recipe(recipe)));
        frames.add(new ArrayList<>());
        frames.add(new ArrayList<>());
        obligations.push(new Obligation(goal, 1, null, null));
    }

    boolean step() {
        if (result != null) return true;
        try {
            charge();
            if (propagating) return push();
            if (obligations.isEmpty()) {
                propagating = true;
                pushFrame = 1;
                pushIndex = 0;
                pushing = null;
                frames.add(new ArrayList<>());
                return false;
            }
            Obligation active = obligations.peek();
            if (leq(active.cube, initial)) {
                List<PlanStep> path = new ArrayList<>();
                for (Obligation at = active; at.next != null; at = at.next) path.add(at.edge);
                witness = new PlanStep.Sequence(path);
                return finish(BackwardCoverability.Result.WITNESS);
            }
            if (excluded(active.cube, active.level)) {
                obligations.pop();
                return false;
            }
            if (active.level == 0) throw new IllegalStateException("PDR predecessor is neither initial nor excluded");
            if (active.action < actions.size()) {
                var action = actions.get(active.action);
                List<BigInteger> predecessor = predecessor(active.cube, action.summary());
                if (excluded(predecessor, active.level - 1)) active.action++;
                else {
                    reserve(128L + 96L * keys.size());
                    obligations.push(new Obligation(predecessor, active.level - 1, active, action.program()));
                }
                return false;
            }
            List<BigInteger> cube = generalize(active.cube, active.level - 1);
            for (int i = 1; i <= active.level; i++) add(i, cube);
            obligations.pop();
            return false;
        } catch (Stop stopped) {
            return finish(BackwardCoverability.Result.UNKNOWN);
        }
    }

    private boolean push() {
        if (pushFrame > depth) {
            if (++depth > 64) return finish(BackwardCoverability.Result.UNKNOWN);
            propagating = false;
            obligations.push(new Obligation(goal, depth, null, null));
            return false;
        }
        if (pushing == null) {
            pushing = List.copyOf(frames.get(pushFrame));
            pushIndex = 0;
        }
        if (pushIndex < pushing.size()) {
            var cube = pushing.get(pushIndex++);
            if (inductive(cube, pushFrame)) add(pushFrame + 1, cube);
            return false;
        }
        if (new HashSet<>(frames.get(pushFrame)).equals(new HashSet<>(frames.get(pushFrame + 1)))) {
            // Recheck the final inductive invariant using only the original
            // transitions. Macros or frame bookkeeping cannot certify closure.
            var proof = proof(frames.get(pushFrame));
            var verdict = ExecutionProof.verify(proof, Math.max(1024, allowance - work), units -> {
                work += units;
                budget.charge(units);
            });
            if (verdict == CountProof.Verdict.VERIFIED) {
                if (budget.proofJournal() != null) budget.proofJournal().add(proof);
                return finish(BackwardCoverability.Result.CLOSED);
            }
        }
        pushFrame++;
        pushing = null;
        return false;
    }

    private List<BigInteger> generalize(List<BigInteger> original, int relativeTo) {
        List<BigInteger> cube = new ArrayList<>(original);
        for (int i = 0; i < cube.size() && work < allowance * 3 / 4; i++) {
            charge();
            BigInteger old = cube.get(i);
            if (old.signum() == 0) continue;
            cube.set(i, BigInteger.ZERO);
            if (inductive(cube, relativeTo)) generalized++;
            else cube.set(i, old);
        }
        return List.copyOf(cube);
    }

    private boolean inductive(List<BigInteger> cube, int relativeTo) {
        if (leq(cube, initial)) return false;
        for (var action : actions) if (!excluded(predecessor(cube, action.summary()), relativeTo)) return false;
        return true;
    }

    private List<BigInteger> predecessor(List<BigInteger> after, SequenceSummary<K> action) {
        List<BigInteger> before = new ArrayList<>();
        for (int k = 0; k < keys.size(); k++) {
            charge();
            K key = keys.get(k);
            before.add(action.required(key).max(after.get(k).subtract(action.delta(key))));
        }
        return List.copyOf(before);
    }

    private boolean excluded(List<BigInteger> cube, int frame) {
        if (frame == 0) return !leq(cube, initial);
        for (var blocked : frames.get(frame)) if (leq(blocked, cube)) return true;
        return false;
    }

    private void add(int frame, List<BigInteger> cube) {
        if (excluded(cube, frame)) return;
        reserve(128L + 96L * keys.size());
        lemmas++;
        frames.get(frame).removeIf(old -> leq(cube, old));
        frames.get(frame).add(cube);
    }

    private boolean leq(List<BigInteger> a, List<BigInteger> b) {
        for (int i = 0; i < a.size(); i++) {
            charge();
            if (a.get(i).compareTo(b.get(i)) > 0) return false;
        }
        return true;
    }

    private ExecutionProof.Certificate proof(List<List<BigInteger>> invariant) {
        List<List<BigInteger>> inputs = new ArrayList<>(), outputs = new ArrayList<>();
        for (var recipe : recipes) {
            inputs.add(keys.stream().map(key -> BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L))).toList());
            outputs.add(keys.stream().map(key -> BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L))).toList());
        }
        return new ExecutionProof.Certificate("pdr:inductive_material_invariant", ExecutionProof.Kind.BACKWARD_CLOSURE,
                initial, goal, inputs, outputs, invariant, Set.of());
    }

    private boolean finish(BackwardCoverability.Result value) {
        result = value;
        budget.note("pdr_cover", "result=" + value + "; frames=" + depth + "; lemmas=" + lemmas + "; generalized=" + generalized + "; work=" + work);
        return true;
    }

    private void charge() {
        budget.check();
        if (++work > allowance) throw new Stop();
    }

    private void reserve(long bytes) {
        if (!budget.tryReserve(bytes)) throw new Stop();
        memory += bytes;
    }

    BackwardCoverability.Result result() {
        return result;
    }

    PlanStep witness() {
        return witness;
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
