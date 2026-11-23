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
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;

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
 * so filtering the ten-thousands of cells of a large structure costs one array read per cell;</li>
 * <li>{@link Cells#accepted} pre-filters the layout by the held item and keeps that filtered view
 * cached, so a preview pass never walks cells that cannot take the held part. This is what keeps a
 * hundred-thousand-cell structure from exhausting the scan budget before it reaches a socket.</li>
 * </ul>
 *
 * <p>
 * Walking a hundred-thousand-cell pattern is far too expensive for a single client tick, so the
 * layout is compiled in small time slices: {@link #beginTick()} opens a budget, and
 * {@link #cellsFor} / {@link #advancePending()} only spend that budget. {@code cellsFor} returns
 * {@code null} while the layout is still being compiled; callers simply skip the overlay for that
 * frame. The finished layout is cached, so the cost is paid once per structure and orientation.
 *
 * <p>
 * Memory is bounded on purpose, because a long session can visit many mega structures:
 * <ul>
 * <li>finished layouts live in a least-recently-used cache whose cell budget is derived from the
 * client heap size, so visiting new structures evicts old ones instead of growing forever;</li>
 * <li>only the most recently used layout keeps an item-filtered copy, so accepted-cell arrays
 * cannot accumulate;</li>
 * <li>a single layout larger than a heap-derived limit is refused outright, and the positions
 * under compilation at once are capped, so one enormous structure cannot exhaust the heap.</li>
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

    private static final int MAX_CACHED_LAYOUTS = 24;
    /** Rough retained bytes per cached cell: packed offset, predicate id and section tables. */
    private static final long CACHE_BYTES_PER_CELL = 24L;
    /** Rough peak bytes per cell while one layout is being compiled. */
    private static final long BUILD_BYTES_PER_CELL = 40L;
    private static final int MAX_PENDING_LAYOUTS = 4;
    private static final int MAX_CANDIDATE_CACHE = 8192;
    private static final int MAX_BOUNDS_CACHE = 128;

    /** Wall-clock budget one client tick may spend compiling layouts. */
    private static final long TICK_BUDGET_NANOS = 1_500_000L;
    /** How many cells are walked between two clock reads. */
    private static final int CHECK_INTERVAL = 256;

    private static final int STAGE_WALK = 0;
    private static final int STAGE_BUCKET = 1;
    private static final int STAGE_SORT = 2;
    private static final int STAGE_DONE = 3;

    /** Layouts at or above this cell count are treated as mega structures by the progress overlay. */
    public static final long HUGE_LAYOUT_CELLS = 100_000L;

    private static final Map<MultiblockMachineDefinition, BlockPattern> PATTERNS = new IdentityHashMap<>();
    private static final Map<Class<?>, Boolean> OVERRIDES_PATTERN = new HashMap<>();
    private static final Map<MultiblockControllerMachine, CachedPattern> DYNAMIC_PATTERNS = new WeakHashMap<>();
    /** Access-ordered so the least recently used layout is evicted first under memory pressure. */
    private static final Map<OrientationKey, Cells> CELLS = new LinkedHashMap<>(32, 0.75F, true);
    private static final Map<OrientationKey, Build> PENDING = new LinkedHashMap<>();
    private static final Map<BoundsKey, double[]> BOUNDS = new HashMap<>();
    private static final Map<SimplePredicate, CandidateSet> CANDIDATES = new IdentityHashMap<>();
    private static final Map<SimplePredicate, ItemDecision> ITEM_DECISIONS = new IdentityHashMap<>();
    private static final Map<SimplePredicate, BlockDecision> BLOCK_DECISIONS = new IdentityHashMap<>();
    private static final Set<BlockPattern> FAILED_PATTERNS = Collections.newSetFromMap(new IdentityHashMap<>());

    private static long cachedCells;
    private static long pendingCells;
    private static long tickDeadline;
    private static volatile boolean accessorFailed;
    /** Only the most recently used layout keeps a filtered copy, so accepted cells cannot pile up. */
    private static Cells acceptedOwner;
    private static Item acceptedItem;
    private static Accepted acceptedLayout;

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

    /** Starts a new layout-compilation budget for this client tick. */
    public static void beginTick() {
        tickDeadline = System.nanoTime() + TICK_BUDGET_NANOS;
    }

    /**
     * Live progress of the layout of {@code pattern}, or {@code null} when that structure is not
     * being indexed right now. The progress overlay uses this so it never reports the background
     * compilation of a structure the player is no longer looking at.
     */
    public static Progress progress(BlockPattern pattern) {
        if (pattern == null) {
            return null;
        }
        Build largest = null;
        for (Build build : PENDING.values()) {
            if (build.key.pattern() != pattern) {
                continue;
            }
            if (largest == null || build.totalCells > largest.totalCells) {
                largest = build;
            }
        }
        return largest == null ? null : new Progress(largest.visitedCells, largest.totalCells, largest.stage);
    }

    /** @param stage {@link #STAGE_WALK} while positions are still being walked, then the index stages. */
    public record Progress(long done, long total, int stage) {

        public boolean walking() {
            return this.stage == STAGE_WALK;
        }
    }

    /** Retained-cell budget for the whole layout cache, derived from the client heap size. */
    private static long cacheCellBudget() {
        long bytes = Runtime.getRuntime().maxMemory() / 24L;
        return Math.max(250_000L, Math.min(6_000_000L, bytes / CACHE_BYTES_PER_CELL));
    }

    /** A single layout larger than this is refused, so one mega structure cannot exhaust the heap. */
    private static long maxLayoutCells() {
        long bytes = Runtime.getRuntime().maxMemory() / 10L;
        return Math.max(1_000_000L, Math.min(24_000_000L, bytes / BUILD_BYTES_PER_CELL));
    }

    /** Total positions that may be under compilation at the same time. */
    private static long maxPendingCells() {
        return maxLayoutCells();
    }

    /** Advances every layout that was requested earlier, within the current tick budget. */
    public static void advancePending() {
        if (PENDING.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<OrientationKey, Build>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext() && System.nanoTime() < tickDeadline) {
            Map.Entry<OrientationKey, Build> entry = iterator.next();
            Cells result = entry.getValue().step(tickDeadline);
            if (result != null) {
                pendingCells -= entry.getValue().totalCells;
                iterator.remove();
                publish(entry.getKey(), entry.getValue(), result);
            }
        }
    }

    /**
     * @return the cells of this pattern for one orientation, or {@code null} while the layout is
     *         still being compiled. The returned layout is shared and must not be mutated.
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
        if (accessorFailed || FAILED_PATTERNS.contains(pattern)) {
            return Cells.EMPTY;
        }
        Build build = PENDING.get(key);
        if (build == null) {
            if (PENDING.size() >= MAX_PENDING_LAYOUTS || pendingCells >= maxPendingCells()) {
                return null;
            }
            build = new Build(key, pattern, front, up, flipped);
            PENDING.put(key, build);
            pendingCells += build.totalCells;
        }
        Cells result = build.step(tickDeadline);
        if (result == null) {
            return null;
        }
        pendingCells -= build.totalCells;
        PENDING.remove(key);
        publish(key, build, result);
        return result;
    }

    private static void publish(OrientationKey key, Build build, Cells cells) {
        if (build.failed()) {
            FAILED_PATTERNS.add(key.pattern());
            return;
        }
        if (cells.count() == 0) {
            // Cache the empty layout so a pattern without socket cells is never re-walked.
            CELLS.put(key, Cells.EMPTY);
            trimCache(key);
            return;
        }
        CELLS.put(key, cells);
        cachedCells += cells.count();
        trimCache(key);
    }

    /**
     * Evicts least recently used layouts until the cache is back inside its size and memory
     * budget. Unlike a full clear, hot structures survive and a session that visits many mega
     * structures keeps a bounded footprint.
     */
    private static void trimCache(OrientationKey keep) {
        if (CELLS.size() <= MAX_CACHED_LAYOUTS && cachedCells <= cacheCellBudget()) {
            return;
        }
        long budget = cacheCellBudget();
        Iterator<Map.Entry<OrientationKey, Cells>> iterator = CELLS.entrySet().iterator();
        while (iterator.hasNext() && (CELLS.size() > MAX_CACHED_LAYOUTS || cachedCells > budget)) {
            Map.Entry<OrientationKey, Cells> eldest = iterator.next();
            if (eldest.getKey().equals(keep)) {
                continue;
            }
            Cells evicted = eldest.getValue();
            cachedCells -= evicted.count();
            if (acceptedOwner == evicted) {
                acceptedOwner = null;
                acceptedItem = null;
                acceptedLayout = null;
            }
            iterator.remove();
        }
    }

    /**
     * Conservative test whether a controller-relative block can be a cell of this pattern in
     * either orientation. Building a layout for a structure the player merely looks at from
     * outside is the most expensive thing this class can do, so a cheap transformed bounding box
     * rejects every unrelated multiblock before a single cell is indexed.
     */
    public static boolean mayContain(BlockPattern pattern, Direction front, Direction up, int dx, int dy, int dz) {
        if (pattern == null) {
            return false;
        }
        double[] bounds = boundsFor(pattern, front, up);
        if (bounds == null) {
            return true;
        }
        return dx >= bounds[0] - 1.0D && dx <= bounds[3] + 1.0D &&
                dy >= bounds[1] - 1.0D && dy <= bounds[4] + 1.0D &&
                dz >= bounds[2] - 1.0D && dz <= bounds[5] + 1.0D;
    }

    private static double[] boundsFor(BlockPattern pattern, Direction front, Direction up) {
        BoundsKey key = new BoundsKey(pattern, front, up);
        double[] cached = BOUNDS.get(key);
        if (cached != null) {
            return cached;
        }
        if (accessorFailed || FAILED_PATTERNS.contains(pattern)) {
            return null;
        }
        double[] computed;
        try {
            computed = computeBounds(pattern, front, up);
        } catch (Throwable failure) {
            computed = null;
        }
        if (computed == null) {
            return null;
        }
        if (BOUNDS.size() >= MAX_BOUNDS_CACHE) {
            BOUNDS.clear();
        }
        BOUNDS.put(key, computed);
        return computed;
    }

    private static double[] computeBounds(BlockPattern pattern, Direction front, Direction up) {
        BlockPatternAccessor accessor = accessor(pattern);
        if (accessor == null) {
            return null;
        }
        TraceabilityPredicate[][][] matches = accessor.gtlcore$blockMatches();
        int[] center = accessor.gtlcore$centerOffset();
        if (matches == null || matches.length == 0 || matches[0] == null || matches[0].length == 0 ||
                center == null || center.length < 5 || pattern.aisleRepetitions == null) {
            return null;
        }
        int fingerLength = matches.length;
        int thumbLength = matches[0].length;
        int palmLength = matches[0][0] == null ? 0 : matches[0][0].length;
        if (palmLength == 0) {
            return null;
        }
        int totalSlices = 0;
        int minZ = -center[4];
        boolean findFirstAisle = false;
        int z = minZ;
        for (int c = 0; c < fingerLength; c++) {
            int repetition = 0;
            while (findFirstAisle ? repetition < pattern.aisleRepetitions[c][1] : z <= -center[3]) {
                findFirstAisle = true;
                z++;
                repetition++;
                totalSlices++;
            }
        }
        if (totalSlices == 0) {
            return null;
        }
        int x0 = -center[0];
        int x1 = x0 + palmLength - 1;
        int y0 = -center[1];
        int y1 = y0 + thumbLength - 1;
        int z0 = minZ;
        int z1 = minZ + totalSlices - 1;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZv = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int flip = 0; flip < 2; flip++) {
            boolean flipped = flip == 1;
            for (int corner = 0; corner < 8; corner++) {
                int cx = (corner & 1) == 0 ? x0 : x1;
                int cy = (corner & 2) == 0 ? y0 : y1;
                int cz = (corner & 4) == 0 ? z0 : z1;
                BlockPos offset = accessor.gtlcore$setActualRelativeOffset(cx, cy, cz, front, up, flipped);
                if (offset == null) {
                    continue;
                }
                minX = Math.min(minX, offset.getX());
                minY = Math.min(minY, offset.getY());
                minZv = Math.min(minZv, offset.getZ());
                maxX = Math.max(maxX, offset.getX());
                maxY = Math.max(maxY, offset.getY());
                maxZ = Math.max(maxZ, offset.getZ());
            }
        }
        if (minX > maxX) {
            return null;
        }
        return new double[] { minX, minY, minZv, maxX, maxY, maxZ };
    }

    private static BlockPatternAccessor accessor(BlockPattern pattern) {
        try {
            return (BlockPatternAccessor) (Object) pattern;
        } catch (ClassCastException | LinkageError failure) {
            accessorFailed = true;
            GTLCore.LOGGER.error("BlockPattern accessor is not available; socket preview is disabled", failure);
            return null;
        }
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
     * Resumable layout compilation. The pattern walk is driven by a precomputed list of aisle
     * slices {@code (c, z)}; every slice expands to {@code thumbLength * palmLength} cells, so the
     * walk can stop between any two cells and continue exactly where it left off on a later tick.
     */
    private static final class Build {

        private final OrientationKey key;
        private final BlockPattern pattern;
        private final Direction front;
        private final Direction up;
        private final boolean flipped;
        private final BlockPatternAccessor accessor;
        private final TraceabilityPredicate[][][] matches;
        private final int[] center;
        private final int thumbLength;
        private final int palmLength;
        private final int[] sliceIsle;
        private final int[] sliceZ;
        private final int sliceCount;
        private final long totalCells;
        private long visitedCells;
        private final LongArrayList offsets;
        private final ShortArrayList ids;
        private final ObjectArrayList<TraceabilityPredicate> predicates = new ObjectArrayList<>();
        private final IdentityHashMap<TraceabilityPredicate, Short> predicateIds = new IdentityHashMap<>();
        private int stage = STAGE_WALK;
        private int slice;
        private int b;
        private int a;
        private int checks;
        private boolean failed;
        private Long2ObjectOpenHashMap<IntArrayList> buckets;
        private int bucketCursor;
        private int bucketChecks;
        private long[] sectionKeys;
        private int[] sectionStarts;
        private long[] orderedOffsets;
        private short[] orderedIds;
        private int sortSection;
        private int sortCursor;
        private Cells result;

        Build(OrientationKey key, BlockPattern pattern, Direction front, Direction up, boolean flipped) {
            this.key = key;
            this.pattern = pattern;
            this.front = front;
            this.up = up;
            this.flipped = flipped;
            BlockPatternAccessor patternAccessor = null;
            TraceabilityPredicate[][][] patternMatches = null;
            int[] patternCenter = null;
            int[] isle = new int[0];
            int[] zs = new int[0];
            try {
                patternAccessor = accessor(pattern);
                if (patternAccessor != null) {
                    patternMatches = patternAccessor.gtlcore$blockMatches();
                    patternCenter = patternAccessor.gtlcore$centerOffset();
                }
            } catch (Throwable failure) {
                patternMatches = null;
            }
            int thumb = 0;
            int palm = 0;
            if (patternMatches != null && patternMatches.length > 0 && patternMatches[0] != null &&
                    patternMatches[0].length > 0 && patternCenter != null && patternCenter.length >= 5 &&
                    pattern.aisleRepetitions != null && pattern.aisleRepetitions.length >= patternMatches.length) {
                thumb = patternMatches[0].length;
                palm = patternMatches[0][0] == null ? 0 : patternMatches[0][0].length;
                if (palm > 0) {
                    IntArrayList isles = new IntArrayList();
                    IntArrayList slices = new IntArrayList();
                    int minZ = -patternCenter[4];
                    boolean findFirstAisle = false;
                    int z = minZ;
                    for (int c = 0; c < patternMatches.length; c++) {
                        int repetition = 0;
                        while (findFirstAisle ? repetition < pattern.aisleRepetitions[c][1] : z <= -patternCenter[3]) {
                            isles.add(c);
                            slices.add(z);
                            findFirstAisle = true;
                            z++;
                            repetition++;
                        }
                    }
                    isle = isles.toIntArray();
                    zs = slices.toIntArray();
                }
            }
            this.accessor = patternAccessor;
            this.matches = patternMatches;
            this.center = patternCenter;
            this.thumbLength = thumb;
            this.palmLength = palm;
            this.sliceIsle = isle;
            this.sliceZ = zs;
            this.sliceCount = isle.length;
            this.totalCells = (long) sliceCount * thumb * palm;
            boolean tooLarge = this.totalCells > maxLayoutCells();
            // Pre-size the walk buffers: growing an eight-million-entry list would otherwise hold
            // both the old and the new array at the same time during the walk. An oversized layout
            // is refused before anything is allocated for it.
            int capacity = tooLarge ? 0 : (int) Math.min(this.totalCells, Integer.MAX_VALUE - 8L);
            this.offsets = new LongArrayList(capacity);
            this.ids = new ShortArrayList(capacity);
            if (patternAccessor == null || patternMatches == null || patternCenter == null || sliceCount == 0) {
                this.failed = true;
                this.result = Cells.EMPTY;
            } else if (tooLarge) {
                // One layout this large would need hundreds of megabytes of transient arrays. Refuse
                // it instead of letting a single mega structure exhaust the heap.
                this.failed = true;
                this.result = Cells.EMPTY;
                GTLCore.LOGGER.warn(
                        "Structure pattern {} has {} positions, above the socket preview limit of {}; skipping it",
                        pattern.getClass().getName(), this.totalCells, maxLayoutCells());
            }
        }

        boolean failed() {
            return failed;
        }

        OrientationKey key() {
            return key;
        }

        /** @return the finished layout, or {@code null} when the tick budget ran out first. */
        Cells step(long deadline) {
            if (result != null) {
                return result;
            }
            while (true) {
                switch (stage) {
                    case STAGE_WALK -> {
                        if (!walk(deadline)) {
                            return null;
                        }
                        if (failed) {
                            result = Cells.EMPTY;
                            return result;
                        }
                        stage = STAGE_BUCKET;
                    }
                    case STAGE_BUCKET -> {
                        if (!bucket(deadline)) {
                            return null;
                        }
                        stage = STAGE_SORT;
                    }
                    case STAGE_SORT -> {
                        if (!sort(deadline)) {
                            return null;
                        }
                        stage = STAGE_DONE;
                    }
                    default -> {
                        result = new Cells(sectionKeys, sectionStarts, orderedOffsets, orderedIds,
                                predicates.toArray(new TraceabilityPredicate[0]));
                        return result;
                    }
                }
            }
        }

        private boolean walk(long deadline) {
            while (slice < sliceCount) {
                int c = sliceIsle[slice];
                int z = sliceZ[slice];
                while (b < thumbLength) {
                    int y = -center[1] + b;
                    while (a < palmLength) {
                        this.visitedCells++;
                        if (++checks >= CHECK_INTERVAL) {
                            checks = 0;
                            if (System.nanoTime() >= deadline) {
                                return false;
                            }
                        }
                        int ax = a++;
                        int x = -center[0] + ax;
                        TraceabilityPredicate predicate = matches[c][b][ax];
                        if (predicate == null || predicate.isController) {
                            continue;
                        }
                        BlockPos offset = accessor.gtlcore$setActualRelativeOffset(x, y, z, front, up, flipped);
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
                            failed = true;
                            return true;
                        }
                        long packed = pack(offX, offY, offZ);
                        offsets.add(packed);
                        ids.add(predicateId(predicate));
                    }
                    a = 0;
                    b++;
                }
                b = 0;
                slice++;
            }
            return true;
        }

        private short predicateId(TraceabilityPredicate predicate) {
            Short existing = predicateIds.get(predicate);
            if (existing != null) {
                return existing;
            }
            if (predicates.size() >= Short.MAX_VALUE) {
                failed = true;
                return 0;
            }
            short id = (short) predicates.size();
            predicates.add(predicate);
            predicateIds.put(predicate, id);
            return id;
        }

        private boolean bucket(long deadline) {
            int count = offsets.size();
            if (buckets == null) {
                buckets = new Long2ObjectOpenHashMap<>();
            }
            while (bucketCursor < count) {
                if (++bucketChecks >= CHECK_INTERVAL) {
                    bucketChecks = 0;
                    if (System.nanoTime() >= deadline) {
                        return false;
                    }
                }
                long packed = offsets.getLong(bucketCursor);
                long section = sectionKeyOf(packed);
                IntArrayList bucket = buckets.get(section);
                if (bucket == null) {
                    bucket = new IntArrayList();
                    buckets.put(section, bucket);
                }
                bucket.add(bucketCursor);
                bucketCursor++;
            }
            sectionKeys = buckets.keySet().toLongArray();
            Arrays.sort(sectionKeys);
            sectionStarts = new int[sectionKeys.length + 1];
            orderedOffsets = new long[count];
            orderedIds = new short[count];
            return true;
        }

        private boolean sort(long deadline) {
            while (sortSection < sectionKeys.length) {
                if (System.nanoTime() >= deadline) {
                    return false;
                }
                IntArrayList bucket = buckets.get(sectionKeys[sortSection]);
                int start = sortCursor;
                for (int k = 0; k < bucket.size(); k++) {
                    int source = bucket.getInt(k);
                    orderedOffsets[sortCursor] = offsets.getLong(source);
                    orderedIds[sortCursor] = ids.getShort(source);
                    sortCursor++;
                }
                // Sorting a copy would double the per-cell footprint for the life of the layout.
                // Sorting offsets and ids together keeps membership lookups and the preview walk
                // on the same array pair.
                sortPairs(orderedOffsets, orderedIds, start, sortCursor);
                sectionStarts[sortSection] = start;
                sectionStarts[sortSection + 1] = sortCursor;
                sortSection++;
            }
            return true;
        }
    }

    /**
     * Sorts matching {@code offsets} and {@code ids} slices by offset in place. A dedicated pair
     * sort avoids keeping a second, sorted copy of every offset for the lifetime of the layout,
     * which is a large share of the cache footprint on mega structures.
     */
    private static void sortPairs(long[] offsets, short[] ids, int from, int to) {
        int size = to - from;
        if (size < 2) {
            return;
        }
        if (size < 12) {
            for (int i = from + 1; i < to; i++) {
                long offset = offsets[i];
                short id = ids[i];
                int j = i - 1;
                while (j >= from && offsets[j] > offset) {
                    offsets[j + 1] = offsets[j];
                    ids[j + 1] = ids[j];
                    j--;
                }
                offsets[j + 1] = offset;
                ids[j + 1] = id;
            }
            return;
        }
        long pivot = median(offsets[from], offsets[from + size / 2], offsets[to - 1]);
        int less = from;
        int index = from;
        int greater = to;
        while (index < greater) {
            long value = offsets[index];
            if (value < pivot) {
                swapPair(offsets, ids, less, index);
                less++;
                index++;
            } else if (value > pivot) {
                greater--;
                swapPair(offsets, ids, index, greater);
            } else {
                index++;
            }
        }
        sortPairs(offsets, ids, from, less);
        sortPairs(offsets, ids, greater, to);
    }

    private static void swapPair(long[] offsets, short[] ids, int first, int second) {
        if (first == second) {
            return;
        }
        long offset = offsets[first];
        offsets[first] = offsets[second];
        offsets[second] = offset;
        short id = ids[first];
        ids[first] = ids[second];
        ids[second] = id;
    }

    private static long median(long a, long b, long c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    /**
     * Flat, section-indexed cell layout of one structure orientation.
     *
     * <p>
     * {@link #offset(int)} / {@link #predicateId(int)} walk every cell once; a section can be
     * visited on its own through {@link #sectionStart(long)} and {@link #sectionEnd(long)}, so a
     * preview only pays for the part of a huge multiblock that is actually near the player.
     */
    public static final class Cells {

        private static final Cells EMPTY = new Cells(new long[0], new int[] { 0 }, new long[0], new short[0],
                new TraceabilityPredicate[0]);

        private final long[] sectionKeys;
        private final int[] sectionStarts;
        /** Packed offsets, sorted within every section slice. */
        private final long[] offsets;
        private final short[] predicateIds;
        private final TraceabilityPredicate[] predicates;

        private Cells(long[] sectionKeys, int[] sectionStarts, long[] offsets, short[] predicateIds,
                      TraceabilityPredicate[] predicates) {
            this.sectionKeys = sectionKeys;
            this.sectionStarts = sectionStarts;
            this.offsets = offsets;
            this.predicateIds = predicateIds;
            this.predicates = predicates;
        }

        /**
         * The cells that accept {@code item}, grouped by section exactly like this layout.
         *
         * <p>
         * Without this filter a preview pass has to walk every pattern cell in the sections
         * around the player, and on a hundred-thousand-cell structure the visit cap is exhausted
         * long before the nearby sections that actually accept the held part are reached. With it
         * the walk only pays for candidate sockets, so the highlight covers the whole render range
         * no matter how large the structure is.
         *
         * <p>
         * Only the most recently used layout keeps its filtered copy (see {@code acceptedOwner} in
         * {@link PatternSockets}), so visiting many structures cannot accumulate filtered arrays.
         */
        public Accepted accepted(Item item) {
            if (item == null) {
                return Accepted.EMPTY;
            }
            if (acceptedOwner == this && acceptedItem == item && acceptedLayout != null) {
                return acceptedLayout;
            }
            boolean[] acceptedByPredicate = new boolean[this.predicates.length];
            for (int id = 0; id < acceptedByPredicate.length; id++) {
                acceptedByPredicate[id] = accepts(this.predicates[id], item);
            }
            LongArrayList acceptedOffsets = new LongArrayList();
            ShortArrayList acceptedIds = new ShortArrayList();
            int[] starts = new int[this.sectionKeys.length + 1];
            for (int section = 0; section < this.sectionKeys.length; section++) {
                starts[section] = acceptedOffsets.size();
                for (int index = this.sectionStarts[section]; index < this.sectionStarts[section + 1]; index++) {
                    short predicateId = this.predicateIds[index];
                    if (!acceptedByPredicate[predicateId]) {
                        continue;
                    }
                    acceptedOffsets.add(this.offsets[index]);
                    acceptedIds.add(predicateId);
                }
            }
            starts[this.sectionKeys.length] = acceptedOffsets.size();
            Accepted result = acceptedOffsets.isEmpty() ? Accepted.EMPTY :
                    new Accepted(acceptedOffsets.toLongArray(), acceptedIds.toShortArray(), this.sectionKeys, starts);
            acceptedOwner = this;
            acceptedItem = item;
            acceptedLayout = result;
            return result;
        }

        public int count() {
            return this.offsets.length;
        }

        public long offset(int index) {
            return this.offsets[index];
        }

        public int predicateId(int index) {
            return this.predicateIds[index];
        }

        public int predicateCount() {
            return this.predicates.length;
        }

        public TraceabilityPredicate predicate(int id) {
            return this.predicates[id];
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
            return Arrays.binarySearch(this.offsets, this.sectionStarts[index],
                    this.sectionStarts[index + 1], packedOffset) >= 0;
        }
    }

    /**
     * Item-filtered cell index of one layout. It keeps the layout's section keys and start table
     * (a section without candidates simply has an empty slice), so a preview pass walks the same
     * sections but only touches cells that accept the held part.
     */
    public static final class Accepted {

        private static final Accepted EMPTY = new Accepted(new long[0], new short[0], new long[0], new int[] { 0 });

        private final long[] offsets;
        private final short[] predicateIds;
        private final long[] sectionKeys;
        private final int[] sectionStarts;

        private Accepted(long[] offsets, short[] predicateIds, long[] sectionKeys, int[] sectionStarts) {
            this.offsets = offsets;
            this.predicateIds = predicateIds;
            this.sectionKeys = sectionKeys;
            this.sectionStarts = sectionStarts;
        }

        public int count() {
            return this.offsets.length;
        }

        public long offset(int index) {
            return this.offsets[index];
        }

        public int predicateId(int index) {
            return this.predicateIds[index];
        }

        /** @return first candidate index of the section, or {@code -1} when the layout has none. */
        public int sectionStart(long sectionKey) {
            int index = Arrays.binarySearch(this.sectionKeys, sectionKey);
            return index < 0 ? -1 : this.sectionStarts[index];
        }

        /** @return exclusive end of the section slice, or {@code -1} when the layout has none. */
        public int sectionEnd(long sectionKey) {
            int index = Arrays.binarySearch(this.sectionKeys, sectionKey);
            return index < 0 ? -1 : this.sectionStarts[index + 1];
        }
    }

    private record CandidateSet(Set<Item> items, Set<Block> blocks) {}

    private record ItemDecision(Item item, boolean accepted) {}

    private record BlockDecision(Block block, boolean accepted) {}

    private record CachedPattern(BlockPattern pattern, long time) {}

    private record OrientationKey(BlockPattern pattern, Direction front, Direction up, boolean flipped) {}

    private record BoundsKey(BlockPattern pattern, Direction front, Direction up) {}
}
