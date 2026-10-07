package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Implicit weighted hitting sets of independently checked unsatisfiable soft cores. */
final class CountCoreSoft implements AutoCloseable {

    private final List<ExactLinearProgram.Constraint> hard;
    private final List<BitSet> cores = new ArrayList<>();
    private final BigInteger[] low, high;
    private final List<CountSoftSearch.Soft> soft;
    private final PlanningBudget budget;
    private final long allowance;
    private CountHittingSet master;
    private CountCoreMinimize minimizer;
    private CountLcg probe;
    private BigInteger[] relaxed, counts;
    private BigInteger lower = BigInteger.ZERO, cost;
    private long work, memory;
    private int rounds;
    private boolean complete, infeasible, optimal;

    CountCoreSoft(List<ExactLinearProgram.Constraint> hard, BigInteger[] low, BigInteger[] high,
                  List<CountSoftSearch.Soft> soft, PlanningBudget budget, long maximumWork) {
        this.hard = hard;
        this.low = low;
        this.high = high;
        this.soft = soft;
        this.budget = budget;
        allowance = Math.min(maximumWork, budget.remainingWork() / 32);
        if (soft.size() > 64 || low.length > 128 || hard.size() > 512 || allowance < 4096 || Arrays.stream(low).anyMatch(v -> v.signum() < 0)) {
            complete = true;
            return;
        }
        long bytes = 4096L + 512L * low.length + 512L * soft.size();
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        relaxed = new BigInteger[soft.size()];
        Arrays.fill(relaxed, BigInteger.ZERO);
    }

    boolean step() {
        if (complete) return true;
        long before = budget.threadWork();
        try {
            budget.check();
            if (work >= allowance) return finish();
            if (minimizer != null) {
                if (!minimizer.step()) return false;
                var core = minimizer.core();
                minimizer.close();
                minimizer = null;
                if (core.isEmpty()) {
                    infeasible = true;
                    return finish();
                }
                cores.add(core);
                master = new CountHittingSet(cores, soft.stream().map(CountSoftSearch.Soft::weight).toArray(BigInteger[]::new),
                        budget, Math.min(16384, Math.max(256, (allowance - work) / 2)));
                return false;
            }
            if (master != null) {
                if (!master.step()) return false;
                lower = lower.max(master.lowerBound());
                var assignment = master.counts();
                if (assignment == null) return finish();
                relaxed = assignment;
                if (master.optimal()) lower = lower.max(master.cost());
                master.close();
                master = null;
            }
            if (probe == null) {
                if (rounds >= 128) return finish();
                var active = new ArrayList<>(hard);
                for (int j = 0; j < soft.size(); j++) if (relaxed[j].signum() == 0) active.add(soft.get(j).condition());
                probe = new CountLcg(active, low, high, budget, Math.min(16384, Math.max(1024, (allowance - work) / 2)), true);
                rounds++;
            }
            if (!probe.step()) return false;
            var witness = probe.counts();
            if (witness != null) {
                counts = witness;
                cost = BigInteger.ZERO;
                for (var preference : soft) if (!satisfies(preference.condition(), witness)) cost = cost.add(preference.weight());
                if (cost.compareTo(lower) < 0) throw new IllegalStateException("Soft witness contradicts checked core lower bound");
                optimal = cost.equals(lower);
                return finish();
            }
            if (!probe.infeasible() || probe.certificate() == null) return finish();
            var proof = probe.certificate();
            if (!proof.closed() || CountProof.verify(proof, Math.max(1, allowance - work), budget::charge) != CountProof.Verdict.VERIFIED) return finish();
            var core = new BitSet();
            for (int j = 0; j < soft.size(); j++) if (relaxed[j].signum() == 0) core.set(j);
            minimizer = new CountCoreMinimize(hard, low, high, soft, core, proof, budget,
                    Math.min(8192, Math.max(0, (allowance - work) / 4)));
            probe.close();
            probe = null;
            return false;
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private boolean satisfies(ExactLinearProgram.Constraint row, BigInteger[] x) {
        BigInteger sum = BigInteger.ZERO;
        for (var e : row.terms().entrySet()) {
            budget.check();
            sum = sum.add(e.getValue().multiply(x[e.getKey()]));
        }
        return sum.compareTo(row.upper()) <= 0;
    }

    private boolean finish() {
        complete = true;
        budget.note("count_core_soft", "cores=" + cores.size() + "; lower=" + lower + "; cost=" + cost + "; optimal=" + optimal + "; infeasible=" + infeasible + "; work=" + work);
        return true;
    }

    BigInteger[] counts() {
        return counts == null ? null : counts.clone();
    }

    BigInteger cost() {
        return cost;
    }

    BigInteger lowerBound() {
        return lower;
    }

    boolean optimal() {
        return optimal;
    }

    boolean infeasible() {
        return infeasible;
    }

    @Override
    public void close() {
        if (minimizer != null) minimizer.close();
        minimizer = null;
        if (probe != null) probe.close();
        probe = null;
        if (master != null) master.close();
        master = null;
        budget.release(memory);
        memory = 0;
    }
}
