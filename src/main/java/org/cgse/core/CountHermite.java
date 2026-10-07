package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Row Hermite reduction: expose integer equalities in the original coordinates. */
final class CountHermite implements AutoCloseable {

    private final PlanningBudget budget;
    private final int variables;
    private final long allowance;
    private final List<CountProof.Row> axioms = new ArrayList<>();
    private final List<ExactLinearProgram.Constraint> cuts = new ArrayList<>();
    private final Set<ExactLinearProgram.Constraint> known;
    private BigInteger[][] matrix, transform;
    private int rank, column;
    private long work, memory;
    private boolean complete;

    CountHermite(List<ExactLinearProgram.Constraint> rows, int variables, PlanningBudget budget) {
        this.budget = budget;
        this.variables = variables;
        allowance = Math.min(65536, budget.remainingWork() / 64);
        known = new HashSet<>(rows);
        if (variables > 96 || rows.size() > 1024 || allowance < 4096) {
            complete = true;
            return;
        }
        Set<ExactLinearProgram.Constraint> used = new HashSet<>();
        var equations = new ArrayList<ExactLinearProgram.Constraint>();
        for (var row : rows) {
            budget.check();
            if (row.terms().size() < 2 || used.contains(row)) continue;
            var opposite = new TreeMap<Integer, BigInteger>();
            for (var term : row.terms().entrySet()) {
                budget.check();
                opposite.put(term.getKey(), term.getValue().negate());
            }
            var reverse = new ExactLinearProgram.Constraint(opposite, row.upper().negate());
            if (!known.contains(reverse)) continue;
            used.add(row);
            used.add(reverse);
            equations.add(row);
            axioms.add(CountProof.row(row));
            axioms.add(CountProof.row(reverse));
        }
        if (equations.size() < 2 || equations.size() > 96) {
            complete = true;
            return;
        }
        long bytes = 4096L + 192L * equations.size() * (variables + equations.size() + 1L);
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        try {
            matrix = new BigInteger[equations.size()][variables + 1];
            transform = new BigInteger[equations.size()][equations.size()];
            for (int r = 0; r < matrix.length; r++) {
                Arrays.fill(matrix[r], BigInteger.ZERO);
                Arrays.fill(transform[r], BigInteger.ZERO);
                for (var term : equations.get(r).terms().entrySet()) {
                    charge();
                    matrix[r][term.getKey()] = term.getValue();
                }
                matrix[r][variables] = equations.get(r).upper();
                transform[r][r] = BigInteger.ONE;
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    boolean step() {
        if (complete) return true;
        charge();
        if (work >= allowance) return finish(false);
        if (column == variables || rank == matrix.length) return finish(true);
        int selected = -1;
        for (int r = rank; r < matrix.length; r++) {
            charge();
            if (matrix[r][column].signum() != 0 && (selected < 0 || matrix[r][column].abs().compareTo(matrix[selected][column].abs()) < 0)) selected = r;
        }
        if (selected < 0) {
            column++;
            return false;
        }
        swap(rank, selected);
        if (matrix[rank][column].signum() < 0) {
            for (int j = 0; j <= variables; j++) {
                charge();
                matrix[rank][j] = matrix[rank][j].negate();
            }
            for (int j = 0; j < transform.length; j++) {
                charge();
                transform[rank][j] = transform[rank][j].negate();
            }
        }
        for (int r = rank + 1; r < matrix.length; r++) if (matrix[r][column].signum() != 0) {
            BigInteger q = matrix[r][column].divide(matrix[rank][column]);
            if (!subtract(r, rank, q)) return finish(false);
            return false;
        }
        for (int r = 0; r < rank; r++) {
            BigInteger[] qr = matrix[r][column].divideAndRemainder(matrix[rank][column]);
            BigInteger q = qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
            if (q.signum() != 0 && !subtract(r, rank, q)) return finish(false);
        }
        rank++;
        column++;
        return false;
    }

    private boolean subtract(int target, int source, BigInteger q) {
        boolean bounded = true;
        for (int j = 0; j <= variables; j++) {
            charge();
            matrix[target][j] = matrix[target][j].subtract(q.multiply(matrix[source][j]));
            bounded &= matrix[target][j].bitLength() <= 512;
        }
        for (int j = 0; j < transform.length; j++) {
            charge();
            transform[target][j] = transform[target][j].subtract(q.multiply(transform[source][j]));
            bounded &= transform[target][j].bitLength() <= 512;
        }
        return bounded;
    }

    private void swap(int a, int b) {
        var row = matrix[a];
        matrix[a] = matrix[b];
        matrix[b] = row;
        row = transform[a];
        transform[a] = transform[b];
        transform[b] = row;
    }

    private boolean finish(boolean reduced) {
        complete = true;
        if (reduced) {
            var steps = new ArrayList<CountProof.Combination>();
            var proposed = new ArrayList<ExactLinearProgram.Constraint>();
            for (int r = 0; r < matrix.length; r++) for (int sign : new int[] { 1, -1 }) {
                BigInteger gcd = BigInteger.ZERO;
                for (int j = 0; j < variables; j++) {
                    charge();
                    gcd = gcd.gcd(matrix[r][j]);
                }
                if (gcd.signum() == 0) gcd = BigInteger.ONE;
                var terms = new TreeMap<Integer, BigInteger>();
                for (int j = 0; j < variables; j++) if (matrix[r][j].signum() != 0) terms.put(j, matrix[r][j].multiply(BigInteger.valueOf(sign)).divide(gcd));
                var qr = matrix[r][variables].multiply(BigInteger.valueOf(sign)).divideAndRemainder(gcd);
                var cut = new ExactLinearProgram.Constraint(terms, qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0]);
                if (known.contains(cut) || terms.isEmpty() && cut.upper().signum() >= 0) continue;
                var parents = new TreeMap<Integer, BigInteger>();
                for (int j = 0; j < transform.length; j++) {
                    charge();
                    BigInteger value = transform[r][j].multiply(BigInteger.valueOf(sign));
                    if (value.signum() != 0) parents.put(2 * j + (value.signum() < 0 ? 1 : 0), value.abs());
                }
                steps.add(new CountProof.Combination(parents, gcd, CountProof.row(cut)));
                proposed.add(cut);
            }
            if (!steps.isEmpty()) {
                var proof = new CountProof.Derivation("row_hermite_original_coordinates", variables, axioms, steps);
                long checks = 1024 + steps.stream().mapToLong(s -> s.parents().keySet().stream().mapToLong(i -> 4L + axioms.get(i).terms().size()).sum()).sum();
                if (checks <= allowance - work) {
                    budget.charge(checks);
                    work += checks;
                    if (CountProof.verify(proof, checks) == CountProof.Verdict.VERIFIED) {
                        cuts.addAll(proposed);
                        if (budget.proofJournal() != null) budget.proofJournal().add(proof);
                    }
                }
            }
        }
        budget.note("count_hermite", "rank=" + rank + "; cuts=" + cuts.size() + "; complete=" + reduced + "; work=" + work);
        return true;
    }

    private void charge() {
        budget.check();
        work++;
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
