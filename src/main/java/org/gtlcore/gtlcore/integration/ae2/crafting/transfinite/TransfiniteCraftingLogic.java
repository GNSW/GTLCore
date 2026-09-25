package org.gtlcore.gtlcore.integration.ae2.crafting.transfinite;

import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.gtlcore.gtlcore.integration.ae2.crafting.CraftingDispatchReason;
import org.gtlcore.gtlcore.integration.ae2.crafting.CraftingDispatchReasonState;
import org.gtlcore.gtlcore.integration.ae2.crafting.CraftingPatternAutoExpand;
import org.gtlcore.gtlcore.integration.ae2.crafting.CraftingPatternPower;
import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingDispatchReasonProvider;
import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingJobSuspension;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphCpuController;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphCpuHost;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphJobCodec;
import org.gtlcore.gtlcore.mixin.ae2.logic.ElapsedTimeTrackerAccessor;
import org.gtlcore.gtlcore.utils.NumberUtils;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.core.AELog;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.CraftingJobStatusPacket;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.hooks.ticking.TickHandler;
import appeng.me.service.CraftingService;
import com.google.common.base.Preconditions;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class TransfiniteCraftingLogic implements ICraftingJobSuspension, ICraftingDispatchReasonProvider,
                                            GraphCpuHost {

    private static final int DISPATCH_HISTORY_LENGTH = 3;
    private static final int STORE_BATCH_SIZE = 64;
    private static final int MAX_CONSECUTIVE_STORE_FAILURES = 5;
    private static final double POWER_EPSILON = 0.01;
    private static final ElapsedTimeTracker EMPTY_TIME_TRACKER = new ElapsedTimeTracker();

    private final TransfiniteCraftingCPU cpu;
    private final GraphCpuController graph;
    private final ListCraftingInventory inventory = new ListCraftingInventory(this::postChange);
    private final long[] usedDispatches = new long[DISPATCH_HISTORY_LENGTH];
    private final Set<Consumer<AEKey>> listeners = new ObjectOpenHashSet<>();
    private final Map<IPatternDetails, Integer> workingDispatchReasons = new HashMap<>();

    private @Nullable TransfiniteCraftingJob job;
    private Map<AEKey, Integer> publishedDispatchReasons = Map.of();
    private boolean collectDispatchReasons;
    private boolean cantStoreItems;
    private boolean batchingChanges;
    private boolean dirty;
    private long lastModifiedOnTick = TickHandler.instance().getCurrentTick();

    TransfiniteCraftingLogic(TransfiniteCraftingCPU cpu) {
        this.cpu = cpu;
        this.graph = new GraphCpuController(this);
    }

    public ICraftingSubmitResult trySubmitJob(IGrid grid, ICraftingPlan plan, IActionSource source,
                                              @Nullable ICraftingRequester requester) {
        if (this.graph.ownsTask()) return CraftingSubmitResult.CPU_BUSY;
        if (GraphCpuController.isGraphPlan(plan)) {
            return this.job == null ? this.graph.submit(grid, plan, source, requester) : CraftingSubmitResult.CPU_BUSY;
        }
        if (this.job != null) {
            return CraftingSubmitResult.CPU_BUSY;
        }
        if (!this.cpu.isActive()) {
            return CraftingSubmitResult.CPU_OFFLINE;
        }
        if (this.cpu.getAvailableStorage() < plan.bytes()) {
            return CraftingSubmitResult.CPU_TOO_SMALL;
        }
        if (!this.inventory.list.isEmpty()) {
            AELog.warn("Transfinite crafting CPU inventory is not empty when a job is submitted.");
        }

        KeyCounter extractionShortfall = new KeyCounter();
        if (plan instanceof MissingCraftingPlan) {
            extractAvailableInitialItems(plan, grid, source, extractionShortfall);
        } else {
            GenericStack missingIngredient = CraftingCpuHelper.tryExtractInitialItems(
                    plan, grid, this.inventory, source);
            if (missingIngredient != null) {
                return CraftingSubmitResult.missingIngredient(missingIngredient);
            }
        }

        Integer playerId = source.player()
                .map(player -> player instanceof ServerPlayer serverPlayer ?
                        IPlayerRegistry.getPlayerId(serverPlayer) : null)
                .orElse(null);
        UUID craftId = UUID.randomUUID();
        CraftingLink cpuLink = new CraftingLink(
                CraftingCpuHelper.generateLinkData(craftId, requester == null, false), this.cpu);
        this.job = new TransfiniteCraftingJob(
                plan, extractionShortfall, cpuLink, playerId, this::onWaitingForChanged);
        indexAllWaitingItems();
        markChanged();
        notifyJobOwner(this.job, CraftingJobStatusPacket.Status.STARTED);

        if (requester == null) {
            return CraftingSubmitResult.successful(null);
        }

        CraftingLink requesterLink = new CraftingLink(
                CraftingCpuHelper.generateLinkData(craftId, false, true), requester);
        CraftingService craftingService = (CraftingService) grid.getCraftingService();
        craftingService.addLink(cpuLink);
        craftingService.addLink(requesterLink);
        return CraftingSubmitResult.successful(requesterLink);
    }

    private void extractAvailableInitialItems(ICraftingPlan plan, IGrid grid, IActionSource source,
                                              KeyCounter extractionShortfall) {
        var storage = grid.getStorageService().getInventory();
        for (var entry : plan.usedItems()) {
            AEKey key = entry.getKey();
            long required = entry.getLongValue();
            long extracted = storage.extract(key, required, Actionable.MODULATE, source);
            if (extracted > 0) {
                this.inventory.insert(key, extracted, Actionable.MODULATE);
            }
            if (extracted < required) {
                extractionShortfall.add(key, required - extracted);
            }
        }
    }

    public void tickCraftingLogic(IEnergyService energyService, CraftingService craftingService) {
        if (this.graph.ownsTask()) {
            this.graph.tick(energyService, craftingService);
            return;
        }
        this.batchingChanges = true;
        try {
            tickCraftingLogicInternal(energyService, craftingService);
        } finally {
            this.batchingChanges = false;
            flushDirty();
        }
    }

    private long tickCraftingLogicInternal(IEnergyService energyService, CraftingService craftingService) {
        this.collectDispatchReasons = !this.listeners.isEmpty();
        this.workingDispatchReasons.clear();

        if (!this.cpu.isActive()) {
            markAllRemaining(CraftingDispatchReason.CPU_INACTIVE);
            publishDispatchReasons();
            return 0;
        }

        this.cantStoreItems = false;
        if (this.job == null) {
            storeItems();
            this.cantStoreItems = !this.inventory.list.isEmpty();
            publishDispatchReasons();
            return 0;
        }
        if (this.job.getLink().isCanceled()) {
            cancel();
            publishDispatchReasons();
            return 0;
        }
        if (this.job.isSuspended()) {
            markAllRemaining(CraftingDispatchReason.JOB_SUSPENDED);
            publishDispatchReasons();
            return 0;
        }

        long recentlyUsed = 0;
        for (long used : this.usedDispatches) {
            recentlyUsed = NumberUtils.saturatedAdd(recentlyUsed, used);
        }
        long dispatchBudget = Math.max(0, this.cpu.getParallelism() - recentlyUsed);
        long dispatchedCalls = 0;
        if (dispatchBudget > 0) {
            long remainingOperations = dispatchBudget;
            while (remainingOperations > 0) {
                long pushed = executeCrafting(
                        remainingOperations, craftingService, energyService, this.cpu.getLevel());
                if (pushed <= 0) {
                    break;
                }
                dispatchedCalls = NumberUtils.saturatedAdd(dispatchedCalls, pushed);
                remainingOperations -= pushed;
            }
        } else {
            markAllRemaining(CraftingDispatchReason.CPU_OPERATION_LIMIT);
        }

        if (dispatchedCalls >= dispatchBudget && dispatchBudget > 0) {
            markAllUnclassified(CraftingDispatchReason.CPU_OPERATION_LIMIT);
        }
        System.arraycopy(this.usedDispatches, 0, this.usedDispatches, 1, this.usedDispatches.length - 1);
        this.usedDispatches[0] = dispatchedCalls;
        publishDispatchReasons();
        return dispatchedCalls;
    }

    private int getTaskKindCount() {
        return this.job == null ? 0 : this.job.getTasks().size();
    }

    private int getWaitingKindCount() {
        return this.job == null ? 0 : this.job.getWaitingFor().list.size();
    }

    public long executeCrafting(long maxDispatches, CraftingService craftingService, IEnergyService energyService, Level level) {
        if (this.graph.ownsTask()) return 0;
        TransfiniteCraftingJob currentJob = this.job;
        if (currentJob == null || maxDispatches <= 0) {
            return 0;
        }

        long dispatchedCalls = 0;
        var taskIterator = Object2LongMaps.fastIterator(currentJob.getTasks());
        taskLoop:
        while (taskIterator.hasNext() && dispatchedCalls < maxDispatches) {
            var task = taskIterator.next();
            long taskOperations = task.getLongValue();
            if (taskOperations <= 0) {
                taskIterator.remove();
                continue;
            }

            IPatternDetails details = task.getKey();
            boolean processing = details.supportsPushInputsToExternalInventory();
            boolean providerSeen = false;
            boolean idleProviderSeen = false;
            boolean providerRejected = false;
            int taskReasonMask = 0;

            for (var provider : craftingService.getProviders(details)) {
                providerSeen = true;
                if (provider.isBusy()) {
                    continue;
                }
                idleProviderSeen = true;

                boolean autoExpand = CraftingPatternAutoExpand.canAutoExpand(processing, provider);
                long requestedOperations = taskOperations;
                long operations = autoExpand ? CraftingPatternAutoExpand.getOperations(
                        true, provider, details, requestedOperations) : 1;
                operations = Math.max(1, Math.min(operations, requestedOperations));

                KeyCounter expectedOutputs = new KeyCounter();
                KeyCounter expectedContainerItems = new KeyCounter();
                KeyCounter[] craftingContainer = processing ?
                        (autoExpand ? AEUtils.extractForProcessingPattern(
                                details, this.inventory, expectedOutputs, operations) :
                                AEUtils.extractForProcessingPattern(details, this.inventory, expectedOutputs)) :
                        AEUtils.extractForCraftPattern(
                                details, this.inventory, level, expectedOutputs, expectedContainerItems);

                if (craftingContainer == null) {
                    taskReasonMask |= CraftingDispatchReason.WAITING_FOR_INPUTS.mask();
                    break;
                }
                double patternPower = CraftingPatternPower.forCpu(
                        CraftingCpuHelper.calculatePatternPower(craftingContainer), autoExpand, operations);
                boolean hasPower = energyService.extractAEPower(
                        patternPower, Actionable.SIMULATE, PowerMultiplier.CONFIG) >=
                        patternPower - POWER_EPSILON;
                if (!hasPower) {
                    CraftingCpuHelper.reinjectPatternInputs(this.inventory, craftingContainer);
                    taskReasonMask |= CraftingDispatchReason.INSUFFICIENT_POWER.mask();
                    break;
                }
                boolean pushed = provider.pushPattern(details, craftingContainer);
                if (!pushed) {
                    CraftingCpuHelper.reinjectPatternInputs(this.inventory, craftingContainer);
                    providerRejected = true;
                    continue;
                }

                taskReasonMask = 0;
                energyService.extractAEPower(patternPower, Actionable.MODULATE, PowerMultiplier.CONFIG);
                dispatchedCalls = NumberUtils.saturatedAdd(dispatchedCalls, 1);
                for (var expectedOutput : expectedOutputs) {
                    currentJob.getWaitingFor().insert(
                            expectedOutput.getKey(), expectedOutput.getLongValue(), Actionable.MODULATE);
                }
                for (var containerItem : expectedContainerItems) {
                    currentJob.getWaitingFor().insert(
                            containerItem.getKey(), containerItem.getLongValue(), Actionable.MODULATE);
                    ((ElapsedTimeTrackerAccessor) currentJob.getTimeTracker()).invokeAddMaxItems(
                            containerItem.getLongValue(), containerItem.getKey().getType());
                }

                taskOperations -= operations;
                task.setValue(taskOperations);
                markChanged();
                if (taskOperations <= 0) {
                    taskIterator.remove();
                    this.workingDispatchReasons.remove(details);
                    continue taskLoop;
                }
                if (dispatchedCalls >= maxDispatches) {
                    break taskLoop;
                }
                if (autoExpand) {
                    continue taskLoop;
                }
            }

            if (!providerSeen) {
                taskReasonMask |= CraftingDispatchReason.NO_PROVIDER.mask();
            } else if (!idleProviderSeen) {
                taskReasonMask |= CraftingDispatchReason.PROVIDERS_BUSY.mask();
            } else if (providerRejected) {
                taskReasonMask |= CraftingDispatchReason.PROVIDER_REJECTED.mask();
            }
            recordTaskReason(details, taskReasonMask);
        }
        return dispatchedCalls;
    }

    public long insert(AEKey what, long amount, Actionable mode) {
        if (this.graph.ownsTask()) return this.graph.insert(what, amount, mode);
        TransfiniteCraftingJob currentJob = this.job;
        if (what == null || currentJob == null || amount <= 0) {
            return 0;
        }

        long accepted = currentJob.getWaitingFor().extract(what, amount, Actionable.SIMULATE);
        if (accepted <= 0) {
            return 0;
        }
        accepted = Math.min(amount, accepted);

        long inserted = accepted;
        if (what.matches(currentJob.getFinalOutput())) {
            inserted = currentJob.getLink().insert(what, accepted, mode);
        }

        if (mode == Actionable.MODULATE) {
            ((ElapsedTimeTrackerAccessor) currentJob.getTimeTracker()).invokeDecrementItems(
                    accepted, what.getType());
            currentJob.getWaitingFor().extract(what, accepted, Actionable.MODULATE);
            if (what.matches(currentJob.getFinalOutput())) {
                postChange(what);
                long remaining = Math.max(0, currentJob.getRemainingAmount() - accepted);
                currentJob.setRemainingAmount(remaining);
                if (remaining == 0) {
                    finishJob(true);
                }
            } else {
                this.inventory.insert(what, accepted, Actionable.MODULATE);
            }
            markChanged();
        }
        return inserted;
    }

    public void cancel() {
        if (this.graph.ownsTask()) {
            this.graph.cancel();
            return;
        }
        if (this.job != null) {
            finishJob(false);
        }
    }

    private void finishJob(boolean success) {
        TransfiniteCraftingJob completedJob = this.job;
        if (completedJob == null) {
            return;
        }
        if (success) {
            completedJob.getLink().markDone();
        } else {
            completedJob.getLink().cancel();
        }

        for (var waiting : completedJob.getWaitingFor().list) {
            this.cpu.getHost().updateWaitingIndex(this.cpu, waiting.getKey(), false);
        }
        completedJob.getWaitingFor().clear();
        for (var task : completedJob.getTasks().object2LongEntrySet()) {
            for (var output : task.getKey().getOutputs()) {
                postChange(output.what());
            }
        }
        notifyJobOwner(completedJob, success ? CraftingJobStatusPacket.Status.FINISHED :
                CraftingJobStatusPacket.Status.CANCELLED);
        this.job = null;
        this.workingDispatchReasons.clear();
        storeItems();
        markChanged();
    }

    public void storeItems() {
        if (this.graph.ownsTask()) return;
        Preconditions.checkState(this.job == null,
                "CPU should not have a job while returning its inventory");
        if (this.inventory.list.isEmpty()) {
            return;
        }
        IGrid grid = this.cpu.getGrid();
        if (grid == null) {
            return;
        }

        var storage = grid.getStorageService().getInventory();
        int attempted = 0;
        int consecutiveFailures = 0;
        boolean changed = false;
        for (var entry : this.inventory.list) {
            if (attempted >= STORE_BATCH_SIZE || consecutiveFailures >= MAX_CONSECUTIVE_STORE_FAILURES) {
                break;
            }
            attempted++;
            long inserted = storage.insert(
                    entry.getKey(), entry.getLongValue(), Actionable.MODULATE, this.cpu.getActionSource());
            if (inserted <= 0) {
                consecutiveFailures++;
                continue;
            }
            consecutiveFailures = 0;
            changed = true;
            postChange(entry.getKey());
            entry.setValue(entry.getLongValue() - inserted);
        }
        if (changed) {
            this.inventory.list.removeZeros();
            markChanged();
        }
    }

    public void readFromNbt(CompoundTag data) {
        this.inventory.readFromNBT(data.getList("inventory", CompoundTag.TAG_COMPOUND));
        if (data.contains(GraphJobCodec.NBT_KEY, CompoundTag.TAG_COMPOUND)) {
            this.job = null;
            this.graph.read(data);
            return;
        }
        if (data.contains("job", CompoundTag.TAG_COMPOUND)) {
            this.job = new TransfiniteCraftingJob(data.getCompound("job"), this);
            if (this.job.getFinalOutput() == null) {
                finishJob(false);
            } else {
                indexAllWaitingItems();
            }
        }
    }

    public void writeToNbt(CompoundTag data) {
        data.put("inventory", this.inventory.writeToNBT());
        if (this.graph.ownsTask()) {
            this.graph.write(data);
            return;
        }
        data.remove(GraphJobCodec.NBT_KEY);
        if (this.job != null) {
            data.put("job", this.job.writeToNbt());
        }
    }

    public boolean hasJob() {
        if (this.graph.ownsTask()) return true;
        return this.job != null;
    }

    public boolean canBeRemoved() {
        if (this.graph.ownsTask()) return false;
        return this.job == null && this.inventory.list.isEmpty();
    }

    public @Nullable GenericStack getFinalJobOutput() {
        if (this.graph.ownsTask()) return this.graph.finalOutput();
        return this.job == null ? null : this.job.getFinalOutput();
    }

    public ElapsedTimeTracker getElapsedTimeTracker() {
        if (this.graph.ownsTask()) return this.graph.tracker();
        return this.job == null ? EMPTY_TIME_TRACKER : this.job.getTimeTracker();
    }

    public @Nullable ICraftingLink getLastLink() {
        if (this.graph.ownsTask()) return this.graph.link();
        return this.job == null ? null : this.job.getLink();
    }

    public long getLastModifiedOnTick() {
        return Math.max(this.lastModifiedOnTick, this.graph.modifiedTick());
    }

    public void addListener(Consumer<AEKey> listener) {
        this.listeners.add(listener);
    }

    public void removeListener(Consumer<AEKey> listener) {
        this.listeners.remove(listener);
    }

    public long getStored(AEKey key) {
        if (this.graph.ownsTask()) return this.graph.stored(key);
        return this.inventory.extract(key, Long.MAX_VALUE, Actionable.SIMULATE);
    }

    public long getWaitingFor(AEKey key) {
        if (this.graph.ownsTask()) return this.graph.waiting(key);
        return this.job == null ? 0 :
                this.job.getWaitingFor().extract(key, Long.MAX_VALUE, Actionable.SIMULATE);
    }

    public boolean isRequesting(AEKey key) {
        if (this.graph.ownsTask()) return this.graph.waiting(key) > 0;
        if (this.job == null) {
            return false;
        }
        GenericStack finalOutput = this.job.getFinalOutput();
        return finalOutput != null && key.matches(finalOutput) || this.getWaitingFor(key) > 0;
    }

    public boolean isRequestingAny() {
        if (this.graph.ownsTask()) return true;
        return this.job != null;
    }

    public long getPendingOutputs(AEKey key) {
        if (this.graph.ownsTask()) return this.graph.pending(key);
        long count = 0;
        if (this.job != null) {
            for (var task : this.job.getTasks().object2LongEntrySet()) {
                for (var output : task.getKey().getOutputs()) {
                    if (key.matches(output)) {
                        count = NumberUtils.saturatedAdd(count,
                                NumberUtils.saturatedMultiply(output.amount(), task.getLongValue()));
                    }
                }
            }
        }
        return count;
    }

    public void getAllItems(KeyCounter output) {
        if (this.graph.ownsTask()) {
            this.graph.allItems(output);
            return;
        }
        output.addAll(this.inventory.list);
        if (this.job == null) {
            return;
        }
        output.addAll(this.job.getWaitingFor().list);
        for (var task : this.job.getTasks().object2LongEntrySet()) {
            for (var taskOutput : task.getKey().getOutputs()) {
                output.add(taskOutput.what(),
                        NumberUtils.saturatedMultiply(taskOutput.amount(), task.getLongValue()));
            }
        }
    }

    public boolean isCantStoreItems() {
        if (this.graph.ownsTask()) return this.graph.cantStore();
        return this.cantStoreItems;
    }

    TransfiniteCraftingCPU getCpu() {
        return this.cpu;
    }

    void onWaitingForChanged(AEKey key) {
        postChange(key);
        this.cpu.getHost().updateWaitingIndex(this.cpu, key, getWaitingFor(key) > 0);
    }

    private void indexAllWaitingItems() {
        if (this.job == null) {
            return;
        }
        for (var entry : this.job.getWaitingFor().list) {
            this.cpu.getHost().updateWaitingIndex(this.cpu, entry.getKey(), true);
        }
    }

    private void markChanged() {
        this.lastModifiedOnTick = TickHandler.instance().getCurrentTick();
        this.dirty = true;
        if (!this.batchingChanges) {
            flushDirty();
        }
    }

    private void flushDirty() {
        if (this.dirty) {
            this.dirty = false;
            this.cpu.markDirty();
        }
    }

    private void postChange(AEKey key) {
        this.lastModifiedOnTick = TickHandler.instance().getCurrentTick();
        for (var listener : this.listeners) {
            listener.accept(key);
        }
    }

    private void notifyJobOwner(TransfiniteCraftingJob changedJob, CraftingJobStatusPacket.Status status) {
        this.lastModifiedOnTick = TickHandler.instance().getCurrentTick();
        Integer playerId = changedJob.getPlayerId();
        if (playerId == null) {
            return;
        }
        var server = this.cpu.getLevel().getServer();
        var player = IPlayerRegistry.getConnected(server, playerId);
        if (player != null && changedJob.getFinalOutput() != null) {
            NetworkHandler.instance().sendTo(new CraftingJobStatusPacket(
                    changedJob.getLink().getCraftingID(),
                    changedJob.getFinalOutput().what(),
                    changedJob.getFinalOutput().amount(),
                    changedJob.getRemainingAmount(),
                    status), player);
        }
    }

    private static boolean isWrittenBookOutput(@Nullable GenericStack output) {
        return output != null && output.what() instanceof AEItemKey itemKey &&
                itemKey.getItem() == Items.WRITTEN_BOOK && itemKey.hasTag() &&
                itemKey.getTag().contains("display");
    }

    @Override
    public boolean gtlcore$isJobSuspended() {
        if (this.graph.ownsTask()) return this.graph.suspended();
        return this.job != null && this.job.isSuspended();
    }

    @Override
    public void gtlcore$setJobSuspended(boolean suspended) {
        if (this.graph.ownsTask()) {
            this.graph.suspend(suspended);
            return;
        }
        if (this.job != null && this.job.isSuspended() != suspended) {
            this.job.setSuspended(suspended);
            markChanged();
        }
    }

    @Override
    public int gtlcore$getDispatchReasonMask(AEKey key) {
        if (this.graph.ownsTask()) return this.graph.reasonMask(key);
        return this.publishedDispatchReasons.getOrDefault(key, 0);
    }

    private void recordTaskReason(IPatternDetails details, int reasonMask) {
        if (!this.collectDispatchReasons) {
            return;
        }
        if (reasonMask == 0) {
            this.workingDispatchReasons.remove(details);
        } else {
            this.workingDispatchReasons.put(details, reasonMask);
        }
    }

    @Override
    public ICraftingCPU cpu() {
        return this.cpu;
    }

    @Override
    public IGrid grid() {
        return this.cpu.getGrid();
    }

    @Override
    public Level level() {
        return this.cpu.getLevel();
    }

    @Override
    public IActionSource source() {
        return this.cpu.getActionSource();
    }

    @Override
    public boolean active() {
        return this.cpu.isActive();
    }

    @Override
    public boolean unboundedJobStorage() {
        return true;
    }

    @Override
    public long dispatchCapacity() {
        return this.cpu.getParallelism();
    }

    @Override
    public ListCraftingInventory orphanInventory() {
        return this.inventory;
    }

    @Override
    public void dirty() {
        markChanged();
    }

    @Override
    public void changed(AEKey key) {
        postChange(key);
    }

    @Override
    public void output(GenericStack stack) {
        markChanged();
    }

    @Override
    public void requesting(AEKey key, boolean requested) {
        this.cpu.getHost().updateWaitingIndex(this.cpu, key, requested);
    }

    private void markAllRemaining(CraftingDispatchReason reason) {
        if (!this.collectDispatchReasons || this.job == null) {
            return;
        }
        for (IPatternDetails details : this.job.getTasks().keySet()) {
            this.workingDispatchReasons.put(details, reason.mask());
        }
    }

    private void markAllUnclassified(CraftingDispatchReason reason) {
        if (!this.collectDispatchReasons || this.job == null) {
            return;
        }
        for (IPatternDetails details : this.job.getTasks().keySet()) {
            this.workingDispatchReasons.putIfAbsent(details, reason.mask());
        }
    }

    private void publishDispatchReasons() {
        if (!this.collectDispatchReasons) {
            this.publishedDispatchReasons = Map.of();
            return;
        }
        Map<AEKey, Integer> current = new Object2IntOpenHashMap<>();
        if (this.job != null) {
            for (IPatternDetails details : this.job.getTasks().keySet()) {
                int reasonMask = this.workingDispatchReasons.getOrDefault(details, 0);
                if (reasonMask == 0) {
                    continue;
                }
                for (GenericStack output : details.getOutputs()) {
                    current.merge(output.what(), reasonMask, (existing, added) -> existing | added);
                }
            }
        }
        for (AEKey changed : CraftingDispatchReasonState.changedKeys(this.publishedDispatchReasons, current)) {
            postChange(changed);
        }
        this.publishedDispatchReasons = Map.copyOf(current);

        if (this.job != null && this.job.getTasks().isEmpty() && isWrittenBookOutput(getFinalJobOutput())) {
            finishJob(true);
        }
    }
}
