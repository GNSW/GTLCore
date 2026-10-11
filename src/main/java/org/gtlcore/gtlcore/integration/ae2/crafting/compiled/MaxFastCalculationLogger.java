package org.gtlcore.gtlcore.integration.ae2.crafting.compiled;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.config.ConfigHolder;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import appeng.core.AppEng;
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
import java.util.concurrent.atomic.AtomicLong;

public final class MaxFastCalculationLogger {

    private static final String LOGGER_NAME = "gtlcore.max_fast.calculation";
    private static final String APPENDER_NAME_PREFIX = "GTLCoreMaxFastCalculation-";
    private static final String MINECRAFT_LOG_DIRECTORY = "logs";
    private static final String LOG_DIRECTORY = "gtlcore";
    private static final String LOG_FILE_PREFIX = "max-fast-calculations-";
    private static final String LOG_FILE_EXTENSION = ".log";
    private static final String ROLLOVER_FILE_SUFFIX = "-%i.log.gz";
    private static final String TIMESTAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss-SSS";
    private static final String LOG_LAYOUT = "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %msg%n";
    private static final String ROLLOVER_SIZE = "16 MB";
    private static final String ROLLOVER_FILE_COUNT = "4";
    private static final String UNKNOWN_ENVIRONMENT_VALUE = "unknown";

    private static final Object INITIALIZATION_LOCK = new Object();
    private static final AtomicLong CALCULATION_SEQUENCE = new AtomicLong();
    private static volatile Logger logger;
    private static volatile String activeAppenderName;
    private static volatile boolean initializationFailed;

    private MaxFastCalculationLogger() {}

    static long nextCalculationId() {
        return CALCULATION_SEQUENCE.incrementAndGet();
    }

    static boolean isEnabled() {
        return ConfigHolder.INSTANCE == null || ConfigHolder.INSTANCE.diagnostics.debugLogging.enableMaxFastCalculationLogging;
    }

    public static void info(String message, Object... arguments) {
        if (!isEnabled()) {
            return;
        }
        try {
            Logger dedicatedLogger = getLogger();
            if (dedicatedLogger != null) {
                dedicatedLogger.info(message, arguments);
                return;
            }
        } catch (Throwable exception) {
            disableDedicatedLogger(exception);
        }
        try {
            GTLCore.LOGGER.info(message, arguments);
        } catch (Throwable ignored) {
            // Diagnostic logging must never interrupt a crafting calculation.
        }
    }

    private static void disableDedicatedLogger(Throwable exception) {
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
            } catch (Throwable cleanupException) {
                try {
                    exception.addSuppressed(cleanupException);
                } catch (Throwable ignored) {
                    // Preserve the original logging failure when suppression is unavailable.
                }
            } finally {
                logger = null;
                activeAppenderName = null;
                initializationFailed = true;
            }
        }
        try {
            GTLCore.LOGGER.error("Dedicated MAX_FAST calculation log failed; falling back to the main log", exception);
        } catch (Throwable ignored) {
            // There is no remaining logging backend to report this failure through.
        }
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
                throw new IllegalStateException("Log4j2 did not create the MAX_FAST rolling file appender");
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
            dedicatedLogger.info(
                    "[MAX_FAST] log_file={} rollover_size={} rollover_files={}",
                    logFile.toAbsolutePath(),
                    ROLLOVER_SIZE,
                    ROLLOVER_FILE_COUNT);
            dedicatedLogger.info(
                    "[MAX_FAST] versions gtlcore={} ae2={}",
                    getModVersion(GTLCore.MOD_ID),
                    getModVersion(AppEng.MOD_ID));
            Runtime runtime = Runtime.getRuntime();
            dedicatedLogger.info(
                    "[MAX_FAST] environment available_processors={} max_heap_bytes={} java_version={} " +
                            "java_vendor={} java_vm_name={} java_vm_version={} os_name={} os_version={} os_arch={}",
                    runtime.availableProcessors(),
                    runtime.maxMemory(),
                    getEnvironmentProperty("java.version"),
                    getEnvironmentProperty("java.vendor"),
                    getEnvironmentProperty("java.vm.name"),
                    getEnvironmentProperty("java.vm.version"),
                    getEnvironmentProperty("os.name"),
                    getEnvironmentProperty("os.version"),
                    getEnvironmentProperty("os.arch"));
            return dedicatedLogger;
        } catch (Throwable exception) {
            if (configuration != null) {
                configuration.removeLogger(LOGGER_NAME);
            }
            if (appender != null) {
                appender.stop();
            }
            activeAppenderName = null;
            initializationFailed = true;
            GTLCore.LOGGER.error("Failed to initialize dedicated MAX_FAST calculation log", exception);
            return null;
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

    private static String getEnvironmentProperty(String propertyName) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            return UNKNOWN_ENVIRONMENT_VALUE;
        }
        return value.strip()
                .replace(' ', '_')
                .replace('\t', '_')
                .replace('\r', '_')
                .replace('\n', '_');
    }

    private static String getModVersion(String modId) {
        try {
            return ModList.get()
                    .getModContainerById(modId)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .filter(version -> !version.isBlank())
                    .orElse(UNKNOWN_ENVIRONMENT_VALUE);
        } catch (Throwable ignored) {
            return UNKNOWN_ENVIRONMENT_VALUE;
        }
    }
}
