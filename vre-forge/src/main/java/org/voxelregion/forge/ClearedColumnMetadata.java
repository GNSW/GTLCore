/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.Set;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/** One column reduction supplies all heightmaps and sky sources for proven AIR/bedrock terrain. */
public final class ClearedColumnMetadata {
    public static boolean rebuild(ChunkAccess chunk, Set<Heightmap.Types> types) {
        if (!(chunk.getSkyLightSources() instanceof NativeAccess.ColumnSkySources sky)) return false;
        var sections = chunk.getSections();
        int highest = -1;
        for (int i = 0; i < sections.length; i++) {
            if (sections[i].maybeHas(state -> !air(state) && state != Blocks.BEDROCK.defaultBlockState())) return false;
            if (!sections[i].hasOnlyAir()) highest = i;
        }
        int minimum = chunk.getMinBuildHeight();
        int uniformFloor = AirRegionLightBatch.floor(chunk, chunk);
        var heights = new SimpleBitStorage(Mth.ceillog2(chunk.getHeight() + 1), 256);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int top = uniformFloor == Integer.MAX_VALUE ? minimum : uniformFloor;
            search: for (int section = uniformFloor == Integer.MAX_VALUE ? highest : -1; section >= 0; section--) {
                if (sections[section].hasOnlyAir()) continue;
                for (int y = 15; y >= 0; y--) if (sections[section].getBlockState(x, y, z) == Blocks.BEDROCK.defaultBlockState()) {
                    top = (chunk.getSectionYFromSectionIndex(section) << 4) + y + 1;
                    break search;
                }
            }
            int index = (z << 4) | x;
            heights.set(index, top - minimum);
            // Native sky uses minBuildHeight - 1 as its unbounded-open-column sentinel.
            sky.vre$setSource(index, top == minimum ? minimum - 1 : top);
        }
        for (var type : types) chunk.setHeightmap(type, heights.getRaw());
        return true;
    }
    private static boolean air(BlockState state) {
        return state == Blocks.AIR.defaultBlockState() || state == Blocks.CAVE_AIR.defaultBlockState()
                || state == Blocks.VOID_AIR.defaultBlockState();
    }
    private ClearedColumnMetadata() { }
}
