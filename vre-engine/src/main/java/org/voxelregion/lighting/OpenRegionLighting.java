/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.lighting;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/** Detached region. Missing columns are closed boundaries, never assumed to be cleared terrain. */
public final class OpenRegionLighting {
    public static Map<Integer, ClearedChunkLighting.Result> solve(int height, Map<Integer, BitSet> columns, boolean sky) {
        return solve(height, 4, columns, sky);
    }
    /** Keys are x + z * side; buffers contain only present members, not the enclosing square. */
    public static Map<Integer, ClearedChunkLighting.Result> solve(int height, int side, Map<Integer, BitSet> columns, boolean sky) {
        if (height < 16 || height > ClearedChunkLighting.MAX_HEIGHT || height % 16 != 0
                || side < 1 || side > 64 || columns.isEmpty())
            throw new IllegalArgumentException("Invalid open region");
        int band = 16;
        for (var entry : columns.entrySet()) {
            if (entry.getKey() < 0 || entry.getKey() >= side * side || entry.getValue().length() > height * 256)
                throw new IllegalArgumentException("Invalid retained column");
            band = Math.max(band, ((entry.getValue().length() + 4095) / 4096) * 16);
        }
        Map<Integer, ClearedChunkLighting.Result> results = new HashMap<>();
        if (!sky) {
            for (int key : columns.keySet()) results.put(key, new ClearedChunkLighting.Result(height, null, null, 0, 0));
            return Map.copyOf(results);
        }
        // Compact only present columns. A sparse group must not reserve/scan sixteen
        // columns merely because its two members happen to be on opposite corners.
        final int cells = band * 256;
        int[] ordinals = new int[side * side]; Arrays.fill(ordinals, -1);
        int ordinal = 0;
        for (int key : columns.keySet()) ordinals[key] = ordinal++;
        int[] neighbors = new int[columns.size() * 4]; Arrays.fill(neighbors, -1);
        byte[] light = new byte[columns.size() * cells];
        for (var entry : columns.entrySet()) {
            int key = entry.getKey(), member = ordinals[key], base = member * cells;
            if (key % side > 0) neighbors[member * 4] = ordinals[key - 1];
            if (key % side < side - 1) neighbors[member * 4 + 1] = ordinals[key + 1];
            if (key >= side) neighbors[member * 4 + 2] = ordinals[key - side];
            if (key < side * (side - 1)) neighbors[member * 4 + 3] = ordinals[key + side];
            var opaque = entry.getValue();
            for (int i = opaque.nextSetBit(0); i >= 0; i = opaque.nextSetBit(i + 1))
                light[base + i] = -1;
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                for (int y = band - 1; y >= 0; y--) {
                    int i = base + x + z * 16 + y * 256;
                    if (light[i] < 0) break;
                    light[i] = 15;
                }
        }
        // Seed only the lit/shadow interface. Equal-brightness open air creates no propagation work.
        int[] queue = new int[light.length]; int read = 0, write = 0;
        for (int i = 0; i < light.length; i++) {
            if (light[i] != 0) continue;
            int member = i / cells, local = i - member * cells;
            int x = local & 15, z = (local >>> 4) & 15;
            int west = x > 0 ? i - 1 : across(neighbors[member * 4], cells, local + 15);
            int east = x < 15 ? i + 1 : across(neighbors[member * 4 + 1], cells, local - 15);
            int north = z > 0 ? i - 16 : across(neighbors[member * 4 + 2], cells, local + 240);
            int south = z < 15 ? i + 16 : across(neighbors[member * 4 + 3], cells, local - 240);
            if (lit(light, west) || lit(light, east) || lit(light, north) || lit(light, south)
                    || local >= 256 && light[i-256] == 15) {
                light[i] = 14; queue[write++] = i;
            }
        }
        // All initial sources have level 14. FIFO levels decrease monotonically, so each dark cell enters once.
        while (read < write) {
            int i = queue[read++], value = light[i] - 1;
            if (value <= 0) continue;
            int member = i / cells, local = i - member * cells;
            int x = local & 15, z = (local >>> 4) & 15;
            write = spread(light, queue, write, x > 0 ? i - 1 : across(neighbors[member * 4], cells, local + 15), value);
            write = spread(light, queue, write, x < 15 ? i + 1 : across(neighbors[member * 4 + 1], cells, local - 15), value);
            write = spread(light, queue, write, z > 0 ? i - 16 : across(neighbors[member * 4 + 2], cells, local + 240), value);
            write = spread(light, queue, write, z < 15 ? i + 16 : across(neighbors[member * 4 + 3], cells, local - 240), value);
            if (local >= 256) write = spread(light, queue, write, i - 256, value);
            if (local < cells - 256) write = spread(light, queue, write, i + 256, value);
        }
        for (int key : columns.keySet()) {
            byte[] packed = new byte[band * 128]; int base = ordinals[key] * cells;
            for (int local = 0; local < cells; local += 2) {
                int i = base + local;
                packed[local >>> 1] = (byte)(Math.max(0,light[i]) | Math.max(0,light[i+1]) << 4);
            }
            results.put(key, new ClearedChunkLighting.Result(height, packed, null, 15, 0));
        }
        return Map.copyOf(results);
    }
    private static int across(int member, int cells, int local) { return member < 0 ? -1 : member * cells + local; }
    private static boolean lit(byte[] light, int index) { return index >= 0 && light[index] == 15; }
    private static int spread(byte[] light, int[] queue, int write, int index, int value) {
        if (index >= 0 && light[index] == 0) { light[index] = (byte)value; queue[write++] = index; }
        return write;
    }
    private OpenRegionLighting() { }
}
