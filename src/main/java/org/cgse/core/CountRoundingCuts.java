// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Bounded rank-one Chvatal-Gomory separation (aggregation and integer rounding,
 * also used by SCIP). Every cut carries a separately checked exact certificate.
 * This deliberately samples small row combinations instead of solving another
 * integer program to find a cut; unsuccessful separation leaves the model intact.
 */
final class CountRoundingCuts implements AutoCloseable {

    private final PlanningBudget budget;
    private final BigInteger[] lower;
    private final ExactRational[] point;
    private final List<ExactLinearProgram.Constraint> selected = new ArrayList<>();
    private final List<ExactLinearProgram.Constraint> cuts = new ArrayList<>();
    private final List<int[]> combinations = new ArrayList<>();
    private final Set<ExactLinearProgram.Constraint> known;
    private final long allowance;
    private long work, memory;
    private int cursor;
    private boolean complete;

    CountRoundingCuts(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                      ExactRational[] point, PlanningBudget budget) {
        this.budget = budget;
        this.lower = lower;
        this.point = point;
        known = new HashSet<>(rows);
        allowance = Math.min(65536, budget.remainingWork() / 32);
        long bytes = 4096L + 512L * lower.length + 256L * rows.size();
        if (lower.length > 256 || rows.size() > 1024 || allowance < 4096 || !budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        try {
            Map<ExactLinearProgram.Constraint, ExactRational> slack = new HashMap<>();
            for (var row : rows) {
                charge();
                if (row.terms().size() < 2 || row.terms().size() > 128) continue;
                ExactRational value = activity(row);
                BigInteger magnitude = row.terms().values().stream().map(BigInteger::abs).max(BigInteger::compareTo).orElse(BigInteger.ONE);
                slack.put(row, ExactRational.of(row.upper()).subtract(value).divide(ExactRational.of(magnitude)));
                selected.add(row);
            }
            selected.sort(Comparator.comparing(slack::get));
            if (selected.size() > 24) selected.subList(24, selected.size()).clear();
            for (int i = 0; i < selected.size(); i++) combinations.add(new int[] { i });
            for (int i = 0; i < selected.size(); i++) for (int j = i + 1; j < selected.size(); j++) combinations.add(new int[] { i, j });
            for (int i = 0; i < Math.min(12, selected.size()); i++) for (int j = i + 1; j < Math.min(12, selected.size()); j++)
                for (int k = j + 1; k < Math.min(12, selected.size()); k++) combinations.add(new int[] { i, j, k });
        } catch (Stop | ExactRational.PrecisionLimit stop) {
            complete = true;
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    boolean step() {
        if (complete) return true;
        try {
            charge();
            if (cursor == combinations.size() || cuts.size() == 8) return finish();
            int[] combination = combinations.get(cursor++);
            Map<Integer, BigInteger> sum = new TreeMap<>();
            BigInteger bound = BigInteger.ZERO;
            for (int id : combination) {
                var row = selected.get(id);
                bound = bound.add(row.upper());
                for (var term : row.terms().entrySet()) {
                    charge();
                    sum.merge(term.getKey(), term.getValue(), BigInteger::add);
                }
            }
            BigInteger shifted = bound;
            for (var term : sum.entrySet()) {
                charge();
                shifted = shifted.subtract(term.getValue().multiply(lower[term.getKey()]));
            }
            Set<BigInteger> divisors = new LinkedHashSet<>(List.of(BigInteger.TWO, BigInteger.valueOf(3), BigInteger.valueOf(5), BigInteger.valueOf(7)));
            BigInteger gcd = BigInteger.ZERO;
            for (var value : sum.values()) gcd = gcd.gcd(value);
            if (gcd.compareTo(BigInteger.ONE) > 0) divisors.add(gcd);
            sum.values().stream().map(BigInteger::abs).filter(v -> v.compareTo(BigInteger.ONE) > 0).distinct().sorted().limit(4).forEach(divisors::add);
            for (var divisor : divisors) {
                Map<Integer, BigInteger> terms = new TreeMap<>();
                BigInteger rhs = floor(shifted, divisor);
                for (var term : sum.entrySet()) {
                    charge();
                    BigInteger coefficient = floor(term.getValue(), divisor);
                    if (coefficient.signum() != 0) terms.put(term.getKey(), coefficient);
                    rhs = rhs.add(coefficient.multiply(lower[term.getKey()]));
                }
                var cut = new ExactLinearProgram.Constraint(terms, rhs);
                if (known.contains(cut) || activity(cut).compareTo(ExactRational.of(rhs)) <= 0) continue;
                var axioms = new ArrayList<CountProof.Row>();
                var multipliers = new ArrayList<BigInteger>();
                long checkWork = 4L + 3L * lower.length;
                for (int id : combination) {
                    var row = selected.get(id);
                    axioms.add(new CountProof.Row(row.terms(), row.upper()));
                    multipliers.add(BigInteger.ONE);
                    checkWork += 2L + 2L * row.terms().size();
                }
                for (int i = 0; i < lower.length; i++) {
                    axioms.add(new CountProof.Row(Map.of(i, BigInteger.ONE.negate()), lower[i].negate()));
                    multipliers.add(BigInteger.ZERO);
                }
                if (checkWork > allowance - work) return finish();
                for (long i = 0; i < checkWork; i++) charge();
                var proof = new CountProof.Rounding("aggregated_integer_rounding", lower.length, axioms, multipliers, divisor,
                        Arrays.asList(lower), new CountProof.Row(terms, rhs));
                if (CountProof.verify(proof, checkWork) != CountProof.Verdict.VERIFIED) continue;
                cuts.add(cut);
                known.add(cut);
                if (budget.proofJournal() != null) budget.proofJournal().add(proof);
                if (cuts.size() == 8) return finish();
            }
            return false;
        } catch (Stop | ExactRational.PrecisionLimit stop) {
            return finish();
        }
    }

    private ExactRational activity(ExactLinearProgram.Constraint row) {
        ExactRational value = ExactRational.ZERO;
        for (var term : row.terms().entrySet()) {
            charge();
            value = value.add(point[term.getKey()].multiply(ExactRational.of(term.getValue())));
        }
        return value;
    }

    private static BigInteger floor(BigInteger value, BigInteger divisor) {
        BigInteger[] qr = value.divideAndRemainder(divisor);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }

    private void charge() {
        if (++work > allowance) throw new Stop();
        budget.check();
    }

    private boolean finish() {
        complete = true;
        if (!cuts.isEmpty()) budget.note("count_rounding_cuts", "verified_cuts=" + cuts.size() + "; combinations=" + cursor + "; work=" + work);
        return true;
    }

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    List<ExactLinearProgram.Constraint> cuts() {
        return List.copyOf(cuts);
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
