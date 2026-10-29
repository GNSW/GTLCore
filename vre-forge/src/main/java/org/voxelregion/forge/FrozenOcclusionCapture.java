/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Point3;
import org.voxelregion.geometry.Volume;
import org.voxelregion.geometry.VoxelMask;
import org.voxelregion.propagation.FrozenOcclusion;
import org.voxelregion.propagation.MacroVoxelMask;
import org.voxelregion.spatial.ChunkCursor;
import org.voxelregion.spatial.ChunkKey;
import org.voxelregion.spatial.SectionKey;

/** Read-only, budgeted world capture. No removal may start until the complete snapshot is sealed. */
public final class FrozenOcclusionCapture {
    /**
     * Only static state/tag decisions belong here, never contextual explosion/hardness callbacks.
     * Revision must change when the policy or relevant data-pack tags change.
     */
    public interface BlockerPolicy {
        boolean blocks(BlockState state);
        long revision();
    }

    /**
     * Must prove the supplied revision is still current. A missing/unloaded/unknown revision is false,
     * unless a host mutation subscription has continuously tracked that chunk since capture.
     */
    @FunctionalInterface public interface RevisionValidator {
        boolean isCurrent(ChunkKey chunk, long capturedRevision);
    }

    public enum State { NEED_CHUNK, SCANNING, READY_TO_FREEZE, SEALED, FROZEN, STALE, MEMORY_LIMIT, TRACKING_UNAVAILABLE }

    /** Palette skips count as one work unit; partial sections charge each examined voxel. */
    public record Progress(State state, int visited, int boxes, int chunks, long estimatedBytes, String message) {}

    /** Immutable world-free artifact, suitable for journal serialization before the first deletion. */
    public record Snapshot(FrozenOcclusion occlusion, List<IntBox> blockers, Map<ChunkKey, Long> revisions,
                           long policyRevision, int macroCellSize, long estimatedBytes) {
        public Snapshot {
            Objects.requireNonNull(occlusion);
            blockers = List.copyOf(blockers);
            revisions = Map.copyOf(revisions);
        }

        public boolean validate(RevisionValidator validator) {
            Objects.requireNonNull(validator);
            for (var entry : revisions.entrySet())
                if (!validator.isCurrent(entry.getKey(), entry.getValue())) return false;
            return true;
        }
    }

    /** Detached immutable input. Persist first, then compileIndex may run on a bounded worker. */
    public record SealedSnapshot(Point3 center, IntBox bounds, FrozenOcclusion.Mode mode,
                                 List<IntBox> blockers, Map<ChunkKey, Long> revisions,
                                 long policyRevision, int macroCellSize, int maxBoxes,
                                 long maxBytes, long estimatedBytes) {
        public SealedSnapshot {
            Objects.requireNonNull(center); Objects.requireNonNull(bounds); Objects.requireNonNull(mode);
            blockers = List.copyOf(blockers);
            revisions = Map.copyOf(revisions);
            long minimumEstimate = 524_288L + 160L * revisions.size() + 1_024L * blockers.size();
            if (maxBoxes < 1 || blockers.size() > maxBoxes || maxBytes < 1
                    || estimatedBytes < minimumEstimate || estimatedBytes > maxBytes)
                throw new IllegalArgumentException("Invalid sealed snapshot limits");
            if (blockers.stream().anyMatch(box -> box.isEmpty() || !bounds.contains(box)))
                throw new IllegalArgumentException("Snapshot blocker lies outside capture bounds");
            if (macroCellSize != 4 && macroCellSize != 8 && macroCellSize != 16)
                throw new IllegalArgumentException("Invalid macro cell size");
            if (policyRevision < 0 || revisions.values().stream().anyMatch(value -> value < 0))
                throw new IllegalArgumentException("Untracked snapshot revision");
        }

        /** Pure data only: no Level, chunk, tag query or mutation callback is retained. */
        public Snapshot compileIndex() {
            FrozenOcclusion frozen = switch (mode) {
                case NONE -> FrozenOcclusion.none();
                case AXIS_6 -> FrozenOcclusion.axis6(center, bounds, blockers, maxBoxes);
                case AXIS_26 -> FrozenOcclusion.axis26(center, bounds, blockers, maxBoxes);
                case RAYCAST_REFINED -> FrozenOcclusion.raycastRefined(center, bounds, blockers, maxBoxes);
            };
            if (frozen.estimatedBytes() > maxBytes - 160L * revisions.size())
                throw new IllegalStateException("Frozen BVH estimate exceeds memory limit");
            return new Snapshot(frozen, blockers, revisions, policyRevision, macroCellSize, estimatedBytes);
        }
    }

    public static final class Session {
        // Conservative accounting includes the active mask/state cache, map entries, source boxes,
        // temporary BVH construction lists, and the immutable index. It is not a JVM heap profiler.
        private static final long BASE_BYTES = 524_288;
        private static final long CHUNK_BYTES = 160;
        private static final long BOX_BYTES = 1_024;
        private final ServerLevel level;
        private final Volume volume;
        private final Point3 center;
        private final IntBox bounds;
        private final FrozenOcclusion.Mode mode;
        private final int macroCellSize;
        private final int maxBoxes;
        private final int maxChunks;
        private final long maxBytes;
        private final BlockerPolicy policy;
        private final long policyRevision;
        private final ToLongFunction<LevelChunk> revisions;
        private final ChunkCursor cursor;
        private final Map<ChunkKey, Long> captured = new LinkedHashMap<>();
        private final ArrayList<IntBox> blockers = new ArrayList<>();
        private final IdentityHashMap<BlockState, Boolean> sectionPolicy = new IdentityHashMap<>();
        private State state;
        private String message = "";
        private ChunkKey next;
        private LevelChunk active;
        private long activeRevision;
        private int sectionIndex;
        private int endSectionIndex;
        private int voxel;
        private Coverage coverage;
        private VoxelMask.Builder mask;

        public Session(ServerLevel level, Volume volume, Point3 center, FrozenOcclusion.Mode mode,
                       int macroCellSize, int maxBoxes, int maxChunks, long maxBytes,
                       BlockerPolicy policy, ToLongFunction<LevelChunk> revisions) {
            this.level = Objects.requireNonNull(level);
            requireOwner();
            this.volume = Objects.requireNonNull(volume);
            this.center = Objects.requireNonNull(center);
            this.mode = Objects.requireNonNull(mode);
            if (macroCellSize != 4 && macroCellSize != 8 && macroCellSize != 16)
                throw new IllegalArgumentException("Macro cells must have size 4, 8 or 16");
            if (maxBoxes < 1 || maxChunks < 1 || maxBytes < 1) throw new IllegalArgumentException("Capture limits must be positive");
            this.macroCellSize = macroCellSize;
            this.maxBoxes = maxBoxes;
            this.maxChunks = maxChunks;
            this.maxBytes = maxBytes;
            this.policy = Objects.requireNonNull(policy);
            this.policyRevision = policy.revision();
            this.revisions = Objects.requireNonNull(revisions);
            this.bounds = volume.bounds().intersection(new IntBox(IntBox.WORLD_MIN, level.getMinBuildHeight(),
                    IntBox.WORLD_MIN, IntBox.WORLD_MAX, level.getMaxBuildHeight(), IntBox.WORLD_MAX));
            this.cursor = new ChunkCursor(volume, level.getMinBuildHeight(), level.getMaxBuildHeight());
            state = State.NEED_CHUNK;
            if (policyRevision < 0) fail(State.TRACKING_UNAVAILABLE, "Static blocker policy has no reliable revision");
            else if (BASE_BYTES > maxBytes) fail(State.MEMORY_LIMIT, "Capture scratch reservation exceeds memory limit");
            else if (mode == FrozenOcclusion.Mode.NONE) state = State.READY_TO_FREEZE;
            else advanceChunk();
        }

        public State state() { return state; }
        public String message() { return message; }
        public ChunkKey nextChunk() { return next; }
        public int capturedChunks() { return captured.size(); }
        public int blockerBoxes() { return blockers.size(); }
        public long estimatedBytes() { return BASE_BYTES + CHUNK_BYTES * captured.size() + BOX_BYTES * blockers.size(); }

        /** Caller supplies only the requested FULL chunk, under its own bounded loading/ticket policy. */
        public void acceptChunk(LevelChunk chunk, long revision) {
            requireOwner();
            Objects.requireNonNull(chunk);
            if (state != State.NEED_CHUNK) throw new IllegalStateException("Capture is not waiting for a chunk: " + state);
            if (chunk.getLevel() != level || chunk.getPos().x != next.x() || chunk.getPos().z != next.z())
                throw new IllegalArgumentException("Expected capture chunk " + next);
            if (level.getChunkSource().getChunkNow(next.x(), next.z()) != chunk)
                throw new IllegalArgumentException("Capture chunk is not currently loaded as FULL");
            if (revision < 0 || revisions.applyAsLong(chunk) != revision) {
                fail(State.TRACKING_UNAVAILABLE, "Chunk revision is unavailable or changed before capture"); return;
            }
            if (captured.size() >= maxChunks || estimatedBytes() + CHUNK_BYTES > maxBytes) {
                fail(State.MEMORY_LIMIT, "Capture chunk/revision manifest exceeds limit"); return;
            }
            if (policy.revision() != policyRevision) { fail(State.STALE, "Blocker tags changed before capture"); return; }
            active = chunk;
            activeRevision = revision;
            captured.put(next, revision);
            int minY = Math.max(bounds.minY(), level.getMinBuildHeight());
            int maxY = Math.min(bounds.maxY(), level.getMaxBuildHeight());
            sectionIndex = chunk.getSectionIndex(minY);
            endSectionIndex = chunk.getSectionIndex(maxY - 1) + 1;
            voxel = 0;
            coverage = null;
            mask = null;
            sectionPolicy.clear();
            state = State.SCANNING;
        }

        /** Explicit optional claim-as-barrier policy. Ordinary claims are never added automatically. */
        public void recordWholeChunkBarrier(LevelChunk chunk, long revision) {
            acceptChunk(chunk, revision);
            if (state != State.SCANNING || !verifyActive()) return;
            IntBox barrier = next.bounds(level.getMinBuildHeight(), level.getMaxBuildHeight()).intersection(bounds);
            if (appendBoxes(barrier.isEmpty() ? List.of() : List.of(barrier))) finishChunk();
        }

        /**
         * Subscribe this before scanning starts if chunks may be released before final validation.
         * Mutation of any already accepted chunk invalidates capture; the session never revises its
         * old epoch to hide an intervening player edit. This method performs no world access.
         */
        public void observeMutation(ChunkKey chunk, long currentRevision) {
            requireOwner();
            Long expected = captured.get(chunk);
            if (state != State.FROZEN && state != State.SEALED && expected != null && expected.longValue() != currentRevision)
                fail(State.STALE, "Captured chunk changed: " + chunk);
        }

        /** The time limit is cooperative; one palette callback or one macro compaction is indivisible. */
        public Progress step(int maxVisits, long deadlineNanos) {
            requireOwner();
            if (maxVisits < 0) throw new IllegalArgumentException("Negative visit budget");
            int visited = 0;
            if (state != State.SCANNING || !verifyActive()) return progress(visited);
            while (state == State.SCANNING && visited < maxVisits && System.nanoTime() < deadlineNanos) {
                if (sectionIndex >= endSectionIndex) { finishChunk(); break; }
                int sectionY = active.getSectionYFromSectionIndex(sectionIndex);
                SectionKey key = new SectionKey(next.x(), sectionY, next.z());
                var section = active.getSection(sectionIndex);
                if (coverage == null) {
                    visited++;
                    coverage = volume.classify(key.bounds());
                    if (coverage == Coverage.OUTSIDE || !section.maybeHas(this::blocks)) {
                        if (!verifyActive()) break;
                        advanceSection();
                        continue;
                    }
                    if (coverage == Coverage.INSIDE && !section.maybeHas(block -> !blocks(block))) {
                        if (!verifyActive()) break;
                        if (!appendBoxes(List.of(key.bounds().intersection(bounds)))) break;
                        advanceSection();
                        continue;
                    }
                    if (!verifyActive()) break;
                    mask = VoxelMask.builder();
                    if (visited >= maxVisits) break;
                }
                int x = voxel & 15, z = (voxel >>> 4) & 15, y = voxel >>> 8;
                int worldX = key.x() * 16 + x, worldY = sectionY * 16 + y, worldZ = key.z() * 16 + z;
                boolean blocker = (coverage == Coverage.INSIDE || volume.contains(worldX, worldY, worldZ))
                        && blocks(section.getBlockState(x, y, z));
                if (state != State.SCANNING) break;
                if (blocker) mask.set(x, y, z);
                visited++;
                voxel++;
                if (voxel == 4096) {
                    if (!verifyActive()) break;
                    List<IntBox> boxes = MacroVoxelMask.from(mask.build(), macroCellSize).toBoxes(key).stream()
                            .map(box -> box.intersection(bounds)).filter(box -> !box.isEmpty()).toList();
                    if (!appendBoxes(boxes)) break;
                    advanceSection();
                }
            }
            if (state == State.SCANNING && verifyActive() && sectionIndex >= endSectionIndex) finishChunk();
            return progress(visited);
        }

        /**
         * Seal only after full coverage and host revision validation. Persist the returned boxes,
         * policy revision and chunk manifest before deleting anything; a restart must not rebuild
         * an original occlusion field from an already partially cleared world.
         */
        public SealedSnapshot sealSnapshot(RevisionValidator validator) {
            requireOwner();
            Objects.requireNonNull(validator);
            if (state != State.READY_TO_FREEZE) throw new IllegalStateException("Capture is not complete: " + state);
            if (policy.revision() != policyRevision) {
                fail(State.STALE, "Blocker tags changed before freeze"); throw new IllegalStateException(message);
            }
            for (var entry : captured.entrySet()) {
                if (!validator.isCurrent(entry.getKey(), entry.getValue()) || state != State.READY_TO_FREEZE) {
                    fail(State.STALE, "Cannot confirm captured chunk revision: " + entry.getKey());
                    throw new IllegalStateException(message);
                }
            }
            if (policy.revision() != policyRevision) {
                fail(State.STALE, "Blocker tags changed during validation"); throw new IllegalStateException(message);
            }
            state = State.SEALED;
            return new SealedSnapshot(center, bounds, mode, blockers, captured, policyRevision, macroCellSize,
                    maxBoxes, maxBytes, estimatedBytes());
        }

        /** Synchronous convenience for small tests. Production should compile a sealed snapshot off-thread. */
        public Snapshot freeze(RevisionValidator validator) {
            Snapshot result = sealSnapshot(validator).compileIndex();
            state = State.FROZEN;
            return result;
        }

        private boolean blocks(BlockState block) {
            Boolean cached = sectionPolicy.get(block);
            if (cached != null) return cached;
            boolean result = policy.blocks(block);
            sectionPolicy.put(block, result);
            return result;
        }

        private boolean verifyActive() {
            if (state != State.SCANNING) return false;
            if (policy.revision() != policyRevision || active == null
                    || level.getChunkSource().getChunkNow(next.x(), next.z()) != active
                    || revisions.applyAsLong(active) != activeRevision) {
                fail(State.STALE, "World or static blocker policy changed during read-only capture");
                return false;
            }
            return true;
        }

        private boolean appendBoxes(List<IntBox> boxes) {
            for (IntBox incoming : boxes) {
                IntBox combined = incoming;
                while (!blockers.isEmpty()) {
                    int last = blockers.size() - 1;
                    // Exact geometric adjacency alone is insufficient for quantized shadows:
                    // the portable helper also proves both sources AND their union use the same
                    // signed propagation direction, including the established tie rules.
                    IntBox merged = FrozenOcclusion.mergeAdjacent(mode, center, blockers.get(last), combined);
                    if (merged == null) break;
                    blockers.remove(last);
                    combined = merged;
                }
                if (blockers.size() >= maxBoxes || estimatedBytes() + BOX_BYTES > maxBytes) {
                    fail(State.MEMORY_LIMIT, "Macro obstacle boxes/BVH reservation exceed limit; restart capture with a larger cap");
                    return false;
                }
                blockers.add(combined);
            }
            return true;
        }

        private void advanceSection() {
            sectionIndex++;
            voxel = 0;
            coverage = null;
            mask = null;
            sectionPolicy.clear();
        }

        private void finishChunk() {
            if (!verifyActive()) return;
            active = null;
            mask = null;
            sectionPolicy.clear();
            advanceChunk();
        }

        private void advanceChunk() {
            if (cursor.hasNext()) { next = cursor.next(); state = State.NEED_CHUNK; }
            else { next = null; state = State.READY_TO_FREEZE; }
        }

        private Progress progress(int visited) {
            return new Progress(state, visited, blockers.size(), captured.size(), estimatedBytes(), message);
        }

        private void fail(State failure, String reason) {
            state = failure;
            message = reason;
            active = null;
            mask = null;
            sectionPolicy.clear();
        }

        private void requireOwner() {
            if (!level.getServer().isSameThread()) throw new IllegalStateException("Occlusion capture requires the server thread");
        }
    }

    private FrozenOcclusionCapture() {}
}
