/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;

/** Version-specific mixin bridges; these are never part of the portable engine API. */
public final class NativeAccess {
    public interface ColumnSkySources {
        void vre$setSource(int index, int y);
    }
    public interface DurableStorage {
        /** Caller has awaited its actual stores; force those bytes without depending on later stores. */
        CompletableFuture<Void> vre$flushStored(java.util.Collection<ChunkPos> positions);
    }
    public interface ClimateParameters {
        net.minecraft.world.level.biome.Climate.ParameterList<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> vre$climateParameters();
    }
    public interface PreparedRegionFile {
        boolean vre$usesDeflate();
        void vre$writeCompressed(ChunkPos position, java.nio.ByteBuffer data) throws java.io.IOException;
        void vre$retainFile();
        void vre$releaseFile() throws java.io.IOException;
    }
    public interface RegionFiles {
        net.minecraft.world.level.chunk.storage.RegionFile vre$regionFile(ChunkPos position) throws java.io.IOException;
    }
    public interface PendingStore {
        net.minecraft.nbt.CompoundTag vre$data();
        CompletableFuture<Void> vre$result();
    }
    public interface ChunkSave {
        boolean vre$save(ChunkAccess chunk);
        IntSupplier vre$queueLevel(long position);
    }
    /** A negative revision means mutation provenance is unavailable and cannot prove a stable image. */
    public interface ChunkRevision {
        long vre$mutationRevision();
    }
    /** Identity actually processed by a successful native LevelChunk.postProcessGeneration call. */
    public interface ChunkPostProcessing {
        boolean vre$completedUpgrade(net.minecraft.world.level.chunk.UpgradeData upgrade);
    }
    /** Persisted proof that terrain/biomes were materialized by the cleared-terrain algorithm. */
    public interface ClearedTerrain {
        net.minecraft.nbt.CompoundTag vre$clearedTerrain();
        void vre$clearedTerrain(net.minecraft.nbt.CompoundTag marker);
        boolean vre$isClearedTerrain();
    }
    public interface NoiseSamples {
        net.minecraft.world.level.block.state.BlockState vre$interpolatedState();
        net.minecraft.world.level.biome.Climate.Sampler vre$climateSampler(net.minecraft.world.level.levelgen.NoiseRouter router,
                java.util.List<net.minecraft.world.level.biome.Climate.ParameterPoint> targets);
    }
    public interface NoiseFluids {
        net.minecraft.world.level.levelgen.Aquifer.FluidPicker vre$fluidPicker();
    }
    public record OpenColumn(ChunkPos position, int firstAirY,
            org.voxelregion.lighting.ClearedChunkLighting.Result solved, long solutionGroup) {
        public OpenColumn(ChunkPos position, int firstAirY) { this(position, firstAirY, null, 0); }
    }
    /** Detached changed-cell mask; callers cannot change the queued light workload. */
    public record LightChanges(int sectionY, java.util.BitSet cells) {
        public LightChanges {
            if (cells.length() > 4096) throw new IllegalArgumentException("Invalid section light mask");
            cells = (java.util.BitSet) cells.clone();
        }
        @Override public java.util.BitSet cells() { return (java.util.BitSet) cells.clone(); }
    }
    /** Immutable full-height column cropped from a larger independently solved terrain region. */
    public record SolvedLightColumn(ChunkPos position, int minSectionY,
            org.voxelregion.lighting.ClearedChunkLighting.Result solved) {
        public SolvedLightColumn {
            java.util.Objects.requireNonNull(position, "position");
            java.util.Objects.requireNonNull(solved, "solved");
        }
    }
    public record SolvedRegionStatistics(long requested, long installed, long cutRejected,
            long staleBefore, long staleAfter, long failed, long cutComparedCells, long cutMismatchedCells,
            long cutDifferentNonReject) { }
    public interface LightFence {
        /** Provider can rebuild a complete changed column without per-voxel native checks. */
        default boolean vre$rebuildsWholeChunks() { return false; }
        CompletableFuture<Void> vre$changedChecks(ChunkPos position, java.util.List<LightChanges> changes);
        CompletableFuture<Boolean> vre$clearAirRegion(java.util.List<OpenColumn> positions, int minSectionY,
                int sectionCount, boolean hasSky, BooleanSupplier stillValid);
        CompletableFuture<Void> vre$afterChunkWork(ChunkPos position);
        CompletableFuture<Void> vre$batchChecks(ChunkPos position, int[] sectionYs);
        CompletableFuture<Void> vre$batchChecks(ChunkPos position, int[] sectionYs, boolean replaySkySources);
        /** Same exact native checks with one completion fence; older providers retain their two existing submissions. */
        default CompletableFuture<Void> vre$combinedChecks(ChunkPos position, java.util.List<LightChanges> changes,
                int[] boundarySectionYs, boolean replaySkySources) {
            var changedWork = changes.isEmpty() ? CompletableFuture.<Void>completedFuture(null)
                    : vre$changedChecks(position, changes);
            var boundaryWork = boundarySectionYs.length == 0 ? vre$afterChunkWork(position)
                    : vre$batchChecks(position, boundarySectionYs, replaySkySources);
            return CompletableFuture.allOf(changedWork, boundaryWork);
        }
        /**
         * Publishes a rectangular full-height image only when its horizontal cut equals the native field.
         * Participants include the calculation halo; each native sorter lane must arrive before publication.
         * The host validates the identity and mutation revision of the entire snapshot before and after
         * installation. False requires retry/expansion; it never acknowledges successful lighting.
         */
        default CompletableFuture<Boolean> vre$installSolvedRegion(java.util.List<SolvedLightColumn> installation,
                java.util.List<ChunkPos> participants, BooleanSupplier stillValid) {
            return CompletableFuture.completedFuture(false);
        }
        /**
         * Publishes the exact affected halo of a fully completed terrain mutation. Inclusive dirty bounds
         * must have one complete chunk of repair halo inside installation, and installation must have one
         * complete chunk of calculation halo inside participants. The caller must register only AFTER all
         * owner-thread mutation and native remainder callbacks for the dirty footprint have finished.
         * stillValid must cover every participant's identity/revision, cancellation and pending mutation
         * ownership. A later mutation invalidates that proof synchronously. Under this contract an old
         * cut may differ because another nearby mutation has not published yet; overlap is an assignment
         * of the same exact field, so cut differences are diagnostic rather than a mutual-wait condition.
         */
        default CompletableFuture<Boolean> vre$installSolvedMutationRegion(
                java.util.List<SolvedLightColumn> installation, java.util.List<ChunkPos> participants,
                int minDirtyChunkX, int minDirtyChunkZ, int maxDirtyChunkX, int maxDirtyChunkZ,
                BooleanSupplier stillValid) {
            return CompletableFuture.completedFuture(false);
        }
        default boolean vre$supportsSolvedMutationRegion() { return false; }
        default SolvedRegionStatistics vre$solvedRegionStatistics() {
            return new SolvedRegionStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        CompletableFuture<Boolean> vre$solveAndInstall(ChunkPos position, int minSectionY,
                Supplier<org.voxelregion.lighting.ClearedChunkLighting.Snapshot> input,
                BooleanSupplier stillValid, Runnable releaseInput);
        CompletableFuture<Void> vre$fullChecks(ChunkPos position, int minSectionY, int sectionCount);
    }
    public interface SectionStats {
        short vre$nonEmptyBlockCount();
        int vre$accurateNonAirCount();
    }
    public interface SkySources {
        int vre$lowestSourceY(int worldX, int worldZ, int missingValue);
    }
    /** A caller-owned copy of the actual visible layer, excluding queued unpublished data. Null means absent. */
    public interface VisibleLightData {
        DataLayer vre$copyVisibleSectionData(long sectionPosition);
        /** Light-owner thread only, after preceding section-status work has drained. */
        default boolean vre$storesUpdatingSection(long sectionPosition) { return true; }
    }
    public interface LightBoundary {
        net.minecraft.world.level.chunk.LightChunk vre$lightChunk(int x, int z);
    }
    /** Selective counterpart of native clearArea; preserves scheduled work at retained cells. */
    public interface TickFilter {
        void vre$clearChanged(ChunkPos chunk, Predicate<BlockPos> changed);
    }
    private NativeAccess() {}
}
