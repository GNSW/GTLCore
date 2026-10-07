package org.cgse.core;

/** Tick-based backoff for one failed suffix; events never shorten its quiet period. */
public final class ReplanRetry {

    private int failures;
    private long retryTick;
    private long nextLogTick;
    private String loggedFailure = "";

    public int failed(long tick) {
        if (failures < Integer.MAX_VALUE) failures++;
        int delay = Math.min(600, 40 << Math.min(4, failures - 1));
        retryTick = tick + delay;
        return delay;
    }

    public boolean ready(long tick) {
        return tick >= retryTick;
    }

    public boolean shouldLog(long tick, String failure) {
        if (failure.equals(loggedFailure) && tick < nextLogTick) return false;
        loggedFailure = failure;
        nextLogTick = tick + 600;
        return true;
    }

    public int failures() {
        return failures;
    }

    public void reset() {
        failures = 0;
        retryTick = 0;
        nextLogTick = 0;
        loggedFailure = "";
    }
}
