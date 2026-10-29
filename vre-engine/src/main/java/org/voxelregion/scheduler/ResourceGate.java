// SPDX-License-Identifier: MPL-2.0
package org.voxelregion.scheduler;

/** Limits outstanding asynchronous resources across all tasks, not per explosion. */
public final class ResourceGate {
    public record Limits(int loads, int saves, long pendingBytes) {
        public Limits {
            if (loads < 0 || saves < 0 || pendingBytes < 0) throw new IllegalArgumentException("Negative limit");
        }
    }
    public record Usage(int loads, int saves, long pendingBytes) {}
    private final Limits limits;
    private int loads, saves;
    private long bytes;

    public ResourceGate(Limits limits) { this.limits = java.util.Objects.requireNonNull(limits); }

    /** A null result means backpressure. Never allocate or request a chunk before acquiring. */
    public synchronized Lease tryAcquire(int loading, int saving, long pendingBytes) {
        if (loading < 0 || saving < 0 || pendingBytes < 0) throw new IllegalArgumentException("Negative cost");
        if (loading > limits.loads - loads || saving > limits.saves - saves
                || pendingBytes > limits.pendingBytes - bytes) return null;
        loads += loading;
        saves += saving;
        bytes += pendingBytes;
        return new Lease(loading, saving, pendingBytes);
    }

    public synchronized Usage usage() { return new Usage(loads, saves, bytes); }

    public final class Lease implements AutoCloseable {
        private final int loading, saving;
        private final long pendingBytes;
        private boolean closed;
        private Lease(int loading, int saving, long pendingBytes) {
            this.loading = loading; this.saving = saving; this.pendingBytes = pendingBytes;
        }
        @Override public void close() {
            synchronized (ResourceGate.this) {
                if (closed) return;
                closed = true;
                loads -= loading; saves -= saving; bytes -= pendingBytes;
            }
        }
    }
}
