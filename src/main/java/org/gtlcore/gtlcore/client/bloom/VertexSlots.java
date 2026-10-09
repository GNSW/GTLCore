package org.gtlcore.gtlcore.client.bloom;

import java.util.Arrays;

/**
 * Stable quad-aligned GPU slots. Turning a block off keeps its slot reusable.
 * No world/model data is cached here: every notified block is still tessellated.
 */
final class VertexSlots {

    private int[] offsets;
    private int[] lengths;
    private int used;

    VertexSlots() {}

    VertexSlots(VertexSlots previous) {
        if (previous.offsets != null) {
            offsets = previous.offsets.clone();
            lengths = previous.lengths.clone();
        }
        used = previous.used;
    }

    int reserve(int index, int bytes) {
        if (bytes <= 0 || bytes % 128 != 0) throw new IllegalArgumentException("Complete quads required");
        if (offsets == null) {
            offsets = new int[4096];
            lengths = new int[4096];
            Arrays.fill(offsets, -1);
        }
        if (lengths[index] < bytes) {
            offsets[index] = used;
            lengths[index] = bytes;
            used = Math.addExact(used, bytes);
        }
        return offsets[index];
    }

    int offset(int index) {
        return offsets == null ? -1 : offsets[index];
    }

    int used() {
        return used;
    }
}
