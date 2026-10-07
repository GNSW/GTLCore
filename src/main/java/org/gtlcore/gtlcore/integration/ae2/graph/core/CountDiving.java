package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Coefficient-lock guided rounding dives, following SCIP's diving strategy.
 * Propagation runs at every node; a small exact feasibility LP repairs the
 * relaxation after a fixing. Reverse branches are retained within a local
 * quota. A dive exports checked integer witnesses, never an infeasibility proof.
 */
final class CountDiving implements AutoCloseable {

    private record Node(BigInteger[] lower, BigInteger[] upper, ExactRational[] point, int depth) {}

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] originalLower, originalUpper;
    private final PlanningBudget budget;
    private final Deque<Node> pending = new ArrayDeque<>();
    private final long allowance, nodeBytes;
    private int[] downLocks, upLocks;
    private Node active;
    private List<ExactLinearProgram.Constraint> problem;
    private CountBounds propagating;
    private ExactLinearProgram linear;
    private ExactLinearProgram.Basis basis;
    private BigInteger[] lower, upper, counts;
    private ExactRational[] point;
    private int visited, relaxations, reversals;
    private long work, nodeStart, memory;
    private boolean complete;

    CountDiving(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                ExactRational[] point, PlanningBudget budget) {
        this.rows = rows;
        originalLower = lower;
        originalUpper = upper;
        this.budget = budget;
        allowance = Math.min(32768, budget.remainingWork() / 16);
        nodeBytes = 256L + 192L * lower.length;
        if (point == null || lower.length == 0 || lower.length > 96 || rows.size() > 384 || allowance < 2048) {
            complete = true;
            return;
        }
        long bytes = 1024L + 32L * rows.size() + 32L * lower.length;
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        long before = budget.threadWork();
        try {
            downLocks = new int[lower.length];
            upLocks = new int[lower.length];
            for (var row : rows) for (var term : row.terms().entrySet()) {
                budget.check();
                if (budget.threadWork() - before >= allowance / 4) {
                    complete = true;
                    return;
                }
                if (term.getValue().signum() < 0) downLocks[term.getKey()]++;
                else if (term.getValue().signum() > 0) upLocks[term.getKey()]++;
            }
            push(lower, upper, point, 0);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        } finally {
            work += budget.threadWork() - before;
        }
    }

    boolean step() {
        if (complete) return true;
        long before = budget.threadWork();
        try {
            budget.check();
            if (work >= allowance || visited >= 32) return finish("local_limit");
            if (active == null) {
                if (pending.isEmpty()) return finish("unresolved");
                active = pending.pop();
                visited++;
                lower = active.lower();
                upper = active.upper();
                point = active.point();
                problem = new ArrayList<>(rows);
                for (int i = 0; i < lower.length; i++) {
                    budget.check();
                    problem.add(bound(i, lower[i], true));
                    if (upper[i] != null) problem.add(bound(i, upper[i], false));
                }
                nodeStart = work;
                propagating = new CountBounds(lower.length, problem, budget);
                return false;
            }
            if (propagating != null) {
                if (!propagating.step()) {
                    if (work - nodeStart >= 4096) discard();
                    return false;
                }
                boolean blocked = propagating.blocked();
                lower = propagating.lowerBounds();
                upper = propagating.upperBounds();
                propagating.close();
                propagating = null;
                if (blocked) {
                    reversals++;
                    discard();
                    return false;
                }
                // Even incomplete propagation yields sound bounds. Check the
                // rounded proposal against every original row before accepting.
                if (acceptRounded(point)) return finish("verified_rounding");
                if (active.depth() >= 32) {
                    discard();
                    return false;
                }
                if (active.depth() == 0 && rationalValid(point)) return branch();
                if (relaxations >= 12) return finish("relaxation_limit");
                BigInteger[] objective = new BigInteger[lower.length];
                Arrays.fill(objective, BigInteger.ZERO);
                // A completed feasibility basis can seed a deeper dive. The
                // LP independently checks that this branch retains every
                // ancestor assumption, so reversal to a sibling is safe too.
                long bytes = (problem.size() + 2L) * (lower.length + 2L) * 768L +
                        64L * (problem.size() + lower.length + 2L);
                boolean retain = bytes <= Math.min(4L << 20, budget.availableBytes() / 8);
                // Keep the short cold dive's choices stable. Once that dive
                // reverses or needs repeated repairs, reuse an eligible basis
                // instead of paying for every feasibility LP from scratch.
                var ancestor = relaxations < 3 && reversals == 0 ? null : basis;
                linear = new ExactLinearProgram(lower.length, problem, objective, budget, ancestor, retain);
                relaxations++;
                nodeStart = work;
                return false;
            }
            if (linear != null) {
                if (!linear.step()) {
                    if (work - nodeStart >= 8192) discard();
                    return false;
                }
                var status = linear.result();
                point = linear.point();
                var nextBasis = linear.takeBasis();
                if (nextBasis != null) {
                    if (basis != null) basis.close();
                    basis = nextBasis;
                }
                linear.close();
                linear = null;
                if (status != ExactLinearProgram.Result.OPTIMAL || point == null) {
                    reversals++;
                    discard();
                    return false;
                }
                if (acceptRounded(point)) return finish("verified_relaxation");
                return branch();
            }
            discard();
            return false;
        } catch (ExactRational.PrecisionLimit ignored) {
            return finish("precision_limit");
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private boolean branch() {
        int chosen = -1, bestLocks = Integer.MAX_VALUE;
        ExactRational bestDistance = null;
        boolean preferredUp = false;
        for (int i = 0; i < point.length; i++) {
            budget.check();
            if (point[i].integral() || lower[i].equals(upper[i])) continue;
            var fraction = point[i].subtract(ExactRational.of(point[i].floor()));
            boolean up = upLocks[i] < downLocks[i] || upLocks[i] == downLocks[i] &&
                    fraction.compareTo(new ExactRational(BigInteger.ONE, BigInteger.TWO)) > 0;
            int locks = up ? upLocks[i] : downLocks[i];
            var distance = up ? ExactRational.ONE.subtract(fraction) : fraction;
            if (chosen < 0 || locks < bestLocks || locks == bestLocks && distance.compareTo(bestDistance) < 0) {
                chosen = i;
                bestLocks = locks;
                bestDistance = distance;
                preferredUp = up;
            }
        }
        if (chosen >= 0) {
            // Stack order explores the cheaper rounding direction first. The
            // reverse domain remains available if propagation contradicts it.
            child(chosen, !preferredUp);
            child(chosen, preferredUp);
        }
        discard();
        return false;
    }

    private void child(int id, boolean up) {
        BigInteger[] low = lower.clone(), high = upper.clone();
        if (up) low[id] = low[id].max(point[id].ceil());
        else high[id] = high[id] == null ? point[id].floor() : high[id].min(point[id].floor());
        if (high[id] != null && low[id].compareTo(high[id]) > 0) return;
        push(low, high, point, active.depth() + 1);
    }

    private void push(BigInteger[] low, BigInteger[] high, ExactRational[] value, int depth) {
        if (!budget.tryReserve(nodeBytes)) return;
        memory += nodeBytes;
        pending.push(new Node(low.clone(), high.clone(), value, depth));
    }

    private boolean acceptRounded(ExactRational[] value) {
        BigInteger[] candidate = new BigInteger[lower.length];
        for (int i = 0; i < candidate.length; i++) {
            budget.check();
            var fraction = value[i].subtract(ExactRational.of(value[i].floor()));
            candidate[i] = (fraction.compareTo(new ExactRational(BigInteger.ONE, BigInteger.TWO)) > 0 ? value[i].ceil() : value[i].floor()).max(lower[i]);
            if (upper[i] != null) candidate[i] = candidate[i].min(upper[i]);
            if (candidate[i].compareTo(lower[i]) < 0 || candidate[i].compareTo(originalLower[i]) < 0 ||
                    originalUpper[i] != null && candidate[i].compareTo(originalUpper[i]) > 0)
                return false;
        }
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.check();
                sum = sum.add(term.getValue().multiply(candidate[term.getKey()]));
            }
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        counts = candidate;
        return true;
    }

    private boolean rationalValid(ExactRational[] value) {
        for (int i = 0; i < value.length; i++) {
            budget.check();
            if (value[i].compareTo(ExactRational.of(lower[i])) < 0 ||
                    upper[i] != null && value[i].compareTo(ExactRational.of(upper[i])) > 0)
                return false;
        }
        for (var row : rows) {
            ExactRational sum = ExactRational.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.check();
                sum = sum.add(value[term.getKey()].multiply(ExactRational.of(term.getValue())));
            }
            if (sum.compareTo(ExactRational.of(row.upper())) > 0) return false;
        }
        return true;
    }

    private static ExactLinearProgram.Constraint bound(int id, BigInteger value, boolean lower) {
        return new ExactLinearProgram.Constraint(Map.of(id, lower ? BigInteger.ONE.negate() : BigInteger.ONE), lower ? value.negate() : value);
    }

    private void discard() {
        if (propagating != null) propagating.close();
        if (linear != null) linear.close();
        propagating = null;
        linear = null;
        if (active != null) {
            budget.release(nodeBytes);
            memory -= nodeBytes;
        }
        active = null;
        problem = null;
    }

    private boolean finish(String detail) {
        complete = true;
        budget.note("count_diving", detail + "; nodes=" + visited + "; relaxations=" + relaxations + "; reversals=" + reversals + "; work=" + work);
        return true;
    }

    BigInteger[] counts() {
        return counts == null ? null : counts.clone();
    }

    @Override
    public void close() {
        discard();
        if (basis != null) basis.close();
        basis = null;
        pending.clear();
        budget.release(memory);
        memory = 0;
    }
}
