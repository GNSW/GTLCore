package ca.spottedleaf.starlight.common.light;

import ca.spottedleaf.starlight.common.chunk.ExtendedChunk;
import ca.spottedleaf.starlight.common.config.GlobalLightConfig;
import ca.spottedleaf.starlight.common.thread.GlobalLightWorkers;
import ca.spottedleaf.starlight.common.util.CoordinateUtils;
import ca.spottedleaf.starlight.common.util.WorldUtil;
import ca.spottedleaf.starlight.common.world.ExtendedWorld;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortCollection;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import org.voxelregion.lighting.scheduler.SpatialTaskScheduler;
import org.voxelregion.lighting.scheduler.SpatialTaskScheduler.ChunkRect;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class StarLightInterface {

    public static final TicketType<ChunkPos> CHUNK_WORK_TICKET = TicketType.create("starlight_chunk_work_ticket", (p1, p2) -> Long.compare(p1.toLong(), p2.toLong()));

    /**
     * Can be {@code null}, indicating the light is all empty.
     */
    protected final Level world;
    protected final LightChunkGetter lightAccess;

    protected final ArrayDeque<SkyStarLightEngine> cachedSkyPropagators;
    protected final ArrayDeque<BlockStarLightEngine> cachedBlockPropagators;

    protected final LightQueue lightQueue = new LightQueue(this);

    protected final LayerLightEventListener skyReader;
    protected final LayerLightEventListener blockReader;
    protected final boolean isClientSide;

    protected final int minSection;
    protected final int maxSection;
    protected final int minLightSection;
    protected final int maxLightSection;

    public final LevelLightEngine lightEngine;

    private final boolean hasBlockLight;
    private final boolean hasSkyLight;
    private final SpatialTaskScheduler<Object> scheduler;
    private boolean closing;
    private int maximumOwnershipRadius = 2;
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();

    public StarLightInterface(final LightChunkGetter lightAccess, final boolean hasSkyLight, final boolean hasBlockLight, final LevelLightEngine lightEngine) {
        this.lightAccess = lightAccess;
        this.world = lightAccess == null ? null : (Level)lightAccess.getLevel();
        this.cachedSkyPropagators = hasSkyLight && lightAccess != null ? new ArrayDeque<>() : null;
        this.cachedBlockPropagators = hasBlockLight && lightAccess != null ? new ArrayDeque<>() : null;
        this.isClientSide = !(this.world instanceof ServerLevel);
        this.scheduler = this.isClientSide ? null : new SpatialTaskScheduler<>(GlobalLightWorkers.executor(),
                GlobalLightConfig.parallelism(), GlobalLightConfig.maxOutstanding(), ignored -> new Object());
        if (this.world == null) {
            this.minSection = -4;
            this.maxSection = 19;
            this.minLightSection = -5;
            this.maxLightSection = 20;
        } else {
            this.minSection = WorldUtil.getMinSection(this.world);
            this.maxSection = WorldUtil.getMaxSection(this.world);
            this.minLightSection = WorldUtil.getMinLightSection(this.world);
            this.maxLightSection = WorldUtil.getMaxLightSection(this.world);
        }
        this.lightEngine = lightEngine;
        this.hasBlockLight = hasBlockLight;
        this.hasSkyLight = hasSkyLight;
        this.skyReader = !hasSkyLight ? LayerLightEventListener.DummyLightLayerEventListener.INSTANCE : new ProviderLightReader(this) {
            @Override
            public void checkBlock(final BlockPos blockPos) {
                StarLightInterface.this.lightEngine.checkBlock(blockPos.immutable());
            }

            @Override
            public void propagateLightSources(final ChunkPos chunkPos) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean hasLightWork() {
                // not really correct...
                return StarLightInterface.this.hasUpdates();
            }

            @Override
            public int runLightUpdates() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setLightEnabled(final ChunkPos chunkPos, final boolean bl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DataLayer getDataLayerData(final SectionPos pos) {
                final ChunkAccess chunk = StarLightInterface.this.getAnyChunkNow(pos.getX(), pos.getZ());
                if (chunk == null || (!StarLightInterface.this.isClientSide && !chunk.isLightCorrect()) || !chunk.getStatus().isOrAfter(ChunkStatus.LIGHT)) {
                    return null;
                }

                final int sectionY = pos.getY();

                if (sectionY > StarLightInterface.this.maxLightSection || sectionY < StarLightInterface.this.minLightSection) {
                    return null;
                }

                if (((ExtendedChunk)chunk).getSkyEmptinessMap() == null) {
                    return null;
                }

                return ((ExtendedChunk)chunk).getSkyNibbles()[sectionY - StarLightInterface.this.minLightSection].toVanillaNibble();
            }

            @Override
            public int getLightValue(final BlockPos blockPos) {
                return StarLightInterface.this.getSkyLightValue(blockPos, StarLightInterface.this.getAnyChunkNow(blockPos.getX() >> 4, blockPos.getZ() >> 4));
            }

            @Override
            public void updateSectionStatus(final SectionPos pos, final boolean notReady) {
                StarLightInterface.this.sectionChange(pos, notReady);
            }
        };
        this.blockReader = !hasBlockLight ? LayerLightEventListener.DummyLightLayerEventListener.INSTANCE : new ProviderLightReader(this) {
            @Override
            public void checkBlock(final BlockPos blockPos) {
                StarLightInterface.this.lightEngine.checkBlock(blockPos.immutable());
            }

            @Override
            public void propagateLightSources(final ChunkPos chunkPos) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean hasLightWork() {
                // not really correct...
                return StarLightInterface.this.hasUpdates();
            }

            @Override
            public int runLightUpdates() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setLightEnabled(final ChunkPos chunkPos, final boolean bl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DataLayer getDataLayerData(final SectionPos pos) {
                final ChunkAccess chunk = StarLightInterface.this.getAnyChunkNow(pos.getX(), pos.getZ());

                if (chunk == null || pos.getY() < StarLightInterface.this.minLightSection || pos.getY() > StarLightInterface.this.maxLightSection) {
                    return null;
                }

                return ((ExtendedChunk)chunk).getBlockNibbles()[pos.getY() - StarLightInterface.this.minLightSection].toVanillaNibble();
            }

            @Override
            public int getLightValue(final BlockPos blockPos) {
                return StarLightInterface.this.getBlockLightValue(blockPos, StarLightInterface.this.getAnyChunkNow(blockPos.getX() >> 4, blockPos.getZ() >> 4));
            }

            @Override
            public void updateSectionStatus(final SectionPos pos, final boolean notReady) {
                StarLightInterface.this.sectionChange(pos, notReady);
            }
        };
    }

    public boolean hasSkyLight() {
        return this.hasSkyLight;
    }

    public boolean hasBlockLight() {
        return this.hasBlockLight;
    }

    public int getSkyLightValue(final BlockPos blockPos, final ChunkAccess chunk) {
        if (!this.hasSkyLight) {
            return 0;
        }
        final int x = blockPos.getX();
        int y = blockPos.getY();
        final int z = blockPos.getZ();

        final int minSection = this.minSection;
        final int maxSection = this.maxSection;
        final int minLightSection = this.minLightSection;
        final int maxLightSection = this.maxLightSection;

        if (chunk == null || (!this.isClientSide && !chunk.isLightCorrect()) || !chunk.getStatus().isOrAfter(ChunkStatus.LIGHT)) {
            return 15;
        }

        int sectionY = y >> 4;

        if (sectionY > maxLightSection) {
            return 15;
        }

        if (sectionY < minLightSection) {
            sectionY = minLightSection;
            y = sectionY << 4;
        }

        final SWMRNibbleArray[] nibbles = ((ExtendedChunk)chunk).getSkyNibbles();
        final SWMRNibbleArray immediate = nibbles[sectionY - minLightSection];

        if (!immediate.isNullNibbleVisible()) {
            return immediate.getVisible(x, y, z);
        }

        final boolean[] emptinessMap = ((ExtendedChunk)chunk).getSkyEmptinessMap();

        if (emptinessMap == null) {
            return 15;
        }

        // are we above this chunk's lowest empty section?
        int lowestY = minLightSection - 1;
        for (int currY = maxSection; currY >= minSection; --currY) {
            if (emptinessMap[currY - minSection]) {
                continue;
            }

            // should always be full lit here
            lowestY = currY;
            break;
        }

        if (sectionY > lowestY) {
            return 15;
        }

        // this nibble is going to depend solely on the skylight data above it
        // find first non-null data above (there does exist one, as we just found it above)
        for (int currY = sectionY + 1; currY <= maxLightSection; ++currY) {
            final SWMRNibbleArray nibble = nibbles[currY - minLightSection];
            if (!nibble.isNullNibbleVisible()) {
                return nibble.getVisible(x, 0, z);
            }
        }

        // should never reach here
        return 15;
    }

    public int getBlockLightValue(final BlockPos blockPos, final ChunkAccess chunk) {
        if (!this.hasBlockLight) {
            return 0;
        }
        final int y = blockPos.getY();
        final int cy = y >> 4;

        final int minLightSection = this.minLightSection;
        final int maxLightSection = this.maxLightSection;

        if (cy < minLightSection || cy > maxLightSection) {
            return 0;
        }

        if (chunk == null) {
            return 0;
        }

        final SWMRNibbleArray nibble = ((ExtendedChunk)chunk).getBlockNibbles()[cy - minLightSection];
        return nibble.getVisible(blockPos.getX(), y, blockPos.getZ());
    }

    public int getRawBrightness(final BlockPos pos, final int ambientDarkness) {
        final ChunkAccess chunk = this.getAnyChunkNow(pos.getX() >> 4, pos.getZ() >> 4);

        final int sky = this.getSkyLightValue(pos, chunk) - ambientDarkness;
        // Don't fetch the block light level if the skylight level is 15, since the value will never be higher.
        if (sky == 15) {
            return 15;
        }
        final int block = this.getBlockLightValue(pos, chunk);
        return Math.max(sky, block);
    }

    public LayerLightEventListener getSkyReader() {
        return this.skyReader;
    }

    public LayerLightEventListener getBlockReader() {
        return this.blockReader;
    }

    public boolean isClientSide() {
        return this.isClientSide;
    }

    public ChunkAccess getAnyChunkNow(final int chunkX, final int chunkZ) {
        if (this.world == null) {
            // empty world
            return null;
        }
        return ((ExtendedWorld)this.world).getAnyChunkImmediately(chunkX, chunkZ);
    }

    public boolean hasUpdates() {
        return !this.lightQueue.isEmpty() || this.scheduler != null && this.scheduler.statistics().outstanding() != 0;
    }

    public Level getWorld() {
        return this.world;
    }

    public LightChunkGetter getLightAccess() {
        return this.lightAccess;
    }

    protected final SkyStarLightEngine getSkyLightEngine() {
        if (this.cachedSkyPropagators == null) {
            return null;
        }
        final SkyStarLightEngine ret;
        synchronized (this.cachedSkyPropagators) {
            ret = this.cachedSkyPropagators.pollFirst();
        }

        if (ret == null) {
            return new SkyStarLightEngine(this.world);
        }
        return ret;
    }

    protected final void releaseSkyLightEngine(final SkyStarLightEngine engine) {
        if (this.cachedSkyPropagators == null) {
            return;
        }
        synchronized (this.cachedSkyPropagators) {
            this.cachedSkyPropagators.addFirst(engine);
        }
    }

    protected final BlockStarLightEngine getBlockLightEngine() {
        if (this.cachedBlockPropagators == null) {
            return null;
        }
        final BlockStarLightEngine ret;
        synchronized (this.cachedBlockPropagators) {
            ret = this.cachedBlockPropagators.pollFirst();
        }

        if (ret == null) {
            return new BlockStarLightEngine(this.world);
        }
        return ret;
    }

    protected final void releaseBlockLightEngine(final BlockStarLightEngine engine) {
        if (this.cachedBlockPropagators == null) {
            return;
        }
        synchronized (this.cachedBlockPropagators) {
            this.cachedBlockPropagators.addFirst(engine);
        }
    }

    public LightQueue.ChunkTasks blockChange(final BlockPos pos) {
        if (this.world == null || pos.getY() < WorldUtil.getMinBlockY(this.world) || pos.getY() > WorldUtil.getMaxBlockY(this.world)) { // empty world
            return null;
        }

        return this.lightQueue.queueBlockChange(pos);
    }

    public LightQueue.ChunkTasks sectionChange(final SectionPos pos, final boolean newEmptyValue) {
        if (this.world == null) { // empty world
            return null;
        }

        return this.lightQueue.queueSectionChange(pos, newEmptyValue);
    }

    public void forceLoadInChunk(final ChunkAccess chunk, final Boolean[] emptySections) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();
        boolean success = false;
        try {
            if (skyEngine != null) {
                skyEngine.forceHandleEmptySectionChanges(this.lightAccess, chunk, emptySections);
            }
            if (blockEngine != null) {
                blockEngine.forceHandleEmptySectionChanges(this.lightAccess, chunk, emptySections);
            }
            success = true;
        } finally {
            if (success) {
                this.releaseSkyLightEngine(skyEngine);
                this.releaseBlockLightEngine(blockEngine);
            }
        }
    }

    public void loadInChunk(final int chunkX, final int chunkZ, final Boolean[] emptySections) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();
        boolean success = false;
        try {
            if (skyEngine != null) {
                skyEngine.handleEmptySectionChanges(this.lightAccess, chunkX, chunkZ, emptySections);
            }
            if (blockEngine != null) {
                blockEngine.handleEmptySectionChanges(this.lightAccess, chunkX, chunkZ, emptySections);
            }
            success = true;
        } finally {
            if (success) {
                this.releaseSkyLightEngine(skyEngine);
                this.releaseBlockLightEngine(blockEngine);
            }
        }
    }

    public void lightChunk(final ChunkAccess chunk, final Boolean[] emptySections) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();
        boolean success = false;
        try {
            if (skyEngine != null) {
                skyEngine.light(this.lightAccess, chunk, emptySections);
            }
            if (blockEngine != null) {
                blockEngine.light(this.lightAccess, chunk, emptySections);
            }
            success = true;
        } finally {
            if (success) {
                this.releaseSkyLightEngine(skyEngine);
                this.releaseBlockLightEngine(blockEngine);
            }
        }
    }

    public void relightChunks(final Set<ChunkPos> chunks, final Consumer<ChunkPos> chunkLightCallback,
                              final IntConsumer onComplete) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();
        boolean success = false;
        try {
            if (skyEngine != null) {
                skyEngine.relightChunks(this.lightAccess, chunks, blockEngine == null ? chunkLightCallback : null,
                        blockEngine == null ? onComplete : null);
            }
            if (blockEngine != null) {
                blockEngine.relightChunks(this.lightAccess, chunks, chunkLightCallback, onComplete);
            }
            success = true;
        } finally {
            if (success) {
                this.releaseSkyLightEngine(skyEngine);
                this.releaseBlockLightEngine(blockEngine);
            }
        }
    }

    public void checkChunkEdges(final int chunkX, final int chunkZ) {
        this.checkSkyEdges(chunkX, chunkZ);
        this.checkBlockEdges(chunkX, chunkZ);
    }

    public void checkSkyEdges(final int chunkX, final int chunkZ) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        boolean success = false;
        try {
            if (skyEngine != null) {
                skyEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ);
            }
            success = true;
        } finally {
            if (success) this.releaseSkyLightEngine(skyEngine);
        }
    }

    public void checkBlockEdges(final int chunkX, final int chunkZ) {
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();
        boolean success = false;
        try {
            if (blockEngine != null) {
                blockEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ);
            }
            success = true;
        } finally {
            if (success) this.releaseBlockLightEngine(blockEngine);
        }
    }

    public void checkSkyEdges(final int chunkX, final int chunkZ, final ShortCollection sections) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        boolean success = false;
        try {
            if (skyEngine != null) {
                skyEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ, sections);
            }
            success = true;
        } finally {
            if (success) this.releaseSkyLightEngine(skyEngine);
        }
    }

    public void checkBlockEdges(final int chunkX, final int chunkZ, final ShortCollection sections) {
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();
        boolean success = false;
        try {
            if (blockEngine != null) {
                blockEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ, sections);
            }
            success = true;
        } finally {
            if (success) this.releaseBlockLightEngine(blockEngine);
        }
    }

    public void scheduleChunkLight(final ChunkPos pos, final Runnable run) {
        this.lightQueue.queueChunkLighting(pos, run);
    }

    public LightQueue.ChunkTasks enqueueChunkLight(final ChunkPos pos, final int ownershipRadius, final Runnable run) {
        return this.lightQueue.queueChunkLighting(pos, ownershipRadius, run);
    }

    /** Worker-only: reconstruct the affected halo, not just the changed center's light arrays. */
    public void rebuildColumnAndHalo(final ChunkPos centerPos) {
        final ChunkAccess center = this.getAnyChunkNow(centerPos.x, centerPos.z);
        if (center == null || !center.getStatus().isOrAfter(ChunkStatus.LIGHT)) {
            throw new IllegalStateException("Cannot relight missing/unlit chunk " + centerPos);
        }
        if (!center.isLightCorrect()) {
            this.lightChunk(center, StarLightEngine.getEmptySectionsForChunk(center));
            center.setLightCorrect(true);
        }
        final Set<ChunkPos> affected = new java.util.LinkedHashSet<>();
        affected.add(centerPos);
        for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
            final ChunkAccess chunk = this.getAnyChunkNow(centerPos.x + x, centerPos.z + z);
            if (chunk != null && chunk.isLightCorrect() && chunk.getStatus().isOrAfter(ChunkStatus.LIGHT)) {
                affected.add(chunk.getPos());
            }
        }
        try {
            this.relightChunks(affected, pos -> {
                final ChunkAccess chunk = this.getAnyChunkNow(pos.x, pos.z);
                if (chunk == null) throw new IllegalStateException("Chunk unloaded while relighting " + pos);
                chunk.setLightCorrect(true);
                chunk.setUnsaved(true);
            }, null);
        } catch (Throwable failure) {
            for (ChunkPos pos : affected) {
                final ChunkAccess chunk = this.getAnyChunkNow(pos.x, pos.z);
                if (chunk != null) chunk.setLightCorrect(false);
            }
            throw failure;
        }
    }

    /** Accepted update receipt, including all work coalesced into this immutable task generation. */
    public CompletableFuture<Void> submitChunkLight(final ChunkPos pos, final Runnable run) {
        final LightQueue.ChunkTasks task = this.lightQueue.queueChunkLighting(pos, run);
        this.propagateChanges();
        return task.onComplete.copy();
    }

    /** Covers both the pending merge queue and active workers; native sorter emptiness is insufficient. */
    public CompletableFuture<Void> fence(final ChunkRect area) {
        synchronized (this.lightQueue) {
            final List<CompletableFuture<?>> pending = new ArrayList<>();
            for (final LightQueue.ChunkTasks task : this.lightQueue.chunkTasks.values()) {
                int x = CoordinateUtils.getChunkX(task.chunkCoordinate);
                int z = CoordinateUtils.getChunkZ(task.chunkCoordinate);
                if ((long)x + task.ownershipRadius >= area.minX() && (long)x - task.ownershipRadius <= area.maxX()
                        && (long)z + task.ownershipRadius >= area.minZ() && (long)z - task.ownershipRadius <= area.maxZ()) pending.add(task.onComplete);
            }
            if (this.scheduler != null) pending.add(this.scheduler.fence(area).toCompletableFuture());
            this.propagateChanges();
            return CompletableFuture.allOf(pending.toArray(CompletableFuture<?>[]::new));
        }
    }

    /** Nonblocking snapshot used by final save/shutdown before closing chunk storage. */
    public CompletableFuture<Void> fenceAll() {
        synchronized (this.lightQueue) {
            final List<CompletableFuture<?>> pending = new ArrayList<>();
            for (final LightQueue.ChunkTasks task : this.lightQueue.chunkTasks.values()) pending.add(task.onComplete);
            if (this.scheduler != null) pending.add(this.scheduler.fenceAll().toCompletableFuture());
            this.propagateChanges();
            return CompletableFuture.allOf(pending.toArray(CompletableFuture<?>[]::new));
        }
    }

    /**
     * Nonblocking serialization guard. -1 means queued/active work; equal nonnegative generations
     * before/after a copy prove no new dispatched task raced the visible-data copy. Checking the
     * merge queue visits only nearby centers, not all pending tasks in a large world.
     */
    public long quiescentGeneration(final ChunkRect area) {
        synchronized (this.lightQueue) {
            final int radius = this.maximumOwnershipRadius;
            final long width = (long)area.maxX() - area.minX() + 1 + 2L * radius;
            final long depth = (long)area.maxZ() - area.minZ() + 1 + 2L * radius;
            if (width * depth > 1_048_576L) return -1;
            for (long z = (long)area.minZ() - radius; z <= (long)area.maxZ() + radius; z++) {
                for (long x = (long)area.minX() - radius; x <= (long)area.maxX() + radius; x++) {
                    if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) continue;
                    final LightQueue.ChunkTasks task = this.lightQueue.chunkTasks.get(CoordinateUtils.getChunkKey((int)x, (int)z));
                    if (task != null && x + task.ownershipRadius >= area.minX() && x - task.ownershipRadius <= area.maxX()
                            && z + task.ownershipRadius >= area.minZ() && z - task.ownershipRadius <= area.maxZ()) return -1;
                }
            }
            if (this.scheduler == null) return 0;
            final long sequence = this.scheduler.statistics().submitted();
            final CompletableFuture<Void> ready = this.scheduler.fence(area).toCompletableFuture();
            return ready.isDone() && !ready.isCompletedExceptionally() && !ready.isCancelled() ? sequence : -1;
        }
    }

    public CompletableFuture<Void> closeAsync() {
        synchronized (this.lightQueue) {
            this.closing = true;
            this.propagateChanges();
            this.finishCloseIfDrained();
            return this.stopped.copy();
        }
    }

    private void finishCloseIfDrained() {
        if (!this.closing || !this.lightQueue.chunkTasks.isEmpty()) return;
        if (this.scheduler == null) this.stopped.complete(null);
        else this.scheduler.closeAsync().whenComplete((ignored, error) -> {
            if (error == null) this.stopped.complete(null); else this.stopped.completeExceptionally(error);
        });
    }

    public SpatialTaskScheduler.Statistics schedulerStatistics() {
        return this.scheduler == null ? null : this.scheduler.statistics();
    }

    /** Owner admission holds this through ticket registration; workers use the same dispatch lock. */
    public Object admissionLock() { return this.lightQueue; }

    public void removeChunkTasks(final ChunkPos pos) {
        this.lightQueue.removeChunk(pos);
    }

    public void propagateChanges() {
        if (this.scheduler != null) {
            this.dispatchServerTasks();
            return;
        }
        if (this.lightQueue.isEmpty()) {
            return;
        }

        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();
        boolean success = false;
        try {
            LightQueue.ChunkTasks task;
            while ((task = this.lightQueue.removeFirstTask()) != null) {
                try {
                    this.handleTask(task, skyEngine, blockEngine);
                    task.onComplete.complete(null);
                } catch (Throwable failure) {
                    task.onComplete.completeExceptionally(failure);
                    throw failure;
                }
            }
            success = true;
        } finally {
            if (success) {
                this.releaseSkyLightEngine(skyEngine);
                this.releaseBlockLightEngine(blockEngine);
            }
        }
    }

    private void dispatchServerTasks() {
        synchronized (this.lightQueue) {
            while (!this.lightQueue.chunkTasks.isEmpty()
                    && this.scheduler.statistics().outstanding() < GlobalLightConfig.maxOutstanding()) {
                final LightQueue.ChunkTasks task = this.lightQueue.removeFirstTask();
                try {
                    this.scheduler.submitChunk(CoordinateUtils.getChunkX(task.chunkCoordinate),
                            CoordinateUtils.getChunkZ(task.chunkCoordinate), task.ownershipRadius, ignored -> {
                        final SkyStarLightEngine sky = this.getSkyLightEngine();
                        final BlockStarLightEngine block = this.getBlockLightEngine();
                        boolean success = false;
                        try {
                            this.handleTask(task, sky, block);
                            success = true;
                            return CompletableFuture.completedFuture(null);
                        } catch (Throwable failure) {
                            // The lease still excludes every overlapping writer. Do not let autosave
                            // certify partially published light after the failed receipt leaves the queue.
                            try { this.invalidateFailedTask(task); }
                            catch (Throwable invalidationFailure) { failure.addSuppressed(invalidationFailure); }
                            throw failure;
                        } finally {
                            // Never pool scratch engines left in a failed intermediate state.
                            if (success) {
                                this.releaseSkyLightEngine(sky);
                                this.releaseBlockLightEngine(block);
                            }
                        }
                    }).completion().whenComplete((ignored, failure) -> {
                        if (failure == null) task.onComplete.complete(null);
                        else task.onComplete.completeExceptionally(failure);
                        this.dispatchServerTasks();
                    });
                } catch (Throwable failure) {
                    task.onComplete.completeExceptionally(failure);
                }
            }
            this.finishCloseIfDrained();
        }
    }

    private void invalidateFailedTask(final LightQueue.ChunkTasks task) {
        // Helpers already discard failed scratch locally, before another worker can borrow it.
        // Also drop idle world caches so subsequent recovery begins with fresh engine state.
        if (this.cachedSkyPropagators != null) synchronized (this.cachedSkyPropagators) { this.cachedSkyPropagators.clear(); }
        if (this.cachedBlockPropagators != null) synchronized (this.cachedBlockPropagators) { this.cachedBlockPropagators.clear(); }
        final int cx = CoordinateUtils.getChunkX(task.chunkCoordinate);
        final int cz = CoordinateUtils.getChunkZ(task.chunkCoordinate);
        for (int z = -task.ownershipRadius; z <= task.ownershipRadius; z++) {
            for (int x = -task.ownershipRadius; x <= task.ownershipRadius; x++) {
                final ChunkAccess chunk = this.getAnyChunkNow(cx + x, cz + z);
                if (chunk != null) {
                    chunk.setLightCorrect(false);
                    chunk.setUnsaved(true);
                }
            }
        }
    }

    private void handleTask(final LightQueue.ChunkTasks task, final SkyStarLightEngine skyEngine,
                            final BlockStarLightEngine blockEngine) {
                if (task.lightTasks != null) {
                    for (final Runnable run : task.lightTasks) {
                        run.run();
                    }
                }

                final long coordinate = task.chunkCoordinate;
                final int chunkX = CoordinateUtils.getChunkX(coordinate);
                final int chunkZ = CoordinateUtils.getChunkZ(coordinate);

                final Set<BlockPos> positions = task.changedPositions;
                final Boolean[] sectionChanges = task.changedSectionSet;

                if (skyEngine != null && (!positions.isEmpty() || sectionChanges != null)) {
                    skyEngine.blocksChangedInChunk(this.lightAccess, chunkX, chunkZ, positions, sectionChanges);
                }
                if (blockEngine != null && (!positions.isEmpty() || sectionChanges != null)) {
                    blockEngine.blocksChangedInChunk(this.lightAccess, chunkX, chunkZ, positions, sectionChanges);
                }

                if (skyEngine != null && task.queuedEdgeChecksSky != null) {
                    skyEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ, task.queuedEdgeChecksSky);
                }
                if (blockEngine != null && task.queuedEdgeChecksBlock != null) {
                    blockEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ, task.queuedEdgeChecksBlock);
                }

    }

    public static final class LightQueue {

        protected final Long2ObjectLinkedOpenHashMap<ChunkTasks> chunkTasks = new Long2ObjectLinkedOpenHashMap<>();
        protected final StarLightInterface manager;

        public LightQueue(final StarLightInterface manager) {
            this.manager = manager;
        }

        public synchronized boolean isEmpty() {
            return this.chunkTasks.isEmpty();
        }

        public synchronized LightQueue.ChunkTasks queueBlockChange(final BlockPos pos) {
            this.checkOpen();
            final ChunkTasks tasks = this.chunkTasks.computeIfAbsent(CoordinateUtils.getChunkKey(pos), ChunkTasks::new);
            tasks.changedPositions.add(pos.immutable());
            return tasks;
        }

        public synchronized LightQueue.ChunkTasks queueSectionChange(final SectionPos pos, final boolean newEmptyValue) {
            this.checkOpen();
            if (pos.getY() < this.manager.minSection || pos.getY() > this.manager.maxSection) return null;
            final ChunkTasks tasks = this.chunkTasks.computeIfAbsent(CoordinateUtils.getChunkKey(pos), ChunkTasks::new);

            if (tasks.changedSectionSet == null) {
                tasks.changedSectionSet = new Boolean[this.manager.maxSection - this.manager.minSection + 1];
            }
            tasks.changedSectionSet[pos.getY() - this.manager.minSection] = Boolean.valueOf(newEmptyValue);

            return tasks;
        }

        public synchronized LightQueue.ChunkTasks queueChunkLighting(final ChunkPos pos, final Runnable lightTask) {
            return this.queueChunkLighting(pos, 2, lightTask);
        }

        public synchronized LightQueue.ChunkTasks queueChunkLighting(final ChunkPos pos, final int ownershipRadius,
                                                                    final Runnable lightTask) {
            this.checkOpen();
            if (ownershipRadius < 2 || ownershipRadius > 128) throw new IllegalArgumentException("Invalid light ownership radius");
            this.manager.maximumOwnershipRadius = Math.max(this.manager.maximumOwnershipRadius, ownershipRadius);
            final ChunkTasks tasks = this.chunkTasks.computeIfAbsent(CoordinateUtils.getChunkKey(pos), ChunkTasks::new);
            tasks.ownershipRadius = Math.max(tasks.ownershipRadius, ownershipRadius);
            if (tasks.lightTasks == null) {
                tasks.lightTasks = new ArrayList<>();
            }
            tasks.lightTasks.add(lightTask);

            return tasks;
        }

        public synchronized LightQueue.ChunkTasks queueChunkSkylightEdgeCheck(final SectionPos pos, final ShortCollection sections) {
            this.checkOpen();
            final ChunkTasks tasks = this.chunkTasks.computeIfAbsent(CoordinateUtils.getChunkKey(pos), ChunkTasks::new);

            ShortOpenHashSet queuedEdges = tasks.queuedEdgeChecksSky;
            if (queuedEdges == null) {
                queuedEdges = tasks.queuedEdgeChecksSky = new ShortOpenHashSet();
            }
            queuedEdges.addAll(sections);

            return tasks;
        }

        public synchronized LightQueue.ChunkTasks queueChunkBlocklightEdgeCheck(final SectionPos pos, final ShortCollection sections) {
            this.checkOpen();
            final ChunkTasks tasks = this.chunkTasks.computeIfAbsent(CoordinateUtils.getChunkKey(pos), ChunkTasks::new);

            ShortOpenHashSet queuedEdges = tasks.queuedEdgeChecksBlock;
            if (queuedEdges == null) {
                queuedEdges = tasks.queuedEdgeChecksBlock = new ShortOpenHashSet();
            }
            queuedEdges.addAll(sections);

            return tasks;
        }

        public void removeChunk(final ChunkPos pos) {
            final ChunkTasks tasks;
            synchronized (this) {
                tasks = this.chunkTasks.remove(CoordinateUtils.getChunkKey(pos));
            }
            if (tasks != null) {
                tasks.onComplete.complete(null);
            }
        }

        public synchronized ChunkTasks removeFirstTask() {
            if (this.chunkTasks.isEmpty()) {
                return null;
            }
            return this.chunkTasks.removeFirst();
        }

        private void checkOpen() {
            if (this.manager.closing) throw new RejectedExecutionException("World lighting has closed");
        }

        public static final class ChunkTasks {

            public int ownershipRadius = 2;

            public final Set<BlockPos> changedPositions = new ObjectOpenHashSet<>();
            public Boolean[] changedSectionSet;
            public ShortOpenHashSet queuedEdgeChecksSky;
            public ShortOpenHashSet queuedEdgeChecksBlock;
            public List<Runnable> lightTasks;

            public boolean isTicketAdded = false;
            public final CompletableFuture<Void> onComplete = new CompletableFuture<>();

            public final long chunkCoordinate;

            public ChunkTasks(final long chunkCoordinate) {
                this.chunkCoordinate = chunkCoordinate;
            }
        }
    }
}
