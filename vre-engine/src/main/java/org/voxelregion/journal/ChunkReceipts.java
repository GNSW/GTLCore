// SPDX-License-Identifier: MPL-2.0
package org.voxelregion.journal;

import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/** Persist this complete set in the same chunk image as the corresponding mutation. */
public final class ChunkReceipts {
    public enum Outcome { APPLIED, SKIPPED_PERMISSION, CANCELLED }
    private final Map<UUID, Outcome> entries;
    private final int capacity;
    public ChunkReceipts(int capacity) { this(capacity, Map.of()); }
    public ChunkReceipts(int capacity, Map<UUID, Outcome> entries) {
        if (capacity <= 0 || entries.size() > capacity) throw new IllegalArgumentException("Receipt capacity exceeded");
        this.capacity = capacity; this.entries = Map.copyOf(entries);
    }
    public Outcome get(UUID operation) { return entries.get(operation); }
    public Map<UUID, Outcome> entries() { return entries; }
    public ChunkReceipts record(UUID operation, Outcome outcome) {
        java.util.Objects.requireNonNull(operation); java.util.Objects.requireNonNull(outcome);
        Outcome old = entries.get(operation);
        if (old != null) {
            if (old != outcome) throw new IllegalStateException("Terminal chunk receipt cannot change");
            return this;
        }
        if (entries.size() == capacity) throw new IllegalStateException("Archive operation before adding more receipts");
        Map<UUID, Outcome> next = new HashMap<>(entries); next.put(operation, outcome);
        return new ChunkReceipts(capacity, next);
    }
    /** Only after the operation's permanent terminal record excludes any future replay. */
    public ChunkReceipts archive(UUID operation, boolean durableTerminalRecordVerified) {
        if (!durableTerminalRecordVerified) throw new IllegalStateException("Cannot discard replay protection");
        Map<UUID, Outcome> next = new HashMap<>(entries); next.remove(operation);
        return new ChunkReceipts(capacity, next);
    }
}
