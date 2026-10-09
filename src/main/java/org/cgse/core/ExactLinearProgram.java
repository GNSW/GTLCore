// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

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
            // Propagation and bounded candidate search must not depend on the
            // per-JVM iteration salt of Map.copyOf. Keep coordinate order stable
            // across launches, while retaining immutable constant-time lookup.
            if (terms.size() < 2) terms = Map.copyOf(terms);
            else {
                Map<Integer, BigInteger> ordered = new LinkedHashMap<>();
                terms.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> ordered.put(entry.getKey(), Objects.requireNonNull(entry.getValue())));
                terms = Collections.unmodifiableMap(ordered);
            }
        }
    }

    private final List<Constraint> constraints;
    private final List<Constraint> inputRows;
    private final BigInteger[] objective;
    private final PlanningBudget budget;
    private final int rows, variables;
    private final long allowance;
    private ExactRational[][] table;
    private int[] basic, nonbasic;
    private int phase, pivotRow = -1, pivotColumn, cell;
    private ExactRational divisor;
    private ExactRational.ProductQuotient rowFactor;
    private int factorRow = -1;
    private long factorMemory;
    private boolean factorAttempted;
    private long memory, work;
    private Result result;
    private ExactRational[] point, certificate;
    private ExactRational[] optimumDual;
    private final boolean keepBasis;
    private boolean hot;
    private Basis savedBasis;
    private ExactRevisedProgram sparse;
    private ExactColumnProgram priced;
    private final boolean allowPricing;
    private boolean pricingTried;
    private boolean numericalTried;

    /** Completed tableau owned by a reference-counted immutable ancestor. */
    static final class Basis implements AutoCloseable {

        final List<Constraint> constraints;
        final BigInteger[] objective;
        final ExactRational[][] table;
        final int[] basic, nonbasic;
        final int[] revised;
        final int variables;
        private final PlanningBudget budget;
        private final long memory;
        private final AtomicInteger owners = new AtomicInteger(1);

        Basis(ExactLinearProgram source) {
            constraints = source.constraints;
            objective = source.objective;
            table = source.table;
            basic = source.basic;
            nonbasic = source.nonbasic;
            revised = null;
            variables = source.variables;
            budget = source.budget;
            memory = source.memory;
        }

        Basis(int variables, List<Constraint> constraints, BigInteger[] objective, int[] revised, PlanningBudget budget, long memory) {
            this.variables = variables;
            this.constraints = constraints;
            this.objective = objective.clone();
            this.revised = revised.clone();
            table = null;
            basic = nonbasic = null;
            this.budget = budget;
            this.memory = memory;
        }

        Basis retain() {
            owners.incrementAndGet();
            return this;
        }

        @Override
        public void close() {
            if (owners.decrementAndGet() == 0) budget.release(memory);
        }
    }

    ExactLinearProgram(int variables, List<Constraint> constraints, BigInteger[] objective, PlanningBudget budget) {
        this(variables, constraints, objective, budget, null, false);
    }

    ExactLinearProgram(int variables, List<Constraint> constraints, BigInteger[] objective, PlanningBudget budget, Basis ancestor, boolean keepBasis) {
        this(variables, constraints, objective, budget, ancestor, keepBasis, true);
    }

    ExactLinearProgram(int variables, List<Constraint> constraints, BigInteger[] objective, PlanningBudget budget, Basis ancestor, boolean keepBasis, boolean allowPricing) {
        this.allowPricing = allowPricing;
        this.variables = variables;
        var requestedRows = constraints;
        constraints = distinctRows(variables, constraints, budget);
        if (ancestor != null && (ancestor.variables != variables || !Arrays.equals(ancestor.objective, objective))) ancestor = null;
        if (ancestor != null && !retainsConstraints(constraints, ancestor.constraints, budget)) ancestor = null;
        List<Constraint> combined = constraints;
        if (ancestor != null) {
            var union = new ArrayList<>(ancestor.constraints);
            var known = new HashSet<>(ancestor.constraints);
            for (var row : constraints) if (known.add(row)) union.add(row);
            combined = List.copyOf(union);
            if (ancestor.revised == null && (combined.size() > 512 || (combined.size() + 2L) * (variables + 2L) > 65_536) || combined.size() > 1024) {
                ancestor = null;
                combined = constraints;
            }
        }
        this.constraints = List.copyOf(combined);
        inputRows = this.constraints.equals(requestedRows) ? null : List.copyOf(requestedRows);
        this.objective = objective.clone();
        this.budget = budget;
        this.keepBasis = keepBasis;
        allowance = Math.min(500_000, budget.remainingWork() / 4);
        rows = this.constraints.size();
        long cells = (rows + 2L) * (variables + 2L);
        long bytes = cells * 768L + 64L * (rows + variables + 2L);
        // Medium cold tableaux can spend their whole rational allowance just
        // finding a basis. The numerical pass supplies indices only; exact
        // factorization and rational optimization still own every verdict.
        if (ancestor == null && variables >= 64 && variables <= 128 && rows <= 256) {
            try {
                numericalTried = true;
                int[] proposal = CountLpProposal.propose(variables, this.constraints, this.objective, budget,
                        Math.min(2_000_000, budget.remainingWork() / 4));
                if (proposal != null) {
                    sparse = new ExactRevisedProgram(variables, this.constraints, this.objective, budget);
                    if (sparse.reconstruct(proposal)) return;
                    sparse.close();
                    sparse = null;
                }
            } catch (RuntimeException | Error failure) {
                // A constructor has no caller-owned workspace yet. This also
                // releases a factorization interrupted during reconstruction.
                close();
                throw failure;
            }
        }
        if (ancestor != null && ancestor.revised != null) {
            sparse = new ExactRevisedProgram(variables, this.constraints, this.objective, budget, ancestor);
            return;
        }
        if (allowance < 1024 || variables > 384 || rows > 512 || cells > 65_536 || !budget.tryReserve(bytes)) {
            if (allowance >= 1024 && ExactRevisedProgram.suitable(variables, this.constraints))
                sparse = new ExactRevisedProgram(variables, this.constraints, this.objective, budget);
            else if (allowPricing && variables >= 64) {
                priced = new ExactColumnProgram(variables, this.constraints, objective, budget);
                pricingTried = true;
            } else result = Result.UNKNOWN;
            budget.note("count_lp_admission", "dense_cells=" + cells + "; dense_bytes=" + bytes + "; sparse=" + (sparse != null));
            return;
        }
        memory = bytes;
        try {
            table = new ExactRational[rows + 2][variables + 2];
            for (ExactRational[] row : table) Arrays.fill(row, ExactRational.ZERO);
            basic = new int[rows];
            nonbasic = new int[variables + 1];
            if (ancestor != null) {
                warm(ancestor);
                return;
            }
            for (int i = 0; i < rows; i++) {
                basic[i] = variables + i;
                for (var term : this.constraints.get(i).terms().entrySet()) {
                    budget.check();
                    table[i][term.getKey()] = ExactRational.of(term.getValue());
                }
                table[i][variables] = ExactRational.ONE.negate();
                table[i][variables + 1] = ExactRational.of(this.constraints.get(i).upper());
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

    private static List<Constraint> distinctRows(int variables, List<Constraint> rows, PlanningBudget budget) {
        // Propagation often repeats the original unary upper bounds. Retain
        // the first occurrence so these equal rows do not double the basis.
        if (variables < 64 || variables > 128 || rows.size() > 1024) return rows;
        long allowance = Math.min(16384, budget.remainingWork() / 64), started = budget.threadWork();
        long bytes = 128L + 96L * rows.size();
        if (allowance < 1024 || !budget.tryReserve(bytes)) return rows;
        try {
            Set<Constraint> distinct = new LinkedHashSet<>();
            for (var row : rows) {
                budget.check();
                for (var ignored : row.terms().entrySet()) {
                    budget.check();
                    if (budget.threadWork() - started >= allowance) return rows;
                }
                distinct.add(row);
            }
            return distinct.size() == rows.size() ? rows : List.copyOf(distinct);
        } finally {
            budget.release(bytes);
        }
    }

    private static boolean retainsConstraints(List<Constraint> rows, List<Constraint> ancestor, PlanningBudget budget) {
        Map<Map<Integer, BigInteger>, BigInteger> limits = new HashMap<>();
        for (var row : rows) {
            budget.check();
            limits.merge(row.terms(), row.upper(), BigInteger::min);
        }
        for (var row : ancestor) {
            budget.check();
            if (row.terms().isEmpty() && row.upper().signum() >= 0) continue;
            BigInteger limit = limits.get(row.terms());
            if (limit == null || limit.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private void warm(Basis ancestor) {
        int oldRows = ancestor.constraints.size();
        System.arraycopy(ancestor.nonbasic, 0, nonbasic, 0, nonbasic.length);
        System.arraycopy(ancestor.basic, 0, basic, 0, ancestor.basic.length);
        for (int i = 0; i < oldRows; i++) {
            charge();
            System.arraycopy(ancestor.table[i], 0, table[i], 0, variables + 2);
        }
        System.arraycopy(ancestor.table[oldRows], 0, table[rows], 0, variables + 2);
        int[] basicRow = new int[variables], nonbasicColumn = new int[variables];
        Arrays.fill(basicRow, -1);
        Arrays.fill(nonbasicColumn, -1);
        for (int i = 0; i < oldRows; i++) if (basic[i] >= 0 && basic[i] < variables) basicRow[basic[i]] = i;
        for (int j = 0; j <= variables; j++) if (nonbasic[j] >= 0 && nonbasic[j] < variables) nonbasicColumn[nonbasic[j]] = j;
        for (int i = oldRows; i < rows; i++) {
            basic[i] = variables + i;
            table[i][variables + 1] = ExactRational.of(constraints.get(i).upper());
            for (var term : constraints.get(i).terms().entrySet()) {
                ExactRational value = ExactRational.of(term.getValue());
                int r = basicRow[term.getKey()];
                if (r >= 0) for (int j = 0; j < variables + 2; j++) {
                    charge();
                    table[i][j] = table[i][j].subtract(value.multiply(table[r][j]));
                }
                else table[i][nonbasicColumn[term.getKey()]] = table[i][nonbasicColumn[term.getKey()]].add(value);
            }
        }
        hot = true;
        phase = 4;
        budget.note("count_lp_reuse", "ancestor_rows=" + oldRows + "; added_rows=" + (rows - oldRows));
    }

    boolean step() {
        budget.check();
        if (result != null) return true;
        if (priced != null) {
            if (!priced.step()) return false;
            result = priced.result();
            point = priced.point();
            certificate = priced.certificate();
            optimumDual = priced.dual();
            priced.close();
            priced = null;
            return true;
        }
        if (sparse != null) {
            if (!sparse.step()) return false;
            result = sparse.result();
            point = sparse.point();
            certificate = sparse.certificate();
            optimumDual = sparse.optimumDual();
            if (keepBasis && result == Result.OPTIMAL) savedBasis = sparse.snapshot();
            hot = sparse.hot();
            sparse.close();
            sparse = null;
            if (result == Result.UNKNOWN && startPricing()) return false;
            return true;
        }
        if (work >= allowance) return finish(Result.UNKNOWN);
        try {
            if (pivotRow >= 0) {
                updatePivot();
                return false;
            }
            if (phase == 4) return dualStep();
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

    private boolean dualStep() {
        int leaving = -1;
        for (int i = 0; i < rows; i++) {
            charge();
            if (table[i][variables + 1].signum() < 0 && (leaving < 0 || basic[i] < basic[leaving])) leaving = i;
        }
        if (leaving < 0) {
            point = primal();
            return finish(validPoint() && validOptimum() ? Result.OPTIMAL : Result.UNKNOWN);
        }
        int entering = -1;
        ExactRational ratio = null;
        for (int j = 0; j <= variables; j++) {
            charge();
            if (nonbasic[j] == -1 || table[leaving][j].signum() >= 0) continue;
            ExactRational next = table[rows][j].divide(table[leaving][j].negate());
            if (entering < 0 || next.compareTo(ratio) < 0 || next.compareTo(ratio) == 0 && nonbasic[j] < nonbasic[entering]) {
                entering = j;
                ratio = next;
            }
        }
        if (entering < 0) {
            certificate = dual(leaving);
            if (basic[leaving] >= variables) certificate[basic[leaving] - variables] = ExactRational.ONE;
            return finish(validCertificate() ? Result.INFEASIBLE : Result.UNKNOWN);
        }
        pivot(leaving, entering);
        return false;
    }

    private void pivot(int row, int column) {
        pivotRow = row;
        pivotColumn = column;
        divisor = table[row][column];
        cell = 0;
        rowFactor = null;
        factorRow = -1;
    }

    private void updatePivot() {
        if (!factorAttempted) {
            factorAttempted = true;
            // One reduced 2048-bit fraction, retained only by this tableau.
            // Declining optional scratch must not change LP admission.
            if (budget.tryReserve(768)) factorMemory = 768;
        }
        int columns = variables + 2, cells = (rows + 2) * columns;
        for (int part = 0; part < 256 && cell < cells; part++, cell++) {
            int i = cell / columns, j = cell % columns;
            if (i == pivotRow || j == pivotColumn || table[i][pivotColumn].signum() == 0 || table[pivotRow][j].signum() == 0) continue;
            chargePivot(table[i][j], table[pivotRow][j], table[i][pivotColumn], divisor);
            if (factorMemory != 0 && factorRow != i) {
                factorRow = i;
                rowFactor = new ExactRational.ProductQuotient(table[i][pivotColumn], divisor);
            }
            // The factor is unchanged across this row, including 256-cell
            // handoffs. Reordering must not bypass the old product's precision
            // limit or reject a factor that only fits after multiplication.
            ExactRational change = rowFactor != null ? rowFactor.apply(table[pivotRow][j]) :
                    table[pivotRow][j].multiply(table[i][pivotColumn]).divide(divisor);
            table[i][j] = table[i][j].subtract(change);
        }
        if (cell < cells) return;
        // The column entries are about to be replaced; do not retain an old
        // multiplier alongside the next pivot's optional fraction.
        rowFactor = null;
        factorRow = -1;
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

    private boolean startPricing() {
        // Pricing is a fallback for large sparse models, not an extra toll on
        // every small LP. Every master still checks the full original catalog.
        if (!allowPricing || pricingTried || variables < 128 || budget.remainingWork() < 65536) return false;
        pricingTried = true;
        releaseTable();
        point = certificate = optimumDual = null;
        result = null;
        priced = new ExactColumnProgram(variables, constraints, objective, budget);
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
        if (!upper.equals(attained)) return false;
        optimumDual = weights;
        return true;
    }

    private void charge() {
        budget.check();
        work++;
    }

    private void chargePivot(ExactRational a, ExactRational b, ExactRational c, ExactRational d) {
        int bits = Math.max(Math.max(bits(a), bits(b)), Math.max(bits(c), bits(d)));
        work += budget.operation(PlanningBudget.Operation.RATIONAL, bits) / PlanningBudget.WORK_SCALE;
    }

    private static int bits(ExactRational value) {
        return Math.max(value.numerator().bitLength(), value.denominator().bitLength());
    }

    private boolean finish(Result value) {
        if (value == Result.UNKNOWN && !numericalTried && variables >= 16 && table != null) {
            numericalTried = true;
            releaseTable();
            int[] proposal = CountLpProposal.propose(variables, constraints, objective, budget);
            if (proposal != null) {
                sparse = new ExactRevisedProgram(variables, constraints, objective, budget);
                if (sparse.reconstruct(proposal)) return false;
                sparse.close();
                sparse = null;
            }
        }
        if (value == Result.UNKNOWN && startPricing()) return false;
        if (value == Result.INFEASIBLE && certificate != null && budget.proofJournal() != null)
            budget.proofJournal().add(CountProof.certificate("rational_relaxation", variables, constraints, List.of(), certificate, true));
        result = value;
        if (keepBasis && value == Result.OPTIMAL && Arrays.stream(basic).noneMatch(id -> id == -1)) {
            releaseFactor();
            savedBasis = new Basis(this);
            table = null;
            memory = 0;
        } else releaseTable();
        return true;
    }

    Basis takeBasis() {
        Basis result = savedBasis;
        savedBasis = null;
        return result;
    }

    boolean hot() {
        return hot;
    }

    Result result() {
        return result;
    }

    ExactRational[] point() {
        return point == null ? null : point.clone();
    }

    ExactRational[] certificate() {
        return inputWeights(certificate);
    }

    ExactRational[] optimumDual() {
        return inputWeights(optimumDual);
    }

    private ExactRational[] inputWeights(ExactRational[] weights) {
        if (weights == null) return null;
        if (inputRows == null) return weights.clone();
        long bytes = 128L + 96L * inputRows.size();
        budget.reserve(bytes);
        try {
            Map<Constraint, Integer> positions = new HashMap<>();
            for (int i = 0; i < inputRows.size(); i++) {
                budget.check();
                positions.putIfAbsent(inputRows.get(i), i);
            }
            ExactRational[] restored = new ExactRational[inputRows.size()];
            Arrays.fill(restored, ExactRational.ZERO);
            for (int i = 0; i < weights.length; i++) {
                budget.check();
                if (weights[i].signum() == 0) continue;
                Integer position = positions.get(constraints.get(i));
                // A hot ancestor can retain a weaker row absent from the new
                // input. Its internal proof remains valid, but does not expose
                // positional multipliers for a different caller row list.
                if (position == null) return null;
                restored[position] = restored[position].add(weights[i]);
            }
            return restored;
        } finally {
            budget.release(bytes);
        }
    }

    @Override
    public void close() {
        if (priced != null) {
            priced.close();
            priced = null;
        }
        if (sparse != null) {
            sparse.close();
            sparse = null;
        }
        if (savedBasis != null) {
            savedBasis.close();
            savedBasis = null;
        }
        releaseTable();
    }

    private void releaseTable() {
        releaseFactor();
        table = null;
        budget.release(memory);
        memory = 0;
    }

    private void releaseFactor() {
        rowFactor = null;
        factorRow = -1;
        budget.release(factorMemory);
        factorMemory = 0;
    }
}
