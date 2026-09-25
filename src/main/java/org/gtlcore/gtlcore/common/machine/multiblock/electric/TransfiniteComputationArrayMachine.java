package org.gtlcore.gtlcore.common.machine.multiblock.electric;

import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MECraftingCPUInterfacePartMachine;
import org.gtlcore.gtlcore.integration.ae2.crafting.transfinite.TransfiniteCraftingCPU;
import org.gtlcore.gtlcore.utils.NumberUtils;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.gui.fancy.TooltipsPanel;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.feature.IFancyUIMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IDisplayUIMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.pattern.MultiblockWorldSavedData;

import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ComponentPanelWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import appeng.api.config.Actionable;
import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.me.service.CraftingService;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class TransfiniteComputationArrayMachine extends MultiblockControllerMachine
                                                implements IFancyUIMachine, IDisplayUIMachine {

    public static final long MIN_PARALLELISM = 1L;
    public static final long MAX_PARALLELISM = Long.MAX_VALUE;
    public static final long DEFAULT_PARALLELISM = 1L;
    public static final long UNBOUNDED_JOB_STORAGE = Long.MAX_VALUE;

    private static final CpuSelectionMode DEFAULT_SELECTION_MODE = CpuSelectionMode.ANY;
    private static final CpuSelectionMode[] CPU_SELECTION_MODES = CpuSelectionMode.values();
    private static final String NBT_CPUS = "TransfiniteCraftingCpus";
    private static final String NBT_CPU_ID = "Id";
    private static final String NBT_CPU_BYTES = "Bytes";
    private static final String NBT_CPU_STATE = "State";
    private static final long ASYNC_PATTERN_CHECK_INTERVAL = 4L;
    /**
     * Ticks after loading during which the structure is checked every tick instead of every
     * {@link #ASYNC_PATTERN_CHECK_INTERVAL} ticks. This array is large enough that the throttled cadence can
     * leave it unformed noticeably long after entering the world, so it is polled hard for the first
     * 5 seconds and then falls back to the normal interval.
     */
    private static final int EAGER_CHECK_TICKS = 100;
    private static final int UI_WIDTH = 198;
    private static final int UI_HEIGHT = 208;
    private static final int MAIN_PAGE_WIDTH = 190;
    private static final int MAIN_PAGE_HEIGHT = 125;
    private static final int SCREEN_MARGIN = 4;
    private static final int SCREEN_WIDTH = MAIN_PAGE_WIDTH - 2 * SCREEN_MARGIN;
    private static final int SCREEN_HEIGHT = MAIN_PAGE_HEIGHT - 2 * SCREEN_MARGIN;
    private static final int SCREEN_CONTENT_X = 4;
    private static final int TITLE_Y = 5;
    private static final int STATUS_Y = 17;
    private static final int STATUS_WIDTH = SCREEN_WIDTH - 12;
    private static final int SELECTION_MODE_BUTTON_Y = 61;
    private static final int SELECTION_MODE_BUTTON_WIDTH = SCREEN_WIDTH - 8;
    private static final int SELECTION_MODE_BUTTON_HEIGHT = 18;

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            TransfiniteComputationArrayMachine.class, MultiblockControllerMachine.MANAGED_FIELD_HOLDER);

    @Persisted
    @DescSynced
    private CpuSelectionMode selectionMode = DEFAULT_SELECTION_MODE;

    private final Map<UUID, TransfiniteCraftingCPU> activeCpus = new LinkedHashMap<>();
    private final Map<AEKey, ObjectOpenHashSet<TransfiniteCraftingCPU>> waitingIndex = new Object2ObjectOpenHashMap<>();
    @Nullable
    private ListTag pendingCpus;
    @Nullable
    private MECraftingCPUInterfacePartMachine networkInterface;
    private TransfiniteCraftingCPU capacityCpu;
    private final AtomicBoolean patternCheckQueued = new AtomicBoolean();
    /** Remaining ticks of the post-load eager check window; decremented from the async check thread. */
    private final AtomicInteger eagerCheckTicks = new AtomicInteger();

    public TransfiniteComputationArrayMachine(IMachineBlockEntity holder) {
        super(holder);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        restoreCpus();
        // Entering the world should settle the structure as soon as possible, so prioritise it over the
        // throttled cadence until it forms or the window runs out.
        this.eagerCheckTicks.set(!isFormed() || this.networkInterface == null ? EAGER_CHECK_TICKS : 0);
    }

    @Override
    public void asyncCheckPattern(long periodID) {
        if (isFormed() && !getMultiblockState().hasError() && this.networkInterface != null) {
            return;
        }
        boolean eager = this.eagerCheckTicks.get() > 0 && this.eagerCheckTicks.getAndDecrement() > 0;
        if (!eager && Math.floorMod(getHolder().getOffset() + periodID, ASYNC_PATTERN_CHECK_INTERVAL) != 0) {
            return;
        }
        if (!this.patternCheckQueued.compareAndSet(false, true)) {
            return;
        }
        if (!(getLevel() instanceof ServerLevel serverLevel)) {
            this.patternCheckQueued.set(false);
            return;
        }
        long startedGameTime = serverLevel.getGameTime();
        boolean formedBefore = isFormed();

        if (isInValid()) {

            this.patternCheckQueued.set(false);
            return;
        }
        if (getLevel() != serverLevel) {

            this.patternCheckQueued.set(false);
            return;
        }

        final boolean matched;
        try {
            matched = checkPatternWithTryLock();
        } catch (RuntimeException | Error exception) {

            this.patternCheckQueued.set(false);
            throw exception;
        }
        if (!matched) {

            this.patternCheckQueued.set(false);
            return;
        }

        this.eagerCheckTicks.set(0);
        try {
            serverLevel.getServer().execute(() -> {
                getPatternLock().lock();
                try {
                    if (isInValid()) {

                        return;
                    }
                    if (getLevel() != serverLevel) {

                        return;
                    }

                    setFlipped(getMultiblockState().isNeededFlip());
                    onStructureFormed();
                    var savedData = MultiblockWorldSavedData.getOrCreate(serverLevel);
                    savedData.addMapping(getMultiblockState());
                    savedData.removeAsyncLogic(this);
                } catch (RuntimeException | Error exception) {

                    throw exception;
                } finally {
                    getPatternLock().unlock();
                    this.patternCheckQueued.set(false);
                }
            });
        } catch (RuntimeException | Error exception) {

            this.patternCheckQueued.set(false);
            throw exception;
        }
    }

    public long getParallelism() {
        return this.networkInterface == null ? DEFAULT_PARALLELISM : this.networkInterface.getParallelism();
    }

    public void restoreNetworkInterface(@NotNull MECraftingCPUInterfacePartMachine networkInterface) {
        this.networkInterface = networkInterface;
    }

    public boolean isOperational() {
        return isFormed() && this.networkInterface != null && this.networkInterface.getMainNode().isActive();
    }

    public long getAvailableStorage() {
        return UNBOUNDED_JOB_STORAGE;
    }

    public IActionSource getActionSource() {
        return this.networkInterface == null ? IActionSource.empty() : this.networkInterface.getActionSource();
    }

    @Nullable
    public IGrid getGrid() {
        return this.networkInterface == null ? null : this.networkInterface.getMainNode().getGrid();
    }

    public Component getCpuName() {
        Component defaultName = getBlockState().getBlock().getName();
        return this.networkInterface == null ? defaultName :
                this.networkInterface.resolveCpuName(defaultName);
    }

    public CpuSelectionMode getSelectionMode() {
        return this.selectionMode;
    }

    public boolean canBeAutoSelectedFor(IActionSource source) {
        return switch (getSelectionMode()) {
            case ANY -> true;
            case PLAYER_ONLY -> source.player().isPresent();
            case MACHINE_ONLY -> source.player().isEmpty();
        };
    }

    public boolean isPreferredFor(IActionSource source) {
        return switch (getSelectionMode()) {
            case ANY -> false;
            case PLAYER_ONLY -> source.player().isPresent();
            case MACHINE_ONLY -> source.player().isEmpty();
        };
    }

    public ICraftingSubmitResult submitJob(IGrid grid, ICraftingPlan plan, IActionSource source,
                                           @Nullable ICraftingRequester requester) {
        if (!isOperational()) {
            return CraftingSubmitResult.CPU_OFFLINE;
        }
        if (plan.bytes() > getAvailableStorage()) {
            return CraftingSubmitResult.CPU_TOO_SMALL;
        }

        UUID id = UUID.randomUUID();
        var cpu = new TransfiniteCraftingCPU(this, id, plan.bytes());
        ICraftingSubmitResult result = cpu.getCraftingLogic().trySubmitJob(grid, plan, source, requester);
        if (result.successful()) {
            this.activeCpus.put(id, cpu);
            markDirty();
            notifyCraftingCpuChange();
        }
        return result;
    }

    public void forEachActiveCpu(Consumer<TransfiniteCraftingCPU> consumer) {
        this.activeCpus.values().forEach(consumer);
    }

    public long tickCraftingCpus(IEnergyService energyService, CraftingService craftingService) {
        boolean removedCpu = false;
        long latestChange = 0;
        var iterator = this.activeCpus.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            TransfiniteCraftingCPU cpu = entry.getValue();
            cpu.getCraftingLogic().tickCraftingLogic(energyService, craftingService);
            latestChange = Math.max(latestChange, cpu.getCraftingLogic().getLastModifiedOnTick());
            if (cpu.getCraftingLogic().canBeRemoved()) {
                removeFromWaitingIndex(cpu);
                iterator.remove();
                removedCpu = true;
            }
        }
        if (removedCpu) {
            markDirty();
            notifyCraftingCpuChange();
        }
        return latestChange;
    }

    public void collectWaitingFor(Set<AEKey> output) {
        output.addAll(this.waitingIndex.keySet());
    }

    public void collectRequestingKeys(Set<AEKey> output) {
        output.addAll(this.waitingIndex.keySet());
        for (TransfiniteCraftingCPU cpu : this.activeCpus.values()) {
            var finalOutput = cpu.getCraftingLogic().getFinalJobOutput();
            if (finalOutput != null) {
                output.add(finalOutput.what());
            }
        }
    }

    public long insertIntoCpus(AEKey key, long amount, Actionable mode) {
        if (amount <= 0) {
            return 0;
        }
        var candidates = this.waitingIndex.get(key);
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }

        if (mode == Actionable.SIMULATE) {
            long accepted = 0;
            for (TransfiniteCraftingCPU cpu : candidates) {
                accepted += cpu.getCraftingLogic().insert(key, amount - accepted, mode);
                if (accepted >= amount) {
                    break;
                }
            }
            return accepted;
        }

        long inserted = 0;
        while (inserted < amount) {
            candidates = this.waitingIndex.get(key);
            if (candidates == null || candidates.isEmpty()) {
                break;
            }
            TransfiniteCraftingCPU cpu = candidates.iterator().next();
            long accepted = cpu.getCraftingLogic().insert(key, amount - inserted, mode);
            if (accepted <= 0) {
                updateWaitingIndex(cpu, key, false);
                continue;
            }
            inserted += accepted;
        }
        return inserted;
    }

    public long getRequestedAmount(AEKey key) {
        var candidates = this.waitingIndex.get(key);
        if (candidates == null) {
            return 0;
        }
        long requested = 0;
        for (TransfiniteCraftingCPU cpu : candidates) {
            requested = NumberUtils.saturatedAdd(requested, cpu.getCraftingLogic().getWaitingFor(key));
        }
        return requested;
    }

    public boolean isRequesting(AEKey key) {
        if (this.waitingIndex.containsKey(key)) {
            return true;
        }
        for (TransfiniteCraftingCPU cpu : this.activeCpus.values()) {
            if (cpu.getCraftingLogic().isRequesting(key)) {
                return true;
            }
        }
        return false;
    }

    public boolean isRequestingAny() {
        if (!this.waitingIndex.isEmpty()) {
            return true;
        }
        for (TransfiniteCraftingCPU cpu : this.activeCpus.values()) {
            if (cpu.getCraftingLogic().isRequestingAny()) {
                return true;
            }
        }
        return false;
    }

    public boolean containsCpu(TransfiniteCraftingCPU cpu) {
        UUID id = cpu.getId();
        return id != null && this.activeCpus.get(id) == cpu;
    }

    public void updateWaitingIndex(TransfiniteCraftingCPU cpu, AEKey key, boolean waiting) {
        if (waiting) {
            this.waitingIndex.computeIfAbsent(key, ignored -> new ObjectOpenHashSet<>()).add(cpu);
            return;
        }
        var candidates = this.waitingIndex.get(key);
        if (candidates != null) {
            candidates.remove(cpu);
            if (candidates.isEmpty()) {
                this.waitingIndex.remove(key);
            }
        }
    }

    public TransfiniteCraftingCPU getCapacityCpu() {
        if (this.capacityCpu == null) {
            this.capacityCpu = TransfiniteCraftingCPU.capacityView(this);
        }
        return this.capacityCpu;
    }

    public int getActiveJobCount() {
        int count = 0;
        for (TransfiniteCraftingCPU cpu : this.activeCpus.values()) {
            if (cpu.isBusy()) {
                count++;
            }
        }
        return count;
    }

    private void removeFromWaitingIndex(TransfiniteCraftingCPU cpu) {
        var iterator = this.waitingIndex.values().iterator();
        while (iterator.hasNext()) {
            var candidates = iterator.next();
            candidates.remove(cpu);
            if (candidates.isEmpty()) {
                iterator.remove();
            }
        }
    }

    public void markCpuDirty() {
        markDirty();
    }

    @Override
    public void onStructureFormed() {
        try {
            super.onStructureFormed();

            var parts = getParts();
            this.networkInterface = parts.stream()
                    .filter(MECraftingCPUInterfacePartMachine.class::isInstance)
                    .map(MECraftingCPUInterfacePartMachine.class::cast)
                    .findFirst()
                    .orElse(null);

            notifyCraftingCpuChange();

        } catch (RuntimeException | Error exception) {

            throw exception;
        }
    }

    @Override
    public void onStructureInvalid() {
        MECraftingCPUInterfacePartMachine previousInterface = this.networkInterface;
        boolean previousInterfaceOnline = previousInterface != null && previousInterface.isOnline();
        boolean previousNodeActive = previousInterface != null && previousInterface.getMainNode().isActive();
        boolean previousGridPresent = previousInterface != null && previousInterface.getMainNode().getGrid() != null;
        super.onStructureInvalid();
        this.networkInterface = null;
        if (previousInterface != null) {
            previousInterface.notifyCraftingCpuChange();
        }
    }

    private void notifyCraftingCpuChange() {
        if (this.networkInterface != null) {
            this.networkInterface.notifyCraftingCpuChange();
        }
    }

    @Override
    public void saveCustomPersistedData(@NotNull CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        if (this.pendingCpus != null) {
            // A save during chunk attachment must retain jobs whose patterns
            // cannot yet be decoded against a server level.
            tag.put(NBT_CPUS, this.pendingCpus.copy());
            return;
        }
        ListTag cpuList = new ListTag();
        for (var entry : this.activeCpus.entrySet()) {
            CompoundTag cpuTag = new CompoundTag();
            cpuTag.putUUID(NBT_CPU_ID, entry.getKey());
            cpuTag.putLong(NBT_CPU_BYTES, entry.getValue().getAvailableStorage());
            CompoundTag stateTag = new CompoundTag();
            entry.getValue().getCraftingLogic().writeToNbt(stateTag);
            cpuTag.put(NBT_CPU_STATE, stateTag);
            cpuList.add(cpuTag);
        }
        tag.put(NBT_CPUS, cpuList);
    }

    @Override
    public void loadCustomPersistedData(@NotNull CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (this.selectionMode == null) {
            this.selectionMode = DEFAULT_SELECTION_MODE;
        }
        this.activeCpus.clear();
        this.waitingIndex.clear();
        this.pendingCpus = tag.getList(NBT_CPUS, Tag.TAG_COMPOUND).copy();
        restoreCpus();
    }

    private void restoreCpus() {
        if (this.pendingCpus == null || getLevel() == null) return;
        ListTag cpuList = this.pendingCpus;
        this.activeCpus.clear();
        this.waitingIndex.clear();
        for (int i = 0; i < cpuList.size(); i++) {
            CompoundTag cpuTag = cpuList.getCompound(i);
            if (!cpuTag.hasUUID(NBT_CPU_ID)) {
                continue;
            }
            UUID id = cpuTag.getUUID(NBT_CPU_ID);
            long bytes = Math.max(0L, cpuTag.getLong(NBT_CPU_BYTES));
            var cpu = new TransfiniteCraftingCPU(this, id, bytes);
            cpu.getCraftingLogic().readFromNbt(cpuTag.getCompound(NBT_CPU_STATE));
            this.activeCpus.put(id, cpu);
        }
        this.pendingCpus = null;
    }

    @Override
    public ModularUI createUI(Player player) {
        return new ModularUI(UI_WIDTH, UI_HEIGHT, this, player)
                .widget(new FancyMachineUIWidget(this, UI_WIDTH, UI_HEIGHT));
    }

    @Override
    public @NotNull Widget createUIWidget() {
        var selectionModeButton = new ButtonWidget(
                SCREEN_CONTENT_X, SELECTION_MODE_BUTTON_Y,
                SELECTION_MODE_BUTTON_WIDTH, SELECTION_MODE_BUTTON_HEIGHT,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(this::getSelectionModeButtonText)),
                clickData -> cycleSelectionMode())
                .setHoverTooltips(
                        Component.translatable("gui.gtlcore.transfinite_computation_array.selection_mode.tooltip"),
                        Component.translatable("gui.gtlcore.transfinite_computation_array.selection_mode.any.tooltip"),
                        Component.translatable("gui.gtlcore.transfinite_computation_array.selection_mode.player_only.tooltip"),
                        Component.translatable("gui.gtlcore.transfinite_computation_array.selection_mode.machine_only.tooltip"));

        var screen = new DraggableScrollableWidgetGroup(
                SCREEN_MARGIN, SCREEN_MARGIN, SCREEN_WIDTH, SCREEN_HEIGHT)
                .setBackground(getScreenTexture());
        screen.addWidget(new LabelWidget(
                SCREEN_CONTENT_X, TITLE_Y, getBlockState().getBlock().getDescriptionId()));
        screen.addWidget(new ComponentPanelWidget(SCREEN_CONTENT_X, STATUS_Y, this::addDisplayText)
                .textSupplier(isRemote() ? null : this::addDisplayText)
                .setMaxWidthLimit(STATUS_WIDTH));
        screen.addWidget(selectionModeButton);

        var mainPage = new WidgetGroup(0, 0, MAIN_PAGE_WIDTH, MAIN_PAGE_HEIGHT);
        mainPage.addWidget(screen);
        mainPage.setBackground(GuiTextures.BACKGROUND_INVERSE);
        return mainPage;
    }

    @Override
    public List<IFancyUIProvider> getSubTabs() {
        return getParts().stream()
                .filter(IFancyUIProvider.class::isInstance)
                .map(IFancyUIProvider.class::cast)
                .toList();
    }

    @Override
    public void attachTooltips(TooltipsPanel tooltipsPanel) {
        for (IMultiPart part : getParts()) {
            part.attachFancyTooltipsToController(this, tooltipsPanel);
        }
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        boolean formed = isFormed();
        boolean online = isOperational();
        textList.add(Component.translatable(formed ?
                "gui.gtlcore.transfinite_computation_array.formed" :
                "gui.gtlcore.transfinite_computation_array.unformed")
                .withStyle(formed ? ChatFormatting.GREEN : ChatFormatting.RED));
        textList.add(Component.translatable(online ?
                "gui.gtlcore.transfinite_computation_array.online" :
                "gui.gtlcore.transfinite_computation_array.offline")
                .withStyle(online ? ChatFormatting.GREEN : ChatFormatting.RED));
        textList.add(Component.translatable("gui.gtlcore.transfinite_computation_array.jobs",
                NumberUtils.numberText(getActiveJobCount()).withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.GRAY));
        textList.add(Component.translatable("gui.gtlcore.transfinite_computation_array.storage")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        IDisplayUIMachine.super.addDisplayText(textList);
    }

    private void cycleSelectionMode() {
        int nextMode = (this.selectionMode.ordinal() + 1) % CPU_SELECTION_MODES.length;
        setSelectionMode(CPU_SELECTION_MODES[nextMode]);
    }

    private void setSelectionMode(CpuSelectionMode selectionMode) {
        if (this.selectionMode == selectionMode) {
            return;
        }
        this.selectionMode = selectionMode;
        markDirty();
        notifyCraftingCpuChange();
    }

    private String getSelectionModeButtonText() {
        return Component.translatable(
                "gui.gtlcore.transfinite_computation_array.selection_mode",
                Component.translatable(getSelectionModeTranslationKey())).getString();
    }

    private String getSelectionModeTranslationKey() {
        return switch (this.selectionMode) {
            case ANY -> "gui.gtlcore.transfinite_computation_array.selection_mode.any";
            case PLAYER_ONLY -> "gui.gtlcore.transfinite_computation_array.selection_mode.player_only";
            case MACHINE_ONLY -> "gui.gtlcore.transfinite_computation_array.selection_mode.machine_only";
        };
    }

    @Override
    public @NotNull ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }
}
