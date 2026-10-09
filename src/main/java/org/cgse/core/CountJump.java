// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Bounded feasibility-jump search over exact integer counts. This is a local
 * implementation of the weighted-violation and incremental-jump approach used
 * by OR-Tools' feasibility_jump.cc, not an infeasibility prover. Floating point
 * only orders candidate moves; domains, activities and the accepted witness are
 * checked with mathematical integers. No learned constraint escapes this search.
 */
final class CountJump implements AutoCloseable {

    private record Breakpoint(BigInteger numerator, BigInteger denominator, double slope) {}

    private static final class Term {

        final int row, variable;
        final BigInteger coefficient;
        // For a clean binary variable, this is the exact hinge difference for
        // its current proposed flip. Dirty variables never consume this cache.
        BigInteger gain;

        Term(int row, int variable, BigInteger coefficient) {
            this.row = row;
            this.variable = variable;
            this.coefficient = coefficient;
        }

        int row() {
            return row;
        }

        BigInteger coefficient() {
            return coefficient;
        }
    }

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper, values, residual, scales, jumps;
    private final double[] weights, scores;
    private final boolean[] binaryDomains, cacheGains;
    private final List<List<Term>> neighbors = new ArrayList<>();
    private final BitSet movable = new BitSet();
    private final BitSet relevant = new BitSet();
    private final int[] violatedIncidences;
    private boolean wideDomains;
    private long cachedGainBytes;
    private final List<List<Term>> affected = new ArrayList<>();
    private final BitSet dirty = new BitSet(), violated = new BitSet();
    private final PlanningBudget budget;
    private final long pairAfter;
    private long allowance;
    private final SplittableRandom random = new SplittableRandom(0x49f6b58dL);
    private BigInteger[] counts;
    private long memory, work, improvements;
    private double violation, bestViolation = Double.POSITIVE_INFINITY;
    private int initialized, moves, bumps, restarts, stale, pairs, last = -1;
    private boolean complete, pairMode, retained, paused, compoundEligible;

    CountJump(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
              PlanningBudget budget, long allowance) {
        this(rows, lower, upper, budget, allowance, null);
    }

    CountJump(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
              PlanningBudget budget, long allowance, BigInteger[] initial) {
        if (initial != null && initial.length != lower.length) throw new IllegalArgumentException("Initial point dimensions");
        this.rows = rows;
        this.budget = budget;
        this.allowance = Math.min(allowance, budget.remainingWork() / 8);
        pairAfter = this.allowance * 3 / 4;
        long entries = rows.stream().mapToLong(row -> row.terms().size()).sum();
        long bytes = 2048 + 260L * lower.length + 288L * rows.size() + 112L * entries;
        boolean admitted = lower.length <= 1024 && rows.size() <= 4096 && entries <= 65536 &&
                this.allowance >= 1024 && CountModelViews.admissible(lower.length, rows.size(), entries, budget) &&
                budget.tryReserve(bytes);
        memory = admitted ? bytes : 0;
        try {
            this.lower = admitted ? lower.clone() : new BigInteger[0];
            this.upper = admitted ? upper.clone() : new BigInteger[0];
            values = this.lower.clone();
            violatedIncidences = new int[this.lower.length];
            residual = new BigInteger[admitted ? rows.size() : 0];
            scales = new BigInteger[residual.length];
            weights = new double[residual.length];
            jumps = new BigInteger[this.lower.length];
            scores = new double[this.lower.length];
            binaryDomains = new boolean[this.lower.length];
            cacheGains = new boolean[residual.length];
            if (!admitted) {
                complete = true;
                return;
            }
            compoundEligible = lower.length <= 128 && entries <= 8192;
            for (int i = 0; i < lower.length; i++) {
                charge();
                if (initial != null) {
                    values[i] = initial[i].max(lower[i]);
                    if (upper[i] != null) values[i] = values[i].min(upper[i]);
                }
                if (upper[i] == null || upper[i].subtract(lower[i]).compareTo(BigInteger.ONE) > 0) {
                    compoundEligible = false;
                    wideDomains = true;
                }
                affected.add(new ArrayList<>());
                binaryDomains[i] = upper[i] != null && upper[i].subtract(lower[i]).equals(BigInteger.ONE);
            }
            for (int i = 0; i < residual.length; i++) neighbors.add(new ArrayList<>());
            Arrays.fill(weights, 1.0);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    CountJump retained() {
        retained = true;
        return this;
    }

    boolean paused() {
        return paused;
    }

    void resume(long quantum) {
        if (!paused || quantum <= 0) throw new IllegalStateException("Jump search is not paused");
        if (budget.remainingWork() == 0) budget.check();
        allowance = work + Math.min(quantum, budget.remainingWork());
        paused = false;
        complete = false;
    }

    /** Best unweighted violation observed; repeated moves earn no new reward. */
    long progress() {
        return improvements;
    }

    boolean step() {
        if (complete) return true;
        if (work >= allowance) {
            paused = retained;
            return finish(retained ? "local_pause" : "work_limit");
        }
        charge();
        // A retained arm's trajectory must not depend on its first scheduling
        // quantum. The original one-shot arm keeps its old quota split.
        if (!pairMode && values.length <= 128 && (!retained || compoundEligible) &&
                initialized == rows.size() && work >= (retained ? 32_768 : pairAfter)) {
            // A second heuristic starts from the same domains. Its failures
            // have no bearing on any unvisited count or execution ordering.
            pairMode = true;
            System.arraycopy(lower, 0, values, 0, lower.length);
            Arrays.fill(weights, 1.0);
            affected.forEach(List::clear);
            neighbors.forEach(List::clear);
            budget.release(cachedGainBytes);
            memory -= cachedGainBytes;
            cachedGainBytes = 0;
            Arrays.fill(cacheGains, false);
            movable.clear();
            relevant.clear();
            Arrays.fill(violatedIncidences, 0);
            dirty.clear();
            violated.clear();
            initialized = 0;
            violation = 0;
            stale = 0;
            last = -1;
            return false;
        }
        if (initialized < rows.size()) {
            int r = initialized++;
            var row = rows.get(r);
            BigInteger value = row.upper().negate(), scale = BigInteger.ONE, maximum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                charge();
                value = value.add(term.getValue().multiply(values[term.getKey()]));
                scale = scale.max(term.getValue().abs());
                if (term.getValue().signum() != 0 && maximum != null) {
                    BigInteger endpoint = term.getValue().signum() > 0 ? upper[term.getKey()] : lower[term.getKey()];
                    maximum = endpoint == null ? null : maximum.add(term.getValue().multiply(endpoint));
                }
            }
            // A root-domain tautology contributes no violation at any future
            // point. Keep it in the final exact witness check, but omit its
            // constant-zero score and neighbor updates during the walk.
            if (maximum != null && maximum.compareTo(row.upper()) <= 0) value = BigInteger.ZERO;
            else {
                long cacheBytes = 0;
                for (var term : row.terms().entrySet()) {
                    charge();
                    int id = term.getKey();
                    if (lower[id].equals(upper[id]) || term.getValue().signum() == 0) continue;
                    Term edge = new Term(r, id, term.getValue());
                    affected.get(id).add(edge);
                    neighbors.get(r).add(edge);
                    movable.set(id);
                    // For a binary flip, the hinge gain is bounded by |coefficient|.
                    if (binary(id)) cacheBytes += 64L + (term.getValue().bitLength() + 7L) / 8;
                }
                if (budget.tryReserve(cacheBytes)) {
                    // Refusing this optional cache keeps the same arithmetic
                    // and candidate order, without retaining gain objects.
                    cacheGains[r] = true;
                    memory += cacheBytes;
                    cachedGainBytes += cacheBytes;
                }
            }
            residual[r] = value;
            scales[r] = scale;
            violation += ratio(value.max(BigInteger.ZERO), scale);
            violated.set(r, value.signum() > 0);
            if (wideDomains && value.signum() > 0) for (Term term : neighbors.get(r)) {
                charge();
                if (violatedIncidences[term.variable]++ == 0) relevant.set(term.variable);
            }
            if (initialized == rows.size()) dirty.or(movable);
            return false;
        }
        if (violation < bestViolation - 1e-12 * Math.max(1, Math.abs(violation))) {
            bestViolation = violation;
            improvements++;
        }
        if (violated.isEmpty()) {
            // Re-evaluate the original rows instead of trusting cached deltas.
            for (int i = 0; i < values.length; i++) {
                charge();
                if (values[i].compareTo(lower[i]) < 0 || upper[i] != null && values[i].compareTo(upper[i]) > 0)
                    throw new IllegalStateException("Jump count outside domain");
            }
            for (var row : rows) {
                BigInteger sum = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) {
                    charge();
                    sum = sum.add(term.getValue().multiply(values[term.getKey()]));
                }
                if (sum.compareTo(row.upper()) > 0) throw new IllegalStateException("Jump count violates original row");
            }
            counts = values.clone();
            return finish("verified_witness");
        }
        int next = dirty.nextSetBit(0);
        if (next >= 0) {
            dirty.clear(next);
            // A coordinate outside every violated row cannot improve the
            // weighted violation. Keep its expensive integer hinge sweep lazy;
            // touching an affected row makes the coordinate dirty again.
            if (!retained || !wideDomains || relevant.get(next)) recompute(next);
            else {
                // A later binary delta update must not reuse a score that was
                // left stale while this coordinate was outside the frontier.
                jumps[next] = null;
                scores[next] = Double.POSITIVE_INFINITY;
            }
            return false;
        }
        int best = -1;
        // Retain the existing Boolean/compound trajectory. For general integer
        // walks this smaller frontier contains every improving single move.
        BitSet scan = retained && wideDomains ? relevant : movable;
        for (int i = scan.nextSetBit(0); i >= 0; i = scan.nextSetBit(i + 1)) {
            charge();
            if (jumps[i] != null && (best < 0 || scores[i] < scores[best] || scores[i] == scores[best] && i != last && best == last)) best = i;
        }
        if (best >= 0 && scores[best] < -1e-12) {
            move(best, jumps[best]);
            stale = 0;
        } else if (best >= 0 && pairMode && pair()) {
            stale = 0;
        } else {
            bump();
            if (++stale >= 4 || bumps % 32 == 0) {
                perturb();
                stale = 0;
            }
        }
        return false;
    }

    private void recompute(int variable) {
        jumps[variable] = null;
        scores[variable] = Double.POSITIVE_INFINITY;
        if (affected.get(variable).isEmpty()) return;
        if (binary(variable)) {
            jumps[variable] = values[variable].equals(lower[variable]) ? upper[variable] : lower[variable];
            scores[variable] = score(variable, jumps[variable]);
            return;
        }
        Set<BigInteger> candidates = new TreeSet<>();
        candidates.add(lower[variable]);
        if (upper[variable] != null) candidates.add(upper[variable]);
        candidates.add(clamp(variable, values[variable].subtract(BigInteger.ONE)));
        candidates.add(clamp(variable, values[variable].add(BigInteger.ONE)));
        if (!sweep(variable, candidates)) for (Term term : affected.get(variable)) {
            charge();
            BigInteger at = floorDivide(residual[term.row()].negate(), term.coefficient());
            candidate(variable, candidates, at);
        }
        candidates.remove(values[variable]);
        for (BigInteger value : candidates) {
            double score = score(variable, value);
            if (score < scores[variable]) {
                scores[variable] = score;
                jumps[variable] = value;
            }
        }
    }

    /** Weighted hinges form a convex function along one integer coordinate. */
    private boolean sweep(int variable, Set<BigInteger> candidates) {
        var incident = affected.get(variable);
        if (incident.size() < 8) return false;
        long bytes = 1024L + 192L * incident.size();
        for (Term term : incident) {
            charge();
            bytes += 32L + (residual[term.row()].bitLength() + 7L) / 8;
        }
        if (!budget.tryReserve(bytes)) return false;
        try {
            var points = new ArrayList<Breakpoint>(incident.size());
            double slope = 0;
            for (Term term : incident) {
                charge();
                boolean positive = term.coefficient().signum() > 0;
                double change = weights[term.row()] * ratio(term.coefficient().abs(), scales[term.row()]);
                if (!positive) slope -= change;
                points.add(new Breakpoint(positive ? residual[term.row()].negate() : residual[term.row()],
                        term.coefficient().abs(), change));
            }
            points.sort((left, right) -> {
                charge();
                return left.numerator().multiply(right.denominator()).compareTo(right.numerator().multiply(left.denominator()));
            });
            // Keep both ends of a flat minimum. Exact rational ordering avoids
            // merging nearby breakpoints at large recipe counts. Floating
            // weights choose candidates only; score and witness checks below
            // still use exact integer activities and original domains.
            for (int i = 0; i < points.size(); i++) {
                charge();
                Breakpoint point = points.get(i);
                slope += point.slope();
                // Cancellation can leave the terminal zero slope slightly
                // negative. Keep its endpoint even in an unbounded domain.
                if (slope >= 0 || i == points.size() - 1) {
                    candidate(variable, candidates, floorDivide(point.numerator(), point.denominator()));
                    if (slope > 0) break;
                }
            }
            return true;
        } finally {
            budget.release(bytes);
        }
    }

    private void candidate(int variable, Set<BigInteger> candidates, BigInteger delta) {
        candidates.add(clamp(variable, values[variable].add(delta)));
        candidates.add(clamp(variable, values[variable].add(delta).add(BigInteger.ONE)));
    }

    private double score(int variable, BigInteger value) {
        BigInteger delta = value.subtract(values[variable]);
        double score = 0.0;
        for (Term term : affected.get(variable)) {
            charge();
            int r = term.row();
            BigInteger changed = residual[r].add(term.coefficient().multiply(delta));
            BigInteger difference = changed.max(BigInteger.ZERO).subtract(residual[r].max(BigInteger.ZERO));
            if (binary(variable) && cacheGains[r]) term.gain = difference;
            score += weights[r] * ratio(difference, scales[r]);
        }
        return score;
    }

    private void move(int variable, BigInteger value) {
        BigInteger delta = value.subtract(values[variable]);
        values[variable] = value;
        last = variable;
        moves++;
        dirty.set(variable);
        for (Term term : affected.get(variable)) {
            charge();
            int r = term.row();
            BigInteger old = residual[r];
            residual[r] = old.add(term.coefficient().multiply(delta));
            violation += ratio(residual[r].max(BigInteger.ZERO).subtract(old.max(BigInteger.ZERO)), scales[r]);
            violated.set(r, residual[r].signum() > 0);
            for (Term entry : neighbors.get(r)) {
                charge();
                int id = entry.variable;
                if (wideDomains && (old.signum() > 0) != (residual[r].signum() > 0)) {
                    violatedIncidences[id] += residual[r].signum() > 0 ? 1 : -1;
                    relevant.set(id, violatedIncidences[id] > 0);
                }
                if (dirty.get(id)) continue;
                if (binary(id) && jumps[id] != null) {
                    BigInteger change = entry.coefficient.multiply(jumps[id].subtract(values[id]));
                    BigInteger previous = cacheGains[r] && entry.gain != null ? entry.gain :
                            old.add(change).max(BigInteger.ZERO).subtract(old.max(BigInteger.ZERO));
                    BigInteger next = residual[r].add(change).max(BigInteger.ZERO).subtract(residual[r].max(BigInteger.ZERO));
                    scores[id] += weights[r] * ratio(next.subtract(previous), scales[r]);
                    if (cacheGains[r]) entry.gain = next;
                } else dirty.set(id);
            }
        }
        if (moves % 128 == 0) dirty.or(movable);
    }

    private void bump() {
        bumps++;
        for (int r = violated.nextSetBit(0); r >= 0; r = violated.nextSetBit(r + 1)) {
            charge();
            weights[r] += 1.0;
            for (Term entry : neighbors.get(r)) {
                charge();
                int id = entry.variable;
                if (dirty.get(id)) continue;
                if (binary(id) && jumps[id] != null) {
                    BigInteger gain = cacheGains[r] ? entry.gain : null;
                    if (gain == null) {
                        BigInteger next = residual[r].add(entry.coefficient.multiply(jumps[id].subtract(values[id])));
                        gain = next.max(BigInteger.ZERO).subtract(residual[r].max(BigInteger.ZERO));
                    }
                    scores[id] += ratio(gain, scales[r]);
                } else dirty.set(id);
            }
        }
    }

    private boolean binary(int id) {
        return binaryDomains[id];
    }

    /** Cross a one-coordinate barrier without committing an expensive prefix. */
    private boolean pair() {
        if (values.length > 128 || retained && !compoundEligible) return false;
        // Stop only after a complete candidate evaluation and restore the
        // tentative first move. This private quota is independent of portfolio
        // slices; dense or multi-value walks keep ordinary jumps instead.
        long until = retained ? work + 32_768 : allowance;
        List<Integer> choices = new ArrayList<>();
        for (int i = 0; i < values.length; i++) if (jumps[i] != null) choices.add(i);
        choices.sort(Comparator.comparingDouble((Integer i) -> scores[i]).thenComparingInt(i -> i));
        BigInteger[] proposed = jumps.clone();
        double[] firstScores = scores.clone();
        int previous = last;
        for (int k = 0; k < Math.min(6, choices.size()) && work < until; k++) {
            int first = choices.get(k);
            BigInteger original = values[first];
            move(first, proposed[first]);
            for (int second = 0; second < values.length && work < until; second++) {
                charge();
                if (second == first || affected.get(second).isEmpty()) continue;
                recompute(second);
                if (jumps[second] != null && firstScores[first] + scores[second] < -1e-12) {
                    move(second, jumps[second]);
                    pairs++;
                    return true;
                }
            }
            move(first, original);
            last = previous;
        }
        return false;
    }

    private void perturb() {
        restarts++;
        int selected = random.nextInt(violated.cardinality());
        int row = violated.nextSetBit(0);
        while (selected-- > 0) row = violated.nextSetBit(row + 1);
        List<Integer> variables = new ArrayList<>();
        for (int id : rows.get(row).terms().keySet()) {
            charge();
            if (!lower[id].equals(upper[id]) && rows.get(row).terms().get(id).signum() != 0) variables.add(id);
        }
        if (variables.isEmpty()) return;
        int id = variables.get(random.nextInt(variables.size()));
        BigInteger a = rows.get(row).terms().get(id);
        BigInteger delta = floorDivide(residual[row].negate(), a);
        if (a.signum() < 0 && delta.multiply(a).compareTo(residual[row].negate()) > 0) delta = delta.add(BigInteger.ONE);
        BigInteger value = clamp(id, values[id].add(delta));
        if (value.equals(values[id])) {
            value = clamp(id, values[id].add(BigInteger.valueOf(random.nextBoolean() ? 1 : -1)));
        }
        if (!value.equals(values[id])) move(id, value);
    }

    private BigInteger clamp(int id, BigInteger value) {
        value = value.max(lower[id]);
        return upper[id] == null ? value : value.min(upper[id]);
    }

    private static BigInteger floorDivide(BigInteger a, BigInteger b) {
        BigInteger[] qr = a.divideAndRemainder(b);
        return qr[1].signum() != 0 && a.signum() != b.signum() ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }

    private static double ratio(BigInteger a, BigInteger b) {
        if (a.signum() == 0) return 0;
        int left = Math.max(0, a.abs().bitLength() - 52), right = Math.max(0, b.bitLength() - 52);
        double value = a.shiftRight(left).doubleValue() / b.shiftRight(right).doubleValue();
        return Math.copySign(Math.max(1e-100, Math.min(1e100, Math.abs(Math.scalb(value, left - right)))), value);
    }

    private void charge() {
        budget.check();
        work++;
    }

    private boolean finish(String detail) {
        complete = true;
        budget.note("count_jump", detail + "; variables=" + values.length + "; rows=" + rows.size() +
                "; moves=" + moves + "; pairs=" + pairs + "; bumps=" + bumps + "; perturbations=" + restarts + "; work=" + work);
        return true;
    }

    BigInteger[] counts() {
        return counts == null ? null : counts.clone();
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
