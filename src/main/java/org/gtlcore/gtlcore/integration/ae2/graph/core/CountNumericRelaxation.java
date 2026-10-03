package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Bounded numerical relaxation used only to suggest integer witnesses and row
 * combinations. A floating point status, objective or multiplier is never a
 * feasibility proof: callers must validate every conclusion with exact integers.
 */
final class CountNumericRelaxation {

    record Result(double[] point, double[] dual, boolean phaseOneInfeasible, long work, int pivots) {}

    /** One order owns one basis; a change of matrix, objective or budget invalidates it. */
    static final class Session implements AutoCloseable {

        private PlanningBudget budget;
        private CountNumericRelaxation solver;
        private List<ExactLinearProgram.Constraint> matrix;
        private BigInteger[] objective;
        private long memory, reused, rebuilt, invalidated, fallbacks;

        Result solve(int variables, List<ExactLinearProgram.Constraint> rows, BigInteger[] costs,
                     PlanningBudget budget, long maximumWork) {
            if (this.budget != budget) {
                clear();
                this.budget = budget;
            }
            long started = budget.threadWork();
            try {
                budget.checkpoint();
                if (!admissible(variables, rows, costs, maximumWork)) {
                    clear();
                    return null;
                }
                if (solver != null) {
                    if (matches(variables, rows, costs, started, Math.max(1, maximumWork / 8))) {
                        solver.resetWork(Math.max(1, (maximumWork - (budget.threadWork() - started)) / 2));
                        Result result = null;
                        try {
                            result = solver.warm(rows);
                        } catch (Stop ignored) {
                            // A partially updated tableau must never be retained.
                        } finally {
                            solver.flush();
                        }
                        if (result != null) {
                            reused++;
                            return accounted(result, started);
                        }
                        fallbacks++;
                    } else invalidated++;
                    clear();
                }
                long remaining = maximumWork - (budget.threadWork() - started);
                if (remaining <= 0) return null;
                long terms = 0;
                for (var row : rows) terms += row.terms().size();
                // The snapshot can outlive the caller's projected rows. Include
                // their maps and finite-double (at most 1024-bit) integer data,
                // plus the separately retained objective, before copying them.
                long bytes = workspaceBytes(variables, rows.size()) + 256L * (rows.size() + variables + terms);
                if (!budget.tryReserve(bytes)) {
                    // Retaining a matrix is optional; its smaller cold workspace
                    // can still fit under the same request's memory limit.
                    var result = CountNumericRelaxation.solve(variables, rows, costs, budget, remaining);
                    return result == null ? null : accounted(result, started);
                }
                memory = bytes;
                solver = new CountNumericRelaxation(variables, rows.size(), budget, remaining);
                Result result;
                try {
                    solver.initialize(rows, costs);
                    result = solver.solve();
                } catch (Stop ignored) {
                    result = null;
                } finally {
                    solver.flush();
                }
                rebuilt++;
                if (result != null && !result.phaseOneInfeasible()) {
                    matrix = List.copyOf(rows);
                    objective = costs.clone();
                } else clear();
                return result == null ? null : accounted(result, started);
            } catch (RuntimeException | Error failure) {
                clear();
                throw failure;
            }
        }

        private boolean matches(int variables, List<ExactLinearProgram.Constraint> rows, BigInteger[] costs,
                                long started, long allowance) {
            if (solver.variables != variables || matrix.size() != rows.size() || !Arrays.equals(objective, costs)) return false;
            for (int r = 0; r < rows.size(); r++) {
                if (budget.threadWork() - started >= allowance) return false;
                budget.operation(PlanningBudget.Operation.SCAN, 0);
                var old = matrix.get(r).terms();
                var next = rows.get(r).terms();
                if (old == next) continue;
                if (old.size() != next.size()) return false;
                for (var term : old.entrySet()) {
                    if (budget.threadWork() - started >= allowance) return false;
                    budget.operation(PlanningBudget.Operation.SCAN, 0);
                    if (!term.getValue().equals(next.get(term.getKey()))) return false;
                }
            }
            return true;
        }

        private Result accounted(Result result, long started) {
            return new Result(result.point(), result.dual(), result.phaseOneInfeasible(),
                    budget.threadWork() - started, result.pivots());
        }

        long reused() {
            return reused;
        }

        long rebuilt() {
            return rebuilt;
        }

        long invalidated() {
            return invalidated;
        }

        long fallbacks() {
            return fallbacks;
        }

        private void clear() {
            solver = null;
            matrix = null;
            objective = null;
            if (budget != null) budget.release(memory);
            memory = 0;
        }

        @Override
        public void close() {
            clear();
        }
    }

    private static final double EPSILON = 1e-8;
    private static final int OPERATIONS_PER_UNIT = 16;

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private final int variables, rowCount;
    private final double[][] tableau;
    private final int[] basic, nonbasic, pivotColumns;
    private final double[] rowScales, rightHandSides, transformedBounds;
    private final PlanningBudget budget;
    private long maximumWork;
    private double objectiveScale;
    private long operations, charged;
    private int pivots;

    static Result solve(int variables, List<ExactLinearProgram.Constraint> rows, BigInteger[] objective,
                        PlanningBudget budget, long maximumWork) {
        if (!admissible(variables, rows, objective, maximumWork)) return null;
        long bytes = workspaceBytes(variables, rows.size());
        if (!budget.tryReserve(bytes)) return null;
        CountNumericRelaxation solver = null;
        try {
            solver = new CountNumericRelaxation(variables, rows.size(), budget, maximumWork);
            // Initialize only after assigning solver so a rejected conversion
            // still charges its last partial batch and releases its workspace.
            solver.initialize(rows, objective);
            return solver.solve();
        } catch (Stop stopped) {
            return null;
        } finally {
            try {
                if (solver != null) solver.flush();
            } finally {
                budget.release(bytes);
            }
        }
    }

    private static boolean admissible(int variables, List<ExactLinearProgram.Constraint> rows,
                                      BigInteger[] objective, long maximumWork) {
        return variables >= 1 && variables <= 512 && objective.length == variables && rows.size() <= 2048 &&
                (rows.size() + 2L) * (variables + 2L) <= 1_048_576 && maximumWork >= 1;
    }

    private static long workspaceBytes(int variables, int rows) {
        return 4096L + (rows + 2L) * (variables + 2L) * Double.BYTES + 96L * (variables + rows);
    }

    private CountNumericRelaxation(int variables, int rowCount, PlanningBudget budget, long maximumWork) {
        this.variables = variables;
        this.rowCount = rowCount;
        this.budget = budget;
        this.maximumWork = maximumWork;
        tableau = new double[rowCount + 2][variables + 2];
        basic = new int[rowCount];
        nonbasic = new int[variables + 1];
        pivotColumns = new int[variables + 2];
        rowScales = new double[rowCount];
        rightHandSides = new double[rowCount];
        transformedBounds = new double[rowCount];
    }

    private void resetWork(long maximumWork) {
        this.maximumWork = maximumWork;
        operations = charged = 0;
        pivots = 0;
    }

    /** Recompute B^-1*b from the implicit basic/nonbasic slack columns, then dual-simplex. */
    private Result warm(List<ExactLinearProgram.Constraint> rows) {
        for (int column = 0; column <= variables; column++) {
            operation();
            if (nonbasic[column] != -1 && tableau[rowCount][column] < -EPSILON) throw new Stop();
        }
        for (int row = 0; row < rowCount; row++) {
            operation();
            if (basic[row] < 0) throw new Stop();
            rightHandSides[row] = finite(rows.get(row).upper().doubleValue() / rowScales[row]);
        }
        double objectiveValue = 0;
        for (int row = 0; row < rowCount; row++) {
            operation();
            transformedBounds[row] = basic[row] >= variables ? rightHandSides[basic[row] - variables] : 0;
        }
        for (int column = 0; column <= variables; column++) if (nonbasic[column] >= variables) {
            double value = rightHandSides[nonbasic[column] - variables];
            operation();
            objectiveValue = finite(objectiveValue + tableau[rowCount][column] * value);
            for (int row = 0; row < rowCount; row++) {
                operation();
                transformedBounds[row] = finite(transformedBounds[row] + tableau[row][column] * value);
            }
        }
        for (int row = 0; row < rowCount; row++) {
            operation();
            tableau[row][variables + 1] = transformedBounds[row];
        }
        tableau[rowCount][variables + 1] = objectiveValue;
        // Phase one has no role in an existing basis. Its old objective is not
        // a valid objective after changing bounds and must not drift on pivots.
        Arrays.fill(tableau[rowCount + 1], 0);
        for (int iteration = 0; iteration < 8192; iteration++) {
            int leaving = -1;
            for (int row = 0; row < rowCount; row++) {
                operation();
                if (tableau[row][variables + 1] < -EPSILON &&
                        (leaving < 0 || basic[row] < basic[leaving]))
                    leaving = row;
            }
            if (leaving < 0) {
                var result = optimum();
                if (!validPoint(rows, result.point())) throw new Stop();
                flush();
                return new Result(result.point(), result.dual(), false, charged, pivots);
            }
            int entering = -1;
            double ratio = 0;
            for (int column = 0; column <= variables; column++) {
                operation();
                if (nonbasic[column] == -1 || tableau[leaving][column] >= -EPSILON) continue;
                double next = finite(Math.max(0, tableau[rowCount][column]) / -tableau[leaving][column]);
                if (entering < 0 || next < ratio - EPSILON ||
                        Math.abs(next - ratio) <= EPSILON && nonbasic[column] < nonbasic[entering]) {
                    entering = column;
                    ratio = next;
                }
            }
            if (entering < 0) {
                // The leaving basis row is a numerical Farkas suggestion.
                // Exact callers still reconstruct a global integer consequence.
                var ray = new double[rowCount];
                if (basic[leaving] >= variables) ray[basic[leaving] - variables] = 1;
                for (int column = 0; column <= variables; column++) {
                    operation();
                    if (nonbasic[column] >= variables) ray[nonbasic[column] - variables] = Math.max(0, tableau[leaving][column]);
                }
                for (int row = 0; row < rowCount; row++) {
                    operation();
                    ray[row] = finite(ray[row] / rowScales[row]);
                }
                flush();
                return new Result(null, ray, true, charged, pivots);
            }
            pivot(leaving, entering);
        }
        throw new Stop();
    }

    private boolean validPoint(List<ExactLinearProgram.Constraint> rows, double[] point) {
        for (double value : point) {
            operation();
            if (!Double.isFinite(value) || value < -EPSILON) return false;
        }
        for (int row = 0; row < rowCount; row++) {
            double sum = 0, magnitude = 1 + Math.abs(rightHandSides[row]);
            for (var term : rows.get(row).terms().entrySet()) {
                operation();
                double contribution = finite(term.getValue().doubleValue() / rowScales[row] * point[term.getKey()]);
                sum = finite(sum + contribution);
                magnitude = finite(magnitude + Math.abs(contribution));
            }
            if (sum > rightHandSides[row] + EPSILON * magnitude) return false;
        }
        return true;
    }

    private void initialize(List<ExactLinearProgram.Constraint> rows, BigInteger[] objective) {
        for (int row = 0; row < rowCount; row++) {
            double scale = 1;
            for (var coefficient : rows.get(row).terms().values()) {
                operation();
                scale = Math.max(scale, Math.abs(finite(coefficient.doubleValue())));
            }
            rowScales[row] = scale;
            for (var term : rows.get(row).terms().entrySet()) {
                operation();
                if (term.getKey() < 0 || term.getKey() >= variables) throw new Stop();
                tableau[row][term.getKey()] = finite(term.getValue().doubleValue() / scale);
            }
            basic[row] = variables + row;
            tableau[row][variables] = -1;
            tableau[row][variables + 1] = finite(rows.get(row).upper().doubleValue() / scale);
        }
        double scale = 1;
        for (var coefficient : objective) {
            operation();
            scale = Math.max(scale, Math.abs(finite(coefficient.doubleValue())));
        }
        objectiveScale = scale;
        for (int column = 0; column < variables; column++) {
            operation();
            nonbasic[column] = column;
            tableau[rowCount][column] = -finite(objective[column].doubleValue() / scale);
        }
        nonbasic[variables] = -1;
        tableau[rowCount + 1][variables] = 1;
    }

    private Result solve() {
        int worst = -1;
        for (int row = 0; row < rowCount; row++) {
            operation();
            if (worst < 0 || tableau[row][variables + 1] < tableau[worst][variables + 1]) worst = row;
        }
        if (worst >= 0 && tableau[worst][variables + 1] < -EPSILON) {
            pivot(worst, variables);
            if (!simplex(rowCount + 1)) return null;
            if (tableau[rowCount + 1][variables + 1] < -EPSILON) {
                var dual = dual(rowCount + 1);
                flush();
                return new Result(null, dual, true, charged, pivots);
            }
            if (Math.abs(tableau[rowCount + 1][variables + 1]) > EPSILON) return null;
            for (int row = 0; row < rowCount; row++) if (basic[row] == -1) {
                int chosen = -1;
                for (int column = 0; column <= variables; column++) {
                    operation();
                    if (nonbasic[column] != -1 && Math.abs(tableau[row][column]) > EPSILON &&
                            (chosen < 0 || nonbasic[column] < nonbasic[chosen]))
                        chosen = column;
                }
                if (chosen < 0) return null;
                pivot(row, chosen);
            }
        }
        if (!simplex(rowCount)) return null;
        return optimum();
    }

    private Result optimum() {
        var point = new double[variables];
        for (int row = 0; row < rowCount; row++) {
            operation();
            if (basic[row] >= 0 && basic[row] < variables) point[basic[row]] = tableau[row][variables + 1];
        }
        var dual = dual(rowCount);
        flush();
        return new Result(point, dual, false, charged, pivots);
    }

    private double[] dual(int objectiveRow) {
        var dual = new double[rowCount];
        for (int column = 0; column <= variables; column++) {
            operation();
            if (nonbasic[column] >= variables) {
                int row = nonbasic[column] - variables;
                // Undo both normalizations before suggesting a combination of
                // the caller's rows. Phase one uses its own unit objective.
                dual[row] = finite(tableau[objectiveRow][column] *
                        (objectiveRow == rowCount ? objectiveScale : 1) / rowScales[row]);
            }
        }
        return dual;
    }

    private boolean simplex(int objectiveRow) {
        for (int iteration = 0; iteration < 8192; iteration++) {
            int entering = -1;
            for (int column = 0; column <= variables; column++) {
                operation();
                if (nonbasic[column] != -1 && tableau[objectiveRow][column] < -EPSILON &&
                        (entering < 0 || tableau[objectiveRow][column] < tableau[objectiveRow][entering] - EPSILON ||
                                Math.abs(tableau[objectiveRow][column] - tableau[objectiveRow][entering]) <= EPSILON &&
                                        nonbasic[column] < nonbasic[entering]))
                    entering = column;
            }
            if (entering < 0) return true;
            int leaving = -1;
            double ratio = 0;
            for (int row = 0; row < rowCount; row++) {
                operation();
                if (tableau[row][entering] <= EPSILON) continue;
                double next = finite(tableau[row][variables + 1] / tableau[row][entering]);
                if (leaving < 0 || next < ratio - EPSILON ||
                        Math.abs(next - ratio) <= EPSILON && basic[row] < basic[leaving]) {
                    leaving = row;
                    ratio = next;
                }
            }
            if (leaving < 0) return false;
            pivot(leaving, entering);
        }
        return false;
    }

    private void pivot(int row, int column) {
        double pivot = finite(tableau[row][column]);
        if (Math.abs(pivot) < 1e-15) throw new Stop();
        pivots++;
        int columns = 0;
        // Scan the pivot row once. Sparse pivots do not need a full matrix
        // scan merely to rediscover the same zero columns in every row.
        for (int target = 0; target < variables + 2; target++) {
            operation();
            if (target != column && tableau[row][target] != 0) pivotColumns[columns++] = target;
        }
        for (int other = 0; other < rowCount + 2; other++) {
            operation();
            if (other == row || tableau[other][column] == 0) continue;
            double factor = finite(tableau[other][column] / pivot);
            for (int index = 0; index < columns; index++) {
                operation();
                int target = pivotColumns[index];
                tableau[other][target] = finite(tableau[other][target] - tableau[row][target] * factor);
            }
        }
        for (int target = 0; target < variables + 2; target++) if (target != column) {
            operation();
            tableau[row][target] = finite(tableau[row][target] / pivot);
        }
        for (int other = 0; other < rowCount + 2; other++) if (other != row) {
            operation();
            tableau[other][column] = finite(tableau[other][column] / -pivot);
        }
        tableau[row][column] = 1 / pivot;
        int previous = basic[row];
        basic[row] = nonbasic[column];
        nonbasic[column] = previous;
    }

    private static double finite(double value) {
        if (!Double.isFinite(value)) throw new Stop();
        return value;
    }

    private void operation() {
        operations++;
        if ((operations & 255) == 0) {
            flush();
            if (charged >= maximumWork) throw new Stop();
        }
    }

    private void flush() {
        // Scalar numerical operations are cheaper than exact rational pivots.
        // The shared work budget still checks time/cancellation every batch.
        long next = (operations + OPERATIONS_PER_UNIT - 1) / OPERATIONS_PER_UNIT;
        if (next > charged) {
            long delta = next - charged;
            charged = next;
            budget.charge(delta);
        } else budget.checkpoint();
    }
}
