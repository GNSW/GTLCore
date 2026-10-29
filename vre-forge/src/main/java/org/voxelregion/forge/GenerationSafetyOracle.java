/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraftforge.fml.ModList;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.Volume;
import org.voxelregion.planner.GenerationSafety;
import org.voxelregion.propagation.FrozenOcclusion;
import org.voxelregion.spatial.ChunkKey;

/**
 * Deliberately narrow, version-specific proof for untouched vanilla flat chunks.
 * Unknown generators, biome features, structures, mod generation hooks and
 * contextual protection always fall back. SAFE here proves the generator
 * profile only: GenerationGate separately proves absence and checks the actual
 * native fresh ProtoChunk before any shortcut is applied. A shortcut must skip
 * at least one nonair layer and retain at most {@link #MAX_RETAINED_WRITES}
 * native writes, so large protected flat worlds remain on the generation worker.
 */
public final class GenerationSafetyOracle {
    /** Fixed upper bound on retained-layer writes in the atomic main-thread NOISE callback. */
    public static final int MAX_RETAINED_WRITES = 4096;
    private static final Set<String> VERIFIED_MODS = Set.of("minecraft", "forge", "voxelregion_test");

    private GenerationSafetyOracle() { }

    public static GenerationSafety assess(ServerLevel level, ChunkKey chunk, Volume volume,
            FrozenOcclusion.Mode propagation, GenerationGate.Rules rules) {
        return inspect(level, chunk, volume, propagation, rules).safety();
    }

    @SuppressWarnings("deprecation")
    static Candidate inspect(ServerLevel level, ChunkKey chunk, Volume volume,
            FrozenOcclusion.Mode propagation, GenerationGate.Rules rules) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(propagation, "propagation");
        Objects.requireNonNull(rules, "rules");
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Generation evidence requires the server thread");
        if (propagation != FrozenOcclusion.Mode.NONE) return requires("Only NONE propagation has a complete flat-generation proof");
        if (!rules.staticProtection()) return requires("Contextual protection requires native generation and ordinary planning");
        if (volume.classify(chunk.bounds(level.getMinBuildHeight(), level.getMaxBuildHeight())) != Coverage.INSIDE) {
            return requires("The operation does not prove full coverage of the actual build height");
        }
        if (!insideStableBorder(level, chunk.x(), chunk.z())) {
            return requires("The full chunk must fit inside a stationary world border");
        }
        var generator = level.getChunkSource().getGenerator();
        if (generator.getClass() != FlatLevelSource.class) return requires("Only exact vanilla FlatLevelSource is supported; noise/custom generators require generation");
        for (var mod : ModList.get().getMods()) {
            if (!VERIFIED_MODS.contains(mod.getModId())) {
                return unknown("No world-generation/retrogen proof for installed mod " + mod.getModId());
            }
        }
        FlatLevelSource flat = (FlatLevelSource) generator;
        var settings = flat.settings();
        if (settings.structureOverrides().isEmpty() || settings.structureOverrides().get().size() != 0) {
            return requires("Flat structure overrides must be explicitly empty");
        }
        // Use the generator's memoized settings. Calling settings.adjustGenerationSettings
        // directly a second time can mutate the layer list and is not a safe inspection.
        if (flat.getBiomeGenerationSettings(settings.getBiome()).features().stream().anyMatch(set -> set.size() != 0)) {
            return requires("Flat generation has placed features, including deferred nonopaque fill layers");
        }
        List<BlockState> nativeLayers = settings.getLayers();
        if (nativeLayers.stream().anyMatch(Objects::isNull)) return requires("Deferred/null flat layers require native feature generation");
        int height = level.getHeight();
        if (height <= 0 || height > 4096) return unknown("Build-height profile is outside the verified flat limit");
        List<BlockState> original = List.copyOf(nativeLayers.subList(0, Math.min(height, nativeLayers.size())));
        ArrayList<BlockState> retained = new ArrayList<>(original.size());
        for (BlockState state : original) {
            if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft")) {
                return unknown("Mod block layer requires an explicit generation proof");
            }
            if (state.hasBlockEntity()) return requires("Block-entity flat layers require ordinary generation");
            boolean mayDestroy;
            try {
                mayDestroy = rules.mayDestroy().test(state);
            } catch (RuntimeException failure) {
                return unknown("Layer protection snapshot failed: " + failure.getClass().getSimpleName());
            }
            retained.add(retainedState(state, mayDestroy, rules.clearFluids()));
        }
        long originalLayers = original.stream().filter(state -> !state.isAir()).count();
        long retainedLayers = retained.stream().filter(state -> !state.isAir()).count();
        if (retainedLayers * 256L > MAX_RETAINED_WRITES)
            return requires("Retained flat layers exceed the bounded main-thread write allowance");
        if (retainedLayers >= originalLayers)
            return requires("No original nonair layer can be skipped; retain ordinary worker generation");
        return new Candidate(new GenerationSafety(GenerationSafety.Decision.SAFE,
                "Exact vanilla flat layers; explicit empty structures; no placed features; verified mod set; static protection; full height; NONE propagation"),
                flat, original, List.copyOf(retained), level.getMinBuildHeight(), height);
    }

    @SuppressWarnings("deprecation")
    static boolean stillMatches(ServerLevel level, Candidate candidate) {
        if (level.getChunkSource().getGenerator() != candidate.generator()
                || level.getMinBuildHeight() != candidate.minY() || level.getHeight() != candidate.height()) return false;
        var settings = candidate.generator().settings();
        if (settings.structureOverrides().isEmpty() || settings.structureOverrides().get().size() != 0) return false;
        if (candidate.generator().getBiomeGenerationSettings(settings.getBiome()).features().stream().anyMatch(set -> set.size() != 0)) return false;
        List<BlockState> layers = settings.getLayers();
        int length = Math.min(candidate.height(), layers.size());
        return length == candidate.original().size() && layers.subList(0, length).equals(candidate.original());
    }

    /** Main-thread NOISE check; a changed or unreadable rule never authorizes the frozen shortcut. */
    static boolean protectionStillMatches(Candidate candidate, GenerationGate.Rules rules) {
        if (!rules.staticProtection()) return false;
        try {
            for (int i = 0; i < candidate.original().size(); i++) {
                BlockState state = candidate.original().get(i);
                BlockState current = retainedState(state, rules.mayDestroy().test(state), rules.clearFluids());
                if (!current.equals(candidate.retained().get(i))) return false;
            }
            return true;
        } catch (RuntimeException unknownProtection) { return false; }
    }

    private static BlockState retainedState(BlockState state, boolean mayDestroy, boolean clearFluids) {
        if (!mayDestroy) return state;
        return !clearFluids && !state.getFluidState().isEmpty()
                ? state.getFluidState().createLegacyBlock() : Blocks.AIR.defaultBlockState();
    }

    /** Full footprint containment, deliberately stricter than WorldBorder's chunk-overlap predicate. */
    static boolean insideStableBorder(ServerLevel level, int chunkX, int chunkZ) {
        var border = level.getWorldBorder();
        // A real-time border lerp could cross the footprint while the commit
        // callback writes. Such worlds use the ordinary per-block path.
        if (border.getLerpRemainingTime() > 0) return false;
        long minX = (long) chunkX * 16L, minZ = (long) chunkZ * 16L;
        return minX >= border.getMinX() && minX + 16L <= border.getMaxX()
                && minZ >= border.getMinZ() && minZ + 16L <= border.getMaxZ();
    }

    private static Candidate requires(String reason) { return rejected(GenerationSafety.Decision.REQUIRES_GENERATION, reason); }
    private static Candidate unknown(String reason) { return rejected(GenerationSafety.Decision.UNKNOWN, reason); }
    private static Candidate rejected(GenerationSafety.Decision decision, String reason) {
        return new Candidate(new GenerationSafety(decision, reason), null, List.of(), List.of(), 0, 0);
    }

    record Candidate(GenerationSafety safety, FlatLevelSource generator, List<BlockState> original,
                     List<BlockState> retained, int minY, int height) { }
}
