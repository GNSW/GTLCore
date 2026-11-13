package org.gtlcore.gtlcore.api.pattern;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

/** Records the largest attempt in the last complete check, rather than a permanent high-water mark. */
public final class PatternCapacityHints {

    private BlockPattern pattern;
    private int cacheSize;
    private int ioSize;
    private int activeSize;
    private int roundCacheSize;
    private int roundIoSize;
    private int roundActiveSize;
    private boolean checking;
    private boolean attemptStarted;

    public void begin(BlockPattern pattern) {
        if (this.pattern != pattern) {
            this.pattern = pattern;
            cacheSize = ioSize = activeSize = 0;
        }
        roundCacheSize = roundIoSize = roundActiveSize = 0;
        attemptStarted = false;
        checking = true;
    }

    /** Called before clean discards this attempt's collections. The previous check is never sampled. */
    public void beforeClean(MultiblockState state) {
        if (!checking) return;
        capture(state);
        attemptStarted = true;
    }

    private void capture(MultiblockState state) {
        if (!attemptStarted) return;
        roundCacheSize = Math.max(roundCacheSize, state.cache.size());
        Long2ObjectOpenHashMap<IO> io = state.getMatchContext().get("ioMap");
        LongSet active = state.getMatchContext().get("vaBlocks");
        if (io != null) roundIoSize = Math.max(roundIoSize, io.size());
        if (active != null) roundActiveSize = Math.max(roundActiveSize, active.size());
    }

    public void finish(MultiblockState state, boolean completed) {
        try {
            if (completed) {
                capture(state);
                cacheSize = roundCacheSize;
                ioSize = roundIoSize;
                activeSize = roundActiveSize;
            }
        } finally {
            checking = false;
            attemptStarted = false;
        }
    }

    public LongOpenHashSet newCache() {
        return checking && cacheSize > 0 ? new LongOpenHashSet(cacheSize) : new LongOpenHashSet();
    }

    public Long2ObjectOpenHashMap<IO> newIoMap(int firstSliceSize) {
        return new Long2ObjectOpenHashMap<>(checking && ioSize > 0 ? ioSize : firstSliceSize);
    }

    public LongSet newActiveBlocks() {
        return checking && activeSize > 0 ? new LongOpenHashSet(activeSize) : new LongOpenHashSet();
    }
}
