/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.lighting;

import java.util.Arrays;
import java.util.BitSet;

/** Independent column interior solution; the host reconciles shared/exterior faces as one cohort. */
public final class OpenColumnLighting {
    public static ClearedChunkLighting.Result solve(int height, BitSet retainedOpaque, boolean sky) {
        byte[][] block = new byte[6][], skylight = new byte[6][];
        BitSet[] open = new BitSet[6];
        for (var face : ClearedChunkLighting.Face.values()) {
            int size = ClearedChunkLighting.faceSize(height, face), index = face.ordinal();
            block[index] = new byte[size]; skylight[index] = new byte[size];
            open[index] = new BitSet(size);
        }
        BitSet direct = new BitSet(256);
        if (sky) {
            int top = ClearedChunkLighting.Face.UP.ordinal();
            Arrays.fill(skylight[top], (byte)15); open[top].set(0, 256); direct.set(0, 256);
        }
        return ClearedChunkLighting.solve(new ClearedChunkLighting.Snapshot(height, retainedOpaque,
                block, skylight, open, direct));
    }
    private OpenColumnLighting() { }
}
