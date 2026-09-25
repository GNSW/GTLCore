package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.integration.ae2.graph.core.*;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.me.service.CraftingService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Main-thread bridge to registered effective patterns and their real provider slots. */
public final class GtlExecutionAdapter implements GraphJobRuntime.Adapter<AEKey> {

    private final GraphCpuHost host;
    private final CraftingLink link;
    private final Map<String, IPatternDetails> bindings = new HashMap<>();
    private final Map<String, Integer> rotations = new HashMap<>();
    private long epoch = Long.MIN_VALUE;
    private CraftingService service;
    private IEnergyService energy;
    private ICraftingProvider selectedProvider;
    private IPatternDetails selectedPattern;
    private KeyCounter[] selectedInputs;
    private double selectedPower;
    private long selectedBatch;
    private String reason = "";
    private String bindingFailure = "";
    private String bindingDetail = "";

    public GtlExecutionAdapter(GraphCpuHost host, CraftingLink link) {
        this.host = host;
        this.link = link;
    }

    public void services(CraftingService service, IEnergyService energy) {
        this.service = service;
        this.energy = energy;
        long version = ((GraphRequestTracker) service).gtlcore$graphProviderGeneration();
        if (version != epoch) {
            bindings.clear();
            rotations.clear();
            epoch = version;
        }
    }

    public String reason() {
        return reason;
    }

    public String bindingFailure() {
        return bindingFailure;
    }

    public String bindingDetail() {
        return bindingDetail;
    }

    public String waitingDetails(GraphPlan<AEKey> plan, Map<AEKey, Long> expected) {
        List<String> details = new ArrayList<>();
        int checked = 0;
        for (GraphRecipe<AEKey> recipe : plan.recipes().values()) {
            if (++checked > 4096 || details.size() >= 4) break;
            if (recipe.executionOutputs().keySet().stream().noneMatch(expected::containsKey)) continue;
            IPatternDetails pattern = resolve(recipe);
            if (pattern == null) {
                details.add(bindingFailure + ": " + bindingDetail);
                continue;
            }
            int providers = 0;
            for (ICraftingProvider provider : service.getProviders(pattern)) {
                if (++providers > 2 || details.size() >= 4) break;
                details.add(provider instanceof org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine buffer ?
                        buffer.gtlcore$graphDiagnostic(pattern) : provider.getClass().getName() + "; busy=" + provider.isBusy());
            }
            if (providers == 0) details.add("NO_REGISTERED_PROVIDER: " + recipe.id());
        }
        return details.toString();
    }

    public IPatternDetails resolve(GraphRecipe<AEKey> recipe) {
        bindingFailure = "NO_REGISTERED_PATTERN";
        bindingDetail = "";
        IPatternDetails existing = bindings.get(recipe.binding());
        if (existing != null && matches(existing, recipe)) return existing;
        bindings.remove(recipe.binding());
        for (AEKey key : recipe.executionOutputs().keySet()) {
            for (IPatternDetails pattern : service.getCraftingFor(key)) {
                if (matches(pattern, recipe)) {
                    bindings.put(recipe.binding(), pattern);
                    return pattern;
                }
            }
        }
        return null;
    }

    private boolean matches(IPatternDetails pattern, GraphRecipe<AEKey> recipe) {
        String fingerprint = PatternFingerprint.of(pattern);
        if (!fingerprint.equals(recipe.binding())) {
            if (bindingFailure.equals("NO_REGISTERED_PATTERN")) mismatch("PATTERN_FINGERPRINT_CHANGED",
                    "current=" + fingerprint + " pattern=" + pattern.getClass().getName());
            return false;
        }
        long[] selectedMultipliers = new long[pattern.getInputs().length];
        Map<AEKey, Long> currentOutputs = new LinkedHashMap<>();
        for (var output : pattern.getOutputs()) currentOutputs.merge(output.what(), output.amount(), CheckedAmounts::add);
        for (int i = 0; i < recipe.slots().size(); i++) {
            var slot = recipe.slots().get(i);
            int inputSlot = slot.inputSlot() < 0 ? i : slot.inputSlot();
            if (inputSlot < 0 || inputSlot >= pattern.getInputs().length) return mismatch("INPUT_SLOT_CHANGED", "slot=" + inputSlot + " current_slots=" + pattern.getInputs().length);
            var input = pattern.getInputs()[inputSlot];
            if (slot.configuration() != (pattern.supportsPushInputsToExternalInventory() && GtlDispatchPolicy.configuration(slot.key()))) return mismatch("INPUT_CONFIGURATION_CHANGED", "slot=" + slot);
            if (slot.reusable() != (pattern.supportsPushInputsToExternalInventory() && GtlDispatchPolicy.reusable(slot.key()))) return mismatch("INPUT_REUSE_CHANGED", "slot=" + slot);
            if (!input.isValid(slot.key(), host.level())) return mismatch("INPUT_NOT_VALID", "slot=" + slot);
            long copies = 0;
            for (var possible : input.getPossibleInputs()) {
                if (possible.what().getPrimaryKey().equals(slot.key().getPrimaryKey()) && slot.amount() % possible.amount() == 0) {
                    copies = slot.amount() / possible.amount();
                    break;
                }
            }
            if (copies <= 0) return mismatch("INPUT_AMOUNT_CHANGED", "slot=" + slot);
            selectedMultipliers[inputSlot] = CheckedAmounts.add(selectedMultipliers[inputSlot], copies);
            AEKey returned = input.getRemainingKey(slot.key());
            if (returned != null) currentOutputs.merge(returned, copies, CheckedAmounts::add);
        }
        for (int i = 0; i < selectedMultipliers.length; i++) if (selectedMultipliers[i] != pattern.getInputs()[i].getMultiplier())
            return mismatch("INPUT_MULTIPLIER_CHANGED", "slot=" + i + " selected=" + selectedMultipliers[i] + " current=" + pattern.getInputs()[i].getMultiplier());
        if (!currentOutputs.equals(recipe.executionOutputs())) return mismatch("REMAINDER_OR_OUTPUT_CHANGED", "selected=" + recipe.executionOutputs() + " current=" + currentOutputs);
        bindingFailure = "";
        bindingDetail = "";
        return true;
    }

    private boolean mismatch(String failure, String detail) {
        bindingFailure = failure;
        bindingDetail = detail;
        return false;
    }

    @Override
    public long capacity(GraphRecipe<AEKey> recipe, long requested) {
        selectedProvider = null;
        selectedPattern = resolve(recipe);
        if (selectedPattern == null) {
            reason = "PLAN_STALE_OR_PROVIDER_OFFLINE";
            return 0;
        }
        List<ICraftingProvider> providers = new ArrayList<>();
        service.getProviders(selectedPattern).forEach(providers::add);
        if (providers.isEmpty()) {
            reason = "PROVIDER_OFFLINE";
            return 0;
        }
        int start = Math.floorMod(rotations.getOrDefault(recipe.binding(), 0), providers.size());
        for (int offset = 0; offset < Math.min(64, providers.size()); offset++) {
            int index = (start + offset) % providers.size();
            ICraftingProvider provider = providers.get(index);
            rotations.put(recipe.binding(), index + 1);
            if (provider.isBusy()) {
                reason = "PROVIDERS_BUSY";
                continue;
            }
            if (!GraphDispatchContext.call(() -> GraphDispatchContext.allowed(provider, selectedPattern))) {
                reason = "MISSING_TOOL";
                continue;
            }
            boolean expanded = GtlDispatchPolicy.expanded(selectedPattern, provider);
            long batch = GraphDispatchContext.call(recipe.inputs(), requested, () -> {
                long offered = GtlDispatchPolicy.capacity(selectedPattern, provider, requested, expanded);
                return GraphDispatchContext.capacity(provider, selectedPattern, offered);
            });
            if (batch == 0) {
                reason = "PROVIDERS_BUSY";
                continue;
            }
            batch = Math.min(requested, batch);
            KeyCounter[] inputs = inputs(recipe, batch, selectedPattern.getInputs().length);
            double power = GtlDispatchPolicy.power(CraftingCpuHelper.calculatePatternPower(inputs), batch, expanded);
            if (!Double.isFinite(power) || power < 0) throw new IllegalStateException("Invalid pattern energy quote");
            if (energy.extractAEPower(power, Actionable.SIMULATE, PowerMultiplier.CONFIG) < power - 0.01) {
                reason = "WAIT_ENERGY";
                continue;
            }
            selectedProvider = provider;
            selectedInputs = inputs;
            selectedPower = power;
            selectedBatch = batch;
            reason = "";
            return batch;
        }
        return 0;
    }

    private static KeyCounter[] inputs(GraphRecipe<AEKey> recipe, long batch, int slots) {
        KeyCounter[] inputs = new KeyCounter[slots];
        for (int i = 0; i < slots; i++) inputs[i] = new KeyCounter();
        for (int i = 0; i < recipe.slots().size(); i++) {
            var slot = recipe.slots().get(i);
            int index = slot.inputSlot() < 0 ? i : slot.inputSlot();
            inputs[index].add(slot.key(), CheckedAmounts.multiply(slot.amount(), slot.configuration() ? 1 : batch));
        }
        return inputs;
    }

    @Override
    public GraphJobRuntime.Outcome push(GraphRecipe<AEKey> recipe, long runs, Map<AEKey, Long> inputs) {
        if (selectedProvider == null || selectedBatch != runs) throw new IllegalStateException("Unprepared graph dispatch");
        for (AEKey key : recipe.executionOutputs().keySet()) {
            host.requesting(key, true);
            ((GraphRequestTracker) service).gtlcore$expectGraphOutput(key);
        }
        boolean accepted = GraphDispatchContext.call(recipe.inputs(), runs, () -> {
            if (!GraphDispatchContext.allowed(selectedProvider, selectedPattern) ||
                    GraphDispatchContext.capacity(selectedProvider, selectedPattern, runs) < runs)
                return false;
            return selectedProvider.pushPattern(selectedPattern, selectedInputs);
        });
        if (!accepted) {
            // A false return is refundable only under the provider's no-side-effect
            // contract. Detect consumed/mutated slot counters rather than knowingly
            // cloning transferred inputs back into the CPU.
            KeyCounter[] original = inputs(recipe, runs, selectedInputs.length);
            for (int i = 0; i < original.length; i++) {
                for (var entry : original[i]) if (selectedInputs[i].get(entry.getKey()) != entry.getLongValue()) return GraphJobRuntime.Outcome.IN_DOUBT;
                for (var entry : selectedInputs[i]) if (original[i].get(entry.getKey()) != entry.getLongValue()) return GraphJobRuntime.Outcome.IN_DOUBT;
            }
            reason = "PROVIDER_REJECTED";
            return GraphJobRuntime.Outcome.REJECTED;
        }
        double extracted = energy.extractAEPower(selectedPower, Actionable.MODULATE, PowerMultiplier.CONFIG);
        if (extracted < selectedPower - 0.01) return GraphJobRuntime.Outcome.IN_DOUBT;
        return GraphJobRuntime.Outcome.ACCEPTED;
    }

    @Override
    public long deliver(AEKey key, long amount) {
        if (host.grid() == null) return 0;
        return link.isStandalone() ? host.grid().getStorageService().getInventory().insert(key, amount, Actionable.MODULATE, host.source()) :
                link.insert(key, amount, Actionable.MODULATE);
    }

    @Override
    public long refund(AEKey key, long amount) {
        if (host.grid() == null) return 0;
        return host.grid().getStorageService().getInventory().insert(key, amount, Actionable.MODULATE, host.source());
    }
}
