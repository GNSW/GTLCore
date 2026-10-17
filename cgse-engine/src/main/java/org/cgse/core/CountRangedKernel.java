// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Enumerate narrow row activities in a two-dimensional integer kernel, not its wide coordinates. */
final class CountRangedKernel implements AutoCloseable {
    private record Direction(BigInteger a, BigInteger b) {}
    private static final class Range {
        BigInteger low, high;
        Range(BigInteger low, BigInteger high) { this.low = low; this.high = high; }
    }

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] low, high;
    private final PlanningBudget budget;
    private final Map<Direction, Range> ranges = new LinkedHashMap<>();
    private Direction direction;
    private BigInteger next, last, firstX, firstY;
    private BigInteger[] counts;
    private int cursor;
    private long memory, activities;
    private boolean prepared, complete;

    CountRangedKernel(List<ExactLinearProgram.Constraint> rows, BigInteger[] low,
                      BigInteger[] high, PlanningBudget budget) {
        this.rows = rows;
        this.low = low;
        this.high = high;
        this.budget = budget;
        if (low.length != 2 || rows.size() > 768) { complete = true; return; }
        for (int i = 0; i < 2; i++) if (high[i] == null || low[i].compareTo(high[i]) > 0 ||
                Math.max(low[i].bitLength(), high[i].bitLength()) > 512) { complete = true; return; }
        long bytes = 4096L + 1024L * rows.size();
        if (!budget.tryReserve(bytes)) complete = true;
        else memory = bytes;
    }

    /** Each completed row/activity is a checkpoint owned and metered by the parent. */
    boolean step() {
        if (complete) return true;
        budget.check();
        if (!prepared) {
            if (cursor < rows.size()) {
                var row = rows.get(cursor++);
                BigInteger a = row.terms().getOrDefault(0, BigInteger.ZERO);
                BigInteger b = row.terms().getOrDefault(1, BigInteger.ZERO);
                if (row.terms().keySet().stream().anyMatch(id -> id < 0 || id >= 2) ||
                        Math.max(Math.max(a.bitLength(), b.bitLength()), row.upper().bitLength()) > 512)
                    return complete = true;
                integerCost(a, b);
                BigInteger gcd = a.gcd(b);
                if (gcd.signum() == 0) return row.upper().signum() < 0 ? complete = true : false;
                boolean positive = (a.signum() == 0 ? b : a).signum() > 0;
                BigInteger divisor = positive ? gcd : gcd.negate();
                var key = new Direction(a.divide(divisor), b.divide(divisor));
                var range = ranges.computeIfAbsent(key, this::box);
                if (positive) range.high = range.high.min(floor(row.upper(), gcd));
                else range.low = range.low.max(floor(row.upper(), gcd).negate());
                return false;
            }
            BigInteger width = null;
            for (var entry : ranges.entrySet()) {
                budget.check();
                var range = entry.getValue();
                BigInteger span = range.high.subtract(range.low);
                if (span.signum() < 0) return complete = true;
                if (span.compareTo(BigInteger.valueOf(127)) > 0 || width != null && span.compareTo(width) >= 0) continue;
                direction = entry.getKey();
                next = range.low;
                last = range.high;
                width = span;
            }
            if (direction == null) return complete = true;
            var bezout = bezout(direction.a, direction.b);
            firstX = bezout[0];
            firstY = bezout[1];
            prepared = true;
            budget.note("count_ranged_kernel", "activities=" + last.subtract(next).add(BigInteger.ONE));
            return false;
        }
        if (next.compareTo(last) > 0) return complete = true;
        BigInteger x = multiply(firstX, next), y = multiply(firstY, next);
        BigInteger dx = direction.b, dy = direction.a.negate();
        var interval = new Range(null, null);
        boolean feasible = bound(interval, dx, high[0].subtract(x)) &&
                bound(interval, dx.negate(), x.subtract(low[0])) &&
                bound(interval, dy, high[1].subtract(y)) &&
                bound(interval, dy.negate(), y.subtract(low[1]));
        for (var row : rows) {
            if (!feasible) break;
            budget.check();
            BigInteger a = row.terms().getOrDefault(0, BigInteger.ZERO);
            BigInteger b = row.terms().getOrDefault(1, BigInteger.ZERO);
            feasible = bound(interval, multiply(a, dx).add(multiply(b, dy)),
                    row.upper().subtract(multiply(a, x)).subtract(multiply(b, y)));
        }
        // Move the frontier only after the whole activity has been checked.
        // A shared budget/cancellation exit cannot skip a partially tested one.
        next = next.add(BigInteger.ONE);
        activities++;
        if (feasible) {
            BigInteger value = BigInteger.ZERO;
            if (interval.low != null) value = value.max(interval.low);
            if (interval.high != null) value = value.min(interval.high);
            counts = new BigInteger[] { x.add(multiply(dx, value)), y.add(multiply(dy, value)) };
            complete = true;
        }
        return complete;
    }

    private Range box(Direction key) {
        return new Range(multiply(key.a, key.a.signum() >= 0 ? low[0] : high[0])
                .add(multiply(key.b, key.b.signum() >= 0 ? low[1] : high[1])),
                multiply(key.a, key.a.signum() >= 0 ? high[0] : low[0])
                        .add(multiply(key.b, key.b.signum() >= 0 ? high[1] : low[1])));
    }

    private boolean bound(Range range, BigInteger slope, BigInteger rhs) {
        budget.check();
        if (slope.signum() == 0) return rhs.signum() >= 0;
        if (slope.signum() > 0) {
            BigInteger value = floor(rhs, slope);
            range.high = range.high == null ? value : range.high.min(value);
        } else {
            // slope*t <= rhs with slope < 0 requires ceil(rhs/slope).
            BigInteger value = floor(rhs, slope.negate()).negate();
            range.low = range.low == null ? value : range.low.max(value);
        }
        return range.low == null || range.high == null || range.low.compareTo(range.high) <= 0;
    }

    private BigInteger[] bezout(BigInteger a, BigInteger b) {
        BigInteger r = a.abs(), next = b.abs(), x = BigInteger.ONE, nx = BigInteger.ZERO,
                y = BigInteger.ZERO, ny = BigInteger.ONE;
        while (next.signum() != 0) {
            budget.check();
            integerCost(r, next);
            var qr = r.divideAndRemainder(next);
            r = next;
            next = qr[1];
            BigInteger change = x.subtract(multiply(qr[0], nx)); x = nx; nx = change;
            change = y.subtract(multiply(qr[0], ny)); y = ny; ny = change;
        }
        return new BigInteger[] { a.signum() < 0 ? x.negate() : x, b.signum() < 0 ? y.negate() : y };
    }

    private BigInteger floor(BigInteger value, BigInteger divisor) {
        integerCost(value, divisor);
        var qr = value.divideAndRemainder(divisor);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }
    private BigInteger multiply(BigInteger a, BigInteger b) { integerCost(a, b); return a.multiply(b); }
    private void integerCost(BigInteger a, BigInteger b) {
        budget.operation(PlanningBudget.Operation.INTEGER, Math.max(a.bitLength(), b.bitLength()));
    }
    BigInteger[] counts() { return counts; }
    long progress() { return cursor + activities; }
    @Override public void close() {
        ranges.clear();
        budget.release(memory);
        memory = 0;
        complete = true;
    }
}
