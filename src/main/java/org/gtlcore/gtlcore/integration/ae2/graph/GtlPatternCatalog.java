package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.crafting.ManualCraftingInventoryLock;

import com.gregtechceu.gtceu.api.item.IGTTool;

import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.core.AEConfig;
import appeng.me.service.CraftingService;
import org.cgse.core.CapturedRecipe;
import org.cgse.core.GraphPlan;
import org.cgse.core.GraphRecipe;
import org.cgse.core.PlanningBudget;
import org.cgse.core.PlanningFailure;

import java.util.*;

/** Snapshot creation and pattern methods run on the server thread, never the solver thread. */
public final class GtlPatternCatalog {

    private static final int MAX_CAPTURE_STATES = 100_000;
    private static final Set<GtlPatternCatalog> CATALOGS = Collections.newSetFromMap(new WeakHashMap<>());

    public GtlPatternCatalog() {
        CATALOGS.add(this);
    }

    /** Server-thread retention boundary. Active requests keep their immutable snapshots. */
    static void trimRetainedCaches() {
        record Cached(GtlPatternCatalog owner, Roots roots, Structure structure) {}
        List<Cached> entries = new ArrayList<>();
        long bytes = 0;
        for (var owner : CATALOGS) for (var entry : owner.cache.entrySet()) {
            entries.add(new Cached(owner, entry.getKey(), entry.getValue()));
            bytes += entry.getValue().catalog().retainedBytes() + 128L * entry.getValue().resources().size();
        }
        long limit = ConfigHolder.INSTANCE.ae.ae2GraphCacheMemoryMiB * (1L << 20);
        if (bytes <= limit) return;
        entries.sort(Comparator.comparingLong(entry -> entry.structure().catalog().lastAccess()));
        for (var entry : entries) {
            entry.owner().cache.remove(entry.roots(), entry.structure());
            bytes -= entry.structure().catalog().retainedBytes() + 128L * entry.structure().resources().size();
            if (bytes <= limit) break;
        }
    }

    private record Roots(AEKey target, Set<AEKey> recovery, boolean discoverByproducts) {}

    public record Signature(PatternFingerprint.Values values, int priority) {}

    private final Map<Roots, Structure> cache = new LinkedHashMap<>(16, 0.75f, true);
    private Object recipeManager;
    private static long dataGeneration;
    private long cachedDataGeneration = -1;
    private long invalidationGeneration;
    private ByproductPatternIndex byproducts;
    private long byproductRevision = -1;

    /** A failed execution preflight disproves the cached catalog, even without a provider event. */
    public void invalidateBinding(String binding) {
        cache.values().removeIf(structure -> structure.catalog().mayContainBinding(binding));
        byproducts = null;
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
        return begin(grid, service, level, source, target, recovery, budget, false);
    }

    public Capture begin(IGrid grid, CraftingService service, Level level, IActionSource source, AEKey target,
                         Set<AEKey> recovery, PlanningBudget budget, boolean refreshInventory) {
        return begin(grid, service, level, source, target, recovery, budget, refreshInventory, Set.of());
    }

    /** Additional identities are already owned/owed by a job; this never credits their quantity as ME stock. */
    public Capture begin(IGrid grid, CraftingService service, Level level, IActionSource source, AEKey target,
                         Set<AEKey> recovery, PlanningBudget budget, boolean refreshInventory, Set<AEKey> discoveryOnly) {
        return new Capture(grid, service, level, source,
                new Roots(target, Set.copyOf(recovery), ConfigHolder.INSTANCE.ae.ae2GraphDiscoverByproducts), budget, refreshInventory, discoveryOnly);
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
        private final KeyCounter additionalDiscovery = new KeyCounter();
        private final Iterator<AEKey> discoveryKeys;
        private long revision;
        private final long dataRevision;
        private final long invalidationRevision;
        private final boolean simulate;
        private final boolean refreshInventory;
        private final Map<AEKey, List<Signature>> dependencies = new LinkedHashMap<>();
        private final Set<AEKey> seen = new LinkedHashSet<>();
        // IGNORE_ALL fuzzy scans use only the primary key. Cache one representative
        // instead of revisiting thousands of NBT variants on every warm request.
        private final Map<Object, AEKey> templates = new LinkedHashMap<>();
        private final Set<IPatternDetails> seenPatterns = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Deque<AEKey> pending = new ArrayDeque<>();
        private final List<CapturedPatternCatalog.Entry> entries = new ArrayList<>();
        // Candidate discovery is deliberately separate from physical inventory.
        private final KeyCounter potential = new KeyCounter();
        private int potentialCount, freezing;
        private ByproductPatternIndex potentialSource;
        private Iterator<AEKey> producerCandidates;
        private Iterator<? extends it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<AEKey>> stockCandidates;
        private Iterator<? extends it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<AEKey>> additionalCandidates;
        private final Map<IPatternDetails, CandidateCapture> captures = new IdentityHashMap<>();
        private final Map<IPatternDetails, Signature> capturedSignatures = new IdentityHashMap<>();
        private final List<IPatternDetails> captureOrder = new ArrayList<>();
        private final Map<Object, List<IPatternDetails>> watchers = new HashMap<>();
        private final Deque<IPatternDetails> recaptures = new ArrayDeque<>();
        private final Set<IPatternDetails> queuedCaptures = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Object> flexible = new HashSet<>(), expandedFamilies = new HashSet<>();
        private final Map<Object, List<AEKey>> searchedFamilies = new HashMap<>();
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
        private ByproductPatternIndex sourceIndex;
        private ByproductPatternIndex.Build indexing;
        private long indexingRevision = -1;

        Capture(IGrid grid, CraftingService service, Level level, IActionSource source, Roots roots, PlanningBudget budget,
                boolean refreshInventory, Set<AEKey> discoveryOnly) {
            if (!level.getServer().isSameThread()) throw new IllegalStateException("Graph snapshot requires server thread");
            this.grid = grid;
            this.service = service;
            this.storage = grid.getStorageService();
            this.level = level;
            this.source = source;
            this.roots = roots;
            this.budget = budget;
            this.refreshInventory = refreshInventory;
            if (discoveryOnly.size() > MAX_CAPTURE_STATES)
                throw new PlanningBudget.Exhausted(PlanningBudget.Limit.GRAPH_LIMIT, "job_discovery_keys");
            discoveryKeys = Set.copyOf(discoveryOnly).iterator();
            available = source == null ? new KeyCounter() : storage.getCachedInventory();
            revision = ((GraphRequestTracker) service).gtlcore$graphProviderGeneration();
            dataRevision = dataGeneration;
            invalidationRevision = invalidationGeneration;
            if (recipeManager != level.getRecipeManager() || cachedDataGeneration != dataRevision) {
                cache.clear();
                byproducts = null;
                recipeManager = level.getRecipeManager();
                cachedDataGeneration = dataRevision;
            }
            simulate = source != null && (refreshInventory || AEConfig.instance().isCraftingSimulatedExtraction() ||
                    ManualCraftingInventoryLock.hasReservations(storage.getInventory()));
            structure = cache.get(roots);
            if (structure != null) structure.catalog().touch();
            if (structure != null) {
                hit = true;
                for (AEKey template : structure.inputTemplates()) flexible.add(template.getPrimaryKey());
                if (structure.providerRevision() != revision) keys = structure.dependencies().keySet().iterator();
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
            budget.compilationCheck();
            budget.phase(PlanningBudget.Phase.SNAPSHOT);
            if (!prepareSources()) return false;
            if (!hit && potentialSource != null && potentialSource != sourceIndex)
                throw new IllegalStateException("GRAPH_PATTERN_CHANGED_DURING_SNAPSHOT");
            if (discoveryKeys.hasNext()) {
                budget.reserve(96);
                additionalDiscovery.add(discoveryKeys.next(), 1);
                return false;
            }
            if (!hit && phase == 1 && !preparePotential()) return false;
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
                        patterns = sources(key).iterator();
                    } else {
                        phase = 2;
                        keys = structure.inputTemplates().iterator();
                    }
                }
                case 1 -> {
                    if (normalizing != null) {
                        if (!normalizing.step()) return false;
                        AEKey dependency = normalizing.dependencies.pollFirst();
                        if (dependency != null) {
                            pending.add(dependency);
                            return false;
                        }
                        AEKey returned = normalizing.returns.pollFirst();
                        if (returned != null) {
                            offerPotential(returned);
                            return false;
                        }
                        normalizing = null;
                    }
                    if (!recaptures.isEmpty()) {
                        IPatternDetails pattern = recaptures.removeFirst();
                        queuedCaptures.remove(pattern);
                        normalizing = captures.get(pattern);
                        return false;
                    }
                    if (patterns != null && patterns.hasNext()) {
                        IPatternDetails pattern = patterns.next();
                        Signature signature = signature(pattern);
                        versions.add(signature);
                        if (!seenPatterns.add(pattern)) return false;
                        budget.reserve(64);
                        normalizing = new CandidateCapture(pattern, signature.values(), potential, level, budget);
                        captures.put(pattern, normalizing);
                        capturedSignatures.put(pattern, signature);
                        captureOrder.add(pattern);
                        Set<Object> watched = new HashSet<>();
                        for (int slot = 0; slot < signature.values().inputs().size(); slot++) {
                            for (var possible : signature.values().inputs().get(slot).choices()) {
                                if (!normalizing.exactInputs[slot]) {
                                    AEKey template = possible.stack().what();
                                    registerTemplate(template);
                                    if (watched.add(template.getPrimaryKey()))
                                        watchers.computeIfAbsent(template.getPrimaryKey(), ignored -> new ArrayList<>()).add(pattern);
                                }
                                pending.add(possible.stack().what());
                            }
                        }
                        return false;
                    }
                    if (patterns != null) {
                        dependencies.put(key, List.copyOf(versions));
                        patterns = null;
                    }
                    if (pending.isEmpty()) {
                        phase = 5;
                        return false;
                    }
                    key = pending.removeFirst();
                    if (!seen.add(key)) return false;
                    if (seen.size() > 100_000) throw new PlanningBudget.Exhausted(PlanningBudget.Limit.GRAPH_LIMIT);
                    budget.reserve(96);
                    Object family = key.getPrimaryKey();
                    if (flexible.contains(family) && !expandedFamilies.add(family)) return false;
                    searchedFamilies.computeIfAbsent(family, ignored -> new ArrayList<>()).add(key);
                    versions = new ArrayList<>();
                    patterns = sources(key).iterator();
                }
                case 5 -> {
                    if (freezing < captureOrder.size()) {
                        IPatternDetails pattern = captureOrder.get(freezing++);
                        CapturedPattern captured = captures.get(pattern).result();
                        Signature signature = capturedSignatures.get(pattern);
                        bounded |= captured.bounded();
                        recipeCount += captured.size();
                        if (recipeCount > 100_000) throw new PlanningBudget.Exhausted(PlanningBudget.Limit.GRAPH_LIMIT);
                        entries.add(new CapturedPatternCatalog.Entry(pattern, signature.values(), signature.priority(), captured));
                    } else {
                        phase = 2;
                        keys = templates.values().iterator();
                    }
                }
                case 2 -> {
                    if (keys.hasNext()) {
                        AEKey template = keys.next();
                        if (fuzzyPrimary.add(template.getPrimaryKey())) {
                            includeFuzzyKeys(fuzzy, available, template);
                            includeFuzzyKeys(fuzzy, additionalDiscovery, template);
                        }
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
                            budget.compilationCheck();
                        }
                        scanned++;
                        AEKey resource = keys.next();
                        if (source != null) {
                            long count = available.get(resource);
                            // AE may defer a just-inserted stack's cache update to
                            // its end-tick event. Query an empty cached key before
                            // diagnosing absence; this never transfers material.
                            if (simulate || count == 0) count = storage.getInventory().extract(resource,
                                    refreshInventory || count == 0 ? Long.MAX_VALUE : count, Actionable.SIMULATE, source);
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
                            keys = structure.dependencies().keySet().iterator();
                            patterns = null;
                            return false;
                        }
                        if (structure.providerRevision() != revision) structure = new Structure(structure.catalog(), structure.resources(),
                                structure.inputTemplates(), structure.fuzzyKeys(), structure.boundedAlternatives(), structure.dependencies(), revision);
                        cache.put(roots, structure);
                        trimRetainedCaches();
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
                        patterns = sources(key).iterator();
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

        private boolean prepareSources() {
            if (dataRevision != dataGeneration) throw new IllegalStateException("GRAPH_DATA_CHANGED_DURING_SNAPSHOT");
            if (invalidationRevision != invalidationGeneration) throw new IllegalStateException("GRAPH_BINDING_INVALIDATED_DURING_SNAPSHOT");
            long latest = ((GraphRequestTracker) service).gtlcore$graphProviderGeneration();
            if (byproducts != null && byproductRevision == latest) {
                sourceIndex = byproducts;
                indexing = null;
                return true;
            }
            // Provider updates may occur between tick slices. Discard the
            // partial index before touching its live registry iterator again.
            if (indexing == null || indexingRevision != latest) {
                indexingRevision = latest;
                indexing = new ByproductPatternIndex.Build(((GraphRequestTracker) service).gtlcore$registeredGraphPatterns());
            }
            if (!indexing.step(budget)) return false;
            sourceIndex = byproducts = indexing.result();
            byproductRevision = latest;
            indexing = null;
            return true;
        }

        private List<IPatternDetails> sources(AEKey resource) {
            var primary = service.getCraftingFor(resource);
            if (flexible.contains(resource.getPrimaryKey()))
                return sourceIndex.fuzzySources(resource, primary, roots.discoverByproducts());
            return roots.discoverByproducts() ? sourceIndex.sources(resource, primary) : List.copyOf(primary);
        }

        private boolean preparePotential() {
            if (potentialSource != sourceIndex) {
                potentialSource = sourceIndex;
                producerCandidates = sourceIndex.candidates(roots.discoverByproducts());
                stockCandidates = available.iterator();
                additionalCandidates = additionalDiscovery.iterator();
            }
            if (producerCandidates != null && producerCandidates.hasNext()) {
                offerPotential(producerCandidates.next());
                return false;
            }
            producerCandidates = null;
            if (stockCandidates != null && stockCandidates.hasNext()) {
                var entry = stockCandidates.next();
                if (entry.getLongValue() > 0) offerPotential(entry.getKey());
                return false;
            }
            stockCandidates = null;
            if (additionalCandidates != null && additionalCandidates.hasNext()) {
                offerPotential(additionalCandidates.next().getKey());
                return false;
            }
            additionalCandidates = null;
            return true;
        }

        private void offerPotential(AEKey key) {
            if (potential.get(key) > 0) return;
            if (++potentialCount > MAX_CAPTURE_STATES) throw new PlanningBudget.Exhausted(PlanningBudget.Limit.GRAPH_LIMIT, "candidate_discovery_keys");
            budget.reserve(96);
            potential.add(key, 1);
            for (IPatternDetails pattern : watchers.getOrDefault(key.getPrimaryKey(), List.of())) {
                if (captures.get(pattern).offer(key) && queuedCaptures.add(pattern)) recaptures.addLast(pattern);
            }
        }

        private void registerTemplate(AEKey key) {
            Object family = key.getPrimaryKey();
            templates.putIfAbsent(family, key);
            if (!flexible.add(family)) return;
            // A family first encountered as an exact root must be revisited once
            // a flexible consumer makes its other producer variants relevant.
            for (AEKey previous : searchedFamilies.getOrDefault(family, List.of())) {
                dependencies.remove(previous);
                seen.remove(previous);
                pending.addLast(previous);
            }
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
            flexible.clear();
            expandedFamilies.clear();
            searchedFamilies.clear();
            pending.add(roots.target());
            pending.addAll(roots.recovery());
        }

        public Snapshot result() {
            if (result == null) throw new IllegalStateException("Snapshot incomplete");
            return result;
        }
    }

    static void includeFuzzyKeys(Set<AEKey> target, KeyCounter source, AEKey template) {
        for (var entry : source.findFuzzy(template, FuzzyMode.IGNORE_ALL))
            if (entry.getLongValue() > 0) target.add(entry.getKey());
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

        final IPatternDetails pattern;
        final PatternFingerprint.Values values;
        final KeyCounter available;
        final Level level;
        final PlanningBudget budget;
        final IPatternDetails.IInput[] inputs;
        final boolean[] exactInputs;
        final InputDomain[] domains;
        final Deque<AEKey> dependencies = new ArrayDeque<>(), returns = new ArrayDeque<>();
        final CapturedRecipe.InputMode mode;
        int inputSlot;
        boolean bounded, done;
        CapturedPattern result;

        CandidateCapture(IPatternDetails pattern, PatternFingerprint.Values values, KeyCounter available, Level level, PlanningBudget budget) {
            this.pattern = pattern;
            this.values = values;
            this.available = available;
            this.level = level;
            this.budget = budget;
            inputs = pattern.getInputs();
            if (inputs.length > 256 || values.outputs().size() > 256) throw new PlanningBudget.Exhausted(PlanningBudget.Limit.GRAPH_LIMIT);
            budget.reserve(96L + 40L * (inputs.length + values.outputs().size()));
            exactInputs = new boolean[inputs.length];
            domains = new InputDomain[inputs.length];
            for (int slot = 0; slot < inputs.length; slot++)
                exactInputs[slot] = inputs[slot].getClass().getName().equals("appeng.crafting.pattern.AEProcessingPattern$Input");
            mode = independentExternal(pattern) ? CapturedRecipe.InputMode.MIXED : CapturedRecipe.InputMode.LEGACY;
        }

        private static boolean independentExternal(IPatternDetails pattern) {
            if (!pattern.supportsPushInputsToExternalInventory()) return false;
            try {
                return pattern.getClass().getMethod("pushInputsToExternalInventory", KeyCounter[].class,
                        IPatternDetails.PatternInputSink.class).getDeclaringClass() == IPatternDetails.class;
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }

        /** Add a newly discovered physical key to relevant domains, never to stock. */
        boolean offer(AEKey key) {
            boolean changed = false;
            for (int slot = 0; slot < domains.length; slot++) {
                InputDomain domain = domains[slot];
                if (domain != null && !exactInputs[slot]) changed |= domain.offer(key);
            }
            if (changed) {
                inputSlot = 0;
                done = false;
                result = null;
            }
            return changed;
        }

        boolean step() {
            budget.compilationCheck();
            if (done) return true;
            if (inputSlot == inputs.length) {
                done = true;
                return true;
            }
            InputDomain domain = domains[inputSlot];
            if (domain == null) domains[inputSlot] = domain = new InputDomain(inputSlot);
            if (domain.step()) inputSlot++;
            return false;
        }

        CapturedPattern result() {
            if (!done) throw new IllegalStateException("Candidate capture incomplete");
            if (result == null) {
                List<CapturedPattern.Input> captured = new ArrayList<>();
                for (int slot = 0; slot < domains.length; slot++) {
                    InputDomain domain = domains[slot];
                    captured.add(new CapturedPattern.Input(values.inputs().get(slot).multiplier(),
                            new ArrayList<>(domain.candidates.values()), mode));
                }
                result = new CapturedPattern(captured, values.outputs(), values.external(), bounded);
            }
            return result;
        }

        final class InputDomain {

            final int slot;
            final IPatternDetails.IInput input;
            final Map<Object, GenericStack> families = new LinkedHashMap<>();
            final Map<AEKey, PatternFingerprint.Choice> declared = new HashMap<>();
            final Set<AEKey> offered = new HashSet<>();
            final Deque<GenericStack> pending = new ArrayDeque<>();
            final Map<AEKey, CapturedPattern.Candidate> candidates = new LinkedHashMap<>();
            final Iterator<PatternFingerprint.Choice> choices;
            Iterator<GenericStack> templates;
            Iterator<? extends it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<AEKey>> fuzzy;
            GenericStack template;
            boolean seeded;

            InputDomain(int slot) {
                this.slot = slot;
                input = inputs[slot];
                choices = values.inputs().get(slot).choices().iterator();
            }

            boolean offer(AEKey key) {
                GenericStack family = families.get(key.getPrimaryKey());
                return family != null && enqueue(new GenericStack(key, family.amount()));
            }

            private boolean enqueue(GenericStack stack) {
                if (offered.contains(stack.what())) return false;
                if (offered.size() >= MAX_CAPTURE_STATES) {
                    bounded = true;
                    return false;
                }
                budget.reserve(96);
                offered.add(stack.what());
                pending.addLast(stack);
                return true;
            }

            boolean step() {
                // Native processing inputs have exactly one identity and no
                // implicit remainder. Preserve their allocation-light path.
                if (!seeded && exactInputs[slot] && values.inputs().get(slot).choices().size() == 1) {
                    seeded = true;
                    var choice = values.inputs().get(slot).choices().get(0);
                    capture(choice.stack(), choice);
                    return true;
                }
                if (!seeded) {
                    if (choices.hasNext()) {
                        var choice = choices.next();
                        declared.putIfAbsent(choice.stack().what(), choice);
                        GenericStack previous = families.putIfAbsent(choice.stack().what().getPrimaryKey(), choice.stack());
                        // The API gives no amount for an undeclared key when one
                        // family has several weights. Keep that uncertainty visible.
                        if (previous != null && previous.amount() != choice.stack().amount() && !exactInputs[slot]) bounded = true;
                        enqueue(choice.stack());
                        return false;
                    }
                    if (!exactInputs[slot]) {
                        if (fuzzy != null && fuzzy.hasNext()) {
                            var entry = fuzzy.next();
                            if (entry.getLongValue() > 0) enqueue(new GenericStack(entry.getKey(), template.amount()));
                            return false;
                        }
                        fuzzy = null;
                        if (templates == null) templates = families.values().iterator();
                        if (templates.hasNext()) {
                            template = templates.next();
                            fuzzy = available.findFuzzy(template.what(), FuzzyMode.IGNORE_ALL).iterator();
                            return false;
                        }
                    }
                    seeded = true;
                }
                if (pending.isEmpty()) return true;
                GenericStack candidate = pending.removeFirst();
                capture(candidate, declared.get(candidate.what()));
                return false;
            }

            private void capture(GenericStack candidate, PatternFingerprint.Choice declaration) {
                if (!input.isValid(candidate.what(), level)) return;
                GtlStateReturns.Capture returned = declaration == null ? GtlStateReturns.capture(pattern, input, candidate.what()) :
                        new GtlStateReturns.Capture(declaration.contract(), declaration.remaining(), !declaration.contract().deterministic());
                if (!returned.contract().complete()) throw new PlanningFailure(GraphPlan.Result.UNSUPPORTED_PATTERN_SEMANTICS,
                        "input_return_contract_incomplete; slot=" + slot + "; semantics=" + returned.contract().semantics());
                bounded |= returned.uncertain();
                if (values.external() && mode == CapturedRecipe.InputMode.LEGACY &&
                        values.inputs().get(slot).multiplier() > 1 && candidates.size() > 0)
                    bounded = true;
                budget.reserve(64);
                var stateFamily = GtlStateReturns.family(pattern, input, candidate.what());
                candidates.put(candidate.what(), new CapturedPattern.Candidate(candidate, returned.nominal(),
                        values.external() && GtlDispatchPolicy.configuration(candidate.what()),
                        values.external() && GtlDispatchPolicy.reusable(candidate.what()), stateFamily));
                dependencies.addLast(candidate.what());
                boolean tool = !exactInputs[slot] && candidate.what() instanceof AEItemKey item && item.getItem() instanceof IGTTool;
                // Tool durability/charge is a symbolic capacity domain. Do not
                // enumerate thousands or billions of unused future tool states.
                if (tool) {
                    if (stateFamily == null && returned.nominal() != null && !returned.nominal().equals(candidate.what())) bounded = true;
                } else for (AEKey successor : returned.contract().successors()) {
                    returns.addLast(successor);
                    if (!exactInputs[slot]) offer(successor);
                }
            }
        }
    }

    public record Structure(CapturedPatternCatalog catalog, Set<AEKey> resources,
                            Set<AEKey> inputTemplates, Set<AEKey> fuzzyKeys, boolean boundedAlternatives,
                            Map<AEKey, List<Signature>> dependencies, long providerRevision) {}

    public record Snapshot(Structure structure, Map<AEKey, Long> stock, Set<AEKey> emitable, long epoch, boolean cacheHit) {}
}
