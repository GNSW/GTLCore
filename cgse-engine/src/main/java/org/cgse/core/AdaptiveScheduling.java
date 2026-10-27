// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Optional execution hints. These observations never own inventory or retire physical obligations. */
final class AdaptiveScheduling<K> {
    private static final int MAX_NODES = 512, MAX_SAMPLES = 512, WORK_PER_TICK = 4096;
    private static final long INITIAL_WINDOW = 100;
    // Receipts can run on either side of the CPU tick. A one-tick observation
    // cannot distinguish immediate output from a short machine/transport delay.
    private static final long MIN_WINDOW = 4;
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
        if (node.pressureRuns > 0 ? node.pressureSamples < node.probeAfter : clock < node.relaxedUntil) return false;
        long window = window(node);
        // An overdue window relaxes only extra growth reservation. It does not
        // imply a missing return or a permanently discovered machine limit.
        return node.pending.isEmpty() || clock - node.pending.peekFirst().started <= window;
    }

    boolean congested(String recipe) { return enabled && nodes.containsKey(recipe) && !growing(recipe); }

    long capacityReserve(String recipe) {
        Node<K> node = nodes.get(recipe);
        if (node == null) return 0;
        long reserve = clock < node.relaxedUntil ? node.capacityRuns : 0;
        if (node.pressureRuns > 0 && node.minElapsed > 0) {
            // A queued batch is not a physical parallel limit. Estimate the
            // useful working batch from observed throughput and a short return
            // interval, with one tick of receipt/CPU ordering uncertainty.
            BigInteger interval = BigInteger.valueOf(node.pressureElapsed).add(BigInteger.ONE);
            BigInteger useful = BigInteger.valueOf(node.pressureRuns)
                    .multiply(BigInteger.valueOf(node.minElapsed).add(BigInteger.ONE));
            reserve = Math.max(reserve, ExactAmounts.capped(useful.add(interval).subtract(BigInteger.ONE).divide(interval)));
        }
        return reserve;
    }

    void blocked(String recipe) {
        Node<K> node = nodes.get(recipe);
        if (node == null) return;
        if (!node.pending.isEmpty()) {
            // A packet limit alone is not machine saturation. Require both a
            // confirmed partial grant and a later busy probe while it runs.
            if (node.limitedPending > 0) { node.pressureAt = clock; node.capacityRuns = node.limitedRuns; }
            return;
        }
        if (node.pressureRuns > 0) node.pressureSamples = 0;
        if (node.blockedSince < 0) node.blockedSince = clock;
        else if (clock - node.blockedSince >= Math.min(5, window(node))) {
            // A single busy/energy/provider probe is not a measured throughput
            // limit. Require a later failed probe before lending growth stock.
            node.relaxedUntil = later(5);
            node.blockedSince = clock;
        }
    }

    void begin(long id, String recipe, long runs, Map<K, Long> outputs) {
        begin(id, recipe, runs, outputs, false);
    }

    void begin(long id, String recipe, long runs, Map<K, Long> outputs, boolean limited) {
        Node<K> node = nodes.get(recipe);
        if (!enabled || node == null || samples.size() >= MAX_SAMPLES) return;
        Map<K, Long> remaining = new HashMap<>();
        for (K key : node.keys) {
            long amount = outputs.getOrDefault(key, 0L);
            if (amount > 0) remaining.put(key, amount);
        }
        if (remaining.isEmpty()) return;
        Sample<K> sample = new Sample<>(node, runs, clock, remaining);
        sample.limited = limited;
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
        if (!sample.confirmed && sample.limited) sample.node.limitedPending++;
        if (sample.limited) sample.node.limitedRuns = sample.runs;
        sample.confirmed = true;
        Node<K> node = sample.node;
        if (node.pressureRuns > 0 && node.pressureSamples >= node.probeAfter && sample.runs > node.pressureBatch) {
            sample.probe = true;
            node.pressureSamples = 0;
            node.pressureBatch = sample.runs;
        }
        sample.node.blockedSince = -1;
        if (sample.remaining.isEmpty()) complete(id, sample);
    }

    void reject(long id) {
        Sample<K> sample = samples.remove(id);
        if (sample != null) {
            sample.node.pending.remove(sample);
            if (sample.confirmed && sample.limited) sample.node.limitedPending--;
        }
    }

    private void complete(long id, Sample<K> sample) {
        samples.remove(id);
        Node<K> node = sample.node;
        node.pending.remove(sample);
        long elapsed = sample.elapsed;
        node.minElapsed = node.minElapsed == 0 ? elapsed : Math.min(node.minElapsed, elapsed);
        if (sample.limited) {
            node.limitedPending--;
            if (node.pressureAt >= sample.started && node.pressureAt != node.lastPressureWindow) {
                node.lastPressureWindow = node.pressureAt;
                node.busyWindows = Math.min(2, node.busyWindows + 1);
                if (node.busyWindows >= 2) node.relaxedUntil = later(window(node));
            } else if (node.pressureAt < sample.started) node.busyWindows = 0;
        }
        // Compare a genuinely larger workload only after latency grew beyond
        // tick quantization. Equal latency is successful parallel scaling.
        boolean expanded = node.lastRuns > 0 && sample.runs / 2 >= node.lastRuns
                && node.lastElapsed >= MIN_WINDOW && elapsed - node.lastElapsed > 2;
        boolean plateau = expanded && BigInteger.valueOf(sample.runs)
                .multiply(BigInteger.valueOf(node.lastElapsed - 1))
                .compareTo(BigInteger.valueOf(node.lastRuns).multiply(BigInteger.valueOf(elapsed).add(BigInteger.ONE))) <= 0;
        boolean pressured = node.pressureRuns > 0;
        if (pressured) {
            boolean faster = elapsed < MIN_WINDOW || BigInteger.valueOf(sample.runs)
                    .multiply(BigInteger.valueOf(node.pressureElapsed - 1))
                    .compareTo(BigInteger.valueOf(node.pressureRuns).multiply(BigInteger.valueOf(elapsed).add(BigInteger.ONE))) > 0;
            if (faster) {
                node.pressureRuns = 0;
                node.minElapsed = elapsed;
                node.relaxedUntil = 0;
                node.pressureSamples = 0;
                node.probeAfter = 8;
            } else {
                if (node.pressureSamples < node.probeAfter * 2) node.pressureSamples++;
                if (sample.probe) node.probeAfter = Math.min(32, node.probeAfter * 2);
                // If a larger-batch invitation cannot be used across coupled
                // stages, verified returns still bound how long old evidence
                // can restrict scheduling. Forget and measure again.
                if (node.pressureSamples >= node.probeAfter * 2) {
                    node.pressureRuns = 0;
                    node.minElapsed = elapsed;
                    node.relaxedUntil = 0;
                    node.pressureSamples = 0;
                    node.probeAfter = 8;
                }
            }
        } else if (plateau) {
            node.pressureRuns = node.lastRuns;
            node.pressureElapsed = node.lastElapsed;
            node.pressureBatch = sample.runs;
            node.pressureSamples = 0;
        }
        if (node.baseline > 0 && elapsed > window(node)) node.relaxedUntil = later(window(node));
        // Keep fractional ticks, but do not learn queue growth as new service
        // capacity. Bounded growth probes remain possible after real returns.
        if (node.baseline == 0) node.baseline = elapsed;
        else if (node.pressureRuns == 0) node.baseline += (elapsed - node.baseline) / 4;
        node.lastRuns = sample.runs;
        node.lastElapsed = elapsed;
    }

    private long later(long duration) { return clock > Long.MAX_VALUE - duration ? Long.MAX_VALUE : clock + duration; }
    private long window(Node<K> node) { return node.baseline == 0 ? INITIAL_WINDOW :
            Math.max(MIN_WINDOW, (long) Math.ceil(node.baseline * 2)); }

    private static final class Node<K> {
        final Set<K> keys = new HashSet<>();
        final Deque<Sample<K>> pending = new ArrayDeque<>();
        double baseline;
        long lastRuns, lastElapsed, relaxedUntil, blockedSince = -1;
        long pressureRuns, pressureElapsed, pressureBatch;
        int pressureSamples, probeAfter = 8;
        int limitedPending, busyWindows;
        long pressureAt = -1, lastPressureWindow = -1, limitedRuns, capacityRuns, minElapsed;
    }

    private static final class Sample<K> {
        final Node<K> node;
        final long runs, started;
        final Map<K, Long> remaining;
        long elapsed;
        boolean confirmed, probe, limited;
        Sample(Node<K> node, long runs, long started, Map<K, Long> remaining) {
            this.node = node; this.runs = runs; this.started = started; this.remaining = remaining;
        }
    }
}
