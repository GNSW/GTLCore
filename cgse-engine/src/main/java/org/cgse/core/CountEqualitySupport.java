// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.function.IntConsumer;

/** Exact bounded-sum supports borrowed by a private candidate search. */
final class CountEqualitySupport implements AutoCloseable {
    private static final int MAX_TERMS = 12, MAX_WIDTH = 15, MAX_RANGE = 2048;
    private static final int STRIDE = (MAX_RANGE >>> 6) + 1;

    /** Borrowed until the next call. All bounds describe the same input box. */
    record Result(int[] variables, BigInteger[] lower, BigInteger[] upper, boolean empty, int[] supports) {}

    private static final class Equation {
        final ExactLinearProgram.Constraint row;
        final int[] ids;
        final BigInteger[] coefficients, previousLow, previousHigh;
        final int[] previousMasks;
        boolean previousMasked;
        Result previousResult;

        Equation(ExactLinearProgram.Constraint row) {
            this.row = row;
            ids = row.terms().keySet().stream().mapToInt(Integer::intValue).toArray();
            coefficients = row.terms().values().toArray(BigInteger[]::new);
            previousLow = new BigInteger[ids.length];
            previousHigh = new BigInteger[ids.length];
            previousMasks = new int[ids.length];
        }
    }

    private final PlanningBudget budget;
    // One private propagator is visited sequentially, including worker
    // handoffs. The existing 64 KiB scratch reservation covers these arrays.
    // Only exact result arrays escape; no equation cache borrows this scratch.
    private long[] prefix = new long[(MAX_TERMS + 1) * STRIDE];
    private long[] suffix = new long[(MAX_TERMS + 1) * STRIDE];
    private int[] width = new int[MAX_TERMS], coefficient = new int[MAX_TERMS];
    private int[] allowed = new int[MAX_TERMS], shifts = new int[MAX_TERMS];
    private int[] minimum = new int[MAX_TERMS], maximum = new int[MAX_TERMS], supports = new int[MAX_TERMS];
    private boolean[] negative = new boolean[MAX_TERMS];
    private Equation[] equations;
    private BigInteger[] anchors;
    private long memory, calls, changes, failures, expandedCalls;
    private boolean expanded;

    private CountEqualitySupport(PlanningBudget budget, long memory) {
        this.budget = budget;
        this.memory = memory;
    }

    static CountEqualitySupport create(List<ExactLinearProgram.Constraint> rows, BigInteger[] low,
                                       BigInteger[] high, PlanningBudget budget) {
        if (low.length > 512 || rows.size() > 2048 || low.length != high.length) return null;
        long terms = 0;
        for (var row : rows) terms += row.terms().size();
        if (terms > 16384) return null;
        // Pairing maps, cached boxes, exact arithmetic and the largest live
        // prefix/suffix arrays (including temporary shifted words) are covered.
        // Cached pre-update endpoints and supported hulls can each retain
        // distinct 1024-bit integers after the LCG trail has backtracked.
        long bytes = 65536L + 512L * terms + 192L * rows.size() + 128L * low.length;
        if (!budget.tryReserve(bytes)) return null;
        var result = new CountEqualitySupport(budget, bytes);
        try {
            var known = new HashMap<Map<Integer, BigInteger>, Integer>();
            for (int r = 0; r < rows.size(); r++) {
                budget.check();
                var row = rows.get(r);
                Integer before = known.get(row.terms());
                if (before == null || row.upper().compareTo(rows.get(before).upper()) < 0) known.put(row.terms(), r);
            }
            result.equations = new Equation[rows.size()];
            result.anchors = low.clone();
            int pairs = 0;
            for (int r = 0; r < rows.size(); r++) {
                budget.check();
                var row = rows.get(r);
                if (result.equations[r] != null || row.terms().size() < 2 || row.terms().size() > MAX_TERMS) continue;
                var opposite = new HashMap<Integer, BigInteger>();
                boolean small = row.upper().bitLength() <= 1024, weighted = false;
                for (var term : row.terms().entrySet()) {
                    budget.check();
                    int i = term.getKey();
                    if (i < 0 || i >= low.length || low[i] == null || high[i] == null) { small = false; break; }
                    BigInteger width = high[i].subtract(low[i]);
                    small &= width.signum() >= 0 && width.compareTo(BigInteger.valueOf(MAX_WIDTH)) <= 0 &&
                            Math.max(low[i].bitLength(), high[i].bitLength()) <= 1024 && term.getValue().bitLength() <= 1024;
                    weighted |= term.getValue().abs().compareTo(BigInteger.ONE) > 0;
                    opposite.put(i, term.getValue().negate());
                }
                Integer reverse = known.get(opposite);
                if (!small || !weighted || reverse == null || reverse == r || !row.upper().negate().equals(rows.get(reverse).upper())) continue;
                var equation = new Equation(row);
                result.equations[r] = result.equations[reverse] = equation;
                pairs++;
            }
            if (pairs == 0) { result.close(); return null; }
            budget.note("count_equality_support", "pairs=" + pairs + "; variables=" + low.length + "; private_candidate_scope");
            return result;
        } catch (RuntimeException | Error failure) {
            result.close();
            throw failure;
        }
    }

    Result propagate(int rowId, BigInteger[] low, BigInteger[] high) {
        return propagate(rowId, low, high, null);
    }

    /** Strengthen the same private model after its initial search opportunity.
     * Cached refusals must be reconsidered, including equations whose bounds
     * did not change. This changes propagation, not the domain or proof scope. */
    void expandDomains(IntConsumer wake) {
        if (expanded) return;
        expanded = true;
        for (int r = 0; r < equations.length; r++) {
            budget.check();
            if (equations[r] != null) {
                // Invalidate once at the transition rather than adding a
                // phase comparison to every ordinary exact-box cache hit.
                equations[r].previousLow[0] = null;
                equations[r].previousResult = null;
                wake.accept(r);
            }
        }
        budget.note("count_equality_expansion", "bounded_sum_range=" + MAX_RANGE + "; max_terms=" + MAX_TERMS);
    }

    /** Optional masks use the original lower-bound offsets, not the current
     * hull. Both holes and bounds are part of the exact cache key. The caller
     * owns their scoped explanations and restores masks on backtracking. */
    Result propagate(int rowId, BigInteger[] low, BigInteger[] high, int[] masks) {
        // The parent may append certified rows later. Only the immutable,
        // originally paired equations participate in this private analysis.
        if (rowId >= equations.length || equations[rowId] == null) return null;
        Equation equation = equations[rowId];
        int n = equation.ids.length;
        boolean same = equation.previousMasked == (masks != null);
        for (int k = 0; k < n; k++) {
            budget.check();
            int i = equation.ids[k];
            same &= low[i].equals(equation.previousLow[k]) && high[i].equals(equation.previousHigh[k]);
            if (masks != null) same &= masks[i] == equation.previousMasks[k];
        }
        if (same) return equation.previousResult;
        equation.previousResult = null;
        // Equality, rather than a monotonic stamp, also handles backtracking.
        for (int k = 0; k < n; k++) {
            equation.previousLow[k] = low[equation.ids[k]];
            equation.previousHigh[k] = high[equation.ids[k]];
            if (masks != null) equation.previousMasks[k] = masks[equation.ids[k]];
        }
        equation.previousMasked = masks != null;
        BigInteger target = equation.row.upper(), gcd = BigInteger.ZERO;
        int free = 0;
        for (int k = 0; k < n; k++) {
            budget.check();
            int i = equation.ids[k];
            BigInteger a = equation.coefficients[k];
            negative[k] = a.signum() < 0;
            target = target.subtract(a.multiply(negative[k] ? high[i] : low[i]));
            width[k] = high[i].subtract(low[i]).intValueExact();
            if (masks != null) {
                BigInteger offset = low[i].subtract(anchors[i]);
                if (offset.signum() < 0 || offset.compareTo(BigInteger.valueOf(MAX_WIDTH)) > 0 ||
                        width[k] < 0 || width[k] + offset.intValue() > MAX_WIDTH) return null;
                shifts[k] = offset.intValue();
                allowed[k] = (masks[i] >>> shifts[k]) & ((1 << (width[k] + 1)) - 1);
                if (allowed[k] == 0) return empty(equation);
            }
            if (width[k] > 0 && a.signum() != 0) { gcd = gcd.gcd(a); free++; }
        }
        // Keep the initial short-residual path. The later masked phase admits
        // up to twelve coordinates by their bounded sum range, never by
        // enumerating the Cartesian product of their sixteen-value domains.
        if (free < 2 || free > 4 && (!expanded || masks == null) || gcd.signum() == 0) return null;
        calls++;
        if (target.signum() < 0 || target.mod(gcd).signum() != 0) return empty(equation);
        target = target.divide(gcd);
        if (target.bitLength() > 30) return null;
        int rhs = target.intValue(), range = 0;
        for (int k = 0; k < n; k++) {
            budget.check();
            BigInteger value = width[k] == 0 ? BigInteger.ZERO : equation.coefficients[k].abs().divide(gcd);
            if (value.compareTo(BigInteger.valueOf(MAX_RANGE)) > 0) return null;
            coefficient[k] = value.intValue();
            range += coefficient[k] * width[k];
        }
        if (rhs > range) return empty(equation);
        if (range > MAX_RANGE) return null;
        if (free > 4) expandedCalls++;
        if (rhs > range / 2) {
            rhs = range - rhs;
            for (int k = 0; k < n; k++) negative[k] = !negative[k];
        }
        int words = (rhs >>> 6) + 1;
        // Clear every active row, even when the preceding equation used a
        // different arity/range or exited after finding an empty domain.
        for (int k = 0; k <= n; k++) {
            Arrays.fill(prefix, k * STRIDE, k * STRIDE + words, 0L);
            Arrays.fill(suffix, k * STRIDE, k * STRIDE + words, 0L);
        }
        prefix[0] = 1;
        suffix[n * STRIDE + (rhs >>> 6)] = 1L << (rhs & 63);
        long lastMask = -1L >>> (63 - (rhs & 63));
        long wordWork = 1L + (range >>> 6);
        for (int k = 0; k < n; k++) {
            for (int v = 0; v <= width[k]; v++) {
                budget.charge(wordWork);
                if (masks != null && (allowed[k] & (1 << (negative[k] ? width[k] - v : v))) == 0) continue;
                left(k * STRIDE, (k + 1) * STRIDE, words, coefficient[k] * v);
            }
            prefix[(k + 1) * STRIDE + words - 1] &= lastMask;
        }
        if ((prefix[n * STRIDE + (rhs >>> 6)] & (1L << (rhs & 63))) == 0) return empty(equation);
        for (int k = n - 1; k >= 0; k--) {
            for (int v = 0; v <= width[k]; v++) {
                budget.charge(wordWork);
                if (masks != null && (allowed[k] & (1 << (negative[k] ? width[k] - v : v))) == 0) continue;
                right((k + 1) * STRIDE, k * STRIDE, words, coefficient[k] * v);
            }
        }
        boolean changed = false;
        for (int k = 0; k < n; k++) {
            int min = width[k] + 1, max = -1, support = 0;
            for (int v = 0; v <= width[k]; v++) {
                budget.charge(wordWork);
                if (masks != null && (allowed[k] & (1 << (negative[k] ? width[k] - v : v))) == 0) continue;
                if (intersects(k * STRIDE, (k + 1) * STRIDE, words, coefficient[k] * v)) {
                    int value = negative[k] ? width[k] - v : v;
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                    support |= 1 << value;
                }
            }
            if (max < 0) return empty(equation);
            minimum[k] = min;
            maximum[k] = max;
            supports[k] = support;
            changed |= min > 0 || max < width[k];
            if (masks != null) changed |= support != allowed[k];
        }
        if (!changed) return null;
        // Most propagations make no deletion. Allocate exact, cache-owned
        // endpoints only after finding a change, preserving earlier results
        // when another equation overwrites the reusable word workspace.
        var resultLow = new BigInteger[n];
        var resultHigh = new BigInteger[n];
        int[] resultMasks = masks == null ? null : new int[n];
        for (int k = 0; k < n; k++) {
            resultLow[k] = low[equation.ids[k]].add(BigInteger.valueOf(minimum[k]));
            resultHigh[k] = low[equation.ids[k]].add(BigInteger.valueOf(maximum[k]));
            if (masks != null) {
                int span = ((1 << (width[k] + 1)) - 1) << shifts[k];
                resultMasks[k] = (masks[equation.ids[k]] & ~span) | (supports[k] << shifts[k]);
            }
        }
        changes++;
        return equation.previousResult = new Result(equation.ids, resultLow, resultHigh, false, resultMasks);
    }

    /** OR an unsigned left shift into a different prefix row, truncated to words. */
    private void left(int from, int to, int words, int shift) {
        int whole = shift >>> 6, bits = shift & 63;
        for (int i = whole; i < words; i++) {
            long value = prefix[from + i - whole] << bits;
            if (bits != 0 && i > whole) value |= prefix[from + i - whole - 1] >>> (64 - bits);
            prefix[to + i] |= value;
        }
    }

    /** OR an unsigned right shift into a different suffix row. */
    private void right(int from, int to, int words, int shift) {
        int whole = shift >>> 6, bits = shift & 63;
        for (int i = 0; i + whole < words; i++) {
            long value = suffix[from + i + whole] >>> bits;
            if (bits != 0 && i + whole + 1 < words) value |= suffix[from + i + whole + 1] << (64 - bits);
            suffix[to + i] |= value;
        }
    }

    /** Tests P & (S >>> shift) without constructing either temporary bitset. */
    private boolean intersects(int before, int after, int words, int shift) {
        int whole = shift >>> 6, bits = shift & 63;
        for (int i = 0; i + whole < words; i++) {
            long value = suffix[after + i + whole] >>> bits;
            if (bits != 0 && i + whole + 1 < words) value |= suffix[after + i + whole + 1] << (64 - bits);
            if ((prefix[before + i] & value) != 0) return true;
        }
        return false;
    }

    private Result empty(Equation equation) {
        failures++;
        return equation.previousResult = new Result(equation.ids, null, null, true, null);
    }

    @Override public void close() {
        if (memory == 0) return;
        budget.note("count_equality_support", "calls=" + calls + "; changes=" + changes + "; empty_boxes=" + failures +
                "; expanded_calls=" + expandedCalls);
        equations = null;
        anchors = null;
        prefix = suffix = null;
        width = coefficient = allowed = shifts = minimum = maximum = supports = null;
        negative = null;
        budget.release(memory);
        memory = 0;
    }
}
