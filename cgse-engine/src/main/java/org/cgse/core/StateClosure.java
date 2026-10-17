// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resumable discovery of return states reachable from offered candidates. This
 * is a candidate domain, never inventory: discovering a key creates no stock.
 * Hosts with live callbacks must drive this object on their owning thread.
 */
public final class StateClosure<K> implements AutoCloseable {

    @FunctionalInterface
    public interface Resolver<K> {
        /** Null rejects this input state; accepted states need a sound contract. */
        StateReturn<K> capture(K input);
    }

    /** Complete is relative to the offered seeds, not all possible host inputs. */
    public record Coverage(int discovered, int resolved, int rejected, int pending,
                           boolean stateLimit, boolean seedsComplete, boolean returnsComplete, boolean deterministic,
                           boolean complete) {}

    private final PlanningBudget budget;
    private final int maximumStates;
    private final Resolver<K> resolver;
    private final Set<K> discovered = new LinkedHashSet<>();
    private final Deque<K> pending = new ArrayDeque<>();
    private final Map<K, StateReturn<K>> captured = new LinkedHashMap<>();
    private K active;
    private StateReturn<K> activeReturn;
    private K successor;
    private Iterator<Map<K, Long>> outcomes;
    private Iterator<K> successors = Collections.emptyIterator();
    private long memory;
    private int rejected;
    private boolean stateLimit, seedsIncomplete, returnsIncomplete, uncertain, closed;

    public StateClosure(PlanningBudget budget, int maximumStates, Resolver<K> resolver) {
        this.budget = Objects.requireNonNull(budget);
        if (maximumStates <= 0) throw new IllegalArgumentException("Non-positive state limit");
        this.maximumStates = maximumStates;
        this.resolver = Objects.requireNonNull(resolver);
        budget.checkpoint();
        reserve(192);
    }

    /** Declared choices, real stock, and producer outputs may all offer candidates. */
    public boolean offer(K key) {
        open();
        Objects.requireNonNull(key);
        budget.compilationCheck();
        if (discovered.contains(key)) return true;
        if (discovered.size() == maximumStates) {
            stateLimit = true;
            return false;
        }
        reserve(112);
        discovered.add(key);
        pending.addLast(key);
        return true;
    }

    /** Omitted seed/provider domains must remain visible in the final coverage. */
    public void incomplete() {
        open();
        seedsIncomplete = true;
    }

    /**
     * At most one host callback or one successor inspection per call. A host
     * callback itself must be bounded and must not sample a stochastic rule as
     * exact. Budget failures leave coverage incomplete and are never proofs.
     */
    public boolean step() {
        open();
        budget.compilationCheck();
        if (active == null) {
            if (pending.isEmpty()) return true;
            K next = pending.peekFirst();
            StateReturn<K> capturedReturn = resolver.capture(next);
            if (capturedReturn == null) {
                pending.removeFirst();
                rejected++;
                return pending.isEmpty();
            }
            active = next;
            activeReturn = capturedReturn;
            // Keep the captured immutable contract if admission is interrupted;
            // a retry must not run a live return callback for this state again.
        }
        if (outcomes == null) {
            reserve(activeReturn.estimatedBytes());
            captured.put(active, activeReturn);
            returnsIncomplete |= !activeReturn.complete();
            uncertain |= !activeReturn.deterministic();
            outcomes = activeReturn.outcomes().iterator();
            return false;
        }
        if (successor != null || successors.hasNext()) {
            if (successor == null) successor = successors.next();
            offer(successor);
            successor = null;
            return false;
        }
        if (outcomes.hasNext()) {
            successors = outcomes.next().keySet().iterator();
            return false;
        }
        pending.removeFirst();
        active = null;
        activeReturn = null;
        outcomes = null;
        return pending.isEmpty();
    }

    public Coverage coverage() {
        open();
        return new Coverage(discovered.size(), captured.size(), rejected, pending.size(), stateLimit,
                !seedsIncomplete, !returnsIncomplete, !uncertain,
                pending.isEmpty() && !stateLimit && !seedsIncomplete && !returnsIncomplete);
    }

    /** Live immutable view, valid until close; the caller owns any exported copy. */
    public Map<K, StateReturn<K>> captured() {
        open();
        return Collections.unmodifiableMap(captured);
    }

    private void reserve(long bytes) {
        budget.reserve(bytes);
        memory += bytes;
    }

    private void open() {
        if (closed) throw new IllegalStateException("State closure is closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        discovered.clear();
        pending.clear();
        captured.clear();
        active = null;
        activeReturn = null;
        successor = null;
        outcomes = null;
        successors = Collections.emptyIterator();
        budget.release(memory);
        memory = 0;
    }
}
