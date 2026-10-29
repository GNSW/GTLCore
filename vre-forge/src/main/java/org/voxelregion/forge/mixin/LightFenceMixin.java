/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import it.unimi.dsi.fastutil.objects.ObjectList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.SkyLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.voxelregion.forge.NativeAccess;
import org.voxelregion.forge.DetachedLightTasks;
import org.voxelregion.lighting.ClearedChunkLighting;

/** A same-chunk FIFO fence on the native light mailbox; never changes light-correct flags or generation tickets. */
@Mixin(ThreadedLevelLightEngine.class)
abstract class LightFenceMixin implements NativeAccess.LightFence {
    @Shadow @Final private ChunkMap chunkMap;
    @Shadow @Final private ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> sorterMailbox;
    @Shadow @Final private ObjectList<?> lightTasks;
    @Shadow private void runUpdate() { throw new AssertionError(); }

    @org.spongepowered.asm.mixin.injection.Inject(method = "initializeLight", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void vre$initializePreparedColumn(net.minecraft.world.level.chunk.ChunkAccess chunk, boolean alreadyLit,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<CompletableFuture<net.minecraft.world.level.chunk.ChunkAccess>> callback) {
        if (!alreadyLit && org.voxelregion.forge.ClearedInitialLighting.initializing(chunk))
            callback.setReturnValue(((ThreadedLevelLightEngine)(Object)this).initializeLight(chunk, true));
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "lightChunk", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void vre$publishInitialColumn(net.minecraft.world.level.chunk.ChunkAccess chunk, boolean alreadyLit,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<CompletableFuture<net.minecraft.world.level.chunk.ChunkAccess>> callback) {
        var result = org.voxelregion.forge.ClearedInitialLighting.light(chunk,
                installed -> ((ThreadedLevelLightEngine)(Object)this).lightChunk(chunk, installed));
        if (result != null) callback.setReturnValue(result);
    }

    @Override public CompletableFuture<Boolean> vre$clearAirRegion(java.util.List<NativeAccess.OpenColumn> input,
            int minSectionY, int sectionCount, boolean hasSky, BooleanSupplier stillValid) {
        var positions = java.util.List.copyOf(input);
        if (positions.isEmpty() || sectionCount < 1 || sectionCount > ClearedChunkLighting.MAX_HEIGHT / 16)
            throw new IllegalArgumentException("Invalid air light cohort");
        var result = new CompletableFuture<Boolean>();
        var remaining = new java.util.concurrent.atomic.AtomicInteger(positions.size());
        // Every chunk first crosses its own sorter lane. The last arrival drains all preceding
        // section-status work once; choosing a single chunk lane would race other lanes' updates.
        for (var column : positions) sorterMailbox.tell(ChunkTaskPriorityQueueSorter.message(() -> {
            if (remaining.decrementAndGet() != 0) return;
            try {
                do { runUpdate(); } while (!lightTasks.isEmpty());
                if (!stillValid.getAsBoolean()) { result.complete(false); return; }
                var engine = (ThreadedLevelLightEngine)(Object)this;
                var block = engine.getLayerListener(LightLayer.BLOCK);
                var sky = engine.getLayerListener(LightLayer.SKY);
                if (!(block instanceof LightEngine<?, ?> blockEngine) || !(block instanceof NativeAccess.VisibleLightData)
                        || !(block instanceof NativeAccess.LightBoundary) || hasSky && !(sky instanceof LightEngine<?, ?>)) {
                    result.complete(false); return;
                }
                var columns = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(positions.size());
                var images = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<NativeAccess.OpenColumn>(positions.size());
                for (var image : positions) {
                    var pos = image.position();
                    columns.add(pos.toLong());
                    images.put(pos.toLong(), image);
                    for (int section = 0; section < sectionCount; section++) {
                        long key = SectionPos.asLong(pos.x, minSectionY + section, pos.z);
                        vre$queueAllocated(blockEngine, key, new DataLayer(0));
                        if (hasSky && sky instanceof LightEngine<?, ?> skyEngine) {
                            int solid = Math.max(0, Math.min(16, image.firstAirY() - (minSectionY + section) * 16));
                            DataLayer data;
                            if (image.solved() != null) {
                                int uniform = image.solved().uniformSky(section);
                                data = uniform < 0 ? new DataLayer(image.solved().skySection(section)) : new DataLayer(uniform);
                            }
                            else if (solid == 0) data = new DataLayer(15);
                            else if (solid == 16) data = new DataLayer(0);
                            else { byte[] packed = new byte[2048]; Arrays.fill(packed, solid * 128, packed.length, (byte)255); data = new DataLayer(packed); }
                            vre$queueAllocated(skyEngine, key, data);
                        }
                    }
                }
                runUpdate();
                for (var image : positions) {
                    var pos = image.position();
                    if (image.solved() != null) {
                        // A worker solved adjacent columns together. Only independently solved
                        // group edges need native reconciliation; internal faces are already final.
                        int bandSections = Math.max(1, (image.firstAirY() - minSectionY * 16 + 15) / 16);
                        int top = (minSectionY + Math.min(sectionCount, bandSections)) * 16;
                        for (int side = 0; side < 4; side++) {
                            int dx = side == 0 ? -1 : side == 1 ? 1 : 0;
                            int dz = side == 2 ? -1 : side == 3 ? 1 : 0;
                            var neighbor = images.get(ChunkPos.asLong(pos.x + dx, pos.z + dz));
                            if (neighbor == null || image.solutionGroup() == 0 || neighbor.solutionGroup() != image.solutionGroup())
                                vre$bandFace(block, sky, pos, minSectionY * 16, top, dx, dz);
                        }
                    }
                    boolean west = !columns.contains(ChunkPos.asLong(pos.x-1, pos.z));
                    boolean east = !columns.contains(ChunkPos.asLong(pos.x+1, pos.z));
                    boolean north = !columns.contains(ChunkPos.asLong(pos.x, pos.z-1));
                    boolean south = !columns.contains(ChunkPos.asLong(pos.x, pos.z+1));
                    if (!(west || east || north || south)) continue;
                    if (west) vre$airFace(block, sky, pos, minSectionY, sectionCount, -1, 0, hasSky, image.firstAirY());
                    if (east) vre$airFace(block, sky, pos, minSectionY, sectionCount, 1, 0, hasSky, image.firstAirY());
                    if (north) vre$airFace(block, sky, pos, minSectionY, sectionCount, 0, -1, hasSky, image.firstAirY());
                    if (south) vre$airFace(block, sky, pos, minSectionY, sectionCount, 0, 1, hasSky, image.firstAirY());
                }
                runUpdate();
                result.complete(stillValid.getAsBoolean());
            } catch (RuntimeException | LinkageError error) { result.completeExceptionally(error); }
        }, column.position().toLong(), ((NativeAccess.ChunkSave) chunkMap).vre$queueLevel(column.position().toLong())));
        return result;
    }

    private static void vre$bandFace(LayerLightEventListener block, LayerLightEventListener sky,
            ChunkPos pos, int bottom, int top, int dx, int dz) {
        if (!(block instanceof NativeAccess.LightBoundary boundary)) return;
        var current = boundary.vre$lightChunk(pos.x, pos.z);
        var neighbor = boundary.vre$lightChunk(pos.x + dx, pos.z + dz);
        if (current == null || neighbor == null) return;
        var inside = new BlockPos.MutableBlockPos(); var outside = new BlockPos.MutableBlockPos();
        for (int y = bottom; y < top; y++) for (int i = 0; i < 16; i++) {
            int x = pos.getMinBlockX() + (dx < 0 ? 0 : dx > 0 ? 15 : i);
            int z = pos.getMinBlockZ() + (dz < 0 ? 0 : dz > 0 ? 15 : i);
            inside.set(x, y, z); outside.set(x + dx, y, z + dz);
            // Detached sky starts at the closed-boundary lower bound. Removing
            // terrain cannot darken external skylight, so only an improving edge
            // can change the solution. Do not invalidate/rebuild already exact gradients.
            int a = sky.getLightValue(inside), b = sky.getLightValue(outside);
            if (b > a + 1 && current.getBlockState(inside).getLightBlock(current, inside) < 15) sky.checkBlock(inside);
            if (a > b + 1 && neighbor.getBlockState(outside).getLightBlock(neighbor, outside) < 15) sky.checkBlock(outside);
            // External block light can be a stale remnant of a removed emitter.
            // Recheck its source as well as the cleared destination, never reuse it as an immutable seed.
            if (block.getLightValue(outside) > 0) { block.checkBlock(outside); block.checkBlock(inside); }
        }
    }

    private static void vre$queueAllocated(LightEngine<?, ?> engine, long section, DataLayer data) {
        // Native storage deliberately omits empty sections outside the retained terrain halo.
        // Their block light is zero and skylight inherits the next stored layer/top-of-column.
        // Queueing data there leaves it unconsumed: every later native inconsistency pass
        // rescans the ever-growing queue. Only publish layers that native storage actually owns.
        engine.queueSectionData(section, engine instanceof NativeAccess.VisibleLightData visible
                && !visible.vre$storesUpdatingSection(section) ? null : data);
    }

    /** AIR already has direct sky 15 and no internal emitters. Only external incoming block light
     * and outgoing sky into shadowed transmissive cells can change; solid walls/open sky add no work. */
    private static void vre$airFace(LayerLightEventListener block, LayerLightEventListener sky, ChunkPos pos,
            int minSectionY, int count, int dx, int dz, boolean hasSky, int firstAirY) {
        if (!(block instanceof NativeAccess.LightBoundary boundary)) return;
        int nx = pos.x + dx, nz = pos.z + dz;
        var neighbor = boundary.vre$lightChunk(nx, nz);
        if (neighbor == null) return;
        var outside = new BlockPos.MutableBlockPos(); var inside = new BlockPos.MutableBlockPos();
        int[] sourceY = new int[16]; int highestSource = Integer.MIN_VALUE;
        for (int i = 0; i < 16; i++) {
            int lx = dx < 0 ? 15 : dx > 0 ? 0 : i, lz = dz < 0 ? 15 : dz > 0 ? 0 : i;
            sourceY[i] = neighbor.getSkyLightSources().getLowestSourceY(lx, lz);
            highestSource = Math.max(highestSource, sourceY[i]);
        }
        for (int section = minSectionY; section < minSectionY + count; section++) {
            DataLayer incoming = block instanceof NativeAccess.VisibleLightData visible
                    ? visible.vre$copyVisibleSectionData(SectionPos.asLong(nx, section, nz)) : null;
            boolean hasBlock = incoming != null && !incoming.isEmpty();
            boolean checkSky = hasSky && (section << 4) < highestSource;
            if (!hasBlock && !checkSky) continue;
            for (int y = Math.max(section << 4, firstAirY); y < (section + 1) << 4; y++) for (int i = 0; i < 16; i++) {
                int lx = dx < 0 ? 15 : dx > 0 ? 0 : i, lz = dz < 0 ? 15 : dz > 0 ? 0 : i;
                outside.set((nx << 4) + lx, y, (nz << 4) + lz);
                if (hasBlock && incoming.get(lx, y & 15, lz) > 0) {
                    block.checkBlock(outside);
                    block.checkBlock(inside.set(outside.getX() - dx, y, outside.getZ() - dz));
                }
                if (checkSky && y < sourceY[i] && sky.getLightValue(outside) < 14
                        && neighbor.getBlockState(outside).getLightBlock(neighbor, outside) < 15)
                    sky.checkBlock(outside);
            }
        }
    }

    @Override public CompletableFuture<Void> vre$afterChunkWork(ChunkPos position) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        sorterMailbox.tell(ChunkTaskPriorityQueueSorter.message(() -> {
            try {
                // Prior tasks for this chunk have entered lightTasks through the same sorter.
                // This callback runs on the light thread, where no concurrent append is legal.
                do { runUpdate(); } while (!lightTasks.isEmpty());
                result.complete(null);
            } catch (RuntimeException | LinkageError failure) { result.completeExceptionally(failure); }
        }, position.toLong(), ((NativeAccess.ChunkSave) chunkMap).vre$queueLevel(position.toLong())));
        return result;
    }

    @Override public CompletableFuture<Void> vre$batchChecks(ChunkPos position, int[] sectionYs) {
        return vre$batchChecks(position, sectionYs, false);
    }

    @Override public CompletableFuture<Boolean> vre$solveAndInstall(ChunkPos position, int minSectionY,
            Supplier<ClearedChunkLighting.Snapshot> input, BooleanSupplier stillValid, Runnable releaseInput) {
        ClearedChunkLighting.Snapshot snapshot;
        try { snapshot = input.get(); }
        catch (RuntimeException | LinkageError failure) {
            releaseInput.run(); return CompletableFuture.failedFuture(failure);
        }
        if (snapshot == null) { releaseInput.run(); return CompletableFuture.completedFuture(false); }
        var priority = ((NativeAccess.ChunkSave) chunkMap).vre$queueLevel(position.toLong());
        return DetachedLightTasks.submit(snapshot, stillValid,
                callback -> sorterMailbox.tell(ChunkTaskPriorityQueueSorter.message(callback, position.toLong(), priority)),
                solved -> vre$installSolved(position, minSectionY, solved, stillValid), releaseInput);
    }

    /** Runs only on the native lighting thread. Native storage owns every installed DataLayer. */
    private boolean vre$installSolved(ChunkPos position, int minSectionY,
            ClearedChunkLighting.Result solved, BooleanSupplier stillValid) {
        do { runUpdate(); } while (!lightTasks.isEmpty());
        if (!stillValid.getAsBoolean()) return false;
        var engine = (ThreadedLevelLightEngine) (Object) this;
        LayerLightEventListener block = engine.getLayerListener(LightLayer.BLOCK);
        LayerLightEventListener sky = engine.getLayerListener(LightLayer.SKY);
        if (!(block instanceof LightEngine<?, ?> blockEngine)) return false;
        for (int section = 0; section < solved.sectionCount(); section++) {
            long key = SectionPos.asLong(position.x, minSectionY + section, position.z);
            int uniformBlock = solved.uniformBlock(section);
            vre$queueAllocated(blockEngine, key, uniformBlock >= 0 ? new DataLayer(uniformBlock) : new DataLayer(solved.blockSection(section)));
            if (sky instanceof LightEngine<?, ?> skyEngine) {
                int uniformSky = solved.uniformSky(section);
                vre$queueAllocated(skyEngine, key, uniformSky >= 0 ? new DataLayer(uniformSky) : new DataLayer(solved.skySection(section)));
            }
        }
        int count = solved.sectionCount(); solved = null;
        // LightEngine.queueSectionData is the native storage protocol underlying
        // LevelLightEngine's public method. On this owner thread it schedules the
        // queuedSections -> updatingSectionData -> visibleSectionData publication.
        // No raw storage map, copy-on-write buffer, light-correct flag or ticket is changed.
        runUpdate();
        vre$checkPrism(block, sky, position, minSectionY, count, false);
        runUpdate();
        if (!stillValid.getAsBoolean()) {
            vre$checkPrism(block, sky, position, minSectionY, count, true);
            runUpdate();
            return false;
        }
        return true;
    }

    @Override public CompletableFuture<Void> vre$fullChecks(ChunkPos position, int minSectionY, int sectionCount) {
        if (sectionCount < 1 || sectionCount > ClearedChunkLighting.MAX_HEIGHT / 16)
            throw new IllegalArgumentException("Invalid native light repair height");
        CompletableFuture<Void> result = new CompletableFuture<>();
        sorterMailbox.tell(ChunkTaskPriorityQueueSorter.message(() -> {
            try {
                do { runUpdate(); } while (!lightTasks.isEmpty());
                var engine = (ThreadedLevelLightEngine) (Object) this;
                vre$checkPrism(engine.getLayerListener(LightLayer.BLOCK), engine.getLayerListener(LightLayer.SKY),
                        position, minSectionY, sectionCount, true);
                runUpdate(); result.complete(null);
            } catch (RuntimeException | LinkageError error) { result.completeExceptionally(error); }
        }, position.toLong(), ((NativeAccess.ChunkSave) chunkMap).vre$queueLevel(position.toLong())));
        return result;
    }

    @Override public CompletableFuture<Void> vre$changedChecks(ChunkPos position, java.util.List<NativeAccess.LightChanges> input) {
        var changes = java.util.List.copyOf(input);
        var result = new CompletableFuture<Void>();
        // One sorter entry per chunk, not one entry for every removed voxel. The
        // exact native check set is retained and consumed on the light owner.
        sorterMailbox.tell(ChunkTaskPriorityQueueSorter.message(() -> {
            try {
                do { runUpdate(); } while (!lightTasks.isEmpty());
                var engine = (ThreadedLevelLightEngine)(Object)this;
                var block = engine.getLayerListener(LightLayer.BLOCK);
                var sky = engine.getLayerListener(LightLayer.SKY);
                var cursor = new BlockPos.MutableBlockPos();
                for (var section : changes) {
                    var cells = section.cells();
                    for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(i + 1))
                        vre$checkBoth(block, sky, cursor.set(position.getMinBlockX() + (i & 15),
                                section.sectionY() * 16 + (i >>> 8), position.getMinBlockZ() + ((i >>> 4) & 15)));
                }
                runUpdate(); result.complete(null);
            } catch (RuntimeException | LinkageError error) { result.completeExceptionally(error); }
        }, position.toLong(), ((NativeAccess.ChunkSave) chunkMap).vre$queueLevel(position.toLong())));
        return result;
    }

    private static void vre$checkPrism(LayerLightEventListener block, LayerLightEventListener sky,
            ChunkPos position, int minSectionY, int sectionCount, boolean all) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minY = minSectionY * 16, maxY = minY + sectionCount * 16;
        int minX = position.getMinBlockX(), minZ = position.getMinBlockZ();
        for (int y = minY; y < maxY; y++) {
            if (all || y == minY || y == maxY - 1) {
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                    vre$checkBoth(block, sky, cursor.set(minX + x, y, minZ + z));
            } else {
                for (int z = 0; z < 16; z++) {
                    vre$checkBoth(block, sky, cursor.set(minX, y, minZ + z));
                    vre$checkBoth(block, sky, cursor.set(minX + 15, y, minZ + z));
                }
                for (int x = 1; x < 15; x++) {
                    vre$checkBoth(block, sky, cursor.set(minX + x, y, minZ));
                    vre$checkBoth(block, sky, cursor.set(minX + x, y, minZ + 15));
                }
            }
        }
    }

    @Override public CompletableFuture<Void> vre$batchChecks(ChunkPos position, int[] sectionYs, boolean replaySkySources) {
        // Freeze caller-owned input before handing it to the native lighting thread.
        int[] sections = Arrays.stream(sectionYs).distinct().sorted().toArray();
        CompletableFuture<Void> result = new CompletableFuture<>();
        sorterMailbox.tell(ChunkTaskPriorityQueueSorter.message(() -> {
            try {
                // In particular, apply the preceding updateSectionStatus calls before checking nodes.
                do { runUpdate(); } while (!lightTasks.isEmpty());
                var engine = (ThreadedLevelLightEngine) (Object) this;
                LayerLightEventListener block = engine.getLayerListener(LightLayer.BLOCK);
                LayerLightEventListener sky = engine.getLayerListener(LightLayer.SKY);
                BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
                int minX = position.getMinBlockX(), minZ = position.getMinBlockZ();
                for (int i = 0; i < sections.length; i++) {
                    int baseY = sections[i] << 4;
                    // Four side faces, without duplicate edge nodes.
                    for (int y = baseY; y < baseY + 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            vre$checkBoth(block, sky, cursor.set(minX, y, minZ + z));
                            vre$checkBoth(block, sky, cursor.set(minX + 15, y, minZ + z));
                        }
                        for (int x = 1; x < 15; x++) {
                            vre$checkBoth(block, sky, cursor.set(minX + x, y, minZ));
                            vre$checkBoth(block, sky, cursor.set(minX + x, y, minZ + 15));
                        }
                    }
                    boolean bottom = i == 0 || sections[i - 1] != sections[i] - 1;
                    boolean top = i + 1 == sections.length || sections[i + 1] != sections[i] + 1;
                    // Adjacent cleared sections form one prism; their shared horizontal face is internal.
                    for (int x = 1; x < 15; x++) for (int z = 1; z < 15; z++) {
                        if (bottom) vre$checkBoth(block, sky, cursor.set(minX + x, baseY, minZ + z));
                        if (top) vre$checkBoth(block, sky, cursor.set(minX + x, baseY + 15, minZ + z));
                    }
                }
                // One native propagation pass consumes both layers' check sets. No generation tickets
                // or light-correct flags are modified by this completion acknowledgement.
                if (replaySkySources && sky instanceof SkyLightEngine skyEngine) {
                    // Existing 15-valued sources can feed old 14-valued interior air. Replaying
                    // increases alone prunes at those unchanged 14 nodes and misses newly opened
                    // side corridors. Native checkNode performs decrease-then-increase at each
                    // source/shadow interface, invalidating the old gradient before rebuilding it.
                    int[] lowest = new int[256];
                    NativeAccess.SkySources sources = (NativeAccess.SkySources) (Object) skyEngine;
                    for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                        lowest[(z << 4) | x] = sources.vre$lowestSourceY(minX + x, minZ + z, Integer.MAX_VALUE);
                    // x/z edges already participate in the six exterior faces. Equal-height open
                    // columns add no work; a narrow old shaft adds only its vertical source edges.
                    for (int z = 1; z < 15; z++) for (int x = 1; x < 15; x++) {
                        int column = (z << 4) | x;
                        int low = lowest[column];
                        int high = Math.max(Math.max(lowest[column - 1], lowest[column + 1]),
                                Math.max(lowest[column - 16], lowest[column + 16]));
                        if (low >= high) continue;
                        for (int section : sections) {
                            int bottom = Math.max(section << 4, low);
                            int top = Math.min((section << 4) + 16, high);
                            for (int y = bottom; y < top; y++)
                                sky.checkBlock(cursor.set(minX + x, y, minZ + z));
                        }
                    }
                }
                runUpdate();
                result.complete(null);
            } catch (RuntimeException | LinkageError failure) { result.completeExceptionally(failure); }
        }, position.toLong(), ((NativeAccess.ChunkSave) chunkMap).vre$queueLevel(position.toLong())));
        return result;
    }

    private static void vre$checkBoth(LayerLightEventListener block, LayerLightEventListener sky, BlockPos position) {
        block.checkBlock(position);
        sky.checkBlock(position);
    }
}
