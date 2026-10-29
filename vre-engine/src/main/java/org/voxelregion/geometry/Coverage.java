/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion.geometry;

/** Exact classification of the voxel centers in a half-open integer box. */
public enum Coverage {
    OUTSIDE, INSIDE, PARTIAL
}
