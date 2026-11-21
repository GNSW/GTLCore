package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.config.StorageGraphMode;

import java.util.List;

public final class StorageGraphDiagnostics {

    private static final long MIN_INTERVAL_NANOS = 1_000_000_000L;
    private static long lastLogNanos;
    private static long lastTopologyLogNanos;
    private static long lastAvailableLogNanos;
    private static long lastSourcesLogNanos;
    private static long lastMountsLogNanos;
    private static long lastSimulateLogNanos;
    private static long lastInsertLogNanos;
    private static long lastFallbackLogNanos;
    private static long lastIncompleteLogNanos;

    private StorageGraphDiagnostics() {}

    public static void logSnapshot(StorageGraphSnapshot snapshot) {
        if (snapshot == null || !isLoggingEnabled() || !isShadowMode()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastLogNanos < MIN_INTERVAL_NANOS) {
            return;
        }
        lastLogNanos = now;
        GTLCore.LOGGER.info(
                "[StorageGraph] epoch={} policy={} rawMounts={} uniqueSources={} aliasGroups={} opaqueSources={} complete={}",
                snapshot.topologyEpoch(), snapshot.policyEpoch(), snapshot.rawMountCount(),
                snapshot.uniqueSourceCount(), snapshot.aliasGroupCount(), snapshot.opaqueSourceCount(),
                snapshot.complete());
    }

    public static void logTopologyChange(long epoch) {
        if (!isLoggingEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastTopologyLogNanos < MIN_INTERVAL_NANOS) {
            return;
        }
        lastTopologyLogNanos = now;
        GTLCore.LOGGER.info("[StorageGraph] topology changed epoch={}", epoch);
    }

    public static void logPolicyChange(long epoch) {
        if (!isLoggingEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastTopologyLogNanos < MIN_INTERVAL_NANOS) {
            return;
        }
        lastTopologyLogNanos = now;
        GTLCore.LOGGER.info("[StorageGraph] policy changed epoch={}", epoch);
    }

    public static void logAvailableStacks(int legacyKeys, int uniqueKeys, long legacyTotal, long uniqueTotal,
                                          int mismatchedKeys, long legacyBefore, long legacyAfter, String perSource) {
        if (!isLoggingEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastAvailableLogNanos < MIN_INTERVAL_NANOS) {
            return;
        }
        lastAvailableLogNanos = now;
        GTLCore.LOGGER.info(
                "[StorageGraph] availableStacks legacyKeys={} uniqueKeys={} legacyTotal={} uniqueTotal={} mismatchedKeys={} legacyBefore={} legacyAfter={} perSource=[{}]",
                legacyKeys, uniqueKeys, legacyTotal, uniqueTotal, mismatchedKeys, legacyBefore, legacyAfter, perSource);
    }

    public static void logSources(List<Source> sources) {
        if (!isLoggingEnabled() || !isShadowMode()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastSourcesLogNanos < MIN_INTERVAL_NANOS) {
            return;
        }
        lastSourcesLogNanos = now;
        int limit = Math.min(8, sources.size());
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            Source source = sources.get(i);
            if (i > 0) {
                shown.append(", ");
            }
            shown.append(source.kind()).append(':')
                    .append(source.physical() == null ? "null" : source.physical().getClass().getName())
                    .append('@').append(Integer.toHexString((int) source.identityHash()));
        }
        GTLCore.LOGGER.info("[StorageGraph] sources={} shown=[{}]", sources.size(), shown);
    }

    public static void logMounts(String mounts) {
        if (!isLoggingEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastMountsLogNanos < MIN_INTERVAL_NANOS) {
            return;
        }
        lastMountsLogNanos = now;
        GTLCore.LOGGER.info("[StorageGraph] mounts=[{}]", mounts);
    }

    public static void logSimulate(long legacy, long unique, int totalSources, int finiteSources,
                                   String what, long amount) {
        if (!isLoggingEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastSimulateLogNanos < MIN_INTERVAL_NANOS) {
            return;
        }
        lastSimulateLogNanos = now;
        GTLCore.LOGGER.info("[StorageGraph] simulate what={} amount={} legacy={} unique={} sources={} finite={}",
                what, amount, legacy, unique, totalSources, finiteSources);
    }

    public static void logSimulateInsert(long legacy, long unique, int totalSources, int finiteSources,
                                         String what, long amount) {
        if (!isLoggingEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastInsertLogNanos < MIN_INTERVAL_NANOS) {
            return;
        }
        lastInsertLogNanos = now;
        GTLCore.LOGGER.info("[StorageGraph] insert what={} amount={} legacy={} unique={} sources={} finite={}",
                what, amount, legacy, unique, totalSources, finiteSources);
    }

    public static void logCompatFallback(int uniqueSources, int opaqueSources) {
        if (!isLoggingEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastFallbackLogNanos < MIN_INTERVAL_NANOS) {
            return;
        }
        lastFallbackLogNanos = now;
        GTLCore.LOGGER.info("[StorageGraph] compat fallback uniqueSources={} opaqueSources={}",
                uniqueSources, opaqueSources);
    }

    public static void logIncomplete(StorageGraphSnapshot snapshot) {
        if (snapshot == null || !isLoggingEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastIncompleteLogNanos < MIN_INTERVAL_NANOS) {
            return;
        }
        lastIncompleteLogNanos = now;
        GTLCore.LOGGER.info(
                "[StorageGraph] incomplete graph sources={} edges={} rawMounts={}; falling back to native AE2",
                snapshot.sources().size(), snapshot.edges().size(), snapshot.rawMountCount());
    }

    public static void resetThrottle() {
        lastLogNanos = 0L;
        lastTopologyLogNanos = 0L;
        lastAvailableLogNanos = 0L;
        lastSourcesLogNanos = 0L;
        lastMountsLogNanos = 0L;
        lastSimulateLogNanos = 0L;
        lastInsertLogNanos = 0L;
        lastFallbackLogNanos = 0L;
        lastIncompleteLogNanos = 0L;
    }

    private static boolean isLoggingEnabled() {
        ConfigHolder config = ConfigHolder.INSTANCE;
        return config != null && config.ae.ae2StorageGraphMode != StorageGraphMode.OFF &&
                config.ae.ae2StorageGraphLogging;
    }

    private static boolean isShadowMode() {
        ConfigHolder config = ConfigHolder.INSTANCE;
        return config != null && config.ae.ae2StorageGraphMode == StorageGraphMode.SHADOW;
    }
}
