package org.gtlcore.gtlcore.client.bloom;

import org.gtlcore.gtlcore.GTLCore;

import com.gregtechceu.gtceu.api.block.ActiveBlock;

import com.lowdragmc.lowdraglib.client.model.custommodel.LDLMetadataSection;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.model.data.ModelData;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Render-thread owned. No worker shares a BufferBuilder or accesses a live block entity. */
public final class SectionMeshes {

    private static final byte[] EMPTY = new byte[0];
    private final Long2ObjectOpenHashMap<Entry> entries = new Long2ObjectOpenHashMap<>();
    private final RegionMeshes regions = new RegionMeshes();
    private static final Comparator<Entry> PRIORITY = Comparator.<Entry>comparingInt(e -> e.sortVisibility)
            .thenComparingInt(e -> e.sortPriority)
            .thenComparingLong(e -> e.built ? e.lastServed : 0)
            .thenComparingDouble(e -> e.sortDistance);
    private PriorityQueue<Entry> pending = new PriorityQueue<>(PRIORITY);
    private final Map<BlockState, Boolean> candidates = new IdentityHashMap<>();
    private final Map<BlockState, BlockState> alternatives = new IdentityHashMap<>();
    private final EmissionTemplates templates = new EmissionTemplates();
    private final RandomSource candidateRandom = RandomSource.create(0);
    private final BufferBuilder workBuffer = new BufferBuilder(32 * 1024);
    private final LongArrayList stalePositions = new LongArrayList();
    private final ArrayDeque<Entry> uploads = new ArrayDeque<>();
    private long scannedBlocks, tessellatedBlocks, uploadedBytes, meshAllocations, meshPatches;
    private ClientLevel level;
    private Job job;
    private boolean sortPending;
    private Vec3 lastSortCamera;
    private int sortFrames;
    private long completedBuilds;
    private long serviceSerial;
    private long uploadSerial;
    private int lastDraws;
    private double lastBuildMs;
    private long lastUploadNanos;

    public void setLevel(ClientLevel newLevel) {
        if (newLevel == level) return;
        clear();
        level = newLevel;
        if (level != null) discoverLoaded();
    }

    public void rebuildAll() {
        ClientLevel previous = level;
        clear();
        level = previous;
        if (level != null) discoverLoaded();
    }

    private void discoverLoaded() {
        Minecraft mc = Minecraft.getInstance();
        int radius = mc.options.getEffectiveRenderDistance() + 2;
        BlockPos camera = mc.gameRenderer.getMainCamera().getBlockPosition();
        int cx = camera.getX() >> 4, cz = camera.getZ() >> 4;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) loadChunk(chunk);
            }
        }
    }

    public void loadChunk(LevelChunk chunk) {
        if (level == null || chunk.getLevel() != level) return;
        for (int y = level.getMinSection(); y < level.getMaxSection(); y++) {
            if (!chunk.getSection(y - level.getMinSection()).hasOnlyAir()) {
                dirty(chunk.getPos().x, y, chunk.getPos().z);
            }
        }
    }

    public void unloadChunk(int x, int z) {
        if (level == null) return;
        for (int y = level.getMinSection(); y < level.getMaxSection(); y++) {
            Entry entry = entries.remove(SectionPos.asLong(x, y, z));
            if (entry != null) {
                entry.valid = false;
                removeMesh(entry);
                entry.pieces.clear();
                entry.work = null;
            }
        }
        if (job != null && !job.entry.valid) cancelJob();
        pending.removeIf(entry -> !entry.valid);
    }

    public void dirty(int x, int y, int z) {
        Entry entry = entry(x, y, z);
        if (entry == null) return;
        entry.dirty.set(0, 4096);
        queueAgain(entry);
    }

    /** The changed block is serviced before the neighbour/model refresh caused by that change. */
    public void dirtyBlock(int x, int y, int z) {
        dirtyArea(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1);
        Entry entry = entry(x >> 4, y >> 4, z >> 4);
        if (entry == null) return;
        entry.directDirty.set(((y & 15) << 8) | ((z & 15) << 4) | (x & 15));
        sortPending = true;
    }

    /** Inclusive block bounds, already expanded by the caller for neighbour-dependent models. */
    public void dirtyArea(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int sx = x0 >> 4; sx <= x1 >> 4; sx++) for (int sy = y0 >> 4; sy <= y1 >> 4; sy++)
            for (int sz = z0 >> 4; sz <= z1 >> 4; sz++) {
                Entry e = entry(sx, sy, sz);
                if (e == null) continue;
                if (!e.built && e.work == null) e.dirty.set(0, 4096);
                else {
                    int ax = Math.max(0, x0 - sx * 16), bx = Math.min(15, x1 - sx * 16);
                    for (int y = Math.max(0, y0 - sy * 16); y <= Math.min(15, y1 - sy * 16); y++)
                        for (int z = Math.max(0, z0 - sz * 16); z <= Math.min(15, z1 - sz * 16); z++) {
                            int from = (y << 8) + (z << 4) + ax, to = (y << 8) + (z << 4) + bx + 1;
                            e.dirty.set(from, to);
                            e.urgentDirty.set(from, to);
                        }
                }
                queueAgain(e);
            }
    }

    private Entry entry(int x, int y, int z) {
        if (level == null || y < level.getMinSection() || y >= level.getMaxSection() || !level.getChunkSource().hasChunk(x, z)) return null;
        long key = SectionPos.asLong(x, y, z);
        Entry existing = entries.get(key);
        if (existing != null) return existing;
        // Terrain/light invalidation includes empty neighbouring sections. They cannot
        // contribute emission and must not fill the initial-build queue in a large base.
        var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (chunk == null || chunk.getSection(y - level.getMinSection()).hasOnlyAir()) return null;
        Entry created = new Entry(x, y, z);
        entries.put(key, created);
        return created;
    }

    public void buildSome(Vec3 camera, Frustum frustum) {
        if (level == null) return;
        long start = System.nanoTime();
        long deadline = start + (long) (BloomConfig.buildBudgetMs() * 1_000_000);
        sortFrames++;
        // Updated, already built geometry goes ahead of new terrain. An unfinished initial
        // scan is resumable: it cannot monopolize the queue while a nearby machine switches.
        if (!pending.isEmpty() && (sortPending || lastSortCamera == null || lastSortCamera.distanceToSqr(camera) >= 4 || sortFrames >= 8)) {
            List<Entry> sorted = new ArrayList<>(pending);
            for (Entry e : sorted) {
                e.sortVisibility = frustum.isVisible(e.bounds) ? 0 : 1;
                e.sortDistance = e.bounds.getCenter().distanceToSqr(camera);
                e.sortPriority = e.updatePriority();
            }
            // The collection constructor heapifies in linear time. Re-inserting every
            // dirty section individually made scheduling itself exceed the frame budget.
            pending = new PriorityQueue<>(sorted);
            sortPending = false;
            lastSortCamera = camera;
            sortFrames = 0;
        }
        // Reserve part of the same frame budget for publication. Coalesce all of a section's
        // changes before packing/uploading it, instead of doing that after every 64 blocks.
        long reserve = Math.min((deadline - start) / 3, Math.max(100_000L, lastUploadNanos));
        long buildDeadline = deadline - reserve;
        while (System.nanoTime() < buildDeadline) {
            Entry e = pollNext();
            if (e == null) break;
            e.queued = false;
            e.lastServed = ++serviceSerial;
            if (!e.valid) continue;
            LevelChunk chunk = level.getChunkSource().getChunk(e.x, e.z, ChunkStatus.FULL, false);
            if (chunk == null) {
                removeMesh(e);
                e.pieces.clear();
                e.work = null;
                continue;
            }
            if (e.work == null) e.work = new Job(e, chunk);
            job = e.work;
            job.absorbUrgent();
            try {
                // Bound the scheduling quantum as well as CPU time. Local changes join the
                // current pass at priority; whole-section invalidations retain a later pass.
                for (int steps = 0; steps < 64 && !job.todo.isEmpty() && System.nanoTime() < buildDeadline; steps++)
                    job.step();
                boolean finished = job.todo.isEmpty();
                if (job.changed) {
                    // First-time terrain can publish too: a completed visible light must not
                    // wait for thousands of unrelated positions in the rest of its section.
                    if (!e.uploadPending) {
                        e.uploadPending = true;
                        e.uploadOrder = ++uploadSerial;
                        uploads.add(e);
                    }
                    job.changed = false;
                }
                if (finished) {
                    e.built = true;
                    e.work = null;
                    completedBuilds++;
                }
                job = null;
                if (e.work != null || !e.dirty.isEmpty()) queueAgain(e, finished);
            } catch (RuntimeException failure) {
                GTLCore.LOGGER.error("Cannot update bloom section {}, {}, {}; next update will retry", e.x, e.y, e.z, failure);
                discardWorkBuffer();
                removeMesh(e);
                e.pieces.clear();
                e.work = null;
                e.built = false;
                job = null;
            }
        }
        if (uploads.size() > 1) {
            List<Entry> ready = new ArrayList<>(uploads);
            for (Entry e : ready) e.uploadVisibility = frustum.isVisible(e.bounds) ? 0 : 1;
            ready.sort(Comparator.<Entry>comparingInt(e -> e.uploadVisibility).thenComparingLong(e -> e.uploadOrder));
            uploads.clear();
            uploads.addAll(ready);
        }
        long uploadStart = System.nanoTime();
        int uploaded = 0;
        while (!uploads.isEmpty() && (uploaded == 0 || System.nanoTime() < deadline)) {
            Entry ready = uploads.removeFirst();
            ready.uploadPending = false;
            if (ready.valid) {
                upload(ready);
                uploaded++;
            }
        }
        lastUploadNanos = uploaded == 0 ? 0 : (System.nanoTime() - uploadStart) / uploaded;
        lastBuildMs = (System.nanoTime() - start) / 1_000_000.0;
    }

    private void queueAgain(Entry e) {
        queueAgain(e, true);
    }

    private Entry pollNext() {
        // Keep every visible section making progress beside continuously updating machines,
        // including already-built sections waiting for a background refresh.
        if ((serviceSerial & 7) == 7 && !pending.isEmpty()) {
            Entry selected = null;
            for (Entry e : pending) {
                if (e.sortVisibility <= pending.peek().sortVisibility &&
                        (selected == null || e.lastServed < selected.lastServed))
                    selected = e;
            }
            if (selected != null) {
                pending.remove(selected);
                return selected;
            }
        }
        return pending.poll();
    }

    private void queueAgain(Entry e, boolean priorityChanged) {
        if (e.valid && !e.queued) {
            e.queued = true;
            e.sortPriority = e.updatePriority();
            pending.add(e);
        }
        if (priorityChanged) sortPending = true;
    }

    private void upload(Entry e) {
        List<Piece> pieces = new ArrayList<>(e.pieces.values());
        pieces.sort(Comparator.comparingInt(p -> p.index));
        VertexSlots nextSlots = e.slots;
        for (Piece p : pieces) nextSlots.reserve(p.index, p.vertices.length);
        boolean replace = e.mesh == null || nextSlots.used() > e.capacity;
        if (replace) {
            // Compact only when a slot grows beyond the arena. Normal on/off cycles reuse
            // both their block slots and the section's GPU objects.
            nextSlots = new VertexSlots();
            for (Piece p : pieces) nextSlots.reserve(p.index, p.vertices.length);
            int used = nextSlots.used();
            // Initial partial publication must not repeatedly reallocate at every small batch.
            // Grow geometrically; tiny sections reserve only eight quads.
            int capacity = 0;
            if (used > 0) {
                capacity = Math.max(1024, e.capacity);
                while (capacity < used) capacity = Math.multiplyExact(capacity, 2);
            }
            RegionMeshes.Slice next = null;
            if (capacity > 0) {
                var vertices = org.lwjgl.system.MemoryUtil.memCalloc(capacity);
                try {
                    for (Piece p : pieces) {
                        vertices.position(nextSlots.offset(p.index));
                        vertices.put(p.vertices);
                    }
                    vertices.position(0);
                    vertices.limit(capacity);
                    next = regions.upload(e.x, e.y, e.z, vertices);
                    uploadedBytes += capacity;
                    meshAllocations++;
                } finally {
                    org.lwjgl.system.MemoryUtil.memFree(vertices);
                }
            }
            removeMesh(e);
            e.mesh = next;
            e.capacity = capacity;
        } else {
            // Slot offsets are stable; changed blocks alone reach the driver. Removed
            // pieces simply disappear from draw ranges, with no buffer upload at all.
            List<Piece> changed = new ArrayList<>();
            for (Piece p : pieces) if (e.published.get(p.index) != p) changed.add(p);
            final VertexSlots plannedSlots = nextSlots;
            changed.sort(Comparator.comparingInt(p -> plannedSlots.offset(p.index)));
            int largest = 0, run = 0, end = -1;
            for (Piece p : changed) {
                int offset = nextSlots.offset(p.index);
                if (offset != end) run = 0;
                run += p.vertices.length;
                end = offset + p.vertices.length;
                largest = Math.max(largest, run);
            }
            if (largest > 0) {
                var vertices = org.lwjgl.system.MemoryUtil.memAlloc(largest);
                try {
                    int first = -1;
                    end = -1;
                    for (Piece p : changed) {
                        int offset = nextSlots.offset(p.index);
                        if (first >= 0 && offset != end) {
                            vertices.flip();
                            e.mesh.updateVertices(first, vertices);
                            uploadedBytes += vertices.remaining();
                            meshPatches++;
                            vertices.clear();
                            first = -1;
                        }
                        if (first < 0) first = offset;
                        vertices.put(p.vertices);
                        end = offset + p.vertices.length;
                    }
                    vertices.flip();
                    e.mesh.updateVertices(first, vertices);
                    uploadedBytes += vertices.remaining();
                    meshPatches++;
                } finally {
                    org.lwjgl.system.MemoryUtil.memFree(vertices);
                }
            }
        }
        e.slots = nextSlots;
        e.uploaded = pieces.toArray(Piece[]::new);
        e.published.clear();
        for (Piece p : pieces) e.published.put(p.index, p);
        e.counts = new int[pieces.size()];
        e.offsets = new long[pieces.size()];
        e.rangesKnown = false;
    }

    private boolean isCandidate(BlockState state) {
        if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) return false;
        if (BloomRules.fullBlock(state)) return true;
        return candidates.computeIfAbsent(state, value -> {
            var model = EmissionTemplates.localModel(Minecraft.getInstance().getBlockRenderer().getBlockModel(value));
            var fixed = EmissionTemplates.retainedModel(model);
            // Exact LDLib CTM over a simple model only selects between each base sprite
            // and its declared connection sprite. Check both without tessellating every
            // unmarked casing/glass/off variant. Unknown dynamic wrappers remain candidates.
            if (fixed.getClass() != SimpleBakedModel.class && !(fixed instanceof FrameOcclusionModels.Model)) return true;
            boolean connected = fixed != model;
            for (Direction side : Direction.values()) {
                for (var quad : fixed.getQuads(value, side, candidateRandom, ModelData.EMPTY, null))
                    if (emits(quad, connected)) return true;
            }
            for (var quad : fixed.getQuads(value, null, candidateRandom, ModelData.EMPTY, null))
                if (emits(quad, connected)) return true;
            return false;
        });
    }

    private static boolean emits(BakedQuad quad, boolean connected) {
        if (BloomMetadata.matches(quad)) return true;
        if (!connected) return false;
        var connection = LDLMetadataSection.getMetadata(quad.getSprite()).connection;
        return connection != null && BloomMetadata.matchesTexture(connection);
    }

    /**
     * Static active casings, including LDLib CTM over static models, keep both states resident.
     * Unlike a shared vertex template, these are still tessellated at their actual position,
     * including connected textures, Forge culling and AO.
     * Unknown wrappers, tint providers, block entities and offsets retain the ordinary path.
     */
    private BlockState alternateState(BlockState state) {
        if (alternatives.containsKey(state)) return alternatives.get(state);
        BlockState other = null;
        if (state.getBlock() instanceof ActiveBlock && state.hasProperty(ActiveBlock.ACTIVE) &&
                !state.hasBlockEntity() && !state.hasOffsetFunction() && state.getRenderShape() == RenderShape.MODEL) {
            BlockState candidate = state.cycle(ActiveBlock.ACTIVE);
            if (!candidate.hasBlockEntity() && !candidate.hasOffsetFunction() && candidate.getRenderShape() == RenderShape.MODEL &&
                    canRetainState(state) && canRetainState(candidate)) {
                other = candidate;
                alternatives.put(other, state);
            }
        }
        alternatives.put(state, other);
        return other;
    }

    private boolean canRetainState(BlockState state) {
        var model = EmissionTemplates.retainedModel(Minecraft.getInstance().getBlockRenderer().getBlockModel(state));
        if (model.getClass() != SimpleBakedModel.class) return false;
        boolean full = BloomRules.fullBlock(state);
        // A custom tint provider can read the live state at pos instead of the state passed
        // to it. Never precompute its colour while that state is inactive in the world.
        for (var layer : model.getRenderTypes(state, candidateRandom, ModelData.EMPTY)) {
            for (int side = 0; side <= 6; side++) {
                for (var quad : model.getQuads(state, side == 6 ? null : Direction.from3DDataValue(side),
                        candidateRandom, ModelData.EMPTY, layer))
                    if (quad.isTinted() && (full || BloomMetadata.matches(quad))) return false;
            }
        }
        return true;
    }

    private boolean isPotentialCandidate(BlockState state) {
        if (isCandidate(state)) return true;
        BlockState alternate = alternateState(state);
        return alternate != null && isCandidate(alternate);
    }

    public boolean draw(GlProgram shader, Matrix4f view, Frustum frustum, Vec3 camera) {
        lastDraws = 0;
        shader.matrix("ModelView", view);
        stalePositions.clear();
        regions.beginFrame();
        for (Entry e : entries.values()) {
            if (e.mesh == null || e.uploaded.length == 0 || !e.valid || !frustum.isVisible(e.bounds)) continue;
            LevelChunk chunk = level.getChunkSource().getChunk(e.x, e.z, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            var section = chunk.getSection(e.y - level.getMinSection());
            boolean changed = !rangesCurrent(e, section);
            if (changed) updateRanges(e, section);
            if (e.ranges > 0) {
                e.mesh.select(e.counts, e.offsets, e.ranges, changed);
            }
        }
        lastDraws = regions.draw(shader, camera);
        // Do not insert neighbour entries while iterating the section map.
        for (long packed : stalePositions) {
            int x = BlockPos.getX(packed), y = BlockPos.getY(packed), z = BlockPos.getZ(packed);
            dirtyArea(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1);
        }
        return lastDraws > 0;
    }

    private static boolean rangesCurrent(Entry e, LevelChunkSection section) {
        return e.rangesKnown && e.drawnSection == section && section instanceof SectionRevision revision &&
                e.drawnRevision == revision.gtlcore$bloomRevision();
    }

    private static void rememberRanges(Entry e, LevelChunkSection section) {
        e.drawnSection = section;
        e.rangesKnown = section instanceof SectionRevision;
        if (section instanceof SectionRevision revision) e.drawnRevision = revision.gtlcore$bloomRevision();
    }

    /** Rebuild selection only on a state/storage change; geometry stays resident on the GPU. */
    private void updateRanges(Entry e, LevelChunkSection section) {
        int ranges = 0, first = 0, count = 0, end = 0;
        for (Piece piece : e.uploaded) {
            int i = piece.index;
            BlockState current = section.getBlockState(i & 15, i >> 8, (i >> 4) & 15);
            boolean primary = current == piece.state;
            boolean known = primary || current == piece.alternate;
            int bytes = primary ? piece.primaryBytes : known ? piece.vertices.length - piece.primaryBytes : 0;
            int length = bytes / 128 * 6;
            int offset = (e.slots.offset(i) + (primary ? 0 : piece.primaryBytes)) / 128 * 6;
            if (!known || length == 0) {
                // The displayed mesh may lag even if a mod bypassed renderer invalidation.
                // Suppress just this old block now; do not wait for a rebuild or blank the
                // entire section. Unchanged neighbours keep their bloom and one draw batch.
                if (count > 0) {
                    e.counts[ranges] = count;
                    e.offsets[ranges++] = (long) first * 4;
                    count = 0;
                }
                // The current CPU piece may already reflect this change while its
                // completed publication waits for budget. Do not dirty it again per draw.
                if (!known && e.pieces.get(i) == piece && !e.dirty.get(i) && (e.work == null || !e.work.todo.get(i)))
                    stalePositions.add(BlockPos.asLong(e.x * 16 + (i & 15), e.y * 16 + (i >> 8), e.z * 16 + ((i >> 4) & 15)));
            } else {
                if (count > 0 && offset != end) {
                    e.counts[ranges] = count;
                    e.offsets[ranges++] = (long) first * 4;
                    count = 0;
                }
                if (count == 0) first = offset;
                count += length;
                end = offset + length;
            }
        }
        if (count > 0) {
            e.counts[ranges] = count;
            e.offsets[ranges++] = (long) first * 4;
        }
        e.ranges = ranges;
        rememberRanges(e, section);
    }

    public boolean hasMeshes() {
        for (Entry entry : entries.values()) if (entry.mesh != null && entry.uploaded.length > 0) return true;
        return false;
    }

    /** Dormant cached variants must not activate the full-screen bloom pipeline by themselves. */
    public boolean hasVisibleMeshes(Frustum frustum) {
        for (Entry e : entries.values()) {
            if (e.mesh == null || !e.valid || !frustum.isVisible(e.bounds)) continue;
            LevelChunk chunk = level.getChunkSource().getChunk(e.x, e.z, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            var section = chunk.getSection(e.y - level.getMinSection());
            if (rangesCurrent(e, section)) {
                if (e.ranges > 0) return true;
                continue;
            }
            for (Piece piece : e.uploaded) {
                int i = piece.index;
                BlockState current = section.getBlockState(i & 15, i >> 8, (i >> 4) & 15);
                if (current == piece.state) {
                    if (piece.primaryBytes > 0) return true;
                } else if (current == piece.alternate) {
                    if (piece.vertices.length > piece.primaryBytes) return true;
                } else {
                    // Let draw detect and enqueue unnotified state changes even when there
                    // are no other emitting blocks. It will skip an empty mask afterwards.
                    return true;
                }
            }
            e.ranges = 0;
            rememberRanges(e, section);
        }
        return false;
    }

    public int pendingCount() {
        return pending.size() + uploads.size();
    }

    public long scannedBlocks() {
        return scannedBlocks;
    }

    public long tessellatedBlocks() {
        return tessellatedBlocks;
    }

    public long uploadedBytes() {
        return uploadedBytes;
    }

    public long meshAllocations() {
        return meshAllocations;
    }

    public long meshPatches() {
        return meshPatches;
    }

    public String status() {
        long bytes = regions.bytes();
        int meshes = 0;
        for (Entry e : entries.values()) if (e.mesh != null) {
            meshes++;
        }
        return String.format(java.util.Locale.ROOT,
                "sections=%d, draws=%d, pending=%d, builds=%d, mesh=%.1f MiB, build/upload CPU=%.2f ms, blocks=%d/%d, GPU allocations/patches=%d/%d, uploaded=%.1f MiB",
                meshes, lastDraws, pendingCount(), completedBuilds, bytes / 1048576.0, lastBuildMs, tessellatedBlocks, scannedBlocks,
                meshAllocations, meshPatches, uploadedBytes / 1048576.0);
    }

    public void clear() {
        cancelJob();
        for (Entry e : entries.values()) {
            e.valid = false;
            removeMesh(e);
            e.pieces.clear();
            e.work = null;
        }
        entries.clear();
        pending.clear();
        candidates.clear();
        alternatives.clear();
        templates.clear();
        uploads.clear();
        level = null;
        sortPending = false;
        lastDraws = 0;
        lastSortCamera = null;
        sortFrames = 0;
        completedBuilds = 0;
        scannedBlocks = 0;
        tessellatedBlocks = 0;
        uploadedBytes = 0;
        meshAllocations = 0;
        meshPatches = 0;
        serviceSerial = 0;
        uploadSerial = 0;
        lastUploadNanos = 0;
        stalePositions.clear();
    }

    private void cancelJob() {
        discardWorkBuffer();
        job = null;
    }

    private void discardWorkBuffer() {
        if (workBuffer.building()) {
            var discarded = workBuffer.endOrDiscardIfEmpty();
            if (discarded != null) discarded.release();
        }
        workBuffer.discard();
    }

    private static void removeMesh(Entry e) {
        if (e.mesh != null) {
            e.mesh.close();
            e.mesh = null;
        }
        e.uploaded = new Piece[0];
        e.counts = new int[0];
        e.offsets = new long[0];
        e.rangesKnown = false;
        e.drawnSection = null;
        e.ranges = 0;
        e.published.clear();
        e.slots = new VertexSlots();
        e.capacity = 0;
    }

    // Both state variants share one stable slot. A dormant variant can have zero vertices;
    // it must not evict the other variant or enqueue a rebuild merely because it is selected.
    private record Piece(int index, BlockState state, BlockState alternate, int primaryBytes, byte[] vertices) {}

    private static final class Entry implements Comparable<Entry> {

        final int x, y, z;
        final AABB bounds;
        final Int2ObjectOpenHashMap<Piece> pieces = new Int2ObjectOpenHashMap<>();
        final BitSet dirty = new BitSet(4096);
        final BitSet urgentDirty = new BitSet(4096);
        final BitSet directDirty = new BitSet(4096);
        final Int2ObjectOpenHashMap<Piece> published = new Int2ObjectOpenHashMap<>();
        VertexSlots slots = new VertexSlots();
        int capacity;
        Piece[] uploaded = new Piece[0];
        int[] counts = new int[0];
        long[] offsets = new long[0];
        LevelChunkSection drawnSection;
        long drawnRevision;
        int ranges;
        boolean rangesKnown;
        Job work;
        long lastServed, uploadOrder;
        int sortVisibility, uploadVisibility;
        int sortPriority = 2;
        double sortDistance;
        boolean queued, built, uploadPending, valid = true;
        RegionMeshes.Slice mesh;

        @Override
        public int compareTo(Entry other) {
            return PRIORITY.compare(this, other);
        }

        int updatePriority() {
            if (!directDirty.isEmpty() || work != null && !work.direct.isEmpty()) return 0;
            return !urgentDirty.isEmpty() || work != null && !work.urgent.isEmpty() ? 1 : 2;
        }

        Entry(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            // Covers and pipe geometry may extend slightly outside the block.
            bounds = new AABB(x * 16.0 - 1, y * 16.0 - 1, z * 16.0 - 1, x * 16.0 + 17, y * 16.0 + 17, z * 16.0 + 17);
        }
    }

    private final class Job {

        final Entry entry;
        final LevelChunk chunk;
        final LevelChunkSection section;
        final BitSet todo;
        final BitSet urgent = new BitSet(4096);
        final BitSet direct = new BitSet(4096);
        int scanCursor, selection;
        final PoseStack pose = new PoseStack();
        final RandomSource random = RandomSource.create();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean changed;

        Job(Entry entry, LevelChunk chunk) {
            this.entry = entry;
            this.chunk = chunk;
            section = chunk.getSection(entry.y - level.getMinSection());
            todo = (BitSet) entry.dirty.clone();
            urgent.or(entry.urgentDirty);
            direct.or(entry.directDirty);
            entry.dirty.clear();
            entry.urgentDirty.clear();
            entry.directDirty.clear();
            if (section.hasOnlyAir() || !section.maybeHas(SectionMeshes.this::isPotentialCandidate)) {
                changed = !entry.pieces.isEmpty();
                entry.pieces.clear();
                todo.clear();
                urgent.clear();
                direct.clear();
            }
        }

        void absorbUrgent() {
            // A local state/model-data update must not wait behind the remainder of a
            // pre-existing full section scan. Keep a separate priority set; the normal
            // cursor still advances at least every fourth selection under continuous churn.
            urgent.or(entry.urgentDirty);
            todo.or(entry.urgentDirty);
            direct.or(entry.directDirty);
            todo.or(entry.directDirty);
            entry.urgentDirty.clear();
            entry.directDirty.clear();
        }

        void step() {
            for (int skipped = 0; skipped < 64; skipped++) {
                boolean priority = (++selection & 3) != 0;
                int index = priority ? direct.nextSetBit(0) : -1;
                if (index < 0 && priority) index = urgent.nextSetBit(0);
                if (index < 0) {
                    index = todo.nextSetBit(scanCursor);
                    if (index < 0) index = todo.nextSetBit(0);
                    if (index >= 0) scanCursor = index + 1;
                }
                if (index < 0) return;
                todo.clear(index);
                urgent.clear(index);
                direct.clear(index);
                // Notifications coalesced before this position is read need no second pass.
                entry.dirty.clear(index);
                entry.urgentDirty.clear(index);
                entry.directDirty.clear(index);
                scannedBlocks++;
                int x = index & 15, z = (index >> 4) & 15, y = index >> 8;
                BlockState state = section.getBlockState(x, y, z);
                BlockState alternate = alternateState(state);
                if (alternate != null && !state.getValue(ActiveBlock.ACTIVE)) {
                    BlockState swap = state;
                    state = alternate;
                    alternate = swap;
                }
                if (!isCandidate(state) && (alternate == null || !isCandidate(alternate))) {
                    if (entry.pieces.remove(index) != null) changed = true;
                    continue;
                }
                pos.set(entry.x * 16 + x, entry.y * 16 + y, entry.z * 16 + z);
                byte[] primary = vertices(state, x, y, z);
                byte[] secondary = alternate == null ? EMPTY : vertices(alternate, x, y, z);
                byte[] combined = primary;
                if (secondary.length > 0) {
                    combined = Arrays.copyOf(primary, primary.length + secondary.length);
                    System.arraycopy(secondary, 0, combined, primary.length, secondary.length);
                }
                install(index, state, alternate, primary.length, combined);
                return;
            }
        }

        private byte[] vertices(BlockState state, int x, int y, int z) {
            if (!isCandidate(state)) return EMPTY;
            tessellatedBlocks++;
            var renderer = Minecraft.getInstance().getBlockRenderer();
            var original = EmissionTemplates.localModel(renderer.getBlockModel(state));
            byte[] cached = templates.vertices(state, original, level, pos, x, y, z);
            if (cached != null) return cached;
            ModelData data = level.getModelDataManager().getAt(pos);
            var blockEntity = chunk.getBlockEntity(pos);
            if (blockEntity != null) data = blockEntity.getModelData();
            data = original.getModelData(level, pos, state, data == null ? ModelData.EMPTY : data);
            var model = new EmissionModel(original, BloomRules.fullBlock(state), level, pos, state);
            long seed = state.getSeed(pos);
            random.setSeed(seed);
            workBuffer.begin(VertexFormat.Mode.QUADS, EmissionFormat.BLOCK);
            pose.pushPose();
            pose.translate(x, y, z);
            try {
                for (RenderType type : original.getRenderTypes(state, random, data)) {
                    random.setSeed(seed);
                    renderer.getModelRenderer().tesselateBlock(level, model, state, pos, pose, workBuffer,
                            false, random, seed, OverlayTexture.NO_OVERLAY, data, type);
                }
            } finally {
                pose.popPose();
            }
            var result = workBuffer.endOrDiscardIfEmpty();
            if (result == null) return EMPTY;
            try {
                var vertices = result.vertexBuffer();
                byte[] bytes = new byte[vertices.remaining()];
                vertices.get(bytes);
                return bytes;
            } finally {
                result.release();
            }
        }

        private void install(int index, BlockState state, BlockState alternate, int primaryBytes, byte[] bytes) {
            if (bytes.length == 0) {
                if (entry.pieces.remove(index) != null) changed = true;
                return;
            }
            Piece previous = entry.pieces.get(index);
            if (previous == null || previous.state != state || previous.alternate != alternate ||
                    previous.primaryBytes != primaryBytes || !Arrays.equals(previous.vertices, bytes)) {
                entry.pieces.put(index, new Piece(index, state, alternate, primaryBytes, bytes));
                changed = true;
            }
        }
    }
}
