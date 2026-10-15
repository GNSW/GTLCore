// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Compiles a bounded integer equation into reusable two-dimensional slices. Candidate only. */
final class CountEquationSlices implements AutoCloseable {
    private record Row(BigInteger x, BigInteger z, BigInteger upper) {}

    private final List<ExactLinearProgram.Constraint> original;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private final int x, y, z;
    private final BigInteger a, b, c, rhs, stride;
    private List<Row> rows;
    private BigInteger next, end, lowX, highX, period, residue, residueStep;
    private BigInteger[] counts;
    private int cursor;
    private long memory;
    private boolean complete, prepared;

    CountEquationSlices(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                        int x, int y, int z, BigInteger a, BigInteger b, BigInteger c, BigInteger rhs,
                        BigInteger next, BigInteger end, BigInteger stride, PlanningBudget budget) {
        original = rows;
        this.lower = lower;
        this.upper = upper;
        this.x = x;
        this.y = y;
        this.z = z;
        this.a = a;
        this.b = b;
        this.c = c;
        this.rhs = rhs;
        this.next = next;
        this.end = end;
        this.stride = stride;
        this.budget = budget;
        lowX = lower[x];
        highX = upper[x];
        long coefficientBits = 1, valueBits = rhs.bitLength();
        for (int i = 0; i < lower.length; i++) {
            budget.check();
            valueBits = Math.max(valueBits, Math.max(lower[i].bitLength(), upper[i].bitLength()));
        }
        for (var row : rows) {
            budget.check();
            valueBits = Math.max(valueBits, row.upper().bitLength());
            for (var value : row.terms().values()) {
                budget.check();
                coefficientBits = Math.max(coefficientBits, value.bitLength());
            }
        }
        // Include projected products/sums, normalization scratch, list slots,
        // and the single lifted witness. No per-slice map or ambient vector.
        long integerBytes = 32 + (2 * coefficientBits + valueBits + 32) / 8;
        long bytes = 1024 + 256L * lower.length + (rows.size() + 2L) * (256 + 8 * integerBytes);
        if (!budget.tryReserve(bytes)) complete = true;
        else {
            memory = bytes;
            this.rows = new ArrayList<>(rows.size() + 2);
        }
    }

    boolean step() {
        if (complete) return true;
        budget.checkpoint();
        if (!prepared) {
            if (cursor < original.size() + 2) {
                var row = cursor < original.size() ? original.get(cursor) :
                        new ExactLinearProgram.Constraint(Map.of(y, cursor == original.size() ?
                                BigInteger.ONE : BigInteger.ONE.negate()),
                                cursor == original.size() ? upper[y] : lower[y].negate());
                cursor++;
                compile(row);
                return complete;
            }
            budget.check();
            BigInteger g = a.gcd(b);
            period = b.abs().divide(g);
            BigInteger inverse = period.equals(BigInteger.ONE) ? BigInteger.ZERO : a.divide(g).mod(period).modInverse(period);
            residue = rhs.subtract(c.multiply(next)).divide(g).multiply(inverse).mod(period);
            residueStep = c.multiply(stride).divide(g).negate().multiply(inverse).mod(period);
            prepared = true;
            return false;
        }
        BigInteger low = lowX, high = highX;
        for (var row : rows) {
            budget.check();
            BigInteger bound = row.upper.subtract(row.z.multiply(next));
            if (row.x.signum() > 0) high = high.min(floor(bound, row.x));
            else low = low.max(ceil(bound, row.x));
            if (low.compareTo(high) > 0) break;
        }
        budget.check();
        if (low.compareTo(high) <= 0) {
            BigInteger value = low.add(residue.subtract(low).mod(period));
            if (value.compareTo(high) <= 0) {
                BigInteger[] found = lower.clone();
                found[x] = value;
                found[y] = rhs.subtract(c.multiply(next)).subtract(a.multiply(value)).divide(b);
                if (z >= 0) found[z] = next;
                verify(found);
                counts = found;
                return complete = true;
            }
        }
        next = next.add(stride);
        residue = residue.add(residueStep).mod(period);
        return complete = next.compareTo(end) > 0;
    }

    private void compile(ExactLinearProgram.Constraint row) {
        BigInteger bound = row.upper();
        for (var term : row.terms().entrySet()) {
            budget.check();
            int id = term.getKey();
            if (id != x && id != y && id != z) bound = bound.subtract(term.getValue().multiply(lower[id]));
        }
        BigInteger ax = row.terms().getOrDefault(x, BigInteger.ZERO);
        BigInteger ay = row.terms().getOrDefault(y, BigInteger.ZERO).multiply(BigInteger.valueOf(b.signum()));
        BigInteger az = row.terms().getOrDefault(z, BigInteger.ZERO);
        // Eliminate y using the exact equation. Multiplication by |b| keeps
        // the inequality direction even when the selected pivot is negative.
        budget.charge(4);
        ax = ax.multiply(b.abs()).subtract(ay.multiply(a));
        az = az.multiply(b.abs()).subtract(ay.multiply(c));
        bound = bound.multiply(b.abs()).subtract(ay.multiply(rhs));
        BigInteger gcd = ax.gcd(az);
        if (gcd.signum() == 0) {
            if (bound.signum() < 0) complete = true;
            return;
        }
        ax = ax.divide(gcd);
        az = az.divide(gcd);
        bound = floor(bound, gcd);
        if (ax.signum() == 0) {
            if (az.signum() > 0) end = end.min(floor(bound, az));
            else {
                BigInteger minimum = ceil(bound, az);
                if (minimum.compareTo(next) > 0) next = next.add(ceil(minimum.subtract(next), stride).multiply(stride));
            }
        } else if (az.signum() == 0) {
            if (ax.signum() > 0) highX = highX.min(floor(bound, ax));
            else lowX = lowX.max(ceil(bound, ax));
        } else rows.add(new Row(ax, az, bound));
        if (lowX.compareTo(highX) > 0 || next.compareTo(end) > 0) complete = true;
    }

    private void verify(BigInteger[] found) {
        for (int i = 0; i < found.length; i++) {
            budget.check();
            if (found[i].compareTo(lower[i]) < 0 || found[i].compareTo(upper[i]) > 0)
                throw new IllegalStateException("Equation slice exceeds original domain");
        }
        for (var row : original) {
            BigInteger value = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.check();
                value = value.add(term.getValue().multiply(found[term.getKey()]));
            }
            if (value.compareTo(row.upper()) > 0) throw new IllegalStateException("Equation slice violates original row");
        }
    }

    private static BigInteger floor(BigInteger a, BigInteger b) {
        var qr = a.divideAndRemainder(b);
        return qr[1].signum() != 0 && a.signum() != b.signum() ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }

    private static BigInteger ceil(BigInteger a, BigInteger b) {
        return floor(a.negate(), b).negate();
    }

    BigInteger[] counts() { return counts; }

    @Override public void close() {
        budget.release(memory);
        memory = 0;
        rows = null;
        complete = true;
    }
}
