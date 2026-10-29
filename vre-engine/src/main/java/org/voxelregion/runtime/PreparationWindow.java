/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.runtime;

/** Upstream readiness feedback, independent of downstream durable-completion admission. */
public final class PreparationWindow {
    public record Decision(int target, int submissions, int promotions) { }

    private int capacity = 128, starvedFrames, pressuredFrames, progressAge = Integer.MAX_VALUE;
    private double preparedPerFrame;

    /** Call once per frame. Incomplete readiness samples may not trigger expansion. */
    public Decision observe(int newlyReady, int consumed, int ready, int waiting, boolean headWaiting,
                            int downstreamPending, int downstreamCapacity, long headroomNanos,
                            int maximum, boolean sampleComplete) {
        if (newlyReady < 0 || consumed < 0 || ready < 0 || waiting < 0 || downstreamPending < 0
                || downstreamCapacity < 1 || headroomNanos < 0 || maximum < 0)
            throw new IllegalArgumentException("Invalid preparation sample");
        if (maximum == 0) return new Decision(0, 0, 0);
        capacity = Math.min(capacity, maximum);
        int progress = Math.max(newlyReady, consumed);
        if (progress > 0) {
            preparedPerFrame = Math.max(progress, preparedPerFrame * .8 + progress * .2);
            progressAge = 0;
        } else if (progressAge < Integer.MAX_VALUE) progressAge++;

        // An empty downstream is not a stall: slow loading/post-processing must not
        // collapse its own preparation window just because no saves can yet complete.
        boolean backlog = downstreamPending > 0
                && downstreamPending >= Math.max(1L, (long) downstreamCapacity * 3 / 4);
        boolean pressure = backlog || headroomNanos < 500_000L;
        if (pressure) {
            starvedFrames = 0;
            if (++pressuredFrames >= 8) {
                capacity = Math.max(Math.min(32, maximum), capacity / 2);
                preparedPerFrame *= .5;
                pressuredFrames = 0;
            }
        } else {
            pressuredFrames = 0;
            boolean starved = sampleComplete && headWaiting && headroomNanos >= 2_000_000L
                    && waiting >= Math.max(1, capacity / 2)
                    && ready <= Math.max(8, Math.ceil(preparedPerFrame * 2));
            if (starved && ++starvedFrames >= 4) {
                // A permanently blocked native future gets only a bounded probe.
                // Further growth requires actual upstream readiness or consumption.
                int ceiling = progressAge <= 8 ? maximum : Math.min(maximum, 256);
                if (capacity < ceiling)
                    capacity = Math.min(ceiling, capacity + Math.min(64, Math.max(8, capacity / 4)));
                starvedFrames = 0;
            } else if (!starved) starvedFrames = 0;
        }

        int submissions = Math.min(capacity, Math.min(128, Math.max(64, (int)Math.ceil(preparedPerFrame * 2))));
        int promotions = Math.min(capacity, Math.min(128, Math.max(32, (int)Math.ceil(preparedPerFrame * 2))));
        if (backlog) submissions = 0;
        if (headroomNanos < 150_000L) submissions = promotions = 0;
        else if (headroomNanos < 1_000_000L) {
            submissions = Math.min(submissions, 8);
            promotions = Math.min(promotions, 4);
        }
        return new Decision(capacity, submissions, promotions);
    }
}
