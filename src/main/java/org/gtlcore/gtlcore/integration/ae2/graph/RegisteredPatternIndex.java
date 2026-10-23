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
    private final PatternIndexMap<AEKey, List<IPatternDetails>> primary = new PatternIndexMap<>(), secondary = new PatternIndexMap<>();
    private final PatternIndexMap<Object, List<IPatternDetails>> primaryFamilies = new PatternIndexMap<>(), allFamilies = new PatternIndexMap<>();
    private final Map<AEKey, int[]> references = new LinkedHashMap<>();
    private final PatternIndexMap<AEKey, Boolean> candidates = new PatternIndexMap<>();
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
        if (removals.isEmpty() && view != null) return view;
        // A whole-grid unmount need not remove thousands of entries one by one.
        // Old views own their roots and remain valid after these owners reset.
        if (!declarations.isEmpty() && removals.size() >= declarations.size() && removals.containsAll(declarations.keySet())) {
            declarations.clear();
            primary.clear();
            secondary.clear();
            primaryFamilies.clear();
            allFamilies.clear();
            references.clear();
            candidates.clear();
            retainedBytes = 0;
            invalidate();
        }
        var batch = new RemovalBatch();
        for (var pattern : removals) {
            Declaration previous = declarations.get(pattern);
            if (previous != null) {
                invalidate();
                declarations.remove(pattern);
                update(previous, false, batch);
                retainedBytes -= previous.bytes();
            }
            if (oversized.remove(pattern)) invalidate();
        }
        batch.primary.apply(primary);
        batch.secondary.apply(secondary);
        batch.primaryFamilies.apply(primaryFamilies);
        batch.allFamilies.apply(allFamilies);
        removals.clear();
        if (!oversized.isEmpty()) throw new PlanningBudget.Exhausted(PlanningBudget.Limit.GRAPH_LIMIT, "byproduct_index_size");
        if (view == null) view = new ByproductPatternIndex(primary.snapshot(), secondary.snapshot(),
                primaryFamilies.snapshot(), allFamilies.snapshot(), candidates.snapshot());
        return view;
    }

    private void invalidate() {
        view = null;
    }

    private record Declaration(IPatternDetails pattern, AEKey primary, List<AEKey> outputs, long bytes) {}

    private void update(Declaration declaration, boolean add) {
        update(declaration, add, null);
    }

    private void update(Declaration declaration, boolean add, RemovalBatch batch) {
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
            if (families.add(family)) {
                if (batch == null) update(allFamilies, family, declaration.pattern(), add);
                else batch.allFamilies.add(family, declaration.pattern());
            }
            if (primary) {
                if (batch == null) {
                    update(this.primary, key, declaration.pattern(), add);
                    update(primaryFamilies, family, declaration.pattern(), add);
                } else {
                    batch.primary.add(key, declaration.pattern());
                    batch.primaryFamilies.add(family, declaration.pattern());
                }
            } else if (batch == null) update(secondary, key, declaration.pattern(), add);
            else batch.secondary.add(key, declaration.pattern());
        }
    }

    private static final class RemovalBatch {

        final Removals<AEKey> primary = new Removals<>(), secondary = new Removals<>();
        final Removals<Object> primaryFamilies = new Removals<>(), allFamilies = new Removals<>();
    }

    private static final class Removals<K> {

        final Map<K, Set<IPatternDetails>> removed = new LinkedHashMap<>();

        void add(K key, IPatternDetails pattern) {
            removed.computeIfAbsent(key, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(pattern);
        }

        void apply(PatternIndexMap<K, List<IPatternDetails>> index) {
            removed.forEach((key, patterns) -> {
                List<IPatternDetails> previous = index.get(key);
                if (previous == null) return;
                var values = new ArrayList<IPatternDetails>();
                for (var pattern : previous) if (!patterns.contains(pattern)) values.add(pattern);
                if (values.isEmpty()) index.remove(key);
                else index.put(key, List.copyOf(values));
            });
        }
    }

    private static <K> void update(PatternIndexMap<K, List<IPatternDetails>> index, K key, IPatternDetails pattern, boolean add) {
        List<IPatternDetails> values = new ArrayList<>(Objects.requireNonNullElse(index.get(key), List.of()));
        if (add) values.add(pattern);
        else values.removeIf(value -> value == pattern);
        if (values.isEmpty()) index.remove(key);
        else index.put(key, List.copyOf(values));
    }
}
