package org.gtlcore.gtlcore.common.machine.multiblock.part.ae;

import org.gtlcore.gtlcore.api.machine.MEExtendedOutputFancyConfigurator;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEFilterIOTrait;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.gtlcore.gtlcore.utils.NumberUtils;

import com.gregtechceu.gtceu.api.gui.fancy.TabsWidget;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.recipe.ingredient.*;

import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.syncdata.annotation.LazyManaged;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.*;
import appeng.api.stacks.*;
import org.jetbrains.annotations.NotNull;

import java.util.Iterator;
import java.util.List;

import static org.gtlcore.gtlcore.integration.ae2.AEUtils.reFunds;

public class MEExtendedOutputPartMachine extends MEExtendedOutputPartMachineBase {

    @Override
    public @NotNull ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            MEExtendedOutputPartMachine.class, MEExtendedOutputPartMachineBase.MANAGED_FIELD_HOLDER);

    protected final static int FILTER_ROW = 3;
    protected final static int FILTER_COL = 9;

    @Persisted
    @LazyManaged
    protected final MEOutputFilterHandler filterHandler;

    public MEExtendedOutputPartMachine(IMachineBlockEntity holder) {
        super(holder);
        filterHandler = new MEOutputFilterHandler(FILTER_ROW, FILTER_COL, this::onFilterChanged, this::updatePriority, this::getPriority, this::setPriority);
    }

    private void onFilterChanged() {
        markDirty();
        notifyHandlers();
    }

    public MEOutputFilterHandler getFilterHandler() {
        return filterHandler;
    }

    // ========================================
    // GUI SYSTEM
    // ========================================

    @Override
    public ModularUI createUI(Player entityPlayer) {
        final var ui = super.createUI(entityPlayer);
        ui.registerCloseListener(filterHandler::onUIClosed);
        return ui;
    }

    @Override
    public void attachSideTabs(TabsWidget sideTabs) {
        super.attachSideTabs(sideTabs);
        sideTabs.attachSubTab(new MEExtendedOutputFancyConfigurator(filterHandler::createMainWidgetGroup));
    }

    // ========================================
    // DataStick Copy
    // ========================================

    public CompoundTag writeConfigToTag() {
        var tag = super.writeConfigToTag();
        tag.put("meOutputFilterHandler", filterHandler.serializeNBT());
        return tag;
    }

    public void readConfigFromTag(CompoundTag tag) {
        super.readConfigFromTag(tag);
        if (tag.contains("meOutputFilterHandler")) {
            filterHandler.deserializeNBT(tag.getCompound("meOutputFilterHandler"));
            onFilterChanged();
        }
    }

    // ========================================
    // ME Output Handlers && Tick Service
    // ========================================

    @Override
    protected NotifiableMERecipeHandlerTrait<Ingredient, ItemStack> createItemOutputHandler() {
        return new MEExtendedOutputPartMachineBase.MEItemOutputHandler(this) {

            @Override
            public MEExtendedOutputPartMachine getMachine() {
                return (MEExtendedOutputPartMachine) this.machine;
            }

            @Override
            public boolean outputHasFilter() {
                return filterHandler.isHasItemFilter();
            }

            @Override
            public List<Ingredient> meHandleRecipeOutputInner(List<Ingredient> left, boolean simulate) {
                if (simulate) return filterHandler.testIngredient(left);
                for (Iterator<Ingredient> it = left.iterator(); it.hasNext();) {
                    Ingredient ingredient = it.next();
                    if (ingredient instanceof IntProviderIngredient intProvider) {
                        intProvider.setItemStacks(null);
                        intProvider.setSampledCount(null);
                    }

                    ItemStack[] items = ingredient.getItems();
                    if (items.length != 0) {
                        ItemStack output = items[0];
                        if (output.isEmpty()) it.remove();
                        else if (filterHandler.test(output)) {
                            buffer.mergeLong(AEItemKey.of(output),
                                    ingredient instanceof LongIngredient longIngredient ? longIngredient.getActualAmount() : output.getCount(),
                                    NumberUtils::saturatedAdd);
                            it.remove();
                        }
                    } else it.remove();
                }
                return left;
            }
        };
    }

    @Override
    protected NotifiableMERecipeHandlerTrait<FluidIngredient, FluidStack> createFluidOutputHandler() {
        return new MEExtendedOutputPartMachineBase.MEFluidOutputHandler(this) {

            @Override
            public MEExtendedOutputPartMachine getMachine() {
                return (MEExtendedOutputPartMachine) this.machine;
            }

            @Override
            public boolean outputHasFilter() {
                return filterHandler.isHasFluidFilter();
            }

            @Override
            public List<FluidIngredient> meHandleRecipeOutputInner(List<FluidIngredient> left, boolean simulate) {
                if (simulate) return filterHandler.testFluidIngredient(left);
                for (Iterator<FluidIngredient> it = left.iterator(); it.hasNext();) {
                    FluidIngredient fluidIngredient = it.next();
                    if (!fluidIngredient.isEmpty()) {
                        FluidStack[] fluids = fluidIngredient.getStacks();
                        if (fluids.length != 0) {
                            FluidStack output = fluids[0];
                            if (output.isEmpty()) it.remove();
                            else if (filterHandler.test(output)) {
                                buffer.mergeLong(AEFluidKey.of(output.getFluid()), output.getAmount(), NumberUtils::saturatedAdd);
                                it.remove();
                            }
                        } else it.remove();
                    } else it.remove();
                }

                return left;
            }
        };
    }

    @Override
    protected @NotNull IMEFilterIOTrait createMETrait() {
        return new MEFilterIOTrait(this);
    }

    @Override
    public @NotNull IMEFilterIOTrait getMETrait() {
        return (IMEFilterIOTrait) meTrait;
    }

    @Override
    protected void registerDefaultServices() {
        getMainNode().addService(IGridTickable.class, new Ticker());
    }

    protected class Ticker implements IGridTickable {

        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return new TickingRequest(MIN_FREQUENCY, MAX_FREQUENCY, false, true);
        }

        @Override
        public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
            if (!getMainNode().isActive()) {
                isSleeping = true;
                return TickRateModulation.SLEEP;
            }

            if (buffer.isEmpty()) {
                if (ticksSinceLastCall >= MAX_FREQUENCY) {
                    isSleeping = true;
                    return TickRateModulation.SLEEP;
                } else return TickRateModulation.SLOWER;
            } else return reFunds(buffer, getMainNode().getGrid(), actionSource) ? TickRateModulation.URGENT : TickRateModulation.SLOWER;
        }
    }

    protected class MEFilterIOTrait extends MEIOTrait implements IMEFilterIOTrait {

        public MEFilterIOTrait(MEExtendedOutputPartMachine machine) {
            super(machine);
        }

        @Override
        public MEExtendedOutputPartMachine getMachine() {
            return (MEExtendedOutputPartMachine) machine;
        }

        @Override
        public boolean hasItemFilter() {
            return filterHandler.isHasItemFilter();
        }

        @Override
        public boolean hasFluidFilter() {
            return filterHandler.isHasFluidFilter();
        }
    }
}
