// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Bounded conflict-graph separation. Edges require exact resource/domain proofs. */
final class CountCliques implements AutoCloseable {

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final ExactRational[] point;
    private final PlanningBudget budget;
    private final List<ExactLinearProgram.Constraint> cuts = new ArrayList<>();
    private final List<CountProof.Clique> proofs = new ArrayList<>();
    private final Set<ExactLinearProgram.Constraint> known;
    private BitSet[] graph;
    private int[][] reasons;
    private int cursor, start;
    private final long allowance;
    private long work, memory;
    private boolean complete;

    CountCliques(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                 ExactRational[] point, PlanningBudget budget) {
        this.rows = rows;
        this.lower = lower;
        this.upper = upper;
        this.point = point;
        this.budget = budget;
        known = new HashSet<>(rows);
        allowance = Math.min(65536, budget.remainingWork() / 32);
        long entries = rows.stream().mapToLong(row -> row.terms().size()).sum();
        long bytes = 2048 + 24L * lower.length * lower.length + 384L * entries + 512L * rows.size();
        if (lower.length > 256 || rows.size() > 1024 || allowance < 1024 || !budget.tryReserve(bytes)) complete = true;
        else {
            memory = bytes;
            graph = new BitSet[2 * lower.length];
            reasons = new int[graph.length][graph.length];
            for (int i = 0; i < graph.length; i++) graph[i] = new BitSet();
        }
    }

    boolean step() {
        if (complete) return true;
        try {
            charge();
            if (cursor < rows.size()) {
                compile(cursor++);
                return false;
            }
            if (start == graph.length || cuts.size() >= 8) return finish("complete");
            int root = start++;
            if (graph[root].cardinality() < 2) return false;
            List<Integer> clique = new ArrayList<>();
            clique.add(root);
            BitSet available = (BitSet) graph[root].clone();
            while (!available.isEmpty() && clique.size() < 32) {
                int best = -1;
                for (int id = available.nextSetBit(0); id >= 0; id = available.nextSetBit(id + 1)) {
                    charge();
                    if (best < 0 || score(id).compareTo(score(best)) > 0) best = id;
                }
                clique.add(best);
                available.and(graph[best]);
            }
            if (clique.size() < 3) return false;
            ExactRational activity = ExactRational.ZERO;
            Map<Integer, BigInteger> terms = new TreeMap<>();
            BigInteger bound = BigInteger.ONE;
            for (int literal : clique) {
                int id = literal / 2;
                terms.put(id, (literal & 1) == 1 ? BigInteger.ONE : BigInteger.ONE.negate());
                bound = (literal & 1) == 1 ? bound.add(lower[id]) : bound.subtract(upper[id]);
                if (point != null) activity = activity.add(score(literal));
            }
            var cut = new ExactLinearProgram.Constraint(terms, bound);
            if (point != null && activity.compareTo(ExactRational.ONE) <= 0 || !known.add(cut)) return false;
            long bytes = 1024L + 512L * rows.size() + 512L * lower.length +
                    rows.stream().flatMap(r -> r.terms().values().stream()).mapToLong(v -> 256L + v.bitLength() / 8).sum();
            if (!budget.tryReserve(bytes)) return finish("proof_memory_limit");
            memory += bytes;
            List<CountProof.Row> axioms = new ArrayList<>(rows.stream().map(CountProof::row).toList());
            for (int id = 0; id < lower.length; id++) {
                axioms.add(new CountProof.Row(Map.of(id, BigInteger.ONE.negate()), lower[id].negate()));
                if (upper[id] != null) axioms.add(new CountProof.Row(Map.of(id, BigInteger.ONE), upper[id]));
            }
            List<Integer> witnesses = new ArrayList<>();
            for (int i = 0; i < clique.size(); i++) for (int j = i + 1; j < clique.size(); j++) {
                int row = reasons[clique.get(i)][clique.get(j)];
                for (int ignored = 0; ignored <= rows.get(row).terms().size(); ignored++) charge();
                witnesses.add(row);
            }
            var proof = new CountProof.Clique("resource_clique", lower.length, axioms, Arrays.asList(lower),
                    Arrays.asList(upper), clique, witnesses, CountProof.row(cut));
            if (CountProof.verify(proof, 1_000_000) != CountProof.Verdict.VERIFIED)
                throw new IllegalStateException("Invalid resource clique certificate");
            cuts.add(cut);
            proofs.add(proof);
            if (budget.proofJournal() != null) budget.proofJournal().add(proof);
            return false;
        } catch (Stop stop) {
            return finish("work_limit");
        }
    }

    private ExactRational score(int literal) {
        if (point == null) return ExactRational.of(BigInteger.valueOf(graph[literal].cardinality()));
        return (literal & 1) == 1 ? point[literal / 2].subtract(ExactRational.of(lower[literal / 2])) :
                ExactRational.of(upper[literal / 2]).subtract(point[literal / 2]);
    }

    private void compile(int index) {
        var row = rows.get(index);
        BigInteger minimum = BigInteger.ZERO;
        List<Integer> literals = new ArrayList<>();
        List<BigInteger> weights = new ArrayList<>();
        for (var term : row.terms().entrySet()) {
            charge();
            int id = term.getKey(), sign = term.getValue().signum();
            if (sign == 0) continue;
            BigInteger endpoint = sign > 0 ? lower[id] : upper[id];
            if (endpoint == null) return;
            minimum = minimum.add(term.getValue().multiply(endpoint));
            if (upper[id] != null && upper[id].subtract(lower[id]).equals(BigInteger.ONE)) {
                literals.add(2 * id + (sign > 0 ? 1 : 0));
                weights.add(term.getValue().abs());
            }
        }
        BigInteger capacity = row.upper().subtract(minimum);
        if (capacity.signum() < 0) return; // The bounds propagator owns this proof.
        for (int i = 0; i < literals.size(); i++) for (int j = i + 1; j < literals.size(); j++) {
            charge();
            if (weights.get(i).add(weights.get(j)).compareTo(capacity) <= 0) continue;
            int a = literals.get(i), b = literals.get(j);
            graph[a].set(b);
            graph[b].set(a);
            reasons[a][b] = reasons[b][a] = index;
        }
    }

    private void charge() {
        budget.check();
        if (++work > allowance) throw new Stop();
    }

    private boolean finish(String reason) {
        complete = true;
        budget.note("count_cliques", reason + "; cuts=" + cuts.size() + "; work=" + work);
        return true;
    }

    List<ExactLinearProgram.Constraint> cuts() {
        return List.copyOf(cuts);
    }

    List<CountProof.Clique> proofs() {
        return List.copyOf(proofs);
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
