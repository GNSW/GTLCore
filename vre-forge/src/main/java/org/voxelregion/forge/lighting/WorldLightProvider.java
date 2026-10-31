/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.lighting;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import org.voxelregion.forge.NativeAccess;
import org.voxelregion.lighting.ClearedChunkLighting;
import org.voxelregion.lighting.FreshLightBatchPlanner;
import org.voxelregion.lighting.GlobalLightField;
import org.voxelregion.lighting.LightBatchPlanner;
import org.voxelregion.lighting.LightBatchPlanner.Rect;
import org.voxelregion.lighting.LightWorkingBudget;

/**
 * World-local immutable publication with detached numerical jobs. The normal Minecraft light
 * mailbox still orders terrain events, saved light, generation tickets and completion callbacks.
 * Only numerical work leaves that owner; workers never query a level or a block callback.
 */
public final class WorldLightProvider {
    public interface Access { WorldLightProvider vre$worldLighting(); }
    private static final int MAX_TILE = 4;
    private static final int THREADS = Math.max(1, Math.min(8,
            Integer.getInteger("vre.globalLighting.threads", Runtime.getRuntime().availableProcessors() - 1)));
    private static final long CACHE_BYTES = Math.min(192L << 20, Runtime.getRuntime().maxMemory() / 16);
    private static final long HEAP_RESERVE = Math.min(512L << 20, Runtime.getRuntime().maxMemory() / 16);
    private static final LightWorkingBudget WORKING = new LightWorkingBudget(
            Math.max(16L << 20, Math.min(384L << 20, Runtime.getRuntime().maxMemory() / 12)));
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(THREADS, task -> {
        Thread thread = new Thread(task, "vre-light-field"); thread.setDaemon(true); return thread;
    });
    private static final java.util.concurrent.atomic.LongAdder FULL_JOBS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder INCREMENTAL_JOBS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder CAPTURE_NS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder SOLVE_NS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder SOLVE_WORK = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder RETRIES = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder FULL_MISS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder FULL_IDENTITY = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder FULL_REVISION = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder FULL_FORCED = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder COVER_HITS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder OPEN_JOBS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder OPEN_COLUMNS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder OPEN_REJECTED = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder OPEN_CAPTURE_NS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder OPEN_SOLVE_NS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder OPEN_BAND_CELLS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder MEMORY_SPLITS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder MEMORY_EVICTED = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder MEMORY_REJECTED = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder FULL_SAMPLED_COLUMNS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder FULL_OUTPUT_COLUMNS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder FULL_MERGED_JOBS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder FULL_MERGED_INPUTS = new java.util.concurrent.atomic.LongAdder();
    public record Statistics(long fullJobs, long incrementalJobs, long captureNanos, long solveNanos,
                             long solveWork, long retries, long fullMiss, long fullIdentity,
                             long fullRevision, long fullForced, long coverHits, long openJobs,
                             long openColumns, long openRejected, long openCaptureNanos,
                             long openSolveNanos, long openInputBandCells, long workingCapacityBytes,
                             long workingReservedBytes, long workingPeakBytes, long memorySplits,
                             long pressureEvictedCacheBytes, long memoryRejected,
                             long fullSampledColumns, long fullOutputColumns,
                             long fullMergedJobs, long fullMergedInputs,
                             Map<String, Long> opticalTileCache) { }
    public static Statistics statistics() { return new Statistics(FULL_JOBS.sum(), INCREMENTAL_JOBS.sum(),
            CAPTURE_NS.sum(), SOLVE_NS.sum(), SOLVE_WORK.sum(), RETRIES.sum(), FULL_MISS.sum(),
            FULL_IDENTITY.sum(), FULL_REVISION.sum(), FULL_FORCED.sum(), COVER_HITS.sum(), OPEN_JOBS.sum(),
            OPEN_COLUMNS.sum(), OPEN_REJECTED.sum(), OPEN_CAPTURE_NS.sum(), OPEN_SOLVE_NS.sum(), OPEN_BAND_CELLS.sum(),
            WORKING.capacity(), WORKING.statistics().reserved(), WORKING.statistics().peak(), MEMORY_SPLITS.sum(),
            MEMORY_EVICTED.sum(), MEMORY_REJECTED.sum(), FULL_SAMPLED_COLUMNS.sum(), FULL_OUTPUT_COLUMNS.sum(),
            FULL_MERGED_JOBS.sum(), FULL_MERGED_INPUTS.sum(), WorldOptics.tileCacheStatistics()); }
    public static boolean enabled() { return !"false".equalsIgnoreCase(System.getProperty("vre.globalLighting")); }

    /** Mini-worlds retain their own light-data contract, including GuideME's full-bright layers. */
    public static WorldLightProvider create(LightChunkGetter source, boolean block, boolean sky) {
        if (!enabled()) return null;
        Object world = source.getLevel();
        boolean physical = world instanceof ServerLevel ||
                net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT
                        && ClientWorld.isPhysical(world);
        return physical ? new WorldLightProvider(source, block, sky) : null;
    }
    private static final class ClientWorld {
        static boolean isPhysical(Object world) { return world instanceof net.minecraft.client.multiplayer.ClientLevel; }
    }

    private final LightChunkGetter source;
    private final int minY, height, minSection, sectionCount;
    private final boolean block, sky, client;
    private final Layer blockLayer = new Layer(false), skyLayer = new Layer(true);
    private volatile Map<Long, Image> visible = Map.of();
    private final Set<Long> dirty = new LinkedHashSet<>(), whole = new HashSet<>();
    private final Map<Long, BlockPos> edits = new LinkedHashMap<>();
    private final Map<Long, DataLayer[]> incomingBlock = new HashMap<>(), incomingSky = new HashMap<>();
    private final Set<Long> enabledColumns = new HashSet<>(), unload = new HashSet<>(), disabled = new HashSet<>();
    private final Map<Long, Long> revisions = new HashMap<>();
    // Becoming a light source is an availability event, not an optical write. Keep
    // its generation separately so old fields must prove unchanged terrain even if
    // the arrival and their eventual reuse happen in different owner batches.
    private final Map<Long, Long> sourceEpochs = new HashMap<>();
    private final LinkedHashMap<Rect, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);
    // Missing halo is a dormant dependency, not runnable numerical work. Only an actual
    // column arrival may wake it; polling hasWork must not spin waiting for world generation.
    private final Set<Long> deferred = new HashSet<>();
    private final List<Publication> completions = new ArrayList<>();
    private long cachedBytes;
    private volatile boolean pending, running;
    private volatile IllegalStateException terminalFailure;
    private Set<Long> failedActiveColumns = Set.of();

    private record Image(DataLayer[] block, DataLayer[] sky, Object publicationToken) {
        private Image(DataLayer[] block, DataLayer[] sky) { this(block, sky, new Object()); }
    }
    private record Publication(long column, CompletableFuture<Void> completion) { }
    public static final class MissingLightHaloException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private MissingLightHaloException(Collection<Long> columns) {
            super("Light field has not been published for columns " + columns.stream()
                    .map(key -> "[" + ChunkPos.getX(key) + "," + ChunkPos.getZ(key) + "]").toList()
                    + "; the column or its one-column terrain halo is unavailable");
        }
    }
    // The numerical field may cover more columns than were requested for publication.
    // Tokens identify the actual installed image, not merely a column that received this
    // result sometime earlier. Another overlapping field may have replaced that image.
    // Keep only tiny tokens here; retaining old images would also retain their nibble arrays.
    private record Cached(GlobalLightField.Result result, Object[] identities, long[] revisions,
                          long[] sourceEpochs, long[] terrainRevisions,
                          Map<Long, Object> publishedTokens) { }
    private record Work(Rect field, List<Rect> outputs, Object[] identities, long[] revisions, long[] sourceEpochs,
                        long[] terrainRevisions,
                        CompletableFuture<FieldSolution> result, LightWorkingBudget.Lease lease) { }
    /** Both kernels publish through the same owner; only a general field is an incremental cache. */
    private record FieldSolution(GlobalLightField.Result general, Map<Integer, ClearedChunkLighting.Result> open,
                                 int width, int depth) {
        private static FieldSolution general(GlobalLightField.Result result) {
            return new FieldSolution(java.util.Objects.requireNonNull(result), null, 0, 0);
        }
        private static FieldSolution open(WorldOptics.OpenCapture input) {
            return new FieldSolution(null, input.solve(), input.width(), input.depth());
        }
        private ClearedChunkLighting.Result cropColumn(int x, int z) {
            if (general != null) return general.cropColumn(x, z);
            if (x < 1 || z < 1 || x >= width - 1 || z >= depth - 1)
                throw new IllegalArgumentException("Open lighting requires a complete calculation halo");
            return java.util.Objects.requireNonNull(open.get(x + Math.max(width, depth) * z), "Missing proved open column");
        }
    }
    private record FieldState(Object[] identities, long[] revisions, long[] sourceEpochs, long[] terrainRevisions) { }
    private enum Miss { NONE, ABSENT, IDENTITY, REVISION, FORCED }
    private record Choice(Rect field, Cached cached, FieldState state, Miss miss) { }
    private record GroupKey(Rect field, Cached cached) { }
    private static final class Plan {
        final Choice choice;
        final List<Rect> outputs = new ArrayList<>();
        WorldOptics.OpenCapture provedOpen;
        Plan(Choice choice) { this.choice = choice; }
    }

    private WorldLightProvider(LightChunkGetter source, boolean block, boolean sky) {
        this.source = source; this.block = block; this.sky = sky;
        this.client = !(source.getLevel() instanceof ServerLevel);
        minY = source.getLevel().getMinBuildHeight(); height = source.getLevel().getHeight();
        minSection = (minY >> 4) - 1; sectionCount = (height >> 4) + 2;
    }
    public LayerLightEventListener layer(LightLayer kind) { return kind == LightLayer.SKY ? skyLayer : blockLayer; }
    // The server scheduler polls this while numerical workers are running. Never
    // make that poll wait for the light owner's publication monitor.
    public boolean hasWork() { return terminalFailure == null && (pending || running); }
    private void ensureHealthy() {
        IllegalStateException failed = terminalFailure;
        if (failed != null) throw failed;
    }
    /** Called after the failing native-owner invocation has unwound, never by a math worker. */
    public synchronized void failTerminal(Throwable failure) {
        if (terminalFailure != null) return;
        terminalFailure = new IllegalStateException("World light provider stopped after publication failure", failure);
        // Do not load chunks, or leave an old published layer marked valid in a final save.
        // Include the active batch: drain has already removed those columns from dirty.
        for (long key : failedActiveColumns) invalidateSavedLight(key);
        for (long key : dirty) invalidateSavedLight(key);
        for (long key : whole) invalidateSavedLight(key);
        for (long key : deferred) invalidateSavedLight(key);
        for (Publication receipt : completions) {
            int cx = ChunkPos.getX(receipt.column), cz = ChunkPos.getZ(receipt.column);
            for (int z = cz-1; z <= cz+1; z++) for (int x = cx-1; x <= cx+1; x++)
                invalidateSavedLight(ChunkPos.asLong(x,z));
            receipt.completion.completeExceptionally(failure);
        }
        dirty.clear(); whole.clear(); deferred.clear(); edits.clear(); completions.clear();
        incomingBlock.clear(); incomingSky.clear(); unload.clear();
        failedActiveColumns = Set.of(); cache.clear(); cachedBytes = 0;
        pending = false;
    }
    private void invalidateSavedLight(long key) {
        if (source.getChunkForLighting(ChunkPos.getX(key), ChunkPos.getZ(key))
                instanceof net.minecraft.world.level.chunk.ChunkAccess chunk) chunk.setLightCorrect(false);
    }
    /** An accepted request can fail before its rebuild action has entered this provider. */
    public synchronized void invalidateFailedPublication(ChunkPos position) {
        for (int z = position.z-1; z <= position.z+1; z++)
            for (int x = position.x-1; x <= position.x+1; x++)
                invalidateSavedLight(ChunkPos.asLong(x, z));
    }
    public synchronized void changed(BlockPos position) {
        ensureHealthy();
        // Multiplayer clients cannot infer missing neighbours. The server publishes the
        // complete field; local prediction must never erase its incoming boundary light.
        if (client) return;
        if (position.getY() < minY || position.getY() >= minY + height) return;
        pending = true;
        edits.put(position.asLong(), position.immutable());
        for (int z = (position.getZ() - 14) >> 4; z <= (position.getZ() + 14) >> 4; z++)
            for (int x = (position.getX() - 14) >> 4; x <= (position.getX() + 14) >> 4; x++)
                if (publishedOrEnabled(ChunkPos.asLong(x, z))) dirty.add(ChunkPos.asLong(x, z));
    }
    public synchronized void rebuild(ChunkPos position) {
        ensureHealthy();
        if (client) return;
        pending = true;
        whole.add(position.toLong());
        dirty.add(position.toLong());
        markAffected(position);
    }
    private void markAffected(ChunkPos position) {
        for (int z = position.z - 1; z <= position.z + 1; z++)
            for (int x = position.x - 1; x <= position.x + 1; x++)
                if (publishedOrEnabled(ChunkPos.asLong(x, z))) dirty.add(ChunkPos.asLong(x, z));
    }
    private boolean publishedOrEnabled(long key) {
        return !disabled.contains(key) && (visible.containsKey(key) || enabledColumns.contains(key));
    }
    public synchronized void sourceColumn(ChunkPos position) {
        ensureHealthy();
        long key = position.toLong();
        enabledColumns.add(key); disabled.remove(key); unload.remove(key);
        if (client) return;
        // Native LIGHT guarantees exactly one INITIALIZE_LIGHT halo. Each previously
        // published column already sampled that halo's terrain; later optical writes
        // to initialized ProtoChunks enter checkBlock. Lighting a new column therefore
        // needs only that output, not a second halo of premature neighbour outputs.
        pending = true; dirty.add(key); sourceEpochs.merge(key, 1L, Long::sum);
        wakeDeferred(position);
    }
    public void section(SectionPos position, boolean empty) {
        ensureHealthy();
        // This notification maintains native sparse storage, not a change of optics.
        // Our published field already covers the complete column, including empty sections.
        // Actual optical writes arrive through checkBlock; new columns through sourceColumn;
        // direct bulk replacements explicitly rebuild. Upgrading a delayed occupancy event
        // to a full solve repeats edits already applied by a previous native task batch.
    }
    public synchronized void enable(ChunkPos position, boolean value) {
        ensureHealthy();
        if (value) { enabledColumns.add(position.toLong()); disabled.remove(position.toLong()); unload.remove(position.toLong()); wakeDeferred(position); }
        else { enabledColumns.remove(position.toLong()); disabled.add(position.toLong()); unload.add(position.toLong()); pending = true; }
    }
    public synchronized void retain(ChunkPos position, boolean value) {
        ensureHealthy();
        if (value) unload.remove(position.toLong());
    }
    public synchronized void queue(LightLayer kind, SectionPos section, DataLayer data) {
        ensureHealthy();
        int index = section.y() - minSection;
        if (index < 0 || index >= sectionCount) return;
        pending = true;
        Map<Long, DataLayer[]> queuedLayers = kind == LightLayer.SKY ? incomingSky : incomingBlock;
        long key = section.chunk().toLong();
        DataLayer[] layers = queuedLayers.computeIfAbsent(key, ignored -> {
            Image old = visible.get(key);
            return old == null ? new DataLayer[sectionCount] : (kind == LightLayer.SKY ? old.sky : old.block).clone();
        });
        layers[index] = data == null ? null : data.copy();
        if (!client) wakeDeferred(section.chunk());
    }
    public synchronized void afterPublication(ChunkPos position, CompletableFuture<Void> completion) {
        if (terminalFailure != null) { completion.completeExceptionally(terminalFailure); return; }
        completions.add(new Publication(position.toLong(), completion)); pending = true;
    }

    /** May be used by a future continuation; never throw this from native runUpdate itself. */
    public synchronized void requirePublished(ChunkPos position) {
        ensureHealthy();
        long key = position.toLong();
        if (deferred.contains(key) || !visible.containsKey(key)) throw new MissingLightHaloException(List.of(key));
    }

    /** Native already-lit NBT can legitimately omit every light array. Runs only on its POST owner. */
    public synchronized void initializeImplicitSavedColumn(ChunkPos position) {
        ensureHealthy();
        long key = position.toLong();
        if (client || visible.containsKey(key) || deferred.contains(key) || disabled.contains(key)) return;
        sourceColumn(position);
        drain();
    }

    private void wakeDeferred(ChunkPos arrival) {
        if (deferred.isEmpty()) return;
        for (int z = arrival.z - 1; z <= arrival.z + 1; z++) for (int x = arrival.x - 1; x <= arrival.x + 1; x++) {
            long key = ChunkPos.asLong(x, z);
            if (!deferred.contains(key) || disabled.contains(key)) continue;
            boolean complete = true;
            for (int dz = -1; dz <= 1 && complete; dz++) for (int dx = -1; dx <= 1; dx++)
                if (source.getChunkForLighting(x + dx, z + dz) == null) { complete = false; break; }
            if (complete) { dirty.add(key); whole.add(key); pending = true; }
        }
    }

    private void completePublications(List<Publication> receipts, Set<Long> unresolved) {
        for (Publication receipt : receipts) {
            // A receipt owns its center and affected neighboring outputs, not every
            // unrelated request which happened to share the owner's numerical batch.
            // Previously deferred neighbors remain obligations even for an empty fence.
            List<Long> missing = new ArrayList<>();
            int centerX = ChunkPos.getX(receipt.column), centerZ = ChunkPos.getZ(receipt.column);
            for (int z = centerZ - 1; z <= centerZ + 1; z++) for (int x = centerX - 1; x <= centerX + 1; x++) {
                long key = ChunkPos.asLong(x, z);
                if (unresolved.contains(key) || deferred.contains(key)) missing.add(key);
            }
            if (!visible.containsKey(receipt.column) && !missing.contains(receipt.column)) missing.add(receipt.column);
            if (missing.isEmpty()) receipt.completion.complete(null);
            else receipt.completion.completeExceptionally(new MissingLightHaloException(missing));
        }
    }

    /** Called on the ordinary native light owner, never by a numerical worker. */
    public synchronized int drain() {
        ensureHealthy();
        if (running || !pending) return 0;
        running = true;
        pending = false;
        List<Publication> receipts = new ArrayList<>(completions); completions.clear();
        List<Work> batch = new ArrayList<>(THREADS);
        Set<Long> attempted = Set.of();
        try {
            if (dirty.isEmpty() && whole.isEmpty() && edits.isEmpty()
                    && incomingBlock.isEmpty() && incomingSky.isEmpty() && unload.isEmpty()) {
                completePublications(receipts, Set.of());
                return 0;
            }
            Set<Long> affected = new LinkedHashSet<>(dirty); dirty.clear(); attempted = affected;
            Set<Long> full = new HashSet<>(whole); whole.clear();
            List<BlockPos> changed = new ArrayList<>(edits.values()); edits.clear();
            Set<Long> revised = new HashSet<>();
            for (BlockPos pos : changed) {
                revised.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
            }
            revised.addAll(full);
            for (long key : revised) revisions.merge(key, 1L, Long::sum);
            Map<Long, Image> next = new HashMap<>(visible);
            Set<Long> packets = new HashSet<>(incomingBlock.keySet()); packets.addAll(incomingSky.keySet());
            for (long key : packets) {
                Image old = next.get(key);
                next.put(key, new Image(incomingBlock.getOrDefault(key, old == null ? new DataLayer[sectionCount] : old.block),
                        incomingSky.getOrDefault(key, old == null ? new DataLayer[sectionCount] : old.sky)));
            }
            incomingBlock.clear(); incomingSky.clear();
            // A client chunk packet is an authoritative published field, not an instruction to
            // re-solve all its terrain. Normal subsequent block events still enter the solver.
            if (client) affected.removeAll(packets);
            Set<Long> published = new HashSet<>(packets);
            Set<Long> excluded = client ? new HashSet<>(packets) : new HashSet<>();
            excluded.addAll(disabled);
            for (long key : unload) {
                if (!enabledColumns.contains(key)) {
                    next.remove(key); affected.remove(key); revisions.remove(key); sourceEpochs.remove(key);
                    deferred.remove(key);
                    published.add(key); excluded.add(key);
                    invalidateCache(key);
                }
            }
            for (long key : packets) invalidateCache(key);
            unload.clear();
            List<LightBatchPlanner.Column> columns = new ArrayList<>();
            Set<Long> unresolved = new LinkedHashSet<>();
            for (long key : affected) {
                int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
                if (!excluded.contains(key) && source.getChunkForLighting(x, z) != null)
                    columns.add(new LightBatchPlanner.Column(x, z));
                else unresolved.add(key);
            }
            if (heapAvailable() < HEAP_RESERVE * 2) evictNumericalCache();
            int tileLimit = heapAvailable() < HEAP_RESERVE * 2 ? 1 : MAX_TILE;
            while (tileLimit > 1 && new Rect(0, 0, tileLimit, tileLimit).estimatedWorkingBytes(height)
                    > GlobalLightField.DEFAULT_WORKING_BYTES) tileLimit--;
            List<Rect> regions = LightBatchPlanner.plan(columns, tileLimit, tileLimit);
            // Freeze LRU entries for planning. Installing one group must not change the
            // choice made for another output in this same owner batch.
            Map<Rect, Cached> cacheView = new LinkedHashMap<>(cache);
            Map<Rect, FieldState> states = new HashMap<>();
            Map<GroupKey, Plan> plans = new LinkedHashMap<>();
            for (Rect output : regions) {
                Choice choice = chooseField(output, cacheView, states, full, revised);
                GroupKey key = new GroupKey(choice.field, choice.cached);
                plans.computeIfAbsent(key, ignored -> new Plan(choice)).outputs.add(output);
            }
            cacheView.clear(); states.clear();
            ArrayDeque<Plan> remaining = mergeFreshPlans(plans.values(), tileLimit); plans.clear();
            int processed = 0;
            while (!remaining.isEmpty()) {
                Plan plan = remaining.removeFirst();
                long estimate = workingEstimate(plan.choice, changed);
                boolean pressure = heapAvailable() < HEAP_RESERVE + estimate;
                LightWorkingBudget.Lease lease = null;
                if (pressure) {
                    processed += install(batch, next, published, excluded, unresolved); batch.clear();
                    evictNumericalCache();
                    // Clear frozen cache references as well as the LRU, otherwise its old
                    // fields would remain retained throughout this low-memory batch.
                    ArrayDeque<Plan> fullOnly = new ArrayDeque<>();
                    for (Plan old : remaining) for (Rect output : old.outputs)
                        fullOnly.addLast(fullPlan(output));
                    remaining = fullOnly;
                }
                if (estimate > WORKING.capacity() || pressure) {
                    processed += install(batch, next, published, excluded, unresolved); batch.clear();
                    lease = tryProvedOpen(plan);
                    if (lease == null && (plan.choice.cached != null || plan.outputs.size() != 1
                            || plan.choice.field.width() > 1 || plan.choice.field.depth() > 1)) {
                        MEMORY_SPLITS.increment();
                        for (int i = plan.outputs.size() - 1; i >= 0; i--) {
                            Rect output = plan.outputs.get(i);
                            for (int z = output.depth() - 1; z >= 0; z--)
                                for (int x = output.width() - 1; x >= 0; x--)
                                    remaining.addFirst(fullPlan(new Rect(output.x()+x, output.z()+z, 1, 1)));
                        }
                        continue;
                    }
                    // Runtime free space includes garbage awaiting collection. Pressure
                    // selects smaller jobs; only the owned-storage cap can reject one.
                    if (lease == null && estimate > WORKING.capacity()) throw memoryFailure(estimate);
                }
                if (lease == null) lease = WORKING.tryAcquire(estimate);
                if (lease == null) {
                    // Never wait for capacity while holding jobs which only this owner can publish.
                    processed += install(batch, next, published, excluded, unresolved); batch.clear();
                    lease = acquireWorking(estimate);
                }
                batch.add(start(plan, changed, lease));
                if (batch.size() >= THREADS) {
                    processed += install(batch, next, published, excluded, unresolved); batch.clear();
                }
            }
            processed += install(batch, next, published, excluded, unresolved); batch.clear();
            deferred.removeAll(published);
            deferred.addAll(unresolved);
            Map<Long, Image> previous = visible;
            visible = Collections.unmodifiableMap(next);
            for (long key : published) notifyChanged(key, previous.get(key), next.get(key));
            completePublications(receipts, unresolved);
            return processed;
        } catch (Throwable error) {
            failedActiveColumns = attempted;
            cache.clear(); cachedBytes = 0;
            for (Work work : batch) finishReservation(work);
            batch.clear();
            for (long key : attempted) invalidateSavedLight(key);
            for (Publication receipt : receipts) receipt.completion.completeExceptionally(error);
            throw error;
        } finally {
            // Failure must not release reservations while a detached worker still uses them.
            for (Work work : batch) finishReservation(work);
            running = false;
        }
    }
    public static final class ResourceLimitException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private ResourceLimitException(String message) { super(message); }
    }
    private static long heapAvailable() {
        Runtime runtime = Runtime.getRuntime();
        return Math.max(0L, runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()));
    }
    private void evictNumericalCache() {
        MEMORY_EVICTED.add(cachedBytes); cache.clear(); cachedBytes = 0;
    }
    private static ResourceLimitException memoryFailure(long bytes) {
        MEMORY_REJECTED.increment();
        return new ResourceLimitException("Insufficient light capture memory: requested=" + bytes
                + ", capacity=" + WORKING.capacity() + ", heapAvailable=" + heapAvailable()
                + ", heapReserve=" + HEAP_RESERVE);
    }
    private static LightWorkingBudget.Lease acquireWorking(long bytes) {
        if (bytes > WORKING.capacity()) throw memoryFailure(bytes);
        try {
            return WORKING.acquire(bytes);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for light memory", interrupted);
        }
    }
    private Plan fullPlan(Rect output) {
        Plan plan = new Plan(new Choice(output, null, fieldState(output), Miss.FORCED));
        plan.outputs.add(output); return plan;
    }
    /** Share overlapping read halos without adding an output or a terrain dependency. */
    private ArrayDeque<Plan> mergeFreshPlans(Collection<Plan> plans, int tileLimit) {
        Map<Rect, Plan> fresh = new LinkedHashMap<>();
        for (Plan plan : plans) if (plan.choice.cached == null && plan.outputs.size() == 1
                && plan.outputs.get(0).equals(plan.choice.field)) fresh.put(plan.choice.field, plan);
        if (fresh.size() < 2 || tileLimit < 2) return new ArrayDeque<>(plans);
        Map<Plan, Plan> replacements = new HashMap<>();
        for (FreshLightBatchPlanner.Group group : FreshLightBatchPlanner.plan(fresh.keySet(), tileLimit, tileLimit,
                height, Math.min(WORKING.capacity(), GlobalLightField.DEFAULT_WORKING_BYTES))) {
            if (group.outputs().size() < 2) continue;
            Miss reason = fresh.get(group.outputs().get(0)).choice.miss;
            for (Rect output : group.outputs()) if (fresh.get(output).choice.miss == Miss.FORCED) reason = Miss.FORCED;
            Choice choice = new Choice(group.field(), null, fieldState(group.field()), reason);
            // The pure planner accounts for the numerical field. Capture buffers and
            // contextual material callbacks have an additional host-side estimate.
            if (workingEstimate(choice, List.of()) > WORKING.capacity()) continue;
            Plan merged = new Plan(choice); merged.outputs.addAll(group.outputs());
            for (Rect output : group.outputs()) replacements.put(fresh.get(output), merged);
        }
        ArrayDeque<Plan> result = new ArrayDeque<>();
        Set<Plan> emitted = new HashSet<>();
        for (Plan plan : plans) {
            Plan selected = replacements.getOrDefault(plan, plan);
            if (emitted.add(selected)) result.addLast(selected);
        }
        return result;
    }
    private long workingEstimate(Choice choice, List<BlockPos> changed) {
        Rect field = choice.field;
        long bytes = field.estimatedWorkingBytes(height);
        if (choice.miss == Miss.NONE) {
            // Each edit can refresh seven cells and their shape neighbours. This covers
            // our maps, positions, Optical/face arrays and CellUpdate objects, not mod internals.
            long relevant = 0;
            long minX = (long)field.sampleMinX()*16-1, minZ = (long)field.sampleMinZ()*16-1;
            long maxX = minX + field.sampleWidth()*16L + 1, maxZ = minZ + field.sampleDepth()*16L + 1;
            for (BlockPos pos : changed) if (pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ) relevant++;
            return Math.addExact(bytes, Math.multiplyExact(relevant, 8192L));
        }
        return Math.addExact(bytes, WorldOptics.estimatedPreparedCaptureBytes(source, field.sampleMinX(), field.sampleMinZ(),
                field.sampleWidth(), field.sampleDepth(), minY, height));
    }
    private LightWorkingBudget.Lease tryProvedOpen(Plan plan) {
        if (plan.choice.miss == Miss.NONE) return null;
        Rect field = plan.choice.field;
        long proof = WorldOptics.estimatedOpenProofBytes(field.sampleWidth(), field.sampleDepth(), height);
        if (proof > WORKING.capacity()) return null;
        LightWorkingBudget.Lease lease = WORKING.tryAcquire(proof);
        if (lease == null) return null; // Never block while retaining another proof or calculation.
        boolean retained = false;
        try {
            long started = System.nanoTime();
            WorldOptics.OpenCapture open;
            try { open = WorldOptics.tryCaptureOpen(source, field.sampleMinX(), field.sampleMinZ(),
                    field.sampleWidth(), field.sampleDepth(), minY, height, sky); }
            finally { OPEN_CAPTURE_NS.add(System.nanoTime() - started); }
            if (open == null) { OPEN_REJECTED.increment(); return null; }
            long complete = Math.max(proof, open.estimatedWorkingBytes());
            if (complete > WORKING.capacity() || !lease.tryIncreaseTo(complete)) return null;
            plan.provedOpen = open; OPEN_JOBS.increment(); retained = true;
            return lease;
        } finally { if (!retained) lease.close(); }
    }
    private Work start(Plan plan, List<BlockPos> changed, LightWorkingBudget.Lease lease) {
        Choice choice = plan.choice; Rect field = choice.field; Cached old = choice.cached;
        try {
            final java.util.function.Supplier<FieldSolution> prepare;
            long captureStarted = System.nanoTime();
            if (choice.miss == Miss.NONE) {
                INCREMENTAL_JOBS.increment();
                if (plan.outputs.size() != 1 || !plan.outputs.get(0).equals(field)) COVER_HITS.increment();
                var updates = WorldOptics.changes(source, field.sampleMinX(), field.sampleMinZ(),
                        field.sampleWidth(), field.sampleDepth(), minY, height, changed);
                prepare = () -> FieldSolution.general(solve(() -> new GlobalLightField.Session(old.result, updates, List.of())));
            } else {
                FULL_JOBS.increment(); recordFullMiss(choice.miss);
                FULL_SAMPLED_COLUMNS.add((long)field.sampleWidth() * field.sampleDepth());
                for (Rect output : plan.outputs) FULL_OUTPUT_COLUMNS.add((long)output.width() * output.depth());
                if (plan.outputs.size() > 1) {
                    FULL_MERGED_JOBS.increment(); FULL_MERGED_INPUTS.add(plan.outputs.size());
                }
                if (plan.provedOpen != null) {
                    WorldOptics.OpenCapture open = plan.provedOpen; plan.provedOpen = null;
                    prepare = () -> solveOpen(open);
                } else prepare = prepareFull(field, lease);
            }
            CAPTURE_NS.add(System.nanoTime() - captureStarted);
            FieldState state = choice.state;
            CompletableFuture<FieldSolution> future = new CompletableFuture<>();
            // Allocate ownership metadata before the worker can begin. An allocation
            // failure must not orphan a running calculation and its reservation.
            Work work = new Work(field, List.copyOf(plan.outputs), state.identities, state.revisions,
                    state.sourceEpochs, state.terrainRevisions, future, lease);
            WORKERS.execute(() -> {
                try { future.complete(prepare.get()); }
                catch (Throwable failure) { future.completeExceptionally(failure); }
            });
            return work;
        } catch (Throwable failure) { lease.close(); throw failure; }
    }
    private static void finishReservation(Work work) {
        try { work.result.join(); }
        catch (java.util.concurrent.CompletionException | java.util.concurrent.CancellationException ignored) { }
        finally { work.lease.close(); }
    }
    private Choice chooseField(Rect output, Map<Rect, Cached> view, Map<Rect, FieldState> states,
                               Set<Long> full, Set<Long> revised) {
        List<Rect> candidates = new ArrayList<>();
        for (Rect field : view.keySet()) if (containsOutput(field, output)) candidates.add(field);
        candidates.sort(java.util.Comparator.comparingLong((Rect field) -> (long)field.sampleWidth() * field.sampleDepth())
                .thenComparingInt(Rect::z).thenComparingInt(Rect::x).thenComparingInt(Rect::depth).thenComparingInt(Rect::width));
        Miss firstMiss = Miss.ABSENT;
        boolean foundFailure = false;
        for (Rect field : candidates) {
            Cached cached = view.get(field);
            FieldState state = states.computeIfAbsent(field, this::fieldState);
            Miss reason = canReuse(field, cached, state, full, revised);
            if (reason == Miss.NONE) return new Choice(field, cached, state, reason);
            if (!foundFailure) { firstMiss = reason; foundFailure = true; }
        }
        // Rebuilding a failed large cached field would reintroduce output padding and
        // unnecessary work. On a miss, solve exactly this planner output plus its halo.
        return new Choice(output, null, states.computeIfAbsent(output, this::fieldState), firstMiss);
    }
    private static boolean containsOutput(Rect field, Rect output) {
        return output.x() >= field.x() && output.z() >= field.z()
                && (long)output.x() + output.width() <= (long)field.x() + field.width()
                && (long)output.z() + output.depth() <= (long)field.z() + field.depth();
    }
    private FieldState fieldState(Rect region) {
        Object[] current = identities(region);
        return new FieldState(current, stamps(region, revisions), stamps(region, sourceEpochs), terrainRevisions(current));
    }
    private static Miss canReuse(Rect field, Cached cached, FieldState state, Set<Long> full, Set<Long> revised) {
        int width = field.sampleWidth(), x = field.sampleMinX(), z = field.sampleMinZ();
        for (int i = 0; i < state.revisions.length; i++)
            if (full.contains(ChunkPos.asLong(x + i % width, z + i / width))) return Miss.FORCED;
        if (!Arrays.equals(state.identities, cached.identities)) return Miss.IDENTITY;
        for (int i = 0; i < state.revisions.length; i++) {
            long key = ChunkPos.asLong(x + i % width, z + i / width);
            if (state.revisions[i] != cached.revisions[i] + (revised.contains(key) ? 1 : 0)) return Miss.REVISION;
            // Ordinary checkBlock batches retain their existing exact-event contract.
            // A known raw change in a column absent from that batch is not covered by
            // its updates; do not silently bless it merely because the light epoch agrees.
            if (cached.terrainRevisions[i] >= 0 && state.terrainRevisions[i] < 0) return Miss.REVISION;
            if (!revised.contains(key) && state.terrainRevisions[i] != cached.terrainRevisions[i]) return Miss.REVISION;
        }
        if (!Arrays.equals(state.sourceEpochs, cached.sourceEpochs)) {
            // New source availability can reuse a solved field only with a complete
            // unchanged-terrain proof. Unknown ProtoChunk stamps (-1) are never proof;
            // neither may a simultaneous checkBlock/rebuild hide an unobserved write.
            for (int i = 0; i < state.revisions.length; i++) {
                long key = ChunkPos.asLong(x + i % width, z + i / width);
                if (revised.contains(key) || state.terrainRevisions[i] < 0 || cached.terrainRevisions[i] < 0
                        || state.terrainRevisions[i] != cached.terrainRevisions[i]) return Miss.REVISION;
            }
        }
        return Miss.NONE;
    }
    private static void recordFullMiss(Miss reason) {
        switch (reason) {
            case ABSENT -> FULL_MISS.increment();
            case IDENTITY -> FULL_IDENTITY.increment();
            case REVISION -> FULL_REVISION.increment();
            case FORCED -> FULL_FORCED.increment();
            case NONE -> throw new IllegalArgumentException("Incremental job is not a full miss");
        }
    }
    private static long cachedSize(Cached value) {
        return value.result.estimatedBytes() + 256L + 40L * value.identities.length + 80L * value.publishedTokens.size();
    }
    /** Select before allocating a general full-height image, including for native sourceColumn. */
    private java.util.function.Supplier<FieldSolution> prepareFull(Rect field, LightWorkingBudget.Lease lease) {
        long started = System.nanoTime();
        WorldOptics.OpenCapture open;
        try {
            open = WorldOptics.tryCaptureOpen(source, field.sampleMinX(), field.sampleMinZ(),
                    field.sampleWidth(), field.sampleDepth(), minY, height, sky);
        } finally { OPEN_CAPTURE_NS.add(System.nanoTime() - started); }
        if (open != null) {
            if (open.estimatedWorkingBytes() < lease.bytes()) lease.reduceTo(open.estimatedWorkingBytes());
            OPEN_JOBS.increment();
            return () -> solveOpen(open);
        }
        OPEN_REJECTED.increment();
        WorldOptics.PreparedCapture capture = WorldOptics.prepareCapture(source, field.sampleMinX(), field.sampleMinZ(),
                field.sampleWidth(), field.sampleDepth(), minY, height, sky);
        return () -> {
            GlobalLightField.Snapshot snapshot;
            long materializeStarted = System.nanoTime();
            try { snapshot = capture.materialize(); }
            finally { CAPTURE_NS.add(System.nanoTime() - materializeStarted); }
            return FieldSolution.general(solve(() -> new GlobalLightField.Session(snapshot)));
        };
    }
    private static FieldSolution solveOpen(WorldOptics.OpenCapture input) {
        long started = System.nanoTime();
        try {
            FieldSolution result = FieldSolution.open(input);
            // This is the proved low-band input volume, not GlobalLightField's work counter.
            OPEN_BAND_CELLS.add(input.bandCells());
            return result;
        } finally {
            long elapsed = System.nanoTime() - started;
            OPEN_SOLVE_NS.add(elapsed); SOLVE_NS.add(elapsed);
        }
    }
    private int install(List<Work> jobs, Map<Long, Image> next, Set<Long> published, Set<Long> excluded,
                        Set<Long> unresolved) {
        int count = 0;
        ArrayDeque<Plan> retries = new ArrayDeque<>();
        while (!jobs.isEmpty() || !retries.isEmpty()) {
            if (jobs.isEmpty()) {
                Plan retry = retries.removeFirst();
                long estimate = workingEstimate(retry.choice, List.of());
                if ((estimate > WORKING.capacity() || heapAvailable() < HEAP_RESERVE + estimate)
                        && (retry.choice.field.width() > 1 || retry.choice.field.depth() > 1)) {
                    MEMORY_SPLITS.increment(); evictNumericalCache();
                    for (Rect output : retry.outputs) for (int z = 0; z < output.depth(); z++)
                        for (int x = 0; x < output.width(); x++)
                            retries.addLast(fullPlan(new Rect(output.x()+x, output.z()+z, 1, 1)));
                    continue;
                }
                LightWorkingBudget.Lease lease = null;
                if (estimate > WORKING.capacity() || heapAvailable() < HEAP_RESERVE + estimate)
                    lease = tryProvedOpen(retry);
                jobs.add(start(retry, List.of(), lease == null ? acquireWorking(estimate) : lease));
            }
            Work work = jobs.get(0);
            try {
            FieldSolution result = work.result.join();
            // A stale job relinquishes all its buffers before another capture is admitted.
            // Finish the other reserved jobs first; otherwise waiting for a larger retry
            // reservation could wait on a lease which this owner itself must release.
            Object[] current = identities(work.field);
            long[] versions = terrainRevisions(current);
            boolean identityChanged = !Arrays.equals(work.identities, current);
            if (identityChanged || !Arrays.equals(work.terrainRevisions, versions)) {
                RETRIES.increment();
                if (identityChanged) FULL_IDENTITY.increment(); else FULL_REVISION.increment();
                for (Rect output : work.outputs) retries.addLast(fullPlan(output));
                continue;
            }
            Cached prior = cache.get(work.field);
            Map<Long, Object> installed = new HashMap<>();
            if (prior != null && prior.result == result.general) installed.putAll(prior.publishedTokens);
            boolean completelyPublished = true;
            for (Rect output : work.outputs) for (int z = 0; z < output.depth(); z++) for (int x = 0; x < output.width(); x++) {
                int columnX = output.x() + x, columnZ = output.z() + z;
                int relativeX = columnX - work.field.x(), relativeZ = columnZ - work.field.z();
                boolean completeHalo = true;
                for (int dz = 0; dz < 3; dz++) for (int dx = 0; dx < 3; dx++)
                    completeHalo &= work.identities[(relativeZ + dz) * work.field.sampleWidth() + relativeX + dx] != null;
                long key = ChunkPos.asLong(columnX, columnZ);
                if (!completeHalo || excluded.contains(key)) {
                    unresolved.add(key); completelyPublished = false; continue;
                }
                // A numerical field hit alone does not prove this column ever received it.
                Image currentImage = next.get(key);
                if (prior != null && prior.result == result.general && currentImage != null
                        && prior.publishedTokens.get(key) == currentImage.publicationToken) {
                    deferred.remove(key); installed.put(key, currentImage.publicationToken); continue;
                }
                var crop = result.cropColumn(relativeX + 1, relativeZ + 1);
                DataLayer[] blocks = new DataLayer[sectionCount], skies = new DataLayer[sectionCount];
                blocks[0] = new DataLayer(0); blocks[sectionCount - 1] = new DataLayer(0);
                skies[0] = new DataLayer(0); skies[sectionCount - 1] = new DataLayer(sky ? 15 : 0);
                for (int section = 1; section < sectionCount - 1; section++) {
                    int uniformBlock = block ? crop.uniformBlock(section - 1) : 0;
                    int uniformSky = sky ? crop.uniformSky(section - 1) : 0;
                    blocks[section] = uniformBlock >= 0 ? new DataLayer(uniformBlock)
                            : new DataLayer(crop.blockSection(section - 1));
                    skies[section] = uniformSky >= 0 ? new DataLayer(uniformSky)
                            : new DataLayer(crop.skySection(section - 1));
                }
                Image image = new Image(blocks, skies);
                next.put(key, image); published.add(key); installed.put(key, image.publicationToken); count++;
                if (result.general == null) {
                    // Publication does not change terrain. Overlapping numerical fields
                    // retain their own revision proofs, and their old publication tokens
                    // cannot suppress a later crop over this newly installed image.
                    OPEN_COLUMNS.increment();
                }
            }
            if (completelyPublished && result.general != null) {
                Cached replacement = new Cached(result.general, work.identities, work.revisions,
                        work.sourceEpochs, work.terrainRevisions, Map.copyOf(installed));
                cache.put(work.field, replacement);
                cachedBytes += cachedSize(replacement) - (prior == null ? 0 : cachedSize(prior));
            } else {
                Cached removed = cache.remove(work.field);
                if (removed != null) cachedBytes -= cachedSize(removed);
            }
            while (cachedBytes > CACHE_BYTES && !cache.isEmpty()) {
                var iterator = cache.entrySet().iterator(); Cached remove = iterator.next().getValue();
                cachedBytes -= cachedSize(remove); iterator.remove();
            }
            } finally {
                finishReservation(work);
                jobs.remove(0);
            }
        }
        return count;
    }
    private static GlobalLightField.Result solve(java.util.function.Supplier<GlobalLightField.Session> prepare) {
        long started = System.nanoTime();
        try {
            var session = prepare.get();
            GlobalLightField.Progress progress = null;
            while (!session.isComplete()) progress = session.step(262_144);
            if (progress != null) SOLVE_WORK.add(progress.totalWork());
            return session.result();
        } finally { SOLVE_NS.add(System.nanoTime() - started); }
    }
    private void invalidateCache(long column) {
        int x = ChunkPos.getX(column), z = ChunkPos.getZ(column);
        var iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next(); Rect region = entry.getKey();
            if (x >= region.sampleMinX() && x < (long)region.sampleMinX() + region.sampleWidth()
                    && z >= region.sampleMinZ() && z < (long)region.sampleMinZ() + region.sampleDepth()) {
                cachedBytes -= cachedSize(entry.getValue()); iterator.remove();
            }
        }
    }
    private Object[] identities(Rect region) {
        int width = region.sampleWidth(), depth = region.sampleDepth();
        Object[] result = new Object[width * depth];
        for (int dz = 0; dz < depth; dz++) for (int dx = 0; dx < width; dx++)
            result[dx + dz * width] = source.getChunkForLighting(region.sampleMinX() + dx, region.sampleMinZ() + dz);
        return result;
    }
    private static long[] terrainRevisions(Object[] chunks) {
        long[] result = new long[chunks.length];
        for (int i = 0; i < chunks.length; i++)
            result[i] = chunks[i] instanceof NativeAccess.ChunkRevision revision ? revision.vre$mutationRevision() : -1L;
        return result;
    }
    private long[] stamps(Rect region, Map<Long, Long> values) {
        int width = region.sampleWidth(), depth = region.sampleDepth();
        long[] result = new long[width * depth];
        for (int dz = 0; dz < depth; dz++) for (int dx = 0; dx < width; dx++)
            result[dx + dz * width] = values.getOrDefault(ChunkPos.asLong(region.sampleMinX() + dx, region.sampleMinZ() + dz), 0L);
        return result;
    }
    private void notifyChanged(long key, Image old, Image next) {
        for (int section = 0; section < sectionCount; section++) {
            SectionPos pos = SectionPos.of(ChunkPos.getX(key), minSection + section, ChunkPos.getZ(key));
            if (block && different(old == null ? null : old.block[section], next == null ? null : next.block[section]))
                source.onLightUpdate(LightLayer.BLOCK, pos);
            if (sky && different(old == null ? null : old.sky[section], next == null ? null : next.sky[section]))
                source.onLightUpdate(LightLayer.SKY, pos);
        }
    }
    private static boolean different(DataLayer a, DataLayer b) {
        if (a == b) return false;
        if (a == null || b == null) return true;
        boolean uniformA = a.isDefinitelyHomogenous(), uniformB = b.isDefinitelyHomogenous();
        if (uniformA && uniformB) return a.get(0, 0, 0) != b.get(0, 0, 0);
        if (!uniformA && !uniformB) return !Arrays.equals(a.getData(), b.getData());
        DataLayer uniform = uniformA ? a : b, mixed = uniformA ? b : a;
        int value = uniform.get(0, 0, 0);
        byte expected = (byte) (value | value << 4);
        // Only materialized layers may expose their array: getData() would mutate
        // a homogeneous published layer by allocating its backing storage.
        for (byte actual : mixed.getData()) if (actual != expected) return true;
        return false;
    }
    public int brightness(BlockPos pos, int skyDarkening) {
        return Math.max(blockLayer.getLightValue(pos), skyLayer.getLightValue(pos) - skyDarkening);
    }
    public boolean hasSection(SectionPos pos) { return visible.containsKey(pos.chunk().toLong()); }

    private final class Layer implements LayerLightEventListener, NativeAccess.VisibleLightData, NativeAccess.LightBoundary {
        private final boolean skyLayer;
        Layer(boolean skyLayer) { this.skyLayer = skyLayer; }
        @Override public DataLayer getDataLayerData(SectionPos section) {
            Image image = visible.get(section.chunk().toLong()); int index = section.y() - minSection;
            if (image == null || index < 0 || index >= sectionCount) return null;
            DataLayer result = (skyLayer ? image.sky : image.block)[index];
            if (result == null) return null;
            DataLayer copy = result.copy();
            // Native chunk NBT omits isEmpty() layers. An explicit zero sky layer must
            // survive that omission rule, otherwise reload would inherit light above it.
            // Materialize only this owned copy; published arrays remain immutable.
            if (!client && skyLayer && copy.isEmpty()) copy.getData();
            return copy;
        }
        @Override public int getLightValue(BlockPos pos) {
            if (skyLayer && !sky || !skyLayer && !block) return 0;
            if (pos.getY() >= minY + height) return skyLayer ? 15 : 0;
            if (pos.getY() < minY - 16) return 0;
            Image image = visible.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
            int section = (pos.getY() >> 4) - minSection;
            if (image == null || section < 0 || section >= sectionCount) return 0;
            DataLayer layer = (skyLayer ? image.sky : image.block)[section];
            if (layer != null) return layer.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
            if (!skyLayer) return 0;
            // Native packets and NBT omit sky layers whose value is inherited from above.
            for (int above = section + 1; above < sectionCount; above++) {
                DataLayer inherited = image.sky[above];
                if (inherited != null) return inherited.get(pos.getX() & 15, 0, pos.getZ() & 15);
            }
            return 15;
        }
        @Override public void checkBlock(BlockPos pos) { changed(pos); }
        @Override public boolean hasLightWork() { return hasWork(); }
        @Override public int runLightUpdates() { return drain(); }
        @Override public void updateSectionStatus(SectionPos pos, boolean empty) { section(pos, empty); }
        @Override public void setLightEnabled(ChunkPos pos, boolean value) { enable(pos, value); }
        @Override public void propagateLightSources(ChunkPos pos) { sourceColumn(pos); }
        @Override public DataLayer vre$copyVisibleSectionData(long section) { return getDataLayerData(SectionPos.of(section)); }
        @Override public boolean vre$storesUpdatingSection(long section) { return hasSection(SectionPos.of(section)); }
        @Override public net.minecraft.world.level.chunk.LightChunk vre$lightChunk(int x, int z) { return source.getChunkForLighting(x, z); }
    }
}
