package org.gtlcore.gtlcore.common.structure.place;

import org.gtlcore.gtlcore.common.structure.io.StructureFileIO;
import org.gtlcore.gtlcore.common.structure.io.StructureMappingIO;
import org.gtlcore.gtlcore.common.structure.local.BlueprintBundle;
import org.gtlcore.gtlcore.common.structure.local.BlueprintIO;
import org.gtlcore.gtlcore.common.structure.local.LocalBlueprintLibrary;
import org.gtlcore.gtlcore.common.structure.model.StructureDefinition.Structure;
import org.gtlcore.gtlcore.common.structure.transform.StructureLocalPos;
import org.gtlcore.gtlcore.common.structure.transform.StructureTransform;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LightEngine;

import it.unimi.dsi.fastutil.chars.Char2ReferenceOpenHashMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * 分 tick 结构放置：批量写 section，保留批量语义。
 * <p>
 * 无 BlockEntity 的方块直接写 {@link LevelChunkSection}，并按开关更新光照/高度图；
 * 带 BlockEntity 的位置回退到 {@link net.minecraft.world.level.Level#setBlock}。
 * </p>
 */
public final class StructurePlacer {

    /** 单 tick 内最多扫描的 pattern 格数（含跳过空气），防止极端情况下空转过久。 */
    public static final int MAX_SCAN_PER_TICK = 1_000_000;

    private static final Heightmap.Types[] HEIGHTMAP_TYPES = {
            Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR,
            Heightmap.Types.WORLD_SURFACE
    };

    private static final Set<Block> PLACEMENT_BLACKLIST = Set.of(
            Blocks.BEDROCK,
            Blocks.END_PORTAL_FRAME,
            Blocks.END_PORTAL,
            Blocks.END_GATEWAY,
            Blocks.BARRIER);

    private StructurePlacer() {}

    /** 已加载的图案 + 映射，避免扣费后加载失败。 */
    public record LoadedStructure(
                                  StructureFileIO.PatternData data,
                                  Char2ReferenceOpenHashMap<BlockState> mapping) {}

    /**
     * 预加载图案与映射；失败抛异常。应在扣材料<strong>之前</strong>调用。
     */
    public static LoadedStructure load(Structure structure) throws IOException {
        Objects.requireNonNull(structure, "structure");
        if (LocalBlueprintLibrary.isLocal(structure.resource())) {
            Path file = LocalBlueprintLibrary.resolve(structure.name());
            if (file == null || !Files.isRegularFile(file)) {
                throw new IOException("blueprint not found: " + structure.name());
            }
            BlueprintBundle.Bundle bundle = BlueprintIO.load(file);
            if (bundle.sizeX() <= 0 || bundle.sizeY() <= 0 || bundle.sizeZ() <= 0) {
                throw new IOException("invalid blueprint size: " + structure.name());
            }
            return new LoadedStructure(
                    new StructureFileIO.PatternData(bundle.pattern(), bundle.sizeX(), bundle.sizeY(), bundle.sizeZ()),
                    new Char2ReferenceOpenHashMap<>(bundle.palette()));
        }
        StructureFileIO.PatternData data = StructureFileIO.loadResource(structure.resource());
        if (data.sizeX() <= 0 || data.sizeY() <= 0 || data.sizeZ() <= 0) {
            throw new IOException("invalid pattern size for " + structure.resource());
        }
        Char2ReferenceOpenHashMap<BlockState> mapping = StructureMappingIO.loadResource(structure.blockMapping());
        if (mapping.isEmpty()) {
            throw new IOException("empty or missing block mapping: " + structure.blockMapping());
        }
        return new LoadedStructure(data, mapping);
    }

    /** 单个结构放置任务，由 BlockEntity 每 tick 调用 {@link #tick()}。 */
    public static final class PlacementTask {

        private final ServerLevel level;
        private final BlockIterator iterator;
        private final int perTick;
        private final boolean skipOccupied;
        private final boolean skipAir;
        private final boolean updateLight;
        private final boolean updateHeightmap;

        public PlacementTask(ServerLevel level,
                             BlockPos startPos,
                             LoadedStructure loaded,
                             int perTick,
                             boolean skipOccupied,
                             boolean skipAir,
                             boolean updateLight,
                             boolean updateHeightmap,
                             boolean zMirror,
                             boolean xMirror,
                             int rotation) {
            this.level = level;
            this.iterator = new BlockIterator(
                    loaded.data().pattern(), startPos, loaded.mapping(), zMirror, xMirror, rotation);
            this.perTick = Math.max(1, perTick);
            this.skipOccupied = skipOccupied;
            this.skipAir = skipAir;
            this.updateLight = updateLight;
            this.updateHeightmap = updateHeightmap;
        }

        public int progress() {
            return iterator.getProgressPercentage();
        }

        /** @return true 表示全部放置完成 */
        public boolean tick() {
            Map<BlockPos, BlockState> batch = new LinkedHashMap<>(Math.min(perTick, 4096));
            int placed = 0;
            int scanned = 0;

            while (iterator.hasNext() && placed < perTick && scanned < MAX_SCAN_PER_TICK) {
                scanned++;
                BlockIterator.Entry entry = iterator.next();
                BlockState state = entry.state();
                if (skipAir && state.isAir()) {
                    continue;
                }
                BlockPos pos = entry.pos();
                if (level.isOutsideBuildHeight(pos.getY())) {
                    continue;
                }
                BlockState old = level.getBlockState(pos);
                if (old.getBlock() == org.gtlcore.gtlcore.common.data.GTLStructureContent.STRUCTURE_PLACER.get()) {
                    continue;
                }
                if (state.isAir() && old.isAir()) {
                    continue;
                }
                // 跳过已有方块：世界上非空气则不放置
                if (skipOccupied && !old.isAir()) {
                    placed++;
                    continue;
                }
                if (PLACEMENT_BLACKLIST.contains(old.getBlock())) {
                    placed++;
                    continue;
                }
                batch.put(pos, state);
                placed++;
            }

            if (!batch.isEmpty()) {
                applyBatch(batch);
            }
            return !iterator.hasNext();
        }

        private void applyBatch(Map<BlockPos, BlockState> batch) {
            ServerChunkCache chunkSource = (ServerChunkCache) level.getChunkSource();

            // 按区块分组，避免在区块之间来回 getChunk；
            // 组内按 y 升序处理，保证高度图增量更新与放置顺序一致。
            Map<Long, List<Map.Entry<BlockPos, BlockState>>> byChunk = new LinkedHashMap<>();
            for (Map.Entry<BlockPos, BlockState> entry : batch.entrySet()) {
                BlockPos pos = entry.getKey();
                if (level.isOutsideBuildHeight(pos.getY())) continue;
                long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
                byChunk.computeIfAbsent(chunkKey, key -> new ArrayList<>(64)).add(entry);
            }

            for (Map.Entry<Long, List<Map.Entry<BlockPos, BlockState>>> group : byChunk.entrySet()) {
                long chunkKey = group.getKey();
                LevelChunk chunk = level.getChunk(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
                List<Map.Entry<BlockPos, BlockState>> entries = group.getValue();
                entries.sort(Comparator.comparingInt(entry -> entry.getKey().getY()));
                boolean chunkModified = false;

                for (Map.Entry<BlockPos, BlockState> entry : entries) {
                    BlockPos pos = entry.getKey();
                    BlockState state = entry.getValue();
                    int y = pos.getY();

                    BlockState old = chunk.getBlockState(pos);
                    if (old == state) continue;

                    // 带 BlockEntity 的位置走原版路径，保证方块实体完整创建/移除
                    if (old.hasBlockEntity() || state.hasBlockEntity()) {
                        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
                        chunkModified = true;
                        continue;
                    }

                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                    int lx = pos.getX() & 15;
                    int ly = y & 15;
                    int lz = pos.getZ() & 15;
                    boolean wasEmpty = section.hasOnlyAir();
                    section.setBlockState(lx, ly, lz, state, false);

                    level.onBlockStateChange(pos, old, state);

                    if (updateLight) {
                        if (wasEmpty != section.hasOnlyAir()) {
                            level.getLightEngine().updateSectionStatus(pos, section.hasOnlyAir());
                        }
                        if (LightEngine.hasDifferentLightProperties(level, pos, old, state)) {
                            level.getLightEngine().checkBlock(pos);
                        }
                    }
                    if (updateHeightmap) {
                        // Heightmap#update 只接受区块内局部坐标（0-15），
                        // 传世界坐标会算出越界索引导致 AIOOBE（Index 43 out of bounds for length 43）。
                        for (Heightmap.Types type : HEIGHTMAP_TYPES) {
                            chunk.getOrCreateHeightmapUnprimed(type).update(lx, y, lz, state);
                        }
                    }
                    chunkModified = true;
                    chunkSource.blockChanged(pos);
                }
                if (chunkModified) {
                    chunk.setUnsaved(true);
                }
            }
        }
    }

    private static final class BlockIterator implements Iterator<BlockIterator.Entry> {

        private final String[][] pattern;
        private final BlockPos startPos;
        private final Char2ReferenceOpenHashMap<BlockState> mapping;
        private final boolean zMirror;
        private final boolean xMirror;
        private final int rotation;
        private final int sizeX;
        private final int sizeZ;
        private final StructureLocalPos originOffset;

        private int z;
        private int y;
        private int x;
        private boolean done;

        BlockIterator(String[][] pattern, BlockPos startPos,
                      Char2ReferenceOpenHashMap<BlockState> mapping,
                      boolean zMirror, boolean xMirror, int rotation) {
            this.pattern = pattern;
            this.startPos = startPos;
            this.mapping = mapping;
            this.zMirror = zMirror;
            this.xMirror = xMirror;
            this.rotation = rotation;

            int sx = pattern.length > 0 && pattern[0].length > 0 ? pattern[0][0].length() : 0;
            int sy = pattern.length > 0 ? pattern[0].length : 0;
            int sz = pattern.length;
            this.sizeX = sx;
            this.sizeZ = sz;
            this.originOffset = StructureTransform.calcOriginOffset(sx, sy, sz, rotation, xMirror, zMirror);
        }

        int getProgressPercentage() {
            if (pattern.length == 0) return 0;
            return Math.min(100, (int) (((double) z / pattern.length) * 100));
        }

        @Override
        public boolean hasNext() {
            if (done) return false;
            while (z < pattern.length) {
                String[] aisle = pattern[z];
                while (y < aisle.length) {
                    String row = aisle[y];
                    while (x < row.length()) {
                        if (mapping.containsKey(row.charAt(x))) {
                            return true;
                        }
                        x++;
                    }
                    x = 0;
                    y++;
                }
                y = 0;
                z++;
            }
            done = true;
            return false;
        }

        @Override
        public Entry next() {
            if (!hasNext()) throw new NoSuchElementException();
            char c = pattern[z][y].charAt(x);
            BlockState state = mapping.get(c);
            if (state == null) {
                state = Blocks.AIR.defaultBlockState();
            }
            StructureLocalPos t = StructureTransform.transformCoords(
                    x, y, z, sizeX, sizeZ, rotation, xMirror, zMirror);
            BlockPos pos = new BlockPos(
                    startPos.getX() + t.x() + originOffset.x(),
                    startPos.getY() + t.y() + originOffset.y(),
                    startPos.getZ() + t.z() + originOffset.z());
            state = StructureTransform.transformStateForPlace(state, rotation, xMirror, zMirror);
            x++;
            return new Entry(pos, state);
        }

        record Entry(BlockPos pos, BlockState state) {}
    }
}
