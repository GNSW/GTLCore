// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Candidate-only integer affine elimination followed by exact LLL/nearest-plane repair.
 * No floating tolerances, native dependency, or negative conclusion escapes this strategy.
 */
final class CountAffineLattice implements AutoCloseable {

    private static final ExactRational HALF = new ExactRational(BigInteger.ONE, BigInteger.TWO);
    private static final ExactRational LOVASZ = new ExactRational(BigInteger.valueOf(3), BigInteger.valueOf(4));

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private long allowance;
    private final boolean lowerFirst;
    private final List<ExactLinearProgram.Constraint> equations = new ArrayList<>();
    private final Map<ExactLinearProgram.Constraint, ExactLinearProgram.Constraint> oppositeFaces = new HashMap<>();
    private final List<BigInteger[]> basis = new ArrayList<>();
    // Aligned with basis, including every pivot, removal and deep insertion.
    // Words avoid allocating a temporary union for each two-column operation.
    private final List<long[]> supports = new ArrayList<>();
    private BigInteger[] point, counts, repairPoint;
    private ExactRational[][] mu;
    private ExactRational[] norms;
    private int equation, phase, pivot = 1, attempt, trialFaces, equationLimit, exactEquations, faceAttempt, exactEliminations;
    private long work, memory;
    private boolean complete, retaining, paused;
    private ExactLinearProgram.Constraint intersectRow;
    private BigInteger intersectRhs;
    private BigInteger[] intersectValues;
    private int intersectColumn, gramReturn, reductionColumn = -1;
    private boolean intersectTrial, intersectExpanded;
    private CountGramSchmidt gram;
    private long checkpoints;
    private CountKernelSearch kernel;
    private CountLatticePotential potential;
    private CountLatticePotential.Workspace potentialWorkspace;
    private boolean deep, strengthened;
    private int insertionFrom = -1, insertionTo;

    CountAffineLattice(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                       BigInteger[] upper, PlanningBudget budget) {
        this(rows, lower, upper, budget, 131_072);
    }

    CountAffineLattice(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                       BigInteger[] upper, PlanningBudget budget, long maximumWork) {
        this(rows, lower, upper, budget, maximumWork, false);
    }

    CountAffineLattice(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                       BigInteger[] upper, PlanningBudget budget, long maximumWork, boolean lowerFirst) {
        this.rows = rows;
        this.lower = lower;
        this.upper = upper;
        this.budget = budget;
        this.lowerFirst = lowerFirst;
        allowance = Math.min(maximumWork, Math.min(131_072, budget.remainingWork() / 32));
        if (lower.length > 128 || rows.size() > 512 || allowance < 1024) complete = true;
        for (int i = 0; i < lower.length; i++)
            if (upper[i] == null || lower[i].compareTo(upper[i]) > 0) complete = true;
    }

    boolean step() {
        if (complete || paused) return true;
        budget.checkpoint();
        if (work >= allowance) {
            if (retaining) return paused = true;
            return complete = true;
        }
        try {
            charge();
            if (phase == 0) {
                prepare();
            } else if (phase == 1) {
                if (equation < equationLimit) {
                    if (intersectRow == null) {
                        int index = equation;
                        if (index >= exactEquations) index = exactEquations + Math.floorMod(index - exactEquations + faceAttempt, trialFaces);
                        var row = equations.get(index);
                        if (faceAttempt % 2 != 0) row = oppositeFaces.getOrDefault(row, row);
                        beginIntersection(row, index >= exactEquations);
                    } else intersectStep();
                } else if (basis.size() <= 1) {
                    interval();
                    return finish();
                } else {
                    // The larger ambient box is useful only when the exact
                    // intersections actually expose a small integer kernel.
                    if (lower.length > 48 && basis.size() > 48) return complete = true;
                    beginGram(2);
                }
            } else if (phase == 2) {
                if (pivot < basis.size()) reduce();
                else {
                    closePotential();
                    phase = 3;
                }
            } else if (phase == 3) {
                nearest();
                if (counts != null) return finish();
                if (++attempt == (lowerFirst ? 1 : 9)) {
                    if (!lowerFirst && !strengthened) {
                        // Preserve the cheap ordinary LLL/nearest attempts.
                        // Only their failure admits a stronger retained pass.
                        strengthened = deep = true;
                        pivot = 1;
                        reductionColumn = -1;
                        attempt = 0;
                        phase = 2;
                    } else beginKernel();
                }
            } else if (phase == 7) {
                potentialStep();
            } else if (phase == 5) {
                gramStep();
            } else if (phase == 6) {
                if (pivot < basis.size()) reduce();
                else {
                    point = nearestPoint();
                    pivot = 1;
                    reductionColumn = -1;
                    mu = null;
                    norms = null;
                    phase = 1;
                }
            } else {
                long started = budget.threadWork();
                boolean done;
                try { done = kernel.step(); }
                finally { work += budget.threadWork() - started; }
                if (done) {
                    if (kernel.paused()) {
                        // Renew an older child deadline only inside the current
                        // owner grant; this does not replenish the request cap.
                        if (work < allowance) { kernel.resume(allowance - work); return false; }
                        return paused = true;
                    }
                    counts = kernel.counts();
                    kernel.close();
                    kernel = null;
                    return finish();
                }
            }
            return complete;
        } catch (LocalLimit | ExactRational.PrecisionLimit limit) {
            // Arithmetic/precision refusal is terminal, never a resumable
            // partially changed factorization or an infeasibility proof.
            return complete = true;
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
        int free = 0;
        for (int i = 0; i < lower.length; i++) {
            charge();
            if (!lower[i].equals(upper[i])) free++;
        }
        // Do not send every larger model through dense arithmetic. Equations
        // are an admission estimate, not a rank proof or an infeasibility test;
        // the actual kernel size is checked after their exact intersections.
        if (lower.length > 48 && (!exactWeighted || free - exactEquations > 48)) {
            complete = true;
            return;
        }
        long bytes = 8192L + 512L * lower.length * free + 1536L * free * free + 256L * rows.size()
                + (48L + 8L * ((lower.length + 63) / 64)) * free;
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
        if (kernel != null) kernel.close();
        kernel = null;
        closePotential();
        deep = strengthened = false;
        insertionFrom = -1;
        basis.clear();
        supports.clear();
        mu = null;
        norms = null;
        equation = attempt = exactEliminations = 0;
        intersectRow = null;
        intersectValues = null;
        gram = null;
        reductionColumn = -1;
        repairPoint = null;
        pivot = 1;
        point = lower.clone();
        for (int i = 0; i < lower.length; i++) if (!lower[i].equals(upper[i])) {
            var column = new BigInteger[lower.length];
            Arrays.fill(column, BigInteger.ZERO);
            column[i] = BigInteger.ONE;
            basis.add(column);
            var support = new long[(lower.length + 63) / 64];
            support[i >>> 6] = 1L << (i & 63);
            supports.add(support);
        }
        phase = 1;
    }

    private void beginIntersection(ExactLinearProgram.Constraint row, boolean trial) {
        intersectRow = row;
        intersectTrial = trial;
        intersectExpanded = false;
        intersectRhs = row.upper().subtract(dot(row, point));
        var values = new BigInteger[basis.size()];
        for (int j = 0; j < values.length; j++) values[j] = dot(row, basis.get(j));
        int first = -1;
        for (int j = 0; j < values.length; j++)
            if (values[j].signum() != 0 && (first < 0 || values[j].abs().compareTo(values[first].abs()) < 0)) first = j;
        if (first < 0) {
            intersectRow = null;
            equation++;
            checkpoints++;
            if (trial ? intersectRhs.signum() < 0 : intersectRhs.signum() != 0) finish();
            return;
        }
        swapBasis(first, 0);
        var old = values[0];
        values[0] = values[first];
        values[first] = old;
        intersectValues = values;
        intersectColumn = 1;
    }

    private void intersectStep() {
        var values = intersectValues;
        if (intersectColumn < values.length) {
            int j = intersectColumn++;
            if (values[j].signum() == 0) return;
            // A unimodular two-column transformation: [a b] U = [gcd(a,b) 0].
            var a = values[0];
            var b = values[j];
            var bezout = bezout(a, b);
            var g = bezout[0];
            // These exact quotients are shared by every coordinate of the
            // same unimodular column transformation.
            var reducedA = a.divide(g);
            var reducedB = b.divide(g);
            var u = basis.get(0);
            var v = basis.get(j);
            var us = supports.get(0);
            var vs = supports.get(j);
            for (int word = 0; word < us.length; word++) {
                charge();
                long union = us[word] | vs[word], leftBits = 0, rightBits = 0;
                while (union != 0) {
                    charge();
                    int bit = Long.numberOfTrailingZeros(union), i = (word << 6) + bit;
                    union &= union - 1;
                    // Read both old coordinates before overwriting either row.
                    var left = bounded(u[i].multiply(bezout[1]).add(v[i].multiply(bezout[2])));
                    var right = bounded(v[i].multiply(reducedA).subtract(u[i].multiply(reducedB)));
                    u[i] = left;
                    v[i] = right;
                    if (left.signum() != 0) leftBits |= 1L << bit;
                    if (right.signum() != 0) rightBits |= 1L << bit;
                    intersectExpanded |= right.bitLength() > 128;
                }
                us[word] = leftBits;
                vs[word] = rightBits;
            }
            values[0] = g;
            values[j] = BigInteger.ZERO;
            return;
        }
        BigInteger rhs = intersectRhs;
        if (intersectTrial) {
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
        supports.remove(0);
        for (int i = 0; i < point.length; i++) {
            charge();
            point[i] = bounded(point[i].add(fixed[i].multiply(qr[0])));
        }
        intersectRow = null;
        intersectValues = null;
        equation++;
        if (!intersectTrial) exactEliminations++;
        checkpoints++;
        // Exact Bezout substitutions can inflate an otherwise small integer
        // lattice before the final LLL stage. Reduce the intermediate basis
        // before these representatives exhaust the local precision allowance.
        if (intersectExpanded && basis.size() > 1) {
            pivot = 1;
            reductionColumn = -1;
            beginGram(6);
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

    private void beginGram(int continuation) {
        // Workspace is covered by the lattice reservation before any basis or
        // factor allocation. The basis cannot change during this phase.
        gram = new CountGramSchmidt(basis, this::charge);
        mu = gram.mu();
        norms = gram.norms();
        gramReturn = continuation;
        phase = 5;
    }

    /** One complete projection is a checkpoint: the next turn never repeats it. */
    private void gramStep() {
        int before = gram.completedRows();
        boolean done = gram.step();
        if (gram.dependent()) throw new LocalLimit();
        checkpoints += gram.completedRows() - before;
        if (done) {
            gram = null;
            phase = gramReturn;
        }
    }

    private void reduce() {
        int k = pivot;
        if (reductionColumn == -1) reductionColumn = k - 1;
        if (reductionColumn >= 0) {
            int j = reductionColumn--;
            charge();
            BigInteger q = nearestInteger(mu[k][j]);
            if (q.signum() == 0) {
                if (reductionColumn < 0) reductionColumn = -2;
                return;
            }
            subtractBasis(k, j, q);
            ExactRational multiple = null;
            for (int i = 0; i < j; i++) {
                charge();
                if (multiple == null) multiple = ExactRational.of(q);
                mu[k][i] = mu[k][i].subtract(multiple.multiply(mu[j][i]));
            }
            mu[k][j] = mu[k][j].subtract(multiple == null ? ExactRational.of(q) : multiple);
            // A completed nonzero size reduction is paid numeric progress.
            // Counting only equations and Gram rows made long retained LLL
            // phases appear idle to the portfolio while their basis improved.
            checkpoints++;
            if (reductionColumn < 0) reductionColumn = -2;
            return;
        }
        reductionColumn = -1;
        ExactRational m = mu[k][k - 1];
        ExactRational square = m.multiply(m);
        if (norms[k].compareTo(LOVASZ.subtract(square).multiply(norms[k - 1])) >= 0) {
            if (deep) {
                if (potentialWorkspace == null) potentialWorkspace = new CountLatticePotential.Workspace(norms, budget);
                potential = new CountLatticePotential(mu, norms, k, budget, potentialWorkspace);
                phase = 7;
            } else pivot++;
            return;
        }
        swap(k, m, square);
        // A Lovasz swap strictly decreases the lattice potential. Empty scans,
        // zero reductions and polling a paused search never earn this feedback.
        checkpoints++;
        pivot = Math.max(1, pivot - 1);
    }

    private void swap(int k) {
        ExactRational m = mu[k][k - 1];
        ExactRational square = m.multiply(m);
        swap(k, m, square);
    }

    private void swap(int k, ExactRational m, ExactRational square) {
        ExactRational combined = norms[k].add(square.multiply(norms[k - 1]));
        ExactRational next = m.multiply(norms[k - 1]).divide(combined);
        norms[k] = norms[k].multiply(norms[k - 1]).divide(combined);
        norms[k - 1] = combined;
        mu[k][k - 1] = next;
        for (int i = 0; i < k - 1; i++) {
            charge();
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
        swapBasis(k, k - 1);
    }

    private void swapBasis(int a, int b) {
        Collections.swap(basis, a, b);
        Collections.swap(supports, a, b);
    }

    private void subtractBasis(int target, int source, BigInteger q) {
        var u = basis.get(target);
        var v = basis.get(source);
        var us = supports.get(target);
        var vs = supports.get(source);
        for (int word = 0; word < vs.length; word++) {
            charge();
            long remaining = vs[word];
            // Outside the source support the target is unchanged. In
            // particular, cancellation must clear the target's support bit.
            while (remaining != 0) {
                charge();
                int bit = Long.numberOfTrailingZeros(remaining), i = (word << 6) + bit;
                remaining &= remaining - 1;
                u[i] = bounded(u[i].subtract(q.multiply(v[i])));
                if (u[i].signum() == 0) us[word] &= ~(1L << bit);
                else us[word] |= 1L << bit;
            }
        }
    }

    private void potentialStep() {
        if (insertionFrom >= 0) {
            // Adjacent factor updates are atomic, but a deep insertion can
            // yield between them. No scan sees its partially permuted basis.
            swap(insertionFrom--);
            if (insertionFrom == insertionTo) {
                insertionFrom = -1;
                pivot = Math.max(1, insertionTo);
                phase = 2;
                // Only the complete insertion has the certified potential
                // decrease; individual adjacent swaps need not improve it.
                checkpoints++;
            }
            return;
        }
        long started = budget.threadWork();
        boolean done;
        try { done = potential.step(); }
        finally { work += budget.threadWork() - started; }
        if (!done) return;
        boolean declined = potential.declined();
        int target = potential.insertion();
        potential.close();
        potential = null;
        if (declined) {
            deep = false;
            closePotential();
            beginKernel();
        } else if (target < 0) {
            pivot++;
            phase = 2;
        } else {
            insertionFrom = pivot;
            insertionTo = target;
        }
    }

    private void beginKernel() {
        long started = budget.threadWork();
        try {
            kernel = new CountKernelSearch(rows, lower, upper, repairPoint, basis, mu, norms, budget,
                    retaining ? Math.max(2048, allowance - work) : allowance - work);
            if (retaining) kernel.retained();
        } finally { work += budget.threadWork() - started; }
        phase = 4;
    }

    private void closePotential() {
        if (potential != null) potential.close();
        potential = null;
        if (potentialWorkspace != null) potentialWorkspace.close();
        potentialWorkspace = null;
    }

    private void nearest() {
        var candidate = nearestPoint();
        if (valid(candidate)) counts = candidate;
        repairPoint = candidate;
    }

    private BigInteger[] nearestPoint() {
        var candidate = point.clone();
        var residual = new ExactRational[point.length];
        for (int i = 0; i < residual.length; i++) {
            charge();
            // The general arm keeps its midpoint/interior trials, then the
            // lower corner. A short conditional turn goes straight to that
            // corner and kernel repair. Targets never restrict feasible counts.
            int fraction = lowerFirst ? 0 : attempt == 0 ? 8 : attempt == 8 ? 0 : 2 + Math.floorMod(i * 7 + attempt * 5, 13);
            var target = ExactRational.of(lower[i]).add(new ExactRational(upper[i].subtract(lower[i]).multiply(BigInteger.valueOf(fraction)), BigInteger.valueOf(16)));
            residual[i] = target.subtract(ExactRational.of(point[i]));
        }
        // LLL keeps mu and squared norms exact as columns are changed. Compute
        // projections from this factorization, avoiding another Gram-Schmidt
        // pass merely to reconstruct stale orthogonal vectors for rounding.
        var projection = new ExactRational[basis.size()];
        for (int j = 0; j < basis.size(); j++) {
            ExactRational value = ExactRational.ZERO;
            var support = supports.get(j);
            for (int word = 0; word < support.length; word++) {
                charge();
                long remaining = support[word];
                while (remaining != 0) {
                    charge();
                    int i = (word << 6) + Long.numberOfTrailingZeros(remaining);
                    remaining &= remaining - 1;
                    value = value.add(residual[i].multiply(ExactRational.of(basis.get(j)[i])));
                }
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
            ExactRational multiple = null;
            for (int i = 0; i < j; i++) {
                charge();
                if (multiple == null) multiple = ExactRational.of(q);
                projection[i] = projection[i].subtract(multiple.multiply(mu[j][i]).multiply(norms[i]));
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
        return value.add(HALF).floor();
    }

    private static BigInteger bounded(BigInteger value) {
        if (value.bitLength() > 512) throw new LocalLimit();
        return value;
    }

    private void charge() {
        budget.check();
        if (++work > allowance && !retaining) throw new LocalLimit();
    }

    private boolean finish() {
        if (counts == null && equationLimit < equations.size() && memory > 0 && (retaining || work < allowance)) {
            equationLimit = equations.size();
            reset();
            return false;
        }
        if (counts == null && trialFaces > 0 && memory > 0 && (retaining || work < allowance) && ++faceAttempt < Math.min(4, trialFaces + 1)) {
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

    CountAffineLattice retained() { retaining = true; return this; }
    boolean paused() { return paused; }
    /** Scheduling evidence from two actual exact dimension reductions, not row count alone. */
    boolean hasCoupledElimination() {
        return exactEliminations >= 2;
    }
    long progress() { return checkpoints + (kernel == null ? 0 : kernel.progress()); }
    void resume(long quantum) {
        if (!retaining || !paused || complete) throw new IllegalStateException("Affine search is not paused");
        allowance = CountContinuation.deadline(work, quantum, budget);
        if (kernel != null && kernel.paused()) kernel.resume(allowance - work);
        paused = false;
    }

    @Override
    public void close() {
        if (kernel != null) kernel.close();
        kernel = null;
        closePotential();
        gram = null;
        budget.release(memory);
        memory = 0;
        complete = true;
        paused = false;
    }

    private static final class LocalLimit extends RuntimeException {}
}
