package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Predicate;

/** Small exact linear consequences, certified before crossing a model's coordinate boundary. */
final class CountViewCuts implements AutoCloseable {

    private static final int MAX_FACTS = 64, MAX_TERMS = 64, MAX_BITS = 2048;

    private record Fact(Object origin, ExactLinearProgram.Constraint row, CountProof.Derivation source, List<CountProof.Row> mapping) {}

    private record Coordinate(int original, BigInteger scale, BigInteger offset) {}

    private final PlanningBudget budget;
    private final CountModelViews.View original;
    private final List<Fact> facts = new ArrayList<>();
    private final Set<ExactLinearProgram.Constraint> known = new HashSet<>();
    private final Map<CountModelViews.View, Coordinate[]> lifts = new IdentityHashMap<>();
    private long memory;
    private long started, allowance;

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private void start() {
        started = budget.threadWork();
        allowance = Math.min(32768, budget.remainingWork() / 32);
    }

    private void check() {
        if (budget.threadWork() - started >= allowance) throw new Stop();
        budget.check();
    }

    private CountViewCuts(PlanningBudget budget, long bytes, CountModelViews.View original) {
        this.budget = budget;
        memory = bytes;
        this.original = original;
    }

    static CountViewCuts create(PlanningBudget budget) {
        return create(budget, null);
    }

    static CountViewCuts create(PlanningBudget budget, CountModelViews.View original) {
        long bytes = 262144; // Bounded certificate accumulation and affine lift scratch.
        return bytes <= budget.availableBytes() / 8 && budget.tryReserve(bytes) ? new CountViewCuts(budget, bytes, original) : null;
    }

    int version() {
        return facts.size();
    }

    void publish(CountModelViews.View view, List<CountLpLearning.Cut> cuts, int from, Object origin) {
        start();
        try {
            publishChecked(view, cuts, from, origin);
        } catch (Stop ignored) {
            // Optional sharing is bounded independently of the main search.
        }
    }

    private void publishChecked(CountModelViews.View view, List<CountLpLearning.Cut> cuts, int from, Object origin) {
        if (view.semantics() != CountModelViews.Semantics.EQUIVALENT ||
                view.substitution() != null && !view.substitution().unconditional())
            return;
        for (int i = from; i < cuts.size() && facts.size() < MAX_FACTS; i++) {
            check();
            var cut = cuts.get(i);
            if (!certified(view.rows(), cut)) continue;
            var row = view.substitution() == null ? cut.row() : lift(view, cut.row());
            if (row == null || !bounded(row)) continue;
            row = CountReduction.normalize(row);
            if (row.terms().isEmpty() && row.upper().signum() >= 0 || known.contains(row)) continue;
            long bytes = 256L + 512L * row.terms().size() + (row.upper().bitLength() + 7L) / 8;
            if (budget.proofJournal() != null) bytes += 512L + 192L * view.rows().size() + 384L * view.shape().terms() +
                    (view.substitution() == null ? 0 : 512L * view.substitution().coordinates().size());
            if (bytes > budget.availableBytes() / 8 || !budget.tryReserve(bytes)) break;
            memory += bytes;
            known.add(row);
            CountProof.Derivation proof = budget.proofJournal() == null ? null : new CountProof.Derivation("affine_cut:" + view.name(),
                    view.lower().length, view.rows().stream().map(CountProof::row).toList(),
                    List.of(new CountProof.Combination(cut.parents(), cut.divisor(), CountProof.row(cut.row()))));
            facts.add(new Fact(origin, row, proof, proof == null ? List.of() : CountAffineProof.mapping(view)));
        }
    }

    /** The proposal is not trusted: independently rebuild its nonnegative integer combination. */
    private boolean certified(List<ExactLinearProgram.Constraint> source, CountLpLearning.Cut cut) {
        if (cut.parents().isEmpty() || cut.parents().size() > 128 || cut.divisor().signum() <= 0 ||
                cut.divisor().bitLength() > MAX_BITS || !bounded(cut.row()))
            return false;
        Map<Integer, BigInteger> sum = new TreeMap<>();
        BigInteger upper = BigInteger.ZERO;
        for (var parent : cut.parents().entrySet()) {
            check();
            int id = parent.getKey();
            BigInteger weight = parent.getValue();
            if (id < 0 || id >= source.size() || weight.signum() < 0 || weight.bitLength() > MAX_BITS) return false;
            var row = source.get(id);
            if (!bounded(row)) return false;
            upper = upper.add(row.upper().multiply(weight));
            if (upper.bitLength() > MAX_BITS) return false;
            for (var term : row.terms().entrySet()) {
                check();
                BigInteger value = sum.getOrDefault(term.getKey(), BigInteger.ZERO).add(term.getValue().multiply(weight));
                if (value.bitLength() > MAX_BITS) return false;
                if (value.signum() == 0) sum.remove(term.getKey());
                else sum.put(term.getKey(), value);
                if (sum.size() > 2 * MAX_TERMS) return false;
            }
        }
        for (var term : sum.entrySet()) {
            check();
            var qr = term.getValue().divideAndRemainder(cut.divisor());
            if (qr[1].signum() != 0) return false;
            term.setValue(qr[0]);
        }
        return sum.equals(cut.row().terms()) && floor(upper, cut.divisor()).equals(cut.row().upper());
    }

    /** Clear all denominators with a positive multiplier; negative affine scales retain their sign. */
    private ExactLinearProgram.Constraint lift(CountModelViews.View view, ExactLinearProgram.Constraint row) {
        Coordinate[] coordinates = lifts.get(view);
        if (coordinates == null) {
            long bytes = 256L + 96L * view.lower().length;
            if (!budget.tryReserve(bytes)) return null;
            memory += bytes;
            coordinates = new Coordinate[view.lower().length];
            var expressions = view.substitution().coordinates();
            for (int i = 0; i < expressions.size(); i++) {
                check();
                var expression = expressions.get(i);
                if (expression.terms().size() != 1 || expression.constant().bitLength() > MAX_BITS) continue;
                var term = expression.terms().entrySet().iterator().next();
                int id = term.getKey();
                if (id < 0 || id >= coordinates.length || term.getValue().signum() == 0 || term.getValue().bitLength() > MAX_BITS) continue;
                if (coordinates[id] == null || term.getValue().abs().compareTo(coordinates[id].scale.abs()) < 0)
                    coordinates[id] = new Coordinate(i, term.getValue(), expression.constant());
            }
            lifts.put(view, coordinates);
        }
        BigInteger multiplier = BigInteger.ONE;
        for (int id : row.terms().keySet()) {
            check();
            if (id < 0 || id >= coordinates.length || coordinates[id] == null) return null;
            BigInteger divisor = coordinates[id].scale.abs();
            multiplier = multiplier.divide(multiplier.gcd(divisor)).multiply(divisor);
            if (multiplier.bitLength() > MAX_BITS) return null;
        }
        BigInteger upper = row.upper().multiply(multiplier);
        Map<Integer, BigInteger> terms = new TreeMap<>();
        for (var term : row.terms().entrySet()) {
            check();
            Coordinate c = coordinates[term.getKey()];
            BigInteger coefficient = term.getValue().multiply(multiplier.divide(c.scale));
            upper = upper.add(coefficient.multiply(c.offset));
            terms.merge(c.original, coefficient, BigInteger::add);
        }
        return new ExactLinearProgram.Constraint(terms, upper);
    }

    int transfer(CountModelViews.View view, int after, Object consumer, Predicate<ExactLinearProgram.Constraint> accept) {
        int imported = 0;
        start();
        try {
            for (int i = after; i < facts.size(); i++) {
                check();
                Fact fact = facts.get(i);
                if (fact.origin == consumer) continue;
                var row = view.substitution() == null ? fact.row : view.substitution().row(fact.row, budget);
                if (!bounded(row)) continue;
                row = CountReduction.normalize(row);
                if (budget.proofJournal() != null) {
                    if (original == null || fact.source == null || Arrays.stream(original.lower()).anyMatch(v -> v.signum() < 0) ||
                            Arrays.stream(view.lower()).anyMatch(v -> v.signum() < 0))
                        continue;
                    var proof = new CountAffineProof.Certificate(original.lower().length, CountAffineProof.axioms(original),
                            fact.source, fact.mapping, CountProof.row(fact.row), view.lower().length,
                            CountAffineProof.axioms(view), CountAffineProof.mapping(view), CountProof.row(row));
                    long left = Math.max(0, allowance - (budget.threadWork() - started));
                    if (CountAffineProof.verify(proof, left, budget::charge) != CountProof.Verdict.VERIFIED || !budget.proofJournal().add(proof)) continue;
                }
                if (accept.test(row)) imported++;
            }
        } catch (Stop ignored) {
            // Unimported facts remain optional; no search conclusion is made.
        }
        return imported;
    }

    private boolean bounded(ExactLinearProgram.Constraint row) {
        if (row.terms().size() > MAX_TERMS || row.upper().bitLength() > MAX_BITS) return false;
        for (var coefficient : row.terms().values()) {
            check();
            if (coefficient.bitLength() > MAX_BITS) return false;
        }
        return true;
    }

    private static BigInteger floor(BigInteger value, BigInteger divisor) {
        var qr = value.divideAndRemainder(divisor);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }

    @Override
    public void close() {
        facts.clear();
        known.clear();
        lifts.clear();
        budget.release(memory);
        memory = 0;
    }
}
