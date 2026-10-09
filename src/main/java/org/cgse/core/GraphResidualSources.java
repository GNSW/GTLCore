// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.function.Predicate;

/** Incremental catalog admission around a failed support. No negative inference leaves this view. */
final class GraphResidualSources<K> implements AutoCloseable {
    private static final int PAGE_SIZE = 4, WINDOW_SIZE = 32;
    private static final long WINDOW_BYTES = 128L * WINDOW_SIZE, MAX_WINDOW_BYTES = 8 * WINDOW_BYTES;
    private final GraphCompiler<K> compiler;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final Set<String> excluded;
    private final Map<String, GraphRecipe<K>> pool;
    private final Predicate<GraphRecipe<K>> admit;
    private final PlanningBudget budget;
    private final Map<K, Boundary> boundaries = new LinkedHashMap<>();
    private final Deque<Boundary> pending = new ArrayDeque<>(), deferred = new ArrayDeque<>();
    private final PriorityQueue<Choice<K>> best = new PriorityQueue<>((a, b) -> compare(b, a));
    private final List<Choice<K>> declined = new ArrayList<>();
    private final Map<Boundary, Window> windows = new LinkedHashMap<>();
    private Boundary active;
    private Window window;
    private int cursor, candidates, emitted, added, maximum;
    private long memory, windowBytes, scanned;
    private boolean scanning;

    private final class Boundary {
        final K key;
        Boundary(K key) { this.key = key; }
    }
    private final class Window {
        final List<Choice<K>> choices = new ArrayList<>();
        final int generation = boundaries.size();
        long bytes;
        boolean more;
        Window(long bytes) { this.bytes = bytes; }
    }
    private record Choice<K>(GraphRecipe<K> recipe, int rawMissing, int unavailable, int covered, double pressure, int ordinal) {}

    GraphResidualSources(GraphCompiler<K> compiler, Map<K, Long> stock, Set<K> external, Set<String> excluded,
                         Map<String, GraphRecipe<K>> pool, Predicate<GraphRecipe<K>> admit, PlanningBudget budget) {
        this.compiler = compiler;
        this.stock = stock;
        this.external = external;
        this.excluded = excluded;
        this.pool = pool;
        this.admit = admit;
        this.budget = budget;
    }

    void offer(K key) {
        budget.operation(PlanningBudget.Operation.SCAN, 1);
        if (external.contains(key) || boundaries.containsKey(key) || compiler.producers(key).isEmpty()) return;
        long bytes = boundaries.isEmpty() ? 1280 : 256;
        if (boundaries.size() >= 512 || !budget.tryReserve(bytes)) return;
        memory += bytes;
        var boundary = new Boundary(key);
        boundaries.put(key, boundary);
        pending.addLast(boundary);
    }

    boolean pending() { return active != null || !pending.isEmpty() || !deferred.isEmpty(); }

    void begin(int maximum) {
        this.maximum = maximum;
        added = 0;
        pending.addAll(deferred);
        deferred.clear();
    }

    boolean step() {
        budget.checkpoint();
        if (added >= maximum) return true;
        if (active == null) {
            if (pending.isEmpty()) return true;
            active = pending.removeFirst();
            cursor = candidates = emitted = 0;
            best.clear();
            declined.clear();
            window = windows.remove(active);
            if (window != null) {
                // Coverage scores depend on the admitted resource boundary.
                // Stock, exclusions and compiler identity belong to this request.
                window.choices.removeIf(choice -> pool.containsKey(choice.recipe().id()));
                if (window.generation != boundaries.size() || window.more && window.choices.size() < PAGE_SIZE) {
                    release(window);
                    window = null;
                }
            }
            scanning = window == null;
            if (scanning) window = prepareWindow(compiler.producers(active.key).size());
        }
        var sources = compiler.producers(active.key);
        if (scanning && cursor < sources.size()) {
            budget.operation(PlanningBudget.Operation.SCAN, 1);
            var recipe = sources.get(cursor++);
            scanned++;
            if (pool.containsKey(recipe.id()) || excluded.contains(recipe.id())) return false;
            int rawMissing = 0, unavailable = 0, covered = 0;
            double pressure = 0;
            for (var input : recipe.inputs().entrySet()) {
                budget.operation(PlanningBudget.Operation.SCAN, 1);
                if (external.contains(input.getKey())) continue;
                long available = stock.getOrDefault(input.getKey(), 0L);
                if (available < input.getValue()) {
                    unavailable++;
                    if (compiler.producers(input.getKey()).isEmpty()) rawMissing++;
                }
                pressure += (double) input.getValue() / Math.max(1, available);
            }
            for (K output : recipe.executionOutputs().keySet()) {
                budget.operation(PlanningBudget.Operation.SCAN, 1);
                if (boundaries.containsKey(output)) covered++;
            }
            var choice = new Choice<>(recipe, rawMissing, unavailable, covered, pressure, cursor);
            candidates++;
            // Retain a few future pages, but still admit only four choices per
            // boundary visit. A bounded heap avoids a longer insertion scan.
            int capacity = window.bytes == 0 ? PAGE_SIZE : WINDOW_SIZE;
            if (best.size() < capacity) best.add(choice);
            else if (compare(choice, best.peek()) < 0) {
                best.remove();
                best.add(choice);
            }
            return false;
        }
        if (scanning) {
            window.more = candidates > best.size();
            window.choices.addAll(best);
            window.choices.sort(GraphResidualSources::compare);
            best.clear();
            scanning = false;
        }
        while (emitted < PAGE_SIZE && !window.choices.isEmpty() && added < maximum) {
            budget.operation(PlanningBudget.Operation.SCAN, 1);
            var choice = window.choices.remove(0);
            if (pool.containsKey(choice.recipe().id()) || excluded.contains(choice.recipe().id())) continue;
            emitted++;
            boolean accepted = admit.test(choice.recipe());
            if (!accepted && windowBytes > 0) {
                // Optional ranking storage must not deny a real recipe's
                // reservation. Keep only the current four-choice page, release
                // cached future pages, then retry once without their pressure.
                releaseRankings();
                accepted = admit.test(choice.recipe());
            }
            if (accepted) {
                added++;
                for (K key : choice.recipe().inputs().keySet()) offer(key);
            } else declined.add(choice);
        }
        // A small admission grant can split the page. Preserve its remaining
        // choices and its place in the fair boundary order until the next turn.
        if (emitted < PAGE_SIZE && !window.choices.isEmpty() && added >= maximum) return false;
        window.choices.addAll(declined);
        window.choices.sort(GraphResidualSources::compare);
        boolean remaining = window.more || !window.choices.isEmpty();
        if (remaining) deferred.addLast(active);
        if (!window.choices.isEmpty() && window.bytes != 0) windows.put(active, window);
        else release(window);
        window = null;
        active = null;
        declined.clear();
        return false;
    }

    private Window prepareWindow(int sources) {
        if (sources > PAGE_SIZE) {
            while (windowBytes + WINDOW_BYTES > MAX_WINDOW_BYTES) evictWindow();
            // Eviction changes only an optional ranking cache, never the pool,
            // pending boundaries or a count model's proof/continuation scope.
            while (!budget.tryReserve(WINDOW_BYTES)) {
                if (windows.isEmpty()) return new Window(0);
                evictWindow();
            }
            windowBytes += WINDOW_BYTES;
            memory += WINDOW_BYTES;
            return new Window(WINDOW_BYTES);
        }
        return new Window(0);
    }

    private void evictWindow() {
        var iterator = windows.entrySet().iterator();
        var oldest = iterator.next().getValue();
        iterator.remove();
        release(oldest);
    }

    private void dropWindows() {
        for (var cached : windows.values()) release(cached);
        windows.clear();
    }

    /** Reclaim optional pages before letting them deny the downstream count model. */
    long releaseRankings() {
        long before = windowBytes;
        dropWindows();
        if (window != null) {
            while (best.size() > PAGE_SIZE) best.remove();
            int remaining = PAGE_SIZE - emitted;
            if (window.choices.size() > remaining) {
                window.choices.subList(remaining, window.choices.size()).clear();
                window.more = true;
            }
            releaseBytes(window);
        }
        return before;
    }

    private void releaseBytes(Window value) {
        budget.release(value.bytes);
        memory -= value.bytes;
        windowBytes -= value.bytes;
        value.bytes = 0;
    }

    private void release(Window value) {
        value.choices.clear();
        releaseBytes(value);
    }

    private static int compare(Choice<?> a, Choice<?> b) {
        int order = Integer.compare(a.rawMissing, b.rawMissing);
        if (order == 0) order = Integer.compare(b.covered, a.covered);
        if (order == 0) order = Integer.compare(a.unavailable, b.unavailable);
        if (order == 0) order = Double.compare(a.pressure, b.pressure);
        return order == 0 ? Integer.compare(a.ordinal, b.ordinal) : order;
    }

    long scanned() { return scanned; }
    int added() { return added; }

    @Override public void close() {
        pending.clear();
        deferred.clear();
        boundaries.clear();
        best.clear();
        declined.clear();
        windows.clear();
        window = null;
        active = null;
        budget.release(memory);
        memory = 0;
        windowBytes = 0;
    }
}
