package org.gtlcore.gtlcore.common.machine.multiblock.part.ae;

import org.gtlcore.gtlcore.api.gui.MEPatternCatalystUIManager;
import org.gtlcore.gtlcore.api.gui.PatternCircuitConfigurator;
import org.gtlcore.gtlcore.api.machine.trait.*;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEPatternTrait;
import org.gtlcore.gtlcore.common.data.GTLItems;
import org.gtlcore.gtlcore.common.data.GTLMachines;
import org.gtlcore.gtlcore.common.item.VirtualIngredientBehavior;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.gtlcore.gtlcore.integration.ae2.widget.AEPatternViewExtendSlotWidget;
import org.gtlcore.gtlcore.utils.GTLUtil;
import org.gtlcore.gtlcore.utils.Registries;

import com.gregtechceu.gtceu.api.capability.recipe.*;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfiguratorButton;
import com.gregtechceu.gtceu.api.machine.*;
import com.gregtechceu.gtceu.api.machine.fancyconfigurator.*;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AETextInputButtonWidget;
import com.gregtechceu.gtceu.utils.*;

import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.*;
import com.lowdragmc.lowdraglib.misc.*;
import com.lowdragmc.lowdraglib.side.fluid.*;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.LazyManaged;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.*;
import appeng.api.stacks.*;
import appeng.core.definitions.AEItems;
import appeng.crafting.pattern.EncodedPatternItem;
import appeng.crafting.pattern.ProcessingPatternItem;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.*;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Stream;

import static org.gtlcore.gtlcore.api.pattern.AdvancedBlockPattern.foundItem;

public class MEPatternBufferPartMachine extends MEPatternBufferPartMachineBase {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            MEPatternBufferPartMachine.class, MEPatternBufferPartMachineBase.MANAGED_FIELD_HOLDER);

    @DescSynced
    @Persisted
    public boolean isHiddenTerminal = false;

    @Override
    public boolean isVisibleInTerminal() {
        return !isHiddenTerminal;
    }

    private final InternalInventory internalPatternInventory = new InternalInventory() {

        @Override
        public int size() {
            return maxPatternCount;
        }

        @Override
        public ItemStack getStackInSlot(int slotIndex) {
            return patternInventory.getStackInSlot(slotIndex);
        }

        @Override
        public void setItemDirect(int slotIndex, ItemStack stack) {
            patternInventory.setStackInSlot(slotIndex, stack);
            patternInventory.onContentsChanged(slotIndex);
            onPatternChange(slotIndex);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot >= 0 && slot < maxPatternCount && AEUtils.PROCESS_FILTER.apply(stack);
        }
    };

    // ========================================
    // Info
    // ========================================

    protected final int maxPatternCount;
    private final boolean[] hasPatternArray;
    @DescSynced
    protected final boolean[] cacheRecipe;
    @Persisted
    protected boolean keepByProduct = false;
    @DescSynced
    @Persisted
    private int embeddedCircuitConfig = 1;
    @DescSynced
    @Persisted
    private boolean skipExistingCircuitPatterns = true;

    // ========================================
    // Inventory
    // ========================================

    @Getter
    @Persisted
    private final ItemStackTransfer patternInventory;

    @Persisted
    protected final ItemStackTransfer[] catalystItems;

    @Persisted
    @LazyManaged
    protected final FluidTransferList[] catalystFluids;

    @Getter
    @Persisted
    protected final InternalSlot[] internalInventory;

    // ========================================
    // Handlers
    // ========================================

    protected final MEPatternBufferRecipeHandlerTrait recipeHandler;

    // ========================================
    // Cache Map
    // ========================================

    protected final Int2ReferenceMap<ObjectSet<@NotNull GTRecipe>> recipeMultipleCacheMap;
    protected final byte[] cacheRecipeCount;
    private final BiMap<@NotNull IPatternDetails, Integer> patternSlotMap;
    private final Int2ObjectMap<IPatternDetails> slot2PatternMap;
    protected IntConsumer removeSlotFromMap = i -> {};

    public MEPatternBufferPartMachine(IMachineBlockEntity holder, int maxPatternCount, IO io) {
        super(holder, io);

        this.maxPatternCount = maxPatternCount;

        this.hasPatternArray = new boolean[maxPatternCount];
        this.cacheRecipe = new boolean[maxPatternCount];
        this.internalInventory = new InternalSlot[maxPatternCount];
        this.catalystItems = new ItemStackTransfer[maxPatternCount];
        this.catalystFluids = new FluidTransferList[maxPatternCount];
        this.cacheRecipeCount = new byte[maxPatternCount];

        this.patternSlotMap = HashBiMap.create();
        this.slot2PatternMap = new Int2ObjectOpenHashMap<>();
        this.recipeMultipleCacheMap = new Int2ReferenceOpenHashMap<>();

        this.patternInventory = new ItemStackTransfer(maxPatternCount);
        this.patternInventory.setFilter(AEUtils.PROCESS_FILTER);
        Arrays.setAll(internalInventory, this::createInternalSlot);
        Arrays.setAll(catalystItems, i -> {
            var transfer = new ItemStackTransfer(9);
            transfer.setFilter(stack -> !(stack.getItem() instanceof ProcessingPatternItem));
            return transfer;
        });
        Arrays.setAll(catalystFluids, i -> new FluidTransferList(Stream.generate(() -> (IFluidTransfer) new FluidStorage(16 * FluidHelper.getBucket()))
                .limit(9)
                .toList()));
        Arrays.fill(cacheRecipeCount, (byte) 1);

        this.recipeHandler = createRecipeHandler(io);

        for (InternalSlot internalSlot : internalInventory) {
            internalSlot.setOnContentsChanged(() -> {
                recipeHandler.getMeFluidHandler().notifyListeners();
                recipeHandler.getMeItemHandler().notifyListeners();
            });
        }

        if (io == IO.BOTH) {
            getMainNode().addService(IGridTickable.class, new Ticker());
        }
    }

    protected InternalSlot createInternalSlot(int slotIndex) {
        return new PatternBufferInternalSlot(slotIndex);
    }

    protected MEPatternBufferRecipeHandlerTrait createRecipeHandler(IO io) {
        return new MEPatternBufferRecipeHandlerTrait(this, io);
    }

    // ========================================
    // LIFECYCLE & NETWORK MANAGEMENT
    // ========================================

    @Override
    public void onLoad() {
        super.onLoad();
        if (getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().execute(() -> {
                for (int i = 0; i < patternInventory.getSlots(); i++) {
                    var pattern = patternInventory.getStackInSlot(i);
                    var realPattern = getRealPattern(i, pattern);
                    if (realPattern != null) {
                        this.slot2PatternMap.put(i, realPattern);
                        hasPatternArray[i] = true;
                    }
                }
                reCalculatePatternSlotMap();
                needPatternSync = true;
            });
            for (int i = 0; i < maxPatternCount; i++) {
                final int index = i;
                internalInventory[index].setOnContentsChanged(() -> {
                    recipeHandler.getMeFluidHandler().notifyListeners();
                    recipeHandler.getMeItemHandler().notifyListeners();
                });
                catalystItems[index].setOnContentsChanged(() -> reCalculateCatalystItemMap(index));
                for (IFluidTransfer transfer : catalystFluids[index].transfers) {
                    if (transfer instanceof FluidStorage storage) {
                        storage.setOnContentsChanged(() -> reCalculateCatalystFluidMap(index));
                    }
                }
            }
        }
    }

    protected void onPatternChange(int index) {
        if (isRemote()) return;

        var internalInv = internalInventory[index];
        var newPattern = patternInventory.getStackInSlot(index);
        var newPatternDetailsWithOutCircuit = getRealPattern(index, newPattern);
        var oldPatternDetails = slot2PatternMap.get(index);

        if (newPatternDetailsWithOutCircuit != null) {
            slot2PatternMap.put(index, newPatternDetailsWithOutCircuit);
            hasPatternArray[index] = true;
        } else {
            slot2PatternMap.remove(index);
            hasPatternArray[index] = false;
        }

        if (oldPatternDetails != null && !oldPatternDetails.equals(newPatternDetailsWithOutCircuit)) {
            internalInv.getCacheManager().clearAllCaches();
            internalInv.clearVirtualSupply();
            removeSlotFromGTRecipeCache(index);
            refundSlot(internalInv.getItemInventory(), internalInv.getFluidInventory());
            AEUtils.reFunds(buffer, getMainNode().getGrid(), actionSource);
        }

        reCalculatePatternSlotMap();
        needPatternSync = true;
    }

    @Override
    public void onMachineRemoved() {
        super.onMachineRemoved();
        clearInventory(patternInventory);
        for (ItemStackTransfer catalystItem : catalystItems) {
            clearInventory(catalystItem);
        }
    }

    private void reCalculateCatalystItemMap(int slot) {
        final var internalSlot = internalInventory[slot];
        final var itemCatalystInventory = internalSlot.getItemCatalystInventory();
        itemCatalystInventory.clear();
        internalSlot.getConfiguredVirtualItems().clear();
        internalSlot.getConfiguredVirtualFluids().clear();
        var catalystItem = catalystItems[slot];
        for (int i = 0; i < catalystItem.getSlots(); i++) {
            ItemStack stack = catalystItem.getStackInSlot(i);
            if (stack.isEmpty()) continue;

            if (GTLItems.VIRTUAL_INGREDIENT.isIn(stack)) {
                // A wrapper parked here vouches for its payload without the slot ever holding it. The wrapper itself
                // is not a catalyst, so it must not reach the catalyst map, or recipes would start matching against
                // the wrapper item instead of what it stands for.
                AEItemKey payloadItem = VirtualIngredientBehavior.payloadItemKey(stack);
                if (payloadItem != null) internalSlot.getConfiguredVirtualItems().add(payloadItem);
                AEFluidKey payloadFluid = VirtualIngredientBehavior.payloadFluidKey(stack);
                if (payloadFluid != null) internalSlot.getConfiguredVirtualFluids().add(payloadFluid);
                continue;
            }

            itemCatalystInventory.mergeLong(AEItemKey.of(stack), stack.getCount(), Long::sum);
        }
        removeSlotFromGTRecipeCache(slot);
        internalSlot.getOnContentsChanged().run();
    }

    private void reCalculateCatalystFluidMap(int slot) {
        final var fluidCatalystInventory = internalInventory[slot].getFluidCatalystInventory();
        fluidCatalystInventory.clear();
        var catalystFluid = catalystFluids[slot];
        for (int i = 0; i < catalystFluid.getTanks(); i++) {
            FluidStack stack = catalystFluid.getFluidInTank(i);
            if (!stack.isEmpty()) {
                fluidCatalystInventory.mergeLong(AEFluidKey.of(stack.getFluid()), stack.getAmount(), Long::sum);
            }
        }
        internalInventory[slot].getOnContentsChanged().run();
    }

    protected void reCalculatePatternSlotMap() {
        patternSlotMap.clear();
        for (var entry : Int2ObjectMaps.fastIterable(slot2PatternMap)) {
            int slot = entry.getIntKey();
            var pattern = entry.getValue();
            if (pattern != null) {
                if (cacheRecipe[slot]) patternSlotMap.forcePut(pattern, slot);
                else patternSlotMap.putIfAbsent(pattern, slot);
            }
        }
    }

    protected void removeSlotFromGTRecipeCache(int slot) {
        cacheRecipe[slot] = false;
        recipeMultipleCacheMap.remove(slot);
        removeSlotFromMap.accept(slot);
        notifyProxySlotRemoved(slot);
    }

    protected void refreshAllByProduct() {
        this.slot2PatternMap.clear();
        for (int i = 0; i < patternInventory.getSlots(); i++) {
            var pattern = patternInventory.getStackInSlot(i);
            var realPattern = getRealPattern(i, pattern);
            if (realPattern != null) {
                this.slot2PatternMap.put(i, realPattern);
            }
        }
        reCalculatePatternSlotMap();
        needPatternSync = true;
    }

    // ========================================
    // Slot Access and Pattern Routing
    // ========================================

    @Override
    @Nullable
    protected Integer getSlotIndexForPattern(IPatternDetails pattern) {
        return patternSlotMap.get(pattern);
    }

    @Override
    protected int getInternalSlotCount() {
        return internalInventory.length;
    }

    @Override
    protected InternalSlot getInternalSlot(int slotIndex) {
        return internalInventory[slotIndex];
    }

    @Override
    protected boolean hasRecipeCacheInSlot(int slotIndex) {
        return cacheRecipe[slotIndex];
    }

    @Override
    protected boolean hasPatternInSlot(int slotIndex) {
        return slotIndex >= 0 && slotIndex < hasPatternArray.length && hasPatternArray[slotIndex];
    }

    // ========================================
    // Persistence
    // ========================================

    @Override
    public void saveCustomPersistedData(@NotNull CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        if (!recipeMultipleCacheMap.isEmpty()) {
            final CompoundTag recipeCacheTag = new CompoundTag();
            for (var entry : Int2ReferenceMaps.fastIterable(recipeMultipleCacheMap)) {
                var recipeSet = entry.getValue();
                if (recipeSet.isEmpty()) continue;

                final ListTag list = new ListTag();
                for (GTRecipe recipe : recipeSet) {
                    list.add(GTLUtil.serializeNBT(recipe));
                }

                recipeCacheTag.put(Integer.toString(entry.getIntKey()), list);
            }
            tag.put("recipeMultipleCacheIdMap", recipeCacheTag);
        }

        tag.putByteArray("cacheRecipeCount", cacheRecipeCount);
    }

    @Override
    public void loadCustomPersistedData(@NotNull CompoundTag tag) {
        super.loadCustomPersistedData(tag);

        var byteArray = tag.getByteArray("cacheRecipeCount");
        System.arraycopy(byteArray, 0, cacheRecipeCount, 0, byteArray.length);

        recipeMultipleCacheMap.clear();
        var recipeManager = Registries.getRecipeManager();
        if (tag.contains("recipeMultipleCacheIdMap")) {
            CompoundTag recipeCacheTag = tag.getCompound("recipeMultipleCacheIdMap");
            for (String key : recipeCacheTag.getAllKeys()) {
                final int slotIndex = Integer.parseInt(key);
                final ListTag recipeTags = recipeCacheTag.getList(key, Tag.TAG_COMPOUND);
                for (Tag recipeTag : recipeTags) {
                    GTRecipe recipe = GTLUtil.deserializeNBT(recipeTag);
                    if (recipe != null && slotIndex >= 0 && slotIndex < maxPatternCount) {
                        var real = recipe.recipeType.getRecipe(recipeManager, recipe.id);
                        if (real != null) {
                            var set = recipeMultipleCacheMap.computeIfAbsent(slotIndex, integer -> new ObjectArraySet<>());
                            set.add(real);
                            if (set.size() >= cacheRecipeCount[slotIndex]) cacheRecipe[slotIndex] = true;
                        }
                    }
                }
            }
        } else if (tag.contains("gtRecipeCache")) {
            CompoundTag oldRecipeCacheTag = tag.getCompound("gtRecipeCache");
            for (String key : oldRecipeCacheTag.getAllKeys()) {
                int slotIndex = Integer.parseInt(key);
                Tag recipeTag = oldRecipeCacheTag.get(key);
                GTRecipe recipe = GTLUtil.deserializeNBT(recipeTag);
                if (recipe != null && slotIndex >= 0 && slotIndex < maxPatternCount) {
                    var real = recipe.recipeType.getRecipe(recipeManager, recipe.id);
                    if (real != null) {
                        var set = recipeMultipleCacheMap.computeIfAbsent(slotIndex, integer -> new ObjectArraySet<>());
                        set.add(real);
                        if (set.size() >= cacheRecipeCount[slotIndex]) cacheRecipe[slotIndex] = true;
                    }
                }
            }
        }
    }

    public void copyFromTag(CompoundTag tag, ServerPlayer serverPlayer) {
        this.setCustomName(tag.getString("name"));
        var list = tag.getList("patterns", Tag.TAG_COMPOUND);

        int listIndex = 0;
        for (int index = 0; index < internalPatternInventory.size() && listIndex < list.size(); index++) {
            if (!internalPatternInventory.getStackInSlot(index).isEmpty()) {
                continue;
            }

            var result = foundItem(serverPlayer, List.of(AEItems.BLANK_PATTERN.stack()), AEItems.BLANK_PATTERN.stack()::is);
            if (result.getA() == null) break;

            CompoundTag patternData = list.getCompound(listIndex);
            var patternTag = patternData.getCompound("pattern");
            var sourceCacheCount = patternData.getByte("cacheCount");
            if (sourceCacheCount <= 0) break;

            internalPatternInventory.setItemDirect(index, ItemStack.of(patternTag));
            this.cacheRecipeCount[index] = sourceCacheCount;
            var handler = result.getB();
            if (handler != null) handler.extractItem(result.getC(), 1, false);

            listIndex++;
        }
    }

    public CompoundTag copyToTag(CompoundTag tags) {
        var tag = new CompoundTag();
        tag.putString("name", customName);

        var listPattern = new ListTag();
        for (int slotIndex : patternSlotMap.values()) {
            ItemStack stack = internalPatternInventory.getStackInSlot(slotIndex);
            if (!stack.isEmpty()) {
                CompoundTag patternData = new CompoundTag();
                patternData.put("pattern", stack.serializeNBT());
                patternData.putByte("cacheCount", cacheRecipeCount[slotIndex]);
                listPattern.add(patternData);
            }
        }
        tag.put("patterns", listPattern);

        tags.put("tag", tag);
        return tags;
    }

    public boolean pasteFromTag(CompoundTag tag) {
        var patternList = tag.getList("patterns", Tag.TAG_COMPOUND);
        int usedCount = 0;
        for (ItemStack ignored : internalPatternInventory) {
            usedCount++;
        }
        if (internalPatternInventory.size() - usedCount < patternList.size()) return false;

        this.setCustomName(tag.getString("name"));
        this.keepByProduct = tag.getBoolean("keepByProduct");

        int listIndex = 0;
        for (int slotIndex = 0; slotIndex < internalPatternInventory.size() && listIndex < patternList.size(); slotIndex++) {
            if (!internalPatternInventory.getStackInSlot(slotIndex).isEmpty()) {
                continue;
            }

            CompoundTag patternData = patternList.getCompound(listIndex);
            var patternTag = patternData.getCompound("pattern");
            var sourceCacheCount = patternData.getByte("cacheCount");
            var catalystItemsTag = patternData.getCompound("catalystItems");
            var catalystFluidsTag = patternData.getCompound("catalystFluids");

            internalPatternInventory.setItemDirect(slotIndex, ItemStack.of(patternTag));
            this.catalystItems[slotIndex].deserializeNBT(catalystItemsTag);
            this.catalystFluids[slotIndex].deserializeNBT(catalystFluidsTag);
            if (sourceCacheCount > 0) this.cacheRecipeCount[slotIndex] = sourceCacheCount;

            this.catalystItems[slotIndex].onContentsChanged();
            this.catalystFluids[slotIndex].onContentsChanged();

            listIndex++;
        }

        this.sharedCatalystInventory.storage.deserializeNBT(tag.getCompound("sharedCatalystInventory"));
        this.sharedCircuitInventory.storage.deserializeNBT(tag.getCompound("sharedCircuitInventory"));

        var catalystTanks = tag.getList("sharedCatalystTank", Tag.TAG_COMPOUND);
        var tankStorages = this.sharedCatalystTank.getStorages();
        for (int i = 0; i < Math.min(catalystTanks.size(), tankStorages.length); i++) {
            tankStorages[i].deserializeNBT(catalystTanks.getCompound(i));
        }

        this.sharedCatalystTank.onContentsChanged();
        this.sharedCatalystInventory.onContentsChanged();
        this.sharedCircuitInventory.onContentsChanged();

        releaseProxies();

        for (long l : tag.getLongArray("proxies")) {
            var pos = BlockPos.of(l);
            if (MetaMachine.getMachine(Objects.requireNonNull(getLevel()), pos) instanceof MEPatternBufferProxyPartMachine proxy) {
                proxy.setBuffer(getPos());
            }
        }

        refreshAllByProduct();
        markDirty();

        return true;
    }

    public CompoundTag cutToTag(CompoundTag tags) {
        var tag = new CompoundTag();
        tag.putString("name", customName);
        tag.putString("dimension", Objects.requireNonNull(getLevel()).dimension().location().toString());
        tag.putLong("bufferPos", getPos().asLong());

        var listPattern = new ListTag();
        for (int slotIndex = 0; slotIndex < internalPatternInventory.size(); slotIndex++) {
            ItemStack stack = internalPatternInventory.getStackInSlot(slotIndex);
            if (stack.isEmpty()) continue;

            CompoundTag patternData = new CompoundTag();
            patternData.put("pattern", stack.serializeNBT());
            patternData.putByte("cacheCount", cacheRecipeCount[slotIndex]);
            patternData.put("catalystItems", catalystItems[slotIndex].serializeNBT());
            patternData.put("catalystFluids", catalystFluids[slotIndex].serializeNBT());
            listPattern.add(patternData);

            internalPatternInventory.setItemDirect(slotIndex, ItemStack.EMPTY);
            for (int i = 0; i < catalystItems[slotIndex].getSlots(); i++) {
                catalystItems[slotIndex].setStackInSlot(i, ItemStack.EMPTY);
            }
            for (int i = 0; i < catalystFluids[slotIndex].getTanks(); i++) {
                catalystFluids[slotIndex].setFluidInTank(i, FluidStack.empty());
            }

            catalystItems[slotIndex].onContentsChanged();
            catalystFluids[slotIndex].onContentsChanged();
            cacheRecipeCount[slotIndex] = 1;
        }
        tag.put("patterns", listPattern);
        tag.put("sharedCatalystInventory", sharedCatalystInventory.storage.serializeNBT());
        tag.put("sharedCircuitInventory", sharedCircuitInventory.storage.serializeNBT());
        tag.putBoolean("keepByProduct", keepByProduct);
        tag.put("proxies", new LongArrayTag(getProxyPosList()));

        var tankList = new ListTag();
        Arrays.stream(sharedCatalystTank.getStorages())
                .map(FluidStorage::serializeNBT)
                .forEach(tankList::add);
        tag.put("sharedCatalystTank", tankList);

        for (int i = 0; i < sharedCatalystInventory.getSlots(); i++) {
            sharedCatalystInventory.setStackInSlot(i, ItemStack.EMPTY);
        }
        for (int i = 0; i < sharedCatalystTank.getTanks(); i++) {
            sharedCatalystTank.setFluidInTank(i, FluidStack.empty());
        }
        sharedCircuitInventory.setStackInSlot(0, ItemStack.EMPTY);
        sharedCatalystInventory.onContentsChanged();
        sharedCatalystTank.onContentsChanged();
        sharedCircuitInventory.onContentsChanged();

        releaseProxies();

        tags.put("cut", tag);
        markDirty();
        return tags;
    }

    @Override
    @NotNull
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    // ========================================
    // GUI SYSTEM
    // ========================================

    @Override
    public void attachConfigurators(ConfiguratorPanel configuratorPanel) {
        super.attachConfigurators(configuratorPanel);
        configuratorPanel.attachConfigurators(new IFancyConfiguratorButton.Toggle(
                org.gtlcore.gtlcore.api.gui.GuiTextures.BUTTON_DISABLE_BYPRODUCT.getSubTexture(0, 0, 1, 0.5),
                org.gtlcore.gtlcore.api.gui.GuiTextures.BUTTON_DISABLE_BYPRODUCT.getSubTexture(0, 0.5, 1, 0.5),
                () -> !this.keepByProduct, (clickData, pressed) -> {
                    this.keepByProduct = !pressed;
                    refreshAllByProduct();
                })
                .setTooltipsSupplier(pressed -> List.of(Component.translatable("tooltip.gtlcore.disable_by_product").setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW))
                        .append(Component.translatable(pressed ? "gtceu.multiblock.universal.distinct.yes" : "gtceu.multiblock.universal.distinct.no")))));

        // Visibility in ME Pattern Access Terminal
        configuratorPanel.attachConfigurators(new IFancyConfiguratorButton.Toggle(
                org.gtlcore.gtlcore.api.gui.GuiTextures.BUTTON_VISIBLE.getSubTexture(0, 0, 1, 0.5),
                org.gtlcore.gtlcore.api.gui.GuiTextures.BUTTON_VISIBLE.getSubTexture(0, 0.5, 1, 0.5),
                () -> this.isHiddenTerminal, (clickData, pressed) -> this.isHiddenTerminal = pressed)
                .setTooltipsSupplier(pressed -> List.of(
                        Component.translatable(pressed ? "gui.gtlcore.hidden_in_terminal" : "gui.gtlcore.visible_in_terminal"))));

        configuratorPanel.attachConfigurators(new PatternCircuitConfigurator(
                () -> embeddedCircuitConfig,
                this::setEmbeddedCircuitConfig,
                () -> skipExistingCircuitPatterns,
                (clickData, pressed) -> skipExistingCircuitPatterns = pressed,
                this::applyConfiguredCircuit,
                this::removeAllPatternCircuits));
    }

    @Override
    public @NotNull Widget createUIWidget() {
        int rowSize = 9;
        int colSize = maxPatternCount / rowSize;
        var group = new WidgetGroup(0, 0, 18 * rowSize + 16, 18 * colSize + 16);

        group.addWidget(new LabelWidget(8, 2,
                () -> this.isOnline ? "gtceu.gui.me_network.online" : "gtceu.gui.me_network.offline"));

        group.addWidget(new AETextInputButtonWidget(18 * rowSize + 8 - 70, 2, 70, 10)
                .setText(customName)
                .setOnConfirm(this::setCustomName)
                .setButtonTooltips(Component.translatable("gui.gtceu.rename.desc")));

        final var catalystUIManager = new MEPatternCatalystUIManager(group.getSizeWidth() + 4, catalystItems, catalystFluids, cacheRecipeCount, this::removeSlotFromGTRecipeCache);
        group.waitToAdded(catalystUIManager);

        int index = 0;
        for (int y = 0; y < colSize; ++y) {
            for (int x = 0; x < rowSize; ++x) {
                int finalI = index;

                var slot = new AEPatternViewExtendSlotWidget(patternInventory, index++, x * 18 + 8, y * 18 + 14)
                        .setOnMiddleClick(() -> catalystUIManager.toggleFor(finalI))
                        .setOnPatternSlotChanged(() -> this.onPatternChange(finalI))
                        .setOccupiedTexture(GuiTextures.SLOT)
                        .setItemHook(stack -> {
                            if (!stack.isEmpty() && stack.getItem() instanceof EncodedPatternItem iep) {
                                final ItemStack out = iep.getOutput(stack);
                                if (!out.isEmpty()) return out;
                            }
                            return stack;
                        })
                        .setOnAddedTooltips((s, l) -> {
                            if (cacheRecipe[finalI])
                                l.add(Component.translatable("gtceu.machine.pattern.recipe.cache"));
                        })
                        .setBackground(GuiTextures.SLOT, GuiTextures.PATTERN_OVERLAY);
                group.addWidget(slot);
            }
        }
        return group;
    }

    // ========================================
    // CIRCUIT HANDLING
    // ========================================

    private void setEmbeddedCircuitConfig(int embeddedCircuitConfig) {
        this.embeddedCircuitConfig = Math.max(1, Math.min(IntCircuitBehaviour.CIRCUIT_MAX, embeddedCircuitConfig));
    }

    private void applyConfiguredCircuit(ClickData clickData) {
        if (clickData.isRemote) return;
        embedCircuitToPatterns(embeddedCircuitConfig, !skipExistingCircuitPatterns);
    }

    private void removeAllPatternCircuits(ClickData clickData) {
        if (clickData.isRemote) return;
        for (int i = 0; i < internalPatternInventory.size(); i++) {
            ItemStack stack = internalPatternInventory.getStackInSlot(i);
            if (stack.isEmpty()) continue;

            ItemStack after = realPatternHelper.removeCircuitFromPattern(stack, getLevel());
            if (!after.isEmpty() && !ItemStack.matches(stack, after)) {
                internalPatternInventory.setItemDirect(i, after);
            }
        }
    }

    private void embedCircuitToPatterns(int circuitConfig, boolean replaceExisting) {
        int safeCircuitConfig = Math.max(1, Math.min(IntCircuitBehaviour.CIRCUIT_MAX, circuitConfig));
        for (int i = 0; i < internalPatternInventory.size(); i++) {
            ItemStack stack = internalPatternInventory.getStackInSlot(i);
            if (stack.isEmpty()) continue;

            ItemStack after = realPatternHelper.createPatternWithCircuit(stack, safeCircuitConfig, replaceExisting, getLevel());
            if (!after.isEmpty() && !ItemStack.matches(stack, after)) {
                internalPatternInventory.setItemDirect(i, after);
            }
        }
    }

    private IPatternDetails getRealPattern(int slot, ItemStack stack) {
        if (!stack.isEmpty()) {
            var internalSlot = internalInventory[slot];
            return realPatternHelper.processPatternWithCircuit(
                    stack, internalSlot.getCacheManager()::setCircuitCache, getLevel(), keepByProduct);
        }
        return null;
    }

    // ========================================
    // AE2 CRAFTING
    // ========================================

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return patternSlotMap.keySet().stream().toList();
    }

    /** Bounded, read-only context for orders waiting for machine output. */
    public String gtlcore$graphDiagnostic(IPatternDetails pattern) {
        Integer slot = getSlotIndexForPattern(pattern);
        var controllers = new java.util.LinkedHashSet<>(getControllers());
        for (var proxy : getProxies()) {
            controllers.addAll(proxy.getControllers());
            if (controllers.size() >= 8) break;
        }
        String state = controllers.isEmpty() ? "NO_FORMED_CONTROLLER" : controllers.stream().limit(8).map(controller -> {
            var machine = controller.self();
            return machine.getDefinition().getId() + "@" + machine.getPos() + ":" +
                    (machine instanceof com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine working ?
                            working.getRecipeLogic().getStatus() : "connected");
        }).toList().toString();
        String inputs = "";
        if (slot != null && slot >= 0 && slot < internalInventory.length) {
            InternalSlot buffer = internalInventory[slot];
            inputs = "; items=" + buffer.getItemInventory().object2LongEntrySet().stream().limit(4).toList() +
                    "; fluids=" + buffer.getFluidInventory().object2LongEntrySet().stream().limit(4).toList();
        }
        return "buffer=" + getPos() + "; online=" + getMainNode().isActive() + "; slot=" + slot + "; controllers=" + state + inputs;
    }

    // ========================================
    // PATTERN CONTAINER IMPLEMENTATION
    // ========================================

    @Override
    public InternalInventory getTerminalPatternInventory() {
        return internalPatternInventory;
    }

    @Override
    public PatternContainerGroup getTerminalGroup() {
        List<IMultiController> controllers = getControllers();

        if (!controllers.isEmpty()) {
            IMultiController controller = controllers.get(0);
            MultiblockMachineDefinition controllerDefinition = controller.self().getDefinition();

            if (!customName.isEmpty()) {
                return new PatternContainerGroup(
                        AEItemKey.of(controllerDefinition.asStack()),
                        Component.literal(customName),
                        Collections.emptyList());
            } else {
                ItemStack circuitStack = sharedCircuitInventory.storage.getStackInSlot(0);
                int circuitConfiguration = circuitStack.isEmpty() ? -1 :
                        IntCircuitBehaviour.getCircuitConfiguration(circuitStack);

                Component groupName = circuitConfiguration != -1 ?
                        Component.translatable(controllerDefinition.getDescriptionId())
                                .append(" - " + circuitConfiguration) :
                        Component.translatable(controllerDefinition.getDescriptionId());

                return new PatternContainerGroup(
                        AEItemKey.of(controllerDefinition.asStack()), groupName, Collections.emptyList());
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

    // ========================================
    // IMEPatternPartMachine
    // ========================================

    @Override
    protected @NotNull MEPatternTrait createMETrait() {
        return new MEPatternTrait(this);
    }

    @Override
    public Pair<IMERecipeHandlerTrait<Ingredient, ItemStack>, IMERecipeHandlerTrait<FluidIngredient, FluidStack>> getMERecipeHandlerTraits() {
        return Pair.of(recipeHandler.meItemHandler, recipeHandler.meFluidHandler);
    }

    protected class PatternBufferInternalSlot extends InternalSlot {

        @Getter
        private final Object2LongMap<AEItemKey> itemCatalystInventory = new Object2LongArrayMap<>();

        @Getter
        private final Object2LongMap<AEFluidKey> fluidCatalystInventory = new Object2LongArrayMap<>();

        public PatternBufferInternalSlot(int slotIndex) {
            super(slotIndex);
            itemCatalystInventory.defaultReturnValue(0);
            fluidCatalystInventory.defaultReturnValue(0);
        }

        @Override
        protected boolean hasItemCatalystInventory() {
            return !itemCatalystInventory.isEmpty();
        }

        @Override
        protected boolean hasFluidCatalystInventory() {
            return !fluidCatalystInventory.isEmpty();
        }

        @Override
        protected void appendLimitItemCatalystInputs(ObjectList<ItemStack> limitInput) {
            for (var entry : Object2LongMaps.fastIterable(itemCatalystInventory)) {
                limitInput.add(entry.getKey().toStack((int) Math.min(entry.getLongValue(), Integer.MAX_VALUE)));
            }
        }

        @Override
        protected void appendLimitFluidCatalystInputs(ObjectList<FluidStack> limitInput) {
            for (var entry : Object2LongMaps.fastIterable(fluidCatalystInventory)) {
                limitInput.add(FluidStack.create(entry.getKey().getFluid(), entry.getLongValue()));
            }
        }

        @Override
        public boolean testCatalystItemInternal(GTRecipe recipe) {
            for (var content : recipe.getInputContents(ItemRecipeCapability.CAP)) {
                if (content.chance <= 0) continue;
                var ingredient = (Ingredient) content.getContent();
                for (ItemStack item : ingredient.getItems()) {
                    AEItemKey key = AEItemKey.of(item);
                    if (itemCatalystInventory.containsKey(key)) return false;
                }
            }
            return true;
        }

        @Override
        public boolean testCatalystFluidInternal(GTRecipe recipe) {
            for (var content : recipe.getInputContents(FluidRecipeCapability.CAP)) {
                if (content.chance <= 0) continue;
                var fluidIngredient = (FluidIngredient) content.getContent();
                for (FluidStack stack : fluidIngredient.getStacks()) {
                    AEFluidKey key = AEFluidKey.of(stack.getFluid());
                    if (fluidCatalystInventory.containsKey(key)) return false;
                }
            }
            return true;
        }

        @Override
        public CompoundTag serializeNBT() {
            CompoundTag tag = super.serializeNBT();

            ListTag itemCatalystTag = AEUtils.createListTag(AEItemKey::toTag, itemCatalystInventory);
            if (!itemCatalystTag.isEmpty()) tag.put("catalystInventory", itemCatalystTag);

            ListTag fluidCatalystTag = AEUtils.createListTag(AEFluidKey::toTag, fluidCatalystInventory);
            if (!fluidCatalystTag.isEmpty()) tag.put("catalystFluidInventory", fluidCatalystTag);

            return tag;
        }

        @Override
        public void deserializeNBT(CompoundTag tag) {
            super.deserializeNBT(tag);

            itemCatalystInventory.clear();
            ListTag catalystItems = tag.getList("catalystInventory", Tag.TAG_COMPOUND);
            AEUtils.loadInventory(catalystItems, AEItemKey::fromTag, itemCatalystInventory);

            fluidCatalystInventory.clear();
            ListTag catalystFluids = tag.getList("catalystFluidInventory", Tag.TAG_COMPOUND);
            AEUtils.loadInventory(catalystFluids, AEFluidKey::fromTag, fluidCatalystInventory);
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
            } else return AEUtils.reFunds(buffer, getMainNode().getGrid(), actionSource) ? TickRateModulation.URGENT : TickRateModulation.SLOWER;
        }
    }

    protected class MEPatternTrait extends MEIOTrait implements IMEPatternTrait {

        public MEPatternTrait(MEPatternBufferPartMachine machine) {
            super(machine);
        }

        @Override
        public MEPatternBufferPartMachine getMachine() {
            return (MEPatternBufferPartMachine) machine;
        }

        @Override
        public @NotNull ObjectSet<@NotNull GTRecipe> getCachedGTRecipe() {
            ObjectSet<GTRecipe> recipes = new ObjectOpenHashSet<>();
            for (var it = Int2ReferenceMaps.fastIterator(recipeMultipleCacheMap); it.hasNext();) {
                var entry = it.next();
                var recipeSet = entry.getValue();
                int slot = entry.getIntKey();
                if (recipeSet.isEmpty()) it.remove();
                else if (cacheRecipe[slot] && internalInventory[slot].isActive()) recipes.addAll(recipeSet);
            }
            return recipes;
        }

        @Override
        public void setSlotCacheRecipe(int index, GTRecipe recipe) {
            if (recipe != null && recipe.recipeType != GTRecipeTypes.DUMMY_RECIPES) {
                var set = recipeMultipleCacheMap.computeIfAbsent(index, integer -> new ObjectArraySet<>());
                if (set.add(recipe)) cacheRecipe[index] = set.size() >= cacheRecipeCount[index];
            }
        }

        @Override
        public @NotNull Int2ReferenceMap<ObjectSet<@NotNull GTRecipe>> getSlot2RecipesCache() {
            return recipeMultipleCacheMap;
        }

        @Override
        public void setOnPatternChange(IntConsumer removeMapOnSlot) {
            removeSlotFromMap = removeMapOnSlot;
        }

        @Override
        public boolean hasCacheInSlot(int slot) {
            return cacheRecipe[slot];
        }
    }
}
