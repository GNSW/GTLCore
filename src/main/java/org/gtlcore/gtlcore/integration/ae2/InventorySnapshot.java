package org.gtlcore.gtlcore.integration.ae2;

import org.gtlcore.gtlcore.integration.ae2.crafting.compiled.MaxFastFingerprint;

import appeng.api.config.FuzzyMode;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ObjLongConsumer;

/** An immutable inventory version. Only workers build or wait for its fuzzy index. */
public final class InventorySnapshot {

    public static final InventorySnapshot EMPTY = new InventorySnapshot(new State(null, null, new KeyCounter()), 0);
    private static final ThreadPoolExecutor COMPACTOR = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), task -> {
                Thread thread = new Thread(task, "GTL inventory snapshots");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });

    private volatile State state;
    private final int depth;

    private InventorySnapshot(State state, int depth) {
        this.state = state;
        this.depth = depth;
    }

    public long get(AEKey key) {
        return index().get(key);
    }

    public List<AEKey> findFuzzy(AEKey key) {
        var entries = index().findFuzzy(key, FuzzyMode.IGNORE_ALL);
        var keys = new ArrayList<AEKey>(entries.size());
        for (var entry : entries) keys.add(entry.getKey());
        return keys;
    }

    public MaxFastFingerprint.Result fingerprint() {
        return MaxFastFingerprint.ofKeyCounter(index());
    }

    /** Never called by publication or request construction on the server thread. */
    public void prepare() {
        index();
    }

    private KeyCounter index() {
        State ready = state;
        if (ready.index != null) return ready.index;
        synchronized (this) {
            ready = state;
            if (ready.index != null) return ready.index;
            var changes = new ArrayList<Changes>();
            State cursor = ready;
            while (cursor.index == null) {
                changes.add(cursor.changes);
                cursor = cursor.parent.state;
            }
            var result = new KeyCounter();
            result.addAll(cursor.index);
            for (int i = changes.size() - 1; i >= 0; i--) {
                changes.get(i).forEach((key, amount) -> {
                    if (amount > 0) result.set(key, amount);
                    else result.remove(key);
                });
            }
            // Publish the complete index and release the history in one write.
            // Readers already traversing that history own their local references.
            state = new State(null, null, result);
            return result;
        }
    }

    private record State(InventorySnapshot parent, Changes changes, KeyCounter index) {}

    /** Append-only while AE computes its existing delta; ownership transfers at publication. */
    public static final class Changes {

        private static final int CHUNK_SIZE = 64;
        private final List<AEKey[]> keys = new ArrayList<>();
        private final List<long[]> amounts = new ArrayList<>();
        private int size;
        private boolean sealed;

        public void add(AEKey key, long amount) {
            if (sealed) throw new IllegalStateException("Published inventory delta is immutable");
            int offset = size % CHUNK_SIZE;
            if (offset == 0) {
                keys.add(new AEKey[CHUNK_SIZE]);
                amounts.add(new long[CHUNK_SIZE]);
            }
            keys.get(size / CHUNK_SIZE)[offset] = key;
            amounts.get(size / CHUNK_SIZE)[offset] = amount;
            size++;
        }

        public void forEach(ObjLongConsumer<AEKey> action) {
            for (int chunk = 0, remaining = size; remaining > 0; chunk++, remaining -= CHUNK_SIZE) {
                AEKey[] chunkKeys = keys.get(chunk);
                long[] chunkAmounts = amounts.get(chunk);
                for (int i = 0, end = Math.min(remaining, CHUNK_SIZE); i < end; i++)
                    action.accept(chunkKeys[i], chunkAmounts[i]);
            }
        }
    }

    /** Server-owned publication; background compaction never locks the publisher. */
    public static final class Publisher {

        private volatile InventorySnapshot current = EMPTY;
        private final AtomicBoolean compacting = new AtomicBoolean();

        public InventorySnapshot current() {
            return current;
        }

        public void publish(Changes changes) {
            changes.sealed = true;
            if (changes.size != 0) {
                InventorySnapshot previous = current;
                int depth = previous.state.index == null ? previous.depth + 1 : 1;
                current = new InventorySnapshot(new State(previous, changes, null), depth);
            }
            scheduleCompaction();
        }

        private void scheduleCompaction() {
            if (!needsCompaction(current) || !compacting.compareAndSet(false, true)) return;
            try {
                COMPACTOR.execute(() -> {
                    boolean completed = false;
                    try {
                        current.prepare();
                        completed = true;
                    } finally {
                        compacting.set(false);
                        // Coalesce updates arriving while this version was built.
                        if (completed) scheduleCompaction();
                    }
                });
            } catch (RejectedExecutionException ignored) {
                // A saturated maintenance queue must never make the server copy
                // inventory or wait. The next publication retries; workers can
                // always materialize their own version independently.
                compacting.set(false);
            }
        }

        private static boolean needsCompaction(InventorySnapshot snapshot) {
            State state = snapshot.state;
            return state.index == null && (snapshot.depth >= 16 || state.changes.size >= 4096);
        }
    }
}
