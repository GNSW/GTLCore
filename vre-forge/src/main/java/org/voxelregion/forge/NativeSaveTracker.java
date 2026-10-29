/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;

/** Captures the actual native terrain/POI store futures, including individual failures. */
public final class NativeSaveTracker {
    private static final ThreadLocal<Capture> ACTIVE = new ThreadLocal<>();
    public static Capture begin(ChunkPos position, IOWorker terrain) {
        if (ACTIVE.get() != null) throw new IllegalStateException("Nested native chunk save capture");
        Capture capture = new Capture(position, terrain);
        ACTIVE.set(capture);
        return capture;
    }
    public static boolean capturing() { return ACTIVE.get() != null; }
    public static void stored(IOWorker worker, ChunkPos position, CompletableFuture<Void> result) {
        Capture capture = ACTIVE.get();
        if (capture != null) {
            capture.workers.add(worker);
            capture.positions.computeIfAbsent(worker, ignored -> new LinkedHashSet<>()).add(position);
            capture.futures.add(result);
            if (worker == capture.terrain && position.equals(capture.position)) capture.terrainObserved = true;
        }
    }
    public static final class Capture implements AutoCloseable {
        private final ChunkPos position;
        private final IOWorker terrain;
        private final Set<IOWorker> workers = new LinkedHashSet<>();
        private final Map<IOWorker, Set<ChunkPos>> positions = new LinkedHashMap<>();
        private final List<CompletableFuture<Void>> futures = new ArrayList<>();
        private boolean terrainObserved;
        private Capture(ChunkPos position, IOWorker terrain) { this.position = position; this.terrain = terrain; }
        public Set<IOWorker> workers() { return Set.copyOf(workers); }
        public Map<IOWorker, Set<ChunkPos>> positions() {
            Map<IOWorker, Set<ChunkPos>> snapshot = new LinkedHashMap<>();
            positions.forEach((worker, chunks) -> snapshot.put(worker, Set.copyOf(chunks)));
            return Map.copyOf(snapshot);
        }
        public CompletableFuture<Void> writes() {
            if (!terrainObserved) return CompletableFuture.failedFuture(new IllegalStateException("Native terrain save was not observed"));
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        }
        @Override public void close() {
            if (ACTIVE.get() != this) throw new IllegalStateException("Save capture closed on wrong thread or out of order");
            ACTIVE.remove();
        }
    }
    private NativeSaveTracker() {}
}
