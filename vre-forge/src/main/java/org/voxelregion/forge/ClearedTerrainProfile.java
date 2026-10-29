/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.BitSet;
import java.util.List;
import net.minecraft.data.worldgen.SurfaceRuleData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * A terrain algorithm selected before generation, not a block filter after generation.
 * Only never-generated, completely covered chunks may use this contract. Other generated
 * terrain/features are intentionally omitted; existing chunk contents are never inputs here.
 */
public final class ClearedTerrainProfile {
    public enum Algorithm { EMPTY, FLAT_BEDROCK, NOISE_BEDROCK }
    private final ChunkGenerator generator;
    private final Algorithm algorithm;
    private final int minY, height;
    private final boolean floor, roof;
    private final List<BlockState> flatLayers;
    private volatile CompiledBiomeColumns biomes;
    private volatile boolean biomesCompiled;

    private ClearedTerrainProfile(ChunkGenerator generator, Algorithm algorithm, int minY, int height,
            boolean floor, boolean roof, List<BlockState> flatLayers) {
        this.generator = generator; this.algorithm = algorithm; this.minY = minY; this.height = height;
        this.floor = floor; this.roof = roof; this.flatLayers = flatLayers;
    }

    /** Null requires ordinary generation. Mod presence alone does not disable a known generator. */
    public static ClearedTerrainProfile select(ChunkGenerator generator, LevelHeightAccessor height,
            boolean clearFluids, boolean destroyBedrock) {
        if (!clearFluids || height.getHeight() <= 0 || height.getHeight() > 4096) return null;
        boolean flat = generator.getClass() == FlatLevelSource.class;
        boolean noise = generator.getClass() == NoiseBasedChunkGenerator.class;
        if (destroyBedrock) return new ClearedTerrainProfile(generator, Algorithm.EMPTY,
                height.getMinBuildHeight(), height.getHeight(), false, false, List.of());
        if (!flat && !noise) return null;
        if (flat) {
            // Deferred fluid layers are irrelevant when clearFluids=true; retain the actual bedrock layers.
            var settings = ((FlatLevelSource) generator).settings();
            var layers = new java.util.ArrayList<BlockState>();
            for (var layer : settings.getLayers())
                layers.add(layer != null && layer.is(Blocks.BEDROCK) ? layer : Blocks.AIR.defaultBlockState());
            return new ClearedTerrainProfile(generator, Algorithm.FLAT_BEDROCK,
                    height.getMinBuildHeight(), height.getHeight(), true, false, List.copyOf(layers));
        }
        var settings = ((NoiseBasedChunkGenerator) generator).generatorSettings().value();
        var rule = settings.surfaceRule();
        // Match the actual rules, not the registry name (data packs can replace that name).
        boolean overworld = rule.equals(SurfaceRuleData.overworld());
        boolean nether = rule.equals(SurfaceRuleData.nether());
        boolean end = rule.equals(SurfaceRuleData.end());
        if (!overworld && !nether && !end) return null;
        var noiseSettings = settings.noiseSettings().clampToHeightAccessor(height);
        if (noiseSettings.height() < 16 || (noiseSettings.minY() & 15) != 0
                || (noiseSettings.height() & 15) != 0) return null;
        return new ClearedTerrainProfile(generator, end ? Algorithm.EMPTY : Algorithm.NOISE_BEDROCK,
                noiseSettings.minY(), noiseSettings.height(), !end, nether, List.of());
    }

    public Algorithm algorithm() { return algorithm; }
    public ChunkGenerator generator() { return generator; }

    public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> sampleColumnBiome(
            ChunkPos position, LevelHeightAccessor height, RandomState random) {
        int y = Math.max(height.getMinBuildHeight(), Math.min(height.getMaxBuildHeight() - 1, generator.getSeaLevel())) >> 2;
        return generator.getBiomeSource().getNoiseBiome((position.getMinBlockX() + 8) >> 2, y,
                (position.getMinBlockZ() + 8) >> 2, random.sampler());
    }

    private CompiledBiomeColumns compiledBiomes() {
        if (!biomesCompiled) synchronized (this) {
            if (!biomesCompiled) {
                biomes = CompiledBiomeColumns.create(generator.getBiomeSource());
                biomesCompiled = true;
            }
        }
        return biomes;
    }

    /** Reuse the generator's horizontal climate caches across all vertical samples in this image. */
    public void fillBiomes(net.minecraft.world.level.chunk.ProtoChunk image, RandomState random) {
        fillBiomes(image, random, true);
    }

    public void fillBiomes(net.minecraft.world.level.chunk.ProtoChunk image, RandomState random, boolean exact) {
        if (!exact) {
            // New, deliberately empty terrain has no original biome image to preserve.
            // Sample the native source once near sea level; exact mode retains every quart
            // sample, including underground biome bands. Existing chunk biomes never enter here.
            int x = (image.getPos().getMinBlockX() + 8) >> 2;
            int z = (image.getPos().getMinBlockZ() + 8) >> 2;
            var sampler = random.sampler();
            var biome = sampleColumnBiome(image.getPos(), image, random);
            for (int i = 0; i < image.getSections().length; i++) {
                int y = ((image.getSectionYFromSectionIndex(i) << 4) + 8) >> 2;
                image.getSection(i).fillBiomesFromNoise((qx, qy, qz, ignored) -> biome,
                        sampler, x & ~3, y & ~3, z & ~3);
            }
            return;
        }
        var compiled = compiledBiomes();
        image.fillBiomesFromNoise(compiled == null ? generator.getBiomeSource() : compiled.resolver(), climateSampler(image, random));
    }

    private net.minecraft.world.level.biome.Climate.Sampler climateSampler(
            net.minecraft.world.level.chunk.ProtoChunk image, RandomState random) {
        if (generator instanceof NoiseBasedChunkGenerator noise) {
            var settings = noise.generatorSettings().value();
            var cells = settings.noiseSettings().clampToHeightAccessor(image);
            var pos = image.getPos();
            NoiseChunk cache;
            try (var hashes = NoiseHashMemo.open()) {
                cache = new NoiseChunk(16 / cells.getCellWidth(), random, pos.getMinBlockX(), pos.getMinBlockZ(), cells,
                        new Beardifier(it.unimi.dsi.fastutil.objects.ObjectLists.<Beardifier.Rigid>emptyList().listIterator(),
                                it.unimi.dsi.fastutil.objects.ObjectLists.<net.minecraft.world.level.levelgen.structure.pools.JigsawJunction>emptyList().listIterator()),
                        settings, ((NativeAccess.NoiseFluids) noise).vre$fluidPicker(), Blender.empty());
            }
            return ((NativeAccess.NoiseSamples) cache).vre$climateSampler(random.router(), settings.spawnTarget());
        }
        return random.sampler();
    }

    /** Detached mask: bit index is (worldY - buildMinY) * 256 + z * 16 + x. */
    public BitSet retainedBedrock(ChunkPos chunk, LevelHeightAccessor worldHeight, RandomState random) {
        BitSet retained = new BitSet();
        if (algorithm == Algorithm.EMPTY) return retained;
        if (algorithm == Algorithm.FLAT_BEDROCK) {
            for (int i = 0; i < Math.min(height, flatLayers.size()); i++)
                if (flatLayers.get(i).is(Blocks.BEDROCK)) retained.set(i * 256, (i + 1) * 256);
            return retained;
        }
        var noise = (NoiseBasedChunkGenerator) generator;
        // Bedrock is a surface rule applied only to default terrain. Sampling its two small
        // bands preserves density/aquifer holes; a random five-layer blanket would be wrong.
        if (floor) sampleBand(noise, random, chunk, 1, worldHeight, minY, false, new BitSet[]{retained});
        if (roof) sampleBand(noise, random, chunk, 1, worldHeight, minY + height - 16, true, new BitSet[]{retained});
        return retained;
    }

    /** Row-major detached masks, sharing one native lattice per band across adjacent chunks. */
    public List<BitSet> retainedBedrockSquare(ChunkPos minimum, int side, LevelHeightAccessor worldHeight, RandomState random) {
        if (side < 1 || side > 4) throw new IllegalArgumentException("Bedrock batch side");
        if (side == 1) return List.of(retainedBedrock(minimum, worldHeight, random));
        BitSet[] masks = new BitSet[side * side];
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++)
            masks[z * side + x] = algorithm == Algorithm.NOISE_BEDROCK ? new BitSet()
                    : retainedBedrock(new ChunkPos(minimum.x + x, minimum.z + z), worldHeight, random);
        if (algorithm == Algorithm.NOISE_BEDROCK) {
            var noise = (NoiseBasedChunkGenerator) generator;
            if (floor) sampleBand(noise, random, minimum, side, worldHeight, minY, false, masks);
            if (roof) sampleBand(noise, random, minimum, side, worldHeight, minY + height - 16, true, masks);
        }
        return List.of(masks);
    }

    private void sampleBand(NoiseBasedChunkGenerator noise, RandomState random, ChunkPos chunk, int side,
            LevelHeightAccessor worldHeight, int bandMinY, boolean roofBand, BitSet[] results) {
        LevelHeightAccessor band = LevelHeightAccessor.create(bandMinY, 16);
        var positional = random.getOrCreateRandomFactory(new ResourceLocation(roofBand ? "bedrock_roof" : "bedrock_floor"));
        int low = roofBand ? minY + height - 6 : minY;
        int high = roofBand ? minY + height - 1 : minY + 5;
        var settings = noise.generatorSettings().value();
        NoiseSettings cells = settings.noiseSettings().clampToHeightAccessor(band);
        int width = cells.getCellWidth(), cellHeight = cells.getCellHeight(), cellsXZ = side * 16 / width;
        // One shared lattice for the entire protected band. getBaseColumn per block column would
        // construct and resample the same noise graph 256 times, erasing the shortcut's benefit.
        NoiseChunk samples;
        try (var hashes = NoiseHashMemo.open(); var bounds = NoiseBatchScope.open(side * 16)) {
            samples = new NoiseChunk(cellsXZ, random, chunk.getMinBlockX(), chunk.getMinBlockZ(), cells,
                    new Beardifier(it.unimi.dsi.fastutil.objects.ObjectLists.<Beardifier.Rigid>emptyList().listIterator(),
                            it.unimi.dsi.fastutil.objects.ObjectLists.<net.minecraft.world.level.levelgen.structure.pools.JigsawJunction>emptyList().listIterator()), settings,
                    ((NativeAccess.NoiseFluids) noise).vre$fluidPicker(), Blender.empty());
        }
        samples.initializeForFirstCellX();
        try {
            for (int cx = 0; cx < cellsXZ; cx++) {
                samples.advanceCellX(cx);
                for (int cz = 0; cz < cellsXZ; cz++) {
                    for (int cy = cells.height() / cellHeight - 1; cy >= 0; cy--) {
                        int cellMinY = bandMinY + cy * cellHeight;
                        int from = Math.max(cellMinY, roofBand ? high - 4 : low);
                        int to = Math.min(cellMinY + cellHeight - 1, roofBand ? high : low + 4);
                        if (from > to) continue;
                        samples.selectCellYZ(cy, cz);
                        for (int y = to; y >= from; y--) {
                            samples.updateForY(y, (y - cellMinY) / (double) cellHeight);
                            for (int dx = 0; dx < width; dx++) {
                                int x = cx * width + dx, wx = chunk.getMinBlockX() + x;
                                samples.updateForX(wx, dx / (double) width);
                                for (int dz = 0; dz < width; dz++) {
                                    int z = cz * width + dz, wz = chunk.getMinBlockZ() + z;
                                    samples.updateForZ(wz, dz / (double) width);
                                    boolean gradient = y <= low || (y < high
                                            && positional.at(wx, y, wz).nextFloat() < (high - y) / (double) (high - low));
                                    if (!(roofBand ? !gradient : gradient)) continue;
                                    BlockState state = ((NativeAccess.NoiseSamples) samples).vre$interpolatedState();
                                    if (state == null || state == settings.defaultBlock())
                                        results[(z >>> 4) * side + (x >>> 4)].set(
                                                (y - worldHeight.getMinBuildHeight()) * 256 + (z & 15) * 16 + (x & 15));
                                }
                            }
                        }
                    }
                }
                samples.swapSlices();
            }
        } finally { samples.stopInterpolation(); }
    }
}
