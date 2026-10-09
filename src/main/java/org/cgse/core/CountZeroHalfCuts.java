// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Bounded parity-guided row aggregation, with independently checked integer-rounding cuts. */
final class CountZeroHalfCuts implements AutoCloseable {
    private record Row(int source, ExactRational slack) {}
    private record Basis(BitSet parity, BitSet sources) {}
    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final ExactRational[] point;
    private final PlanningBudget budget;
    private final long allowance;
    private final List<Row> selected = new ArrayList<>();
    private final List<ExactLinearProgram.Constraint> cuts = new ArrayList<>();
    private final Set<ExactLinearProgram.Constraint> known = new HashSet<>();
    private Basis[] basis;
    private List<ExactLinearProgram.Constraint> closureRows;
    private int[] columns;
    private int cursor, combinations;
    private long work, turnStarted, memory;
    private boolean ready, complete;

    /** Cheap odd-cycle closure of Boolean pair rows, without first solving an LP. */
    static CountZeroHalfCuts pairs(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                                   BigInteger[] upper, PlanningBudget budget) {
        int n = lower.length;
        if (n == 0 || n > 256 || rows.size() > 1024 || budget.remainingWork() < 131072) return null;
        long bytes = 1024L + 128L * n + 16L * rows.size();
        if (!budget.tryReserve(bytes)) return null;
        try {
            var point = new ExactRational[n];
            var binary = new boolean[n];
            for (int i = 0; i < n; i++) {
                budget.check();
                binary[i] = lower[i].signum() == 0 && BigInteger.ONE.equals(upper[i]);
                point[i] = ExactRational.of(lower[i]);
                if (binary[i]) point[i] = point[i].add(new ExactRational(BigInteger.ONE, BigInteger.TWO));
            }
            var pairs = new ArrayList<ExactLinearProgram.Constraint>();
            var boundVariables = new BitSet(n);
            for (var row : rows) {
                budget.check();
                if (row.terms().size() < 2 || row.terms().size() > 16) continue;
                BigInteger doubled = BigInteger.ZERO;
                boolean units = true;
                int choices = 0;
                for (var term : row.terms().entrySet()) {
                    budget.check();
                    int id = term.getKey();
                    if (binary[id]) {
                        choices++;
                        units &= term.getValue().abs().equals(BigInteger.ONE);
                    }
                    doubled = doubled.add(lower[id].shiftLeft(1).add(binary[id] ? BigInteger.ONE : BigInteger.ZERO).multiply(term.getValue()));
                }
                if (units && choices == 2 && doubled.equals(row.upper().shiftLeft(1))) {
                    pairs.add(row);
                    for (int id : row.terms().keySet()) if (!binary[id]) boundVariables.set(id);
                }
            }
            if (pairs.size() < 5) return null;
            var premises = new ArrayList<ExactLinearProgram.Constraint>();
            // Keep the real bound as a premise instead of dropping the extra
            // coordinate. E.g. x+y+finish<=2 with finish>=1 exposes a pair.
            for (int id = boundVariables.nextSetBit(0); id >= 0; id = boundVariables.nextSetBit(id + 1)) {
                budget.check();
                premises.add(new ExactLinearProgram.Constraint(Map.of(id, BigInteger.ONE.negate()), lower[id].negate()));
            }
            premises.addAll(pairs);
            var separator = new CountZeroHalfCuts(premises, lower, upper, point, budget);
            separator.closureRows = rows;
            return separator;
        } catch (ExactRational.PrecisionLimit unsupported) { return null; }
        finally { budget.release(bytes); }
    }

    CountZeroHalfCuts(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                      ExactRational[] point, PlanningBudget budget) {
        this.rows = rows;
        this.lower = lower;
        this.upper = upper;
        this.point = point;
        this.budget = budget;
        allowance = Math.min(65536, budget.remainingWork() / 32);
        int n = lower.length;
        // Include one exact aggregate/certificate plus the selected parity basis.
        // Row terms are capped before selection; no copy of the full matrix.
        long bytes = 4096L + 1024L * n + 256L * rows.size() +
                192L * Math.min(256, rows.size()) * Math.min(128, n) + 16L * n * ((n + 63) / 64 + 2);
        if (point == null || n == 0 || n > 256 || rows.size() > 1024 || allowance < 4096 || !budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
    }

    boolean step() {
        if (complete) return true;
        turnStarted = budget.threadWork();
        try {
            charge();
            if (!ready) {
                prepare();
                ready = true;
                return false;
            }
            if (cursor == selected.size() || cuts.size() >= 8) {
                closeSparseDemands();
                return finish();
            }
            int id = cursor++;
            var parity = new BitSet(lower.length);
            for (var term : rows.get(selected.get(id).source()).terms().entrySet()) {
                charge();
                if (term.getValue().testBit(0)) parity.set(term.getKey());
            }
            var sources = new BitSet(selected.size());
            sources.set(id);
            for (int column : columns) {
                charge();
                if (!parity.get(column)) continue;
                Basis pivot = basis[column];
                if (pivot == null) {
                    basis[column] = new Basis((BitSet) parity.clone(), (BitSet) sources.clone());
                    break;
                }
                charge(1L + (lower.length + selected.size() + 63) / 64);
                parity.xor(pivot.parity());
                sources.xor(pivot.sources());
                // Provenance is a SET of original rows. XOR only finds a useful
                // combination; its inequality is their nonnegative exact SUM.
                // Only complete parity cancellations enter the LP. Weak partial
                // aggregates can change its face without closing the odd cycle.
                if (sources.cardinality() > 3 && parity.isEmpty()) separate(sources);
                if (cuts.size() >= 8) break;
            }
            return false;
        } catch (Stop | ExactRational.PrecisionLimit cutoff) { return finish(); }
        finally { work += budget.threadWork() - turnStarted; }
    }

    private void prepare() {
        for (int i = 0; i < rows.size(); i++) {
            charge();
            var row = rows.get(i);
            if (row.terms().isEmpty() || row.terms().size() > 128) continue;
            charge(row.terms().size());
            known.add(row);
            var slack = ExactRational.of(row.upper()).subtract(activity(row));
            // A half-sum with total slack >= 1 cannot separate this point.
            if (slack.signum() >= 0 && slack.compareTo(ExactRational.ONE) < 0) selected.add(new Row(i, slack));
        }
        selected.sort((a, b) -> {
            charge();
            int compared = a.slack().compareTo(b.slack());
            return compared != 0 ? compared : Integer.compare(a.source(), b.source());
        });
        if (selected.size() > 256) selected.subList(256, selected.size()).clear();
        var order = new ArrayList<Integer>();
        var distances = new ExactRational[lower.length];
        for (int i = 0; i < lower.length; i++) {
            charge();
            order.add(i);
            distances[i] = distance(i);
        }
        // Eliminate expensive odd coefficients first; a near-bound coordinate
        // is cheap to round. This affects proposals, never validity or scope.
        order.sort((a, b) -> {
            charge();
            return distances[b].compareTo(distances[a]);
        });
        columns = order.stream().mapToInt(Integer::intValue).toArray();
        basis = new Basis[lower.length];
    }

    private ExactRational distance(int id) {
        var low = point[id].subtract(ExactRational.of(lower[id]));
        if (upper[id] == null) return low;
        var high = ExactRational.of(upper[id]).subtract(point[id]);
        return low.compareTo(high) <= 0 ? low : high;
    }

    private void separate(BitSet sources) {
        combinations++;
        ExactRational slack = ExactRational.ZERO;
        for (int i = sources.nextSetBit(0); i >= 0; i = sources.nextSetBit(i + 1)) {
            charge();
            slack = slack.add(selected.get(i).slack());
        }
        if (slack.compareTo(ExactRational.ONE) >= 0) return;
        var terms = new TreeMap<Integer, BigInteger>();
        var axioms = new ArrayList<CountProof.Row>();
        var multipliers = new ArrayList<BigInteger>();
        BigInteger bound = BigInteger.ZERO;
        long checks = 4L + 3L * lower.length;
        for (int i = sources.nextSetBit(0); i >= 0; i = sources.nextSetBit(i + 1)) {
            var row = rows.get(selected.get(i).source());
            bound = bound.add(row.upper());
            axioms.add(CountProof.row(row));
            multipliers.add(BigInteger.ONE);
            checks += 2L + 2L * row.terms().size();
            for (var term : row.terms().entrySet()) {
                charge();
                terms.merge(term.getKey(), term.getValue(), BigInteger::add);
            }
        }
        for (var term : terms.entrySet()) {
            charge();
            int id = term.getKey();
            if (term.getValue().testBit(0)) return;
            bound = bound.subtract(term.getValue().multiply(lower[id]));
        }
        var cutTerms = new TreeMap<Integer, BigInteger>();
        BigInteger rhs = bound.shiftRight(1);
        for (var term : terms.entrySet()) {
            charge();
            BigInteger coefficient = term.getValue().shiftRight(1);
            if (coefficient.signum() != 0) cutTerms.put(term.getKey(), coefficient);
            rhs = rhs.add(coefficient.multiply(lower[term.getKey()]));
        }
        var cut = new ExactLinearProgram.Constraint(cutTerms, rhs);
        if (known.contains(cut) || activity(cut).compareTo(ExactRational.of(rhs)) <= 0) return;
        for (int i = 0; i < lower.length; i++) {
            axioms.add(new CountProof.Row(Map.of(i, BigInteger.ONE.negate()), lower[i].negate()));
            multipliers.add(BigInteger.ZERO);
        }
        charge(checks);
        var proof = new CountProof.Rounding("zero_half_parity", lower.length, axioms, multipliers, BigInteger.TWO,
                Arrays.asList(lower), CountProof.row(cut));
        if (CountProof.verify(proof, checks) != CountProof.Verdict.VERIFIED) return;
        cuts.add(cut);
        known.add(cut);
        if (budget.proofJournal() != null) budget.proofJournal().add(proof);
    }

    private ExactRational activity(ExactLinearProgram.Constraint row) {
        ExactRational value = ExactRational.ZERO;
        for (var term : row.terms().entrySet()) {
            charge();
            budget.operation(PlanningBudget.Operation.RATIONAL, term.getValue().bitLength());
            value = value.add(point[term.getKey()].multiply(ExactRational.of(term.getValue())));
        }
        return value;
    }

    /** Cancel whole cycle capacities from a demand before another exponential search. */
    private void closeSparseDemands() {
        if (closureRows == null || cuts.isEmpty()) return;
        var capacities = List.copyOf(cuts);
        int attempts = 0;
        for (var row : closureRows) {
            charge();
            int negative = 0;
            for (var coefficient : row.terms().values()) {
                charge();
                if (coefficient.signum() < 0) negative++;
            }
            if (negative < 2 || row.terms().size() > 256) continue;
            if (++attempts > 32) break;
            var sum = new TreeMap<>(row.terms());
            BigInteger rhs = row.upper();
            var axioms = new ArrayList<CountProof.Row>();
            var weights = new LinkedHashMap<Integer, BigInteger>();
            axioms.add(CountProof.row(row));
            weights.put(0, BigInteger.ONE);
            for (var capacity : capacities) {
                BigInteger numerator = null, denominator = null;
                int cancelled = 0;
                boolean sameRatio = true;
                for (var term : capacity.terms().entrySet()) {
                    charge();
                    BigInteger coefficient = sum.get(term.getKey());
                    if (coefficient == null || coefficient.signum() * term.getValue().signum() >= 0) continue;
                    cancelled++;
                    if (numerator == null) {
                        numerator = coefficient.abs();
                        denominator = term.getValue().abs();
                    } else if (!coefficient.abs().multiply(denominator).equals(term.getValue().abs().multiply(numerator))) sameRatio = false;
                }
                if (!sameRatio || cancelled < 2) continue;
                BigInteger gcd = numerator.gcd(denominator), scale = denominator.divide(gcd), add = numerator.divide(gcd);
                var next = new TreeMap<Integer, BigInteger>();
                for (var term : sum.entrySet()) { charge(); next.put(term.getKey(), term.getValue().multiply(scale)); }
                for (var term : capacity.terms().entrySet()) {
                    charge();
                    next.merge(term.getKey(), term.getValue().multiply(add), BigInteger::add);
                }
                next.values().removeIf(value -> value.signum() == 0);
                // A strict support decrease bounds the chain; large coefficients
                // or fill-in are not accepted just to consume the local quota.
                if (next.size() >= sum.size() || next.values().stream().anyMatch(value -> value.bitLength() > 256)) continue;
                BigInteger nextRhs = rhs.multiply(scale).add(capacity.upper().multiply(add));
                if (nextRhs.bitLength() > 1024) continue;
                weights.replaceAll((id, value) -> value.multiply(scale));
                weights.put(axioms.size(), add);
                axioms.add(CountProof.row(capacity));
                sum = next;
                rhs = nextRhs;
            }
            if (sum.size() > 1 || axioms.size() == 1) continue;
            var consequence = CountReduction.normalize(new ExactLinearProgram.Constraint(sum, rhs));
            if (known.contains(consequence)) continue;
            BigInteger divisor = sum.isEmpty() ? BigInteger.ONE : sum.firstEntry().getValue().abs();
            var proof = new CountProof.Derivation("zero_half_sparse_demand", lower.length, axioms,
                    List.of(new CountProof.Combination(weights, divisor, CountProof.row(consequence))));
            long checks = 16L + 3L * axioms.size() + 2L * axioms.stream().mapToLong(a -> a.terms().size()).sum();
            charge(checks);
            if (CountProof.verify(proof, checks) != CountProof.Verdict.VERIFIED) continue;
            cuts.add(consequence);
            known.add(consequence);
            if (budget.proofJournal() != null) budget.proofJournal().add(proof);
            if (cuts.size() >= 16) return;
        }
    }

    private void charge() {
        charge(1);
    }
    private long spent() { return work + budget.threadWork() - turnStarted; }
    private void charge(long amount) {
        if (amount > allowance - spent()) throw new Stop();
        budget.charge(amount);
    }
    private boolean finish() {
        complete = true;
        if (!selected.isEmpty()) budget.note("count_zero_half", "cuts=" + cuts.size() + "; combinations=" + combinations +
                "; rows=" + selected.size() + "; work=" + spent());
        return true;
    }
    List<ExactLinearProgram.Constraint> cuts() { return List.copyOf(cuts); }
    @Override public void close() { budget.release(memory); memory = 0; }
}
