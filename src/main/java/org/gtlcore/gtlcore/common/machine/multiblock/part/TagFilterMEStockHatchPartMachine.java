package org.gtlcore.gtlcore.common.machine.multiblock.part;

import org.gtlcore.gtlcore.api.gui.TagFilterConfigurator;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IModifiableSyncOffset;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.ExportOnlyAEConfigureFluidSlot;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.IMESlot;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.IOptimizedMEList;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.gtlcore.gtlcore.utils.MachineUtil;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.fancy.*;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.gregtechceu.gtceu.integration.ae2.machine.MEInputHatchPartMachine;
import com.gregtechceu.gtceu.integration.ae2.slot.*;
import com.gregtechceu.gtceu.integration.ae2.utils.AEUtil;

import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.*;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;

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
public class TagFilterMEStockHatchPartMachine extends MEInputHatchPartMachine implements IModifiableSyncOffset {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(TagFilterMEStockHatchPartMachine.class,
            MEInputHatchPartMachine.MANAGED_FIELD_HOLDER);

    private static final boolean ENABLE_ULTIMATE_ME_STOCKING = ConfigHolder.INSTANCE.machines.enableUltimateMEStocking;

    @Persisted
    protected String tagWhite = "";

    @Persisted
    protected String tagBlack = "";

    @Getter
    @Setter
    @Persisted
    private boolean isCountSort = false;

    public TagFilterMEStockHatchPartMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Override
    public void setWorkingEnabled(boolean workingEnabled) {
        super.setWorkingEnabled(workingEnabled);
        MachineUtil.notifyControllersRecipeLogic(this);
    }

    @Override
    protected NotifiableFluidTank createTank(long tankSize, int slotCount, Object... args) {
        this.aeFluidHandler = new ExportOnlyAEStockingFluidList(this, CONFIG_SIZE);
        return this.aeFluidHandler;
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
        for (ExportOnlyAEFluidSlot slot : this.aeFluidHandler.getInventory()) {
            var config = slot.getConfig();
            if (config != null) {
                var key = config.what();
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
            aeFluidHandler.clearInventory(0);
            return;
        }
        IStorageService storageService = grid.getStorageService();
        MEStorage networkStorage = storageService.getInventory();
        IPartitionList filter = new TagPriorityList(this.tagWhite, this.tagBlack);

        List<GenericStack> order = new ObjectArrayList<>();
        final var inventory = this.aeFluidHandler.getInventory();

        var counter = storageService.getCachedInventory();
        int index = 0;
        for (Object2LongMap.Entry<AEKey> entry : counter) {
            if (!isCountSort && index >= CONFIG_SIZE) break;
            AEKey what = entry.getKey();
            long amount = entry.getLongValue();
            if (amount <= 0) continue;
            if (!(what instanceof AEFluidKey fluidKey)) continue;
            if (!filter.isListed(fluidKey)) {
                continue;
            }
            if (isCountSort) {
                order.add(new GenericStack(fluidKey, amount));
            } else {
                long request = networkStorage.extract(what, amount, Actionable.SIMULATE, actionSource);
                if (request == 0) continue;
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
                var slot = inventory[index];
                ((IMESlot) slot).setConfigWithoutNotify(new GenericStack(stack.what(), 1));
                slot.setStock(new GenericStack(stack.what(), request));
                index++;
            }
        }
        aeFluidHandler.clearInventory(index);

        ((IOptimizedMEList) aeFluidHandler).onConfigChanged();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().tell(new TickTask(1, () -> ((IOptimizedMEList) this.aeFluidHandler).onConfigChanged()));
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
        public boolean isStocking() {
            return true;
        }

        @Override
        public boolean isAutoPull() {
            return true;
        }

        @Override
        public List<FluidIngredient> handleRecipeInner(IO io, GTRecipe recipe, List<FluidIngredient> left, @Nullable String slotName, boolean simulate) {
            if (io != IO.IN || left.isEmpty()) {
                return left;
            }
            if (!TagFilterMEStockHatchPartMachine.this.isWorkingEnabled()) {
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
                                long extracted = aeNetwork.extract(aeFluidKey, amount, simulate ? Actionable.SIMULATE : Actionable.MODULATE, TagFilterMEStockHatchPartMachine.this.actionSource);
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
                final MEStorage aeNetwork = Objects.requireNonNull(getMainNode().getGrid()).getStorageService().getInventory();
                for (var key : configList) {
                    long extracted = aeNetwork.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, TagFilterMEStockHatchPartMachine.this.actionSource);
                    if (extracted > 0) {
                        fluidList.add(FluidStack.create(key.getFluid(), extracted));
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
        public FluidStack drain(long maxDrain, boolean simulate, boolean notifyChanges) {
            if (this.stock != null && this.config != null) {
                if (!isOnline() || !TagFilterMEStockHatchPartMachine.this.isWorkingEnabled()) return FluidStack.empty();
                IGrid grid = getMainNode().getGrid();
                if (grid == null) return FluidStack.empty();
                MEStorage aeNetwork = grid.getStorageService().getInventory();
                Actionable action = simulate ? Actionable.SIMULATE : Actionable.MODULATE;
                var key = config.what();
                long extracted = aeNetwork.extract(key, maxDrain, action, actionSource);
                if (extracted > 0) {
                    FluidStack resultStack = key instanceof AEFluidKey fluidKey ? AEUtil.toFluidStack(fluidKey, extracted) : FluidStack.empty();
                    if (!simulate) {
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

        @Override
        public ExportOnlyAEStockingFluidSlot copy() {
            return new ExportOnlyAEStockingFluidSlot(this.config == null ? null : copy(this.config), this.stock == null ? null : copy(this.stock));
        }
    }
}
