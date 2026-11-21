package org.gtlcore.gtlcore.common.machine.multiblock.electric;

import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineModule;
import org.gtlcore.gtlcore.common.data.GTLBlocks;
import org.gtlcore.gtlcore.common.data.GTLRecipeModifiers;
import org.gtlcore.gtlcore.utils.MachineUtil;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.feature.IMachineLife;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.OverclockingLogic;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.logic.OCParams;
import com.gregtechceu.gtceu.api.recipe.logic.OCResult;
import com.gregtechceu.gtceu.common.data.GTRecipeModifiers;
import com.gregtechceu.gtceu.utils.FormattingUtil;

import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.ChatFormatting;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class SpaceElevatorModuleMachine extends WorkableElectricMultiblockMachine
                                        implements IModularMachineModule<SpaceElevatorMachine, SpaceElevatorModuleMachine>, IMachineLife {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(SpaceElevatorModuleMachine.class, WorkableElectricMultiblockMachine.MANAGED_FIELD_HOLDER);
    private static final int CONNECTION_CHECK_INTERVAL_TICKS = 20;

    public SpaceElevatorModuleMachine(IMachineBlockEntity holder, boolean sepmTier, Object... args) {
        super(holder, args);
        this.sepmTier = sepmTier;
    }

    @DescSynced
    private int spaceElevatorTier = 0;
    private int moduleTier = 0;

    private final boolean sepmTier;
    @Nullable
    private TickableSubscription connectionSubscription;

    @Persisted
    @Nullable
    @Getter
    @Setter
    private BlockPos hostPosition;

    @Nullable
    @Getter
    @Setter
    private SpaceElevatorMachine host;

    @Override
    public @NotNull Class<SpaceElevatorMachine> getHostType() {
        return SpaceElevatorMachine.class;
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    // ========================================
    // Elevator connection
    // ========================================

    @Override
    public void onConnected(@NotNull SpaceElevatorMachine host) {
        updateConnectionSubscription();
        getSpaceElevatorTier();
        recipeLogic.updateTickSubscription();
        recipeLogic.markLastRecipeDirty();
        recipeLogic.setStatus(RecipeLogic.Status.IDLE);
    }

    @Override
    public BlockPos[] getHostScanPositions() {
        if (!(getLevel() instanceof ServerLevel serverLevel)) {
            return MachineUtil.EMPTY_POS_ARRAY;
        }
        return getHostPositions(findPowerCore(serverLevel));
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        updateConnectionSubscription();
        reconcileHostConnection("module_structure_formed");
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        removeFromHost(this.host);
        updateConnectionSubscription();
    }

    @Override
    public void onPartUnload() {
        super.onPartUnload();
        removeFromHost(this.host);
        stopConnectionSubscription();
    }

    @Override
    public void onMachineRemoved() {
        removeFromHost(this.host);
        stopConnectionSubscription();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        updateConnectionSubscription();
    }

    @Override
    public void onUnload() {
        stopConnectionSubscription();
        super.onUnload();
    }

    @Override
    public void removeFromHost(@Nullable SpaceElevatorMachine host) {
        BlockPos previousHostPosition = getHostPosition();
        IModularMachineModule.super.removeFromHost(host);
        if (previousHostPosition != null) {

        }
    }

    private void updateConnectionSubscription() {
        if (isFormed()) {
            if (connectionSubscription == null) {
                connectionSubscription = subscribeServerTick(this::connectionTick);
            }
        } else {
            stopConnectionSubscription();
        }
    }

    private void stopConnectionSubscription() {
        if (connectionSubscription != null) {
            connectionSubscription.unsubscribe();
            connectionSubscription = null;
        }
    }

    private void connectionTick() {
        if (!isFormed()) {
            stopConnectionSubscription();
            return;
        }
        if (getOffsetTimer() % CONNECTION_CHECK_INTERVAL_TICKS == 0) {
            reconcileHostConnection("periodic_retry");
        }
    }

    private void reconcileHostConnection(String trigger) {
        if (!(getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockPos savedHostPosition = getHostPosition();
        SpaceElevatorMachine liveHost = getLiveHost(serverLevel, savedHostPosition);
        if (liveHost != null) {
            boolean repairedRegistration = !liveHost.getModuleSet().contains(this);
            if (getHost() != liveHost || repairedRegistration) {
                connectToHost(liveHost);

            }
            return;
        }

        if (getHost() != null) {
            removeFromHost(getHost());
        }

        BlockPos powerCore = findPowerCore(serverLevel);
        BlockPos[] candidates = getHostPositions(powerCore);

        for (BlockPos candidatePos : candidates) {
            MetaMachine machine = MetaMachine.getMachine(serverLevel, candidatePos);
            boolean formed = machine instanceof SpaceElevatorMachine elevator && elevator.isFormed();
            boolean valid = isValidHost(machine);

            if (valid) {
                SpaceElevatorMachine candidateHost = (SpaceElevatorMachine) machine;
                connectToHost(candidateHost);

                return;
            }
        }
    }

    @Nullable
    private SpaceElevatorMachine getLiveHost(ServerLevel level, @Nullable BlockPos position) {
        if (position == null) {
            return null;
        }
        MetaMachine machine = MetaMachine.getMachine(level, position);
        return isValidHost(machine) ? (SpaceElevatorMachine) machine : null;
    }

    @Nullable
    private BlockPos findPowerCore(Level level) {
        BlockPos pos = getPos();
        BlockPos[] powerCorePositions = new BlockPos[] {
                pos.offset(8, -2, 3),
                pos.offset(8, -2, -3),
                pos.offset(-8, -2, 3),
                pos.offset(-8, -2, -3),
                pos.offset(3, -2, 8),
                pos.offset(-3, -2, 8),
                pos.offset(3, -2, -8),
                pos.offset(-3, -2, -8)
        };
        for (BlockPos position : powerCorePositions) {
            if (level.getBlockState(position).is(GTLBlocks.POWER_CORE.get())) {
                return position;
            }
        }
        return null;
    }

    private static BlockPos[] getHostPositions(@Nullable BlockPos powerCore) {
        if (powerCore == null) {
            return MachineUtil.EMPTY_POS_ARRAY;
        }
        return new BlockPos[] {
                powerCore.offset(3, 2, 0),
                powerCore.offset(-3, 2, 0),
                powerCore.offset(0, 2, 3),
                powerCore.offset(0, 2, -3)
        };
    }

    private static String hostCandidateRejection(MetaMachine machine, boolean formed) {
        if (machine == null) {
            return "missing_machine";
        }
        if (!(machine instanceof SpaceElevatorMachine)) {
            return "wrong_machine_type";
        }
        return formed ? "invalid_host" : "host_not_formed";
    }

    private static String machineType(MetaMachine machine) {
        return machine == null ? "none" : machine.getClass().getName();
    }

    // ========================================
    // Recipe Tier
    // ========================================

    private void getSpaceElevatorTier() {
        if (this.host != null) {
            final RecipeLogic logic = host.getRecipeLogic();
            if (logic.isWorking() && logic.getProgress() > 80) {
                spaceElevatorTier = host.getTier() - GTValues.ZPM;
                moduleTier = host.getCasingTier();
            } else if (!logic.isWorking()) {
                spaceElevatorTier = 0;
                moduleTier = 0;
            }
        } else {
            spaceElevatorTier = 0;
            moduleTier = 0;
        }
    }

    @Nullable
    public static GTRecipe recipeModifier(MetaMachine machine, @NotNull GTRecipe recipe, @NotNull OCParams params,
                                          @NotNull OCResult result) {
        if (machine instanceof SpaceElevatorModuleMachine moduleMachine) {
            moduleMachine.getSpaceElevatorTier();
            if (moduleMachine.spaceElevatorTier < 1) {
                return null;
            }
            if (moduleMachine.sepmTier && recipe.data.getInt("SEPMTier") > moduleMachine.moduleTier) {
                return null;
            }
            GTRecipe recipe1 = GTLRecipeModifiers.reduction(machine, recipe, 1, Math.pow(0.8, moduleMachine.spaceElevatorTier - 1));
            if (recipe1 != null) {
                recipe1 = GTRecipeModifiers.accurateParallel(machine, recipe1, (int) Math.pow(4, moduleMachine.moduleTier - 1), false).getFirst();
                if (recipe1 != null) return RecipeHelper.applyOverclock(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK, recipe1, moduleMachine.getOverclockVoltage(), params, result);
            }
        }
        return null;
    }

    @Override
    public boolean onWorking() {
        boolean value = super.onWorking();
        if (getOffsetTimer() % 20 == 0) {
            getSpaceElevatorTier();
            if (spaceElevatorTier < 1) {
                getRecipeLogic().setProgress(0);
            }
        }
        return value;
    }

    @Override
    public void addDisplayText(@NotNull List<Component> textList) {
        super.addDisplayText(textList);
        if (!this.isFormed) return;
        if (getOffsetTimer() % 10 == 0) {
            getSpaceElevatorTier();
        }
        textList.add(Component.translatable("gtceu.multiblock.parallel", Component.literal(FormattingUtil.formatNumbers(Math.pow(4, moduleTier - 1))).withStyle(ChatFormatting.DARK_PURPLE)).withStyle(ChatFormatting.GRAY));
        textList.add(Component.translatable(spaceElevatorTier < 1 ? "tooltip.gtlcore.space_elevator_not_connected" : "tooltip.gtlcore.space_elevator_connected"));
        textList.add(Component.translatable("gtceu.machine.duration_multiplier.tooltip", FormattingUtil.formatPercent(Math.pow(0.8, spaceElevatorTier - 1))));
    }
}
