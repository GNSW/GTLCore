// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Equivalent sparse row operations using exact equalities. A pivot stays in the
 * output before any row that uses it, so cancellation never drops a premise.
 * Variables, domains and recipe coordinates are unchanged.
 */
final class CountRowSparsifier implements AutoCloseable {
    private final List<ExactLinearProgram.Constraint> source;
    private final PlanningBudget budget;
    private final long limit;
    private final List<ExactLinearProgram.Constraint> result = new ArrayList<>();
    private record Equation(ExactLinearProgram.Constraint row, int forward, int reverse) {}
    private record Reduced(ExactLinearProgram.Constraint row, int forward, int reverse) {}
    private record Candidate(ExactLinearProgram.Constraint row, Ratio ratio, Equation pivot, BigInteger divisor) {}
    private final List<Equation> equations = new ArrayList<>();
    private final List<CountProof.Combination> derivation = new ArrayList<>();
    private final boolean recording;
    private final Map<Integer, List<Integer>> incidence = new HashMap<>();
    private final BitSet paired = new BitSet();
    private long work, memory;
    private boolean complete, changed;

    CountRowSparsifier(List<ExactLinearProgram.Constraint> source, PlanningBudget budget) {
        this.source = source;
        this.budget = budget;
        recording = budget.proofJournal() != null;
        limit = Math.min(65536, budget.remainingWork() / 64);
    }

    /** All-or-nothing optional compilation; a local refusal keeps the input. */
    List<ExactLinearProgram.Constraint> compile() {
        if (complete) return changed ? List.copyOf(result) : source;
        complete = true;
        long terms = 0;
        for (var row : source) terms += row.terms().size();
        if (source.size() < 3 || terms < 12 || 4 * terms + source.size() > limit) return source;
        long bytes = 4096L + 320L * source.size() + (recording ? 896L : 384L) * terms;
        if (bytes > budget.availableBytes() / 8 || !budget.tryReserve(bytes)) return source;
        memory = bytes;
        try {
            var indices = new HashMap<ExactLinearProgram.Constraint, Integer>();
            for (int i = 0; i < source.size(); i++) {
                charge(1L + source.get(i).terms().size());
                // Production light compilation removes zero entries. Standalone
                // callers with a noncanonical support keep their input too.
                if (source.get(i).upper().abs().bitLength() > 1024 ||
                        source.get(i).terms().values().stream().anyMatch(value -> value.signum() == 0 || value.abs().bitLength() > 1024))
                    return source;
                indices.putIfAbsent(source.get(i), i);
            }
            var equalityIds = new ArrayList<Integer>();
            var reverseIds = new HashMap<Integer, Integer>();
            for (int i = 0; i < source.size(); i++) {
                charge(1);
                var row = source.get(i);
                if (paired.get(i) || row.terms().size() < 2 || row.terms().size() > 64) continue;
                Integer other = indices.get(negate(row));
                if (other == null || other == i || paired.get(other)) continue;
                paired.set(i); paired.set(other);
                equalityIds.add(i);
                reverseIds.put(i, other);
            }
            if (equalityIds.isEmpty()) return source;
            // Smaller retained equations minimize fill. The source index is the
            // deterministic tie breaker; this never changes producer priorities.
            charge((long) equalityIds.size() * (32 - Integer.numberOfLeadingZeros(equalityIds.size())));
            equalityIds.sort(Comparator.comparingInt((Integer i) -> source.get(i).terms().size()).thenComparingInt(i -> i));
            for (int id : equalityIds) {
                var reduced = reduce(id, reverseIds.get(id));
                var row = reduced.row;
                result.add(row); result.add(negate(row));
                if (row.terms().size() >= 2) {
                    int index = equations.size();
                    equations.add(new Equation(row, reduced.forward, reduced.reverse));
                    for (int variable : row.terms().keySet()) {
                        charge(1);
                        incidence.computeIfAbsent(variable, ignored -> new ArrayList<>()).add(index);
                    }
                }
            }
            for (int i = 0; i < source.size(); i++) if (!paired.get(i)) result.add(reduce(i, -1).row);
            long remaining = 0;
            for (var row : result) { charge(1); remaining += row.terms().size(); }
            // A new portfolio representation must buy a substantial reduction,
            // not merely change traversal order at the cost of another engine.
            changed = remaining * 8 <= terms * 7;
            if (changed && recording) {
                int variables = 0;
                var axioms = new ArrayList<CountProof.Row>();
                for (var row : source) {
                    charge(1L + row.terms().size());
                    axioms.add(CountProof.row(row));
                    for (int variable : row.terms().keySet()) variables = Math.max(variables, variable + 1);
                }
                var proof = new CountProof.Derivation("sparse_equalities_original_coordinates", variables, axioms, derivation);
                // The independent checker charges the last refused tick too.
                // Leave that tick inside this compiler's local work envelope.
                long proofWork = limit - work - 1;
                if (proofWork <= 0) throw new Limit();
                if (CountProof.verify(proof, proofWork, this::charge) != CountProof.Verdict.VERIFIED) {
                    changed = false;
                    return source;
                }
                budget.proofJournal().add(proof);
            }
            if (changed) budget.note("count_row_sparsify", "terms=" + terms + "->" + remaining +
                    "; equations=" + equalityIds.size() + "; work=" + work + "; equivalent_original_coordinates");
            return changed ? List.copyOf(result) : source;
        } catch (Limit refusal) {
            changed = false;
            return source;
        }
    }

    private Reduced reduce(int id, int reverse) {
        var row = source.get(id);
        boolean equality = reverse >= 0;
        if (row.terms().size() < 2 || row.terms().size() > 128) return new Reduced(row, id, reverse);
        while (true) {
            var overlap = new TreeMap<Integer, Integer>();
            for (int variable : row.terms().keySet()) {
                charge(1);
                for (int index : incidence.getOrDefault(variable, List.of())) {
                    charge(1);
                    overlap.merge(index, 1, Integer::sum);
                }
            }
            Candidate best = null;
            for (var entry : overlap.entrySet()) {
                var equation = equations.get(entry.getKey());
                var pivot = equation.row;
                // Even cancelling every common term must remove more terms
                // than the pivot introduces. Avoid constructing losing unions.
                if (2 * entry.getValue() <= pivot.terms().size()) continue;
                var ratios = new LinkedHashMap<Ratio, Integer>();
                for (var term : pivot.terms().entrySet()) {
                    charge(1);
                    BigInteger value = row.terms().get(term.getKey());
                    if (value == null) continue;
                    BigInteger gcd = value.gcd(term.getValue());
                    var ratio = new Ratio(term.getValue().abs().divide(gcd),
                            value.negate().multiply(BigInteger.valueOf(term.getValue().signum())).divide(gcd));
                    ratios.merge(ratio, 1, Integer::sum);
                }
                for (var ratio : ratios.entrySet()) {
                    charge(1);
                    // The ratio histogram counts exact cancellations before
                    // building a BigInteger union. Dense rows with mismatched
                    // coefficients otherwise waste a complete trial per term.
                    int size = row.terms().size() + pivot.terms().size() - entry.getValue() - ratio.getValue();
                    if (size >= (best == null ? row.terms().size() : best.row.terms().size())) continue;
                    var candidate = combine(row, equation, ratio.getKey(), equality);
                    if (candidate != null && (best == null || candidate.row.terms().size() < best.row.terms().size())) best = candidate;
                }
            }
            if (best == null) return new Reduced(row, id, reverse);
            if (recording) {
                boolean subtract = best.ratio.equality.signum() < 0;
                id = record(id, subtract ? best.pivot.reverse : best.pivot.forward, best, best.row);
                if (equality) reverse = record(reverse, subtract ? best.pivot.forward : best.pivot.reverse, best, negate(best.row));
            }
            row = best.row;
            if (row.terms().size() < 2) return new Reduced(row, id, reverse);
        }
    }

    private int record(int row, int pivot, Candidate candidate, ExactLinearProgram.Constraint consequence) {
        charge(2L + consequence.terms().size());
        int id = source.size() + derivation.size();
        derivation.add(new CountProof.Combination(Map.of(row, candidate.ratio.row, pivot, candidate.ratio.equality.abs()),
                candidate.divisor, CountProof.row(consequence)));
        return id;
    }

    private record Ratio(BigInteger row, BigInteger equality) {}

    private Candidate combine(ExactLinearProgram.Constraint row, Equation equation, Ratio ratio, boolean equality) {
        var pivot = equation.row;
        // Positive row multiplier preserves <=; either sign of the equality
        // multiplier is valid because both faces of that pivot are retained.
        if (ratio.row.bitLength() > 256 || ratio.equality.bitLength() > 256) return null;
        var terms = new TreeMap<Integer, BigInteger>();
        int beforeBits = row.upper().abs().bitLength();
        for (var term : row.terms().entrySet()) {
            chargeProduct(term.getValue(), ratio.row);
            beforeBits = Math.max(beforeBits, term.getValue().abs().bitLength());
            terms.put(term.getKey(), term.getValue().multiply(ratio.row));
        }
        for (var term : pivot.terms().entrySet()) {
            chargeProduct(term.getValue(), ratio.equality);
            terms.merge(term.getKey(), term.getValue().multiply(ratio.equality), BigInteger::add);
        }
        terms.values().removeIf(value -> value.signum() == 0);
        if (terms.size() >= row.terms().size()) return null;
        chargeProduct(row.upper(), ratio.row);
        chargeProduct(pivot.upper(), ratio.equality);
        BigInteger bound = row.upper().multiply(ratio.row).add(pivot.upper().multiply(ratio.equality));
        BigInteger gcd = BigInteger.ZERO;
        for (var value : terms.values()) { charge(1); gcd = gcd.gcd(value); }
        if (gcd.signum() != 0) {
            // Divide equalities exactly. Inequalities may round down only after
            // the full nonnegative combination has been formed.
            if (equality) gcd = gcd.gcd(bound);
            for (var term : terms.entrySet()) { charge(1); term.setValue(term.getValue().divide(gcd)); }
            var qr = bound.divideAndRemainder(gcd);
            bound = qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
        } else gcd = BigInteger.ONE;
        int maxBits = Math.max(64, beforeBits);
        if (bound.abs().bitLength() > maxBits) return null;
        for (var value : terms.values()) if (value.abs().bitLength() > maxBits) return null;
        return new Candidate(new ExactLinearProgram.Constraint(terms, bound), ratio, equation, gcd);
    }

    private ExactLinearProgram.Constraint negate(ExactLinearProgram.Constraint row) {
        var terms = new TreeMap<Integer, BigInteger>();
        for (var term : row.terms().entrySet()) { charge(1); terms.put(term.getKey(), term.getValue().negate()); }
        return new ExactLinearProgram.Constraint(terms, row.upper().negate());
    }

    private void chargeProduct(BigInteger a, BigInteger b) {
        charge(Math.max(1, (a.abs().bitLength() + 63L) / 64) * Math.max(1, (b.abs().bitLength() + 63L) / 64));
    }

    private void charge(long amount) {
        if (amount > limit - work) throw new Limit();
        budget.charge(amount);
        work += amount;
    }

    @Override public void close() {
        budget.release(memory); memory = 0;
        result.clear(); equations.clear(); incidence.clear(); paired.clear(); derivation.clear();
    }

    private static final class Limit extends RuntimeException {
        Limit() { super(null, null, false, false); }
    }
}
