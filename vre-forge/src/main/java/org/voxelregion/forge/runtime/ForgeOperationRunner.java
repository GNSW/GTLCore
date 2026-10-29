/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.spatial.ChunkKey;
import org.voxelregion.spatial.SectionKey;
import org.voxelregion.geometry.Volume;
import org.voxelregion.spatial.ChunkCursor;
import org.voxelregion.spatial.CenterOutChunkCursor;
import org.voxelregion.geometry.Point3;
import org.voxelregion.forge.NativeCommitFences;
import org.voxelregion.forge.PaletteSectionClearBackend;
import org.voxelregion.forge.ChunkPacketQueue;
import org.voxelregion.forge.HostIntegration;
import org.voxelregion.forge.GenerationGate;
import org.voxelregion.forge.RegionGeometry;
import org.voxelregion.propagation.FrozenOcclusion;
import net.minecraftforge.fml.ModList;

/** Bounded server-thread execution with palette batches and durable native completion fences. */
final class ForgeOperationRunner {
    private static final TicketType<UUID> TICKET = TicketType.create("voxelregion_test", Comparator.<UUID>naturalOrder());
    private static final TagKey<Block> PROTECTED = TagKey.create(Registries.BLOCK, new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "protected"));
    private static final Map<UUID, HeldChunk> HELD = new HashMap<>();
    private static final Map<ServerLevel, it.unimi.dsi.fastutil.longs.LongOpenHashSet> RESERVED = new java.util.IdentityHashMap<>();
    private static final Map<UUID, EntityWork> ENTITIES = new HashMap<>();
    private static final Map<UUID, java.util.ArrayDeque<PendingCommit>> IN_FLIGHT = new HashMap<>();
    private static final Map<UUID, CompletableFuture<GenerationGate.Registration>> GENERATING = new HashMap<>();
    private static final Map<UUID, CompletableFuture<Boolean>> EXISTING = new HashMap<>();
    private static final Map<UUID, ChunkPreparation> PREPARING = new HashMap<>();
    private static final Map<UUID, org.voxelregion.forge.PostProcessingClearance> DEFERRED_POST_PROCESSING = new HashMap<>();
    private static final int MAX_ENTITY_CANDIDATES = 16_384;
    private static int roundRobin;
    private static boolean executingStep;
    private static final org.voxelregion.runtime.CompletionWindow completionWindow = new org.voxelregion.runtime.CompletionWindow();
    private static int admissionWindow = 128, completedThisFrame;
    static int admissionWindow() { return Math.min(admissionWindow, AdapterConfig.MAX_IN_FLIGHT_CHUNKS.get()); }
    static int pendingCommits() {
        int result = 0;
        for (var queue : IN_FLIGHT.values()) result += queue.size();
        return result;
    }
    static boolean executingStep() { return executingStep; }
    private static final PaletteSectionClearBackend.HostHooks BULK_HOOKS = new PaletteSectionClearBackend.HostHooks() {
        @Override public boolean supportsBulk(ServerLevel level) {
            // Embedding hosts with additional block/section watchers must register an audited hook implementation.
            return HostIntegration.supportsBulk(level);
        }
        @Override public long mutationRevision(LevelChunk chunk) { return ChunkMutationTracking.revision(chunk); }
        @Override public void beforePaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY) { ChunkMutationTracking.changing(chunk); }
        @Override public void afterPaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY) { ChunkMutationTracking.changed(chunk); }
        @Override public void afterSectionCleared(ServerLevel level, LevelChunk chunk, PaletteSectionClearBackend.Prepared previous) {
            HostIntegration.afterSectionCleared(level, chunk, previous);
        }
    };

    static OperationData.Task submit(ServerLevel level, UUID actor, BlockPos center, int radius, int fuse, boolean generate) throws IOException {
        return submit(level, actor, center, radius, fuse, generate, true);
    }

    static OperationData.Task submit(ServerLevel level, UUID actor, BlockPos center, int radius, int fuse,
                                     boolean generate, boolean clearFluids) throws IOException {
        return submitConfigured(level, actor, center, radius, fuse, generate, clearFluids, null);
    }

    static OperationData.Task submitGeometry(ServerLevel level, UUID actor, RegionGeometry geometry,
            org.voxelregion.forge.ForgeRegionEngine.Options options) throws IOException {
        IntBox b = geometry.volume().bounds();
        if (b.isEmpty()) throw new IllegalArgumentException("Empty operation geometry");
        BlockPos center = BlockPos.containing(((long)b.minX() + b.maxX()) / 2.0,
                ((long)b.minY() + b.maxY()) / 2.0, ((long)b.minZ() + b.maxZ()) / 2.0);
        double dx = Math.max(center.getX() + .5 - b.minX(), b.maxX() - center.getX() - .5);
        double dy = Math.max(center.getY() + .5 - b.minY(), b.maxY() - center.getY() - .5);
        double dz = Math.max(center.getZ() + .5 - b.minZ(), b.maxZ() - center.getZ() - .5);
        double radius = Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz));
        if (radius > AdapterConfig.MAX_RADIUS.get()) throw new IllegalArgumentException("Geometry enclosing radius exceeds server maximum");
        return submitConfigured(level, actor, center, Math.max(1, (int) radius), options.fuseTicks(),
                options.generateMissingChunks(), options.clearFluids(), geometry, options.destroyBedrock());
    }

    static OperationData.Task submitOptions(ServerLevel level, UUID actor, BlockPos center, int radius,
            org.voxelregion.forge.ForgeRegionEngine.Options options) throws IOException {
        return submitConfigured(level, actor, center, radius, options.fuseTicks(), options.generateMissingChunks(),
                options.clearFluids(), null, options.destroyBedrock());
    }

    private static OperationData.Task submitConfigured(ServerLevel level, UUID actor, BlockPos center, int radius, int fuse,
            boolean generate, boolean clearFluids, RegionGeometry geometry) throws IOException {
        return submitConfigured(level, actor, center, radius, fuse, generate, clearFluids, geometry, false);
    }

    private static OperationData.Task submitConfigured(ServerLevel level, UUID actor, BlockPos center, int radius, int fuse,
            boolean generate, boolean clearFluids, RegionGeometry geometry, boolean destroyBedrock) throws IOException {
        if (radius < 1 || radius > AdapterConfig.MAX_RADIUS.get())
            throw new IllegalArgumentException("Radius " + radius + " must be 1.." + AdapterConfig.MAX_RADIUS.get()
                    + "; active server config: " + RuntimeBridge.activeConfigFile() + " (operations.maximum_radius)");
        if (fuse < 0 || fuse > 1200) throw new IllegalArgumentException("Fuse " + fuse + " must be 0..1200 ticks");
        OperationData data = OperationData.get(level);
        long pending = 0;
        for (ServerLevel world : level.getServer().getAllLevels())
            pending += OperationData.get(world).tasks.stream().filter(t -> !t.terminal()).count();
        if (pending >= AdapterConfig.MAX_OPERATIONS.get()) throw new IllegalStateException("Maximum pending operations reached");
        var task = new OperationData.Task(UUID.randomUUID(), actor, center.getX() + .5, center.getY() + .5, center.getZ() + .5,
                radius, fuse, generate, clearFluids, AdapterConfig.RESISTANCE_LIMIT.get(), AdapterConfig.ENTITY_DAMAGE.get(),
                AdapterConfig.PROTECT_ALL_CLAIMS.get(), AdapterConfig.PHYSICS.get(), level.getMinSection());
        task.destroyBedrock = destroyBedrock;
        task.clearedMissingTerrain = true;
        if (geometry != null) { task.geometry = geometry; task.volume = geometry.volume(); }
        task.owner = level;
        task.wholeChunk = true;
        task.centralBootstrap = false;
        task.chunkX = Math.floorDiv((int)Math.floor(task.cx), 16);
        task.chunkZ = Math.floorDiv((int)Math.floor(task.cz), 16);
        task.progression = AdapterConfig.PROGRESSION.get();
        task.propagation = AdapterConfig.PROPAGATION.get();
        task.occlusionCell = AdapterConfig.OCCLUSION_CELL.get();
        if (task.propagation != FrozenOcclusion.Mode.NONE && !java.util.Set.of(4,8,16).contains(task.occlusionCell))
            throw new IllegalArgumentException("Occlusion macro cells must be 4, 8 or 16");
        task.shellWidth = AdapterConfig.SHELL_WIDTH.get();
        if (task.volume.bounds().maxY() <= level.getMinBuildHeight() || task.volume.bounds().minY() >= level.getMaxBuildHeight())
            throw new IllegalArgumentException("Sphere does not intersect this dimension's build height");
        IntentJournal.append(level, task);
        data.tasks.removeIf(t -> t.terminal() && !IN_FLIGHT.containsKey(t.id) && !HELD.containsKey(t.id)
                && !GENERATING.containsKey(t.id) && data.tasks.size() > 64);
        data.tasks.add(task);
        data.setDirty();
        return task;
    }

    static void tick(MinecraftServer server) {
        tick(server, RuntimeBridge.sharedDeadline());
    }

    static void tick(MinecraftServer server, long deadline) {
        completedThisFrame = 0;
        // Always drain submitted work, including paused, cancelled and failed tasks.
        for (ServerLevel level : server.getAllLevels()) {
            OperationData data = OperationData.get(level);
            for (var task : data.tasks) {
                drainCommits(level, data, task);
                if (pruneTerminalMemory(task)) data.setDirty();
            }
        }
        admissionWindow = completionWindow.observe(completedThisFrame, pendingCommits(), AdapterConfig.MAX_IN_FLIGHT_CHUNKS.get());
        Budget budget = new Budget(deadline, AdapterConfig.VISITS_PER_TICK.get(), AdapterConfig.WRITES_PER_TICK.get());
        List<Work> work = new ArrayList<>();
        long metadata = estimatedMetadataBytes(server) + OperationOcclusion.estimatedBytes();
        for (ServerLevel level : server.getAllLevels()) {
            OperationData data = OperationData.get(level);
            for (OperationData.Task task : data.tasks) {
                if (task.state == OperationData.State.FUSE) {
                    if (task.fuse > 0) {
                        if (task.fuse % 10 == 0)
                            level.sendParticles(ParticleTypes.SMOKE, task.cx, task.cy, task.cz, 4, .1, .15, .1, .01);
                        task.fuse--;
                    } else task.state = OperationData.State.RUNNING;
                    data.setDirty();
                }
                if (task.state == OperationData.State.RUNNING || task.state == OperationData.State.RECOVERING
                        || task.state == OperationData.State.SETTLING) work.add(new Work(level, data, task));
            }
        }
        if (work.isEmpty()) return;
        if (metadata > AdapterConfig.MAX_PENDING_BYTES.get()) {
            long transientBytes = org.voxelregion.forge.ClearedGeneration.estimatedPendingBytes()
                    + org.voxelregion.forge.ParallelChunkCompression.estimatedBytes()
                    + org.voxelregion.forge.ClearedChunkLightBatch.estimatedPendingBytes()
                    + org.voxelregion.forge.AirRegionLightBatch.estimatedBytes();
            if (transientBytes > 0) {
                // Native loading may enqueue waiting records between admission checks. Drain
                // those jobs without invalidating the field and triggering ordinary generation.
                for (Work item : work) item.task.message = "Waiting for in-flight preparation/compression memory.";
                return;
            }
            for (Work item : work) pause(item.task, OperationData.State.PAUSED,
                    "Global operation metadata budget reached; cancel old operations or raise the configured cap.");
            return;
        }
        int idle = 0;
        while (budget.available() && idle < work.size()) {
            Work item = work.get(Math.floorMod(roundRobin++, work.size()));
            if (!item.task.active() || item.task.state == OperationData.State.FUSE) { idle++; continue; }
            try {
                executingStep = true;
                if (step(item, budget)) idle = 0; else idle++;
            } catch (RuntimeException ex) {
                item.task.state = OperationData.State.FAILED;
                item.task.message = "Native callback/adapter failed; no automatic retry: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
                RuntimeBridge.LOGGER.error("Region operation {} failed at chunk {},{}", item.task.id, item.task.chunkX, item.task.chunkZ, ex);
                try { IntentJournal.checkpoint(item.level, item.task); } catch (RuntimeException journalFailure) { ex.addSuppressed(journalFailure); }
                OperationOcclusion.release(item.task.id);
                item.task.needsChunkPacket = true;
                try { settlePartial(item.task); }
                catch (RuntimeException cleanupFailure) { ex.addSuppressed(cleanupFailure); release(item.task.id); }
            } finally {
                executingStep = false;
            }
            item.data.setDirty();
        }
    }

    private static boolean step(Work work, Budget budget) {
        ServerLevel level = work.level;
        OperationData.Task task = work.task;
        for (var earlier : work.data.tasks) {
            if (earlier == task) break;
            if ((task.propagation != FrozenOcclusion.Mode.NONE || earlier.propagation != FrozenOcclusion.Mode.NONE)
                    && task.volume.bounds().intersects(earlier.volume.bounds())
                    && (!earlier.terminal() || IN_FLIGHT.containsKey(earlier.id) || HELD.containsKey(earlier.id))) {
                task.message = "Waiting for earlier overlapping propagation operation " + earlier.id
                        + " (including paused work and outstanding completion fences).";
                return false;
            }
        }
        if (task.state == OperationData.State.SETTLING) {
            if (task.unconfirmedFences != 0) return false;
            task.state = OperationData.State.RUNNING;
        }
        if (task.propagation != FrozenOcclusion.Mode.NONE && !task.occlusionReady) {
            var planning = OperationOcclusion.step(level, task, budget.visits, budget.deadline,
                    () -> hold(level, task), () -> releaseTicket(task.id));
            budget.visits -= Math.max(1, planning.visits());
            if (!planning.ready()) return planning.visits() > 0 || !task.active();
        }
        if (!task.entitiesHandled) return entityStep(level, task, budget);
        if (task.clearedMissingTerrain && task.generate && task.wholeChunk
                && task.propagation == FrozenOcclusion.Mode.NONE && task.physics == AdapterConfig.Physics.NONE
                && task.resistance >= 1200.0) {
            org.voxelregion.forge.ClearedGeneration.register(level, task.id, task.intentHash(), task.volume,
                    task.clearFluids, task.destroyBedrock, () -> task.active() && task.state != OperationData.State.FUSE,
                    pos -> ClaimChecks.check(level, pos.x, pos.z, task.protectEveryClaim) == ClaimChecks.Decision.ALLOW);
        }
        if (task.phase >= task.phaseCount()) {
            if (task.unconfirmedFences != 0) { task.state = OperationData.State.SETTLING; return false; }
            task.state = OperationData.State.WRITTEN;
            pruneTerminalMemory(task);
            task.message = "All requested phases completed; native lighting and terrain/POI persistence fences acknowledged."
                    + (task.skippedUnloaded == 0 ? "" : (task.wholeChunk ? " Generation-disabled mode excluded " : " Loaded-only mode excluded ")
                    + task.skippedUnloaded + " chunks.");
            IntentJournal.checkpoint(level, task);
            release(task.id);
            return true;
        }
        Volume currentVolume = task.phaseVolume(level.getMinBuildHeight(), level.getMaxBuildHeight());
        boolean centerOrder = task.wholeChunk && task.progression == AdapterConfig.Progression.CENTER_OUT;
        if (centerOrder && task.centerCursor == null) {
            Point3 center = Point3.of(task.cx, task.cy, task.cz);
            task.centerCursor = task.restoredCenterCursor == null
                    ? new CenterOutChunkCursor(currentVolume, center, level.getMinBuildHeight(), level.getMaxBuildHeight())
                    : CenterOutChunkCursor.restore(currentVolume, center, level.getMinBuildHeight(), level.getMaxBuildHeight(), task.restoredCenterCursor);
            if (task.restoredCenterCursor != null) { task.replayOffset = task.cursorOffset; task.restoredCenterCursor = null; }
        }
        if (!centerOrder && task.phaseCursor == null) {
            if (task.restoredCursor != null) {
                task.phaseCursor = ChunkCursor.restore(currentVolume, level.getMinBuildHeight(), level.getMaxBuildHeight(), task.restoredCursor);
                task.replayOffset = task.cursorOffset; task.restoredCursor = null;
            } else task.phaseCursor = new ChunkCursor(currentVolume, level.getMinBuildHeight(), level.getMaxBuildHeight());
        }
        java.util.Iterator<ChunkKey> chunkCursor = centerOrder ? task.centerCursor : task.phaseCursor;
        // Restore an iterator without an unbounded restart loop; no world reads/writes occur while replaying its geometry.
        if (task.replayOffset < task.cursorOffset) {
            budget.visits--;
            if (!chunkCursor.hasNext()) throw new IllegalStateException("Persisted chunk cursor is outside the phase");
            chunkCursor.next(); task.replayOffset++;
            return true;
        }
        if (!task.wholeChunk && !task.chunkSelected && task.phase == 0 && task.centralBootstrap && !task.centerChunkScheduled) {
            task.centerChunkScheduled = true;
            task.chunkX = Math.floorDiv((int)Math.floor(task.cx), 16); task.chunkZ = Math.floorDiv((int)Math.floor(task.cz), 16);
            task.chunkSelected = true; task.sectionY = level.getMinSection(); task.voxel = 0;
        }
        if (!task.chunkSelected) {
            budget.visits--;
            if (!chunkCursor.hasNext()) {
                if (task.unconfirmedFences != 0) {
                    task.state = OperationData.State.SETTLING;
                    task.message = "Waiting for all chunk fences in this phase: " + task.unconfirmedFences;
                    return false;
                }
                task.phase++; task.cursorOffset = 0; task.replayOffset = 0;
                task.phaseCursor = null; task.centerCursor = null; task.activeVolume = null;
                IntentJournal.checkpoint(level, task);
                return true;
            }
            ChunkKey next = chunkCursor.next(); task.cursorOffset++; task.replayOffset++;
            task.chunkX = next.x(); task.chunkZ = next.z(); task.chunkSelected = true;
            task.sectionY = level.getMinSection(); task.voxel = 0;
        }
        if (task.excludedChunks.contains(ChunkPos.asLong(task.chunkX, task.chunkZ))) {
            finishChunk(level, task);
            return true;
        }
        ClaimChecks.Decision permission = ClaimChecks.check(level, task.chunkX, task.chunkZ, task.protectEveryClaim);
        if (permission == ClaimChecks.Decision.UNAVAILABLE) {
            pause(task, OperationData.State.PAUSED_PROTECTION, "FTB protection is not ready or its API failed; no writes permitted.");
            return true;
        }
        if (permission == ClaimChecks.Decision.DENY) {
            skipDeniedChunk(level, task);
            return true;
        }

        if (!task.chunkStarted && pendingCommits() >= admissionWindow()) {
            task.message = "Waiting for light/save/packet completion capacity (window=" + admissionWindow() + ").";
            return false;
        }
        ChunkReadAhead.fill(level, task, budget.deadline);

        LevelChunk chunk = level.getChunkSource().getChunkNow(task.chunkX, task.chunkZ);
        if (chunk == null) {
            if (!task.generate && !task.wholeChunk) {
                // Exclude an unloaded chunk rather than allowing a distant corner to prevent visible central work.
                if (!excludeChunk(level, task)) return false;
                task.skippedUnloaded++;
                finishChunk(level, task);
                return true;
            }
            if (!task.generate) {
                var existing = EXISTING.get(task.id);
                if (existing == null) {
                    var scanner = level.getChunkSource().chunkMap.chunkScanner();
                    // Use the native IOWorker so queued writes are considered, not only .mca files.
                    if (!(scanner instanceof net.minecraft.world.level.chunk.storage.IOWorker storage))
                        throw new IllegalStateException("Native storage cannot identify existing chunks");
                    existing = storage.loadAsync(new ChunkPos(task.chunkX, task.chunkZ)).thenApply(image -> image.isPresent()
                            && net.minecraft.world.level.chunk.ChunkStatus.byName(image.get().getString("Status")) == net.minecraft.world.level.chunk.ChunkStatus.FULL);
                    EXISTING.put(task.id, existing);
                }
                if (!existing.isDone()) { task.message = "Reading existing chunk from native storage."; return false; }
                if (!existing.join()) {
                    if (!excludeChunk(level, task)) return false;
                    task.skippedUnloaded++;
                    finishChunk(level, task);
                    task.message = "Chunk requires generation, explicitly disabled for this operation.";
                    return true;
                }
            }
            if (retainedChunks() >= AdapterConfig.MAX_IN_FLIGHT_CHUNKS.get() && !HELD.containsKey(task.id)) return false;
            var registration = GENERATING.get(task.id);
            if (registration == null) {
                registration = GenerationGate.register(level, task.id, task.intentHash(), new ChunkKey(task.chunkX, task.chunkZ),
                        currentVolume, task.propagation,
                        new GenerationGate.Rules(HostIntegration.supportsBulk(level), task.clearFluids, 0,
                                state -> state.is(Blocks.BEDROCK) ? task.destroyBedrock : !state.is(PROTECTED) && state.getBlock().getClass() == Block.class
                                        && state.getDestroySpeed(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO) >= 0
                                        && state.getBlock().getExplosionResistance() < task.resistance),
                        () -> task.active() && ClaimChecks.check(level, task.chunkX, task.chunkZ, task.protectEveryClaim) == ClaimChecks.Decision.ALLOW);
                GENERATING.put(task.id, registration);
            }
            if (!registration.isDone()) { task.message = "Checking native chunk storage and generation safety."; return false; }
            var admitted = registration.join();
            if (!hold(level, task)) return false;
            task.message = org.voxelregion.forge.ClearedGeneration.registered(level, task.id)
                    ? "Loading existing terrain or publishing prepared cleared chunks."
                    : "Waiting for native FULL generation/loading: " + admitted.safety().evidence();
            return false;
        }
        if (!hold(level, task)) return false;
        var generated = GENERATING.remove(task.id);
        if (generated != null) {
            var registration = generated.join();
            if (registration.result().isDone()) {
                var result = registration.result().join();
                task.generatedSkipped += result.skippedBlockWrites();
            }
            registration.close();
        }
        task.message = "";
        if (task.wholeChunk && !task.chunkStarted) {
            if (!finishNativeGeneration(level, task, chunk)) return false;
            return prepareWholeChunk(level, task, chunk, currentVolume, budget);
        }
        if (task.wholeChunk) throw new IllegalStateException("A complete-chunk commit cannot span server ticks");
        ChunkReceipts receipts = ChunkReceipts.of(chunk);
        if (task.chunkStarted) receipts.adoptLiveChanges(task.id, task.phase);
        if (!task.chunkStarted) {
            ChunkReceipts.Cursor cursor;
            var prior = receipts.checkpoint(task.id);
            if (!task.recovering && !task.resumeRequiresValidation && prior != null && prior.workingPhase() < 0 && prior.completedThroughPhase() < task.phase
                    && prior.state() == ChunkReceipts.STARTED) {
                cursor = receipts.beginLiveNextPhase(task.id, task.intentHash(), task.phase, level.getMinSection(), level.getMaxSection());
            } else if (receipts.state(task.id) != 0 || task.recovering || task.resumeRequiresValidation) {
                var recovery = receipts.resumeCursor(task.id, task.intentHash(), task.phase);
                switch (recovery.decision()) {
                    case SKIP_PHASE, STOP_TERMINAL -> { finishChunk(level, task); return true; }
                    case PAUSE_UNCERTAIN -> { pause(task, OperationData.State.PAUSED_RECOVERY, recovery.reason()); return true; }
                    case RESUME -> cursor = recovery.cursor();
                    case BEGIN_NEXT_PHASE -> cursor = receipts.beginPhase(task.id, task.intentHash(), task.phase, level.getMinSection(), level.getMaxSection());
                    default -> throw new IllegalStateException("Unknown recovery decision");
                }
            } else {
                cursor = receipts.beginPhase(task.id, task.intentHash(), task.phase, level.getMinSection(), level.getMaxSection());
            }
            task.sectionY = cursor.sectionY(); task.voxel = cursor.voxel();
            chunk.setUnsaved(true);
            task.chunkStarted = true; task.resumeRequiresValidation = false;
        }
        if (task.sectionY >= level.getMaxSection()) {
            receipts.completePhase(task.id, task.phase, task.phase + 1 == task.phaseCount());
            chunk.setUnsaved(true);
            enqueueCommit(task, chunk, true);
            finishChunk(level, task); // The submitted chunk's ticket now belongs to its fence.
            return true;
        }
        var section = chunk.getSection(level.getSectionIndexFromSectionY(task.sectionY));
        Coverage coverage = currentVolume.classify(new SectionKey(task.chunkX, task.sectionY, task.chunkZ).bounds());
        Coverage shadow = task.occlusion.classify(new SectionKey(task.chunkX, task.sectionY, task.chunkZ).bounds());
        if (shadow == Coverage.INSIDE) coverage = Coverage.OUTSIDE;
        else if (shadow == Coverage.PARTIAL && coverage != Coverage.OUTSIDE) coverage = Coverage.PARTIAL;
        if (coverage == Coverage.OUTSIDE || section.hasOnlyAir()) {
            task.sectionY++;
            task.voxel = 0;
            receipts.advanceCursor(task.id, task.phase, task.sectionY, 0);
            return true;
        }
        Explosion context = new Explosion(level, null, task.cx, task.cy, task.cz, task.radius, false, Explosion.BlockInteraction.KEEP);
        if (task.voxel == 0 && coverage == Coverage.INSIDE && task.physics == AdapterConfig.Physics.NONE
                && AdapterConfig.BULK_SECTIONS.get() && budget.writes >= 4096 && budget.visits >= 4096) {
            List<PaletteSectionClearBackend.Prepared> plans = new ArrayList<>();
            int maximum = Math.min(budget.writes / 4096, budget.visits / 4096);
            for (int sectionY = task.sectionY; sectionY < level.getMaxSection() && plans.size() < maximum; sectionY++) {
                if (System.nanoTime() >= budget.deadline || !chunkInsideBorder(level, chunk) || task.occlusion.classify(new SectionKey(task.chunkX, sectionY, task.chunkZ).bounds()) != Coverage.OUTSIDE || currentVolume.classify(new SectionKey(task.chunkX, sectionY, task.chunkZ).bounds()) != Coverage.INSIDE) break;
                BlockPos minimum = new BlockPos(task.chunkX << 4, sectionY << 4, task.chunkZ << 4);
                var preparation = PaletteSectionClearBackend.prepare(level, chunk, level.getSectionIndexFromSectionY(sectionY),
                        state -> !mustKeep(state, level, minimum, context, task.resistance, task.clearFluids, task.destroyBedrock), BULK_HOOKS);
                if (!preparation.accepted()) break;
                plans.add(preparation.prepared());
            }
            if (!plans.isEmpty()) {
                receipts.adoptLiveChanges(task.id, task.phase);
                receipts.beforeWrite(task.id, task.phase, task.sectionY, 0);
                var report = PaletteSectionClearBackend.commitBatch(plans, () -> task.active() && ClaimChecks.check(level,
                        task.chunkX, task.chunkZ, task.protectEveryClaim) == ClaimChecks.Decision.ALLOW && chunkInsideBorder(level, chunk));
                if (report.applied()) {
                    // A later FIFO barrier cannot erase an earlier batch's exceptional completion.
                    task.queuedLightWork = CompletableFuture.allOf(task.queuedLightWork, report.lightWork());
                    task.changed += report.changedBlocks(); task.bulkSections += plans.size();
                    budget.writes -= report.changedBlocks(); budget.visits -= 4096 * plans.size();
                    task.visited += 4096L * plans.size(); task.sectionY += plans.size();
                    task.needsChunkPacket |= report.needsChunkPacket();
                    receipts.afterWrite(task.id, task.phase, task.sectionY, 0);
                    return true;
                }
                receipts.abortUnappliedWrite(task.id, task.phase);
            }
        }
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        if (coverage == Coverage.PARTIAL && (task.sectionSelection == null || task.selectionSectionY != task.sectionY)) {
            var bits = new java.util.BitSet(4096);
            int baseX = task.chunkX << 4, baseY = task.sectionY << 4, baseZ = task.chunkZ << 4;
            currentVolume.emitRanges(new SectionKey(task.chunkX, task.sectionY, task.chunkZ).bounds(), (x, z, from, to) -> {
                int column = (x - baseX) | ((z - baseZ) << 4);
                for (int y = from; y < to; y++) bits.set(column | ((y - baseY) << 8));
            });
            task.occlusion.shadowMask(new SectionKey(task.chunkX, task.sectionY, task.chunkZ)).forEachRanges((x, z, from, to) -> {
                int column = x | (z << 4);
                for (int y = from; y < to; y++) bits.clear(column | (y << 8));
            });
            task.sectionSelection = bits; task.selectionSectionY = task.sectionY;
        }
        int end = Math.min(4096, task.voxel + 64);
        while (task.active() && task.voxel < end && budget.available()) {
            if (coverage == Coverage.PARTIAL) {
                int selected = task.sectionSelection.nextSetBit(task.voxel);
                if (selected < 0) selected = 4096;
                if (selected != task.voxel) {
                    task.voxel = selected;
                    receipts.advanceCursor(task.id, task.phase, task.sectionY, selected);
                }
                if (selected >= end) break;
            }
            int index = task.voxel;
            int x = (task.chunkX << 4) + (index & 15);
            int y = (task.sectionY << 4) + (index >>> 8);
            int z = (task.chunkZ << 4) + ((index >>> 4) & 15);
            position.set(x, y, z);
            budget.visits--;
            task.visited++;
            if (y >= level.getMinBuildHeight() && y < level.getMaxBuildHeight()
                    && (coverage == Coverage.INSIDE || currentVolume.contains(x, y, z))
                    && level.getWorldBorder().isWithinBounds(position)) {
                BlockState state = chunk.getBlockState(position);
                if (!state.isAir()) {
                    if (mustKeep(state, level, position, context, task.resistance, task.clearFluids, task.destroyBedrock)) {
                        task.kept++;
                    } else {
                        // Resolve potentially mod-defined fluid methods before the final
                        // permission and identity checks, so their callbacks cannot invalidate them.
                        BlockState replacement = task.clearFluids ? Blocks.AIR.defaultBlockState()
                                : state.getFluidState().createLegacyBlock();
                        // Revalidate immediately before each actual write, including after arbitrary mod callbacks.
                        ClaimChecks.Decision current = ClaimChecks.check(level, task.chunkX, task.chunkZ, task.protectEveryClaim);
                        if (current != ClaimChecks.Decision.ALLOW) {
                            if (current == ClaimChecks.Decision.DENY) {
                                skipDeniedChunk(level, task);
                            } else pause(task, OperationData.State.PAUSED_PROTECTION, "Protection changed/unavailable during commit.");
                            return true;
                        }
                        // UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE. Native chunk code maintains BE, POI,
                        // heightmaps, skylight sources, dirty state, callbacks and light checks.
                        // Neighbor physics is deliberately suppressed in this isolated baseline.
                        int flags = task.physics == AdapterConfig.Physics.COMPATIBLE ? Block.UPDATE_ALL
                                : Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
                        // Contextual mod protection callbacks can themselves replace this state.
                        // Never apply the old state's decision to a newly placed protected machine.
                        if (task.active() && chunk.getBlockState(position) == state && level.getWorldBorder().isWithinBounds(position)) {
                            receipts.adoptLiveChanges(task.id, task.phase);
                            receipts.beforeWrite(task.id, task.phase, task.sectionY, task.voxel);
                            if (org.voxelregion.forge.DropSuppression.setBlock(level, position, replacement, flags)) {
                                task.changed++;
                                if (task.physics == AdapterConfig.Physics.BOUNDARY_ONLY)
                                    notifyBoundary(level, task, position, state.getBlock());
                            }
                            budget.writes--;
                            receipts.afterWrite(task.id, task.phase, task.sectionY, task.voxel + 1);
                        } else task.kept++;
                    }
                }
            }
            task.voxel++;
            receipts.adoptLiveChanges(task.id, task.phase);
            var checkpoint = receipts.checkpoint(task.id);
            var next = new ChunkReceipts.Cursor(task.sectionY, task.voxel);
            if (!next.equals(checkpoint.cursor())) receipts.advanceCursor(task.id, task.phase, next.sectionY(), next.voxel());
        }
        if (task.voxel == 4096) { task.voxel = 0; task.sectionY++; }
        return true;
    }

    /** Prepare immutable replacement sections over bounded frames; never expose a partially cleared chunk. */
    private static boolean prepareWholeChunk(ServerLevel level, OperationData.Task task, LevelChunk chunk,
            Volume volume, Budget budget) {
        var receipts = ChunkReceipts.of(chunk);
        if (!PREPARING.containsKey(task.id) && (receipts.state(task.id) != 0 || task.recovering || task.resumeRequiresValidation)) {
            var recovery = receipts.resumeCursor(task.id, task.intentHash(), task.phase);
            switch (recovery.decision()) {
                case SKIP_PHASE, STOP_TERMINAL -> { finishChunk(level, task); return true; }
                case PAUSE_UNCERTAIN -> { pause(task, OperationData.State.PAUSED_RECOVERY, recovery.reason()); return true; }
                case RESUME -> {
                    if (recovery.cursor().sectionY() != level.getMinSection() || recovery.cursor().voxel() != 0) {
                        pause(task, OperationData.State.PAUSED_RECOVERY, "Incomplete whole-chunk commit cannot be replayed.");
                        return true;
                    }
                }
                case BEGIN_NEXT_PHASE -> { }
                default -> throw new IllegalStateException("Unknown recovery decision");
            }
        }
        ChunkPreparation plan = PREPARING.get(task.id);
        long revision = ChunkMutationTracking.revision(chunk);
        if (plan == null || plan.chunk != chunk || plan.revision != revision || !plan.borderMatches(level)
                || plan.postProcessing != DEFERRED_POST_PROCESSING.get(task.id)) {
            PREPARING.remove(task.id);
            long preparationBytes = 65_536L * chunk.getSections().length;
            if (preparationBytes + 8L * 1024 + 4096 > AdapterConfig.MAX_PENDING_BYTES.get()) {
                pause(task, OperationData.State.PAUSED, "Complete-chunk preparation needs at least "
                        + (preparationBytes + 8L * 1024 + 4096) + " metadata bytes; increase the configured limit.");
                return true;
            }
            if (estimatedMetadataBytes(level.getServer()) + OperationOcclusion.estimatedBytes()
                    + preparationBytes > AdapterConfig.MAX_PENDING_BYTES.get()) {
                task.message = "Waiting for complete-chunk preparation memory.";
                return false;
            }
            plan = new ChunkPreparation(level, chunk, revision, level.getMinSection(), DEFERRED_POST_PROCESSING.get(task.id));
            PREPARING.put(task.id, plan);
        }
        Explosion context = new Explosion(level, null, task.cx, task.cy, task.cz, task.radius, false, Explosion.BlockInteraction.KEEP);
        while (plan.nextSection < level.getMaxSection() && budget.available()) {
            int sectionY = plan.nextSection;
            var section = chunk.getSection(level.getSectionIndexFromSectionY(sectionY));
            var bounds = new SectionKey(task.chunkX, sectionY, task.chunkZ).bounds();
            Coverage coverage = volume.classify(bounds), shadow = task.occlusion.classify(bounds);
            if (coverage == Coverage.OUTSIDE || shadow == Coverage.INSIDE || section.hasOnlyAir()) {
                plan.nextSection++; budget.visits--; continue;
            }
            java.util.BitSet selection = null;
            if (coverage != Coverage.INSIDE || shadow != Coverage.OUTSIDE || !chunkInsideBorder(level, chunk)) {
                selection = new java.util.BitSet(4096);
                final var selected = selection;
                int baseX = bounds.minX(), baseY = bounds.minY(), baseZ = bounds.minZ();
                var p = new BlockPos.MutableBlockPos();
                volume.emitRanges(bounds, (x, z, from, to) -> {
                    int column = (x - baseX) | ((z - baseZ) << 4);
                    for (int y = from; y < to; y++) if (level.getWorldBorder().isWithinBounds(p.set(x, y, z)))
                        selected.set(column | ((y - baseY) << 8));
                });
                task.occlusion.shadowMask(new SectionKey(task.chunkX, sectionY, task.chunkZ)).forEachRanges((x, z, from, to) -> {
                    int column = x | (z << 4);
                    for (int y = from; y < to; y++) selected.clear(column | (y << 8));
                });
                if (selection.isEmpty()) { plan.nextSection++; budget.visits--; continue; }
            }
            int selectedCount = selection == null ? 4096 : selection.cardinality();
            if (task.physics == AdapterConfig.Physics.NONE && AdapterConfig.BULK_SECTIONS.get()) {
                var minimum = new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ());
                var prepared = PaletteSectionClearBackend.prepareSelected(level, chunk, level.getSectionIndexFromSectionY(sectionY),
                        selection, state -> !mustKeep(state, level, minimum, context, task.resistance, task.clearFluids, task.destroyBedrock),
                        BULK_HOOKS, plan.postProcessing);
                if (!prepared.accepted()) {
                    PREPARING.remove(task.id);
                    if (plan.postProcessing != null) {
                        requireNativePostProcessing(level, task);
                        return false;
                    }
                    if (prepared.rejection() == PaletteSectionClearBackend.Rejection.STALE) return true;
                    pause(task, OperationData.State.PAUSED, "Whole-chunk preparation unavailable: " + prepared.rejection());
                    return true;
                }
                var preparedSection = prepared.prepared();
                plan.sections.add(preparedSection);
                if (preparedSection.nativeRemainderCount() != 0)
                    plan.nativeSections.add(new NativeSection(sectionY, preparedSection.nativeRemainder()));
                plan.changedBlocks += preparedSection.changedBlocks();
                plan.keptBlocks += preparedSection.keptBlocks();
                budget.visits -= preparedSection.planningWork();
            } else {
                if (selection == null) { selection = new java.util.BitSet(4096); selection.set(0, 4096); }
                plan.nativeSections.add(new NativeSection(sectionY, selection));
                budget.visits -= selectedCount;
            }
            if (ChunkMutationTracking.revision(chunk) != plan.revision) { PREPARING.remove(task.id); return true; }
            plan.visited += selectedCount;
            plan.nextSection++;
        }
        task.message = "Preparing complete chunk " + task.chunkX + "," + task.chunkZ + ": "
                + (plan.nextSection - level.getMinSection()) + "/" + chunk.getSections().length + " sections; terrain unchanged.";
        if (plan.nextSection < level.getMaxSection() || !budget.available()) return true;
        if (plan.postProcessing != null && (!plan.nativeSections.isEmpty() || !plan.postProcessing.plansEraseAll(plan.sections))) {
            requireNativePostProcessing(level, task);
            return false;
        }
        if (plan.changedBlocks > 0 && RuntimeBridge.availableMetadataBytes(level.getServer())
                < 65_536L + level.getHeight() * 1792L) {
            task.message = "Waiting for batch-light memory before changing terrain.";
            return false;
        }
        if (ClaimChecks.check(level, task.chunkX, task.chunkZ, task.protectEveryClaim) != ClaimChecks.Decision.ALLOW) {
            PREPARING.remove(task.id);
            return true; // The next step applies the ordinary protection transition.
        }
        if (ChunkMutationTracking.revision(chunk) != plan.revision || !plan.borderMatches(level) || !PaletteSectionClearBackend.validateBatch(plan.sections)) {
            PREPARING.remove(task.id); return true;
        }
        // One synchronous chunk commit. A native callback remains indivisible, but time slicing
        // happens before/after this transaction, not by exposing successive terrain voxels.
        receipts.beginPhase(task.id, task.intentHash(), task.phase, level.getMinSection(), level.getMaxSection());
        task.chunkStarted = true;
        receipts.beforeWrite(task.id, task.phase, level.getMinSection(), 0);
        task.authoritativeLightThrough = level.getGameTime() + 40;
        long started = System.nanoTime();
        final ChunkPreparation committedPlan = plan;
        // The palette transaction precedes native removals. Evaluate solved lighting only
        // after both have finished, against the actual final chunk rather than old remainders.
        CompletableFuture<Boolean> mutationsFinished = new CompletableFuture<>();
        if (!plan.sections.isEmpty()) {
            var report = PaletteSectionClearBackend.commitBatch(plan.sections,
                    () -> task.active() && committedPlan.borderMatches(level)
                            && (committedPlan.postProcessing == null || committedPlan.postProcessing.matchesMetadata())
                            && ClaimChecks.check(level, task.chunkX, task.chunkZ, task.protectEveryClaim) == ClaimChecks.Decision.ALLOW,
                    (lightLevel, lightChunk, lightPlans, nativeFallback) -> mutationsFinished.thenCompose(complete ->
                            complete ? org.voxelregion.forge.ClearedChunkLightBatch.afterWholeChunkMutation(
                                    lightLevel, lightChunk, nativeFallback) : nativeFallback.get()));
            if (!report.applied()) {
                receipts.abortUnappliedWrite(task.id, task.phase);
                task.chunkStarted = false; PREPARING.remove(task.id);
                task.message = "Chunk plan invalidated before commit: " + report.result();
                return true;
            }
            task.queuedLightWork = CompletableFuture.allOf(task.queuedLightWork, report.lightWork());
            task.changed += report.changedBlocks(); budget.writes -= plan.sections.size();
            task.bulkSections += plan.sections.stream().filter(s -> s.changedBlocks() != 0).count();
            task.needsChunkPacket |= report.needsChunkPacket();
        }
        boolean mutationsComplete = false;
        try {
            var position = new BlockPos.MutableBlockPos();
            for (var nativeSection : plan.nativeSections) {
                for (int index = nativeSection.selection.nextSetBit(0); index >= 0; index = nativeSection.selection.nextSetBit(index + 1)) {
                    position.set((task.chunkX << 4) + (index & 15), (nativeSection.sectionY << 4) + (index >>> 8),
                            (task.chunkZ << 4) + ((index >>> 4) & 15));
                    BlockState state = chunk.getBlockState(position);
                    if (state.isAir()) continue;
                    if (mustKeep(state, level, position, context, task.resistance, task.clearFluids, task.destroyBedrock)) { task.kept++; continue; }
                    BlockState replacement = task.clearFluids ? Blocks.AIR.defaultBlockState() : state.getFluidState().createLegacyBlock();
                    ClaimChecks.Decision permission = ClaimChecks.check(level, task.chunkX, task.chunkZ, task.protectEveryClaim);
                    if (permission != ClaimChecks.Decision.ALLOW) {
                        receipts.afterWrite(task.id, task.phase, level.getMaxSection(), 0);
                        task.needsChunkPacket = true;
                        pause(task, OperationData.State.PAUSED_PROTECTION, "Protection changed during native removal; prior changes retained. Partial chunk requires recovery inspection before replay.");
                        return true;
                    }
                    if (!task.active() || chunk.getBlockState(position) != state || !level.getWorldBorder().isWithinBounds(position)) continue;
                    int flags = task.physics == AdapterConfig.Physics.COMPATIBLE ? Block.UPDATE_ALL : Block.UPDATE_KNOWN_SHAPE;
                    if (org.voxelregion.forge.DropSuppression.setBlock(level, position, replacement, flags)) {
                        task.changed++;
                        if (task.physics == AdapterConfig.Physics.BOUNDARY_ONLY) notifyBoundary(level, task, position, state.getBlock());
                    }
                    budget.writes--;
                }
            }
            clearFallingTerrain(level, task, chunk, volume, context);
            // Entity-removal observers may cancel after publication. Their cancellation already
            // owns settlement; preserve it and leave pending native work intact.
            if (plan.postProcessing != null && !task.active()) return true;
            if (plan.postProcessing != null && !plan.postProcessing.consumeAfterCommit(() -> task.active()
                    && committedPlan.borderMatches(level)
                    && ClaimChecks.check(level, task.chunkX, task.chunkZ, task.protectEveryClaim) == ClaimChecks.Decision.ALLOW)) {
                // A watcher can introduce new work after palette publication. Preserve it and the
                // dirty receipt: this is a post-write recovery stop, never an unapplied retry.
                receipts.afterWrite(task.id, task.phase, level.getMaxSection(), 0);
                task.needsChunkPacket = true;
                chunk.setUnsaved(true);
                pause(task, OperationData.State.PAUSED_RECOVERY,
                        "Post-processing changed after terrain commit; pending native work retained. Partial chunk requires recovery inspection before replay.");
                return true;
            }
            mutationsComplete = true;
        } finally {
            // Protection changes and throwing native callbacks must release the pending
            // light/save/packet dependency through genuine native repair, never hang it.
            mutationsFinished.complete(mutationsComplete);
        }
        task.visited += plan.visited;
        task.kept += plan.keptBlocks;
        receipts.afterWrite(task.id, task.phase, level.getMaxSection(), 0);
        receipts.completePhase(task.id, task.phase, true);
        task.needsChunkPacket = true;
        chunk.setUnsaved(true);
        task.chunkCommitNanos += System.nanoTime() - started;
        task.committedChunks++;
        task.message = "Complete chunk committed; lighting, save and packet acknowledgements pending.";
        enqueueCommit(task, chunk, true);
        PREPARING.remove(task.id);
        finishChunk(level, task);
        return true;
    }

    private static boolean mustKeep(BlockState state, ServerLevel level, BlockPos position, Explosion context, double limit,
            boolean clearFluids, boolean destroyBedrock) {
        if (state.is(Blocks.BEDROCK)) return !destroyBedrock;
        if (state.is(PROTECTED)) return true;
        // A region clear includes free liquids. Their ray-explosion hardness/resistance
        // is not a preservation rule. Explicit block tags and chunk claims still win;
        // waterlogged solids do not enter this branch and retain their host protection.
        if (state.getBlock() instanceof LiquidBlock && !state.getFluidState().isEmpty()) return !clearFluids;
        float hardness = state.getDestroySpeed(level, position);
        if (!Float.isFinite(hardness) || hardness < 0) return true;
        float resistance = state.getExplosionResistance(level, position, context);
        return !Float.isFinite(resistance) || resistance < 0 || resistance >= limit;
    }

    /** Falling terrain is no longer in a section palette and otherwise lands after the clear. */
    private static void clearFallingTerrain(ServerLevel level, OperationData.Task task, LevelChunk chunk,
            Volume volume, Explosion context) {
        if (task.physics != AdapterConfig.Physics.NONE) return;
        var pos = chunk.getPos();
        var bounds = new AABB(pos.getMinBlockX(), level.getMinBuildHeight(), pos.getMinBlockZ(),
                pos.getMaxBlockX() + 1, level.getMaxBuildHeight(), pos.getMaxBlockZ() + 1);
        for (var falling : level.getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class, bounds)) {
            // Do not reinterpret a mod's custom entity lifecycle or remove incoming
            // terrain whose source lies outside this operation's selected volume.
            if (falling.getClass() != net.minecraft.world.entity.item.FallingBlockEntity.class || falling.isRemoved()) continue;
            var at = falling.blockPosition(); var origin = falling.getStartPos();
            if ((at.getX() >> 4) != pos.x || (at.getZ() >> 4) != pos.z
                    || !volume.contains(at.getX(), at.getY(), at.getZ())
                    || !volume.contains(origin.getX(), origin.getY(), origin.getZ())
                    || task.occlusion.classify(new org.voxelregion.geometry.IntBox(at.getX(), at.getY(), at.getZ(),
                            at.getX() + 1, at.getY() + 1, at.getZ() + 1)) != Coverage.OUTSIDE
                    || task.occlusion.classify(new org.voxelregion.geometry.IntBox(origin.getX(), origin.getY(), origin.getZ(),
                            origin.getX() + 1, origin.getY() + 1, origin.getZ() + 1)) != Coverage.OUTSIDE
                    || !level.getWorldBorder().isWithinBounds(at)
                    || !level.getWorldBorder().isWithinBounds(origin)) continue;
            if (mustKeep(falling.getBlockState(), level, origin, context, task.resistance, task.clearFluids, task.destroyBedrock)) continue;
            if (task.active() && ClaimChecks.check(level, pos.x, pos.z, task.protectEveryClaim) == ClaimChecks.Decision.ALLOW
                    && ClaimChecks.check(level, origin.getX() >> 4, origin.getZ() >> 4, task.protectEveryClaim) == ClaimChecks.Decision.ALLOW)
                falling.discard();
        }
    }

    private static void notifyBoundary(ServerLevel level, OperationData.Task task, BlockPos source, Block oldBlock) {
        for (var direction : net.minecraft.core.Direction.values()) {
            BlockPos neighbor = source.relative(direction);
            if (!level.hasChunkAt(neighbor)) continue;
            BlockState retained = level.getBlockState(neighbor);
            if (retained.isAir()) continue;
            // Internal ordinary terrain will be removed by a later range. External terrain,
            // fluids and special retained blocks receive a bounded direct notification.
            if (task.volume.contains(neighbor.getX(), neighbor.getY(), neighbor.getZ())
                    && retained.getBlock().getClass() == Block.class && !retained.is(PROTECTED)) continue;
            if (ClaimChecks.check(level, neighbor.getX() >> 4, neighbor.getZ() >> 4, task.protectEveryClaim) == ClaimChecks.Decision.ALLOW)
                level.neighborChanged(neighbor, oldBlock, source);
        }
    }

    private static boolean entityStep(ServerLevel level, OperationData.Task task, Budget budget) {
        if (task.entityDamage <= 0) { task.entitiesHandled = true; return true; }
        if (task.recovering) {
            pause(task, OperationData.State.PAUSED_RECOVERY, "Entity damage cannot be replayed without a co-saved entity receipt.");
            return true;
        }
        EntityWork work = ENTITIES.get(task.id);
        if (work == null) {
            List<Entity> candidates = new ArrayList<>();
            level.getEntities().get(net.minecraft.world.level.entity.EntityTypeTest.forClass(Entity.class),
                    new AABB(task.cx - task.radius, task.cy - task.radius, task.cz - task.radius,
                            task.cx + task.radius, task.cy + task.radius, task.cz + task.radius), entity -> {
                        if (!entity.isSpectator()) candidates.add(entity);
                        return candidates.size() > MAX_ENTITY_CANDIDATES
                                ? net.minecraft.util.AbortableIterationConsumer.Continuation.ABORT
                                : net.minecraft.util.AbortableIterationConsumer.Continuation.CONTINUE;
                    });
            if (candidates.size() > MAX_ENTITY_CANDIDATES) {
                pause(task, OperationData.State.PAUSED, "Entity candidate quota exceeded before applying any entity effects.");
                return true;
            }
            work = new EntityWork(candidates);
            ENTITIES.put(task.id, work);
        }
        if (work.index == work.entities.size()) {
            ENTITIES.remove(task.id);
            task.entitiesHandled = true;
            return true;
        }
        Entity entity = work.entities.get(work.index);
        ClaimChecks.Decision claim = ClaimChecks.check(level, entity.chunkPosition().x, entity.chunkPosition().z, true);
        if (claim == ClaimChecks.Decision.UNAVAILABLE) {
            pause(task, OperationData.State.PAUSED_PROTECTION, "Entity claim protection unavailable.");
            return true;
        }
        work.index++;
        budget.visits--;
        if (claim == ClaimChecks.Decision.ALLOW && entity.isAlive() && entity.level() == level) {
            double distance = Math.sqrt(entity.distanceToSqr(task.cx, task.cy, task.cz));
            if (distance <= task.radius && (task.geometry == null || task.geometry.containsPoint(entity.getX(), entity.getY(), entity.getZ()))) {
                float damage = (float) (task.entityDamage * (1.0 - distance / task.radius));
                if (damage > 0) {
                    Explosion explosion = new Explosion(level, level.getPlayerByUUID(task.actor), task.cx, task.cy, task.cz,
                            task.radius, false, Explosion.BlockInteraction.KEEP);
                    entity.hurt(level.damageSources().explosion(explosion), damage);
                }
            }
        }
        return true;
    }

    private static boolean hold(ServerLevel level, OperationData.Task task) {
        if (HELD.containsKey(task.id)) return true;
        if (retainedChunks() >= AdapterConfig.MAX_IN_FLIGHT_CHUNKS.get()) return false;
        ChunkPos position = new ChunkPos(task.chunkX, task.chunkZ);
        var reservations = RESERVED.computeIfAbsent(level, ignored -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet());
        if (reservations.contains(position.toLong())) return false;
        int distance = ChunkReadAhead.ticketDistance(task.id, position);
        level.getChunkSource().addRegionTicket(TICKET, position, distance, task.id);
        HELD.put(task.id, new HeldChunk(level, position, distance));
        reservations.add(position.toLong());
        ChunkReadAhead.adopted(task.id, position);
        return true;
    }

    /** FULL accessibility precedes native post-processing. Temporarily request the real ticking lifecycle. */
    private static boolean finishNativeGeneration(ServerLevel level, OperationData.Task task, LevelChunk chunk) {
        boolean pending = ChunkReadAhead.pendingPostProcessing(chunk);
        HeldChunk held = HELD.get(task.id);
        if (pending) {
            // Never replace/cancel native lifecycle futures. Only a distance-zero lease can
            // defer provably erased work; a previously requested native lifecycle runs normally.
            if (held.ticketDistance == 0) {
                var proof = DEFERRED_POST_PROCESSING.get(task.id);
                if (proof == null || !proof.currentRevision(level, chunk)) {
                    proof = postProcessingClearance(level, task, chunk);
                    if (proof != null) DEFERRED_POST_PROCESSING.put(task.id, proof);
                }
                if (proof != null) return true;
            }
            requireNativePostProcessing(level, task);
            return false;
        }
        DEFERRED_POST_PROCESSING.remove(task.id);
        if (held.ticketDistance != 0) {
            if (!((org.voxelregion.forge.GenerationBridge) level.getChunkSource().chunkMap).vre$postProcessingReady(chunk)) {
                task.message = "Waiting for native generation post-processing completion.";
                return false;
            }
            level.getChunkSource().addRegionTicket(TICKET, held.position, 0, task.id);
            level.getChunkSource().removeRegionTicket(TICKET, held.position, held.ticketDistance, task.id);
            HELD.put(task.id, new HeldChunk(level, held.position, 0));
        }
        return true;
    }

    /** Bounded pure qualification, also used speculatively by read-ahead; submission always rechecks. */
    static org.voxelregion.forge.PostProcessingClearance postProcessingClearance(ServerLevel level,
            OperationData.Task task, LevelChunk chunk) {
        // Keep native neighbor preparation batched for disk-loading/generating requests.
        // Full R1024 measurements found that speculative deferral reduced post-list work
        // but delayed upstream readiness and did not improve end-to-end completion.
        // A loaded-only request can still erase its proven pending work without requiring
        // the native 3x3 promotion of additional terrain.
        if (!task.active() || task.generate || !task.wholeChunk || task.physics != AdapterConfig.Physics.NONE || !task.clearFluids
                || task.propagation != FrozenOcclusion.Mode.NONE || !AdapterConfig.BULK_SECTIONS.get()
                || !BULK_HOOKS.supportsBulk(level)
                || ClaimChecks.check(level, chunk.getPos().x, chunk.getPos().z, task.protectEveryClaim) != ClaimChecks.Decision.ALLOW)
            return null;
        Explosion context = new Explosion(level, null, task.cx, task.cy, task.cz, task.radius, false, Explosion.BlockInteraction.KEEP);
        Volume selected = task.phaseVolume(level.getMinBuildHeight(), level.getMaxBuildHeight());
        return org.voxelregion.forge.PostProcessingClearance.capture(level, chunk, (pos, state) ->
                selected.contains(pos.getX(), pos.getY(), pos.getZ()) && level.getWorldBorder().isWithinBounds(pos)
                        && !mustKeep(state, level, pos, context, task.resistance, task.clearFluids, task.destroyBedrock));
    }

    private static void requireNativePostProcessing(ServerLevel level, OperationData.Task task) {
        PREPARING.remove(task.id);
        DEFERRED_POST_PROCESSING.remove(task.id);
        HeldChunk held = HELD.get(task.id);
        if (held.ticketDistance == 0) {
            // The real BLOCK_TICKING future owns fluid/shape/BE/upgrade work and 3x3 prerequisites.
            level.getChunkSource().addRegionTicket(TICKET, held.position, 1, task.id);
            level.getChunkSource().removeRegionTicket(TICKET, held.position, 0, task.id);
            HELD.put(task.id, new HeldChunk(level, held.position, 1));
        }
        task.message = "Waiting for native generation post-processing before preparing the complete chunk.";
    }

    private static void finishChunk(ServerLevel level, OperationData.Task task) {
        PREPARING.remove(task.id);
        releaseTicket(task.id);
        task.chunkStarted = false;
        task.chunkSelected = false;
        task.sectionSelection = null;
        task.selectionSectionY = Integer.MIN_VALUE;
        task.voxel = 0;
        task.sectionY = level.getMinSection();
    }

    static void pause(OperationData.Task task, OperationData.State state, String message) {
        task.state = state;
        task.message = message;
        task.resumeRequiresValidation |= task.chunkStarted;
        task.chunkStarted = false; // Resume must validate its co-saved receipt, not adopt intervening edits.
        checkpointAndSettle(task);
    }

    static boolean resume(OperationData.Task task) {
        if (task.terminal() || task.active() || task.state == OperationData.State.PAUSED_RECOVERY || task.unconfirmedFences != 0) return false;
        task.state = task.fuse > 0 ? OperationData.State.FUSE : OperationData.State.RUNNING;
        task.message = "";
        try { if (task.owner != null) IntentJournal.checkpoint(task.owner, task); }
        catch (RuntimeException failure) {
            task.state = OperationData.State.FAILED;
            task.message = "Resume control journal failed; no new writes are permitted.";
            if (task.owner != null) OperationData.get(task.owner).setDirty();
            throw failure;
        }
        return true;
    }

    static void cancel(OperationData.Task task) {
        task.state = OperationData.State.CANCELLED;
        task.message = "Cancelled; previous writes are retained.";
        try { checkpointAndSettle(task); }
        finally { ENTITIES.remove(task.id); OperationOcclusion.release(task.id); }
    }

    private static void checkpointAndSettle(OperationData.Task task) {
        ChunkReadAhead.release(task.id);
        org.voxelregion.forge.ClearedGeneration.release(task.id);
        if (task.owner != null) OperationData.get(task.owner).setDirty();
        RuntimeException checkpointFailure = null;
        try { if (task.owner != null) IntentJournal.checkpoint(task.owner, task); }
        catch (RuntimeException failure) {
            checkpointFailure = failure;
            task.state = OperationData.State.FAILED;
            task.message = "Control journal failed; submitted writes still require completion: " + failure.getMessage();
        }
        try { settlePartial(task); }
        catch (RuntimeException cleanupFailure) {
            if (checkpointFailure == null) throw cleanupFailure;
            checkpointFailure.addSuppressed(cleanupFailure);
        }
        if (checkpointFailure != null) throw checkpointFailure;
    }

    private static void settlePartial(OperationData.Task task) {
        PREPARING.remove(task.id);
        DEFERRED_POST_PROCESSING.remove(task.id);
        HeldChunk held = HELD.get(task.id);
        if (held == null) { releaseTicket(task.id); return; }
        LevelChunk chunk = held.level.getChunkSource().getChunkNow(held.position.x, held.position.z);
        if (chunk == null) { releaseTicket(task.id); return; }
        if (task.state == OperationData.State.CANCELLED && ChunkReceipts.of(chunk).state(task.id) != 0)
            ChunkReceipts.of(chunk).cancel(task.id);
        enqueueCommit(task, chunk, false);
    }

    /** Transfer one current ticket to a bounded completion queue before advancing any cursor. */
    private static void enqueueCommit(OperationData.Task task, LevelChunk chunk, boolean countAck) {
        HeldChunk held = HELD.get(task.id);
        if (held == null || !held.position.equals(chunk.getPos())) throw new IllegalStateException("Completion lost its current chunk reservation");
        CompletableFuture<Void> saved;
        NativeCommitFences.SaveFence nativeSave = null;
        try { nativeSave = NativeCommitFences.enqueueSave(held.level, chunk, task.queuedLightWork); saved = nativeSave.saved(); }
        catch (RuntimeException failure) { saved = CompletableFuture.failedFuture(failure); }
        CompletableFuture<Void> packet = CompletableFuture.completedFuture(null);
        if (task.needsChunkPacket) {
            try { packet = ChunkPacketQueue.enqueue(held.level, chunk, task.queuedLightWork); }
            catch (RuntimeException failure) { packet = CompletableFuture.failedFuture(failure); }
        }
        var future = CompletableFuture.allOf(saved, packet, task.queuedLightWork);
        var queue = IN_FLIGHT.computeIfAbsent(task.id, ignored -> new java.util.ArrayDeque<>());
        queue.addLast(new PendingCommit(held, task.phase, task.queuedLightWork, saved, packet, nativeSave, future, countAck));
        task.unconfirmedFences++;
        HELD.remove(task.id);
        task.needsChunkPacket = false;
        task.queuedLightWork = CompletableFuture.completedFuture(null); // Ownership moved into the queued future.
    }

    private static void drainCommits(ServerLevel level, OperationData data, OperationData.Task task) {
        var queue = IN_FLIGHT.get(task.id);
        if (queue == null) return;
        boolean changed = false, failed = false;
        var iterator = queue.iterator();
        while (iterator.hasNext()) {
            PendingCommit commit = iterator.next();
            if (!commit.future.isDone()) {
                if (task.state != OperationData.State.FAILED && (commit.save.isCompletedExceptionally()
                        || commit.packet.isCompletedExceptionally() || commit.light.isCompletedExceptionally()
                        || commit.nativeSave != null && commit.nativeSave.lighting().isCompletedExceptionally())) {
                    failed = true; changed = true;
                    task.state = OperationData.State.FAILED;
                    task.message = "A native completion stage failed; new writes stopped while other submitted stages drain.";
                    RuntimeBridge.LOGGER.error("Region operation {} has a failed completion stage at {}; retaining its ticket until remaining stages terminate",
                            task.id, commit.held.position);
                }
                continue;
            }
            iterator.remove(); changed = true;
            try {
                commit.future.join();
                if (commit.countAck) { task.finishedChunks++; completedThisFrame++; }
            } catch (RuntimeException failure) {
                failed = true;
                task.state = OperationData.State.FAILED;
                task.message = "Native completion failed; no automatic retry: " + failure.getMessage();
                RuntimeBridge.LOGGER.error("Region operation {} failed while settling chunk {}", task.id, commit.held.position, failure);
            } finally {
                task.authoritativeLightThrough = level.getGameTime() + 40;
                commit.held.level.getChunkSource().removeRegionTicket(TICKET, commit.held.position, commit.held.ticketDistance, task.id);
                unreserve(commit.held);
                task.unconfirmedFences--;
            }
        }
        if (!changed) return;
        if (queue.isEmpty()) IN_FLIGHT.remove(task.id);
        if (failed) {
            OperationOcclusion.release(task.id); ENTITIES.remove(task.id);
            task.needsChunkPacket = true;
            settlePartial(task); // Already submitted writes in a current chunk also have to settle.
        }
        data.setDirty();
        if (!task.active() && (failed || task.unconfirmedFences == 0)) {
            pruneTerminalMemory(task);
            try { IntentJournal.checkpoint(level, task); }
            catch (RuntimeException failure) {
                task.state = OperationData.State.FAILED;
                task.message = "Completion control checkpoint failed: " + failure.getMessage();
                RuntimeBridge.LOGGER.error("Region completion checkpoint failed for {}", task.id, failure);
            }
        }
    }

    static int retainedChunks() {
        int result = HELD.size() + ChunkReadAhead.retained();
        for (var queue : IN_FLIGHT.values()) result += queue.size();
        return result;
    }
    static long estimatedMetadataBytes(MinecraftServer server) {
        long bytes = 8L * 1024 + ChunkReadAhead.estimatedBytes()
                + org.voxelregion.forge.AirRegionLightBatch.estimatedBytes()
                + org.voxelregion.forge.ParallelChunkCompression.estimatedBytes()
                + org.voxelregion.forge.ClearedGeneration.estimatedPendingBytes()
                + org.voxelregion.forge.ClearedChunkLightBatch.estimatedPendingBytes();
        for (var proof : DEFERRED_POST_PROCESSING.values()) bytes += proof.estimatedBytes();
        for (var level : server.getAllLevels()) for (var task : OperationData.get(level).tasks) {
            bytes += 4096L + 24L * task.excludedChunks.size(); // Cursor, current masks and bounded per-task bookkeeping.
            if (task.centerCursor != null) bytes += task.centerCursor.estimatedBytes();
            var preparation = PREPARING.get(task.id);
            if (preparation != null) bytes += 65_536L * preparation.chunk.getSections().length;
            if (!task.terminal() && task.entityDamage > 0 && !task.entitiesHandled)
                bytes += 1024L + 16L * (MAX_ENTITY_CANDIDATES + 1);
        }
        return bytes;
    }
    private static boolean excludeChunk(ServerLevel level, OperationData.Task task) {
        long key = ChunkPos.asLong(task.chunkX, task.chunkZ);
        if (task.excludedChunks.contains(key)) return true;
        if (task.excludedChunks.size() >= 2_000_000) throw new IllegalStateException("Excluded chunk metadata limit reached");
        if (estimatedMetadataBytes(level.getServer()) + OperationOcclusion.estimatedBytes() + 24
                > AdapterConfig.MAX_PENDING_BYTES.get()) {
            pause(task, OperationData.State.PAUSED, "Global metadata quota reached before extending loaded-only/permission exclusions.");
            return false;
        }
        task.excludedChunks.add(key);
        return true;
    }
    private static boolean pruneTerminalMemory(OperationData.Task task) {
        if (!task.terminal() || IN_FLIGHT.containsKey(task.id) || HELD.containsKey(task.id)
                || GENERATING.containsKey(task.id) || task.excludedChunks.isEmpty()) return false;
        task.excludedChunks.clear();
        task.excludedChunks.trim();
        return true;
    }

    private static boolean chunkInsideBorder(ServerLevel level, LevelChunk chunk) {
        var border = level.getWorldBorder();
        return border.getLerpRemainingTime() == 0 && chunk.getPos().getMinBlockX() >= border.getMinX()
                && chunk.getPos().getMinBlockX() + 16 <= border.getMaxX()
                && chunk.getPos().getMinBlockZ() >= border.getMinZ() && chunk.getPos().getMinBlockZ() + 16 <= border.getMaxZ();
    }

    private static void skipDeniedChunk(ServerLevel level, OperationData.Task task) {
        if (!excludeChunk(level, task)) return;
        task.skippedChunks++;
        var chunk = level.getChunkSource().getChunkNow(task.chunkX, task.chunkZ);
        if (chunk != null && HELD.containsKey(task.id)) {
            var receipts = ChunkReceipts.of(chunk);
            if (receipts.state(task.id) != 0) receipts.skipPermission(task.id);
            settlePartial(task);
            finishChunk(level, task);
            task.message = "Permission changed; prior writes are settling while other allowed chunks continue.";
        } else finishChunk(level, task);
    }

    static Component status(OperationData.Task task) {
        var stages = progress(task);
        String chunkPosition = task.chunkSelected ? task.chunkX + "," + task.chunkZ
                : task.state == OperationData.State.FUSE ? "center(" + Math.floorDiv((int)Math.floor(task.cx), 16) + "," + Math.floorDiv((int)Math.floor(task.cz), 16) + ")" : "none";
        return Component.literal(task.id + " " + task.state + " radius=" + task.radius + " chunk=" + chunkPosition
                + " clearFluids=" + task.clearFluids + " physics=" + task.physics + " propagation=" + task.propagation + " visited=" + task.visited + " changed=" + task.changed + " kept=" + task.kept
                + (task.wholeChunk ? " chunkOrder=" + task.progression : " phase=" + Math.min(task.phase + 1, task.phaseCount()) + "/" + task.phaseCount())
                + " bulkSections=" + task.bulkSections + " committedChunks=" + task.committedChunks
                + " inFlight=" + task.unconfirmedFences + " chunks=" + task.finishedChunks + " denied=" + task.skippedChunks + " unloaded=" + task.skippedUnloaded + " generationWritesSkipped=" + task.generatedSkipped
                + " stage=" + stages.preparationStage() + " writing=" + stages.writingChunks() + " loading=" + stages.loadingChunks()
                + " lighting=" + stages.pendingLighting() + " saving=" + stages.pendingSaves() + " packets=" + stages.pendingPackets()
                + (task.message.isEmpty() ? "" : " | " + task.message));
    }

    static org.voxelregion.forge.ForgeRegionEngine.Progress progress(OperationData.Task task) {
        var held = HELD.get(task.id);
        int loading = GENERATING.containsKey(task.id) || EXISTING.containsKey(task.id) || held != null
                && (held.ticketDistance != 0 || held.level.getChunkSource().getChunkNow(held.position.x, held.position.z) == null) ? 1 : 0;
        int writing = task.active() && task.chunkStarted && held != null && loading == 0 ? 1 : 0;
        int lighting = 0, saves = 0, packets = 0, retained = held == null ? 0 : 1;
        var queue = IN_FLIGHT.get(task.id);
        if (queue != null) for (var commit : queue) {
            retained++;
            if (!commit.light.isDone() || commit.nativeSave != null && !commit.nativeSave.lighting().isDone()) lighting++;
            if (!commit.save.isDone()) saves++;
            if (!commit.packet.isDone()) packets++;
        }
        String stage = task.state == OperationData.State.FUSE ? "FUSE" : task.propagation == FrozenOcclusion.Mode.NONE
                ? "READY" : OperationOcclusion.stage(task.id);
        if (PREPARING.containsKey(task.id)) stage = "PREPARING_CHUNK";
        else if (held != null && held.ticketDistance != 0) stage = "POST_PROCESSING_CHUNK";
        else if (loading != 0) stage = "LOADING_CHUNK";
        else if (task.state == OperationData.State.SETTLING) stage = "SETTLING";
        else if (task.terminal()) stage = task.state.name();
        if (task.recovering && !task.terminal()) stage = "RECOVERY_" + stage;
        return new org.voxelregion.forge.ForgeRegionEngine.Progress(stage, writing, loading + ChunkReadAhead.loading(task.id),
                lighting, saves, packets, retained + ChunkReadAhead.retained(task.id));
    }

    private static void releaseTicket(UUID id) {
        PREPARING.remove(id);
        DEFERRED_POST_PROCESSING.remove(id);
        EXISTING.remove(id);
        var generation = GENERATING.remove(id);
        if (generation != null) generation.thenAccept(GenerationGate.Registration::close);
        HeldChunk held = HELD.remove(id);
        if (held != null) {
            held.level.getChunkSource().removeRegionTicket(TICKET, held.position, held.ticketDistance, id);
            unreserve(held);
        }
    }
    private static void unreserve(HeldChunk held) {
        var reservations = RESERVED.get(held.level);
        if (reservations != null) {
            reservations.remove(held.position.toLong());
            if (reservations.isEmpty()) RESERVED.remove(held.level);
        }
    }
    private static void release(UUID id) { ChunkReadAhead.release(id); org.voxelregion.forge.ClearedGeneration.release(id); OperationOcclusion.release(id); releaseTicket(id); ENTITIES.remove(id); }
    static void stop() {
        completionWindow.reset(); admissionWindow = 128; completedThisFrame = 0;
        ChunkReadAhead.stop();
        org.voxelregion.forge.ClearedGeneration.stop();
        java.util.Set<UUID> ids = new java.util.HashSet<>(HELD.keySet()); ids.addAll(GENERATING.keySet());
        for (UUID id : ids) release(id);
        // Stop is not a durable acknowledgement: retain each task's nonzero persisted uncertainty marker.
        for (var entry : IN_FLIGHT.entrySet()) for (var commit : entry.getValue())
            commit.held.level.getChunkSource().removeRegionTicket(TICKET, commit.held.position, commit.held.ticketDistance, entry.getKey());
        IN_FLIGHT.clear(); RESERVED.clear(); PREPARING.clear(); DEFERRED_POST_PROCESSING.clear(); EXISTING.clear();
        ENTITIES.clear(); OperationOcclusion.stop(); roundRobin = 0;
    }
    private record Work(ServerLevel level, OperationData data, OperationData.Task task) {}
    private record NativeSection(int sectionY, java.util.BitSet selection) {}
    private static final class ChunkPreparation {
        final LevelChunk chunk;
        final long revision;
        final org.voxelregion.forge.PostProcessingClearance postProcessing;
        final List<PaletteSectionClearBackend.Prepared> sections = new ArrayList<>();
        final List<NativeSection> nativeSections = new ArrayList<>();
        int nextSection, changedBlocks, keptBlocks;
        long visited;
        final double borderMinX, borderMaxX, borderMinZ, borderMaxZ;
        ChunkPreparation(ServerLevel level, LevelChunk chunk, long revision, int nextSection,
                org.voxelregion.forge.PostProcessingClearance postProcessing) {
            this.chunk = chunk; this.revision = revision; this.nextSection = nextSection;
            this.postProcessing = postProcessing;
            var border = level.getWorldBorder();
            borderMinX = border.getMinX(); borderMaxX = border.getMaxX();
            borderMinZ = border.getMinZ(); borderMaxZ = border.getMaxZ();
        }
        boolean borderMatches(ServerLevel level) {
            var border = level.getWorldBorder();
            return borderMinX == border.getMinX() && borderMaxX == border.getMaxX()
                    && borderMinZ == border.getMinZ() && borderMaxZ == border.getMaxZ();
        }
    }
    private record HeldChunk(ServerLevel level, ChunkPos position, int ticketDistance) {}
    private record PendingCommit(HeldChunk held, long phase, CompletableFuture<Void> light,
            CompletableFuture<Void> save, CompletableFuture<Void> packet, NativeCommitFences.SaveFence nativeSave,
            CompletableFuture<Void> future, boolean countAck) {}
    private static final class EntityWork {
        final List<Entity> entities;
        int index;
        EntityWork(List<Entity> entities) { this.entities = entities; }
    }
    private static final class Budget {
        final long deadline;
        int visits, writes;
        Budget(long deadline, int visits, int writes) { this.deadline = deadline; this.visits = visits; this.writes = writes; }
        boolean available() { return visits > 0 && writes > 0 && System.nanoTime() < deadline; }
    }
    private ForgeOperationRunner() {}
}
