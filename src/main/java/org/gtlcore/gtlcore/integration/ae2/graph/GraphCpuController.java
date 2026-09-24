package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.crafting.CraftingDispatchReason;
import org.gtlcore.gtlcore.integration.ae2.crafting.transfinite.MissingCraftingPlan;
import org.gtlcore.gtlcore.integration.ae2.graph.core.*;
import org.gtlcore.gtlcore.mixin.ae2.logic.ElapsedTimeTrackerAccessor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import appeng.api.config.Actionable;
import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.CraftingJobStatusPacket;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.hooks.ticking.TickHandler;
import appeng.me.service.CraftingService;

import java.util.*;

/** AE CPU compatibility shell; it never hands an executing graph task to legacy logic. */
public final class GraphCpuController {

    private final GraphCpuHost host;
    private final long[] usedOps = new long[3];
    private GraphJobRuntime<AEKey> runtime;
    private CraftingLink link;
    private GtlExecutionAdapter adapter;
    private ElapsedTimeTracker tracker = new ElapsedTimeTracker();
    private CompoundTag unreadable;
    private Integer playerId;
    private Set<AEKey> indexed = Set.of();
    private long observedVersion = -1;
    private long modifiedTick;
    private long tickNanos;
    private long tickCalls;
    private String lastDiagnostic = "";
    private final GtlPatternCatalog replanCatalog = new GtlPatternCatalog();
    private GraphPlanningRequest replanRequest;
    private GraphJobRuntime.ReplanCheckpoint<AEKey> checkpoint;
    private GraphStorageWatch.Subscription dependencyWatch;
    private Map<AEKey, List<String>> failedBindings = Map.of();
    private long providerGeneration = Long.MIN_VALUE;
    private boolean dependencyChanged;
    private Iterator<String> bindingsToCheck;

    public GraphCpuController(GraphCpuHost host) {
        this.host = host;
    }

    public static boolean isGraphPlan(ICraftingPlan plan) {
        return plan instanceof AeGraphPlan || plan instanceof MissingCraftingPlan missing && missing.delegate() instanceof AeGraphPlan;
    }

    public boolean ownsTask() {
        return runtime != null || unreadable != null;
    }

    public ICraftingSubmitResult submit(IGrid grid, ICraftingPlan supplied, IActionSource source, ICraftingRequester requester) {
        if (ownsTask() || !host.orphanInventory().list.isEmpty()) return CraftingSubmitResult.CPU_BUSY;
        if (!host.active()) return CraftingSubmitResult.CPU_OFFLINE;
        if (host.cpu().getAvailableStorage() < supplied.bytes()) return CraftingSubmitResult.CPU_TOO_SMALL;
        boolean allowMissing = supplied instanceof MissingCraftingPlan;
        AeGraphPlan view = (AeGraphPlan) (allowMissing ? ((MissingCraftingPlan) supplied).delegate() : supplied);
        GraphPlan<AEKey> plan = view.graph();
        if (!plan.feasible()) {
            if (!allowMissing || (plan.result() != GraphPlan.Result.MISSING_INPUT && plan.result() != GraphPlan.Result.MISSING_SEED)) return CraftingSubmitResult.INCOMPLETE_PLAN;
            plan = new GraphPlan<>(plan.target(), plan.amount(), plan.preserveSeeds(), plan.steps(), plan.recipes(),
                    plan.initial(), plan.seeds(), Map.of(), GraphPlan.Result.FEASIBLE, plan.searchNodes(), plan.planningNanos());
        }
        PlanVerifier.verify(plan);
        UUID id = UUID.randomUUID();
        CraftingLink cpuLink = new CraftingLink(CraftingCpuHelper.generateLinkData(id, requester == null, false), host.cpu());
        GtlExecutionAdapter preparedAdapter = new GtlExecutionAdapter(host, cpuLink);
        preparedAdapter.services((CraftingService) grid.getCraftingService(), grid.getEnergyService());
        for (String recipe : plan.patternTimes().keySet()) {
            var selected = plan.recipes().get(recipe);
            if (preparedAdapter.resolve(selected) == null) {
                // Do not let Retry reproduce a catalog that execution has just rejected.
                // No inventory has been extracted yet; the current plan remains rejected.
                ((GraphRequestTracker) grid.getCraftingService()).gtlcore$invalidateGraphBinding(selected.binding());
                if (ConfigHolder.INSTANCE.ae2GraphDiagnosticLogging) GTLCore.LOGGER.warn(
                        "[Graph Crafting] submit rejected plan={} target={} amount={} recipe={} binding={} reason={} detail={} provider_revision={} catalog_invalidated=true",
                        view.id(), plan.target(), plan.amount(), recipe, selected.binding(), preparedAdapter.bindingFailure(), preparedAdapter.bindingDetail(),
                        ((GraphRequestTracker) grid.getCraftingService()).gtlcore$graphProviderGeneration());
                return CraftingSubmitResult.INCOMPLETE_PLAN;
            }
        }
        Map<AEKey, Long> taken = new LinkedHashMap<>(), waiting = new LinkedHashMap<>(view.emitted());
        var storage = grid.getStorageService().getInventory();
        if (!allowMissing) {
            for (var entry : view.usedItems()) {
                long available = storage.extract(entry.getKey(), entry.getLongValue(), Actionable.SIMULATE, source);
                if (available < entry.getLongValue()) return CraftingSubmitResult.missingIngredient(new GenericStack(entry.getKey(), entry.getLongValue() - available));
            }
        }
        try {
            for (var entry : plan.initial().entrySet()) {
                long requested = entry.getValue() - waiting.getOrDefault(entry.getKey(), 0L);
                long extracted = storage.extract(entry.getKey(), requested, Actionable.MODULATE, source);
                if (extracted < 0 || extracted > requested) throw new IllegalStateException("Invalid initial extraction");
                if (extracted > 0) taken.put(entry.getKey(), extracted);
                if (extracted < requested) {
                    if (allowMissing) waiting.merge(entry.getKey(), requested - extracted, CheckedAmounts::add);
                    else {
                        // A short actual extraction may follow successful simulation. Transfer
                        // every acquired item to AE's persistent idle/refund inventory.
                        taken.forEach((key, count) -> host.orphanInventory().insert(key, count, Actionable.MODULATE));
                        taken.clear();
                        host.dirty();
                        return CraftingSubmitResult.missingIngredient(new GenericStack(entry.getKey(), requested - extracted));
                    }
                }
            }
        } catch (RuntimeException e) {
            taken.forEach((key, count) -> host.orphanInventory().insert(key, count, Actionable.MODULATE));
            host.dirty();
            throw e;
        }
        try {
            runtime = new GraphJobRuntime<>(plan, taken, waiting);
        } catch (RuntimeException e) {
            taken.forEach((key, count) -> host.orphanInventory().insert(key, count, Actionable.MODULATE));
            host.dirty();
            throw e;
        }
        link = cpuLink;
        adapter = preparedAdapter;
        // A CPU is reused across jobs; diagnostics and dispatch windows belong to
        // this order rather than to the lifetime of the physical CPU.
        tickNanos = 0;
        tickCalls = 0;
        Arrays.fill(usedOps, 0);
        lastDiagnostic = "";
        providerGeneration = Long.MIN_VALUE;
        observedVersion = -1;
        playerId = source.player().map(player -> player instanceof ServerPlayer serverPlayer ? IPlayerRegistry.getPlayerId(serverPlayer) : null).orElse(null);
        tracker = new ElapsedTimeTracker();
        var time = (ElapsedTimeTrackerAccessor) tracker;
        waiting.forEach((key, count) -> time.invokeAddMaxItems(count, key.getType()));
        plan.patternTimes().forEach((recipe, count) -> runtime.plan().recipes().get(recipe).outputs().forEach((key, amount) -> time.invokeAddMaxItems(CheckedAmounts.multiply(amount, count), key.getType())));
        publish(true);
        notifyOwner(CraftingJobStatusPacket.Status.STARTED);
        if (ConfigHolder.INSTANCE.ae2GraphDiagnosticLogging) GTLCore.LOGGER.info(
                "[Graph Crafting] submit accepted plan={} job={} cpu={} target={} amount={}",
                view.id(), id, host.cpu().getClass().getName(), plan.target(), plan.amount());
        if (requester == null) return CraftingSubmitResult.successful(null);
        CraftingLink requesterLink = new CraftingLink(CraftingCpuHelper.generateLinkData(id, false, true), requester);
        CraftingService service = (CraftingService) grid.getCraftingService();
        service.addLink(cpuLink);
        service.addLink(requesterLink);
        return CraftingSubmitResult.successful(requesterLink);
    }

    public void tick(IEnergyService energy, CraftingService service) {
        if (runtime == null || !host.active()) return;
        long start = System.nanoTime();
        if (link.isCanceled()) runtime.cancel();
        adapter.services(service, energy);
        updateReplan(service);
        long remaining = Math.max(0, host.dispatchCapacity() - usedOps[0] - usedOps[1] - usedOps[2]);
        // A work budget limits calls, not material quantity. Batch size remains long.
        int budget = (int) Math.min(4096, remaining);
        if (runtime.state() == GraphJobRuntime.State.SETTLING || runtime.state() == GraphJobRuntime.State.CANCELLING) budget = 64;
        int dispatched = runtime.tick(adapter, TickHandler.instance().getCurrentTick(), budget);
        usedOps[2] = usedOps[1];
        usedOps[1] = usedOps[0];
        usedOps[0] = dispatched;
        tickNanos += System.nanoTime() - start;
        tickCalls++;
        if (runtime.state() == GraphJobRuntime.State.NEEDS_ATTENTION && !runtime.reason().equals(lastDiagnostic)) {
            lastDiagnostic = runtime.reason();
            GTLCore.LOGGER.error("[Graph Crafting] job={} paused: {}; no automatic redispatch or input refund", link.getCraftingID(), lastDiagnostic);
        }
        publish(false);
        if (runtime.finished()) finish();
    }

    public long insert(AEKey key, long amount, Actionable mode) {
        if (runtime == null) return 0;
        long accepted = runtime.accept(key, amount, mode == Actionable.SIMULATE);
        if (mode == Actionable.MODULATE && accepted > 0) {
            ((ElapsedTimeTrackerAccessor) tracker).invokeDecrementItems(accepted, key.getType());
            host.changed(key);
            host.requesting(key, runtime.waiting(key) > 0);
            host.dirty();
            modifiedTick = TickHandler.instance().getCurrentTick();
        }
        return accepted;
    }

    public void cancel() {
        if (runtime == null) return;
        clearReplan();
        link.cancel();
        runtime.cancel();
        publish(true);
    }

    /** Called by the native CPU's breakCluster after it cancels the task. */
    public void handoffCancelledInventory() {
        if (runtime == null || runtime.state() != GraphJobRuntime.State.CANCELLING) return;
        if (ConfigHolder.INSTANCE.ae2GraphDiagnosticLogging) GTLCore.LOGGER.info(
                "[Graph Crafting] job={} cancelled inventory handed to AE for CPU rebuild/break", link.getCraftingID());
        runtime.detachCancelledInventory().forEach((key, amount) -> host.orphanInventory().insert(key, amount, Actionable.MODULATE));
        finish();
    }

    private void finish() {
        clearReplan();
        boolean success = runtime.state() == GraphJobRuntime.State.COMPLETED;
        if (success) link.markDone();
        else link.cancel();
        notifyOwner(success ? CraftingJobStatusPacket.Status.FINISHED : CraftingJobStatusPacket.Status.CANCELLED);
        if (ConfigHolder.INSTANCE.ae2GraphDiagnosticLogging) GTLCore.LOGGER.info(
                "[Graph Crafting] job={} state={} dispatches={} rejects={} checks={} tick_calls={} tick_ms={}",
                link.getCraftingID(), runtime.state(), runtime.dispatches(), runtime.rejections(), runtime.checks(), tickCalls, tickNanos / 1_000_000.0);
        for (AEKey key : indexed) {
            host.requesting(key, false);
            host.changed(key);
        }
        indexed = Set.of();
        runtime = null;
        adapter = null;
        link = null;
        host.output(null);
        host.dirty();
        modifiedTick = TickHandler.instance().getCurrentTick();
    }

    private void publish(boolean force) {
        if (runtime == null || !force && runtime.version() == observedVersion) return;
        observedVersion = runtime.version();
        Set<AEKey> next = Set.copyOf(runtime.expected().keySet());
        Set<AEKey> changed = new HashSet<>(indexed);
        changed.addAll(next);
        changed.addAll(runtime.drainChangedKeys());
        for (AEKey key : changed) {
            host.requesting(key, next.contains(key));
            host.changed(key);
        }
        if (host.grid() != null) for (AEKey key : next) ((GraphRequestTracker) host.grid().getCraftingService()).gtlcore$expectGraphOutput(key);
        indexed = next;
        host.output(finalOutput());
        host.dirty();
        modifiedTick = TickHandler.instance().getCurrentTick();
    }

    public void read(CompoundTag parent) {
        CompoundTag tag = parent.getCompound(GraphJobCodec.NBT_KEY);
        try {
            runtime = new GraphJobRuntime<>(GraphJobCodec.read(tag));
            link = new CraftingLink(tag.getCompound("link"), host.cpu());
            adapter = new GtlExecutionAdapter(host, link);
            tracker = new ElapsedTimeTracker(tag.getCompound("time"));
            playerId = tag.contains("playerId") ? tag.getInt("playerId") : null;
            for (int i = 0; i < usedOps.length; i++) usedOps[i] = Math.max(0, Math.min(4096, tag.getLong("ops" + i)));
            publish(true);
        } catch (RuntimeException e) {
            runtime = null;
            unreadable = tag.copy(); // preserve data verbatim rather than deleting unknown/corrupt task inventory
            GTLCore.LOGGER.error("Graph task could not be loaded; CPU paused and original NBT retained", e);
        }
    }

    public void write(CompoundTag parent) {
        if (unreadable != null) {
            parent.put(GraphJobCodec.NBT_KEY, unreadable.copy());
            return;
        }
        if (runtime == null) {
            parent.remove(GraphJobCodec.NBT_KEY);
            return;
        }
        CompoundTag tag = GraphJobCodec.write(runtime.snapshot());
        CompoundTag linkTag = new CompoundTag();
        link.writeToNBT(linkTag);
        tag.put("link", linkTag);
        tag.put("time", tracker.writeToNBT());
        if (playerId != null) tag.putInt("playerId", playerId);
        for (int i = 0; i < usedOps.length; i++) tag.putLong("ops" + i, usedOps[i]);
        parent.put(GraphJobCodec.NBT_KEY, tag);
        parent.remove("job");
    }

    public GenericStack finalOutput() {
        if (runtime == null || runtime.state() == GraphJobRuntime.State.CANCELLING || runtime.finished()) return null;
        return new GenericStack(runtime.plan().target(), runtime.remainingDelivery());
    }

    public CraftingLink link() {
        return link;
    }

    public ElapsedTimeTracker tracker() {
        return tracker;
    }

    public long modifiedTick() {
        return modifiedTick;
    }

    public long stored(AEKey key) {
        return runtime == null ? 0 : runtime.held(key);
    }

    public long waiting(AEKey key) {
        return runtime == null ? 0 : runtime.waiting(key);
    }

    public void waitingKeys(Set<AEKey> out) {
        if (runtime != null) out.addAll(runtime.expected().keySet());
    }

    public boolean suspended() {
        return runtime != null && runtime.suspended();
    }

    public void suspend(boolean value) {
        if (runtime != null) {
            runtime.suspend(value);
            publish(true);
        }
    }

    public boolean cantStore() {
        return runtime != null && (runtime.state() == GraphJobRuntime.State.SETTLING || runtime.state() == GraphJobRuntime.State.CANCELLING);
    }

    public long pending(AEKey key) {
        return runtime == null ? 0 : runtime.pendingOutput(key);
    }

    public void allItems(KeyCounter out) {
        if (runtime == null) return;
        Set<AEKey> keys = new LinkedHashSet<>(runtime.owned().keySet());
        keys.addAll(runtime.expected().keySet());
        keys.addAll(runtime.pendingKeys());
        for (AEKey key : keys) {
            // UI totals only; never used for transfer/accounting.
            long amount;
            try {
                amount = CheckedAmounts.add(CheckedAmounts.add(stored(key), waiting(key)), pending(key));
            } catch (ArithmeticException e) {
                amount = Long.MAX_VALUE;
            }
            if (amount > 0) out.add(key, amount);
        }
    }

    public int reasonMask(AEKey key) {
        if (unreadable != null) return CraftingDispatchReason.JOB_SUSPENDED.mask();
        if (runtime == null) return 0;
        if (runtime.state() == GraphJobRuntime.State.NEEDS_ATTENTION) return runtime.reason().contains("IN_DOUBT") ?
                CraftingDispatchReason.DISPATCH_IN_DOUBT.mask() : CraftingDispatchReason.PLAN_STALE.mask();
        if (runtime.suspended()) return CraftingDispatchReason.JOB_SUSPENDED.mask();
        if (!host.active()) return CraftingDispatchReason.CPU_INACTIVE.mask();
        if (runtime.reason().startsWith("REPLAN")) return CraftingDispatchReason.PLAN_STALE.mask();
        int waiting = 0;
        if (runtime.externalWaiting(key) > 0) waiting |= CraftingDispatchReason.WAITING_FOR_EXTERNAL.mask();
        if (runtime.inFlight(key) > 0) waiting |= CraftingDispatchReason.WAITING_FOR_OUTPUTS.mask();
        var recovery = runtime.recovery();
        if (recovery.seeds().containsKey(key) && (recovery.status() == RecoveryObligation.Status.IN_FLIGHT ||
                recovery.status() == RecoveryObligation.Status.INTERMEDIATE))
            waiting |= CraftingDispatchReason.RECOVERY_PENDING.mask();
        if (waiting != 0) return waiting;
        if (runtime.reason().equals("WAIT_INPUT") || runtime.reason().equals("WAIT_PREFIX_RESERVATION"))
            return CraftingDispatchReason.WAITING_FOR_INPUTS.mask();
        return switch (adapter == null ? "" : adapter.reason()) {
            case "WAIT_ENERGY" -> CraftingDispatchReason.INSUFFICIENT_POWER.mask();
            case "PROVIDER_OFFLINE" -> CraftingDispatchReason.NO_PROVIDER.mask();
            case "PLAN_STALE_OR_PROVIDER_OFFLINE" -> CraftingDispatchReason.PLAN_STALE.mask();
            case "PROVIDERS_BUSY" -> CraftingDispatchReason.PROVIDERS_BUSY.mask();
            case "PROVIDER_REJECTED" -> CraftingDispatchReason.PROVIDER_REJECTED.mask();
            case "MISSING_TOOL" -> CraftingDispatchReason.MISSING_TOOL.mask();
            default -> 0;
        };
    }

    private void notifyOwner(CraftingJobStatusPacket.Status status) {
        if (playerId == null || runtime == null) return;
        ServerPlayer player = IPlayerRegistry.getConnected(host.level().getServer(), playerId);
        if (player != null) NetworkHandler.instance().sendTo(new CraftingJobStatusPacket(link.getCraftingID(),
                runtime.plan().target(), runtime.plan().amount(), runtime.remainingDelivery(), status), player);
    }

    private void clearReplan() {
        if (replanRequest != null) replanRequest.cancel(false);
        replanRequest = null;
        checkpoint = null;
        if (dependencyWatch != null) dependencyWatch.close();
        dependencyWatch = null;
        failedBindings = Map.of();
        dependencyChanged = false;
        bindingsToCheck = null;
    }

    /** Poll completed futures only; world access and ownership changes stay on this thread. */
    private void updateReplan(CraftingService service) {
        if (runtime.state() != GraphJobRuntime.State.RUNNING) {
            clearReplan();
            return;
        }
        long generation = ((GraphRequestTracker) service).gtlcore$graphProviderGeneration();
        if (generation != providerGeneration) {
            providerGeneration = generation;
            bindingsToCheck = runtime.pendingRuns().keySet().iterator();
            if (checkpoint != null && replanRequest == null && !bindingSignatures(service, failedBindings.keySet()).equals(failedBindings))
                dependencyChanged = true;
        }
        if (replanRequest != null) {
            if (!replanRequest.isDone()) return;
            GraphPlanningRequest request = replanRequest;
            replanRequest = null;
            if (!runtime.replanCurrent(checkpoint.epoch())) {
                clearReplan();
                return;
            }
            String failure = "REPLAN_UNKNOWN";
            try {
                AeGraphPlan selected = (AeGraphPlan) request.join();
                if (!selected.graph().feasible()) failure = "REPLAN_" + selected.graph().result();
                else if (selected.bytes() > host.cpu().getAvailableStorage()) failure = "REPLAN_CPU_TOO_SMALL";
                else if (installReplan(selected)) {
                    if (ConfigHolder.INSTANCE.ae2GraphDiagnosticLogging)
                        GTLCore.LOGGER.info("[Graph Crafting] job={} replan=installed committed_recipes={} remaining_recipes={}",
                                link.getCraftingID(), runtime.committedRuns().size(), runtime.pendingRuns().size());
                    clearReplan();
                    publish(true);
                    return;
                } else failure = "REPLAN_RESOURCES_OR_PROVIDER_CHANGED";
            } catch (RuntimeException e) {
                Throwable cause = e;
                while (cause.getCause() != null) cause = cause.getCause();
                failure = "REPLAN_" + cause.getClass().getSimpleName() + ": " + cause.getMessage();
            }
            Set<AEKey> dependencies = new LinkedHashSet<>(request.dependencies());
            dependencies.add(runtime.plan().target());
            runtime.plan().recipes().values().forEach(recipe -> {
                dependencies.addAll(recipe.inputs().keySet());
                dependencies.addAll(recipe.outputs().keySet());
            });
            failedBindings = bindingSignatures(service, dependencies);
            if (dependencyWatch != null) dependencyWatch.close();
            dependencyChanged = false;
            dependencyWatch = ((GraphStorageWatch) host.grid().getStorageService())
                    .gtlcore$watchGraphResources(dependencies, () -> dependencyChanged = true);
            runtime.waitForReplanDependency(checkpoint.epoch(), failure);
            GTLCore.LOGGER.info("[Graph Crafting] job={} {}; waiting for relevant storage/provider change", link.getCraftingID(), failure);
            return;
        }
        if (checkpoint != null) {
            if (!dependencyChanged) return;
            runtime.abortReplan(checkpoint.epoch(), "");
            clearReplan();
            startReplan(service);
            return;
        }
        if (runtime.suspended() || bindingsToCheck == null) return;
        long deadline = System.nanoTime() + 1_000_000L;
        for (int checked = 0; checked < 32 && bindingsToCheck.hasNext(); checked++) {
            var recipe = runtime.plan().recipes().get(bindingsToCheck.next());
            if (recipe != null && adapter.resolve(recipe) == null) {
                bindingsToCheck = null;
                startReplan(service);
                return;
            }
            if (System.nanoTime() - deadline >= 0) return;
        }
        if (!bindingsToCheck.hasNext()) bindingsToCheck = null;
    }

    private void startReplan(CraftingService service) {
        if (runtime.remainingDelivery() <= 0) return;
        checkpoint = runtime.beginReplan();
        replanRequest = CraftingEngineRouter.replan(replanCatalog, host, service, checkpoint, runtime.plan().preserveSeeds());
    }

    private boolean installReplan(AeGraphPlan selected) {
        GraphPlan<AEKey> replacement = selected.graph();
        for (String id : replacement.patternTimes().keySet()) if (adapter.resolve(replacement.recipes().get(id)) == null) return false;
        Map<AEKey, Long> forecast = runtime.forecastInventory();
        Map<AEKey, Long> needed = new LinkedHashMap<>(), emitted = new LinkedHashMap<>();
        replacement.initial().forEach((key, count) -> {
            long extra = Math.max(0, count - forecast.getOrDefault(key, 0L));
            long external = Math.min(extra, selected.emitted().getOrDefault(key, 0L));
            if (external > 0) emitted.put(key, external);
            if (extra > external) needed.put(key, extra - external);
        });
        var storage = host.grid().getStorageService().getInventory();
        for (var entry : needed.entrySet())
            if (storage.extract(entry.getKey(), entry.getValue(), Actionable.SIMULATE, host.source()) < entry.getValue()) return false;
        Map<AEKey, Long> taken = new LinkedHashMap<>();
        boolean committed = false;
        try {
            for (var entry : needed.entrySet()) {
                long actual = storage.extract(entry.getKey(), entry.getValue(), Actionable.MODULATE, host.source());
                if (actual < 0 || actual > entry.getValue()) throw new IllegalStateException("Invalid replan extraction");
                if (actual > 0) taken.put(entry.getKey(), actual);
                if (actual != entry.getValue()) return false;
            }
            committed = runtime.replaceSuffix(checkpoint.epoch(), replacement, taken, emitted);
            if (committed) {
                tracker = new ElapsedTimeTracker();
                var time = (ElapsedTimeTrackerAccessor) tracker;
                runtime.expected().forEach((key, amount) -> time.invokeAddMaxItems(amount, key.getType()));
                runtime.pendingKeys().forEach(key -> time.invokeAddMaxItems(runtime.pendingOutput(key), key.getType()));
            }
            return committed;
        } finally {
            if (!committed) {
                taken.forEach((key, amount) -> host.orphanInventory().insert(key, amount, Actionable.MODULATE));
                host.dirty();
            }
        }
    }

    private static Map<AEKey, List<String>> bindingSignatures(CraftingService service, Set<AEKey> keys) {
        Map<AEKey, List<String>> result = new LinkedHashMap<>();
        for (AEKey key : keys) {
            List<String> values = new ArrayList<>();
            for (var pattern : service.getCraftingFor(key)) {
                int priority = Integer.MIN_VALUE;
                for (var provider : service.getProviders(pattern)) priority = Math.max(priority, provider.getPatternPriority());
                values.add(PatternFingerprint.of(pattern) + ':' + priority);
            }
            result.put(key, List.copyOf(values));
        }
        return Map.copyOf(result);
    }
}
