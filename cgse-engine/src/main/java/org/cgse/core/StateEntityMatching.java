// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.ToIntBiFunction;

/** Owned physical entities shared by slots with different eligibility rules.
 * Whole-key slots branch over keys; mixed and unit slots use integral residual
 * flow. Both the assignments and augmentations survive step boundaries. This
 * is an exact one-firing allocation, not a theorem about future tool choices. */
final class StateEntityMatching<K> implements AutoCloseable {
    /** Hall deficit in this one-firing inventory, never a future-supply bound. */
    record Deficit(List<Integer> slots, BigInteger required, BigInteger available) {}
    /** Whole-key allocation failed although splittable flow can fit. A
     * positive threshold carries a checked cohort subset; otherwise all
     * competing slots are retained after exhaustive allocation failure. */
    record Fragmentation(List<Integer> slots, long threshold, int available) {}
    private final PlanningBudget budget;
    private final List<StateEntityAllocation.Demand> demands;
    private final List<StateEntityAllocation.Supply<K>> supplies;
    private final List<StateEntityAllocation.Pick<K>> result = new ArrayList<>();
    private final boolean[] allowed;
    private final long[] flow, left;
    private final BigInteger[] remaining;
    private final int[] chosen, next, parentDemand, parentSupply, queue;
    private final int[] priority;
    private final int fixed, width;
    private int depth, unmet, failedSlot;
    private long memory;
    private boolean done, feasible, flowing, totalShortage, hallDeficit;
    private boolean partialEligibility, relaxationTried, relaxing, failedFlow;
    private boolean packingTried, wholeKeyFailure;
    private StateEntityPacking<K> packing;
    private StateEntityCohorts<K> cohorts;

    static <K> StateEntityMatching<K> create(List<StateEntityAllocation.Demand> demands,
            List<StateEntityAllocation.Supply<K>> supplies,
            BiPredicate<StateEntityAllocation.Demand, K> eligible, PlanningBudget budget) {
        return create(demands, supplies, eligible, null, budget);
    }

    static <K> StateEntityMatching<K> create(List<StateEntityAllocation.Demand> demands,
            List<StateEntityAllocation.Supply<K>> supplies, BiPredicate<StateEntityAllocation.Demand, K> eligible,
            ToIntBiFunction<StateEntityAllocation.Demand, K> preference, PlanningBudget budget) {
        long edges = (long) demands.size() * supplies.size();
        if (edges > Integer.MAX_VALUE - 8) return null;
        // Includes the eligibility/flow matrix and worst-case materialized picks.
        long bytes = 768L + 192L * demands.size() + (preference == null ? 224L : 256L) * supplies.size() +
                (preference == null ? 96L : 104L) * edges;
        if (!budget.tryReserve(bytes)) return preference == null ? null : create(demands, supplies, eligible, budget);
        try { return new StateEntityMatching<>(demands, supplies, eligible, preference, budget, bytes); }
        catch (RuntimeException | Error failure) { budget.release(bytes); throw failure; }
    }

    private StateEntityMatching(List<StateEntityAllocation.Demand> demands,
            List<StateEntityAllocation.Supply<K>> supplies,
            BiPredicate<StateEntityAllocation.Demand, K> eligible,
            ToIntBiFunction<StateEntityAllocation.Demand, K> preference, PlanningBudget budget, long bytes) {
        this.budget = budget; memory = bytes;
        this.demands = new ArrayList<>(demands); this.supplies = new ArrayList<>(supplies);
        this.demands.sort((a, b) -> {
            budget.check();
            int kind = Boolean.compare(!whole(a), !whole(b));
            return kind != 0 ? kind : Long.compare(b.amount(), a.amount());
        });
        this.supplies.sort((a, b) -> { budget.check(); return Long.compare(b.capacity(), a.capacity()); });
        width = supplies.size(); int count = demands.size(), indivisible = 0;
        allowed = new boolean[count * width]; flow = new long[allowed.length];
        priority = preference == null ? null : new int[allowed.length];
        int[] scores = preference == null ? null : new int[width];
        Integer[] order = preference == null ? null : new Integer[width];
        remaining = new BigInteger[width]; left = new long[count];
        BigInteger available = BigInteger.ZERO, needed = BigInteger.ZERO;
        for (int s = 0; s < width; s++) {
            budget.check(); remaining[s] = this.supplies.get(s).count(); available = available.add(remaining[s]);
        }
        for (int d = 0; d < count; d++) {
            budget.check(); var demand = this.demands.get(d);
            if (whole(demand)) indivisible++;
            needed = needed.add(BigInteger.valueOf(demand.amount()));
            left[d] = demand.amount();
            for (int s = 0; s < width; s++) {
                budget.check(); allowed[d * width + s] = eligible.test(demand, this.supplies.get(s).state());
                partialEligibility |= !allowed[d * width + s];
                if (scores != null) {
                    scores[s] = allowed[d * width + s] ? preference.applyAsInt(demand, this.supplies.get(s).state()) : Integer.MAX_VALUE;
                    order[s] = s;
                }
            }
            if (scores != null) {
                // Stable per-slot edge order is only a search preference. All
                // allowed edges remain available to branches and augmentations.
                Arrays.sort(order, (a, b) -> { budget.check(); return Integer.compare(scores[a], scores[b]); });
                for (int s = 0; s < width; s++) { budget.check(); priority[d * width + s] = order[s]; }
            }
        }
        fixed = indivisible; unmet = fixed;
        chosen = new int[fixed]; next = new int[fixed]; Arrays.fill(chosen, -1);
        parentDemand = new int[count]; parentSupply = new int[width]; queue = new int[count];
        failedSlot = count == 0 ? -1 : this.demands.get(0).slot();
        if (needed.compareTo(available) > 0) totalShortage = done = true;
    }

    private static boolean whole(StateEntityAllocation.Demand demand) { return !demand.mixed() && demand.amount() > 1; }

    /** One branch/undo or integral augmentation; quantities never expand into entities. */
    boolean step() {
        budget.checkpoint();
        if (done) return true;
        budget.check();
        if (cohorts != null) {
            if (!cohorts.step()) return false;
            if (!cohorts.possible()) {
                failedSlot = cohorts.failedSlot(); wholeKeyFailure = done = true;
            } else { cohorts.close(); cohorts = null; }
            return done;
        }
        if (packing != null) {
            if (!packing.step()) return false;
            boolean possible = packing.possible(); packing.close(); packing = null;
            if (!possible) wholeKeyFailure = done = true;
            return done;
        }
        if (!flowing) {
            if (depth == fixed) { flowing = true; unmet = fixed; return false; }
            var demand = demands.get(depth);
            if (next[depth] == width) {
                if (startRelaxation(false)) return false;
                failedSlot = demand.slot();
                if (startPacking()) return false;
                next[depth] = 0;
                if (depth == 0) { wholeKeyFailure = done = true; return true; }
                undoFixed(); return false;
            }
            int ordinal = next[depth]++;
            int s = priority == null ? ordinal : priority[depth * width + ordinal];
            BigInteger use = BigInteger.valueOf(demand.amount());
            if (!allowed[depth * width + s] || remaining[s].compareTo(use) < 0) return false;
            remaining[s] = remaining[s].subtract(use); chosen[depth++] = s;
            return false;
        }
        while (unmet < demands.size() && left[unmet] == 0) { budget.check(); unmet++; }
        if (unmet == demands.size()) {
            if (relaxing) {
                // Relaxed flow is only a necessary check. Restore the retained
                // whole-key branch, not the relaxed (possibly split) witness.
                relaxing = false; flowing = false; unmet = fixed;
                resetFlow();
                for (int d = 0; d < depth; d++) {
                    budget.check(); int s = chosen[d];
                    remaining[s] = remaining[s].subtract(BigInteger.valueOf(demands.get(d).amount()));
                }
                if (failedFlow) undoFixed();
                startPacking();
                return false;
            }
            for (int d = 0; d < demands.size(); d++) {
                budget.check(); var demand = demands.get(d);
                if (d < fixed) result.add(new StateEntityAllocation.Pick<>(demand.slot(), supplies.get(chosen[d]).state(), demand.amount()));
                else for (int s = 0; s < width; s++) {
                    budget.check(); long use = flow[d * width + s];
                    if (use > 0) result.add(new StateEntityAllocation.Pick<>(demand.slot(), supplies.get(s).state(), use));
                }
            }
            feasible = done = true; return true;
        }
        if (augment(unmet)) return false;
        failedSlot = demands.get(unmet).slot();
        if (relaxing || fixed == 0) { hallDeficit = done = true; return true; }
        if (startRelaxation(true)) return false;
        // A failed flow only rejects this whole-key assignment. Restore its
        // residual inventory and continue the retained indivisible search.
        for (int d = fixed; d < demands.size(); d++) {
            budget.check(); left[d] = demands.get(d).amount();
            for (int s = 0; s < width; s++) {
                budget.check(); int edge = d * width + s;
                if (flow[edge] > 0) remaining[s] = remaining[s].add(BigInteger.valueOf(flow[edge]));
                flow[edge] = 0;
            }
        }
        flowing = false; undoFixed(); return false;
    }

    /** On the first failed branch, test the splittable relaxation on original
     * inventory. Its Hall deficit can reject all whole-key assignments. A
     * successful relaxation resumes the exact branch; it never emits a plan.
     * Delay this check so a successful first assignment keeps its old cost. */
    private boolean startRelaxation(boolean fromFlow) {
        if (relaxationTried || !partialEligibility || fixed == 0) return false;
        relaxationTried = relaxing = flowing = true; failedFlow = fromFlow; unmet = 0;
        resetFlow();
        return true;
    }

    /** Preserve Hall feedback first. Once splittable flow succeeds (or all
     * edges are allowed), a separate bound can detect whole-key fragmentation.
     * It uses original supply counts, never the failed branch's residuals. */
    private boolean startPacking() {
        if (packingTried) return false;
        packingTried = true;
        if (partialEligibility) {
            cohorts = StateEntityCohorts.create(demands, fixed, supplies, allowed, budget);
            if (cohorts != null) return true;
        }
        packing = StateEntityPacking.create(demands, fixed, supplies, budget);
        return packing != null;
    }

    private void resetFlow() {
        for (int s = 0; s < width; s++) { budget.check(); remaining[s] = supplies.get(s).count(); }
        for (int d = 0; d < demands.size(); d++) {
            budget.check(); left[d] = demands.get(d).amount();
            for (int s = 0; s < width; s++) { budget.check(); flow[d * width + s] = 0; }
        }
    }

    private void undoFixed() {
        depth--;
        int s = chosen[depth]; remaining[s] = remaining[s].add(BigInteger.valueOf(demands.get(depth).amount()));
        chosen[depth] = -1;
    }

    private boolean augment(int root) {
        Arrays.fill(parentDemand, -1); Arrays.fill(parentSupply, -1);
        int head = 0, tail = 0; queue[tail++] = root; parentDemand[root] = -2;
        while (head < tail) {
            int d = queue[head++];
            for (int ordinal = 0; ordinal < width; ordinal++) {
                int s = priority == null ? ordinal : priority[d * width + ordinal];
                budget.check();
                if (!allowed[d * width + s] || parentSupply[s] != -1) continue;
                parentSupply[s] = d;
                if (remaining[s].signum() > 0) {
                    long use = remaining[s].min(BigInteger.valueOf(left[root])).longValueExact();
                    for (int v = d; v != root; ) {
                        budget.check(); int previous = parentDemand[v];
                        use = Math.min(use, flow[v * width + previous]); v = parentSupply[previous];
                    }
                    remaining[s] = remaining[s].subtract(BigInteger.valueOf(use));
                    int to = s;
                    for (int v = d; ; ) {
                        budget.check(); flow[v * width + to] = Math.addExact(flow[v * width + to], use);
                        if (v == root) break;
                        to = parentDemand[v]; flow[v * width + to] -= use; v = parentSupply[to];
                    }
                    left[root] -= use; return true;
                }
                for (int other = relaxing ? 0 : fixed; other < demands.size(); other++) {
                    budget.check();
                    if (parentDemand[other] != -1 || flow[other * width + s] == 0) continue;
                    parentDemand[other] = s; queue[tail++] = other;
                }
            }
        }
        return false;
    }

    boolean feasible() { return done && feasible; }
    int failedSlot() { return failedSlot; }

    /** Request-local recovery evidence, not an entity shortage or a global
     * count bound. Pending/cancelled searches and closed storage export none. */
    Fragmentation fragmentation() {
        if (!done || feasible || !wholeKeyFailure || memory == 0) return null;
        if (cohorts != null) {
            var deficit = cohorts.deficit();
            if (deficit != null) return new Fragmentation(deficit.slots(), deficit.threshold(), deficit.available());
        }
        if (demands.size() > 32) return null;
        var slots = new ArrayList<Integer>();
        for (var demand : demands) { budget.check(); slots.add(demand.slot()); }
        return new Fragmentation(List.copyOf(slots), 0, 0);
    }

    /** Failed exact or relaxed residual flow exposes a deficient set.
     * Whole-key branch failures alone are not Hall deficits. Keep feedback
     * small even when the allocation itself is much larger. */
    Deficit deficit() {
        if (!done || feasible || memory == 0 || (!totalShortage && !hallDeficit) || demands.size() > 32) return null;
        var slots = new ArrayList<Integer>();
        BigInteger required = BigInteger.ZERO, available = BigInteger.ZERO;
        for (int d = 0; d < demands.size(); d++) {
            budget.check();
            if (!totalShortage && parentDemand[d] == -1) continue;
            slots.add(demands.get(d).slot());
            required = required.add(BigInteger.valueOf(demands.get(d).amount()));
        }
        for (int s = 0; s < width; s++) for (int d = 0; d < demands.size(); d++) {
            budget.check();
            if ((totalShortage || parentDemand[d] != -1) && allowed[d * width + s]) {
                available = available.add(supplies.get(s).count()); break;
            }
        }
        return required.compareTo(available) > 0 ? new Deficit(List.copyOf(slots), required, available) : null;
    }
    List<StateEntityAllocation.Pick<K>> result() { if (!feasible()) throw new IllegalStateException("No matching witness"); return result; }

    @Override public void close() {
        if (packing != null) packing.close(); packing = null;
        if (cohorts != null) cohorts.close(); cohorts = null;
        demands.clear(); supplies.clear(); result.clear(); Arrays.fill(remaining, null);
        budget.release(memory); memory = 0; done = true;
    }
}
