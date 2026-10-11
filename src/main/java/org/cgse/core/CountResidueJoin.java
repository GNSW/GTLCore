// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

/**
 * Candidate-only four-list matching over one certified integer equality.
 * Pair sums are joined one residue class at a time, retaining all quarter lists.
 * A stopped, truncated or exhausted search never asserts infeasibility.
 */
final class CountResidueJoin implements AutoCloseable {

    record Shape(int variables, int split, int first, int third, int residueBits,
                 int capacity, long bytes, long work) {
        static Shape create(int variables, long span) {
            if (variables < 4 || variables > 52 || span <= 0 || span > Long.MAX_VALUE / 4) return null;
            int split = (variables + 1) / 2, first = split / 2, third = (variables - split) / 2;
            int largest = Math.max(split - first, variables - split - third);
            int bits = Math.max(1, Math.max(split - 20,
                    Math.min(largest, variables - (64 - Long.numberOfLeadingZeros(span)) - 3)));
            int left = 1 << (split - bits), right = 1 << (variables - split - bits);
            int capacity = left * 2;
            long lists = (1L << first) + (1L << (split - first)) +
                    (1L << third) + (1L << (variables - split - third));
            long bytes = 1056 + 8L * variables + 8L * capacity + 8L * lists +
                    4L * lists + (9L << bits) + 128;
            // The density estimate chooses a strategy, not a completeness bound.
            // Skewed residues still charge each actual pair and hash inspection.
            long work = 4 * (left + (long) right) + 4 * lists;
            return new Shape(variables, split, first, third, bits, capacity, bytes, work);
        }
    }

    private final PlanningBudget budget;
    private final Shape shape;
    private final long target;
    private long[] coefficients, table;
    private long[][] sums;
    private int[][] heads, links, options;
    private final int[] optionCounts = new int[2];
    private final boolean[] reduceQuarter = new boolean[4];
    private boolean[] covered;
    private final int[] starts, sizes;
    private PairCursor left, right;
    private long memory, work, progress, candidate = -1, value, key, leftTotal;
    private int phase, group, cursor, residue, complement, slot, joinedCode, distinct, truncated, reused, duplicates;
    private boolean pending, done, reflected, partial;

    CountResidueJoin(long[] coefficients, long target, PlanningBudget budget, Shape shape) {
        this.budget = budget;
        this.shape = shape;
        this.target = target;
        starts = new int[] {0, shape.first, shape.split, shape.split + shape.third};
        sizes = new int[] {1 << shape.first, 1 << (shape.split - shape.first),
                1 << shape.third, 1 << (shape.variables - shape.split - shape.third)};
        if (!budget.tryReserve(shape.bytes)) {
            done = true;
            return;
        }
        memory = shape.bytes;
        try {
            this.coefficients = coefficients.clone();
            table = new long[shape.capacity];
            sums = new long[4][];
            for (int i = 0; i < 4; i++) sums[i] = new long[sizes[i]];
            heads = new int[][] {new int[1 << shape.residueBits], new int[1 << shape.residueBits]};
            links = new int[][] {new int[sizes[0]], new int[sizes[2]]};
            options = new int[][] {new int[sizes[1]], new int[sizes[3]]};
            covered = new boolean[1 << shape.residueBits];
            left = new PairCursor(0);
            right = new PairCursor(2);
            budget.note("count_residue_join", "quarters=" + sizes[0] + "," + sizes[1] + "," + sizes[2] + "," + sizes[3] +
                    "; residue_bits=" + shape.residueBits + "; bytes=" + shape.bytes + "; positive_only");
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    boolean step() {
        if (done) return true;
        budget.checkpoint();
        for (int quantum = 0; quantum < 16; quantum++) {
            if (phase == 0) {
                // Store by the option mask, so no separate list-code allocation
                // or sorting is required for exact witness reconstruction.
                charge();
                int code = cursor ^ (cursor >>> 1);
                if (cursor != 0) {
                    int bit = Integer.numberOfTrailingZeros(cursor);
                    long coefficient = coefficients[starts[group] + bit];
                    if ((cursor & (cursor - 1)) == 0 && !reduceQuarter[group]) {
                        // Equal/opposite weights or a zero guarantee duplicate
                        // quarter sums. Unstructured quarters keep their cheap
                        // original walk instead of paying to hash every sum.
                        reduceQuarter[group] = coefficient == 0;
                        for (int previous = 0; previous < bit && !reduceQuarter[group]; previous++) {
                            charge();
                            long other = coefficients[starts[group] + previous];
                            reduceQuarter[group] = other == coefficient || other == -coefficient;
                        }
                    }
                    value += (code & (1 << bit)) == 0 ? -coefficient : coefficient;
                }
                sums[group][code] = value;
                progress++;
                if (++cursor == sizes[group]) {
                    cursor = 0;
                    value = 0;
                    if (++group == 4) {
                        phase = 1;
                        group = 0;
                        leftTotal = sums[0][sizes[0] - 1] + sums[1][sizes[1] - 1];
                    }
                }
            } else if (phase == 1) {
                // Quarter sums remain indexed by the original mask. Reuse the
                // as-yet empty pair table to keep one exact representative per
                // sum, without allocating another key table or sorting masks.
                int capacity = sizes[group] * 2;
                if (!reduceQuarter[group] || capacity > table.length) {
                    // Dense odd-sized quarters may exceed this scratch space.
                    // Keep every choice there; admission must not rely on the
                    // expected number of duplicates or spin on a full table.
                    charge();
                    reduceQuarter[group] = false;
                    if ((group & 1) != 0) optionCounts[group / 2] = sizes[group];
                    cursor = sizes[group] - 1;
                } else {
                    if (!pending) {
                        key = sums[group][cursor];
                        slot = hash(key) & (capacity - 1);
                        pending = true;
                    }
                    charge();
                    long entry = table[slot];
                    long epoch = 0x80000000L + group;
                    int previous = (int) entry;
                    if ((entry >>> 32) != epoch) {
                        table[slot] = (epoch << 32) | cursor;
                        if ((group & 1) != 0) options[group / 2][optionCounts[group / 2]++] = cursor;
                    } else if (sums[group][previous] == key) {
                        duplicates++;
                        if ((group & 1) == 0) {
                            // Inner buckets visit masks in descending order;
                            // outer lists visit in ascending order. Keep the
                            // same first representative as the original walk.
                            links[group / 2][previous] = -2;
                            table[slot] = (epoch << 32) | cursor;
                        }
                    } else {
                        slot = (slot + 1) & (capacity - 1);
                        continue;
                    }
                    pending = false;
                }
                if (++cursor == sizes[group]) {
                    cursor = 0;
                    if (++group == 4) {
                        phase = 5;
                        group = 0;
                    }
                }
            } else if (phase == 5) {
                charge();
                if (links[group][cursor] != -2) {
                    int bucket = (int) sums[group * 2][cursor] & (heads[group].length - 1);
                    links[group][cursor] = heads[group][bucket] - 1;
                    heads[group][bucket] = cursor + 1;
                }
                if (++cursor == sizes[group * 2]) {
                    cursor = 0;
                    if (++group == 2) phase = 2;
                }
            } else if (phase == 4) {
                charge();
                if (++residue == covered.length) return finish();
                if (covered[residue]) continue;
                phase = 2;
                distinct = 0;
                reflected = partial = false;
                left.reset();
                right.reset();
            } else {
                PairCursor pairs = phase == 2 ? left : right;
                if (!pending) {
                    if (!pairs.next()) continue;
                    if (pairs.done) {
                        if (phase == 2) phase = 3;
                        else {
                            covered[reflected ? complement : residue] = true;
                            complement = (int) (leftTotal - residue) & (covered.length - 1);
                            if (!reflected && !partial && !covered[complement]) {
                                // Complementing a left option mask replaces its
                                // sum s by leftTotal - s. Reuse the complete table
                                // for that second residue instead of enumerating
                                // and hashing all of its left pairs again.
                                reflected = true;
                                reused++;
                                right.reset();
                            } else phase = 4;
                        }
                        return false;
                    }
                    key = phase == 2 ? pairs.value : reflected ? leftTotal - target + pairs.value : target - pairs.value;
                    joinedCode = pairs.code;
                    slot = hash(key) & (table.length - 1);
                    pending = true;
                }
                charge();
                // Quarter sums already retain the exact key. A slot needs only
                // its option mask and epoch, avoiding a second long-key array.
                long entry = table[slot];
                int storedCode = (int) entry;
                if ((entry >>> 32) != residue + 1) {
                    if (phase == 2) {
                        if (distinct >= table.length / 4 * 3) {
                            // A skewed bucket can exceed its density estimate.
                            // Probe the retained partial table, then try another
                            // residue; this is still only a witness heuristic.
                            truncated++;
                            partial = true;
                            pending = false;
                            phase = 3;
                            return false;
                        }
                        table[slot] = ((long) (residue + 1) << 32) | joinedCode;
                        distinct++;
                    }
                } else if (sums[0][storedCode & (sizes[0] - 1)] + sums[1][storedCode >>> shape.first] == key) {
                    if (phase == 3) {
                        int leftCode = storedCode;
                        if (reflected) leftCode ^= (1 << shape.split) - 1;
                        candidate = (long) leftCode | ((long) joinedCode << shape.split);
                        return finish();
                    }
                } else {
                    slot = (slot + 1) & (table.length - 1);
                    continue;
                }
                pending = false;
                pairs.consume();
                progress++;
            }
        }
        return false;
    }

    private final class PairCursor {
        final int group;
        int outer, outerCode, inner = -1, code;
        long value;
        boolean ready, done;

        PairCursor(int group) { this.group = group; }

        boolean next() {
            if (ready || done) return true;
            if (inner < 0) {
                if (outer == optionCounts[group / 2]) return done = true;
                charge();
                outerCode = reduceQuarter[group + 1] ? options[group / 2][outer] : outer;
                long wanted = group == 0 ? residue : target - (reflected ? complement : residue);
                int bucket = (int) (wanted - sums[group + 1][outerCode]) & (heads[0].length - 1);
                inner = heads[group / 2][bucket] - 1;
                if (inner < 0) { outer++; return false; }
            }
            charge();
            value = sums[group][inner] + sums[group + 1][outerCode];
            code = inner | (outerCode << (group == 0 ? shape.first : shape.third));
            ready = true;
            return true;
        }

        void consume() {
            ready = false;
            inner = links[group / 2][inner];
            if (inner < 0) outer++;
        }

        void reset() { outer = 0; inner = -1; ready = done = false; }
    }

    private void charge() { budget.check(); work++; }

    private static int hash(long value) {
        value = (value ^ (value >>> 33)) * 0xff51afd7ed558ccdL;
        value = (value ^ (value >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return (int) (value ^ (value >>> 33));
    }

    private boolean finish() {
        done = true;
        budget.note("count_residue_join", "witness=" + (candidate >= 0) + "; residue=" + residue +
                "; reused=" + reused + "; truncated=" + truncated + "; duplicates=" + duplicates +
                "; work=" + work + "; positive_only");
        return true;
    }

    long work() { return work; }
    long progress() { return progress; }
    long candidate() { return candidate; }
    boolean probing() { return !done && phase == 3; }
    long continuationWork() { return done ? 0 : shape.work; }

    @Override
    public void close() {
        done = true;
        coefficients = table = null;
        sums = null;
        heads = links = options = null;
        covered = null;
        left = right = null;
        budget.release(memory);
        memory = 0;
    }
}
