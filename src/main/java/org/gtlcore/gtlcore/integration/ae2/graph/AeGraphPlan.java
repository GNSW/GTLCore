package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.integration.ae2.graph.core.CheckedAmounts;
import org.gtlcore.gtlcore.integration.ae2.graph.core.GraphPlan;
import org.gtlcore.gtlcore.integration.ae2.graph.core.GraphRecipe;
import org.gtlcore.gtlcore.integration.ae2.graph.core.PlanNodeCost;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;

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
    private final Map<AEKey, Long> emitted;
    private final long bytes;
    private final UUID id = UUID.randomUUID();
    private final Map<String, Long> selectedCounts;
    private final Map<IPatternDetails, Long> selectedPatterns;
    private volatile GraphRingView display;
    private CompletableFuture<GraphRingView> displayWork;

    public AeGraphPlan(GraphPlan<AEKey> graph, Map<String, IPatternDetails> bindings, Set<AEKey> emitable,
                       Map<AEKey, Long> stock) {
        this.graph = graph;
        this.bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
        selectedCounts = graph.patternTimes();
        Map<IPatternDetails, Long> selected = new LinkedHashMap<>();
        selectedCounts.forEach((recipe, count) -> selected.merge(
                java.util.Objects.requireNonNull(this.bindings.get(graph.recipes().get(recipe).binding()), "Missing pattern binding"),
                count, CheckedAmounts::add));
        // Pattern definitions can have clustered hash codes (large families of
        // NBT recipes). Map.copyOf's linear probing magnifies those collisions.
        // This map is owned exclusively by the immutable plan; retain its hash
        // table instead of rebuilding a second open-addressed one.
        selectedPatterns = Collections.unmodifiableMap(selected);
        Map<AEKey, Long> emissions = new LinkedHashMap<>();
        graph.initial().forEach((key, amount) -> {
            if (emitable.contains(key) && amount > stock.getOrDefault(key, 0L)) emissions.put(key, amount - stock.getOrDefault(key, 0L));
        });
        this.emitted = Map.copyOf(emissions);
        this.bytes = computeBytes(graph, selectedCounts);
    }

    public GraphPlan<AEKey> graph() {
        return graph;
    }

    public UUID id() {
        return id;
    }

    public synchronized GraphRingView display() {
        if (display == null) display = new GraphRingView(id, graph, selectedCounts);
        return display;
    }

    public synchronized CompletableFuture<GraphRingView> displayAsync() {
        if (display != null) return CompletableFuture.completedFuture(display);
        if (displayWork == null) displayWork = CraftingEngineRouter.describe(new GraphRingView.Builder(id, graph, selectedCounts))
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
        return counter(emitted);
    }

    @Override
    public KeyCounter missingItems() {
        return counter(graph.missing());
    }

    @Override
    public KeyCounter usedItems() {
        Map<AEKey, Long> used = new LinkedHashMap<>(graph.initial());
        emitted.forEach((key, count) -> used.compute(key, (ignored, amount) -> amount - count));
        // AE's summary adds missingItems separately. initial is the complete
        // required inventory, so including its missing portion here double-counts it.
        graph.missing().forEach((key, count) -> used.compute(key, (ignored, amount) -> amount - count));
        return counter(used);
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

    private static long computeBytes(GraphPlan<AEKey> graph, Map<String, Long> counts) {
        // The material charge depends on units per byte, not key identity. Sum
        // equal denominators first instead of doing rational arithmetic once for
        // every resource in a large graph.
        Map<Long, BigInteger> material = new LinkedHashMap<>();
        material.put((long) graph.target().getType().getAmountPerByte(), BigInteger.valueOf(graph.amount()));
        BigInteger runs = BigInteger.ZERO;
        for (var count : counts.entrySet()) {
            BigInteger repetitions = BigInteger.valueOf(count.getValue());
            runs = runs.add(repetitions);
            GraphRecipe<AEKey> recipe = graph.recipes().get(count.getKey());
            recipe.inputs().forEach((key, amount) -> material.merge((long) key.getType().getAmountPerByte(),
                    BigInteger.valueOf(amount).multiply(repetitions), BigInteger::add));
        }
        // AE charges 8 / amountPerByte for requested/input units plus one byte per run.
        // Sum fractions exactly before rounding; graph compression does not discount CPU storage.
        BigInteger numerator = runs.add(PlanNodeCost.count(graph, counts.keySet()).multiply(BigInteger.valueOf(8))), denominator = BigInteger.ONE;
        for (var entry : material.entrySet()) {
            BigInteger divisor = BigInteger.valueOf(entry.getKey());
            BigInteger gcd = denominator.gcd(divisor);
            BigInteger scale = divisor.divide(gcd);
            numerator = numerator.multiply(scale).add(entry.getValue().multiply(BigInteger.valueOf(8)).multiply(denominator.divide(gcd)));
            denominator = denominator.multiply(scale);
        }
        return CheckedAmounts.amount(CheckedAmounts.ceilDiv(numerator, denominator));
    }
}
