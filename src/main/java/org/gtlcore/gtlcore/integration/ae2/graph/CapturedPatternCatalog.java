package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.integration.ae2.graph.core.GraphCompiler;
import org.gtlcore.gtlcore.integration.ae2.graph.core.GraphRecipe;
import org.gtlcore.gtlcore.integration.ae2.graph.core.PlanningBudget;
import org.gtlcore.gtlcore.integration.ae2.graph.core.PlanningScheduler;
import org.gtlcore.gtlcore.integration.ae2.graph.core.PreparedCatalog;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Deferred expansion and encoding. Handles stay opaque: no provider, world or pattern callbacks. */
public final class CapturedPatternCatalog {

    public record Recipe(List<GraphRecipe.Slot<AEKey>> slots, Map<AEKey, Long> outputs) {

        public Recipe {
            slots = List.copyOf(slots);
            outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
        }

        GraphRecipe<AEKey> encode(String binding, PatternFingerprint.Context fingerprints) {
            StringBuilder identity = new StringBuilder(binding);
            for (var slot : slots) identity.append('|').append(slot.inputSlot()).append(':')
                    .append(fingerprints.key(slot.key())).append(':').append(slot.amount());
            return new GraphRecipe<>(fingerprints.hash(identity.toString()), binding, slots, outputs);
        }
    }

    public record Entry(IPatternDetails handle, PatternFingerprint.Values values, int priority, CapturedPattern pattern) {}

    public record Prepared(GraphCompiler<AEKey> compiler, Map<String, IPatternDetails> bindings) {}

    private volatile List<Entry> entries;
    private final int size;
    private volatile Prepared prepared;

    public CapturedPatternCatalog(List<Entry> entries, int size) {
        this.entries = List.copyOf(entries);
        this.size = size;
    }

    public int size() {
        return size;
    }

    public boolean mayContainBinding(String binding) {
        Prepared current = prepared;
        return current == null || current.bindings().containsKey(binding);
    }

    public Build build(PlanningBudget budget) {
        return new Build(budget);
    }

    public final class Build implements PlanningScheduler.Work<Prepared> {

        private final PlanningBudget budget;
        private final List<Entry> capturedEntries;
        private final PatternFingerprint.Context fingerprints = new PatternFingerprint.Context();
        private final Map<String, IPatternDetails> bindings = new LinkedHashMap<>();
        private final Map<String, Integer> priorities = new HashMap<>();
        private final Set<String> seen = new HashSet<>();
        private final List<GraphRecipe<AEKey>> recipes = new ArrayList<>();
        private int cursor;
        private String binding;
        private CapturedPattern.Expansion variants;
        private CompletableFuture<List<List<Encoded>>> parallel;
        private Iterator<List<Encoded>> batches;
        private Iterator<Encoded> encoded;
        private Iterator<GraphRecipe<AEKey>> merging;
        private long parallelActiveNanos;
        private int parallelBatches;
        private PreparedCatalog<AEKey>.Build indexing;
        private Prepared result;

        private Build(PlanningBudget budget) {
            this.budget = budget;
            capturedEntries = entries;
        }

        @Override
        public boolean advance(PlanningScheduler.Slice slice) {
            if (prepared != null) {
                result = prepared;
                return true;
            }
            budget.phase(PlanningBudget.Phase.BUILD);
            while (indexing == null && slice.next()) {
                budget.check();
                if (parallel != null) {
                    if (!parallel.isDone()) return false;
                    batches = parallel.join().iterator();
                    parallel = null;
                }
                if (merging != null && merging.hasNext()) {
                    GraphRecipe<AEKey> recipe = merging.next();
                    if (seen.add(recipe.id())) recipes.add(recipe);
                } else if (encoded != null && encoded.hasNext()) {
                    Encoded entry = encoded.next();
                    bindings.putIfAbsent(entry.binding(), entry.entry().handle());
                    priorities.put(entry.binding(), entry.entry().priority());
                    merging = entry.recipes().iterator();
                    parallelActiveNanos += entry.nanos();
                } else if (batches != null && batches.hasNext()) encoded = batches.next().iterator();
                else if (variants != null && variants.hasNext()) {
                    GraphRecipe<AEKey> recipe = variants.next().encode(binding, fingerprints);
                    if (seen.add(recipe.id())) recipes.add(recipe);
                } else if (cursor < capturedEntries.size()) {
                    if (size >= 512 && slice.parallelism() > 1) {
                        List<Supplier<List<Encoded>>> partitions = new ArrayList<>();
                        // Bounded waves, never a task for every pattern. Workers
                        // own their hash contexts and buffers; only this ordered
                        // continuation changes bindings, priorities and indexes.
                        for (int part = 0; part < slice.parallelism() && cursor < capturedEntries.size(); part++) {
                            int start = cursor, count = 0;
                            while (cursor < capturedEntries.size() && cursor - start < 64) {
                                int next = capturedEntries.get(cursor).pattern().size();
                                if (cursor > start && count + next > 256) break;
                                count += next;
                                cursor++;
                            }
                            int end = cursor;
                            partitions.add(() -> encode(start, end));
                        }
                        parallelBatches += partitions.size();
                        parallel = slice.fork(partitions);
                        return false;
                    }
                    Entry entry = capturedEntries.get(cursor++);
                    binding = fingerprints.of(entry.values());
                    bindings.putIfAbsent(binding, entry.handle());
                    priorities.put(binding, entry.priority());
                    variants = entry.pattern().expand(budget);
                } else indexing = new PreparedCatalog<>(recipes, priorities).build(budget);
            }
            if (indexing == null || !indexing.advance(slice)) return false;
            Prepared complete = new Prepared(indexing.result(), Collections.unmodifiableMap(new LinkedHashMap<>(bindings)));
            synchronized (CapturedPatternCatalog.this) {
                if (prepared == null) {
                    prepared = complete;
                    // The compiled recipes now own their values. Do not retain a
                    // second set of variant maps for the lifetime of this cache.
                    // Active builders keep their own immutable list reference.
                    entries = List.of();
                }
                result = prepared;
            }
            return true;
        }

        private List<Encoded> encode(int start, int end) {
            var context = new PatternFingerprint.Context();
            List<Encoded> result = new ArrayList<>(end - start);
            for (int index = start; index < end; index++) {
                budget.check();
                long began = System.nanoTime();
                Entry entry = capturedEntries.get(index);
                String binding = context.of(entry.values());
                List<GraphRecipe<AEKey>> expanded = new ArrayList<>(entry.pattern().size());
                var expansion = entry.pattern().expand(budget);
                while (expansion.hasNext()) expanded.add(expansion.next().encode(binding, context));
                result.add(new Encoded(entry, binding, expanded, System.nanoTime() - began));
            }
            return result;
        }

        @Override
        public CompletableFuture<?> waitingFor() {
            return parallel;
        }

        public long parallelActiveNanos() {
            return parallelActiveNanos;
        }

        public int parallelBatches() {
            return parallelBatches;
        }

        @Override
        public Prepared result() {
            if (result == null) throw new IllegalStateException("Captured catalog preparation incomplete");
            return result;
        }
    }

    private record Encoded(Entry entry, String binding, List<GraphRecipe<AEKey>> recipes, long nanos) {}
}
