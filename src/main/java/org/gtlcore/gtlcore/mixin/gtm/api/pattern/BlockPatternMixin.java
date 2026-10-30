package org.gtlcore.gtlcore.mixin.gtm.api.pattern;

import org.gtlcore.gtlcore.api.pattern.AdvancedBlockPattern;
import org.gtlcore.gtlcore.api.pattern.BlockPatternAccessor;
import org.gtlcore.gtlcore.api.pattern.FixedPatternLayout;
import org.gtlcore.gtlcore.api.pattern.PatternLayoutSearch;
import org.gtlcore.gtlcore.api.pattern.PreviewMatcherTiming;
import org.gtlcore.gtlcore.api.pattern.WorldPatternMatcher;
import org.gtlcore.gtlcore.api.pattern.WorldPatternTiming;
import org.gtlcore.gtlcore.mixin.gtm.api.machine.IMultiblockStateInvoker;

import com.gregtechceu.gtceu.api.block.ActiveBlock;
import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.pattern.*;
import com.gregtechceu.gtceu.api.pattern.error.*;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;
import com.gregtechceu.gtceu.api.pattern.util.PatternMatchContext;

import com.lowdragmc.lowdraglib.utils.BlockInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import it.unimi.dsi.fastutil.longs.*;
import it.unimi.dsi.fastutil.objects.*;
import org.spongepowered.asm.mixin.*;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * 思路参考自gto
 * &#064;line <a href="https://github.com/GregTech-Odyssey/GTOCore">...</a>
 */

@Mixin(BlockPattern.class)
public abstract class BlockPatternMixin implements BlockPatternAccessor {

    @Shadow(remap = false)
    @Final
    protected TraceabilityPredicate[][][] blockMatches;
    @Shadow(remap = false)
    @Final
    protected int fingerLength;
    @Shadow(remap = false)
    @Final
    protected int thumbLength;
    @Shadow(remap = false)
    @Final
    protected int palmLength;
    @Shadow(remap = false)
    @Final
    public int[][] aisleRepetitions;
    @Shadow(remap = false)
    @Final
    protected int[] centerOffset;

    @Shadow(remap = false)
    protected abstract BlockPos setActualRelativeOffset(int x, int y, int z, Direction facing, Direction upwardsFacing, boolean isFlipped);

    @Override
    public TraceabilityPredicate[][][] gtlcore$blockMatches() {
        return this.blockMatches;
    }

    @Override
    public int[] gtlcore$centerOffset() {
        return this.centerOffset;
    }

    @Override
    public BlockPos gtlcore$setActualRelativeOffset(int x, int y, int z, Direction facing,
                                                    Direction upwardsFacing, boolean isFlipped) {
        return setActualRelativeOffset(x, y, z, facing, upwardsFacing, isFlipped);
    }

    @Shadow(remap = false)
    protected abstract void resetFacing(BlockPos pos, BlockState blockState, Direction facing, BiFunction<BlockPos, Direction, Boolean> checker, Consumer<BlockState> consumer);

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public boolean checkPatternAt(MultiblockState worldState, BlockPos centerPos, Direction frontFacing, Direction upwardsFacing, boolean isFlipped, boolean savePredicate) {
        try (var timing = WorldPatternTiming.beginMatcher(worldState, frontFacing, isFlipped)) {
            boolean matched = gtlcore$checkPatternAt(worldState, centerPos, frontFacing, upwardsFacing, isFlipped, savePredicate);
            if (timing != null) timing.result(matched);
            return matched;
        }
    }

    @Unique
    private boolean gtlcore$checkPatternAt(MultiblockState worldState, BlockPos centerPos, Direction frontFacing,
                                           Direction upwardsFacing, boolean isFlipped, boolean savePredicate) {
        int[] fixedRepetitions = (PreviewMatcherTiming.useFixedLayout(worldState) ||
                WorldPatternMatcher.useFixedLayout(worldState)) ?
                        FixedPatternLayout.repetitions(this.aisleRepetitions) : null;
        if (fixedRepetitions != null && fixedRepetitions.length != this.fingerLength) fixedRepetitions = null;
        boolean incremental = fixedRepetitions == null && PreviewMatcherTiming.useIncrementalSearch(worldState) &&
                PatternLayoutSearch.supports(this.aisleRepetitions, this.fingerLength);
        int[] matchedRepetitions = new int[this.fingerLength];
        int minStartZ = -this.centerOffset[4];
        int maxStartZ = -this.centerOffset[3];
        for (int startZ = minStartZ; startZ <= maxStartZ; startZ++) {
            Arrays.fill(matchedRepetitions, 0);
            WorldPatternTiming.attempt(worldState, fixedRepetitions != null ? "fixed_linear" : incremental ? "incremental" : "legacy_replay");
            if (incremental) PreviewMatcherTiming.incrementalAttempt();
            else PreviewMatcherTiming.attempt(fixedRepetitions != null);
            boolean matched;
            if (fixedRepetitions != null) {
                ((IMultiblockStateInvoker) worldState).cleanState();
                System.arraycopy(fixedRepetitions, 0, matchedRepetitions, 0, this.fingerLength);
                matched = FixedPatternLayout.match(fixedRepetitions, startZ,
                        (aisle, z) -> gtlcore$matchSlice(worldState, centerPos, frontFacing, upwardsFacing,
                                isFlipped, savePredicate, aisle, z)) &&
                        gtlcore$hasRequiredGlobalCounts(worldState);
            } else if (incremental) {
                matched = PatternLayoutSearch.match(this.aisleRepetitions, startZ, matchedRepetitions,
                        new PatternLayoutSearch.Checks(
                                ((IMultiblockStateInvoker) worldState)::cleanState,
                                (aisle, z) -> gtlcore$matchSlice(worldState, centerPos, frontFacing, upwardsFacing,
                                        isFlipped, savePredicate, aisle, z),
                                () -> gtlcore$hasRequiredGlobalCounts(worldState),
                                () -> worldState.error == MultiblockState.UNLOAD_ERROR,
                                PreviewMatcherTiming::replay, PreviewMatcherTiming::replayedSlice));
            } else {
                matched = gtlcore$matchAisles(worldState, centerPos, frontFacing, upwardsFacing, isFlipped,
                        savePredicate, startZ, 0, startZ, matchedRepetitions);
            }
            if (matched) {
                worldState.setError(null);
                worldState.setNeededFlip(isFlipped);
                PatternMatchContext matchContext = worldState.getMatchContext();
                matchContext.set(AdvancedBlockPattern.MATCHED_REPETITIONS_CONTEXT, matchedRepetitions.clone());
                matchContext.set(AdvancedBlockPattern.MATCHED_MIN_Z_CONTEXT, startZ);
                return true;
            }
            if (worldState.error == MultiblockState.UNLOAD_ERROR) return false;
        }

        if (!worldState.hasError()) worldState.setError(new PatternError());
        return false;
    }

    @Unique
    private boolean gtlcore$matchAisles(MultiblockState worldState, BlockPos centerPos, Direction frontFacing,
                                        Direction upwardsFacing, boolean isFlipped, boolean savePredicate, int startZ,
                                        int aisle, int z, int[] repetitions) {
        if (aisle == 0 && worldState instanceof IMultiblockStateInvoker stateGet) stateGet.cleanState();

        int minRepetitions = this.aisleRepetitions[aisle][0];
        int maxRepetitions = this.aisleRepetitions[aisle][1];
        int validRepetitions = 0;
        while (validRepetitions < maxRepetitions &&
                gtlcore$matchSlice(worldState, centerPos, frontFacing, upwardsFacing, isFlipped, savePredicate, aisle,
                        z + validRepetitions)) {
            validRepetitions++;
        }
        if (worldState.error == MultiblockState.UNLOAD_ERROR) return false;

        for (int count = validRepetitions; count >= minRepetitions; count--) {
            repetitions[aisle] = count;
            Arrays.fill(repetitions, aisle + 1, repetitions.length, 0);
            if (!gtlcore$replayLayout(worldState, centerPos, frontFacing, upwardsFacing, isFlipped, savePredicate,
                    startZ, aisle, repetitions)) {
                if (worldState.error == MultiblockState.UNLOAD_ERROR) return false;
                continue;
            }
            if (aisle + 1 < this.fingerLength) {
                if (gtlcore$matchAisles(worldState, centerPos, frontFacing, upwardsFacing, isFlipped, savePredicate,
                        startZ, aisle + 1, z + count, repetitions)) {
                    return true;
                }
                if (worldState.error == MultiblockState.UNLOAD_ERROR) return false;
            } else if (gtlcore$hasRequiredGlobalCounts(worldState)) {
                return true;
            }
        }
        repetitions[aisle] = 0;
        return false;
    }

    @Unique
    private boolean gtlcore$replayLayout(MultiblockState worldState, BlockPos centerPos, Direction frontFacing,
                                         Direction upwardsFacing, boolean isFlipped, boolean savePredicate, int startZ,
                                         int lastAisle, int[] repetitions) {
        PreviewMatcherTiming.replay();
        WorldPatternTiming.replay(worldState);
        ((IMultiblockStateInvoker) worldState).cleanState();
        int z = startZ;
        for (int aisle = 0; aisle <= lastAisle; aisle++) {
            for (int repeat = 0; repeat < repetitions[aisle]; repeat++, z++) {
                PreviewMatcherTiming.replayedSlice();
                WorldPatternTiming.replayedSlice(worldState);
                if (!gtlcore$matchSlice(worldState, centerPos, frontFacing, upwardsFacing, isFlipped, savePredicate,
                        aisle, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Unique
    private boolean gtlcore$matchSlice(MultiblockState worldState, BlockPos centerPos, Direction frontFacing,
                                       Direction upwardsFacing, boolean isFlipped, boolean savePredicate, int aisle,
                                       int z) {
        PreviewMatcherTiming.slice();
        WorldPatternTiming.slice(worldState);
        Map<SimplePredicate, Integer> layerCount = worldState.getLayerCount();
        layerCount.clear();
        PatternMatchContext matchContext = worldState.getMatchContext();
        for (int b = 0, y = -this.centerOffset[1]; b < this.thumbLength; b++, y++) {
            for (int a = 0, x = -this.centerOffset[0]; a < this.palmLength; a++, x++) {
                worldState.setError(null);
                TraceabilityPredicate predicate = this.blockMatches[aisle][b][a];
                if (predicate.isAny()) continue;
                BlockPos pos = setActualRelativeOffset(x, y, z, frontFacing, upwardsFacing, isFlipped)
                        .offset(centerPos.getX(), centerPos.getY(), centerPos.getZ());
                if (!(worldState instanceof IMultiblockStateInvoker stateGet) || !stateGet.updateState(pos, predicate)) {
                    return false;
                }
                if (predicate.addCache()) {
                    worldState.addPosCache(pos);
                    if (savePredicate) {
                        matchContext.getOrCreate("predicates", Object2ObjectOpenHashMap::new).put(pos, predicate);
                    }
                }
                boolean canPartShared = true;
                if (worldState.getTileEntity() instanceof IMachineBlockEntity machineBlockEntity &&
                        machineBlockEntity.getMetaMachine() instanceof IMultiPart part) {
                    if (part.isFormed() && !part.canShared() && !part.hasController(worldState.controllerPos)) {
                        canPartShared = false;
                        worldState.setError(new PatternStringError("multiblocked.pattern.error.share"));
                    } else {
                        matchContext.getOrCreate("parts", ObjectOpenHashSet::new).add(part);
                    }
                }
                if (worldState.getBlockState().getBlock() instanceof ActiveBlock) {
                    matchContext.getOrCreate("vaBlocks", LongOpenHashSet::new).add(worldState.getPos().asLong());
                }
                if (!predicate.test(worldState) || !canPartShared) return false;
                matchContext.getOrCreate("ioMap", Long2ObjectOpenHashMap::new).put(worldState.getPos().asLong(),
                        worldState.io);
            }
        }
        for (var entry : layerCount.entrySet()) {
            if (entry.getValue() < entry.getKey().minLayerCount) {
                worldState.setError(new SinglePredicateError(entry.getKey(), 3));
                return false;
            }
        }
        return true;
    }

    @Unique
    private boolean gtlcore$hasRequiredGlobalCounts(MultiblockState worldState) {
        for (var entry : worldState.getGlobalCount().entrySet()) {
            if (entry.getValue() < entry.getKey().minCount) {
                worldState.setError(new SinglePredicateError(entry.getKey(), 1));
                return false;
            }
        }
        return true;
    }

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public BlockInfo[][][] getPreview(int[] repetition) {
        Object2IntOpenHashMap<SimplePredicate> cacheGlobal = new Object2IntOpenHashMap<>();
        Long2ObjectOpenHashMap<BlockInfo> blocks = new Long2ObjectOpenHashMap<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int l = 0, x = 0; l < this.fingerLength; l++) {
            for (int r = 0; r < repetition[l]; r++) {
                Object2IntOpenHashMap<SimplePredicate> cacheLayer = new Object2IntOpenHashMap<>();
                for (int y = 0; y < this.thumbLength; y++) {
                    for (int z = 0; z < this.palmLength; z++) {
                        TraceabilityPredicate predicate = this.blockMatches[l][y][z];
                        if (predicate.isAny()) continue;
                        boolean find = false;
                        BlockInfo[] infos = null;
                        for (SimplePredicate limit : predicate.limited) {
                            if (limit.minLayerCount > 0) {
                                if (cacheLayer.getInt(limit) < limit.minLayerCount) {
                                    cacheLayer.addTo(limit, 1);
                                } else continue;
                                if (cacheGlobal.getInt(limit) < limit.previewCount) {
                                    cacheGlobal.addTo(limit, 1);
                                } else continue;
                            } else continue;
                            infos = limit.candidates == null ? null : limit.candidates.get();
                            find = true;
                            break;
                        }
                        if (!find) {
                            for (SimplePredicate limit : predicate.limited) {
                                if (limit.minCount == -1 && limit.previewCount == -1) continue;
                                if (cacheGlobal.getInt(limit) < limit.previewCount) {
                                    cacheGlobal.addTo(limit, 1);
                                } else if (limit.minCount > 0) {
                                    if (cacheGlobal.getInt(limit) < limit.minCount) {
                                        cacheGlobal.addTo(limit, 1);
                                    } else continue;
                                } else continue;
                                infos = limit.candidates == null ? null : limit.candidates.get();
                                find = true;
                                break;
                            }
                        }
                        if (!find) {
                            for (SimplePredicate common : predicate.common) {
                                if (common.previewCount > 0) {
                                    if (cacheGlobal.getInt(common) < common.previewCount) {
                                        cacheGlobal.addTo(common, 1);
                                    } else continue;
                                } else continue;
                                infos = common.candidates == null ? null : common.candidates.get();
                                find = true;
                                break;
                            }
                        }
                        if (!find) {
                            for (SimplePredicate common : predicate.common) {
                                if (common.previewCount == -1) {
                                    infos = common.candidates == null ? null : common.candidates.get();
                                    find = true;
                                    break;
                                }
                            }
                        }
                        if (!find) {
                            for (SimplePredicate limit : predicate.limited) {
                                if (limit.previewCount != -1) continue;
                                if (limit.maxCount != -1 || limit.maxLayerCount != -1) {
                                    if (cacheGlobal.getOrDefault(limit, 0) < limit.maxCount) {
                                        cacheGlobal.addTo(limit, 1);
                                    } else if (cacheLayer.getOrDefault(limit, 0) < limit.maxLayerCount) {
                                        cacheLayer.addTo(limit, 1);
                                    } else continue;
                                }
                                infos = limit.candidates == null ? null : limit.candidates.get();
                                break;
                            }
                        }
                        BlockInfo info = infos == null || infos.length == 0 ? BlockInfo.EMPTY : infos[0];
                        BlockPos pos = setActualRelativeOffset(z, y, x, Direction.NORTH, Direction.UP, false);

                        blocks.put(pos.asLong(), info);
                        minX = Math.min(pos.getX(), minX);
                        minY = Math.min(pos.getY(), minY);
                        minZ = Math.min(pos.getZ(), minZ);
                        maxX = Math.max(pos.getX(), maxX);
                        maxY = Math.max(pos.getY(), maxY);
                        maxZ = Math.max(pos.getZ(), maxZ);
                    }
                }
                x++;
            }
        }

        BlockInfo[][][] result = (BlockInfo[][][]) Array.newInstance(BlockInfo.class, new int[] { maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1 });
        int finalMinX = minX;
        int finalMinY = minY;
        int finalMinZ = minZ;
        blocks.long2ObjectEntrySet().fastForEach(entry -> {
            var blockPos = entry.getLongKey();
            var pos = BlockPos.of(blockPos);
            var info = entry.getValue();
            resetFacing(pos, info.getBlockState(), null, (p, f) -> {
                BlockInfo blockInfo = blocks.get(p.relative(f).asLong());
                if (blockInfo == null || blockInfo.getBlockState().getBlock() == Blocks.AIR) {
                    if (blocks.get(blockPos).getBlockState().getBlock() instanceof MetaMachineBlock machineBlock) {
                        if (machineBlock.newBlockEntity(BlockPos.ZERO,
                                machineBlock.defaultBlockState()) instanceof IMachineBlockEntity machineBlockEntity) {
                            var machine = machineBlockEntity.getMetaMachine();
                            if (machine instanceof IMultiController) return false;
                            else return machine.isFacingValid(f);
                        }
                    }
                    return true;
                }
                return false;
            }, info::setBlockState);
            result[pos.getX() - finalMinX][pos.getY() - finalMinY][pos.getZ() - finalMinZ] = info;
        });
        return result;
    }
}
