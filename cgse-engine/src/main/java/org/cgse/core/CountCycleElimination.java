// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded sparse elimination of an active propagation loop. Every proposal
 * retains an exact positive combination of original rows for proof replay. */
final class CountCycleElimination {
    private record State(Map<Integer, BigInteger> terms, BigInteger upper,
                         Map<Integer, BigInteger> parents, int used) {}
    private static final class Stop extends RuntimeException {
        private Stop() { super(null, null, false, false); }
    }
    private final List<ExactLinearProgram.Constraint> rows;
    private final List<Integer> recent;
    private final BigInteger[] low, high;
    private final Runnable charge;
    private final BooleanSupplier stopped;

    private CountCycleElimination(List<ExactLinearProgram.Constraint> rows, List<Integer> recent,
            BigInteger[] low, BigInteger[] high, Runnable charge, BooleanSupplier stopped) {
        this.rows = rows; this.recent = recent; this.low = low; this.high = high;
        this.charge = charge; this.stopped = stopped;
    }

    static CountLpLearning.Cut find(List<ExactLinearProgram.Constraint> rows, List<Integer> recent,
            BigInteger[] low, BigInteger[] high, Runnable charge, BooleanSupplier stopped) {
        if (recent.size() < 3 || recent.size() > 16) return null;
        try { return new CountCycleElimination(rows, recent, low, high, charge, stopped).search(); }
        catch (Stop stop) { return null; }
    }

    private void check() { if (stopped.getAsBoolean()) throw new Stop(); charge.run(); }

    private BigInteger product(BigInteger a, BigInteger b) {
        for (long i = 0, units = 1 + ((long) a.bitLength() + b.bitLength()) / 64; i < units; i++) check();
        return a.multiply(b);
    }

    private CountLpLearning.Cut search() {
        for (int root = 0; root < recent.size(); root++) {
            check(); int id = recent.get(root); var row = rows.get(id);
            var state = new State(row.terms(), row.upper(), Map.of(id, BigInteger.ONE), 1 << root);
            var beam = List.of(state);
            for (int depth = 1; depth < Math.min(8, recent.size()); depth++) {
                var next = new ArrayList<State>();
                for (var left : beam) for (int i = 0; i < recent.size(); i++) {
                    check();
                    if ((left.used & (1 << i)) != 0) continue;
                    var right = rows.get(recent.get(i));
                    for (var pivot : left.terms.entrySet()) {
                        check(); var opposite = right.terms().get(pivot.getKey());
                        if (opposite == null || opposite.signum() * pivot.getValue().signum() >= 0) continue;
                        var joined = combine(left, right, recent.get(i), i, pivot.getValue(), opposite);
                        if (joined == null) continue;
                        var cut = contradicted(joined);
                        if (cut != null) return cut;
                        // A path through a loop need not shrink at every edge.
                        // Keep a small deterministic frontier, never a proof of
                        // infeasibility when no useful consequence is found.
                        if (!next.contains(joined)) next.add(joined);
                        if (next.size() > 16) {
                            next.sort(Comparator.comparingInt(s -> s.terms.size()));
                            next.remove(next.size() - 1);
                        }
                    }
                }
                if (next.isEmpty()) break;
                beam = next;
            }
        }
        return null;
    }

    private State combine(State left, ExactLinearProgram.Constraint right, int id, int position,
                          BigInteger a, BigInteger b) {
        var gcd = a.gcd(b); var lm = b.abs().divide(gcd); var rm = a.abs().divide(gcd);
        var terms = new TreeMap<Integer, BigInteger>();
        for (var entry : left.terms.entrySet()) { check(); terms.put(entry.getKey(), product(entry.getValue(), lm)); }
        for (var entry : right.terms().entrySet()) { check(); terms.merge(entry.getKey(), product(entry.getValue(), rm), BigInteger::add); }
        terms.values().removeIf(v -> v.signum() == 0);
        if (terms.size() > 16 || terms.size() > Math.max(left.terms.size(), right.terms().size())) return null;
        BigInteger upper = product(left.upper, lm).add(product(right.upper(), rm));
        if (upper.bitLength() > 2048 || terms.values().stream().anyMatch(v -> v.bitLength() > 2048)) return null;
        var parents = new LinkedHashMap<Integer, BigInteger>();
        for (var entry : left.parents.entrySet()) { check(); parents.put(entry.getKey(), product(entry.getValue(), lm)); }
        parents.merge(id, rm, BigInteger::add);
        if (parents.values().stream().anyMatch(v -> v.bitLength() > 2048)) return null;
        return new State(terms, upper, parents, left.used | (1 << position));
    }

    private CountLpLearning.Cut contradicted(State state) {
        BigInteger divisor = BigInteger.ZERO;
        for (var value : state.terms.values()) { check(); divisor = divisor.gcd(value); }
        if (divisor.signum() == 0) divisor = BigInteger.ONE;
        var qr = state.upper.divideAndRemainder(divisor);
        BigInteger upper = qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
        var terms = new TreeMap<Integer, BigInteger>(); BigInteger minimum = BigInteger.ZERO;
        for (var entry : state.terms.entrySet()) {
            check(); BigInteger coefficient = entry.getValue().divide(divisor);
            BigInteger bound = coefficient.signum() > 0 ? low[entry.getKey()] : high[entry.getKey()];
            if (bound == null) return null;
            terms.put(entry.getKey(), coefficient); minimum = minimum.add(product(coefficient, bound));
        }
        return minimum.compareTo(upper) > 0 ? new CountLpLearning.Cut(
                new ExactLinearProgram.Constraint(terms, upper), Map.copyOf(state.parents), divisor) : null;
    }
}
