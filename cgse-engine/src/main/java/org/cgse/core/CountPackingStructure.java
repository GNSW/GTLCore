// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Immutable analysis of one declared compiler view. Prices only propose exact row combinations.
 * Fixed coordinates are substituted exactly; every omitted row is implied by that same box. */
final class CountPackingStructure implements AutoCloseable {
    final List<ExactLinearProgram.Constraint> rows;
    final BigInteger[] lower, upper;
    final int[] columns, ranks;
    final int[] capacityRows;
    final int demandRow;
    final long[][] matrix;
    final long[] rhs, profit;
    final long target;
    private final PlanningBudget budget;
    private long memory;

    static CountPackingStructure create(CountModelViews.View view, CountModelViews.Domains domains,
                                   PlanningBudget budget) {
        var low = domains.lower(); var high = domains.upper();
        int n = low.length;
        if (n < 64 || n > 512 || view.rows().size() > 2048 || view.shape().terms() > 32768 ||
                budget.remainingWork() < 524288) return null;
        long bytes = 8192L + 128L * n + 128L * view.rows().size() + 96L * view.shape().terms();
        if (!budget.tryReserve(bytes)) return null;
        try {
            var ids = new ArrayList<Integer>();
            for (int i = 0; i < n; i++) {
                budget.check();
                if (low[i] == null || high[i] == null || low[i].signum() < 0 ||
                        high[i].compareTo(BigInteger.ONE) > 0 || low[i].compareTo(high[i]) > 0) return null;
                if (!low[i].equals(high[i])) ids.add(i);
            }
            if (ids.size() < 2) return null;
            int[] map = new int[n]; Arrays.fill(map, -1);
            for (int i = 0; i < ids.size(); i++) map[ids.get(i)] = i;
            var packed = new ArrayList<long[]>(); var bounds = new ArrayList<Long>();
            var sources = new ArrayList<Integer>(); int demandRow = -1;
            long[] profit = null; long target = 0;
            for (int rowId = 0; rowId < view.rows().size(); rowId++) {
                var row = view.rows().get(rowId);
                var bound = row.upper(); var min = BigInteger.ZERO; var max = BigInteger.ZERO;
                var vector = new long[ids.size()]; boolean positive = false, negative = false;
                for (var term : row.terms().entrySet()) {
                    budget.check(); int id = term.getKey(); var a = term.getValue();
                    if (a.abs().bitLength() > 45) return null;
                    if (map[id] < 0) bound = bound.subtract(a.multiply(low[id]));
                    else {
                        vector[map[id]] = a.longValueExact();
                        if (a.signum() > 0) { max = max.add(a); positive = true; }
                        else if (a.signum() < 0) { min = min.add(a); negative = true; }
                    }
                }
                if (bound.compareTo(max) >= 0) continue;
                if (bound.abs().bitLength() > 50 || max.subtract(min).bitLength() > 50) return null;
                if (positive && !negative && bound.signum() >= 0) {
                    // Reject before retaining another dense row; sparse input
                    // must not allocate a rows-by-variables matrix unchecked.
                    if (packed.size() == 32) return null;
                    packed.add(vector); bounds.add(bound.longValueExact());
                    sources.add(rowId);
                } else if (negative && !positive && bound.signum() < 0 && profit == null) {
                    profit = vector; for (int i = 0; i < profit.length; i++) profit[i] = -profit[i];
                    target = bound.negate().longValueExact();
                    demandRow = rowId;
                } else return null;
            }
            if (profit == null || packed.isEmpty() || packed.size() > 32) return null;
            var result = new CountPackingStructure(view.rows(), low, high,
                    ids.stream().mapToInt(Integer::intValue).toArray(), packed.toArray(long[][]::new),
                    bounds.stream().mapToLong(Long::longValue).toArray(), profit, target,
                    sources.stream().mapToInt(Integer::intValue).toArray(), demandRow, budget, bytes);
            bytes = 0;
            return result;
        } finally { budget.release(bytes); }
    }

    private CountPackingStructure(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                                  int[] columns, long[][] matrix, long[] rhs, long[] profit, long target,
                                  int[] capacityRows, int demandRow,
                                  PlanningBudget budget, long bytes) {
        this.rows = List.copyOf(rows); this.lower = lower.clone(); this.upper = upper.clone();
        this.columns = columns; this.matrix = matrix; this.rhs = rhs; this.profit = profit; this.target = target;
        this.capacityRows = capacityRows; this.demandRow = demandRow;
        this.budget = budget; memory = bytes;
        ranks = rankColumns();
    }

    /** Prices choose nonnegative integer multipliers, never the validity of the
     * result. Reconstruct from unshifted original rows so fixed coordinates and
     * declared domains cannot become hidden premises of a shared explanation. */
    CountLpLearning.Cut explain(double[] prices) {
        if (prices.length != capacityRows.length) throw new IllegalArgumentException("Capacity price count");
        var parents = new TreeMap<Integer, BigInteger>();
        parents.put(demandRow, BigInteger.valueOf(65536));
        for (int r = 0; r < prices.length; r++) {
            double price = prices[r];
            if (!(price > 0) || !Double.isFinite(price) || price * 65536 > 1e12) continue;
            long weight = Math.round(price * 65536);
            if (weight != 0) parents.put(capacityRows[r], BigInteger.valueOf(weight));
        }
        var terms = new TreeMap<Integer, BigInteger>(); var bound = BigInteger.ZERO;
        for (var parent : parents.entrySet()) {
            var row = rows.get(parent.getKey()); var weight = parent.getValue();
            budget.check(); bound = bound.add(row.upper().multiply(weight));
            for (var term : row.terms().entrySet()) {
                budget.operation(PlanningBudget.Operation.INTEGER, Math.max(term.getValue().bitLength(), weight.bitLength()));
                terms.merge(term.getKey(), term.getValue().multiply(weight), BigInteger::add);
            }
        }
        terms.values().removeIf(value -> value.signum() == 0);
        var gcd = BigInteger.ZERO;
        for (var a : terms.values()) { budget.check(); gcd = gcd.gcd(a); }
        var divisor = gcd.signum() == 0 ? BigInteger.ONE : gcd;
        terms.replaceAll((id, a) -> { budget.check(); return a.divide(divisor); });
        var qr = bound.divideAndRemainder(divisor);
        var upper = qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
        return new CountLpLearning.Cut(new ExactLinearProgram.Constraint(terms, upper), parents, divisor);
    }

    private int[] rankColumns() {
        int n = columns.length, m = matrix.length;
        var signatures = new long[n][1 + 2 * m];
        for (int i = 0; i < n; i++) {
            final int column = i;
            var order = new Integer[m]; for (int r = 0; r < m; r++) order[r] = r;
            Arrays.sort(order, (a, b) -> {
                budget.operation(PlanningBudget.Operation.SCAN, 0);
                int compared = Long.compare(rhs[a], rhs[b]);
                return compared != 0 ? compared : Long.compare(matrix[a][column], matrix[b][column]);
            });
            signatures[i][0] = -profit[i];
            for (int r = 0; r < m; r++) {
                budget.operation(PlanningBudget.Operation.SCAN, 0);
                signatures[i][1 + 2 * r] = rhs[order[r]];
                signatures[i][2 + 2 * r] = matrix[order[r]][i];
            }
        }
        var order = new Integer[n]; for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            for (int j = 0; j < signatures[a].length; j++) {
                budget.operation(PlanningBudget.Operation.SCAN, 0);
                int compared = Long.compare(signatures[a][j], signatures[b][j]);
                if (compared != 0) return compared;
            }
            return 0;
        });
        var ranks = new int[n]; for (int i = 0; i < n; i++) ranks[order[i]] = i;
        return ranks;
    }

    @Override public void close() { budget.release(memory); memory = 0; }
}
