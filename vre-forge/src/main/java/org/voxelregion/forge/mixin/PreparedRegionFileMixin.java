/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.voxelregion.forge.NativeAccess;

@Mixin(RegionFile.class)
abstract class PreparedRegionFileMixin implements NativeAccess.PreparedRegionFile {
    @Shadow @Final RegionFileVersion version;
    @Shadow @Final private FileChannel file;
    @Shadow @Final protected net.minecraft.world.level.chunk.storage.RegionBitmap usedSectors;
    @Shadow private void writeHeader() throws IOException { throw new AssertionError(); }
    @Unique private Path vre$path;
    @Unique private FileChannel vre$buffered;
    @Unique private boolean vre$writing, vre$headerDirty, vre$publishingHeader;
    @Unique private int vre$leases;
    @Unique private boolean vre$closePending;
    @Unique private boolean vre$nativeDirty = true;
    @Unique private final java.util.ArrayList<int[]> vre$deferredFree = new java.util.ArrayList<>();

    @Inject(method = "<init>(Ljava/nio/file/Path;Ljava/nio/file/Path;Lnet/minecraft/world/level/chunk/storage/RegionFileVersion;Z)V", at = @At("RETURN"))
    private void vre$capturePath(Path path, Path external, RegionFileVersion version, boolean sync, CallbackInfo callback) {
        vre$path = path;
    }

    @Override public boolean vre$usesDeflate() { return version == RegionFileVersion.VERSION_DEFLATE; }
    @Override public synchronized void vre$retainFile() { vre$leases++; }
    @Override public synchronized void vre$releaseFile() throws IOException {
        if (vre$leases <= 0) throw new IllegalStateException("Unbalanced region lease");
        if (--vre$leases == 0 && vre$closePending) { vre$closePending = false; ((RegionFile)(Object)this).close(); }
    }
    @Inject(method = "close", remap = false, at = @At("HEAD"), cancellable = true)
    private synchronized void vre$deferLeasedClose(CallbackInfo callback) {
        if (vre$leases > 0) { vre$closePending = true; callback.cancel(); }
    }
    @Shadow protected abstract void write(ChunkPos position, ByteBuffer data) throws IOException;

    @Override public synchronized void vre$writeCompressed(ChunkPos position, ByteBuffer data) throws IOException {
        // External .mcc commits retain the native per-write protocol. Ordinary region images
        // use its allocator/offsets, but synchronize a cohort rather than each small write.
        if (data.remaining() >= 255 * 4096 || vre$path == null) {
            vre$flushBatch(); write(position, data); return;
        }
        if (vre$buffered == null) vre$buffered = FileChannel.open(vre$path, StandardOpenOption.WRITE);
        vre$writing = true;
        try { write(position, data); }
        finally { vre$writing = false; }
    }

    @Inject(method = {"write", "clear"}, at = @At("HEAD"))
    private void vre$beforeOrdinaryWrite(CallbackInfo callback) throws IOException {
        if (!vre$writing) vre$flushBatch();
    }

    @Redirect(method = {"write", "writeHeader"}, at = @At(value = "INVOKE", target = "Ljava/nio/channels/FileChannel;write(Ljava/nio/ByteBuffer;J)I"))
    private int vre$writeBuffered(FileChannel nativeChannel, ByteBuffer data, long offset) throws IOException {
        if (!vre$writing && !vre$publishingHeader) return nativeChannel.write(data, offset);
        int length = data.remaining();
        while (data.hasRemaining()) {
            int written = vre$buffered.write(data, offset);
            if (written <= 0) throw new IOException("Buffered region write made no progress");
            offset += written;
        }
        return length;
    }

    @Inject(method = "writeHeader", at = @At("HEAD"), cancellable = true)
    private void vre$deferHeader(CallbackInfo callback) {
        if (vre$writing && !vre$publishingHeader) { vre$headerDirty = true; callback.cancel(); }
    }

    @Inject(method = "writeHeader", at = @At("RETURN"))
    private void vre$markNativeHeader(CallbackInfo callback) {
        if (!vre$writing && !vre$publishingHeader) vre$nativeDirty = true;
    }

    @Redirect(method = "write", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/storage/RegionBitmap;free(II)V"))
    private void vre$retainOldSectors(net.minecraft.world.level.chunk.storage.RegionBitmap bitmap, int first, int count) {
        // Until the new header is durable, the old disk header must still address intact data.
        if (vre$writing) vre$deferredFree.add(new int[]{first, count}); else bitmap.free(first, count);
    }

    @Unique private synchronized void vre$flushBatch() throws IOException {
        if (!vre$headerDirty) return;
        vre$buffered.force(true); // New data and file length must be durable before publishing its offsets.
        vre$publishingHeader = true;
        try { writeHeader(); }
        finally { vre$publishingHeader = false; }
        vre$buffered.force(true);
        vre$headerDirty = false;
        for (int[] range : vre$deferredFree) usedSectors.free(range[0], range[1]);
        vre$deferredFree.clear();
    }

    @Inject(method = "flush", at = @At("HEAD"), cancellable = true)
    private synchronized void vre$flushPrepared(CallbackInfo callback) throws IOException {
        if (((Object)this).getClass() != RegionFile.class || vre$buffered == null) { vre$flushBatch(); return; }
        vre$flushBatch();
        if (vre$nativeDirty) { file.force(true); vre$nativeDirty = false; }
        callback.cancel();
    }

    @Redirect(method = "close", remap = false, at = @At(value = "INVOKE", target = "Ljava/nio/channels/FileChannel;force(Z)V", remap = false))
    private void vre$flushBeforeClose(FileChannel channel, boolean metadata) throws IOException {
        vre$flushBatch(); channel.force(metadata);
    }

    @Redirect(method = "close", remap = false, at = @At(value = "INVOKE", target = "Ljava/nio/channels/FileChannel;close()V", remap = false))
    private void vre$closePrepared(FileChannel channel) throws IOException {
        try { if (vre$buffered != null) vre$buffered.close(); }
        finally { vre$buffered = null; channel.close(); }
    }
}
