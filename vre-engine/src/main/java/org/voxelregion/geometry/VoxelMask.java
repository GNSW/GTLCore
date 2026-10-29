/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion.geometry;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable 16x16x16 local selection. Bit index is {@code x | (z << 4) | (y << 8)}.
 * Partial masks own exactly 64 longs. Empty and full selections use shared
 * singleton instances without a backing array. Builders never expose storage.
 */
public final class VoxelMask {
    public static final int SIDE = 16;
    public static final int VOXELS = 4096;
    public static final VoxelMask EMPTY = new VoxelMask(null, 0);
    public static final VoxelMask ALL = new VoxelMask(null, VOXELS);

    private final long[] words;
    private final int cardinality;

    private VoxelMask(long[] ownedWords, int cardinality) {
        this.words = ownedWords;
        this.cardinality = cardinality;
    }

    public static Builder builder() { return new Builder(); }
    public int cardinality() { return cardinality; }
    public boolean isEmpty() { return cardinality == 0; }
    public boolean isFull() { return cardinality == VOXELS; }

    public boolean contains(int x, int y, int z) {
        checkLocal(x);
        checkLocal(y);
        checkLocal(z);
        if (words == null) return isFull();
        int index = x | (z << 4) | (y << 8);
        return (words[index >>> 6] & (1L << (index & 63))) != 0;
    }

    public VoxelMask andNot(VoxelMask other) {
        Objects.requireNonNull(other, "other");
        if (isEmpty() || other.isFull() || this == other) return EMPTY;
        if (other.isEmpty()) return this;
        long[] result = new long[64];
        int count = 0;
        for (int i = 0; i < result.length; i++) {
            result[i] = word(i) & ~other.word(i);
            count += Long.bitCount(result[i]);
        }
        return owned(result, count);
    }

    public VoxelMask union(VoxelMask other) {
        Objects.requireNonNull(other, "other");
        if (isFull() || other.isFull()) return ALL;
        if (other.isEmpty() || this == other) return this;
        if (isEmpty()) return other;
        long[] result = new long[64];
        int count = 0;
        for (int i = 0; i < result.length; i++) {
            result[i] = word(i) | other.word(i);
            count += Long.bitCount(result[i]);
        }
        return owned(result, count);
    }

    /** Emits maximal selected Y runs in local coordinates, each exactly once. */
    public void forEachRanges(RangeSink sink) {
        Objects.requireNonNull(sink, "sink");
        if (isEmpty()) return;
        for (int z = 0; z < SIDE; z++) {
            for (int x = 0; x < SIDE; x++) {
                if (isFull()) {
                    sink.accept(x, z, 0, SIDE);
                    continue;
                }
                int bit = x | (z << 4);
                int y = 0;
                while (y < SIDE) {
                    while (y < SIDE && !selected(bit | (y << 8))) y++;
                    int start = y;
                    while (y < SIDE && selected(bit | (y << 8))) y++;
                    if (start < y) sink.accept(x, z, start, y);
                }
            }
        }
    }

    /**
     * Conservative storage estimate, not a JVM layout promise: 576 bytes for
     * a partial mask and 32 for a shared singleton (no per-use array).
     */
    public long estimatedBytes() { return words == null ? 32L : 576L; }

    private boolean selected(int index) {
        return (words[index >>> 6] & (1L << (index & 63))) != 0;
    }

    private long word(int index) {
        return words == null ? (isFull() ? -1L : 0L) : words[index];
    }

    private static VoxelMask owned(long[] words, int count) {
        if (count == 0) return EMPTY;
        if (count == VOXELS) return ALL;
        return new VoxelMask(words, count);
    }

    private static void checkLocal(int coordinate) {
        if (coordinate < 0 || coordinate >= SIDE) {
            throw new IndexOutOfBoundsException("Local coordinate must be in [0,16): " + coordinate);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof VoxelMask mask && cardinality == mask.cardinality
                && Arrays.equals(words, mask.words);
    }

    @Override
    public int hashCode() { return 31 * cardinality + Arrays.hashCode(words); }

    /** Mutable, reusable construction buffer; not thread safe. */
    public static final class Builder {
        private final long[] words = new long[64];

        public Builder() { }

        public Builder set(int x, int y, int z) {
            checkLocal(x);
            checkLocal(y);
            checkLocal(z);
            int index = x | (z << 4) | (y << 8);
            words[index >>> 6] |= 1L << (index & 63);
            return this;
        }

        /** Adds a half-open local Y run. An empty run is allowed. */
        public Builder setRange(int x, int z, int minY, int maxYExclusive) {
            checkLocal(x);
            checkLocal(z);
            if (minY < 0 || maxYExclusive > SIDE || minY > maxYExclusive) {
                throw new IndexOutOfBoundsException("Invalid local Y interval");
            }
            int column = x | (z << 4);
            long bit = 1L << (column & 63);
            int wordInLayer = column >>> 6;
            for (int y = minY; y < maxYExclusive; y++) {
                words[wordInLayer + (y << 2)] |= bit;
            }
            return this;
        }

        public VoxelMask build() {
            int count = 0;
            for (long word : words) count += Long.bitCount(word);
            if (count == 0) return EMPTY;
            if (count == VOXELS) return ALL;
            return new VoxelMask(words.clone(), count);
        }
    }
}
