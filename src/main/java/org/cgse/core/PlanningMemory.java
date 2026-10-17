// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.concurrent.atomic.AtomicLong;

/** Shared logical workspace cap. Work counters and retained caches use separate accounts. */
public final class PlanningMemory {
    private final AtomicLong used = new AtomicLong();
    private volatile long limit;

    public PlanningMemory(long limit) { limit(limit); }

    public void limit(long bytes) {
        if (bytes <= 0) throw new IllegalArgumentException("Nonpositive global planning memory limit");
        limit = bytes;
    }

    public long used() { return used.get(); }
    public long available() { return Math.max(0, limit - used.get()); }

    Account account() { return new Account(); }

    final class Account {
        private long held;
        private boolean closed;

        synchronized boolean reserve(long bytes) {
            if (closed) return false;
            long previous;
            do {
                previous = used.get();
                if (bytes > limit - previous) return false;
            } while (!used.compareAndSet(previous, previous + bytes));
            held += bytes;
            return true;
        }

        synchronized void release(long bytes) {
            if (closed) return; // Disposal returned the entire account after worker cleanup.
            if (bytes > held) throw new IllegalStateException("Unbalanced global planning memory");
            held -= bytes;
            used.addAndGet(-bytes);
        }

        synchronized long available() { return closed ? 0 : PlanningMemory.this.available(); }

        synchronized void close() {
            if (closed) return;
            closed = true;
            used.addAndGet(-held);
            held = 0;
        }
    }
}
