package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Predicate;

/** Short, guarded nogoods owned by one immutable count model, never by a catalog or JVM session. */
final class CountViewConflicts implements AutoCloseable {

    private static final int MAX_FACTS = 64, MAX_ATOMS = 16, MAX_BITS = 1024;

    private record Fact(Object origin, CountConflict conflict) {}

    /** x[original] = scale * y + offset; reversing a unary bound needs no division. */
    private record Coordinate(int original, BigInteger scale, BigInteger offset) {}

    private final PlanningBudget budget;
    private final List<Fact> facts = new ArrayList<>();
    private final Set<CountConflict> known = new HashSet<>();
    private final Map<CountModelViews.View, Coordinate[]> lifts = new IdentityHashMap<>();
    private long memory;

    private CountViewConflicts(PlanningBudget budget, long bytes) {
        this.budget = budget;
        memory = bytes;
    }

    static CountViewConflicts create(PlanningBudget budget) {
        // Includes transient mapping/normalization of one short conjunction.
        long bytes = 32768;
        return bytes <= budget.availableBytes() / 8 && budget.tryReserve(bytes) ? new CountViewConflicts(budget, bytes) : null;
    }

    int version() {
        return facts.size();
    }

    /** The caller must establish view ownership and that the engine uses exactly view.rows(). */
    void publish(CountModelViews.View view, BigInteger[] lower, BigInteger[] upper,
                 List<CountConflict> learned, int from, Object origin) {
        if (facts.size() >= MAX_FACTS || from >= learned.size() || lower.length != view.lower().length || upper.length != lower.length) return;
        List<ExactLinearProgram.Constraint> guards = new ArrayList<>();
        for (int i = 0; i < lower.length; i++) {
            budget.check();
            if (lower[i].compareTo(view.lower()[i]) > 0)
                guards.add(bound(i, true, lower[i]));
            if (upper[i] != null && (view.upper()[i] == null || upper[i].compareTo(view.upper()[i]) < 0))
                guards.add(bound(i, false, upper[i]));
            if (guards.size() > MAX_ATOMS) return;
        }
        Coordinate[] lift = view.substitution() == null ? null : lift(view);
        if (view.substitution() != null && lift == null) return;
        int before = facts.size();
        for (int k = from; k < learned.size() && facts.size() < MAX_FACTS; k++) {
            budget.check();
            var assumptions = learned.get(k).assumptions();
            if (assumptions.size() + guards.size() > MAX_ATOMS) continue;
            var guarded = new ArrayList<>(guards);
            guarded.addAll(assumptions);
            CountConflict value = canonical(guarded, lower.length);
            if (value == null) continue;
            if (lift != null) {
                List<ExactLinearProgram.Constraint> original = new ArrayList<>();
                boolean supported = true;
                for (var row : value.assumptions()) {
                    budget.check();
                    var term = row.terms().entrySet().iterator().next();
                    Coordinate coordinate = lift[term.getKey()];
                    if (coordinate == null) {
                        supported = false;
                        break;
                    }
                    BigInteger sign = term.getValue().multiply(BigInteger.valueOf(coordinate.scale.signum()));
                    BigInteger rhs = coordinate.scale.abs().multiply(row.upper()).add(sign.multiply(coordinate.offset));
                    original.add(new ExactLinearProgram.Constraint(Map.of(coordinate.original, sign), rhs));
                }
                if (!supported) continue;
                value = canonical(original, view.substitution().coordinates().size());
            }
            if (value == null || known.contains(value)) continue;
            long bytes = 256L + 512L * value.assumptions().size();
            // Optional knowledge must leave workspace for the active engine.
            if (bytes > budget.availableBytes() / 8 || !budget.tryReserve(bytes)) break;
            memory += bytes;
            facts.add(new Fact(origin, value));
            known.add(value);
        }
        if (facts.size() > before)
            budget.note("count_view_conflicts", "origin=" + view.name() + "; published=" + (facts.size() - before) +
                    "; guards=" + guards.size() + "; version=" + facts.size() + "; scope=owned_count_model");
    }

    int transfer(CountModelViews.View view, int after, Object consumer, Predicate<CountConflict> accept) {
        int imported = 0;
        for (int i = after; i < facts.size(); i++) {
            budget.check();
            Fact fact = facts.get(i);
            if (fact.origin == consumer) continue;
            CountConflict value = view.substitution() == null ? fact.conflict : view.substitution().conflict(fact.conflict, budget);
            value = canonical(value.assumptions(), view.lower().length);
            if (value != null && accept.test(value)) imported++;
        }
        return imported;
    }

    private Coordinate[] lift(CountModelViews.View view) {
        Coordinate[] result = lifts.get(view);
        if (result != null) return result;
        long bytes = 256L + 96L * view.lower().length;
        if (!budget.tryReserve(bytes)) return null;
        memory += bytes;
        result = new Coordinate[view.lower().length];
        var expressions = view.substitution().coordinates();
        for (int i = 0; i < expressions.size(); i++) {
            budget.check();
            var expression = expressions.get(i);
            if (expression.terms().size() != 1 || expression.constant().bitLength() > MAX_BITS) continue;
            var term = expression.terms().entrySet().iterator().next();
            int id = term.getKey();
            BigInteger scale = term.getValue();
            if (scale.signum() == 0 || scale.bitLength() > MAX_BITS) continue;
            if (result[id] == null || scale.abs().compareTo(result[id].scale.abs()) < 0)
                result[id] = new Coordinate(i, scale, expression.constant());
        }
        lifts.put(view, result);
        return result;
    }

    /** null means unsupported or an impossible conjunction, neither of which excludes any solution. */
    private CountConflict canonical(List<ExactLinearProgram.Constraint> assumptions, int variables) {
        if (assumptions.size() > MAX_ATOMS) return null;
        Map<Integer, ExactLinearProgram.Constraint> rows = new TreeMap<>();
        for (var input : assumptions) {
            budget.check();
            if (input.terms().size() > 1 || input.upper().bitLength() > MAX_BITS) return null;
            if (input.terms().values().stream().anyMatch(value -> value.bitLength() > MAX_BITS)) return null;
            var row = CountReduction.normalize(input);
            if (row.terms().isEmpty()) {
                if (row.upper().signum() < 0) return null;
                continue;
            }
            var term = row.terms().entrySet().iterator().next();
            int id = term.getKey();
            if (id < 0 || id >= variables) return null;
            int key = 2 * id + (term.getValue().signum() > 0 ? 1 : 0);
            var old = rows.get(key);
            if (old == null || row.upper().compareTo(old.upper()) < 0) rows.put(key, row);
        }
        for (var entry : rows.entrySet()) if ((entry.getKey() & 1) == 0) {
            var upper = rows.get(entry.getKey() + 1);
            if (upper != null && entry.getValue().upper().negate().compareTo(upper.upper()) > 0) return null;
        }
        return new CountConflict(new ArrayList<>(rows.values()));
    }

    private static ExactLinearProgram.Constraint bound(int variable, boolean minimum, BigInteger value) {
        return new ExactLinearProgram.Constraint(Map.of(variable, minimum ? BigInteger.ONE.negate() : BigInteger.ONE), minimum ? value.negate() : value);
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
