// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

/** Exact retained point coordinates. A proved absolute-sum bound permits int
 * storage; query arithmetic and boxes remain long. No coefficient is rounded. */
final class CountPackingValues {
    private final int[] compact;
    private final long[] wide;

    static boolean canPack(long[][] rows, int from, int to, PlanningBudget budget) {
        for (long[] row : rows) {
            if (row.length < to) throw new IllegalArgumentException("Packing row width");
            long sum = 0;
            for (int i = from; i < to; i++) {
                budget.operation(PlanningBudget.Operation.INTEGER, 64);
                long value = row[i];
                if (value < -Integer.MAX_VALUE || value > Integer.MAX_VALUE) return false;
                long magnitude = Math.abs(value);
                if (magnitude > Integer.MAX_VALUE - sum) return false;
                sum += magnitude;
            }
        }
        return true;
    }

    CountPackingValues(int length, boolean small) {
        compact = small ? new int[length] : null;
        wide = small ? null : new long[length];
    }

    long get(int index) { return compact == null ? wide[index] : compact[index]; }
    void set(int index, long value) {
        if (compact == null) wide[index] = value;
        else compact[index] = Math.toIntExact(value);
    }
    void copy(int index, long[] destination, int offset, int length) {
        if (compact == null) System.arraycopy(wide, index, destination, offset, length);
        else for (int i = 0; i < length; i++) destination[offset + i] = compact[index + i];
    }
    void put(long[] source, int offset, int index, int length) {
        if (compact == null) System.arraycopy(source, offset, wide, index, length);
        else for (int i = 0; i < length; i++) compact[index + i] = Math.toIntExact(source[offset + i]);
    }
}
