/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import com.mojang.datafixers.util.Either;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.Volume;
import org.voxelregion.spatial.ChunkKey;

/**
 * Missing-terrain materialization. The native holder, disk read, light stages and FULL promotion
 * remain authoritative. Terrain/biomes are computed without live chunks, then published once.
 * In particular, a saved or partially generated chunk never enters this algorithm.
 */
public final class ClearedGeneration {
    public static final String MARKER = "VoxelRegionClearedTerrain";
    private static final Map<ServerLevel, Map<UUID, Field>> fields = new HashMap<>();
    private static final Map<Key, Field> absent = new HashMap<>();
    private static final Map<ChunkAccess, Field> fresh = new java.util.WeakHashMap<>();
    private static final Map<ChunkAccess, CompletableFuture<Boolean>> preparing = new java.util.WeakHashMap<>();
    private static final java.util.concurrent.ConcurrentLinkedQueue<Ready> ready = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.ArrayDeque<Waiting> waiting = new java.util.ArrayDeque<>();
    private static final java.util.concurrent.ConcurrentHashMap<ServerLevel, java.util.concurrent.atomic.AtomicBoolean> wakes = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong reserved = new java.util.concurrent.atomic.AtomicLong();
    private static long materializedChunks, bedrockBlocks;
    private static long publicationBatches, largestPublicationBatch;
    private static final int MAX_PREPARING = 4096;

    /** Called on the server thread after the fuse/occlusion decision, with a durable intent already written. */
    public static void register(ServerLevel level, UUID operation, String intent, Volume volume,
            boolean clearFluids, boolean destroyBedrock, BooleanSupplier active, Predicate<ChunkPos> permission) {
        requireOwner(level);
        var entries = fields.computeIfAbsent(level, ignored -> new LinkedHashMap<>());
        if (entries.containsKey(operation)) return;
        var profile = ClearedTerrainProfile.select(level.getChunkSource().getGenerator(), level, clearFluids, destroyBedrock);
        if (profile != null) entries.put(operation, new Field(operation, intent, volume, profile, destroyBedrock, active, permission,
                profile.algorithm() == ClearedTerrainProfile.Algorithm.NOISE_BEDROCK ? new ParallelBedrockBatches(profile, volume) : null));
    }

    public static void release(UUID operation) {
        ClearedInitialLighting.release(operation);
        for (var entries : fields.values()) {
            var removed = entries.remove(operation);
            if (removed != null && removed.bedrock != null) removed.bedrock.close();
        }
        absent.entrySet().removeIf(entry -> entry.getValue().operation.equals(operation));
        // A detached calculation has not committed yet. Its owner-thread recheck sees active=false.
        // Once published, native LIGHT/FULL must finish the already authorized image, even after cancellation.
    }

    public static void stop() {
        for (var entries : fields.values()) for (var field : entries.values())
            if (field.bedrock != null) field.bedrock.close();
        fields.clear(); absent.clear(); fresh.clear();
        for (var completion : java.util.List.copyOf(preparing.values())) completion.complete(false);
        preparing.clear(); waiting.clear(); ready.clear(); wakes.clear();
    }

    private static Field candidate(ServerLevel level, ChunkPos pos) {
        var entries = fields.get(level);
        if (entries == null) return null;
        for (var field : entries.values()) {
            if (field.active.getAsBoolean() && field.profile.generator() == level.getChunkSource().getGenerator()
                    && field.volume.classify(new ChunkKey(pos.x, pos.z).bounds(level.getMinBuildHeight(), level.getMaxBuildHeight())) == Coverage.INSIDE
                    && GenerationSafetyOracle.insideStableBorder(level, pos.x, pos.z)) return field;
        }
        return null;
    }

    public static CompletableFuture<Optional<CompoundTag>> observeRead(ServerLevel level, ChunkPos pos,
            CompletableFuture<Optional<CompoundTag>> read, Executor owner) {
        absent.remove(new Key(level, pos.toLong()));
        // This entry point is on the owner thread, including the ordinary ChunkMap read path.
        if (candidate(level, pos) == null) return read;
        return read.thenApplyAsync(image -> {
            var key = new Key(level, pos.toLong());
            Field field = candidate(level, pos);
            if (image.isEmpty() && field != null) absent.put(key, field); else absent.remove(key);
            return image;
        }, owner);
    }

    public static void observeFresh(ServerLevel level, ChunkAccess chunk) {
        requireOwner(level);
        Field field = absent.remove(new Key(level, chunk.getPos().toLong()));
        if (field != null && chunk.getClass() == ProtoChunk.class && chunk.getStatus() == ChunkStatus.EMPTY)
            fresh.put(chunk, field);
    }

    /** Null means retain the normal scheduler. No neighbors are required for the selected empty-terrain algorithm. */
    public static CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> schedule(
            ServerLevel level, ChunkMap map, ChunkHolder holder, ChunkStatus status,
            Supplier<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> ordinary) {
        requireOwner(level);
        if (status == ChunkStatus.EMPTY || status.isOrAfter(ChunkStatus.INITIALIZE_LIGHT)) return null;
        // Saved materializations already have FEATURES status and use native loading. This path only
        // removes generation dependencies for fresh positions covered by a currently active field.
        Field requested = candidate(level, holder.getPos());
        if (requested == null) return null;
        Executor owner = ((GenerationBridge) map).vre$generationExecutor();
        // A cleared image supplies every stage through FEATURES at once. Requesting each
        // intermediate parent serialized six redundant owner-mailbox continuations per chunk.
        // Existing/partial terrain still returns to the original scheduler below.
        return holder.getOrScheduleFuture(ChunkStatus.EMPTY, map).thenComposeAsync(previous -> previous.map(chunk -> {
            if (isCleared(chunk)) return CompletableFuture.completedFuture(Either.left(chunk));
            if (!registered(level, requested.operation) || !requested.active.getAsBoolean()) return abandoned();
            Field field = fresh.get(chunk);
            if (field == null || chunk.getStatus() != ChunkStatus.EMPTY || !field.active.getAsBoolean()) return ordinary.get();
            CompletableFuture<Boolean> prepared = preparing.get(chunk);
            if (prepared == null) {
                if (preparing.size() >= MAX_PREPARING)
                    return CompletableFuture.failedFuture(new IllegalStateException("Cleared generation request window exceeded"));
                prepared = materialize(level, (ProtoChunk) chunk, field, owner);
                preparing.put(chunk, prepared);
                prepared.whenComplete((done, failure) -> { preparing.remove(chunk); fresh.remove(chunk); });
            }
            return prepared.thenCompose(ok -> ok ? CompletableFuture.completedFuture(Either.left(chunk))
                    : !registered(level, field.operation) || !field.active.getAsBoolean() ? abandoned() : ordinary.get());
        }, failure -> CompletableFuture.completedFuture(Either.right(failure))), owner);
    }

    private static CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> abandoned() {
        // Removed prefetch tickets must not turn cancellation/stop into thousands of full
        // terrain generations. A future real ticket can retry this native loading failure.
        return CompletableFuture.completedFuture(Either.right(new ChunkHolder.ChunkLoadingFailure() {
            @Override public String toString() { return "Cleared-terrain request was released"; }
        }));
    }

    private static CompletableFuture<Boolean> materialize(ServerLevel level, ProtoChunk target, Field field, Executor owner) {
        if (!permitted(level, target, field)) return CompletableFuture.completedFuture(false);
        var completion = new CompletableFuture<Boolean>();
        waiting.add(new Waiting(level, target, field, owner, completion));
        startWaiting(level.getServer(), System.nanoTime() + 1_000_000L);
        return completion;
    }

    private static void startWaiting(net.minecraft.server.MinecraftServer server, long deadline) {
        while (!waiting.isEmpty() && System.nanoTime() < deadline) {
            var item = waiting.peek();
            if (item.completion.isDone()) { waiting.remove(); continue; }
            if (item.level.getServer() != server || !permitted(item.level, item.target, item.field)) {
                waiting.remove(); item.completion.complete(false); continue;
            }
            long bytes = 131_072L + item.level.getHeight() * 256L;
            long batchBytes = item.field.bedrock == null ? 0 : ParallelBedrockBatches.reservation(item.level);
            if (bytes + batchBytes > org.voxelregion.forge.runtime.RuntimeBridge.availableMetadataBytes(server)) break;
            waiting.remove();
            reserved.addAndGet(bytes);
            startImage(item, bytes);
        }
    }

    private static void startImage(Waiting item, long bytes) {
        var level = item.level; var target = item.target; var field = item.field;
        var owner = item.owner; var completion = item.completion;
        var owners = new java.util.concurrent.atomic.AtomicInteger(2);
        Runnable release = () -> { if (owners.decrementAndGet() == 0) reserved.addAndGet(-bytes); };
        completion.whenComplete((value, error) -> release.run());
        var height = LevelHeightAccessor.create(level.getMinBuildHeight(), level.getHeight());
        var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        var random = level.getChunkSource().randomState();
        var position = target.getPos();
        boolean exactBiomes = org.voxelregion.forge.runtime.RuntimeBridge.exactNewChunkBiomes();
        // A private ProtoChunk is only a detached container for biome palettes and protected bands.
        // It is never installed as a replacement holder and never receives a fake light/FULL flag.
        CompletableFuture<Image> calculation;
        try {
        var masks = field.bedrock == null
                ? CompletableFuture.<java.util.BitSet>completedFuture(null)
                : field.bedrock.request(position, height, random);
        calculation = masks.thenApplyAsync(retained -> {
            if (retained == null) retained = field.profile.retainedBedrock(position, height, random);
            var sections = new net.minecraft.world.level.chunk.LevelChunkSection[height.getSectionsCount()];
            var defaultBiome = exactBiomes ? registry.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS)
                    : field.profile.sampleColumnBiome(position, height, random);
            for (int i = 0; i < sections.length; i++) sections[i] = new net.minecraft.world.level.chunk.LevelChunkSection(
                    new RegionBlockStateContainer(Blocks.AIR.defaultBlockState()),
                    new net.minecraft.world.level.chunk.PalettedContainer<>(registry.asHolderIdMap(), defaultBiome,
                            net.minecraft.world.level.chunk.PalettedContainer.Strategy.SECTION_BIOMES));
            var image = new ProtoChunk(position, UpgradeData.EMPTY, sections,
                    new net.minecraft.world.ticks.ProtoChunkTicks<>(), new net.minecraft.world.ticks.ProtoChunkTicks<>(), height, registry, null);
            if (exactBiomes) field.profile.fillBiomes(image, random);
            for (int bit = retained.nextSetBit(0); bit >= 0; bit = retained.nextSetBit(bit + 1)) {
                int y = height.getMinBuildHeight() + (bit >>> 8);
                image.getSection(image.getSectionIndex(y)).setBlockState(bit & 15, y & 15, (bit >>> 4) & 15,
                        Blocks.BEDROCK.defaultBlockState(), false);
            }
            if (!ClearedColumnMetadata.rebuild(image, ChunkStatus.FEATURES.heightmapsAfter()))
                Heightmap.primeHeightmaps(image, ChunkStatus.FEATURES.heightmapsAfter());
            return new Image(image, retained.cardinality(), exactBiomes);
        }, RegionPreparationPool.executor());
        } catch (RuntimeException error) {
            release.run(); completion.completeExceptionally(error); return;
        }
        calculation.whenComplete((image, error) -> {
            release.run();
            if (completion.isDone()) return;
            ready.add(new Ready(level, target, field, image, error, completion));
            // A native managedBlock may be waiting inside the current tick. Wake its own
            // ChunkMap executor as well as draining at tick END; otherwise END-only batching deadlocks.
            wakePublication(level, owner);
        });
    }

    private static void wakePublication(ServerLevel level, Executor owner) {
        var wake = wakes.computeIfAbsent(level, ignored -> new java.util.concurrent.atomic.AtomicBoolean());
        if (wake.compareAndSet(false, true)) owner.execute(() -> {
            wake.set(false);
            publishReadyBatch(level.getServer(), System.nanoTime() + 4_000_000L);
            if (!ready.isEmpty()) wakePublication(level, owner);
        });
    }

    /** One server transaction publishes a cohort of ready images, then releases their native futures together. */
    public static void publishReadyBatch(net.minecraft.server.MinecraftServer server, long deadline) {
        if (!server.isSameThread()) throw new IllegalStateException("Terrain batch publication requires the server owner");
        var published = new java.util.ArrayList<Ready>();
        for (int i = 0; i < 128 && System.nanoTime() < deadline; i++) {
            var item = ready.poll();
            if (item == null) break;
            if (item.completion.isDone()) continue;
            if (item.level.getServer() != server) { item.completion.complete(false); continue; }
            if (item.error != null) { item.completion.completeExceptionally(item.error); continue; }
            try {
            var level = item.level; var target = item.target; var field = item.field; var image = item.image;
            if (!permitted(level, target, field)) { item.completion.complete(false); continue; }
            var sections = image.chunk.getSections();
            System.arraycopy(sections, 0, target.getSections(), 0, sections.length);
            for (var type : ChunkStatus.FEATURES.heightmapsAfter())
                target.setHeightmap(type, image.chunk.getOrCreateHeightmapUnprimed(type).getRawData());
            var marker = new CompoundTag();
            marker.putInt("schema", 1); marker.putUUID("operation", field.operation);
            marker.putString("intent", field.intent); marker.putString("algorithm", field.profile.algorithm().name());
            marker.putBoolean("destroyBedrock", field.destroyBedrock); marker.putInt("bedrock", image.bedrock);
            marker.putString("biomes", image.exactBiomes ? "NATIVE_QUART" : "CHUNK_SEA_LEVEL");
            ((NativeAccess.ClearedTerrain) target).vre$clearedTerrain(marker);
            ClearedInitialLighting.register(level, target);
            // Structures, terrain, carvers and decoration are deliberately absent from this materialization.
            // Biomes/protected terrain/heightmaps are now complete. Native INITIALIZE_LIGHT/LIGHT/FULL still run.
            target.setStatus(ChunkStatus.FEATURES);
            target.setUnsaved(true);
            level.onStructureStartsAvailable(target);
            materializedChunks++; bedrockBlocks += image.bedrock;
            published.add(item);
            } catch (Throwable failure) { item.completion.completeExceptionally(failure); }
        }
        if (!published.isEmpty()) {
            publicationBatches++; largestPublicationBatch = Math.max(largestPublicationBatch, published.size());
            for (var item : published) item.completion.complete(true);
        }
        startWaiting(server, deadline);
    }

    private static boolean permitted(ServerLevel level, ProtoChunk chunk, Field field) {
        requireOwner(level);
        if (!field.active.getAsBoolean() || candidate(level, chunk.getPos()) != field
                || !field.permission.test(chunk.getPos()) || chunk.getStatus() != ChunkStatus.EMPTY
                || chunk.getBlendingData() != null || chunk.getBelowZeroRetrogen() != null
                || !chunk.getUpgradeData().isEmpty() || !chunk.getAllStarts().isEmpty()
                || !chunk.getAllReferences().isEmpty() || !chunk.getBlockEntitiesPos().isEmpty()
                || !chunk.getEntities().isEmpty()) return false;
        for (var section : chunk.getSections()) if (!section.hasOnlyAir()) return false;
        return true;
    }

    public static ChunkAccess unwrap(ChunkAccess chunk) {
        return chunk instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : chunk;
    }
    public static boolean isCleared(ChunkAccess chunk) {
        return unwrap(chunk) instanceof NativeAccess.ClearedTerrain marker && marker.vre$isClearedTerrain();
    }
    public static long materializedChunks() { return materializedChunks; }
    public static boolean registered(ServerLevel level, UUID operation) {
        var entries = fields.get(level); return entries != null && entries.containsKey(operation);
    }
    public static long retainedBedrockBlocks() { return bedrockBlocks; }
    public static long estimatedPendingBytes() { return reserved.get() + waiting.size() * 512L + ParallelBedrockBatches.estimatedBytes(); }
    public static long bedrockBatches() { return ParallelBedrockBatches.completedBatches(); }
    public static long reusedBedrockChunks() { return ParallelBedrockBatches.reusedChunks(); }
    public static int maximumParallelBedrockBatches() { return ParallelBedrockBatches.maximumActive(); }
    public static long publicationBatches() { return publicationBatches; }
    public static long largestPublicationBatch() { return largestPublicationBatch; }
    private static void requireOwner(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Cleared generation requires the server owner");
    }
    private record Field(UUID operation, String intent, Volume volume, ClearedTerrainProfile profile,
                         boolean destroyBedrock, BooleanSupplier active, Predicate<ChunkPos> permission,
                         ParallelBedrockBatches bedrock) { }
    private record Key(ServerLevel level, long position) { }
    private record Image(ProtoChunk chunk, int bedrock, boolean exactBiomes) { }
    private record Ready(ServerLevel level, ProtoChunk target, Field field, Image image, Throwable error,
                         CompletableFuture<Boolean> completion) { }
    private record Waiting(ServerLevel level, ProtoChunk target, Field field, Executor owner,
                           CompletableFuture<Boolean> completion) { }
    private ClearedGeneration() { }
}
