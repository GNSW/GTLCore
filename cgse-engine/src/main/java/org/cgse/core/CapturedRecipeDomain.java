// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Exact lazy Cartesian domain. Ordinals are stable within one immutable capture.
 * No live callback, inventory filtering, or Cartesian product is retained here.
 * The owner retains emitted values until close(), which releases their logical
 * reservation along with the domain metadata.
 */
public final class CapturedRecipeDomain<K> implements AutoCloseable {

    private static final BigInteger ONE = BigInteger.ONE;
    private final CapturedRecipe<K> recipe;
    private final PlanningBudget budget;
    private final BigInteger[] choices;
    private BigInteger size = ONE, next = BigInteger.ZERO;
    private long memory, compositionScratch;
    private boolean closed;

    CapturedRecipeDomain(CapturedRecipe<K> recipe, PlanningBudget budget) {
        this.recipe = recipe;
        this.budget = budget;
        try {
            budget.checkpoint();
            reserve(128L + 64L * recipe.inputs().size());
            choices = new BigInteger[recipe.inputs().size()];
            long productBytes = 0;
            for (int slot = 0; slot < choices.length; slot++) {
                budget.compilationCheck();
                var input = recipe.inputs().get(slot);
                if (input.multiplier() <= 0) throw new IllegalArgumentException("Non-positive input multiplier");
                int n = input.candidates().size();
                long bits = mixed(input) ? compositionBits(n, input.multiplier()) : 32;
                // Both the per-slot cardinality and the Cartesian product remain
                // live. Long MIXED cardinalities no longer fit in slot metadata.
                reserve(integerBytes(bits));
                long scratch = mixed(input) ? 256L + 6L * integerBytes(bits + 64) : 0;
                compositionScratch = Math.max(compositionScratch, scratch);
                reserve(scratch);
                try {
                    choices[slot] = mixed(input) ? compositions(n, input.multiplier()) : BigInteger.valueOf(n);
                } finally { release(scratch); }
                // Retain one growing product, not the sum of all obsolete
                // intermediate products (quadratic space for a wide recipe).
                long nextBytes = 64L + (size.bitLength() + (long) choices[slot].bitLength() + 7L) / 8;
                reserve(nextBytes);
                long productScratch = 256L + 8L * integerBytes(size.bitLength() + (long) choices[slot].bitLength() + 64);
                reserve(productScratch);
                try { size = size.multiply(choices[slot]); }
                finally { release(productScratch); }
                budget.release(productBytes);
                memory -= productBytes;
                productBytes = nextBytes;
            }
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public BigInteger size() { return size; }

    /** Captured alternatives may themselves have been cut off by the host. */
    public boolean captureComplete() { return !recipe.captureBounded(); }

    public BigInteger coveredPrefix() { return next; }

    public boolean hasNext() { return !closed && next.compareTo(size) < 0; }

    public CapturedRecipe.Resolved<K> next() {
        if (!hasNext()) throw new NoSuchElementException();
        var result = resolve(next);
        next = next.add(ONE);
        return result;
    }

    /** Restore an independently solved slot allocation to its canonical variant. */
    BigInteger ordinal(List<Map<Integer, Long>> allocation) {
        if (closed) throw new IllegalStateException("Closed candidate domain");
        if (allocation.size() != choices.length) throw new IllegalArgumentException("Slot count differs");
        long terms = 0;
        for (var slot : allocation) { budget.compilationCheck(); terms += slot.size(); }
        long scratch = compositionScratch + 6L * integerBytes(size.bitLength() + 64L) + 128L * terms;
        reserve(scratch);
        try {
            return ordinalReserved(allocation);
        } finally { release(scratch); }
    }

    private BigInteger ordinalReserved(List<Map<Integer, Long>> allocation) {
        BigInteger ordinal = BigInteger.ZERO;
        for (int slot = 0; slot < choices.length; slot++) {
            budget.compilationCheck();
            var input = recipe.inputs().get(slot);
            var selected = new TreeMap<>(allocation.get(slot));
            long copies = 0;
            for (var e : selected.entrySet()) {
                budget.compilationCheck();
                if (e.getKey() < 0 || e.getKey() >= input.candidates().size() || e.getValue() <= 0)
                    throw new IllegalArgumentException("Invalid slot allocation");
                copies = Math.addExact(copies, e.getValue());
            }
            if (copies != input.multiplier() || (!mixed(input) && selected.size() != 1))
                throw new IllegalArgumentException("Illegal mixing or multiplicity");
            BigInteger rank;
            if (selected.size() == 1) rank = BigInteger.valueOf(selected.firstKey());
            else {
                int n = input.candidates().size(), start = 0;
                long left = input.multiplier();
                boolean first = true;
                rank = BigInteger.valueOf(n);
                for (var e : selected.entrySet()) {
                    long minimumTail = first ? 1 : 0;
                    for (int at = start; at < e.getKey(); at++)
                        rank = rank.add(tails(n - at - 1, minimumTail, left - 1));
                    rank = rank.add(tails(n - e.getKey() - 1, minimumTail, left - e.getValue() - 1));
                    start = e.getKey() + 1;
                    left -= e.getValue();
                    first = false;
                }
            }
            ordinal = ordinal.multiply(choices[slot]).add(rank);
        }
        return ordinal;
    }

    /** Random access does not claim coverage of the skipped ordinals. */
    public CapturedRecipe.Resolved<K> resolve(BigInteger ordinal) {
        if (closed) throw new IllegalStateException("Closed candidate domain");
        if (ordinal.signum() < 0 || ordinal.compareTo(size) >= 0) throw new IndexOutOfBoundsException(ordinal.toString());
        budget.compilationCheck();
        long scratch = compositionScratch + 6L * integerBytes(size.bitLength() + 64L) + 64L * choices.length;
        reserve(scratch);
        try {
            return resolveReserved(ordinal);
        } finally { release(scratch); }
    }

    private CapturedRecipe.Resolved<K> resolveReserved(BigInteger ordinal) {
        long maxSlots = 0;
        for (var input : recipe.inputs()) maxSlots += mixed(input) ? Math.min(input.multiplier(), input.candidates().size()) : 1;
        // Includes resolved slots/returns, GraphRecipe's aggregate maps, and index references.
        long bytes = 512L + 256L * (maxSlots + recipe.outputs().size()) + 16L * choices.length;
        reserve(bytes);
        try {
            BigInteger[] selected = new BigInteger[choices.length];
            for (int slot = choices.length - 1; slot >= 0; slot--) {
                budget.compilationCheck();
                BigInteger[] qr = ordinal.divideAndRemainder(choices[slot]);
                selected[slot] = qr[1];
                ordinal = qr[0];
            }
            var slots = new ArrayList<GraphRecipe.Slot<K>>();
            var outputs = new LinkedHashMap<K, Long>();
            for (var output : recipe.outputs()) {
                budget.compilationCheck();
                outputs.merge(output.what(), output.amount(), CheckedAmounts::add);
            }
            for (int slot = 0; slot < choices.length; slot++) {
                var input = recipe.inputs().get(slot);
                var candidates = input.candidates();
                BigInteger rank = selected[slot];
                if (rank.compareTo(BigInteger.valueOf(candidates.size())) < 0) {
                    add(slots, outputs, slot, candidates.get(rank.intValueExact()), input.multiplier());
                } else {
                    // Singles precede the old descending-copy DFS of mixed weak compositions.
                    rank = rank.subtract(BigInteger.valueOf(candidates.size()));
                    int start = 0;
                    long left = input.multiplier();
                    boolean first = true;
                    while (left > 0) {
                        boolean found = false;
                        for (int at = start; at < candidates.size() && !found; at++) {
                            budget.compilationCheck();
                            int remaining = candidates.size() - at - 1;
                            long minimumTail = first ? 1 : 0;
                            BigInteger block = tails(remaining, minimumTail, left - 1);
                            if (rank.compareTo(block) >= 0) { rank = rank.subtract(block); continue; }
                            long low = minimumTail, high = left - 1;
                            while (low < high) {
                                budget.compilationCheck();
                                long middle = low + (high - low) / 2;
                                if (rank.compareTo(tails(remaining, minimumTail, middle)) < 0) high = middle;
                                else low = middle + 1;
                            }
                            rank = rank.subtract(tails(remaining, minimumTail, low - 1));
                            add(slots, outputs, slot, candidates.get(at), left - low);
                            left = low;
                            start = at + 1;
                            first = false;
                            found = true;
                        }
                        if (!found) throw new IllegalStateException("Invalid mixed candidate rank");
                    }
                }
            }
            return new CapturedRecipe.Resolved<>(slots, outputs);
        } catch (RuntimeException failure) {
            budget.release(bytes);
            memory -= bytes;
            throw failure;
        }
    }

    private void add(List<GraphRecipe.Slot<K>> slots, LinkedHashMap<K, Long> outputs, int slot,
                     CapturedRecipe.Candidate<K> candidate, long copies) {
        budget.compilationCheck();
        long amount = CheckedAmounts.multiply(candidate.stack().amount(), copies);
        slots.add(new GraphRecipe.Slot<>(candidate.stack().what(), amount, slot, candidate.configuration(), candidate.reusable()));
        if (candidate.reusable()) outputs.merge(candidate.stack().what(), amount, CheckedAmounts::add);
        if (candidate.remaining() != null) outputs.merge(candidate.remaining(), copies, CheckedAmounts::add);
    }

    private boolean mixed(CapturedRecipe.Input<K> input) {
        return recipe.mixed(input) && input.candidates().size() > 1;
    }

    /** Sum weak-composition blocks over a tail-copy interval, never over each copy. */
    private BigInteger tails(int candidates, long from, long through) {
        budget.compilationCheck();
        if (through < from) return BigInteger.ZERO;
        if (candidates == 0) return from == 0 ? ONE : BigInteger.ZERO;
        BigInteger total = compositions(candidates + 1, through);
        return from == 0 ? total : total.subtract(compositions(candidates + 1, from - 1));
    }

    private BigInteger compositions(int candidates, long copies) {
        if (copies == 0) return ONE;
        if (candidates == 0) return BigInteger.ZERO;
        BigInteger count = ONE;
        long terms = Math.min(copies, candidates - 1L);
        BigInteger base = BigInteger.valueOf(copies).add(BigInteger.valueOf(candidates - 1L - terms));
        for (int k = 1; k <= terms; k++) {
            budget.compilationCheck();
            count = count.multiply(base.add(BigInteger.valueOf(k))).divide(BigInteger.valueOf(k));
        }
        return count;
    }

    /** Binomial numerator bound, computed without allocating a large integer first. */
    private static long compositionBits(int candidates, long copies) {
        long terms = Math.min(copies, Math.max(0, candidates - 1L));
        int topBits = Math.max(64 - Long.numberOfLeadingZeros(copies),
                32 - Integer.numberOfLeadingZeros(Math.max(0, candidates - 1))) + 1;
        return Math.max(1, terms * topBits);
    }

    private static long integerBytes(long bits) { return 64L + (bits + 7L) / 8; }

    private void reserve(long bytes) {
        budget.reserve(bytes);
        memory += bytes;
    }

    private void release(long bytes) {
        budget.release(bytes);
        memory -= bytes;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        budget.release(memory);
        memory = 0;
    }
}
