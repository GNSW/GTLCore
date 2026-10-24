// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** Optional affine source projection. Every result is checked against the original
 * integer rows; an equality choice, floating point result or cutoff proves nothing. */
final class CountDemandProjection implements AutoCloseable {
    private record Edge(int row, BigInteger coefficient) {}
    private static final class Limit extends RuntimeException {}
    private static final int MAX_VARIABLES = 8192, MAX_TERMS = 65536, MAX_BLOCK = 32, MAX_EXPRESSION = 128;
    private final int variables;
    private final int expressionLimit;
    private final List<ExactLinearProgram.Constraint> original;
    private final PlanningBudget budget;
    private final long allowance;
    private long memory, work;
    private final List<List<Edge>> columns = new ArrayList<>();
    private final ArrayDeque<Integer> queue = new ArrayDeque<>();
    private final BitSet queued = new BitSet();
    private BigInteger[] low, high, counts, debts, result;
    private int[] canonical, pivot, freeIndex;
    private final List<Integer> free = new ArrayList<>();
    private final List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
    private final List<int[]> blocks = new ArrayList<>();
    private final Map<Integer, Map<Integer, ExactRational>> expressions = new LinkedHashMap<>();
    private final LinkedHashMap<Map<Integer, BigInteger>, BigInteger> projected = new LinkedHashMap<>();
    private List<ExactLinearProgram.Constraint> reduced;
    private BigInteger[] costs;
    private final CountNumericRelaxation.Session numeric = new CountNumericRelaxation.Session(true);
    private int phase, block, cursor, updates, validation;
    private long visitStart;
    private boolean done;

    CountDemandProjection(int variables, List<ExactLinearProgram.Constraint> rows,
                          PlanningBudget budget, long allowance) {
        this(variables, rows, budget, allowance, MAX_EXPRESSION);
    }

    /** A separate candidate may spend more sparse workspace without changing
     * the ordinary projection's cost or cutoff. Neither limit proves failure. */
    CountDemandProjection(int variables, List<ExactLinearProgram.Constraint> rows,
                          PlanningBudget budget, long allowance, int expressionLimit) {
        if (expressionLimit < 1 || expressionLimit > 512) throw new IllegalArgumentException("Invalid expression limit");
        this.expressionLimit = expressionLimit;
        this.variables = variables; original = rows; this.budget = budget; this.allowance = Math.max(0, allowance);
        if (variables <= 0 || variables > MAX_VARIABLES || rows.size() > 8192 || allowance <= 0) { done = true; return; }
        visitStart = budget.threadWork();
        try {
            long terms = 0;
            for (var row : rows) {
                hit(); terms += row.terms().size(); bounded(row.upper());
                if (terms > MAX_TERMS) throw new Limit();
            }
            reserve(4096L + 512L * variables + 256L * rows.size() + 128L * terms);
            low = new BigInteger[variables]; high = new BigInteger[variables]; Arrays.fill(low, BigInteger.ZERO);
            for (int i = 0; i < variables; i++) columns.add(new ArrayList<>());
            for (int r = 0; r < rows.size(); r++) {
                for (var t : rows.get(r).terms().entrySet()) {
                    hit(); bounded(t.getValue());
                    if (t.getKey() < 0 || t.getKey() >= variables) throw new Limit();
                    columns.get(t.getKey()).add(new Edge(r, t.getValue()));
                }
                enqueue(r);
            }
        } catch (Limit ignored) { done = true; }
        catch (RuntimeException | Error error) { close(); throw error; }
        finally { work += budget.threadWork() - visitStart; }
    }

    boolean step() {
        if (done) return true;
        visitStart = budget.threadWork(); String detail = budget.failureDetail();
        try {
            hit();
            switch (phase) {
                case 0 -> {
                    if (!queue.isEmpty()) { int r = pop(); propagate(r); }
                    else { prepareProjection(); phase = 1; }
                }
                case 1 -> {
                    if (block < blocks.size()) eliminate(blocks.get(block++));
                    else { cursor = 0; phase = 2; }
                }
                case 2 -> {
                    if (cursor < rows.size()) project(rows.get(cursor++));
                    else { cursor = 0; phase = 3; }
                }
                case 3 -> {
                    if (cursor < variables) {
                        int v = cursor++;
                        if (canonical[v] == v && pivot[v] >= 0) {
                            var expression = new LinkedHashMap<Integer, ExactRational>();
                            add(expression, expressions.get(v), ExactRational.ONE.negate());
                            constrain(expression, BigInteger.ZERO);
                        }
                    } else { finishProjection(); phase = 4; }
                }
                case 4 -> {
                    if (free.isEmpty()) { restore(new double[0]); phase = 5; break; }
                    var answer = numeric.solve(free.size(), reduced, costs, budget,
                            Math.max(1, Math.min(262144, remaining())));
                    if (answer == null) { if (!numeric.pending()) throw new Limit(); }
                    else if (answer.point() == null || answer.phaseOneInfeasible()) throw new Limit();
                    else { restore(answer.point()); numeric.close(); phase = 5; }
                }
                case 5 -> {
                    if (!queue.isEmpty()) repair(pop());
                    else { validation = 0; phase = 6; }
                }
                case 6 -> {
                    if (validation < original.size()) {
                        var row = original.get(validation++); BigInteger sum = BigInteger.ZERO;
                        for (var t : row.terms().entrySet()) { hit(); sum = sum.add(t.getValue().multiply(counts[t.getKey()])); }
                        if (sum.compareTo(row.upper()) > 0) throw new Limit();
                    } else {
                        result = counts; done = true;
                        budget.note("count_demand_projection", "verified; variables=" + variables + "; projected=" +
                                free.size() + "; rows=" + reduced.size() + "; repairs=" + updates);
                    }
                }
                default -> throw new IllegalStateException("projection phase");
            }
        } catch (Limit | ExactRational.PrecisionLimit ignored) {
            budget.failureDetail(detail); done = true;
        } finally { work += budget.threadWork() - visitStart; }
        return done;
    }

    private void propagate(int id) {
        var row = original.get(id); BigInteger minimum = BigInteger.ZERO; int unbounded = 0;
        for (var t : row.terms().entrySet()) {
            hit(); BigInteger value = t.getValue().signum() > 0 ? low[t.getKey()] : high[t.getKey()];
            if (value == null) unbounded++; else minimum = minimum.add(t.getValue().multiply(value));
        }
        if (unbounded == 0 && minimum.compareTo(row.upper()) > 0) throw new Limit();
        for (var t : row.terms().entrySet()) {
            hit(); int v = t.getKey(); BigInteger a = t.getValue();
            BigInteger old = a.signum() > 0 ? low[v] : high[v];
            if (unbounded - (old == null ? 1 : 0) != 0 || a.signum() == 0) continue;
            BigInteger rhs = row.upper().subtract(minimum);
            if (old != null) rhs = rhs.add(a.multiply(old));
            BigInteger next;
            if (a.signum() > 0) {
                if (rhs.signum() < 0) throw new Limit();
                next = rhs.divide(a);
                if (high[v] != null && next.compareTo(high[v]) >= 0) continue;
                high[v] = next;
            } else {
                if (rhs.signum() >= 0) continue;
                next = CheckedAmounts.ceilDiv(rhs.negate(), a.negate());
                if (next.compareTo(low[v]) <= 0) continue;
                low[v] = next;
            }
            bounded(next);
            if (high[v] != null && low[v].compareTo(high[v]) > 0) throw new Limit();
            for (var e : columns.get(v)) { hit(); enqueue(e.row()); }
        }
    }

    private void prepareProjection() {
        canonical = new int[variables]; pivot = new int[variables]; Arrays.fill(pivot, -1);
        var signatures = new HashMap<List<Edge>, Integer>();
        for (int v = 0; v < variables; v++) {
            for (var ignored : columns.get(v)) hit();
            Integer first = signatures.putIfAbsent(columns.get(v), v); canonical[v] = first == null ? v : first;
        }
        var groupedLow = new BigInteger[variables]; Arrays.fill(groupedLow, BigInteger.ZERO);
        for (int v = 0; v < variables; v++) { hit(); groupedLow[canonical[v]] = groupedLow[canonical[v]].add(low[v]); }
        var produced = new int[variables];
        var distinct = new LinkedHashMap<Map<Integer, BigInteger>, BigInteger>();
        for (var row : original) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            for (var t : row.terms().entrySet()) { hit(); terms.put(canonical[t.getKey()], t.getValue()); }
            // Forced gross production can repeat the target material row.
            // Count distinct obligations when identifying single-output
            // pivots; a duplicate row is not another physical coproduct.
            BigInteger old = distinct.get(terms);
            if (old == null) { reserve(192L + 80L * terms.size()); distinct.put(terms, row.upper()); }
            else if (row.upper().compareTo(old) < 0) distinct.put(terms, row.upper());
        }
        for (var entry : distinct.entrySet()) {
            hit(); var terms = entry.getKey();
            rows.add(new ExactLinearProgram.Constraint(terms, entry.getValue()));
            for (var t : terms.entrySet()) if (t.getValue().signum() < 0) produced[t.getKey()]++;
        }
        ExactRational[] strength = new ExactRational[variables];
        for (int r = 0; r < rows.size(); r++) {
            int producer = -1; var row = rows.get(r); BigInteger other = BigInteger.ZERO;
            for (var t : row.terms().entrySet()) {
                hit();
                if (t.getValue().signum() < 0) { if (producer >= 0) { producer = -2; break; } producer = t.getKey(); }
            }
            if (producer < 0 || produced[producer] > 1 ||
                    groupedLow[producer].signum() == 0 && row.upper().signum() > 0) continue;
            for (var t : row.terms().entrySet()) {
                hit(); if (t.getKey() != producer) other = other.add(t.getValue().multiply(groupedLow[t.getKey()]));
            }
            var value = new ExactRational(other.subtract(row.upper()), row.terms().get(producer).negate());
            if (strength[producer] == null || value.compareTo(strength[producer]) > 0) {
                strength[producer] = value; pivot[producer] = r;
            }
        }
        buildBlocks();
        for (int v = 0; v < variables; v++) {
            hit(); if (canonical[v] != v || pivot[v] >= 0) continue;
            free.add(v); var expression = new LinkedHashMap<Integer, ExactRational>(); put(expression, v, ExactRational.ONE);
            expressions.put(v, expression);
        }
        if (free.size() > 512) throw new Limit();
        freeIndex = new int[variables]; Arrays.fill(freeIndex, -1);
        for (int i = 0; i < free.size(); i++) freeIndex[free.get(i)] = i;
    }

    /** Iterative Kosaraju; dependency edges are visited in reverse component order. */
    private void buildBlocks() {
        var edges = new ArrayList<List<Integer>>(); var reverse = new ArrayList<List<Integer>>();
        for (int v = 0; v < variables; v++) { edges.add(new ArrayList<>()); reverse.add(new ArrayList<>()); }
        for (int v = 0; v < variables; v++) {
            if (pivot[v] < 0) continue;
            for (int k : rows.get(pivot[v]).terms().keySet()) {
                hit(); if (k != v && pivot[k] >= 0) { edges.get(v).add(k); reverse.get(k).add(v); }
            }
        }
        var seen = new BitSet(); var order = new ArrayList<Integer>();
        int[] stack = new int[variables], position = new int[variables];
        for (int first = 0; first < variables; first++) {
            if (pivot[first] < 0 || seen.get(first)) continue;
            int depth = 0; stack[0] = first; seen.set(first);
            while (depth >= 0) {
                hit(); int v = stack[depth]; var next = edges.get(v);
                if (position[v] < next.size()) { int k = next.get(position[v]++); if (!seen.get(k)) { seen.set(k); stack[++depth] = k; } }
                else { order.add(v); depth--; }
            }
        }
        seen.clear();
        for (int p = order.size() - 1; p >= 0; p--) {
            int first = order.get(p); if (seen.get(first)) continue;
            var members = new ArrayList<Integer>(); int size = 1; stack[0] = first; seen.set(first);
            while (size > 0) {
                hit(); int v = stack[--size]; members.add(v);
                for (int k : reverse.get(v)) { hit(); if (!seen.get(k)) { seen.set(k); stack[size++] = k; } }
            }
            if (members.size() > MAX_BLOCK) throw new Limit();
            members.sort(Integer::compare); blocks.add(members.stream().mapToInt(Integer::intValue).toArray());
        }
        Collections.reverse(blocks);
    }

    private void eliminate(int[] block) {
        int n = block.length; reserve(1024L + 768L * n * n);
        var indices = new HashMap<Integer, Integer>();
        for (int i = 0; i < n; i++) indices.put(block[i], i);
        var matrix = new ExactRational[n][n]; var rhs = new ArrayList<Map<Integer, ExactRational>>();
        for (int p = 0; p < n; p++) {
            Arrays.fill(matrix[p], ExactRational.ZERO); var row = rows.get(pivot[block[p]]);
            var value = new LinkedHashMap<Integer, ExactRational>(); put(value, -1, ExactRational.of(row.upper()));
            for (var t : row.terms().entrySet()) {
                hit(); Integer at = indices.get(t.getKey());
                if (at != null) matrix[p][at] = ExactRational.of(t.getValue());
                else add(value, expressions.get(t.getKey()), ExactRational.of(t.getValue().negate()));
            }
            rhs.add(value);
        }
        for (int c = 0; c < n; c++) {
            int p = c; while (p < n && matrix[p][c].signum() == 0) { hit(); p++; }
            if (p == n) throw new Limit();
            var saved = matrix[c]; matrix[c] = matrix[p]; matrix[p] = saved;
            var value = rhs.get(c); rhs.set(c, rhs.get(p)); rhs.set(p, value);
            var divisor = matrix[c][c];
            for (int j = c; j < n; j++) { hit(); matrix[c][j] = matrix[c][j].divide(divisor); }
            divide(rhs.get(c), divisor);
            for (p = 0; p < n; p++) {
                if (p == c || matrix[p][c].signum() == 0) continue;
                var scale = matrix[p][c];
                for (int j = c; j < n; j++) { hit(); matrix[p][j] = matrix[p][j].subtract(matrix[c][j].multiply(scale)); }
                add(rhs.get(p), rhs.get(c), scale.negate());
            }
        }
        for (int i = 0; i < n; i++) expressions.put(block[i], rhs.get(i));
    }

    private void project(ExactLinearProgram.Constraint row) {
        var value = new LinkedHashMap<Integer, ExactRational>();
        for (var t : row.terms().entrySet()) { hit(); add(value, expressions.get(t.getKey()), ExactRational.of(t.getValue())); }
        constrain(value, row.upper());
    }

    private void constrain(Map<Integer, ExactRational> terms, BigInteger upper) {
        var constant = terms.remove(-1); var bound = ExactRational.of(upper);
        if (constant != null) bound = bound.subtract(constant);
        if (terms.isEmpty()) { if (bound.signum() < 0) throw new Limit(); return; }
        BigInteger denominator = bound.denominator();
        for (var v : terms.values()) { hit(); denominator = lcm(denominator, v.denominator()); }
        var integers = new LinkedHashMap<Integer, BigInteger>(); BigInteger common = BigInteger.ZERO;
        for (var t : terms.entrySet()) {
            hit(); BigInteger a = t.getValue().numerator().multiply(denominator.divide(t.getValue().denominator())); bounded(a);
            integers.put(freeIndex[t.getKey()], a); common = common.gcd(a.abs());
        }
        BigInteger b = bound.numerator().multiply(denominator.divide(bound.denominator()));
        if (common.signum() == 0) throw new Limit();
        BigInteger divisor = common;
        integers.replaceAll((k, v) -> v.divide(divisor)); b = floor(b, common); bounded(b);
        BigInteger old = projected.get(integers);
        if (old == null) { reserve(128L + 192L * integers.size()); projected.put(integers, b); }
        else if (b.compareTo(old) < 0) projected.put(integers, b);
        if (projected.size() > 2048) throw new Limit();
    }

    private void finishProjection() {
        reduced = projected.entrySet().stream().map(e -> new ExactLinearProgram.Constraint(e.getKey(), e.getValue())).toList();
        var sum = new LinkedHashMap<Integer, ExactRational>();
        // The objective may have more support than an individual expression.
        for (var e : expressions.values()) for (var t : e.entrySet()) {
            hit(); if (t.getKey() < 0) continue;
            var old = sum.getOrDefault(t.getKey(), ExactRational.ZERO);
            if (!sum.containsKey(t.getKey())) reserve(768);
            sum.put(t.getKey(), old.subtract(t.getValue()));
        }
        BigInteger denominator = BigInteger.ONE;
        for (var value : sum.values()) { hit(); denominator = lcm(denominator, value.denominator()); }
        costs = new BigInteger[free.size()]; Arrays.fill(costs, BigInteger.ZERO);
        for (var t : sum.entrySet()) { hit(); costs[freeIndex[t.getKey()]] = t.getValue().numerator().multiply(denominator.divide(t.getValue().denominator())); bounded(costs[freeIndex[t.getKey()]]); }
        budget.note("count_demand_projection", "compiled; variables=" + variables + "; projected=" + free.size() + "; rows=" + reduced.size());
    }

    private void restore(double[] point) {
        if (point.length != free.size()) throw new Limit();
        var values = new ExactRational[point.length];
        for (int i = 0; i < point.length; i++) {
            hit(); if (!Double.isFinite(point[i])) throw new Limit();
            BigDecimal number = BigDecimal.valueOf(point[i]); int scale = number.scale();
            if (Math.abs(scale) > 256) throw new Limit();
            values[i] = scale >= 0 ? new ExactRational(number.unscaledValue(), BigInteger.TEN.pow(scale)) :
                    ExactRational.of(number.unscaledValue().multiply(BigInteger.TEN.pow(-scale)));
        }
        counts = new BigInteger[variables]; Arrays.fill(counts, BigInteger.ZERO);
        for (var entry : expressions.entrySet()) {
            var sum = ExactRational.ZERO;
            for (var t : entry.getValue().entrySet()) {
                hit(); sum = sum.add(t.getKey() < 0 ? t.getValue() : t.getValue().multiply(values[freeIndex[t.getKey()]]));
            }
            hit(); int v = entry.getKey();
            BigInteger proposed = sum.signum() <= 0 ? BigInteger.ZERO : sum.ceil();
            // These bounds come from exact propagation of the original rows.
            // A numerical point just above an integer capacity must not seed
            // an irreversible overrun in the increment-only repair. Clipping
            // is only a candidate choice; every original row is still checked.
            proposed = proposed.max(low[v]);
            if (high[v] != null) proposed = proposed.min(high[v]);
            counts[v] = proposed; bounded(counts[v]);
        }
        debts = new BigInteger[original.size()]; queue.clear(); queued.clear();
        for (int r = 0; r < original.size(); r++) {
            var row = original.get(r); BigInteger value = row.upper().negate();
            for (var t : row.terms().entrySet()) { hit(); value = value.add(t.getValue().multiply(counts[t.getKey()])); }
            debts[r] = value; if (value.signum() > 0) enqueue(r);
        }
    }

    private void repair(int row) {
        if (debts[row].signum() <= 0) return;
        if (++updates > 131072) throw new Limit();
        int best = -1, bestDebts = Integer.MAX_VALUE; double bestWeight = Double.POSITIVE_INFINITY;
        BigInteger runs = null;
        for (var source : original.get(row).terms().entrySet()) {
            hit(); int v = source.getKey(); if (source.getValue().signum() >= 0 || canonical[v] != v) continue;
            BigInteger extra = CheckedAmounts.ceilDiv(debts[row], source.getValue().negate()); int negative = 0; double weight = 0;
            for (var e : columns.get(v)) {
                hit(); BigInteger after = debts[e.row()].add(e.coefficient().multiply(extra));
                if (after.signum() <= 0) continue;
                negative++; BigInteger gain = BigInteger.ONE;
                for (var a : original.get(e.row()).terms().values()) { hit(); if (a.signum() < 0) gain = gain.max(a.negate()); }
                weight += after.doubleValue() / gain.doubleValue();
            }
            if (negative < bestDebts || negative == bestDebts && (weight < bestWeight || weight == bestWeight && (runs == null || extra.compareTo(runs) < 0))) {
                best = v; runs = extra; bestDebts = negative; bestWeight = weight;
            }
        }
        if (best < 0) throw new Limit();
        counts[best] = counts[best].add(runs); bounded(counts[best]);
        for (var e : columns.get(best)) {
            hit(); debts[e.row()] = debts[e.row()].add(e.coefficient().multiply(runs));
            if (debts[e.row()].signum() > 0) enqueue(e.row());
        }
    }

    private void add(Map<Integer, ExactRational> target, Map<Integer, ExactRational> source, ExactRational factor) {
        if (source == null) throw new Limit();
        for (var t : source.entrySet()) { hit(); put(target, t.getKey(), target.getOrDefault(t.getKey(), ExactRational.ZERO).add(t.getValue().multiply(factor))); }
    }
    private void put(Map<Integer, ExactRational> target, int key, ExactRational value) {
        if (value.signum() == 0) { target.remove(key); return; }
        if (!target.containsKey(key)) { if (target.size() >= expressionLimit) throw new Limit(); reserve(768); }
        target.put(key, value);
    }
    private void divide(Map<Integer, ExactRational> row, ExactRational divisor) {
        for (var t : row.entrySet()) { hit(); t.setValue(t.getValue().divide(divisor)); }
    }
    private static BigInteger floor(BigInteger value, BigInteger positive) {
        var parts = value.divideAndRemainder(positive);
        return value.signum() < 0 && parts[1].signum() != 0 ? parts[0].subtract(BigInteger.ONE) : parts[0];
    }
    private static BigInteger lcm(BigInteger a, BigInteger b) { BigInteger next = a.divide(a.gcd(b)).multiply(b); bounded(next); return next; }
    private static void bounded(BigInteger value) { if (value.bitLength() > 1024) throw new Limit(); }
    private void enqueue(int row) { if (!queued.get(row)) { queued.set(row); queue.addLast(row); } }
    private int pop() { int row = queue.removeFirst(); queued.clear(row); return row; }
    private long remaining() { return allowance - work - (budget.threadWork() - visitStart); }
    private void hit() { budget.check(); if (remaining() <= 0) throw new Limit(); }
    private void reserve(long bytes) { if (!budget.tryReserve(bytes)) throw new Limit(); memory += bytes; }
    BigInteger[] result() { return result; }
    long work() { return work; }
    @Override public void close() {
        numeric.close(); budget.release(memory); memory = 0;
        columns.clear(); rows.clear(); blocks.clear(); queue.clear(); expressions.clear(); projected.clear();
        free.clear(); reduced = null; costs = null; canonical = pivot = freeIndex = null;
        low = high = debts = null; done = true;
    }
}
