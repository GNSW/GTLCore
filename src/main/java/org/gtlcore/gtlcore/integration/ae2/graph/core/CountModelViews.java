package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Immutable integer model plus optional equivalent search representations.
 * Light compilation keeps recipe coordinates; elimination carries its inverse.
 * All candidates are checked against the original rows and domains. Execution
 * and seed verification still happen separately on the original recipes.
 */
final class CountModelViews implements AutoCloseable {

    record Shape(int variables, long terms, int coefficientBits, int unfixed) {

        long cost() {
            return Math.max(1, terms + 4L * unfixed) * Math.max(1, (coefficientBits + 63L) / 64);
        }
    }

    record View(String name, List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                Shape shape, CountReduction inverse) {

        BigInteger[] restore(BigInteger[] counts) {
            return inverse == null ? counts : inverse.expand(counts);
        }
    }

    private final PlanningBudget budget;
    private final View original;
    private final List<View> views = new ArrayList<>();
    private boolean lightAttempted;
    private long memory;

    private CountModelViews(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                            PlanningBudget budget, long bytes) {
        this.budget = budget;
        memory = bytes;
        original = new View("original", List.copyOf(rows), low.clone(), high.clone(), shape(rows, low, high, budget), null);
        views.add(original);
    }

    static CountModelViews create(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                                  PlanningBudget budget) {
        if (low.length > 512 || rows.size() > 2048 || rows.stream().mapToLong(r -> r.terms().size()).sum() > 32768) return null;
        long bytes = 1024L + 32L * low.length + 16L * rows.size();
        if (!budget.tryReserve(bytes)) return null;
        try {
            return new CountModelViews(rows, low, high, budget, bytes);
        } catch (RuntimeException | Error failure) {
            budget.release(bytes);
            throw failure;
        }
    }

    List<View> available() {
        return List.copyOf(views);
    }

    /** Integer-equivalent row simplification without changing variable coordinates. */
    void compileLight() {
        if (lightAttempted) return;
        lightAttempted = true;
        long bytes = 1024L + 256L * original.rows.size() + 192L * original.shape.terms;
        if (!budget.tryReserve(bytes)) return;
        memory += bytes;
        Map<Map<Integer, BigInteger>, ExactLinearProgram.Constraint> unique = new LinkedHashMap<>();
        for (var row : original.rows) {
            var normalized = simplify(row);
            if (normalized == null) continue;
            var previous = unique.get(normalized.terms());
            if (previous == null || normalized.upper().compareTo(previous.upper()) < 0) unique.put(normalized.terms(), normalized);
        }
        var rows = List.copyOf(unique.values());
        if (rows.equals(original.rows)) {
            budget.release(bytes);
            memory -= bytes;
            return;
        }
        views.add(new View("normalized", rows, original.lower, original.upper, shape(rows, original.lower, original.upper, budget), null));
        budget.note("count_light_compile", "rows=" + original.rows.size() + "->" + rows.size() +
                "; terms=" + original.shape.terms + "->" + views.get(views.size() - 1).shape.terms);
    }

    private ExactLinearProgram.Constraint simplify(ExactLinearProgram.Constraint row) {
        Map<Integer, BigInteger> terms = new LinkedHashMap<>();
        BigInteger bound = row.upper(), maximum = BigInteger.ZERO;
        boolean finite = true;
        for (var term : row.terms().entrySet()) {
            budget.check();
            int id = term.getKey();
            BigInteger a = term.getValue();
            if (a.signum() == 0) continue;
            if (original.lower[id].equals(original.upper[id])) {
                bound = bound.subtract(a.multiply(original.lower[id]));
                continue;
            }
            terms.put(id, a);
            BigInteger endpoint = a.signum() > 0 ? original.upper[id] : original.lower[id];
            if (endpoint == null) finite = false;
            else maximum = maximum.add(a.multiply(endpoint));
        }
        if (finite) {
            BigInteger gap = maximum.subtract(bound);
            if (gap.signum() <= 0) return null;
            // In coordinates measured down from the maximizing endpoints the
            // row is sum |a_i| * distance_i >= gap. Integer distances permit
            // capping a coefficient at gap, preserving EVERY integer solution.
            // This is not an objective-based or tolerance-based reduction.
            for (var term : terms.entrySet()) {
                budget.check();
                BigInteger a = term.getValue();
                if (a.abs().compareTo(gap) <= 0) continue;
                int id = term.getKey();
                BigInteger next = a.signum() > 0 ? gap : gap.negate();
                BigInteger endpoint = a.signum() > 0 ? original.upper[id] : original.lower[id];
                bound = bound.add(next.subtract(a).multiply(endpoint));
                term.setValue(next);
            }
        }
        return CountReduction.normalize(new ExactLinearProgram.Constraint(terms, bound));
    }

    /** The owner retains the reduction until all searches using its inverse close. */
    void addReduced(CountReduction reduction) {
        var rows = reduction.rows();
        var low = reduction.lower();
        var high = reduction.upper();
        if (views.stream().anyMatch(v -> v.rows.equals(rows) && Arrays.equals(v.lower, low) && Arrays.equals(v.upper, high))) return;
        long bytes = 256L + 32L * low.length;
        if (!budget.tryReserve(bytes)) return;
        memory += bytes;
        views.add(new View("reduced", rows, low, high, shape(rows, low, high, budget), reduction));
    }

    BigInteger[] restoreAndCheck(View view, BigInteger[] counts) {
        if (counts == null) return null;
        BigInteger[] result = view.restore(counts);
        if (result.length != original.lower.length) throw new IllegalStateException("Unmapped count model");
        for (int i = 0; i < result.length; i++) {
            budget.check();
            if (result[i].compareTo(original.lower[i]) < 0 || original.upper[i] != null && result[i].compareTo(original.upper[i]) > 0)
                throw new IllegalStateException("Restored counts violate original domain");
        }
        for (var row : original.rows) {
            BigInteger total = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.check();
                total = total.add(term.getValue().multiply(result[term.getKey()]));
            }
            if (total.compareTo(row.upper()) > 0) throw new IllegalStateException("Restored counts violate original constraint");
        }
        return result;
    }

    private static Shape shape(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, PlanningBudget budget) {
        long terms = 0;
        int bits = 1, unfixed = 0;
        for (var row : rows) for (BigInteger coefficient : row.terms().values()) {
            budget.check();
            terms++;
            bits = Math.max(bits, coefficient.bitLength());
        }
        for (int i = 0; i < low.length; i++) if (!low[i].equals(high[i])) unfixed++;
        return new Shape(low.length, terms, bits, unfixed);
    }

    @Override
    public void close() {
        views.clear();
        budget.release(memory);
        memory = 0;
    }
}
