package org.gtlcore.gtlcore.mixin.mc.worldgen;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

import com.mojang.datafixers.util.Either;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkMap.class)
public abstract class GenerationSaveFenceMixin {

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    protected abstract ChunkHolder getUpdatingChunkIfPresent(long position);

    @Inject(method = "scheduleChunkGeneration", at = @At("RETURN"))
    private void gtlcore$retainGenerationNeighbors(ChunkHolder center, ChunkStatus status,
                                                   CallbackInfoReturnable<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> callback) {
        if (Boolean.getBoolean("gtlcore.disableParallelChunkGeneration") || status != ChunkStatus.FEATURES) return;
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Generation save fences require server thread");
        var future = callback.getReturnValue();
        if (future.isDone()) return;
        int radius = status.getRange();
        ChunkPos pos = center.getPos();
        // The native range future protects only parent readiness. Keep neighbor holders alive
        // through the actual generation result, before any worker is allowed to mutate them.
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                ChunkHolder holder = getUpdatingChunkIfPresent(ChunkPos.asLong(pos.x + dx, pos.z + dz));
                if (holder != null) ((GenerationSaveFenceAccessor) holder).gtlcore$addGenerationSaveDependency(
                        "gtlcore spatial generation", future);
            }
        }
    }
}
