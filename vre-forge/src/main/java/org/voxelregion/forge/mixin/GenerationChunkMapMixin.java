/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import com.mojang.datafixers.util.Either;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.voxelregion.forge.GenerationBridge;
import org.voxelregion.forge.GenerationGate;
import org.voxelregion.forge.ClearedGeneration;
import org.voxelregion.runtime.ScopedReentryPermit;

/** Admission facts from the actual native disk-read and new-ProtoChunk path. */
@Mixin(ChunkMap.class)
public abstract class GenerationChunkMapMixin implements GenerationBridge {
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;
    @Shadow private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;
    @Shadow @Final ServerLevel level;
    @Shadow @Final private BlockableEventLoop<Runnable> mainThreadExecutor;
    @Shadow private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> scheduleChunkGeneration(ChunkHolder holder, ChunkStatus status) { throw new AssertionError(); }
    @Unique private ScopedReentryPermit<ChunkHolder, ChunkStatus> vre$ordinaryGeneration;

    @Override public boolean vre$generationBusy(long position) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Generation holder evidence requires the server thread");
        return updatingChunkMap.containsKey(position) || visibleChunkMap.containsKey(position) || pendingUnloads.containsKey(position);
    }
    @Override public Executor vre$generationExecutor() { return mainThreadExecutor; }
    @Override public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> vre$requestLightRead(ChunkPos position) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Light read requests require the server thread");
        var holder = updatingChunkMap.get(position.toLong());
        if (holder == null || visibleChunkMap.get(position.toLong()) != holder
                || holder.getTicketLevel() > net.minecraft.server.level.ChunkLevel.byStatus(ChunkStatus.FEATURES)) return null;
        // Unlike ServerChunkCache.getChunkFuture, this never runsDistanceManagerUpdates or
        // managedBlock. Existing read-ahead requests share this very same native future.
        return holder.getOrScheduleFuture(ChunkStatus.FEATURES, (ChunkMap)(Object)this);
    }
    @Override public boolean vre$postProcessingReady(net.minecraft.world.level.chunk.LevelChunk chunk) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Generation completion requires the server thread");
        var holder = updatingChunkMap.get(chunk.getPos().toLong());
        if (holder == null) return false;
        var result = holder.getTickingChunkFuture().getNow(null);
        return result != null && result.left().filter(ready -> ready == chunk).isPresent();
    }

    @Inject(method = "readChunk", at = @At("RETURN"), cancellable = true)
    private void vre$observeDiskRead(ChunkPos position,
            CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> callback) {
        callback.setReturnValue(ClearedGeneration.observeRead(level, position,
                GenerationGate.observeNativeRead(level, position, callback.getReturnValue(), mainThreadExecutor), mainThreadExecutor));
    }

    @Inject(method = "createEmptyChunk", at = @At("RETURN"))
    private void vre$bindFreshIdentity(ChunkPos position, CallbackInfoReturnable<ChunkAccess> callback) {
        GenerationGate.observeFreshChunk(level, callback.getReturnValue());
        ClearedGeneration.observeFresh(level, callback.getReturnValue());
    }

    @Inject(method = "scheduleChunkGeneration", at = @At("HEAD"), cancellable = true)
    private void vre$materializeClearedTerrain(ChunkHolder holder, ChunkStatus status,
            CallbackInfoReturnable<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> callback) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Generation admission requires the server thread");
        if (vre$ordinaryGeneration == null) vre$ordinaryGeneration = new ScopedReentryPermit<>();
        // Bypass only this exact call back into the original body. That body
        // schedules neighbor/parent futures synchronously; those still need their
        // own cleared-terrain admission instead of inheriting a global bypass.
        if (vre$ordinaryGeneration.consume(holder, status)) return;
        var replacement = ClearedGeneration.schedule(level, (ChunkMap) (Object) this, holder, status,
                () -> vre$ordinaryGeneration.call(holder, status, () -> scheduleChunkGeneration(holder, status)));
        if (replacement != null) callback.setReturnValue(replacement);
    }
}
