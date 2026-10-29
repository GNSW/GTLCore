/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion.geometry;

import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Constant-storage radial progression with one immutable shell in flight.
 * Radii use exact half-block units and the step must be positive. The first
 * shell includes the center and extends to min(step, finalRadius); subsequent
 * shells exclude the previous outer sphere. A zero final radius still has one
 * initial shell. Shells may contain no voxels because of clipping or the lattice.
 *
 * <p>Time alone never advances this policy. A host retains its work cursor for
 * {@link #currentShell()} across ticks and calls {@link #completeCurrent} only
 * after that shell's required work completes. Pausing freezes acknowledgement
 * as well as the radius. Looking at the current shell cannot skip work. A stale
 * acknowledgement is rejected by identity. This is an in-process policy, not
 * a durable journal or a world-write transaction.
 *
 * <p>Voxels in different shells never overlap, but sections and chunks can be
 * visited by several shells. A host's completion receipts must account for
 * that distinction. Radius fraction is not a measure of completed world work.
 * Mutable and not thread safe; use from the owning scheduler thread.
 */
public final class RadialWavefront {
    private final Point3 center;
    private final long finalRadius2;
    private final long stepRadius2;
    private final int minY;
    private final int maxY;
    private long completedRadius2 = SphereShell.NO_INNER_RADIUS_2;
    private long completedShellCount;
    private boolean paused;
    private SphereShell current;

    public RadialWavefront(Point3 center, long finalRadius2, long stepRadius2, int minY, int maxY) {
        this.center = Objects.requireNonNull(center, "center");
        ExactGeometry.checkRadius(finalRadius2);
        ExactGeometry.checkRadius(stepRadius2);
        if (stepRadius2 == 0) throw new IllegalArgumentException("Radial step must be positive");
        new IntBox(IntBox.WORLD_MIN, minY, IntBox.WORLD_MIN, IntBox.WORLD_MAX, maxY, IntBox.WORLD_MAX);
        this.finalRadius2 = finalRadius2;
        this.stepRadius2 = stepRadius2;
        this.minY = minY;
        this.maxY = maxY;
    }

    /**
     * Restores an acknowledged phase boundary in O(1), after the host has proved
     * that its durable receipts permit recovery. The supplied radius must match
     * min(finalRadius, completedShellCount * step), or -1 before the first shell.
     * A pending shell's internal chunk/write cursor belongs to the host and is
     * not restored here. This validates geometry, not world recovery safety.
     */
    public static RadialWavefront restore(Point3 center, long finalRadius2, long stepRadius2,
            int minY, int maxY, long completedShellCount, long completedRadius2, boolean paused) {
        RadialWavefront result = new RadialWavefront(center, finalRadius2, stepRadius2, minY, maxY);
        long total = finalRadius2 == 0 ? 1 : 1 + (finalRadius2 - 1) / stepRadius2;
        if (completedShellCount < 0 || completedShellCount > total) {
            throw new IllegalArgumentException("Completed shell count is outside this wavefront");
        }
        long expected = completedShellCount == 0 ? SphereShell.NO_INNER_RADIUS_2
                : Math.min(finalRadius2, Math.multiplyExact(completedShellCount, stepRadius2));
        if (completedRadius2 != expected) {
            throw new IllegalArgumentException("Completed radius disagrees with completed shell count");
        }
        result.completedShellCount = completedShellCount;
        result.completedRadius2 = completedRadius2;
        result.paused = paused;
        return result;
    }

    public Point3 center() { return center; }
    public long finalRadius2() { return finalRadius2; }
    public long stepRadius2() { return stepRadius2; }
    public int minY() { return minY; }
    public int maxY() { return maxY; }
    /** -1 means no shell has been completed yet, including when finalRadius2 is zero. */
    public long completedRadius2() { return completedRadius2; }
    public long completedShellCount() { return completedShellCount; }
    public boolean isComplete() { return completedRadius2 == finalRadius2; }
    /** Remains true while paused if there is still work. */
    public boolean hasNext() { return !isComplete(); }
    public boolean isPaused() { return paused; }
    public void pause() { paused = true; }
    public void resume() { paused = false; }

    /** Stable across repeated calls and pauses until explicitly acknowledged. */
    public SphereShell currentShell() {
        if (isComplete()) throw new NoSuchElementException("Radial wavefront completed");
        if (current == null) {
            long outer = Math.min(finalRadius2, Math.max(0, completedRadius2) + stepRadius2);
            current = new SphereShell(center, completedRadius2, outer, minY, maxY);
        }
        return current;
    }

    /**
     * Acknowledges exactly the current shell. Rejects stale/foreign shells,
     * repeated completion and completion while paused without changing state.
     */
    public void completeCurrent(SphereShell expected) {
        Objects.requireNonNull(expected, "expected");
        if (paused) throw new IllegalStateException("Radial wavefront is paused");
        if (current == null || current != expected || isComplete()) {
            throw new IllegalStateException("Acknowledgement does not name the current shell");
        }
        completedRadius2 = current.outerInclusiveRadius2();
        completedShellCount++;
        current = null;
    }
}
