package ca.spottedleaf.starlight.mixin.common.lightengine;

import ca.spottedleaf.starlight.common.light.StarLightEngine;
import ca.spottedleaf.starlight.common.light.StarLightInterface;
import ca.spottedleaf.starlight.common.light.StarLightLightingProvider;
import ca.spottedleaf.starlight.common.light.RegionLightLease;
import ca.spottedleaf.starlight.common.util.CoordinateUtils;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.voxelregion.forge.NativeAccess;
import org.voxelregion.lighting.ClearedChunkLighting;
import org.voxelregion.lighting.scheduler.SpatialTaskScheduler.ChunkRect;

@Mixin(ThreadedLevelLightEngine.class)
public abstract class ThreadedLevelLightEngineMixin extends LevelLightEngine implements StarLightLightingProvider, NativeAccess.LightFence {

    @Final
    @Shadow
    private ChunkMap chunkMap;

    @Final
    @Shadow
    private static Logger LOGGER;

    /** @author VRE contributors @reason Submit merged tasks to the global spatial provider. */
    @Overwrite
    public void tryScheduleUpdate() { this.getLightEngine().propagateChanges(); }

    /** @author VRE contributors @reason Stop world access only after all accepted writers finish. */
    @Overwrite(remap = false)
    public void close() {
        final var closed = this.getLightEngine().closeAsync();
        final var server = ((ServerLevel)this.getLightEngine().getWorld()).getServer();
        if (server.isSameThread()) server.managedBlock(closed::isDone);
        closed.join();
    }

    public ThreadedLevelLightEngineMixin(final LightChunkGetter chunkProvider, final boolean hasBlockLight, final boolean hasSkyLight) {
        super(chunkProvider, hasBlockLight, hasSkyLight);
    }

    @Unique
    private final Long2IntOpenHashMap chunksBeingWorkedOn = new Long2IntOpenHashMap();

    @Unique
    private CompletableFuture<Void> queueTaskForSection(final int chunkX, final int chunkY, final int chunkZ,
                                     final Supplier<StarLightInterface.LightQueue.ChunkTasks> runnable) {
        final ServerLevel world = (ServerLevel)this.getLightEngine().getWorld();

        final ChunkAccess center = this.getLightEngine().getAnyChunkNow(chunkX, chunkZ);
        if (center == null || !center.getStatus().isOrAfter(ChunkStatus.LIGHT)) {
            // do not accept updates in unlit chunks, unless we might be generating a chunk. thanks to the amazing
            // chunk scheduling, we could be lighting and generating a chunk at the same time
            return CompletableFuture.completedFuture(null);
        }

        if (center.getStatus() != ChunkStatus.FULL) {
            // do not keep chunk loaded, we are probably in a gen thread
            // if we proceed to add a ticket the chunk will be loaded, which is not what we want (avoid cascading gen)
            final StarLightInterface.LightQueue.ChunkTasks update = runnable.get();
            return update == null ? CompletableFuture.completedFuture(null) : update.onComplete.copy();
        }

        if (!world.getChunkSource().chunkMap.mainThreadExecutor.isSameThread()) {
            // ticket logic is not safe to run off-main, re-schedule
            final CompletableFuture<Void> accepted = new CompletableFuture<>();
            try {
                world.getChunkSource().chunkMap.mainThreadExecutor.execute(() -> {
                    try {
                        this.queueTaskForSection(chunkX, chunkY, chunkZ, runnable).whenComplete((ignored, error) -> {
                            if (error == null) accepted.complete(null); else accepted.completeExceptionally(error);
                        });
                        this.tryScheduleUpdate();
                    } catch (Throwable error) { accepted.completeExceptionally(error); }
                });
            } catch (Throwable error) { accepted.completeExceptionally(error); }
            return accepted;
        }

        synchronized (this.getLightEngine().admissionLock()) {
        final long key = CoordinateUtils.getChunkKey(chunkX, chunkZ);

        final StarLightInterface.LightQueue.ChunkTasks updateFuture = runnable.get();

        if (updateFuture == null) {
            // not scheduled
            return CompletableFuture.completedFuture(null);
        }

        if (updateFuture.isTicketAdded) {
            // ticket already added
            return updateFuture.onComplete.copy();
        }
        updateFuture.isTicketAdded = true;

        final int references = this.chunksBeingWorkedOn.addTo(key, 1);
        if (references == 0) {
            final ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            world.getChunkSource().addRegionTicket(StarLightInterface.CHUNK_WORK_TICKET, pos, 0, pos);
        }

        updateFuture.onComplete.whenCompleteAsync((final Void ignore, final Throwable failure) -> {
            final int newReferences = this.chunksBeingWorkedOn.get(key);
            if (newReferences == 1) {
                this.chunksBeingWorkedOn.remove(key);
                final ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                world.getChunkSource().removeRegionTicket(StarLightInterface.CHUNK_WORK_TICKET, pos, 0, pos);
            } else {
                this.chunksBeingWorkedOn.put(key, newReferences - 1);
            }
        }, world.getChunkSource().chunkMap.mainThreadExecutor).whenComplete((final Void ignore, final Throwable thr) -> {
            if (thr != null) {
                LOGGER.error("Failed to remove ticket level for post chunk task " + new ChunkPos(chunkX, chunkZ), thr);
            }
        });
        return updateFuture.onComplete.copy();
        }
    }

    /**
     * @reason Redirect scheduling call away from the vanilla light engine, as well as enforce
     * that chunk neighbours are loaded before the processing can occur
     * @author Spottedleaf
     */
    @Overwrite
    public void checkBlock(final BlockPos pos) {
        final BlockPos posCopy = pos.immutable();
        this.queueTaskForSection(posCopy.getX() >> 4, posCopy.getY() >> 4, posCopy.getZ() >> 4, () -> {
            return this.getLightEngine().blockChange(posCopy);
        });
    }

    /**
     * @reason Avoid messing with the vanilla light engine state
     * @author Spottedleaf
     */
    @Overwrite
    public void updateChunkStatus(final ChunkPos pos) {}

    /**
     * @reason Redirect to schedule for our own logic, as well as ensure 1 radius neighbours
     * are loaded
     * Note: Our scheduling logic will discard this call if the chunk is not lit, unloaded, or not at LIGHT stage yet.
     * @author Spottedleaf
     */
    @Overwrite
    public void updateSectionStatus(final SectionPos pos, final boolean notReady) {
        this.queueTaskForSection(pos.getX(), pos.getY(), pos.getZ(), () -> {
            return this.getLightEngine().sectionChange(pos, notReady);
        });
    }

    /**
     * @reason Avoid messing with the vanilla light engine state
     * @author Spottedleaf
     */
    @Overwrite
    public void propagateLightSources(final ChunkPos pos) {
        // handled by light()
    }

    /**
     * @reason Avoid messing with the vanilla light engine state
     * @author Spottedleaf
     */
    @Overwrite
    public void setLightEnabled(final ChunkPos pos, final boolean lightEnabled) {
        // light impl does not need to do this
    }

    /**
     * @reason Light data is now attached to chunks, and this means we need to hook into chunk loading logic
     * to load the data rather than rely on this call. This call also would mess with the vanilla light engine state.
     * @author Spottedleaf
     */
    @Overwrite
    public void queueSectionData(final LightLayer lightType, final SectionPos pos, final @Nullable DataLayer nibbles) {
        // load hooks inside ChunkSerializer
    }

    /**
     * @reason Avoid messing with the vanilla light engine state
     * @author Spottedleaf
     */
    @Overwrite
    public void retainData(final ChunkPos pos, final boolean retainData) {
        // light impl does not need to do this
    }

    /**
     * @reason Starlight does not have to do this
     * @author Spottedleaf
     */
    @Overwrite
    public CompletableFuture<ChunkAccess> initializeLight(final ChunkAccess chunk, final boolean lit) {
        return CompletableFuture.completedFuture(chunk);
    }

    /**
     * @reason Route to new logic to either light or just load the data
     * @author Spottedleaf
     */
    @Overwrite
    public CompletableFuture<ChunkAccess> lightChunk(final ChunkAccess chunk, final boolean lit) {
        final ChunkPos chunkPos = chunk.getPos();

        CompletableFuture<Void> publication;
        try {
        publication = this.getLightEngine().submitChunkLight(chunkPos, () -> {
            final Boolean[] emptySections = StarLightEngine.getEmptySectionsForChunk(chunk);
            if (!lit) {
                chunk.setLightCorrect(false);
                this.getLightEngine().lightChunk(chunk, emptySections);
                chunk.setLightCorrect(true);
            } else {
                this.getLightEngine().forceLoadInChunk(chunk, emptySections);
                // can't really force the chunk to be edged checked, as we need neighbouring chunks - but we don't have
                // them, so if it's not loaded then i guess we can't do edge checks. later loads of the chunk should
                // catch what we miss here.
                this.getLightEngine().checkChunkEdges(chunkPos.x, chunkPos.z);
            }

        });
        } catch (Throwable error) {
            publication = CompletableFuture.failedFuture(error);
        }
        return publication.thenApply(ignored -> chunk).whenComplete((final ChunkAccess c, final Throwable throwable) -> {
            // The generation future includes all coalesced blocks/edges and spatial publication.
            this.chunkMap.releaseLightTicket(chunkPos);
            if (throwable != null) {
                LOGGER.error("Failed to light chunk " + chunkPos, throwable);
            }
        });
    }

    @Override public boolean vre$rebuildsWholeChunks() { return true; }

    @Override public CompletableFuture<Void> vre$afterChunkWork(final ChunkPos pos) {
        return this.getLightEngine().fence(ChunkRect.around(pos.x, pos.z, 0));
    }

    @Override public CompletableFuture<Void> vre$changedChecks(final ChunkPos pos,
                                                               final List<NativeAccess.LightChanges> changes) {
        if (changes.isEmpty()) return this.vre$afterChunkWork(pos);
        final ChunkAccess target = this.getLightEngine().getAnyChunkNow(pos.x, pos.z);
        if (target == null || !target.getStatus().isOrAfter(ChunkStatus.LIGHT)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Missing light update target " + pos));
        }
        final var immutable = List.copyOf(changes);
        final CompletableFuture<Void> result = this.queueTaskForSection(pos.x, 0, pos.z, () -> {
            StarLightInterface.LightQueue.ChunkTasks last = null;
            for (final var change : immutable) {
                final var cells = change.cells();
                for (int bit = cells.nextSetBit(0); bit >= 0; bit = cells.nextSetBit(bit + 1)) {
                    last = this.getLightEngine().blockChange(new BlockPos(pos.getMinBlockX() + (bit & 15),
                            (change.sectionY() << 4) + (bit >>> 8), pos.getMinBlockZ() + ((bit >>> 4) & 15)));
                }
            }
            return last;
        });
        this.tryScheduleUpdate();
        return result;
    }

    @Override public CompletableFuture<Void> vre$batchChecks(final ChunkPos pos, final int[] sections) {
        return this.vre$batchChecks(pos, sections, true);
    }

    @Override public CompletableFuture<Void> vre$batchChecks(final ChunkPos pos, final int[] sections,
                                                            final boolean replaySkySources) {
        return sections.length == 0 ? this.vre$afterChunkWork(pos) : this.vre$fullChecks(pos, 0, 1);
    }

    @Override public CompletableFuture<Void> vre$combinedChecks(final ChunkPos pos,
            final List<NativeAccess.LightChanges> changes, final int[] sections, final boolean replaySkySources) {
        return sections.length == 0 ? this.vre$changedChecks(pos, changes) : this.vre$fullChecks(pos, 0, 1);
    }

    @Override public CompletableFuture<Void> vre$fullChecks(final ChunkPos pos, final int minSectionY,
                                                          final int sectionCount) {
        final ServerLevel world = (ServerLevel)this.getLightEngine().getWorld();
        if (!world.getServer().isSameThread()) {
            final CompletableFuture<Void> result = new CompletableFuture<>();
            try {
                world.getServer().execute(() -> {
                    try {
                        this.vre$fullChecks(pos, minSectionY, sectionCount).whenComplete((ignored, error) -> {
                            if (error == null) result.complete(null); else result.completeExceptionally(error);
                        });
                    } catch (Throwable error) { result.completeExceptionally(error); }
                });
            } catch (Throwable error) { result.completeExceptionally(error); }
            return result;
        }
        final ChunkAccess expected = this.getLightEngine().getAnyChunkNow(pos.x, pos.z);
        if (expected == null || !expected.getStatus().isOrAfter(ChunkStatus.LIGHT)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Missing light publication target " + pos));
        }
        final RegionLightLease lease = RegionLightLease.acquire(world, this.getLightEngine(), pos, 3);
        try {
        final CompletableFuture<Void> result = this.queueTaskForSection(pos.x, 0, pos.z, () ->
                this.getLightEngine().enqueueChunkLight(pos, 3, () -> {
                    if (this.getLightEngine().getAnyChunkNow(pos.x, pos.z) != expected) {
                        throw new IllegalStateException("Light publication target changed " + pos);
                    }
                    // Publish 3x3; relightChunks reads temporary neighbours out to radius 3.
                    this.getLightEngine().rebuildColumnAndHalo(pos);
                }));
        this.tryScheduleUpdate();
        return result.whenCompleteAsync((ignored, error) -> lease.close(), world.getServer());
        } catch (Throwable error) {
            lease.close();
            return CompletableFuture.failedFuture(error);
        }
    }

    @Override public CompletableFuture<Boolean> vre$clearAirRegion(final List<NativeAccess.OpenColumn> positions,
            final int minSectionY, final int sectionCount, final boolean hasSky, final BooleanSupplier stillValid) {
        // The global provider owns all arrays. Callers fall back to our relight API, never vanilla storage.
        return CompletableFuture.completedFuture(false);
    }

    @Override public CompletableFuture<Boolean> vre$solveAndInstall(final ChunkPos pos, final int minSectionY,
            final Supplier<ClearedChunkLighting.Snapshot> input, final BooleanSupplier stillValid,
            final Runnable releaseInput) {
        try { releaseInput.run(); }
        catch (Throwable error) { return CompletableFuture.failedFuture(error); }
        return CompletableFuture.completedFuture(false);
    }
}
