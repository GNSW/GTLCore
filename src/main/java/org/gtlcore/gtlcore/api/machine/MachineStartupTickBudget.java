package org.gtlcore.gtlcore.api.machine;

import org.gtlcore.gtlcore.common.machine.multiblock.electric.TransfiniteComputationArrayMachine;
import org.gtlcore.gtlcore.config.ConfigHolder;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.feature.IGridConnectedMachine;

import net.minecraft.server.level.ServerLevel;

import java.util.Map;
import java.util.WeakHashMap;

public final class MachineStartupTickBudget {

    private static final Map<ServerLevel, LevelBudget> LEVEL_BUDGETS = new WeakHashMap<>();

    private MachineStartupTickBudget() {}

    public static boolean tryAcquire(ServerLevel level, MetaMachine machine) {
        ConfigHolder config = ConfigHolder.INSTANCE;
        if (config != null && !config.performance.enableMachineStartupTickBudget) {
            return true;
        }

        int normalBudget = config == null ? ConfigHolder.DEFAULT_MACHINE_STARTUP_TICK_BUDGET_PER_LEVEL :
                config.performance.machineStartupTickBudgetPerLevel;
        int aeBudget = config == null ? ConfigHolder.DEFAULT_MACHINE_STARTUP_AE_TICK_BUDGET_PER_LEVEL :
                config.performance.machineStartupAeTickBudgetPerLevel;
        int timeBudgetMillis = config == null ? ConfigHolder.DEFAULT_MACHINE_STARTUP_TICK_TIME_BUDGET_MILLIS :
                config.performance.machineStartupTickTimeBudgetMillis;
        synchronized (LEVEL_BUDGETS) {
            LevelBudget levelBudget = LEVEL_BUDGETS.computeIfAbsent(level, ignored -> new LevelBudget());
            levelBudget.beginTick(level, level.getGameTime(), normalBudget, aeBudget, timeBudgetMillis);
            if (machine instanceof TransfiniteComputationArrayMachine) {
                levelBudget.critical++;
                return true;
            }
            if (levelBudget.elapsedNanos >= levelBudget.timeBudgetNanos) {
                if (machine instanceof IGridConnectedMachine) {
                    levelBudget.aeTimeDeferred++;
                } else {
                    levelBudget.normalTimeDeferred++;
                }
                return false;
            }
            if (machine instanceof IGridConnectedMachine) {
                if (levelBudget.aeAdmitted < aeBudget) {
                    levelBudget.aeAdmitted++;
                    return true;
                }
                levelBudget.aeDeferred++;
                return false;
            }
            if (levelBudget.normalAdmitted < normalBudget) {
                levelBudget.normalAdmitted++;
                return true;
            }
            levelBudget.normalDeferred++;
            return false;
        }
    }

    public static void recordExecution(ServerLevel level, long elapsedNanos) {
        synchronized (LEVEL_BUDGETS) {
            LevelBudget levelBudget = LEVEL_BUDGETS.get(level);
            if (levelBudget != null) {
                levelBudget.elapsedNanos += elapsedNanos;
            }
        }
    }

    private static final class LevelBudget {

        private long gameTime = Long.MIN_VALUE;
        private int normalBudget;
        private int aeBudget;
        private int normalAdmitted;
        private int normalDeferred;
        private int aeAdmitted;
        private int aeDeferred;
        private int critical;
        private long timeBudgetNanos;
        private long elapsedNanos;
        private int normalTimeDeferred;
        private int aeTimeDeferred;

        private void beginTick(ServerLevel level, long currentGameTime, int currentNormalBudget, int currentAeBudget,
                               int currentTimeBudgetMillis) {
            if (gameTime == currentGameTime) {
                return;
            }
            if (gameTime != Long.MIN_VALUE &&
                    (normalAdmitted > 0 || normalDeferred > 0 || aeAdmitted > 0 || aeDeferred > 0 || critical > 0)) {

            }
            gameTime = currentGameTime;
            normalBudget = currentNormalBudget;
            aeBudget = currentAeBudget;
            timeBudgetNanos = currentTimeBudgetMillis * 1_000_000L;
            normalAdmitted = 0;
            normalDeferred = 0;
            aeAdmitted = 0;
            aeDeferred = 0;
            critical = 0;
            elapsedNanos = 0;
            normalTimeDeferred = 0;
            aeTimeDeferred = 0;
        }
    }
}
