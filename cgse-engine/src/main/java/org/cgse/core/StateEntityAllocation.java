// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.ToIntFunction;

/** Retained allocation of one family's owned entities to simultaneous slots.
 * Indivisible slots need one exact key; mixed slots can use the remaining keys.
 * A failure concerns this inventory and these slots, never a count-model cut. */
final class StateEntityAllocation<K> implements AutoCloseable {
    enum Failure { NONE, ENTITY_SHORTAGE, WHOLE_KEY_FRAGMENTATION }
    record Demand(int slot, long amount, boolean mixed) {
        Demand { if (slot < 0 || amount <= 0) throw new IllegalArgumentException("Entity demand"); }
    }
    record Supply<K>(K state, BigInteger count, long capacity) {
        Supply { if (state == null || count.signum() <= 0 || capacity <= 0) throw new IllegalArgumentException("Entity supply"); }
    }
    record Pick<K>(int slot, K state, long amount) {}

    private final PlanningBudget budget;
    private final List<Supply<K>> supplies;
    private final List<Demand> demands;
    private final BigInteger[] remaining;
    private final int[] chosen, next, preferred;
    private final int[] priorities;
    private final List<Pick<K>> result = new ArrayList<>();
    private final int indivisible;
    private int depth, mixed, bin;
    private long left, memory;
    private boolean done, feasible, emitting;
    private Failure failure = Failure.NONE;
    private boolean packingTried;
    private StateEntityPacking<K> packing;

    static <K> StateEntityAllocation<K> create(List<Demand> demands, List<Supply<K>> supplies, PlanningBudget budget) {
        return create(demands, supplies, null, budget);
    }

    static <K> StateEntityAllocation<K> create(List<Demand> demands, List<Supply<K>> supplies,
            ToIntFunction<K> preference, PlanningBudget budget) {
        // At most one pick per indivisible slot. Splitting the mixed remainder
        // exhausts either a slot or a supply at each pick, hence O(slots + keys).
        long bytes = 512L + 160L * demands.size() + (preference == null ? 224L : 320L) * supplies.size();
        if (!budget.tryReserve(bytes)) return preference == null ? null : create(demands, supplies, budget);
        try { return new StateEntityAllocation<>(demands, supplies, preference, budget, bytes); }
        catch (RuntimeException | Error failure) { budget.release(bytes); throw failure; }
    }

    private StateEntityAllocation(List<Demand> demands, List<Supply<K>> supplies, ToIntFunction<K> preference,
            PlanningBudget budget, long bytes) {
        this.budget = budget; memory = bytes;
        this.demands = new ArrayList<>(demands); this.supplies = new ArrayList<>(supplies);
        this.demands.sort((a, b) -> {
            budget.check();
            int mode = Boolean.compare(a.mixed(), b.mixed());
            return mode != 0 ? mode : a.mixed() ? Integer.compare(a.slot(), b.slot()) : Long.compare(b.amount(), a.amount());
        });
        // Prefer using long-lived tools while several entities are needed in
        // parallel. Capacity is a heuristic only, not a survival certificate.
        var scores = preference == null ? null : new HashMap<K, Integer>();
        if (scores != null) for (var supply : this.supplies) {
            budget.check(); scores.put(supply.state(), preference.applyAsInt(supply.state()));
        }
        this.supplies.sort((a, b) -> {
            budget.check();
            int order = scores == null ? 0 : Integer.compare(scores.get(a.state()), scores.get(b.state()));
            return order != 0 ? order : Long.compare(b.capacity(), a.capacity());
        });
        priorities = scores == null ? null : new int[supplies.size()];
        remaining = new BigInteger[supplies.size()];
        BigInteger available = BigInteger.ZERO, needed = BigInteger.ZERO;
        for (int i = 0; i < this.supplies.size(); i++) {
            budget.check(); remaining[i] = this.supplies.get(i).count(); available = available.add(remaining[i]);
            if (priorities != null) priorities[i] = scores.get(this.supplies.get(i).state());
        }
        int fixed = 0;
        for (var demand : this.demands) { budget.check(); needed = needed.add(BigInteger.valueOf(demand.amount())); if (!demand.mixed()) fixed++; }
        indivisible = fixed; mixed = fixed;
        chosen = new int[fixed]; next = new int[fixed]; preferred = new int[fixed];
        Arrays.fill(chosen, -1); Arrays.fill(preferred, -2);
        if (needed.compareTo(available) > 0) { done = true; failure = Failure.ENTITY_SHORTAGE; }
    }

    /** One retained branch, undo, or output fragment; never restarts the DFS. */
    boolean step() {
        budget.checkpoint();
        if (done) return true;
        budget.check();
        if (packing != null) {
            if (!packing.step()) return false;
            boolean possible = packing.possible(); packing.close(); packing = null;
            if (!possible) { done = true; failure = Failure.WHOLE_KEY_FRAGMENTATION; }
            return done;
        }
        if (emitting) {
            if (mixed == demands.size()) { feasible = done = true; return true; }
            Demand demand = demands.get(mixed);
            if (left == 0) left = demand.amount();
            if (remaining[bin].signum() == 0) { bin++; return false; }
            long use = remaining[bin].min(BigInteger.valueOf(left)).longValueExact();
            result.add(new Pick<>(demand.slot(), supplies.get(bin).state(), use));
            remaining[bin] = remaining[bin].subtract(BigInteger.valueOf(use));
            left -= use;
            if (left == 0) mixed++;
            return false;
        }
        if (depth == indivisible) {
            for (int i = 0; i < indivisible; i++) {
                budget.check(); var demand = demands.get(i);
                result.add(new Pick<>(demand.slot(), supplies.get(chosen[i]).state(), demand.amount()));
            }
            emitting = true; return false;
        }
        BigInteger amount = BigInteger.valueOf(demands.get(depth).amount());
        if (preferred[depth] == -2) {
            // Best-fit first avoids starving large indivisible slots. Enumerate
            // the other assignments too: greedy bin packing is not complete.
            int best = -1;
            for (int i = 0; i < remaining.length; i++) {
                budget.check();
                int order = best < 0 || priorities == null ? 0 : Integer.compare(priorities[i], priorities[best]);
                if (remaining[i].compareTo(amount) >= 0 && (best < 0 || order < 0 ||
                        order == 0 && remaining[i].compareTo(remaining[best]) < 0)) best = i;
            }
            preferred[depth] = best; next[depth] = -1;
        }
        int candidate;
        if (next[depth] == -1) { candidate = preferred[depth]; next[depth] = 0; }
        else {
            candidate = next[depth]++;
            if (candidate == preferred[depth]) return false;
        }
        if (candidate < 0 || candidate >= remaining.length) {
            if (!packingTried) {
                packingTried = true;
                packing = StateEntityPacking.create(demands, indivisible, supplies, budget);
                // Keep the exhausted branch in place until the original-stock
                // bound finishes. It may only reject, never supply a witness.
                if (packing != null) return false;
            }
            preferred[depth] = -2;
            if (depth == 0) { done = true; failure = Failure.WHOLE_KEY_FRAGMENTATION; return true; }
            depth--;
            remaining[chosen[depth]] = remaining[chosen[depth]].add(BigInteger.valueOf(demands.get(depth).amount()));
            chosen[depth] = -1;
            return false;
        }
        if (remaining[candidate].compareTo(amount) < 0) return false;
        remaining[candidate] = remaining[candidate].subtract(amount);
        chosen[depth++] = candidate;
        return false;
    }

    boolean feasible() { return done && feasible; }
    /** Only an exhausted current-inventory allocation has a failure kind.
     * Cancellation, closing and a successful allocation do not establish one. */
    Failure failure() { return failure; }
    List<Pick<K>> result() { if (!feasible()) throw new IllegalStateException("No allocation witness"); return result; }

    @Override public void close() {
        if (packing != null) packing.close(); packing = null;
        supplies.clear(); demands.clear(); result.clear(); Arrays.fill(remaining, null);
        budget.release(memory); memory = 0; done = true;
    }
}
