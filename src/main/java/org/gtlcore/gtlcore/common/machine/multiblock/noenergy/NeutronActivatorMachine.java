package org.gtlcore.gtlcore.common.machine.multiblock.noenergy;

import org.gtlcore.gtlcore.api.machine.multiblock.NoEnergyMultiblockMachine;
import org.gtlcore.gtlcore.api.pattern.util.IValueContainer;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import org.gtlcore.gtlcore.common.data.GTLRecipeModifiers;
import org.gtlcore.gtlcore.common.machine.multiblock.part.NeutronAcceleratorPartMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.part.NeutronSensorPartMachine;
import org.gtlcore.gtlcore.integration.voxelregion.GtlRegionExplosionFacade;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper;
import com.gregtechceu.gtceu.api.data.tag.TagPrefix;
import com.gregtechceu.gtceu.api.machine.ConditionalSubscriptionHandler;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.common.data.GTMaterials;
import com.gregtechceu.gtceu.common.data.GTRecipeModifiers;
import com.gregtechceu.gtceu.common.machine.multiblock.part.ItemBusPartMachine;
import com.gregtechceu.gtceu.utils.FormattingUtil;

import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.ChatFormatting;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class NeutronActivatorMachine extends NoEnergyMultiblockMachine {

    private static final UUID MACHINE_ACTOR = new UUID(0, 0);
    private static final int EXPLOSION_RADIUS = 12;

    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            NeutronActivatorMachine.class, NoEnergyMultiblockMachine.MANAGED_FIELD_HOLDER);

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    private int height = 0;

    @Getter
    @Persisted
    @DescSynced
    private int eV;

    private static final Item dustBeryllium = ChemicalHelper.get(TagPrefix.dust, GTMaterials.Beryllium).getItem();
    private static final Item dustGraphite = ChemicalHelper.get(TagPrefix.dust, GTMaterials.Graphite).getItem();

    protected ConditionalSubscriptionHandler neutronEnergySubs;

    private Set<NeutronSensorPartMachine> sensorMachines;
    private Set<ItemBusPartMachine> busMachines;
    private Set<NeutronAcceleratorPartMachine> acceleratorMachines;

    public NeutronActivatorMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        this.neutronEnergySubs = new ConditionalSubscriptionHandler(this, this::neutronEnergyUpdate, this::isFormed);
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        IValueContainer<?> container = getMultiblockState().getMatchContext()
                .getOrCreate("SpeedPipeValue", IValueContainer::noop);
        if (container.getValue() instanceof Integer integer) {
            height = integer;
        }
        for (IMultiPart part : getParts()) {
            if (part instanceof ItemBusPartMachine itemBusPartMachine) {
                busMachines = Objects.requireNonNullElseGet(busMachines, HashSet::new);
                busMachines.add(itemBusPartMachine);
            }
            if (part instanceof NeutronSensorPartMachine neutronSensorMachine) {
                sensorMachines = Objects.requireNonNullElseGet(sensorMachines, HashSet::new);
                sensorMachines.add(neutronSensorMachine);
            }
            if (part instanceof NeutronAcceleratorPartMachine neutronAccelerator) {
                acceleratorMachines = Objects.requireNonNullElseGet(acceleratorMachines, HashSet::new);
                acceleratorMachines.add(neutronAccelerator);
            }
        }
        neutronEnergySubs.initialize(getLevel());
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        height = 0;
        sensorMachines = null;
        busMachines = null;
        acceleratorMachines = null;
        neutronEnergySubs.unsubscribe();
    }

    private double getEfficiencyFactor() {
        return Math.pow(0.95, Math.max(height - 4, 0));
    }

    @Nullable
    public static GTRecipe recipeModifier(MetaMachine machine, @NotNull GTRecipe recipe) {
        if (machine instanceof NeutronActivatorMachine nMachine) {
            if (nMachine.eV > recipe.data.getInt("ev_min") * 1000000 &&
                    nMachine.eV < recipe.data.getInt("ev_max") * 1000000) {
                GTRecipe recipe1 = GTRecipeModifiers.accurateParallel(machine, recipe, GTLRecipeModifiers.getHatchParallel(nMachine), false)
                        .getFirst();
                recipe1.duration = (int) Math.round(Math.max(recipe1.duration * nMachine.getEfficiencyFactor(), 1));
                return recipe1;
            } else if (nMachine.eV < recipe.data.getInt("ev_min") * 1000000) {
                RecipeResult.of((IRecipeLogicMachine) machine, RecipeResult.fail(Component.translatable("gtceu.recipe.fail.no.enough.neutron")));
            } else if (nMachine.eV > recipe.data.getInt("ev_max") * 1000000) {
                RecipeResult.of((IRecipeLogicMachine) machine, RecipeResult.fail(Component.translatable("gtceu.recipe.fail.too.much.neutron")));
            }
        }
        return null;
    }

    @Override
    public boolean beforeWorking(@Nullable GTRecipe recipe) {
        if (recipe != null) {
            if (eV >= recipe.data.getInt("evt") * 1000 * getEVtMultiplier(recipe)) return true;
            else {
                RecipeResult.of(this, RecipeResult.fail(Component.translatable("gtceu.recipe.fail.no.enough.eV.consumed")));
                return false;
            }
        }
        return false;
    }

    @Override
    public boolean onWorking() {
        boolean value = super.onWorking();
        var lastRecipe = getRecipeLogic().getLastRecipe();
        if (lastRecipe != null) {
            int evt = (int) (lastRecipe.data.getInt("evt") * 1000 * getEVtMultiplier(lastRecipe));
            if (eV < evt) {
                getRecipeLogic().setProgress(0);
            } else {
                eV -= evt;
            }
        }
        return value;
    }

    private double getEVtMultiplier(GTRecipe recipe) {
        return Math.max(1, Math.pow(IGTRecipe.of(recipe).getRealParallels(), 1.5) * getEfficiencyFactor());
    }

    protected void neutronEnergyUpdate() {
        if (acceleratorMachines == null) return;
        for (var accelerator : acceleratorMachines) {
            long increase = accelerator.consumeEnergy();
            if (increase > 0) {
                this.eV += (int) Math.round(Math.max(increase * getEfficiencyFactor(), 1));
            }
        }
        if (this.eV > 1200000000) {
            this.eV = 0;
            triggerExplosion();
            return;
        }
        moderateUpdate();
        absorptionUpdate();
    }

    private void triggerExplosion() {
        if (getLevel() instanceof ServerLevel serverLevel) {
            if (GtlRegionExplosionFacade.submitSphere(serverLevel, MACHINE_ACTOR, getPos(), EXPLOSION_RADIUS)) {
                serverLevel.removeBlock(getPos(), false);
            }
        }
    }

    protected void moderateUpdate() {
        boolean anyAcceleratorProducing = acceleratorMachines != null &&
                acceleratorMachines.stream().anyMatch(NeutronAcceleratorPartMachine::isProducing);
        if (!anyAcceleratorProducing) {
            if (getOffsetTimer() % 20 == 0) {
                this.eV = Math.max(eV - 72 * 1000, 0);
            }
            if (this.eV < 0) this.eV = 0;
        }
        if (!isFormed() || sensorMachines == null) return;
        sensorMachines.forEach(sensor -> sensor.update(eV));
    }

    protected void absorptionUpdate() {
        if (busMachines == null || eV <= 0 && getOffsetTimer() % 10 != 0) return;
        for (var bus : busMachines) {
            var inv = bus.getInventory();
            var io = inv.getHandlerIO();
            if (io == IO.IN || io == IO.BOTH) {
                for (int i = 0; i < inv.getSlots(); i++) {
                    var stack = inv.getStackInSlot(i);
                    if (stack.is(dustBeryllium) || stack.is(dustGraphite)) {
                        int consume = Math.min(Math.max(eV / (10 * 1000000), 1), stack.getCount());
                        inv.extractItemInternal(i, consume, false);
                        this.eV -= 10 * 1000000 * consume;
                    }
                }
            }
        }
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        super.addDisplayText(textList);
        if (isFormed()) {
            int numParallels = GTLRecipeModifiers.getHatchParallel(this);
            textList.add(Component.translatable("gtceu.multiblock.parallel",
                    Component.literal(FormattingUtil.formatNumbers(numParallels)).withStyle(ChatFormatting.DARK_PURPLE))
                    .withStyle(ChatFormatting.GRAY));
            textList.add(Component.translatable("gtceu.machine.neutron_activator.ev", processNumber(eV)));
            textList.add(Component.translatable("gtceu.machine.neutron_activator.efficiency",
                    FormattingUtil.formatNumbers(Math.max(1, Math.pow(numParallels, 1.5) * getEfficiencyFactor()))));
            textList.add(Component.translatable("gtceu.machine.neutron_activator.height", height));
            textList.add(Component.translatable("gtceu.machine.neutron_activator.time",
                    FormattingUtil.formatNumbers(getEfficiencyFactor() * 100)).append("%"));
        }
    }

    private String processNumber(int num) {
        float num2;
        num2 = ((float) num) / 1000F;
        if (num2 <= 0) {
            return String.format("%d", num);
        }
        if (num2 < 1000.0) {
            return String.format("%.1fK", num2);
        }
        num2 /= 1000F;
        return String.format("%.1fM", num2);
    }
}
