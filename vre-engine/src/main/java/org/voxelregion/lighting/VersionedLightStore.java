/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.lighting;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-world, independently owned publication store. Terrain revisions and light generations
 * are separate: edits invalidate evidence without erasing the last visible light image.
 * Capture and publication use short synchronized map operations; optical work runs outside
 * the lock. Readers receive immutable columns, so neither workers nor a packet writer can
 * mutate an image while another reader serializes it.
 *
 * <p>The host must mark every terrain/optical change before admitting a worker snapshot,
 * capture the entire read halo, and publish the complete affected footprint. Host mutation
 * and host packet/section installation must share the same owner-thread ordering as these
 * calls. A successful portable publication is not by itself a Minecraft publication fence.
 */
public final class VersionedLightStore {
    public record ColumnKey(int x, int z) { }
    public record ColumnLight(int minSection, long terrainRevision, long lightGeneration,
            ClearedChunkLighting.Result data) {
        public ColumnLight { Objects.requireNonNull(data, "data"); }
        public int block(int x, int worldY, int z) { return data.block(x, localY(worldY), z); }
        public int sky(int x, int worldY, int z) { return data.sky(x, localY(worldY), z); }
        private int localY(int y) {
            long local = (long)y - (long)minSection * 16;
            if (local < 0 || local >= data.height()) throw new IndexOutOfBoundsException("Outside stored column");
            return (int)local;
        }
    }
    /** A complete atomic read image and its revision evidence. Absent columns cannot be captured. */
    public static final class Capture {
        private final VersionedLightStore owner;
        private final Map<ColumnKey, Long> revisions;
        private final Map<ColumnKey, ColumnLight> visible;
        private Capture(VersionedLightStore owner, Map<ColumnKey, Long> revisions, Map<ColumnKey, ColumnLight> visible) {
            this.owner = owner; this.revisions = Map.copyOf(revisions); this.visible = Map.copyOf(visible);
        }
        public Map<ColumnKey, Long> terrainRevisions() { return revisions; }
        /** A tracked column without a first light solution has no visible entry yet. */
        public Map<ColumnKey, ColumnLight> visibleColumns() { return visible; }
    }
    public record Publication(boolean installed, long generation, int columns) { }
    public record Statistics(int trackedColumns, int visibleColumns, long terrainChanges,
            long publications, long stalePublications, long publishedColumns) { }
    private static final class Entry {
        long revision;
        ColumnLight visible;
        Entry(long revision) { this.revision = revision; }
    }
    private final Map<ColumnKey, Entry> columns = new HashMap<>();
    private long revisionSequence, generation, stalePublications, publishedColumns;

    /** Registers a loaded column, or invalidates all captures of its old optical terrain. */
    public synchronized long markChanged(int chunkX, int chunkZ) {
        ColumnKey key = new ColumnKey(chunkX, chunkZ);
        long revision = nextRevision();
        Entry entry = columns.get(key);
        if (entry == null) columns.put(key, new Entry(revision)); else entry.revision = revision;
        return revision;
    }

    /** Unload/reload cannot reuse a captured revision, even if the block content is identical. */
    public synchronized boolean remove(int chunkX, int chunkZ) {
        return columns.remove(new ColumnKey(chunkX, chunkZ)) != null;
    }

    public synchronized Optional<ColumnLight> read(int chunkX, int chunkZ) {
        Entry entry = columns.get(new ColumnKey(chunkX, chunkZ));
        return Optional.ofNullable(entry == null ? null : entry.visible);
    }

    /** False also means unregistered/no solution. A caller must not interpret that as a lit void. */
    public synchronized boolean isCurrent(int chunkX, int chunkZ) {
        Entry entry = columns.get(new ColumnKey(chunkX, chunkZ));
        return entry != null && entry.visible != null && entry.visible.terrainRevision == entry.revision;
    }

    public synchronized Capture capture(Collection<ColumnKey> readFootprint) {
        Objects.requireNonNull(readFootprint, "readFootprint");
        if (readFootprint.isEmpty()) throw new IllegalArgumentException("Empty read footprint");
        Map<ColumnKey, Long> revisions = new LinkedHashMap<>();
        Map<ColumnKey, ColumnLight> visible = new LinkedHashMap<>();
        for (ColumnKey key : readFootprint) {
            Entry entry = columns.get(Objects.requireNonNull(key, "column key"));
            if (entry == null) throw new IllegalStateException("Unregistered optical column " + key);
            revisions.put(key, entry.revision);
            if (entry.visible != null) visible.put(key, entry.visible);
        }
        return new Capture(this, revisions, visible);
    }

    public synchronized boolean unchanged(Capture capture) {
        requireOwner(capture);
        for (var pair : capture.revisions.entrySet()) {
            Entry entry = columns.get(pair.getKey());
            if (entry == null || entry.revision != pair.getValue()) return false;
        }
        return true;
    }

    /** All-or-nothing installation, checking the whole read halo rather than just output columns. */
    public synchronized Publication publishIfUnchanged(Capture capture, int minSection,
            Map<ColumnKey, ClearedChunkLighting.Result> output) {
        requireOwner(capture); Objects.requireNonNull(output, "output");
        if (output.isEmpty()) throw new IllegalArgumentException("Empty light publication");
        for (var pair : output.entrySet()) {
            if (!capture.revisions.containsKey(pair.getKey())) throw new IllegalArgumentException("Output outside captured footprint");
            Objects.requireNonNull(pair.getValue(), "light result");
        }
        if (!unchanged(capture)) { stalePublications++; return new Publication(false, generation, 0); }
        if (generation == Long.MAX_VALUE) throw new IllegalStateException("Light publication sequence exhausted");
        long next = generation + 1;
        // Allocate before touching visible state, so allocation/validation failures do not
        // leave a partly installed batch with the same generation advertised as complete.
        Map<ColumnKey, ColumnLight> prepared = new LinkedHashMap<>();
        for (var pair : output.entrySet())
            prepared.put(pair.getKey(), new ColumnLight(minSection, capture.revisions.get(pair.getKey()), next, pair.getValue()));
        for (var pair : prepared.entrySet()) columns.get(pair.getKey()).visible = pair.getValue();
        generation = next; publishedColumns += prepared.size();
        return new Publication(true, next, prepared.size());
    }

    public synchronized Statistics statistics() {
        int visible = 0;
        for (Entry entry : columns.values()) if (entry.visible != null) visible++;
        return new Statistics(columns.size(), visible, revisionSequence, generation, stalePublications, publishedColumns);
    }
    private long nextRevision() {
        if (revisionSequence == Long.MAX_VALUE) throw new IllegalStateException("Terrain revision sequence exhausted");
        return ++revisionSequence;
    }
    private void requireOwner(Capture capture) {
        if (Objects.requireNonNull(capture, "capture").owner != this)
            throw new IllegalArgumentException("Capture belongs to another light store");
    }
}
