/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

/** Full tickServer duration, including Forge END listeners omitted by vanilla averageTickTime. */
public final class ServerTickTimings {
    private static volatile long lastNanos, samples, tickStarted;
    private static volatile double averageMillis;
    public static void record(long nanos) {
        lastNanos = Math.max(0, nanos);
        double millis = lastNanos / 1_000_000.0;
        averageMillis = samples == 0 ? millis : averageMillis * .9 + millis * .1;
        samples++;
    }
    public static long lastNanos() { return lastNanos; }
    public static void beginTick(long now) { tickStarted = now; }
    public static long currentTickElapsedNanos() { return tickStarted == 0 ? 0 : Math.max(0, System.nanoTime() - tickStarted); }
    public static long sampleCount() { return samples; }
    public static double averageMillis() { return averageMillis; }
    public static void reset() { lastNanos = samples = tickStarted = 0; averageMillis = 0; }
    private ServerTickTimings() {}
}
