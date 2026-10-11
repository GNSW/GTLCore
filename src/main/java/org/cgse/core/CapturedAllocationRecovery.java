// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Integral slot marginals to canonical firings, without enumerating the firing count. */
final class CapturedAllocationRecovery<K> implements AutoCloseable {

    private final CapturedRecipe<K> recipe;
    private final CapturedRecipeDomain<K> domain;
    private final PlanningBudget budget;
    private final BigInteger firings;
    private final List<List<Integer>> candidates = new ArrayList<>();
    private final List<List<BigInteger>> counts = new ArrayList<>();
    private final List<Map<Integer, Long>> groups = new ArrayList<>();
    private final Map<BigInteger, BigInteger> variants = new LinkedHashMap<>();
    private BigInteger[] remaining;
    private int[] position;
    private BigInteger decomposed = BigInteger.ZERO;
    private long memory;
    private boolean done;

    /** For mixed slots amounts are copies; for indivisible slots they are whole firings. */
    CapturedAllocationRecovery(CapturedRecipe<K> recipe, CapturedRecipeDomain<K> domain,
                               BigInteger firings, List<Map<Integer, BigInteger>> allocation, PlanningBudget budget) {
        if (firings.signum() <= 0 || allocation.size() != recipe.inputs().size())
            throw new IllegalArgumentException("Invalid integral allocation shape");
        this.recipe = recipe;
        this.domain = domain;
        this.firings = firings;
        this.budget = budget;
        try {
            long terms = 0;
            for (var slot : allocation) { budget.compilationCheck(); terms += slot.size(); }
            if (!reserve(512L + 512L * allocation.size() + 160L * terms)) { done = true; return; }
            remaining = new BigInteger[allocation.size()]; position = new int[allocation.size()];
            for (int slot = 0; slot < allocation.size(); slot++) {
                budget.compilationCheck();
                var input = recipe.inputs().get(slot);
                var ids = new ArrayList<Integer>(); var amounts = new ArrayList<BigInteger>();
                BigInteger total = BigInteger.ZERO;
                for (var e : allocation.get(slot).entrySet()) {
                    budget.compilationCheck();
                    if (e.getKey() < 0 || e.getKey() >= input.candidates().size() || e.getValue().signum() < 0)
                        throw new IllegalArgumentException("Invalid integral candidate allocation");
                    if (e.getValue().signum() == 0) continue;
                    ids.add(e.getKey()); amounts.add(e.getValue()); total = total.add(e.getValue());
                }
                if (!total.equals(firings.multiply(BigInteger.valueOf(mixed(slot) ? input.multiplier() : 1))))
                    throw new IllegalArgumentException("Incomplete integral slot allocation");
                candidates.add(ids); counts.add(amounts); groups.add(Map.of()); remaining[slot] = BigInteger.ZERO;
            }
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    boolean step() {
        if (done) return true;
        budget.compilationCheck();
        BigInteger batch = firings.subtract(decomposed);
        for (int slot = 0; slot < recipe.inputs().size(); slot++) {
            budget.compilationCheck();
            if (remaining[slot].signum() == 0) nextGroup(slot);
            batch = batch.min(remaining[slot]);
        }
        if (batch.signum() <= 0) throw new IllegalStateException("Empty integral firing group");
        BigInteger ordinal = domain.ordinal(groups);
        if (!variants.containsKey(ordinal) && !reserve(192L + (ordinal.bitLength() + 7L) / 8)) {
            done = true; return true;
        }
        variants.merge(ordinal, batch, BigInteger::add);
        for (int slot = 0; slot < remaining.length; slot++) {
            budget.compilationCheck(); remaining[slot] = remaining[slot].subtract(batch);
        }
        decomposed = decomposed.add(batch);
        return done = decomposed.equals(firings);
    }

    private boolean mixed(int slot) { return !recipe.external() && recipe.inputs().get(slot).multiplier() <= 9; }

    private void nextGroup(int slot) {
        var amounts = counts.get(slot);
        int first = nextCandidate(slot);
        var group = new LinkedHashMap<Integer, Long>();
        long copies = recipe.inputs().get(slot).multiplier();
        BigInteger multiplier = BigInteger.valueOf(copies);
        BigInteger runs = mixed(slot) ? amounts.get(first).divide(multiplier) : amounts.get(first);
        if (runs.signum() > 0) {
            group.put(candidates.get(slot).get(first), copies);
            amounts.set(first, mixed(slot) ? amounts.get(first).remainder(multiplier) : BigInteger.ZERO);
        } else {
            runs = BigInteger.ONE;
            while (copies > 0) {
                budget.compilationCheck();
                int at = nextCandidate(slot);
                long take = amounts.get(at).min(BigInteger.valueOf(copies)).longValueExact();
                group.put(candidates.get(slot).get(at), take);
                amounts.set(at, amounts.get(at).subtract(BigInteger.valueOf(take))); copies -= take;
            }
        }
        groups.set(slot, group); remaining[slot] = runs;
    }

    private int nextCandidate(int slot) {
        var amounts = counts.get(slot);
        while (position[slot] < amounts.size()) {
            budget.compilationCheck();
            if (amounts.get(position[slot]).signum() > 0) return position[slot];
            position[slot]++;
        }
        throw new IllegalStateException("Unfunded integral slot");
    }

    List<BigInteger> results() { return List.copyOf(variants.keySet()); }
    Map<BigInteger, BigInteger> batches() { return Map.copyOf(variants); }
    boolean complete() { return decomposed.equals(firings); }

    private boolean reserve(long bytes) {
        if (!budget.tryReserve(bytes)) return false;
        memory += bytes; return true;
    }

    @Override public void close() {
        budget.release(memory); memory = 0; done = true;
        candidates.clear(); counts.clear(); groups.clear(); variants.clear(); remaining = null; position = null;
    }
}
