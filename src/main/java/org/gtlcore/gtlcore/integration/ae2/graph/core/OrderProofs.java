package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** One immutable order's explanations, translated by recipe identity across strategies. */
final class OrderProofs<K> implements AutoCloseable {

    RecipeCountModel<K> model;
    private final PlanningBudget budget;
    private final CountConflictPool conflicts;
    private final List<Map<K, Integer>> sourceCores = new ArrayList<>();
    private final Map<String, Integer> ids = new HashMap<>();
    private long jumps, imported, propagated, memory;
    private long prefixRevision = -1;
    private List<CountConflict> prefixConflicts = List.of();

    OrderProofs(RecipeCountModel<K> model, PlanningBudget budget) {
        this.budget = budget;
        conflicts = new CountConflictPool(budget);
        if (model != null) adopt(model);
    }

    void adopt(RecipeCountModel<K> value) {
        if (model != null) return;
        model = value;
        for (int i = 0; i < model.recipes.size(); i++) ids.put(model.recipes.get(i).id(), i);
    }

    void publish(RecipeCountModel<K> from, Collection<CountConflict> learned) {
        if (!compatible(from)) return;
        List<CountConflict> mapped = translate(learned, from, ids);
        imported += mapped.size();
        conflicts.add(mapped);
    }

    List<CountConflict> forModel(RecipeCountModel<K> to) {
        if (!compatible(to)) return List.of();
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < to.recipes.size(); i++) positions.put(to.recipes.get(i).id(), i);
        return translate(conflicts.snapshot(), model, positions);
    }

    private boolean compatible(RecipeCountModel<K> other) {
        // A smaller recipe catalog, a changed target or newly funded inventory
        // invalidates general count clauses. In particular, bootstrap/preview
        // requests never inherit their parent's proofs.
        return model != null && model.stock.equals(other.stock) && model.goals.equals(other.goals) && model.external.equals(other.external) &&
                model.recipes.size() == other.recipes.size() && new HashSet<>(model.recipes).equals(new HashSet<>(other.recipes));
    }

    private List<CountConflict> translate(Collection<CountConflict> values, RecipeCountModel<K> from, Map<String, Integer> to) {
        List<CountConflict> result = new ArrayList<>();
        for (var conflict : values) {
            List<ExactLinearProgram.Constraint> assumptions = new ArrayList<>();
            for (var row : conflict.assumptions()) {
                Map<Integer, BigInteger> terms = new LinkedHashMap<>();
                for (var term : row.terms().entrySet()) {
                    budget.check();
                    terms.put(to.get(from.recipes.get(term.getKey()).id()), term.getValue());
                }
                assumptions.add(new ExactLinearProgram.Constraint(terms, row.upper()));
            }
            result.add(new CountConflict(assumptions));
        }
        return result;
    }

    CountConflict rejectedSupport(Set<String> allowed) {
        BigInteger[] low = new BigInteger[model.recipes.size()], high = new BigInteger[low.length];
        Arrays.fill(low, BigInteger.ZERO);
        for (int i = 0; i < high.length; i++) if (!allowed.contains(model.recipes.get(i).id())) high[i] = BigInteger.ZERO;
        for (var conflict : conflicts.snapshot()) if (conflict.impliedBy(low, high, budget)) {
            conflicts.used(List.of(conflict));
            return conflict;
        }
        return null;
    }

    boolean rejectedPrefix(Map<String, BigInteger> counts) {
        if (model == null) return false;
        BigInteger[] lower = new BigInteger[model.recipes.size()], upper = new BigInteger[lower.length];
        for (int i = 0; i < lower.length; i++) lower[i] = counts.getOrDefault(model.recipes.get(i).id(), BigInteger.ZERO);
        for (var conflict : prefixConflicts()) if (conflict.impliedBy(lower, upper, budget)) {
            conflicts.used(List.of(conflict));
            return true;
        }
        return false;
    }

    boolean hasCountConflicts() {
        return model != null && !conflicts.isEmpty();
    }

    boolean hasPrefixConflicts() {
        return model != null && !prefixConflicts().isEmpty();
    }

    private List<CountConflict> prefixConflicts() {
        if (prefixRevision != conflicts.revision()) {
            // A prefix has lower counts, but no upper bounds on future runs.
            // An assumption with a positive coefficient cannot be implied.
            // Negating such an unresolved assumption also cannot bound a
            // monotone prefix. Keep it in the general pool, not this hot path.
            prefixConflicts = conflicts.snapshot().stream().filter(conflict -> conflict.assumptions().stream()
                    .allMatch(row -> row.terms().values().stream().allMatch(value -> value.signum() <= 0))).toList();
            prefixRevision = conflicts.revision();
        }
        return prefixConflicts;
    }

    /** Propagate a learned clause before allocation creates a forbidden batch. */
    BigInteger maximumAdditional(Map<String, BigInteger> counts, String recipe, BigInteger maximum) {
        return maximumAdditional(counts, Map.of(recipe, BigInteger.ONE), maximum);
    }

    /** A compiled block uses the same recipe coordinates as every other strategy. */
    BigInteger maximumAdditional(Map<String, BigInteger> counts, Map<String, BigInteger> block, BigInteger maximum) {
        if (!hasPrefixConflicts()) return maximum;
        BigInteger[] lower = new BigInteger[model.recipes.size()], upper = new BigInteger[lower.length];
        for (int i = 0; i < lower.length; i++) lower[i] = counts.getOrDefault(model.recipes.get(i).id(), BigInteger.ZERO);
        for (var conflict : prefixConflicts()) {
            var implication = conflict.propagate(lower, upper, budget);
            if (implication == null) continue;
            if (implication.row() == null) return BigInteger.ZERO;
            var row = implication.row();
            if (row.terms().values().stream().anyMatch(v -> v.signum() < 0)) continue;
            BigInteger coefficient = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.check();
                coefficient = coefficient.add(term.getValue().multiply(block.getOrDefault(model.recipes.get(term.getKey()).id(), BigInteger.ZERO)));
            }
            if (coefficient.signum() <= 0) continue;
            // These are final count constraints. A negative coefficient could
            // be repaired by a later source, so only nonnegative rows bound a
            // monotone execution prefix without upper bounds on its suffix.
            BigInteger minimum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.check();
                minimum = minimum.add(term.getValue().multiply(lower[term.getKey()]));
            }
            BigInteger permitted = row.upper().subtract(minimum).divide(coefficient).max(BigInteger.ZERO);
            if (permitted.compareTo(maximum) < 0) {
                conflicts.used(List.of(conflict));
                propagated++;
                maximum = permitted;
            }
        }
        return maximum;
    }

    void learnSource(Map<K, Integer> core) {
        if (sourceCores.contains(core)) return;
        long bytes = 128L + 64L * core.size();
        if (sourceCores.size() >= 128 || !budget.tryReserve(bytes)) return;
        memory += bytes;
        sourceCores.add(Collections.unmodifiableMap(new LinkedHashMap<>(core)));
    }

    Map<K, Integer> sourceConflict(Map<K, Integer> assignment) {
        return sourceConflict(assignment, false);
    }

    /** A complete source program uses source zero for every omitted choice. */
    Map<K, Integer> sourceConflict(Map<K, Integer> assignment, boolean defaultSources) {
        for (var core : sourceCores) if (core.entrySet().stream().allMatch(e -> e.getValue().equals(defaultSources ? assignment.getOrDefault(e.getKey(), 0) : assignment.get(e.getKey())))) {
            jumps++;
            return core;
        }
        return null;
    }

    @Override
    public void close() {
        budget.note("order_conflicts", "transferred=" + imported + "; source_cores=" + sourceCores.size() + "; backjumps=" + jumps +
                "; allocation_propagations=" + propagated);
        conflicts.close();
        budget.release(memory);
        memory = 0;
        if (model != null) model.close();
    }
}
