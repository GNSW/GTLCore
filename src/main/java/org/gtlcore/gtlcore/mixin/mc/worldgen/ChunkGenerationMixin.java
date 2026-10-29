package org.gtlcore.gtlcore.mixin.mc.worldgen;

import org.gtlcore.gtlcore.common.worldgen.RegionGeneration;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.util.Either;
import org.spongepowered.asm.mixin.Mixin;
import org.voxelregion.forge.GenerationBridge;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

@Mixin(ChunkStatus.class)
public abstract class ChunkGenerationMixin {

    @WrapMethod(method = "generate")
    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> gtlcore$orderGeneration(
                                                                                                            Executor executor, ServerLevel level, ChunkGenerator generator, StructureTemplateManager templates,
                                                                                                            ThreadedLevelLightEngine lighting,
                                                                                                            Function<ChunkAccess, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> full,
                                                                                                            List<ChunkAccess> chunks,
                                                                                                            Operation<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> original) {
        return RegionGeneration.generate(level, (ChunkStatus) (Object) this, chunks, executor,
                () -> original.call(executor, level, generator, templates, lighting, full, chunks));
    }

    @WrapMethod(method = "load")
    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> gtlcore$orderLoading(
                                                                                                         ServerLevel level, StructureTemplateManager templates, ThreadedLevelLightEngine lighting,
                                                                                                         Function<ChunkAccess, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> full,
                                                                                                         ChunkAccess chunk,
                                                                                                         Operation<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> original) {
        ChunkStatus status = (ChunkStatus) (Object) this;
        if (status != ChunkStatus.STRUCTURE_STARTS && status != ChunkStatus.INITIALIZE_LIGHT && status != ChunkStatus.LIGHT)
            return original.call(level, templates, lighting, full, chunk);
        Executor owner = ((GenerationBridge) level.getChunkSource().chunkMap).vre$generationExecutor();
        return RegionGeneration.generate(level, status, List.of(chunk), owner,
                () -> original.call(level, templates, lighting, full, chunk));
    }
}
