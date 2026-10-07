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
                if (lower.length >= 2 && lower.length <= 128 && upper.length == lower.length &&
                        rows.size() <= 512 && maximumWork >= 1024) {
                    if (structure != null && structure.matches(rows, lower.length, helper::check)) structureHits++;
                    else {
                        clearStructure();
                        structure = CountLpStructure.create(rows, lower.length, budget, helper::check);
                        if (structure != null) prepared++;
                    }
                } else clearStructure();
                var result = CountLpLearning.solve(rows, lower, upper, budget,
                        maximumWork - helper.spent(), numerical, structure);
                if (result == null) {
                    numerical.close();
                    if (structure != null) {
                        // Cached metadata is optional. Release it before the
                        // old preparation path retries, using only this call's
                        // remaining work and memory rather than a fresh budget.
                        clearStructure();
                        result = CountLpLearning.solve(rows, lower, upper, budget,
                                maximumWork - helper.spent(), numerical, null);
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
        private final PlanningBudget budget;
        private long memory;

        Result(double[] point, Cut cut, boolean numericalInfeasible, long numericalWork, PlanningBudget budget, long memory) {
            this.point = point;
            this.cut = cut;
            this.numericalInfeasible = numericalInfeasible;
            this.numericalWork = numericalWork;
            this.budget = budget;
            this.memory = memory;
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

    private CountLpLearning(PlanningBudget budget, long allowance) {
        this.budget = budget;
        started = budget.threadWork();
        this.allowance = allowance;
    }

    static Result solve(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                        PlanningBudget budget, long maximumWork) {
        return solve(rows, lower, upper, budget, maximumWork, null, null);
    }

    private static Result solve(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                                PlanningBudget budget, long maximumWork, CountNumericRelaxation.Session numerical,
                                CountLpStructure structure) {
        if (lower.length < 2 || lower.length > 128 || upper.length != lower.length || rows.size() > 512 || maximumWork < 1024) return null;
        long bytes = 0;
        try {
            var helper = new CountLpLearning(budget, Math.min(maximumWork, budget.remainingWork()));
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
            if (high[i] == null || low[i].signum() < 0 || high[i].compareTo(BigInteger.ONE) > 0 || low[i].compareTo(high[i]) > 0)
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
                    // Boolean endpoint shifts are exact copies/additions, with no
                    // multiplication by a large count hidden in this projection.
                    if (low[id].signum() != 0) bound = subtract(bound, coefficient);
                    if (map[id] >= 0) {
                        terms.put(map[id], coefficient);
                        if (coefficient.signum() > 0) maximum = add(maximum, coefficient);
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
        if (numerical.point() != null) {
            point = new double[variables];
            for (int i = 0; i < variables; i++) {
                check();
                point[i] = map[i] < 0 ? low[i].doubleValue() : low[i].doubleValue() + numerical.point()[map[i]];
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
        if (maximumDual > 0 && Double.isFinite(maximumDual)) {
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
        return new Result(point, cut, numerical.phaseOneInfeasible(), numerical.work(), budget, bytes);
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
            if ((term.getValue().signum() > 0 ? low[term.getKey()] : high[term.getKey()]).signum() != 0)
                minimum = add(minimum, term.getValue());
        }
        BigInteger slack = subtract(bound, minimum);
        boolean useful = slack.signum() < 0;
        if (!useful) for (var term : terms.entrySet()) {
            check();
            if (!low[term.getKey()].equals(high[term.getKey()]) && term.getValue().abs().compareTo(slack) > 0) useful = true;
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
