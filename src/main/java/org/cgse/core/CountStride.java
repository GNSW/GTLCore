package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Count-equivalent root view from proved residues: x = shift + step * z.
 * The owning reduction supplies the exact request rows and domains used to
 * derive those residues. No branch-local or restricted-view fact is imported.
 * This view proposes feasibility; plan preferences use restored recipe counts.
 */
final class CountStride implements AutoCloseable {

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper, shift, stride;
    private final int[] coordinates;
    private final PlanningBudget budget;
    private long memory;

    private CountStride(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                        BigInteger[] shift, BigInteger[] stride, int[] coordinates, PlanningBudget budget, long memory) {
        this.rows = List.copyOf(rows);
        this.lower = lower;
        this.upper = upper;
        this.shift = shift;
        this.stride = stride;
        this.coordinates = coordinates;
        this.budget = budget;
        this.memory = memory;
    }

    static CountStride create(List<ExactLinearProgram.Constraint> source, CountResiduePresolve proof, PlanningBudget budget) {
        if (!proof.hasStrides()) return null;
        long allowance = Math.min(32768, budget.remainingWork() / 64);
        if (allowance < 1024) return null;
        long started = budget.threadWork();
        long terms = source.stream().mapToLong(row -> row.terms().size()).sum();
        long bytes = 2048L + 512L * proof.variables() + 384L * terms + 256L * source.size();
        if (!budget.tryReserve(bytes)) return null;
        try {
            BigInteger[] low = proof.lower(), high = proof.upper(), step = proof.moduli(), residue = proof.residues();
            BigInteger[] shift = new BigInteger[low.length];
            int[] coordinates = new int[low.length];
            Arrays.fill(coordinates, -1);
            List<BigInteger> upper = new ArrayList<>();
            int changed = 0;
            for (int i = 0; i < low.length; i++) {
                budget.check();
                if (budget.threadWork() - started >= allowance) return null;
                shift[i] = low[i].add(residue[i].subtract(low[i]).mod(step[i]));
                if (shift[i].bitLength() > 1024 || high[i] != null && shift[i].compareTo(high[i]) > 0) return null;
                BigInteger bound = high[i] == null ? null : high[i].subtract(shift[i]).divide(step[i]);
                if (BigInteger.ZERO.equals(bound)) continue;
                coordinates[i] = upper.size();
                upper.add(bound);
                if (step[i].compareTo(BigInteger.ONE) > 0) changed++;
            }
            if (changed == 0) return null;
            List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
            int bits = 0;
            for (var row : source) {
                Map<Integer, BigInteger> coefficients = new LinkedHashMap<>();
                BigInteger rhs = row.upper();
                for (var term : row.terms().entrySet()) {
                    budget.check();
                    if (budget.threadWork() - started >= allowance) return null;
                    int i = term.getKey();
                    rhs = rhs.subtract(term.getValue().multiply(shift[i]));
                    if (coordinates[i] >= 0) {
                        BigInteger coefficient = term.getValue().multiply(step[i]);
                        if (coefficient.bitLength() > 1024) return null;
                        coefficients.put(coordinates[i], coefficient);
                    }
                }
                if (rhs.bitLength() > 1024) return null;
                var transformed = CountReduction.normalize(new ExactLinearProgram.Constraint(coefficients, rhs));
                rows.add(transformed);
                for (var coefficient : transformed.terms().values()) bits = Math.max(bits, coefficient.bitLength());
            }
            BigInteger[] lower = new BigInteger[upper.size()];
            Arrays.fill(lower, BigInteger.ZERO);
            var result = new CountStride(rows, lower, upper.toArray(BigInteger[]::new), shift, step, coordinates, budget, bytes);
            bytes = 0;
            budget.note("count_stride", "variables=" + low.length + "->" + lower.length + "; stepped=" + changed +
                    "; coefficient_bits=" + bits + "; scope=root_count_equivalent");
            return result;
        } finally {
            budget.release(bytes);
        }
    }

    List<ExactLinearProgram.Constraint> rows() {
        return rows;
    }

    BigInteger[] lower() {
        return lower.clone();
    }

    BigInteger[] upper() {
        return upper.clone();
    }

    BigInteger[] expand(BigInteger[] values) {
        if (values == null) return null;
        if (values.length != lower.length) throw new IllegalArgumentException("Stride coordinate length mismatch");
        BigInteger[] result = shift.clone();
        for (int i = 0; i < result.length; i++) {
            budget.check();
            if (coordinates[i] >= 0) result[i] = result[i].add(stride[i].multiply(values[coordinates[i]]));
        }
        return result;
    }

    CountMapping substitution() {
        var expressions = new ArrayList<CountMapping.Expression>();
        for (int i = 0; i < shift.length; i++) {
            budget.check();
            expressions.add(new CountMapping.Expression(coordinates[i] < 0 ? Map.of() : Map.of(coordinates[i], stride[i]), shift[i]));
        }
        return new CountMapping(expressions);
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
