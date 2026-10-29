/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.voxelregion.geometry.Sphere;
import org.voxelregion.geometry.Volume;
import org.voxelregion.geometry.SphereShell;
import org.voxelregion.geometry.Point3;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.spatial.ChunkCursor;
import org.voxelregion.spatial.CenterOutChunkCursor;

final class OperationData extends SavedData {
    enum State { FUSE, RUNNING, SETTLING, RECOVERING, PAUSED, PAUSED_UNLOADED, PAUSED_PROTECTION, PAUSED_RECOVERY, FAILED, CANCELLED, WRITTEN }
    final List<Task> tasks = new ArrayList<>();
    private transient boolean reconciled;

    static OperationData get(ServerLevel level) {
        OperationData data = level.getDataStorage().computeIfAbsent(OperationData::load, OperationData::new, "voxelregion_test_operations");
        if (!data.reconciled) { IntentJournal.reconcile(level, data); data.reconciled = true; }
        for (Task task : data.tasks) task.owner = level;
        return data;
    }

    static OperationData load(CompoundTag tag) {
        OperationData data = new OperationData();
        ListTag list = tag.getList("operations", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                Task task = Task.load(list.getCompound(i));
                if (!task.terminal()) {
                    task.state = task.unconfirmedFences == 0 ? State.RECOVERING : State.PAUSED_RECOVERY;
                    task.recovering = true;
                    task.chunkStarted = false;
                    task.message = task.unconfirmedFences == 0
                            ? "Checking co-saved chunk receipts and mutation revisions before recovery."
                            : "Saved progress contains unconfirmed pipelined chunk writes; the advanced cursor is not replay evidence.";
                }
                data.tasks.add(task);
            } catch (RuntimeException ex) {
                RuntimeBridge.LOGGER.error("Invalid persisted region operation; it will not execute", ex);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Task task : tasks) list.add(task.save());
        tag.put("operations", list);
        tag.putInt("schema", 1);
        return tag;
    }

    static final class Task {
        final UUID id;
        final UUID actor;
        final double cx, cy, cz;
        final int radius;
        final boolean generate;
        final boolean clearFluids;
        final double resistance;
        final double entityDamage;
        final boolean protectEveryClaim;
        final AdapterConfig.Physics physics;
        Volume volume;
        org.voxelregion.forge.RegionGeometry geometry;
        AdapterConfig.Progression progression = AdapterConfig.Progression.CENTER_OUT;
        int shellWidth = 16;
        boolean centralBootstrap = true, centerChunkScheduled;
        // Missing on old tasks: retain their original shell interpretation and receipt identity.
        boolean wholeChunk;
        boolean destroyBedrock;
        boolean clearedMissingTerrain;
        long phase;
        long cursorOffset;
        transient long replayOffset;
        transient ChunkCursor phaseCursor;
        transient ChunkCursor.Checkpoint restoredCursor;
        transient CenterOutChunkCursor centerCursor;
        transient CenterOutChunkCursor.Checkpoint restoredCenterCursor;
        transient Volume activeVolume;
        transient String cachedIntentHash;
        transient ServerLevel owner;
        transient boolean needsChunkPacket;
        transient long authoritativeLightThrough;
        transient java.util.concurrent.CompletableFuture<Void> queuedLightWork = java.util.concurrent.CompletableFuture.completedFuture(null);
        transient java.util.BitSet sectionSelection;
        transient int selectionSectionY = Integer.MIN_VALUE;
        boolean chunkSelected;
        boolean recovering;
        boolean resumeRequiresValidation;
        int unconfirmedFences;
        org.voxelregion.propagation.FrozenOcclusion.Mode propagation = org.voxelregion.propagation.FrozenOcclusion.Mode.NONE;
        String frozenHash = "";
        int occlusionCell = 4;
        transient boolean occlusionReady;
        transient org.voxelregion.propagation.FrozenOcclusion occlusion = org.voxelregion.propagation.FrozenOcclusion.none();
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet excludedChunks = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        long skippedUnloaded, bulkSections, generatedSkipped;
        long committedChunks, chunkCommitNanos;
        State state = State.FUSE;
        String message = "";
        int fuse;
        int chunkX, chunkZ, sectionY, voxel;
        boolean chunkStarted, entitiesHandled;
        long visited, changed, kept, skippedChunks, finishedChunks;

        Task(UUID id, UUID actor, double cx, double cy, double cz, int radius, int fuse,
             boolean generate, boolean clearFluids, double resistance, double entityDamage, boolean protectEveryClaim, AdapterConfig.Physics physics, int minSectionY) {
            this.id = id;
            this.actor = actor;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.radius = radius;
            this.fuse = fuse;
            this.generate = generate;
            this.clearFluids = clearFluids;
            this.resistance = resistance;
            this.entityDamage = entityDamage;
            this.protectEveryClaim = protectEveryClaim;
            this.physics = physics;
            volume = Sphere.of(cx, cy, cz, radius);
            chunkX = Math.floorDiv(volume.bounds().minX(), 16);
            chunkZ = Math.floorDiv(volume.bounds().minZ(), 16);
            sectionY = minSectionY;
        }

        boolean terminal() { return state == State.CANCELLED || state == State.WRITTEN || state == State.FAILED; }
        boolean active() { return state == State.FUSE || state == State.RUNNING || state == State.SETTLING || state == State.RECOVERING; }

        long phaseCount() { return wholeChunk || progression == AdapterConfig.Progression.AS_FAST_AS_POSSIBLE ? 1 : centralBootstrap ? 1 + (radius - 1L + shellWidth - 1) / shellWidth : Math.max(1, (radius + (long) shellWidth - 1) / shellWidth); }
        Volume phaseVolume(int minY, int maxY) {
            if (activeVolume == null) {
                activeVolume = wholeChunk || progression == AdapterConfig.Progression.AS_FAST_AS_POSSIBLE ? volume
                    : new SphereShell(Point3.of(cx, cy, cz), phase == 0 ? -1 : (centralBootstrap ? 1 + (phase - 1) * shellWidth : phase * shellWidth) * 2L,
                            Math.min(radius * 2L, (centralBootstrap ? (phase == 0 ? 1 : 1 + phase * shellWidth) : (phase + 1) * shellWidth) * 2L), minY, maxY);
                if (geometry != null && activeVolume != volume) activeVolume = org.voxelregion.geometry.Volumes.intersection(volume, activeVolume);
            }
            return activeVolume;
        }

        String intentHash() {
            if (cachedIntentHash != null) return cachedIntentHash;
            // Stable representation of the complete immutable policy used by this adapter version.
            String text = id + "|" + actor + "|" + cx + "|" + cy + "|" + cz + "|" + radius
                    + "|" + generate + "|" + clearFluids + "|" + resistance + "|" + entityDamage
                    + "|" + protectEveryClaim + "|" + physics + "|" + progression + "|" + shellWidth
                    + (centralBootstrap ? "|centralBootstrap:1" : "")
                    + (wholeChunk ? "|wholeChunk:1" : "")
                    + (destroyBedrock ? "|destroyBedrock:1" : "")
                    + (clearedMissingTerrain ? "|clearedMissingTerrain:1" : "")
                    + (geometry == null ? "" : "|geometry:" + geometry.canonical())
                    + (propagation == org.voxelregion.propagation.FrozenOcclusion.Mode.NONE ? "" : "|propagation:" + propagation + "|cell:" + occlusionCell);
            try { return cachedIntentHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id);
            if (geometry != null) t.put("geometry", geometry.save());
            t.putUUID("actor", actor);
            t.putDouble("cx", cx); t.putDouble("cy", cy); t.putDouble("cz", cz);
            t.putInt("radius", radius); t.putInt("fuse", fuse); t.putBoolean("generate", generate);
            t.putBoolean("clearFluids", clearFluids);
            t.putBoolean("centralBootstrap", centralBootstrap); t.putBoolean("centerChunkScheduled", centerChunkScheduled);
            t.putBoolean("wholeChunk", wholeChunk);
            t.putBoolean("destroyBedrock", destroyBedrock);
            t.putBoolean("clearedMissingTerrain", clearedMissingTerrain);
            t.putString("propagation", propagation.name()); t.putString("frozenHash", frozenHash); t.putInt("occlusionCell", occlusionCell);
            t.putString("progression", progression.name()); t.putInt("shellWidth", shellWidth);
            t.putLong("phase", phase); t.putLong("cursorOffset", cursorOffset); t.putBoolean("chunkSelected", chunkSelected);
            ChunkCursor.Checkpoint checkpoint = phaseCursor == null ? restoredCursor : phaseCursor.checkpoint();
            if (checkpoint != null) {
                CompoundTag c = new CompoundTag();
                c.putInt("version", checkpoint.formatVersion());
                IntBox b = checkpoint.clippedBounds();
                c.putIntArray("bounds", new int[] {b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()});
                c.putInt("rx", checkpoint.regionX()); c.putInt("rz", checkpoint.regionZ());
                c.putInt("cx", checkpoint.chunkX()); c.putInt("cz", checkpoint.chunkZ()); c.putBoolean("end", checkpoint.exhausted());
                t.put("chunkCursor", c);
            }
            var centerCheckpoint = centerCursor == null ? restoredCenterCursor : centerCursor.checkpoint();
            if (centerCheckpoint != null) {
                CompoundTag c = new CompoundTag();
                c.putInt("version", centerCheckpoint.formatVersion());
                IntBox b = centerCheckpoint.clippedBounds();
                c.putIntArray("bounds", new int[] {b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()});
                c.putLongArray("center", new long[] {centerCheckpoint.center().x2(), centerCheckpoint.center().y2(), centerCheckpoint.center().z2()});
                c.putInt("x", centerCheckpoint.lastChunkX()); c.putInt("z", centerCheckpoint.lastChunkZ());
                c.putBoolean("started", centerCheckpoint.started()); c.putBoolean("end", centerCheckpoint.exhausted());
                t.put("centerCursor", c);
            }
            t.putBoolean("recovering", recovering); t.putBoolean("resumeRequiresValidation", resumeRequiresValidation);
            t.putInt("unconfirmedFences", unconfirmedFences);
            long[] exclusions = excludedChunks.toLongArray(); java.util.Arrays.sort(exclusions);
            t.putLongArray("excludedChunks", exclusions);
            t.putLong("generatedSkipped", generatedSkipped); t.putLong("skippedUnloaded", skippedUnloaded); t.putLong("bulkSections", bulkSections);
            t.putLong("committedChunks", committedChunks); t.putLong("chunkCommitNanos", chunkCommitNanos);
            t.putDouble("resistance", resistance); t.putDouble("entityDamage", entityDamage);
            t.putBoolean("protectEveryClaim", protectEveryClaim);
            t.putString("physics", physics.name());
            t.putString("state", state.name()); t.putString("message", message);
            t.putInt("chunkX", chunkX); t.putInt("chunkZ", chunkZ); t.putInt("sectionY", sectionY); t.putInt("voxel", voxel);
            t.putBoolean("chunkStarted", chunkStarted); t.putBoolean("entitiesHandled", entitiesHandled);
            t.putLong("visited", visited); t.putLong("changed", changed); t.putLong("kept", kept);
            t.putLong("skippedChunks", skippedChunks); t.putLong("finishedChunks", finishedChunks);
            return t;
        }

        static Task load(CompoundTag t) {
            Task job = new Task(t.getUUID("id"), t.getUUID("actor"), t.getDouble("cx"), t.getDouble("cy"), t.getDouble("cz"),
                    t.getInt("radius"), t.getInt("fuse"), t.getBoolean("generate"),
                    !t.contains("clearFluids") || t.getBoolean("clearFluids"), t.getDouble("resistance"),
                    t.getDouble("entityDamage"), t.getBoolean("protectEveryClaim"),
                    t.contains("physics") ? AdapterConfig.Physics.valueOf(t.getString("physics")) : AdapterConfig.Physics.NONE, t.getInt("sectionY"));
            if (t.contains("geometry", Tag.TAG_COMPOUND)) { job.geometry = org.voxelregion.forge.RegionGeometry.load(t.getCompound("geometry")); job.volume = job.geometry.volume(); }
            job.state = State.valueOf(t.getString("state")); job.message = t.getString("message");
            job.centralBootstrap = t.getBoolean("centralBootstrap"); job.centerChunkScheduled = t.getBoolean("centerChunkScheduled");
            job.wholeChunk = t.getBoolean("wholeChunk");
            job.destroyBedrock = t.getBoolean("destroyBedrock");
            job.clearedMissingTerrain = t.getBoolean("clearedMissingTerrain");
            job.propagation = t.contains("propagation") ? org.voxelregion.propagation.FrozenOcclusion.Mode.valueOf(t.getString("propagation")) : org.voxelregion.propagation.FrozenOcclusion.Mode.NONE;
            job.occlusionCell = t.contains("occlusionCell") ? t.getInt("occlusionCell") : 4;
            if (!java.util.Set.of(4, 8, 16).contains(job.occlusionCell)) throw new IllegalArgumentException("Invalid persisted macro cell size");
            job.frozenHash = t.getString("frozenHash");
            if (!job.frozenHash.isEmpty() && !job.frozenHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid frozen field hash");
            job.progression = t.contains("progression") ? AdapterConfig.Progression.valueOf(t.getString("progression"))
                    : AdapterConfig.Progression.AS_FAST_AS_POSSIBLE;
            job.shellWidth = t.contains("shellWidth") ? t.getInt("shellWidth") : 16;
            if (job.shellWidth < 1 || job.shellWidth > 128) throw new IllegalArgumentException("Invalid shell width");
            job.phase = t.getLong("phase"); job.cursorOffset = t.getLong("cursorOffset");
            if (job.phase < 0 || job.phase > job.phaseCount() || job.cursorOffset < 0)
                throw new IllegalArgumentException("Invalid persisted wave cursor");
            job.resumeRequiresValidation = t.getBoolean("resumeRequiresValidation");
            job.unconfirmedFences = t.getInt("unconfirmedFences");
            if (job.unconfirmedFences < 0 || job.unconfirmedFences > 512)
                throw new IllegalArgumentException("Invalid in-flight chunk evidence count");
            job.chunkSelected = t.getBoolean("chunkSelected"); job.recovering = t.getBoolean("recovering");
            if (t.contains("chunkCursor", Tag.TAG_COMPOUND)) {
                CompoundTag c = t.getCompound("chunkCursor"); int[] b = c.getIntArray("bounds");
                if (b.length != 6) throw new IllegalArgumentException("Invalid iterator bounds");
                job.restoredCursor = new ChunkCursor.Checkpoint(c.getInt("version"), new IntBox(b[0], b[1], b[2], b[3], b[4], b[5]),
                        c.getInt("rx"), c.getInt("rz"), c.getInt("cx"), c.getInt("cz"), c.getBoolean("end"));
            }
            if (t.contains("centerCursor", Tag.TAG_COMPOUND)) {
                CompoundTag c = t.getCompound("centerCursor"); int[] b = c.getIntArray("bounds"); long[] center = c.getLongArray("center");
                if (!job.wholeChunk || b.length != 6 || center.length != 3) throw new IllegalArgumentException("Invalid center cursor");
                job.restoredCenterCursor = new CenterOutChunkCursor.Checkpoint(c.getInt("version"),
                        new IntBox(b[0], b[1], b[2], b[3], b[4], b[5]), new Point3(center[0], center[1], center[2]),
                        c.getInt("x"), c.getInt("z"), c.getBoolean("started"), c.getBoolean("end"));
            }
            long[] excluded = t.getLongArray("excludedChunks");
            if (excluded.length > 2_000_000) throw new IllegalArgumentException("Too many excluded chunks");
            for (long position : excluded) job.excludedChunks.add(position);
            job.generatedSkipped = t.getLong("generatedSkipped"); job.skippedUnloaded = t.getLong("skippedUnloaded"); job.bulkSections = t.getLong("bulkSections");
            job.committedChunks = t.getLong("committedChunks"); job.chunkCommitNanos = t.getLong("chunkCommitNanos");
            job.chunkX = t.getInt("chunkX"); job.chunkZ = t.getInt("chunkZ"); job.voxel = t.getInt("voxel");
            job.chunkStarted = t.getBoolean("chunkStarted"); job.entitiesHandled = t.getBoolean("entitiesHandled");
            job.visited = t.getLong("visited"); job.changed = t.getLong("changed"); job.kept = t.getLong("kept");
            job.skippedChunks = t.getLong("skippedChunks"); job.finishedChunks = t.getLong("finishedChunks");
            return job;
        }
    }
}
