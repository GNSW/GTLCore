package org.gtlcore.gtlcore.mixin.gtm.api.machine;

import org.gtlcore.gtlcore.api.machine.IPerformanceDisplayMachine;
import org.gtlcore.gtlcore.api.machine.ISuspendableMachine;
import org.gtlcore.gtlcore.api.machine.MachineStartupTickBudget;
import org.gtlcore.gtlcore.api.machine.PerformanceMonitorMachine;
import org.gtlcore.gtlcore.api.machine.trait.IBatchMachine;

import com.gregtechceu.gtceu.api.block.BlockProperties;
import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.item.tool.GTToolType;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.integration.ae2.machine.feature.IGridConnectedMachine;

import com.lowdragmc.lowdraglib.async.AsyncThreadData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import com.mojang.datafixers.util.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Mixin(MetaMachine.class)
public abstract class MetaMachineMixin implements IPerformanceDisplayMachine, ISuspendableMachine {

    @Unique
    private long gtlcore$lastExecutionTime;

    @Unique
    private int gtlcore$averageTickTime;

    @Unique
    private long gtlcore$totaTtickCount;

    @Unique
    private boolean gtlcore$observe;

    @Unique
    private boolean gtlcore$suspendAfterFinish;

    @Unique
    private boolean gtlcore$startupTickAdmitted;

    @Shadow(remap = false)
    public abstract boolean isRemote();

    @Shadow(remap = false)
    @Final
    public IMachineBlockEntity holder;

    @Shadow(remap = false)
    protected abstract void executeTick();

    @Shadow(remap = false)
    public abstract @Nullable Level getLevel();

    @Shadow(remap = false)
    public abstract BlockPos getPos();

    @Shadow(remap = false)
    public abstract BlockState getBlockState();

    @Shadow(remap = false)
    public abstract long getOffsetTimer();

    @Shadow(remap = false)
    @Final
    private List<TickableSubscription> serverTicks;

    @Shadow(remap = false)
    @Final
    private List<TickableSubscription> waitingToAdd;

    @Shadow(remap = false)
    public abstract boolean isInValid();

    @Shadow(remap = false)
    public abstract void onChanged();

    @Inject(method = "onChanged", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$moveAsyncOnChangedToServerThread(CallbackInfo ci) {
        Level level = getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;
        MinecraftServer server = serverLevel.getServer();
        if (server.isSameThread()) return;
        if (!AsyncThreadData.isThreadService()) return;
        ci.cancel();
        server.tell(new TickTask(server.getTickCount(), () -> {
            if (!isInValid()) {
                onChanged();
            }
        }));
    }

    @Override
    public int gtlcore$getTickTime() {
        return gtlcore$averageTickTime;
    }

    @Override
    public void gtlcore$observe() {
        gtlcore$observe = true;
    }

    @Override
    @SuppressWarnings("all")
    public void runTick() {
        executeTick();
    }

    /**
     * @author form nutant
     * @reason Add tick time
     */
    @Overwrite(remap = false)
    public final void serverTick() {
        if (cancelTick()) return;
        Level level = getLevel();
        long currentTime = System.currentTimeMillis();
        if (currentTime - gtlcore$lastExecutionTime < 40) {
            return;
        }
        gtlcore$lastExecutionTime = currentTime;
        boolean startupTick = false;
        ServerLevel startupLevel = null;
        if (!gtlcore$startupTickAdmitted && level instanceof ServerLevel serverLevel) {
            if (!MachineStartupTickBudget.tryAcquire(serverLevel, (MetaMachine) (Object) this)) {
                return;
            }
            gtlcore$startupTickAdmitted = true;
            startupTick = true;
            startupLevel = serverLevel;
        }
        boolean observe = PerformanceMonitorMachine.observe || gtlcore$observe;
        if (observe) currentTime = System.nanoTime();
        long startupStartedNanos = startupTick ? System.nanoTime() : 0;
        runTick();
        if (startupTick) {
            MachineStartupTickBudget.recordExecution(startupLevel, System.nanoTime() - startupStartedNanos);
        }
        if (observe) {
            gtlcore$totaTtickCount += System.nanoTime() - currentTime;
            if (getOffsetTimer() % 40 == 0) {
                gtlcore$observe = false;
                gtlcore$averageTickTime = (int) (gtlcore$totaTtickCount / 40000);
                gtlcore$totaTtickCount = 0;
            }
            if (PerformanceMonitorMachine.observe) PerformanceMonitorMachine.PERFORMANCE_MAP.put((MetaMachine) (Object) this, gtlcore$averageTickTime);
        } else if (!keepTick() && serverTicks.isEmpty() && waitingToAdd.isEmpty() && !isInValid()) {
            gtlcore$averageTickTime = 0;
            gtlcore$totaTtickCount = 0;
            Objects.requireNonNull(getLevel()).setBlockAndUpdate(getPos(), getBlockState().setValue(BlockProperties.SERVER_TICK, false));
        }
    }

    @Inject(method = "onToolClick", at = @At("HEAD"), remap = false, cancellable = true)
    private void gtlcore$toggleBatchProcessingWithWireCutter(Set<@NotNull GTToolType> toolTypes, ItemStack itemStack,
                                                             UseOnContext context,
                                                             CallbackInfoReturnable<Pair<GTToolType, InteractionResult>> cir) {
        Player player = context.getPlayer();
        MetaMachine machine = holder.getMetaMachine();
        if (!Set.of(GTToolType.WIRE_CUTTER).equals(toolTypes) || player == null || !player.isShiftKeyDown() ||
                !(machine instanceof IBatchMachine batchMachine) ||
                !batchMachine.canConfigureBatchProcessing())
            return;

        InteractionResult result = player instanceof ServerPlayer serverPlayer ?
                gtlcore$toggleBatchProcessing(serverPlayer, context.getHand(), batchMachine) :
                InteractionResult.SUCCESS;
        cir.setReturnValue(Pair.of(GTToolType.WIRE_CUTTER, result));
    }

    @Inject(method = "onToolClick", at = @At("RETURN"), remap = false, cancellable = true)
    private void gtlcore$configureGridWithWireCutter(Set<@NotNull GTToolType> toolTypes, ItemStack itemStack,
                                                     UseOnContext context,
                                                     CallbackInfoReturnable<Pair<GTToolType, InteractionResult>> cir) {
        if (cir.getReturnValue().getSecond() != InteractionResult.PASS ||
                !toolTypes.contains(GTToolType.WIRE_CUTTER))
            return;

        Player player = context.getPlayer();
        MetaMachine machine = holder.getMetaMachine();
        if (player instanceof ServerPlayer serverPlayer && machine instanceof IGridConnectedMachine gridConnectedMachine) {
            cir.setReturnValue(Pair.of(GTToolType.WIRE_CUTTER,
                    gTLCore$onWireCutterClick(serverPlayer, context.getHand(), gridConnectedMachine)));
        }
    }

    @Unique
    private InteractionResult gtlcore$toggleBatchProcessing(ServerPlayer serverPlayer, InteractionHand hand,
                                                            IBatchMachine batchMachine) {
        serverPlayer.swing(hand);
        if (!batchMachine.supportsBatchProcessing()) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("gui.gtlcore.batch_processing.unsupported_mode"), true);
            return InteractionResult.CONSUME;
        }

        boolean enabled = !batchMachine.isBatchEnabled();
        batchMachine.setBatchEnabled(enabled);
        serverPlayer.sendSystemMessage(Component.translatable(enabled ?
                "gui.gtlcore.batch_processing.enabled" :
                "gui.gtlcore.batch_processing.disabled"), true);
        return InteractionResult.CONSUME;
    }

    @Inject(method = "shouldRenderGrid", at = @At("HEAD"), remap = false, cancellable = true)
    private void shouldRenderGrid(Player player, BlockPos pos, BlockState state, ItemStack held, Set<GTToolType> toolTypes, CallbackInfoReturnable<Boolean> cir) {
        if (toolTypes.contains(GTToolType.WIRE_CUTTER)) {
            MetaMachine metaMachine = holder.getMetaMachine();
            if (metaMachine instanceof IGridConnectedMachine) cir.setReturnValue(true);
        }
    }

    @Unique
    private InteractionResult gTLCore$onWireCutterClick(ServerPlayer serverPlayer, InteractionHand hand, IGridConnectedMachine machine) {
        serverPlayer.swing(hand);
        if (holder.self().getPersistentData().getBoolean("isAllFacing")) {
            machine.getMainNode().setExposedOnSides(EnumSet.of(((MetaMachine) machine).getFrontFacing()));
            serverPlayer.sendSystemMessage(Component.translatable("gtlcore.me_front"), true);
            holder.self().getPersistentData().putBoolean("isAllFacing", false);
        } else {
            machine.getMainNode().setExposedOnSides(EnumSet.allOf(Direction.class));
            serverPlayer.sendSystemMessage(Component.translatable("gtlcore.me_any"), true);
            holder.self().getPersistentData().putBoolean("isAllFacing", true);
        }
        return InteractionResult.CONSUME;
    }

    @Inject(method = "onSoftMalletClick", at = @At(value = "HEAD"), cancellable = true, remap = false)
    public void onSoftMalletClick(Player playerIn, InteractionHand hand, Direction gridSide, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        if (isRemote()) return;
        if (!playerIn.isShiftKeyDown()) {
            gtlcore$setSuspendAfterFinish(false);
            return;
        }
        var controllable = GTCapabilityHelper.getControllable(getLevel(), getPos(), gridSide);
        if (controllable == null) return;
        if (!controllable.isWorkingEnabled() || gtlcore$isSuspendAfterFinish()) {
            cir.cancel();
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        gtlcore$setSuspendAfterFinish(true);
        playerIn.sendSystemMessage(Component.translatable("gtlcore.behaviour.soft_hammer.idle_after_cycle"));
        playerIn.swing(hand);
        cir.cancel();
        cir.setReturnValue(InteractionResult.CONSUME);
    }

    @Override
    @Unique
    public boolean gtlcore$isSuspendAfterFinish() {
        return gtlcore$suspendAfterFinish;
    }

    @Override
    @Unique
    public void gtlcore$setSuspendAfterFinish(boolean suspendAfterFinish) {
        gtlcore$suspendAfterFinish = suspendAfterFinish;
        if (isRemote()) return;
        onChanged();
    }
}
