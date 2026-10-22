// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Sparse readiness notifications for the optional component-ordered scan. */
final class CountScheduleReady<K> implements AutoCloseable {
    private final List<SequenceSummary<K>> actions;
    private final int[] component;
    private final Set<K> external;
    private final PlanningBudget budget;
    private final Map<K, List<Integer>> waiting = new HashMap<>();
    private final PriorityQueue<Integer> ready;
    private final BitSet queued = new BitSet();
    private int[] live;
    private int first;
    private long memory;

    CountScheduleReady(List<SequenceSummary<K>> actions, int[] component, Set<K> external,
                       BigInteger[] remaining, PlanningBudget budget) {
        this.actions = actions; this.component = component; this.external = external; this.budget = budget;
        ready = new PriorityQueue<>((a, b) -> {
            budget.check(); int order = Integer.compare(component[a], component[b]);
            return order == 0 ? Integer.compare(a, b) : order;
        });
        long terms = 0; int maximum = 0;
        for (int i = 0; i < actions.size(); i++) {
            budget.check(); terms += actions.get(i).required().size(); maximum = Math.max(maximum, component[i]);
        }
        long bytes = 1024L + 128L * actions.size() + 128L * terms + 4L * (maximum + 1);
        if (!budget.tryReserve(bytes)) return;
        memory = bytes;
        try {
            live = new int[maximum + 1];
            for (int i = 0; i < actions.size(); i++) for (K key : actions.get(i).required().keySet()) {
                budget.check();
                if (!external.contains(key)) waiting.computeIfAbsent(key, ignored -> new ArrayList<>()).add(i);
            }
            reset(remaining);
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    boolean available() { return memory != 0; }

    void reset(BigInteger[] remaining) {
        if (!available()) return;
        ready.clear(); queued.clear(); Arrays.fill(live, 0); first = 0;
        for (int i = 0; i < remaining.length; i++) {
            budget.check();
            if (remaining[i].signum() > 0) { live[component[i]]++; enqueue(i); }
        }
    }

    int choose(Map<K, BigInteger> held, BigInteger[] remaining, BitSet visited) {
        while (first < live.length && live[first] == 0) { budget.check(); first++; }
        while (!ready.isEmpty()) {
            budget.check(); int next = ready.peek();
            if (component[next] > first) return -1;
            ready.remove(); queued.clear(next);
            if (remaining[next].signum() == 0 || visited.get(next)) continue;
            boolean enabled = true;
            for (var port : actions.get(next).required().entrySet()) {
                budget.check();
                if (!external.contains(port.getKey()) && held.getOrDefault(port.getKey(), BigInteger.ZERO).compareTo(port.getValue()) < 0) {
                    enabled = false; break;
                }
            }
            if (enabled) return next;
        }
        return -1;
    }

    void changed(K key) {
        for (int next : waiting.getOrDefault(key, List.of())) { budget.check(); enqueue(next); }
    }

    void consumed(int recipe, BigInteger left) { if (left.signum() == 0) live[component[recipe]]--; }
    private void enqueue(int recipe) { if (!queued.get(recipe)) { queued.set(recipe); ready.add(recipe); } }

    @Override public void close() { budget.release(memory); memory = 0; }
}
