package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Candidate-only integer affine elimination followed by exact LLL/nearest-plane repair.
 * No floating tolerances, native dependency, or negative conclusion escapes this strategy.
 */
final class CountAffineLattice implements AutoCloseable {

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private final long allowance;
    private final List<ExactLinearProgram.Constraint> equations = new ArrayList<>();
    private final Map<ExactLinearProgram.Constraint, ExactLinearProgram.Constraint> oppositeFaces = new HashMap<>();
    private final List<BigInteger[]> basis = new ArrayList<>();
    private BigInteger[] point, counts;
    private ExactRational[][] orthogonal, mu;
    private ExactRational[] norms;
    private int equation, phase, pivot = 1, attempt, trialFaces, equationLimit, exactEquations, faceAttempt;
    private long work, memory;
    private boolean complete;

    CountAffineLattice(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                       BigInteger[] upper, PlanningBudget budget) {
        this.rows = rows;
        this.lower = lower;
        this.upper = upper;
        this.budget = budget;
        allowance = Math.min(131_072, budget.remainingWork() / 32);
        if (lower.length > 48 || rows.size() > 512 || allowance < 1024) complete = true;
        for (int i = 0; i < lower.length; i++)
            if (upper[i] == null || lower[i].compareTo(upper[i]) > 0) complete = true;
    }

    boolean step() {
        if (complete) return true;
        try {
            charge();
            if (phase == 0) {
                prepare();
            } else if (phase == 1) {
                if (equation < equationLimit) {
                    int index = equation++;
                    if (index >= exactEquations) index = exactEquations + Math.floorMod(index - exactEquations + faceAttempt, trialFaces);
                    var row = equations.get(index);
                    if (faceAttempt % 2 != 0) row = oppositeFaces.getOrDefault(row, row);
                    intersect(row, index >= exactEquations);
                } else if (basis.size() <= 1) {
                    interval();
                    return finish();
                } else {
                    gramSchmidt();
                    phase = 2;
                }
            } else if (phase == 2) {
                if (pivot < basis.size()) reduce();
                else {
                    phase = 3;
                }
            } else {
                nearest();
                if (counts != null || ++attempt == 8) return finish();
            }
            return complete;
        } catch (LocalLimit | ExactRational.PrecisionLimit limit) {
            return finish();
        }
    }

    private void prepare() {
        var known = new HashMap<Map<Integer, BigInteger>, BigInteger>();
        for (var row : rows) {
            charge();
            known.merge(row.terms(), row.upper(), BigInteger::min);
        }
        var used = new HashSet<Map<Integer, BigInteger>>();
        for (var row : rows) {
            charge();
            if (row.terms().isEmpty() || used.contains(row.terms())) continue;
            var opposite = new TreeMap<Integer, BigInteger>();
            for (var term : row.terms().entrySet()) {
                charge();
                opposite.put(term.getKey(), term.getValue().negate());
            }
            if (!row.upper().negate().equals(known.get(opposite))) continue;
            used.add(row.terms());
            used.add(opposite);
            equations.add(row);
        }
        exactEquations = equations.size();
        boolean exactWeighted = equations.stream().filter(row -> weighted(row)).count() >= 2;
        for (var row : rows) {
            charge();
            if (used.contains(row.terms()) || row.terms().size() < 2 || !weighted(row)) continue;
            var opposite = new TreeMap<Integer, BigInteger>();
            for (var term : row.terms().entrySet()) {
                charge();
                opposite.put(term.getKey(), term.getValue().negate());
            }
            var reverse = known.get(opposite);
            if (reverse == null || row.upper().compareTo(reverse.negate()) <= 0) continue;
            used.add(row.terms());
            used.add(opposite);
            equations.add(row);
            oppositeFaces.put(row, new ExactLinearProgram.Constraint(opposite, reverse));
            trialFaces++;
        }
        // A tight demand face can reveal balances hidden by joint outputs or
        // small allowed surplus. This is a proposal restriction, NOT a derived
        // equality: rejected faces never become bounds or infeasibility claims.
        for (var row : rows) {
            charge();
            if (used.contains(row.terms()) || row.terms().size() < 2 ||
                    row.terms().values().stream().anyMatch(v -> v.signum() >= 0) ||
                    row.terms().values().stream().allMatch(v -> v.abs().equals(BigInteger.ONE)))
                continue;
            used.add(row.terms());
            equations.add(row);
            trialFaces++;
        }
        // Unit-sum choice groups already have cheaper dedicated strategies.
        // This portfolio slot targets coupled weighted balances, whose large
        // integer coefficients defeat rounding and broad count enumeration.
        if (equations.stream().filter(row -> weighted(row)).count() < 2) {
            complete = true;
            return;
        }
        long bytes = 8192L + 2048L * lower.length * lower.length + 256L * rows.size();
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        equationLimit = exactWeighted ? exactEquations : equations.size();
        reset();
    }

    private static boolean weighted(ExactLinearProgram.Constraint row) {
        return row.terms().values().stream().anyMatch(v -> v.abs().compareTo(BigInteger.ONE) > 0);
    }

    private void reset() {
        basis.clear();
        orthogonal = mu = null;
        norms = null;
        equation = attempt = 0;
        pivot = 1;
        point = lower.clone();
        for (int i = 0; i < lower.length; i++) if (!lower[i].equals(upper[i])) {
            var column = new BigInteger[lower.length];
            Arrays.fill(column, BigInteger.ZERO);
            column[i] = BigInteger.ONE;
            basis.add(column);
        }
        phase = 1;
    }

    private void intersect(ExactLinearProgram.Constraint row, boolean trial) {
        boolean expanded = false;
        BigInteger rhs = row.upper().subtract(dot(row, point));
        var values = new BigInteger[basis.size()];
        for (int j = 0; j < values.length; j++) values[j] = dot(row, basis.get(j));
        int first = -1;
        for (int j = 0; j < values.length; j++)
            if (values[j].signum() != 0 && (first < 0 || values[j].abs().compareTo(values[first].abs()) < 0)) first = j;
        if (first < 0) {
            if (trial ? rhs.signum() < 0 : rhs.signum() != 0) finish();
            return;
        }
        Collections.swap(basis, first, 0);
        var old = values[0];
        values[0] = values[first];
        values[first] = old;
        for (int j = 1; j < values.length; j++) {
            if (values[j].signum() == 0) continue;
            // A unimodular two-column transformation: [a b] U = [gcd(a,b) 0].
            var a = values[0];
            var b = values[j];
            var bezout = bezout(a, b);
            var g = bezout[0];
            var u = basis.get(0);
            var v = basis.get(j);
            var left = new BigInteger[point.length];
            var right = new BigInteger[point.length];
            for (int i = 0; i < point.length; i++) {
                charge();
                left[i] = bounded(u[i].multiply(bezout[1]).add(v[i].multiply(bezout[2])));
                right[i] = bounded(v[i].multiply(a.divide(g)).subtract(u[i].multiply(b.divide(g))));
                expanded |= right[i].bitLength() > 128;
            }
            basis.set(0, left);
            basis.set(j, right);
            values[0] = g;
            values[j] = BigInteger.ZERO;
        }
        if (trial) {
            // Only values congruent to this prefix's integer lattice are
            // reachable. Use the nearest such value inside the inequality,
            // allowing surplus instead of requiring a possibly impossible face.
            var g = values[0].abs();
            rhs = rhs.subtract(rhs.mod(g));
        }
        var qr = rhs.divideAndRemainder(values[0]);
        if (qr[1].signum() != 0) {
            finish();
            return;
        }
        var fixed = basis.remove(0);
        for (int i = 0; i < point.length; i++) {
            charge();
            point[i] = bounded(point[i].add(fixed[i].multiply(qr[0])));
        }
        // Exact Bezout substitutions can inflate an otherwise small integer
        // lattice before the final LLL stage. Reduce the intermediate basis
        // before these representatives exhaust the local precision allowance.
        if (expanded && basis.size() > 1) {
            gramSchmidt();
            pivot = 1;
            while (pivot < basis.size()) reduce();
            point = nearestPoint();
            pivot = 1;
            orthogonal = mu = null;
            norms = null;
        }
    }

    private BigInteger[] bezout(BigInteger a, BigInteger b) {
        BigInteger r = a.abs(), next = b.abs(), s = BigInteger.ONE, ns = BigInteger.ZERO,
                t = BigInteger.ZERO, nt = BigInteger.ONE;
        while (next.signum() != 0) {
            charge();
            var qr = r.divideAndRemainder(next);
            r = next;
            next = qr[1];
            var value = s.subtract(qr[0].multiply(ns));
            s = ns;
            ns = value;
            value = t.subtract(qr[0].multiply(nt));
            t = nt;
            nt = value;
        }
        return new BigInteger[] { r, a.signum() < 0 ? s.negate() : s, b.signum() < 0 ? t.negate() : t };
    }

    private void gramSchmidt() {
        int d = basis.size(), n = point.length;
        orthogonal = new ExactRational[d][n];
        mu = new ExactRational[d][d];
        norms = new ExactRational[d];
        for (int i = 0; i < d; i++) {
            Arrays.fill(mu[i], ExactRational.ZERO);
            for (int k = 0; k < n; k++) orthogonal[i][k] = ExactRational.of(basis.get(i)[k]);
            for (int j = 0; j < i; j++) {
                ExactRational value = ExactRational.ZERO;
                for (int k = 0; k < n; k++) {
                    charge();
                    value = value.add(ExactRational.of(basis.get(i)[k]).multiply(orthogonal[j][k]));
                }
                mu[i][j] = value.divide(norms[j]);
                for (int k = 0; k < n; k++) {
                    charge();
                    orthogonal[i][k] = orthogonal[i][k].subtract(mu[i][j].multiply(orthogonal[j][k]));
                }
            }
            ExactRational norm = ExactRational.ZERO;
            for (int k = 0; k < n; k++) {
                charge();
                norm = norm.add(orthogonal[i][k].multiply(orthogonal[i][k]));
            }
            if (norm.signum() <= 0) throw new LocalLimit();
            norms[i] = norm;
        }
    }

    private void reduce() {
        int k = pivot;
        for (int j = k - 1; j >= 0; j--) {
            charge();
            BigInteger q = nearestInteger(mu[k][j]);
            if (q.signum() == 0) continue;
            for (int i = 0; i < point.length; i++) {
                charge();
                basis.get(k)[i] = bounded(basis.get(k)[i].subtract(q.multiply(basis.get(j)[i])));
            }
            for (int i = 0; i < j; i++) {
                charge();
                mu[k][i] = mu[k][i].subtract(ExactRational.of(q).multiply(mu[j][i]));
            }
            mu[k][j] = mu[k][j].subtract(ExactRational.of(q));
        }
        ExactRational m = mu[k][k - 1];
        ExactRational square = m.multiply(m);
        if (norms[k].compareTo(new ExactRational(BigInteger.valueOf(3), BigInteger.valueOf(4)).subtract(square).multiply(norms[k - 1])) >= 0) {
            pivot++;
            return;
        }
        ExactRational combined = norms[k].add(square.multiply(norms[k - 1]));
        ExactRational next = m.multiply(norms[k - 1]).divide(combined);
        norms[k] = norms[k].multiply(norms[k - 1]).divide(combined);
        norms[k - 1] = combined;
        mu[k][k - 1] = next;
        for (int i = 0; i < k - 1; i++) {
            var value = mu[k][i];
            mu[k][i] = mu[k - 1][i];
            mu[k - 1][i] = value;
        }
        for (int i = k + 1; i < basis.size(); i++) {
            charge();
            var value = mu[i][k];
            mu[i][k] = mu[i][k - 1].subtract(m.multiply(value));
            mu[i][k - 1] = value.add(next.multiply(mu[i][k]));
        }
        Collections.swap(basis, k, k - 1);
        pivot = Math.max(1, pivot - 1);
    }

    private void nearest() {
        var candidate = nearestPoint();
        if (valid(candidate)) counts = candidate;
    }

    private BigInteger[] nearestPoint() {
        var candidate = point.clone();
        var residual = new ExactRational[point.length];
        for (int i = 0; i < residual.length; i++) {
            charge();
            // Midpoint first, then deterministic interior targets. These only
            // guide proposals; they never restrict the caller's feasible set.
            int fraction = attempt == 0 ? 8 : 2 + Math.floorMod(i * 7 + attempt * 5, 13);
            var target = ExactRational.of(lower[i]).add(new ExactRational(upper[i].subtract(lower[i]).multiply(BigInteger.valueOf(fraction)), BigInteger.valueOf(16)));
            residual[i] = target.subtract(ExactRational.of(point[i]));
        }
        // LLL keeps mu and squared norms exact as columns are changed. Compute
        // projections from this factorization, avoiding another Gram-Schmidt
        // pass merely to reconstruct stale orthogonal vectors for rounding.
        var projection = new ExactRational[basis.size()];
        for (int j = 0; j < basis.size(); j++) {
            ExactRational value = ExactRational.ZERO;
            for (int i = 0; i < point.length; i++) {
                charge();
                value = value.add(residual[i].multiply(ExactRational.of(basis.get(j)[i])));
            }
            for (int i = 0; i < j; i++) {
                charge();
                value = value.subtract(mu[j][i].multiply(projection[i]));
            }
            projection[j] = value;
        }
        for (int j = basis.size() - 1; j >= 0; j--) {
            var q = nearestInteger(projection[j].divide(norms[j]));
            for (int i = 0; i < point.length; i++) {
                charge();
                var change = basis.get(j)[i].multiply(q);
                candidate[i] = bounded(candidate[i].add(change));
            }
            for (int i = 0; i < j; i++) {
                charge();
                projection[i] = projection[i].subtract(ExactRational.of(q).multiply(mu[j][i]).multiply(norms[i]));
            }
        }
        return candidate;
    }

    private void interval() {
        if (basis.isEmpty()) {
            if (valid(point)) counts = point.clone();
            return;
        }
        BigInteger lo = null, hi = null;
        var constraints = new ArrayList<>(rows);
        for (int i = 0; i < point.length; i++) {
            constraints.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), upper[i]));
            constraints.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), lower[i].negate()));
        }
        for (var row : constraints) {
            BigInteger slope = dot(row, basis.get(0)), limit = row.upper().subtract(dot(row, point));
            if (slope.signum() == 0) {
                if (limit.signum() < 0) return;
            } else {
                var value = new ExactRational(limit, slope);
                if (slope.signum() > 0) hi = hi == null ? value.floor() : hi.min(value.floor());
                else lo = lo == null ? value.ceil() : lo.max(value.ceil());
            }
        }
        if (lo != null && hi != null && lo.compareTo(hi) > 0) return;
        BigInteger t = lo == null ? hi == null ? BigInteger.ZERO : hi.min(BigInteger.ZERO) : lo.max(BigInteger.ZERO);
        if (hi != null) t = t.min(hi);
        var candidate = point.clone();
        for (int i = 0; i < candidate.length; i++) {
            charge();
            candidate[i] = bounded(candidate[i].add(basis.get(0)[i].multiply(t)));
        }
        if (valid(candidate)) counts = candidate;
    }

    private boolean valid(BigInteger[] candidate) {
        for (int i = 0; i < candidate.length; i++) {
            charge();
            if (candidate[i].compareTo(lower[i]) < 0 || candidate[i].compareTo(upper[i]) > 0) return false;
        }
        for (var row : rows) if (dot(row, candidate).compareTo(row.upper()) > 0) return false;
        return true;
    }

    private BigInteger dot(ExactLinearProgram.Constraint row, BigInteger[] values) {
        BigInteger sum = BigInteger.ZERO;
        for (var term : row.terms().entrySet()) {
            charge();
            sum = bounded(sum.add(term.getValue().multiply(values[term.getKey()])));
        }
        return sum;
    }

    private static BigInteger nearestInteger(ExactRational value) {
        return value.add(new ExactRational(BigInteger.ONE, BigInteger.TWO)).floor();
    }

    private static BigInteger bounded(BigInteger value) {
        if (value.bitLength() > 512) throw new LocalLimit();
        return value;
    }

    private void charge() {
        budget.check();
        if (++work > allowance) throw new LocalLimit();
    }

    private boolean finish() {
        if (counts == null && equationLimit < equations.size() && memory > 0 && work < allowance) {
            equationLimit = equations.size();
            reset();
            return false;
        }
        if (counts == null && trialFaces > 0 && memory > 0 && work < allowance && ++faceAttempt < Math.min(4, trialFaces + 1)) {
            reset();
            return false;
        }
        complete = true;
        if (!equations.isEmpty()) budget.note("count_affine_lattice", "witness=" + (counts != null) + "; variables=" + lower.length +
                "; equations=" + equations.size() + "; trial_faces=" + trialFaces + "; kernel=" + basis.size() + "; work=" + work);
        return true;
    }

    BigInteger[] counts() {
        return counts;
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }

    private static final class LocalLimit extends RuntimeException {}
}
