// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.function.UnaryOperator;

/**
 * Immutable integer model plus optional equivalent search representations.
 * Light compilation keeps recipe coordinates; elimination carries its inverse.
 * All candidates are checked against the original rows and domains. Execution
 * and seed verification still happen separately on the original recipes.
 */
final class CountModelViews implements AutoCloseable {

    /** Count coverage only. Every positive candidate still needs original execution validation. */
    enum Semantics {

        EQUIVALENT,
        RELAXATION,
        RESTRICTED,
        HINT;

        boolean transfersProof() {
            return this == EQUIVALENT || this == RELAXATION;
        }
    }

    record Shape(int variables, long terms, int coefficientBits, int unfixed) {

        long cost() {
            return Math.max(1, terms + 4L * unfixed) * Math.max(1, (coefficientBits + 63L) / 64);
        }
    }

    record View(String name, List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                Shape shape, UnaryOperator<BigInteger[]> inverse, Semantics semantics, CountMapping substitution) {

        BigInteger[] restore(BigInteger[] counts) {
            return inverse == null ? counts : inverse.apply(counts);
        }
    }

    private final PlanningBudget budget;
    private final View original;
    private View reduced;
    private final List<View> views = new ArrayList<>();
    private final List<View> auxiliaryViews = new ArrayList<>();
    private boolean lightAttempted;
    private long memory;
    private BigInteger[] sharedLower, sharedUpper;
    private long factVersion;
    private CountViewConflicts conflicts;
    private CountViewCuts cuts;
    private RecipeCountModel<?> templateModel;

    void retainTemplates(RecipeCountModel<?> model) { templateModel = model; }

    record Domains(BigInteger[] lower, BigInteger[] upper, long version) {}

    private CountModelViews(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                            PlanningBudget budget, long bytes) {
        this.budget = budget;
        memory = bytes;
        original = new View("original", List.copyOf(rows), low.clone(), high.clone(), shape(rows, low, high, budget), null, Semantics.EQUIVALENT, null);
        views.add(original);
    }

    static CountModelViews create(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                                  PlanningBudget budget) {
        long terms = rows.stream().mapToLong(r -> r.terms().size()).sum();
        if (!admissible(low.length, rows.size(), terms, budget)) return null;
        long bytes = 1024L + 32L * low.length + 16L * rows.size();
        if (!budget.tryReserve(bytes)) return null;
        try {
            return new CountModelViews(rows, low, high, budget, bytes);
        } catch (RuntimeException | Error failure) {
            budget.release(bytes);
            throw failure;
        }
    }

    /** Optional search admission, never an infeasibility test. */
    static boolean admissible(int variables, int rows, long terms, PlanningBudget budget) {
        // Permit large sparse models when their actual indexing work fits.
        // Outer limits bound Java allocations even with an enormous caller
        // budget; each representation separately reserves its real workspace.
        return variables <= 8192 && rows <= 32768 && terms <= 1_048_576 &&
                4L * variables + rows + terms <= budget.remainingWork();
    }

    List<View> available() {
        return List.copyOf(views);
    }

    /** Actual input of the existing reduced-model specialists, even after view deduplication. */
    View reduced() {
        return reduced;
    }

    boolean compiledAlternatives() { return lightAttempted || reduced != null; }

    /** Integer-equivalent row simplification without changing variable coordinates. */
    void compileLight() {
        if (lightAttempted) return;
        lightAttempted = true;
        long bytes = 1024L + 256L * original.rows.size() + 192L * original.shape.terms;
        if (!budget.tryReserve(bytes)) return;
        memory += bytes;
        Map<Map<Integer, BigInteger>, ExactLinearProgram.Constraint> unique = new LinkedHashMap<>();
        for (var row : original.rows) {
            var normalized = simplify(row);
            if (normalized == null) continue;
            var previous = unique.get(normalized.terms());
            if (previous == null || normalized.upper().compareTo(previous.upper()) < 0) unique.put(normalized.terms(), normalized);
        }
        var rows = List.copyOf(unique.values());
        if (rows.equals(original.rows)) {
            budget.release(bytes);
            memory -= bytes;
            return;
        }
        views.add(new View("normalized", rows, original.lower, original.upper, shape(rows, original.lower, original.upper, budget), null, Semantics.EQUIVALENT, null));
        budget.note("count_light_compile", "rows=" + original.rows.size() + "->" + rows.size() +
                "; terms=" + original.shape.terms + "->" + views.get(views.size() - 1).shape.terms);
    }

    private ExactLinearProgram.Constraint simplify(ExactLinearProgram.Constraint row) {
        Map<Integer, BigInteger> terms = new LinkedHashMap<>();
        BigInteger bound = row.upper(), maximum = BigInteger.ZERO;
        boolean finite = true;
        for (var term : row.terms().entrySet()) {
            budget.check();
            int id = term.getKey();
            BigInteger a = term.getValue();
            if (a.signum() == 0) continue;
            if (original.lower[id].equals(original.upper[id])) {
                bound = bound.subtract(a.multiply(original.lower[id]));
                continue;
            }
            terms.put(id, a);
            BigInteger endpoint = a.signum() > 0 ? original.upper[id] : original.lower[id];
            if (endpoint == null) finite = false;
            else maximum = maximum.add(a.multiply(endpoint));
        }
        if (finite) {
            BigInteger gap = maximum.subtract(bound);
            if (gap.signum() <= 0) return null;
            // In coordinates measured down from the maximizing endpoints the
            // row is sum |a_i| * distance_i >= gap. Integer distances permit
            // capping a coefficient at gap, preserving EVERY integer solution.
            // This is not an objective-based or tolerance-based reduction.
            for (var term : terms.entrySet()) {
                budget.check();
                BigInteger a = term.getValue();
                if (a.abs().compareTo(gap) <= 0) continue;
                int id = term.getKey();
                BigInteger next = a.signum() > 0 ? gap : gap.negate();
                BigInteger endpoint = a.signum() > 0 ? original.upper[id] : original.lower[id];
                bound = bound.add(next.subtract(a).multiply(endpoint));
                term.setValue(next);
            }
        }
        var normalized = CountReduction.normalize(new ExactLinearProgram.Constraint(terms, bound));
        return CountIntegerCoefficients.simplify(normalized, original.lower, original.upper, budget);
    }

    /** The owner retains the reduction until all searches using its inverse close. */
    void addReduced(CountReduction reduction) {
        // A correct inverse cannot justify a negative result from a different
        // request, trial face, inventory overlay or set of branch assumptions.
        if (!reduction.matchesScope(original.rows, original.lower, original.upper))
            throw new IllegalArgumentException("Count view belongs to another assumption scope");
        var stride = reduction.stride();
        if (stride != null && views.stream().noneMatch(view -> view.name.equals("stride"))) {
            var rows = stride.rows();
            var low = stride.lower();
            var high = stride.upper();
            long bytes = 256L + 32L * low.length + 384L * original.lower.length;
            if (budget.tryReserve(bytes)) {
                memory += bytes;
                var view = new View("stride", rows, low, high, shape(rows, low, high, budget), stride::expand, Semantics.EQUIVALENT, stride.substitution());
                views.add(view);
                publishBounds(view, low, high);
            }
        }
        var rows = reduction.rows();
        var low = reduction.lower();
        var high = reduction.upper();
        reduced = views.stream().filter(v -> v.rows.equals(rows) && Arrays.equals(v.lower, low) && Arrays.equals(v.upper, high)).findFirst().orElse(null);
        if (reduced != null) return;
        long bytes = 256L + 32L * low.length + 384L * original.lower.length;
        if (!budget.tryReserve(bytes)) return;
        memory += bytes;
        var view = new View("reduced", rows, low, high, shape(rows, low, high, budget), reduction::expand, Semantics.EQUIVALENT, reduction.substitution());
        views.add(view);
        reduced = view;
        publishBounds(view, low, high);
    }

    boolean sharesBounds(View view) {
        return original.lower.length <= 1024 && budget.remainingWork() >= 8L * original.lower.length + 1024 &&
                sharesCuts(view);
    }

    boolean sharesCuts(View view) {
        // Sparse certified rows have their own term, byte and work caps. The
        // dense-domain sharing limit must not discard a small consequence in
        // a large model. Ownership and covering semantics are still required.
        return view != null && view.semantics.transfersProof() &&
                (views.stream().anyMatch(value -> value == view) || auxiliaryViews.stream().anyMatch(value -> value == view));
    }

    /** Register the existing canonical LP engine for sharing, without starting another search arm. */
    View registerLp(CountReduction reduction, CountCanonicalModel canonical) {
        if (!reduction.matchesScope(original.rows, original.lower, original.upper)) return null;
        long bytes = 512L + 384L * original.lower.length + 64L * canonical.lower().length;
        if (bytes > budget.availableBytes() / 8 || !budget.tryReserve(bytes)) return null;
        memory += bytes;
        var mapping = reduction.substitution().then(canonical.substitution(), budget);
        var rows = canonical.rows();
        var lower = canonical.lower();
        var upper = canonical.upper();
        var view = new View("lp_canonical", rows, lower, upper, shape(rows, lower, upper, budget),
                values -> mapping.restore(values, budget), Semantics.EQUIVALENT, mapping);
        auxiliaryViews.add(view);
        return view;
    }

    void publishCuts(View view, List<CountLpLearning.Cut> learned, int from, Object origin) {
        if (from >= learned.size() || !sharesCuts(view)) return;
        if (cuts == null) cuts = CountViewCuts.create(budget, original);
        if (cuts == null) return;
        int before = cuts.version();
        cuts.publish(view, learned, from, origin);
        if (cuts.version() > before) budget.note("count_view_cuts", "origin=" + view.name() + "; certified=" + (cuts.version() - before) +
                "; version=" + cuts.version() + "; scope=owned_count_model");
        // Identity-coordinate views may cite normalized material rows. The cache
        // independently reconstructs each premise from the complete material row;
        // bound-dependent simplifications and unmapped affine rows are rejected.
        if (view.inverse == null && view.substitution == null && view.semantics == Semantics.EQUIVALENT &&
                templateModel != null && templateModel.rowTemplates != null)
            for (int i = from; i < learned.size(); i++)
                templateModel.rowTemplates.remember(templateModel, view.rows(), learned.get(i), budget);
    }

    int cutVersion() {
        return cuts == null ? 0 : cuts.version();
    }

    int importCuts(View view, int after, Object origin, CountLcg solver) {
        if (cuts == null || after >= cuts.version() || !sharesCuts(view)) return 0;
        return cuts.transfer(view, after, origin, solver::learn);
    }

    /** Only proved level-zero domains from a registered, covering view enter this request-local pool. */
    void publishBounds(View view, BigInteger[] low, BigInteger[] high) {
        if (!sharesBounds(view) || low.length != view.lower.length || high.length != low.length) return;
        int changed = 0;
        for (int i = 0; i < original.lower.length; i++) {
            budget.check();
            BigInteger l, h;
            if (view.substitution == null) {
                l = low[i];
                h = high[i];
            } else {
                var expression = view.substitution.coordinates().get(i);
                if (expression.terms().isEmpty()) l = h = expression.constant();
                else {
                    // This first sharing layer intentionally handles only the
                    // exact single-coordinate affine maps used by our views.
                    if (expression.terms().size() != 1) continue;
                    var term = expression.terms().entrySet().iterator().next();
                    int id = term.getKey();
                    BigInteger a = term.getValue(), offset = expression.constant();
                    BigInteger left = a.signum() > 0 ? low[id] : high[id];
                    BigInteger right = a.signum() > 0 ? high[id] : low[id];
                    l = left == null ? null : a.multiply(left).add(offset);
                    h = right == null ? null : a.multiply(right).add(offset);
                }
            }
            if (l != null && l.bitLength() > 1024 || h != null && h.bitLength() > 1024) continue;
            BigInteger oldLow = sharedLower == null ? original.lower[i] : sharedLower[i];
            BigInteger oldHigh = sharedUpper == null ? original.upper[i] : sharedUpper[i];
            boolean tightenLow = l != null && l.compareTo(oldLow) > 0;
            boolean tightenHigh = h != null && (oldHigh == null || h.compareTo(oldHigh) < 0);
            if (!tightenLow && !tightenHigh) continue;
            if (sharedLower == null) {
                long bytes = 256L + 352L * original.lower.length;
                if (!budget.tryReserve(bytes)) return;
                memory += bytes;
                sharedLower = original.lower.clone();
                sharedUpper = original.upper.clone();
            }
            if (tightenLow) sharedLower[i] = l;
            if (tightenHigh) sharedUpper[i] = h;
            changed++;
        }
        if (changed > 0) {
            factVersion++;
            budget.note("count_view_facts", "origin=" + view.name + "; root_bounds=" + changed + "; version=" + factVersion + "; scope=owned_count_model");
        }
    }

    /** Import into a new engine only; retained engines keep their original trail and proof scope. */
    Domains domains(View view) {
        // Proof-mode engines must begin with the declared view's axioms. A
        // shared bound enters LCG as a checked consequence below, never as an
        // unexplained new root-domain assumption in its final certificate.
        if (sharedLower == null || budget.proofJournal() != null || !sharesBounds(view)) return new Domains(view.lower, view.upper, 0);
        BigInteger[] low = view.lower.clone(), high = view.upper.clone();
        for (int i = 0; i < original.lower.length; i++) {
            budget.check();
            if (view.substitution == null) {
                low[i] = low[i].max(sharedLower[i]);
                if (sharedUpper[i] != null) high[i] = high[i] == null ? sharedUpper[i] : high[i].min(sharedUpper[i]);
            } else {
                var expression = view.substitution.coordinates().get(i);
                if (expression.terms().size() != 1) continue;
                var term = expression.terms().entrySet().iterator().next();
                int id = term.getKey();
                BigInteger a = term.getValue(), offset = expression.constant();
                if (a.signum() > 0) {
                    low[id] = low[id].max(ceil(sharedLower[i].subtract(offset), a));
                    if (sharedUpper[i] != null) {
                        BigInteger bound = floor(sharedUpper[i].subtract(offset), a);
                        high[id] = high[id] == null ? bound : high[id].min(bound);
                    }
                } else if (a.signum() < 0) {
                    BigInteger bound = floor(offset.subtract(sharedLower[i]), a.negate());
                    high[id] = high[id] == null ? bound : high[id].min(bound);
                    if (sharedUpper[i] != null) low[id] = low[id].max(ceil(offset.subtract(sharedUpper[i]), a.negate()));
                }
            }
        }
        return new Domains(low, high, factVersion);
    }

    long boundVersion() {
        return factVersion;
    }

    /** Independently justify original and destination coordinates before importing a root bound. */
    int importProofBounds(View view, long after, CountLcg solver) {
        if (budget.proofJournal() == null || sharedLower == null || after >= factVersion || !sharesBounds(view) ||
                original.lower.length > 1024 || view.lower.length > 1024 || original.rows.size() > 2048 || view.rows.size() > 2048 ||
                Arrays.stream(original.lower).anyMatch(value -> value.signum() < 0) ||
                Arrays.stream(view.lower).anyMatch(value -> value.signum() < 0))
            return 0;
        long started = budget.threadWork(), allowance = Math.min(32768, budget.remainingWork() / 32);
        long terms = original.shape.terms + view.shape.terms + original.rows.size() + view.rows.size() +
                2L * (original.lower.length + view.lower.length);
        if (view.substitution != null) {
            terms += view.substitution.coordinates().size();
            for (var expression : view.substitution.coordinates()) terms += expression.terms().size();
        }
        // Include certificate scratch even if the archive declines admission.
        // Large/expensive implications remain optional and leave the ordinary
        // solver and its unmodified domain available.
        long bytes = 1024L + 256L * terms;
        if (terms >= allowance || bytes > budget.availableBytes() / 8 || !budget.tryReserve(bytes)) return 0;
        try {
            budget.charge(terms);
            var originalAxioms = CountAffineProof.axioms(original);
            var targetAxioms = CountAffineProof.axioms(view);
            var targetMapping = CountAffineProof.mapping(view);
            int imported = 0;
            for (int i = 0; i < original.lower.length && imported < 16; i++) {
                for (int side = 0; side < 2; side++) {
                    boolean minimum = side == 0;
                    long left = allowance - (budget.threadWork() - started);
                    if (left <= 0) return imported;
                    budget.check();
                    BigInteger value = minimum ? sharedLower[i] : sharedUpper[i];
                    BigInteger baseline = minimum ? original.lower[i] : original.upper[i];
                    if (value == null || baseline != null && (minimum ? value.compareTo(baseline) <= 0 : value.compareTo(baseline) >= 0)) continue;
                    var row = new ExactLinearProgram.Constraint(Map.of(i, minimum ? BigInteger.ONE.negate() : BigInteger.ONE), minimum ? value.negate() : value);
                    var target = view.substitution == null ? row : view.substitution.row(row, budget);
                    target = CountReduction.normalize(target);
                    if (target.terms().size() != 1) continue;
                    var term = target.terms().entrySet().iterator().next();
                    int id = term.getKey();
                    boolean lowerBound = term.getValue().signum() < 0;
                    BigInteger endpoint = lowerBound ? target.upper().negate() : target.upper();
                    if (lowerBound ? endpoint.compareTo(view.lower[id]) <= 0 : view.upper[id] != null && endpoint.compareTo(view.upper[id]) >= 0) continue;
                    var originalClause = List.of(CountAffineProof.opposite(CountProof.row(row)));
                    var targetClause = List.of(CountAffineProof.opposite(CountProof.row(target)));
                    var proof = new CountAffineConflictProof.Certificate(original.lower.length, originalAxioms,
                            CountAffineConflictProof.clause(original.lower.length, originalAxioms, originalClause), List.of(),
                            originalClause, view.lower.length, targetAxioms, targetMapping, targetClause);
                    left = allowance - (budget.threadWork() - started);
                    if (left <= 0) return imported;
                    if (CountAffineConflictProof.verify(proof, left, budget::charge) == CountProof.Verdict.VERIFIED &&
                            budget.proofJournal().add(proof) && solver.learn(target))
                        imported++;
                }
            }
            return imported;
        } finally {
            budget.release(bytes);
        }
    }

    /** Conditional facts remain guarded by the exporting engine's initial domains. */
    void publishConflicts(View view, Domains scope, List<CountConflict> learned, int from, Object origin) {
        if (from >= learned.size() || !sharesBounds(view)) return;
        if (conflicts == null) conflicts = CountViewConflicts.create(budget, original);
        if (conflicts != null) conflicts.publish(view, scope.lower, scope.upper, learned, from, origin);
    }

    int conflictVersion() {
        return conflicts == null ? 0 : conflicts.version();
    }

    int importConflicts(View view, int after, Object origin, CountLcg solver) {
        if (conflicts == null || after >= conflicts.version() || !sharesBounds(view)) return 0;
        return conflicts.transfer(view, after, origin, solver::learn);
    }

    int importConflicts(View view, int after, Object origin, CountCdcl solver) {
        if (conflicts == null || after >= conflicts.version() || !sharesBounds(view)) return 0;
        return conflicts.transfer(view, after, origin, solver::learn);
    }

    private static BigInteger floor(BigInteger n, BigInteger positive) {
        var qr = n.divideAndRemainder(positive);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }

    private static BigInteger ceil(BigInteger n, BigInteger positive) {
        return floor(n.negate(), positive).negate();
    }

    BigInteger[] restoreAndCheck(View view, BigInteger[] counts) {
        try (var costs = budget.trace(PlanningCostTrace.Stage.RESTORE)) {
            return restoreAndCheckMeasured(view, counts);
        }
    }

    private BigInteger[] restoreAndCheckMeasured(View view, BigInteger[] counts) {
        if (counts == null) return null;
        BigInteger[] result = view.restore(counts);
        if (result == null || result.length != original.lower.length) return invalidCandidate(view, "Unmapped count model");
        for (int i = 0; i < result.length; i++) {
            budget.check();
            if (result[i].compareTo(original.lower[i]) < 0 || original.upper[i] != null && result[i].compareTo(original.upper[i]) > 0)
                return invalidCandidate(view, "Restored counts violate original domain");
        }
        for (var row : original.rows) {
            BigInteger total = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.check();
                total = total.add(term.getValue().multiply(result[term.getKey()]));
            }
            if (total.compareTo(row.upper()) > 0) return invalidCandidate(view, "Restored counts violate original constraint");
        }
        return result;
    }

    private BigInteger[] invalidCandidate(View view, String reason) {
        if (view.semantics == Semantics.EQUIVALENT) throw new IllegalStateException(reason);
        return null;
    }

    private static Shape shape(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, PlanningBudget budget) {
        long terms = 0;
        int bits = 1, unfixed = 0;
        for (var row : rows) for (BigInteger coefficient : row.terms().values()) {
            budget.check();
            terms++;
            bits = Math.max(bits, coefficient.bitLength());
        }
        for (int i = 0; i < low.length; i++) if (!low[i].equals(high[i])) unfixed++;
        return new Shape(low.length, terms, bits, unfixed);
    }

    @Override
    public void close() {
        if (cuts != null) cuts.close();
        cuts = null;
        if (conflicts != null) conflicts.close();
        conflicts = null;
        views.clear();
        auxiliaryViews.clear();
        sharedLower = sharedUpper = null;
        budget.release(memory);
        memory = 0;
    }
}
