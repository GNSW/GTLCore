package com.wcwen.gtmbsockets.client;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import com.wcwen.gtmbsockets.mixin.BlockPatternAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Turns a GregTech {@link BlockPattern} into a list of "cells" and answers whether a cell accepts
 * a particular part item / block.
 *
 * <p>Everything is cached:
 * <ul>
 *     <li>one {@link BlockPattern} per {@link MultiblockMachineDefinition} (the factory may build a
 *         fresh pattern on every call);</li>
 *     <li>one cell list per (pattern, front, up, flipped) combination;</li>
 *     <li>one candidate item/block set per {@link SimplePredicate}.</li>
 * </ul>
 */
public final class PatternSockets {

    private static final Logger LOG = LoggerFactory.getLogger("gtmbsockets");

    private static final Map<MultiblockMachineDefinition, BlockPattern> PATTERNS = new IdentityHashMap<>();
    private static final Map<Class<?>, Boolean> OVERRIDES_PATTERN = new HashMap<>();
    private static final Map<MultiblockControllerMachine, CachedPattern> DYNAMIC_PATTERNS = new WeakHashMap<>();
    private static final Map<OrientationKey, List<Cell>> CELLS = new HashMap<>();
    private static final Map<OrientationKey, Map<Long, List<Cell>>> SECTION_CELLS = new HashMap<>();
    private static final Map<SimplePredicate, CandidateSet> CANDIDATES = new IdentityHashMap<>();
    private static final Set<BlockPattern> FAILED_PATTERNS =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    private static volatile boolean accessorFailed;

    private PatternSockets() {
    }

    public static BlockPattern patternFor(MultiblockControllerMachine controller) {
        // A few controllers (cleanroom, charcoal pile, GTLCore simulation machine, ...) build
        // their pattern dynamically instead of using the definition factory.  Honour them and
        // keep their pattern for a short while so a huge dynamic pattern is not rebuilt per frame.
        if (overridesGetPattern(controller.getClass())) {
            long now = System.currentTimeMillis();
            CachedPattern cached = DYNAMIC_PATTERNS.get(controller);
            if (cached != null && now - cached.time() < 1000L) {
                return cached.pattern();
            }
            try {
                BlockPattern pattern = controller.getPattern();
                if (pattern != null) {
                    if (DYNAMIC_PATTERNS.size() > 256) {
                        DYNAMIC_PATTERNS.clear();
                    }
                    DYNAMIC_PATTERNS.put(controller, new CachedPattern(pattern, now));
                }
                return pattern;
            } catch (Throwable t) {
                LOG.warn("[gtmbsockets] could not build dynamic pattern for {}",
                        controller.getClass().getName(), t);
                return null;
            }
        }

        MultiblockMachineDefinition definition = controller.getDefinition();
        if (definition == null) {
            return null;
        }
        BlockPattern cached = PATTERNS.get(definition);
        if (cached != null) {
            return cached;
        }
        try {
            if (definition.getPatternFactory() == null) {
                return null;
            }
            BlockPattern pattern = definition.getPatternFactory().get();
            if (pattern != null) {
                PATTERNS.put(definition, pattern);
            }
            return pattern;
        } catch (Throwable t) {
            LOG.warn("[gtmbsockets] could not build structure pattern for {}", definition.getName(), t);
            return null;
        }
    }

    private static boolean overridesGetPattern(Class<?> type) {
        Boolean known = OVERRIDES_PATTERN.get(type);
        if (known != null) {
            return known;
        }
        boolean overrides;
        try {
            Method method = type.getMethod("getPattern");
            overrides = method.getDeclaringClass() != IMultiController.class;
        } catch (Throwable t) {
            overrides = true;
        }
        OVERRIDES_PATTERN.put(type, overrides);
        return overrides;
    }

    public static List<Cell> cellsFor(BlockPattern pattern, Direction front, Direction up, boolean flipped) {
        OrientationKey key = new OrientationKey(pattern, front, up, flipped);
        List<Cell> cached = CELLS.get(key);
        if (cached != null) {
            return cached;
        }
        List<Cell> computed = computeCells(pattern, front, up, flipped);
        if (CELLS.size() > 128) {
            CELLS.clear();
        }
        CELLS.put(key, computed);
        return computed;
    }

    /**
     * Cells grouped by their 16x16x16 section relative to the controller.  A large multiblock is
     * then walked section-by-section around the player instead of scanning every cell every tick.
     */
    public static Map<Long, List<Cell>> sectionCellsFor(BlockPattern pattern, Direction front,
                                                        Direction up, boolean flipped) {
        OrientationKey key = new OrientationKey(pattern, front, up, flipped);
        Map<Long, List<Cell>> cached = SECTION_CELLS.get(key);
        if (cached != null) {
            return cached;
        }
        Map<Long, List<Cell>> sections = new HashMap<>();
        for (Cell cell : cellsFor(pattern, front, up, flipped)) {
            int sx = cell.offset().m_123341_() >> 4;
            int sy = cell.offset().m_123342_() >> 4;
            int sz = cell.offset().m_123343_() >> 4;
            sections.computeIfAbsent(sectionKey(sx, sy, sz), ignored -> new ArrayList<>()).add(cell);
        }
        if (SECTION_CELLS.size() > 128) {
            SECTION_CELLS.clear();
        }
        SECTION_CELLS.put(key, sections);
        return sections;
    }

    public static long sectionKey(int sx, int sy, int sz) {
        return ((long) (sx & 0x3FFFFFF) << 38)
                | ((long) (sy & 0xFFF) << 26)
                | (sz & 0x3FFFFFF);
    }

    /**
     * Checks whether the looked-at block is one of the structure cells of this pattern.  Used for
     * casing blocks, which have no block entity that could point at the controller.
     */
    public static boolean contains(BlockPattern pattern, Direction front, Direction up, boolean flipped,
                                   BlockPos controllerPos, BlockPos lookPos) {
        if (pattern == null || controllerPos == null || lookPos == null) {
            return false;
        }
        int dx = lookPos.m_123341_() - controllerPos.m_123341_();
        int dy = lookPos.m_123342_() - controllerPos.m_123342_();
        int dz = lookPos.m_123343_() - controllerPos.m_123343_();
        List<Cell> section = sectionCellsFor(pattern, front, up, flipped)
                .get(sectionKey(dx >> 4, dy >> 4, dz >> 4));
        if (section == null) {
            return false;
        }
        for (Cell cell : section) {
            BlockPos offset = cell.offset();
            if (offset.m_123341_() == dx && offset.m_123342_() == dy && offset.m_123343_() == dz) {
                return true;
            }
        }
        return false;
    }

    public static boolean accepts(TraceabilityPredicate predicate, Item item) {
        if (predicate == null || item == null) {
            return false;
        }
        for (SimplePredicate simple : predicate.common) {
            if (candidateSet(simple).items().contains(item)) {
                return true;
            }
        }
        for (SimplePredicate simple : predicate.limited) {
            if (candidateSet(simple).items().contains(item)) {
                return true;
            }
        }
        return false;
    }

    public static boolean acceptsBlock(TraceabilityPredicate predicate, Block block) {
        if (predicate == null || block == null) {
            return false;
        }
        for (SimplePredicate simple : predicate.common) {
            if (candidateSet(simple).blocks().contains(block)) {
                return true;
            }
        }
        for (SimplePredicate simple : predicate.limited) {
            if (candidateSet(simple).blocks().contains(block)) {
                return true;
            }
        }
        return false;
    }

    private static List<Cell> computeCells(BlockPattern pattern, Direction front, Direction up, boolean flipped) {
        if (accessorFailed || FAILED_PATTERNS.contains(pattern)) {
            return Collections.emptyList();
        }
        BlockPatternAccessor accessor;
        try {
            accessor = (BlockPatternAccessor) (Object) pattern;
        } catch (ClassCastException | LinkageError t) {
            accessorFailed = true;
            LOG.error("[gtmbsockets] BlockPattern accessor is not available; hatch highlighting is disabled", t);
            return Collections.emptyList();
        }
        try {
            TraceabilityPredicate[][][] matches = accessor.gtmbsockets$blockMatches();
            int[] center = accessor.gtmbsockets$centerOffset();
            if (matches == null || matches.length == 0 || matches[0] == null || matches[0].length == 0
                    || center == null || center.length < 5) {
                return Collections.emptyList();
            }

            int fingerLength = matches.length;
            int thumbLength = matches[0].length;
            int palmLength = matches[0][0] == null ? 0 : matches[0][0].length;
            if (palmLength == 0 || pattern.aisleRepetitions == null
                    || pattern.aisleRepetitions.length < fingerLength) {
                return Collections.emptyList();
            }

            List<Cell> cells = new ArrayList<>();
            Set<Long> seen = new HashSet<>();

            // This loop mirrors BlockPattern#checkPatternAt exactly, including the aisle
            // repetitions.  The only difference is that we do not evaluate the predicate here;
            // every non-controller cell is recorded and filtered by the held item later.
            int minZ = -center[4];
            boolean findFirstAisle = false;
            int z = minZ++;
            for (int c = 0; c < fingerLength; c++) {
                int repetition = 0;
                while (findFirstAisle ? repetition < pattern.aisleRepetitions[c][1]
                        : z <= -center[3]) {
                    for (int b = 0; b < thumbLength; b++) {
                        int y = -center[1] + b;
                        for (int a = 0; a < palmLength; a++) {
                            int x = -center[0] + a;
                            TraceabilityPredicate predicate = matches[c][b][a];
                            if (predicate == null || predicate.isController) {
                                continue;
                            }
                            BlockPos offset = accessor.gtmbsockets$setActualRelativeOffset(
                                    x, y, z, front, up, flipped);
                            if (offset == null || !seen.add(offset.m_121878_())) {
                                continue;
                            }
                            cells.add(new Cell(offset, predicate));
                        }
                    }
                    findFirstAisle = true;
                    z++;
                    repetition++;
                }
            }
            return cells;
        } catch (LinkageError t) {
            accessorFailed = true;
            LOG.error("[gtmbsockets] BlockPattern accessor failed; hatch highlighting is disabled", t);
            return Collections.emptyList();
        } catch (Throwable t) {
            if (FAILED_PATTERNS.size() > 256) {
                FAILED_PATTERNS.clear();
            }
            FAILED_PATTERNS.add(pattern);
            LOG.warn("[gtmbsockets] could not walk a structure pattern; skipping it", t);
            return Collections.emptyList();
        }
    }

    private static CandidateSet candidateSet(SimplePredicate simple) {
        CandidateSet cached = CANDIDATES.get(simple);
        if (cached != null) {
            return cached;
        }
        Set<Item> items = new HashSet<>();
        Set<Block> blocks = new HashSet<>();
        try {
            BlockInfo[] infos = simple.candidates == null ? null : simple.candidates.get();
            if (infos != null) {
                for (BlockInfo info : infos) {
                    if (info == null) {
                        continue;
                    }
                    BlockState state = info.getBlockState();
                    if (state == null) {
                        continue;
                    }
                    Block block = state.m_60734_();
                    if (block == Blocks.f_50016_) {
                        continue;
                    }
                    blocks.add(block);
                    ItemStack stack = info.getItemStackForm();
                    if (stack != null && !stack.m_41619_()) {
                        items.add(stack.m_41720_());
                    }
                }
            }
        } catch (Throwable t) {
            LOG.warn("[gtmbsockets] could not read pattern candidates for {}", simple.type, t);
        }
        CandidateSet result = new CandidateSet(Set.copyOf(items), Set.copyOf(blocks));
        if (CANDIDATES.size() > 8192) {
            CANDIDATES.clear();
        }
        CANDIDATES.put(simple, result);
        return result;
    }

    public record Cell(BlockPos offset, TraceabilityPredicate predicate) {
    }

    private record CandidateSet(Set<Item> items, Set<Block> blocks) {
    }

    private record CachedPattern(BlockPattern pattern, long time) {
    }

    private record OrientationKey(BlockPattern pattern, Direction front, Direction up, boolean flipped) {
    }
}
