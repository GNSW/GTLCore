package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Small finite integer domains encoded by ordered bound literals x >= lower+k,
 * as in CP/SAT order encodings. Long domains are never enumerated. An optional
 * lower-bound face of a wide domain can yield a witness but cannot export a
 * negative conclusion or a learned clause about the unrestricted model.
 */
final class CountDomainSearch implements AutoCloseable {

    private final List<ExactLinearProgram.Constraint> original;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private final int[] start, width;
    private final List<Integer> owner = new ArrayList<>(), threshold = new ArrayList<>();
    private final List<CountConflict> learned = new ArrayList<>();
    private CountCdcl search;
    private CountLcg lazy;
    private CountSymmetry symmetry;
    private boolean symmetryAttempted;
    private BigInteger[] counts;
    private long memory;
    private boolean trial, complete, infeasible;

    CountDomainSearch(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                      PlanningBudget budget, long allowance) {
        this(rows, lower, upper, budget, allowance, CountCdcl.Branching.ACTIVITY);
    }

    CountDomainSearch(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                      PlanningBudget budget, long allowance, CountCdcl.Branching branching) {
        original = rows;
        this.lower = lower;
        this.upper = upper;
        this.budget = budget;
        start = new int[lower.length];
        width = new int[lower.length];
        // Match the actual PB backend's sparse admission. A binary model must
        // not be rejected merely because the original variable count is 513.
        if (lower.length > 1024 || rows.size() > 4096) {
            complete = true;
            return;
        }
        int variables = 0;
        for (int i = 0; i < lower.length; i++) {
            start[i] = variables;
            BigInteger span = upper[i] == null ? null : upper[i].subtract(lower[i]);
            if (span == null || span.compareTo(BigInteger.valueOf(32)) > 0) {
                lazy = new CountLcg(rows, lower, upper, budget, Math.min(65536, allowance));
                return;
            } else if (span.signum() < 0) {
                complete = true;
                return;
            } else width[i] = span.intValueExact();
            variables += width[i];
        }
        if (variables > 1024) {
            lazy = new CountLcg(rows, lower, upper, budget, Math.min(65536, allowance));
            return;
        }
        long terms = rows.stream().flatMap(r -> r.terms().keySet().stream()).mapToLong(i -> width[i]).sum();
        if (terms > 65536) {
            lazy = new CountLcg(rows, lower, upper, budget, Math.min(65536, allowance));
            return;
        }
        long bytes = 2048 + 256L * variables + 192L * rows.size() + 128L * terms;
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        try {
            List<ExactLinearProgram.Constraint> encoded = new ArrayList<>();
            for (int i = 0; i < lower.length; i++) for (int k = 1; k <= width[i]; k++) {
                budget.check();
                int id = start[i] + k - 1;
                owner.add(i);
                threshold.add(k);
                if (k > 1) encoded.add(new ExactLinearProgram.Constraint(Map.of(id, BigInteger.ONE, id - 1, BigInteger.ONE.negate()), BigInteger.ZERO));
            }
            for (var row : rows) {
                BigInteger bound = row.upper();
                Map<Integer, BigInteger> entries = new LinkedHashMap<>();
                for (var term : row.terms().entrySet()) {
                    budget.check();
                    int id = term.getKey();
                    bound = bound.subtract(term.getValue().multiply(lower[id]));
                    for (int k = 0; k < width[id]; k++) {
                        budget.check();
                        entries.put(start[id] + k, term.getValue());
                    }
                }
                encoded.add(new ExactLinearProgram.Constraint(entries, bound));
            }
            BigInteger[] low = new BigInteger[variables], high = new BigInteger[variables];
            Arrays.fill(low, BigInteger.ZERO);
            Arrays.fill(high, BigInteger.ONE);
            // Encoding can expose many threshold decisions from few original
            // variables. Give that expanded representation a short first try,
            // preserving time for the compact LP and arithmetic representations.
            long work = variables > 128 && variables > 2L * lower.length ? Math.min(65536, allowance) : allowance;
            search = new CountCdcl(encoded, low, high, budget, work, branching);
            symmetryAttempted = branching != CountCdcl.Branching.ACTIVITY;
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    boolean step() {
        if (complete) return true;
        if (symmetry != null) {
            if (!symmetry.step()) return false;
            counts = symmetry.counts();
            infeasible = symmetry.infeasible();
            symmetry.close();
            symmetry = null;
            complete = true;
            // Clauses using lex leaders are scoped to the canonical view.
            // They must never enter the original execution-conflict pool.
            return true;
        }
        if (lazy != null) {
            if (!lazy.step()) return false;
            counts = lazy.counts();
            infeasible = lazy.infeasible();
            memory += CountMapping.retain(learned, lazy.learnedConflicts(), budget);
            lazy.close();
            lazy = null;
            complete = true;
            return true;
        }
        if (!search.step()) return false;
        var binary = search.counts();
        infeasible = search.infeasible() && !trial;
        if (binary != null) {
            counts = lower.clone();
            for (int i = 0; i < lower.length; i++) for (int k = 0; k < width[i]; k++) {
                budget.check();
                if (k > 0 && binary[start[i] + k].compareTo(binary[start[i] + k - 1]) > 0) throw new IllegalStateException("Nonmonotone order encoding");
                counts[i] = counts[i].add(binary[start[i] + k]);
            }
            for (int i = 0; i < counts.length; i++) if (counts[i].compareTo(lower[i]) < 0 || upper[i] != null && counts[i].compareTo(upper[i]) > 0)
                throw new IllegalStateException("Integer encoding violates original domain");
            for (var row : original) {
                BigInteger sum = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) {
                    budget.check();
                    sum = sum.add(term.getValue().multiply(counts[term.getKey()]));
                }
                if (sum.compareTo(row.upper()) > 0) throw new IllegalStateException("Integer encoding violates original row");
            }
        }
        if (!trial) for (var conflict : search.learnedConflicts()) {
            // Long threshold clauses are valuable inside the encoded search,
            // but can flood the small cross-strategy pool with nearly identical
            // interval combinations. Export short implications in that case.
            if (owner.size() > 2L * lower.length && conflict.assumptions().size() > 2) continue;
            var assumptions = new ArrayList<ExactLinearProgram.Constraint>();
            for (var row : conflict.assumptions()) {
                budget.check();
                var term = row.terms().entrySet().iterator().next();
                int literal = term.getKey(), id = owner.get(literal);
                boolean minimum = term.getValue().signum() < 0;
                BigInteger bound = lower[id].add(BigInteger.valueOf(threshold.get(literal) - (minimum ? 0L : 1L)));
                assumptions.add(new ExactLinearProgram.Constraint(Map.of(id, minimum ? BigInteger.ONE.negate() : BigInteger.ONE), minimum ? bound.negate() : bound));
            }
            long bytes = 128L + 144L * assumptions.size();
            if (!budget.tryReserve(bytes)) break;
            memory += bytes;
            learned.add(new CountConflict(assumptions));
        }
        if (counts == null && !infeasible && !symmetryAttempted && owner.size() == lower.length && owner.size() >= 8) {
            symmetryAttempted = true;
            search.close();
            search = null;
            symmetry = new CountSymmetry(original, lower, upper, budget, 262144);
            return false;
        }
        complete = true;
        budget.note("count_order_encoding", "variables=" + lower.length + "->" + owner.size() + "; trial=" + trial + "; witness=" + (counts != null) + "; proven_infeasible=" + infeasible);
        return true;
    }

    BigInteger[] counts() {
        return counts == null ? null : counts.clone();
    }

    boolean infeasible() {
        return infeasible;
    }

    List<CountConflict> learnedConflicts() {
        return List.copyOf(learned);
    }

    @Override
    public void close() {
        if (symmetry != null) symmetry.close();
        symmetry = null;
        if (search != null) search.close();
        if (lazy != null) lazy.close();
        lazy = null;
        search = null;
        budget.release(memory);
        memory = 0;
    }
}
