/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.lighting;

/** Process-shared reservations for detached light capture, calculation and publication. */
public final class LightWorkingBudget implements AutoCloseable {
    private final long capacity;
    private long reserved, peak, denied, waits;
    private boolean closed;

    public LightWorkingBudget(long capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Positive light memory capacity required");
        this.capacity = capacity;
    }
    public long capacity() { return capacity; }
    public record Statistics(long capacity, long reserved, long peak, long denied, long waits) { }
    public synchronized Statistics statistics() { return new Statistics(capacity, reserved, peak, denied, waits); }

    /** Does not wait. Owners must finish their already reserved batch before calling acquire. */
    public synchronized Lease tryAcquire(long bytes) {
        check(bytes);
        if (bytes > capacity - reserved) { denied++; return null; }
        return reserve(bytes);
    }
    /** Caller must hold no other leases which only that caller can release. */
    public synchronized Lease acquire(long bytes) throws InterruptedException {
        check(bytes);
        while (bytes > capacity - reserved) {
            waits++;
            wait();
            check(bytes);
        }
        return reserve(bytes);
    }
    private void check(long bytes) {
        if (closed) throw new IllegalStateException("Light working budget closed");
        if (bytes <= 0 || bytes > capacity)
            throw new IllegalArgumentException("Light job requires " + bytes + " bytes; capacity=" + capacity);
    }
    private Lease reserve(long bytes) {
        reserved += bytes; peak = Math.max(peak, reserved);
        return new Lease(bytes);
    }
    public final class Lease implements AutoCloseable {
        private long bytes;
        private Lease(long bytes) { this.bytes = bytes; }
        public long bytes() { synchronized (LightWorkingBudget.this) { return bytes; } }
        /** Never waits while retaining a proof reservation; false leaves the lease unchanged. */
        public boolean tryIncreaseTo(long requested) {
            synchronized (LightWorkingBudget.this) {
                check(requested);
                if (bytes == 0 || requested < bytes) throw new IllegalArgumentException("Active growing lease required");
                long additional = requested - bytes;
                if (additional > capacity - reserved) { denied++; return false; }
                reserved += additional; bytes = requested; peak = Math.max(peak, reserved);
                return true;
            }
        }
        /** A proved compact input may release the unused part before numerical dispatch. */
        public void reduceTo(long retainedBytes) {
            synchronized (LightWorkingBudget.this) {
                if (retainedBytes <= 0 || retainedBytes > bytes)
                    throw new IllegalArgumentException("Reservation may only shrink while active");
                reserved -= bytes - retainedBytes; bytes = retainedBytes;
                LightWorkingBudget.this.notifyAll();
            }
        }
        @Override public void close() {
            synchronized (LightWorkingBudget.this) {
                reserved -= bytes; bytes = 0;
                LightWorkingBudget.this.notifyAll();
            }
        }
    }
    /** Active leases remain charged until the actual owners have finished using them. */
    @Override public synchronized void close() { closed = true; notifyAll(); }
}
