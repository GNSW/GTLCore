package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Bounded two-phase rational simplex for sparse Ax <= b, x >= 0. Bland's
 * ordering avoids cycling; each continuation updates at most 256 tableau cells.
 * Both primal points and negative Farkas certificates are independently checked.
 */
final class ExactLinearProgram implements AutoCloseable {

    enum Result {
        OPTIMAL,
        INFEASIBLE,
        UNBOUNDED,
        UNKNOWN
    }

    record Constraint(Map<Integer, BigInteger> terms, BigInteger upper) {

        Constraint {
            terms = Map.copyOf(terms);
        }
    }

    private final List<Constraint> constraints;
    private final BigInteger[] objective;
    private final PlanningBudget budget;
    private final int rows, variables;
    private final long allowance;
    private ExactRational[][] table;
    private int[] basic, nonbasic;
    private int phase, pivotRow = -1, pivotColumn, cell;
    private ExactRational divisor;
    private long memory, work;
    private Result result;
    private ExactRational[] point, certificate;

    ExactLinearProgram(int variables, List<Constraint> constraints, BigInteger[] objective, PlanningBudget budget) {
        this.variables = variables;
        this.constraints = List.copyOf(constraints);
        this.objective = objective.clone();
        this.budget = budget;
        allowance = Math.min(500_000, budget.remainingWork() / 4);
        rows = constraints.size();
        long cells = (rows + 2L) * (variables + 2L);
        long bytes = cells * 768L + 64L * (rows + variables + 2L);
        if (allowance < 1024 || variables > 384 || rows > 512 || cells > 65_536 || !budget.tryReserve(bytes)) {
            result = Result.UNKNOWN;
            return;
        }
        memory = bytes;
        try {
            table = new ExactRational[rows + 2][variables + 2];
            for (ExactRational[] row : table) Arrays.fill(row, ExactRational.ZERO);
            basic = new int[rows];
            nonbasic = new int[variables + 1];
            for (int i = 0; i < rows; i++) {
                basic[i] = variables + i;
                for (var term : constraints.get(i).terms().entrySet()) {
                    budget.check();
                    table[i][term.getKey()] = ExactRational.of(term.getValue());
                }
                table[i][variables] = ExactRational.ONE.negate();
                table[i][variables + 1] = ExactRational.of(constraints.get(i).upper());
            }
            for (int j = 0; j < variables; j++) {
                nonbasic[j] = j;
                table[rows][j] = ExactRational.of(objective[j]).negate();
            }
            nonbasic[variables] = -1;
            table[rows + 1][variables] = ExactRational.ONE;
        } catch (ExactRational.PrecisionLimit limit) {
            finish(Result.UNKNOWN);
        } catch (RuntimeException | Error failure) {
            // Cancellation/limits can interrupt construction before the caller
            // owns this workspace. Release it here, just as after a work slice.
            close();
            throw failure;
        }
    }

    boolean step() {
        budget.check();
        if (result != null) return true;
        if (work >= allowance) return finish(Result.UNKNOWN);
        try {
            if (pivotRow >= 0) {
                updatePivot();
                return false;
            }
            if (phase == 0) {
                int worst = -1;
                for (int i = 0; i < rows; i++) if (worst < 0 || table[i][variables + 1].compareTo(table[worst][variables + 1]) < 0) worst = i;
                phase = 1;
                if (worst >= 0 && table[worst][variables + 1].signum() < 0) pivot(worst, variables);
                else phase = 3;
                return false;
            }
            if (phase == 2) {
                for (int i = 0; i < rows; i++) if (basic[i] == -1) {
                    int entering = -1;
                    for (int j = 0; j <= variables; j++) if (nonbasic[j] != -1 && table[i][j].signum() != 0 &&
                            (entering < 0 || nonbasic[j] < nonbasic[entering]))
                        entering = j;
                    if (entering >= 0) {
                        pivot(i, entering);
                        return false;
                    }
                }
                phase = 3;
                return false;
            }
            int objectiveRow = phase == 1 ? rows + 1 : rows;
            int entering = -1;
            for (int j = 0; j <= variables; j++) {
                charge();
                if (nonbasic[j] != -1 && table[objectiveRow][j].signum() < 0 &&
                        (entering < 0 || nonbasic[j] < nonbasic[entering]))
                    entering = j;
            }
            if (entering < 0) {
                if (phase == 1) {
                    if (table[rows + 1][variables + 1].signum() < 0) {
                        certificate = dual(rows + 1);
                        return finish(validCertificate() ? Result.INFEASIBLE : Result.UNKNOWN);
                    }
                    phase = 2;
                    return false;
                }
                point = primal();
                return finish(validPoint() && validOptimum() ? Result.OPTIMAL : Result.UNKNOWN);
            }
            int leaving = -1;
            ExactRational ratio = null;
            for (int i = 0; i < rows; i++) {
                charge();
                if (table[i][entering].signum() <= 0) continue;
                ExactRational next = table[i][variables + 1].divide(table[i][entering]);
                if (leaving < 0 || next.compareTo(ratio) < 0 || next.compareTo(ratio) == 0 && basic[i] < basic[leaving]) {
                    leaving = i;
                    ratio = next;
                }
            }
            if (leaving < 0) return finish(phase == 1 ? Result.UNKNOWN : Result.UNBOUNDED);
            pivot(leaving, entering);
            return false;
        } catch (ExactRational.PrecisionLimit limit) {
            return finish(Result.UNKNOWN);
        }
    }

    private void pivot(int row, int column) {
        pivotRow = row;
        pivotColumn = column;
        divisor = table[row][column];
        cell = 0;
    }

    private void updatePivot() {
        int columns = variables + 2, cells = (rows + 2) * columns;
        for (int part = 0; part < 256 && cell < cells; part++, cell++) {
            int i = cell / columns, j = cell % columns;
            if (i == pivotRow || j == pivotColumn || table[i][pivotColumn].signum() == 0 || table[pivotRow][j].signum() == 0) continue;
            charge();
            table[i][j] = table[i][j].subtract(table[pivotRow][j].multiply(table[i][pivotColumn]).divide(divisor));
        }
        if (cell < cells) return;
        for (int j = 0; j < columns; j++) if (j != pivotColumn) {
            charge();
            table[pivotRow][j] = table[pivotRow][j].divide(divisor);
        }
        for (int i = 0; i < rows + 2; i++) if (i != pivotRow) {
            charge();
            table[i][pivotColumn] = table[i][pivotColumn].divide(divisor).negate();
        }
        table[pivotRow][pivotColumn] = ExactRational.ONE.divide(divisor);
        int previous = basic[pivotRow];
        basic[pivotRow] = nonbasic[pivotColumn];
        nonbasic[pivotColumn] = previous;
        pivotRow = -1;
    }

    private ExactRational[] primal() {
        ExactRational[] values = new ExactRational[variables];
        Arrays.fill(values, ExactRational.ZERO);
        for (int i = 0; i < rows; i++) if (basic[i] >= 0 && basic[i] < variables) values[basic[i]] = table[i][variables + 1];
        return values;
    }

    private ExactRational[] dual(int row) {
        ExactRational[] weights = new ExactRational[rows];
        Arrays.fill(weights, ExactRational.ZERO);
        for (int j = 0; j <= variables; j++) if (nonbasic[j] >= variables) weights[nonbasic[j] - variables] = table[row][j];
        return weights;
    }

    private boolean validPoint() {
        for (ExactRational value : point) if (value.signum() < 0) return false;
        for (Constraint constraint : constraints) {
            ExactRational value = ExactRational.ZERO;
            for (var term : constraint.terms().entrySet()) {
                charge();
                value = value.add(point[term.getKey()].multiply(ExactRational.of(term.getValue())));
            }
            if (value.compareTo(ExactRational.of(constraint.upper())) > 0) return false;
        }
        return true;
    }

    private boolean validCertificate() {
        ExactRational[] columns = new ExactRational[variables];
        Arrays.fill(columns, ExactRational.ZERO);
        ExactRational upper = ExactRational.ZERO;
        for (int i = 0; i < rows; i++) {
            if (certificate[i].signum() < 0) return false;
            upper = upper.add(certificate[i].multiply(ExactRational.of(constraints.get(i).upper())));
            for (var term : constraints.get(i).terms().entrySet()) {
                charge();
                columns[term.getKey()] = columns[term.getKey()].add(certificate[i].multiply(ExactRational.of(term.getValue())));
            }
        }
        return upper.signum() < 0 && Arrays.stream(columns).allMatch(value -> value.signum() >= 0);
    }

    private boolean validOptimum() {
        ExactRational[] weights = dual(rows), columns = new ExactRational[variables];
        Arrays.fill(columns, ExactRational.ZERO);
        ExactRational upper = ExactRational.ZERO, attained = ExactRational.ZERO;
        for (int i = 0; i < rows; i++) {
            if (weights[i].signum() < 0) return false;
            upper = upper.add(weights[i].multiply(ExactRational.of(constraints.get(i).upper())));
            for (var term : constraints.get(i).terms().entrySet()) {
                charge();
                columns[term.getKey()] = columns[term.getKey()].add(weights[i].multiply(ExactRational.of(term.getValue())));
            }
        }
        for (int j = 0; j < variables; j++) {
            if (columns[j].compareTo(ExactRational.of(objective[j])) < 0) return false;
            attained = attained.add(point[j].multiply(ExactRational.of(objective[j])));
        }
        return upper.equals(attained);
    }

    private void charge() {
        budget.check();
        work++;
    }

    private boolean finish(Result value) {
        result = value;
        close();
        return true;
    }

    Result result() {
        return result;
    }

    ExactRational[] point() {
        return point == null ? null : point.clone();
    }

    ExactRational[] certificate() {
        return certificate == null ? null : certificate.clone();
    }

    @Override
    public void close() {
        table = null;
        budget.release(memory);
        memory = 0;
    }
}
