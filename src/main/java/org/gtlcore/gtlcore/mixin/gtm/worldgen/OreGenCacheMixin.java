package org.gtlcore.gtlcore.mixin.gtm.worldgen;

import org.gtlcore.gtlcore.common.worldgen.ParallelOrePlanning;

import com.gregtechceu.gtceu.api.data.worldgen.ores.GeneratedVein;
import com.gregtechceu.gtceu.api.data.worldgen.ores.GeneratedVeinMetadata;
import com.gregtechceu.gtceu.api.data.worldgen.ores.OreGenCache;
import com.gregtechceu.gtceu.api.data.worldgen.ores.OreGenerator;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;

import com.google.common.cache.Cache;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.stream.Stream;

@Mixin(value = OreGenCache.class, remap = false)
public abstract class OreGenCacheMixin {

    @Shadow
    @Final
    private OreGenerator oreGenerator;
    @Shadow
    @Final
    private Cache<ChunkPos, List<GeneratedVein>> generatedVeinsByOrigin;

    @Shadow
    private Stream<ChunkPos> getSurroundingChunks(ChunkPos center, int distance) {
        throw new AssertionError();
    }

    @Shadow
    private List<GeneratedVeinMetadata> getOrCreateVeinMetadata(WorldGenLevel level, ChunkGenerator generator, ChunkPos origin) {
        throw new AssertionError();
    }

    @Shadow
    private Stream<GeneratedVein> lambda$consumeChunkVeins$2(WorldGenLevel level, ChunkGenerator generator, ChunkPos origin) {
        throw new AssertionError();
    }

    @WrapMethod(method = "consumeChunkVeins")
    private List<GeneratedVein> gtlcore$prepareIndependentOrigins(WorldGenLevel level, ChunkGenerator generator,
                                                                  ChunkAccess chunk, Operation<List<GeneratedVein>> original) {
        if (!ParallelOrePlanning.enabled(level)) return original.call(level, generator, chunk);
        List<ChunkPos> origins = getSurroundingChunks(chunk.getPos(), OreVeinUtilAccessor.gtlcore$maximumSearchDistance()).toList();
        return ParallelOrePlanning.consume(level, oreGenerator, origins,
                origin -> getOrCreateVeinMetadata(level, generator, origin), generatedVeinsByOrigin,
                origin -> lambda$consumeChunkVeins$2(level, generator, origin).filter(java.util.Objects::nonNull).toList());
    }
}
