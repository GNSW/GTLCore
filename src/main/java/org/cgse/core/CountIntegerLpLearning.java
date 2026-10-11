// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Bounded integer LP proposals on complete sparse row supports, never a numerical proof. */
final class CountIntegerLpLearning implements AutoCloseable {

    private static final int MAX_COLUMNS = 128, MAX_ROWS = 512, MAX_TERMS = 4096;
    private final CountLpLearning.Session numerical = new CountLpLearning.Session(true);
    private PlanningBudget budget;
    private List<ExactLinearProgram.Constraint> sources = List.of(), projected = List.of();
    private int[] columns = new int[0], rowIds = new int[0];
    private int variables;
    private long memory, started, allowance, checkLimit, builds, hits;

    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }

    /** Cheap admission for the integer portfolio, not a claim that the LP is feasible. */
    static boolean hasBoundedIntegerSupport(List<ExactLinearProgram.Constraint> rows, BigInteger[] low,
                                            BigInteger[] high, PlanningBudget budget) {
        long started = budget.threadWork(), limit = Math.min(16384, budget.remainingWork() / 64);
        if (limit < 1024 || budget.remainingWork() < 65536 || low.length != high.length ||
                low.length < 2 || low.length > 8192 || rows.size() > 32768) return false;
        for (var row : rows) {
            if (budget.threadWork() - started >= limit) return false;
            budget.check();
            if (row.terms().size() < 2 || row.terms().size() > MAX_COLUMNS) continue;
            boolean finite = row.upper().bitLength() <= 1024, integer = false, weighted = false;
            for (var term : row.terms().entrySet()) {
                if (budget.threadWork() - started >= limit) return false;
                budget.check();
                int id = term.getKey();
                if (id < 0 || id >= low.length || !bounded(low[id], high[id]) || term.getValue().bitLength() > 1024) {
                    finite = false;
                    break;
                }
                if (!low[id].equals(high[id])) {
                    integer |= high[id].compareTo(BigInteger.ONE) > 0 || low[id].signum() < 0;
                    weighted |= term.getValue().abs().compareTo(BigInteger.ONE) > 0;
                }
            }
            if (finite && integer && weighted) return true;
        }
        return false;
    }

    CountLpLearning.Result solve(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                                 int focus, PlanningBudget budget, long maximumWork) {
        if (this.budget != budget) {
            close();
            this.budget = budget;
        }
        started = budget.threadWork();
        allowance = Math.min(maximumWork, budget.remainingWork());
        checkLimit = allowance / 3;
        if (allowance < 2048 || low.length != high.length || low.length > 8192 || rows.size() > 32768) return null;
        try {
            if (!matches(rows, low, high, focus)) {
                clearKernel();
                if (!prepare(rows, low, high, focus)) return null;
            } else hits++;
            checkLimit = allowance;
            var lower = new BigInteger[columns.length];
            var upper = new BigInteger[columns.length];
            for (int i = 0; i < columns.length; i++) {
                check();
                lower[i] = low[columns[i]];
                upper[i] = high[columns[i]];
            }
            try (var result = numerical.solve(projected, lower, upper, budget, allowance - spent() - 1024)) {
                if (result == null) return null;
                long bytes = 256L + 16L * low.length + 192L * columns.length + (result.cut == null ? 0 :
                        512L * result.cut.row().terms().size() + 256L * result.cut.parents().size());
                if (!budget.tryReserve(bytes)) return null;
                try {
                    double[] point = null;
                    BigInteger[] integerPoint = result.integerPoint == null ? null : new BigInteger[low.length];
                    if (result.point != null) {
                        point = new double[low.length];
                        // Unrepresented columns have no LP suggestion. They are not zero.
                        Arrays.fill(point, Double.NaN);
                        for (int i = 0; i < columns.length; i++) {
                            check();
                            point[columns[i]] = result.point[i];
                            if (integerPoint != null) integerPoint[columns[i]] = result.integerPoint[i];
                        }
                    }
                    CountLpLearning.Cut cut = null;
                    if (result.cut != null) {
                        Map<Integer, BigInteger> terms = new TreeMap<>(), parents = new TreeMap<>();
                        for (var term : result.cut.row().terms().entrySet()) {
                            check();
                            terms.put(columns[term.getKey()], term.getValue());
                        }
                        for (var parent : result.cut.parents().entrySet()) {
                            check();
                            parents.put(rowIds[parent.getKey()], parent.getValue());
                        }
                        cut = new CountLpLearning.Cut(new ExactLinearProgram.Constraint(terms, result.cut.row().upper()),
                                Map.copyOf(parents), result.cut.divisor());
                    }
                    var lifted = new CountLpLearning.Result(point, cut, result.numericalInfeasible, result.numericalWork, budget, bytes, integerPoint);
                    bytes = 0;
                    return lifted;
                } finally { budget.release(bytes); }
            }
        } catch (Stop ignored) {
            return null;
        }
    }

    private boolean matches(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, int focus) {
        if (projected.isEmpty() || variables != low.length) return false;
        boolean focused = focus < 0 || focus >= rows.size();
        for (int i = 0; i < rowIds.length; i++) {
            check();
            if (rowIds[i] >= rows.size() || sources.get(i) != rows.get(rowIds[i])) return false;
            focused |= rowIds[i] == focus;
        }
        for (int id : columns) {
            check();
            if (!bounded(low[id], high[id])) return false;
        }
        return focused;
    }

    /** Omitting entire rows is a relaxation; omitting terms from an included row would not be. */
    private boolean prepare(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, int focus) {
        long scratch = 4096L + 32L * low.length + 8L * rows.size();
        if (scratch > budget.availableBytes() / 8 || !budget.tryReserve(scratch)) return false;
        try {
            int anchor = -1;
            if (focus >= 0 && focus < rows.size() && eligible(rows.get(focus), low, high)) anchor = focus;
            if (anchor < 0) for (int r = 0; r < rows.size(); r++) {
                check();
                var row = rows.get(r);
                if (row.terms().size() < 2 || row.terms().size() > MAX_COLUMNS || !eligible(row, low, high)) continue;
                boolean weighted = false;
                for (var term : row.terms().entrySet()) {
                    check();
                    weighted |= term.getValue().abs().compareTo(BigInteger.ONE) > 0 && !low[term.getKey()].equals(high[term.getKey()]);
                }
                if (weighted) { anchor = r; break; }
            }
            if (anchor < 0) return false;
            Set<Integer> selected = new LinkedHashSet<>(), selectedRows = new LinkedHashSet<>();
            selected.addAll(rows.get(anchor).terms().keySet());
            selectedRows.add(anchor);
            long terms = rows.get(anchor).terms().size();
            // Bounded closure around the triggering row. Each selected row retains
            // ALL of its boundary variables, even if they do not occur in the anchor.
            for (int pass = 0; pass < 4 && selectedRows.size() < MAX_ROWS; pass++) {
                int before = selectedRows.size();
                for (int r = 0; r < rows.size() && selectedRows.size() < MAX_ROWS; r++) {
                    check();
                    if (selectedRows.contains(r)) continue;
                    var row = rows.get(r);
                    if (row.terms().size() > MAX_COLUMNS || terms + row.terms().size() > MAX_TERMS) continue;
                    int added = 0;
                    boolean connected = low.length <= MAX_COLUMNS;
                    for (int id : row.terms().keySet()) {
                        check();
                        if (selected.contains(id)) connected = true;
                        else added++;
                    }
                    if (!connected || selected.size() + added > MAX_COLUMNS || !eligible(row, low, high)) continue;
                    selectedRows.add(r);
                    selected.addAll(row.terms().keySet());
                    terms += row.terms().size();
                }
                if (before == selectedRows.size()) break;
            }
            if (selected.size() < 2) return false;
            long bytes = 4096L + 512L * selected.size() + 512L * selectedRows.size() + 384L * terms;
            if (bytes > budget.availableBytes() / 8 || !budget.tryReserve(bytes)) return false;
            memory = bytes;
            try {
                variables = low.length;
                columns = selected.stream().mapToInt(Integer::intValue).toArray();
                rowIds = selectedRows.stream().mapToInt(Integer::intValue).toArray();
                Map<Integer, Integer> indices = new HashMap<>();
                for (int i = 0; i < columns.length; i++) { check(); indices.put(columns[i], i); }
                var input = new ArrayList<ExactLinearProgram.Constraint>();
                var reduced = new ArrayList<ExactLinearProgram.Constraint>();
                for (int r : rowIds) {
                    var row = rows.get(r);
                    Map<Integer, BigInteger> remapped = new TreeMap<>();
                    for (var term : row.terms().entrySet()) { check(); remapped.put(indices.get(term.getKey()), term.getValue()); }
                    input.add(row);
                    reduced.add(new ExactLinearProgram.Constraint(remapped, row.upper()));
                }
                sources = List.copyOf(input);
                projected = List.copyOf(reduced);
                builds++;
                budget.note("count_integer_lp_support", "variables=" + columns.length + "/" + low.length +
                        "; rows=" + rowIds.length + "/" + rows.size() + "; complete_row_support");
                return true;
            } catch (RuntimeException | Error failure) { clearKernel(); throw failure; }
        } finally { budget.release(scratch); }
    }

    private boolean eligible(ExactLinearProgram.Constraint row, BigInteger[] low, BigInteger[] high) {
        if (row.terms().size() > MAX_COLUMNS || row.upper().bitLength() > 1024) return false;
        for (var term : row.terms().entrySet()) {
            check();
            int id = term.getKey();
            if (id < 0 || id >= low.length || term.getValue().bitLength() > 1024 || !bounded(low[id], high[id])) return false;
        }
        return true;
    }

    private static boolean bounded(BigInteger low, BigInteger high) {
        return high != null && low.compareTo(high) <= 0 && low.bitLength() <= 1024 && high.bitLength() <= 1024;
    }

    private long spent() { return budget.threadWork() - started; }
    private void check() {
        if (spent() >= checkLimit) throw new Stop();
        budget.check();
    }

    private void clearKernel() {
        if (budget != null) budget.release(memory);
        memory = 0;
        sources = projected = List.of();
        columns = rowIds = new int[0];
    }

    @Override public void close() {
        numerical.close();
        clearKernel();
        if (budget != null && builds > 0) budget.note("count_integer_lp_support", "builds=" + builds + "; reused=" + hits);
        budget = null;
    }
}
