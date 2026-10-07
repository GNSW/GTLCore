package org.cgse.core;

import java.util.*;

/** Sparse exact PLU factorization; fill and work are charged before retention. */
final class ExactSparseFactor implements AutoCloseable {

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private final PlanningBudget budget;
    private final List<Map<Integer, ExactRational>> lower = new ArrayList<>(), upper = new ArrayList<>();
    private int[] permutation;
    private long memory, work, allowance;
    private boolean ready;

    static ExactSparseFactor build(List<Map<Integer, ExactRational>> columns, PlanningBudget budget, long allowance) {
        ExactSparseFactor factor = new ExactSparseFactor(budget);
        factor.allowance = allowance;
        try {
            factor.factor(columns);
        } catch (Stop | ExactRational.PrecisionLimit stop) {
            factor.close();
            return null;
        } catch (RuntimeException | Error failure) {
            factor.close();
            throw failure;
        }
        return factor;
    }

    private ExactSparseFactor(PlanningBudget budget) {
        this.budget = budget;
    }

    private void factor(List<Map<Integer, ExactRational>> columns) {
        int n = columns.size();
        reserve(1024L + 512L * n);
        permutation = new int[n];
        List<Map<Integer, ExactRational>> workRows = new ArrayList<>(), multipliers = new ArrayList<>();
        List<Set<Integer>> activeColumns = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            workRows.add(new TreeMap<>());
            multipliers.add(new TreeMap<>());
            activeColumns.add(new TreeSet<>());
        }
        for (int j = 0; j < n; j++) for (var entry : columns.get(j).entrySet()) {
            charge();
            if (entry.getValue().signum() == 0) continue;
            reserve(896);
            workRows.get(entry.getKey()).put(j, entry.getValue());
            activeColumns.get(j).add(entry.getKey());
        }
        for (int k = 0; k < n; k++) {
            charge();
            int chosen = -1;
            for (int id : activeColumns.get(k)) {
                charge();
                if (chosen < 0 || workRows.get(id).size() < workRows.get(chosen).size()) chosen = id;
                if (workRows.get(id).size() == 1) break;
            }
            if (chosen < 0) throw new Stop();
            permutation[k] = chosen;
            Map<Integer, ExactRational> pivot = workRows.get(chosen);
            for (int column : pivot.keySet()) activeColumns.get(column).remove(chosen);
            ExactRational divisor = pivot.get(k);
            for (int id : new ArrayList<>(activeColumns.get(k))) {
                charge();
                Map<Integer, ExactRational> row = workRows.get(id);
                ExactRational multiplier = row.remove(k).divide(divisor);
                activeColumns.get(k).remove(id);
                reserve(896);
                multipliers.get(id).put(k, multiplier);
                for (var entry : pivot.entrySet()) {
                    charge();
                    int column = entry.getKey();
                    if (column == k) continue;
                    ExactRational old = row.getOrDefault(column, ExactRational.ZERO);
                    ExactRational value = old.subtract(multiplier.multiply(entry.getValue()));
                    if (value.signum() == 0) {
                        row.remove(column);
                        activeColumns.get(column).remove(id);
                    } else {
                        if (old.signum() == 0) {
                            reserve(896);
                            activeColumns.get(column).add(id);
                        }
                        row.put(column, value);
                    }
                }
            }
            upper.add(pivot);
            lower.add(multipliers.get(chosen));
        }
        ready = true;
    }

    void solve(ExactRational[] vector, Runnable charge) {
        if (!ready || vector.length != permutation.length) throw new IllegalArgumentException("Invalid sparse basis solve");
        ExactRational[] result = new ExactRational[vector.length];
        for (int i = 0; i < result.length; i++) {
            charge.run();
            result[i] = vector[permutation[i]];
            for (var entry : lower.get(i).entrySet()) {
                charge.run();
                result[i] = result[i].subtract(entry.getValue().multiply(result[entry.getKey()]));
            }
        }
        for (int i = result.length - 1; i >= 0; i--) {
            for (var entry : upper.get(i).entrySet()) if (entry.getKey() != i) {
                charge.run();
                result[i] = result[i].subtract(entry.getValue().multiply(result[entry.getKey()]));
            }
            result[i] = result[i].divide(upper.get(i).get(i));
        }
        System.arraycopy(result, 0, vector, 0, result.length);
    }

    void transpose(ExactRational[] vector, Runnable charge) {
        if (!ready || vector.length != permutation.length) throw new IllegalArgumentException("Invalid sparse transpose solve");
        ExactRational[] result = vector.clone();
        for (int i = 0; i < result.length; i++) {
            charge.run();
            result[i] = result[i].divide(upper.get(i).get(i));
            for (var entry : upper.get(i).entrySet()) if (entry.getKey() != i) {
                charge.run();
                int j = entry.getKey();
                result[j] = result[j].subtract(entry.getValue().multiply(result[i]));
            }
        }
        for (int i = result.length - 1; i >= 0; i--) for (var entry : lower.get(i).entrySet()) {
            charge.run();
            int j = entry.getKey();
            result[j] = result[j].subtract(entry.getValue().multiply(result[i]));
        }
        for (int i = 0; i < result.length; i++) vector[permutation[i]] = result[i];
    }

    long bytes() {
        return memory;
    }

    private void reserve(long bytes) {
        if (!budget.tryReserve(bytes)) throw new Stop();
        memory += bytes;
    }

    private void charge() {
        budget.check();
        if (++work > allowance) throw new Stop();
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
