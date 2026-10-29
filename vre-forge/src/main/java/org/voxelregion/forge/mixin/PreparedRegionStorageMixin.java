/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import java.io.IOException;
import java.nio.ByteBuffer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.voxelregion.forge.NativeAccess;
import org.voxelregion.forge.ParallelChunkCompression;

@Mixin(RegionFileStorage.class)
abstract class PreparedRegionStorageMixin implements NativeAccess.RegionFiles {
    @Shadow @Final private it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<RegionFile> regionCache;
    @Shadow private RegionFile getRegionFile(ChunkPos position) throws IOException { throw new AssertionError(); }
    @Override public RegionFile vre$regionFile(ChunkPos position) throws IOException { return getRegionFile(position); }

    @Redirect(method = "write", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/RegionFile;clear(Lnet/minecraft/world/level/ChunkPos;)V"))
    private void vre$serializeNativeDelete(RegionFile file, ChunkPos position) throws IOException {
        // Native no-lane deletes must share the exact same file monitor as detached
        // forces and compressed writes; RegionFile.clear itself is not synchronized.
        synchronized (file) { file.clear(position); }
    }

    @Inject(method = "getRegionFile", at = @At("HEAD"), cancellable = true)
    private void vre$reuseLeased(ChunkPos position, CallbackInfoReturnable<RegionFile> callback) {
        var queue = org.voxelregion.forge.ParallelRegionStores.find((RegionFileStorage)(Object)this);
        RegionFile file = queue == null ? null : queue.leasedFile(position);
        if (file != null) callback.setReturnValue(file);
    }
    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private void vre$readInFlight(ChunkPos position, CallbackInfoReturnable<CompoundTag> callback) {
        var queue = org.voxelregion.forge.ParallelRegionStores.find((RegionFileStorage)(Object)this);
        if (queue != null && queue.hasLatest(position)) callback.setReturnValue(queue.latest(position));
    }
    @Inject(method = "scanChunk", at = @At("HEAD"), cancellable = true)
    private void vre$scanInFlight(ChunkPos position, net.minecraft.nbt.StreamTagVisitor visitor, CallbackInfo callback) {
        var queue = org.voxelregion.forge.ParallelRegionStores.find((RegionFileStorage)(Object)this);
        if (queue != null && queue.hasLatest(position)) {
            var data = queue.latest(position); if (data != null) data.acceptAsRoot(visitor); callback.cancel();
        }
    }

    @Inject(method = "flush", at = @At("HEAD"), cancellable = true)
    private void vre$parallelFlush(CallbackInfo callback) throws IOException {
        if (org.voxelregion.forge.ParallelRegionStores.find((RegionFileStorage)(Object)this) == null) return;
        var files = java.util.List.copyOf(regionCache.values());
        if (files.stream().anyMatch(file -> file.getClass() != RegionFile.class || !(file instanceof NativeAccess.PreparedRegionFile))) return;
        for (var file : files) ((NativeAccess.PreparedRegionFile)file).vre$retainFile();
        Throwable failure = null;
        try {
            java.util.concurrent.CompletableFuture.allOf(files.stream().map(org.voxelregion.forge.ParallelRegionStores::flush)
                    .toArray(java.util.concurrent.CompletableFuture[]::new)).join();
        } catch (java.util.concurrent.CompletionException error) { failure = error.getCause(); }
        finally {
            for (var file : files) try { ((NativeAccess.PreparedRegionFile)file).vre$releaseFile(); }
            catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        if (failure instanceof IOException error) throw error;
        if (failure != null) throw new IOException("Parallel region flush failed", failure);
        callback.cancel();
    }
    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void vre$writePrepared(ChunkPos position, CompoundTag data, CallbackInfo callback) throws IOException {
        if (data == null) return;
        var prepared = ParallelChunkCompression.find(data);
        if (prepared == null) return;
        RegionFile file = getRegionFile(position);
        if (file.getClass() != RegionFile.class || !(file instanceof NativeAccess.PreparedRegionFile target) || !target.vre$usesDeflate()) return;
        try { target.vre$writeCompressed(position, ByteBuffer.wrap(prepared.join())); }
        catch (java.util.concurrent.CompletionException error) { throw new IOException("Detached chunk compression failed", error.getCause()); }
        callback.cancel();
    }
}
