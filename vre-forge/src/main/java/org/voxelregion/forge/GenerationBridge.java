/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.concurrent.Executor;

/** Internal ChunkMap bridge; queries require its owning server thread. */
public interface GenerationBridge {
    boolean vre$generationBusy(long position);
    Executor vre$generationExecutor();
    /** Nonblocking owner-thread check; propagates an exceptional native post-processing future. */
    boolean vre$postProcessingReady(net.minecraft.world.level.chunk.LevelChunk chunk);
    /** Poll after adding a FEATURES read ticket; null means native ticket propagation is pending. */
    java.util.concurrent.CompletableFuture<com.mojang.datafixers.util.Either<
            net.minecraft.world.level.chunk.ChunkAccess, net.minecraft.server.level.ChunkHolder.ChunkLoadingFailure>>
            vre$requestLightRead(net.minecraft.world.level.ChunkPos position);
}
