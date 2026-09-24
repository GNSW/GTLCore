package org.gtlcore.gtlcore.common.machine.multiblock.part.ae;

import org.gtlcore.gtlcore.api.gui.PatternBufferProxyPositionConfigurator;
import org.gtlcore.gtlcore.api.machine.trait.IMERecipeHandlerTrait;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEPatternPartMachine;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEPatternTrait;
import org.gtlcore.gtlcore.api.machine.trait.NotifiableCircuitItemStackHandler;
import org.gtlcore.gtlcore.common.data.GTLItems;
import org.gtlcore.gtlcore.common.item.VirtualIngredientBehavior;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphDispatchPreflight;
import org.gtlcore.gtlcore.integration.ae2.handler.MEBufferPatternHelper;
import org.gtlcore.gtlcore.integration.ae2.handler.SlotCacheManager;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.fancyconfigurator.ButtonConfigurator;
import com.gregtechceu.gtceu.api.machine.fancyconfigurator.CircuitFancyConfigurator;
import com.gregtechceu.gtceu.api.machine.fancyconfigurator.FancyInvConfigurator;
import com.gregtechceu.gtceu.api.machine.fancyconfigurator.FancyTankConfigurator;
import com.gregtechceu.gtceu.api.machine.feature.IInteractedMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.common.data.GTItems;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.gregtechceu.gtceu.utils.ResearchManager;

import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.side.fluid.FluidHelper;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.syncdata.IContentChangeAware;
import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.*;
import appeng.helpers.patternprovider.PatternContainer;
import com.hepdd.gtmthings.common.block.machine.trait.CatalystFluidStackHandler;
import com.hepdd.gtmthings.common.block.machine.trait.CatalystItemStackHandler;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.objects.*;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared implementation for pattern buffer variants.
 */
public abstract class MEPatternBufferPartMachineBase extends MEIOPartMachine
                                                     implements ICraftingProvider, PatternContainer, IMEPatternPartMachine, IInteractedMachine,
                                                     GraphDispatchPreflight {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            MEPatternBufferPartMachineBase.class, MEIOPartMachine.MANAGED_FIELD_HOLDER);
    private static final Comparator<BlockPos> PROXY_POSITION_ORDER = Comparator.comparingInt((BlockPos position) -> position.getX())
            .thenComparingInt(position -> position.getY())
            .thenComparingInt(position -> position.getZ());

    // ========================================
    // Common Fields
    // ========================================

    @DescSynced
    @Persisted
    @Setter
    @Getter
    protected String customName = "";

    @Getter
    protected final Object2LongOpenHashMap<AEKey> buffer;

    @Getter
    @Persisted(key = "shareInventory")
    protected final CatalystItemStackHandler sharedCatalystInventory;

    @Getter
    @Persisted(key = "shareTank")
    protected final CatalystFluidStackHandler sharedCatalystTank;

    @Getter
    @Persisted(key = "mePatternCircuitInventory")
    protected final NotifiableItemStackHandler sharedCircuitInventory;

    @Getter
    protected final MEBufferPatternHelper realPatternHelper;

    protected boolean needPatternSync;

    @Nullable
    protected TickableSubscription updateSubs;

    @Persisted(key = "proxies")
    private final ObjectOpenHashSet<BlockPos> proxies;
    private final Set<MEPatternBufferProxyPartMachine> proxyMachines;

    // ========================================
    // Constructor
    // ========================================

    public MEPatternBufferPartMachineBase(IMachineBlockEntity holder, IO io) {
        super(holder, io);

        this.buffer = new Object2LongOpenHashMap<>();
        this.sharedCatalystInventory = new CatalystItemStackHandler(this, 9, IO.IN, IO.NONE);
        this.sharedCatalystTank = new CatalystFluidStackHandler(this, 9, 16 * FluidHelper.getBucket(), IO.IN, IO.NONE);
        this.sharedCircuitInventory = new NotifiableCircuitItemStackHandler(this);
        this.proxies = new ObjectOpenHashSet<>();
        this.proxyMachines = new ReferenceOpenHashSet<>();

        this.realPatternHelper = new MEBufferPatternHelper((NotifiableCircuitItemStackHandler) sharedCircuitInventory);

        getMainNode().addService(ICraftingProvider.class, this);
    }

    // ========================================
    // LIFECYCLE & NETWORK MANAGEMENT
    // ========================================

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.@NotNull State reason) {
        super.onMainNodeStateChanged(reason);
        this.updateSubscription();
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

    @Override
    public void onMachineRemoved() {
        clearInventory(sharedCatalystInventory);
        releaseProxies();
    }

    // ========================================
    // PROXY MANAGEMENT
    // ========================================

    public void addProxy(MEPatternBufferProxyPartMachine proxy) {
        proxies.add(proxy.getPos());
        proxyMachines.add(proxy);
    }

    public void removeProxy(MEPatternBufferProxyPartMachine proxy) {
        proxies.remove(proxy.getPos());
        proxyMachines.remove(proxy);
    }

    public Set<MEPatternBufferProxyPartMachine> getProxies() {
        if (proxyMachines.size() != proxies.size()) {
            var level = getLevel();
            if (level == null) return Collections.unmodifiableSet(proxyMachines);

            proxyMachines.clear();
            for (var it = proxies.iterator(); it.hasNext();) {
                var pos = it.next();
                if (MetaMachine.getMachine(level, pos) instanceof MEPatternBufferProxyPartMachine proxy) {
                    proxyMachines.add(proxy);
                } else {
                    it.remove();
                }
            }
        }
        return Collections.unmodifiableSet(proxyMachines);
    }

    public List<BlockPos> getBoundProxyPositions() {
        return proxies.stream().sorted(PROXY_POSITION_ORDER).toList();
    }

    protected List<Long> getProxyPosList() {
        return getBoundProxyPositions().stream().map(BlockPos::asLong).toList();
    }

    protected void releaseProxies() {
        for (MEPatternBufferProxyPartMachine proxy : List.copyOf(getProxies())) {
            proxy.setBuffer(null);
        }
        proxyMachines.clear();
        proxies.clear();
    }

    protected void notifyProxySlotRemoved(int slot) {
        for (MEPatternBufferProxyPartMachine proxy : getProxies()) {
            proxy.removeSlotFromMap.accept(slot);
        }
    }

    // ========================================
    // REFUND SYSTEM
    // ========================================

    protected void refundAll(ClickData clickData) {
        if (!clickData.isRemote) {
            // Every slot, not only the active ones: a slot whose sole content is a leftover virtual supply has an
            // empty real inventory and would otherwise never be visited.
            for (int i = 0; i < getInternalSlotCount(); i++) {
                InternalSlot slot = getInternalSlot(i);
                refundSlot(slot.itemInventory, slot.fluidInventory);
                slot.clearVirtualSupply();
            }
            AEUtils.reFunds(buffer, getMainNode().getGrid(), actionSource);
        }
    }

    protected void refundSlot(Object2LongOpenHashMap<AEItemKey> itemInventory, Object2LongOpenHashMap<AEFluidKey> fluidInventory) {
        for (var it = itemInventory.object2LongEntrySet().fastIterator(); it.hasNext();) {
            var entry = it.next();
            long amount = entry.getLongValue();
            if (amount > 0) {
                buffer.addTo(entry.getKey(), amount);
                it.remove();
            }
        }

        for (var it = fluidInventory.object2LongEntrySet().fastIterator(); it.hasNext();) {
            var entry = it.next();
            long amount = entry.getLongValue();
            if (amount > 0) {
                buffer.addTo(entry.getKey(), amount);
                it.remove();
            }
        }
    }

    // ========================================
    // GUI SYSTEM
    // ========================================

    @Override
    public void attachConfigurators(ConfiguratorPanel configuratorPanel) {
        configuratorPanel.attachConfigurators(new ButtonConfigurator(
                new GuiTextureGroup(GuiTextures.BUTTON, GuiTextures.REFUND_OVERLAY), this::refundAll)
                .setTooltips(List.of(Component.translatable("gui.gtceu.refund_all.desc"))));

        configuratorPanel.attachConfigurators(new FancyInvConfigurator(
                sharedCatalystInventory.storage, Component.translatable("gui.gtceu.share_inventory.title"))
                .setTooltips(List.of(
                        Component.translatable("gui.gtceu.share_inventory.desc.0"),
                        Component.translatable("gui.gtceu.share_inventory.desc.1"))));

        configuratorPanel.attachConfigurators(new FancyTankConfigurator(
                sharedCatalystTank.getStorages(), Component.translatable("gui.gtceu.share_tank.title"))
                .setTooltips(List.of(
                        Component.translatable("gui.gtceu.share_tank.desc.0"),
                        Component.translatable("gui.gtceu.share_inventory.desc.1"))));

        configuratorPanel.attachConfigurators(new CircuitFancyConfigurator(sharedCircuitInventory.storage));
        configuratorPanel.attachConfigurators(new PatternBufferProxyPositionConfigurator(this::getBoundProxyPositions));
    }

    // ========================================
    // PERSISTENCE
    // ========================================

    @Override
    public void saveCustomPersistedData(@NotNull CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        ListTag bufferTag = AEUtils.createListTag(AEKey::toTagGeneric, buffer);
        if (!bufferTag.isEmpty()) tag.put("buffer", bufferTag);
    }

    @Override
    public void loadCustomPersistedData(@NotNull CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        ListTag bufferTag = tag.getList("buffer", Tag.TAG_COMPOUND);
        AEUtils.loadInventory(bufferTag, AEKey::fromTagGeneric, buffer);
    }

    @Override
    @NotNull
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    // ========================================
    // DATA STICK INTERACTION
    // ========================================

    @Override
    public InteractionResult onUse(BlockState state, Level world, BlockPos pos, Player player,
                                   InteractionHand hand, BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) return InteractionResult.PASS;

        if (stack.is(GTItems.TOOL_DATA_STICK.asItem())) {
            if (!world.isClientSide) {
                var researchData = ResearchManager.readResearchId(stack);
                if (researchData != null) {
                    return InteractionResult.PASS;
                }

                stack.getOrCreateTag().putIntArray("pos", new int[] { pos.getX(), pos.getY(), pos.getZ() });
                player.sendSystemMessage(Component.translatable("gtceu.machine.me.import_copy_settings"));
            }
            return InteractionResult.sidedSuccess(world.isClientSide);
        }

        return InteractionResult.PASS;
    }

    // ========================================
    // AE2 PATTERN CONTAINER
    // ========================================

    @Override
    public @Nullable IGrid getGrid() {
        return getMainNode().getGrid();
    }

    @Override
    public boolean isBusy() {
        return false;
    }

    public Pair<Object2LongOpenHashMap<Item>, Object2LongOpenHashMap<Fluid>> getMergedInternalSlot() {
        Object2LongOpenHashMap<Item> items = new Object2LongOpenHashMap<>();
        Object2LongOpenHashMap<Fluid> fluids = new Object2LongOpenHashMap<>();
        for (InternalSlot slot : getActiveInternalSlots()) {
            if (!slot.isActive()) continue;
            MEPatternBufferJadeMerger.mergeItemKeys(items, slot.getItemInventory());
            MEPatternBufferJadeMerger.mergeFluidKeys(fluids, slot.getFluidInventory());
        }
        for (int slotIndex = 0; slotIndex < getInternalSlotCount(); slotIndex++) {
            InternalSlot slot = getInternalSlot(slotIndex);
            MEPatternBufferJadeMerger.mergeItemKeys(items, slot.getItemCatalystInventory());
            MEPatternBufferJadeMerger.mergeFluidKeys(fluids, slot.getFluidCatalystInventory());
        }
        MEPatternBufferJadeMerger.mergeItemStacks(items, sharedCatalystInventory.getContents());
        MEPatternBufferJadeMerger.mergeFluidStacks(fluids, sharedCatalystTank.getContents());
        return Pair.of(items, fluids);
    }

    @Override
    public @NotNull IMEPatternTrait getMETrait() {
        return (IMEPatternTrait) meTrait;
    }

    public ItemStack getCircuitForRecipe(int slotIndex) {
        return realPatternHelper.getCircuitForRecipe(getInternalSlot(slotIndex).getCacheManager().getCircuitStack());
    }

    // ========================================
    // SLOT ACCESS AND PATTERN ROUTING
    // ========================================

    protected Iterable<InternalSlot> getActiveInternalSlots() {
        int slotCount = getInternalSlotCount();
        List<InternalSlot> slots = new ObjectArrayList<>(slotCount);
        for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
            InternalSlot slot = getInternalSlot(slotIndex);
            if (slot.isActive()) slots.add(slot);
        }
        return slots;
    }

    protected @Nullable InternalSlot getFirstActiveInternalSlot(IntCollection slots) {
        for (int slot : slots) {
            if (slot >= 0 && slot < getInternalSlotCount()) {
                InternalSlot internalSlot = getInternalSlot(slot);
                if (internalSlot.isActive()) return internalSlot;
            }
        }
        return null;
    }

    protected @Nullable InternalSlot getInternalSlotOrNull(int slotIndex) {
        if (slotIndex >= 0 && slotIndex < getInternalSlotCount()) {
            return getInternalSlot(slotIndex);
        }
        return null;
    }

    protected int[] getActiveSlots() {
        int slotCount = getInternalSlotCount();
        int[] slots = new int[slotCount];
        int size = 0;
        for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
            if (getInternalSlot(slotIndex).isActive()) slots[size++] = slotIndex;
        }
        return size == slotCount ? slots : Arrays.copyOf(slots, size);
    }

    protected int[] getActiveAndUnCachedSlots() {
        int slotCount = getInternalSlotCount();
        int[] slots = new int[slotCount];
        int size = 0;
        for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
            if (getInternalSlot(slotIndex).isActive() && !hasRecipeCacheInSlot(slotIndex)) slots[size++] = slotIndex;
        }
        return size == slotCount ? slots : Arrays.copyOf(slots, size);
    }

    @Nullable
    protected abstract Integer getSlotIndexForPattern(IPatternDetails pattern);

    protected abstract int getInternalSlotCount();

    protected abstract InternalSlot getInternalSlot(int slotIndex);

    protected boolean hasRecipeCacheInSlot(int slotIndex) {
        return false;
    }

    protected abstract boolean hasPatternInSlot(int slotIndex);

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (!getMainNode().isActive()) {
            return false;
        }

        var slotIndex = getSlotIndexForPattern(patternDetails);
        if (slotIndex != null && slotIndex >= 0 && slotIndex < getInternalSlotCount()) {
            getInternalSlot(slotIndex).pushPattern(inputHolder);
            return true;
        }
        return false;
    }

    @Override
    public boolean gtlcore$canProduceGraphPattern(IPatternDetails pattern) {
        Integer slot = getSlotIndexForPattern(pattern);
        return getMainNode().isActive() && slot != null && slot >= 0 && slot < getInternalSlotCount();
    }

    @Override
    public long gtlcore$graphCapacity(IPatternDetails pattern, Map<AEKey, Long> inputPerRun, long requested) {
        if (!gtlcore$canProduceGraphPattern(pattern)) return 0;
        InternalSlot slot = getInternalSlot(getSlotIndexForPattern(pattern));
        for (var input : inputPerRun.entrySet()) {
            long held;
            if (input.getKey() instanceof AEItemKey item) held = slot.getItemInventory().getLong(item);
            else if (input.getKey() instanceof AEFluidKey fluid) held = slot.getFluidInventory().getLong(fluid);
            else return 0;
            if (held < 0) return 0;
            requested = Math.min(requested, (Long.MAX_VALUE - held) / input.getValue());
        }
        return requested;
    }

    @Override
    public abstract Pair<IMERecipeHandlerTrait<Ingredient, ItemStack>, IMERecipeHandlerTrait<FluidIngredient, FluidStack>> getMERecipeHandlerTraits();

    // ========================================
    // INTERNAL SLOT CLASS
    // ========================================

    /**
     * Ingredient storage for one resolved pattern slot.
     */
    protected class InternalSlot implements ITagSerializable<CompoundTag>, IContentChangeAware {

        @Getter
        @Setter
        protected Runnable onContentsChanged = () -> {};

        @Getter
        private final Object2LongOpenHashMap<AEItemKey> itemInventory = new Object2LongOpenHashMap<>();

        @Getter
        private final Object2LongOpenHashMap<AEFluidKey> fluidInventory = new Object2LongOpenHashMap<>();

        /** Sets rather than counted maps: having no quantity is what lets these satisfy a demand for any amount. */
        private final ObjectSet<AEItemKey> virtualItemSupply = new ObjectOpenHashSet<>();

        private final ObjectSet<AEFluidKey> virtualFluidSupply = new ObjectOpenHashSet<>();

        /**
         * A pattern carrying a wrapped circuit has no circuit left for {@code processPatternWithCircuit} to recover,
         * so unlike a real one this is the only record of it and has to outlive a reload on its own.
         */
        private int virtualCircuitConfig = -1;

        /**
         * Kept apart from the pushed ones: these live as long as the wrapper sits in the slot, not as long as the
         * pattern.
         */
        @Getter
        private final ObjectSet<AEItemKey> configuredVirtualItems = new ObjectOpenHashSet<>();

        @Getter
        private final ObjectSet<AEFluidKey> configuredVirtualFluids = new ObjectOpenHashSet<>();

        private boolean suppliesItem(AEItemKey key) {
            return virtualItemSupply.contains(key) || configuredVirtualItems.contains(key);
        }

        private boolean suppliesFluid(AEFluidKey key) {
            return virtualFluidSupply.contains(key) || configuredVirtualFluids.contains(key);
        }

        @Persisted
        @Getter
        private final SlotCacheManager cacheManager = new SlotCacheManager();

        @Getter
        private final int slotIndex;

        public InternalSlot(int slotIndex) {
            this.slotIndex = slotIndex;
            itemInventory.defaultReturnValue(0);
            fluidInventory.defaultReturnValue(0);
        }

        public boolean isActive() {
            return hasPatternInSlot(slotIndex) && (!itemInventory.isEmpty() || !fluidInventory.isEmpty());
        }

        public boolean isItemActive(boolean simulate) {
            return hasPatternInSlot(slotIndex) && (simulate ?
                    (!itemInventory.isEmpty() || !sharedCatalystInventory.isEmpty() ||
                            !getCircuitForRecipe(slotIndex).isEmpty() || hasItemCatalystInventory() ||
                            hasVirtualItemSupply()) :
                    !itemInventory.isEmpty());
        }

        public boolean isFluidActive(boolean simulate) {
            return hasPatternInSlot(slotIndex) && (simulate ?
                    (!fluidInventory.isEmpty() || !sharedCatalystTank.isEmpty() || hasFluidCatalystInventory() ||
                            hasVirtualFluidSupply()) :
                    !fluidInventory.isEmpty());
        }

        public void add(AEKey what, long amount) {
            if (amount <= 0L) return;
            if (what instanceof AEItemKey itemKey) {
                ItemStack stack = itemKey.getReadOnlyStack();
                if (GTLItems.VIRTUAL_INGREDIENT.isIn(stack)) {
                    // Wrapper is spent here: supply is unlimited, so only the payload is worth remembering.
                    AEItemKey payloadItem = VirtualIngredientBehavior.payloadItemKey(stack);
                    if (payloadItem != null) {
                        ItemStack payload = payloadItem.toStack(1);
                        if (IntCircuitBehaviour.isIntegratedCircuit(payload)) {
                            // Circuits are stripped out of the item map and matched against the slot's cached
                            // configuration, so a virtual one has to land in that channel or nothing consults it.
                            virtualCircuitConfig = IntCircuitBehaviour.getCircuitConfiguration(payload);
                            cacheManager.setCircuitCache(virtualCircuitConfig);
                        } else {
                            virtualItemSupply.add(payloadItem);
                        }
                    }
                    AEFluidKey payloadFluid = VirtualIngredientBehavior.payloadFluidKey(stack);
                    if (payloadFluid != null) virtualFluidSupply.add(payloadFluid);
                    return;
                }
                itemInventory.addTo(itemKey, amount);
            } else if (what instanceof AEFluidKey fluidKey) {
                fluidInventory.addTo(fluidKey, amount);
            }
        }

        public void pushPattern(KeyCounter[] inputHolder) {
            AEUtils.pushInputsToMEPatternBufferInventory(inputHolder, this::add);
            onContentsChanged.run();
        }

        public Object2LongMap<ItemStack> getItemStackInputMap() {
            var itemInputMap = new Object2LongOpenHashMap<ItemStack>();
            for (Object2LongMap.Entry<AEItemKey> entry : Object2LongMaps.fastIterable(itemInventory)) {
                AEItemKey key = entry.getKey();
                long amount = entry.getLongValue();
                if (amount <= 0) continue;
                itemInputMap.addTo(key.toStack(1), amount);
            }
            return itemInputMap;
        }

        public Object2LongMap<FluidStack> getFluidStackInputMap() {
            var fluidInputMap = new Object2LongOpenHashMap<FluidStack>();
            for (Object2LongMap.Entry<AEFluidKey> entry : Object2LongMaps.fastIterable(fluidInventory)) {
                AEFluidKey key = entry.getKey();
                long amount = entry.getLongValue();
                if (amount <= 0) continue;
                fluidInputMap.addTo(FluidStack.create(key.getFluid(), 1), amount);
            }
            return fluidInputMap;
        }

        public ObjectList<ItemStack> getLimitItemStackInput() {
            var limitInput = new ObjectArrayList<ItemStack>(itemInventory.size());
            for (var it = Object2LongMaps.fastIterator(itemInventory); it.hasNext();) {
                var entry = it.next();
                long amount = entry.getLongValue();
                if (amount <= 0) {
                    it.remove();
                    continue;
                }
                limitInput.add(entry.getKey().toStack((int) Math.min(amount, Integer.MAX_VALUE)));
            }
            appendLimitItemCatalystInputs(limitInput);
            appendVirtualItemInputs(limitInput);
            return limitInput;
        }

        public ObjectList<FluidStack> getLimitFluidStackInput() {
            var limitInput = new ObjectArrayList<FluidStack>(fluidInventory.size());
            for (var it = Object2LongMaps.fastIterator(fluidInventory); it.hasNext();) {
                var entry = it.next();
                long amount = entry.getLongValue();
                if (amount <= 0) {
                    it.remove();
                    continue;
                }
                limitInput.add(FluidStack.create(entry.getKey().getFluid(), amount));
            }
            appendLimitFluidCatalystInputs(limitInput);
            appendVirtualFluidInputs(limitInput);
            return limitInput;
        }

        public boolean testCatalystItemInternal(GTRecipe recipe) {
            return true;
        }

        public boolean testCatalystFluidInternal(GTRecipe recipe) {
            return true;
        }

        public boolean handleItemInternal(Object2LongMap<Ingredient> left, int leftCircuit, boolean simulate) {
            if (left.isEmpty() && leftCircuit < 0) return true;

            if (simulate && leftCircuit > 0 && !(leftCircuit == cacheManager.getCircuitCache())) {
                return false;
            }

            for (var it = Object2LongMaps.fastIterator(left); it.hasNext();) {
                var entry = it.next();
                var ingredient = entry.getKey();
                long needAmount = entry.getLongValue();
                if (needAmount <= 0) {
                    it.remove();
                    continue;
                }

                AEItemKey bestMatch = simulate ?
                        cacheManager.getBestItemMatchSimulate(ingredient, itemInventory, getItemCatalystInventory(), needAmount) :
                        cacheManager.getBestItemMatch(ingredient, itemInventory, needAmount);
                if (bestMatch == null) {
                    return false;
                }
            }

            if (!simulate) {
                for (var it = Object2LongMaps.fastIterator(left); it.hasNext();) {
                    var entry = it.next();
                    var ingredient = entry.getKey();
                    long needAmount = entry.getLongValue();

                    var bestMatch = cacheManager.getBestItemMatch(ingredient, itemInventory, needAmount);
                    if (bestMatch != null) {
                        long amount = itemInventory.getLong(bestMatch);
                        long except = amount - needAmount;
                        if (except <= 0) {
                            itemInventory.removeLong(bestMatch);
                        } else {
                            itemInventory.put(bestMatch, except);
                        }
                        it.remove();
                    }
                }
            }

            return true;
        }

        public boolean handleFluidInternal(Object2LongMap<FluidIngredient> left, boolean simulate) {
            if (left.isEmpty()) return true;

            for (var it = Object2LongMaps.fastIterator(left); it.hasNext();) {
                var entry = it.next();
                var ingredient = entry.getKey();
                long needAmount = entry.getLongValue();
                if (needAmount <= 0) {
                    it.remove();
                    continue;
                }

                AEFluidKey bestMatch = simulate ?
                        cacheManager.getBestFluidMatchSimulate(ingredient, fluidInventory, getFluidCatalystInventory(), needAmount) :
                        cacheManager.getBestFluidMatch(ingredient, fluidInventory, needAmount);
                if (bestMatch == null) {
                    return false;
                }
            }

            if (!simulate) {
                for (var it = Object2LongMaps.fastIterator(left); it.hasNext();) {
                    var entry = it.next();
                    var ingredient = entry.getKey();
                    long needAmount = entry.getLongValue();

                    AEFluidKey bestMatch = cacheManager.getBestFluidMatch(ingredient, fluidInventory, needAmount);
                    if (bestMatch != null) {
                        long amount = fluidInventory.getLong(bestMatch);
                        long except = amount - needAmount;
                        if (except <= 0) {
                            fluidInventory.removeLong(bestMatch);
                        } else {
                            fluidInventory.put(bestMatch, except);
                        }
                        it.remove();
                    }
                }
            }

            return true;
        }

        /**
         * Drops the pattern-vouched virtual supply. Safe at any time: the next dispatch pushes the wrapper again and
         * re-establishes it. A real circuit is untouched -- its cache is owned by pattern processing, not by
         * {@code virtualCircuitConfig}.
         */
        public void clearVirtualSupply() {
            virtualItemSupply.clear();
            virtualFluidSupply.clear();
            if (virtualCircuitConfig >= 0) {
                virtualCircuitConfig = -1;
                cacheManager.clearCircuitCache();
            }
        }

        public Object2LongMap<AEItemKey> getItemCatalystInventory() {
            return Object2LongMaps.emptyMap();
        }

        public Object2LongMap<AEFluidKey> getFluidCatalystInventory() {
            return Object2LongMaps.emptyMap();
        }

        protected boolean hasItemCatalystInventory() {
            return false;
        }

        protected boolean hasFluidCatalystInventory() {
            return false;
        }

        protected void appendLimitItemCatalystInputs(ObjectList<ItemStack> limitInput) {}

        protected void appendLimitFluidCatalystInputs(ObjectList<FluidStack> limitInput) {}

        // ==================== Virtual ingredient supply ====================
        //
        // Keys this slot may satisfy without holding the material. Restricted to inputs the recipe declares
        // non-consumed (chance == 0), which RecipeRunner already drops from the real pass, so nothing that would have
        // been paid for is skipped. Defaults below are inert; buffers that do not opt in behave as before.

        /** Keeps the slot eligible during matching even when its real inventory is empty. */
        protected boolean hasVirtualItemSupply() {
            return !virtualItemSupply.isEmpty() || !configuredVirtualItems.isEmpty();
        }

        protected boolean hasVirtualFluidSupply() {
            return !virtualFluidSupply.isEmpty() || !configuredVirtualFluids.isEmpty();
        }

        /**
         * Publishes virtual keys to GT recipe lookup, without which the branch tree never reaches a recipe needing
         * them. Amounts are unbounded because this list only feeds lookup; parallel calculation reads
         * {@link #getItemStackInputMap()}, which never sees these.
         */
        protected void appendVirtualItemInputs(ObjectList<ItemStack> limitInput) {
            for (AEItemKey key : virtualItemSupply) {
                limitInput.add(key.toStack(Integer.MAX_VALUE));
            }
            for (AEItemKey key : configuredVirtualItems) {
                if (!virtualItemSupply.contains(key)) limitInput.add(key.toStack(Integer.MAX_VALUE));
            }
        }

        protected void appendVirtualFluidInputs(ObjectList<FluidStack> limitInput) {
            for (AEFluidKey key : virtualFluidSupply) {
                limitInput.add(FluidStack.create(key.getFluid(), Integer.MAX_VALUE));
            }
            for (AEFluidKey key : configuredVirtualFluids) {
                if (!virtualFluidSupply.contains(key)) {
                    limitInput.add(FluidStack.create(key.getFluid(), Integer.MAX_VALUE));
                }
            }
        }

        /**
         * Rejects recipes that actually consume a virtually supplied input. One slipping through would match and then
         * fail every dispatch, caching a recipe that can never run.
         *
         * @return false to veto this recipe for this slot
         */
        public boolean testVirtualItemInternal(GTRecipe recipe) {
            if (!hasVirtualItemSupply()) return true;
            for (var content : recipe.getInputContents(ItemRecipeCapability.CAP)) {
                if (content.chance <= 0) continue;
                for (ItemStack item : ((Ingredient) content.getContent()).getItems()) {
                    if (suppliesItem(AEItemKey.of(item))) return false;
                }
            }
            return true;
        }

        public boolean testVirtualFluidInternal(GTRecipe recipe) {
            if (!hasVirtualFluidSupply()) return true;
            for (var content : recipe.getInputContents(FluidRecipeCapability.CAP)) {
                if (content.chance <= 0) continue;
                for (FluidStack stack : ((FluidIngredient) content.getContent()).getStacks()) {
                    if (suppliesFluid(AEFluidKey.of(stack.getFluid()))) return false;
                }
            }
            return true;
        }

        /**
         * Drops the demands this slot covers virtually, whatever amount they ask for. Returns a filtered copy because
         * callers pass the same map to several slots in turn, or the argument itself when nothing is covered.
         */
        public Object2LongMap<Ingredient> stripVirtuallySuppliedItems(Object2LongMap<Ingredient> left) {
            if (left.isEmpty() || !hasVirtualItemSupply()) return left;
            Object2LongMap<Ingredient> remaining = null;
            for (var entry : Object2LongMaps.fastIterable(left)) {
                if (!coversItem(entry.getKey())) continue;
                if (remaining == null) remaining = new Object2LongOpenHashMap<>(left);
                remaining.removeLong(entry.getKey());
            }
            return remaining == null ? left : remaining;
        }

        public Object2LongMap<FluidIngredient> stripVirtuallySuppliedFluids(Object2LongMap<FluidIngredient> left) {
            if (left.isEmpty() || !hasVirtualFluidSupply()) return left;
            Object2LongMap<FluidIngredient> remaining = null;
            for (var entry : Object2LongMaps.fastIterable(left)) {
                if (!coversFluid(entry.getKey())) continue;
                if (remaining == null) remaining = new Object2LongOpenHashMap<>(left);
                remaining.removeLong(entry.getKey());
            }
            return remaining == null ? left : remaining;
        }

        private boolean coversItem(Ingredient ingredient) {
            for (ItemStack item : ingredient.getItems()) {
                if (suppliesItem(AEItemKey.of(item))) return true;
            }
            return false;
        }

        private boolean coversFluid(FluidIngredient ingredient) {
            for (FluidStack stack : ingredient.getStacks()) {
                if (suppliesFluid(AEFluidKey.of(stack.getFluid()))) return true;
            }
            return false;
        }

        @Override
        public CompoundTag serializeNBT() {
            CompoundTag tag = new CompoundTag();

            ListTag itemsTag = AEUtils.createListTag(AEItemKey::toTag, itemInventory);
            if (!itemsTag.isEmpty()) tag.put("inventory", itemsTag);

            ListTag fluidsTag = AEUtils.createListTag(AEFluidKey::toTag, fluidInventory);
            if (!fluidsTag.isEmpty()) tag.put("fluidInventory", fluidsTag);

            // Kept across a reload so a slot stays runnable until its next dispatch re-establishes the supply.
            if (!virtualItemSupply.isEmpty()) {
                ListTag list = new ListTag();
                for (AEItemKey key : virtualItemSupply) list.add(key.toTag());
                tag.put("virtualItemSupply", list);
            }
            if (!virtualFluidSupply.isEmpty()) {
                ListTag list = new ListTag();
                for (AEFluidKey key : virtualFluidSupply) list.add(key.toTag());
                tag.put("virtualFluidSupply", list);
            }
            if (virtualCircuitConfig >= 0) tag.putInt("virtualCircuit", virtualCircuitConfig);

            return tag;
        }

        @Override
        public void deserializeNBT(CompoundTag tag) {
            itemInventory.clear();
            fluidInventory.clear();

            ListTag items = tag.getList("inventory", Tag.TAG_COMPOUND);
            AEUtils.loadInventory(items, AEItemKey::fromTag, itemInventory);

            ListTag fluids = tag.getList("fluidInventory", Tag.TAG_COMPOUND);
            AEUtils.loadInventory(fluids, AEFluidKey::fromTag, fluidInventory);

            virtualItemSupply.clear();
            ListTag virtualItems = tag.getList("virtualItemSupply", Tag.TAG_COMPOUND);
            for (int i = 0; i < virtualItems.size(); i++) {
                AEItemKey key = AEItemKey.fromTag(virtualItems.getCompound(i));
                if (key != null) virtualItemSupply.add(key);
            }

            virtualFluidSupply.clear();
            ListTag virtualFluids = tag.getList("virtualFluidSupply", Tag.TAG_COMPOUND);
            for (int i = 0; i < virtualFluids.size(); i++) {
                AEFluidKey key = AEFluidKey.fromTag(virtualFluids.getCompound(i));
                if (key != null) virtualFluidSupply.add(key);
            }

            virtualCircuitConfig = tag.contains("virtualCircuit", Tag.TAG_INT) ? tag.getInt("virtualCircuit") : -1;
            // A pattern holding a real circuit re-registers it by itself and outranks this.
            if (virtualCircuitConfig >= 0) cacheManager.setCircuitCache(virtualCircuitConfig);
        }
    }
}
