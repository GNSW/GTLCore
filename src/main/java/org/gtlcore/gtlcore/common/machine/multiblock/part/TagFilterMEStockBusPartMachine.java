package org.gtlcore.gtlcore.common.machine.multiblock.part;

import org.gtlcore.gtlcore.api.gui.TagFilterConfigurator;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IModifiableSyncOffset;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.ExportOnlyAEConfigureItemSlot;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.IMESlot;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.IOptimizedMEList;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.utils.MachineUtil;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.fancy.*;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.gregtechceu.gtceu.integration.ae2.machine.MEInputBusPartMachine;
import com.gregtechceu.gtceu.integration.ae2.slot.*;

import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.*;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.*;
import appeng.api.storage.MEStorage;
import appeng.util.prioritylist.IPartitionList;
import com.glodblock.github.extendedae.common.me.taglist.TagPriorityList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.*;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * @author EasterFG on 2025/2/8
 */
@Setter
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class TagFilterMEStockBusPartMachine extends MEInputBusPartMachine implements IModifiableSyncOffset {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(TagFilterMEStockBusPartMachine.class,
            MEInputBusPartMachine.MANAGED_FIELD_HOLDER);

    private static final boolean ENABLE_ULTIMATE_ME_STOCKING = ConfigHolder.INSTANCE.machines.enableUltimateMEStocking;

    @Persisted
    protected String tagWhite = "";

    @Persisted
    protected String tagBlack = "";

    @Getter
    @Setter
    @Persisted
    private boolean isCountSort = false;

    public TagFilterMEStockBusPartMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
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

    @Override
    public void attachConfigurators(ConfiguratorPanel configuratorPanel) {
        super.attachConfigurators(configuratorPanel);
        configuratorPanel.attachConfigurators(new IFancyConfiguratorButton.Toggle(
                new TextTexture("A-Z"),
                new TextTexture("gui.gtlcore.sort_by_amount"),
                this::isCountSort,
                (clickData, pressed) -> setCountSort(pressed))
                .setTooltipsSupplier(pressed -> List.of(Component.translatable("tooltip.gtlcore.auto_pull_sort_mode"))));
        configuratorPanel.attachConfigurators(createTagFilterConfigurator());
    }

    @Override
    public void autoIO() {
        super.autoIO();
        if (getOffsetTimer() % 50 == 0) {
            refreshList();
            syncME();
        }
    }

    @Override
    protected void syncME() {
        IGrid grid = this.getMainNode().getGrid();
        if (grid == null) {
            return;
        }
        MEStorage networkInv = grid.getStorageService().getInventory();
        for (ExportOnlyAEItemSlot slot : this.aeItemHandler.getInventory()) {
            var config = slot.getConfig();
            if (config != null) {
                // Try to fill the slot
                var key = config.what();
                // try max fill Long.MAX_VALUE
                long extracted = networkInv.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, actionSource);
                if (extracted > 0) {
                    slot.setStock(new GenericStack(key, extracted));
                    continue;
                }
            }
            slot.setStock(null);
        }
    }

    private void refreshList() {
        IGrid grid = this.getMainNode().getGrid();
        if (grid == null) {
            aeItemHandler.clearInventory(0);
            return;
        }
        IStorageService storageService = grid.getStorageService();
        MEStorage networkStorage = storageService.getInventory();
        IPartitionList filter = new TagPriorityList(this.tagWhite, this.tagBlack);

        List<GenericStack> order = new ObjectArrayList<>();
        final var inventory = this.aeItemHandler.getInventory();

        var counter = storageService.getCachedInventory();
        int index = 0;
        for (Object2LongMap.Entry<AEKey> entry : counter) {
            if (!isCountSort && index >= CONFIG_SIZE) break;
            AEKey what = entry.getKey();
            long amount = entry.getLongValue();
            if (amount <= 0) continue;
            if (!(what instanceof AEItemKey itemKey)) continue;
            if (!filter.isListed(itemKey)) {
                continue;
            }
            if (isCountSort) {
                order.add(new GenericStack(itemKey, amount));
            } else {
                long request = networkStorage.extract(what, amount, Actionable.SIMULATE, actionSource);
                if (request == 0) continue;
                // Ensure that it is valid to configure with this stack
                var slot = inventory[index];
                ((IMESlot) slot).setConfigWithoutNotify(new GenericStack(what, 1));
                slot.setStock(new GenericStack(what, request));
                index++;
            }
        }
        if (isCountSort) {
            order.sort((o1, o2) -> Long.compare(o2.amount(), o1.amount()));
            int len = Math.min(order.size(), CONFIG_SIZE);
            for (int i = 0; i < len; i++) {
                GenericStack stack = order.get(i);
                long request = networkStorage.extract(stack.what(), stack.amount(), Actionable.SIMULATE, actionSource);
                if (request == 0) continue;
                // Ensure that it is valid to configure with this stack
                var slot = inventory[index];
                ((IMESlot) slot).setConfigWithoutNotify(new GenericStack(stack.what(), 1));
                slot.setStock(new GenericStack(stack.what(), request));
                index++;
            }
        }
        aeItemHandler.clearInventory(index);

        ((IOptimizedMEList) aeItemHandler).onConfigChanged();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().tell(new TickTask(1, () -> ((IOptimizedMEList) this.aeItemHandler).onConfigChanged()));
        }
    }

    @Override
    protected CompoundTag writeConfigToTag() {
        CompoundTag tag = new CompoundTag();
        tag.putByte("GhostCircuit",
                (byte) IntCircuitBehaviour.getCircuitConfiguration(circuitInventory.getStackInSlot(0)));
        tag.putString("TagWhite", tagWhite);
        tag.putString("TagBlack", tagBlack);
        tag.putInt("SyncOffset", getOffset());
        return tag;
    }

    @Override
    protected void readConfigFromTag(CompoundTag tag) {
        if (tag.contains("GhostCircuit")) {
            circuitInventory.setStackInSlot(0, IntCircuitBehaviour.stack(tag.getByte("GhostCircuit")));
        }

        if (tag.contains("TagWhite")) {
            tagWhite = tag.getString("TagWhite");
        }

        if (tag.contains("TagBlack")) {
            tagBlack = tag.getString("TagBlack");
        }

        if (tag.contains("SyncOffset")) {
            this.setOffset(tag.getInt("SyncOffset"));
        }
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    public String getTagWhite() {
        return tagWhite;
    }

    public String getTagBlack() {
        return tagBlack;
    }

    public IFancyConfigurator createTagFilterConfigurator() {
        return new TagFilterConfigurator(
                this::getTagWhite, value -> tagWhite = value,
                this::getTagBlack, value -> tagBlack = value);
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
        public boolean isStocking() {
            return true;
        }

        @Override
        public boolean isAutoPull() {
            // only read from the network, cant config this slot
            return true;
        }

        @Override
        public @Nullable List<Ingredient> handleRecipeInner(IO io, GTRecipe recipe, List<Ingredient> left, @Nullable String slotName, boolean simulate) {
            if (io != IO.IN || left.isEmpty()) {
                return left;
            }
            if (!TagFilterMEStockBusPartMachine.this.isWorkingEnabled()) {
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
                final MEStorage aeNetwork = Objects.requireNonNull(getMainNode().getGrid()).getStorageService().getInventory();
                for (var key : configList) {
                    long extracted = aeNetwork.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, getActionSource());
                    if (extracted > 0) {
                        itemMap.addTo(key.toStack(), extracted);
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
                    if (!isOnline() || !TagFilterMEStockBusPartMachine.this.isWorkingEnabled()) return ItemStack.EMPTY;
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
}
