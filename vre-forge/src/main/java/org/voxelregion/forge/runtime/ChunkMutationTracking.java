/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.server.level.ServerLevel;

/** Shared by the native mutation hook and trusted direct-section backends. */
public final class ChunkMutationTracking {
    private static final java.util.Map<ServerLevel, java.util.concurrent.CopyOnWriteArrayList<java.util.function.BiConsumer<LevelChunk, Long>>> listeners = new java.util.concurrent.ConcurrentHashMap<>();
    public static Runnable subscribe(ServerLevel level, java.util.function.BiConsumer<LevelChunk, Long> listener) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Mutation subscriptions require the server owner");
        var worldListeners = listeners.computeIfAbsent(level, ignored -> new java.util.concurrent.CopyOnWriteArrayList<>());
        worldListeners.add(listener);
        return () -> {
            worldListeners.removeIf(current -> current == listener);
            if (worldListeners.isEmpty()) listeners.remove(level, worldListeners);
        };
    }
    /**
     * Invalidate old checkpoints before entering arbitrary native callbacks.
     * A write attempt can be a no-op; a conservative extra revision is harmless.
     */
    public static void changing(LevelChunk chunk) { changed(chunk); }

    /** Notify once after each successful native or bulk mutation, on the world thread. */
    public static void changed(LevelChunk chunk) {
        // Integrated servers share these classes with ClientLevel. A client packet
        // must never advance server receipts or dispatch server light/capture observers.
        if (!(chunk.getLevel() instanceof ServerLevel level)) return;
        // Capability discovery can legitimately be incomplete during construction.
        chunk.getCapability(ChunkReceipts.CAPABILITY).ifPresent(receipts -> {
            receipts.changed();
            var worldListeners = listeners.get(level);
            if (worldListeners != null)
                for (var listener : worldListeners) listener.accept(chunk, receipts.mutationRevision());
        });
    }

    /** Use when a backend discovers an untracked write or uncertain mutation provenance. */
    public static void invalidate(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel level)) return;
        chunk.getCapability(ChunkReceipts.CAPABILITY).ifPresent(ChunkReceipts::invalidateTracking);
        var worldListeners = listeners.get(level);
        if (worldListeners != null)
            for (var listener : worldListeners) listener.accept(chunk, -1L);
    }

    /** Missing or invalid capability evidence is unknown, including during chunk construction. */
    public static long revision(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel)) return -1;
        return chunk.getCapability(ChunkReceipts.CAPABILITY).resolve()
                .map(receipts -> receipts.trackingValid() ? receipts.mutationRevision() : -1L).orElse(-1L);
    }

    private ChunkMutationTracking() { }
}
