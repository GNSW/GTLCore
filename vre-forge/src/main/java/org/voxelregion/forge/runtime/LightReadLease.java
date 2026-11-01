/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.voxelregion.forge.GenerationBridge;

/**
 * Owner-thread leases for a changed column's affected outputs and their terrain halo.
 * A whole-column edit can change the adjacent outputs; their snapshots require a
 * second ring of terrain. FEATURES is the lowest native getChunkForLighting state.
 * These tickets never request FULL, ticking, light calculation, or postprocessing.
 */
final class LightReadLease implements AutoCloseable {
    private static final TicketType<Long> TICKET = TicketType.create("voxelregion_light_read", Comparator.<Long>naturalOrder());
    private static final int LEVEL = ChunkLevel.byStatus(ChunkStatus.FEATURES);
    private static final Map<ServerLevel, Map<Long, Entry>> SHARED = new IdentityHashMap<>();
    private final ServerLevel level;
    private final boolean generate;
    private final List<Entry> entries;
    private boolean closed;

    private LightReadLease(ServerLevel level, boolean generate, List<Entry> entries) {
        this.level = level; this.generate = generate; this.entries = entries;
    }

    static LightReadLease acquire(ServerLevel level, ChunkPos center, boolean generate) {
        requireOwner(level);
        Map<Long, Entry> shared = SHARED.computeIfAbsent(level, ignored -> new HashMap<>());
        List<Entry> entries = new ArrayList<>(25);
        for (int z = center.z - 2; z <= center.z + 2; z++) for (int x = center.x - 2; x <= center.x + 2; x++) {
            long key = ChunkPos.asLong(x, z);
            Entry entry = shared.computeIfAbsent(key, ignored -> new Entry(new ChunkPos(key)));
            entry.references++;
            entries.add(entry);
        }
        return new LightReadLease(level, generate, entries);
    }

    /** Never waits or pumps the server mailbox. Native futures are polled on later ticks. */
    boolean ready() {
        requireOwner(level);
        if (closed) throw new IllegalStateException("Closed light read lease");
        boolean ready = true;
        for (Entry entry : entries) ready &= entry.ready(level, generate);
        return ready;
    }

    @Override public void close() {
        requireOwner(level);
        if (closed) return;
        closed = true;
        Map<Long, Entry> shared = SHARED.get(level);
        for (Entry entry : entries) if (--entry.references == 0) {
            if (entry.ticketed) level.getChunkSource().chunkMap.getDistanceManager()
                    .removeTicket(TICKET, entry.position, LEVEL, entry.position.toLong());
            shared.remove(entry.position.toLong(), entry);
        }
        if (shared.isEmpty()) SHARED.remove(level);
    }

    static int retainedColumns() {
        int count = 0;
        for (Map<Long, Entry> entries : SHARED.values()) count += entries.size();
        return count;
    }

    private static void requireOwner(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Light read leases require the server thread");
    }

    static final class MissingExistingTerrain extends IllegalStateException {
        MissingExistingTerrain(ChunkPos position) {
            super("Light read halo needs existing FEATURES terrain at " + position + "; generation is disabled, no terrain was changed");
        }
    }

    private static final class Entry {
        final ChunkPos position;
        int references;
        boolean ticketed;
        CompletableFuture<Boolean> existing;

        Entry(ChunkPos position) { this.position = position; }

        boolean ready(ServerLevel level, boolean generate) {
            var source = level.getChunkSource();
            var current = source.getChunkForLighting(position.x, position.z);
            if (current == null && !generate) {
                // Read through the actual native IOWorker, including pending stores. A low
                // generation status is not permission to run its remaining FEATURES pass.
                if (existing == null) {
                    if (!(source.chunkMap.chunkScanner() instanceof IOWorker storage))
                        throw new IllegalStateException("Native storage cannot prove existing light halo terrain");
                    existing = storage.loadAsync(position).thenApply(image -> image.isPresent()
                            && readable(ChunkStatus.byName(image.get().getString("Status"))));
                }
                if (!existing.isDone()) return false;
                if (!existing.join()) throw new MissingExistingTerrain(position);
            }
            if (!ticketed) {
                source.chunkMap.getDistanceManager().addTicket(TICKET, position, LEVEL, position.toLong());
                ticketed = true;
            }
            // Visibility alone is insufficient: a still-visible holder can already be
            // dropping its old ticket. Wait until the new retention reaches the updating
            // holder before admitting any write, even when terrain is readable now.
            // Resolve the current holder on every poll; a finished old holder's future
            // is not evidence for a replacement holder at the same coordinates.
            var loading = ((GenerationBridge) source.chunkMap).vre$requestLightRead(position);
            if (loading != null && loading.isDone()) {
                var completed = loading.join();
                if (completed.right().isPresent()) throw new IllegalStateException("Native light halo loading failed at "
                        + position + ": " + completed.right().get());
                // Publication into the visible holder map follows the native future. The
                // light owner uses that map too, so do not acknowledge only a detached value.
                return source.getChunkForLighting(position.x, position.z) != null;
            }
            return false;
        }

        private static boolean readable(ChunkStatus status) {
            return status != null && status.isOrAfter(ChunkStatus.FEATURES);
        }
    }
}
