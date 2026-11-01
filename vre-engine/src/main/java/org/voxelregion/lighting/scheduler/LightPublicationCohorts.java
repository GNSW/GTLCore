/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.lighting.scheduler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Bounded-by-arrival publication cohorts over an existing serial owner and ordered marker lanes.
 * Neither marker admission nor arrival waits for publication. A later ready cohort cannot overtake
 * an earlier cohort: its marker may refer to an earlier request which still needs to be applied.
 * The owner callback must settle every supplied receipt before returning. Owner dispatch and marker
 * admission must enqueue without blocking; all numerical work belongs to the owner callback.
 */
public final class LightPublicationCohorts<T> implements AutoCloseable {
    public record Request<T>(long column, T action, CompletableFuture<Void> completion) { }

    private final Object gate = new Object();
    private final Consumer<Runnable> ownerDispatch;
    private final Consumer<List<Request<T>>> publish;
    private final ArrayDeque<Batch<T>> pending = new ArrayDeque<>();
    private Batch<T> open;
    private int admitting;
    private boolean scheduled;
    private Throwable closed;

    public LightPublicationCohorts(Consumer<Runnable> ownerDispatch, Consumer<List<Request<T>>> publish) {
        this.ownerDispatch = Objects.requireNonNull(ownerDispatch, "ownerDispatch");
        this.publish = Objects.requireNonNull(publish, "publish");
    }

    public CompletableFuture<Void> submit(long column, T action, Consumer<Runnable> enqueueMarker) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(enqueueMarker, "enqueueMarker");
        CompletableFuture<Void> completion = new CompletableFuture<>();
        boolean dispatch;
        synchronized (gate) {
            if (closed != null) return CompletableFuture.failedFuture(closed);
            if (open == null) { open = new Batch<>(); pending.addLast(open); }
            Entry<T> entry = new Entry<>(open, new Request<>(column, action, completion));
            open.entries.add(entry); open.remaining++;
            // Serialize admission with submission order. A synchronously invoked test marker
            // may record its arrival, but must not dispatch user callbacks under this lock.
            admitting++;
            try { enqueueMarker.accept(() -> arrive(entry)); }
            catch (RuntimeException | LinkageError failure) { settleMarker(entry, failure); }
            finally { admitting--; }
            dispatch = scheduleReady();
        }
        if (dispatch) dispatchOwner();
        return completion;
    }

    private void arrive(Entry<T> entry) {
        boolean dispatch;
        synchronized (gate) {
            if (closed != null) return;
            settleMarker(entry, null);
            dispatch = scheduleReady();
        }
        if (dispatch) dispatchOwner();
    }

    /** Called only under gate. A rejected admission is an arrived, failed slot. */
    private void settleMarker(Entry<T> entry, Throwable failure) {
        Batch<T> batch = entry.batch;
        if (failure != null && batch.failure == null) batch.failure = failure;
        if (entry.arrived) return;
        entry.arrived = true;
        batch.remaining--;
        batch.sealed = true;
        if (open == batch) open = null;
    }

    /** Only a finite, contiguous ready prefix may be released. */
    private boolean scheduleReady() {
        Batch<T> first = pending.peekFirst();
        if (closed != null || scheduled || admitting != 0 || first == null
                || !first.sealed || first.remaining != 0) return false;
        scheduled = true;
        return true;
    }

    private void dispatchOwner() {
        try { ownerDispatch.accept(this::flush); }
        catch (RuntimeException | LinkageError failure) { failAndClose(failure); }
    }

    private void flush() {
        List<Request<T>> accepted = new ArrayList<>(), ready = new ArrayList<>();
        List<Failed<T>> failed = new ArrayList<>();
        synchronized (gate) {
            if (closed != null) return;
            while (!pending.isEmpty()) {
                Batch<T> batch = pending.peekFirst();
                if (!batch.sealed || batch.remaining != 0) break;
                pending.removeFirst();
                for (Entry<T> entry : batch.entries) {
                    accepted.add(entry.request);
                    if (batch.failure == null) ready.add(entry.request);
                    else failed.add(new Failed<>(entry.request, batch.failure));
                }
            }
        }
        try {
            for (Failed<T> failure : failed)
                failure.request.completion.completeExceptionally(failure.failure);
            boolean stopped;
            synchronized (gate) { stopped = closed != null; }
            if (!stopped && !ready.isEmpty()) {
                publish.accept(List.copyOf(ready));
                // Never manufacture success if the publisher forgot a receipt.
                for (Request<T> request : ready) if (!request.completion.isDone())
                    request.completion.completeExceptionally(new IllegalStateException(
                            "Light publication returned without settling its receipt"));
            }
        } catch (Throwable failure) {
            for (Request<T> request : accepted) request.completion.completeExceptionally(failure);
            if (failure instanceof VirtualMachineError error) throw error;
            if (failure instanceof ThreadDeath error) throw error;
        } finally {
            boolean dispatch;
            Throwable stop;
            synchronized (gate) {
                stop = closed;
                scheduled = false;
                dispatch = scheduleReady();
            }
            if (stop != null) for (Request<T> request : accepted)
                request.completion.completeExceptionally(stop);
            if (dispatch) dispatchOwner();
        }
    }

    /**
     * Reject new requests and fail queued receipts. A claimed owner callback keeps its receipts
     * until it returns, so a failed receipt cannot release resources still used by publication.
     */
    @Override public void close() { failAndClose(new CancellationException("Light engine closed")); }

    /** Fail with the owner's actual terminal cause; active publication still owns its receipts. */
    public void fail(Throwable cause) { failAndClose(Objects.requireNonNull(cause, "cause")); }

    private void failAndClose(Throwable failure) {
        List<Request<T>> abandoned = new ArrayList<>();
        synchronized (gate) {
            if (closed != null) return;
            closed = failure;
            for (Batch<T> batch : pending) for (Entry<T> entry : batch.entries)
                abandoned.add(entry.request);
            pending.clear(); open = null; scheduled = false;
        }
        for (Request<T> request : abandoned) request.completion.completeExceptionally(failure);
    }

    private static final class Batch<T> {
        final List<Entry<T>> entries = new ArrayList<>();
        int remaining;
        boolean sealed;
        Throwable failure;
    }
    private static final class Entry<T> {
        final Batch<T> batch;
        final Request<T> request;
        boolean arrived;
        Entry(Batch<T> batch, Request<T> request) { this.batch = batch; this.request = request; }
    }
    private record Failed<T>(Request<T> request, Throwable failure) { }
}
