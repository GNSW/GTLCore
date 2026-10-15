// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** SCIP-style dynamic rounding locks; these preferences never restrict a domain. */
final class CountLockBranch {

    record Decision(int variable, boolean up) {}

    /**
     * Request-owned rounding scores. Bounds may tighten OR widen between visits;
     * only the current values and immutable row identities define this cache.
     * A refused cache is just the ordinary scan, never a search conclusion.
     */
    static final class Session implements AutoCloseable {

        private record Edge(int row, int sign) {}

        private final PlanningBudget budget;
        private final int variables;
        private List<ExactLinearProgram.Constraint> source = new ArrayList<>();
        private List<List<Edge>> incident = new ArrayList<>();
        private List<Boolean> active = new ArrayList<>();
        private List<Integer> unfixed = new ArrayList<>();
        private BigInteger[] previousLow, previousHigh;
        private int[] up, down;
        private double[] degree;
        private final BitSet dirtyRows = new BitSet(), dirtyColumns = new BitSet();
        private long memory, snapshotBytes;
        private boolean disabled;

        Session(int variables, PlanningBudget budget) {
            this.variables = variables;
            this.budget = budget;
            long bytes = 512L + 144L * variables;
            disabled = bytes > budget.availableBytes() / 8 || !budget.tryReserve(bytes);
            memory = disabled ? 0 : bytes;
            try {
                int length = disabled ? 0 : variables;
                previousLow = new BigInteger[length];
                previousHigh = new BigInteger[length];
                up = new int[length];
                down = new int[length];
                degree = new double[length];
                for (int i = 0; i < length; i++) {
                    scan();
                    incident.add(new ArrayList<>());
                }
            } catch (RuntimeException | Error failure) {
                budget.release(memory);
                memory = 0;
                throw failure;
            }
        }

        Decision choose(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                        double[] activity) {
            if (disabled) return CountLockBranch.choose(rows, low, high, activity, budget);
            try {
                if (low.length != variables || high.length != variables || activity.length != variables ||
                        !extend(rows) || !reserveSnapshot(low, high)) {
                    close();
                    return CountLockBranch.choose(rows, low, high, activity, budget);
                }
                for (int i = 0; i < variables; i++) {
                    scan();
                    if (Objects.equals(previousLow[i], low[i]) && Objects.equals(previousHigh[i], high[i])) continue;
                    for (Edge edge : incident.get(i)) {
                        scan();
                        dirtyRows.set(edge.row());
                    }
                    dirtyColumns.set(i);
                    previousLow[i] = low[i];
                    previousHigh[i] = high[i];
                }
                for (int r = dirtyRows.nextSetBit(0); r >= 0; r = dirtyRows.nextSetBit(r + 1)) {
                    var row = source.get(r);
                    BigInteger maximum = BigInteger.ZERO;
                    boolean finite = true;
                    int free = 0;
                    for (var term : row.terms().entrySet()) {
                        scan();
                        int id = term.getKey();
                        if (term.getValue().signum() != 0 && !low[id].equals(high[id])) free++;
                        BigInteger endpoint = term.getValue().signum() > 0 ? high[id] : low[id];
                        if (endpoint == null) finite = false;
                        else {
                            budget.operation(PlanningBudget.Operation.INTEGER, Math.max(term.getValue().bitLength(), endpoint.bitLength()));
                            maximum = maximum.add(term.getValue().multiply(endpoint));
                        }
                    }
                    boolean enabled = !finite || maximum.compareTo(row.upper()) > 0;
                    if (enabled != active.get(r) || free != unfixed.get(r)) {
                        active.set(r, enabled);
                        unfixed.set(r, free);
                        for (int id : row.terms().keySet()) {
                            scan();
                            dirtyColumns.set(id);
                        }
                    }
                }
                dirtyRows.clear();
                for (int i = dirtyColumns.nextSetBit(0); i >= 0; i = dirtyColumns.nextSetBit(i + 1)) {
                    up[i] = down[i] = 0;
                    degree[i] = 0;
                    if (low[i].equals(high[i])) continue;
                    // Re-sum in ORIGINAL row order. Subtracting old floating
                    // contributions would drift and change tied decisions.
                    for (Edge edge : incident.get(i)) {
                        scan();
                        if (!active.get(edge.row())) continue;
                        degree[i] += 1.0 / unfixed.get(edge.row());
                        if (edge.sign() > 0) up[i]++;
                        else down[i]++;
                    }
                }
                dirtyColumns.clear();
                return select(low, high, activity, up, down, degree, budget);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        private boolean extend(List<ExactLinearProgram.Constraint> rows) {
            if (rows.size() < source.size()) return false;
            for (int r = 0; r < source.size(); r++) {
                scan();
                // Replacing/reordering a row invalidates all scores, even if
                // its old coefficients happen to have the same dimensions.
                if (rows.get(r) != source.get(r)) return false;
            }
            for (int r = source.size(); r < rows.size(); r++) {
                var row = rows.get(r);
                scan();
                long bytes = 128L + 64L * row.terms().size();
                if (bytes > budget.availableBytes() / 8 || !budget.tryReserve(bytes)) return false;
                memory += bytes;
                source.add(row);
                active.add(false);
                unfixed.add(-1);
                for (var term : row.terms().entrySet()) {
                    scan();
                    if (term.getKey() < 0 || term.getKey() >= variables) return false;
                    if (term.getValue().signum() != 0)
                        incident.get(term.getKey()).add(new Edge(r, term.getValue().signum()));
                }
                dirtyRows.set(r);
            }
            return true;
        }

        private boolean reserveSnapshot(BigInteger[] low, BigInteger[] high) {
            // Backtracking may release the trail entries that previously owned
            // these endpoint objects. Account for the retained snapshot too.
            long bytes = 0;
            for (int i = 0; i < variables; i++) {
                scan();
                bytes += 32L + (low[i].bitLength() + 7L) / 8;
                if (high[i] != null) bytes += 32L + (high[i].bitLength() + 7L) / 8;
            }
            long growth = bytes - snapshotBytes;
            if (growth > 0 && !budget.tryReserve(growth)) return false;
            if (growth < 0) budget.release(-growth);
            memory += growth;
            snapshotBytes = bytes;
            return true;
        }

        private void scan() { budget.operation(PlanningBudget.Operation.SCAN, 0); }

        @Override
        public void close() {
            disabled = true;
            budget.release(memory);
            memory = 0;
            snapshotBytes = 0;
            source = List.of();
            incident = List.of();
            active = List.of();
            unfixed = List.of();
            previousLow = previousHigh = new BigInteger[0];
            up = down = new int[0];
            degree = new double[0];
            dirtyRows.clear();
            dirtyColumns.clear();
        }
    }

    static Decision choose(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                           double[] activity, PlanningBudget budget) {
        long bytes = 256L + 24L * low.length;
        if (!budget.tryReserve(bytes)) return null;
        try {
            int[] up = new int[low.length], down = new int[low.length];
            double[] degree = new double[low.length];
            for (var row : rows) {
                BigInteger maximum = BigInteger.ZERO;
                boolean finite = true;
                int unfixed = 0;
                for (var term : row.terms().entrySet()) {
                    budget.operation(PlanningBudget.Operation.SCAN, 0);
                    if (term.getValue().signum() != 0 && !low[term.getKey()].equals(high[term.getKey()])) unfixed++;
                    BigInteger endpoint = term.getValue().signum() > 0 ? high[term.getKey()] : low[term.getKey()];
                    if (endpoint == null) finite = false;
                    else {
                        budget.operation(PlanningBudget.Operation.INTEGER, Math.max(term.getValue().bitLength(), endpoint.bitLength()));
                        maximum = maximum.add(term.getValue().multiply(endpoint));
                    }
                }
                if (finite && maximum.compareTo(row.upper()) <= 0) continue;
                for (var term : row.terms().entrySet()) {
                    budget.operation(PlanningBudget.Operation.SCAN, 0);
                    int id = term.getKey();
                    if (low[id].equals(high[id]) || term.getValue().signum() == 0) continue;
                    // Count only still active coordinates. Fixed auxiliaries
                    // and fulfilled domain rows must not distort this score.
                    degree[id] += 1.0 / unfixed;
                    if (term.getValue().signum() > 0) up[id]++;
                    else if (term.getValue().signum() < 0) down[id]++;
                }
            }
            return select(low, high, activity, up, down, degree, budget);
        } finally {
            budget.release(bytes);
        }
    }

    private static Decision select(BigInteger[] low, BigInteger[] high, double[] activity,
                                   int[] up, int[] down, double[] degree, PlanningBudget budget) {
        int best = -1;
        for (int i = 0; i < low.length; i++) {
            budget.operation(PlanningBudget.Operation.SCAN, 0);
            double score = (up[i] + down[i]) * (1 + activity[i] + degree[i]);
            if (!low[i].equals(high[i]) && (best < 0 ||
                    score > (up[best] + down[best]) * (1 + activity[best] + degree[best])))
                best = i;
        }
        return best < 0 ? null : new Decision(best, down[best] >= up[best]);
    }
}
