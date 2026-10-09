package org.gtlcore.gtlcore.integration.ae2.graph;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import org.cgse.core.PlanningBudget;

import java.util.*;

/** Only declared secondary outputs are discoverable; execution still owns the complete recipe. */
final class ByproductPatternIndex {

    private final Map<AEKey, List<IPatternDetails>> outputs;

    private ByproductPatternIndex(Map<AEKey, List<IPatternDetails>> outputs) {
        this.outputs = Collections.unmodifiableMap(outputs);
    }

    List<IPatternDetails> sources(AEKey key, Collection<IPatternDetails> primary) {
        var secondary = outputs.getOrDefault(key, List.of());
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
        private final Set<IPatternDetails> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        private Iterator<Map.Entry<AEKey, List<IPatternDetails>>> freezing;
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
                for (var output : values) {
                    budget.compilationCheck();
                    if (output.amount() <= 0 || output.what().equals(primary) || !keys.add(output.what())) continue;
                    budget.reserve(96);
                    outputs.computeIfAbsent(output.what(), ignored -> new ArrayList<>()).add(pattern);
                }
                return false;
            }
            if (freezing == null) freezing = outputs.entrySet().iterator();
            if (freezing.hasNext()) {
                var entry = freezing.next();
                entry.setValue(List.copyOf(entry.getValue()));
                return false;
            }
            result = new ByproductPatternIndex(outputs);
            budget.note("byproduct_index", "patterns=" + seen.size() + "; secondary_keys=" + outputs.size());
            return true;
        }

        ByproductPatternIndex result() {
            return Objects.requireNonNull(result, "Byproduct index incomplete");
        }
    }
}
