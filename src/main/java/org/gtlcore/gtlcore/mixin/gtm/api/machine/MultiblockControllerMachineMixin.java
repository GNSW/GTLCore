package org.gtlcore.gtlcore.mixin.gtm.api.machine;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.api.pattern.PatternRetryController;
import org.gtlcore.gtlcore.api.pattern.PatternRetryIndex;
import org.gtlcore.gtlcore.api.pattern.PatternRetryState;
import org.gtlcore.gtlcore.api.pattern.WorldPatternTiming;
import org.gtlcore.gtlcore.config.ConfigHolder;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.pattern.MultiblockWorldSavedData;
import com.gregtechceu.gtceu.api.pattern.error.PatternError;

import net.minecraft.core.BlockPos;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;

/**
 * 代码参考自gto
 * &#064;line <a href="https://github.com/GregTech-Odyssey/GTOCore">...</a>
 */

@Mixin(MultiblockControllerMachine.class)
public abstract class MultiblockControllerMachineMixin extends MetaMachine implements IMultiController, PatternRetryController {

    @Shadow(remap = false)
    protected boolean isFormed;

    @Unique
    private final AtomicBoolean gtlcore$formationCheckQueued = new AtomicBoolean();

    @Unique
    private final PatternRetryState gtlcore$patternRetry = new PatternRetryState();

    @Shadow(remap = false)
    @Final
    private List<IMultiPart> parts;

    @Shadow(remap = false)
    private BlockPos[] partPositions;

    @Unique
    private volatile boolean gtlcore$structureUpdateInProgress;

    @Unique
    private volatile String gtlcore$structureStage = "idle";

    @Unique
    private boolean gtlcore$pendingStructureCheck;

    @Unique
    private boolean gtlcore$pendingControllerRemoval;

    @Unique
    private boolean gtlcore$structureRecoveryFailed;

    @Unique
    private final AtomicBoolean gtlcore$reportedOffThreadParts = new AtomicBoolean();

    @Unique
    private final AtomicBoolean gtlcore$reportedPartsReentry = new AtomicBoolean();

    @Override
    public PatternRetryState gtlcore$getPatternRetryState() {
        return gtlcore$patternRetry;
    }

    @Inject(method = { "onRotated", "setUpwardsFacing" }, at = @At("RETURN"), remap = false)
    private void gtlcore$refreshRetryBounds(CallbackInfo ci) {
        if (getLevel() instanceof ServerLevel level) PatternRetryIndex.get(level).refresh(this);
    }

    @Shadow(remap = false)
    public abstract Lock getPatternLock();

    @Shadow(remap = false)
    public abstract void setFlipped(boolean isFlipped);

    public MultiblockControllerMachineMixin(IMachineBlockEntity holder) {
        super(holder);
    }

    // Diagnostic copies of the GTCEu 1.4.4 interface defaults. Keep lock/check/unlock order unchanged.
    @Override
    public boolean checkPatternWithLock() {
        try (var timing = WorldPatternTiming.beginCheck(this, "lock")) {
            Lock lock = getPatternLock();
            long lockStart = timing == null ? 0L : System.nanoTime();
            lock.lock();
            boolean matched;
            try {
                if (timing != null) timing.lockResult(lockStart, true);
                matched = checkPattern();
            } finally {
                lock.unlock();
            }
            if (timing != null) timing.result(matched);
            return matched;
        }
    }

    @Override
    public boolean checkPatternWithTryLock() {
        try (var timing = WorldPatternTiming.beginCheck(this, "try_lock")) {
            Lock lock = getPatternLock();
            long lockStart = timing == null ? 0L : System.nanoTime();
            boolean acquired = lock.tryLock();
            if (timing != null) timing.lockResult(lockStart, acquired);
            if (acquired) {
                boolean matched;
                try {
                    matched = checkPattern();
                } finally {
                    lock.unlock();
                }
                if (timing != null) timing.result(matched);
                return matched;
            }
            if (timing != null) timing.result(false);
            return false;
        }
    }

    /**
     * @author GTLCore
     * @reason Check and commit formation together using current server-thread world state.
     */
    @Overwrite(remap = false)
    public void asyncCheckPattern(long periodID) {
        if ((getHolder().getOffset() + periodID) % PatternRetryState.NORMAL_PERIODS != 0) return;
        gtlcore$queueFormationCheck(periodID, false, false);
    }

    @Override
    public void gtlcore$queueFormationCheck(long periodID, boolean eager, boolean recheckFormed) {
        if (!(getLevel() instanceof ServerLevel serverLevel) ||
                !gtlcore$patternRetry.shouldCheck(periodID, eager) ||
                !gtlcore$formationCheckQueued.compareAndSet(false, true))
            return;

        try {
            serverLevel.getServer().execute(() -> {
                try {
                    if (isInValid() || getLevel() != serverLevel || !gtlcore$patternRetry.isActive() ||
                            gtlcore$structureRecoveryFailed)
                        return;
                    Lock lock = getPatternLock();
                    if (!lock.tryLock()) return;
                    try {
                        if (gtlcore$structureUpdateInProgress) {
                            gtlcore$deferStructureCheck(serverLevel, false);
                            return;
                        }
                        if (isFormed && !getMultiblockState().hasError() && !recheckFormed) return;
                        PatternRetryIndex.get(serverLevel).watch(this);
                        long revision = gtlcore$patternRetry.begin(eager);
                        try {
                            if (gtlcore$updateStructure(serverLevel, false, "formation")) gtlcore$patternRetry.formed();
                            else gtlcore$patternRetry.failed(periodID, revision);
                        } catch (RuntimeException exception) {
                            gtlcore$patternRetry.failed(periodID, revision);
                            throw exception;
                        }
                    } finally {
                        lock.unlock();
                    }
                } finally {
                    gtlcore$formationCheckQueued.set(false);
                }
            });
        } catch (RuntimeException | Error exception) {
            gtlcore$formationCheckQueued.set(false);
            throw exception;
        }
    }

    @Override
    public void gtlcore$onStructureBlockChanged(boolean controllerRemoved) {
        if (!(getLevel() instanceof ServerLevel level)) return;
        if (!level.getServer().isSameThread()) {
            level.getServer().execute(() -> {
                if (getLevel() == level) gtlcore$onStructureBlockChanged(controllerRemoved);
            });
            return;
        }
        if (gtlcore$structureRecoveryFailed || (isInValid() && !controllerRemoved)) return;
        if (gtlcore$structureUpdateInProgress) {
            gtlcore$deferStructureCheck(level, controllerRemoved);
            return;
        }
        Lock lock = getPatternLock();
        lock.lock();
        try {
            if (!controllerRemoved && !isFormed) {
                MultiblockWorldSavedData.getOrCreate(level).addAsyncLogic(this);
                gtlcore$patternRetry.wake(false);
                return;
            }
            gtlcore$updateStructure(level, controllerRemoved, "block_change");
        } finally {
            lock.unlock();
        }
    }

    /** Both callers hold the pattern lock on the server thread until mapping/registration are committed. */
    @Unique
    private boolean gtlcore$updateStructure(ServerLevel level, boolean controllerRemoved, String reason) {
        var state = getMultiblockState();
        var mwsd = MultiblockWorldSavedData.getOrCreate(level);
        boolean previouslyFormed = isFormed;
        gtlcore$structureUpdateInProgress = true;
        try {
            gtlcore$structureStage = "match";
            boolean matched = false;
            if (!controllerRemoved) {
                try (var timing = WorldPatternTiming.beginCheck(this, reason)) {
                    matched = checkPattern();
                    if (timing != null) timing.result(matched);
                }
            }
            if (!matched) {
                gtlcore$structureStage = "invalidate";
                if (previouslyFormed || controllerRemoved) {
                    onStructureInvalid();
                    setFlipped(false);
                    mwsd.removeMapping(state);
                }
                if (controllerRemoved) mwsd.removeAsyncLogic(this);
                else if (!gtlcore$patternRetry.isActive()) mwsd.addAsyncLogic(this);
                return false;
            }
            setFlipped(state.isNeededFlip());
            gtlcore$structureStage = "form";
            onStructureFormed();
            gtlcore$structureStage = "commit";
            // Subclasses may reject a matched structure, for example when energy IO is missing.
            if (!isFormed()) {
                mwsd.removeMapping(state);
                mwsd.addAsyncLogic(this);
                return false;
            }
            if (previouslyFormed) mwsd.removeMapping(state);
            mwsd.addMapping(state);
            mwsd.removeAsyncLogic(this);
            return true;
        } catch (RuntimeException exception) {
            String failedStage = gtlcore$structureStage;
            PatternRetryIndex.get(level).formationException();
            gtlcore$structureStage = "failure_cleanup";
            try {
                if (previouslyFormed || !"match".equals(failedStage)) onStructureInvalid();
            } catch (RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
                // Unknown part ownership must not be reused by another automatic formation attempt.
                gtlcore$structureRecoveryFailed = true;
            } finally {
                isFormed = false;
                setFlipped(false);
                state.setError(new PatternError());
                mwsd.removeMapping(state);
                if (controllerRemoved || gtlcore$structureRecoveryFailed) mwsd.removeAsyncLogic(this);
                else mwsd.addAsyncLogic(this);
                gtlcore$pendingStructureCheck = false;
                gtlcore$pendingControllerRemoval = false;
            }
            GTLCore.LOGGER.error("[Multiblock Formation] machine={} dim={} pos={} stage={} retry_suspended={}",
                    getClass().getName(), level.dimension().location(), getPos(), failedStage,
                    gtlcore$structureRecoveryFailed, exception);
            throw exception;
        } finally {
            gtlcore$structureStage = "idle";
            gtlcore$structureUpdateInProgress = false;
            if (gtlcore$pendingStructureCheck) {
                boolean removed = gtlcore$pendingControllerRemoval;
                gtlcore$pendingStructureCheck = false;
                gtlcore$pendingControllerRemoval = false;
                // execute() can run inline on the server thread. tell() prevents recursive formation.
                level.getServer().tell(new TickTask(level.getServer().getTickCount(), () -> {
                    if (getLevel() == level) gtlcore$onStructureBlockChanged(removed);
                }));
            }
        }
    }

    @Unique
    private void gtlcore$deferStructureCheck(ServerLevel level, boolean controllerRemoved) {
        if (!gtlcore$pendingStructureCheck) PatternRetryIndex.get(level).deferredRecheck();
        gtlcore$pendingStructureCheck = true;
        gtlcore$pendingControllerRemoval |= controllerRemoved;
    }

    @Inject(method = { "onStructureFormed", "onStructureInvalid", "onPartUnload", "onPartsUpdated" },
            at = @At("HEAD"),
            remap = false)
    private void gtlcore$diagnosePartMutation(CallbackInfo ci) {
        if (!ConfigHolder.INSTANCE.diagnostics.multiblockRetryLogging || !(getLevel() instanceof ServerLevel level)) return;
        if (!level.getServer().isSameThread()) gtlcore$reportPartsAccess(level, "off_thread_mutation", gtlcore$reportedOffThreadParts);
    }

    @Inject(method = { "onPartUnload", "onPartsUpdated" }, at = @At("HEAD"), remap = false)
    private void gtlcore$diagnosePartReentry(CallbackInfo ci) {
        if (!ConfigHolder.INSTANCE.diagnostics.multiblockRetryLogging || !gtlcore$structureUpdateInProgress ||
                !(getLevel() instanceof ServerLevel level))
            return;
        gtlcore$reportPartsAccess(level, "parts_changed_during_structure_update", gtlcore$reportedPartsReentry);
    }

    @Inject(method = "getParts", at = @At("HEAD"), remap = false)
    private void gtlcore$diagnosePartsRebuild(CallbackInfoReturnable<List<IMultiPart>> cir) {
        if (!ConfigHolder.INSTANCE.diagnostics.multiblockRetryLogging || !(getLevel() instanceof ServerLevel level) ||
                parts.size() == partPositions.length)
            return;
        if (!level.getServer().isSameThread()) {
            gtlcore$reportPartsAccess(level, "off_thread_parts_rebuild", gtlcore$reportedOffThreadParts);
        } else if (gtlcore$structureUpdateInProgress) {
            gtlcore$reportPartsAccess(level, "parts_rebuild_during_structure_update", gtlcore$reportedPartsReentry);
        }
    }

    @Unique
    private void gtlcore$reportPartsAccess(ServerLevel level, String operation, AtomicBoolean reported) {
        if (!reported.compareAndSet(false, true)) return;
        GTLCore.LOGGER.warn("[Multiblock Parts] machine={} dim={} pos={} operation={} stage={} thread={}",
                getClass().getName(), level.dimension().location(), getPos(), operation, gtlcore$structureStage,
                Thread.currentThread().getName(), new IllegalStateException("Part list mutation diagnostic"));
    }
}
