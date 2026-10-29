/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import org.voxelregion.geometry.Volume;
import org.voxelregion.planner.GenerationSafety;
import org.voxelregion.propagation.FrozenOcclusion;
import org.voxelregion.spatial.ChunkKey;

/**
 * Bounded admission and a native NOISE-stage shortcut for proven flat generation.
 * Register before requesting the normal FULL chunk. No ticket, fake FULL status
 * or replacement LevelChunk is created here. The existing ChunkStatus pipeline
 * continues through surface, features, light and native promotion.
 *
 * <p>NOISE terrain creation is a real commit point. A main-thread permission
 * recheck and the retained-layer writes happen in one callback on ChunkMap's
 * own executor. Revocation before that callback causes ordinary generation.
 * Revocation after that commit can stop subsequent mutations but cannot undo or
 * conceal the already authorized terrain creation. The host must prepare its
 * durable intent before registration, record this outcome, recheck later writes,
 * and await native lighting/save fences before reporting completion.
 */
public final class GenerationGate {
    public static final int MAX_PENDING = 64;
    private static final Map<Key, Registration> pending = new ConcurrentHashMap<>();
    private static final Map<ChunkAccess, FrozenPermit> fresh = new ConcurrentHashMap<>();

    public enum Outcome { APPLIED_SAFE_FLAT, NATIVE_FALLBACK, CANCELLED }
    public record Result(Outcome outcome, long preservedBlockWrites, long skippedBlockWrites, String reason) { }

    /**
     * Evaluated on the server thread at admission and again immediately before
     * NOISE commit; changing any retained layer causes native fallback. The
     * worker receives only frozen layer states. The host's CommitCheck must also
     * validate policy changes that the per-state predicate cannot represent.
     */
    public record Rules(boolean staticProtection, boolean clearFluids, long revision, Predicate<BlockState> mayDestroy) {
        public Rules { Objects.requireNonNull(mayDestroy, "mayDestroy"); }
    }

    /** Must recheck current claims, rule revision, operation state and cancellation. */
    @FunctionalInterface public interface CommitCheck { boolean allow(); }

    private GenerationGate() { }

    public static CompletableFuture<Registration> register(ServerLevel level, UUID operationId, String intentHash,
            ChunkKey chunk, Volume volume, FrozenOcclusion.Mode propagation, Rules rules, CommitCheck recheck) {
        requireOwner(level);
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(intentHash, "intentHash");
        Objects.requireNonNull(recheck, "recheck");
        if (!intentHash.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Expected SHA-256 intent hash");
        GenerationSafetyOracle.Candidate candidate = GenerationSafetyOracle.inspect(level, chunk, volume, propagation, rules);
        Registration registration = new Registration(level, operationId, intentHash, chunk, rules, recheck, candidate);
        if (candidate.safety().decision() != GenerationSafety.Decision.SAFE) {
            registration.fallback(candidate.safety().evidence());
            return CompletableFuture.completedFuture(registration);
        }
        if (!(level.getChunkSource().chunkMap instanceof GenerationBridge bridge)) {
            registration.reject(GenerationSafety.Decision.UNKNOWN, "Generation bridge is unavailable");
            return CompletableFuture.completedFuture(registration);
        }
        Key key = registration.key;
        if (bridge.vre$generationBusy(key.position) || pending.containsKey(key)) {
            registration.reject(GenerationSafety.Decision.REQUIRES_GENERATION, "Chunk is loaded, generating, unloading or already reserved");
            return CompletableFuture.completedFuture(registration);
        }
        if (pending.size() >= MAX_PENDING) {
            registration.reject(GenerationSafety.Decision.UNKNOWN, "Generation permit capacity reached");
            return CompletableFuture.completedFuture(registration);
        }
        if (!(level.getChunkSource().chunkMap.chunkScanner() instanceof IOWorker storage)) {
            registration.reject(GenerationSafety.Decision.UNKNOWN, "Native storage cannot prove absence without generation");
            return CompletableFuture.completedFuture(registration);
        }
        // Reserve immediately while the asynchronous disk query is in flight.
        pending.put(key, registration);
        CompletableFuture<Registration> admission;
        try {
            admission = storage.loadAsync(new ChunkPos(chunk.x(), chunk.z())).handleAsync((data, failure) -> {
                requireOwner(level);
                if (failure != null) registration.reject(GenerationSafety.Decision.UNKNOWN, "Chunk absence read failed");
                else if (registration.cancelled) registration.close();
                else if (data.isPresent()) registration.reject(GenerationSafety.Decision.REQUIRES_GENERATION, "An on-disk chunk already exists");
                else if (bridge.vre$generationBusy(key.position)) registration.reject(GenerationSafety.Decision.REQUIRES_GENERATION, "Chunk generation started before admission completed");
                else if (!GenerationSafetyOracle.stillMatches(level, candidate)) registration.reject(GenerationSafety.Decision.UNKNOWN, "Flat generator profile changed before admission");
                else registration.admitted = true;
                return registration;
            }, bridge.vre$generationExecutor());
        } catch (RuntimeException | LinkageError failure) {
            registration.fail(failure);
            return CompletableFuture.failedFuture(failure);
        }
        // Cleanup is attached to the actual admission stage, independently of
        // the future returned to hosts. In particular thenAccept(close) alone
        // cannot release a reservation after an exceptional completion.
        admission.whenComplete((ignored, failure) -> {
            if (failure != null) registration.fail(failure);
        });
        return admission;
    }

    /** Native ChunkMap.readChunk hook: only an actual successful absent read permits binding. */
    public static CompletableFuture<Optional<CompoundTag>> observeNativeRead(ServerLevel level, ChunkPos position,
            CompletableFuture<Optional<CompoundTag>> original, Executor ownerExecutor) {
        Registration registration = pending.get(new Key(level, position.toLong()));
        if (registration == null) return original;
        return original.handleAsync((tag, failure) -> {
            requireOwner(level);
            registration.nativeReadAbsent = failure == null && tag.isEmpty();
            if (failure != null) {
                registration.reject(GenerationSafety.Decision.UNKNOWN, "Native chunk read failed before fresh-chunk binding");
                throw new CompletionException(failure);
            }
            if (tag.isPresent()) registration.reject(GenerationSafety.Decision.REQUIRES_GENERATION, "Native load found preexisting chunk data");
            return tag;
        }, ownerExecutor);
    }

    /** Called only for a new object returned by native ChunkMap.createEmptyChunk. */
    public static void observeFreshChunk(ServerLevel level, ChunkAccess chunk) {
        requireOwner(level);
        Registration registration = pending.get(new Key(level, chunk.getPos().toLong()));
        if (registration == null) return;
        if (!registration.admitted || !registration.nativeReadAbsent || registration.cancelled
                || chunk.getClass() != ProtoChunk.class || chunk.getStatus() != ChunkStatus.EMPTY) {
            registration.reject(GenerationSafety.Decision.UNKNOWN, "Fresh native chunk identity or absence evidence is incomplete");
            return;
        }
        FrozenPermit permit = new FrozenPermit(registration, registration.candidate, registration.candidate.generator(),
                ((GenerationBridge) level.getChunkSource().chunkMap).vre$generationExecutor());
        if (registration.bound != null) throw new IllegalStateException("Generation permit cannot be reused for another chunk instance");
        registration.bound = chunk;
        fresh.put(chunk, permit);
    }

    /**
     * Flat fillFromNoise hook. A worker only consumes the immutable permit and
     * schedules a main-thread commit; it never reads claims, the world or FTB.
     * Null means execute the original fillFromNoise immediately.
     */
    public static CompletableFuture<ChunkAccess> tryFill(FlatLevelSource generator, ChunkAccess chunk,
            Supplier<CompletableFuture<ChunkAccess>> nativeFallback) {
        FrozenPermit permit = fresh.remove(chunk);
        if (permit == null) return null;
        CompletableFuture<ChunkAccess> result = new CompletableFuture<>();
        permit.ownerExecutor.execute(() -> {
            Registration registration = permit.registration;
            try {
                requireOwner(registration.level);
                boolean allowed = !registration.cancelled && generator == permit.generator
                        && validFreshNoiseChunk(chunk, permit.candidate);
                if (allowed) {
                    try { allowed = registration.recheck.allow(); }
                    catch (RuntimeException unknownPermission) { allowed = false; }
                }
                allowed = allowed && !registration.cancelled
                        && GenerationSafetyOracle.stillMatches(registration.level, permit.candidate)
                        && GenerationSafetyOracle.insideStableBorder(registration.level, chunk.getPos().x, chunk.getPos().z)
                        && GenerationSafetyOracle.protectionStillMatches(permit.candidate, registration.rules);
                if (!allowed) {
                    registration.fallback("Permit cancelled, evidence changed or permission revoked before NOISE commit");
                    nativeFallback.get().whenComplete((value, failure) -> complete(result, value, failure));
                    return;
                }
                long preserved = fillRetained(chunk, permit.candidate);
                long nativeWrites = permit.candidate.original().stream().filter(state -> !state.isAir()).count() * 256L;
                registration.applied = true;
                registration.finish(new Result(Outcome.APPLIED_SAFE_FLAT, preserved,
                        Math.max(0, nativeWrites - preserved), "Authorized retained flat layers committed at native NOISE stage"));
                result.complete(chunk);
            } catch (Throwable failure) {
                // A partial native write is uncertain: never conceal it by claiming
                // fallback or completion. Let the generation future fail visibly.
                registration.remove();
                registration.result.completeExceptionally(failure);
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private static boolean validFreshNoiseChunk(ChunkAccess chunk, GenerationSafetyOracle.Candidate candidate) {
        if (!(chunk instanceof ProtoChunk proto) || chunk instanceof ImposterProtoChunk
                || chunk.getStatus() != ChunkStatus.BIOMES || chunk.isUpgrading()
                || proto.getBelowZeroRetrogen() != null || chunk.getBlendingData() != null
                || !chunk.getUpgradeData().isEmpty() || chunk.getMinBuildHeight() != candidate.minY()
                || chunk.getHeight() != candidate.height() || !chunk.getAllStarts().isEmpty()
                || !chunk.getAllReferences().isEmpty() || !chunk.getBlockEntitiesPos().isEmpty()
                || !proto.getEntities().isEmpty()) return false;
        for (var section : chunk.getSections()) if (!section.hasOnlyAir()) return false;
        return true;
    }

    private static long fillRetained(ChunkAccess chunk, GenerationSafetyOracle.Candidate candidate) {
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        Heightmap ocean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        long written = 0;
        for (int layer = 0; layer < candidate.retained().size(); layer++) {
            BlockState state = candidate.retained().get(layer);
            if (state.isAir()) continue;
            int y = candidate.minY() + layer;
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                chunk.setBlockState(position.set(x, y, z), state, false);
                ocean.update(x, y, z, state);
                surface.update(x, y, z, state);
                written++;
            }
        }
        chunk.setUnsaved(true);
        return written;
    }

    private static void complete(CompletableFuture<ChunkAccess> result, ChunkAccess value, Throwable failure) {
        if (failure == null) result.complete(value); else result.completeExceptionally(failure);
    }

    public static int pendingCount() { return pending.size(); }

    public static void stop(ServerLevel level) {
        requireOwner(level);
        for (Registration registration : pending.values()) if (registration.level == level) registration.close();
    }

    private static void requireOwner(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Generation commits require the server thread");
    }

    public static final class Registration implements AutoCloseable {
        private final ServerLevel level;
        private final UUID operationId;
        private final String intentHash;
        private final long protectionRevision;
        private final Rules rules;
        private final CommitCheck recheck;
        private final GenerationSafetyOracle.Candidate candidate;
        private final Key key;
        private final CompletableFuture<Result> result = new CompletableFuture<>();
        private GenerationSafety safety;
        private boolean admitted, nativeReadAbsent, cancelled;
        private volatile boolean applied;
        private ChunkAccess bound;

        private Registration(ServerLevel level, UUID operationId, String intentHash, ChunkKey chunk,
                Rules rules, CommitCheck recheck, GenerationSafetyOracle.Candidate candidate) {
            this.level = level;
            this.operationId = operationId;
            this.intentHash = intentHash;
            this.protectionRevision = rules.revision();
            this.rules = rules;
            this.recheck = recheck;
            this.candidate = candidate;
            this.safety = candidate.safety();
            this.key = new Key(level, ChunkPos.asLong(chunk.x(), chunk.z()));
        }

        public UUID operationId() { return operationId; }
        public String intentHash() { return intentHash; }
        public long protectionRevision() { return protectionRevision; }
        public GenerationSafety safety() { return safety; }
        public CompletableFuture<Result> result() { return result; }
        public boolean applied() { return applied; }

        private void reject(GenerationSafety.Decision decision, String reason) {
            safety = new GenerationSafety(decision, reason);
            fallback(reason);
        }
        private void fallback(String reason) { finish(new Result(Outcome.NATIVE_FALLBACK, 0, 0, reason)); }
        private void finish(Result value) { remove(); result.complete(value); }
        private void fail(Throwable failure) { remove(); result.completeExceptionally(failure); }
        private void remove() {
            pending.remove(key, this);
            if (bound != null) { fresh.remove(bound); bound = null; }
        }

        /** Cancels only uncommitted admission; an applied NOISE commit is never rolled back. */
        @Override public void close() {
            requireOwner(level);
            cancelled = true;
            remove();
            if (!result.isDone()) result.complete(new Result(Outcome.CANCELLED, 0, 0, "Permit cancelled before NOISE commit"));
        }
    }

    private record Key(ServerLevel level, long position) { }
    private record FrozenPermit(Registration registration, GenerationSafetyOracle.Candidate candidate,
                                FlatLevelSource generator, Executor ownerExecutor) { }
}
