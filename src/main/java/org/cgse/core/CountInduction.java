package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.function.LongConsumer;

/** BMC plus k-induction strengthened by a checked nonincreasing token-sum invariant. */
final class CountInduction<K> implements AutoCloseable {

    record Problem(List<BigInteger> initial, List<BigInteger> goal,
                   List<List<BigInteger>> inputs, List<List<BigInteger>> outputs) {

        Problem {
            initial = List.copyOf(initial);
            goal = List.copyOf(goal);
            inputs = inputs.stream().map(List::copyOf).toList();
            outputs = outputs.stream().map(List::copyOf).toList();
        }
    }

    record Proof(Problem problem, int depth, CountProof.Certificate base, CountProof.Certificate induction) {}

    private record Encoding(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, int firings) {

        List<CountProof.Row> scope() {
            var result = new ArrayList<>(rows.stream().map(CountProof::row).toList());
            for (int j = 0; j < low.length; j++) {
                result.add(new CountProof.Row(Map.of(j, BigInteger.ONE.negate()), low[j].negate()));
                result.add(new CountProof.Row(Map.of(j, BigInteger.ONE), high[j]));
            }
            return result;
        }
    }

    private final List<GraphRecipe<K>> recipes;
    private final Problem problem;
    private final PlanningBudget budget;
    private final long allowance;
    private long work, memory, proofMemory;
    private int depth = 1;
    private boolean induction;
    private Encoding encoding;
    private CountLcg search;
    private CountProof.Certificate base;
    private Proof proof;
    private PlanStep witness;
    private BackwardCoverability.Result result;

    CountInduction(List<GraphRecipe<K>> recipes, List<K> keys, List<BigInteger> initial, List<BigInteger> goal,
                   PlanningBudget budget, long maximumWork) {
        this.recipes = recipes;
        this.budget = budget;
        allowance = Math.min(maximumWork, budget.remainingWork() / 16);
        problem = new Problem(initial, goal,
                recipes.stream().map(r -> keys.stream().map(k -> BigInteger.valueOf(r.inputs().getOrDefault(k, 0L))).toList()).toList(),
                recipes.stream().map(r -> keys.stream().map(k -> BigInteger.valueOf(r.outputs().getOrDefault(k, 0L))).toList()).toList());
        if (allowance < 8192 || recipes.stream().anyMatch(GraphRecipe::batchSensitiveInputs) || !suitable(problem)) {
            result = BackwardCoverability.Result.UNKNOWN;
            return;
        }
        long bytes = 2L << 20;
        if (!budget.tryReserve(bytes)) {
            result = BackwardCoverability.Result.UNKNOWN;
            return;
        }
        memory = bytes;
    }

    boolean step() {
        if (result != null) return true;
        long before = budget.threadWork();
        try {
            budget.check();
            if (work >= allowance || depth > 6) return finish(BackwardCoverability.Result.UNKNOWN);
            if (search == null) {
                encoding = encode(problem, depth, !induction, budget::charge);
                search = new CountLcg(encoding.rows(), encoding.low(), encoding.high(), budget,
                        Math.min(32768, Math.max(1024, (allowance - work) / 3)), true);
            }
            if (!search.step()) return false;
            var assignment = search.counts();
            boolean closed = search.infeasible();
            if (!induction && assignment != null) {
                List<PlanStep> path = new ArrayList<>();
                var stock = new ArrayList<>(problem.initial());
                for (int t = 0; t < depth; t++) {
                    int selected = -1;
                    for (int r = 0; r <= recipes.size(); r++) if (assignment[encoding.firings() + t * (recipes.size() + 1) + r].signum() > 0) {
                        if (selected >= 0) throw new IllegalStateException("Multiple induction firings");
                        selected = r;
                    }
                    if (selected < 0) throw new IllegalStateException("Missing induction firing");
                    if (selected == recipes.size()) continue;
                    for (int k = 0; k < stock.size(); k++) {
                        budget.check();
                        if (stock.get(k).compareTo(problem.inputs().get(selected).get(k)) < 0) throw new IllegalStateException("Induction prefix unavailable");
                        stock.set(k, stock.get(k).subtract(problem.inputs().get(selected).get(k)).add(problem.outputs().get(selected).get(k)));
                    }
                    path.add(PlanStep.batch(recipes.get(selected).id(), BigInteger.ONE));
                }
                for (int k = 0; k < stock.size(); k++) if (stock.get(k).compareTo(problem.goal().get(k)) < 0) throw new IllegalStateException("Induction target unavailable");
                witness = new PlanStep.Sequence(path);
                return finish(BackwardCoverability.Result.WITNESS);
            }
            if (!closed && assignment == null) return finish(BackwardCoverability.Result.UNKNOWN);
            if (!induction) {
                base = search.certificate();
                if (base == null) return finish(BackwardCoverability.Result.UNKNOWN);
                long bytes = 1024L + base.axioms().stream().mapToLong(r -> 192L + 192L * r.terms().size()).sum() +
                        base.forbidden().stream().flatMap(Collection::stream).mapToLong(r -> 192L + 192L * r.terms().size()).sum();
                if (!budget.tryReserve(bytes)) return finish(BackwardCoverability.Result.UNKNOWN);
                proofMemory = bytes;
                induction = true;
            } else if (closed) {
                proof = new Proof(problem, depth, base, search.certificate());
                if (verify(proof, Math.max(1, allowance - work), budget::charge) == CountProof.Verdict.VERIFIED) {
                    if (budget.proofJournal() != null) budget.proofJournal().add(proof);
                    return finish(BackwardCoverability.Result.CLOSED);
                }
                proof = null;
                return finish(BackwardCoverability.Result.UNKNOWN);
            } else {
                depth++;
                induction = false;
                base = null;
                budget.release(proofMemory);
                proofMemory = 0;
            }
            search.close();
            search = null;
            encoding = null;
            return false;
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private static boolean suitable(Problem p) {
        int n = p.initial().size();
        if (n < 1 || n > 12 || p.inputs().size() > 16 || p.goal().size() != n || p.inputs().size() != p.outputs().size()) return false;
        var all = new ArrayList<>(p.inputs());
        all.addAll(p.outputs());
        all.add(p.initial());
        all.add(p.goal());
        if (all.stream().anyMatch(v -> v.size() != n || v.stream().anyMatch(x -> x == null || x.signum() < 0))) return false;
        for (int r = 0; r < p.inputs().size(); r++) if (p.outputs().get(r).stream().reduce(BigInteger.ZERO, BigInteger::add)
                .compareTo(p.inputs().get(r).stream().reduce(BigInteger.ZERO, BigInteger::add)) > 0)
            return false;
        return true;
    }

    private static Encoding encode(Problem p, int depth, boolean base, LongConsumer charged) {
        int n = p.initial().size(), m = p.inputs().size(), start = (depth + 1) * n, safe = start + depth * (m + 1);
        int variables = safe + (base ? 0 : depth * n);
        BigInteger total = p.initial().stream().reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger[] low = new BigInteger[variables], high = new BigInteger[variables];
        Arrays.fill(low, BigInteger.ZERO);
        Arrays.fill(high, BigInteger.ONE);
        Arrays.fill(high, 0, start, total);
        if (base) for (int k = 0; k < n; k++) low[k] = high[k] = p.initial().get(k);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int t = 0; t <= depth; t++) {
            var sum = new TreeMap<Integer, BigInteger>();
            for (int k = 0; k < n; k++) sum.put(t * n + k, BigInteger.ONE);
            rows.add(new ExactLinearProgram.Constraint(sum, total));
        }
        for (int t = 0; t < depth; t++) {
            var one = new TreeMap<Integer, BigInteger>();
            for (int r = 0; r <= m; r++) one.put(start + t * (m + 1) + r, BigInteger.ONE);
            equality(rows, one, BigInteger.ONE);
            for (int k = 0; k < n; k++) {
                var effect = new TreeMap<Integer, BigInteger>();
                var enabled = new TreeMap<Integer, BigInteger>();
                effect.put((t + 1) * n + k, BigInteger.ONE);
                effect.put(t * n + k, BigInteger.ONE.negate());
                enabled.put(t * n + k, BigInteger.ONE.negate());
                for (int r = 0; r < m; r++) {
                    charged.accept(1);
                    int id = start + t * (m + 1) + r;
                    BigInteger in = p.inputs().get(r).get(k), delta = in.subtract(p.outputs().get(r).get(k));
                    if (delta.signum() != 0) effect.put(id, delta);
                    if (in.signum() != 0) enabled.put(id, in);
                }
                equality(rows, effect, BigInteger.ZERO);
                rows.add(new ExactLinearProgram.Constraint(enabled, BigInteger.ZERO));
            }
            if (!base) {
                var absent = new TreeMap<Integer, BigInteger>();
                for (int k = 0; k < n; k++) {
                    int id = safe + t * n + k;
                    if (p.goal().get(k).signum() == 0) {
                        high[id] = BigInteger.ZERO;
                        continue;
                    }
                    absent.put(id, BigInteger.ONE.negate());
                    BigInteger limit = p.goal().get(k).subtract(BigInteger.ONE), bigM = total.subtract(limit).max(BigInteger.ZERO);
                    var terms = new TreeMap<Integer, BigInteger>();
                    terms.put(t * n + k, BigInteger.ONE);
                    if (bigM.signum() > 0) terms.put(id, bigM);
                    rows.add(new ExactLinearProgram.Constraint(terms, limit.add(bigM)));
                }
                rows.add(new ExactLinearProgram.Constraint(absent, BigInteger.ONE.negate()));
            }
        }
        for (int k = 0; k < n; k++) rows.add(new ExactLinearProgram.Constraint(Map.of(depth * n + k, BigInteger.ONE.negate()), p.goal().get(k).negate()));
        return new Encoding(rows, low, high, start);
    }

    private static void equality(List<ExactLinearProgram.Constraint> rows, Map<Integer, BigInteger> terms, BigInteger value) {
        rows.add(new ExactLinearProgram.Constraint(terms, value));
        var opposite = new TreeMap<Integer, BigInteger>();
        terms.forEach((id, v) -> opposite.put(id, v.negate()));
        rows.add(new ExactLinearProgram.Constraint(opposite, value.negate()));
    }

    static CountProof.Verdict verify(Proof proof, long maximumWork, LongConsumer charged) {
        if (proof == null || proof.depth() < 1 || proof.depth() > 6 || !suitable(proof.problem()) || proof.base() == null || proof.induction() == null ||
                !proof.base().closed() || !proof.induction().closed() || maximumWork < 1)
            return CountProof.Verdict.INVALID;
        long[] used = { 0 };
        LongConsumer cost = units -> {
            used[0] += units;
            charged.accept(units);
        };
        var base = encode(proof.problem(), proof.depth(), true, cost);
        var step = encode(proof.problem(), proof.depth(), false, cost);
        if (proof.base().variables() != base.low().length || proof.induction().variables() != step.low().length ||
                !proof.base().axioms().equals(base.scope()) || !proof.induction().axioms().equals(step.scope()))
            return CountProof.Verdict.INVALID;
        if (used[0] >= maximumWork) return CountProof.Verdict.INCOMPLETE;
        var first = CountProof.verify(proof.base(), maximumWork - used[0], cost);
        if (first != CountProof.Verdict.VERIFIED) return first;
        if (used[0] >= maximumWork) return CountProof.Verdict.INCOMPLETE;
        return CountProof.verify(proof.induction(), maximumWork - used[0], cost);
    }

    private boolean finish(BackwardCoverability.Result value) {
        result = value;
        budget.note("count_k_induction", value + "; depth=" + depth + "; base_and_step=" + (proof != null) + "; work=" + work);
        return true;
    }

    BackwardCoverability.Result result() {
        return result;
    }

    PlanStep witness() {
        return witness;
    }

    Proof proof() {
        return proof;
    }

    @Override
    public void close() {
        if (search != null) search.close();
        search = null;
        budget.release(memory + proofMemory);
        memory = proofMemory = 0;
    }
}
