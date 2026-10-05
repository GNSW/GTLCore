package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** Bounded chunk/deletion core shrinking. Every accepted smaller scope has its own checked proof. */
final class CountCoreMinimize implements AutoCloseable {

    private final List<ExactLinearProgram.Constraint> hard;
    private final List<CountSoftSearch.Soft> soft;
    private final BigInteger[] low, high;
    private final PlanningBudget budget;
    private final long allowance;
    private final Deque<BitSet> pending = new ArrayDeque<>();
    private BitSet core, trial, removed;
    private CountProof.Certificate proof;
    private CountLcg probe;
    private long work, started, memory;
    private int checks, deleted;
    private boolean complete, traced;

    private static final class TraceLimit extends RuntimeException {

        TraceLimit() {
            super(null, null, false, false);
        }
    }

    CountCoreMinimize(List<ExactLinearProgram.Constraint> hard, BigInteger[] low, BigInteger[] high,
                      List<CountSoftSearch.Soft> soft, BitSet core, CountProof.Certificate checked,
                      PlanningBudget budget, long maximumWork) {
        this.hard = hard;
        this.low = low;
        this.high = high;
        this.soft = soft;
        this.core = (BitSet) core.clone();
        this.proof = checked;
        this.budget = budget;
        allowance = Math.min(maximumWork, budget.remainingWork() / 32);
        long bytes = 4096L + 512L * (soft.size() + low.length) + 256L * checked.axioms().stream().mapToLong(r -> r.terms().size() + 1).sum() +
                256L * checked.forbidden().stream().flatMap(List::stream).mapToLong(r -> r.terms().size() + 1).sum();
        if (allowance < 512 || !budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        pending.push((BitSet) core.clone());
    }

    boolean step() {
        if (complete) return true;
        started = budget.threadWork();
        try {
            budget.check();
            if (remaining() < 256 || pending.isEmpty() && probe == null || core.isEmpty()) return finish();
            if (!traced) {
                traced = true;
                traceRoot();
                return false;
            }
            if (probe != null) {
                if (!probe.step()) return false;
                var next = probe.certificate();
                boolean verified = probe.infeasible() && next != null && next.closed() &&
                        CountProof.verify(next, Math.max(1, remaining()), budget::charge) == CountProof.Verdict.VERIFIED;
                if (verified) accept(next);
                else split();
                probe.close();
                probe = null;
                return false;
            }
            removed = pending.pop();
            removed.and(core);
            if (removed.isEmpty()) return false;
            trial = (BitSet) core.clone();
            trial.andNot(removed);
            var rows = rows(trial);
            var axioms = axioms(rows);
            // Replaying the old proof often discards irrelevant assumptions
            // without any new search. Invalid/incomplete replay proves nothing.
            var replay = new CountProof.Certificate("soft_core:reduced", low.length, axioms, proof.forbidden(), List.of(), true);
            checks++;
            var verdict = CountProof.verify(replay, Math.min(4096, Math.max(1, remaining() / 2)), budget::charge);
            if (verdict == CountProof.Verdict.VERIFIED) accept(replay);
            else if (remaining() >= 4096) probe = new CountLcg(rows, low, high, budget, Math.min(2048, remaining() / 3), true);
            else split();
            return false;
        } finally {
            work += budget.threadWork() - started;
        }
    }

    private List<ExactLinearProgram.Constraint> rows(BitSet selected) {
        var result = new ArrayList<>(hard);
        for (int j = selected.nextSetBit(0); j >= 0; j = selected.nextSetBit(j + 1)) {
            budget.check();
            result.add(soft.get(j).condition());
        }
        return result;
    }

    private List<CountProof.Row> axioms(List<ExactLinearProgram.Constraint> rows) {
        var result = new ArrayList<>(rows.stream().map(CountProof::row).toList());
        for (int i = 0; i < low.length; i++) {
            result.add(new CountProof.Row(Map.of(i, BigInteger.ONE.negate()), low[i].negate()));
            if (high[i] != null) result.add(new CountProof.Row(Map.of(i, BigInteger.ONE), high[i]));
        }
        return result;
    }

    /**
     * Propagation explanations locate an initial small core before deletion.
     * Only original rows/domains participate. The result is independently
     * replayed, so this analysis is never itself a pruning certificate.
     */
    private void traceRoot() {
        long until = budget.threadWork() + Math.min(4096, remaining() / 2);
        try {
            var rows = rows(core);
            var lo = low.clone();
            var hi = high.clone();
            var ls = new BitSet[low.length];
            var hs = new BitSet[low.length];
            for (int i = 0; i < low.length; i++) {
                ls[i] = new BitSet();
                hs[i] = new BitSet();
            }
            boolean changed;
            do {
                changed = false;
                for (int r = 0; r < rows.size(); r++) {
                    traceCharge(until);
                    var row = rows.get(r);
                    // Frozen endpoints keep explanations acyclic within a row.
                    var endpoints = new HashMap<Integer, BigInteger>();
                    var supports = new HashMap<Integer, BitSet>();
                    BigInteger sum = BigInteger.ZERO;
                    int infinite = 0;
                    var all = new BitSet();
                    all.set(r);
                    for (var e : row.terms().entrySet()) {
                        traceCharge(until);
                        if (e.getValue().signum() == 0) continue;
                        int id = e.getKey();
                        BigInteger value = e.getValue().signum() > 0 ? lo[id] : hi[id];
                        BitSet reason = e.getValue().signum() > 0 ? ls[id] : hs[id];
                        endpoints.put(id, value);
                        supports.put(id, reason);
                        all.or(reason);
                        if (value == null) infinite++;
                        else sum = sum.add(value.multiply(e.getValue()));
                    }
                    if (infinite == 0 && sum.compareTo(row.upper()) > 0) {
                        trimRoot(all);
                        return;
                    }
                    for (var e : row.terms().entrySet()) {
                        traceCharge(until);
                        int id = e.getKey();
                        BigInteger a = e.getValue();
                        if (a.signum() == 0) continue;
                        BigInteger endpoint = endpoints.get(id);
                        if (infinite - (endpoint == null ? 1 : 0) != 0) continue;
                        BigInteger rhs = row.upper().subtract(sum).add(endpoint == null ? BigInteger.ZERO : a.multiply(endpoint));
                        BigInteger[] qr = rhs.divideAndRemainder(a.abs());
                        BigInteger value = qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
                        if (a.signum() < 0) value = value.negate();
                        if (a.signum() > 0 ? hi[id] != null && value.compareTo(hi[id]) >= 0 : value.compareTo(lo[id]) <= 0) continue;
                        var why = new BitSet();
                        why.set(r);
                        for (var support : supports.entrySet()) {
                            traceCharge(until);
                            if (support.getKey() != id) why.or(support.getValue());
                        }
                        if (a.signum() > 0) {
                            hi[id] = value;
                            hs[id] = why;
                        } else {
                            lo[id] = value;
                            ls[id] = why;
                        }
                        changed = true;
                        if (hi[id] != null && lo[id].compareTo(hi[id]) > 0) {
                            var conflict = (BitSet) ls[id].clone();
                            conflict.or(hs[id]);
                            trimRoot(conflict);
                            return;
                        }
                    }
                }
            } while (changed);
        } catch (TraceLimit limit) {
            // Retain the already proved original core.
        }
    }

    private void trimRoot(BitSet support) {
        trial = new BitSet();
        int at = hard.size();
        for (int j = core.nextSetBit(0); j >= 0; j = core.nextSetBit(j + 1)) if (support.get(at++)) trial.set(j);
        if (trial.cardinality() == core.cardinality()) return;
        removed = (BitSet) core.clone();
        removed.andNot(trial);
        var replay = new CountProof.Certificate("soft_core:propagation_support", low.length, axioms(rows(trial)), List.of(), List.of(), true);
        if (CountProof.verify(replay, Math.max(1, remaining()), budget::charge) == CountProof.Verdict.VERIFIED) accept(replay);
        pending.clear();
        pending.push((BitSet) core.clone());
    }

    private void traceCharge(long until) {
        if (budget.threadWork() - until >= 0) throw new TraceLimit();
        budget.check();
    }

    private void accept(CountProof.Certificate next) {
        // The old proof is already charged. A new LCG proof can be larger;
        // decline the improvement if its retained archive cannot be reserved.
        long bytes = 4096L + 512L * (soft.size() + low.length) + 256L * next.axioms().stream().mapToLong(r -> r.terms().size() + 1).sum() +
                256L * next.forbidden().stream().flatMap(List::stream).mapToLong(r -> r.terms().size() + 1).sum();
        if (bytes > memory && !budget.tryReserve(bytes - memory)) {
            split();
            return;
        }
        if (bytes < memory) budget.release(memory - bytes);
        memory = bytes;
        deleted += core.cardinality() - trial.cardinality();
        core = trial;
        proof = next;
    }

    private void split() {
        if (removed.cardinality() <= 1) return;
        var left = new BitSet();
        int half = removed.cardinality() / 2;
        for (int j = removed.nextSetBit(0); j >= 0 && half-- > 0; j = removed.nextSetBit(j + 1)) left.set(j);
        var right = (BitSet) removed.clone();
        right.andNot(left);
        pending.push(right);
        pending.push(left);
    }

    private long remaining() {
        return Math.max(0, allowance - work - (budget.threadWork() - started));
    }

    private boolean finish() {
        complete = true;
        if (budget.proofJournal() != null) budget.proofJournal().add(proof);
        budget.note("count_core_minimize", "remaining=" + core.cardinality() + "; deleted=" + deleted + "; checks=" + checks + "; work=" + work);
        return true;
    }

    BitSet core() {
        return (BitSet) core.clone();
    }

    CountProof.Certificate certificate() {
        return proof;
    }

    @Override
    public void close() {
        if (probe != null) probe.close();
        probe = null;
        budget.release(memory);
        memory = 0;
    }
}
