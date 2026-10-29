/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.planner;

/**
 * When false, pure fluids survive and destroyable fluid-containing solids are
 * replaced by the fluid block state supplied by the host snapshot.
 */
public record ClearOptions(boolean clearFluids) {
    public static final ClearOptions DEFAULT = new ClearOptions(true);
}
