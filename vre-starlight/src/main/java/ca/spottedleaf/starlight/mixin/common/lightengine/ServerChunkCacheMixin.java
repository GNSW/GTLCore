// SPDX-License-Identifier: LGPL-3.0-only
package ca.spottedleaf.starlight.mixin.common.lightengine;

import ca.spottedleaf.starlight.common.light.StarLightLightingProvider;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Flush light publication before the final terrain save, not after it during close(). */
@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheMixin {
    @Shadow @Final public ChunkMap chunkMap;
    @Shadow @Final ThreadedLevelLightEngine lightEngine;
    @Shadow public abstract boolean pollTask();

    @Inject(method = "save", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;saveAllChunks(Z)V"))
    private void vre$flushLightBeforeSave(boolean flush, CallbackInfo callback) {
        if (!flush) return;
        var engine = ((StarLightLightingProvider)lightEngine).getLightEngine();
        do {
            var receipt = engine.fenceAll();
            // Light workers post dirty-section notifications and ticket release to
            // this owner. A bare Future.join() here could deadlock those callbacks.
            chunkMap.mainThreadExecutor.managedBlock(receipt::isDone);
            receipt.join(); // Already complete; propagate a failed lighting task.
            while (pollTask()) { }
        } while (engine.hasUpdates());
    }
}
