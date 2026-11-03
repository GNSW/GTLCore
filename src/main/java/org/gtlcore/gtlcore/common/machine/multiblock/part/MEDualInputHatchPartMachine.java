package org.gtlcore.gtlcore.common.machine.multiblock.part;

import org.gtlcore.gtlcore.api.machine.trait.MEPart.IModifiableSyncOffset;
import org.gtlcore.gtlcore.client.gui.widget.AEDualConfigWidget;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.gregtechceu.gtceu.integration.ae2.machine.MEInputBusPartMachine;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidList;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidSlot;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemList;

import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.lowdragmc.lowdraglib.utils.Position;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNodeListener;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;

public class MEDualInputHatchPartMachine extends MEInputBusPartMachine implements IModifiableSyncOffset {

    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            MEDualInputHatchPartMachine.class, MEInputBusPartMachine.MANAGED_FIELD_HOLDER);
    private static final int CONFIG_SIZE = 64;
    private static final String CONFIG_STACKS_TAG = "ConfigStacks";
    private static final String GHOST_CIRCUIT_TAG = "GhostCircuit";
    private static final String SYNC_OFFSET_TAG = "SyncOffset";

    public ExportOnlyAEFluidList aeFluidHandler;

    @Persisted
    protected NotifiableFluidTank fluidTank;

    @DescSynced
    @Persisted
    @Setter
    protected int page = 1;

    public MEDualInputHatchPartMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        this.fluidTank = createTank();
    }

    @Override
    protected NotifiableItemStackHandler createInventory(Object... args) {
        this.aeItemHandler = new ExportOnlyAEItemList(this, CONFIG_SIZE);
        return this.aeItemHandler;
    }

    protected NotifiableFluidTank createTank() {
        this.aeFluidHandler = new ExportOnlyAEFluidList(this, CONFIG_SIZE);
        return this.aeFluidHandler;
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.@NotNull State reason) {
        super.onMainNodeStateChanged(reason);
        if (getMainNode().isOnline()) {
            this.aeFluidHandler.notifyListeners();
        }
    }

    @Override
    protected void syncME() {
        IGrid grid = getMainNode().getGrid();
        if (grid == null) {
            return;
        }

        super.syncME();
        MEStorage networkInventory = grid.getStorageService().getInventory();
        for (ExportOnlyAEFluidSlot slot : this.aeFluidHandler.getInventory()) {
            GenericStack excess = slot.exceedStack();
            if (excess != null) {
                long excessAmount = excess.amount();
                long inserted = networkInventory.insert(
                        excess.what(), excessAmount, Actionable.MODULATE, this.actionSource);
                slot.drain(inserted > 0 ? inserted : excessAmount, false);
                if (inserted > 0) {
                    continue;
                }
            }

            GenericStack requested = slot.requestStack();
            if (requested != null) {
                long extracted = networkInventory.extract(
                        requested.what(), requested.amount(), Actionable.MODULATE, this.actionSource);
                if (extracted > 0) {
                    slot.addStack(new GenericStack(requested.what(), extracted));
                }
            }
        }
    }

    @Override
    protected void flushInventory() {
        super.flushInventory();
        IGrid grid = getMainNode().getGrid();
        if (grid == null) {
            return;
        }

        MEStorage networkInventory = grid.getStorageService().getInventory();
        for (ExportOnlyAEFluidSlot slot : this.aeFluidHandler.getInventory()) {
            GenericStack stock = slot.getStock();
            if (stock != null) {
                networkInventory.insert(stock.what(), stock.amount(), Actionable.MODULATE, this.actionSource);
            }
        }
    }

    @Override
    public ModularUI createUI(Player player) {
        return new ModularUI(176, 180, this, player)
                .widget(new com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget(this, 176, 185));
    }

    @Override
    public Widget createUIWidget() {
        WidgetGroup group = new WidgetGroup(new Position(0, 0));
        group.addWidget(new LabelWidget(3, 0, () -> this.isOnline ?
                "gtceu.gui.me_network.online" :
                "gtceu.gui.me_network.offline"));
        group.addWidget(new AEDualConfigWidget(
                3, 10, this.aeItemHandler, this.aeFluidHandler, this::setPage, this.page, true));
        return group;
    }

    @Override
    protected CompoundTag writeConfigToTag() {
        CompoundTag tag = new CompoundTag();
        CompoundTag configStacks = new CompoundTag();
        tag.put(CONFIG_STACKS_TAG, configStacks);

        for (int i = 0; i < CONFIG_SIZE; i++) {
            GenericStack config = this.aeItemHandler.getInventory()[i].getConfig();
            if (config == null) {
                config = this.aeFluidHandler.getInventory()[i].getConfig();
            }
            if (config != null) {
                configStacks.put(Integer.toString(i), GenericStack.writeTag(config));
            }
        }

        tag.putByte(GHOST_CIRCUIT_TAG,
                (byte) IntCircuitBehaviour.getCircuitConfiguration(this.circuitInventory.getStackInSlot(0)));
        tag.putInt(SYNC_OFFSET_TAG, getOffset());
        return tag;
    }

    @Override
    protected void readConfigFromTag(CompoundTag tag) {
        if (tag.contains(CONFIG_STACKS_TAG)) {
            CompoundTag configStacks = tag.getCompound(CONFIG_STACKS_TAG);
            for (int i = 0; i < CONFIG_SIZE; i++) {
                GenericStack config = configStacks.contains(Integer.toString(i)) ?
                        GenericStack.readTag(configStacks.getCompound(Integer.toString(i))) :
                        null;
                if (config != null && config.what() instanceof AEItemKey) {
                    this.aeItemHandler.getInventory()[i].setConfig(config);
                    this.aeFluidHandler.getInventory()[i].setConfig(null);
                } else if (config != null && config.what() instanceof AEFluidKey) {
                    this.aeItemHandler.getInventory()[i].setConfig(null);
                    this.aeFluidHandler.getInventory()[i].setConfig(config);
                } else {
                    this.aeItemHandler.getInventory()[i].setConfig(null);
                    this.aeFluidHandler.getInventory()[i].setConfig(null);
                }
            }
        }

        if (tag.contains(GHOST_CIRCUIT_TAG)) {
            this.circuitInventory.setStackInSlot(
                    0, IntCircuitBehaviour.stack(tag.getByte(GHOST_CIRCUIT_TAG)));
        }
        if (tag.contains(SYNC_OFFSET_TAG)) {
            setOffset(tag.getInt(SYNC_OFFSET_TAG));
        }
    }
}
