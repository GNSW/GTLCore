// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Bounded, reversible coordinate ordering; it adds no constraint or proof. */
final class CountCanonicalModel implements AutoCloseable {

    private static final int MAX_VARIABLES = 512, MAX_ROWS = 2048, MAX_TERMS = 131072, MAX_BITS = 4096;
    private static final long MAX_WORK = 262144;

    private final PlanningBudget budget;
    private List<ExactLinearProgram.Constraint> rows;
    private BigInteger[] lower, upper;
    private int[] order;
    private long memory;

    CountMapping substitution() {
        int[] inverse = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            budget.check();
            inverse[order[i]] = i;
        }
        return CountMapping.representatives(inverse);
    }

    private CountCanonicalModel(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                                BigInteger[] upper, int[] order, PlanningBudget budget, long memory) {
        this.rows = rows;
        this.lower = lower;
        this.upper = upper;
        this.order = order;
        this.budget = budget;
        this.memory = memory;
    }

    /** Null declines only this optional representation; it never means infeasible. */
    static CountCanonicalModel create(List<ExactLinearProgram.Constraint> source, BigInteger[] lower,
                                      BigInteger[] upper, PlanningBudget budget) {
        budget.checkpoint();
        int n = lower.length;
        if (n == 0 || n > MAX_VARIABLES || upper.length != n || source.size() > MAX_ROWS) return null;
        long allowance = Math.min(MAX_WORK, budget.remainingWork() / 16);
        if (allowance < 1024) return null;
        try (Workspace work = new Workspace(budget, allowance)) {
            // Array/list headers, variable order, inverse, costs and sort scratch.
            work.reserve(2048L + 128L * n + 96L * source.size());
            for (int i = 0; i < n; i++) {
                work.scan();
                if (lower[i] == null || upper[i] == null || lower[i].signum() < 0 ||
                        lower[i].compareTo(upper[i]) > 0 || upper[i].bitLength() > MAX_BITS)
                    return null;
            }
            long entries = 0;
            int objective = -1;
            for (int r = 0; r < source.size(); r++) {
                work.scan();
                var row = source.get(r);
                if (row.upper() == null || row.upper().bitLength() > MAX_BITS) return null;
                entries += row.terms().size();
                if (entries > MAX_TERMS) return null;
                boolean positive = true;
                for (var term : row.terms().entrySet()) {
                    work.scan();
                    if (term.getKey() < 0 || term.getKey() >= n || term.getValue() == null ||
                            term.getValue().bitLength() > MAX_BITS)
                        return null;
                    if (term.getValue().signum() <= 0) positive = false;
                }
                // Preserve the original first-row tie rule exactly.
                if (positive && row.terms().size() >= n / 2 &&
                        (objective < 0 || row.terms().size() > source.get(objective).terms().size()))
                    objective = r;
            }
            BigInteger[] costs = new BigInteger[n];
            Arrays.fill(costs, BigInteger.ZERO);
            if (objective >= 0) for (var term : source.get(objective).terms().entrySet()) {
                work.scan();
                costs[term.getKey()] = term.getValue();
            }
            int[] order = order(source, costs, work);
            int[] inverse = new int[n];
            BigInteger[] lo = new BigInteger[n], hi = new BigInteger[n];
            for (int i = 0; i < n; i++) {
                work.scan();
                inverse[order[i]] = i;
                lo[i] = lower[order[i]];
                hi[i] = upper[order[i]];
            }

            // Reserve both construction maps and immutable constraint copies.
            long retained = 256L + 64L * n + 128L * source.size() + 128L * entries;
            work.reserve(2 * retained);
            List<ExactLinearProgram.Constraint> mapped = new ArrayList<>(source.size());
            for (var row : source) {
                work.scan();
                Map<Integer, BigInteger> terms = new TreeMap<>();
                for (var term : row.terms().entrySet()) {
                    work.scan();
                    terms.put(inverse[term.getKey()], term.getValue());
                }
                mapped.add(new ExactLinearProgram.Constraint(terms, row.upper()));
            }
            mapped.sort((a, b) -> {
                for (int i = 0; i < n; i++) {
                    BigInteger x = a.terms().getOrDefault(i, BigInteger.ZERO);
                    BigInteger y = b.terms().getOrDefault(i, BigInteger.ZERO);
                    work.integer(x, y);
                    int compared = x.compareTo(y);
                    if (compared != 0) return compared;
                }
                work.integer(a.upper(), b.upper());
                return a.upper().compareTo(b.upper());
            });
            var result = new CountCanonicalModel(List.copyOf(mapped), lo, hi, order, budget, retained);
            work.transfer(retained);
            return result;
        } catch (Declined ignored) {
            return null;
        }
    }

    private static int[] order(List<ExactLinearProgram.Constraint> rows, BigInteger[] costs, Workspace work) {
        int n = costs.length;
        Integer[] sorted = new Integer[n];
        for (int i = 0; i < n; i++) sorted[i] = i;
        Comparator<Integer> costOrder = (a, b) -> {
            work.integer(costs[a], costs[b]);
            return costs[b].compareTo(costs[a]);
        };
        Arrays.sort(sorted, costOrder);
        boolean[] tied = new boolean[n];
        for (int i = 1; i < n; i++) {
            work.integer(costs[sorted[i - 1]], costs[sorted[i]]);
            if (costs[sorted[i - 1]].equals(costs[sorted[i]])) tied[sorted[i - 1]] = tied[sorted[i]] = true;
        }
        long temporary = 128L * n;
        for (var row : rows) {
            work.scan();
            int tiedTerms = 0;
            long shape = characters(row.upper()) + 3;
            for (var term : row.terms().entrySet()) {
                work.scan();
                shape += characters(term.getValue()) + 2;
                if (tied[term.getKey()]) tiedTerms++;
            }
            if (tiedTerms == 0) continue;
            // UTF-16 strings, joined keys, append/sort arrays, row-shape scratch.
            temporary += 256 + 8 * shape + 32L * row.terms().size();
            for (var term : row.terms().entrySet()) if (tied[term.getKey()])
                temporary += 128 + 8 * (shape + characters(term.getValue()) + 1);
        }
        work.reserve(temporary);
        try {
            List<List<String>> signatures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) signatures.add(tied[i] ? new ArrayList<>() : null);
            for (var row : rows) {
                work.scan();
                boolean needed = false;
                for (int id : row.terms().keySet()) {
                    work.scan();
                    if (tied[id]) needed = true;
                }
                if (!needed) continue;
                List<BigInteger> values = new ArrayList<>(row.terms().values());
                values.sort((a, b) -> {
                    work.integer(a, b);
                    return a.compareTo(b);
                });
                long length = characters(row.upper()) + 3;
                for (var value : values) length += characters(value) + 2;
                work.text(length);
                String shape = row.upper() + ":" + values;
                for (var term : row.terms().entrySet()) if (tied[term.getKey()]) {
                    work.text(shape.length() + characters(term.getValue()) + 1);
                    signatures.get(term.getKey()).add(term.getValue() + ":" + shape);
                }
            }
            String[] keys = new String[n];
            for (int i = 0; i < n; i++) if (tied[i]) {
                var signature = signatures.get(i);
                signature.sort((a, b) -> compareText(a, b, work));
                long length = 2;
                for (String part : signature) length += part.length() + 2;
                work.text(length);
                keys[i] = signature.toString();
            }
            Arrays.sort(sorted, (a, b) -> {
                int result = costOrder.compare(a, b);
                return result != 0 ? result : compareText(keys[a], keys[b], work);
            });
            int[] result = new int[n];
            for (int i = 0; i < n; i++) result[i] = sorted[i];
            return result;
        } finally {
            work.release(temporary);
        }
    }

    private static int compareText(String a, String b, Workspace work) {
        if (a == b) {
            work.scan();
            return 0;
        }
        work.text(Math.min(a.length(), b.length()));
        return a.compareTo(b);
    }

    private static long characters(BigInteger value) {
        return (value.bitLength() * 30103L + 99999) / 100000 + 2;
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

    int[] order() {
        return order.clone();
    }

    BigInteger[] restore(BigInteger[] values) {
        if (values == null) return null;
        if (values.length != order.length) throw new IllegalArgumentException("Coordinate length mismatch");
        budget.checkpoint();
        // One restoration array fits in the retained per-variable workspace.
        BigInteger[] result = new BigInteger[values.length];
        for (int i = 0; i < values.length; i++) {
            budget.operation(PlanningBudget.Operation.SCAN, 0);
            result[order[i]] = values[i];
        }
        return result;
    }

    @Override
    public void close() {
        rows = List.of();
        lower = upper = null;
        order = null;
        budget.release(memory);
        memory = 0;
    }

    private static final class Declined extends RuntimeException {

        Declined() {
            super(null, null, false, false);
        }
    }

    private static final class Workspace implements AutoCloseable {

        final PlanningBudget budget;
        final long limit;
        long ticks, memory;

        Workspace(PlanningBudget budget, long allowance) {
            this.budget = budget;
            limit = allowance * PlanningBudget.WORK_SCALE;
        }

        void scan() {
            charge(PlanningBudget.Operation.SCAN, 0);
        }

        void integer(BigInteger a, BigInteger b) {
            charge(PlanningBudget.Operation.INTEGER, Math.max(a.bitLength(), b.bitLength()));
        }

        void charge(PlanningBudget.Operation operation, int bits) {
            int words = (int) Math.max(1, Math.min(32, (Math.max(0, bits) + 63L) / 64));
            int cost = operation.ticks * (operation == PlanningBudget.Operation.SCAN ? 1 : words);
            if (cost > limit - ticks) throw new Declined();
            ticks += budget.operation(operation, bits);
        }

        void text(long characters) {
            long units = 1 + characters / 16;
            if (units > (limit - ticks) / PlanningBudget.WORK_SCALE) throw new Declined();
            budget.charge(units);
            ticks += units * PlanningBudget.WORK_SCALE;
        }

        void reserve(long bytes) {
            if (!budget.tryReserve(bytes)) throw new Declined();
            memory += bytes;
        }

        void release(long bytes) {
            budget.release(bytes);
            memory -= bytes;
        }

        void transfer(long bytes) {
            release(memory - bytes);
            memory = 0;
        }

        public void close() {
            budget.release(memory);
            memory = 0;
        }
    }
}
