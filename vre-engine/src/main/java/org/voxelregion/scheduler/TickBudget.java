// SPDX-License-Identifier: MPL-2.0
package org.voxelregion.scheduler;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Shared, cooperative tick budget. A backend call cannot be preempted. */
public final class TickBudget {
    public record Limits(long nanos, long work, long lightUpdates, long packetBytes) {
        public Limits {
            if (nanos <= 0 || work <= 0 || lightUpdates < 0 || packetBytes < 0)
                throw new IllegalArgumentException("Invalid tick limits");
        }
    }

    private final Limits limits;
    private final LongSupplier clock;
    private final long start;
    private long work, light, packets;

    public TickBudget(Limits limits) { this(limits, System::nanoTime); }
    public TickBudget(Limits limits, LongSupplier clock) {
        this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock);
        start = clock.getAsLong();
    }

    /** Reserve all dimensions atomically before work, including snapshot allocation. */
    public boolean trySpend(long workUnits, long lightUpdates, long packetBytes) {
        if (workUnits < 0 || lightUpdates < 0 || packetBytes < 0)
            throw new IllegalArgumentException("Negative cost");
        if (timeExpired() || workUnits > limits.work - work || lightUpdates > limits.lightUpdates - light
                || packetBytes > limits.packetBytes - packets) return false;
        work += workUnits;
        light += lightUpdates;
        packets += packetBytes;
        return true;
    }

    public boolean timeExpired() { return elapsedNanos() >= limits.nanos; }
    public boolean exhausted() { return timeExpired() || work >= limits.work; }
    public long elapsedNanos() { return Math.max(0, clock.getAsLong() - start); }
    public long workSpent() { return work; }
    public long lightSpent() { return light; }
    public long packetBytesSpent() { return packets; }
    public long workRemaining() { return limits.work - work; }
    public boolean overranTime() { return elapsedNanos() > limits.nanos; }
}
