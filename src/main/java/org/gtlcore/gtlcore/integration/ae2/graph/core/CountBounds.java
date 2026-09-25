package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** Work-queue integer bound propagation over joint material and branch constraints. */
final class CountBounds implements AutoCloseable {

    private final List<ExactLinearProgram.Constraint> rows;
    private final PlanningBudget budget;
    private final BigInteger[] lower, upper;
    private final List<List<Integer>> affected = new ArrayList<>();
    private final ArrayDeque<Integer> queue = new ArrayDeque<>();
    private final BitSet queued = new BitSet();
    private long memory, work;
    private long allowance;
    private boolean complete, blocked;
    private boolean combined;

    CountBounds(int variables, List<ExactLinearProgram.Constraint> rows, PlanningBudget budget) {
        this.rows = new ArrayList<>(rows);
        this.budget = budget;
        lower = new BigInteger[variables];
        upper = new BigInteger[variables];
        Arrays.fill(lower, BigInteger.ZERO);
        long bytes = 128L + 128L * variables + 64L * rows.size();
        long incidences = 0;
        for (var row : rows) incidences += row.terms().size();
        bytes += 32L * incidences;
        // A wide/deep DAG may need several waves of propagation. Allocate by
        // sparse model size, while retaining a local ceiling for slow cycles.
        allowance = Math.min(262_144L, Math.max(50_000L, 64L * incidences));
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        for (int i = 0; i < variables; i++) affected.add(new ArrayList<>());
        for (int r = 0; r < rows.size(); r++) {
            for (int variable : rows.get(r).terms().keySet()) affected.get(variable).add(r);
            enqueue(r);
        }
    }

    boolean step() {
        budget.check();
        if (complete) return true;
        if (queue.isEmpty()) return finish(false);
        if (work >= allowance) {
            if (!combined) {
                combined = true;
                if (combinePending()) {
                    allowance += 50_000;
                    return false;
                }
            }
            budget.note("count_bounds", "work_limit; terms=" + work + "; pending_rows=" + queue.size());
            return finish(false);
        }
        int id = queue.removeFirst();
        queued.clear(id);
        var row = rows.get(id);
        BigInteger finite = BigInteger.ZERO;
        int unknown = 0;
        for (var term : row.terms().entrySet()) {
            charge();
            BigInteger bound = term.getValue().signum() >= 0 ? lower[term.getKey()] : upper[term.getKey()];
            if (bound == null) unknown++;
            else finite = finite.add(term.getValue().multiply(bound));
        }
        if (unknown == 0 && finite.compareTo(row.upper()) > 0) return finish(true);
        for (var term : row.terms().entrySet()) {
            charge();
            int variable = term.getKey();
            BigInteger coefficient = term.getValue();
            if (coefficient.signum() == 0) continue;
            BigInteger old = coefficient.signum() > 0 ? lower[variable] : upper[variable];
            if (unknown - (old == null ? 1 : 0) != 0) continue;
            BigInteger others = old == null ? finite : finite.subtract(coefficient.multiply(old));
            BigInteger limit = row.upper().subtract(others);
            if (coefficient.signum() > 0) {
                if (limit.signum() < 0) return finish(true);
                BigInteger next = limit.divide(coefficient);
                if (next.bitLength() > 2048) return finish(false);
                if (upper[variable] == null || next.compareTo(upper[variable]) < 0) {
                    upper[variable] = next;
                    affected.get(variable).forEach(this::enqueue);
                }
            } else {
                BigInteger next = CheckedAmounts.ceilDiv(limit.negate(), coefficient.negate());
                if (next.bitLength() > 2048) return finish(false);
                if (next.compareTo(lower[variable]) > 0) {
                    lower[variable] = next;
                    affected.get(variable).forEach(this::enqueue);
                }
            }
            if (upper[variable] != null && lower[variable].compareTo(upper[variable]) > 0) return finish(true);
        }
        return false;
    }

    private void enqueue(int row) {
        if (!queued.get(row)) {
            queued.set(row);
            queue.addLast(row);
        }
    }

    private boolean combinePending() {
        // Tightening one bound at a time can circle indefinitely around a
        // nearly balanced conversion loop. Add exact nonnegative combinations
        // of its sparse rows, eliminating intermediate recipe counts directly.
        // These are consequences of the original constraints, never learned
        // from a scheduling failure or a cutoff.
        Set<Integer> variables = new LinkedHashSet<>();
        for (int row : queue) variables.addAll(rows.get(row).terms().keySet());
        if (variables.size() > 128) return false;
        Set<ExactLinearProgram.Constraint> known = new HashSet<>(rows);
        int added = 0;
        for (int round = 0; round < 3; round++) {
            int before = added;
            for (int variable : variables) {
                List<Integer> positive = new ArrayList<>(), negative = new ArrayList<>();
                for (int row : affected.get(variable)) {
                    budget.check();
                    var terms = rows.get(row).terms();
                    if (terms.size() < 2 || terms.size() > 4) continue;
                    (terms.get(variable).signum() > 0 ? positive : negative).add(row);
                }
                if ((long) positive.size() * negative.size() > 16) continue;
                for (int first : positive) for (int second : negative) {
                    budget.check();
                    var consequence = combine(rows.get(first), rows.get(second), variable);
                    if (consequence == null || !known.add(consequence)) continue;
                    long bytes = 128L + 64L * consequence.terms().size();
                    if (!budget.tryReserve(bytes)) return added > 0;
                    memory += bytes;
                    int id = rows.size();
                    rows.add(consequence);
                    for (int key : consequence.terms().keySet()) affected.get(key).add(id);
                    enqueue(id);
                    if (++added >= 256) return true;
                }
            }
            if (before == added) break;
        }
        return added > 0;
    }

    private ExactLinearProgram.Constraint combine(ExactLinearProgram.Constraint first,
                                                   ExactLinearProgram.Constraint second, int variable) {
        BigInteger a = first.terms().get(variable), b = second.terms().get(variable).negate();
        BigInteger gcd = a.gcd(b);
        a = a.divide(gcd);
        b = b.divide(gcd);
        Map<Integer, BigInteger> terms = new LinkedHashMap<>();
        for (var term : first.terms().entrySet()) {
            budget.check();
            terms.put(term.getKey(), term.getValue().multiply(b));
        }
        for (var term : second.terms().entrySet()) {
            budget.check();
            terms.merge(term.getKey(), term.getValue().multiply(a), BigInteger::add);
        }
        terms.values().removeIf(value -> value.signum() == 0);
        if (terms.size() > 4) return null;
        BigInteger upper = first.upper().multiply(b).add(second.upper().multiply(a));
        gcd = BigInteger.ZERO;
        for (BigInteger value : terms.values()) {
            if (value.bitLength() > 2048) return null;
            gcd = gcd.gcd(value);
        }
        if (upper.bitLength() > 2048) return null;
        if (gcd.signum() > 0) {
            BigInteger divisor = gcd;
            terms.replaceAll((key, value) -> value.divide(divisor));
            BigInteger[] division = upper.divideAndRemainder(divisor);
            upper = division[0];
            if (division[1].signum() < 0) upper = upper.subtract(BigInteger.ONE);
        }
        return new ExactLinearProgram.Constraint(terms, upper);
    }

    List<ExactLinearProgram.Constraint> tightened() {
        var result = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 0; i < lower.length; i++) {
            if (lower[i].signum() > 0) result.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), lower[i].negate()));
            if (upper[i] != null) result.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), upper[i]));
        }
        return result;
    }

    boolean blocked() { return blocked; }

    /** A candidate only; callers must check every row after a work cutoff. */
    BigInteger[] lowerBounds() { return lower.clone(); }

    private void charge() { budget.check(); work++; }

    private boolean finish(boolean value) {
        complete = true;
        blocked = value;
        close();
        return true;
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
