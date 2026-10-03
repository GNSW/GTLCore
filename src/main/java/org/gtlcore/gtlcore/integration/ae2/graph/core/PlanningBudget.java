package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** One request's cumulative limits; a work slice never creates another budget. */
public final class PlanningBudget {

    // Sixteen ticks per existing work unit. Small literal inspections need not
    // cost as much as big-integer elimination. Integer ticks keep scheduling
    // reproducible; wall time, cancellation and memory remain independent caps.
    static final int WORK_SCALE = 16;

    enum Operation {

        SCAN(4),
        INTEGER(8),
        RATIONAL(64);

        final int ticks;

        Operation(int ticks) {
            this.ticks = ticks;
        }
    }

    public enum Limit {
        TIMEOUT,
        SEARCH_LIMIT,
        MEMORY_LIMIT,
        GRAPH_LIMIT,
        QUEUE_LIMIT
    }

    public enum Phase {
        QUEUED,
        SNAPSHOT,
        BUILD,
        ANALYSE,
        SOLVE,
        VERIFY,
        COMPLETE
    }

    private final long timeoutNanos;
    private final long maxNodes;
    private final long maxBytes;
    private final BooleanSupplier cancelled;
    private final LongSupplier clock;
    private final long submitted;
    private final AtomicLong started = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong nodes = new AtomicLong();
    // Branch quanta run contiguously on one worker. A shared thread counter
    // avoids creating weak ThreadLocal keys for every order on the long-lived
    // worker pool; per-order cumulative limits remain in nodes below.
    private static final ThreadLocal<long[]> THREAD_NODES = ThreadLocal.withInitial(() -> new long[1]);
    private volatile boolean countThreadWork;
    private final AtomicLong reservedBytes = new AtomicLong();
    private final AtomicLong peakBytes = new AtomicLong();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private volatile Phase phase = Phase.QUEUED;
    private volatile String failureDetail = "";
    private final Deque<String> diagnostics = new ArrayDeque<>();
    private static final ThreadMXBean CPU_CLOCK = ManagementFactory.getThreadMXBean();
    private final AtomicLongArray phaseNanos = new AtomicLongArray(Phase.values().length);
    private final AtomicLongArray phaseCpuNanos = new AtomicLongArray(Phase.values().length);
    private final ThreadLocal<WorkScope> currentScope = new ThreadLocal<>();
    private final AtomicLong activeWorkers = new AtomicLong(), peakWorkers = new AtomicLong(), maxSlice = new AtomicLong();
    private volatile boolean measuring;
    private volatile CountProof.Journal proofJournal;
    private final Map<String, long[]> strategyTotals = new LinkedHashMap<>();

    /** Optional bounded certificate export; normal planning does not allocate proof archives. */
    public void proofJournal(CountProof.Journal journal) {
        proofJournal = journal;
    }

    CountProof.Journal proofJournal() {
        return proofJournal;
    }

    public PlanningBudget(long milliseconds, long maxNodes, BooleanSupplier cancelled) {
        this(milliseconds, maxNodes, 64L << 20, cancelled, System::nanoTime);
    }

    public PlanningBudget(long milliseconds, long maxNodes, long maxBytes, BooleanSupplier cancelled, LongSupplier clock) {
        if (milliseconds < 0 || maxNodes <= 0 || maxBytes <= 0) throw new IllegalArgumentException("Invalid planning limits");
        this.timeoutNanos = Math.multiplyExact(milliseconds, 1_000_000L);
        this.maxNodes = maxNodes;
        this.maxBytes = maxBytes;
        this.cancelled = cancelled;
        this.clock = clock;
        this.submitted = clock.getAsLong();
    }

    public void start() {
        if (started.get() == Long.MIN_VALUE) started.compareAndSet(Long.MIN_VALUE, clock.getAsLong());
    }

    public void phase(Phase next) {
        if (next != Phase.QUEUED) start();
        if (measuring) {
            WorkScope scope = currentScope.get();
            if (scope != null && scope.current != next) {
                scope.flush();
                scope.current = next;
            }
        }
        phase = next;
    }

    public Phase phase() {
        return phase;
    }

    public void check() {
        chargeTicks(WORK_SCALE);
    }

    /** Account bounded independent checker work without a second per-unit loop. */
    void charge(long units) {
        if (units < 0) throw new IllegalArgumentException("Negative work");
        if (units > Long.MAX_VALUE / WORK_SCALE) throw exhausted(Limit.SEARCH_LIMIT, "work_accounting_overflow");
        chargeTicks(units * WORK_SCALE);
    }

    /** Returns charged ticks so a local continuation uses the same cost model. */
    int operation(Operation operation, int bits) {
        int words = (int) Math.max(1, Math.min(32, (Math.max(0, bits) + 63L) / 64));
        int ticks = operation.ticks * (operation == Operation.SCAN ? 1 : words);
        chargeTicks(ticks);
        return ticks;
    }

    private void chargeTicks(long ticks) {
        checkpoint();
        if (countThreadWork) THREAD_NODES.get()[0] += ticks;
        long total = nodes.addAndGet(ticks);
        if (total < 0 || units(total) > maxNodes) throw exhausted(Limit.SEARCH_LIMIT, "cumulative_work=" + units(total) + "/" + maxNodes);
    }

    static long units(long ticks) {
        return ticks / WORK_SCALE + (ticks % WORK_SCALE == 0 ? 0 : 1);
    }

    /** Per-thread accounting prevents concurrent branches charging one another's work. */
    long threadWork() {
        countThreadWork = true;
        return units(THREAD_NODES.get()[0]);
    }

    public Exhausted exhausted(Limit limit, String detail) {
        failureDetail = detail;
        note("limit", limit + ": " + detail);
        return new Exhausted(limit, detail);
    }

    public void failureDetail(String detail) {
        failureDetail = detail;
    }

    public String failureDetail() {
        return failureDetail;
    }

    /** A bounded trace of strategy transitions, not a record for every search node. */
    public synchronized void note(String stage, String detail) {
        String entry = stage + "@" + nodes() + ": " + detail;
        diagnostics.addLast(entry.length() > 768 ? entry.substring(0, 768) + "..." : entry);
        while (diagnostics.size() > 32) diagnostics.removeFirst();
    }

    public synchronized String diagnostics() {
        return String.join(" | ", diagnostics);
    }

    /** Cancellation/time check without charging another search state. */
    public void checkpoint() {
        if (Thread.currentThread().isInterrupted() || cancelRequested.get() || cancelled.getAsBoolean())
            throw new CancellationException("Graph planning cancelled");
        start();
        if (timeoutNanos != 0 && clock.getAsLong() - started.get() >= timeoutNanos)
            throw exhausted(Limit.TIMEOUT, "active_wall_ms=" + runningWallNanos() / 1_000_000L + "/" + timeoutNanos / 1_000_000L);
    }

    public void reserve(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative memory reservation");
        long current = reservedBytes.addAndGet(bytes);
        if (current < 0 || current > maxBytes) {
            reservedBytes.addAndGet(-bytes);
            throw exhausted(Limit.MEMORY_LIMIT, "reserved_bytes=" + reservedBytes.get() + "; requested_bytes=" + bytes + "; limit_bytes=" + maxBytes);
        }
        peakBytes.accumulateAndGet(current, Math::max);
    }

    /** Optional strategies may decline a workspace without exhausting the order. */
    boolean tryReserve(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative memory reservation");
        checkpoint();
        long previous;
        do {
            previous = reservedBytes.get();
            if (bytes > maxBytes - previous) return false;
        } while (!reservedBytes.compareAndSet(previous, previous + bytes));
        peakBytes.accumulateAndGet(previous + bytes, Math::max);
        return true;
    }

    public void release(long bytes) {
        if (bytes < 0 || reservedBytes.addAndGet(-bytes) < 0) throw new IllegalStateException("Unbalanced planning memory reservation");
    }

    public void cancel() {
        cancelRequested.set(true);
    }

    public long nodes() {
        return units(nodes.get());
    }

    long remainingWork() {
        return Math.max(0, maxNodes - nodes());
    }

    long availableBytes() {
        return Math.max(0, maxBytes - reservedBytes.get());
    }

    public long reservedBytes() {
        return reservedBytes.get();
    }

    public long peakBytes() {
        return peakBytes.get();
    }

    public long waitingNanos() {
        return (started.get() == Long.MIN_VALUE ? clock.getAsLong() : started.get()) - submitted;
    }

    public long elapsedNanos() {
        return clock.getAsLong() - submitted;
    }

    public long runningWallNanos() {
        return started.get() == Long.MIN_VALUE ? 0 : clock.getAsLong() - started.get();
    }

    public void enableMetrics() {
        measuring = true;
    }

    boolean metricsEnabled() {
        return measuring;
    }

    /** Worker-local charged work, not changes to another concurrent branch's counter. */
    synchronized void strategy(String name, long work, long nanos) {
        if (!measuring) return;
        long[] totals = strategyTotals.computeIfAbsent(name, ignored -> new long[3]);
        totals[0] += work;
        totals[1] += nanos;
        totals[2]++;
    }

    private synchronized Map<String, StrategyMetrics> strategies() {
        Map<String, StrategyMetrics> result = new LinkedHashMap<>();
        strategyTotals.forEach((name, totals) -> result.put(name, new StrategyMetrics(totals[0], totals[1], totals[2])));
        return Map.copyOf(result);
    }

    public WorkScope work(Phase initial) {
        if (!measuring) return null;
        if (currentScope.get() != null) throw new IllegalStateException("Nested planning timing scope");
        WorkScope scope = new WorkScope(initial);
        currentScope.set(scope);
        peakWorkers.accumulateAndGet(activeWorkers.incrementAndGet(), Math::max);
        return scope;
    }

    /** Active wall sums across workers. CPU time excludes waiting and is -1 when unavailable. */
    public Metrics metrics() {
        WorkScope scope = currentScope.get();
        if (scope != null) scope.flush();
        Map<Phase, Long> wall = new EnumMap<>(Phase.class), cpu = new EnumMap<>(Phase.class);
        boolean supported = CPU_CLOCK.isCurrentThreadCpuTimeSupported() && CPU_CLOCK.isThreadCpuTimeEnabled();
        for (Phase value : Phase.values()) {
            wall.put(value, phaseNanos.get(value.ordinal()));
            cpu.put(value, supported ? phaseCpuNanos.get(value.ordinal()) : -1L);
        }
        return new Metrics(Map.copyOf(wall), Map.copyOf(cpu), peakWorkers.get(), maxSlice.get(), peakBytes(), strategies());
    }

    public record StrategyMetrics(long chargedWork, long activeNanos, long steps) {}

    public record Metrics(Map<Phase, Long> activeNanos, Map<Phase, Long> cpuNanos, long peakActiveWorkers,
                          long maxWorkSliceNanos, long peakReservedBytes, Map<String, StrategyMetrics> strategies) {}

    public final class WorkScope implements AutoCloseable {

        private Phase current;
        private final long beginning = System.nanoTime();
        private long last = beginning, cpu = cpuNow();
        private boolean closed;

        private WorkScope(Phase initial) {
            current = initial;
        }

        private void flush() {
            long now = System.nanoTime(), cpuTime = cpuNow();
            phaseNanos.addAndGet(current.ordinal(), now - last);
            if (cpu >= 0 && cpuTime >= cpu) phaseCpuNanos.addAndGet(current.ordinal(), cpuTime - cpu);
            last = now;
            cpu = cpuTime;
        }

        @Override
        public void close() {
            if (closed) return;
            flush();
            maxSlice.accumulateAndGet(System.nanoTime() - beginning, Math::max);
            currentScope.remove();
            activeWorkers.decrementAndGet();
            closed = true;
        }
    }

    private static long cpuNow() {
        return CPU_CLOCK.isCurrentThreadCpuTimeSupported() && CPU_CLOCK.isThreadCpuTimeEnabled() ? CPU_CLOCK.getCurrentThreadCpuTime() : -1;
    }

    public static final class Exhausted extends RuntimeException {

        private final Limit limit;

        public Exhausted() {
            this(Limit.SEARCH_LIMIT);
        }

        public Exhausted(Limit limit) {
            this(limit, "");
        }

        public Exhausted(Limit limit, String detail) {
            super("GRAPH_" + limit + (detail.isEmpty() ? "" : " (" + detail + ")"));
            this.limit = limit;
        }

        public Limit limit() {
            return limit;
        }
    }
}
