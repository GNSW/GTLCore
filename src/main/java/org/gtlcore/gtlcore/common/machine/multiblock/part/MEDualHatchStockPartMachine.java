package org.gtlcore.gtlcore.common.machine.multiblock.part;

import org.gtlcore.gtlcore.api.gui.AdvancedMEConfigurator;
import org.gtlcore.gtlcore.api.gui.TurnsConfiguratorButton;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IModifiableSyncOffset;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.ExportOnlyAEConfigureFluidSlot;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.ExportOnlyAEConfigureItemSlot;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.IMESlot;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.IOptimizedMEList;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.gtlcore.gtlcore.client.gui.widget.AEDualConfigWidget;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.gtlcore.gtlcore.utils.MachineUtil;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IDataStickInteractable;
import com.gregtechceu.gtceu.api.machine.trait.*;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.ingredient.*;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.gregtechceu.gtceu.integration.ae2.machine.MEBusPartMachine;
import com.gregtechceu.gtceu.integration.ae2.slot.*;
import com.gregtechceu.gtceu.integration.ae2.utils.AEUtil;

import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.*;
import com.lowdragmc.lowdraglib.gui.widget.*;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.lowdragmc.lowdraglib.utils.Position;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.*;
import appeng.api.storage.MEStorage;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.*;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * @author EasterFG on 2025/3/2
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class MEDualHatchStockPartMachine extends MEBusPartMachine implements IDataStickInteractable, IModifiableSyncOffset {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(MEDualHatchStockPartMachine.class,
            MEBusPartMachine.MANAGED_FIELD_HOLDER);

    protected static final int CONFIG_SIZE = 64;
    protected static final int AUTO_PULL_OFF = 0;
    protected static final int AUTO_PULL_ALL = 1;
    protected static final int AUTO_PULL_ITEM = 2;
    protected static final int AUTO_PULL_FLUID = 3;

    private static final IGuiTexture AUTO_PULL_ALL_ICON = new TextTexture("ALL", 0xFFAA00);
    private static final IGuiTexture AUTO_PULL_ITEM_ICON = new ItemStackTexture(Items.IRON_INGOT);
    private static final IGuiTexture AUTO_PULL_FLUID_ICON = new ItemStackTexture(Items.WATER_BUCKET);

    private static final boolean ENABLE_ULTIMATE_ME_STOCKING = ConfigHolder.INSTANCE.machines.enableUltimateMEStocking;

    public ExportOnlyAEItemList aeItemHandler;

    public ExportOnlyAEFluidList aeFluidHandler;

    @Persisted
    protected NotifiableFluidTank fluidTank;

    @DescSynced
    @Persisted
    @Setter
    protected int page = 1;

    @DescSynced
    @Persisted
    @Getter
    private int autoPullMode;

    public MEDualHatchStockPartMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, IO.IN, args);
        fluidTank = createTank();
    }

    @Override
    public void setWorkingEnabled(boolean workingEnabled) {
        super.setWorkingEnabled(workingEnabled);
        MachineUtil.notifyControllersRecipeLogic(this);
    }

    @Override
    protected NotifiableItemStackHandler createInventory(Object... args) {
        this.aeItemHandler = new ExportOnlyAEStockingItemList(this, CONFIG_SIZE);
        return this.aeItemHandler;
    }

    protected NotifiableFluidTank createTank() {
        this.aeFluidHandler = new ExportOnlyAEStockingFluidList(this, CONFIG_SIZE);
        return this.aeFluidHandler;
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        super.onMainNodeStateChanged(reason);
        if (getMainNode().isOnline()) markMEStockChanged();
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public void autoIO() {
        if (!this.isWorkingEnabled()) return;
        if (!shouldSyncME()) return;

        if (updateMEStatus()) {
            syncME();
            updateInventorySubscription();
        }

        if (autoPullMode != AUTO_PULL_OFF && getOffsetTimer() % (getOffset() == 0 ? 50 : getOffset()) == 0) {
            refreshList();
        }
    }

    private void refreshList() {
        AEKey[] previousConfig = createConfigSnapshot();
        IGrid grid = this.getMainNode().getGrid();
        if (grid == null) {
            aeItemHandler.clearInventory(0);
            aeFluidHandler.clearInventory(0);
            finishRefreshList(previousConfig);
            return;
        }
        IStorageService storageService = grid.getStorageService();
        MEStorage networkStorage = storageService.getInventory();
        final var inventory = this.aeItemHandler.getInventory();
        final var fluidInventory = this.aeFluidHandler.getInventory();

        var counter = storageService.getCachedInventory();
        int index = 0;
        for (Object2LongMap.Entry<AEKey> entry : counter) {
            if (index >= CONFIG_SIZE) break;
            AEKey what = entry.getKey();
            long amount = entry.getLongValue();
            if (amount <= 0) continue;
            boolean isItem = what instanceof AEItemKey;
            if (autoPullMode != AUTO_PULL_ALL) {
                if (autoPullMode == AUTO_PULL_ITEM && !isItem) {
                    continue;
                } else if (autoPullMode == AUTO_PULL_FLUID && isItem) {
                    continue;
                }
            }
            long request = networkStorage.extract(what, amount, Actionable.SIMULATE, actionSource);
            if (request == 0) continue;
            if (isItem) {
                ((IMESlot) fluidInventory[index]).setConfigWithoutNotify(null);
            } else {
                ((IMESlot) inventory[index]).setConfigWithoutNotify(null);
            }
            var itemSlot = inventory[index];
            var fluidSlot = fluidInventory[index];
            var slot = isItem ? itemSlot : fluidSlot;
            if (isItem) {
                ((IMESlot) fluidSlot).setConfigWithoutNotify(null);
                fluidSlot.setStock(null);
            } else {
                ((IMESlot) itemSlot).setConfigWithoutNotify(null);
                itemSlot.setStock(null);
            }
            ((IMESlot) slot).setConfigWithoutNotify(new GenericStack(what, 1));
            slot.setStock(new GenericStack(what, request));
            index++;
        }
        aeItemHandler.clearInventory(index);
        aeFluidHandler.clearInventory(index);

        finishRefreshList(previousConfig);
    }

    private AEKey[] createConfigSnapshot() {
        final var inventory = this.aeItemHandler.getInventory();
        final var fluidInventory = this.aeFluidHandler.getInventory();
        AEKey[] configuredKeys = new AEKey[inventory.length];
        for (int i = 0; i < inventory.length; i++) {
            GenericStack config = inventory[i].getConfig();
            if (config == null) {
                config = fluidInventory[i].getConfig();
            }
            configuredKeys[i] = config == null ? null : config.what();
        }
        return configuredKeys;
    }

    private void finishRefreshList(AEKey[] previousConfig) {
        if (!Arrays.equals(previousConfig, createConfigSnapshot())) {
            ((IOptimizedMEList) aeItemHandler).onConfigChanged();
            ((IOptimizedMEList) aeFluidHandler).onConfigChanged();
        }
        ((IOptimizedMEList) aeItemHandler).setChanged(true);
        ((IOptimizedMEList) aeFluidHandler).setChanged(true);
    }

    protected void syncME() {
        IGrid grid = this.getMainNode().getGrid();
        if (grid == null) {
            return;
        }
        var cachedInventory = grid.getStorageService().getCachedInventory();
        ExportOnlyAEItemSlot[] aeItem = aeItemHandler.getInventory();
        ExportOnlyAEFluidSlot[] aeFluid = aeFluidHandler.getInventory();
        ExportOnlyAESlot slot;
        boolean changed = false;
        for (int i = 0; i < aeItem.length; i++) {
            slot = aeItem[i];
            var config = slot.getConfig();
            if (config == null) {
                slot = aeFluid[i];
                config = slot.getConfig();
            }
            if (config != null) {
                var key = config.what();
                long extracted = cachedInventory.get(key);
                GenericStack nextStock = extracted > 0 ? new GenericStack(key, extracted) : null;
                if (!Objects.equals(slot.getStock(), nextStock)) {
                    slot.setStock(nextStock);
                    changed = true;
                }
                continue;
            }
            if (slot.getStock() != null) {
                slot.setStock(null);
                changed = true;
            }
        }
        if (changed) {
            markMEStockChanged();
        }
    }

    private void markMEStockChanged() {
        ((IOptimizedMEList) aeItemHandler).setChanged(true);
        ((IOptimizedMEList) aeFluidHandler).setChanged(true);
        aeItemHandler.notifyListeners();
        aeFluidHandler.notifyListeners();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().tell(new TickTask(1, () -> {
                ((IOptimizedMEList) this.aeItemHandler).onConfigChanged();
                ((IOptimizedMEList) this.aeFluidHandler).onConfigChanged();
            }));
        }
    }

    @Override
    public ModularUI createUI(Player entityPlayer) {
        return new ModularUI(176, 180, this, entityPlayer)
                .widget(new FancyMachineUIWidget(this, 176, 185));
    }

    @Override
    public Widget createUIWidget() {
        WidgetGroup group = new WidgetGroup(new Position(0, 0));
        // ME Network status
        group.addWidget(new LabelWidget(3, 0, () -> this.isOnline ?
                "gtceu.gui.me_network.online" :
                "gtceu.gui.me_network.offline"));

        // Config slots
        group.addWidget(new AEDualConfigWidget(3, 10, this.aeItemHandler, this.aeFluidHandler, this::setPage, page));

        return group;
    }

    @Override
    public void attachConfigurators(ConfiguratorPanel configuratorPanel) {
        super.attachConfigurators(configuratorPanel);
        configuratorPanel.attachConfigurators(new TurnsConfiguratorButton(
                this::getAutoPullMode,
                (clickData, mode) -> setAutoPullMode(mode),
                GuiTextures.BUTTON_AUTO_PULL.getSubTexture(0, 0, 1, 0.5),
                AUTO_PULL_ALL_ICON,
                AUTO_PULL_ITEM_ICON,
                AUTO_PULL_FLUID_ICON).setTooltipsSupplier(mode -> List.of(Component.translatable("gtlcore.machine.me_dual_hatch_stock.turns." + mode))));
        configuratorPanel.attachConfigurators(new AdvancedMEConfigurator(this::setOffset, this::getOffset));
    }

    protected void setAutoPullMode(int autoPullMode) {
        this.autoPullMode = autoPullMode;
        if (!isRemote()) {
            if (this.autoPullMode == 0) {
                this.aeItemHandler.clearInventory(0);
                this.aeFluidHandler.clearInventory(0);
            } else if (updateMEStatus()) {
                this.refreshList();
                updateInventorySubscription();
            }
        }
    }

    public void setTerminalAutoPullMode(int autoPullMode) {
        setAutoPullMode(Math.max(AUTO_PULL_OFF, Math.min(AUTO_PULL_FLUID, autoPullMode)));
    }

    protected CompoundTag writeConfigToTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("AutoPullMode", autoPullMode);
        CompoundTag configStacks = new CompoundTag();
        if (autoPullMode == 0) {
            tag.put("ConfigStacks", configStacks);
            for (int i = 0; i < CONFIG_SIZE; i++) {
                var slot = this.aeItemHandler.getInventory()[i];
                GenericStack config = slot.getConfig();
                if (config == null) {
                    config = this.aeFluidHandler.getInventory()[i].getConfig();
                    if (config == null) {
                        continue;
                    }
                }
                CompoundTag stackTag = GenericStack.writeTag(config);
                configStacks.put(Integer.toString(i), stackTag);
            }
        }
        tag.putByte("GhostCircuit",
                (byte) IntCircuitBehaviour.getCircuitConfiguration(circuitInventory.getStackInSlot(0)));
        tag.putInt("SyncOffset", getOffset());
        return tag;
    }

    protected void readConfigFromTag(CompoundTag tag) {
        if (tag.contains("AutoPullMode")) {
            var autoPullMode = tag.getInt("AutoPullMode");
            this.setAutoPullMode(autoPullMode);
        }

        if (tag.contains("ConfigStacks")) {
            final var inventory = this.aeItemHandler.getInventory();
            final var fluidInventory = this.aeFluidHandler.getInventory();

            CompoundTag configStacks = tag.getCompound("ConfigStacks");
            for (int i = 0; i < CONFIG_SIZE; i++) {
                String key = Integer.toString(i);
                if (configStacks.contains(key)) {
                    CompoundTag configTag = configStacks.getCompound(key);
                    var stack = GenericStack.readTag(configTag);
                    if (stack != null) {
                        if (stack.what() instanceof AEItemKey) {
                            ((IMESlot) inventory[i]).setConfigWithoutNotify(stack);
                        } else {
                            ((IMESlot) fluidInventory[i]).setConfigWithoutNotify(stack);
                        }
                        continue;
                    }
                }
                ((IMESlot) inventory[i]).setConfigWithoutNotify(null);
                ((IMESlot) fluidInventory[i]).setConfigWithoutNotify(null);
            }

            ((IOptimizedMEList) aeItemHandler).onConfigChanged();
            ((IOptimizedMEList) aeFluidHandler).onConfigChanged();
        }

        if (tag.contains("GhostCircuit")) {
            circuitInventory.setStackInSlot(0, IntCircuitBehaviour.stack(tag.getByte("GhostCircuit")));
        }

        if (tag.contains("SyncOffset")) {
            this.setOffset(tag.getInt("SyncOffset"));
        }
    }

    @Override
    public InteractionResult onDataStickRightClick(Player player, ItemStack dataStick) {
        CompoundTag tag = dataStick.getTag();
        if (tag == null || !tag.contains("MEDualHatchStock")) {
            return InteractionResult.PASS;
        }

        if (!isRemote()) {
            readConfigFromTag(tag.getCompound("MEDualHatchStock"));
            this.updateInventorySubscription();
            player.sendSystemMessage(Component.translatable("gtceu.machine.me.import_paste_settings"));
        }
        return InteractionResult.sidedSuccess(isRemote());
    }

    @Override
    public boolean onDataStickLeftClick(Player player, ItemStack dataStick) {
        if (!isRemote()) {
            CompoundTag tag = new CompoundTag();
            tag.put("MEDualHatchStock", writeConfigToTag());
            dataStick.setTag(tag);
            dataStick.setHoverName(Component.translatable("gtceu.machine.me.me_dual_hatch_stock.data_stick.name"));
            player.sendSystemMessage(Component.translatable("gtceu.machine.me.import_copy_settings"));
        }
        return true;
    }

    private class ExportOnlyAEStockingItemList extends ExportOnlyAEItemList implements IOptimizedMEList {

        protected ObjectArrayList<AEItemKey> configList = new ObjectArrayList<>();

        protected IntArrayList configIndexList = new IntArrayList();

        private long lastSnapshotTick = Long.MIN_VALUE;

        public ExportOnlyAEStockingItemList(MetaMachine holder, int slots) {
            super(holder, slots, ExportOnlyAEStockingItemSlot::new);
            for (ExportOnlyAEItemSlot exportOnlyAEItemSlot : inventory) {
                ((IMESlot) exportOnlyAEItemSlot).setOnConfigChanged(this::onConfigChanged);
            }
        }

        @Override
        public void clearInventory(int startIndex) {
            for (int i = startIndex; i < this.getConfigurableSlots(); ++i) {
                IConfigurableSlot slot = this.getConfigurableSlot(i);
                ((IMESlot) slot).setConfigWithoutNotify(null);
                slot.setStock(null);
            }
        }

        @Override
        public void onConfigChanged() {
            lastSnapshotTick = Long.MIN_VALUE;
            configList.clear();
            configIndexList.clear();
            for (int i = 0, inventoryLength = inventory.length; i < inventoryLength; i++) {
                final var config = inventory[i].getConfig();
                if (config != null && config.what() instanceof AEItemKey key) {
                    configList.add(key);
                    configIndexList.add(i);
                }
            }
        }

        @Override
        public boolean isAutoPull() {
            return autoPullMode > 0;
        }

        @Override
        public boolean isStocking() {
            return true;
        }

        @Override
        public List<Ingredient> handleRecipeInner(IO io, GTRecipe recipe, List<Ingredient> left, @Nullable String slotName, boolean simulate) {
            if (io != IO.IN || left.isEmpty()) {
                return left;
            }
            if (!MEDualHatchStockPartMachine.this.isWorkingEnabled()) {
                return left;
            }
            IGrid grid = getMainNode().getGrid();
            if (grid == null) {
                return left;
            }

            MEStorage aeNetwork = grid.getStorageService().getInventory();
            boolean changed = false;
            var listIterator = left.listIterator();

            while (listIterator.hasNext()) {
                Ingredient ingredient = listIterator.next();
                if (ingredient.isEmpty()) {
                    listIterator.remove();
                } else {
                    long amount;
                    if (ingredient instanceof LongIngredient li) amount = li.getActualAmount();
                    else if (ingredient instanceof SizedIngredient si) amount = si.getAmount();
                    else amount = 1;
                    if (amount < 1) listIterator.remove();
                    else {
                        for (int i = 0, configListSize = configList.size(); i < configListSize; i++) {
                            AEItemKey aeItemKey = configList.get(i);
                            if (aeItemKey.matches(ingredient)) {
                                long extracted = aeNetwork.extract(aeItemKey, amount, simulate ? Actionable.SIMULATE : Actionable.MODULATE, getActionSource());
                                if (extracted > 0) {
                                    changed = true;
                                    amount -= extracted;
                                    if (!simulate) {
                                        var slot = this.inventory[configIndexList.getInt(i)];
                                        if (slot.getStock() != null) {
                                            long amt = slot.getStock().amount() - extracted;
                                            if (amt == 0) slot.setStock(null);
                                            else slot.setStock(new GenericStack(aeItemKey, amt));
                                        }
                                    }
                                }
                            }
                            if (amount <= 0L) {
                                listIterator.remove();
                                break;
                            }
                        }
                    }
                }
            }
            if (!simulate && changed) {
                setChanged(true);
                this.onContentsChanged();
            }
            return left.isEmpty() ? null : left;
        }

        @Override
        public @Nullable Object2LongMap<ItemStack> getMEItemMap() {
            long currentTick = getOffsetTimer();
            if (getChanged() || ENABLE_ULTIMATE_ME_STOCKING && lastSnapshotTick != currentTick) {
                setChanged(false);
                lastSnapshotTick = currentTick;
                final var itemMap = getItemMap();
                itemMap.clear();
                IGrid grid = getMainNode().getGrid();
                if (grid != null) {
                    final MEStorage aeNetwork = grid.getStorageService().getInventory();
                    for (var key : configList) {
                        long extracted = aeNetwork.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, getActionSource());
                        if (extracted > 0) {
                            itemMap.addTo(key.toStack(), extracted);
                        }
                    }
                }
            }
            return getItemMap().isEmpty() ? null : getItemMap();
        }
    }

    private class ExportOnlyAEStockingItemSlot extends ExportOnlyAEConfigureItemSlot {

        public ExportOnlyAEStockingItemSlot() {
            super();
        }

        public ExportOnlyAEStockingItemSlot(@Nullable GenericStack config, @Nullable GenericStack stock) {
            super(config, stock);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate, boolean notifyChanges) {
            if (slot == 0 && this.stock != null) {
                if (this.config != null) {
                    // Extract the items from the real net to either validate (simulate)
                    // or extract (modulate) when this is called
                    if (!isOnline() || !MEDualHatchStockPartMachine.this.isWorkingEnabled()) return ItemStack.EMPTY;
                    IGrid grid = getMainNode().getGrid();
                    if (grid == null) return ItemStack.EMPTY;
                    MEStorage aeNetwork = grid.getStorageService().getInventory();
                    Actionable action = simulate ? Actionable.SIMULATE : Actionable.MODULATE;
                    var key = config.what();
                    long extracted = aeNetwork.extract(key, amount, action, actionSource);
                    if (extracted > 0) {
                        ItemStack resultStack = key instanceof AEItemKey itemKey ? itemKey.toStack((int) extracted) : ItemStack.EMPTY;
                        if (!simulate) {
                            // may as well update the display here
                            this.stock = ExportOnlyAESlot.copy(stock, stock.amount() - extracted);
                            if (this.stock.amount() == 0) {
                                this.stock = null;
                            }
                            if (notifyChanges && this.onContentsChanged != null) {
                                this.onContentsChanged.run();
                            }
                        }
                        return resultStack;
                    }
                }
            }
            return ItemStack.EMPTY;
        }

        @Override
        public ExportOnlyAEStockingItemSlot copy() {
            return new ExportOnlyAEStockingItemSlot(this.config == null ? null : copy(this.config), this.stock == null ? null : copy(this.stock));
        }
    }

    private class ExportOnlyAEStockingFluidList extends ExportOnlyAEFluidList implements IOptimizedMEList {

        protected ObjectArrayList<AEFluidKey> configList = new ObjectArrayList<>();

        protected IntArrayList configIndexList = new IntArrayList();

        private long lastSnapshotTick = Long.MIN_VALUE;

        public ExportOnlyAEStockingFluidList(MetaMachine holder, int slots) {
            super(holder, slots, ExportOnlyAEStockingFluidSlot::new);
            for (ExportOnlyAEFluidSlot exportOnlyAEFluidSlot : inventory) {
                ((IMESlot) exportOnlyAEFluidSlot).setOnConfigChanged(this::onConfigChanged);
            }
        }

        @Override
        public void clearInventory(int startIndex) {
            for (int i = startIndex; i < this.getConfigurableSlots(); ++i) {
                IConfigurableSlot slot = this.getConfigurableSlot(i);
                ((IMESlot) slot).setConfigWithoutNotify(null);
                slot.setStock(null);
            }
        }

        @Override
        public void onConfigChanged() {
            lastSnapshotTick = Long.MIN_VALUE;
            configList.clear();
            configIndexList.clear();
            for (int i = 0, inventoryLength = inventory.length; i < inventoryLength; i++) {
                final var config = inventory[i].getConfig();
                if (config != null && config.what() instanceof AEFluidKey key) {
                    configList.add(key);
                    configIndexList.add(i);
                }
            }
        }

        @Override
        public boolean isAutoPull() {
            return autoPullMode > 0;
        }

        @Override
        public boolean isStocking() {
            return true;
        }

        @Override
        public List<FluidIngredient> handleRecipeInner(IO io, GTRecipe recipe, List<FluidIngredient> left, @Nullable String slotName, boolean simulate) {
            if (io != IO.IN || left.isEmpty()) {
                return left;
            }
            if (!MEDualHatchStockPartMachine.this.isWorkingEnabled()) {
                return left;
            }
            IGrid grid = getMainNode().getGrid();
            if (grid == null) {
                return left;
            }

            MEStorage aeNetwork = grid.getStorageService().getInventory();
            boolean changed = false;
            var listIterator = left.listIterator();

            while (listIterator.hasNext()) {
                FluidIngredient ingredient = listIterator.next();
                if (ingredient.isEmpty()) {
                    listIterator.remove();
                } else {
                    long amount = ingredient.getAmount();
                    if (amount < 1) listIterator.remove();
                    else {
                        for (int i = 0, configListSize = configList.size(); i < configListSize; i++) {
                            AEFluidKey aeFluidKey = configList.get(i);
                            if (AEUtils.testFluidIngredient(ingredient, aeFluidKey)) {
                                long extracted = aeNetwork.extract(aeFluidKey, amount, simulate ? Actionable.SIMULATE : Actionable.MODULATE, getActionSource());
                                if (extracted > 0) {
                                    changed = true;
                                    amount -= extracted;
                                    if (!simulate) {
                                        var slot = this.inventory[configIndexList.getInt(i)];
                                        if (slot.getStock() != null) {
                                            long amt = slot.getStock().amount() - extracted;
                                            if (amt == 0) slot.setStock(null);
                                            else slot.setStock(new GenericStack(aeFluidKey, amt));
                                        }
                                    }
                                }
                            }
                            if (amount <= 0L) {
                                listIterator.remove();
                                break;
                            }
                        }
                    }
                }
            }
            if (!simulate && changed) {
                setChanged(true);
                this.onContentsChanged();
            }
            return left.isEmpty() ? null : left;
        }

        @Override
        public List<FluidStack> getMEFluidList() {
            long currentTick = getOffsetTimer();
            if (getChanged() || ENABLE_ULTIMATE_ME_STOCKING && lastSnapshotTick != currentTick) {
                setChanged(false);
                lastSnapshotTick = currentTick;
                final var fluidList = getFluidList();
                fluidList.clear();
                IGrid grid = getMainNode().getGrid();
                if (grid != null) {
                    final MEStorage aeNetwork = grid.getStorageService().getInventory();
                    for (var key : configList) {
                        long extracted = aeNetwork.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, getActionSource());
                        if (extracted > 0) {
                            fluidList.add(FluidStack.create(key.getFluid(), extracted));
                        }
                    }
                }
            }
            return getFluidList();
        }
    }

    private class ExportOnlyAEStockingFluidSlot extends ExportOnlyAEConfigureFluidSlot {

        public ExportOnlyAEStockingFluidSlot() {
            super();
        }

        public ExportOnlyAEStockingFluidSlot(@Nullable GenericStack config, @Nullable GenericStack stock) {
            super(config, stock);
        }

        @Override
        public ExportOnlyAEFluidSlot copy() {
            return new ExportOnlyAEStockingFluidSlot(this.config == null ? null : copy(this.config), this.stock == null ? null : copy(this.stock));
        }

        @Override
        public FluidStack drain(long maxDrain, boolean simulate, boolean notifyChanges) {
            if (this.stock != null && this.config != null) {
                // Extract the items from the real net to either validate (simulate)
                // or extract (modulate) when this is called
                if (!isOnline() || !MEDualHatchStockPartMachine.this.isWorkingEnabled()) return FluidStack.empty();
                IGrid grid = getMainNode().getGrid();
                if (grid == null) return FluidStack.empty();
                MEStorage aeNetwork = grid.getStorageService().getInventory();
                Actionable action = simulate ? Actionable.SIMULATE : Actionable.MODULATE;
                var key = config.what();
                long extracted = aeNetwork.extract(key, maxDrain, action, actionSource);
                if (extracted > 0) {
                    FluidStack resultStack = key instanceof AEFluidKey fluidKey ? AEUtil.toFluidStack(fluidKey, extracted) : FluidStack.empty();
                    if (!simulate) {
                        // may as well update the display here
                        this.stock = ExportOnlyAESlot.copy(stock, stock.amount() - extracted);
                        if (this.stock.amount() == 0) {
                            this.stock = null;
                        }
                        if (notifyChanges && this.onContentsChanged != null) {
                            this.onContentsChanged.run();
                        }
                    }
                    return resultStack;
                }
            }
            return FluidStack.empty();
        }
    }
}
