package org.gtlcore.gtlcore.integration.ae2.graph;

import org.cgse.core.ReplanRetry;

/** Tick-based backoff for one failed suffix; storage events never shorten its quiet period. */
final class GraphReplanRetry {

    private final ReplanRetry retry = new ReplanRetry();

    int failed(long tick) {
        return retry.failed(tick);
    }

    boolean ready(long tick) {
        return retry.ready(tick);
    }

    boolean shouldLog(long tick, String failure) {
        return retry.shouldLog(tick, failure);
    }

    int failures() {
        return retry.failures();
    }

    void reset() {
        retry.reset();
    }
}
