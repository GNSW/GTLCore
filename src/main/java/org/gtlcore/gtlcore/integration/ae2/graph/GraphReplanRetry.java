package org.gtlcore.gtlcore.integration.ae2.graph;

/** Tick-based backoff for one failed suffix; storage events never shorten its quiet period. */
final class GraphReplanRetry {

    private int failures;
    private long retryTick;
    private long nextLogTick;
    private String loggedFailure = "";

    int failed(long tick) {
        if (failures < Integer.MAX_VALUE) failures++;
        int delay = Math.min(600, 40 << Math.min(4, failures - 1));
        retryTick = tick + delay;
        return delay;
    }

    boolean ready(long tick) {
        return tick >= retryTick;
    }

    boolean shouldLog(long tick, String failure) {
        if (failure.equals(loggedFailure) && tick < nextLogTick) return false;
        loggedFailure = failure;
        nextLogTick = tick + 600;
        return true;
    }

    int failures() {
        return failures;
    }

    void reset() {
        failures = 0;
        retryTick = 0;
        nextLogTick = 0;
        loggedFailure = "";
    }
}
