package org.gtlcore.gtlcore.integration.ae2.graph;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;
import org.cgse.core.PlanningBudget;

import java.util.*;

/** Server-thread output index, maintained alongside AE's effective pattern registry. */
public final class RegisteredPatternIndex {

    private static final long MAX_INDEX_BYTES = 64L << 20;

    private final Set<IPatternDetails> removals = new LinkedHashSet<>(), oversized = new HashSet<>();
    private final Map<IPatternDetails, Declaration> declarations = new HashMap<>();
    private final Map<AEKey, List<IPatternDetails>> secondary = new HashMap<>();
    private final Map<Object, List<IPatternDetails>> primaryFamilies = new HashMap<>(), allFamilies = new HashMap<>();
    private final Map<AEKey, int[]> references = new LinkedHashMap<>();
    private final Map<AEKey, Boolean> candidates = new LinkedHashMap<>();
    private long generation;
    private long retainedBytes;
    private ByproductPatternIndex view;

    /** Coalesce unmount/remount within a provider refresh, including equal pattern objects. */
    public void changed(IPatternDetails pattern, boolean present) {
        if (!present) {
            removals.add(pattern);
            return;
        }
        boolean remounted = removals.remove(pattern);
        Declaration previous = declarations.get(pattern);
        if (previous != null) {
            // Processing outputs are immutable values encoded in the pattern.
            // Other integrations may keep an equal definition while changing
            // their declared outputs; refresh those at the actual remount.
            if (!remounted || pattern.getClass() == AEProcessingPattern.class) return;
            invalidate();
            declarations.remove(pattern);
            update(previous, false);
            retainedBytes -= previous.bytes();
        }
        if (oversized.contains(pattern)) return;
        invalidate();
        var outputs = pattern.getOutputs();
        // Registration must never abort an AE provider update. Reject planning
        // from an oversized registry at the request boundary instead.
        long weight = 256L + 384L * outputs.length;
        if (declarations.size() >= 100_000 || outputs.length > 256 || weight > MAX_INDEX_BYTES - retainedBytes) {
            oversized.add(pattern);
            return;
        }
        Set<AEKey> keys = new LinkedHashSet<>();
        for (GenericStack output : outputs) if (output.amount() > 0) keys.add(output.what());
        Declaration declaration = new Declaration(pattern, pattern.getPrimaryOutput().what(), List.copyOf(keys), weight);
        declarations.put(pattern, declaration);
        retainedBytes += weight;
        update(declaration, true);
    }

    public ByproductPatternIndex view() {
        for (var pattern : removals) {
            Declaration previous = declarations.get(pattern);
            if (previous != null) {
                invalidate();
                declarations.remove(pattern);
                update(previous, false);
                retainedBytes -= previous.bytes();
            }
            if (oversized.remove(pattern)) invalidate();
        }
        removals.clear();
        if (!oversized.isEmpty()) throw new PlanningBudget.Exhausted(PlanningBudget.Limit.GRAPH_LIMIT, "byproduct_index_size");
        if (view == null) {
            long version = generation;
            view = new ByproductPatternIndex(secondary, primaryFamilies, allFamilies, candidates, () -> {
                if (generation != version) throw new IllegalStateException("GRAPH_PATTERN_CHANGED_DURING_SNAPSHOT");
            });
        }
        return view;
    }

    private void invalidate() {
        generation++;
        view = null;
    }

    private record Declaration(IPatternDetails pattern, AEKey primary, List<AEKey> outputs, long bytes) {}

    private void update(Declaration declaration, boolean add) {
        Set<Object> families = new HashSet<>();
        for (AEKey key : declaration.outputs()) {
            boolean primary = key.equals(declaration.primary());
            int[] counts = references.computeIfAbsent(key, ignored -> new int[2]);
            counts[0] += add ? 1 : -1;
            if (primary) counts[1] += add ? 1 : -1;
            if (counts[0] == 0) {
                references.remove(key);
                candidates.remove(key);
            } else candidates.put(key, counts[1] > 0);
            Object family = key.getPrimaryKey();
            if (families.add(family)) update(allFamilies, family, declaration.pattern(), add);
            if (primary) update(primaryFamilies, family, declaration.pattern(), add);
            else update(secondary, key, declaration.pattern(), add);
        }
    }

    private static <K> void update(Map<K, List<IPatternDetails>> index, K key, IPatternDetails pattern, boolean add) {
        List<IPatternDetails> values = index.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (add) values.add(pattern);
        else values.removeIf(value -> value == pattern);
        if (values.isEmpty()) index.remove(key);
    }
}
