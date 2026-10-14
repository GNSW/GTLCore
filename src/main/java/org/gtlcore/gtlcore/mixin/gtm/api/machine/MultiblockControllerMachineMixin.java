package org.gtlcore.gtlcore.mixin.gtm.api.machine;

import org.gtlcore.gtlcore.api.pattern.WorldPatternTiming;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.pattern.MultiblockWorldSavedData;

import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.locks.Lock;

/**
 * 代码参考自gto
 * &#064;line <a href="https://github.com/GregTech-Odyssey/GTOCore">...</a>
 */

@Mixin(MultiblockControllerMachine.class)
public abstract class MultiblockControllerMachineMixin extends MetaMachine implements IMultiController {

    @Shadow(remap = false)
    protected boolean isFormed;

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
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public void asyncCheckPattern(long periodID) {
        if ((getMultiblockState().hasError() || !isFormed) && (getHolder().getOffset() + periodID) % 4 == 0 && checkPatternWithTryLock()) {
            if (getLevel() instanceof ServerLevel serverLevel) {
                serverLevel.getServer().execute(() -> {
                    getPatternLock().lock();
                    try {
                        setFlipped(getMultiblockState().isNeededFlip());
                        onStructureFormed();
                        var mwsd = MultiblockWorldSavedData.getOrCreate(serverLevel);
                        mwsd.addMapping(getMultiblockState());
                        mwsd.removeAsyncLogic(this);
                    } finally {
                        getPatternLock().unlock();
                    }
                });
            }
        }
    }
}
