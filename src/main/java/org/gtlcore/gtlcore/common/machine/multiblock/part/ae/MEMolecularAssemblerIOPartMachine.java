package org.gtlcore.gtlcore.common.machine.multiblock.part.ae;

import org.gtlcore.gtlcore.api.gui.GuiTextures;
import org.gtlcore.gtlcore.api.machine.trait.AECraft.IMECraftIOPart;
import org.gtlcore.gtlcore.api.machine.trait.AECraft.IMECraftPatternContainer;
import org.gtlcore.gtlcore.common.data.GTLMachines;
import org.gtlcore.gtlcore.common.machine.multiblock.part.PaginationUIManager;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphDispatchPreflight;
import org.gtlcore.gtlcore.integration.lowdragmc.misc.MutableItemTransferList;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfiguratorButton;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.fancyconfigurator.FancyInvConfigurator;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AETextInputButtonWidget;

import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.side.item.IItemTransfer;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.crafting.pattern.EncodedPatternItem;
import appeng.helpers.patternprovider.PatternContainer;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.*;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.Map;

import static org.gtlcore.gtlcore.integration.ae2.AEUtils.*;

public class MEMolecularAssemblerIOPartMachine extends MEIOPartMachine implements PatternContainer, IMECraftIOPart,
                                               GraphDispatchPreflight {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(MEMolecularAssemblerIOPartMachine.class, MEIOPartMachine.MANAGED_FIELD_HOLDER);

    private static final int ROWS_PER_PAGE = 8;
    private static final int PATTERNS_PER_ROW = 9;

    private final InternalInventory internalPatternInventory = new InternalInventory() {

        @Override
        public int size() {
            return mutableItemTransferList.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(int slotIndex) {
            return mutableItemTransferList.getStackInSlot(slotIndex);
        }

        @Override
        public void setItemDirect(int slotIndex, ItemStack stack) {
            mutableItemTransferList.setStackInSlot(slotIndex, stack);
            mutableItemTransferList.onContentsChanged();
            onPatternChange(slotIndex);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot <= mutableItemTransferList.getSlots() && AEUtils.molecularFilter(stack, getLevel());
        }
    };
    @DescSynced
    @Persisted
    public boolean isHiddenTerminal = false;

    @Override
    public boolean isVisibleInTerminal() {
        return !isHiddenTerminal;
    }
    // ========================================
    // Status
    // ========================================

    @DescSynced
    @Persisted
    @Setter
    protected String customName = "";

    private boolean needPatternSync;

    private boolean shouldOpen = false;

    // ========================================
    // Handlers
    // ========================================

    protected final NotifiableMAHandlerTrait maHandler;

    private final MutableItemTransferList mutableItemTransferList;

    protected PaginationUIManager paginationUIManager;

    // ========================================
    // Inventory
    // ========================================

    @Persisted
    protected final ItemStackTransfer sharedToolsInventory;

    private final BiMap<@NotNull IPatternDetails, Integer> patternSlotMap;

    private final Int2ObjectMap<@NotNull ObjectSet<@NotNull Item>> toolsSlotMap;

    @Getter
    private final Object2LongLinkedOpenHashMap<GenericStack> outputItems;  // must 1 count

    @Getter
    private final Object2LongOpenHashMap<AEKey> buffer;

    @DescSynced
    private final Set<Long> proxies = new LongOpenHashSet();
    private final ReferenceSortedSet<IItemTransfer> proxyStackTransfers = new ReferenceLinkedOpenHashSet<>();

    public MEMolecularAssemblerIOPartMachine(IMachineBlockEntity holder) {
        super(holder, IO.BOTH);
        getMainNode().addService(IGridTickable.class, new Ticker()).addService(ICraftingProvider.class, this);

        patternSlotMap = HashBiMap.create();
        toolsSlotMap = new Int2ObjectOpenHashMap<>();
        outputItems = new Object2LongLinkedOpenHashMap<>();
        buffer = new Object2LongOpenHashMap<>();
        maHandler = new MECraftHandler(this);
        sharedToolsInventory = new ItemStackTransfer(9);

        mutableItemTransferList = new MutableItemTransferList();
    }

    @Override
    public boolean pushPattern(IPatternDetails iPatternDetails, KeyCounter[] keyCounters) {
        if (!getMainNode().isActive() || !patternSlotMap.containsKey(iPatternDetails) || !(iPatternDetails instanceof AEProcessingPattern processingPattern)) {
            return false;
        }

        final GenericStack output = processingPattern.getOutputs()[0];
        if (!(output.what() instanceof AEItemKey)) return false;

        int slot = patternSlotMap.get(processingPattern);
        if (toolsSlotMap.containsKey(slot)) {
            var requiredTools = new ObjectOpenHashSet<>(toolsSlotMap.get(slot));

            for (int i = 0; i < sharedToolsInventory.getSlots(); i++) {
                final var stack = sharedToolsInventory.getStackInSlot(i);
                if (!stack.isDamageableItem()) requiredTools.remove(stack.getItem());
                if (requiredTools.isEmpty()) break;
            }

            if (!requiredTools.isEmpty()) {
                AEUtils.pushInputsToMEPatternBufferInventory(keyCounters, this.buffer::addTo);
                this.meTrait.notifySelfIO();
                return true;
            }
        }

        final GenericStack requireStack = processingPattern.getInputs()[0].getPossibleInputs()[0];
        long multiplier = 0;
        for (var inputList : keyCounters) {
            for (var input : inputList) {
                if (requireStack.what().equals(input.getKey())) {
                    multiplier = input.getLongValue() / (requireStack.amount() * processingPattern.getInputs()[0].getMultiplier());
                    break;
                }
            }
        }
        if (multiplier == 0) return false;

        outputItems.addTo(output, multiplier);
        maHandler.notifyListeners();
        return true;
    }

    @Override
    public boolean gtlcore$canProduceGraphPattern(IPatternDetails pattern) {
        if (!getMainNode().isActive() || !(pattern instanceof AEProcessingPattern) || !patternSlotMap.containsKey(pattern)) return false;
        var required = toolsSlotMap.get(patternSlotMap.get(pattern));
        if (required == null || required.isEmpty()) return true;
        var missing = new ObjectOpenHashSet<>(required);
        for (int i = 0; i < sharedToolsInventory.getSlots(); i++) {
            var stack = sharedToolsInventory.getStackInSlot(i);
            if (!stack.isDamageableItem()) missing.remove(stack.getItem());
        }
        return missing.isEmpty();
    }

    @Override
    public long gtlcore$graphCapacity(IPatternDetails pattern, Map<AEKey, Long> inputPerRun, long requested) {
        if (!gtlcore$canProduceGraphPattern(pattern)) return 0;
        GenericStack output = pattern.getOutputs()[0];
        long queued = outputItems.getLong(output);
        if (queued < 0 || output.amount() <= 0) return 0;
        return Math.max(0, Math.min(requested, Long.MAX_VALUE / output.amount() - queued));
    }

    @Override
    public void init(@NotNull Set<BlockPos> proxies) {
        clear();

        this.proxies.addAll(proxies.stream().mapToLong(BlockPos::asLong).collect(LongArrayList::new, LongArrayList::add, LongArrayList::addAll));
        mutableItemTransferList.addTransfers(getProxies());

        if (!mutableItemTransferList.isEmpty()) {
            for (int i = 0; i < mutableItemTransferList.getSlots(); i++) {
                var pattern = getPatternDetailsAsProcessing(mutableItemTransferList.getStackInSlot(i), i);
                if (pattern != null) patternSlotMap.forcePut(pattern, i);
            }
            shouldOpen = true;
        } else {
            shouldOpen = false;
        }
        needPatternSync = true;
    }

    @Override
    public @NotNull NotifiableMAHandlerTrait getNotifiableMAHandlerTrait() {
        return this.maHandler;
    }

    @Override
    public boolean canShared() {
        return false;
    }

    protected void clear() {
        this.mutableItemTransferList.clear();
        this.patternSlotMap.clear();
        this.toolsSlotMap.clear();
        this.proxies.clear();
        shouldOpen = false;
    }

    private @Nullable IPatternDetails getPatternDetailsAsProcessing(ItemStack stack, int slotIndex) {
        if (!stack.isEmpty()) {
            if (stack.getItem() instanceof EncodedPatternItem encodedPatternItem) {
                final var decodePattern = encodedPatternItem.decode(stack, getLevel(), false);
                if (decodePattern instanceof IMolecularAssemblerSupportedPattern molecularAssemblerSupportedPattern) {
                    final var pair = AEUtils.createProcessingFromCraftPattern(molecularAssemblerSupportedPattern, getLevel());
                    final var remainingStacks = pair.getRight();
                    final var pattern = pair.getLeft();
                    if (!(pattern instanceof AEProcessingPattern)) return null;
                    if (remainingStacks != null && !remainingStacks.isEmpty()) toolsSlotMap.put(slotIndex, remainingStacks);
                    else toolsSlotMap.remove(slotIndex);
                    return pattern;
                } else return null;
            }
        }
        return null;
    }

    protected ReferenceSortedSet<IItemTransfer> getProxies() {
        if (proxyStackTransfers.size() != proxies.size()) {
            proxyStackTransfers.clear();
            ObjectList<IItemTransfer> patternInventories = new ObjectArrayList<>();
            for (var pos : proxies) {
                if (MetaMachine.getMachine(Objects.requireNonNull(getLevel()), BlockPos.of(pos)) instanceof MECraftPatternContainerPartMachine proxy) {
                    patternInventories.add(proxy.getPatternInventory());
                }
            }
            patternInventories.sort(Comparator.comparingInt(IMECraftPatternContainer::sumNonEmpty).reversed()
                    .thenComparingInt(IItemTransfer::getSlots));
            proxyStackTransfers.addAll(patternInventories);
        }
        return ReferenceSortedSets.unmodifiable(proxyStackTransfers);
    }

    @Override
    public @NotNull ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    // ========================================
    // LIFECYCLE & NETWORK MANAGEMENT
    // ========================================

    @Nullable
    protected TickableSubscription updateSubs;

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.@NotNull State reason) {
        super.onMainNodeStateChanged(reason);
        this.updateSubscription();
    }

    @Override
    public void onUnload() {
        super.onUnload();
        clear();
    }

    @Override
    public void removedFromController(@NotNull IMultiController controller) {
        super.removedFromController(controller);
        clear();
        needPatternSync = true;
    }

    @Override
    public void onMachineRemoved() {
        clearInventory(sharedToolsInventory);
    }

    protected void updateSubscription() {
        if (getMainNode().isOnline()) {
            updateSubs = subscribeServerTick(updateSubs, this::update);
        } else if (updateSubs != null) {
            updateSubs.unsubscribe();
            updateSubs = null;
        }
    }

    protected void update() {
        if (needPatternSync) {
            ICraftingProvider.requestUpdate(getMainNode());
            this.needPatternSync = false;
        }
    }

    protected void onPatternChange(int index) {
        if (isRemote()) return;

        var newPattern = mutableItemTransferList.getStackInSlot(index);
        var newPatternDetails = getPatternDetailsAsProcessing(newPattern, index);

        if (newPatternDetails != null) patternSlotMap.forcePut(newPatternDetails, index);
        else patternSlotMap.inverse().remove(index);

        needPatternSync = true;
    }

    // ========================================
    // GUI
    // ========================================

    @Override
    public @NotNull Widget createUIWidget() {
        var tempmutableItemTransferList = new MutableItemTransferList(getProxies());

        final int totalCount = tempmutableItemTransferList.getSlots();
        final int colSize = 9;
        final int uiWidth = Math.max(PATTERNS_PER_ROW * 18 + 16, 106);
        final int uiHeight = ROWS_PER_PAGE * 18 + 28;

        this.paginationUIManager = new PaginationUIManager(9, ROWS_PER_PAGE, totalCount,
                uiWidth, uiHeight,
                this::onPatternChange,
                tempmutableItemTransferList);

        var group = new WidgetGroup(0, 0, paginationUIManager.getUiWidth(), paginationUIManager.getUiHeight());

        // ME Network status indicator
        group.addWidget(new LabelWidget(8, 2,
                () -> this.isOnline ? "gtceu.gui.me_network.online" : "gtceu.gui.me_network.offline"));

        // Custom name input widget
        group.addWidget(new AETextInputButtonWidget(18 * colSize + 8 - 70, 2, 70, 10)
                .setText(customName)
                .setOnConfirm(this::setCustomName)
                .setButtonTooltips(Component.translatable("gui.gtlcore.rename.desc")));

        group.addWidget(paginationUIManager.createPaginationUI(null));

        return group;
    }

    @Override
    public void attachConfigurators(ConfiguratorPanel configuratorPanel) {
        // Share inventory configurator
        configuratorPanel.attachConfigurators(new FancyInvConfigurator(
                sharedToolsInventory, Component.translatable("gui.gtceu.share_inventory.title"))
                .setTooltips(List.of(
                        Component.translatable("gui.gtceu.share_inventory.desc.0"),
                        Component.translatable("gui.gtceu.share_inventory.desc.1"))));

        // Visibility in ME Pattern Access Terminal
        configuratorPanel.attachConfigurators(new IFancyConfiguratorButton.Toggle(
                GuiTextures.BUTTON_VISIBLE.getSubTexture(0, 0, 1, 0.5),
                GuiTextures.BUTTON_VISIBLE.getSubTexture(0, 0.5, 1, 0.5),
                () -> this.isHiddenTerminal, (clickData, pressed) -> this.isHiddenTerminal = pressed)
                .setTooltipsSupplier(pressed -> List.of(
                        Component.translatable(pressed ? "gui.gtlcore.hidden_in_terminal" : "gui.gtlcore.visible_in_terminal"))));
    }

    @Override
    public boolean shouldOpenUI(Player player, InteractionHand hand, BlockHitResult hit) {
        return shouldOpen;
    }

    // ========================================
    // Persist
    // ========================================

    @Override
    public void saveCustomPersistedData(@NotNull CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        if (buffer.isEmpty() && outputItems.isEmpty()) return;
        ListTag bufferTag = AEUtils.createListTag(AEKey::toTagGeneric, buffer);
        if (!bufferTag.isEmpty()) tag.put("buffer", bufferTag);

        ListTag outputTag = AEUtils.createListTag(GenericStack::writeTag, outputItems);
        if (!outputTag.isEmpty()) tag.put("outputItems", outputTag);
    }

    @Override
    public void loadCustomPersistedData(@NotNull CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        buffer.clear();
        ListTag bufferTag = tag.getList("buffer", Tag.TAG_COMPOUND);
        AEUtils.loadInventory(bufferTag, AEKey::fromTagGeneric, buffer);

        ListTag outputTag = tag.getList("outputItems", Tag.TAG_COMPOUND);
        AEUtils.loadInventory(outputTag, GenericStack::readTag, outputItems);
    }

    // ========================================
    // Pattern
    // ========================================

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return new ObjectArrayList<>(patternSlotMap.keySet());
    }

    @Override
    public boolean isBusy() {
        return false;
    }

    @Override
    public @Nullable IGrid getGrid() {
        return getMainNode().getGrid();
    }

    @Override
    public InternalInventory getTerminalPatternInventory() {
        return internalPatternInventory;
    }

    @Override
    public PatternContainerGroup getTerminalGroup() {
        List<IMultiController> controllers = getControllers();

        // Handle multiblock controller grouping
        if (!controllers.isEmpty()) {
            IMultiController controller = controllers.get(0);
            MultiblockMachineDefinition controllerDefinition = controller.self().getDefinition();

            if (!customName.isEmpty()) {
                return new PatternContainerGroup(
                        AEItemKey.of(controllerDefinition.asStack()),
                        Component.literal(customName),
                        Collections.emptyList());
            } else {
                return new PatternContainerGroup(
                        AEItemKey.of(controllerDefinition.asStack()), Component.translatable(controllerDefinition.getDescriptionId()), Collections.emptyList());
            }
        } else {
            if (!customName.isEmpty()) {
                return new PatternContainerGroup(
                        AEItemKey.of(GTLMachines.GTAEMachines.ME_EXTEND_PATTERN_BUFFER.getItem()),
                        Component.literal(customName),
                        Collections.emptyList());
            } else {
                return new PatternContainerGroup(
                        AEItemKey.of(GTLMachines.GTAEMachines.ME_EXTEND_PATTERN_BUFFER.getItem()),
                        GTLMachines.GTAEMachines.ME_EXTEND_PATTERN_BUFFER.get().getDefinition().getItem().getDescription(),
                        Collections.emptyList());
            }
        }
    }

    protected class Ticker implements IGridTickable {

        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return new TickingRequest(MEExtendedOutputPartMachineBase.MIN_FREQUENCY, MEExtendedOutputPartMachineBase.MAX_FREQUENCY, false, true);
        }

        @Override
        public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
            if (!getMainNode().isActive()) {
                isSleeping = true;
                return TickRateModulation.SLEEP;
            }

            if (buffer.isEmpty()) {
                if (ticksSinceLastCall >= MEExtendedOutputPartMachineBase.MAX_FREQUENCY) {
                    isSleeping = true;
                    return TickRateModulation.SLEEP;
                } else return TickRateModulation.SLOWER;
            } else return reFunds(buffer, getMainNode().getGrid(), actionSource) ? TickRateModulation.URGENT : TickRateModulation.SLOWER;
        }
    }
}
