package org.gtlcore.gtlcore.client.socketpreview;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.api.pattern.BlockPatternAccessor;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;

import com.lowdragmc.lowdraglib.utils.BlockInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Turns a GregTech {@link BlockPattern} into a section-indexed cell layout and answers whether a
 * cell accepts a particular part item or block.
 *
 * <p>
 * The layout is the flat, allocation-free form of the pattern cells:
 * <ul>
 * <li>every cell offset is packed into a single {@code long} (21 bits per axis), so a huge
 * multiblock no longer allocates one {@link BlockPos} per cell;</li>
 * <li>cells are grouped by their 16x16x16 section, and every section slice is additionally kept
 * sorted so {@code contains} is a binary search instead of a linear scan. The multiblock that
 * owns a looked-at casing can therefore be resolved without walking cell lists;</li>
 * <li>one layout per (pattern, front, up, flipped) combination and one candidate item/block set
 * per {@link SimplePredicate}, plus the last decision per predicate for the currently held item,
 * so filtering the ten-thousands of cells of a large structure costs one array read per cell.</li>
 * </ul>
 */
public final class PatternSockets {

    /** Bits per axis used by {@link #pack(int, int, int)} and {@link #sectionKey(int, int, int)}. */
    private static final int OFFSET_BITS = 21;
    private static final long OFFSET_MASK = (1L << OFFSET_BITS) - 1L;
    private static final int OFFSET_SHIFT_Y = OFFSET_BITS;
    private static final int OFFSET_SHIFT_X = OFFSET_BITS * 2;
    private static final int OFFSET_SIGN_SHIFT = 32 - OFFSET_BITS;
    /** Packed offsets cover +-1048575 blocks, far beyond any structure pattern. */
    private static final int OFFSET_LIMIT = 1 << (OFFSET_BITS - 1);

    private static final int MAX_CACHED_LAYOUTS = 32;
    private static final long MAX_CACHED_CELLS = 4_000_000L;
    private static final int MAX_CANDIDATE_CACHE = 8192;

    private static final Map<MultiblockMachineDefinition, BlockPattern> PATTERNS = new IdentityHashMap<>();
    private static final Map<Class<?>, Boolean> OVERRIDES_PATTERN = new HashMap<>();
    private static final Map<MultiblockControllerMachine, CachedPattern> DYNAMIC_PATTERNS = new WeakHashMap<>();
    private static final Map<OrientationKey, Cells> CELLS = new HashMap<>();
    private static final Map<SimplePredicate, CandidateSet> CANDIDATES = new IdentityHashMap<>();
    private static final Map<SimplePredicate, ItemDecision> ITEM_DECISIONS = new IdentityHashMap<>();
    private static final Map<SimplePredicate, BlockDecision> BLOCK_DECISIONS = new IdentityHashMap<>();
    private static final Set<BlockPattern> FAILED_PATTERNS = Collections.newSetFromMap(new IdentityHashMap<>());

    private static long cachedCells;
    private static volatile boolean accessorFailed;

    private PatternSockets() {}

    public static BlockPattern patternFor(MultiblockControllerMachine controller) {
        // A few controllers (cleanroom, charcoal pile, GTLCore simulation machine, ...) build
        // their pattern dynamically instead of using the definition factory. Honour them and
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
            } catch (Throwable failure) {
                GTLCore.LOGGER.warn("Could not build dynamic multiblock pattern for {}",
                        controller.getClass().getName(), failure);
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
        } catch (Throwable failure) {
            GTLCore.LOGGER.warn("Could not build structure pattern for {}", definition.getName(), failure);
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
        } catch (Throwable failure) {
            overrides = true;
        }
        OVERRIDES_PATTERN.put(type, overrides);
        return overrides;
    }

    /**
     * @return the cells of this pattern for one orientation. The returned layout is shared and
     *         must not be mutated.
     */
    public static Cells cellsFor(BlockPattern pattern, Direction front, Direction up, boolean flipped) {
        if (pattern == null) {
            return Cells.EMPTY;
        }
        OrientationKey key = new OrientationKey(pattern, front, up, flipped);
        Cells cached = CELLS.get(key);
        if (cached != null) {
            return cached;
        }
        if (CELLS.size() >= MAX_CACHED_LAYOUTS || cachedCells >= MAX_CACHED_CELLS) {
            CELLS.clear();
            cachedCells = 0L;
        }
        Cells computed = computeCells(pattern, front, up, flipped);
        if (computed.count() > MAX_CACHED_CELLS) {
            // One enormous layout may evict the smaller ones, but never stay uncached: dropping
            // the layout would rebuild it on every preview pass.
            CELLS.clear();
            cachedCells = 0L;
        }
        CELLS.put(key, computed);
        cachedCells += computed.count();
        return computed;
    }

    /** Same layout key the {@link Cells} index uses; section indices are relative to the controller. */
    public static long sectionKey(int sectionX, int sectionY, int sectionZ) {
        return ((long) (sectionX & 0x1FFFFF) << OFFSET_SHIFT_X) |
                ((long) (sectionY & 0x1FFFFF) << OFFSET_SHIFT_Y) |
                (sectionZ & 0x1FFFFF);
    }

    /** Packs a controller-relative offset into one long so cells need no per-cell object. */
    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << OFFSET_SHIFT_X) |
                ((long) (y & 0x1FFFFF) << OFFSET_SHIFT_Y) |
                (z & 0x1FFFFF);
    }

    public static boolean isPackable(int value) {
        return value > -OFFSET_LIMIT && value < OFFSET_LIMIT;
    }

    public static int unpackX(long packed) {
        int bits = ((int) (packed >> OFFSET_SHIFT_X)) & (int) OFFSET_MASK;
        return (bits << OFFSET_SIGN_SHIFT) >> OFFSET_SIGN_SHIFT;
    }

    public static int unpackY(long packed) {
        int bits = ((int) (packed >> OFFSET_SHIFT_Y)) & (int) OFFSET_MASK;
        return (bits << OFFSET_SIGN_SHIFT) >> OFFSET_SIGN_SHIFT;
    }

    public static int unpackZ(long packed) {
        int bits = ((int) packed) & (int) OFFSET_MASK;
        return (bits << OFFSET_SIGN_SHIFT) >> OFFSET_SIGN_SHIFT;
    }

    /** Section key of a packed controller-relative offset. */
    public static long sectionKeyOf(long packed) {
        return sectionKey(unpackX(packed) >> 4, unpackY(packed) >> 4, unpackZ(packed) >> 4);
    }

    public static boolean accepts(TraceabilityPredicate predicate, Item item) {
        if (predicate == null || item == null) {
            return false;
        }
        for (SimplePredicate simple : predicate.common) {
            if (acceptsItem(simple, item)) {
                return true;
            }
        }
        for (SimplePredicate simple : predicate.limited) {
            if (acceptsItem(simple, item)) {
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
            if (acceptsBlock(simple, block)) {
                return true;
            }
        }
        for (SimplePredicate simple : predicate.limited) {
            if (acceptsBlock(simple, block)) {
                return true;
            }
        }
        return false;
    }

    private static boolean acceptsItem(SimplePredicate simple, Item item) {
        ItemDecision decision = ITEM_DECISIONS.get(simple);
        if (decision != null && decision.item() == item) {
            return decision.accepted();
        }
        boolean accepted = candidateSet(simple).items().contains(item);
        if (ITEM_DECISIONS.size() > MAX_CANDIDATE_CACHE) {
            ITEM_DECISIONS.clear();
        }
        ITEM_DECISIONS.put(simple, new ItemDecision(item, accepted));
        return accepted;
    }

    private static boolean acceptsBlock(SimplePredicate simple, Block block) {
        BlockDecision decision = BLOCK_DECISIONS.get(simple);
        if (decision != null && decision.block() == block) {
            return decision.accepted();
        }
        boolean accepted = candidateSet(simple).blocks().contains(block);
        if (BLOCK_DECISIONS.size() > MAX_CANDIDATE_CACHE) {
            BLOCK_DECISIONS.clear();
        }
        BLOCK_DECISIONS.put(simple, new BlockDecision(block, accepted));
        return accepted;
    }

    private static Cells computeCells(BlockPattern pattern, Direction front, Direction up, boolean flipped) {
        if (accessorFailed || FAILED_PATTERNS.contains(pattern)) {
            return Cells.EMPTY;
        }
        BlockPatternAccessor accessor;
        try {
            accessor = (BlockPatternAccessor) (Object) pattern;
        } catch (ClassCastException | LinkageError failure) {
            accessorFailed = true;
            GTLCore.LOGGER.error("BlockPattern accessor is not available; socket preview is disabled", failure);
            return Cells.EMPTY;
        }
        try {
            TraceabilityPredicate[][][] matches = accessor.gtlcore$blockMatches();
            int[] center = accessor.gtlcore$centerOffset();
            if (matches == null || matches.length == 0 || matches[0] == null || matches[0].length == 0 ||
                    center == null || center.length < 5) {
                return Cells.EMPTY;
            }

            int fingerLength = matches.length;
            int thumbLength = matches[0].length;
            int palmLength = matches[0][0] == null ? 0 : matches[0][0].length;
            if (palmLength == 0 || pattern.aisleRepetitions == null ||
                    pattern.aisleRepetitions.length < fingerLength) {
                return Cells.EMPTY;
            }

            LongArrayList offsets = new LongArrayList();
            ObjectArrayList<TraceabilityPredicate> predicates = new ObjectArrayList<>();
            LongOpenHashSet seen = new LongOpenHashSet();

            // This loop mirrors BlockPattern#checkPatternAt exactly, including the aisle
            // repetitions. The only difference is that we do not evaluate the predicate here;
            // every non-controller cell is recorded and filtered by the held item later.
            int minZ = -center[4];
            boolean findFirstAisle = false;
            int z = minZ++;
            for (int c = 0; c < fingerLength; c++) {
                int repetition = 0;
                while (findFirstAisle ? repetition < pattern.aisleRepetitions[c][1] :
                        z <= -center[3]) {
                    for (int b = 0; b < thumbLength; b++) {
                        int y = -center[1] + b;
                        for (int a = 0; a < palmLength; a++) {
                            int x = -center[0] + a;
                            TraceabilityPredicate predicate = matches[c][b][a];
                            if (predicate == null || predicate.isController) {
                                continue;
                            }
                            BlockPos offset = accessor.gtlcore$setActualRelativeOffset(
                                    x, y, z, front, up, flipped);
                            if (offset == null) {
                                continue;
                            }
                            int offX = offset.getX();
                            int offY = offset.getY();
                            int offZ = offset.getZ();
                            if (!isPackable(offX) || !isPackable(offY) || !isPackable(offZ)) {
                                GTLCore.LOGGER.warn(
                                        "Structure pattern {} is too large for the socket preview index",
                                        pattern.getClass().getName());
                                FAILED_PATTERNS.add(pattern);
                                return Cells.EMPTY;
                            }
                            long packed = pack(offX, offY, offZ);
                            if (!seen.add(packed)) {
                                continue;
                            }
                            offsets.add(packed);
                            predicates.add(predicate);
                        }
                    }
                    findFirstAisle = true;
                    z++;
                    repetition++;
                }
            }
            return buildIndex(offsets, predicates);
        } catch (LinkageError failure) {
            accessorFailed = true;
            GTLCore.LOGGER.error("BlockPattern accessor failed; socket preview is disabled", failure);
            return Cells.EMPTY;
        } catch (Throwable failure) {
            if (FAILED_PATTERNS.size() > 256) {
                FAILED_PATTERNS.clear();
            }
            FAILED_PATTERNS.add(pattern);
            GTLCore.LOGGER.warn("Could not walk a structure pattern; skipping it", failure);
            return Cells.EMPTY;
        }
    }

    /**
     * Groups the collected cells by section, keeps the slices sorted for membership tests and
     * drops every temporary collection so only the flat arrays survive.
     */
    private static Cells buildIndex(LongArrayList offsets, ObjectArrayList<TraceabilityPredicate> predicates) {
        int count = offsets.size();
        if (count == 0) {
            return Cells.EMPTY;
        }
        Long2ObjectOpenHashMap<IntArrayList> buckets = new Long2ObjectOpenHashMap<>();
        for (int i = 0; i < count; i++) {
            long key = sectionKeyOf(offsets.getLong(i));
            IntArrayList bucket = buckets.get(key);
            if (bucket == null) {
                bucket = new IntArrayList();
                buckets.put(key, bucket);
            }
            bucket.add(i);
        }

        long[] sectionKeys = buckets.keySet().toLongArray();
        Arrays.sort(sectionKeys);

        int[] sectionStarts = new int[sectionKeys.length + 1];
        long[] orderedOffsets = new long[count];
        TraceabilityPredicate[] orderedPredicates = new TraceabilityPredicate[count];
        long[] sortedOffsets = new long[count];
        int cursor = 0;
        for (int section = 0; section < sectionKeys.length; section++) {
            IntArrayList bucket = buckets.get(sectionKeys[section]);
            sectionStarts[section] = cursor;
            for (int k = 0; k < bucket.size(); k++) {
                int source = bucket.getInt(k);
                orderedOffsets[cursor] = offsets.getLong(source);
                orderedPredicates[cursor] = predicates.get(source);
                cursor++;
            }
            System.arraycopy(orderedOffsets, sectionStarts[section], sortedOffsets, sectionStarts[section],
                    cursor - sectionStarts[section]);
            Arrays.sort(sortedOffsets, sectionStarts[section], cursor);
        }
        sectionStarts[sectionKeys.length] = cursor;
        return new Cells(sectionKeys, sectionStarts, orderedOffsets, orderedPredicates, sortedOffsets);
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
                    Block block = state.getBlock();
                    if (block == Blocks.AIR) {
                        continue;
                    }
                    blocks.add(block);
                    ItemStack stack = info.getItemStackForm();
                    if (stack != null && !stack.isEmpty()) {
                        items.add(stack.getItem());
                    }
                }
            }
        } catch (Throwable failure) {
            GTLCore.LOGGER.warn("Could not read pattern candidates for {}", simple.type, failure);
        }
        CandidateSet result = new CandidateSet(Set.copyOf(items), Set.copyOf(blocks));
        if (CANDIDATES.size() > MAX_CANDIDATE_CACHE) {
            CANDIDATES.clear();
        }
        CANDIDATES.put(simple, result);
        return result;
    }

    /**
     * Flat, section-indexed cell layout of one structure orientation.
     *
     * <p>
     * {@link #offset(int)} / {@link #predicate(int)} walk every cell once; a section can be
     * visited on its own through {@link #sectionStart(long)} and {@link #sectionEnd(long)}, so a
     * preview only pays for the part of a huge multiblock that is actually near the player.
     */
    public static final class Cells {

        private static final Cells EMPTY = new Cells(new long[0], new int[] { 0 }, new long[0],
                new TraceabilityPredicate[0], new long[0]);

        private final long[] sectionKeys;
        private final int[] sectionStarts;
        private final long[] offsets;
        private final TraceabilityPredicate[] predicates;
        private final long[] sortedOffsets;

        private Cells(long[] sectionKeys, int[] sectionStarts, long[] offsets,
                      TraceabilityPredicate[] predicates, long[] sortedOffsets) {
            this.sectionKeys = sectionKeys;
            this.sectionStarts = sectionStarts;
            this.offsets = offsets;
            this.predicates = predicates;
            this.sortedOffsets = sortedOffsets;
        }

        public int count() {
            return this.offsets.length;
        }

        public long offset(int index) {
            return this.offsets[index];
        }

        public TraceabilityPredicate predicate(int index) {
            return this.predicates[index];
        }

        /** @return first cell index of the section, or {@code -1} when the pattern has none there. */
        public int sectionStart(long sectionKey) {
            int index = Arrays.binarySearch(this.sectionKeys, sectionKey);
            return index < 0 ? -1 : this.sectionStarts[index];
        }

        /** @return exclusive end of the section slice, or {@code -1} when the pattern has none there. */
        public int sectionEnd(long sectionKey) {
            int index = Arrays.binarySearch(this.sectionKeys, sectionKey);
            return index < 0 ? -1 : this.sectionStarts[index + 1];
        }

        /** Binary-search membership test for a packed controller-relative offset. */
        public boolean contains(long packedOffset) {
            int index = Arrays.binarySearch(this.sectionKeys, sectionKeyOf(packedOffset));
            if (index < 0) {
                return false;
            }
            return Arrays.binarySearch(this.sortedOffsets, this.sectionStarts[index],
                    this.sectionStarts[index + 1], packedOffset) >= 0;
        }
    }

    private record CandidateSet(Set<Item> items, Set<Block> blocks) {}

    private record ItemDecision(Item item, boolean accepted) {}

    private record BlockDecision(Block block, boolean accepted) {}

    private record CachedPattern(BlockPattern pattern, long time) {}

    private record OrientationKey(BlockPattern pattern, Direction front, Direction up, boolean flipped) {}
}
