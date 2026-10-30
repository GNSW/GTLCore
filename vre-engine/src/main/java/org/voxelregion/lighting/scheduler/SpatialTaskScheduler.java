// SPDX-License-Identifier: MPL-2.0
package org.voxelregion.lighting.scheduler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * Orders overlapping chunk work without blocking a caller or an executor thread on another job.
 * Each instance belongs to one world; neither chunk coordinates nor worker contexts cross worlds.
 * A worker context is leased to one job until its returned stage completes, including publication.
 * The executor is supplied and remains owned by the caller.
 *
 * <p>Accesses to a chunk are ordered at submission: writes depend on preceding readers and writers,
 * reads depend on preceding writers, and reads may overlap reads. Dependency completion releases
 * ordering even after failure; callers must retain failed receipts and arrange recovery themselves.
 * Neither cancelling nor completing a returned future can release a running job's spatial lease.
 *
 * <p>Pending update coalescing belongs to the lighting adapter: submit one immutable generation of
 * a chunk's accumulated changes, then accumulate subsequent changes in a new generation. This
 * scheduler never merges away work or makes a later update share an earlier in-flight receipt.
 */
public final class SpatialTaskScheduler<W> {
    /** Inclusive chunk-coordinate rectangle. */
    public record ChunkRect(int minX, int minZ, int maxX, int maxZ) {
        public ChunkRect {
            if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("Empty chunk rectangle");
        }

        public static ChunkRect around(int x, int z, int radius) {
            if (radius < 0) throw new IllegalArgumentException("Negative chunk radius");
            return new ChunkRect(Math.subtractExact(x, radius), Math.subtractExact(z, radius),
                    Math.addExact(x, radius), Math.addExact(z, radius));
        }

        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        long columns() {
            long width = (long) maxX - minX + 1;
            long depth = (long) maxZ - minZ + 1;
            return width > Long.MAX_VALUE / depth ? Long.MAX_VALUE : width * depth;
        }
    }

    /** A null read or write rectangle means no access of that kind. Writes imply reads. */
    public record Footprint(ChunkRect reads, ChunkRect writes) {
        public Footprint {
            if (reads == null && writes == null) throw new IllegalArgumentException("Empty footprint");
        }

        public static Footprint exclusive(ChunkRect area) {
            return new Footprint(null, Objects.requireNonNull(area));
        }
    }

    @FunctionalInterface
    public interface Work<W> {
        /** Complete only after all reads, writes, and publication using this lease have finished. */
        CompletionStage<Void> run(W worker) throws Exception;
    }

    public record Receipt(long sequence, CompletionStage<Void> completion) {}

    public record Statistics(long submitted, long completed, long failed, long cancelled, int outstanding,
                             int running, int ready, int trackedColumns, int peakRunning,
                             boolean closing) {}

    private static final int MAX_FOOTPRINT_COLUMNS = 1_048_576;
    private final Object lock = new Object();
    private final Executor executor;
    private final IntFunction<? extends W> workerFactory;
    private final int maxOutstanding;
    private final Map<Long, Cell> columns = new HashMap<>();
    private final Map<Long, Entry> outstanding = new LinkedHashMap<>();
    private final ArrayDeque<Entry> ready = new ArrayDeque<>();
    private final ArrayDeque<Worker> available = new ArrayDeque<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private final AtomicInteger pumping = new AtomicInteger();
    private long submitted;
    private long completed;
    private long failed;
    private long cancelled;
    private int running;
    private int settling;
    private int peakRunning;
    private boolean closing;
    private Throwable firstFailure;

    public SpatialTaskScheduler(Executor executor, int parallelism, int maxOutstanding,
                                IntFunction<? extends W> workerFactory) {
        this.executor = Objects.requireNonNull(executor);
        this.workerFactory = Objects.requireNonNull(workerFactory);
        if (parallelism <= 0 || maxOutstanding < parallelism) {
            throw new IllegalArgumentException("Invalid scheduler capacity");
        }
        this.maxOutstanding = maxOutstanding;
        for (int i = 0; i < parallelism; i++) available.addLast(new Worker(i));
    }

    public Receipt submit(Footprint footprint, Work<W> work) {
        Objects.requireNonNull(footprint);
        Objects.requireNonNull(work);
        validateArea(footprint.reads);
        validateArea(footprint.writes);
        Entry entry;
        Set<Entry> dependencies = new HashSet<>();
        synchronized (lock) {
            if (closing) throw new RejectedExecutionException("Lighting scheduler is closing");
            if (outstanding.size() >= maxOutstanding) {
                throw new RejectedExecutionException("Lighting scheduler capacity reached");
            }
            entry = new Entry(++submitted, work);
            if (footprint.writes != null) reserve(entry, footprint.writes, true, null, dependencies);
            if (footprint.reads != null) reserve(entry, footprint.reads, false, footprint.writes, dependencies);
            outstanding.put(entry.sequence, entry);
        }
        CompletableFuture<?>[] previous = dependencies.stream().map(e -> e.released)
                .toArray(CompletableFuture<?>[]::new);
        CompletableFuture.allOf(previous).thenRun(() -> ready(entry));
        return new Receipt(entry.sequence, entry.completion.minimalCompletionStage());
    }

    /** Convenience entry for a light operation owning a full square (normally radius 2). */
    public Receipt submitChunk(int chunkX, int chunkZ, int radius, Work<W> work) {
        return submit(Footprint.exclusive(ChunkRect.around(chunkX, chunkZ, radius)), work);
    }

    /**
     * Snapshot completion barrier for unfinished operations touching this area. Includes queued
     * generations and active publication stages, but does not reject or delay later submissions.
     * Schedule an unload itself as exclusive work if later work must not pass the unload.
     */
    public CompletionStage<Void> fence(ChunkRect area) {
        Objects.requireNonNull(area);
        validateArea(area);
        Set<Entry> entries = new HashSet<>();
        synchronized (lock) {
            for (long z = area.minZ; z <= area.maxZ; z++) {
                for (long x = area.minX; x <= area.maxX; x++) {
                    Cell cell = columns.get(key((int) x, (int) z));
                    if (cell == null) continue;
                    entries.addAll(cell.occupants);
                }
            }
        }
        return completions(entries).minimalCompletionStage();
    }

    /** Snapshot barrier over all currently unfinished operations; later work is not included. */
    public CompletionStage<Void> fenceAll() {
        List<Entry> entries;
        synchronized (lock) { entries = new ArrayList<>(outstanding.values()); }
        return completions(entries).minimalCompletionStage();
    }

    /** Stop admission and asynchronously drain accepted jobs. Never interrupts an active writer. */
    public CompletionStage<Void> closeAsync() {
        return closeAsync(false);
    }

    /**
     * Optionally cancel accepted work that has not started. Cancellation never releases an active
     * writer, and the returned stage waits for all active jobs and accepted receipts to settle.
     */
    public CompletionStage<Void> closeAsync(boolean cancelQueued) {
        boolean finish;
        Throwable failure;
        List<Assignment> cancel = new ArrayList<>();
        synchronized (lock) {
            closing = true;
            if (cancelQueued) {
                for (Entry entry : outstanding.values()) {
                    if (!entry.started && !entry.cancelledBeforeStart) {
                        entry.cancelledBeforeStart = true;
                        cancel.add(entry.assignment == null ? new Assignment(entry, null) : entry.assignment);
                    }
                }
            }
            finish = outstanding.isEmpty() && settling == 0;
            failure = firstFailure;
        }
        for (Assignment assignment : cancel) {
            finish(assignment, new CancellationException("Lighting scheduler stopped before task start"));
        }
        if (finish) complete(stopped, failure);
        return stopped.minimalCompletionStage();
    }

    public Statistics statistics() {
        synchronized (lock) {
            return new Statistics(submitted, completed, failed, cancelled, outstanding.size(), running,
                    ready.size(), columns.size(), peakRunning, closing);
        }
    }

    private void reserve(Entry entry, ChunkRect area, boolean write, ChunkRect skip,
                         Set<Entry> dependencies) {
        for (long z = area.minZ; z <= area.maxZ; z++) {
            for (long x = area.minX; x <= area.maxX; x++) {
                if (skip != null && skip.contains((int) x, (int) z)) continue;
                long key = key((int) x, (int) z);
                Cell cell = columns.computeIfAbsent(key, ignored -> new Cell());
                cell.occupants.add(entry);
                if (cell.writer != null) dependencies.add(cell.writer);
                if (write) {
                    dependencies.addAll(cell.readers);
                    cell.readers.clear();
                    cell.writer = entry;
                } else {
                    cell.readers.add(entry);
                }
                entry.touches.add(new Touch(key, cell));
            }
        }
    }

    private void ready(Entry entry) {
        synchronized (lock) {
            if (entry.finished || entry.cancelledBeforeStart) return;
            ready.addLast(entry);
        }
        pump();
    }

    /** Trampoline also supports a direct executor without recursive dependency-chain stack growth. */
    private void pump() {
        if (pumping.getAndIncrement() != 0) return;
        int seen = 1;
        do {
            Assignment assignment;
            while ((assignment = takeReady()) != null) {
                Assignment dispatch = assignment;
                try {
                    executor.execute(() -> run(dispatch));
                } catch (Throwable failure) {
                    finish(dispatch, failure);
                }
            }
            seen = pumping.addAndGet(-seen);
        } while (seen != 0);
    }

    private Assignment takeReady() {
        synchronized (lock) {
            if (available.isEmpty()) return null;
            Entry entry;
            do {
                entry = ready.pollFirst();
                if (entry == null) return null;
            } while (entry.finished || entry.cancelledBeforeStart);
            Worker worker = available.removeFirst();
            running++;
            peakRunning = Math.max(peakRunning, running);
            Assignment assignment = new Assignment(entry, worker);
            entry.assignment = assignment;
            return assignment;
        }
    }

    private void run(Assignment assignment) {
        synchronized (lock) {
            if (assignment.entry.finished || assignment.entry.cancelledBeforeStart) return;
            assignment.entry.started = true;
        }
        try {
            Worker worker = assignment.worker;
            if (worker.context == null) {
                worker.context = Objects.requireNonNull(workerFactory.apply(worker.index), "Null worker");
            }
            CompletionStage<Void> done = Objects.requireNonNull(
                    assignment.entry.work.run(worker.context), "Null lighting completion");
            done.whenComplete((ignored, failure) -> finish(assignment, failure));
        } catch (Throwable failure) {
            finish(assignment, failure);
        }
    }

    private void finish(Assignment assignment, Throwable failure) {
        Entry entry = assignment.entry;
        synchronized (lock) {
            if (entry.finished) return;
            entry.finished = true;
            for (Touch touch : entry.touches) {
                Cell cell = touch.cell;
                if (cell.writer == entry) cell.writer = null;
                cell.readers.remove(entry);
                cell.occupants.remove(entry);
                if (cell.occupants.isEmpty()) columns.remove(touch.key, cell);
            }
            outstanding.remove(entry.sequence);
            if (assignment.worker != null) running--;
            settling++;
            completed++;
            if (entry.cancelledBeforeStart) {
                cancelled++;
            } else if (failure != null) {
                failed++;
                if (firstFailure == null) firstFailure = failure;
                // A failed engine may retain partially initialized scratch state. Do not reuse it.
                assignment.worker.context = null;
            }
            if (assignment.worker != null) available.addLast(assignment.worker);
        }
        // Ordering and pool release must not depend on arbitrary caller completion callbacks.
        entry.released.complete(null);
        pump();
        complete(entry.completion, failure);
        boolean terminal;
        Throwable terminalFailure;
        synchronized (lock) {
            settling--;
            terminal = closing && outstanding.isEmpty() && settling == 0;
            terminalFailure = firstFailure;
        }
        if (terminal) complete(stopped, terminalFailure);
    }

    private CompletableFuture<Void> completions(Iterable<Entry> entries) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Entry entry : entries) futures.add(entry.completion);
        return CompletableFuture.allOf(futures.toArray(CompletableFuture<?>[]::new));
    }

    private static void complete(CompletableFuture<Void> future, Throwable failure) {
        if (failure == null) future.complete(null); else future.completeExceptionally(failure);
    }

    private static void validateArea(ChunkRect area) {
        if (area != null && area.columns() > MAX_FOOTPRINT_COLUMNS) {
            throw new IllegalArgumentException("Chunk footprint exceeds bounded scheduler capacity");
        }
    }

    private static long key(int x, int z) { return (x & 0xffffffffL) | ((long) z << 32); }

    private final class Cell {
        Entry writer;
        final Set<Entry> readers = new HashSet<>();
        // Include superseded generations, whose failure is not inherited by an ordering-only tail.
        final Set<Entry> occupants = new HashSet<>();
    }

    private final class Entry {
        final long sequence;
        final Work<W> work;
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        final CompletableFuture<Void> released = new CompletableFuture<>();
        final List<Touch> touches = new ArrayList<>();
        boolean finished;
        boolean started;
        boolean cancelledBeforeStart;
        Assignment assignment;

        Entry(long sequence, Work<W> work) { this.sequence = sequence; this.work = work; }
    }

    private final class Touch {
        final long key;
        final Cell cell;
        Touch(long key, Cell cell) { this.key = key; this.cell = cell; }
    }

    private final class Worker {
        final int index;
        W context;
        Worker(int index) { this.index = index; }
    }

    private final class Assignment {
        final Entry entry;
        final Worker worker;
        Assignment(Entry entry, Worker worker) { this.entry = entry; this.worker = worker; }
    }
}
