// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;

/** Optional, bounded, exclusive cost attribution. It never changes a planning allowance. */
public final class PlanningCostTrace {

    public enum Origin {
        UNATTRIBUTED, COORDINATOR, SOURCE_GRAPH, BOOTSTRAP, STOCK_VIEW,
        SUPPORT_NEIGHBORHOOD, ALLOCATION, INTEGER_COUNTS, STOCK_WITNESS, SEED_OPTIMIZATION, FALLBACK, PLAN_REUSE
    }

    public enum Stage { OTHER, PREPARE, SEARCH, RESTORE, SCHEDULE, ASSEMBLE, VERIFY, FORCE_CHECK, CLEANUP }

    public record Context(Origin origin, Stage stage) {}

    /** Work is in exact ticks (sixteen per work unit), so nested fractional charges are not rounded twice. */
    public record Cost(long searchTicks, long compilationTicks, long activeNanos) {}

    /** Active scopes are published when they yield/close; this is not a stop-the-world worker snapshot. */
    public record Snapshot(Map<Context, Cost> costs) {
        public Snapshot { costs = Map.copyOf(costs); }
    }

    private static final int STAGES = Stage.values().length;
    private final AtomicLongArray totals = new AtomicLongArray(Origin.values().length * STAGES * 3);
    private final ThreadLocal<Scope> current = new ThreadLocal<>();

    Scope enter(Origin origin, Stage stage) {
        Scope parent = current.get();
        Context context = new Context(origin != null ? origin : parent == null ? Origin.UNATTRIBUTED : parent.context.origin(),
                stage != null ? stage : parent == null ? Stage.OTHER : parent.context.stage());
        long now = System.nanoTime();
        if (parent != null) parent.flush(now);
        Scope scope = new Scope(parent, context, now);
        current.set(scope);
        return scope;
    }

    Context context() {
        Scope scope = current.get();
        return scope == null ? null : scope.context;
    }

    void charge(long ticks, boolean compilation) {
        Scope scope = current.get();
        if (scope == null) add(index(Origin.UNATTRIBUTED, Stage.OTHER) + (compilation ? 1 : 0), ticks);
        else if (compilation) scope.compilation = sum(scope.compilation, ticks);
        else scope.search = sum(scope.search, ticks);
    }

    public Snapshot snapshot() {
        Map<Context, Cost> costs = new LinkedHashMap<>();
        for (Origin origin : Origin.values()) for (Stage stage : Stage.values()) {
            int i = index(origin, stage);
            long search = totals.get(i), compilation = totals.get(i + 1), nanos = totals.get(i + 2);
            if (search != 0 || compilation != 0 || nanos != 0)
                costs.put(new Context(origin, stage), new Cost(search, compilation, nanos));
        }
        return new Snapshot(costs);
    }

    private static int index(Origin origin, Stage stage) { return (origin.ordinal() * STAGES + stage.ordinal()) * 3; }
    private static long sum(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
    private void add(int index, long value) {
        if (value != 0) totals.getAndUpdate(index, old -> sum(old, value));
    }

    final class Scope implements AutoCloseable {
        private final Scope parent;
        private final Thread owner = Thread.currentThread();
        private Context context;
        private long since, search, compilation;
        private boolean closed;

        private Scope(Scope parent, Context context, long now) {
            this.parent = parent;
            this.context = context;
            since = now;
        }

        void select(Origin origin, Stage stage) {
            checkOwner();
            flush(System.nanoTime());
            context = new Context(origin == null ? context.origin() : origin, stage);
        }

        private void checkOwner() {
            if (closed || Thread.currentThread() != owner || current.get() != this)
                throw new IllegalStateException("Cost scope must close/change on its active worker");
        }

        private void flush(long now) {
            int i = index(context.origin(), context.stage());
            add(i, search);
            add(i + 1, compilation);
            add(i + 2, Math.max(0, now - since));
            search = compilation = 0;
            since = now;
        }

        @Override
        public void close() {
            if (closed) return;
            checkOwner();
            long now = System.nanoTime();
            flush(now);
            closed = true;
            if (parent == null) current.remove();
            else {
                parent.since = now;
                current.set(parent);
            }
        }
    }
}
