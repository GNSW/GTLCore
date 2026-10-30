// SPDX-License-Identifier: LGPL-3.0-only
package ca.spottedleaf.starlight.common.thread;

import ca.spottedleaf.starlight.common.config.GlobalLightConfig;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared bounded CPU pool; spatial ownership and scratch engines remain per dimension. */
public final class GlobalLightWorkers {
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(GlobalLightConfig.parallelism(), task -> {
        Thread thread = new Thread(task, "vre-global-light-" + IDS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    public static ExecutorService executor() { return WORKERS; }
    private GlobalLightWorkers() { }
}
