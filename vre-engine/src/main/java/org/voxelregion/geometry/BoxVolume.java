/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion.geometry;

import java.util.Objects;

/** Selects exactly the integer voxels in its half-open bounds. */
public record BoxVolume(IntBox bounds) implements Volume {
    public BoxVolume {
        Objects.requireNonNull(bounds, "bounds");
    }

    @Override
    public Coverage classify(IntBox box) {
        Objects.requireNonNull(box, "box");
        if (!bounds.intersects(box)) return Coverage.OUTSIDE;
        return bounds.contains(box) ? Coverage.INSIDE : Coverage.PARTIAL;
    }

    @Override
    public boolean contains(int x, int y, int z) {
        return bounds.contains(x, y, z);
    }

    @Override
    public void emitRanges(IntBox clip, RangeSink sink) {
        Objects.requireNonNull(sink, "sink");
        ExactGeometry.emitBox(bounds.intersection(Objects.requireNonNull(clip, "clip")), sink);
    }
}
