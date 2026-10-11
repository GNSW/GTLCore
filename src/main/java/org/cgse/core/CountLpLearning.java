// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;

/** Exact, globally valid row explanations suggested by a bounded numerical relaxation. */
final class CountLpLearning {

    record Cut(ExactLinearProgram.Constraint row, Map<Integer, BigInteger> parents, BigInteger divisor) {}

    /** Retain numerical state and original-row structure; cuts still cite original global rows. */
    static final class Session implements AutoCloseable {

        private CountNumericRelaxation.Session numerical = new CountNumericRelaxation.Session();
        private CountLpStructure structure;
        private PlanningBudget budget;
        private long prepared, structureHits;
        private final boolean integerDomains;

        Session() { this(false); }

        /** Separate bounded-integer channel; the Boolean projection remains the default. */
        Session(boolean integerDomains) { this.integerDomains = integerDomains; }

        Result solve(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                     PlanningBudget budget, long maximumWork) {
            if (this.budget != budget) {
                numerical.close();
                clearStructure();
                numerical = new CountNumericRelaxation.Session();
                this.budget = budget;
            }
            try {
                var helper = new CountLpLearning(budget, Math.min(maximumWork, budget.remainingWork()));
                if (!integerDomains && lower.length >= 2 && lower.length <= 128 && upper.length == lower.length &&
                        rows.size() <= 512 && maximumWork >= 1024) {
                    if (structure != null && structure.matches(rows, lower.length, helper::check)) structureHits++;
                    else {
                        clearStructure();
                        structure = CountLpStructure.create(rows, lower.length, budget, helper::check);
                        if (structure != null) prepared++;
                    }
                } else clearStructure();
                var result = CountLpLearning.solve(rows, lower, upper, budget,
                        maximumWork - helper.spent(), numerical, structure, integerDomains);
                if (result == null) {
                    numerical.close();
                    if (structure != null) {
                        // Cached metadata is optional. Release it before the
                        // old preparation path retries, using only this call's
                        // remaining work and memory rather than a fresh budget.
                        clearStructure();
                        result = CountLpLearning.solve(rows, lower, upper, budget,
                                maximumWork - helper.spent(), numerical, null, integerDomains);
                    }
                }
                return result;
            } catch (Stop stopped) {
                numerical.close();
                return null;
            } catch (RuntimeException | Error failure) {
                numerical.close();
                clearStructure();
                throw failure;
            }
        }

        private void clearStructure() {
            if (structure != null) structure.close();
            structure = null;
        }

        long reused() {
            return numerical.reused();
        }

        long rebuilt() {
            return numerical.rebuilt();
        }

        long invalidated() {
            return numerical.invalidated();
        }

        long fallbacks() {
            return numerical.fallbacks();
        }

        @Override
        public void close() {
            numerical.close();
            clearStructure();
            if (budget != null) budget.note("count_lp_basis", "reused=" + reused() + "; rebuilt=" + rebuilt() +
                    "; invalidated=" + invalidated() + "; restored=" + numerical.restored() + "; fallbacks=" + fallbacks());
            if (budget != null) budget.note("count_lp_structure", "prepared=" + prepared + "; hits=" + structureHits);
            budget = null;
        }
    }

    static final class Result implements AutoCloseable {

        final double[] point;
        final Cut cut;
        final boolean numericalInfeasible;
        final long numericalWork;
        final BigInteger[] integerPoint;
        private final PlanningBudget budget;
        private long memory;

        Result(double[] point, Cut cut, boolean numericalInfeasible, long numericalWork, PlanningBudget budget, long memory) {
            this(point, cut, numericalInfeasible, numericalWork, budget, memory, null);
        }

        Result(double[] point, Cut cut, boolean numericalInfeasible, long numericalWork, PlanningBudget budget, long memory,
               BigInteger[] integerPoint) {
            this.point = point;
            this.cut = cut;
            this.numericalInfeasible = numericalInfeasible;
            this.numericalWork = numericalWork;
            this.budget = budget;
            this.memory = memory;
            this.integerPoint = integerPoint;
        }

        @Override
        public void close() {
            budget.release(memory);
            memory = 0;
        }
    }

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private final PlanningBudget budget;
    private final long started, allowance;
    private boolean integerDomains;

    private CountLpLearning(PlanningBudget budget, long allowance) {
        this.budget = budget;
        started = budget.threadWork();
        this.allowance = allowance;
    }

    static Result solve(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                        PlanningBudget budget, long maximumWork) {
        return solve(rows, lower, upper, budget, maximumWork, null, null, false);
    }

    private static Result solve(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                                PlanningBudget budget, long maximumWork, CountNumericRelaxation.Session numerical,
                                CountLpStructure structure, boolean integerDomains) {
        if (lower.length < 2 || lower.length > 128 || upper.length != lower.length || rows.size() > 512 || maximumWork < 1024) return null;
        long bytes = 0;
        try {
            var helper = new CountLpLearning(budget, Math.min(maximumWork, budget.remainingWork()));
            helper.integerDomains = integerDomains;
            long terms = structure == null ? 0 : structure.termCount;
            if (structure == null) {
                for (var row : rows) {
                    helper.check();
                    if (row.upper().bitLength() > 1024) return null;
                    terms += row.terms().size();
                    for (var coefficient : row.terms().values()) {
                        helper.check();
                        if (coefficient.bitLength() > 1024) return null;
                    }
                }
            }
            // Projection maps, copied sparse rows, integer multipliers and their
            // accumulated exact consequence remain owned until the caller closes.
            long requested = 4096L + 256L * lower.length + 384L * rows.size() + 320L * terms;
            if (!budget.tryReserve(requested)) return null;
            bytes = requested;
            Result result = helper.solve(rows, lower, upper, bytes, numerical, structure);
            if (result != null) bytes = 0;
            return result;
        } catch (Stop stopped) {
            return null;
        } finally {
            budget.release(bytes);
        }
    }

    private Result solve(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, long bytes,
                         CountNumericRelaxation.Session session, CountLpStructure structure) {
        int variables = low.length;
        int[] map = new int[variables];
        Arrays.fill(map, -1);
        List<Integer> free = new ArrayList<>();
        boolean unfixed = false;
        for (int i = 0; i < variables; i++) {
            check();
            if (high[i] == null || low[i].compareTo(high[i]) > 0 || low[i].bitLength() > 1024 || high[i].bitLength() > 1024 ||
                    !integerDomains && (low[i].signum() < 0 || high[i].compareTo(BigInteger.ONE) > 0))
                return null;
            unfixed |= !low[i].equals(high[i]);
            if (!low[i].equals(high[i])) {
                map[i] = free.size();
                free.add(i);
            }
        }
        if (!unfixed) return null;
        List<ExactLinearProgram.Constraint> reduced = new ArrayList<>();
        List<Integer> source = new ArrayList<>();
        int objective = structure == null ? -1 : structure.objective;
        for (int r = 0; r < rows.size(); r++) {
            var row = rows.get(r);
            if (structure == null) {
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
            if (structure == null) {
                for (var term : row.terms().entrySet()) {
                    check();
                    int id = term.getKey();
                    var coefficient = term.getValue();
                    // Preserve the Boolean copy fast path. Integer coordinates
                    // shift by a*l and use the actual width u-l for activity.
                    if (low[id].signum() != 0) bound = subtract(bound,
                            integerDomains ? multiply(coefficient, low[id]) : coefficient);
                    if (map[id] >= 0) {
                        terms.put(map[id], coefficient);
                        if (coefficient.signum() > 0) maximum = add(maximum,
                                integerDomains ? multiply(coefficient, subtract(high[id], low[id])) : coefficient);
                    }
                }
            } else {
                maximum = structure.positiveSums[r];
                for (int p = structure.offsets[r]; p < structure.offsets[r + 1]; p++) {
                    check();
                    int id = structure.columns[p];
                    var coefficient = structure.coefficients[p];
                    if (low[id].signum() != 0) bound = subtract(bound, coefficient);
                    if (map[id] >= 0) terms.put(map[id], coefficient);
                    else if (coefficient.signum() > 0) maximum = subtract(maximum, coefficient);
                }
            }
            // Preserve the cold solver's projection. A basis is reused only
            // when this reduced matrix and its objective remain identical.
            // Forcing fixed columns and trivial rows to remain changes the
            // numerical suggestions that guide the integer search.
            if (bound.compareTo(maximum) >= 0) continue;
            reduced.add(new ExactLinearProgram.Constraint(terms, bound));
            source.add(r);
        }
        int originals = reduced.size();
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
        long numericalAllowance = Math.max(1, allowance - spent());
        var numerical = session == null ? CountNumericRelaxation.solve(free.size(), reduced, cost, budget, numericalAllowance) :
                session.solve(free.size(), reduced, cost, budget, numericalAllowance);
        if (numerical == null) return null;
        double[] point = null;
        BigInteger[] integerPoint = null;
        if (numerical.point() != null) {
            point = new double[variables];
            if (integerDomains) integerPoint = new BigInteger[variables];
            for (int i = 0; i < variables; i++) {
                check();
                point[i] = map[i] < 0 ? low[i].doubleValue() : low[i].doubleValue() + numerical.point()[map[i]];
                if (integerPoint != null) {
                    if (map[i] < 0) integerPoint[i] = low[i];
                    else if (!Double.isFinite(numerical.point()[map[i]])) integerPoint = null;
                    else {
                        // Round z before adding the exact offset. Converting l+z
                        // through double loses whole units above 2^53.
                        var rounded = BigDecimal.valueOf(numerical.point()[map[i]]).setScale(0, RoundingMode.HALF_EVEN).toBigIntegerExact();
                        integerPoint[i] = add(low[i], rounded);
                    }
                }
            }
        }
        double maximumDual = numerical.phaseOneInfeasible() ? 0 : 1, minimumDual = Double.POSITIVE_INFINITY;
        for (int i = 0; i < originals; i++) {
            check();
            double value = numerical.dual()[i];
            if (value > 0 && Double.isFinite(value)) {
                maximumDual = Math.max(maximumDual, value);
                minimumDual = Math.min(minimumDual, value);
            }
        }
        if (!numerical.phaseOneInfeasible() && objective >= 0) minimumDual = Math.min(minimumDual, 1);
        Cut cut = null;
        var rational = rationalWeights(numerical.dual(), source,
                numerical.phaseOneInfeasible() ? -1 : objective);
        if (rational != null) cut = combine(rows, rational, low, high);
        if (cut == null && maximumDual > 0 && Double.isFinite(maximumDual)) {
            int span = Double.isFinite(minimumDual) ? Math.max(0, Math.getExponent(maximumDual) - Math.getExponent(minimumDual)) : 0;
            // Coarse proposals are cheap. A bounded extra precision adapts to
            // mixed row scales instead of silently losing every small weight.
            int last = Math.min(512, 30 + span);
            int[] precisions = last > 30 ? new int[] { 10, 20, 30, last } : new int[] { 10, 20, 30 };
            for (int precision : precisions) {
                Map<Integer, BigInteger> weights = new TreeMap<>();
                for (int i = 0; i < originals; i++) {
                    check();
                    BigInteger weight = weight(numerical.dual()[i], maximumDual, precision);
                    if (weight.signum() > 0) weights.merge(source.get(i), weight, this::add);
                }
                if (!numerical.phaseOneInfeasible() && objective >= 0) {
                    BigInteger weight = weight(1, maximumDual, precision);
                    if (weight.signum() > 0) weights.merge(objective, weight, this::add);
                }
                cut = combine(rows, weights, low, high);
                if (cut != null) break;
            }
        }
        return new Result(point, cut, numerical.phaseOneInfeasible(), numerical.work(), budget, bytes, integerPoint);
    }

    /** Small rational multipliers are proposals; only the exact row sum below can prove anything. */
    private Map<Integer, BigInteger> rationalWeights(double[] dual, List<Integer> source, int objective) {
        Map<Integer, long[]> fractions = new TreeMap<>();
        BigInteger denominator = BigInteger.ONE;
        for (int i = 0; i < source.size(); i++) {
            check();
            double value = dual[i];
            if (!(value > 0) || !Double.isFinite(value)) continue;
            long[] fraction = approximate(value);
            if (fraction == null) return null;
            if (fraction[0] == 0) continue;
            fractions.put(source.get(i), fraction);
            var next = BigInteger.valueOf(fraction[1]);
            integer(Math.max(denominator.bitLength(), next.bitLength()));
            denominator = multiply(denominator.divide(denominator.gcd(next)), next);
            if (denominator.bitLength() > 256) return null;
        }
        Map<Integer, BigInteger> weights = new TreeMap<>();
        for (var entry : fractions.entrySet()) {
            check();
            var fraction = entry.getValue();
            integer(denominator.bitLength());
            weights.put(entry.getKey(), multiply(BigInteger.valueOf(fraction[0]), denominator.divide(BigInteger.valueOf(fraction[1]))));
        }
        if (objective >= 0) weights.merge(objective, denominator, this::add);
        return weights;
    }

    private long[] approximate(double value) {
        if (value > 1e9) return null;
        double remainder = value;
        long p0 = 0, p1 = 1, q0 = 1, q1 = 0;
        for (int step = 0; step < 32; step++) {
            check();
            double whole = Math.floor(remainder);
            if (!Double.isFinite(whole) || whole > 1e9) return null;
            long part = (long) whole;
            if (q1 != 0 && part > (65536 - q0) / q1) return null;
            long q = part * q1 + q0;
            if (part != 0 && p1 > (Long.MAX_VALUE - p0) / part) return null;
            long p = part * p1 + p0;
            if (Math.abs(value - (double) p / q) <= 1e-8 * Math.max(1, value)) return new long[] { p, q };
            p0 = p1; p1 = p; q0 = q1; q1 = q;
            remainder = 1 / (remainder - whole);
        }
        return null;
    }

    private BigInteger weight(double value, double maximum, int precision) {
        if (!(value > 0) || !Double.isFinite(value)) return BigInteger.ZERO;
        if (precision <= 30) return BigInteger.valueOf(Math.round(value / maximum * (1L << precision)));
        // Divide as decimals before scaling: value/maximum as a double could
        // underflow even though a bounded integer multiplier is representable.
        BigDecimal ratio = BigDecimal.valueOf(value).divide(BigDecimal.valueOf(maximum), new MathContext(80, RoundingMode.HALF_EVEN));
        integer(precision);
        return ratio.multiply(new BigDecimal(BigInteger.ONE.shiftLeft(precision))).setScale(0, RoundingMode.HALF_UP).toBigIntegerExact();
    }

    private Cut combine(List<ExactLinearProgram.Constraint> rows, Map<Integer, BigInteger> weights, BigInteger[] low, BigInteger[] high) {
        if (weights.isEmpty()) return null;
        Map<Integer, BigInteger> terms = new TreeMap<>();
        BigInteger bound = BigInteger.ZERO;
        for (var weight : weights.entrySet()) {
            var row = rows.get(weight.getKey());
            bound = add(bound, multiply(row.upper(), weight.getValue()));
            for (var term : row.terms().entrySet()) {
                check();
                terms.merge(term.getKey(), multiply(term.getValue(), weight.getValue()), this::add);
            }
        }
        terms.values().removeIf(value -> value.signum() == 0);
        BigInteger divisor = BigInteger.ZERO;
        for (var coefficient : terms.values()) {
            integer(Math.max(divisor.bitLength(), coefficient.bitLength()));
            divisor = divisor.gcd(coefficient);
        }
        if (divisor.signum() == 0) divisor = BigInteger.ONE;
        final BigInteger common = divisor;
        terms.replaceAll((id, value) -> {
            integer(Math.max(value.bitLength(), common.bitLength()));
            return value.divide(common);
        });
        integer(Math.max(bound.bitLength(), divisor.bitLength()));
        bound = floor(bound, divisor);
        var consequence = new ExactLinearProgram.Constraint(terms, bound);
        BigInteger minimum = BigInteger.ZERO;
        for (var term : terms.entrySet()) {
            check();
            var endpoint = term.getValue().signum() > 0 ? low[term.getKey()] : high[term.getKey()];
            if (endpoint.signum() != 0)
                minimum = add(minimum, integerDomains ? multiply(term.getValue(), endpoint) : term.getValue());
        }
        BigInteger slack = subtract(bound, minimum);
        boolean useful = slack.signum() < 0;
        if (!useful) for (var term : terms.entrySet()) {
            check();
            int id = term.getKey();
            if (!low[id].equals(high[id]) && (integerDomains ? multiply(term.getValue().abs(), subtract(high[id], low[id])) :
                    term.getValue().abs()).compareTo(slack) > 0) useful = true;
        }
        if (!useful || rows.contains(consequence)) return null;
        // This is the certificate construction itself: every weight is an
        // explicit nonnegative integer and the only division is exact on all
        // coefficients, with the right-hand side rounded down. Current branch
        // bounds affected proposal selection only, never the consequence.
        return new Cut(consequence, Collections.unmodifiableMap(new TreeMap<>(weights)), divisor);
    }

    private BigInteger multiply(BigInteger a, BigInteger b) {
        if (a.bitLength() + b.bitLength() > 1024) throw new Stop();
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
        if (spent() >= allowance) throw new Stop();
        budget.operation(PlanningBudget.Operation.INTEGER, bits);
    }

    private void check() {
        if (spent() >= allowance) throw new Stop();
        budget.check();
    }

    private long spent() {
        return budget.threadWork() - started;
    }

    private static BigInteger floor(BigInteger value, BigInteger divisor) {
        var qr = value.divideAndRemainder(divisor);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }
}
