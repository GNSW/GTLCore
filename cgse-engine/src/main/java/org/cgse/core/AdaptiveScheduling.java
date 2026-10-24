// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Optional execution hints. These observations never own inventory or retire physical obligations. */
final class AdaptiveScheduling<K> {
    private static final int MAX_NODES = 512, MAX_SAMPLES = 512, WORK_PER_TICK = 4096;
    private static final long INITIAL_WINDOW = 100;
    private final Map<String, Node<K>> nodes = new HashMap<>();
    private final Map<Long, Sample<K>> samples = new HashMap<>();
    private boolean enabled = true, ticking;
    private long hostTick, clock, generation;
    private int work;

    void enabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        reset();
    }

    boolean enabled() { return enabled; }
    boolean observing() { return !samples.isEmpty(); }
    long generation() { return generation; }

    void reset() {
        generation++;
        nodes.clear();
        samples.clear();
        ticking = false;
        clock = 0;
        work = 0;
    }

    void tick(long tick, boolean running) {
        if (!running || !enabled) { ticking = false; return; }
        if (ticking && hostTick == tick) return;
        // Count active CPU ticks, not wall time or gaps while a CPU/chunk is offline.
        hostTick = tick;
        ticking = true;
        if (clock != Long.MAX_VALUE) clock++;
        work = WORK_PER_TICK;
    }

    boolean spend(int amount) {
        if (!enabled || amount < 0 || amount > work) return false;
        work -= amount;
        return true;
    }

    void watch(String recipe, Set<K> keys) {
        if (!enabled || nodes.size() >= MAX_NODES && !nodes.containsKey(recipe)) return;
        Node<K> node = nodes.computeIfAbsent(recipe, ignored -> new Node<>());
        node.keys.addAll(keys);
    }

    boolean growing(String recipe) {
        if (!enabled) return false;
        Node<K> node = nodes.get(recipe);
        if (node == null) return false;
        if (clock < node.relaxedUntil) return false;
        long window = window(node);
        // An overdue window relaxes only extra growth reservation. It does not
        // imply a missing return or a permanently discovered machine limit.
        return node.pending.isEmpty() || clock - node.pending.peekFirst().started <= window;
    }

    void blocked(String recipe) {
        Node<K> node = nodes.get(recipe);
        if (node != null && node.pending.isEmpty()) node.relaxedUntil = later(5);
    }

    void begin(long id, String recipe, long runs, Map<K, Long> outputs) {
        Node<K> node = nodes.get(recipe);
        if (!enabled || node == null || samples.size() >= MAX_SAMPLES) return;
        Map<K, Long> remaining = new HashMap<>();
        for (K key : node.keys) {
            long amount = outputs.getOrDefault(key, 0L);
            if (amount > 0) remaining.put(key, amount);
        }
        if (remaining.isEmpty()) return;
        Sample<K> sample = new Sample<>(node, runs, clock, remaining);
        samples.put(id, sample);
        node.pending.addLast(sample);
    }

    void returned(long id, K key, long amount) {
        Sample<K> sample = samples.get(id);
        if (sample == null) return;
        Long remaining = sample.remaining.get(key);
        if (remaining == null) return;
        if (amount >= remaining) sample.remaining.remove(key);
        else sample.remaining.put(key, remaining - amount);
        if (sample.remaining.isEmpty()) {
            sample.elapsed = Math.max(1, clock - sample.started);
            if (sample.confirmed) complete(id, sample);
        }
    }

    void confirm(long id) {
        Sample<K> sample = samples.get(id);
        if (sample == null) return;
        sample.confirmed = true;
        if (sample.remaining.isEmpty()) complete(id, sample);
    }

    void reject(long id) {
        Sample<K> sample = samples.remove(id);
        if (sample != null) sample.node.pending.remove(sample);
    }

    private void complete(long id, Sample<K> sample) {
        samples.remove(id);
        Node<K> node = sample.node;
        node.pending.remove(sample);
        long elapsed = sample.elapsed;
        boolean doubled = node.lastRuns > 0 && sample.runs / 2 >= node.lastRuns;
        boolean noGain = doubled && BigInteger.valueOf(sample.runs).multiply(BigInteger.valueOf(node.lastElapsed))
                .compareTo(BigInteger.valueOf(node.lastRuns).multiply(BigInteger.valueOf(elapsed))) <= 0;
        if (noGain) node.slowWindows++;
        else node.slowWindows = 0;
        if (node.baseline > 0 && elapsed > window(node) || node.slowWindows >= 2) {
            node.relaxedUntil = later(window(node));
            node.slowWindows = 0;
        }
        // Overflow-free EWMA; a partial or rejected dispatch never trains it.
        node.baseline = node.baseline == 0 ? elapsed : node.baseline + (elapsed - node.baseline) / 4;
        node.lastRuns = sample.runs;
        node.lastElapsed = elapsed;
    }

    private long later(long duration) { return clock > Long.MAX_VALUE - duration ? Long.MAX_VALUE : clock + duration; }
    private long window(Node<K> node) { return node.baseline == 0 ? INITIAL_WINDOW :
            node.baseline > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : Math.max(2, node.baseline * 2); }

    private static final class Node<K> {
        final Set<K> keys = new HashSet<>();
        final Deque<Sample<K>> pending = new ArrayDeque<>();
        long baseline, lastRuns, lastElapsed, relaxedUntil;
        int slowWindows;
    }

    private static final class Sample<K> {
        final Node<K> node;
        final long runs, started;
        final Map<K, Long> remaining;
        long elapsed;
        boolean confirmed;
        Sample(Node<K> node, long runs, long started, Map<K, Long> remaining) {
            this.node = node; this.runs = runs; this.started = started; this.remaining = remaining;
        }
    }
}
