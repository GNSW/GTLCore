/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.lighting.scheduler;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Terminal failure of a serial computation owner, separate from acknowledgement of its queue.
 * Call {@link #run} on that owner: a latched failure prevents more work, but outstanding
 * business receipts release resources only after active computation has unwound. A completed
 * receipt stays completed. This class never retries a failed task or manufactures success.
 */
public final class LightOwnerFailure {
    private final Object gate = new Object();
    private final Map<Pending<?>, Boolean> pending = new IdentityHashMap<>();
    private final ArrayDeque<Pending<?>> delayedFailures = new ArrayDeque<>();
    private final Consumer<Throwable> onFailure;
    private volatile Throwable failure;
    private int active;
    private boolean settling, settled;

    public LightOwnerFailure(Consumer<Throwable> onFailure) {
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
    }

    public Throwable failure() { return failure; }

    /** Fail an enclosing owner callback before it proceeds to another publication boundary. */
    public void checkHealthy() {
        Throwable stopped = failure;
        if (stopped != null) rethrow(stopped);
    }

    /** An already failed owner discards queued work, including success-only publication tasks. */
    public void run(Runnable task) {
        synchronized (gate) {
            if (failure != null) return;
            active++;
        }
        try {
            task.run();
            // CompletableFuture suppliers capture Throwable themselves. Their completion
            // listener can latch a fatal error even when task.run() returns normally.
            if (failure != null && !(failure instanceof CancellationException)) rethrow(failure);
        }
        catch (Throwable cause) {
            fail(cause);
            rethrow(cause);
        } finally {
            boolean settle;
            synchronized (gate) {
                active--;
                settle = claimSettlement();
            }
            if (settle) settleFailure();
            else settleDelayedFailures();
        }
    }

    /** Queue ACK releases a lane; it is not the task's successful business receipt. */
    public static void runAcknowledged(Runnable task, Runnable acknowledge) {
        Throwable primary = null;
        try { task.run(); }
        catch (Throwable cause) { primary = cause; rethrow(cause); }
        finally {
            try { acknowledge.run(); }
            catch (Throwable cause) {
                if (primary == null) rethrow(cause);
                else suppress(primary, cause);
            }
        }
    }

    /**
     * Connect a native future to the terminal owner. Cancellation of the returned future does
     * not forget cleanup for its still queued task. Failure cleanup runs once, before settling
     * the returned receipt, and should not block or wait for another owner.
     */
    public <T> CompletableFuture<T> track(CompletableFuture<T> source, Consumer<Throwable> cleanup) {
        Objects.requireNonNull(source, "source");
        Pending<T> entry = new Pending<>(Objects.requireNonNull(cleanup, "cleanup"));
        Throwable stopped;
        synchronized (gate) {
            // A terminal event during active work must not let even a newly registered
            // receipt release that work's inputs before its owner callback unwinds.
            stopped = settled ? failure : null;
            if (stopped == null) pending.put(entry, Boolean.TRUE);
            else entry.claimed = true;
        }
        if (stopped != null) entry.fail(stopped);
        else source.whenComplete((value, error) -> complete(entry, value, error));
        return entry.result;
    }

    private <T> void complete(Pending<T> entry, T value, Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof Error) fail(cause);
        synchronized (gate) {
            if (entry.claimed) return;
            if (failure != null) return; // Terminal settlement owns cleanup after active work exits.
            if (error != null && active != 0) {
                entry.sourceFailure = error;
                delayedFailures.addLast(entry);
                return;
            }
            entry.claimed = true;
            pending.remove(entry);
            // Winning this gate is the receipt's completion linearization point. A later
            // terminal event cannot revoke a successfully completed earlier generation.
        }
        if (error != null) entry.fail(error);
        else entry.result.complete(value);
    }

    private void settleDelayedFailures() {
        while (true) {
            Pending<?> entry;
            synchronized (gate) {
                if (active != 0 || failure != null || delayedFailures.isEmpty()) return;
                entry = delayedFailures.removeFirst();
                if (entry.claimed) continue;
                entry.claimed = true;
                pending.remove(entry);
            }
            entry.fail(entry.sourceFailure);
        }
    }

    /** Stop admission immediately; defer cleanup while any already entered owner work is active. */
    public void fail(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        boolean settle;
        synchronized (gate) {
            if (failure != null) return;
            failure = cause;
            settle = claimSettlement();
        }
        if (settle) settleFailure();
    }

    private boolean claimSettlement() {
        if (failure == null || active != 0 || settling) return false;
        settling = true;
        return true;
    }

    private void settleFailure() {
        Throwable cause = failure;
        try { onFailure.accept(cause); }
        catch (Throwable cleanup) { suppress(cause, cleanup); }
        finally {
            // Do not allocate a copy of the pending set while recovering from allocation
            // failure. Registrations racing this drain join pending until it finishes.
            while (true) {
                Pending<?> entry;
                synchronized (gate) {
                    if (pending.isEmpty()) { delayedFailures.clear(); settled = true; break; }
                    var iterator = pending.keySet().iterator();
                    entry = iterator.next(); iterator.remove(); entry.claimed = true;
                }
                entry.fail(cause);
            }
        }
    }

    private static void suppress(Throwable cause, Throwable cleanup) {
        if (cause != cleanup) {
            try { cause.addSuppressed(cleanup); }
            catch (Throwable ignored) { /* Keep settling other receipts even at heap exhaustion. */ }
        }
    }

    private static void rethrow(Throwable cause) {
        if (cause instanceof RuntimeException exception) throw exception;
        if (cause instanceof Error error) throw error;
        throw new IllegalStateException("Unexpected checked failure in light owner", cause);
    }

    private static final class Pending<T> {
        final CompletableFuture<T> result = new CompletableFuture<>();
        final Consumer<Throwable> cleanup;
        boolean claimed;
        Throwable sourceFailure;
        Pending(Consumer<Throwable> cleanup) { this.cleanup = cleanup; }
        void fail(Throwable cause) {
            try { cleanup.accept(cause); }
            catch (Throwable extra) { suppress(cause, extra); }
            finally { result.completeExceptionally(cause); }
        }
    }
}
