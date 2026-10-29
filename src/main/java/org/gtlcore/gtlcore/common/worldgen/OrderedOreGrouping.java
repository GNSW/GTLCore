package org.gtlcore.gtlcore.common.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** GT 1.4.4 grouping with its map layout/order retained and one ChunkPos per occupied chunk. */
public final class OrderedOreGrouping {

    public static final String DISABLE_PROPERTY = "gtlcore.disableOrderedOreGrouping";
    private static final AtomicLong COMPLETED_CALLS = new AtomicLong();
    private static final AtomicLong GROUPED_CHUNKS = new AtomicLong();
    private static final ThreadLocal<long[]> THREAD_COMPLETED = ThreadLocal.withInitial(() -> new long[1]);

    public static <T> Map<ChunkPos, Map<BlockPos, T>> groupByChunks(Map<BlockPos, T> source) {
        // Both original collector suppliers use the no-argument fastutil constructor. Changing
        // their initial capacity changes collision placement and the later ore placement order.
        Map<ChunkPos, Map<BlockPos, T>> grouped = new Object2ObjectOpenHashMap<>();
        var byChunk = new Long2ObjectOpenHashMap<Map<BlockPos, T>>();
        // fastutil 8.5.9's stream spliterator walks the table forwards, while its iterator walks
        // backwards. Keep the collector's encounter order; do not replace this with a for-each.
        source.entrySet().spliterator().forEachRemaining(entry -> {
            BlockPos position = entry.getKey();
            int chunkX = position.getX() >> 4, chunkZ = position.getZ() >> 4;
            long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
            Map<BlockPos, T> values = byChunk.get(chunkKey);
            if (values == null) {
                values = new Object2ObjectOpenHashMap<>();
                // Only first encounters insert into the original outer map, exactly as before.
                grouped.put(new ChunkPos(chunkX, chunkZ), values);
                byChunk.put(chunkKey, values);
            }
            // Retain the original merge operation, including first-wins and null rejection.
            values.merge(position, entry.getValue(), OrderedOreGrouping::firstWins);
        });
        THREAD_COMPLETED.get()[0]++;
        GROUPED_CHUNKS.addAndGet(grouped.size());
        COMPLETED_CALLS.incrementAndGet();
        return grouped;
    }

    private static <T> T firstWins(T first, T ignored) {
        return first;
    }

    /** Calling-thread evidence avoids attributing concurrent background generation to a fixture. */
    public static long completedCallsOnCurrentThread() {
        return THREAD_COMPLETED.get()[0];
    }

    public static Statistics statistics() {
        return new Statistics(COMPLETED_CALLS.get(), GROUPED_CHUNKS.get());
    }

    public record Statistics(long completedCalls, long groupedChunks) {}

    private OrderedOreGrouping() {}
}
