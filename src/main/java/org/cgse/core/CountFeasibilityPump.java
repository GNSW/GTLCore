package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Exact L1 projection/rounding pump. Restricted or interrupted attempts export witnesses only. */
final class CountFeasibilityPump implements AutoCloseable {

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private final Set<List<BigInteger>> rounded = new HashSet<>();
    private final long allowance;
    private ExactRational[] point;
    private ExactLinearProgram projection;
    private BigInteger[] counts;
    private long memory, work;
    private int rounds, perturbed;
    private boolean complete;

    CountFeasibilityPump(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                         ExactRational[] point, PlanningBudget budget, long maximumWork) {
        this.rows = rows;
        this.lower = lower;
        this.upper = upper;
        this.point = point;
        this.budget = budget;
        allowance = Math.min(maximumWork, budget.remainingWork() / 16);
        if (point == null || lower.length > 48 || rows.size() > 192 || allowance < 2048) {
            complete = true;
            return;
        }
        long bytes = 2048L + 2048L * lower.length + 96L * rows.size();
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
    }

    boolean step() {
        if (complete) return true;
        long started = budget.threadWork();
        try {
            budget.check();
            if (work >= allowance || rounds == 6) return finish("local_limit");
            if (projection != null) {
                if (!projection.step()) return false;
                if (projection.result() != ExactLinearProgram.Result.OPTIMAL) return finish("projection_unresolved");
                point = Arrays.copyOf(projection.point(), lower.length);
                projection.close();
                projection = null;
                rounds++;
                return false;
            }
            BigInteger[] target = new BigInteger[lower.length];
            for (int i = 0; i < target.length; i++) {
                budget.check();
                BigInteger floor = point[i].floor();
                boolean up = point[i].subtract(ExactRational.of(floor)).compareTo(new ExactRational(BigInteger.ONE, BigInteger.TWO)) > 0;
                target[i] = clamp(i, up ? point[i].ceil() : floor);
            }
            if (valid(target)) {
                counts = target;
                return finish("verified_witness");
            }
            if (!rounded.add(List.of(target))) {
                // A deterministic perturbation only changes the next proposal.
                // It neither fixes variables nor forbids a region of the model.
                for (int at = 0; at < target.length; at++) {
                    int i = (at + rounds) % target.length;
                    BigInteger alternative = clamp(i, target[i].add((at + rounds) % 2 == 0 ? BigInteger.ONE : BigInteger.ONE.negate()));
                    if (alternative.equals(target[i])) continue;
                    target[i] = alternative;
                    perturbed++;
                    if (at >= rounds % 3) break;
                }
                if (valid(target)) {
                    counts = target;
                    return finish("verified_witness");
                }
            }
            List<ExactLinearProgram.Constraint> problem = new ArrayList<>(rows);
            int n = lower.length;
            BigInteger[] objective = new BigInteger[2 * n];
            Arrays.fill(objective, BigInteger.ZERO);
            for (int i = 0; i < n; i++) {
                budget.check();
                problem.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), lower[i].negate()));
                if (upper[i] != null) problem.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), upper[i]));
                problem.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE, i + n, BigInteger.ONE.negate()), target[i]));
                problem.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate(), i + n, BigInteger.ONE.negate()), target[i].negate()));
                objective[i + n] = BigInteger.ONE.negate();
            }
            projection = new ExactLinearProgram(2 * n, problem, objective, budget);
            return false;
        } finally {
            work += budget.threadWork() - started;
        }
    }

    private BigInteger clamp(int id, BigInteger value) {
        value = value.max(lower[id]);
        return upper[id] == null ? value : value.min(upper[id]);
    }

    private boolean valid(BigInteger[] value) {
        for (int i = 0; i < value.length; i++) if (value[i].compareTo(lower[i]) < 0 || upper[i] != null && value[i].compareTo(upper[i]) > 0) return false;
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.check();
                sum = sum.add(term.getValue().multiply(value[term.getKey()]));
            }
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private boolean finish(String detail) {
        complete = true;
        budget.note("count_feasibility_pump", detail + "; projections=" + rounds + "; perturbed=" + perturbed + "; work=" + work);
        return true;
    }

    BigInteger[] counts() {
        return counts == null ? null : counts.clone();
    }

    @Override
    public void close() {
        if (projection != null) projection.close();
        projection = null;
        budget.release(memory);
        memory = 0;
    }
}
