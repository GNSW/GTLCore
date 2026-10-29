/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.search;

import java.util.*;

/** Exact squared-distance search when only the final coordinate varies along a column.
 * Identical intervals share distance calculations; equal minima with different values remain explicit. */
public final class ColumnNearestIndex<T> {
    public record Entry<T>(long[] minimum, long[] maximum, long offset, T value) {
        public Entry { minimum = minimum.clone(); maximum = maximum.clone(); Objects.requireNonNull(value); }
        @Override public long[] minimum() { return minimum.clone(); }
        @Override public long[] maximum() { return maximum.clone(); }
    }
    private record Interval(long min, long max) {
        Interval { if (min > max) throw new IllegalArgumentException("Inverted interval"); }
        long distance(long coordinate) { return Math.max(0, Math.max(Math.subtractExact(min, coordinate), Math.subtractExact(coordinate, max))); }
    }
    private final int dimensions;
    private final Interval[][] intervals;
    private final int[][] indices;
    private final int[] group;
    private final long[] offsetCost;
    private final Object[] values;
    private final int[] valueIds;
    private final Object[] distinctValues;
    private final long maximumOffset;
    private final Interval[] variable;

    public ColumnNearestIndex(List<Entry<T>> entries) {
        if (entries.isEmpty()) throw new IllegalArgumentException("No candidates");
        dimensions = entries.get(0).minimum.length - 1;
        if (dimensions < 1) throw new IllegalArgumentException("Need a fixed and a variable coordinate");
        intervals = new Interval[dimensions][]; indices = new int[dimensions][entries.size()];
        group = new int[entries.size()]; offsetCost = new long[entries.size()]; values = new Object[entries.size()];
        valueIds = new int[entries.size()]; var valueMap = new LinkedHashMap<T, Integer>(); long maxOffset = 0;
        List<Map<Interval, Integer>> dedup = new ArrayList<>();
        for (int d = 0; d <= dimensions; d++) dedup.add(new LinkedHashMap<>());
        for (int i = 0; i < entries.size(); i++) {
            Entry<T> entry = entries.get(i);
            if (entry.minimum.length != dimensions + 1 || entry.maximum.length != dimensions + 1)
                throw new IllegalArgumentException("Different dimensions");
            for (int d = 0; d <= dimensions; d++) {
                var interval = new Interval(entry.minimum[d], entry.maximum[d]);
                var map = dedup.get(d); int index = map.computeIfAbsent(interval, ignored -> map.size());
                if (d == dimensions) group[i] = index; else indices[d][i] = index;
            }
            offsetCost[i] = Math.multiplyExact(entry.offset, entry.offset); values[i] = entry.value;
            maxOffset = Math.max(maxOffset, offsetCost[i]);
            valueIds[i] = valueMap.computeIfAbsent(entry.value, ignored -> valueMap.size());
        }
        for (int d = 0; d < dimensions; d++) intervals[d] = dedup.get(d).keySet().toArray(Interval[]::new);
        variable = dedup.get(dimensions).keySet().toArray(Interval[]::new);
        distinctValues = valueMap.keySet().toArray(); maximumOffset = maxOffset;
    }
    public Query<T> column(long... coordinates) {
        if (coordinates.length != dimensions) throw new IllegalArgumentException("Different fixed dimensions");
        long[][] distances = new long[dimensions][];
        long maximumScore = maximumOffset;
        for (int d = 0; d < dimensions; d++) {
            distances[d] = new long[intervals[d].length];
            long maximum = 0;
            for (int i = 0; i < intervals[d].length; i++) {
                long distance = intervals[d][i].distance(coordinates[d]);
                distances[d][i] = Math.multiplyExact(distance, distance);
                maximum = Math.max(maximum, distances[d][i]);
            }
            maximumScore = Math.addExact(maximumScore, maximum);
        }
        long[] best = new long[variable.length]; Arrays.fill(best, Long.MAX_VALUE);
        int[] winners = new int[variable.length]; Arrays.fill(winners, -1);
        boolean[] tied = new boolean[variable.length];
        for (int i = 0; i < values.length; i++) {
            long score = offsetCost[i]; int g = group[i]; long bound = best[g];
            for (int d = 0; d < dimensions && score <= bound; d++) score += distances[d][indices[d][i]];
            if (score < bound) { best[g] = score; winners[g] = valueIds[i]; tied[g] = false; }
            else if (score == bound && winners[g] != valueIds[i]) tied[g] = true;
        }
        return new Query<>(variable, best, winners, tied, distinctValues);
    }
    public static final class Query<T> {
        private final Interval[] variable;
        private final long[] fixed;
        private final int[] values;
        private final Object[] distinctValues;
        private final boolean[] tied;
        private Query(Interval[] variable, long[] fixed, int[] values, boolean[] tied, Object[] distinctValues) {
            this.variable = variable; this.fixed = fixed; this.values = values; this.tied = tied; this.distinctValues = distinctValues;
        }
        /** Null means multiple different values have equal minimum distance. The host resolves ties. */
        @SuppressWarnings("unchecked") public T unique(long coordinate) {
            long best = Long.MAX_VALUE; int winner = -1; boolean ambiguous = false;
            for (int i = 0; i < variable.length; i++) {
                long distance = variable[i].distance(coordinate);
                long score = Math.addExact(fixed[i], Math.multiplyExact(distance, distance));
                if (score < best) { best = score; winner = values[i]; ambiguous = tied[i]; }
                else if (score == best) ambiguous |= tied[i] || winner != values[i];
            }
            return ambiguous || winner < 0 ? null : (T)distinctValues[winner];
        }
    }
}
