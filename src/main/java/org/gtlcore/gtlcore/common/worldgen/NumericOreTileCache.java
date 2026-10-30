package org.gtlcore.gtlcore.common.worldgen;

import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Bounded, immutable numeric results for the exact noise leaves already approved by OreDensityCellCache. */
public final class NumericOreTileCache {

    public static final String DISABLE_PROPERTY = "gtlcore.disableOreNumericTileCache";
    public static final String ENABLE_PROPERTY = "gtlcore.enableOreNumericTileCache";
    private static final int SIDE = 16;
    private static final int POINTS = SIDE * SIDE * SIDE;
    private static final int SLOTS = 4096;
    private static final int LOCAL_SLOTS = 64;
    private static final long MAX_PAYLOAD_BYTES = (long) SLOTS * POINTS * Double.BYTES;
    private static final AtomicReferenceArray<Tile> TILES = new AtomicReferenceArray<>(SLOTS);
    private static final Object PUBLICATION_LOCK = new Object();
    private static volatile long epoch;
    private static final AtomicLong REQUESTED_POINTS = new AtomicLong();
    private static final AtomicLong LAST_TILE_HITS = new AtomicLong();
    private static final AtomicLong LOCAL_TILE_HITS = new AtomicLong();
    private static final AtomicLong SHARED_TILE_HITS = new AtomicLong();
    private static final AtomicLong TILE_FILLS = new AtomicLong();
    private static final AtomicLong COMPUTED_POINTS = new AtomicLong();
    private static final AtomicLong SLOT_REPLACEMENTS = new AtomicLong();
    private static final AtomicLong DUPLICATE_FILLS = new AtomicLong();

    private NumericOreTileCache() {}

    static Cursor create(NormalNoise noise, double xzScale, double yScale) {
        // Keep the exact cache available for comparisons. Full-pack measurements showed that
        // eager three-dimensional tiles overcompute sparse requests, so do not enable it by default.
        return !Boolean.getBoolean(ENABLE_PROPERTY) || Boolean.getBoolean(DISABLE_PROPERTY) ? null :
                new Cursor(noise, xzScale, yScale, epoch);
    }

    /** Clears retained results; older in-flight cursors may finish but cannot republish their tiles. */
    public static void clear() {
        synchronized (PUBLICATION_LOCK) {
            epoch++;
            for (int slot = 0; slot < SLOTS; slot++) TILES.set(slot, null);
        }
    }

    public static Statistics statistics() {
        int occupied = 0;
        for (int slot = 0; slot < SLOTS; slot++) if (TILES.get(slot) != null) occupied++;
        return new Statistics(REQUESTED_POINTS.get(), LAST_TILE_HITS.get(), LOCAL_TILE_HITS.get(),
                SHARED_TILE_HITS.get(), TILE_FILLS.get(), COMPUTED_POINTS.get(), SLOT_REPLACEMENTS.get(),
                DUPLICATE_FILLS.get(), occupied, MAX_PAYLOAD_BYTES);
    }

    /** Payload excludes tile/array headers, indexes and at most one strong last tile per active cursor. */
    public record Statistics(long requestedPoints, long lastTileHits, long localTileHits,
                             long sharedTileHits, long tileFills, long computedPoints, long slotReplacements,
                             long duplicateFills, int occupiedSlots, long maxPayloadBytes) {}

    interface Computer {

        double compute(int x, int y, int z);
    }

    /** Invocation-local lookup state. Weak L1 entries cannot pin 64 evicted tiles per noise sampler. */
    static final class Cursor {

        private final NormalNoise noise;
        private final long xzBits, yBits, generation;
        private final int domainHash;
        @SuppressWarnings("unchecked")
        private final WeakReference<Tile>[] local = (WeakReference<Tile>[]) new WeakReference<?>[LOCAL_SLOTS];
        private Tile last;
        private long requestedPoints, lastTileHits, localTileHits, sharedTileHits;
        private long tileFills, computedPoints, slotReplacements, duplicateFills;
        private boolean published;

        Cursor(NormalNoise noise, double xzScale, double yScale, long generation) {
            this.noise = noise;
            this.xzBits = Double.doubleToRawLongBits(xzScale);
            this.yBits = Double.doubleToRawLongBits(yScale);
            this.generation = generation;
            domainHash = mix(System.identityHashCode(noise) ^ Long.hashCode(xzBits) ^ Integer.rotateLeft(Long.hashCode(yBits), 11));
        }

        double sample(int x, int y, int z, Computer computer) {
            requestedPoints++;
            int tileX = x >> 4, tileY = y >> 4, tileZ = z >> 4;
            int offset = (x & 15) | ((y & 15) << 4) | ((z & 15) << 8);
            Tile tile = last;
            if (tile != null && tile.x == tileX && tile.y == tileY && tile.z == tileZ) {
                lastTileHits++;
                return tile.values[offset];
            }
            int hash = mix(domainHash ^ mix(tileX) ^ Integer.rotateLeft(mix(tileY), 11) ^ Integer.rotateLeft(mix(tileZ), 22));
            int localSlot = hash & (LOCAL_SLOTS - 1);
            WeakReference<Tile> reference = local[localSlot];
            tile = reference == null ? null : reference.get();
            if (matches(tile, tileX, tileY, tileZ)) {
                localTileHits++;
            } else {
                int sharedSlot = hash & (SLOTS - 1);
                tile = TILES.get(sharedSlot);
                if (matches(tile, tileX, tileY, tileZ)) {
                    sharedTileHits++;
                } else {
                    // Compute synchronously in this worker. Nested pool submissions or waiting on
                    // another fill could deadlock callers that already occupy the density pool.
                    double[] values = new double[POINTS];
                    int baseX = tileX << 4, baseY = tileY << 4, baseZ = tileZ << 4;
                    int index = 0;
                    for (int dz = 0; dz < SIDE; dz++) {
                        for (int dy = 0; dy < SIDE; dy++) {
                            for (int dx = 0; dx < SIDE; dx++) {
                                values[index++] = computer.compute(baseX | dx, baseY | dy, baseZ | dz);
                                computedPoints++;
                            }
                        }
                    }
                    tile = new Tile(noise, xzBits, yBits, generation, tileX, tileY, tileZ, values);
                    tileFills++;
                    // Publish only a complete immutable tile. Failed computations never enter
                    // the table; racing identical fills may duplicate work, never wait on it.
                    synchronized (PUBLICATION_LOCK) {
                        if (epoch == generation) {
                            Tile previous = TILES.get(sharedSlot);
                            if (matches(previous, tileX, tileY, tileZ)) {
                                duplicateFills++;
                                tile = previous;
                            } else {
                                if (previous != null) slotReplacements++;
                                TILES.set(sharedSlot, tile);
                            }
                        }
                    }
                }
                local[localSlot] = new WeakReference<>(tile);
            }
            last = tile;
            return tile.values[offset];
        }

        private boolean matches(Tile tile, int x, int y, int z) {
            return tile != null && tile.noise == noise && tile.xzBits == xzBits && tile.yBits == yBits &&
                    tile.generation == generation && tile.x == x && tile.y == y && tile.z == z;
        }

        void recordStatistics() {
            if (published) return;
            published = true;
            REQUESTED_POINTS.addAndGet(requestedPoints);
            LAST_TILE_HITS.addAndGet(lastTileHits);
            LOCAL_TILE_HITS.addAndGet(localTileHits);
            SHARED_TILE_HITS.addAndGet(sharedTileHits);
            TILE_FILLS.addAndGet(tileFills);
            COMPUTED_POINTS.addAndGet(computedPoints);
            SLOT_REPLACEMENTS.addAndGet(slotReplacements);
            DUPLICATE_FILLS.addAndGet(duplicateFills);
            last = null;
        }
    }

    private static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ (value >>> 16);
    }

    private static final class Tile {

        final NormalNoise noise;
        final long xzBits, yBits, generation;
        final int x, y, z;
        final double[] values;

        Tile(NormalNoise noise, long xzBits, long yBits, long generation, int x, int y, int z, double[] values) {
            this.noise = noise;
            this.xzBits = xzBits;
            this.yBits = yBits;
            this.generation = generation;
            this.x = x;
            this.y = y;
            this.z = z;
            this.values = values;
        }
    }
}
