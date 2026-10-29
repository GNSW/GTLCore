/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion.geometry;

/** Receives a nonempty half-open Y interval at a fixed (x,z) column. */
@FunctionalInterface
public interface RangeSink {
    void accept(int x, int z, int minY, int maxYExclusive);
}
