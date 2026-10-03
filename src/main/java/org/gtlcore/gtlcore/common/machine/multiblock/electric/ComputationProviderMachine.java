package org.gtlcore.gtlcore.common.machine.multiblock.electric;

import org.gtlcore.gtlcore.api.machine.computation.ComputationConnections;
import org.gtlcore.gtlcore.api.machine.computation.ComputationLedger;
import org.gtlcore.gtlcore.api.machine.computation.ComputationMath;
import org.gtlcore.gtlcore.api.machine.computation.ComputationNetwork;
import org.gtlcore.gtlcore.api.machine.computation.ComputationSource;
import org.gtlcore.gtlcore.utils.MachineIO;
import org.gtlcore.gtlcore.utils.TextUtil;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.IControllable;
import com.gregtechceu.gtceu.api.capability.IOpticalComputationProvider;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockDisplayText;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;

import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.ChatFormatting;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.network.chat.Component;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

import javax.annotation.ParametersAreNonnullByDefault;

import static org.gtlcore.gtlcore.utils.Registries.getItemStack;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class ComputationProviderMachine extends WorkableElectricMultiblockMachine
                                        implements IOpticalComputationProvider, IControllable, ComputationSource {

    private static final long INFINITE_PROVIDER_EU_PER_REQUEST = Integer.MAX_VALUE;
    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            ComputationProviderMachine.class, WorkableElectricMultiblockMachine.MANAGED_FIELD_HOLDER);

    public int allocatedCWUt = 0;
    @Persisted
    public long totalCWU = 0;
    public int maxCWUt = 0;
    @Nullable
    protected TickableSubscription tickSubs;

    boolean canProvideCWUt = true;
    private boolean inf = false;
    private final ComputationLedger computationLedger = new ComputationLedger();
    private long computationTick = Long.MIN_VALUE;
    private long generatedCWU;
    private long generationEnergy;

    public ComputationProviderMachine(IMachineBlockEntity holder, boolean inf, Object... args) {
        super(holder, args);
        this.inf = inf;
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public int requestCWUt(int cwut, boolean simulate, @NotNull Collection<IOpticalComputationProvider> seen) {
        seen.add(this);
        return allocatedCWUt(cwut, simulate);
    }

    // Legacy int entry point; all requests share the physical source ledger.
    private int allocatedCWUt(int cwut, boolean simulate) {
        return ComputationNetwork.request(this, cwut, simulate);
    }

    private void advanceComputationTick() {
        long tick = ComputationNetwork.tick(getLevel());
        if (computationTick != tick) {
            computationTick = tick;
            generatedCWU = 0;
            generationEnergy = 0;
        }
        computationLedger.advance(tick);
        allocatedCWUt = ComputationMath.toInt(computationLedger.used());
    }

    @Override
    public long gtlcore$computationCapacity() {
        return inf && isComputationAvailable() ? Long.MAX_VALUE : getMaxCWUt();
    }

    @Override
    public long gtlcore$availableComputation() {
        advanceComputationTick();
        if (!isComputationAvailable()) return 0;
        if (inf) return canDrawEnergy(INFINITE_PROVIDER_EU_PER_REQUEST) ?
                computationLedger.remaining(Long.MAX_VALUE) : 0;
        long capacity = getMaxCWUt();
        long stock = Math.max(0, totalCWU);
        if (stock < capacity && canDrawEnergy(GTValues.VA[getTier()]))
            stock = Math.min(capacity, ComputationMath.add(stock, 1L << getTier()));
        return Math.min(computationLedger.remaining(capacity), stock);
    }

    @Override
    public boolean gtlcore$canBridgeComputation() {
        return isComputationAvailable();
    }

    @Override
    public Receipt gtlcore$withdrawComputation(long amount) {
        if (amount <= 0 || amount > gtlcore$availableComputation()) return null;
        totalCWU = Math.max(0, totalCWU);
        long energy = inf ? INFINITE_PROVIDER_EU_PER_REQUEST : totalCWU < amount ? GTValues.VA[getTier()] : 0;
        if (energy > 0) {
            long removed = energyContainer.removeEnergy(energy);
            if (removed != energy) {
                if (removed > 0) energyContainer.addEnergy(removed);
                return null;
            }
        }
        long generated = 0;
        if (!inf) {
            if (energy > 0) {
                generated = Math.min(getMaxCWUt(), ComputationMath.add(Math.max(0, totalCWU), 1L << getTier())) - totalCWU;
                totalCWU += generated;
                generatedCWU += generated;
                generationEnergy += energy;
            }
            totalCWU -= amount;
        }
        computationLedger.debit(amount, energy);
        allocatedCWUt = ComputationMath.toInt(computationLedger.used());
        markDirty();
        return new Receipt(amount, () -> {
            long refundEnergy = inf ? energy : 0;
            if (!inf) {
                totalCWU += amount;
                // Other requests can commit between this debit and its cancellation. Only undo a
                // generated batch when all of it is back in stock; never remove another owner's CWU.
                if (generatedCWU > 0 && totalCWU >= generatedCWU) {
                    totalCWU -= generatedCWU;
                    generatedCWU = 0;
                    refundEnergy = generationEnergy;
                    generationEnergy = 0;
                }
            }
            computationLedger.refund(amount, refundEnergy);
            if (refundEnergy > 0) energyContainer.addEnergy(refundEnergy);
            allocatedCWUt = ComputationMath.toInt(computationLedger.used());
            markDirty();
        });
    }

    private boolean canDrawEnergy(long eu) {
        if (energyContainer == null) energyContainer = getEnergyContainer();
        return energyContainer.getEnergyStored() >= eu;
    }

    private boolean isComputationAvailable() {
        return ComputationConnections.loaded(this) && isFormed() && !getRecipeLogic().isSuspend();
    }

    private static final ItemStack OPTICAL_MAINFRAME = getItemStack("kubejs:optical_mainframe", 8);
    private static final ItemStack EXOTIC_MAINFRAME = getItemStack("kubejs:exotic_mainframe", 8);
    private static final ItemStack COSMIC_MAINFRAME = getItemStack("kubejs:cosmic_mainframe", 8);
    private static final ItemStack SUPRACAUSAL_MAINFRAME = getItemStack("kubejs:supracausal_mainframe", 8);

    @Override
    public int getMaxCWUt(@NotNull Collection<IOpticalComputationProvider> seen) {
        seen.add(this);
        if (!isComputationAvailable()) return 0;
        if (inf) return Integer.MAX_VALUE;
        if (maxCWUt == 0) {
            maxCWUt = switch (getTier()) {
                case GTValues.UIV -> MachineIO.notConsumableItem(this, OPTICAL_MAINFRAME) ? 1024 : 0;
                case GTValues.UXV -> MachineIO.notConsumableItem(this, EXOTIC_MAINFRAME) ? 2048 : 0;
                case GTValues.OpV -> MachineIO.notConsumableItem(this, COSMIC_MAINFRAME) ? 4096 : 0;
                case GTValues.MAX -> MachineIO.notConsumableItem(this, SUPRACAUSAL_MAINFRAME) ? 8192 : 0;
                default -> 0;
            };
        }
        return maxCWUt;
    }

    @Override
    public boolean canBridge(@NotNull Collection<IOpticalComputationProvider> seen) {
        seen.add(this);
        return gtlcore$canBridgeComputation();
    }

    public void tick() {
        if (!isFormed()) {
            allocatedCWUt = 0;
            canProvideCWUt = false;
            updateTickSubscription();
            return;
        }
        advanceComputationTick();
        if (getRecipeLogic().isSuspend()) {
            canProvideCWUt = false;
            return;
        }
        canProvideCWUt = true;
        if (computationLedger.previousUsed() != 0 || allocatedCWUt != 0) {
            getRecipeLogic().setStatus(RecipeLogic.Status.WORKING);
        } else {
            getRecipeLogic().setStatus(RecipeLogic.Status.IDLE);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().tell(new TickTask(0, this::updateTickSubscription));
        }
    }

    @Override
    public void onUnload() {
        super.onUnload();
        ComputationNetwork.invalidate();
        if (tickSubs != null) {
            tickSubs.unsubscribe();
            tickSubs = null;
        }
    }

    protected void updateTickSubscription() {
        if (isFormed) {
            tickSubs = subscribeServerTick(tickSubs, this::tick);
        } else if (tickSubs != null) {
            tickSubs.unsubscribe();
            tickSubs = null;
        }
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        maxCWUt = 0;
        ComputationNetwork.invalidate();
        canProvideCWUt = true;
        if (getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().tell(new TickTask(0, this::updateTickSubscription));
        }
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        allocatedCWUt = 0;
        // Re-forming the same controller must not replenish this tick's computation quota.
        // Keep generation receipts too; the next server tick advances both automatically.
        maxCWUt = 0;
        canProvideCWUt = false;
        ComputationNetwork.invalidate();
        updateTickSubscription();
    }

    @Override
    public void onChanged() {
        maxCWUt = 0;
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        MultiblockDisplayText.builder(textList, isFormed())
                .setWorkingStatus(!getRecipeLogic().isSuspend(), computationLedger.previousUsed() > 0)
                .setWorkingStatusKeys(
                        "gtceu.multiblock.idling",
                        "gtceu.multiblock.idling",
                        "gtceu.multiblock.data_bank.providing")
                .addCustom(tl -> {
                    if (isFormed()) {
                        Component cwutInfo = Component.literal(
                                computationLedger.previousUsed() + " / " + (inf ? TextUtil.full_color("∞") : getMaxCWUt()))
                                .append(Component.literal(" CWU/t"))
                                .withStyle(ChatFormatting.AQUA);
                        tl.add(Component.translatable(
                                "gtceu.multiblock.hpca.computation",
                                cwutInfo).withStyle(ChatFormatting.GRAY));
                    }
                })
                .addWorkingStatusLine();
    }
}
