/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.lighting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.voxelregion.forge.NativePaletteImage;
import org.voxelregion.forge.RegionBlockStateContainer;
import org.voxelregion.lighting.ClearedChunkLighting;
import org.voxelregion.lighting.GlobalLightField;
import org.voxelregion.lighting.OpenRegionLighting;

/**
 * Optical capture on the native lighting owner. All callbacks use the native world
 * context; detached snapshots and cell updates never retain a callback or read a world.
 * Host direct-sky blockers are already represented by opacity and directed DOWN
 * faces. Explicit stop bits remain false: the numerical API supports independent
 * stops, but this adapter has no additional source for that separate condition.
 */
public final class WorldOptics {
    private static final int MATERIAL_PAGE_SHIFT = 12;
    private static final int MATERIAL_PAGE_SIZE = 1 << MATERIAL_PAGE_SHIFT;
    private static final Direction[] FACES = {Direction.WEST, Direction.EAST, Direction.DOWN,
            Direction.UP, Direction.NORTH, Direction.SOUTH};
    private static final Optical AIR = new Optical(0, 0, null);
    private static final Optical CLOSED = new Optical(15, 0, null);
    private static final Map<BlockState, Optical> STATIC = new ConcurrentHashMap<>();
    private static final OpticalTileCache TILES = new OpticalTileCache();
    private static final LongAdder PREPARED_INDEXED = new LongAdder(), PREPARED_SCAN = new LongAdder();
    private static final ClassValue<Boolean> DEFAULT_EMISSION = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("getLightEmission", BlockState.class, BlockGetter.class, BlockPos.class)
                        .getDeclaringClass().getName().equals("net.minecraftforge.common.extensions.IForgeBlock");
            } catch (ReflectiveOperationException unavailable) {
                return false;
            }
        }
    };

    private record Optical(int opacity, int emission, VoxelShape[] faces) { }

    /** Identity order is z * columnsX + x; absent chunks are represented by null. */
    public record Capture(GlobalLightField.Snapshot snapshot, Object[] identities) {
        public Capture {
            Objects.requireNonNull(snapshot, "snapshot");
            identities = Arrays.copyOf(identities, identities.length, Object[].class);
        }
        @Override public Object[] identities() { return identities.clone(); }
    }

    /**
     * Owner-frozen input for worker assembly. No world, live chunk or section is
     * retained. Section indices are detached native copies; contextual cells and
     * all paired face obstructions have already been evaluated on the owner. Shape
     * tiles retain only their immutable scalar optics, not their material arrays.
     * The worker never invokes shape or block callbacks.
     */
    public static final class PreparedCapture {
        private final int width, height, depth, columnsX, columnsZ, sections;
        private final boolean sky;
        private final boolean indexSources;
        private final boolean[] present;
        private final PreparedSection[] plans;
        private final GlobalLightField.Snapshot.BlockedPage[] blocked;

        private PreparedCapture(int columnsX, int columnsZ, int height, boolean sky,
                boolean[] present, PreparedSection[] plans, GlobalLightField.Snapshot.BlockedPage[] blocked) {
            this.columnsX = columnsX; this.columnsZ = columnsZ;
            width = columnsX * 16; depth = columnsZ * 16; this.height = height;
            sections = height >>> 4; this.sky = sky;
            this.present = present; this.plans = plans; this.blocked = blocked;
            boolean completeTiles = true;
            for (PreparedSection plan : plans) if (plan != null && plan.cells != null) {
                completeTiles = false;
                break;
            }
            // This is input-representation dispatch, not a guessed density threshold.
            // Packed scalar plans would retain the scan path. This preparation path
            // freezes contextual values as uncached immutable scalar tiles as well;
            // it never adds contextual results to the process-wide static tile cache.
            indexSources = completeTiles;
            // Preparation diagnostics only: later admission/retry/cancellation may
            // prevent this input from becoming a completed or published result.
            (indexSources ? PREPARED_INDEXED : PREPARED_SCAN).increment();
        }

        /** The builder is created, written and consumed by this calling worker. */
        public GlobalLightField.Snapshot materialize() {
            try (var builder = new GlobalLightField.Snapshot.Builder(width, height, depth, indexSources)) {
                for (int cz = 0; cz < columnsZ; cz++) for (int cx = 0; cx < columnsX; cx++) {
                    int column = cx + columnsX * cz;
                    if (sky && present[column]) for (int z = 0; z < 16; z++) {
                        int start = cx * 16 + width * (cz * 16 + z);
                        builder.setSkyColumns(start, start + 16, true);
                    }
                    for (int section = 0; section < sections; section++) {
                        PreparedSection plan = plans[column * sections + section];
                        if (plan == null) continue; // Proven air: zero-initialized scalar channels.
                        int sy = section << 4;
                        if (plan.uniform != null) {
                            Optical value = plan.uniform;
                            builder.fillOpticalTile(cx * 16, sy, cz * 16, value.opacity, value.emission, false);
                        } else if (plan.palette != null || plan.tile != null) {
                            // FrozenPalette is private owner-produced eligibility evidence.
                            // This cache entry performs no BlockState/world/shape callbacks.
                            GlobalLightField.Snapshot.OpticalTile tile = plan.tile != null
                                    ? plan.tile : TILES.getValidated(plan.palette).optics;
                            builder.copyOpticalTile(cx * 16, sy, cz * 16, tile);
                        } else {
                            for (int cell = 0; cell < 4096; cell++) {
                                int value = plan.cells[cell] & 255;
                                if (value == 0) continue;
                                int opacity = value & 15;
                                builder.setOptics(index(cx * 16 + (cell & 15), sy + (cell >>> 8),
                                        cz * 16 + ((cell >>> 4) & 15)), opacity, value >>> 4, false);
                            }
                        }
                    }
                }
                if (blocked != null) for (int page = 0; page < blocked.length; page++)
                    if (blocked[page] != null) builder.copyBlockedPage(page << MATERIAL_PAGE_SHIFT, blocked[page]);
                return builder.consume();
            }
        }

        private int index(int x, int y, int z) { return x + width * (z + depth * y); }
    }

    /** Already-decoded prepared tiles retain only scalar optics, never shape materials. */
    private record PreparedSection(Optical uniform, FrozenPalette palette,
            GlobalLightField.Snapshot.OpticalTile tile, byte[] cells) { }

    /** Created only after owner-side eligibility and all optical callbacks have completed. */
    private record FrozenPalette(NativePaletteImage image, Optical[] palette) { }

    /**
     * Detached full-height AIR/BEDROCK proof, including the complete calculation halo.
     * Masks are owned here and never exposed. The solver may regard the outside as closed
     * only because callers publish columns at least one complete chunk inside this domain.
     */
    public static final class OpenCapture {
        private final int width, depth, height, side, band;
        private final boolean sky;
        private final Map<Integer, BitSet> opaque;

        private OpenCapture(int width, int depth, int height, boolean sky, Map<Integer, BitSet> opaque) {
            this.width = width; this.depth = depth; this.height = height;
            this.side = Math.max(width, depth); this.sky = sky;
            this.opaque = Map.copyOf(opaque);
            int retainedBand = 16;
            for (BitSet mask : opaque.values())
                retainedBand = Math.max(retainedBand, ((mask.length() + 4095) / 4096) * 16);
            this.band = retainedBand;
        }
        public int width() { return width; }
        public int depth() { return depth; }
        public int side() { return side; }
        /** Input band volume, not an instruction count or a full-height voxel scan. */
        public long bandCells() { return (long)width * depth * band * 256; }
        /** Owned masks, byte light, int frontier, packed bands, and per-column containers. */
        public long estimatedWorkingBytes() {
            long columns = (long)width * depth;
            return 65_536L + columns * (256L + (height * 256L + 7) / 8) + 6L * bandCells();
        }
        public Map<Integer, ClearedChunkLighting.Result> solve() {
            return OpenRegionLighting.solve(height, side, opaque, sky);
        }
    }

    private WorldOptics() { }

    /**
     * Returns null unless every sampled column is completely proved. This fresh capture
     * follows the same native-owner lifetime validation as the general snapshot; it is
     * not a persistent cache and never trusts a saved "previously cleared" marker.
     */
    public static OpenCapture tryCaptureOpen(LightChunkGetter source, int minChunkX, int minChunkZ,
            int columnsX, int columnsZ, int minY, int height, boolean sky) {
        Domain domain = new Domain(source, minChunkX, minChunkZ, columnsX, columnsZ, minY, height);
        BlockPos proofPosition = new BlockPos(domain.minX, minY, domain.minZ);
        // Identity alone is not an optical proof: pack hooks may alter vanilla materials.
        if (!openMaterial(Blocks.AIR.defaultBlockState(), domain.world, proofPosition, 0)
                || !openMaterial(Blocks.CAVE_AIR.defaultBlockState(), domain.world, proofPosition, 0)
                || !openMaterial(Blocks.VOID_AIR.defaultBlockState(), domain.world, proofPosition, 0)
                || !openMaterial(Blocks.BEDROCK.defaultBlockState(), domain.world, proofPosition, 15)) return null;
        int side = Math.max(columnsX, columnsZ);
        Map<Integer, BitSet> masks = new HashMap<>();
        for (int cz = 0; cz < columnsZ; cz++) for (int cx = 0; cx < columnsX; cx++) {
            if (!(domain.chunks[cx + columnsX * cz] instanceof ChunkAccess access)) return null;
            BitSet opaque = new BitSet();
            for (int sy = 0; sy < height; sy += 16) {
                int sectionIndex = access.getSectionIndex(minY + sy);
                if (sectionIndex < 0 || sectionIndex >= access.getSectionsCount()) return null;
                LevelChunkSection section = access.getSection(sectionIndex);
                var states = section.getStates();
                if (states.getClass() != PalettedContainer.class && states.getClass() != RegionBlockStateContainer.class)
                    return null;
                // Count alone is insufficient: custom air and unused fluid palette entries
                // are deliberately resolved by the detached actual storage below.
                if (section.hasOnlyAir() && !section.maybeHas(state -> !exactVanillaAir(state))) continue;
                NativePaletteImage image = NativePaletteImage.capture(states);
                if (image == null || image.states.isEmpty()) return null;
                byte[] kinds = new byte[image.states.size()];
                boolean onlyAir = true, onlyBedrock = true;
                for (int p = 0; p < kinds.length; p++) {
                    BlockState state = image.states.get(p);
                    byte kind = (byte)(exactVanillaAir(state) ? 0
                            : state == Blocks.BEDROCK.defaultBlockState() ? 1 : -1);
                    kinds[p] = kind;
                    onlyAir &= kind == 0; onlyBedrock &= kind == 1;
                }
                if (onlyAir) continue;
                int base = sy * 256;
                if (onlyBedrock) { opaque.set(base, base + 4096); continue; }
                for (int cell = 0; cell < 4096; cell++) {
                    int kind = kinds[image.indices.get(cell)];
                    // Reject actual fluids, gravity blocks, emitters, shape blocks and
                    // all other materials; unused palette IDs do not alter the terrain.
                    if (kind < 0) return null;
                    if (kind == 1) opaque.set(base + cell);
                }
            }
            masks.put(cx + side * cz, opaque);
        }
        return new OpenCapture(columnsX, columnsZ, height, sky, masks);
    }

    /** Full-height bit masks plus one bounded palette copy; no full-height material array. */
    public static long estimatedOpenProofBytes(int columnsX, int columnsZ, int height) {
        if (columnsX < 3 || columnsZ < 3 || columnsX > 16 || columnsZ > 16
                || height < 16 || height > ClearedChunkLighting.MAX_HEIGHT || height % 16 != 0)
            throw new IllegalArgumentException("Invalid open proof dimensions");
        return Math.addExact(65_536L, Math.multiplyExact((long)columnsX * columnsZ, 256L + height * 32L));
    }

    private static boolean exactVanillaAir(BlockState state) {
        return state == Blocks.AIR.defaultBlockState() || state == Blocks.CAVE_AIR.defaultBlockState()
                || state == Blocks.VOID_AIR.defaultBlockState();
    }

    private static boolean openMaterial(BlockState state, BlockGetter world, BlockPos position, int opacity) {
        return constant(state) && !state.useShapeForLightOcclusion() && state.getFluidState().isEmpty()
                && state.getLightBlock(world, position) == opacity && state.getLightEmission(world, position) == 0;
    }

    /**
     * Conservative host-owned capture storage in addition to the numerical kernel's bound.
     * This retains the previous allowance for three input byte arrays, two cell bitsets,
     * and a sky-column bitset even though the consumed builder now transfers its arrays
     * directly into the immutable Snapshot. It also covers (only when necessary) material
     * paged references. Dynamic shaped states can retain one Optical and six-reference face array
     * per cell; 112 bytes covers their headers/alignment with eight-byte references.
     * Arbitrary allocations internal to a mod callback are outside this owned-storage bound.
     */
    public static long estimatedCaptureBytes(LightChunkGetter source, int minChunkX, int minChunkZ,
            int columnsX, int columnsZ, int minY, int height) {
        Domain domain = new Domain(source, minChunkX, minChunkZ, columnsX, columnsZ, minY, height);
        boolean shaped = false;
        long dynamicShaped = 0;
        for (LightChunk chunk : domain.chunks) {
            if (chunk == null) continue;
            if (!(chunk instanceof ChunkAccess access)) {
                shaped = true; dynamicShaped += height * 256L; continue;
            }
            for (int sy = 0; sy < height; sy += 16) {
                int index = access.getSectionIndex(minY + sy);
                if (index < 0 || index >= access.getSectionsCount()) {
                    shaped = true; dynamicShaped += 4096L; continue;
                }
                LevelChunkSection section = access.getSection(index);
                if (section.maybeHas(BlockState::useShapeForLightOcclusion)) shaped = true;
                if (section.maybeHas(state -> state.useShapeForLightOcclusion() && !constant(state)))
                    dynamicShaped += 4096L;
            }
        }
        return Math.addExact(65_536L + 64L * domain.chunks.length,
                Math.addExact(3L * domain.cells + (domain.cells + 3L) / 4 + (domain.plane + 7L) / 8,
                        Math.addExact(shaped ? 8L * domain.cells + 32L * ((domain.cells + 4095L) / 4096) : 0L,
                                Math.multiplyExact(112L, dynamicShaped))));
    }

    /**
     * The staged images survive together until worker assembly, unlike the synchronous
     * path's single temporary section. Keep their reservation in addition to the old
     * conservative capture allowance and the numerical solver's separate bound.
     * A validated local palette has at most 256 entries: at most 4096 packed bytes
     * plus two 256-entry reference arrays (eight-byte references), or two bytes/cell.
     * A predecoded shaped tile instead retains its two 4096-byte scalar channels;
     * contextual captures also retain two immutable scalar channels and their tile
     * metadata, without retaining the local input arrays or caching world values.
     * Paired face masks use
     * sparse 4096-byte immutable pages, at most one byte/cell. Four bytes/cell plus
     * 512/section covers these alternatives, array/container headers and plans. The
     * temporary mutable page is discarded immediately after each immutable page is
     * created, so freezing retains at most one extra 4096-byte page. A separate 256 KiB bounds the few strongly
     * held old/current/duplicate decoded tiles and scratch arrays during a cache
     * miss (under 170 KiB); their safety does not rely on remaining in the tile LRU.
     */
    public static long estimatedPreparedCaptureBytes(LightChunkGetter source, int minChunkX, int minChunkZ,
            int columnsX, int columnsZ, int minY, int height) {
        long original = estimatedCaptureBytes(source, minChunkX, minChunkZ, columnsX, columnsZ, minY, height);
        long cells = Math.multiplyExact(Math.multiplyExact((long)columnsX, columnsZ), height * 256L);
        long sections = cells >>> 12;
        return Math.addExact(original, Math.addExact(256L << 10,
                Math.addExact(Math.multiplyExact(4L, cells), Math.multiplyExact(512L, sections))));
    }

    /** Freezes every live read/callback before returning; materialize performs only detached work. */
    public static PreparedCapture prepareCapture(LightChunkGetter source, int minChunkX, int minChunkZ,
            int columnsX, int columnsZ, int minY, int height, boolean sky) {
        Domain domain = new Domain(source, minChunkX, minChunkZ, columnsX, columnsZ, minY, height);
        int sectionCount = height >>> 4;
        PreparedSection[] plans = new PreparedSection[domain.chunks.length * sectionCount];
        boolean[] present = new boolean[domain.chunks.length];
        Optical[][] materials = null;
        BitSet shaped = new BitSet(domain.cells);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int cz = 0; cz < columnsZ; cz++) for (int cx = 0; cx < columnsX; cx++) {
            int column = cx + columnsX * cz;
            LightChunk chunk = domain.chunks[column];
            if (chunk == null) {
                for (int section = 0; section < sectionCount; section++)
                    plans[column * sectionCount + section] = new PreparedSection(CLOSED, null, null, null);
                continue;
            }
            present[column] = true;
            for (int sy = 0; sy < height; sy += 16) {
                int planIndex = column * sectionCount + (sy >>> 4);
                LevelChunkSection section = null;
                if (chunk instanceof ChunkAccess access) {
                    int index = access.getSectionIndex(minY + sy);
                    if (index >= 0 && index < access.getSectionsCount()) section = access.getSection(index);
                }
                if (section != null && section.hasOnlyAir() && !section.maybeHas(state -> !vanillaAir(state))) continue;
                NativePaletteImage image = section == null ? null : NativePaletteImage.capture(section.getStates());
                // Establish the staged-memory bound before allocating the Optical[];
                // unknown or inconsistent palette layouts use owner scalar capture.
                if (image != null && (image.states.isEmpty() || image.states.size() > 256
                        || image.states.size() > (1 << image.indices.getBits()))) {
                    TILES.storageRejected.increment();
                    image = null;
                }
                Optical[] palette = image == null ? null : new Optical[image.states.size()];
                cursor.set(domain.minX + cx * 16, minY + sy, domain.minZ + cz * 16);
                if (palette != null) for (int p = 0; p < palette.length; p++) {
                    BlockState state = image.states.get(p);
                    if (constant(state)) palette[p] = optical(state, domain.world, cursor);
                }
                Optical uniform = uniformUnshaped(palette);
                if (uniform != null) {
                    if (uniform.opacity != 0 || uniform.emission != 0)
                        plans[planIndex] = new PreparedSection(uniform, null, null, null);
                    continue;
                }
                FrozenPalette frozen = freezePalette(image, palette);
                byte[] capturedOpacity = frozen == null ? new byte[4096] : null;
                byte[] capturedEmission = frozen == null ? new byte[4096] : null;
                GlobalLightField.Snapshot.OpticalTile scalar = null;
                boolean hasFaces = false;
                if (frozen != null) for (Optical value : palette) hasFaces |= value.faces != null;
                // Static shape tiles already cache the actual shaped positions. Keep
                // paired geometry on the owner, but visit only those positions, and
                // never retain the tile's 4096 material references in a prepared field.
                if (hasFaces) {
                    DecodedTile decoded = TILES.getValidated(frozen);
                    materials = copyShapedMaterials(domain, cx, cz, sy, decoded, materials, shaped);
                    scalar = decoded.optics;
                    frozen = null;
                } else if (frozen == null) for (int cell = 0; cell < 4096; cell++) {
                    int x = cx * 16 + (cell & 15), z = cz * 16 + ((cell >>> 4) & 15), y = sy + (cell >>> 8);
                    Optical value;
                    if (image == null) {
                        cursor.set(domain.minX + x, minY + y, domain.minZ + z);
                        value = optical(chunk.getBlockState(cursor), domain.world, cursor);
                    } else {
                        int id = image.indices.get(cell);
                        value = palette[id];
                        if (value == null) {
                            cursor.set(domain.minX + x, minY + y, domain.minZ + z);
                            value = optical(image.states.get(id), domain.world, cursor);
                        }
                    }
                    if (capturedOpacity != null) {
                        capturedOpacity[cell] = (byte)value.opacity;
                        capturedEmission[cell] = (byte)value.emission;
                    }
                    if (value.faces != null) {
                        int index = domain.index(x, y, z);
                        if (materials == null)
                            materials = new Optical[(domain.cells + MATERIAL_PAGE_SIZE - 1) >>> MATERIAL_PAGE_SHIFT][];
                        int page = index >>> MATERIAL_PAGE_SHIFT;
                        if (materials[page] == null) materials[page] = new Optical[MATERIAL_PAGE_SIZE];
                        materials[page][index & (MATERIAL_PAGE_SIZE - 1)] = value;
                        shaped.set(index);
                    }
                }
                if (capturedOpacity != null) {
                    // Per-capture, uncached data: all world callbacks finished on
                    // owner. This constructor includes defensive copies, validation
                    // and source metadata; none of those costs is treated as a hit.
                    scalar = new GlobalLightField.Snapshot.OpticalTile(capturedOpacity, capturedEmission);
                    capturedOpacity = null; capturedEmission = null;
                }
                plans[planIndex] = new PreparedSection(null, frozen, scalar, null);
            }
        }
        byte[][] blocked = shaped.isEmpty() ? null : new byte[domain.cells >>> MATERIAL_PAGE_SHIFT][];
        for (int index = shaped.nextSetBit(0); index >= 0; index = shaped.nextSetBit(index + 1)) {
            Optical first = materialAt(materials, index);
            for (int face = 0; face < 6; face++) {
                int neighbor = domain.neighbor(index, face);
                if (neighbor < 0) continue;
                Optical second = materialAt(materials, neighbor);
                if (occludes(first, second == null ? AIR : second, face)) {
                    addBlocked(blocked, index, 1 << face);
                    addBlocked(blocked, neighbor, 1 << (face ^ 1));
                }
            }
        }
        // Local Domain, world/chunk references, mutable cursor and dynamic shape
        // material pages end here. All masks include both sides of cross-section edges.
        GlobalLightField.Snapshot.BlockedPage[] frozenBlocked = null;
        if (blocked != null) {
            frozenBlocked = new GlobalLightField.Snapshot.BlockedPage[blocked.length];
            for (int page = 0; page < blocked.length; page++) if (blocked[page] != null) {
                frozenBlocked[page] = new GlobalLightField.Snapshot.BlockedPage(blocked[page]);
                blocked[page] = null; // Never retain two complete sets of face-mask pages.
            }
        }
        return new PreparedCapture(columnsX, columnsZ, height, sky, present, plans, frozenBlocked);
    }

    private static void addBlocked(byte[][] pages, int index, int mask) {
        int page = index >>> MATERIAL_PAGE_SHIFT;
        if (pages[page] == null) pages[page] = new byte[MATERIAL_PAGE_SIZE];
        pages[page][index & (MATERIAL_PAGE_SIZE - 1)] |= (byte)mask;
    }

    /** Private eligibility capability. No public boolean can bypass owner-side checks. */
    private static FrozenPalette freezePalette(NativePaletteImage image, Optical[] palette) {
        if (image == null || palette == null || palette.length == 0) return null;
        if (palette.length > 256 || palette.length > (1 << image.indices.getBits())) {
            TILES.storageRejected.increment();
            return null;
        }
        Class<?> storage = image.indices.getClass();
        if (storage != SimpleBitStorage.class && storage != ZeroBitStorage.class) {
            TILES.storageRejected.increment();
            return null;
        }
        for (int p = 0; p < palette.length; p++) {
            BlockState state = image.states.get(p);
            if (palette[p] == null || !constant(state)
                    || state.useShapeForLightOcclusion() && !state.canOcclude()) {
                TILES.contextual.increment();
                return null;
            }
        }
        return new FrozenPalette(image, palette);
    }

    public static Capture capture(LightChunkGetter source, int minChunkX, int minChunkZ,
            int columnsX, int columnsZ, int minY, int height, boolean sky) {
        Domain domain = new Domain(source, minChunkX, minChunkZ, columnsX, columnsZ, minY, height);
        GlobalLightField.Snapshot.Builder snapshot = new GlobalLightField.Snapshot.Builder(domain.width, height, domain.depth);
        // Only face-sensitive cells need material identities. Most terrain snapshots
        // have no such cells, and opacity/emission already live in primitive arrays.
        Optical[][] materials = null;
        BitSet shaped = new BitSet(domain.cells);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int cz = 0; cz < columnsZ; cz++) for (int cx = 0; cx < columnsX; cx++) {
            LightChunk chunk = domain.chunks[cx + columnsX * cz];
            if (chunk == null) {
                fillUnshapedColumn(domain, cx, cz, 0, height, CLOSED, snapshot);
                continue;
            }
            if (sky) for (int z = 0; z < 16; z++)
                snapshot.setSkyColumns(cx * 16 + domain.width * (cz * 16 + z), cx * 16 + 16 + domain.width * (cz * 16 + z), true);
            for (int sy = 0; sy < height; sy += 16) {
                LevelChunkSection section = null;
                if (chunk instanceof ChunkAccess access) {
                    int index = access.getSectionIndex(minY + sy);
                    if (index >= 0 && index < access.getSectionsCount()) section = access.getSection(index);
                }
                if (section != null && section.hasOnlyAir() && !section.maybeHas(state -> !vanillaAir(state))) continue;
                NativePaletteImage image = section == null ? null : NativePaletteImage.capture(section.getStates());
                Optical[] palette = image == null ? null : new Optical[image.states.size()];
                cursor.set(domain.minX + cx * 16, minY + sy, domain.minZ + cz * 16);
                if (palette != null) for (int p = 0; p < palette.length; p++) {
                    BlockState state = image.states.get(p);
                    if (constant(state)) {
                        palette[p] = optical(state, domain.world, cursor);
                    }
                }
                Optical uniform = uniformUnshaped(palette);
                if (uniform != null) {
                    fillUnshapedColumn(domain, cx, cz, sy, 16, uniform, snapshot);
                    continue;
                }
                DecodedTile tile = TILES.get(image, palette);
                if (tile != null) {
                    materials = copyTile(tile, domain, cx, cz, sy, snapshot, shaped, materials);
                    continue;
                }
                for (int i = 0; i < 4096; i++) {
                    int x = cx * 16 + (i & 15), z = cz * 16 + ((i >>> 4) & 15), y = sy + (i >>> 8);
                    int index = domain.index(x, y, z);
                    Optical value;
                    if (image == null) {
                        cursor.set(domain.minX + x, minY + y, domain.minZ + z);
                        value = optical(chunk.getBlockState(cursor), domain.world, cursor);
                    } else {
                        int p = image.indices.get(i);
                        value = palette[p];
                        if (value == null) {
                            cursor.set(domain.minX + x, minY + y, domain.minZ + z);
                            value = optical(image.states.get(p), domain.world, cursor);
                        }
                    }
                    if (value == AIR) continue;
                    snapshot.setOptics(index, value.opacity, value.emission, false);
                    if (value.faces != null) {
                        if (materials == null)
                            materials = new Optical[(domain.cells + MATERIAL_PAGE_SIZE - 1) >>> MATERIAL_PAGE_SHIFT][];
                        int page = index >>> MATERIAL_PAGE_SHIFT;
                        if (materials[page] == null) materials[page] = new Optical[MATERIAL_PAGE_SIZE];
                        materials[page][index & (MATERIAL_PAGE_SIZE - 1)] = value;
                        shaped.set(index);
                    }
                }
            }
        }
        for (int index = shaped.nextSetBit(0); index >= 0; index = shaped.nextSetBit(index + 1)) {
            Optical first = materialAt(materials, index);
            for (int face = 0; face < 6; face++) {
                int neighbor = domain.neighbor(index, face);
                if (neighbor < 0) continue;
                Optical second = materialAt(materials, neighbor);
                if (occludes(first, second == null ? AIR : second, face)) {
                    snapshot.orBlockedFaces(index, 1 << face);
                    snapshot.orBlockedFaces(neighbor, 1 << (face ^ 1));
                }
            }
        }
        return new Capture(snapshot.consume(), domain.chunks);
    }

    /** Process-wide bounded decoded tiles, independent of world/chunk identity or revision. */
    public static Map<String, Long> tileCacheStatistics() { return TILES.statistics(); }

    private static Optical[][] copyTile(DecodedTile tile, Domain domain, int cx, int cz, int sy,
            GlobalLightField.Snapshot.Builder snapshot, BitSet shaped, Optical[][] materials) {
        snapshot.copyOpticalTile(cx * 16, sy, cz * 16, tile.optics);
        return copyShapedMaterials(domain, cx, cz, sy, tile, materials, shaped);
    }

    private static Optical[][] copyShapedMaterials(Domain domain, int cx, int cz, int sy,
            DecodedTile tile, Optical[][] materials, BitSet shaped) {
        if (tile.materials != null) {
            if (materials == null)
                materials = new Optical[(domain.cells + MATERIAL_PAGE_SIZE - 1) >>> MATERIAL_PAGE_SHIFT][];
            for (int local = tile.shaped.nextSetBit(0); local >= 0; local = tile.shaped.nextSetBit(local + 1)) {
                int index = domain.index(cx * 16 + (local & 15), sy + (local >>> 8),
                        cz * 16 + ((local >>> 4) & 15));
                int page = index >>> MATERIAL_PAGE_SHIFT;
                if (materials[page] == null) materials[page] = new Optical[MATERIAL_PAGE_SIZE];
                materials[page][index & (MATERIAL_PAGE_SIZE - 1)] = tile.materials[local];
                shaped.set(index);
            }
        }
        // Face occlusion depends on both materials. The normal domain-wide pass below
        // recomputes every shaped edge, including edges crossing section/chunk boundaries.
        return materials;
    }

    private static final class DecodedTile {
        final GlobalLightField.Snapshot.OpticalTile optics;
        final Optical[] materials;
        final BitSet shaped;

        DecodedTile(NativePaletteImage image, Optical[] palette) {
            byte[] opacity = new byte[4096], emission = new byte[4096];
            Optical[] captured = null;
            BitSet selected = null;
            for (int local = 0; local < 4096; local++) {
                Optical value = palette[image.indices.get(local)];
                opacity[local] = (byte)value.opacity;
                emission[local] = (byte)value.emission;
                if (value.faces != null) {
                    if (captured == null) { captured = new Optical[4096]; selected = new BitSet(4096); }
                    captured[local] = value;
                    selected.set(local);
                }
            }
            optics = new GlobalLightField.Snapshot.OpticalTile(opacity, emission);
            materials = captured; shaped = selected;
        }

        long estimatedBytes(TileKey key) {
            // Eight-byte references, object/array headers, and amortized map/table
            // resize overhead are included. Optical objects already belong to STATIC;
            // a tile does not create or own their shapes. No full field is retained.
            return 512L + optics.estimatedBytes()
                    + (16 + key.words.length * 8L) + (32 + key.states.size() * 8L)
                    + (materials == null ? 0 : (16 + 4096 * 8L) + (40 + 64 * 8L));
        }
    }

    private static final class TileKey {
        final Class<?> storage;
        final int bits, size, hash;
        final List<BlockState> states;
        final long[] words;

        TileKey(NativePaletteImage image) {
            storage = image.indices.getClass(); bits = image.indices.getBits(); size = image.indices.getSize();
            states = image.states; words = image.indices.getRaw();
            int value = 31 * (31 * System.identityHashCode(storage) + bits) + size;
            for (BlockState state : states) value = 31 * value + System.identityHashCode(state);
            value = 31 * value + Arrays.hashCode(words);
            hash = value;
        }

        private TileKey(TileKey lookup) {
            storage = lookup.storage; bits = lookup.bits; size = lookup.size; hash = lookup.hash;
            states = lookup.states; // NativePaletteImage owns an immutable List.copyOf.
            words = lookup.words.clone();
        }

        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof TileKey key) || storage != key.storage || bits != key.bits || size != key.size
                    || states.size() != key.states.size() || !Arrays.equals(words, key.words)) return false;
            for (int i = 0; i < states.size(); i++) if (states.get(i) != key.states.get(i)) return false;
            return true;
        }
    }

    private static final class OpticalTileCache {
        private static final long MAX_BYTES = 64L << 20;
        private final LinkedHashMap<TileKey, DecodedTile> entries = new LinkedHashMap<>(16, 0.75f, true);
        private final LongAdder hits = new LongAdder(), misses = new LongAdder(), fills = new LongAdder();
        private final LongAdder contextual = new LongAdder(), storageRejected = new LongAdder();
        private final LongAdder evictions = new LongAdder(), duplicateFills = new LongAdder();
        private long bytes;

        DecodedTile get(NativePaletteImage image, Optical[] palette) {
            if (image == null || palette == null || palette.length == 0) return null;
            Class<?> storage = image.indices.getClass();
            if (storage != SimpleBitStorage.class && storage != ZeroBitStorage.class) {
                storageRejected.increment();
                return null;
            }
            for (int i = 0; i < palette.length; i++) {
                BlockState state = image.states.get(i);
                // hasDynamicShape=false proves the native opacity cache, but native
                // face-shape caching additionally requires canOcclude. Otherwise even
                // getFaceOcclusionShape can call the world-dependent block callback.
                // Reject all such palette entries, including entries not used currently.
                if (palette[i] == null || !constant(state)
                        || state.useShapeForLightOcclusion() && !state.canOcclude()) {
                    contextual.increment();
                    return null;
                }
            }
            return getValidated(new FrozenPalette(image, palette));
        }

        /** Only consumes a private owner-validated capability, never block callbacks. */
        private DecodedTile getValidated(FrozenPalette prepared) {
            NativePaletteImage image = prepared.image;
            Optical[] palette = prepared.palette;
            TileKey lookup = new TileKey(image);
            synchronized (this) {
                DecodedTile cached = entries.get(lookup);
                if (cached != null) { hits.increment(); return cached; }
            }
            misses.increment();
            // NativePaletteImage is already detached; neither key equality nor decoding
            // reads the live container. Decode outside the global bounded-cache lock.
            DecodedTile decoded = new DecodedTile(image, palette);
            TileKey frozen = new TileKey(lookup);
            long weight = decoded.estimatedBytes(frozen);
            synchronized (this) {
                DecodedTile raced = entries.get(lookup);
                if (raced != null) { duplicateFills.increment(); return raced; }
                if (weight > MAX_BYTES) return decoded;
                var oldest = entries.entrySet().iterator();
                while (bytes + weight > MAX_BYTES && oldest.hasNext()) {
                    var entry = oldest.next();
                    bytes -= entry.getValue().estimatedBytes(entry.getKey());
                    oldest.remove(); evictions.increment();
                }
                entries.put(frozen, decoded); bytes += weight; fills.increment();
            }
            return decoded;
        }

        synchronized Map<String, Long> statistics() {
            Map<String, Long> values = new LinkedHashMap<>();
            values.put("optical_tile_hits", hits.sum()); values.put("optical_tile_misses", misses.sum());
            values.put("optical_tile_fills", fills.sum()); values.put("optical_tile_duplicate_fills", duplicateFills.sum());
            values.put("optical_tile_contextual_rejected", contextual.sum());
            values.put("optical_tile_storage_rejected", storageRejected.sum());
            values.put("optical_tile_evictions", evictions.sum());
            values.put("optical_tile_entries", (long)entries.size()); values.put("optical_tile_bytes", bytes);
            values.put("optical_tile_max_bytes", MAX_BYTES);
            values.put("optical_prepared_indexed", PREPARED_INDEXED.sum());
            values.put("optical_prepared_scan", PREPARED_SCAN.sum());
            return Map.copyOf(values);
        }
    }

    private static Optical materialAt(Optical[][] pages, int index) {
        Optical[] page = pages[index >>> MATERIAL_PAGE_SHIFT];
        return page == null ? null : page[index & (MATERIAL_PAGE_SIZE - 1)];
    }

    private static Optical uniformUnshaped(Optical[] palette) {
        if (palette == null || palette.length == 0) return null;
        Optical first = palette[0];
        if (first == null || first.faces != null) return null;
        // Check every palette entry, including unused entries: a contextual state
        // must never be admitted to this shortcut based on another state's optics.
        for (int p = 1; p < palette.length; p++) {
            Optical next = palette[p];
            if (next == null || next.faces != null || next.opacity != first.opacity
                    || next.emission != first.emission) return null;
        }
        return first;
    }

    private static void fillUnshapedColumn(Domain domain, int cx, int cz, int fromY, int countY,
            Optical value, GlobalLightField.Snapshot.Builder snapshot) {
        if (value.opacity == 0 && value.emission == 0) return;
        // Both callers describe complete native sections; merge their metadata
        // once per x/z column rather than recording every scalar cell again.
        for (int y = fromY; y < fromY + countY; y += 16)
            snapshot.fillOpticalTile(cx * 16, y, cz * 16, value.opacity, value.emission, false);
    }

    /** Final outgoing faces are refreshed on each changed cell and its six neighbours. */
    public static List<GlobalLightField.CellUpdate> changes(LightChunkGetter source,
            int minChunkX, int minChunkZ, int columnsX, int columnsZ, int minY, int height,
            Collection<BlockPos> changed) {
        Domain domain = new Domain(source, minChunkX, minChunkZ, columnsX, columnsZ, minY, height);
        Set<BlockPos> affected = new HashSet<>();
        for (BlockPos position : changed) {
            if (domain.contains(position)) affected.add(position.immutable());
            for (Direction face : FACES) {
                BlockPos neighbor = position.relative(face);
                if (domain.contains(neighbor)) affected.add(neighbor);
            }
        }
        Map<BlockPos, Optical> captured = new HashMap<>();
        List<GlobalLightField.CellUpdate> updates = new ArrayList<>(affected.size());
        for (BlockPos position : affected) {
            Optical value = captured.computeIfAbsent(position, domain::optical);
            int mask = 0;
            for (int face = 0; face < 6; face++) {
                BlockPos neighbor = position.relative(FACES[face]);
                if (domain.contains(neighbor) && occludes(value,
                        captured.computeIfAbsent(neighbor, domain::optical), face)) mask |= 1 << face;
            }
            updates.add(new GlobalLightField.CellUpdate(position.getX() - domain.minX, position.getY() - minY,
                    position.getZ() - domain.minZ, value.opacity, value.emission, mask, false));
        }
        return List.copyOf(updates);
    }

    private static boolean vanillaAir(BlockState state) {
        return state.is(Blocks.AIR) || state.is(Blocks.CAVE_AIR) || state.is(Blocks.VOID_AIR);
    }

    private static boolean constant(BlockState state) {
        // State subclasses may override Forge's contextual emission independently
        // of their Block. Native cached face shapes additionally require canOcclude;
        // otherwise getFaceOcclusionShape still invokes a contextual block callback.
        // Unrecognized/dynamic paths are evaluated at every position.
        return state.getClass() == BlockState.class && !state.getBlock().hasDynamicShape()
                && (!state.useShapeForLightOcclusion() || state.canOcclude())
                && DEFAULT_EMISSION.get(state.getBlock().getClass());
    }

    private static Optical optical(BlockState state, BlockGetter world, BlockPos position) {
        if (vanillaAir(state)) return AIR;
        boolean constant = constant(state);
        if (constant) {
            Optical cached = STATIC.get(state);
            if (cached != null) return cached;
        }
        int opacity = Math.max(0, Math.min(15, state.getLightBlock(world, position)));
        int emission = Math.max(0, Math.min(15, state.getLightEmission(world, position)));
        VoxelShape[] faces = null;
        if (state.useShapeForLightOcclusion()) {
            faces = new VoxelShape[6];
            for (int face = 0; face < 6; face++) faces[face] = state.getFaceOcclusionShape(world, position, FACES[face]);
        }
        Optical value = opacity == 0 && emission == 0 && faces == null ? AIR : new Optical(opacity, emission, faces);
        if (constant) {
            Optical previous = STATIC.putIfAbsent(state, value);
            if (previous != null) return previous;
        }
        return value;
    }

    private static boolean occludes(Optical first, Optical second, int face) {
        if (first.faces == null && second.faces == null) return false;
        return Shapes.faceShapeOccludes(first.faces == null ? Shapes.empty() : first.faces[face],
                second.faces == null ? Shapes.empty() : second.faces[face ^ 1]);
    }

    private static final class Domain {
        final BlockGetter world;
        final int minX, minZ, minY, width, depth, height, plane, cells, columnsX;
        final LightChunk[] chunks;

        Domain(LightChunkGetter source, int minChunkX, int minChunkZ, int columnsX, int columnsZ, int minY, int height) {
            Objects.requireNonNull(source, "source");
            if (columnsX < 3 || columnsZ < 3 || columnsX > 16 || columnsZ > 16
                    || height < 16 || height > 4096 || height % 16 != 0 || (minY & 15) != 0)
                throw new IllegalArgumentException("Invalid full-height optical rectangle");
            this.minX = Math.multiplyExact(minChunkX, 16); this.minZ = Math.multiplyExact(minChunkZ, 16);
            this.minY = minY; this.height = height; this.columnsX = columnsX;
            width = columnsX * 16; depth = columnsZ * 16;
            Math.addExact(minX, width); Math.addExact(minZ, depth); Math.addExact(minY, height);
            plane = width * depth; cells = Math.multiplyExact(plane, height);
            if (cells > 16_777_216) throw new IllegalArgumentException("Optical rectangle too large");
            world = Objects.requireNonNull(source.getLevel(), "native callback world");
            if (minY != world.getMinBuildHeight() || height != world.getHeight())
                throw new IllegalArgumentException("Direct-sky capture requires the complete world height");
            chunks = new LightChunk[columnsX * columnsZ];
            for (int z = 0; z < columnsZ; z++) for (int x = 0; x < columnsX; x++)
                chunks[x + z * columnsX] = source.getChunkForLighting(minChunkX + x, minChunkZ + z);
        }

        int index(int x, int y, int z) { return x + width * (z + depth * y); }
        boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() < minX + width && pos.getY() >= minY && pos.getY() < minY + height
                    && pos.getZ() >= minZ && pos.getZ() < minZ + depth;
        }
        Optical optical(BlockPos pos) {
            LightChunk chunk = chunks[(pos.getX() - minX) / 16 + columnsX * ((pos.getZ() - minZ) / 16)];
            return chunk == null ? CLOSED : WorldOptics.optical(chunk.getBlockState(pos), world, pos);
        }
        int neighbor(int index, int face) {
            return switch (face) {
                case 0 -> index % width == 0 ? -1 : index - 1;
                case 1 -> index % width == width - 1 ? -1 : index + 1;
                case 2 -> index < plane ? -1 : index - plane;
                case 3 -> index >= cells - plane ? -1 : index + plane;
                case 4 -> index % plane < width ? -1 : index - width;
                case 5 -> index % plane >= plane - width ? -1 : index + width;
                default -> throw new IllegalArgumentException("Unknown face");
            };
        }
    }
}
