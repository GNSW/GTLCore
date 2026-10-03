package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** Bounded exact negative-cycle detection for the difference-constraint subset. */
final class CountDifference {

    private CountDifference() {}

    private record Edge(int from, int to, BigInteger bound, CountProof.Row row, BigInteger scale) {}

    static CountProof.Certificate contradiction(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                                                BigInteger[] upper, PlanningBudget budget, long maximumWork) {
        long allowance = Math.min(Math.min(8192, maximumWork), budget.remainingWork() / 32);
        if (allowance < 512 || lower.length < 2 || lower.length > 512 || rows.size() > 2048) return null;
        long bytes = 512L + 256L * rows.size() + 512L * lower.length;
        if (!budget.tryReserve(bytes)) return null;
        long start = budget.threadWork();
        try {
            var edges = new ArrayList<Edge>();
            for (var row : rows) {
                budget.operation(PlanningBudget.Operation.SCAN, 0);
                if (row.terms().size() != 2) continue;
                var terms = row.terms().entrySet().iterator();
                var a = terms.next();
                var b = terms.next();
                if (a.getValue().signum() == 0 || !a.getValue().equals(b.getValue().negate())) continue;
                int to = a.getValue().signum() > 0 ? a.getKey() : b.getKey();
                int from = a.getValue().signum() > 0 ? b.getKey() : a.getKey();
                BigInteger scale = a.getValue().abs();
                // Keep a rational-exact edge. Non-divisible bounds are left to
                // integer normalization; a Farkas certificate must not round.
                if (row.upper().remainder(scale).signum() != 0) continue;
                edges.add(new Edge(from, to, row.upper().divide(scale), CountProof.row(row), scale));
            }
            if (edges.size() < 2) return null;
            int origin = lower.length;
            for (int i = 0; i < lower.length; i++) {
                edges.add(new Edge(i, origin, lower[i].negate(),
                        new CountProof.Row(Map.of(i, BigInteger.ONE.negate()), lower[i].negate()), BigInteger.ONE));
                if (upper[i] != null) edges.add(new Edge(origin, i, upper[i],
                        new CountProof.Row(Map.of(i, BigInteger.ONE), upper[i]), BigInteger.ONE));
            }
            int n = lower.length + 1;
            BigInteger[] distances = new BigInteger[n];
            Arrays.fill(distances, BigInteger.ZERO);
            int[] predecessor = new int[n];
            Arrays.fill(predecessor, -1);
            int last = -1;
            for (int pass = 0; pass < n; pass++) {
                last = -1;
                for (int i = 0; i < edges.size(); i++) {
                    if (budget.threadWork() - start >= allowance) return null;
                    var edge = edges.get(i);
                    budget.operation(PlanningBudget.Operation.INTEGER, Math.max(distances[edge.from].bitLength(), edge.bound.bitLength()));
                    BigInteger next = distances[edge.from].add(edge.bound);
                    if (next.compareTo(distances[edge.to]) < 0) {
                        distances[edge.to] = next;
                        predecessor[edge.to] = i;
                        last = edge.to;
                    }
                }
                if (last < 0) return null;
            }
            // Distances start at an artificial super-source; they are NOT
            // variable bounds and must never be used to prune recipe counts.
            // Only a checked negative cycle is exported as a contradiction.
            for (int i = 0; i < n; i++) {
                budget.check();
                if (predecessor[last] < 0) return null;
                last = edges.get(predecessor[last]).from;
            }
            int at = last;
            var axioms = new ArrayList<CountProof.Row>();
            var weights = new ArrayList<CountProof.Fraction>();
            do {
                budget.check();
                if (predecessor[at] < 0 || axioms.size() >= n) return null;
                var edge = edges.get(predecessor[at]);
                axioms.add(edge.row);
                weights.add(new CountProof.Fraction(BigInteger.ONE, edge.scale));
                at = edge.from;
            } while (at != last);
            var proof = new CountProof.Certificate("difference_cycle", lower.length, axioms, List.of(), weights, true);
            if (CountProof.verify(proof, 8192, budget::charge) != CountProof.Verdict.VERIFIED) return null;
            budget.note("count_difference", "checked_negative_cycle; edges=" + axioms.size());
            return proof;
        } finally {
            budget.release(bytes);
        }
    }
}
