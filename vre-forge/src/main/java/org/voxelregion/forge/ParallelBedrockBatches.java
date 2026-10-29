/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.RandomState;

/** Per-operation bounded spatial batches. Workers only calculate masks, never publish neighboring terrain. */
final class ParallelBedrockBatches implements AutoCloseable {
    private static final AtomicLong retainedBytes = new AtomicLong();
    private static final AtomicLong batches = new AtomicLong(), reused = new AtomicLong();
    private static final AtomicInteger active = new AtomicInteger(), maximumActive = new AtomicInteger();
    private static final int MAX_CACHED = 128;
    private final ClearedTerrainProfile profile;
    private final org.voxelregion.geometry.Volume volume;
    // Admission/eviction occur exclusively on the server owner. Completion only changes atomics.
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>();

    ParallelBedrockBatches(ClearedTerrainProfile profile, org.voxelregion.geometry.Volume volume) {
        this.profile = profile; this.volume = volume;
    }
    static long reservation(LevelHeightAccessor height) { return reservation(height, 4); }
    private static long reservation(LevelHeightAccessor height, int side) {
        return 1024L + height.getHeight() * 32L * side * side;
    }
    static long estimatedBytes() { return retainedBytes.get(); }
    static long completedBatches() { return batches.get(); }
    static long reusedChunks() { return reused.get(); }
    static int maximumActive() { return maximumActive.get(); }

    CompletableFuture<BitSet> request(ChunkPos position, LevelHeightAccessor height, RandomState random) {
        int side = batchSide(position, height);
        int x = Math.floorDiv(position.x, side) * side, z = Math.floorDiv(position.z, side) * side;
        var key = new Key(ChunkPos.asLong(x, z), side);
        Entry entry = entries.get(key);
        if (entry == null) {
            if (entries.size() >= MAX_CACHED) {
                var oldest = entries.entrySet().iterator();
                var expired = oldest.next().getValue(); oldest.remove(); expired.release();
            }
            long bytes = reservation(height, side);
            retainedBytes.addAndGet(bytes);
            entry = new Entry(bytes);
            var created = entry;
            entries.put(key, created);
            try {
                CompletableFuture.supplyAsync(() -> {
                    int running = active.incrementAndGet(); maximumActive.accumulateAndGet(running, Math::max);
                    try {
                        var masks = profile.retainedBedrockSquare(new ChunkPos(x, z), side, height, random);
                        batches.incrementAndGet();
                        return masks;
                    } finally { active.decrementAndGet(); }
                }, RegionPreparationPool.executor()).whenComplete((masks, error) -> {
                    if (error == null) created.result.complete(masks); else created.result.completeExceptionally(error);
                    created.release();
                });
            } catch (RuntimeException error) {
                created.result.completeExceptionally(error); created.release();
            }
        } else reused.incrementAndGet();
        int index = (position.z - z) * side + position.x - x;
        entry.requested |= 1 << index;
        var result = entry.result.thenApply(masks -> masks.get(index));
        // Every returned mask is now owned by a separately reserved image preparation.
        if (entry.requested == (1 << (side * side)) - 1) { entries.remove(key); entry.release(); }
        return result;
    }

    private int batchSide(ChunkPos position, LevelHeightAccessor height) {
        for (int side : new int[]{4, 2}) {
            int x = Math.floorDiv(position.x, side) * side * 16;
            int z = Math.floorDiv(position.z, side) * side * 16;
            var bounds = new org.voxelregion.geometry.IntBox(x, height.getMinBuildHeight(), z,
                    x + side * 16, height.getMaxBuildHeight(), z + side * 16);
            if (volume.classify(bounds) == org.voxelregion.geometry.Coverage.INSIDE) return side;
        }
        return 1;
    }

    @Override public void close() {
        for (var entry : entries.values()) entry.release();
        entries.clear();
    }

    private static final class Entry {
        final CompletableFuture<List<BitSet>> result = new CompletableFuture<>();
        final AtomicInteger owners = new AtomicInteger(2); // cache and running calculation
        final long bytes;
        int requested;
        Entry(long bytes) { this.bytes = bytes; }
        void release() { if (owners.decrementAndGet() == 0) retainedBytes.addAndGet(-bytes); }
    }
    private record Key(long origin, int side) { }
}
