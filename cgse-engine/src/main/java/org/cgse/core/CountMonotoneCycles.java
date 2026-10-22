// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Compiles small monotone feedback components inside a large sparse model.
 * Only positive combinations of original inequalities leave this helper. */
final class CountMonotoneCycles {
    private record Row(Map<Integer, BigInteger> terms, BigInteger upper, Map<Integer, BigInteger> parents) {}
    private static final class Stop extends RuntimeException {
        private Stop() { super(null, null, false, false); }
    }
    private final List<ExactLinearProgram.Constraint> source;
    private final int variables;
    private final Runnable charge;
    private final BooleanSupplier stopped;
    private final List<CountLpLearning.Cut> result = new ArrayList<>();

    private CountMonotoneCycles(List<ExactLinearProgram.Constraint> source, int variables,
            Runnable charge, BooleanSupplier stopped) {
        this.source = source; this.variables = variables; this.charge = charge; this.stopped = stopped;
    }
    static List<CountLpLearning.Cut> compile(List<ExactLinearProgram.Constraint> source, int variables,
            Runnable charge, BooleanSupplier stopped) {
        var compilation = new CountMonotoneCycles(source, variables, charge, stopped);
        try { compilation.compile(); } catch (Stop stop) { /* Completed consequences remain valid. */ }
        return List.copyOf(compilation.result);
    }
    private void check() { if (stopped.getAsBoolean()) throw new Stop(); charge.run(); }
    private BigInteger multiply(BigInteger a, BigInteger b) {
        for (long i = 0, units = 1 + ((long) a.bitLength() + b.bitLength()) / 64; i < units; i++) check();
        return a.multiply(b);
    }

    private void compile() {
        int[] chosen = new int[variables]; Arrays.fill(chosen, -1);
        for (int i = 0; i < source.size(); i++) {
            check(); var row = source.get(i); int negative = -1;
            if (row.terms().size() < 2 || row.terms().size() > 64 || row.upper().bitLength() > 1024) continue;
            for (var term : row.terms().entrySet()) {
                check();
                if (term.getValue().bitLength() > 1024) { negative = -1; break; }
                if (term.getValue().signum() < 0) {
                    if (negative >= 0) { negative = -1; break; }
                    negative = term.getKey();
                }
            }
            if (negative >= 0 && (chosen[negative] < 0 || row.terms().size() < source.get(chosen[negative]).terms().size()))
                chosen[negative] = i;
        }
        var edges = new ArrayList<List<Integer>>(); var reverse = new ArrayList<List<Integer>>();
        for (int i = 0; i < variables; i++) { check(); edges.add(new ArrayList<>()); reverse.add(new ArrayList<>()); }
        for (int i = 0; i < variables; i++) if (chosen[i] >= 0) {
            for (var term : source.get(chosen[i]).terms().entrySet()) {
                check(); if (term.getValue().signum() <= 0 || chosen[term.getKey()] < 0) continue;
                edges.get(i).add(term.getKey()); reverse.get(term.getKey()).add(i);
            }
        }
        var seen = new BitSet(variables); var order = new ArrayList<Integer>();
        var stack = new ArrayDeque<Integer>(); int[] cursor = new int[variables];
        for (int root = 0; root < variables; root++) {
            check(); if (seen.get(root)) continue;
            seen.set(root); stack.push(root);
            while (!stack.isEmpty()) {
                check(); int at = stack.peek(); var next = edges.get(at);
                if (cursor[at] < next.size()) {
                    int to = next.get(cursor[at]++);
                    if (!seen.get(to)) { seen.set(to); stack.push(to); }
                } else { stack.pop(); order.add(at); }
            }
        }
        seen.clear();
        for (int position = order.size() - 1; position >= 0 && result.size() < 64; position--) {
            check(); int root = order.get(position); if (seen.get(root)) continue;
            var component = new ArrayList<Integer>(); seen.set(root); stack.push(root);
            while (!stack.isEmpty()) {
                check(); int at = stack.pop(); component.add(at);
                for (int to : reverse.get(at)) { check(); if (!seen.get(to)) { seen.set(to); stack.push(to); } }
            }
            if (component.size() < 2 || component.size() > 24) continue;
            Collections.sort(component);
            var rows = new ArrayList<Row>();
            for (int variable : component) {
                check(); int id = chosen[variable]; var original = source.get(id);
                rows.add(new Row(original.terms(), original.upper(), Map.of(id, BigInteger.ONE)));
            }
            boolean admitted = true;
            for (int pivot = 0; pivot < component.size() && admitted; pivot++) {
                int variable = component.get(pivot); Row eliminating = rows.get(pivot);
                BigInteger coefficient = eliminating.terms.getOrDefault(variable, BigInteger.ZERO);
                if (coefficient.signum() >= 0) continue;
                for (int i = 0; i < rows.size(); i++) if (i != pivot) {
                    check(); Row row = rows.get(i); BigInteger positive = row.terms.getOrDefault(variable, BigInteger.ZERO);
                    if (positive.signum() <= 0) continue;
                    Row next = combine(row, eliminating, positive, coefficient);
                    if (next == null) { admitted = false; break; }
                    rows.set(i, next);
                }
            }
            if (!admitted) continue;
            for (Row row : rows) {
                check(); if (row.parents.size() < 2 || result.size() >= 64) continue;
                BigInteger divisor = BigInteger.ZERO;
                for (BigInteger value : row.terms.values()) { check(); divisor = divisor.gcd(value); }
                if (divisor.signum() == 0) divisor = BigInteger.ONE;
                BigInteger scale = divisor;
                var terms = new LinkedHashMap<Integer, BigInteger>();
                row.terms.forEach((key, value) -> { check(); terms.put(key, value.divide(scale)); });
                var qr = row.upper.divideAndRemainder(scale);
                BigInteger upper = qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
                result.add(new CountLpLearning.Cut(new ExactLinearProgram.Constraint(terms, upper), row.parents, scale));
            }
        }
    }

    private Row combine(Row left, Row right, BigInteger positive, BigInteger negative) {
        BigInteger gcd = positive.gcd(negative), lm = negative.negate().divide(gcd), rm = positive.divide(gcd);
        var terms = new TreeMap<Integer, BigInteger>(); var parents = new TreeMap<Integer, BigInteger>();
        for (var e : left.terms.entrySet()) { check(); terms.put(e.getKey(), multiply(e.getValue(), lm)); }
        for (var e : right.terms.entrySet()) { check(); terms.merge(e.getKey(), multiply(e.getValue(), rm), BigInteger::add); }
        terms.values().removeIf(value -> value.signum() == 0);
        if (terms.size() > 128) return null;
        BigInteger upper = multiply(left.upper, lm).add(multiply(right.upper, rm));
        for (var e : left.parents.entrySet()) { check(); parents.put(e.getKey(), multiply(e.getValue(), lm)); }
        for (var e : right.parents.entrySet()) { check(); parents.merge(e.getKey(), multiply(e.getValue(), rm), BigInteger::add); }
        if (upper.bitLength() > 2048) return null;
        for (var v : terms.values()) { check(); if (v.bitLength() > 2048) return null; }
        for (var v : parents.values()) { check(); if (v.bitLength() > 2048) return null; }
        // Reduce only an exact common factor of the complete derivation.
        BigInteger common = upper.abs();
        for (var v : terms.values()) { check(); common = common.gcd(v); }
        for (var v : parents.values()) { check(); common = common.gcd(v); }
        if (common.compareTo(BigInteger.ONE) > 0) {
            BigInteger divisor = common;
            terms.replaceAll((k, v) -> { check(); return v.divide(divisor); });
            parents.replaceAll((k, v) -> { check(); return v.divide(divisor); });
            upper = upper.divide(divisor);
        }
        return new Row(terms, upper, parents);
    }
}
