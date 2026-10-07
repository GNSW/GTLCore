package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.config.ConfigHolder;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import org.cgse.core.CraftingCostModel;
import org.cgse.core.ExactAmounts;
import org.cgse.core.GraphPlan;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class AeGraphPlan implements ICraftingPlan {

    private final GraphPlan<AEKey> graph;
    private final Map<String, IPatternDetails> bindings;
    private final Map<AEKey, BigInteger> emitted;
    private final long bytes;
    private final BigInteger exactBytes;
    private final CraftingCostModel.Mode costMode;
    private final boolean fallback;
    private final UUID id = UUID.randomUUID();
    private final Map<String, BigInteger> selectedCounts;
    private final Map<IPatternDetails, Long> selectedPatterns;
    private volatile GraphRingView display;
    private CompletableFuture<GraphRingView> displayWork;

    public AeGraphPlan(GraphPlan<AEKey> graph, Map<String, IPatternDetails> bindings, Set<AEKey> emitable,
                       Map<AEKey, Long> stock) {
        this(graph, bindings, emitable, stock, false);
    }

    public AeGraphPlan(GraphPlan<AEKey> graph, Map<String, IPatternDetails> bindings, Set<AEKey> emitable,
                       Map<AEKey, Long> stock, boolean fallback) {
        this.graph = graph;
        this.fallback = fallback;
        this.bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
        selectedCounts = graph.patternTimesExact();
        Map<IPatternDetails, BigInteger> selected = new LinkedHashMap<>();
        selectedCounts.forEach((recipe, count) -> selected.merge(
                java.util.Objects.requireNonNull(this.bindings.get(graph.recipes().get(recipe).binding()), "Missing pattern binding"),
                count, BigInteger::add));
        // Pattern definitions can have clustered hash codes (large families of
        // NBT recipes). Map.copyOf's linear probing magnifies those collisions.
        // This map is owned exclusively by the immutable plan; retain its hash
        // table instead of rebuilding a second open-addressed one.
        selectedPatterns = ExactAmounts.longView(selected);
        Map<AEKey, BigInteger> emissions = new LinkedHashMap<>();
        graph.initialExact().forEach((key, amount) -> {
            BigInteger deficit = amount.subtract(BigInteger.valueOf(stock.getOrDefault(key, 0L)));
            if (emitable.contains(key) && deficit.signum() > 0) emissions.put(key, deficit);
        });
        this.emitted = Map.copyOf(emissions);
        costMode = ConfigHolder.INSTANCE == null ? CraftingCostModel.Mode.LEGACY : ConfigHolder.INSTANCE.ae2GraphByteCostMode;
        this.exactBytes = CraftingCostModel.bytes(graph, costMode, key -> key.getType().getAmountPerByte());
        this.bytes = ExactAmounts.capped(exactBytes);
    }

    public GraphPlan<AEKey> graph() {
        return graph;
    }

    public boolean fallback() {
        return fallback;
    }

    public UUID id() {
        return id;
    }

    public synchronized GraphRingView display() {
        if (display == null) display = new GraphRingView(id, graph, ExactAmounts.longView(selectedCounts));
        return display;
    }

    public synchronized CompletableFuture<GraphRingView> displayAsync() {
        if (display != null) return CompletableFuture.completedFuture(display);
        if (displayWork == null) displayWork = CraftingEngineRouter.describe(new GraphRingView.Builder(id, graph, ExactAmounts.longView(selectedCounts)))
                .thenApply(view -> {
                    display = view;
                    return view;
                });
        return displayWork;
    }

    public Map<String, IPatternDetails> bindings() {
        return bindings;
    }

    public Map<AEKey, Long> emitted() {
        return ExactAmounts.longView(emitted);
    }

    public Map<AEKey, BigInteger> emittedExact() {
        return emitted;
    }

    /**
     * UI-only snapshot for integrations that cast ICraftingPlan to AE's concrete
     * record (notably AE2 Crafting Tree). Never submit this view to a CPU: the
     * original AeGraphPlan retains the verified steps, seeds and execution owner.
     */
    public CraftingPlan summaryView() {
        return new CraftingPlan(finalOutput(), bytes(), simulation(), multiplePaths(),
                usedItems(), emittedItems(), missingItems(), patternTimes());
    }

    @Override
    public GenericStack finalOutput() {
        return new GenericStack(graph.target(), graph.amount());
    }

    @Override
    public long bytes() {
        return bytes;
    }

    public BigInteger exactBytes() {
        return exactBytes;
    }

    public CraftingCostModel.Mode costMode() {
        return costMode;
    }

    /** The long UI view must not discount an exact cost larger than Long.MAX_VALUE. */
    public boolean fitsStorage(long available, boolean unbounded) {
        return bytes <= available && (unbounded || exactBytes.compareTo(BigInteger.valueOf(available)) <= 0);
    }

    @Override
    public boolean simulation() {
        return !graph.feasible();
    }

    @Override
    public boolean multiplePaths() {
        return true;
    }

    @Override
    public KeyCounter emittedItems() {
        return counter(emitted());
    }

    @Override
    public KeyCounter missingItems() {
        return counter(graph.missing());
    }

    @Override
    public KeyCounter usedItems() {
        Map<AEKey, BigInteger> used = new LinkedHashMap<>(graph.initialExact());
        emitted.forEach((key, count) -> used.compute(key, (ignored, amount) -> amount.subtract(count)));
        // AE's summary adds missingItems separately. initial is the complete
        // required inventory, so including its missing portion here double-counts it.
        graph.missingExact().forEach((key, count) -> used.compute(key, (ignored, amount) -> amount.subtract(count)));
        return counter(ExactAmounts.longView(used));
    }

    @Override
    public Map<IPatternDetails, Long> patternTimes() {
        return selectedPatterns;
    }

    public static KeyCounter counter(Map<AEKey, Long> amounts) {
        KeyCounter result = new KeyCounter();
        amounts.forEach((key, amount) -> { if (amount > 0) result.add(key, amount); });
        return result;
    }
}
