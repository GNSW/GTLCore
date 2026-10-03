package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** Numerical simplex proposes a basis only. No floating-point point or verdict escapes. */
final class CountLpProposal {

    private static final double EPS = 1e-9;

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private final PlanningBudget budget;
    private final long allowance;
    private long work;
    private final int n, m;
    private final double[][] table;
    private final int[] basic, nonbasic;

    static int[] propose(int n, List<ExactLinearProgram.Constraint> rows, BigInteger[] objective, PlanningBudget budget) {
        return propose(n, rows, objective, budget, Math.min(262144, budget.remainingWork() / 16));
    }

    static int[] propose(int n, List<ExactLinearProgram.Constraint> rows, BigInteger[] objective, PlanningBudget budget, long allowance) {
        if (n < 2 || n > 128 || rows.size() > 256 || budget.remainingWork() < 32768) return null;
        for (var row : rows) {
            budget.check();
            if (row.upper().bitLength() > 48 || row.terms().values().stream().anyMatch(v -> v.bitLength() > 48)) return null;
        }
        if (Arrays.stream(objective).anyMatch(v -> v.bitLength() > 48)) return null;
        long bytes = 2048L + (rows.size() + 2L) * (n + 2L) * 16L;
        if (!budget.tryReserve(bytes)) return null;
        try {
            var solver = new CountLpProposal(n, rows, objective, budget, allowance);
            int[] result = solver.solve();
            budget.note("count_numeric_proposal", "basis=" + (result != null) + "; work=" + solver.work + "; exact_reconstruction_required");
            return result;
        } catch (Stop stopped) {
            return null;
        } finally {
            budget.release(bytes);
        }
    }

    private CountLpProposal(int n, List<ExactLinearProgram.Constraint> rows, BigInteger[] objective, PlanningBudget budget, long allowance) {
        this.n = n;
        m = rows.size();
        this.budget = budget;
        this.allowance = Math.max(0, Math.min(allowance, budget.remainingWork() / 4));
        table = new double[m + 2][n + 2];
        basic = new int[m];
        nonbasic = new int[n + 1];
        for (int r = 0; r < m; r++) {
            for (var term : rows.get(r).terms().entrySet()) {
                charge();
                table[r][term.getKey()] = term.getValue().doubleValue();
            }
            basic[r] = n + r;
            table[r][n] = -1;
            table[r][n + 1] = rows.get(r).upper().doubleValue();
        }
        for (int j = 0; j < n; j++) {
            nonbasic[j] = j;
            table[m][j] = -objective[j].doubleValue();
        }
        nonbasic[n] = -1;
        table[m + 1][n] = 1;
    }

    private int[] solve() {
        int worst = -1;
        for (int r = 0; r < m; r++) if (worst < 0 || table[r][n + 1] < table[worst][n + 1]) worst = r;
        if (worst >= 0 && table[worst][n + 1] < -EPS) {
            pivot(worst, n);
            if (!simplex(m + 1) || Math.abs(table[m + 1][n + 1]) > EPS) return null;
            for (int r = 0; r < m; r++) if (basic[r] == -1) {
                int chosen = -1;
                for (int j = 0; j <= n; j++) if (nonbasic[j] != -1 && Math.abs(table[r][j]) > EPS && (chosen < 0 || nonbasic[j] < nonbasic[chosen])) chosen = j;
                if (chosen < 0) return null;
                pivot(r, chosen);
            }
        }
        if (!simplex(m)) return null;
        return basic.clone();
    }

    private boolean simplex(int objective) {
        for (int iteration = 0; iteration < 512; iteration++) {
            charge();
            int entering = -1;
            for (int j = 0; j <= n; j++) if (nonbasic[j] != -1 && table[objective][j] < -EPS &&
                    (entering < 0 || table[objective][j] < table[objective][entering] - EPS ||
                            Math.abs(table[objective][j] - table[objective][entering]) <= EPS && nonbasic[j] < nonbasic[entering]))
                entering = j;
            if (entering < 0) return true;
            int leaving = -1;
            double ratio = 0;
            for (int r = 0; r < m; r++) {
                charge();
                if (table[r][entering] <= EPS) continue;
                double next = table[r][n + 1] / table[r][entering];
                if (leaving < 0 || next < ratio - EPS || Math.abs(next - ratio) <= EPS && basic[r] < basic[leaving]) {
                    leaving = r;
                    ratio = next;
                }
            }
            if (leaving < 0) return false;
            pivot(leaving, entering);
        }
        return false;
    }

    private void pivot(int row, int column) {
        double pivot = table[row][column];
        if (!Double.isFinite(pivot) || Math.abs(pivot) <= 1e-15) throw new Stop();
        for (int i = 0; i < m + 2; i++) if (i != row && table[i][column] != 0) for (int j = 0; j < n + 2; j++) if (j != column && table[row][j] != 0) {
            charge();
            table[i][j] -= table[row][j] * (table[i][column] / pivot);
            if (!Double.isFinite(table[i][j])) throw new Stop();
        }
        for (int j = 0; j < n + 2; j++) if (j != column) table[row][j] /= pivot;
        for (int i = 0; i < m + 2; i++) if (i != row) table[i][column] /= -pivot;
        table[row][column] = 1 / pivot;
        int previous = basic[row];
        basic[row] = nonbasic[column];
        nonbasic[column] = previous;
    }

    private void charge() {
        budget.check();
        if (++work > allowance) throw new Stop();
    }
}
