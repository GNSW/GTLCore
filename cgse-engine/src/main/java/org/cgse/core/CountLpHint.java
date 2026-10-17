// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** One bounded finite-domain relaxation supplies a preferred branch direction, never an integer fact. */
final class CountLpHint {

    /** The point remains owned until copied by a caller or this result is closed. */
    static final class Result implements AutoCloseable {

        final double[] point;
        private final PlanningBudget budget;
        private long memory;

        Result(double[] point, PlanningBudget budget, long memory) {
            this.point = point;
            this.budget = budget;
            this.memory = memory;
        }

        @Override
        public void close() {
            budget.release(memory);
            memory = 0;
        }
    }

    enum Outcome {
        UNSUPPORTED,
        WORK_LIMIT,
        MEMORY_LIMIT,
        ATTEMPTED
    }

    /** Admission feedback only; none of these states is an integer conclusion. */
    static final class Attempt {

        Outcome outcome = Outcome.UNSUPPORTED;
    }

    /**
     * A frozen branch-direction proposal. Later branch bounds may change while
     * this LP progresses, so its point is only a hint, never a domain fact.
     * Only one worker owns a visit; the request may hand it to another worker.
     */
    static final class Session implements AutoCloseable {
        private final CountNumericRelaxation.Session numerical = new CountNumericRelaxation.Session(true);
        private PlanningBudget budget;
        private Projection projection;
        private CountNumericRelaxation.Result ready;
        private long memory;

        boolean pending() { return projection != null; }
        long resumptions() { return numerical.resumed(); }

        Result start(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                     PlanningBudget budget, long maximumWork, Attempt attempt) {
            if (pending()) throw new IllegalStateException("Hint already started");
            return CountLpHint.solve(rows, low, high, budget, maximumWork, attempt, this);
        }

        Result resume(long maximumWork, Attempt attempt) {
            if (!pending()) throw new IllegalStateException("No pending hint");
            attempt.outcome = Outcome.ATTEMPTED;
            var helper = new CountLpHint(budget, Math.min(maximumWork, budget.remainingWork()), attempt);
            try {
                budget.checkpoint();
                if (helper.allowance < 1024) {
                    attempt.outcome = Outcome.WORK_LIMIT;
                    return null;
                }
                if (ready == null) {
                    ready = numerical.solve(projection.cost.length, projection.rows, projection.cost, budget, helper.allowance);
                    if (ready == null) {
                        if (numerical.pending()) attempt.outcome = Outcome.WORK_LIMIT;
                        else close();
                        return null;
                    }
                }
                Result result = helper.lift(projection, ready, memory);
                if (result != null) memory = 0; // Result owns the point until its caller copies it.
                close();
                return result;
            } catch (Stop stopped) {
                if (stopped.workLimited) attempt.outcome = Outcome.WORK_LIMIT;
                else close();
                return null;
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        @Override
        public void close() {
            numerical.close();
            projection = null;
            ready = null;
            if (budget != null) budget.release(memory);
            memory = 0;
        }
    }

    private record Projection(int[] map, BigInteger[] low, List<ExactLinearProgram.Constraint> rows,
                              BigInteger[] cost) {}

    private static final class Stop extends RuntimeException {

        final boolean workLimited;

        Stop(boolean workLimited) {
            super(null, null, false, false);
            this.workLimited = workLimited;
        }
    }

    private final PlanningBudget budget;
    private final long started, allowance;
    private final Attempt attempt;

    private CountLpHint(PlanningBudget budget, long allowance, Attempt attempt) {
        this.budget = budget;
        started = budget.threadWork();
        this.allowance = allowance;
        this.attempt = attempt;
    }

    static Result solve(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                        PlanningBudget budget, long maximumWork) {
        return solve(rows, lower, upper, budget, maximumWork, null);
    }

    static Result solve(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                        PlanningBudget budget, long maximumWork, Attempt attempt) {
        return solve(rows, lower, upper, budget, maximumWork, attempt, null);
    }

    private static Result solve(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                                PlanningBudget budget, long maximumWork, Attempt attempt, Session session) {
        if (attempt != null) attempt.outcome = Outcome.UNSUPPORTED;
        if (lower.length < 2 || lower.length > 512 || upper.length != lower.length || rows.size() > 1024) return null;
        if (maximumWork < 1024) {
            if (attempt != null) attempt.outcome = Outcome.WORK_LIMIT;
            return null;
        }
        long bytes = 0;
        try {
            var helper = new CountLpHint(budget, Math.min(maximumWork, budget.remainingWork()), attempt);
            long terms = 0;
            for (int i = 0; i < lower.length; i++) {
                helper.check();
                if (lower[i] == null || upper[i] == null || lower[i].signum() < 0 ||
                        upper[i].bitLength() > 31 || lower[i].compareTo(upper[i]) > 0)
                    return null;
            }
            {
                for (var row : rows) {
                    helper.check();
                    if (row.upper().bitLength() > 1024) return null;
                    terms += row.terms().size();
                    for (var term : row.terms().entrySet()) {
                        helper.check();
                        if (term.getKey() < 0 || term.getKey() >= lower.length || term.getValue().bitLength() > 1024) return null;
                    }
                }
            }
            // Sparse projection rows and their integer shifts are temporary;
            // this conservative workspace also owns the resulting numerical point.
            long requested = 4096L + 256L * lower.length + 384L * rows.size() + 320L * terms;
            if (!budget.tryReserve(requested)) {
                if (attempt != null) attempt.outcome = Outcome.MEMORY_LIMIT;
                return null;
            }
            bytes = requested;
            var projection = helper.project(rows, lower, upper);
            if (projection == null) return null;
            if (session != null) {
                session.projection = projection;
                session.budget = budget;
                session.memory = bytes;
                bytes = 0;
                return session.resume(Math.max(0, helper.allowance - helper.spent()), attempt);
            }
            if (attempt != null) attempt.outcome = Outcome.ATTEMPTED;
            var numerical = CountNumericRelaxation.solve(projection.cost.length, projection.rows, projection.cost,
                    budget, Math.max(1, helper.allowance - helper.spent()));
            Result result = numerical == null ? null : helper.lift(projection, numerical, bytes);
            if (result != null) bytes = 0;
            return result;
        } catch (Stop stopped) {
            if (attempt != null && stopped.workLimited && attempt.outcome != Outcome.ATTEMPTED)
                attempt.outcome = Outcome.WORK_LIMIT;
            return null;
        } finally {
            budget.release(bytes);
        }
    }

    private Projection project(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {
        int variables = low.length;
        int[] map = new int[variables];
        Arrays.fill(map, -1);
        List<Integer> free = new ArrayList<>();
        boolean unfixed = false;
        for (int i = 0; i < variables; i++) {
            check();
            if (high[i] == null || low[i].signum() < 0 || high[i].bitLength() > 31 || low[i].compareTo(high[i]) > 0)
                return null;
            unfixed |= !low[i].equals(high[i]);
            if (!low[i].equals(high[i])) {
                map[i] = free.size();
                free.add(i);
            }
        }
        if (!unfixed) return null;
        List<ExactLinearProgram.Constraint> reduced = new ArrayList<>();
        int objective = -1;
        for (int r = 0; r < rows.size(); r++) {
            var row = rows.get(r);
            {
                boolean positive = true;
                for (var value : row.terms().values()) {
                    check();
                    if (value.signum() <= 0) positive = false;
                }
                if (positive && row.terms().size() >= variables / 2 &&
                        (objective < 0 || row.terms().size() > rows.get(objective).terms().size()))
                    objective = r;
            }
            BigInteger bound = row.upper(), maximum = BigInteger.ZERO;
            Map<Integer, BigInteger> terms = new LinkedHashMap<>();
            {
                for (var term : row.terms().entrySet()) {
                    check();
                    int id = term.getKey();
                    var coefficient = term.getValue();
                    // Both endpoints are mathematical integers, including nonzero offsets.
                    if (low[id].signum() != 0) bound = subtract(bound, multiply(coefficient, low[id]));
                    if (map[id] >= 0) {
                        terms.put(map[id], coefficient);
                        if (coefficient.signum() > 0) maximum = add(maximum, multiply(coefficient, high[id].subtract(low[id])));
                    }
                }
            }
            // A row implied by this finite box cannot improve this LP point.
            if (bound.compareTo(maximum) >= 0) continue;
            reduced.add(new ExactLinearProgram.Constraint(terms, bound));
        }
        for (int i = 0; i < free.size(); i++) {
            check();
            int original = free.get(i);
            reduced.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), high[original].subtract(low[original])));
        }
        var cost = new BigInteger[free.size()];
        Arrays.fill(cost, BigInteger.ZERO);
        if (objective >= 0) for (var term : rows.get(objective).terms().entrySet()) {
            check();
            if (map[term.getKey()] >= 0) cost[map[term.getKey()]] = term.getValue().negate();
        }
        return new Projection(map, low.clone(), List.copyOf(reduced), cost);
    }

    private Result lift(Projection projection, CountNumericRelaxation.Result numerical, long bytes) {
        int variables = projection.low.length;
        double[] point = null;
        if (numerical.point() != null) {
            point = new double[variables];
            for (int i = 0; i < variables; i++) {
                check();
                int id = projection.map[i];
                point[i] = id < 0 ? projection.low[i].doubleValue() : projection.low[i].doubleValue() + numerical.point()[id];
            }
        }
        return point == null ? null : new Result(point, budget, bytes);
    }

    private BigInteger multiply(BigInteger a, BigInteger b) {
        if (a.bitLength() + b.bitLength() > 1024) throw new Stop(false);
        integer(Math.max(a.bitLength(), b.bitLength()));
        return a.multiply(b);
    }

    private BigInteger add(BigInteger a, BigInteger b) {
        integer(Math.max(a.bitLength(), b.bitLength()));
        return a.add(b);
    }

    private BigInteger subtract(BigInteger a, BigInteger b) {
        integer(Math.max(a.bitLength(), b.bitLength()));
        return a.subtract(b);
    }

    private void integer(int bits) {
        if (spent() >= allowance) throw new Stop(true);
        budget.operation(PlanningBudget.Operation.INTEGER, bits);
    }

    private void check() {
        if (spent() >= allowance) throw new Stop(true);
        budget.check();
    }

    private long spent() {
        return budget.threadWork() - started;
    }
}
