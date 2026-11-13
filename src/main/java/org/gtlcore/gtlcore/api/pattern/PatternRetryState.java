package org.gtlcore.gtlcore.api.pattern;

import org.gtlcore.gtlcore.config.ConfigHolder;

/** Cross-thread scheduling state. World reads and listener-index mutations stay on the server thread. */
public final class PatternRetryState {

    // GTCEu 1.4.4's search executor runs every 250 ms; the existing controller cadence is four periods.
    public static final long NORMAL_PERIODS = 4L;

    private boolean active;
    private boolean dirty;
    private boolean attempted;
    private boolean fresh;
    private long revision;
    private long nextPeriod;
    private long delayPeriods = NORMAL_PERIODS;
    private PatternRetryIndex.Counters counters;

    public synchronized void activate(PatternRetryIndex.Counters counters) {
        this.counters = counters;
        active = true;
        reset();
    }

    public synchronized void deactivate() {
        active = false;
        revision++;
    }

    private void reset() {
        dirty = true;
        fresh = true;
        attempted = false;
        delayPeriods = NORMAL_PERIODS;
        revision++;
    }

    public synchronized void resetForLayout() {
        if (!active) return;
        reset();
        if (diagnostics()) counters.layoutResets.increment();
    }

    public synchronized void wake(boolean chunkLoad) {
        if (!active || dirty) return;
        dirty = true;
        revision++;
        if (diagnostics()) {
            counters.wakeups.increment();
            if (chunkLoad) counters.chunkWakeups.increment();
            else counters.blockWakeups.increment();
        }
    }

    public synchronized boolean isActive() {
        return active;
    }

    public synchronized boolean shouldCheck(long period, boolean eager) {
        if (!active) return false;
        if (eager || !ConfigHolder.INSTANCE.performance.multiblockRetryBackoff || dirty || !attempted ||
                period - nextPeriod >= 0)
            return true;
        if (diagnostics()) counters.skipped.increment();
        return false;
    }

    /** Capture the revision after taking the pattern lock; a later wake must survive this check's result. */
    public synchronized long begin(boolean eager) {
        if (diagnostics()) {
            counters.checks.increment();
            if (eager) counters.eagerChecks.increment();
            else if (fresh) counters.initialChecks.increment();
            else if (dirty) counters.wakeChecks.increment();
            else counters.periodicChecks.increment();
        }
        dirty = false;
        fresh = false;
        return revision;
    }

    public synchronized void failed(long period, long startedRevision) {
        if (diagnostics()) counters.failures.increment();
        if (!active || revision != startedRevision) return;
        long maximum = (long) ConfigHolder.INSTANCE.performance.multiblockRetryMaxSeconds * NORMAL_PERIODS;
        long delay = Math.min(delayPeriods, maximum);
        nextPeriod = period + delay;
        delayPeriods = Math.min(delay * 2, maximum);
        attempted = true;
    }

    public synchronized void formed() {
        if (diagnostics()) counters.formed.increment();
    }

    private boolean diagnostics() {
        return counters != null && ConfigHolder.INSTANCE.diagnostics.multiblockRetryLogging;
    }
}
