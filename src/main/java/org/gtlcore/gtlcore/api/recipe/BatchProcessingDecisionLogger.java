package org.gtlcore.gtlcore.api.recipe;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.api.machine.trait.IBatchMachine;
import org.gtlcore.gtlcore.config.ConfigHolder;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import net.minecraftforge.fml.loading.FMLPaths;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

public final class BatchProcessingDecisionLogger {

    private static final String LOG_DIRECTORY = "logs/gtlcore/batch-processing";
    private static final String LOG_FILE_EXTENSION = ".jsonl";
    private static final String UNKNOWN = "unknown";
    private static final String TIMESTAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss-SSS";
    private static final String ENTRY_TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS";
    private static final int MAX_FILE_COMPONENT_LENGTH = 80;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN, Locale.ROOT);
    private static final DateTimeFormatter ENTRY_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern(ENTRY_TIMESTAMP_PATTERN, Locale.ROOT);
    private static final Map<MetaMachine, MachineLog> MACHINE_LOGS = Collections.synchronizedMap(new WeakHashMap<>());

    private BatchProcessingDecisionLogger() {}

    public static boolean shouldLog(MetaMachine machine) {
        ConfigHolder config = ConfigHolder.INSTANCE;
        return config != null && config.diagnostics.debugLogging.enableBatchProcessingLogging &&
                machine instanceof IBatchMachine batchMachine && batchMachine.isBatchEnabled();
    }

    public static void log(MetaMachine machine, GTRecipe recipe, Outcome outcome, Reason reason,
                           Eligibility eligibility, int timeLimitTicks, int timeLimitedCycles,
                           int amountLimitedCycles, int batchSize) {
        if (!shouldLog(machine)) return;

        try {
            MachineLog machineLog = getMachineLog(machine);
            if (machineLog.failed) return;

            synchronized (machineLog) {
                long gameTime = gameTime(machine);
                String recipeId = recipe.id.toString();
                if (machineLog.isDuplicate(gameTime, recipeId, outcome, reason, eligibility, batchSize)) return;

                JsonObject entry = new JsonObject();
                entry.addProperty("timestamp", ENTRY_TIMESTAMP_FORMATTER.format(LocalDateTime.now()));
                entry.addProperty("event", outcome.name());
                entry.addProperty("reason_key", reason.translationKey);
                entry.addProperty("reason_zh_cn", reason.zhCn);
                entry.addProperty("reason_en_us", reason.enUs);
                entry.addProperty("machine_id", machine.getDefinition().getId().toString());
                entry.addProperty("machine_name_key", machine.getDefinition().getDescriptionId());
                entry.addProperty("dimension", dimension(machine));
                entry.addProperty("position", machine.getPos().toShortString());
                entry.addProperty("game_time", gameTime);
                entry.addProperty("recipe_id", recipeId);
                entry.addProperty("recipe_duration_ticks", recipe.duration);
                entry.addProperty("real_parallels", IGTRecipe.of(recipe).getRealParallels());
                entry.addProperty("eligibility_path", eligibility.name());
                entry.addProperty("sub_tick_eligible", eligibility.isSubTickEligible());
                entry.addProperty("time_window_eligible", eligibility.isTimeWindowEligible());
                entry.addProperty("time_limit_ticks", timeLimitTicks);
                entry.addProperty("time_limited_cycles", timeLimitedCycles);
                entry.addProperty("amount_limited_cycles", amountLimitedCycles);
                entry.addProperty("batch_size", batchSize);

                Files.writeString(machineLog.path, GSON.toJson(entry) + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                machineLog.markLogged(gameTime, recipeId, outcome, reason, eligibility, batchSize);
            }
        } catch (Throwable exception) {
            disableMachineLog(machine, exception);
        }
    }

    private static MachineLog getMachineLog(MetaMachine machine) {
        synchronized (MACHINE_LOGS) {
            return MACHINE_LOGS.computeIfAbsent(machine, BatchProcessingDecisionLogger::createMachineLog);
        }
    }

    private static MachineLog createMachineLog(MetaMachine machine) {
        try {
            Path directory = FMLPaths.GAMEDIR.get().resolve(LOG_DIRECTORY);
            Files.createDirectories(directory);

            String timestamp = FILE_TIMESTAMP_FORMATTER.format(LocalDateTime.now());
            String machineName = sanitize(machine.getDefinition().getId().toString());
            String dimension = sanitize(dimension(machine));
            String position = sanitize(machine.getPos().toShortString());
            Path path = directory.resolve(String.join("-", timestamp, machineName, dimension, position) +
                    LOG_FILE_EXTENSION);
            return new MachineLog(path, false);
        } catch (Throwable exception) {
            GTLCore.LOGGER.error("Failed to initialize a batch-processing decision log for machine {} at {}",
                    machine.getDefinition().getId(), machine.getPos(), exception);
            return MachineLog.FAILED;
        }
    }

    private static void disableMachineLog(MetaMachine machine, Throwable exception) {
        synchronized (MACHINE_LOGS) {
            MACHINE_LOGS.put(machine, MachineLog.FAILED);
        }
        GTLCore.LOGGER.error("Batch-processing decision logging failed for machine {} at {}; disabling its log",
                machine.getDefinition().getId(), machine.getPos(), exception);
    }

    private static String dimension(MetaMachine machine) {
        return machine.getLevel() == null ? UNKNOWN : machine.getLevel().dimension().location().toString();
    }

    private static long gameTime(MetaMachine machine) {
        return machine.getLevel() == null ? -1 : machine.getLevel().getGameTime();
    }

    private static String sanitize(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.substring(0, Math.min(sanitized.length(), MAX_FILE_COMPONENT_LENGTH));
    }

    public enum Outcome {

        BATCH_TRIGGERED,
        BATCH_NOT_TRIGGERED
    }

    public enum Eligibility {

        NONE(false, false),
        SUB_TICK(true, false),
        TIME_WINDOW(false, true),
        SUB_TICK_AND_TIME_WINDOW(true, true);

        private final boolean subTickEligible;
        private final boolean timeWindowEligible;

        Eligibility(boolean subTickEligible, boolean timeWindowEligible) {
            this.subTickEligible = subTickEligible;
            this.timeWindowEligible = timeWindowEligible;
        }

        private boolean isSubTickEligible() {
            return subTickEligible;
        }

        private boolean isTimeWindowEligible() {
            return timeWindowEligible;
        }
    }

    public enum Reason {

        NO_ELIGIBLE_PATH(
                "log.gtlcore.batch_processing.reason.no_eligible_path",
                "配方既未进入亚 tick 并行路径，批处理时间窗也无法容纳多个周期",
                "The recipe is not sub-tick parallelized and the batch time window cannot fit multiple cycles"),
        UNSUPPORTED_MACHINE_MODE(
                "log.gtlcore.batch_processing.reason.unsupported_machine_mode",
                "当前机器模式不支持批处理",
                "The current machine mode does not support batch processing"),
        NOT_RECIPE_LOGIC_MACHINE(
                "log.gtlcore.batch_processing.reason.not_recipe_logic_machine",
                "机器未实现配方逻辑接口",
                "The machine does not implement the recipe-logic interface"),
        NON_POSITIVE_DURATION(
                "log.gtlcore.batch_processing.reason.non_positive_duration",
                "配方时长小于或等于零，只能执行一个周期",
                "The recipe duration is zero or negative, so only one cycle can run"),
        TIME_LIMIT_REJECTED(
                "log.gtlcore.batch_processing.reason.time_limit_rejected",
                "批处理时间上限不足以容纳一个配方周期",
                "The batch time limit cannot fit one recipe cycle"),
        TIME_LIMIT_FALLBACK_SINGLE_CYCLE(
                "log.gtlcore.batch_processing.reason.time_limit_fallback_single_cycle",
                "批处理时间窗小于单周期时长，回退为单周期执行",
                "The batch window is shorter than one recipe cycle; falling back to one normal cycle"),
        TIME_LIMIT_SINGLE_CYCLE(
                "log.gtlcore.batch_processing.reason.time_limit_single_cycle",
                "批处理时间上限只能容纳一个配方周期",
                "The batch time limit can fit only one recipe cycle"),
        AMOUNT_OVERFLOW(
                "log.gtlcore.batch_processing.reason.amount_overflow",
                "配方物品或流体数量无效或总量溢出 long 容量，批处理被中止",
                "An item or fluid amount is invalid or exceeds long capacity, so batching was aborted"),
        AMOUNT_LIMIT_SINGLE_CYCLE(
                "log.gtlcore.batch_processing.reason.amount_limit_single_cycle",
                "物品或流体总量上限只允许一个配方周期",
                "Item or fluid amount limits allow only one recipe cycle"),
        CAPACITY_REJECTED(
                "log.gtlcore.batch_processing.reason.capacity_rejected",
                "当前输入、输出或非标准配方能力无法支持一个完整批次",
                "Current inputs, outputs, or nonstandard recipe capabilities cannot support one full batch"),
        CAPACITY_SINGLE_CYCLE(
                "log.gtlcore.batch_processing.reason.capacity_single_cycle",
                "当前输入、输出或非标准配方能力只允许一个配方周期",
                "Current inputs, outputs, or nonstandard recipe capabilities allow only one recipe cycle"),
        MULTIPLE_CYCLES_AVAILABLE(
                "log.gtlcore.batch_processing.reason.multiple_cycles_available",
                "配方满足批处理资格，且时间、数量、输入与输出允许执行多个周期",
                "The recipe is batch eligible and time, amount, input, and output limits allow multiple cycles");

        private final String translationKey;
        private final String zhCn;
        private final String enUs;

        Reason(String translationKey, String zhCn, String enUs) {
            this.translationKey = translationKey;
            this.zhCn = zhCn;
            this.enUs = enUs;
        }
    }

    private static final class MachineLog {

        private static final MachineLog FAILED = new MachineLog(null, true);

        private final Path path;
        private final boolean failed;
        private long lastGameTime = Long.MIN_VALUE;
        private String lastRecipeId;
        private Outcome lastOutcome;
        private Reason lastReason;
        private Eligibility lastEligibility;
        private int lastBatchSize;

        private MachineLog(Path path, boolean failed) {
            this.path = path;
            this.failed = failed;
        }

        private boolean isDuplicate(long gameTime, String recipeId, Outcome outcome, Reason reason,
                                    Eligibility eligibility, int batchSize) {
            return lastGameTime == gameTime && recipeId.equals(lastRecipeId) && lastOutcome == outcome &&
                    lastReason == reason && lastEligibility == eligibility && lastBatchSize == batchSize;
        }

        private void markLogged(long gameTime, String recipeId, Outcome outcome, Reason reason,
                                Eligibility eligibility, int batchSize) {
            lastGameTime = gameTime;
            lastRecipeId = recipeId;
            lastOutcome = outcome;
            lastReason = reason;
            lastEligibility = eligibility;
            lastBatchSize = batchSize;
        }
    }
}
