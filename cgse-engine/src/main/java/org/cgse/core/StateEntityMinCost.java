// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToIntBiFunction;
import java.util.function.ToLongBiFunction;

/** Bounded negative-cycle cancellation on an already feasible entity flow.
 * Equal-size whole-key slots may use unit cohorts, without splitting any slot.
 * Every atomic update preserves slot totals and owned supply. A cutoff keeps
 * the last feasible allocation; no infeasibility or optimality claim escapes. */
final class StateEntityMinCost<K> implements AutoCloseable {
    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }
    private final PlanningBudget budget;
    private final List<StateEntityAllocation.Demand> demands;
    private final List<StateEntityAllocation.Supply<K>> supplies;
    private final boolean[] allowed;
    private final long[] flow;
    private final BigInteger[] remaining, prices, distance;
    private final BigInteger[] wholeRemaining, cohortStock;
    private final BigInteger quantum;
    private final int[] wholeChoices;
    private final int[] parent, cycle;
    private final int width, reservoir;
    private final long allowance, started;
    private long memory;
    private int cycles;
    private boolean done;

    static <K> StateEntityMinCost<K> create(List<StateEntityAllocation.Demand> demands,
            List<StateEntityAllocation.Supply<K>> supplies, boolean[] allowed, long[] flow, BigInteger[] remaining,
            ToIntBiFunction<StateEntityAllocation.Demand, K> preference,
            ToLongBiFunction<StateEntityAllocation.Demand, K> cost, PlanningBudget budget) {
        return create(demands, supplies, allowed, flow, remaining, null, preference, cost, budget);
    }

    static <K> StateEntityMinCost<K> createWhole(List<StateEntityAllocation.Demand> demands,
            List<StateEntityAllocation.Supply<K>> supplies, boolean[] allowed, int[] choices, BigInteger[] remaining,
            ToIntBiFunction<StateEntityAllocation.Demand, K> preference,
            ToLongBiFunction<StateEntityAllocation.Demand, K> cost, PlanningBudget budget) {
        return create(demands, supplies, allowed, null, remaining, choices, preference, cost, budget);
    }

    private static <K> StateEntityMinCost<K> create(List<StateEntityAllocation.Demand> demands,
            List<StateEntityAllocation.Supply<K>> supplies, boolean[] allowed, long[] flow, BigInteger[] remaining, int[] choices,
            ToIntBiFunction<StateEntityAllocation.Demand, K> preference,
            ToLongBiFunction<StateEntityAllocation.Demand, K> cost, PlanningBudget budget) {
        long allowance = Math.min(4096, budget.remainingWork() / 32);
        if (demands.size() < 2 || demands.size() > 32 || supplies.size() < 2 || supplies.size() > 256 ||
                allowed.length > 1024 || allowance < 128) return null;
        long cohort = 0;
        if (choices != null && choices.length != demands.size()) return null;
        for (var demand : demands) {
            if (choices == null) { if (!demand.mixed() && demand.amount() > 1) return null; }
            else {
                if (demand.mixed() || demand.amount() <= 1 || cohort != 0 && cohort != demand.amount()) return null;
                cohort = demand.amount();
            }
        }
        long bytes = 1024L + 160L * allowed.length + 256L * (demands.size() + supplies.size() + 1);
        if (choices != null) bytes += 128L + 8L * allowed.length + 96L * supplies.size();
        if (!budget.tryReserve(bytes)) return null;
        try { return new StateEntityMinCost<>(demands, supplies, allowed, flow, remaining, choices, cohort, preference, cost, budget, allowance, bytes); }
        catch (Stop declined) { budget.release(bytes); return null; }
        catch (RuntimeException | Error failure) { budget.release(bytes); throw failure; }
    }

    private StateEntityMinCost(List<StateEntityAllocation.Demand> demands, List<StateEntityAllocation.Supply<K>> supplies,
            boolean[] allowed, long[] flow, BigInteger[] remaining, int[] choices, long cohort,
            ToIntBiFunction<StateEntityAllocation.Demand, K> preference,
            ToLongBiFunction<StateEntityAllocation.Demand, K> cost, PlanningBudget budget, long allowance, long bytes) {
        this.demands = demands; this.supplies = supplies; this.allowed = allowed;
        this.budget = budget; this.allowance = allowance; started = budget.nodes(); memory = bytes;
        width = supplies.size(); reservoir = demands.size() + width;
        wholeChoices = choices; wholeRemaining = choices == null ? null : remaining;
        quantum = choices == null ? null : BigInteger.valueOf(cohort);
        this.flow = choices == null ? flow : new long[allowed.length];
        this.remaining = choices == null ? remaining : new BigInteger[width];
        cohortStock = choices == null ? null : new BigInteger[width];
        if (choices != null) {
            for (int s = 0; s < width; s++) {
                charge(); cohortStock[s] = supplies.get(s).count().divide(quantum);
                this.remaining[s] = remaining[s].divide(quantum);
            }
            for (int d = 0; d < choices.length; d++) { charge(); this.flow[d * width + choices[d]] = 1; }
        }
        flow = this.flow;
        prices = new BigInteger[flow.length]; distance = new BigInteger[reservoir + 1];
        parent = new int[distance.length]; cycle = new int[distance.length];
        var ranks = new int[flow.length]; var losses = new long[flow.length];
        BigInteger total = BigInteger.ZERO; long maxLoss = 0; boolean penalized = false;
        for (int d = 0; d < demands.size(); d++) {
            charge(); var demand = demands.get(d); total = total.add(BigInteger.valueOf(amount(d)));
            for (int s = 0; s < width; s++) {
                charge(); int edge = d * width + s; if (!allowed[edge]) continue;
                // Includes the protected-rule score/cost callbacks in this
                // optional allowance, not just the residual-graph visits.
                if (budget.nodes() - started + 16 > allowance) throw new Stop();
                K state = supplies.get(s).state();
                ranks[edge] = preference == null ? 0 : preference.applyAsInt(demand, state);
                losses[edge] = cost == null ? 0 : cost.applyAsLong(demand, state);
                if (ranks[edge] < 0 || losses[edge] < 0) throw new Stop();
                maxLoss = Math.max(maxLoss, losses[edge]);
                penalized |= flow[edge] > 0 && (ranks[edge] > 0 || losses[edge] > 0);
            }
        }
        // Nonnegative prices make a zero-penalty allocation already sufficient.
        if (!penalized) throw new Stop();
        BigInteger scale = total.multiply(BigInteger.valueOf(maxLoss)).add(BigInteger.ONE);
        for (int edge = 0; edge < flow.length; edge++) {
            charge(); if (allowed[edge]) prices[edge] = scale.multiply(BigInteger.valueOf(ranks[edge])).add(BigInteger.valueOf(losses[edge]));
        }
    }

    boolean step() {
        budget.checkpoint(); if (done) return true;
        try {
            Arrays.fill(distance, BigInteger.ZERO); Arrays.fill(parent, -1); int changed = -1;
            for (int round = 0; round < distance.length; round++) {
                changed = -1;
                for (int d = 0; d < demands.size(); d++) for (int s = 0; s < width; s++) {
                    charge(); int edge = d * width + s, node = demands.size() + s; if (!allowed[edge]) continue;
                    if (flow[edge] < amount(d) && relax(d, node, prices[edge])) changed = node;
                    if (flow[edge] > 0 && relax(node, d, prices[edge].negate())) changed = d;
                }
                for (int s = 0; s < width; s++) {
                    charge(); int node = demands.size() + s;
                    if (remaining[s].signum() > 0 && relax(node, reservoir, BigInteger.ZERO)) changed = reservoir;
                    if (remaining[s].compareTo(stock(s)) < 0 && relax(reservoir, node, BigInteger.ZERO)) changed = node;
                }
                if (changed < 0) { done = true; return true; }
            }
            int start = changed;
            for (int i = 0; i < distance.length; i++) { charge(); start = parent[start]; if (start < 0) throw new Stop(); }
            int at = start, size = 0; BigInteger delta = null, price = BigInteger.ZERO;
            do {
                charge(); if (size == cycle.length || parent[at] < 0) throw new Stop();
                cycle[size++] = at; int from = parent[at]; BigInteger capacity;
                if (at == reservoir) capacity = remaining[from - demands.size()];
                else if (from == reservoir) capacity = stock(at - demands.size()).subtract(remaining[at - demands.size()]);
                else if (from < demands.size()) {
                    int edge = from * width + at - demands.size();
                    capacity = BigInteger.valueOf(amount(from) - flow[edge]); price = price.add(prices[edge]);
                } else {
                    int edge = at * width + from - demands.size();
                    capacity = BigInteger.valueOf(flow[edge]); price = price.subtract(prices[edge]);
                }
                delta = delta == null ? capacity : delta.min(capacity); at = from;
            } while (at != start);
            if (price.signum() >= 0 || delta == null || delta.signum() <= 0) throw new Stop();
            long amount = delta.longValueExact();
            BigInteger physicalDelta = quantum == null ? null : quantum.multiply(delta);
            // The checked cycle preserves conservation. Keep this update atomic:
            // cancellation / optional work limits apply before or after it.
            for (int i = 0; i < size; i++) {
                int to = cycle[i], from = parent[to];
                if (to == reservoir) {
                    int supply = from - demands.size(); remaining[supply] = remaining[supply].subtract(delta);
                    if (wholeRemaining != null) wholeRemaining[supply] = wholeRemaining[supply].subtract(physicalDelta);
                } else if (from == reservoir) {
                    int supply = to - demands.size(); remaining[supply] = remaining[supply].add(delta);
                    if (wholeRemaining != null) wholeRemaining[supply] = wholeRemaining[supply].add(physicalDelta);
                } else if (from < demands.size()) {
                    flow[from * width + to - demands.size()] += amount;
                    if (wholeChoices != null) wholeChoices[from] = to - demands.size();
                }
                else flow[to * width + from - demands.size()] -= amount;
            }
            if (++cycles == 1) budget.note("state_matching", "improved_assignment; negative_proofs=false");
            if (cycles == 8) done = true;
        } catch (Stop cutoff) { done = true; }
        return done;
    }

    private boolean relax(int from, int to, BigInteger cost) {
        BigInteger value = distance[from].add(cost);
        if (value.compareTo(distance[to]) >= 0) return false;
        distance[to] = value; parent[to] = from; return true;
    }
    private long amount(int demand) { return wholeChoices == null ? demands.get(demand).amount() : 1; }
    private BigInteger stock(int supply) { return cohortStock == null ? supplies.get(supply).count() : cohortStock[supply]; }
    private void charge() { if (budget.nodes() - started >= allowance) throw new Stop(); budget.check(); }
    @Override public void close() {
        budget.release(memory); memory = 0; Arrays.fill(prices, null); Arrays.fill(distance, null);
        if (cohortStock != null) { Arrays.fill(cohortStock, null); Arrays.fill(remaining, null); }
        done = true;
    }
}
