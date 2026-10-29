/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import net.minecraft.world.level.ChunkPos;

/** Extends only the private band evaluator's aquifer cache to its actual horizontal lattice. */
public final class NoiseBatchScope implements AutoCloseable {
    private static final ThreadLocal<Integer> WIDTH = new ThreadLocal<>();
    private final Integer previous;

    private NoiseBatchScope(int width) {
        if (width < 16 || width > 64 || (width & 15) != 0) throw new IllegalArgumentException("Noise batch width");
        previous = WIDTH.get();
        WIDTH.set(width);
    }

    static NoiseBatchScope open(int width) { return new NoiseBatchScope(width); }

    public static ChunkPos aquiferBounds(ChunkPos origin) {
        Integer width = WIDTH.get();
        if (width == null || width == 16) return origin;
        return new ChunkPos(origin.x, origin.z) {
            @Override public int getMaxBlockX() { return getMinBlockX() + width - 1; }
            @Override public int getMaxBlockZ() { return getMinBlockZ() + width - 1; }
        };
    }

    @Override public void close() {
        if (previous == null) WIDTH.remove();
        else WIDTH.set(previous);
    }
}
