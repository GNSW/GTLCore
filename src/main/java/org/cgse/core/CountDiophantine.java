// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Integer equation candidates: coupled affine lattices and bounded low-dimensional faces. */
final class CountDiophantine implements CountContinuation {

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private long allowance;
    private BigInteger[] counts;
    private BigInteger a, b, c, rhs, next, end, stride;
    private int x, y, z = -1;
    private long work, memory;
    private boolean complete, retaining, paused, prepared;
    private CountDiophantine face;
    private BigInteger[] faceBase;
    private int[] faceChoices;
    private int faceAttempt, faceCenter;
    private CountAffineLattice affine;
    private List<Integer> freeVariables;

    CountDiophantine(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                     BigInteger[] upper, PlanningBudget budget) {
        this(rows, lower, upper, budget, 131_072);
    }

    CountDiophantine(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                     BigInteger[] upper, PlanningBudget budget, long maximumWork) {
        this.rows = rows;
        this.lower = lower;
        this.upper = upper;
        this.budget = budget;
        allowance = Math.min(maximumWork, Math.min(131_072, budget.remainingWork() / 32));
        if (lower.length > 256 || rows.size() > 512 || allowance < 256) {
            complete = true;
            return;
        }
        var free = new ArrayList<Integer>();
        for (int i = 0; i < lower.length; i++) {
            if (upper[i] == null || lower[i].compareTo(upper[i]) > 0) {
                complete = true;
                return;
            }
            if (!lower[i].equals(upper[i])) free.add(i);
        }
        if (free.size() < 2) {
            complete = true;
            return;
        }
        long bytes = 4096L + 256L * lower.length + 64L * rows.size();
        if (!budget.tryReserve(bytes)) { complete = true; return; }
        memory = bytes;
        freeVariables = free;
    }

    private void prepareEquation(List<Integer> free) {
        Map<Map<Integer, BigInteger>, BigInteger> known = new HashMap<>();
        for (var row : rows) known.merge(row.terms(), row.upper(), BigInteger::min);
        for (int pass = 0; pass < 2; pass++) for (var row : rows) {
            charge();
            Map<Integer, BigInteger> opposite = new HashMap<>();
            row.terms().forEach((id, value) -> opposite.put(id, value.negate()));
            BigInteger reverse = known.get(opposite);
            if (reverse == null || (pass == 0 ? !row.upper().negate().equals(reverse) : row.upper().compareTo(reverse.negate()) <= 0)) continue;
            // On the second pass, use one endpoint of a feasible interval as
            // a trial equality. Extra intermediate stock must not disable the
            // old exact-allocation witness. Every original row is still checked.
            if (free.size() > 3) {
                prepareFaces(row, free);
                return;
            }
            // Leave the shortest interval for enumeration; the other two are
            // solved as one affine lattice, never by walking their large bounds.
            free.sort(Comparator.comparing(i -> upper[i].subtract(lower[i])));
            for (int i = free.size() - 1; i >= 0; i--) for (int j = i - 1; j >= 0; j--) {
                x = free.get(i);
                y = free.get(j);
                a = row.terms().getOrDefault(x, BigInteger.ZERO);
                b = row.terms().getOrDefault(y, BigInteger.ZERO);
                if (a.signum() == 0 || b.signum() == 0) continue;
                for (int id : free) if (id != x && id != y) z = id;
                c = z < 0 ? BigInteger.ZERO : row.terms().getOrDefault(z, BigInteger.ZERO);
                rhs = row.upper();
                for (var term : row.terms().entrySet())
                    if (term.getKey() != x && term.getKey() != y && term.getKey() != z)
                        rhs = rhs.subtract(term.getValue().multiply(lower[term.getKey()]));
                prepare();
                return;
            }
        }
        complete = true;
    }

    private void prepare() {
        next = z < 0 ? BigInteger.ZERO : lower[z];
        end = z < 0 ? BigInteger.ZERO : upper[z];
        BigInteger g = a.gcd(b);
        stride = BigInteger.ONE;
        if (c.signum() == 0) {
            if (rhs.mod(g).signum() != 0) complete = true;
            return;
        }
        BigInteger min = a.multiply(a.signum() > 0 ? lower[x] : upper[x]).add(b.multiply(b.signum() > 0 ? lower[y] : upper[y]));
        BigInteger max = a.multiply(a.signum() > 0 ? upper[x] : lower[x]).add(b.multiply(b.signum() > 0 ? upper[y] : lower[y]));
        if (c.signum() > 0) {
            next = next.max(ceil(rhs.subtract(max), c));
            end = end.min(floor(rhs.subtract(min), c));
        } else {
            next = next.max(ceil(rhs.subtract(min), c));
            end = end.min(floor(rhs.subtract(max), c));
        }
        BigInteger divisor = c.gcd(g);
        if (rhs.mod(divisor).signum() != 0) {
            complete = true;
            return;
        }
        stride = g.divide(divisor);
        BigInteger residue = stride.equals(BigInteger.ONE) ? BigInteger.ZERO : rhs.divide(divisor)
                .multiply(c.divide(divisor).mod(stride).modInverse(stride)).mod(stride);
        next = next.add(residue.subtract(next).mod(stride));
        if (next.compareTo(end) > 0) complete = true;
    }

    @Override public boolean step() {
        if (complete || paused) return true;
        budget.checkpoint();
        if (work >= allowance) {
            if (retaining) return paused = true;
            return complete = true;
        }
        long before = budget.threadWork();
        try { return advance(before); }
        finally { work += budget.threadWork() - before; }
    }

    private boolean advance(long started) {
        if (!prepared) {
            prepared = true;
            if (freeVariables.size() > 3) {
                affine = new CountAffineLattice(rows, lower, upper, budget,
                        retaining ? Math.max(1024, allowance - work) : allowance - work);
                if (retaining) affine.retained();
            } else prepareEquation(freeVariables);
            return complete;
        }
        if (affine != null) {
            if (!affine.step()) return false;
            if (affine.paused()) {
                // The owner may have yielded before the child reached its old
                // deadline. Spend only this owner's still-unused grant instead
                // of reporting an empty paused batch after the next resume.
                long remaining = allowance - work - (budget.threadWork() - started);
                if (remaining > 0) { affine.resume(remaining); return false; }
                return paused = true;
            }
            counts = affine.counts();
            affine.close();
            affine = null;
            if (counts != null) return complete = true;
            prepareEquation(freeVariables);
            freeVariables = null;
            return complete;
        }
        if (faceChoices != null) return stepFaces(started);
        if (next.compareTo(end) > 0) {
            complete = true;
            budget.note("count_diophantine", "unresolved; work=" + work + "; original_domain_retained");
            return true;
        }
        charge();
        BigInteger[] point = lower.clone(), direction = new BigInteger[lower.length];
        Arrays.fill(direction, BigInteger.ZERO);
        if (z >= 0) point[z] = next;
        BigInteger value = rhs.subtract(c.multiply(next)), g = a.gcd(b), period = b.abs().divide(g);
        point[x] = period.equals(BigInteger.ONE) ? BigInteger.ZERO : value.divide(g)
                .multiply(a.divide(g).mod(period).modInverse(period)).mod(period);
        point[y] = value.subtract(a.multiply(point[x])).divide(b);
        direction[x] = period;
        direction[y] = a.divide(g).negate().multiply(BigInteger.valueOf(b.signum()));
        BigInteger low = null, high = null;
        var constraints = new ArrayList<>(rows);
        for (int id : new int[] { x, y }) {
            constraints.add(new ExactLinearProgram.Constraint(Map.of(id, BigInteger.ONE), upper[id]));
            constraints.add(new ExactLinearProgram.Constraint(Map.of(id, BigInteger.ONE.negate()), lower[id].negate()));
        }
        boolean valid = true;
        for (var row : constraints) {
            BigInteger constant = BigInteger.ZERO, slope = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                charge();
                constant = constant.add(term.getValue().multiply(point[term.getKey()]));
                slope = slope.add(term.getValue().multiply(direction[term.getKey()]));
            }
            BigInteger limit = row.upper().subtract(constant);
            if (slope.signum() > 0) {
                var bound = floor(limit, slope);
                high = high == null ? bound : high.min(bound);
            } else if (slope.signum() < 0) {
                var bound = ceil(limit, slope);
                low = low == null ? bound : low.max(bound);
            } else if (limit.signum() < 0) {
                valid = false;
                break;
            }
        }
        if (valid && (low == null || high == null || low.compareTo(high) <= 0)) {
            BigInteger t = low == null ? high == null ? BigInteger.ZERO : high.min(BigInteger.ZERO) : low;
            for (int i = 0; i < point.length; i++) point[i] = point[i].add(direction[i].multiply(t));
            counts = point;
            complete = true;
            budget.note("count_diophantine", "witness; variables=" + point.length + "; work=" + work);
            return true;
        }
        next = next.add(stride);
        return false;
    }

    private void prepareFaces(ExactLinearProgram.Constraint equation, List<Integer> free) {
        Map<Integer, BigInteger> shape = shape(equation);
        for (var row : rows) {
            if (!retaining && work >= allowance) {
                complete = true;
                return;
            }
            var other = shape(row);
            // This candidate is for a single weighted allocation. Coupled
            // independent balances belong to the other quantity strategies.
            if (other.size() > 1 && !shape.equals(other)) {
                complete = true;
                return;
            }
        }
        faceChoices = free.stream().filter(id -> equation.terms().containsKey(id)).mapToInt(Integer::intValue).toArray();
        if (faceChoices.length < 2) {
            complete = true;
            return;
        }
        faceBase = lower.clone();
        BigInteger remaining = equation.upper();
        for (var term : equation.terms().entrySet()) {
            charge();
            int id = term.getKey();
            if (term.getValue().signum() < 0) faceBase[id] = upper[id];
            remaining = remaining.subtract(term.getValue().multiply(faceBase[id]));
        }
        if (remaining.signum() < 0) {
            complete = true;
            return;
        }
        // A greedy exact-integer point merely selects a promising face. It
        // never fixes variables in the caller or supplies a conflict clause.
        boolean centered = false;
        for (int i = 0; i < faceChoices.length; i++) {
            charge();
            int id = faceChoices[i];
            var coefficient = equation.terms().get(id);
            BigInteger width = upper[id].subtract(lower[id]);
            BigInteger count = remaining.divide(coefficient.abs()).min(width);
            faceBase[id] = faceBase[id].add(count.multiply(BigInteger.valueOf(coefficient.signum())));
            remaining = remaining.subtract(count.multiply(coefficient.abs()));
            if (!centered && count.compareTo(width) < 0) {
                faceCenter = i;
                centered = true;
            }
        }
    }

    private Map<Integer, BigInteger> shape(ExactLinearProgram.Constraint row) {
        Map<Integer, BigInteger> values = new TreeMap<>();
        BigInteger gcd = BigInteger.ZERO;
        for (var term : row.terms().entrySet()) {
            charge();
            if (!lower[term.getKey()].equals(upper[term.getKey()]) && term.getValue().signum() != 0) {
                values.put(term.getKey(), term.getValue());
                gcd = gcd.gcd(term.getValue());
            }
        }
        if (!values.isEmpty()) {
            BigInteger divisor = values.values().iterator().next().signum() > 0 ? gcd : gcd.negate();
            values.replaceAll((id, value) -> value.divide(divisor));
        }
        return values;
    }

    private boolean stepFaces(long started) {
        if (faceAttempt >= Math.min(16, faceChoices.length)) {
            complete = true;
            budget.note("count_diophantine", "faces_unresolved; work=" + work + "; original_domain_retained");
            return true;
        }
        if (face == null) {
            BigInteger[] lo = faceBase.clone(), hi = faceBase.clone();
            for (int j = 0; j < Math.min(3, faceChoices.length); j++) {
                int offset = j == 0 ? 0 : j == 1 ? -faceAttempt - 1 : faceAttempt + 1;
                int id = faceChoices[Math.floorMod(faceCenter + offset, faceChoices.length)];
                lo[id] = lower[id];
                hi[id] = upper[id];
            }
            face = new CountDiophantine(rows, lo, hi, budget,
                    retaining ? Math.max(256, allowance - work) : allowance - work);
            if (retaining) face.retained();
        }
        if (!face.step()) return false;
        if (face.paused()) {
            long remaining = allowance - work - (budget.threadWork() - started);
            if (remaining > 0) { face.resume(remaining); return false; }
            return paused = true;
        }
        counts = face.counts();
        face.close();
        face = null;
        faceAttempt++;
        if (counts != null) {
            complete = true;
            budget.note("count_diophantine", "face_witness; original_variables=" + lower.length + "; attempt=" + faceAttempt);
        }
        return complete;
    }

    private void charge() {
        budget.check();
    }

    private static BigInteger floor(BigInteger a, BigInteger b) {
        var qr = a.divideAndRemainder(b);
        return qr[1].signum() != 0 && a.signum() != b.signum() ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }

    private static BigInteger ceil(BigInteger a, BigInteger b) {
        return floor(a.negate(), b).negate();
    }

    @Override public BigInteger[] counts() {
        return counts;
    }

    CountDiophantine retained() { retaining = true; return this; }
    @Override public boolean paused() { return paused; }
    @Override public long work() { return work; }
    boolean hasCoupledElimination() { return affine != null && affine.hasCoupledElimination(); }
    @Override public long progress() {
        return affine != null ? affine.progress() : faceAttempt;
    }
    @Override public boolean matches(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper) {
        return this.rows.equals(rows) && Arrays.equals(this.lower, lower) && Arrays.equals(this.upper, upper);
    }
    @Override public void resume(long quantum) {
        if (!retaining || !paused || complete) throw new IllegalStateException("Diophantine search is not paused");
        allowance = CountContinuation.deadline(work, quantum, budget);
        if (affine != null && affine.paused()) affine.resume(allowance - work);
        if (face != null && face.paused()) face.resume(allowance - work);
        paused = false;
    }

    @Override
    public void close() {
        if (affine != null) affine.close();
        if (face != null) face.close();
        affine = null;
        face = null;
        budget.release(memory);
        memory = 0;
        complete = true;
        paused = false;
    }
}
