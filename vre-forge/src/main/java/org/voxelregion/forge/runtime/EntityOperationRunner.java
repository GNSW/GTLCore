/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.level.ChunkPos;
import org.voxelregion.forge.EntityStorageBridge;
import org.voxelregion.forge.ForgeEntityOperations;
import org.voxelregion.forge.ForgeEntityOperations.State;
import org.voxelregion.forge.RegionGeometry;
import org.voxelregion.geometry.BoxVolume;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.spatial.ChunkCursor;

/** Bounded entity-store pipeline. No terrain loads, generation tickets, or background entity mutation. */
public final class EntityOperationRunner {
    private static final int MAX_CHUNKS = 16;
    private static final int MAX_CHUNK_ENTITIES = 16_384;
    private static final int MAX_SEEN = 262_144;
    private static final int LOAD_TIMEOUT_TICKS = 1200;
    private static final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private static final ArrayDeque<UUID> active = new ArrayDeque<>();
    private static int heldChunks;

    public static ForgeEntityOperations.Handle submit(ServerLevel level, RegionGeometry geometry,
            ForgeEntityOperations.Options options) {
        check(level);
        if (jobs.values().stream().filter(job -> !job.terminal()).count() >= Math.min(16, AdapterConfig.MAX_OPERATIONS.get()))
            throw new IllegalStateException("Entity operation queue is full");
        IntBox bounds = conservativeBounds(geometry);
        long span = 2L * AdapterConfig.MAX_RADIUS.get() + 4;
        if ((long) bounds.maxX() - bounds.minX() > span || (long) bounds.maxY() - bounds.minY() > span
                || (long) bounds.maxZ() - bounds.minZ() > span)
            throw new IllegalArgumentException("Entity geometry exceeds configured maximum radius envelope");
        if (RuntimeBridge.availableMetadataBytes(level.getServer()) < 8192)
            throw new IllegalStateException("Entity operation metadata allowance is exhausted");
        Job job = new Job(level, geometry, options, bounds);
        jobs.put(job.handle.id(), job); active.addLast(job.handle.id());
        prune();
        return job.handle;
    }

    public static Optional<ForgeEntityOperations.Status> status(ServerLevel level, UUID id) {
        check(level);
        Job job = jobs.get(id);
        return job == null || job.level != level ? Optional.empty() : Optional.of(job.snapshot());
    }

    public static boolean cancel(ServerLevel level, UUID id) {
        check(level);
        Job job = jobs.get(id);
        if (job == null || job.level != level || job.terminal()) return false;
        finish(job, State.CANCELLED, "Remaining work cancelled; completed effects are not undone.");
        return true;
    }

    static void tick(MinecraftServer server, long sharedDeadline) {
        long deadline = Math.min(sharedDeadline, System.nanoTime() + 2_000_000L);
        int steps = 0, stalled = 0;
        while (!active.isEmpty() && steps++ < 256 && System.nanoTime() < deadline) {
            UUID id = active.removeFirst();
            Job job = jobs.get(id);
            if (job == null || job.terminal()) continue;
            boolean progressed;
            try {
                if (job.level.getServer() != server || server.getLevel(job.level.dimension()) != job.level)
                    throw new IllegalStateException("Entity operation dimension is no longer active");
                progressed = step(job, server.getTickCount());
            } catch (RuntimeException failure) {
                finish(job, State.FAILED, "Entity operation failed: " + failure.getMessage());
                RuntimeBridge.LOGGER.error("Entity operation {} failed", id, failure);
                progressed = true;
            }
            if (!job.terminal()) active.addLast(id);
            stalled = progressed ? 0 : stalled + 1;
            if (stalled >= Math.max(1, active.size())) break;
        }
    }

    private static boolean step(Job job, int tick) {
        job.state = State.RUNNING;
        if (heldChunks < MAX_CHUNKS && job.pending.size() < MAX_CHUNKS && job.cursor.hasNext()
                && RuntimeBridge.availableMetadataBytes(job.level.getServer()) > MAX_CHUNK_ENTITIES * 16L) {
            var key = job.cursor.next();
            long position = ChunkPos.asLong(key.x(), key.z());
            if (!job.level.getWorldBorder().isWithinBounds(new ChunkPos(key.x(), key.z()))) {
                job.completedChunks++; return true;
            }
            var decision = ClaimChecks.check(job.level, key.x(), key.z(), true);
            if (decision == ClaimChecks.Decision.UNAVAILABLE) throw new IllegalStateException("Entity claim protection unavailable");
            if (decision == ClaimChecks.Decision.DENY) { job.deniedChunks++; job.completedChunks++; return true; }
            job.manager.vre$acquireEntityChunk(position, job.options.scope() == ForgeEntityOperations.Scope.LOAD_EXISTING_SAVED);
            job.pending.addLast(new ChunkWork(position, tick)); heldChunks++;
            job.message = "Reading native entity storage; terrain is not requested.";
            return true;
        }
        if (job.pending.isEmpty()) {
            if (!job.cursor.hasNext()) { finish(job, State.APPLIED, "Native entity effects applied; normal entity storage persists them asynchronously."); return true; }
            job.message = "Waiting for the shared entity-chunk or metadata allowance.";
            return false;
        }
        ChunkWork work = null;
        for (int scanned = 0, count = job.pending.size(); scanned < count; scanned++) {
            ChunkWork candidate = job.pending.removeFirst();
            job.pending.addLast(candidate);
            if (job.options.scope() == ForgeEntityOperations.Scope.LOADED_ONLY || job.manager.vre$entitiesLoaded(candidate.position)) {
                work = candidate; break;
            }
            if (tick - candidate.startedTick > LOAD_TIMEOUT_TICKS)
                throw new IllegalStateException("Native entity load did not complete within " + LOAD_TIMEOUT_TICKS + " ticks");
        }
        if (work == null) return false;
        if (work.entities == null) {
            work.entities = job.manager.vre$entitySnapshot(work.position, MAX_CHUNK_ENTITIES);
            job.message = "Applying entity effects in bounded batches.";
            return true;
        }
        if (work.index >= work.entities.size()) {
            job.pending.remove(work); heldChunks--; job.completedChunks++;
            // Native serialization can call host code; remove our bookkeeping before that
            // callback can cancel this operation and release the remaining leases.
            job.manager.vre$releaseEntityChunk(work.position);
            return true;
        }
        Entity entity = work.entities.get(work.index++);
        job.examined++;
        if (entity.isRemoved() || entity.level() != job.level || !job.geometry.containsPoint(entity.getX(), entity.getY(), entity.getZ())
                || !job.level.getWorldBorder().isWithinBounds(entity.blockPosition())) return true;
        if (entity instanceof Player && !job.options.includePlayers()) { job.skippedPlayers++; return true; }
        if (job.seen.contains(entity.getUUID())) return true;
        var claim = ClaimChecks.check(job.level, entity.chunkPosition().x, entity.chunkPosition().z, true);
        if (claim == ClaimChecks.Decision.UNAVAILABLE) throw new IllegalStateException("Entity claim protection unavailable");
        if (claim == ClaimChecks.Decision.DENY) return true;
        if (job.seen.size() >= MAX_SEEN) throw new IllegalStateException("Entity deduplication quota exceeded: " + MAX_SEEN);
        if (RuntimeBridge.availableMetadataBytes(job.level.getServer()) < 128)
            throw new IllegalStateException("Entity deduplication exceeds the shared metadata allowance");
        job.seen.add(entity.getUUID());
        if (job.options.mode() == ForgeEntityOperations.Mode.DISCARD && !(entity instanceof Player)) {
            if (entity instanceof ContainerEntity container) {
                // Container vehicles drop inventory even for DISCARDED. Do not use clearContent():
                // it unpacks unopened loot before clearing, which can also invoke loot callbacks.
                container.setLootTable(null);
                container.setLootTableSeed(0L);
                container.getItemStacks().clear();
            }
            entity.discard();
        }
        else {
            entity.kill();
            // Hidden entities do not run death ticks; successful non-player kills must not be saved as stranded corpses.
            if (!(entity instanceof Player) && !entity.isAlive() && !entity.isRemoved()) entity.remove(Entity.RemovalReason.KILLED);
        }
        if (entity.isRemoved() || !entity.isAlive()) job.affected++;
        else job.surviving++;
        return true;
    }

    private static void finish(Job job, State state, String message) {
        job.state = state; job.message = message;
        active.remove(job.handle.id());
        RuntimeException releaseFailure = null;
        while (!job.pending.isEmpty()) {
            ChunkWork work = job.pending.removeFirst(); heldChunks--;
            try { job.manager.vre$releaseEntityChunk(work.position); }
            catch (RuntimeException failure) { releaseFailure = failure; }
        }
        job.seen = Set.of();
        if (releaseFailure != null) {
            job.state = State.FAILED; job.message = "Native entity lease release failed: " + releaseFailure.getMessage();
            RuntimeBridge.LOGGER.error("Entity operation {} lease release failed", job.handle.id(), releaseFailure);
        }
    }

    static void stop() {
        for (Job job : List.copyOf(jobs.values()))
            if (!job.terminal()) finish(job, State.CANCELLED, "Server stopping; entity operations are not replayed.");
        jobs.clear(); active.clear(); heldChunks = 0;
    }
    static long estimatedBytes() {
        long bytes = heldChunks * (512L + 16L * MAX_CHUNK_ENTITIES);
        for (Job job : jobs.values()) bytes += 4096L + job.seen.size() * 64L;
        return bytes;
    }
    private static void prune() {
        var iterator = jobs.values().iterator();
        while (jobs.size() > 64 && iterator.hasNext()) if (iterator.next().terminal()) iterator.remove();
    }
    private static void check(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Entity API requires the server thread");
        if (!(level instanceof EntityStorageBridge)) throw new IllegalStateException("Required VRE entity mixins are absent");
        if (RuntimeBridge.activeConfigFile() == null) throw new IllegalStateException("Install ForgeRegionEngine during mod construction first");
    }
    private static IntBox conservativeBounds(RegionGeometry geometry) {
        // Derive this from continuous primitives, not voxel bounds: an intersection can contain
        // a tangent entity position while containing no block center anywhere.
        if (geometry instanceof RegionGeometry.Box box) return box.bounds();
        if (geometry instanceof RegionGeometry.Ball ball) {
            var c = ball.center(); long r = ball.radius2();
            return new IntBox(lower(c.x2(), r), lower(c.y2(), r), lower(c.z2(), r),
                    upper(c.x2(), r), upper(c.y2(), r), upper(c.z2(), r));
        }
        if (geometry instanceof RegionGeometry.Column column) {
            var c = column.center(); long r = column.radius2();
            return new IntBox(lower(c.x2(), r), column.minY(), lower(c.z2(), r),
                    upper(c.x2(), r), column.maxY(), upper(c.z2(), r));
        }
        var combined = (RegionGeometry.Combined) geometry;
        IntBox left = conservativeBounds(combined.left()), right = conservativeBounds(combined.right());
        return switch (combined.operation()) {
            case INTERSECTION -> left.intersection(right);
            case DIFFERENCE -> left;
            case UNION -> new IntBox(Math.min(left.minX(), right.minX()), Math.min(left.minY(), right.minY()),
                    Math.min(left.minZ(), right.minZ()), Math.max(left.maxX(), right.maxX()),
                    Math.max(left.maxY(), right.maxY()), Math.max(left.maxZ(), right.maxZ()));
        };
    }
    private static int lower(long center, long radius) {
        return (int) Math.max(IntBox.WORLD_MIN, Math.min(IntBox.WORLD_MAX, Math.floorDiv(center - radius, 2)));
    }
    private static int upper(long center, long radius) {
        return (int) Math.max(IntBox.WORLD_MIN, Math.min(IntBox.WORLD_MAX, Math.floorDiv(center + radius, 2) + 1));
    }
    private static final class Job {
        final ServerLevel level;
        final EntityStorageBridge.Manager manager;
        final RegionGeometry geometry;
        final ForgeEntityOperations.Options options;
        final ForgeEntityOperations.Handle handle;
        final ChunkCursor cursor;
        final ArrayDeque<ChunkWork> pending = new ArrayDeque<>();
        Set<UUID> seen = new HashSet<>();
        State state = State.QUEUED;
        long examined, affected, surviving, skippedPlayers, deniedChunks, completedChunks;
        String message = "Queued entity operation.";
        Job(ServerLevel level, RegionGeometry geometry, ForgeEntityOperations.Options options, IntBox bounds) {
            this.level = level; this.geometry = geometry; this.options = options;
            manager = ((EntityStorageBridge) level).vre$entityManager();
            handle = new ForgeEntityOperations.Handle(level.dimension().location(), UUID.randomUUID());
            cursor = new ChunkCursor(new BoxVolume(bounds), bounds.minY(), bounds.maxY());
        }
        boolean terminal() { return state == State.APPLIED || state == State.CANCELLED || state == State.FAILED; }
        ForgeEntityOperations.Status snapshot() {
            return new ForgeEntityOperations.Status(handle, state, examined, affected, surviving, skippedPlayers,
                    deniedChunks, completedChunks, pending.size(), message);
        }
    }
    private static final class ChunkWork {
        final long position;
        final int startedTick;
        List<Entity> entities;
        int index;
        ChunkWork(long position, int startedTick) { this.position = position; this.startedTick = startedTick; }
    }
    private EntityOperationRunner() { }
}
