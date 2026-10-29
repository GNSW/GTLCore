/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.propagation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.VoxelMask;
import org.voxelregion.spatial.SectionKey;

/**
 * Immutable conservative obstacle occupancy for a 16-cubed section, at cell
 * sizes 4, 8 or 16. A bit is set when any exact blocker occupies that cell.
 * Bit order is X fastest, then Z, then Y. The 4-block representation therefore
 * uses x | (z&lt;&lt;2) | (y&lt;&lt;4), for 64 cells total. Coarsening can add shadows;
 * it must never replace the exact destruction/protection mask.
 */
public final class MacroVoxelMask {
    private final int cellSize;
    private final int side;
    private final long bits;

    private MacroVoxelMask(int cellSize, long bits) {
        if (cellSize != 4 && cellSize != 8 && cellSize != 16) {
            throw new IllegalArgumentException("Macro cell size must be 4, 8 or 16");
        }
        this.cellSize = cellSize;
        this.side = 16 / cellSize;
        int cells = side * side * side;
        long allowed = cells == 64 ? -1L : (1L << cells) - 1;
        if ((bits & ~allowed) != 0) throw new IllegalArgumentException("Bits outside the macro grid");
        this.bits = bits;
    }

    public static MacroVoxelMask ofBits(int cellSize, long bits) { return new MacroVoxelMask(cellSize, bits); }

    public static MacroVoxelMask from(VoxelMask exactBlockers, int cellSize) {
        Objects.requireNonNull(exactBlockers, "exactBlockers");
        MacroVoxelMask empty = new MacroVoxelMask(cellSize, 0);
        long[] bits = {0};
        exactBlockers.forEachRanges((x, z, lo, hi) -> {
            int mx = x / cellSize, mz = z / cellSize;
            for (int my = lo / cellSize; my <= (hi - 1) / cellSize; my++) {
                bits[0] |= 1L << empty.index(mx, my, mz);
            }
        });
        return new MacroVoxelMask(cellSize, bits[0]);
    }

    public int cellSize() { return cellSize; }
    public int cellsPerAxis() { return side; }
    public long bits() { return bits; }
    public int occupiedCells() { return Long.bitCount(bits); }
    public boolean isEmpty() { return bits == 0; }

    public boolean containsCell(int x, int y, int z) {
        if (x < 0 || x >= side || y < 0 || y >= side || z < 0 || z >= side) {
            throw new IndexOutOfBoundsException("Macro coordinate outside [0," + side + ")");
        }
        return (bits & (1L << index(x, y, z))) != 0;
    }

    /**
     * Disjoint world boxes covering exactly the occupied macro cells. Greedy
     * X/Z/Y merging introduces no additional occupancy beyond the macro grid.
     * Output is immutable, deterministic and bounded by 64 boxes.
     */
    public List<IntBox> toBoxes(SectionKey section) {
        Objects.requireNonNull(section, "section");
        if (isEmpty()) return List.of();
        IntBox sectionBounds = section.bounds();
        ArrayList<IntBox> result = new ArrayList<>();
        long remaining = bits;
        while (remaining != 0) {
            int first = Long.numberOfTrailingZeros(remaining);
            int x = first % side, z = first / side % side, y = first / (side * side);
            int endX = x + 1, endZ = z + 1, endY = y + 1;
            while (endX < side && present(remaining, endX, y, z)) endX++;
            while (endZ < side && planePresent(remaining, x, endX, y, y + 1, endZ, endZ + 1)) endZ++;
            while (endY < side && planePresent(remaining, x, endX, endY, endY + 1, z, endZ)) endY++;
            for (int yy = y; yy < endY; yy++) for (int zz = z; zz < endZ; zz++) for (int xx = x; xx < endX; xx++) {
                remaining &= ~(1L << index(xx, yy, zz));
            }
            result.add(new IntBox(sectionBounds.minX() + x * cellSize, sectionBounds.minY() + y * cellSize,
                    sectionBounds.minZ() + z * cellSize, sectionBounds.minX() + endX * cellSize,
                    sectionBounds.minY() + endY * cellSize, sectionBounds.minZ() + endZ * cellSize));
        }
        return List.copyOf(result);
    }

    private boolean planePresent(long selected, int x, int endX, int y, int endY, int z, int endZ) {
        for (int yy = y; yy < endY; yy++) for (int zz = z; zz < endZ; zz++) for (int xx = x; xx < endX; xx++) {
            if (!present(selected, xx, yy, zz)) return false;
        }
        return true;
    }

    private boolean present(long selected, int x, int y, int z) { return (selected & (1L << index(x, y, z))) != 0; }
    private int index(int x, int y, int z) { return x + side * z + side * side * y; }
}
