// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Sum-product messages order a private finite equality search. Floating values
 * are hints only: exact supports prune domains and original integer rows accept
 * witnesses. Failure, numerical degeneracy and local limits export no proof.
 */
final class CountMessageSearch implements CountContinuation {
    static final long LIMIT = 40_000_000;
    private static final int MAX_WIDTH = 15, MAX_TERMS = 12, MAX_RANGE = 2048;
    private static final int PROPAGATE = 0, COLLECT = 1, FACTOR = 2, BELIEF = 3,
            SELECT = 4, VERIFY = 5;

    private static final class Factor {
        final int row, rhs, range;
        final int[] ids, coefficient;
        final double[][] messages;
        Factor(int row, int rhs, int range, int[] ids, int[] coefficient, int[] widths) {
            this.row = row; this.rhs = rhs; this.range = range;
            this.ids = ids; this.coefficient = coefficient;
            messages = new double[ids.length][];
            for (int k = 0; k < ids.length; k++) {
                messages[k] = new double[widths[ids[k]] + 1];
                Arrays.fill(messages[k], 1.0 / messages[k].length);
            }
        }
    }

    private record Frame(int[] masks, int variable, int[] values, int next) {}

    private final List<ExactLinearProgram.Constraint> original;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private CountEqualitySupport support;
    private Factor[] factors;
    private int[] widths, masks, values;
    private BigInteger[] low, high, proposal, counts;
    private double[][] belief, logs, incoming, prefix;
    private double[] adjoint, nextAdjoint, message;
    private int[] spans;
    private List<Frame> stack;
    private long memory, work, allowance, sweeps, branches, failures;
    private int phase, cursor, collectPart, factorPart, term, value, sweepsLeft;
    private int verifyCursor, bestUnsatisfied = Integer.MAX_VALUE;
    private boolean changed, collectAfter, complete, paused;

    private CountMessageSearch(List<ExactLinearProgram.Constraint> rows, BigInteger[] low,
                               BigInteger[] high, PlanningBudget budget) {
        original = rows; lower = low.clone(); upper = high.clone(); this.budget = budget;
    }

    /** Admission never depends on case identity, solution values or row names. */
    static CountMessageSearch create(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                                     BigInteger[] upper, PlanningBudget budget, long quantum) {
        if (budget.proofJournal() != null || quantum < 1 || lower.length < 2 || lower.length > 512 ||
                upper.length != lower.length || rows.size() < 2 || rows.size() > 2048) return null;
        long terms = 0;
        for (var row : rows) { budget.check(); terms += row.terms().size(); }
        if (terms > 16384) return null;
        // Includes pairing maps, support-independent factor arrays, 1024-bit
        // bound/validation arithmetic, every possible DFS frame, and the peak
        // reusable convolution/adjoint workspace. Support reserves separately.
        long bytes = 1_048_576L + 4096L * lower.length + 1024L * terms +
                512L * rows.size() + 8L * lower.length * lower.length;
        if (!budget.tryReserve(bytes)) return null;
        CountMessageSearch result = null;
        long before = budget.threadWork();
        try {
            result = new CountMessageSearch(rows, lower, upper, budget);
            result.memory = bytes;
            if (!result.prepare()) { result.close(); return null; }
            result.work = budget.threadWork() - before;
            result.allowance = Math.min(LIMIT, result.work + Math.min(quantum, budget.remainingWork()));
            result.paused = true;
            return result;
        } catch (RuntimeException | Error failure) {
            if (result == null) budget.release(bytes); else result.close();
            throw failure;
        }
    }

    private boolean prepare() {
        int n = lower.length;
        widths = new int[n]; masks = new int[n]; values = new int[n];
        belief = new double[n][]; logs = new double[n][];
        for (int i = 0; i < n; i++) {
            budget.check();
            if (lower[i] == null || upper[i] == null || Math.max(lower[i].bitLength(), upper[i].bitLength()) > 1024) return false;
            BigInteger width = upper[i].subtract(lower[i]);
            if (width.signum() < 0 || width.compareTo(BigInteger.valueOf(MAX_WIDTH)) > 0) return false;
            widths[i] = width.intValue(); masks[i] = (1 << (widths[i] + 1)) - 1;
            belief[i] = new double[widths[i] + 1]; logs[i] = new double[widths[i] + 1];
            Arrays.fill(belief[i], 1.0 / belief[i].length);
        }
        var known = new HashSet<>(original);
        var used = new HashSet<ExactLinearProgram.Constraint>();
        var fs = new ArrayList<Factor>();
        for (int r = 0; r < original.size(); r++) {
            budget.check();
            var row = original.get(r);
            if (used.contains(row)) continue;
            if (row.terms().size() < 2 || row.terms().size() > MAX_TERMS || row.upper().bitLength() > 1024) return false;
            var opposite = new LinkedHashMap<Integer, BigInteger>();
            BigInteger divisor = BigInteger.ZERO;
            for (var t : row.terms().entrySet()) {
                budget.check();
                if (t.getKey() < 0 || t.getKey() >= n || t.getValue().signum() == 0 || t.getValue().bitLength() > 1024) return false;
                opposite.put(t.getKey(), t.getValue().negate()); divisor = divisor.gcd(t.getValue());
            }
            var reverse = new ExactLinearProgram.Constraint(opposite, row.upper().negate());
            if (!known.contains(reverse) || row.upper().remainder(divisor).signum() != 0) return false;
            used.add(row); used.add(reverse);
            int[] ids = new int[row.terms().size()], coefficients = new int[ids.length];
            BigInteger rhs = row.upper().divide(divisor);
            int k = 0, range = 0;
            for (var t : row.terms().entrySet()) {
                budget.charge(4);
                int i = t.getKey(); BigInteger a = t.getValue().divide(divisor);
                if (a.abs().compareTo(BigInteger.valueOf(MAX_RANGE)) > 0) return false;
                ids[k] = i; coefficients[k++] = a.intValue();
                range += a.abs().intValue() * widths[i];
                if (range > MAX_RANGE) return false;
                rhs = rhs.subtract(a.multiply(a.signum() > 0 ? lower[i] : upper[i]));
            }
            // Outside the exact bounded sum is a refusal of this optional arm,
            // never an exported infeasibility result.
            if (rhs.signum() < 0 || rhs.compareTo(BigInteger.valueOf(range)) > 0) return false;
            fs.add(new Factor(r, rhs.intValue(), range, ids, coefficients, widths));
        }
        if (fs.isEmpty()) return false;
        factors = fs.toArray(Factor[]::new);
        support = CountEqualitySupport.create(original, lower, upper, budget);
        if (support == null) return false;
        support.expandDomains(ignored -> {});
        incoming = new double[MAX_TERMS][MAX_WIDTH + 1];
        prefix = new double[MAX_TERMS + 1][MAX_RANGE + 1];
        adjoint = new double[MAX_RANGE + 1]; nextAdjoint = new double[MAX_RANGE + 1];
        message = new double[MAX_WIDTH + 1]; spans = new int[MAX_TERMS + 1];
        low = lower.clone(); high = upper.clone(); stack = new ArrayList<>(n);
        sweepsLeft = 8;
        budget.note("count_equality_messages", "variables=" + n + "; factors=" + factors.length + "; proposal_only");
        return true;
    }

    @Override public boolean step() {
        if (complete || paused) return true;
        budget.checkpoint();
        if (work >= LIMIT) { close(); return true; }
        if (work >= allowance) return paused = true;
        long before = budget.threadWork();
        try {
            switch (phase) {
                case PROPAGATE -> propagate();
                case COLLECT -> collect();
                case FACTOR -> factor();
                case BELIEF -> belief();
                case SELECT -> select();
                case VERIFY -> verify();
                default -> throw new IllegalStateException("Unknown equality message phase");
            }
            return complete;
        } finally { work += budget.threadWork() - before; }
    }

    private void propagate() {
        if (cursor == factors.length) {
            cursor = 0;
            if (changed) { changed = false; return; }
            beginCollect(false); return;
        }
        var result = support.propagate(factors[cursor++].row, low, high, masks);
        if (result == null) return;
        if (result.empty()) { reject(); return; }
        for (int k = 0; k < result.variables().length; k++) {
            budget.check(); int i = result.variables()[k], next = masks[i] & result.supports()[k];
            if (next == 0) { reject(); return; }
            if (next != masks[i]) {
                changed = true; masks[i] = next; low[i] = result.lower()[k]; high[i] = result.upper()[k];
            }
        }
    }

    private void beginCollect(boolean after) {
        phase = COLLECT; collectAfter = after; cursor = collectPart = 0;
    }

    private void collect() {
        if (collectPart == 0) {
            if (cursor == lower.length) { cursor = 0; collectPart = 1; return; }
            int i = cursor++;
            budget.charge(logs[i].length);
            for (int v = 0; v < logs[i].length; v++) logs[i][v] = (masks[i] & (1 << v)) == 0 ? -1e100 : 0;
        } else {
            if (cursor == factors.length) {
                cursor = 0; phase = collectAfter ? BELIEF : FACTOR; factorPart = 0; return;
            }
            Factor f = factors[cursor++];
            for (int k = 0; k < f.ids.length; k++) {
                budget.charge(f.messages[k].length);
                for (int v = 0; v < f.messages[k].length; v++) logs[f.ids[k]][v] += StrictMath.log(Math.max(1e-100, f.messages[k][v]));
            }
        }
    }

    /** One slice handles at most one 2049-cell convolution, not an entire sweep. */
    private void factor() {
        if (cursor == factors.length) { beginCollect(true); return; }
        Factor f = factors[cursor];
        if (factorPart == 0) {
            term = value = 0; spans[0] = 0; prefix[0][0] = 1; factorPart = 1;
        }
        if (factorPart == 1) {
            if (term == f.ids.length) {
                budget.charge(f.range + 1L); Arrays.fill(adjoint, 0, f.range + 1, 0);
                adjoint[f.rhs] = 1; term--; value = 0; factorPart = 3; return;
            }
            int i = f.ids[term], a = Math.abs(f.coefficient[term]); double maximum = -1e100;
            budget.charge(4L * (widths[i] + 1)); Arrays.fill(incoming[term], 0);
            for (int v = 0; v <= widths[i]; v++) if ((masks[i] & (1 << v)) != 0)
                maximum = Math.max(maximum, logs[i][v] - StrictMath.log(Math.max(1e-100, f.messages[term][v])));
            for (int v = 0; v <= widths[i]; v++) if ((masks[i] & (1 << v)) != 0)
                incoming[term][v] = StrictMath.exp(Math.max(-700, logs[i][v] - StrictMath.log(Math.max(1e-100, f.messages[term][v])) - maximum));
            normalize(incoming[term], widths[i] + 1);
            spans[term + 1] = spans[term] + a * widths[i];
            budget.charge(spans[term + 1] + 1L); Arrays.fill(prefix[term + 1], 0, spans[term + 1] + 1, 0);
            value = 0; factorPart = 2; return;
        }
        if (factorPart == 2) {
            int i = f.ids[term];
            if (value > widths[i]) { term++; factorPart = 1; return; }
            int v = value++; budget.check();
            if ((masks[i] & (1 << v)) == 0) return;
            int shift = Math.abs(f.coefficient[term]) * (f.coefficient[term] > 0 ? v : widths[i] - v);
            // Nonnegative contribution coordinates can reach the target only
            // when this prefix and its destination leave enough suffix range.
            // The same window bounds the reverse derivative below. Discarded
            // states contribute exactly zero to every factor message.
            int first = Math.max(Math.max(0, f.rhs - (f.range - spans[term])),
                    Math.max(0, f.rhs - (f.range - spans[term + 1])) - shift);
            int last = Math.min(Math.min(spans[term], f.rhs), Math.min(spans[term + 1], f.rhs) - shift);
            budget.charge(8L + Math.max(0, last - first + 1));
            double probability = incoming[term][v];
            for (int s = first; s <= last; s++) prefix[term + 1][s + shift] += prefix[term][s] * probability;
            return;
        }
        if (factorPart == 3) {
            budget.charge(spans[term] + 1L + MAX_WIDTH + 1);
            Arrays.fill(nextAdjoint, 0, spans[term] + 1, 0); Arrays.fill(message, 0);
            value = 0; factorPart = 4; return;
        }
        int i = f.ids[term];
        if (value <= widths[i]) {
            int v = value++; budget.check(); if ((masks[i] & (1 << v)) == 0) return;
            int shift = Math.abs(f.coefficient[term]) * (f.coefficient[term] > 0 ? v : widths[i] - v);
            int first = Math.max(Math.max(0, f.rhs - (f.range - spans[term])),
                    Math.max(0, f.rhs - (f.range - spans[term + 1])) - shift);
            int last = Math.min(Math.min(spans[term], f.rhs), Math.min(spans[term + 1], f.rhs) - shift);
            budget.charge(8L + 2L * Math.max(0, last - first + 1));
            double probability = incoming[term][v], sum = 0;
            for (int s = first; s <= last; s++) {
                sum += prefix[term][s] * adjoint[s + shift]; nextAdjoint[s] += probability * adjoint[s + shift];
            }
            message[v] = sum; return;
        }
        normalize(message, widths[i] + 1); budget.charge(widths[i] + 1L);
        for (int v = 0; v <= widths[i]; v++) f.messages[term][v] = .35 * f.messages[term][v] + .65 * message[v];
        double[] swap = adjoint; adjoint = nextAdjoint; nextAdjoint = swap;
        if (--term < 0) { cursor++; factorPart = 0; } else factorPart = 3;
    }

    private void normalize(double[] data, int length) {
        budget.charge(2L * length); double sum = 0;
        for (int v = 0; v < length; v++) sum += data[v];
        if (sum == 0 || !Double.isFinite(sum)) { Arrays.fill(data, 0, length, 1.0 / length); return; }
        for (int v = 0; v < length; v++) data[v] /= sum;
    }

    private void belief() {
        if (cursor < lower.length) {
            int i = cursor++; budget.charge(3L * belief[i].length); double max = -Double.MAX_VALUE;
            for (double log : logs[i]) max = Math.max(max, log);
            for (int v = 0; v < belief[i].length; v++) belief[i][v] = (masks[i] & (1 << v)) == 0 ? 0 : StrictMath.exp(Math.max(-700, logs[i][v] - max));
            normalize(belief[i], belief[i].length); values[i] = 0;
            for (int v = 1; v < belief[i].length; v++) if (belief[i][v] > belief[i][values[i]]) values[i] = v;
            return;
        }
        // Rounded independent marginals need not agree. They are candidates
        // only; even their exact factor score does not bypass original rows.
        int bad = 0;
        for (Factor f : factors) {
            int sum = 0; budget.charge(f.ids.length);
            for (int k = 0; k < f.ids.length; k++) { int i = f.ids[k]; sum += Math.abs(f.coefficient[k]) * (f.coefficient[k] > 0 ? values[i] : widths[i] - values[i]); }
            if (sum != f.rhs) bad++;
        }
        sweeps++; bestUnsatisfied = Math.min(bestUnsatisfied, bad); sweepsLeft--;
        if (bad == 0) {
            budget.charge(lower.length); proposal = new BigInteger[lower.length];
            for (int i = 0; i < lower.length; i++) proposal[i] = lower[i].add(BigInteger.valueOf(values[i]));
            phase = VERIFY; verifyCursor = 0;
        } else if (sweepsLeft > 0) beginCollect(false);
        else phase = SELECT;
    }

    private void select() {
        int selected = -1; double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < masks.length; i++) {
            budget.check(); if (Integer.bitCount(masks[i]) <= 1) continue;
            double score = 0; budget.charge(belief[i].length);
            for (double p : belief[i]) if (p > 0) score += p * StrictMath.log(p);
            if (score > best) { best = score; selected = i; }
        }
        if (selected < 0) { reject(); return; }
        int[] order = new int[Integer.bitCount(masks[selected])]; int size = 0;
        for (int v = 0; v <= widths[selected]; v++) if ((masks[selected] & (1 << v)) != 0) {
            int at = size++; budget.charge(order.length);
            while (at > 0 && belief[selected][v] > belief[selected][order[at - 1]]) { order[at] = order[at - 1]; at--; }
            order[at] = v;
        }
        stack.add(new Frame(masks, selected, order, 0)); nextBranch();
    }

    private void reject() { failures++; nextBranch(); }

    private void nextBranch() {
        while (!stack.isEmpty()) {
            budget.check(); int last = stack.size() - 1; Frame f = stack.get(last);
            if (f.next == f.values.length) { stack.remove(last); continue; }
            stack.set(last, new Frame(f.masks, f.variable, f.values, f.next + 1));
            budget.charge(3L * lower.length); masks = f.masks.clone(); masks[f.variable] = 1 << f.values[f.next];
            for (int i = 0; i < masks.length; i++) {
                low[i] = lower[i].add(BigInteger.valueOf(Integer.numberOfTrailingZeros(masks[i])));
                high[i] = lower[i].add(BigInteger.valueOf(31 - Integer.numberOfLeadingZeros(masks[i])));
            }
            branches++; phase = PROPAGATE; cursor = 0; changed = false; sweepsLeft = 2; return;
        }
        close();
    }

    private void verify() {
        if (verifyCursor < lower.length) {
            int i = verifyCursor++; budget.charge(2);
            if (proposal[i].compareTo(lower[i]) < 0 || proposal[i].compareTo(upper[i]) > 0) { proposal = null; reject(); }
            return;
        }
        int r = verifyCursor++ - lower.length;
        if (r == original.size()) { counts = proposal; proposal = null; close(); return; }
        var row = original.get(r); BigInteger sum = BigInteger.ZERO;
        for (var t : row.terms().entrySet()) { budget.charge(2); sum = sum.add(t.getValue().multiply(proposal[t.getKey()])); }
        budget.check();
        if (sum.compareTo(row.upper()) > 0) { proposal = null; reject(); }
    }

    @Override public boolean paused() { return paused; }
    @Override public void resume(long quantum) {
        if (!paused || complete) throw new IllegalStateException("Message search is not paused");
        allowance = Math.min(LIMIT, CountContinuation.deadline(work, quantum, budget)); paused = false;
    }
    long remainingAllowance() { return complete ? 0 : Math.max(0, LIMIT - work); }
    @Override public BigInteger[] counts() { return counts == null ? null : counts.clone(); }
    @Override public boolean matches(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {
        return original == rows && Arrays.equals(lower, low) && Arrays.equals(upper, high);
    }
    @Override public long work() { return work; }
    @Override public long progress() { return sweeps; }
    @Override public void close() {
        complete = true; paused = false;
        if (support != null) { support.close(); support = null; }
        if (memory == 0) return;
        budget.note("count_equality_messages", "sweeps=" + sweeps + "; branches=" + branches + "; failures=" + failures +
                "; best_unsatisfied=" + bestUnsatisfied + "; witness=" + (counts != null));
        factors = null; widths = masks = values = spans = null; low = high = proposal = null;
        belief = logs = incoming = prefix = null; adjoint = nextAdjoint = message = null; stack = null;
        budget.release(memory); memory = 0;
    }
}
