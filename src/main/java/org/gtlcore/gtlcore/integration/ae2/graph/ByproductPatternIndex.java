package org.gtlcore.gtlcore.integration.ae2.graph;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import org.cgse.core.PlanningBudget;

import java.util.*;

/** Declared output identities support candidate discovery, never synthetic stock. */
final class ByproductPatternIndex {

    private final Map<AEKey, List<IPatternDetails>> outputs;
    private final Map<Object, List<IPatternDetails>> fuzzyPrimary, fuzzyAll;
    private final Map<AEKey, Boolean> candidates;

    private ByproductPatternIndex(Map<AEKey, List<IPatternDetails>> outputs,
                                  Map<Object, List<IPatternDetails>> fuzzyPrimary,
                                  Map<Object, List<IPatternDetails>> fuzzyAll, Map<AEKey, Boolean> candidates) {
        this.outputs = Collections.unmodifiableMap(outputs);
        this.fuzzyPrimary = Collections.unmodifiableMap(fuzzyPrimary);
        this.fuzzyAll = Collections.unmodifiableMap(fuzzyAll);
        this.candidates = Collections.unmodifiableMap(candidates);
    }

    Iterator<AEKey> candidates(boolean includeSecondary) {
        return candidates.entrySet().stream().filter(entry -> includeSecondary || entry.getValue())
                .map(Map.Entry::getKey).iterator();
    }

    List<IPatternDetails> fuzzySources(AEKey key, Collection<IPatternDetails> primary, boolean includeSecondary) {
        return merge(primary, (includeSecondary ? fuzzyAll : fuzzyPrimary).getOrDefault(key.getPrimaryKey(), List.of()));
    }

    List<IPatternDetails> sources(AEKey key, Collection<IPatternDetails> primary) {
        return merge(primary, outputs.getOrDefault(key, List.of()));
    }

    private static List<IPatternDetails> merge(Collection<IPatternDetails> primary, List<IPatternDetails> secondary) {
        if (secondary.isEmpty()) return List.copyOf(primary);
        // Some integrations already register secondary outputs. Count each
        // effective pattern once, retaining AE's order for primary sources.
        Set<IPatternDetails> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<IPatternDetails> result = new ArrayList<>();
        for (var pattern : primary) if (seen.add(pattern)) result.add(pattern);
        for (var pattern : secondary) if (seen.add(pattern)) result.add(pattern);
        return result;
    }

    static final class Build {

        private final Iterator<IPatternDetails> patterns;
        private final Map<AEKey, List<IPatternDetails>> outputs = new LinkedHashMap<>();
        private final Map<Object, List<IPatternDetails>> fuzzyPrimary = new LinkedHashMap<>(), fuzzyAll = new LinkedHashMap<>();
        private final Map<AEKey, Boolean> candidates = new LinkedHashMap<>();
        private final Set<IPatternDetails> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        private Iterator<Map.Entry<AEKey, List<IPatternDetails>>> freezing;
        private Iterator<Map.Entry<Object, List<IPatternDetails>>> freezingFamilies;
        private int freezePhase;
        private ByproductPatternIndex result;

        Build(Iterable<IPatternDetails> patterns) {
            this.patterns = patterns.iterator();
        }

        boolean step(PlanningBudget budget) {
            budget.compilationCheck();
            if (result != null) return true;
            if (freezing == null && patterns.hasNext()) {
                var pattern = patterns.next();
                if (!seen.add(pattern)) return false;
                if (seen.size() > 100_000) throw new PlanningBudget.Exhausted(PlanningBudget.Limit.GRAPH_LIMIT, "byproduct_index_patterns");
                budget.reserve(64);
                var values = pattern.getOutputs();
                if (values.length > 256) throw new PlanningBudget.Exhausted(PlanningBudget.Limit.GRAPH_LIMIT, "byproduct_index_outputs");
                Set<AEKey> keys = new HashSet<>();
                var primary = pattern.getPrimaryOutput().what();
                Set<Object> primaryKeys = new HashSet<>();
                for (var output : values) {
                    budget.compilationCheck();
                    if (output.amount() <= 0 || !keys.add(output.what())) continue;
                    budget.reserve(96);
                    boolean isPrimary = output.what().equals(primary);
                    candidates.merge(output.what(), isPrimary, (left, right) -> left || right);
                    Object family = output.what().getPrimaryKey();
                    if (primaryKeys.add(family)) fuzzyAll.computeIfAbsent(family, ignored -> new ArrayList<>()).add(pattern);
                    if (isPrimary) fuzzyPrimary.computeIfAbsent(family, ignored -> new ArrayList<>()).add(pattern);
                    else outputs.computeIfAbsent(output.what(), ignored -> new ArrayList<>()).add(pattern);
                }
                return false;
            }
            if (freezing == null) freezing = outputs.entrySet().iterator();
            if (freezing.hasNext()) {
                var entry = freezing.next();
                entry.setValue(List.copyOf(entry.getValue()));
                return false;
            }
            if (freezingFamilies == null) freezingFamilies = fuzzyPrimary.entrySet().iterator();
            if (freezingFamilies.hasNext()) {
                var entry = freezingFamilies.next();
                entry.setValue(List.copyOf(entry.getValue()));
                return false;
            }
            if (freezePhase++ == 0) {
                freezingFamilies = fuzzyAll.entrySet().iterator();
                return false;
            }
            result = new ByproductPatternIndex(outputs, fuzzyPrimary, fuzzyAll, candidates);
            budget.note("byproduct_index", "patterns=" + seen.size() + "; secondary_keys=" + outputs.size());
            return true;
        }

        ByproductPatternIndex result() {
            return Objects.requireNonNull(result, "Byproduct index incomplete");
        }
    }
}
