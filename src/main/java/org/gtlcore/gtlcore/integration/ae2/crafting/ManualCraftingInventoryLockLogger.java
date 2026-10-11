package org.gtlcore.gtlcore.integration.ae2.crafting;

import org.gtlcore.gtlcore.config.ConfigHolder;

import net.minecraftforge.fml.loading.FMLPaths;

import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy;
import org.apache.logging.log4j.core.appender.rolling.SizeBasedTriggeringPolicy;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.StringJoiner;

public final class ManualCraftingInventoryLockLogger {

    private static final String LOGGER_NAME = "gtlcore.ae2.manual_crafting.inventory_lock";
    private static final String APPENDER_NAME_PREFIX = "GTLCoreManualCraftingInventoryLock-";
    private static final String MINECRAFT_LOG_DIRECTORY = "logs";
    private static final String LOG_DIRECTORY = "gtlcore";
    private static final String LOG_FILE_PREFIX = "ae2-manual-crafting-inventory-lock-";
    private static final String LOG_FILE_EXTENSION = ".log";
    private static final String ROLLOVER_FILE_SUFFIX = "-%i.log.gz";
    private static final String TIMESTAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss-SSS";
    private static final String LOG_LAYOUT = "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %msg%n";
    private static final String ROLLOVER_SIZE = "16 MB";
    private static final String ROLLOVER_FILE_COUNT = "4";

    private static final Object INITIALIZATION_LOCK = new Object();
    private static volatile Logger logger;
    private static volatile String activeAppenderName;
    private static volatile boolean initializationFailed;

    private ManualCraftingInventoryLockLogger() {}

    public static boolean isEnabled() {
        return ConfigHolder.INSTANCE != null &&
                ConfigHolder.INSTANCE.diagnostics.debugLogging.enableAe2ManualCraftingInventoryLockLogging;
    }

    public static void acquired(long reservationId, MEStorage storage, KeyCounter amounts, IActionSource source) {
        if (!isEnabled()) return;
        info("event=ACQUIRED reservation={} storage={} source={} keys={} amounts={}",
                reservationId, storageId(storage), sourceDescription(source), amounts.size(), summarize(amounts));
    }

    public static void conflict(MEStorage storage, AEKey what, long requested, long available,
                                long alreadyReserved, IActionSource source) {
        if (!isEnabled()) return;
        info("event=CONFLICT storage={} source={} key={} requested={} availableToReserve={} alreadyReserved={}",
                storageId(storage), sourceDescription(source), what, requested, available, alreadyReserved);
    }

    public static void extractionLimited(MEStorage storage, AEKey what, long requested, long allowed,
                                         long physicalAvailable, long reservedForOthers, IActionSource source) {
        if (!isEnabled()) return;
        info("event=EXTRACTION_LIMITED storage={} key={} requested={} allowed={} blocked={} physicalAvailable={} reservedForOthers={} source={}",
                storageId(storage), what, requested, allowed, requested - allowed, physicalAvailable,
                reservedForOthers, sourceDescription(source));
    }

    public static void submitted(long reservationId, ICraftingSubmitResult result) {
        if (!isEnabled()) return;
        info("event=SUBMITTED reservation={} successful={} error={} detail={}",
                reservationId, result.successful(), result.errorCode(), result.errorDetail());
    }

    public static void submissionFailed(long reservationId, Throwable exception) {
        if (!isEnabled()) return;
        info("event=SUBMISSION_EXCEPTION reservation={} exception={}", reservationId, exception);
    }

    public static void released(long reservationId, MEStorage storage, KeyCounter amounts) {
        if (!isEnabled()) return;
        info("event=RELEASED reservation={} storage={} keys={} amounts={}",
                reservationId, storageId(storage), amounts.size(), summarize(amounts));
    }

    private static void info(String message, Object... arguments) {
        if (!isEnabled()) {
            return;
        }
        try {
            Logger dedicatedLogger = getLogger();
            if (dedicatedLogger != null) {
                dedicatedLogger.info(message, arguments);
            }
        } catch (Throwable ignored) {
            disableDedicatedLogger();
        }
    }

    private static String summarize(KeyCounter amounts) {
        if (!isEnabled()) {
            return "";
        }
        var summary = new StringJoiner(",", "[", "]");
        for (var entry : amounts) {
            summary.add(entry.getKey() + "=" + entry.getLongValue());
        }
        return summary.toString();
    }

    private static String storageId(MEStorage storage) {
        return Integer.toHexString(System.identityHashCode(storage));
    }

    private static String sourceDescription(IActionSource source) {
        return source == null ? "none" : source.player()
                .map(player -> player.getGameProfile().getName())
                .orElseGet(() -> source.machine().map(machine -> machine.getClass().getName()).orElse("unknown"));
    }

    private static Logger getLogger() {
        Logger currentLogger = logger;
        if (currentLogger != null && isDedicatedLoggerConfigured()) {
            return currentLogger;
        }
        if (initializationFailed) {
            return null;
        }

        synchronized (INITIALIZATION_LOCK) {
            if ((logger == null || !isDedicatedLoggerConfigured()) && !initializationFailed) {
                logger = null;
                activeAppenderName = null;
                logger = createLogger();
            }
            return logger;
        }
    }

    private static Logger createLogger() {
        Configuration configuration = null;
        RollingFileAppender appender = null;
        try {
            String timestamp = DateTimeFormatter
                    .ofPattern(TIMESTAMP_PATTERN, Locale.ROOT)
                    .format(LocalDateTime.now());
            Path logDirectory = FMLPaths.GAMEDIR.get().resolve(MINECRAFT_LOG_DIRECTORY).resolve(LOG_DIRECTORY);
            Files.createDirectories(logDirectory);
            Path logFile = logDirectory.resolve(LOG_FILE_PREFIX + timestamp + LOG_FILE_EXTENSION);
            String filePattern = logDirectory
                    .resolve(LOG_FILE_PREFIX + timestamp + ROLLOVER_FILE_SUFFIX)
                    .toString();

            if (!(LogManager.getContext(false) instanceof LoggerContext context)) {
                throw new IllegalStateException("Log4j2 core LoggerContext is unavailable");
            }

            configuration = context.getConfiguration();
            String appenderName = APPENDER_NAME_PREFIX + timestamp;
            PatternLayout layout = PatternLayout.newBuilder()
                    .withConfiguration(configuration)
                    .withCharset(StandardCharsets.UTF_8)
                    .withPattern(LOG_LAYOUT)
                    .build();
            DefaultRolloverStrategy rolloverStrategy = DefaultRolloverStrategy.newBuilder()
                    .withConfig(configuration)
                    .withMax(ROLLOVER_FILE_COUNT)
                    .build();
            appender = RollingFileAppender.newBuilder()
                    .setConfiguration(configuration)
                    .setName(appenderName)
                    .setLayout(layout)
                    .setIgnoreExceptions(false)
                    .withFileName(logFile.toString())
                    .withFilePattern(filePattern)
                    .withAppend(true)
                    .withCreateOnDemand(false)
                    .withPolicy(SizeBasedTriggeringPolicy.createPolicy(ROLLOVER_SIZE))
                    .withStrategy(rolloverStrategy)
                    .build();
            if (appender == null) {
                throw new IllegalStateException("Log4j2 did not create the inventory lock rolling file appender");
            }

            appender.start();
            configuration.addAppender(appender);
            configuration.removeLogger(LOGGER_NAME);
            LoggerConfig loggerConfig = new LoggerConfig(LOGGER_NAME, Level.INFO, false);
            loggerConfig.addAppender(appender, Level.INFO, null);
            configuration.addLogger(LOGGER_NAME, loggerConfig);
            context.updateLoggers();

            Logger dedicatedLogger = LogManager.getLogger(LOGGER_NAME);
            activeAppenderName = appenderName;
            dedicatedLogger.info("event=LOGGER_STARTED file={} rolloverSize={} rolloverFiles={}",
                    logFile.toAbsolutePath(), ROLLOVER_SIZE, ROLLOVER_FILE_COUNT);
            return dedicatedLogger;
        } catch (Throwable ignored) {
            if (configuration != null) {
                configuration.removeLogger(LOGGER_NAME);
            }
            if (appender != null) {
                appender.stop();
            }
            activeAppenderName = null;
            initializationFailed = true;
            return null;
        }
    }

    private static void disableDedicatedLogger() {
        synchronized (INITIALIZATION_LOCK) {
            try {
                if (LogManager.getContext(false) instanceof LoggerContext context) {
                    Configuration configuration = context.getConfiguration();
                    LoggerConfig loggerConfig = configuration.getLoggers().get(LOGGER_NAME);
                    if (loggerConfig != null) {
                        loggerConfig.getAppenders().values().forEach(appender -> appender.stop());
                        configuration.removeLogger(LOGGER_NAME);
                        context.updateLoggers();
                    }
                }
            } catch (Throwable ignored) {
                // Diagnostics must never interrupt inventory extraction or crafting submission.
            } finally {
                logger = null;
                activeAppenderName = null;
                initializationFailed = true;
            }
        }
    }

    private static boolean isDedicatedLoggerConfigured() {
        String appenderName = activeAppenderName;
        if (appenderName == null || !(LogManager.getContext(false) instanceof LoggerContext context)) {
            return false;
        }
        LoggerConfig loggerConfig = context.getConfiguration().getLoggers().get(LOGGER_NAME);
        return loggerConfig != null && !loggerConfig.isAdditive() &&
                loggerConfig.getAppenders().containsKey(appenderName);
    }
}
