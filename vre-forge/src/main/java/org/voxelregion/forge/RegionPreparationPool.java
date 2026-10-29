/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.concurrent.*;

/** Dedicated detached preparation avoids contending with native lighting's background mailbox. */
final class RegionPreparationPool {
    private static final ThreadPoolExecutor workers;
    static {
        int count = org.voxelregion.forge.runtime.RuntimeBridge.preparationThreads();
        workers = new ThreadPoolExecutor(count, count, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4096), task -> {
            var thread = new Thread(task, "VRE terrain preparation"); thread.setDaemon(true); return thread;
        });
        workers.allowCoreThreadTimeOut(true);
    }
    static Executor executor() { return workers; }
    private RegionPreparationPool() { }
}
