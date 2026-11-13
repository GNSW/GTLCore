package org.gtlcore.gtlcore.api.pattern;

import com.gregtechceu.gtceu.api.pattern.MultiblockState;

import net.minecraft.world.level.ChunkPos;

import java.util.Set;

public interface INewMultiblockWorldSavedData {

    Set<MultiblockState> getControllersInChunk(ChunkPos chunkPos);

    PatternRetryIndex getPatternRetries();
}
