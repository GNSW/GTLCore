// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Candidate-only LP cores. Free the fractional coordinates and a small number
 * of cheap integral coordinates, then retain weighted Boolean conflict learning
 * inside that core. Unlike a bounded-flip neighborhood, all combinations of its
 * released coordinates are available. A closed core proves nothing globally.
 */
final class CountLpCoreSearch implements AutoCloseable {
    private static final int MAX_CORE = 32, MAX_RELEASED = 16;
    private static final long MAX_WORK = 4_194_304;

    private final List<ExactLinearProgram.Constraint> original;
    private final BigInteger[] lower, upper, seed;
    private final int[] fractional, integral;
    private final PlanningBudget budget;
    private final long allowance;
    private final int maximumReleased;
    private long work, memory, projectionMemory, visitStarted, coreStarted;
    private int released, cursor, attempts;
    private int[] map, free;
    private List<ExactLinearProgram.Constraint> projected;
    private CountCdcl search;
    private BigInteger[] counts;
    private boolean complete;

    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }

    static CountLpCoreSearch create(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                                    BigInteger[] upper, double[] point, ExactLinearProgram.Constraint prices,
                                    PlanningBudget budget) {
        budget.checkpoint();
        int n = lower.length;
        long allowance = Math.min(MAX_WORK, budget.remainingWork() / 8);
        if (n < 2 || n > 128 || upper.length != n || point == null || point.length != n || prices == null ||
                rows.isEmpty() || rows.size() > 64 || allowance < 262144) return null;
        long bytes = 2048L + 256L * n + 32L * rows.size();
        if (!budget.tryReserve(bytes)) return null;
        long started = budget.threadWork();
        try {
            var fractional = new ArrayList<Integer>();
            var integral = new ArrayList<Integer>();
            var seed = new BigInteger[n];
            for (int i = 0; i < n; i++) {
                budget.check();
                if (lower[i] == null || upper[i] == null || lower[i].signum() < 0 ||
                        lower[i].compareTo(upper[i]) > 0 || upper[i].compareTo(BigInteger.ONE) > 0 ||
                        !Double.isFinite(point[i])) return null;
                seed[i] = (point[i] >= 0.5 ? BigInteger.ONE : BigInteger.ZERO).max(lower[i]).min(upper[i]);
                if (lower[i].equals(upper[i])) continue;
                if (point[i] > 1e-6 && point[i] < 1 - 1e-6) fractional.add(i);
                else integral.add(i);
            }
            if (fractional.isEmpty() || fractional.size() >= MAX_CORE || integral.isEmpty()) return null;
            // A price is an ordering hint only. No supplied row or floating
            // value becomes an axiom or an exclusion of the original domain.
            for (var term : prices.terms().entrySet()) {
                budget.check();
                if (term.getKey() < 0 || term.getKey() >= n || term.getValue().bitLength() > 1024) return null;
            }
            integral.sort((a, b) -> {
                var x = prices.terms().getOrDefault(a, BigInteger.ZERO);
                var y = prices.terms().getOrDefault(b, BigInteger.ZERO);
                budget.operation(PlanningBudget.Operation.INTEGER, Math.max(x.bitLength(), y.bitLength()));
                return x.abs().compareTo(y.abs());
            });
            var result = new CountLpCoreSearch(rows, lower, upper, seed, fractional, integral, budget,
                    allowance, bytes, budget.threadWork() - started);
            bytes = 0;
            return result;
        } finally { budget.release(bytes); }
    }

    private CountLpCoreSearch(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                               BigInteger[] seed, List<Integer> fractional, List<Integer> integral,
                               PlanningBudget budget, long allowance, long memory, long work) {
        original = List.copyOf(rows);
        this.lower = lower.clone();
        this.upper = upper.clone();
        this.seed = seed;
        this.fractional = fractional.stream().mapToInt(Integer::intValue).toArray();
        this.integral = integral.stream().mapToInt(Integer::intValue).toArray();
        this.budget = budget;
        this.allowance = allowance;
        this.memory = memory;
        this.work = work;
        maximumReleased = Math.min(MAX_RELEASED, Math.min(integral.size(), MAX_CORE - fractional.size()));
        released = Math.min(8, maximumReleased);
    }

    boolean step() {
        if (complete) return true;
        budget.checkpoint();
        visitStarted = budget.threadWork();
        try {
            check();
            if (projected == null && !beginCore()) return finish("workspace_declined");
            if (cursor < original.size()) {
                var row = original.get(cursor++);
                if (row.upper().bitLength() > 1024) return finish("magnitude_declined");
                var terms = new TreeMap<Integer, BigInteger>();
                var bound = row.upper();
                for (var term : row.terms().entrySet()) {
                    check();
                    int id = term.getKey();
                    var a = term.getValue();
                    if (id < 0 || id >= map.length || a.bitLength() > 1024) return finish("magnitude_declined");
                    if (map[id] >= 0) terms.put(map[id], a);
                    else if (seed[id].signum() != 0) {
                        integer(Math.max(bound.bitLength(), a.bitLength()));
                        bound = bound.subtract(a);
                    }
                }
                projected.add(new ExactLinearProgram.Constraint(terms, bound));
                return false;
            }
            if (search == null) {
                var low = new BigInteger[free.length];
                var high = new BigInteger[free.length];
                Arrays.fill(low, BigInteger.ZERO);
                Arrays.fill(high, BigInteger.ONE);
                search = new CountCdcl(projected, low, high, budget, 65536).retained();
            }
            if (!search.step()) return false;
            var candidate = search.counts();
            if (candidate != null) {
                var restored = seed.clone();
                for (int i = 0; i < free.length; i++) { check(); restored[free[i]] = candidate[i]; }
                if (!valid(restored)) return finish("candidate_rejected");
                counts = restored;
                return finish("verified_candidate");
            }
            if (search.paused() && (released == maximumReleased || spent() - coreStarted < allowance / 4)) {
                search.resume(Math.min(65536, Math.max(1, allowance - spent())));
                return false;
            }
            // Every expansion has different fixing assumptions. Discard the
            // old core's clauses, even when it was exhaustively closed.
            clearCore();
            if (released == maximumReleased) return finish("core_unresolved");
            released = Math.min(maximumReleased, 2 * released);
            return false;
        } catch (Stop stopped) {
            return finish("work_limit");
        } finally { work += budget.threadWork() - visitStarted; }
    }

    private boolean beginCore() {
        long bytes = 1024L + 32L * lower.length + 128L * original.size() +
                320L * original.size() * (fractional.length + released);
        if (bytes > budget.availableBytes() / 8 || !budget.tryReserve(bytes)) return false;
        projectionMemory = bytes;
        map = new int[lower.length];
        Arrays.fill(map, -1);
        free = new int[fractional.length + released];
        for (int i = 0; i < free.length; i++) {
            check();
            free[i] = i < fractional.length ? fractional[i] : integral[i - fractional.length];
            map[free[i]] = i;
        }
        cursor = 0;
        projected = new ArrayList<>();
        coreStarted = spent();
        attempts++;
        return true;
    }

    private boolean valid(BigInteger[] candidate) {
        for (int i = 0; i < candidate.length; i++) {
            check();
            if (candidate[i].compareTo(lower[i]) < 0 || candidate[i].compareTo(upper[i]) > 0) return false;
        }
        for (var row : original) {
            var sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                check();
                if (candidate[term.getKey()].signum() == 0) continue;
                integer(Math.max(sum.bitLength(), term.getValue().bitLength()));
                sum = sum.add(term.getValue());
            }
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private long spent() { return work + budget.threadWork() - visitStarted; }
    private void check() { if (spent() >= allowance) throw new Stop(); budget.check(); }
    private void integer(int bits) { if (spent() >= allowance) throw new Stop(); budget.operation(PlanningBudget.Operation.INTEGER, bits); }
    private boolean finish(String detail) {
        complete = true;
        budget.note("count_lp_core", detail + "; fractional=" + fractional.length + "; released=" + released +
                "; attempts=" + attempts + "; work=" + spent());
        return true;
    }

    boolean complete() { return complete; }
    BigInteger[] counts() { return counts == null ? null : counts.clone(); }

    private void clearCore() {
        if (search != null) search.close();
        search = null;
        projected = null;
        map = free = null;
        budget.release(projectionMemory);
        projectionMemory = 0;
    }

    @Override public void close() {
        clearCore();
        budget.release(memory);
        memory = 0;
    }
}
