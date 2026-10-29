/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.voxelregion.forge.NativeSaveTracker;

@Mixin(IOWorker.class)
abstract class NativeStoreMixin implements org.voxelregion.forge.NativeAccess.DurableStorage {
    @Shadow @Final private java.util.Map<ChunkPos, ?> pendingWrites;
    @Shadow @Final private net.minecraft.util.thread.ProcessorMailbox<net.minecraft.util.thread.StrictQueue.IntRunnable> mailbox;
    @Shadow @Final private net.minecraft.world.level.chunk.storage.RegionFileStorage storage;
    @Shadow @Final private java.util.concurrent.atomic.AtomicBoolean shutdownRequested;
    @Shadow private void storePendingChunk() { throw new AssertionError(); }
    @Unique private final java.util.concurrent.atomic.AtomicInteger vre$outstanding = new java.util.concurrent.atomic.AtomicInteger();
    @Unique private boolean vre$draining;
    @Unique private CompletableFuture<Void> vre$queuedFlush;
    @Unique private final java.util.Set<ChunkPos> vre$queuedFlushPositions = new java.util.LinkedHashSet<>();
    @Unique private final java.util.Set<CompletableFuture<Void>> vre$flushRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Inject(method = "runStore", at = @At("HEAD"), cancellable = true)
    private void vre$parallelRegion(ChunkPos position, @org.spongepowered.asm.mixin.injection.Coerce Object pending, CallbackInfo callback) {
        var write = (org.voxelregion.forge.NativeAccess.PendingStore)pending;
        var queues = org.voxelregion.forge.ParallelRegionStores.find(storage);
        if (queues == null) {
            if (write.vre$data() == null || org.voxelregion.forge.ParallelChunkCompression.find(write.vre$data()) == null) return;
            queues = org.voxelregion.forge.ParallelRegionStores.create(storage,
                    task -> mailbox.tell(new net.minecraft.util.thread.StrictQueue.IntRunnable(0, task)));
        }
        if (queues.store(position, write.vre$data(), write.vre$result())) callback.cancel();
    }

    @Inject(method = "synchronize", at = @At("RETURN"), cancellable = true)
    private void vre$awaitDispatched(boolean flush, CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        callback.setReturnValue(callback.getReturnValue().thenCompose(ignored -> {
            var queues = org.voxelregion.forge.ParallelRegionStores.find(storage);
            return queues == null ? CompletableFuture.completedFuture(null) : queues.afterNativeBarrier(flush);
        }));
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "close", remap = false,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/ProcessorMailbox;close()V", remap = false))
    private void vre$drainBeforeMailboxClose(net.minecraft.util.thread.ProcessorMailbox<?> target) {
        CompletableFuture<?>[] flushing;
        synchronized (this) { flushing = vre$flushRequests.toArray(CompletableFuture[]::new); }
        // Native close calls this on its caller thread after SHUTDOWN admission,
        // while the mailbox still accepts force-completion and lease-release work.
        CompletableFuture.allOf(flushing).handle((ignored, error) -> null).join();
        var queues = org.voxelregion.forge.ParallelRegionStores.find(storage);
        if (queues != null) queues.drainBeforeClose();
        target.close();
    }

    @Override public synchronized CompletableFuture<Void> vre$flushStored(java.util.Collection<ChunkPos> positions) {
        if (shutdownRequested.get()) return CompletableFuture.failedFuture(new IllegalStateException("Chunk storage is closing"));
        if (positions.isEmpty()) return CompletableFuture.completedFuture(null);
        vre$queuedFlushPositions.addAll(positions);
        if (vre$queuedFlush != null) return vre$queuedFlush;
        var completion = new CompletableFuture<Void>();
        vre$flushRequests.add(completion);
        completion.whenComplete((ignored, error) -> vre$flushRequests.remove(completion));
        vre$queuedFlush = completion;
        // FOREGROUND is priority zero in this version. The native mailbox owns the file
        // cache and flush admission; only touched files flush on workers. Actual store
        // futures have already completed before this call. Never wait for worker force
        // inside this mailbox: reads, new stores and flush lease releases must continue.
        mailbox.tell(new net.minecraft.util.thread.StrictQueue.IntRunnable(0, () -> {
            java.util.List<ChunkPos> touched;
            synchronized (this) {
                touched = java.util.List.copyOf(vre$queuedFlushPositions);
                vre$queuedFlushPositions.clear(); vre$queuedFlush = null;
            }
            try {
                var queues = org.voxelregion.forge.ParallelRegionStores.create(storage,
                        task -> mailbox.tell(new net.minecraft.util.thread.StrictQueue.IntRunnable(0, task)));
                queues.flushStored(touched).whenComplete((ignored, error) -> {
                    if (error == null) completion.complete(null); else completion.completeExceptionally(error);
                });
            }
            catch (Throwable error) { completion.completeExceptionally(error); }
        }));
        return completion;
    }

    /** Native foreground reads otherwise indefinitely outrank the background save queue. */
    @Inject(method = "tellStorePending", at = @At("HEAD"), cancellable = true)
    private void vre$fairWriteBatch(CallbackInfo callback) {
        if (vre$draining) { callback.cancel(); return; }
        if (vre$outstanding.get() == 0 || pendingWrites.isEmpty()) return;
        // This runs on this IOWorker's mailbox, never the world owner. Reuse native FIFO
        // removal, write/error completion and coalescing; only change scheduling granularity.
        vre$draining = true;
        try {
            long deadline = System.nanoTime() + 2_000_000L;
            for (int i = 0; i < 32 && !pendingWrites.isEmpty() && System.nanoTime() < deadline; i++)
                storePendingChunk();
        } finally { vre$draining = false; }
        if (pendingWrites.isEmpty()) callback.cancel();
    }
    @Inject(method = "store", at = @At("HEAD"))
    private void vre$compressDetached(ChunkPos position, CompoundTag data,
            CallbackInfoReturnable<CompletableFuture<Void>> result) {
        if (!NativeSaveTracker.capturing()) return;
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null && server.isSameThread())
            org.voxelregion.forge.ParallelChunkCompression.prepare(data,
                    org.voxelregion.forge.runtime.RuntimeBridge.availableMetadataBytes(server));
    }
    @Inject(method = "store", at = @At("RETURN"))
    private void vre$observeStore(ChunkPos position, CompoundTag data,
                                 CallbackInfoReturnable<CompletableFuture<Void>> result) {
        if (NativeSaveTracker.capturing()) {
            vre$outstanding.incrementAndGet();
            result.getReturnValue().whenComplete((ignored, error) -> vre$outstanding.decrementAndGet());
        }
        NativeSaveTracker.stored((IOWorker) (Object) this, position, result.getReturnValue());
        org.voxelregion.forge.ParallelChunkCompression.stored(data, result.getReturnValue());
    }
}
