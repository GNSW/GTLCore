/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.runtime;

/** Admission follows acknowledged throughput, never the number of submitted jobs. One sample per frame. */
public final class CompletionWindow {
    private int capacity = 128, stalledFrames;
    private double completedPerFrame;

    public int observe(int completed, int pending, int maximum) {
        if (completed < 0 || pending < 0 || maximum < 1) throw new IllegalArgumentException("Invalid completion sample");
        if (completed > 0) {
            completedPerFrame = Math.max(completed, completedPerFrame * .8 + completed * .2);
            stalledFrames = 0;
            // Four frames of real downstream capacity allow batching without a growing backlog.
            capacity = (int)Math.min(maximum, Math.max(128, Math.ceil(completedPerFrame * 4)));
        } else if (pending > 0 && ++stalledFrames >= 10) {
            capacity = Math.max(32, capacity / 2); stalledFrames = 0;
            completedPerFrame *= .5;
        } else if (pending == 0) stalledFrames = 0;
        return Math.max(1, Math.min(capacity, maximum));
    }
    public void reset() { capacity = 128; stalledFrames = 0; completedPerFrame = 0; }
}
