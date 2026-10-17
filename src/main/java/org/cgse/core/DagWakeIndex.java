// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Resource subscriptions for unfinished DAG nodes outside the awakened queue.
 * Local ranks keep each resource's original recipe order without rescanning
 * removed subscribers. Storage is linear in live input incidences, including
 * sparse resources near the end of a large recipe list. */
final class DagWakeIndex<K> {
    private final Map<K, Integer> rows = new HashMap<>();
    private final int[][] consumers;
    private final List<ActiveBits> pending;
    private final int[] starts, resources, positions;
    private final boolean[] enabled;
    private long wordVisits;

    DagWakeIndex(List<GraphRecipe<K>> recipes, BigInteger[] remaining) {
        starts = new int[Math.addExact(recipes.size(), 1)]; enabled = new boolean[recipes.size()];
        var sizes = new ArrayList<Integer>(); int edges = 0;
        for (int i = 0; i < recipes.size(); i++) {
            starts[i] = edges;
            if (remaining[i].signum() <= 0) continue;
            enabled[i] = true;
            for (K key : recipes.get(i).inputs().keySet()) {
                Integer row = rows.get(key);
                if (row == null) { row = sizes.size(); rows.put(key, row); sizes.add(0); }
                sizes.set(row, Math.addExact(sizes.get(row), 1));
                edges = Math.addExact(edges, 1);
            }
        }
        starts[recipes.size()] = edges;
        resources = new int[edges]; positions = new int[edges];
        consumers = new int[sizes.size()][];
        // ActiveBits is non-static only to attribute primitive word visits to
        // this index; it holds no host callbacks or mutable inventory.
        var building = new ArrayList<ActiveBits>(sizes.size());
        for (int row = 0; row < sizes.size(); row++) {
            consumers[row] = new int[sizes.get(row)]; building.add(new ActiveBits(sizes.get(row)));
        }
        pending = List.copyOf(building);
        int[] filled = new int[sizes.size()];
        for (int i = 0; i < recipes.size(); i++) {
            if (!enabled[i]) continue;
            int edge = starts[i];
            for (K key : recipes.get(i).inputs().keySet()) {
                int row = rows.get(key), position = filled[row]++;
                consumers[row][position] = i;
                resources[edge] = row; positions[edge++] = position;
            }
        }
    }

    int first(K key) {
        Integer row = rows.get(key);
        if (row == null) return -1;
        int rank = pending.get(row).first();
        return rank < 0 ? -1 : consumers[row][rank];
    }

    void enable(int recipe) {
        if (enabled[recipe]) return;
        enabled[recipe] = true;
        for (int edge = starts[recipe]; edge < starts[recipe + 1]; edge++) pending.get(resources[edge]).set(positions[edge], true);
    }

    void disable(int recipe) {
        if (!enabled[recipe]) return;
        enabled[recipe] = false;
        for (int edge = starts[recipe]; edge < starts[recipe + 1]; edge++) pending.get(resources[edge]).set(positions[edge], false);
    }

    long wordVisits() { return wordVisits; }
    int incidences() { return resources.length; }
    long bitmapWords() {
        long words = 0;
        for (var row : pending) {
            if (row.levels == null) words++;
            else for (var level : row.levels) words += level.length;
        }
        return words;
    }

    /** Each upper-level bit names a nonempty lower-level word. Find, insert
     * and erase touch O(log_64(degree)) words, even with a sparse live suffix. */
    private final class ActiveBits {
        private final long[][] levels;
        private long singleWord;

        ActiveBits(int size) {
            // Most state keys have only one consumer. Keep these subscriptions
            // in the object itself instead of allocating two tiny arrays each.
            if (size <= 64) {
                singleWord = size == 0 ? 0 : -1L >>> (64 - size);
                levels = null;
                return;
            }
            var building = new ArrayList<long[]>();
            for (int bits = size; ; ) {
                int length = (int) ((bits + 63L) / 64);
                long[] words = new long[length]; Arrays.fill(words, -1L);
                if (length > 0 && (bits & 63) != 0) words[length - 1] = -1L >>> (64 - (bits & 63));
                building.add(words);
                if (length <= 1) break;
                bits = length;
            }
            levels = building.toArray(long[][]::new);
        }

        int first() {
            if (levels == null) {
                wordVisits++;
                return singleWord == 0 ? -1 : Long.numberOfTrailingZeros(singleWord);
            }
            int rank = 0;
            for (int level = levels.length - 1; level >= 0; level--) {
                wordVisits++;
                long word = levels[level][rank];
                if (word == 0) return -1;
                rank = (rank << 6) + Long.numberOfTrailingZeros(word);
            }
            return rank;
        }

        void set(int bit, boolean present) {
            if (levels == null) {
                wordVisits++;
                long mask = 1L << bit;
                singleWord = present ? singleWord | mask : singleWord & ~mask;
                return;
            }
            for (var level : levels) {
                int at = bit >>> 6; long mask = 1L << (bit & 63);
                wordVisits++;
                long before = level[at], after = present ? before | mask : before & ~mask;
                if (before == after) return;
                level[at] = after;
                // Only an empty/nonempty word transition changes its parent.
                if (present ? before != 0 : after != 0) return;
                bit = at;
            }
        }
    }
}
