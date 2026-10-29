package org.gtlcore.gtlcore.mixin.gtm.worldgen;

import org.gtlcore.gtlcore.common.worldgen.OrderedOreGrouping;
import org.gtlcore.gtlcore.common.worldgen.WorldgenSharedState;

import com.gregtechceu.gtceu.api.data.worldgen.GTOreDefinition;
import com.gregtechceu.gtceu.api.data.worldgen.WorldGeneratorUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;
import java.util.Map;

@Mixin(value = WorldGeneratorUtils.class, remap = false)
public abstract class WorldGeneratorUtilsMixin {

    @WrapMethod(method = "getCachedBiomeVeins", remap = false)
    private static List<Map.Entry<Integer, GTOreDefinition>> gtlcore$publishLegacyCache(
                                                                                        ServerLevel level, Holder<Biome> biome, RandomSource random,
                                                                                        Operation<List<Map.Entry<Integer, GTOreDefinition>>> original) {
        return WorldgenSharedState.cachedBiomeVeins(level, () -> original.call(level, biome, random));
    }

    @WrapMethod(method = "groupByChunks(Ljava/util/Map;)Ljava/util/Map;", remap = false)
    private static <T> Map<ChunkPos, Map<BlockPos, T>> gtlcore$reuseChunkKeys(Map<BlockPos, T> source,
                                                                              Operation<Map<ChunkPos, Map<BlockPos, T>>> original) {
        if (Boolean.getBoolean(OrderedOreGrouping.DISABLE_PROPERTY)) return original.call(source);
        return OrderedOreGrouping.groupByChunks(source);
    }
}
