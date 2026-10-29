// SPDX-License-Identifier: MPL-2.0
package org.voxelregion.scheduler;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Single world-thread, round-robin dispatcher. No job owns a private tick allowance. */
public final class RegionScheduler {
    public enum Step { PROGRESSED, WAITING, COMPLETE, PAUSED }
    public enum State { RUNNING, COMPLETE, PAUSED, CANCELLED, FAILED }
    public interface Job {
        /** Perform bounded work. Also debit the supplied shared budget for actual backend work. */
        Step step(TickBudget budget) throws Exception;
        /** Release tickets/leases; do not roll back already committed world changes. */
        void cancel() throws Exception;
    }
    public record Status(State state, String detail) {}
    public record TickReport(int dispatches, long work, long elapsedNanos, boolean overranTime) {}

    private record Entry(UUID id, Job job) {}
    private final ArrayDeque<Entry> ready = new ArrayDeque<>();
    private final Map<UUID, Entry> active = new LinkedHashMap<>();
    private final Map<UUID, Status> states = new LinkedHashMap<>();
    private final Thread owner = Thread.currentThread();
    private final LongSupplier clock;
    private final int maxJobs;
    private boolean ticking;

    public RegionScheduler(int maxJobs) { this(maxJobs, System::nanoTime); }
    public RegionScheduler(int maxJobs, LongSupplier clock) {
        if (maxJobs <= 0) throw new IllegalArgumentException("Invalid capacity");
        this.maxJobs = maxJobs; this.clock = Objects.requireNonNull(clock);
    }
    private void owner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("World-thread dispatcher used off-thread");
    }
    public void submit(UUID id, Job job) {
        owner(); Objects.requireNonNull(id); Objects.requireNonNull(job);
        if (ticking) throw new IllegalStateException("Submit between ticks");
        if (states.containsKey(id)) throw new IllegalArgumentException("Operation already known");
        if (states.size() >= maxJobs) throw new IllegalStateException("Operation capacity reached; archive terminal records first");
        Entry entry = new Entry(id, job); active.put(id, entry); ready.addLast(entry);
        states.put(id, new Status(State.RUNNING, ""));
    }
    public Status status(UUID id) { owner(); return states.get(id); }
    public void resume(UUID id) {
        owner();
        if (ticking) throw new IllegalStateException("Resume between ticks");
        Status status = states.get(id);
        if (status == null || status.state != State.PAUSED) throw new IllegalStateException("Not paused");
        states.put(id, new Status(State.RUNNING, "")); ready.addLast(active.get(id));
    }
    public void cancel(UUID id) throws Exception {
        owner();
        if (ticking) throw new IllegalStateException("Cancel between ticks");
        Entry entry = active.remove(id);
        if (entry == null) return;
        ready.remove(entry);
        try { entry.job.cancel(); states.put(id, new Status(State.CANCELLED, "")); }
        catch (Exception failure) {
            states.put(id, new Status(State.FAILED, failure.toString())); throw failure;
        }
    }
    /** Caller must durably archive terminal operation evidence before forgetting in-memory status. */
    public void forget(UUID id) {
        owner();
        if (ticking || active.containsKey(id)) throw new IllegalStateException("Operation still active");
        states.remove(id);
    }
    public TickReport tick(TickBudget.Limits limits) {
        owner();
        if (ticking) throw new IllegalStateException("Recursive tick");
        TickBudget budget = new TickBudget(limits, clock);
        ticking = true;
        int dispatches = 0, waiting = 0;
        try {
            while (!ready.isEmpty() && !budget.exhausted()) {
                // A dispatcher debit prevents a broken zero-cost job from spinning forever.
                if (!budget.trySpend(1, 0, 0)) break;
                Entry entry = ready.removeFirst(); dispatches++;
                try {
                    Step step = Objects.requireNonNull(entry.job.step(budget), "Null step");
                    switch (step) {
                        case PROGRESSED -> { ready.addLast(entry); waiting = 0; }
                        case WAITING -> { ready.addLast(entry); waiting++; }
                        case COMPLETE -> { active.remove(entry.id); states.put(entry.id, new Status(State.COMPLETE, "")); waiting = 0; }
                        case PAUSED -> { states.put(entry.id, new Status(State.PAUSED, "")); waiting = 0; }
                    }
                } catch (Exception failure) {
                    active.remove(entry.id);
                    try { entry.job.cancel(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                    states.put(entry.id, new Status(State.FAILED, failure.toString())); waiting = 0;
                }
                if (waiting >= ready.size()) break;
            }
        } finally { ticking = false; }
        return new TickReport(dispatches, budget.workSpent(), budget.elapsedNanos(), budget.overranTime());
    }
}
