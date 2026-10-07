package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Sparse two-phase revised simplex with product-form basis updates. Columns
 * remain sparse; only basis solves use dense vectors. Exact primal/dual checks
 * own the result. A bounded eta chain may decline without a negative claim.
 */
final class ExactRevisedProgram implements AutoCloseable {

    private record Eta(int pivot, int[] rows, ExactRational[] values, ExactRational divisor) {}

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] objective;
    private final PlanningBudget budget;
    private final int n, m;
    private final List<Map<Integer, ExactRational>> columns = new ArrayList<>();
    private final List<Eta> updates = new ArrayList<>();
    private ExactSparseFactor factor;
    private int nextRefactor = 64, refactorizations;
    private long updateMemory;
    private int[] basic, signs;
    private BitSet occupied;
    private ExactRational[] values, dual, point, certificate;
    private int phase, pricing, entering = -1, cleanup;
    private long memory, work;
    private final long allowance;
    private ExactLinearProgram.Result result;
    private boolean hot;

    static boolean suitable(int variables, List<ExactLinearProgram.Constraint> rows) {
        if (variables > 1024 || rows.size() > 1024) return false;
        long terms = rows.stream().mapToLong(row -> row.terms().size()).sum();
        return terms <= 32768 && terms * 5 < (long) variables * rows.size();
    }

    /** Do not turn an optional sparse fallback into a larger dense branch search. */
    static boolean extendsDenseAdmission(int variables, List<ExactLinearProgram.Constraint> rows) {
        return (variables > 384 || rows.size() > 512 || (variables + 2L) * (rows.size() + 2L) > 65536) &&
                suitable(variables, rows);
    }

    ExactRevisedProgram(int variables, List<ExactLinearProgram.Constraint> rows, BigInteger[] objective, PlanningBudget budget) {
        this(variables, rows, objective, budget, null);
    }

    ExactRevisedProgram(int variables, List<ExactLinearProgram.Constraint> rows, BigInteger[] objective, PlanningBudget budget, ExactLinearProgram.Basis ancestor) {
        n = variables;
        m = rows.size();
        this.rows = rows;
        this.objective = objective;
        this.budget = budget;
        allowance = Math.min(750000, budget.remainingWork() / 4);
        long entries = rows.stream().mapToLong(row -> row.terms().size()).sum();
        long bytes = 4096L + 1024L * (n + 3L * m) + 384L * entries;
        if (n > 1024 || m > 1024 || entries > 32768 || allowance < 2048 || !budget.tryReserve(bytes)) {
            result = ExactLinearProgram.Result.UNKNOWN;
            return;
        }
        memory = bytes;
        try {
            for (int j = 0; j < n; j++) columns.add(new LinkedHashMap<>());
            basic = new int[m];
            signs = new int[m];
            values = new ExactRational[m];
            occupied = new BitSet();
            for (int r = 0; r < m; r++) {
                charge();
                for (var term : rows.get(r).terms().entrySet()) {
                    charge();
                    if (term.getValue().signum() != 0) columns.get(term.getKey()).put(r, ExactRational.of(term.getValue()));
                }
                boolean negative = rows.get(r).upper().signum() < 0;
                signs[r] = negative ? -1 : 1;
                basic[r] = (negative ? n + m : n) + r;
                occupied.set(basic[r]);
                values[r] = ExactRational.of(rows.get(r).upper().abs());
            }
            if (ancestor != null) warm(ancestor);
        } catch (Stop | ExactRational.PrecisionLimit limit) {
            finish(ExactLinearProgram.Result.UNKNOWN);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    boolean step() {
        if (result != null) return true;
        try {
            charge();
            if (updates.size() >= nextRefactor) refactor();
            if (phase == 4) return dualStep();
            if (phase == 1) return cleanup();
            if (dual == null) {
                dual = dual();
                pricing = 0;
                entering = -1;
            }
            int total = phase == 0 ? n + 2 * m : n + m;
            for (int end = Math.min(total, pricing + 64); pricing < end; pricing++) {
                charge();
                if (occupied.get(pricing)) continue;
                ExactRational reduced = cost(pricing).subtract(dotColumn(pricing, dual));
                if (reduced.signum() > 0) {
                    entering = pricing++;
                    break;
                }
            }
            if (entering < 0 && pricing < total) return false;
            if (entering < 0) {
                if (phase == 0) {
                    boolean artificial = false;
                    for (int r = 0; r < m; r++) if (basic[r] >= n + m && values[r].signum() > 0) artificial = true;
                    if (artificial) {
                        certificate = dual.clone();
                        var proof = CountProof.certificate("sparse_revised_phase_one", n, rows, List.of(), certificate, true);
                        if (CountProof.verify(proof, 2_000_000) != CountProof.Verdict.VERIFIED) return finish(ExactLinearProgram.Result.UNKNOWN);
                        if (budget.proofJournal() != null) budget.proofJournal().add(proof);
                        return finish(ExactLinearProgram.Result.INFEASIBLE);
                    }
                    phase = 1;
                    dual = null;
                    return false;
                }
                point = new ExactRational[n];
                Arrays.fill(point, ExactRational.ZERO);
                for (int r = 0; r < m; r++) if (basic[r] < n) point[basic[r]] = values[r];
                return finish(verifyOptimum() ? ExactLinearProgram.Result.OPTIMAL : ExactLinearProgram.Result.UNKNOWN);
            }
            ExactRational[] direction = column(entering);
            solve(direction);
            int leaving = -1;
            ExactRational ratio = null;
            for (int r = 0; r < m; r++) {
                charge();
                if (direction[r].signum() <= 0) continue;
                ExactRational next = values[r].divide(direction[r]);
                if (leaving < 0 || next.compareTo(ratio) < 0 || next.equals(ratio) && basic[r] < basic[leaving]) {
                    leaving = r;
                    ratio = next;
                }
            }
            if (leaving < 0) return finish(phase == 0 ? ExactLinearProgram.Result.UNKNOWN : ExactLinearProgram.Result.UNBOUNDED);
            pivot(leaving, entering, direction, ratio);
            dual = null;
            return false;
        } catch (Stop | ExactRational.PrecisionLimit limit) {
            point = null;
            return finish(ExactLinearProgram.Result.UNKNOWN);
        }
    }

    private void warm(ExactLinearProgram.Basis ancestor) {
        if (ancestor.revised == null || ancestor.variables != n || ancestor.constraints.size() > m) return;
        for (int i = 0; i < ancestor.constraints.size(); i++) if (!rows.get(i).equals(ancestor.constraints.get(i))) return;
        int[] candidate = Arrays.copyOf(ancestor.revised, m);
        for (int r = ancestor.revised.length; r < m; r++) candidate[r] = n + r;
        for (int id : candidate) if (id < 0 || id >= n + m) return;
        List<Map<Integer, ExactRational>> basis = new ArrayList<>();
        for (int id : candidate) basis.add(id < n ? columns.get(id) : Map.of(id - n, ExactRational.ONE));
        long before = budget.threadWork();
        ExactSparseFactor proposal;
        try {
            proposal = ExactSparseFactor.build(basis, budget, Math.min(131072, allowance - work));
        } finally {
            work += budget.threadWork() - before;
        }
        if (proposal == null) return;
        factor = proposal;
        basic = candidate;
        occupied.clear();
        for (int id : basic) occupied.set(id);
        for (int r = 0; r < m; r++) values[r] = ExactRational.of(rows.get(r).upper());
        solve(values);
        phase = 4;
        hot = true;
        budget.note("count_sparse_reuse", "ancestor_rows=" + ancestor.revised.length + "; added_rows=" + (m - ancestor.revised.length));
    }

    boolean reconstruct(int[] proposal) {
        if (result != null || proposal == null || proposal.length != m || updates.size() != 0 || factor != null) return false;
        for (int id : proposal) if (id < 0 || id >= n + m) return false;
        List<Map<Integer, ExactRational>> basis = new ArrayList<>();
        for (int id : proposal) basis.add(id < n ? columns.get(id) : Map.of(id - n, ExactRational.ONE));
        ExactSparseFactor candidate = null;
        try {
            long before = budget.threadWork();
            try {
                candidate = ExactSparseFactor.build(basis, budget, Math.min(131072, allowance - work));
            } finally {
                work += budget.threadWork() - before;
            }
            if (candidate == null) return false;
            ExactRational[] primal = rows.stream().map(row -> ExactRational.of(row.upper())).toArray(ExactRational[]::new);
            candidate.solve(primal, this::charge);
            if (Arrays.stream(primal).anyMatch(value -> value.signum() < 0)) return false;
            // The exact factorization reconstructs every original equality and
            // nonnegative slack. Numerical optimality is deliberately ignored.
            factor = candidate;
            candidate = null;
            basic = proposal.clone();
            values = primal;
            occupied.clear();
            for (int id : basic) occupied.set(id);
            phase = 2;
            dual = null;
            budget.note("count_numeric_reconstruction", "exact_primal_basis; rational_optimization_continues");
            return true;
        } catch (Stop | ExactRational.PrecisionLimit stopped) {
            return false;
        } finally {
            if (candidate != null) candidate.close();
        }
    }

    /** Dual reoptimization keeps the ancestor objective, fixing only new primal violations. */
    private boolean dualStep() {
        if (dual == null) dual = dual();
        int leaving = -1;
        for (int r = 0; r < m; r++) if (values[r].signum() < 0 && (leaving < 0 || basic[r] < basic[leaving])) leaving = r;
        if (leaving < 0) {
            point = new ExactRational[n];
            Arrays.fill(point, ExactRational.ZERO);
            for (int r = 0; r < m; r++) if (basic[r] < n) point[basic[r]] = values[r];
            return finish(verifyOptimum() ? ExactLinearProgram.Result.OPTIMAL : ExactLinearProgram.Result.UNKNOWN);
        }
        ExactRational[] inverseRow = new ExactRational[m];
        Arrays.fill(inverseRow, ExactRational.ZERO);
        inverseRow[leaving] = ExactRational.ONE;
        transpose(inverseRow);
        int chosen = -1;
        ExactRational ratio = null;
        for (int column = 0; column < n + m; column++) {
            charge();
            if (occupied.get(column)) continue;
            ExactRational coefficient = dotColumn(column, inverseRow);
            if (coefficient.signum() >= 0) continue;
            ExactRational reduced = cost(column).subtract(dotColumn(column, dual));
            if (reduced.signum() > 0) return finish(ExactLinearProgram.Result.UNKNOWN);
            ExactRational next = reduced.divide(coefficient);
            if (chosen < 0 || next.compareTo(ratio) < 0) {
                chosen = column;
                ratio = next;
            }
        }
        if (chosen < 0) {
            var proof = CountProof.certificate("sparse_dual_reoptimization", n, rows, List.of(), inverseRow, true);
            if (CountProof.verify(proof, 2000000) != CountProof.Verdict.VERIFIED) return finish(ExactLinearProgram.Result.UNKNOWN);
            certificate = inverseRow;
            if (budget.proofJournal() != null) budget.proofJournal().add(proof);
            return finish(ExactLinearProgram.Result.INFEASIBLE);
        }
        ExactRational[] direction = column(chosen);
        solve(direction);
        pivot(leaving, chosen, direction, values[leaving].divide(direction[leaving]));
        dual = null;
        return false;
    }

    private boolean cleanup() {
        while (cleanup < m && basic[cleanup] < n + m) cleanup++;
        if (cleanup == m) {
            phase = 2;
            dual = null;
            return false;
        }
        for (int column = 0; column < n + m; column++) {
            charge();
            if (occupied.get(column)) continue;
            ExactRational[] direction = column(column);
            solve(direction);
            if (direction[cleanup].signum() != 0) {
                pivot(cleanup, column, direction, ExactRational.ZERO);
                cleanup++;
                return false;
            }
        }
        // Redundant equality: its artificial basic value stays exactly zero
        // for every original/slack column and cannot acquire positive flow.
        cleanup++;
        return false;
    }

    private ExactRational cost(int column) {
        if (phase == 0) return column >= n + m ? ExactRational.ONE.negate() : ExactRational.ZERO;
        return column < n ? ExactRational.of(objective[column]) : ExactRational.ZERO;
    }

    private ExactRational[] column(int id) {
        ExactRational[] value = new ExactRational[m];
        Arrays.fill(value, ExactRational.ZERO);
        if (id < n) columns.get(id).forEach((r, coefficient) -> value[r] = coefficient);
        else value[(id - n) % m] = id < n + m ? ExactRational.ONE : ExactRational.ONE.negate();
        return value;
    }

    private ExactRational dotColumn(int id, ExactRational[] value) {
        if (id >= n) return id < n + m ? value[id - n] : value[id - n - m].negate();
        ExactRational sum = ExactRational.ZERO;
        for (var entry : columns.get(id).entrySet()) {
            charge();
            sum = sum.add(entry.getValue().multiply(value[entry.getKey()]));
        }
        return sum;
    }

    private void solve(ExactRational[] value) {
        if (factor != null) factor.solve(value, this::charge);
        else for (int i = 0; i < m; i++) if (signs[i] < 0) value[i] = value[i].negate();
        for (Eta eta : updates) {
            charge();
            ExactRational pivot = value[eta.pivot].divide(eta.divisor);
            if (pivot.signum() != 0) for (int i = 0; i < eta.rows.length; i++) {
                charge();
                int row = eta.rows[i];
                if (row != eta.pivot) value[row] = value[row].subtract(eta.values[i].multiply(pivot));
            }
            value[eta.pivot] = pivot;
        }
    }

    private ExactRational[] dual() {
        ExactRational[] value = new ExactRational[m];
        for (int i = 0; i < m; i++) value[i] = cost(basic[i]);
        transpose(value);
        return value;
    }

    private void transpose(ExactRational[] value) {
        for (int e = updates.size() - 1; e >= 0; e--) {
            Eta eta = updates.get(e);
            ExactRational pivot = value[eta.pivot];
            for (int i = 0; i < eta.rows.length; i++) {
                charge();
                int row = eta.rows[i];
                if (row != eta.pivot) pivot = pivot.subtract(eta.values[i].multiply(value[row]));
            }
            value[eta.pivot] = pivot.divide(eta.divisor);
        }
        if (factor != null) factor.transpose(value, this::charge);
        else for (int i = 0; i < m; i++) if (signs[i] < 0) value[i] = value[i].negate();
    }

    private void refactor() {
        nextRefactor += 64;
        long available = Math.min(65536, allowance - work);
        if (available < 2048) return;
        List<Map<Integer, ExactRational>> basisColumns = new ArrayList<>();
        for (int id : basic) {
            charge();
            basisColumns.add(id < n ? columns.get(id) : Map.of((id - n) % m, id < n + m ? ExactRational.ONE : ExactRational.ONE.negate()));
        }
        long before = budget.threadWork();
        ExactSparseFactor candidate;
        try {
            candidate = ExactSparseFactor.build(basisColumns, budget, available);
        } finally {
            work += budget.threadWork() - before;
        }
        if (candidate == null) return;
        if (factor != null) factor.close();
        factor = candidate;
        refactorizations++;
        updates.clear();
        budget.release(updateMemory);
        memory -= updateMemory;
        updateMemory = 0;
        nextRefactor = 64;
    }

    private void pivot(int leaving, int entering, ExactRational[] direction, ExactRational distance) {
        if (updates.size() >= 256) throw new Stop();
        int size = 0;
        for (var value : direction) if (value.signum() != 0) size++;
        long bytes = 256L + 768L * size;
        if (!budget.tryReserve(bytes)) throw new Stop();
        memory += bytes;
        updateMemory += bytes;
        int[] positions = new int[size];
        ExactRational[] coefficients = new ExactRational[size];
        for (int r = 0, at = 0; r < m; r++) {
            charge();
            if (direction[r].signum() != 0) {
                positions[at] = r;
                coefficients[at++] = direction[r];
            }
            values[r] = values[r].subtract(distance.multiply(direction[r]));
            if (phase != 4 && values[r].signum() < 0) throw new IllegalStateException("Negative revised basic value");
        }
        values[leaving] = distance;
        updates.add(new Eta(leaving, positions, coefficients, direction[leaving]));
        occupied.clear(basic[leaving]);
        occupied.set(entering);
        basic[leaving] = entering;
    }

    private boolean verifyOptimum() {
        if (Arrays.stream(point).anyMatch(value -> value.signum() < 0) || Arrays.stream(dual).anyMatch(value -> value.signum() < 0)) return false;
        ExactRational primal = ExactRational.ZERO, bound = ExactRational.ZERO;
        for (int r = 0; r < m; r++) {
            ExactRational sum = ExactRational.ZERO;
            for (var term : rows.get(r).terms().entrySet()) {
                charge();
                sum = sum.add(ExactRational.of(term.getValue()).multiply(point[term.getKey()]));
            }
            if (sum.compareTo(ExactRational.of(rows.get(r).upper())) > 0) return false;
            bound = bound.add(dual[r].multiply(ExactRational.of(rows.get(r).upper())));
        }
        for (int i = 0; i < n; i++) {
            charge();
            if (dotColumn(i, dual).compareTo(ExactRational.of(objective[i])) < 0) return false;
            primal = primal.add(point[i].multiply(ExactRational.of(objective[i])));
        }
        return primal.equals(bound);
    }

    private void charge() {
        budget.check();
        if (++work > allowance) throw new Stop();
    }

    private boolean finish(ExactLinearProgram.Result outcome) {
        result = outcome;
        budget.note("count_sparse_lp", outcome + "; variables=" + n + "; rows=" + m + "; basis_updates=" + updates.size() + "; refactors=" + refactorizations + "; work=" + work + "; bytes=" + (memory + (factor == null ? 0 : factor.bytes())));
        return true;
    }

    ExactLinearProgram.Result result() {
        return result;
    }

    ExactRational[] point() {
        return point == null ? null : point.clone();
    }

    ExactRational[] certificate() {
        return certificate == null ? null : certificate.clone();
    }

    ExactRational[] optimumDual() {
        return result == ExactLinearProgram.Result.OPTIMAL ? dual.clone() : null;
    }

    boolean hot() {
        return hot;
    }

    ExactLinearProgram.Basis snapshot() {
        if (result != ExactLinearProgram.Result.OPTIMAL || Arrays.stream(basic).anyMatch(id -> id >= n + m)) return null;
        long bytes = 256L + 64L * n + 32L * m;
        if (!budget.tryReserve(bytes)) return null;
        return new ExactLinearProgram.Basis(n, List.copyOf(rows), objective, basic, budget, bytes);
    }

    @Override
    public void close() {
        if (factor != null) factor.close();
        factor = null;
        budget.release(memory);
        memory = 0;
    }
}
