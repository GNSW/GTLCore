package org.gtlcore.gtlcore.common.machine.multiblock.part.ae;

import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEFilterIOTrait;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.gtlcore.gtlcore.integration.ae2.async.AEAccumulator;
import org.gtlcore.gtlcore.integration.ae2.async.AEWriteService;
import org.gtlcore.gtlcore.utils.NumberUtils;

import com.gregtechceu.gtceu.api.gui.widget.IntInputWidget;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;

import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEKey;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.jetbrains.annotations.NotNull;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class MEExtendedAsyncOutputPartMachine extends MEExtendedOutputPartMachineBase {

    private final AEAccumulator accumulator = new AEAccumulator();
    private final WeakReference<AEAccumulator> accRef = new WeakReference<>(accumulator);
    private final Queue<Object2LongOpenHashMap<AEKey>> pendingQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drainRequested = new AtomicBoolean(false);

    public MEExtendedAsyncOutputPartMachine(IMachineBlockEntity holder) {
        super(holder);
    }

    private void requestAsyncDrain() {
        if (drainRequested.compareAndSet(false, true)) {
            AEWriteService.INSTANCE.prepareDrainedData(accRef, pendingQueue, drainRequested);
        }
    }

    private boolean mergeFromPendingData() {
        boolean merged = false;
        Object2LongOpenHashMap<AEKey> data;
        while ((data = pendingQueue.poll()) != null) {
            if (data.isEmpty()) continue;
            data.object2LongEntrySet().fastForEach(e -> buffer.mergeLong(e.getKey(), e.getLongValue(), NumberUtils::saturatedAdd));
            merged = true;
        }
        return merged;
    }

    @Override
    protected void flushAsyncQueue() {
        AEWriteService.INSTANCE.flushBlocking(accRef, pendingQueue, drainRequested, AEWriteService.FLUSH_TIMEOUT);
        mergeFromPendingData();
    }

    @Override
    public void onMachineRemoved() {
        flushAsyncQueue();
        super.onMachineRemoved();
    }

    // ========================================
    // GUI SYSTEM
    // ========================================

    @Override
    public ModularUI createUI(Player entityPlayer) {
        final var ui = super.createUI(entityPlayer);
        ui.registerCloseListener(this::updatePriority);
        return ui;
    }

    @Override
    public @NotNull Widget createUIWidget() {
        WidgetGroup group = (WidgetGroup) super.createUIWidget();
        group.addWidget(new IntInputWidget(90, 0, 80, 10, this::getPriority, this::setPriority).setMin(10).setMax(100000));
        return group;
    }

    // ========================================
    // ME Output Handlers && Tick Service
    // ========================================

    @Override
    protected NotifiableMERecipeHandlerTrait<Ingredient, ItemStack> createItemOutputHandler() {
        return new MEItemOutputHandler(this) {

            public MEExtendedAsyncOutputPartMachine getMachine() {
                return (MEExtendedAsyncOutputPartMachine) this.machine;
            }

            @Override
            public List<Ingredient> meHandleRecipeOutputInner(List<Ingredient> left, boolean simulate) {
                if (simulate) return List.of();
                AEWriteService.INSTANCE.submitIngredientLeft(accRef, left);
                getMachine().markDirty();
                getMETrait().notifySelfIO();
                return List.of();
            }
        };
    }

    @Override
    protected NotifiableMERecipeHandlerTrait<FluidIngredient, FluidStack> createFluidOutputHandler() {
        return new MEFluidOutputHandler(this) {

            public MEExtendedAsyncOutputPartMachine getMachine() {
                return (MEExtendedAsyncOutputPartMachine) this.machine;
            }

            @Override
            public List<FluidIngredient> meHandleRecipeOutputInner(List<FluidIngredient> left, boolean simulate) {
                if (simulate) return List.of();
                AEWriteService.INSTANCE.submitFluidIngredientLeft(accRef, left);
                getMachine().markDirty();
                getMETrait().notifySelfIO();
                return List.of();
            }
        };
    }

    @Override
    protected @NotNull IMEFilterIOTrait createMETrait() {
        return new MEAsyncFilterIOTrait(this);
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
            final boolean isActive = getMainNode().isActive();
            final boolean dataMerged = mergeFromPendingData();
            if (dataMerged) markDirty();
            final boolean hasPendingWork = !pendingQueue.isEmpty() || !accumulator.isEmpty();

            if (hasPendingWork) {
                requestAsyncDrain();
            }

            if (!isActive) {
                if (hasPendingWork) {
                    return TickRateModulation.FASTER;
                } else {
                    if (ticksSinceLastCall >= MAX_FREQUENCY) {
                        isSleeping = true;
                        return TickRateModulation.SLEEP;
                    } else return TickRateModulation.SLOWER;
                }
            }

            if (buffer.isEmpty()) {
                if (hasPendingWork) {
                    return TickRateModulation.FASTER;
                }
                if (ticksSinceLastCall >= MAX_FREQUENCY) {
                    isSleeping = true;
                    return TickRateModulation.SLEEP;
                } else return TickRateModulation.SLOWER;
            } else {
                boolean inserted = AEUtils.reFunds(buffer, getMainNode().getGrid(), actionSource);
                if (inserted) markDirty();
                if (inserted || dataMerged) {
                    return TickRateModulation.URGENT;
                } else {
                    return TickRateModulation.SLOWER;
                }
            }
        }
    }

    protected class MEAsyncFilterIOTrait extends MEIOTrait implements IMEFilterIOTrait {

        public MEAsyncFilterIOTrait(MEExtendedAsyncOutputPartMachine machine) {
            super(machine);
        }

        @Override
        public MEExtendedAsyncOutputPartMachine getMachine() {
            return (MEExtendedAsyncOutputPartMachine) machine;
        }

        @Override
        public void notifySelfIO() {
            super.notifySelfIO();
            if (getMainNode().isActive()) {
                getMainNode().ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
            }
        }
    }
}
