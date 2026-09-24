package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.integration.ae2.crafting.ManualCraftingInventoryLock;
import org.gtlcore.gtlcore.integration.ae2.graph.core.GraphRecipe;
import org.gtlcore.gtlcore.integration.ae2.graph.core.PlanningBudget;

import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.core.AEConfig;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.me.service.CraftingService;

import java.util.*;

/** Snapshot creation and pattern methods run on the server thread, never the solver thread. */
public final class GtlPatternCatalog {

    private static final int MAX_VARIANTS = CapturedPattern.MAX_VARIANTS;

    private record Roots(AEKey target, Set<AEKey> recovery) {}

    public record Signature(PatternFingerprint.Values values, int priority) {}

    private final Map<Roots, Structure> cache = new LinkedHashMap<>(16, 0.75f, true);
    private Object recipeManager;
    private static long dataGeneration;
    private long cachedDataGeneration = -1;
    private long invalidationGeneration;

    /** A failed execution preflight disproves the cached catalog, even without a provider event. */
    public void invalidateBinding(String binding) {
        cache.values().removeIf(structure -> structure.catalog().mayContainBinding(binding));
        invalidationGeneration++;
    }

    public Snapshot capture(IGrid grid, CraftingService service, Level level, IActionSource source, AEKey target, PlanningBudget budget) {
        Capture capture = begin(grid, service, level, source, target, budget);
        while (!capture.step()) { /* Explicit synchronous utility; production uses the tick-budgeted queue. */ }
        return capture.result();
    }

    public static void dataReloaded() {
        dataGeneration++;
    }

    public Capture begin(IGrid grid, CraftingService service, Level level, IActionSource source, AEKey target, PlanningBudget budget) {
        return begin(grid, service, level, source, target, Set.of(), budget);
    }

    public Capture begin(IGrid grid, CraftingService service, Level level, IActionSource source, AEKey target,
                         Set<AEKey> recovery, PlanningBudget budget) {
        return new Capture(grid, service, level, source, new Roots(target, Set.copyOf(recovery)), budget);
    }

    /** World access is split between ticks; each request keeps its frontier and captured bindings. */
    public final class Capture {

        private final IGrid grid;
        private final CraftingService service;
        private final IStorageService storage;
        private final Level level;
        private final IActionSource source;
        private final Roots roots;
        private final PlanningBudget budget;
        private KeyCounter available;
        private long revision;
        private final long dataRevision;
        private final long invalidationRevision;
        private final boolean simulate;
        private final Map<AEKey, List<Signature>> dependencies = new LinkedHashMap<>();
        private final Set<AEKey> seen = new LinkedHashSet<>();
        // IGNORE_ALL fuzzy scans use only the primary key. Cache one representative
        // instead of revisiting thousands of NBT variants on every warm request.
        private final Map<Object, AEKey> templates = new LinkedHashMap<>();
        private final Set<IPatternDetails> seenPatterns = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Deque<AEKey> pending = new ArrayDeque<>();
        private final List<CapturedPatternCatalog.Entry> entries = new ArrayList<>();
        private int recipeCount;
        private final Map<AEKey, Long> stock = new LinkedHashMap<>();
        private final Set<AEKey> emitted = new LinkedHashSet<>(), fuzzy = new HashSet<>();
        private final Set<Object> fuzzyPrimary = new HashSet<>();
        private Structure structure;
        private boolean hit, bounded;
        private int phase;
        private Iterator<AEKey> keys;
        private Iterator<IPatternDetails> patterns;
        private AEKey key;
        private List<Signature> versions;
        private Snapshot result;
        private CandidateCapture normalizing;
        private Iterator<AEKey> normalized;
        private IPatternDetails normalizingPattern;
        private Signature normalizingSignature;

        Capture(IGrid grid, CraftingService service, Level level, IActionSource source, Roots roots, PlanningBudget budget) {
            if (!level.getServer().isSameThread()) throw new IllegalStateException("Graph snapshot requires server thread");
            this.grid = grid;
            this.service = service;
            this.storage = grid.getStorageService();
            this.level = level;
            this.source = source;
            this.roots = roots;
            this.budget = budget;
            available = source == null ? new KeyCounter() : storage.getCachedInventory();
            revision = ((GraphRequestTracker) service).gtlcore$graphProviderGeneration();
            dataRevision = dataGeneration;
            invalidationRevision = invalidationGeneration;
            if (recipeManager != level.getRecipeManager() || cachedDataGeneration != dataRevision) {
                cache.clear();
                recipeManager = level.getRecipeManager();
                cachedDataGeneration = dataRevision;
            }
            simulate = source != null && (AEConfig.instance().isCraftingSimulatedExtraction() ||
                    ManualCraftingInventoryLock.hasReservations(storage.getInventory()));
            structure = cache.get(roots);
            if (structure != null) {
                hit = true;
                if (structure.providerRevision() != revision) keys = structure.resources().iterator();
                else {
                    phase = 2;
                    keys = structure.inputTemplates().iterator();
                }
            } else resetBuild();
        }

        public boolean step() {
            return step(Long.MAX_VALUE);
        }

        public boolean step(long deadline) {
            if (!level.getServer().isSameThread()) throw new IllegalStateException("Graph snapshot escaped server thread");
            budget.check();
            budget.phase(PlanningBudget.Phase.SNAPSHOT);
            switch (phase) {
                case 0 -> { // Revalidate only this target's dependency signatures after a provider edit.
                    if (patterns != null && patterns.hasNext()) {
                        versions.add(signature(patterns.next()));
                        return false;
                    }
                    if (patterns != null) {
                        patterns = null;
                        if (!versions.equals(structure.dependencies().get(key))) {
                            resetBuild();
                            return false;
                        }
                    }
                    if (keys.hasNext()) {
                        key = keys.next();
                        versions = new ArrayList<>();
                        patterns = List.copyOf(service.getCraftingFor(key)).iterator();
                    } else {
                        phase = 2;
                        keys = structure.inputTemplates().iterator();
                    }
                }
                case 1 -> {
                    if (normalizing != null) {
                        if (!normalizing.step()) return false;
                        CapturedPattern captured = normalizing.result();
                        bounded |= captured.bounded();
                        recipeCount += captured.size();
                        if (recipeCount > 100_000) throw new PlanningBudget.Exhausted(PlanningBudget.Limit.GRAPH_LIMIT);
                        entries.add(new CapturedPatternCatalog.Entry(normalizingPattern, normalizingSignature.values(),
                                normalizingSignature.priority(), captured));
                        normalized = captured.dependencies();
                        normalizing = null;
                    }
                    if (normalized != null) {
                        if (normalized.hasNext()) {
                            pending.add(normalized.next());
                            return false;
                        }
                        normalized = null;
                    }
                    if (patterns != null && patterns.hasNext()) {
                        IPatternDetails pattern = patterns.next();
                        Signature signature = signature(pattern);
                        versions.add(signature);
                        if (!seenPatterns.add(pattern)) return false;
                        budget.reserve(64);
                        normalizingPattern = pattern;
                        normalizingSignature = signature;
                        normalizing = new CandidateCapture(pattern, signature.values(), available, level, budget);
                        for (var input : signature.values().inputs()) for (var possible : input.choices()) {
                            if (!normalizing.exactInputs)
                                templates.putIfAbsent(possible.stack().what().getPrimaryKey(), possible.stack().what());
                            pending.add(possible.stack().what());
                        }
                        return false;
                    }
                    if (patterns != null) {
                        dependencies.put(key, List.copyOf(versions));
                        patterns = null;
                    }
                    if (pending.isEmpty()) {
                        phase = 2;
                        keys = templates.values().iterator();
                        return false;
                    }
                    key = pending.removeFirst();
                    if (!seen.add(key)) return false;
                    if (seen.size() > 100_000) throw new PlanningBudget.Exhausted(PlanningBudget.Limit.GRAPH_LIMIT);
                    budget.reserve(96);
                    versions = new ArrayList<>();
                    patterns = List.copyOf(service.getCraftingFor(key)).iterator();
                }
                case 2 -> {
                    if (keys.hasNext()) {
                        AEKey template = keys.next();
                        if (fuzzyPrimary.add(template.getPrimaryKey()))
                            for (var entry : available.findFuzzy(template, FuzzyMode.IGNORE_ALL)) if (entry.getLongValue() > 0) fuzzy.add(entry.getKey());
                    } else if (hit && !fuzzy.equals(structure.fuzzyKeys())) resetBuild();
                    else {
                        if (!hit) {
                            // Ownership transfers here. This Capture never mutates
                            // these collections after graph discovery; later phases
                            // only validate their contents. Avoid rebuilding a large
                            // hash table in one unsliceable server-thread operation.
                            structure = new Structure(new CapturedPatternCatalog(entries, recipeCount), Collections.unmodifiableSet(seen),
                                    Set.copyOf(templates.values()), Collections.unmodifiableSet(fuzzy), bounded,
                                    Collections.unmodifiableMap(dependencies), revision);
                        } else if (structure.providerRevision() != revision) structure = new Structure(structure.catalog(),
                                structure.resources(), structure.inputTemplates(), structure.fuzzyKeys(), structure.boundedAlternatives(),
                                structure.dependencies(), revision);
                        keys = structure.resources().iterator();
                        phase = 3;
                        if (source != null) available = storage.getCachedInventory();
                    }
                }
                case 3 -> {
                    // Amortize state-machine/phase bookkeeping over a bounded
                    // stock scan. World calls stay on the server thread; every
                    // key still checks cancellation and the shared tick deadline.
                    int scanned = 0;
                    while (keys.hasNext() && scanned < 32) {
                        if (scanned > 0) {
                            if (deadline != Long.MAX_VALUE && System.nanoTime() - deadline >= 0) return false;
                            budget.check();
                        }
                        scanned++;
                        AEKey resource = keys.next();
                        if (source != null) {
                            long count = available.get(resource);
                            // AE may defer a just-inserted stack's cache update to
                            // its end-tick event. Query an empty cached key before
                            // diagnosing absence; this never transfers material.
                            if (simulate || count == 0) count = storage.getInventory().extract(resource,
                                    count == 0 ? Long.MAX_VALUE : count, Actionable.SIMULATE, source);
                            if (count > 0) stock.put(resource, count);
                        }
                        if (service.canEmitFor(resource)) emitted.add(resource);
                    }
                    if (!keys.hasNext()) {
                        if (dataRevision != dataGeneration) throw new IllegalStateException("GRAPH_DATA_CHANGED_DURING_SNAPSHOT");
                        if (invalidationRevision != invalidationGeneration) throw new IllegalStateException("GRAPH_BINDING_INVALIDATED_DURING_SNAPSHOT");
                        long latest = ((GraphRequestTracker) service).gtlcore$graphProviderGeneration();
                        if (revision != latest) {
                            revision = latest;
                            phase = 7;
                            keys = structure.resources().iterator();
                            patterns = null;
                            return false;
                        }
                        if (structure.providerRevision() != revision) structure = new Structure(structure.catalog(), structure.resources(),
                                structure.inputTemplates(), structure.fuzzyKeys(), structure.boundedAlternatives(), structure.dependencies(), revision);
                        cache.put(roots, structure);
                        long weight = cache.values().stream().mapToLong(value -> value.resources().size() + value.catalog().size()).sum();
                        // One catalog is already bounded by the request's graph/memory
                        // limits. Keep the newest alone if it exceeds the shared weight
                        // allowance; otherwise large orders immediately evict themselves.
                        while (cache.size() > 1 && (cache.size() > 32 || weight > 65_536)) {
                            Structure removed = cache.remove(cache.keySet().iterator().next());
                            weight -= removed.resources().size() + removed.catalog().size();
                        }
                        // Phase 4 is terminal; no mutable owner escapes alongside
                        // these read-only views. Subsequent requests own new maps.
                        result = new Snapshot(structure, Collections.unmodifiableMap(stock), Collections.unmodifiableSet(emitted), revision, hit);
                        phase = 4;
                    }
                }
                case 7 -> {
                    if (patterns != null && patterns.hasNext()) {
                        versions.add(signature(patterns.next()));
                        return false;
                    }
                    if (patterns != null) {
                        patterns = null;
                        if (!versions.equals(structure.dependencies().get(key))) throw new IllegalStateException("GRAPH_PATTERN_CHANGED_DURING_SNAPSHOT");
                    }
                    if (keys.hasNext()) {
                        key = keys.next();
                        versions = new ArrayList<>();
                        patterns = List.copyOf(service.getCraftingFor(key)).iterator();
                    } else {
                        phase = 3;
                        keys = Collections.emptyIterator();
                    }
                }
                default -> {
                    return true;
                }
            }
            return result != null;
        }

        private Signature signature(IPatternDetails pattern) {
            if (pattern.getInputs().length > 256 || pattern.getOutputs().length > 256)
                throw new PlanningBudget.Exhausted(PlanningBudget.Limit.GRAPH_LIMIT);
            int priority = Integer.MIN_VALUE;
            for (var provider : service.getProviders(pattern)) priority = Math.max(priority, provider.getPatternPriority());
            return new Signature(PatternFingerprint.capture(pattern), priority);
        }

        private void resetBuild() {
            hit = false;
            structure = null;
            phase = 1;
            patterns = null;
            fuzzy.clear();
            fuzzyPrimary.clear();
            pending.add(roots.target());
            pending.addAll(roots.recovery());
        }

        public Snapshot result() {
            if (result == null) throw new IllegalStateException("Snapshot incomplete");
            return result;
        }
    }

    private static Set<AEKey> fuzzyKeys(KeyCounter available, Set<AEKey> templates) {
        Set<AEKey> result = new HashSet<>();
        Set<Object> primary = new HashSet<>();
        for (AEKey key : templates) {
            if (!primary.add(key.getPrimaryKey())) continue;
            for (var entry : available.findFuzzy(key, FuzzyMode.IGNORE_ALL)) if (entry.getLongValue() > 0) result.add(entry.getKey());
        }
        return Set.copyOf(result);
    }

    private static List<GraphRecipe<AEKey>> normalize(IPatternDetails pattern, String binding, KeyCounter available, Level level) {
        return normalize(pattern, binding, available, level, new PlanningBudget(0, 200_000, () -> false));
    }

    private static List<GraphRecipe<AEKey>> normalize(IPatternDetails pattern, String binding, KeyCounter available, Level level, PlanningBudget budget) {
        var work = new CandidateCapture(pattern, PatternFingerprint.capture(pattern), available, level, budget);
        while (!work.step()) {}
        var fingerprints = new PatternFingerprint.Context();
        var expansion = work.result().expand(budget);
        List<GraphRecipe<AEKey>> result = new ArrayList<>();
        while (expansion.hasNext()) result.add(expansion.next().encode(binding, fingerprints));
        return List.copyOf(result);
    }

    static final class CandidateCapture {

        final PatternFingerprint.Values values;
        final KeyCounter available;
        final Level level;
        final PlanningBudget budget;
        final List<CapturedPattern.Input> capturedInputs = new ArrayList<>();
        final List<CapturedPattern.Candidate> capturedCandidates = new ArrayList<>();
        final Map<AEKey, GenericStack> candidates = new LinkedHashMap<>();
        final IPatternDetails.IInput[] inputs;
        final boolean exactInputs;
        int inputSlot;
        boolean bounded;
        Iterator<PatternFingerprint.Choice> possibilities;
        Iterator<GenericStack> capturing;
        Iterator<? extends it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<AEKey>> fuzzy;
        GenericStack possible;
        CapturedPattern result;

        CandidateCapture(IPatternDetails pattern, PatternFingerprint.Values values, KeyCounter available, Level level, PlanningBudget budget) {
            this.values = values;
            this.available = available;
            this.level = level;
            this.budget = budget;
            inputs = pattern.getInputs();
            if (inputs.length > 256 || values.outputs().size() > 256) throw new PlanningBudget.Exhausted(PlanningBudget.Limit.GRAPH_LIMIT);
            budget.reserve(96L + 32L * (inputs.length + values.outputs().size()));
            // Like MAX_FAST's structural exactness check, specialize only the
            // native processing implementation. Its Input.isValid is AEKey.matches;
            // other NBT variants cannot be candidates. Check the actual inputs as
            // well: wrappers or addon input implementations retain the full path.
            boolean exact = pattern.getClass() == AEProcessingPattern.class;
            for (var input : inputs) exact &= input.getClass().getName().equals("appeng.crafting.pattern.AEProcessingPattern$Input");
            exactInputs = exact;
        }

        boolean step() {
            budget.check();
            if (result != null) return true;
            if (inputSlot == inputs.length) {
                result = new CapturedPattern(capturedInputs, values.outputs(), values.external(), bounded);
                return true;
            }
            var input = inputs[inputSlot];
            var inputValues = values.inputs().get(inputSlot);
            if (exactInputs && inputValues.choices().size() == 1) {
                var choice = inputValues.choices().get(0);
                List<CapturedPattern.Candidate> selected;
                if (input.isValid(choice.stack().what(), level)) {
                    budget.reserve(64);
                    selected = List.of(new CapturedPattern.Candidate(choice.stack(), choice.remaining(),
                            values.external() && GtlDispatchPolicy.configuration(choice.stack().what())));
                } else selected = List.of();
                capturedInputs.add(new CapturedPattern.Input(inputValues.multiplier(), selected));
                if (++inputSlot == inputs.length || selected.isEmpty()) {
                    result = new CapturedPattern(capturedInputs, values.outputs(), values.external(), bounded);
                    return true;
                }
                return false;
            }
            if (capturing == null) {
                if (fuzzy != null) {
                    if (fuzzy.hasNext() && candidates.size() < MAX_VARIANTS) {
                        var entry = fuzzy.next();
                        if (entry.getLongValue() > 0 && input.isValid(entry.getKey(), level))
                            candidates.putIfAbsent(entry.getKey(), new GenericStack(entry.getKey(), possible.amount()));
                        return false;
                    }
                    bounded |= fuzzy.hasNext();
                    fuzzy = null;
                }
                if (possibilities == null) possibilities = inputValues.choices().iterator();
                if (possibilities.hasNext() && candidates.size() < MAX_VARIANTS) {
                    possible = possibilities.next().stack();
                    if (input.isValid(possible.what(), level)) candidates.put(possible.what(), possible);
                    if (!exactInputs) fuzzy = available.findFuzzy(possible.what(), FuzzyMode.IGNORE_ALL).iterator();
                    return false;
                }
                bounded |= possibilities.hasNext();
                if (candidates.isEmpty()) {
                    capturedInputs.add(new CapturedPattern.Input(inputValues.multiplier(), List.of()));
                    result = new CapturedPattern(capturedInputs, values.outputs(), values.external(), bounded);
                    return true;
                }
                capturing = candidates.values().iterator();
            }
            GenericStack candidate = capturing.next();
            // Declared remainders were already captured for the binding signature.
            // Only fuzzy alternatives need another callback, still on this thread.
            AEKey remaining = null;
            boolean declared = false;
            for (var choice : inputValues.choices()) if (choice.stack().what().equals(candidate.what())) {
                remaining = choice.remaining();
                declared = true;
                break;
            }
            if (!declared) remaining = input.getRemainingKey(candidate.what());
            budget.reserve(64);
            capturedCandidates.add(new CapturedPattern.Candidate(candidate, remaining,
                    values.external() && GtlDispatchPolicy.configuration(candidate.what())));
            if (!capturing.hasNext()) {
                capturedInputs.add(new CapturedPattern.Input(inputValues.multiplier(), capturedCandidates));
                capturedCandidates.clear();
                inputSlot++;
                possibilities = null;
                capturing = null;
                candidates.clear();
            }
            return false;
        }

        CapturedPattern result() {
            if (result == null) throw new IllegalStateException("Candidate capture incomplete");
            return result;
        }
    }

    public record Structure(CapturedPatternCatalog catalog, Set<AEKey> resources,
                            Set<AEKey> inputTemplates, Set<AEKey> fuzzyKeys, boolean boundedAlternatives,
                            Map<AEKey, List<Signature>> dependencies, long providerRevision) {}

    public record Snapshot(Structure structure, Map<AEKey, Long> stock, Set<AEKey> emitable, long epoch, boolean cacheHit) {}
}
