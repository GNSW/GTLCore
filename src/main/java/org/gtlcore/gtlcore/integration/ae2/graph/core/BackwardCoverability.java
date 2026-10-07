package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** Exact backward antichain; budgets return UNKNOWN, a checked fixpoint proves absence. */
final class BackwardCoverability<K> implements AutoCloseable {

    enum Result {
        WITNESS,
        CLOSED,
        UNKNOWN
    }

    record Action<K>(PlanStep program, SequenceSummary<K> summary) {}

    private record Node(List<BigInteger> required, Node next, PlanStep edge, int depth) {}

    private static final class LocalLimit extends RuntimeException {

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private final List<K> keys;
    private final List<GraphRecipe<K>> recipes;
    private final List<Action<K>> actions = new ArrayList<>();
    private final List<Node> basis = new ArrayList<>();
    private final Deque<Node> pending = new ArrayDeque<>();
    private final PlanningBudget budget;
    private final List<BigInteger> initial, goal;
    private final Map<K, Integer> positions = new HashMap<>();
    private final BitSet unbounded = new BitSet();
    private final long allowance;
    private long work, stepStarted;
    private Node active;
    private int cursor, verifyNode, verifyRecipe;
    private boolean verifying;
    private boolean startupDone, startupChanged, startupVerifying;
    private int startupCursor;
    private long memory, expanded;
    private Result result;
    private PlanStep witness;
    private PdrCoverability<K> pdr;
    private boolean pdrTried;
    private ForwardCoverability<K> forward;
    private boolean forwardTried;
    private CountInduction<K> induction;
    private boolean inductionTried;

    BackwardCoverability(List<GraphRecipe<K>> recipes, Map<K, BigInteger> stock, Map<K, BigInteger> goals,
                         Set<K> external, Collection<Action<K>> macros, PlanningBudget budget, long allowance) {
        this.recipes = List.copyOf(recipes);
        this.budget = budget;
        this.allowance = allowance;
        stepStarted = budget.threadWork();
        Set<K> all = new LinkedHashSet<>(stock.keySet());
        all.addAll(goals.keySet());
        for (var recipe : recipes) {
            all.addAll(recipe.inputs().keySet());
            all.addAll(recipe.outputs().keySet());
        }
        all.removeAll(external);
        keys = List.copyOf(all);
        for (int i = 0; i < keys.size(); i++) positions.put(keys.get(i), i);
        initial = keys.stream().map(key -> stock.getOrDefault(key, BigInteger.ZERO)).toList();
        goal = keys.stream().map(key -> goals.getOrDefault(key, BigInteger.ZERO)).toList();
        if (recipes.stream().anyMatch(GraphRecipe::batchSensitiveInputs)) {
            result = Result.UNKNOWN;
            return;
        }
        actions.addAll(macros);
        for (var recipe : recipes) actions.add(new Action<>(new PlanStep.Batch(recipe.id(), 1), SequenceSummary.recipe(recipe)));
        try {
            remember(new Node(goal, null, null, 0));
        } catch (LocalLimit limit) {
            finish(Result.UNKNOWN);
        } finally {
            work += budget.threadWork() - stepStarted;
        }
    }

    boolean step() {
        stepStarted = budget.threadWork();
        try {
            return advance();
        } finally {
            work += budget.threadWork() - stepStarted;
        }
    }

    private long usedWork() {
        return work + budget.threadWork() - stepStarted;
    }

    private boolean advance() {
        if (result != null) return true;
        if (forward != null) {
            if (!forward.step()) return false;
            Result outcome = forward.result();
            PlanStep path = forward.witness();
            forward.close();
            forward = null;
            if (outcome == Result.UNKNOWN) return finish(Result.UNKNOWN);
            result = outcome;
            witness = path;
            close();
            return true;
        }
        if (pdr != null) {
            if (!pdr.step()) return false;
            Result outcome = pdr.result();
            witness = pdr.witness();
            pdr.close();
            pdr = null;
            if (outcome == Result.UNKNOWN) return finish(Result.UNKNOWN);
            result = outcome;
            close();
            return true;
        }
        if (induction != null) {
            if (!induction.step()) return false;
            result = induction.result();
            witness = induction.witness();
            close();
            return true;
        }
        try {
            charge();
            if (!startupDone) return startupStep();
            if (verifying) return verifyStep();
            if (active == null) {
                if (pending.isEmpty()) {
                    // Check the closure again with ORIGINAL input/output arcs.
                    // A missed predecessor, stale antichain label or unsafe
                    // macro cannot turn a search failure into an absence proof.
                    if (!covered(goal)) throw new IllegalStateException("Missing coverability goal");
                    verifying = true;
                    return false;
                }
                active = pending.removeFirst();
                if (!basis.contains(active)) {
                    active = null;
                    return false;
                }
                if (leq(active.required(), initial)) {
                    var path = new ArrayList<PlanStep>();
                    for (Node n = active; n.next() != null; n = n.next()) path.add(n.edge());
                    witness = new PlanStep.Sequence(path);
                    return finish(Result.WITNESS);
                }
                expanded++;
                cursor = 0;
            }
            if (cursor == actions.size()) {
                active = null;
                return false;
            }
            var action = actions.get(cursor++);
            // Long demand is represented by a calculated repetition as well as
            // the original edge. The latter remains, so this shortcut cannot
            // remove an alternative start or interleaving.
            BigInteger times = BigInteger.ONE;
            for (int k = 0; k < keys.size(); k++) {
                charge();
                BigInteger gain = action.summary().delta(keys.get(k));
                if (gain.signum() > 0) times = times.max(CheckedAmounts.ceilDiv(active.required().get(k).subtract(initial.get(k)), gain));
            }
            if (times.compareTo(BigInteger.ONE) > 0) {
                var repeated = repeat(action.summary(), times);
                remember(new Node(predecessor(active.required(), repeated), active, PlanStep.repeat(action.program(), times), active.depth() + 1));
            }
            remember(new Node(predecessor(active.required(), action.summary()), active, action.program(), active.depth() + 1));
            // A funded predecessor is already a concrete executable witness.
            // Do not make it wait behind exponentially many unrelated prefixes.
            if (!pending.isEmpty() && leq(pending.peekFirst().required(), initial)) active = null;
            return false;
        } catch (LocalLimit limit) {
            return finish(Result.UNKNOWN);
        }
    }

    private boolean startupStep() {
        if (startupCursor < recipes.size()) {
            var recipe = recipes.get(startupCursor++);
            for (var input : recipe.inputs().entrySet()) {
                charge();
                Integer key = positions.get(input.getKey());
                if (key != null && !unbounded.get(key) && initial.get(key).compareTo(BigInteger.valueOf(input.getValue())) < 0) return false;
            }
            for (K output : recipe.outputs().keySet()) {
                charge();
                Integer key = positions.get(output);
                if (key != null && !unbounded.get(key)) {
                    if (startupVerifying) throw new IllegalStateException("Invalid startup invariant");
                    unbounded.set(key);
                    startupChanged = true;
                }
            }
            return false;
        }
        if (startupVerifying) return finish(Result.CLOSED);
        startupCursor = 0;
        if (startupChanged) {
            startupChanged = false;
            return false;
        }
        for (int k = 0; k < keys.size(); k++) if (!unbounded.get(k) && initial.get(k).compareTo(goal.get(k)) < 0) {
            // No reachable marking can exceed this finite component of the
            // optimistic box. Recheck every original transition before using it.
            startupVerifying = true;
            return false;
        }
        startupDone = true;
        return false;
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

    private boolean verifyStep() {
        if (verifyNode == basis.size()) return finish(Result.CLOSED);
        Node node = basis.get(verifyNode);
        if (leq(node.required(), initial)) throw new IllegalStateException("Invalid coverability exclusion");
        if (verifyRecipe == recipes.size()) {
            verifyNode++;
            verifyRecipe = 0;
            return false;
        }
        var recipe = recipes.get(verifyRecipe++);
        var before = new ArrayList<BigInteger>();
        for (int k = 0; k < keys.size(); k++) {
            charge();
            K key = keys.get(k);
            BigInteger in = BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L));
            BigInteger out = BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L));
            before.add(in.add(node.required().get(k).subtract(out).max(BigInteger.ZERO)));
        }
        if (!covered(before)) throw new IllegalStateException("Invalid coverability fixpoint");
        return false;
    }

    private boolean covered(List<BigInteger> vector) {
        for (Node node : basis) if (leq(node.required(), vector)) return true;
        return false;
    }

    private void remember(Node node) {
        if (covered(node.required())) return;
        long bytes = 192L + keys.size() * 96L;
        if (!budget.tryReserve(bytes)) throw new LocalLimit();
        memory += bytes;
        basis.removeIf(old -> leq(node.required(), old.required()));
        basis.add(node);
        if (leq(node.required(), initial)) pending.addFirst(node);
        else pending.addLast(node);
    }

    private boolean leq(List<BigInteger> left, List<BigInteger> right) {
        for (int i = 0; i < left.size(); i++) {
            charge();
            if (left.get(i).compareTo(right.get(i)) > 0) return false;
        }
        return true;
    }

    private void charge() {
        budget.check();
        // Accumulate only active calls; parked time and other workers belong
        // to the request's global budget, not this algorithm's local quota.
        if (usedWork() >= allowance) throw new LocalLimit();
    }

    static <K> SequenceSummary<K> repeat(SequenceSummary<K> unit, BigInteger times) {
        Map<K, BigInteger> required = new LinkedHashMap<>(), delta = new LinkedHashMap<>(), peak = new LinkedHashMap<>();
        for (K key : unit.keys()) {
            required.put(key, unit.required(key).add(unit.delta(key).negate().max(BigInteger.ZERO).multiply(times.subtract(BigInteger.ONE))));
            delta.put(key, unit.delta(key).multiply(times));
            peak.put(key, unit.peak(key).add(unit.delta(key).max(BigInteger.ZERO).multiply(times.subtract(BigInteger.ONE))));
        }
        return new SequenceSummary<>(required, delta, peak);
    }

    private boolean finish(Result value) {
        if (value == Result.UNKNOWN && !forwardTried && keys.size() <= 32 && actions.size() <= 64) {
            forwardTried = true;
            forward = new ForwardCoverability<>(recipes, keys, initial, goal, actions, budget, Math.min(8192, allowance / 4));
            if (forward.result() == null) return false;
            forward.close();
            forward = null;
        }
        if (value == Result.UNKNOWN && !pdrTried && keys.size() <= 32 && recipes.size() <= 64) {
            pdrTried = true;
            pdr = new PdrCoverability<>(recipes, keys, initial, goal, actions, budget, Math.min(16384, allowance / 2));
            if (pdr.result() == null) return false;
            pdr.close();
            pdr = null;
        }
        if (value == Result.CLOSED && budget.proofJournal() != null) {
            List<List<BigInteger>> inputs = new ArrayList<>(), outputs = new ArrayList<>();
            for (var recipe : recipes) {
                inputs.add(keys.stream().map(key -> BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L))).toList());
                outputs.add(keys.stream().map(key -> BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L))).toList());
            }
            budget.proofJournal().add(new ExecutionProof.Certificate("coverability:captured_stock_goals", startupVerifying ? ExecutionProof.Kind.STARTUP_BOX : ExecutionProof.Kind.BACKWARD_CLOSURE,
                    initial, goal, inputs, outputs, basis.stream().map(Node::required).toList(), startupVerifying ? unbounded.stream().boxed().collect(java.util.stream.Collectors.toSet()) : Set.of()));
        }
        if (value == Result.UNKNOWN && !inductionTried && keys.size() <= 12 && recipes.size() <= 16) {
            inductionTried = true;
            induction = new CountInduction<>(recipes, keys, initial, goal, budget, Math.min(65536, allowance));
            if (induction.result() == null) return false;
            induction.close();
            induction = null;
        }
        result = value;
        budget.note("backward_cover", "result=" + value + "; antichain=" + basis.size() + "; expanded=" + expanded + "; work=" + usedWork());
        close();
        return true;
    }

    Result result() {
        return result;
    }

    PlanStep witness() {
        return witness;
    }

    @Override
    public void close() {
        if (induction != null) induction.close();
        induction = null;
        if (forward != null) forward.close();
        forward = null;
        if (pdr != null) pdr.close();
        pdr = null;
        basis.clear();
        pending.clear();
        active = null;
        budget.release(memory);
        memory = 0;
    }
}
