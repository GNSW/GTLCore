package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Tableau-guided pure-integer Gomory separation. Recipe counts and slacks of
 * integral material rows are integers. Fractional slack multipliers yield a
 * nonnegative rank-one CG combination, independently replayed by CountProof.
 */
final class CountGomory {

    private CountGomory() {}

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    static List<ExactLinearProgram.Constraint> separate(ExactLinearProgram.Basis basis, ExactRational[] point, PlanningBudget budget) {
        if (basis == null || basis.table == null && basis.revised == null || point == null || basis.variables > 128 || basis.constraints.size() > 512) return List.of();
        long allowance = Math.min(basis.revised == null ? 32768 : 131072, budget.remainingWork() / 32), started = budget.threadWork();
        if (allowance < 2048) return List.of();
        long bytes = 1024L + 512L * (basis.variables + basis.constraints.size());
        if (basis.revised != null) bytes += 128L * basis.constraints.stream().mapToLong(row -> row.terms().size()).sum();
        if (!budget.tryReserve(bytes)) return List.of();
        List<ExactLinearProgram.Constraint> result = new ArrayList<>();
        ExactSparseFactor factor = null;
        Runnable charge = () -> {
            budget.check();
            if (budget.threadWork() - started >= allowance) throw new Stop();
        };
        try {
            if (basis.revised != null) {
                List<Map<Integer, ExactRational>> columns = new ArrayList<>();
                int[] positions = new int[basis.variables];
                Arrays.fill(positions, -1);
                for (int r = 0; r < basis.revised.length; r++) {
                    charge.run();
                    int id = basis.revised[r];
                    if (id < basis.variables) {
                        positions[id] = r;
                        columns.add(new LinkedHashMap<>());
                    } else columns.add(Map.of(id - basis.variables, ExactRational.ONE));
                }
                for (int r = 0; r < basis.constraints.size(); r++) for (var term : basis.constraints.get(r).terms().entrySet()) {
                    charge.run();
                    int column = positions[term.getKey()];
                    if (column >= 0) columns.get(column).put(r, ExactRational.of(term.getValue()));
                }
                factor = ExactSparseFactor.build(columns, budget, allowance - (budget.threadWork() - started));
                if (factor == null) return List.of();
            }
            int[] basic = basis.revised == null ? basis.basic : basis.revised;
            for (int r = 0; r < basic.length && result.size() < 4 && budget.threadWork() - started < allowance; r++) {
                charge.run();
                if (basis.revised == null ? basic[r] < 0 || basis.table[r][basis.variables + 1].integral() : basic[r] >= basis.variables || point[basic[r]].integral()) continue;
                ExactRational[] fractions = new ExactRational[basis.constraints.size()];
                Arrays.fill(fractions, ExactRational.ZERO);
                if (factor != null) {
                    // B^-T e_r gives this tableau row's original slack
                    // coefficients. Basic slacks have integral unit entries,
                    // so their fractional parts correctly contribute zero.
                    fractions[r] = ExactRational.ONE;
                    factor.transpose(fractions, charge);
                    for (int i = 0; i < fractions.length; i++) {
                        charge.run();
                        fractions[i] = fractions[i].subtract(ExactRational.of(fractions[i].floor()));
                    }
                } else for (int j = 0; j < basis.nonbasic.length; j++) {
                    charge.run();
                    int id = basis.nonbasic[j] - basis.variables;
                    if (id >= 0) fractions[id] = basis.table[r][j].subtract(ExactRational.of(basis.table[r][j].floor()));
                }
                BigInteger divisor = BigInteger.ONE;
                for (ExactRational value : fractions) {
                    charge.run();
                    divisor = divisor.multiply(value.denominator().divide(divisor.gcd(value.denominator())));
                    if (divisor.bitLength() > 256) break;
                }
                if (divisor.bitLength() > 256 || divisor.equals(BigInteger.ONE)) continue;
                List<BigInteger> weights = new ArrayList<>();
                Map<Integer, BigInteger> sum = new TreeMap<>();
                BigInteger bound = BigInteger.ZERO;
                for (int i = 0; i < fractions.length; i++) {
                    charge.run();
                    BigInteger weight = fractions[i].numerator().multiply(divisor.divide(fractions[i].denominator()));
                    weights.add(weight);
                    if (weight.signum() == 0) continue;
                    var row = basis.constraints.get(i);
                    bound = bound.add(row.upper().multiply(weight));
                    for (var term : row.terms().entrySet()) {
                        charge.run();
                        sum.merge(term.getKey(), term.getValue().multiply(weight), BigInteger::add);
                    }
                }
                Map<Integer, BigInteger> terms = new TreeMap<>();
                for (var term : sum.entrySet()) {
                    charge.run();
                    BigInteger value = floor(term.getValue(), divisor);
                    if (value.signum() != 0) terms.put(term.getKey(), value);
                }
                var cut = new ExactLinearProgram.Constraint(terms, floor(bound, divisor));
                var tableau = CountMirCuts.tableau(basis.constraints, weights, sum, bound, divisor, charge);
                if (tableau != null) cut = tableau;
                ExactRational activity = ExactRational.ZERO;
                for (var term : cut.terms().entrySet()) {
                    charge.run();
                    activity = activity.add(point[term.getKey()].multiply(ExactRational.of(term.getValue())));
                }
                if (activity.compareTo(ExactRational.of(cut.upper())) <= 0 || result.contains(cut) || basis.constraints.contains(cut)) continue;
                result.add(cut);
                if (budget.proofJournal() != null) {
                    List<CountProof.Row> axioms = new ArrayList<>(basis.constraints.stream().map(CountProof::row).toList());
                    for (int id = 0; id < basis.variables; id++) {
                        axioms.add(new CountProof.Row(Map.of(id, BigInteger.ONE.negate()), BigInteger.ZERO));
                        weights.add(BigInteger.ZERO);
                    }
                    budget.proofJournal().add(new CountProof.Rounding("tableau_gomory", basis.variables, axioms, weights, divisor,
                            Collections.nCopies(basis.variables, BigInteger.ZERO), CountProof.row(cut),
                            tableau == null ? CountProof.RoundingKind.FLOOR : CountProof.RoundingKind.TABLEAU));
                }
            }
        } catch (Stop | ExactRational.PrecisionLimit limit) {
            // Previously completed cuts remain valid; precision is not a proof.
        } finally {
            if (factor != null) factor.close();
            budget.release(bytes);
        }
        if (!result.isEmpty()) budget.note("count_gomory", "violated_tableau_cuts=" + result.size() + "; work=" + (budget.threadWork() - started));
        return result;
    }

    private static BigInteger floor(BigInteger n, BigInteger d) {
        var qr = n.divideAndRemainder(d);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }
}
