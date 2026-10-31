/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.lighting;

import java.util.BitSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An independently implemented, detached two-layer light field. Ordinary edits relax only
 * changed cells and their dependants, including decreases; they do not rebuild the region.
 * Every diffuse edge loses at least one light level. This makes the fixed point unique,
 * including after removal of the last source from a cycle of mutually illuminated cells.
 *
 * <p>The domain is a complete-height rectangle with a dark horizontal exterior. Direct sky
 * is an explicit proof above each vertical column, not an old light value. A world host
 * must capture enough terrain to include a 16-block halo around every published column,
 * validate all terrain revisions, and publish all columns affected by an edit. This class
 * never reads a world, mutable palette, foreign light array, or block callback.
 */
public final class GlobalLightField {
    public static final long DEFAULT_WORKING_BYTES = 256L * 1024 * 1024;
    private static final int INCREASE_PAGE_SIZE = 256;
    private static final int MAX_INCREASE_PAGES = 256;
    private static final int INDEX_MASK = (1 << 24) - 1;
    private static final int[] FACE = {1, 2, 4, 8, 16, 32};
    private static final int[] OPPOSITE_FACE = {2, 1, 8, 4, 32, 16};

    /** Index order: x + width * (z + depth * y). Face bits: W,E,D,U,N,S. */
    public static final class Snapshot {
        private final int width, height, depth, plane, cells;
        private final byte[] opacity, emission, blocked;
        private final BitSet stops, skyColumns;
        // Exclusive upper bound of any source or downward direct-sky obstruction.
        // Above it all block emission is zero and direct sky remains open. This is not
        // a shorter world: the resulting light field still covers the full height.
        private final int sourceScanEnd;
        // Optional exact sky cuts plus conservative emitter rows, produced only by
        // the checked Builder. Public/raw and incremental snapshots use the scan.
        private final int[] firstDirectY;
        private final BitSet emitterRows;

        public Snapshot(int width, int height, int depth, byte[] opacity, byte[] emission,
                byte[] blockedOutgoingFaces, BitSet stopDirectSky, BitSet directSkyColumns) {
            this.width = width; this.height = height; this.depth = depth;
            this.cells = checkedCells(width, height, depth); this.plane = width * depth;
            CopiedValues opticalOpacity = copyValues(opacity, cells, 15, 15, "opacity");
            CopiedValues opticalEmission = copyValues(emission, cells, 15, 15, "emission");
            CopiedValues opticalFaces = blockedOutgoingFaces == null ? new CopiedValues(new byte[cells], 0) :
                    copyValues(blockedOutgoingFaces, cells, 63, 4, "blocked faces");
            this.opacity = opticalOpacity.values; this.emission = opticalEmission.values;
            this.blocked = opticalFaces.values;
            this.stops = copyBits(stopDirectSky, cells, "direct sky stops");
            this.skyColumns = copyBits(directSkyColumns, plane, "direct sky columns");
            sourceScanEnd = Math.max(Math.max(opticalOpacity.relevantEnd, opticalEmission.relevantEnd),
                    Math.max(opticalFaces.relevantEnd, this.stops.length()));
            firstDirectY = null; emitterRows = null;
        }

        /** Only the consumed, validated builder may transfer these exclusively owned arrays. */
        private Snapshot(Builder builder, int sourceScanEnd) {
            width = builder.width; height = builder.height; depth = builder.depth;
            plane = builder.plane; cells = builder.cells;
            opacity = builder.opacity; emission = builder.emission; blocked = builder.blocked;
            stops = builder.stops; skyColumns = builder.skyColumns;
            this.sourceScanEnd = sourceScanEnd;
            firstDirectY = builder.firstDirectCandidate; emitterRows = builder.emitterRows;
        }

        private static int checkedCells(int width, int height, int depth) {
            if (width < 48 || depth < 48 || width > GeneralChunkLighting.MAX_HORIZONTAL_SIZE ||
                    depth > GeneralChunkLighting.MAX_HORIZONTAL_SIZE || width % 16 != 0 || depth % 16 != 0 ||
                    height < 16 || height > ClearedChunkLighting.MAX_HEIGHT || height % 16 != 0)
                throw new IllegalArgumentException("Invalid complete-height optical domain");
            long count = (long)width * depth * height;
            if (count > GeneralChunkLighting.MAX_CELLS) throw new IllegalArgumentException("Optical domain too large");
            return (int)count;
        }

        /**
         * An immutable, validated 16x16x16 optical tile. Inputs are defensively
         * copied before validation, and neither the arrays nor the row metadata
         * are exposed. Captures may reuse its checked rows across many fields.
         * This contains scalar optics only; world-dependent face tests stay with
         * the host, and stops remain a separate Builder operation.
         */
        public static final class OpticalTile {
            private static final int CELLS = 4096, ROWS = 256;
            private final byte[] opacity, emission;
            private final BitSet sourceRows;
            private final byte[] opacityTop;
            private final BitSet emitterRows;

            public OpticalTile(byte[] opacity, byte[] emission) {
                Objects.requireNonNull(opacity, "opacity"); Objects.requireNonNull(emission, "emission");
                if (opacity.length != CELLS || emission.length != CELLS)
                    throw new IllegalArgumentException("Optical tile requires 4096 cells per channel");
                this.opacity = opacity.clone(); this.emission = emission.clone();
                sourceRows = new BitSet(ROWS);
                opacityTop = new byte[256]; emitterRows = new BitSet(ROWS);
                for (int i = 0; i < CELLS; i++) {
                    int first = this.opacity[i] & 255, second = this.emission[i] & 255;
                    if (first > 15 || second > 15) throw new IllegalArgumentException("Invalid optical tile value");
                    if ((first | second) != 0) sourceRows.set(i >>> 4);
                    if (first != 0) opacityTop[i & 255] = (byte)((i >>> 8) + 1);
                    if (second != 0) emitterRows.set(i >>> 4);
                }
            }

            /** Conservative eight-byte-reference size, including owned channels and row bits. */
            public long estimatedBytes() { return 256L + 2L * (16 + CELLS)
                    + 2L * (48 + (ROWS / 64) * 8L) + (16 + 256); }
        }

        /**
         * An immutable page of 4096 directed face masks in linear field order. The
         * host may freeze only pages touched by shape occlusion, then copy them into
         * another thread's builder without retaining a full-domain temporary channel.
         */
        public static final class BlockedPage {
            private static final int CELLS = 4096;
            private final byte[] masks;
            private final int downEnd;

            public BlockedPage(byte[] masks) {
                Objects.requireNonNull(masks, "masks");
                if (masks.length != CELLS) throw new IllegalArgumentException("Face page requires 4096 cells");
                this.masks = masks.clone();
                downEnd = validateValues(this.masks, 63, 4, "blocked faces");
            }

            public long estimatedBytes() { return 64L + CELLS; }
        }

        /**
         * Single-owner optical capture with no exposed arrays or mutable views. Scalar
         * values, checked rows and ranges are checked on write. The legacy raw-row
         * method retains complete final validation at consume. The completed Snapshot
         * exclusively owns the final buffers.
         *
         * <p>Every operation, including consume/close, must run on the constructing
         * thread. This enforced ownership prevents a write racing with publication.
         * Consume is one-shot even if validation fails; close abandons unpublished work.
         * The ordinary public Snapshot constructor retains its defensive-copy contract.
         */
        public static final class Builder implements AutoCloseable {
            private final int width, height, depth, plane, cells;
            private final Thread owner = Thread.currentThread();
            private byte[] opacity, emission, blocked;
            private BitSet stops, skyColumns;
            private boolean closed, deferredValidation;
            // A monotone upper bound, trimmed against final contents at consume.
            // Overwriting or clearing an earlier source must not lower it prematurely.
            private int sourceCandidate;
            private int[] firstDirectCandidate;
            private BitSet emitterRows;

            public Builder(int width, int height, int depth) {
                this(width, height, depth, false);
            }

            /** Opts checked whole-tile captures into source indexing; scalar callers retain scan cost. */
            public Builder(int width, int height, int depth, boolean indexSources) {
                cells = checkedCells(width, height, depth);
                this.width = width; this.height = height; this.depth = depth; plane = width * depth;
                opacity = new byte[cells]; emission = new byte[cells]; blocked = new byte[cells];
                stops = new BitSet(cells); skyColumns = new BitSet(plane);
                if (indexSources) {
                    firstDirectCandidate = new int[plane]; emitterRows = new BitSet(cells >>> 4);
                }
            }

            /** Assigns final scalar optics at one linear x/z/y index; face masks are separate. */
            public void setOptics(int index, int opacity, int emission, boolean stopDirectSky) {
                writable(); Objects.checkIndex(index, cells); opticalValues(opacity, emission);
                this.opacity[index] = (byte)opacity; this.emission[index] = (byte)emission;
                stops.set(index, stopDirectSky);
                recordOptics(index, opacity != 0 || stopDirectSky, emission != 0);
                if (opacity != 0 || emission != 0 || stopDirectSky)
                    sourceCandidate = Math.max(sourceCandidate, index + 1);
            }

            /** Assigns a contiguous range, including clearing earlier values and stop bits. */
            public void fillOptics(int from, int to, int opacity, int emission, boolean stopDirectSky) {
                writable(); Objects.checkFromToIndex(from, to, cells); opticalValues(opacity, emission);
                Arrays.fill(this.opacity, from, to, (byte)opacity);
                Arrays.fill(this.emission, from, to, (byte)emission);
                stops.set(from, to, stopDirectSky);
                recordRange(from, to, opacity != 0 || stopDirectSky, emission != 0);
                if (from != to && (opacity != 0 || emission != 0 || stopDirectSky))
                    sourceCandidate = Math.max(sourceCandidate, to);
            }

            /**
             * Copies caller-owned byte slices without retaining either source. Stops
             * and face masks are independent. All offsets are checked before either
             * copy; final byte values are validated by consume, never trusted as optics.
             */
            public void copyOpticalRow(int target, byte[] opacity, int opacityOffset,
                    byte[] emission, int emissionOffset, int length) {
                writable(); Objects.requireNonNull(opacity, "opacity"); Objects.requireNonNull(emission, "emission");
                Objects.checkFromIndexSize(target, length, cells);
                Objects.checkFromIndexSize(opacityOffset, length, opacity.length);
                Objects.checkFromIndexSize(emissionOffset, length, emission.length);
                if (length == 0) return;
                deferredValidation = true;
                // Unknown caller bytes are still validated at consume; do not invent
                // an index from values not yet accepted as final scalar optics.
                firstDirectCandidate = null; emitterRows = null;
                sourceCandidate = Math.max(sourceCandidate, target + length);
                System.arraycopy(opacity, opacityOffset, this.opacity, target, length);
                System.arraycopy(emission, emissionOffset, this.emission, target, length);
            }

            /**
             * Copies at most sixteen optical cells with complete validation before any
             * destination write. Each input byte is read once into a local packed value;
             * concurrent caller-array changes cannot replace a validated value before
             * it is copied. Invalid values or ranges leave this builder unchanged.
             * Stops and face masks remain independent, as with the raw-row method.
             */
            public void copyCheckedOpticalRow(int target, byte[] opacity, int opacityOffset,
                    byte[] emission, int emissionOffset, int length) {
                writable(); Objects.requireNonNull(opacity, "opacity"); Objects.requireNonNull(emission, "emission");
                Objects.checkFromIndexSize(target, length, cells);
                Objects.checkFromIndexSize(opacityOffset, length, opacity.length);
                Objects.checkFromIndexSize(emissionOffset, length, emission.length);
                if (length > 16) throw new IllegalArgumentException("Checked optical row exceeds sixteen cells");
                if (length == 0) return;
                long packedOpacity = checkedRow(opacity, opacityOffset, length);
                long packedEmission = checkedRow(emission, emissionOffset, length);
                if ((packedOpacity | packedEmission) != 0)
                    sourceCandidate = Math.max(sourceCandidate, target + length);
                for (int i = 0; i < length; i++) {
                    this.opacity[target + i] = (byte)((packedOpacity >>> (i * 4)) & 15);
                    this.emission[target + i] = (byte)((packedEmission >>> (i * 4)) & 15);
                    recordOptics(target + i, this.opacity[target + i] != 0, this.emission[target + i] != 0);
                }
            }

            private static long checkedRow(byte[] source, int offset, int length) {
                long packed = 0;
                for (int i = 0; i < length; i++) {
                    int value = source[offset + i] & 255;
                    if (value > 15) throw new IllegalArgumentException("Invalid optical row value");
                    packed |= (long)value << (i * 4);
                }
                return packed;
            }

            /** Copies one prevalidated immutable tile row without repeating value checks. */
            public void copyOpticalTileRow(int target, OpticalTile tile, int row) {
                writable(); Objects.requireNonNull(tile, "tile");
                Objects.checkIndex(row, OpticalTile.ROWS); Objects.checkFromIndexSize(target, 16, cells);
                int from = row << 4;
                System.arraycopy(tile.opacity, from, opacity, target, 16);
                System.arraycopy(tile.emission, from, emission, target, 16);
                if (firstDirectCandidate != null) for (int i = 0; i < 16; i++)
                    recordOptics(target + i, tile.opacity[from + i] != 0, tile.emission[from + i] != 0);
                if (tile.sourceRows.get(row)) sourceCandidate = Math.max(sourceCandidate, target + 16);
            }

            /** Copies a complete scalar tile and merges its prevalidated source summaries. */
            public void copyOpticalTile(int x, int y, int z, OpticalTile tile) {
                writable(); Objects.requireNonNull(tile, "tile");
                Objects.checkFromIndexSize(x, 16, width); Objects.checkFromIndexSize(y, 16, height);
                Objects.checkFromIndexSize(z, 16, depth);
                for (int dy = 0; dy < 16; dy++) for (int dz = 0; dz < 16; dz++) {
                    int row = (dy << 4) | dz, from = row << 4;
                    int target = x + width * (z + dz + depth * (y + dy));
                    System.arraycopy(tile.opacity, from, opacity, target, 16);
                    System.arraycopy(tile.emission, from, emission, target, 16);
                    if (tile.sourceRows.get(row)) sourceCandidate = Math.max(sourceCandidate, target + 16);
                    if (emitterRows != null && tile.emitterRows.get(row))
                        emitterRows.set(target >>> 4, ((target + 15) >>> 4) + 1);
                }
                if (firstDirectCandidate != null) for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
                    int top = tile.opacityTop[dx | dz << 4] & 255;
                    if (top != 0) {
                        int column = x + dx + width * (z + dz);
                        firstDirectCandidate[column] = Math.max(firstDirectCandidate[column], y + top);
                    }
                }
            }

            /** Assigns one complete uniform16-cube with one summary merge per column. */
            public void fillOpticalTile(int x, int y, int z, int opacity, int emission, boolean stopDirectSky) {
                writable(); opticalValues(opacity, emission);
                Objects.checkFromIndexSize(x, 16, width); Objects.checkFromIndexSize(y, 16, height);
                Objects.checkFromIndexSize(z, 16, depth);
                boolean source = opacity != 0 || emission != 0 || stopDirectSky;
                for (int dy = 0; dy < 16; dy++) for (int dz = 0; dz < 16; dz++) {
                    int target = x + width * (z + dz + depth * (y + dy));
                    Arrays.fill(this.opacity, target, target + 16, (byte)opacity);
                    Arrays.fill(this.emission, target, target + 16, (byte)emission);
                    stops.set(target, target + 16, stopDirectSky);
                    if (source) sourceCandidate = Math.max(sourceCandidate, target + 16);
                    if (emitterRows != null && emission != 0)
                        emitterRows.set(target >>> 4, ((target + 15) >>> 4) + 1);
                }
                if (firstDirectCandidate != null && (opacity != 0 || stopDirectSky)) {
                    for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
                        int column = x + dx + width * (z + dz);
                        firstDirectCandidate[column] = Math.max(firstDirectCandidate[column], y + 16);
                    }
                }
            }

            /** Adds directed W/E/D/U/N/S obstruction bits after paired-face evaluation. */
            public void orBlockedFaces(int index, int mask) {
                writable(); Objects.checkIndex(index, cells);
                if (mask < 0 || mask > 63) throw new IllegalArgumentException("Invalid blocked faces");
                blocked[index] |= (byte)mask;
                if (firstDirectCandidate != null && (mask & 4) != 0) recordDown(index);
                if ((mask & 4) != 0) sourceCandidate = Math.max(sourceCandidate, index + 1);
            }

            /**
             * Replaces 4096 face masks from a validated immutable page, including zero
             * masks. Scalar optics and explicit sky stops are unchanged. A cleared
             * upper DOWN obstruction is accounted for by final-content bound trimming.
             */
            public void copyBlockedPage(int target, BlockedPage page) {
                writable(); Objects.requireNonNull(page, "page");
                Objects.checkFromIndexSize(target, BlockedPage.CELLS, cells);
                System.arraycopy(page.masks, 0, blocked, target, BlockedPage.CELLS);
                if (firstDirectCandidate != null) for (int i = 0; i < page.downEnd; i++)
                    if ((page.masks[i] & 4) != 0) recordDown(target + i);
                if (page.downEnd != 0) sourceCandidate = Math.max(sourceCandidate, target + page.downEnd);
            }

            public void setStopRange(int from, int to, boolean stopDirectSky) {
                writable(); Objects.checkFromToIndex(from, to, cells);
                stops.set(from, to, stopDirectSky);
                recordRange(from, to, stopDirectSky, false);
                if (from != to && stopDirectSky) sourceCandidate = Math.max(sourceCandidate, to);
            }

            /** Vertical-column indices use x + width*z, independently of cell ranges. */
            public void setSkyColumns(int from, int to, boolean directSky) {
                writable(); Objects.checkFromToIndex(from, to, plane);
                skyColumns.set(from, to, directSky);
            }

            /**
             * Derives the source bound from final contents and transfers ownership
             * without cloning. Checked writes already prove all channel values valid;
             * any use of the raw-row method retains complete final channel validation.
             * Both successful and failed attempts seal this builder and release its
             * buffer references.
             */
            public Snapshot consume() {
                writable(); closed = true;
                try {
                    int end;
                    if (deferredValidation) {
                        int opacityEnd = validateValues(opacity, 15, 15, "opacity");
                        int emissionEnd = validateValues(emission, 15, 15, "emission");
                        int blockedEnd = validateValues(blocked, 63, 4, "blocked faces");
                        end = Math.max(Math.max(opacityEnd, emissionEnd), Math.max(blockedEnd, stops.length()));
                    } else end = finalSourceEnd();
                    if (stops.length() > cells || skyColumns.length() > plane)
                        throw new IllegalArgumentException("Invalid optical bit range");
                    if (firstDirectCandidate != null) finishDirectIndex();
                    return new Snapshot(this, end);
                } finally {
                    release();
                }
            }

            private int finalSourceEnd() {
                int end = sourceCandidate;
                while (end > 0) {
                    int index = end - 1;
                    if (opacity[index] != 0 || emission[index] != 0 || (blocked[index] & 4) != 0 || stops.get(index)) break;
                    end--;
                }
                return end;
            }

            private void recordOptics(int index, boolean obstruction, boolean emitter) {
                if (firstDirectCandidate == null) return;
                if (obstruction) {
                    int column = index % plane;
                    firstDirectCandidate[column] = Math.max(firstDirectCandidate[column], index / plane + 1);
                }
                if (emitter) emitterRows.set(index >>> 4);
            }

            private void recordDown(int index) {
                int column = index % plane;
                firstDirectCandidate[column] = Math.max(firstDirectCandidate[column], index / plane);
            }

            private void recordRange(int from, int to, boolean obstruction, boolean emitter) {
                if (firstDirectCandidate == null || from == to) return;
                if (obstruction) for (int i = Math.max(from, to - plane); i < to; i++)
                    recordOptics(i, true, false);
                if (emitter) emitterRows.set(from >>> 4, ((to - 1) >>> 4) + 1);
            }

            /** Monotone candidates survive overwrites; only disproved upper cuts scan lower. */
            private void finishDirectIndex() {
                for (int column = 0; column < plane; column++) {
                    int cut = firstDirectCandidate[column];
                    while (cut > 0) {
                        int below = column + (cut - 1) * plane;
                        if (opacity[below] != 0 || stops.get(below)
                                || cut < height && (blocked[below + plane] & 4) != 0) break;
                        cut--;
                    }
                    firstDirectCandidate[column] = cut;
                }
            }

            /** Idempotent on the owner thread, including after successful/failed consume. */
            @Override public void close() {
                owner(); closed = true; release();
            }

            private void writable() {
                owner();
                if (closed) throw new IllegalStateException("Optical builder has been consumed or closed");
            }
            private void owner() {
                if (Thread.currentThread() != owner)
                    throw new IllegalStateException("Optical builder accessed outside its owner thread");
            }
            private static void opticalValues(int opacity, int emission) {
                if (opacity < 0 || opacity > 15 || emission < 0 || emission > 15)
                    throw new IllegalArgumentException("Invalid optical value");
            }
            private void release() {
                opacity = null; emission = null; blocked = null; stops = null; skyColumns = null;
                firstDirectCandidate = null; emitterRows = null;
            }
        }

        private Snapshot(Snapshot source, byte[] opacity, byte[] emission, byte[] blocked,
                BitSet stops, BitSet skyColumns, int sourceScanEnd) {
            width = source.width; height = source.height; depth = source.depth;
            plane = source.plane; cells = source.cells;
            this.opacity = opacity; this.emission = emission; this.blocked = blocked;
            this.stops = stops; this.skyColumns = skyColumns;
            this.sourceScanEnd = sourceScanEnd;
            firstDirectY = null; emitterRows = null;
        }

        public int width() { return width; }
        public int height() { return height; }
        public int depth() { return depth; }
        /** Includes snapshot, light scratch, worst-case queue, bit sets, and packed result. */
        public long estimatedWorkingBytes() {
            return GlobalLightField.estimatedWorkingBytes(width, height, depth);
        }
        private int index(int x, int y, int z) {
            if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth)
                throw new IndexOutOfBoundsException("Outside optical domain");
            return x + width * (z + depth * y);
        }
        private int neighbor(int index, int direction) {
            return switch (direction) {
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

    /** All fields are final post-edit optics, including directed source/destination shape tests. */
    public record CellUpdate(int x, int y, int z, int opacity, int emission,
            int blockedOutgoingFaces, boolean stopDirectSky) {
        public CellUpdate {
            if (opacity < 0 || opacity > 15 || emission < 0 || emission > 15 ||
                    blockedOutgoingFaces < 0 || blockedOutgoingFaces > 63)
                throw new IllegalArgumentException("Invalid optical cell update");
        }
    }
    /** Only use when the proof above the full-height domain changes, e.g. dimension sky policy. */
    public record SkyColumnUpdate(int x, int z, boolean directSky) { }
    public enum Stage { DIRECT_SKY, INITIAL_SOURCES, INITIAL_FRONTIER, INITIAL_SKY_FRONTIER,
        INVALIDATING, INVALIDATED_FRONTIER, RELAXING, COMPLETE }
    public record Progress(Stage stage, int work, long totalWork, int queued,
            long changedBlockValues, long changedSkyValues) { }

    /** Immutable solved state, safe to share with readers and subsequent incremental jobs. */
    public static final class Result {
        private final Snapshot terrain;
        private final byte[] block, sky;
        private final BitSet direct;
        private Result(Snapshot terrain, byte[] block, byte[] sky, BitSet direct) {
            this.terrain = terrain; this.block = block; this.sky = sky; this.direct = direct;
        }
        public Snapshot terrain() { return terrain; }
        public int width() { return terrain.width; }
        public int height() { return terrain.height; }
        public int depth() { return terrain.depth; }
        public int block(int x, int y, int z) { return block[terrain.index(x,y,z)]; }
        public int sky(int x, int y, int z) { return sky[terrain.index(x,y,z)]; }
        public long estimatedBytes() { return 512L + 5L * terrain.cells + (2L * terrain.cells + terrain.plane + 7) / 8
                + (terrain.firstDirectY == null ? 0 : 80L + 4L * terrain.plane
                        + ((terrain.cells + 1023L) / 1024) * 8); }
        public ClearedChunkLighting.Result cropColumn(int chunkXIndex, int chunkZIndex) {
            if (chunkXIndex < 1 || chunkXIndex >= terrain.width / 16 - 1 ||
                    chunkZIndex < 1 || chunkZIndex >= terrain.depth / 16 - 1)
                throw new IllegalArgumentException("Published columns require a full 16-block terrain halo");
            byte[] packedBlock = new byte[terrain.height * 128], packedSky = new byte[packedBlock.length];
            int columnStart = chunkXIndex * 16 + terrain.width * (chunkZIndex * 16);
            int target = 0, layerStart = columnStart;
            // The checked interior column bounds cover every source row below. Both
            // target layers use contiguous x/z/y nibble order, two source cells per byte.
            for (int y = 0; y < terrain.height; y++, layerStart += terrain.plane) {
                int rowStart = layerStart;
                for (int z = 0; z < 16; z++, rowStart += terrain.width) {
                    for (int x = 0; x < 16; x += 2, target++) {
                        int source = rowStart + x;
                        packedBlock[target] = (byte)(block[source] | block[source + 1] << 4);
                        packedSky[target] = (byte)(sky[source] | sky[source + 1] << 4);
                    }
                }
            }
            return new ClearedChunkLighting.Result(terrain.height, packedSky, packedBlock, -1, -1);
        }
    }

    /**
     * A single-owner, work-bounded job. Invalidations follow the old field's actual maximum
     * contributors separately for each layer. Every traversed edge strictly decreases its
     * old value, so this dependency walk is acyclic and never exceeds the full mathematical
     * influence bound. Independent direct-sky cells are not invalidated by a neighboring
     * shadow. Afterwards values only increase from surviving sources and the unchanged
     * outer field. A source face change is included even if its own light does not change.
     * Direct sky is rescanned only in touched x/z columns, including a changed downward face.
     * The worker owns all scratch; a completed Result is never subsequently mutated.
     */
    public static final class Session {
        private final Snapshot terrain;
        private final boolean incremental;
        private byte[] block, sky;
        private BitSet direct;
        private final BitSet columns, queued, affected, invalidBlock, invalidSky, pendingBlock, pendingSky;
        private Result previous;
        private int[] queue;
        private int[] firstDirectY;
        private int[] scanFromY;
        private CandidateQueue increases;
        private BitSet spillBlock, spillSky;
        private int spillCount, spillLayer, spillBlockCursor, spillSkyCursor;
        private int pageLimit = MAX_INCREASE_PAGES;
        private int peakIncreasePages;
        private long spillOffers, staleEntries;
        private BitSet emitters;
        private int fullRunEnd, fullRunY, fullFillIndex = -1, unresolvedColumns;
        private int fullFillCeiling, fullTailIndex;
        private int indexedColumn, indexedWord, indexedCell = -1, indexedCellEnd;
        private long indexedRows;
        private long[] indexedEmitterWords;
        private int head, tail, size, position, directColumn = -1, directY;
        private int frontierY = -1;
        private boolean directOpen;
        private long totalWork, changedBlock, changedSky;
        private Stage stage;
        private Result result;

        public Session(Snapshot input) { this(input, DEFAULT_WORKING_BYTES); }
        public Session(Snapshot input, long maxWorkingBytes) {
            terrain = Objects.requireNonNull(input, "input"); checkBudget(terrain, maxWorkingBytes);
            incremental = false;
            block = new byte[terrain.cells]; sky = new byte[terrain.cells]; direct = new BitSet(terrain.cells);
            columns = new BitSet(terrain.plane); columns.set(0, terrain.plane);
            queued = new BitSet(terrain.cells); affected = new BitSet(); queue = new int[Math.min(4096, terrain.cells)];
            invalidBlock = new BitSet(); invalidSky = new BitSet(); pendingBlock = new BitSet(); pendingSky = new BitSet();
            firstDirectY = new int[terrain.plane];
            // One contiguous optical pass gathers direct-column ends and real emitters.
            // Unresolved sky columns use -1; columns without sky are already closed.
            if (terrain.firstDirectY != null) indexedEmitterWords = terrain.emitterRows.toLongArray();
            else {
                Arrays.fill(firstDirectY, terrain.height);
                for (int column = terrain.skyColumns.nextSetBit(0); column >= 0;
                        column = terrain.skyColumns.nextSetBit(column + 1)) firstDirectY[column] = -1;
                unresolvedColumns = terrain.skyColumns.cardinality();
            }
            emitters = new BitSet();
            position = terrain.sourceScanEnd;
            stage = Stage.INITIAL_SOURCES;
        }
        public Session(Result previous, List<CellUpdate> updates, List<SkyColumnUpdate> skyUpdates) {
            this(previous, updates, skyUpdates, DEFAULT_WORKING_BYTES);
        }
        public Session(Result previous, List<CellUpdate> updates, List<SkyColumnUpdate> skyUpdates,
                long maxWorkingBytes) {
            Objects.requireNonNull(previous, "previous"); Objects.requireNonNull(updates, "updates");
            Objects.requireNonNull(skyUpdates, "skyUpdates"); checkBudget(previous.terrain, maxWorkingBytes);
            Snapshot old = previous.terrain;
            if (updates.isEmpty() && skyUpdates.isEmpty()) {
                terrain = old; incremental = true; columns = new BitSet(); queued = new BitSet(); affected = new BitSet();
                invalidBlock = new BitSet(); invalidSky = new BitSet(); pendingBlock = new BitSet(); pendingSky = new BitSet();
                stage = Stage.COMPLETE; result = previous; return;
            }
            // Last edit wins. Resolve duplicates before copying any arrays, so unchanged
            // neighbor-shape refreshes and edits which cancel preserve Result identity.
            Map<Integer, CellUpdate> finalCells = new HashMap<>();
            for (CellUpdate update : updates) {
                int index = old.index(update.x, update.y, update.z);
                finalCells.put(index, update);
            }
            Map<Integer, Boolean> finalSky = new HashMap<>();
            for (SkyColumnUpdate update : skyUpdates) finalSky.put(old.index(update.x, 0, update.z), update.directSky);
            columns = new BitSet(old.plane); scanFromY = new int[old.plane];
            BitSet changedCells = new BitSet(), changedProofs = new BitSet();
            boolean opacityChanged = false, emissionChanged = false, blockedChanged = false, stopsChanged = false;
            for (var pair : finalCells.entrySet()) {
                int index = pair.getKey(); CellUpdate update = pair.getValue();
                boolean a = update.opacity != old.opacity[index], b = update.emission != old.emission[index];
                boolean c = update.blockedOutgoingFaces != old.blocked[index], d = update.stopDirectSky != old.stops.get(index);
                opacityChanged |= a; emissionChanged |= b; blockedChanged |= c; stopsChanged |= d;
                if (a || b || c || d) changedCells.set(index);
                if (a || d || ((update.blockedOutgoingFaces ^ old.blocked[index]) & 4) != 0) {
                    int column = index % old.plane;
                    columns.set(column); scanFromY[column] = Math.max(scanFromY[column], index / old.plane);
                }
            }
            for (var pair : finalSky.entrySet()) if (pair.getValue() != old.skyColumns.get(pair.getKey())) {
                int column = pair.getKey(); changedProofs.set(column); columns.set(column); scanFromY[column] = old.height - 1;
            }
            if (changedCells.isEmpty() && changedProofs.isEmpty()) {
                terrain = old; incremental = true; queued = new BitSet(); affected = new BitSet();
                invalidBlock = new BitSet(); invalidSky = new BitSet(); pendingBlock = new BitSet(); pendingSky = new BitSet();
                stage = Stage.COMPLETE; result = previous; scanFromY = null; return;
            }
            // Unchanged optical channels are immutable and may safely share their old arrays.
            byte[] opacity = opacityChanged ? old.opacity.clone() : old.opacity;
            byte[] emission = emissionChanged ? old.emission.clone() : old.emission;
            byte[] blocked = blockedChanged ? old.blocked.clone() : old.blocked;
            BitSet stops = stopsChanged ? (BitSet)old.stops.clone() : old.stops;
            BitSet skyColumns = changedProofs.isEmpty() ? old.skyColumns : (BitSet)old.skyColumns.clone();
            int sourceScanEnd = old.sourceScanEnd;
            for (var pair : finalCells.entrySet()) {
                int index = pair.getKey(); CellUpdate update = pair.getValue();
                if (opacityChanged) opacity[index] = (byte)update.opacity;
                if (emissionChanged) emission[index] = (byte)update.emission;
                if (blockedChanged) blocked[index] = (byte)update.blockedOutgoingFaces;
                if (stopsChanged) stops.set(index, update.stopDirectSky);
                // Keeping the old upper bound after removals is conservative. New
                // sources, opacity and downward faces must extend it before this
                // immutable terrain can later be used for a fresh full solve.
                if (update.opacity != 0 || update.emission != 0 ||
                        (update.blockedOutgoingFaces & 4) != 0 || update.stopDirectSky)
                    sourceScanEnd = Math.max(sourceScanEnd, index + 1);
            }
            if (!changedProofs.isEmpty()) for (var pair : finalSky.entrySet()) skyColumns.set(pair.getKey(), pair.getValue());
            terrain = new Snapshot(old, opacity, emission, blocked, stops, skyColumns, sourceScanEnd);
            incremental = true;
            this.previous = previous;
            block = previous.block.clone(); sky = previous.sky.clone(); direct = (BitSet)previous.direct.clone();
            queued = new BitSet(terrain.cells); affected = new BitSet(terrain.cells);
            invalidBlock = new BitSet(terrain.cells); invalidSky = new BitSet(terrain.cells);
            pendingBlock = new BitSet(terrain.cells); pendingSky = new BitSet(terrain.cells);
            queue = new int[Math.min(4096, terrain.cells)];
            for (int index = changedCells.nextSetBit(0); index >= 0; index = changedCells.nextSetBit(index + 1)) {
                invalidate(index, 3);
            }
            stage = Stage.DIRECT_SKY;
        }
        public boolean isComplete() { return stage == Stage.COMPLETE; }
        public Result result() {
            if (!isComplete()) throw new IllegalStateException("Light job has not completed");
            return result;
        }
        public Progress step(int maxWork) {
            if (maxWork < 0) throw new IllegalArgumentException("Negative work limit");
            int work = 0;
            while (work < maxWork && stage != Stage.COMPLETE) {
                switch (stage) {
                    case DIRECT_SKY -> {
                        if (!incremental) {
                            work += fillDirectIntervals(maxWork - work);
                            break;
                        }
                        if (directColumn < 0) {
                            directColumn = columns.nextSetBit(position);
                            if (directColumn < 0) {
                                stage = incremental ? Stage.INVALIDATING : Stage.INITIAL_SOURCES; position = 0; continue;
                            }
                            directY = incremental ? scanFromY[directColumn] : terrain.height - 1;
                            directOpen = directY == terrain.height - 1 ? terrain.skyColumns.get(directColumn)
                                    : previous.direct.get(directColumn + (directY + 1) * terrain.plane);
                            if (!incremental) firstDirectY[directColumn] = directOpen ? 0 : terrain.height;
                        }
                        int index = directColumn + terrain.plane * directY;
                        if (terrain.opacity[index] > 0 || terrain.stops.get(index) ||
                                directY < terrain.height - 1 && (terrain.blocked[index + terrain.plane] & 4) != 0) {
                            if (!incremental && directOpen) firstDirectY[directColumn] = directY + 1;
                            directOpen = false;
                        }
                        boolean wasDirect = direct.get(index);
                        if (wasDirect != directOpen) {
                            direct.set(index, directOpen);
                            if (incremental) invalidate(index, 2);
                        }
                        work++;
                        // Once both old and new columns are stopped, neither can regain direct
                        // sky farther down, including when more edits exist below this cell.
                        if (--directY < 0 || !wasDirect && !directOpen) { position = directColumn + 1; directColumn = -1; }
                    }
                    case INITIAL_SOURCES -> work += terrain.firstDirectY == null
                            ? scanFullSources(maxWork - work) : scanIndexedSources(maxWork - work);
                    case INITIAL_FRONTIER -> {
                        // Block sources seed only outgoing edges that can improve their target.
                        int index = emitters.nextSetBit(position);
                        if (index < 0) {
                            emitters = null; position = 0;
                            stage = direct.isEmpty() ? Stage.RELAXING : Stage.INITIAL_SKY_FRONTIER;
                            continue;
                        }
                        spreadCandidates(index, 1);
                        position = index + 1; work++;
                    }
                    case INITIAL_SKY_FRONTIER -> {
                        seedDirectFrontier(); work++;
                        if (position == terrain.plane * 5) { firstDirectY = null; position = 0; stage = Stage.RELAXING; }
                    }
                    case INVALIDATING -> {
                        if (size == 0) { position = 0; stage = Stage.INVALIDATED_FRONTIER; continue; }
                        int index = poll(); queued.clear(index);
                        boolean resetBlock = pendingBlock.get(index), resetSky = pendingSky.get(index);
                        pendingBlock.clear(index); pendingSky.clear(index);
                        int newBlock = terrain.emission[index], newSky = direct.get(index) ? 15 : 0;
                        if (resetBlock && block[index] != newBlock) { block[index] = (byte)newBlock; changedBlock++; }
                        if (resetSky && sky[index] != newSky) { sky[index] = (byte)newSky; changedSky++; }
                        for (int face = 0; face < 6; face++) {
                            int next = terrain.neighbor(index, face);
                            if (next < 0 || (previous.terrain.blocked[index] & FACE[face]) != 0) continue;
                            int loss = Math.max(1, previous.terrain.opacity[next]), layers = 0;
                            if (resetBlock && previous.block[next] > 0 &&
                                    previous.block[index] - loss == previous.block[next]) layers |= 1;
                            if (resetSky && previous.sky[next] > 0 &&
                                    previous.sky[index] - loss == previous.sky[next]) layers |= 2;
                            if (layers != 0) invalidate(next, layers);
                        }
                        work++;
                    }
                    case INVALIDATED_FRONTIER -> {
                        int index = affected.nextSetBit(position);
                        if (index < 0) { stage = Stage.RELAXING; continue; }
                        position = index + 1;
                        // Sources restored to their own emission/direct value may already be
                        // final themselves while their newly opened outgoing edges add light.
                        spreadCandidates(index, 3);
                        int loss = Math.max(1, terrain.opacity[index]), candidateBlock = 0, candidateSky = 0;
                        for (int face = 0; face < 6; face++) {
                            int source = terrain.neighbor(index, face);
                            if (source >= 0 && (terrain.blocked[source] & OPPOSITE_FACE[face]) == 0) {
                                candidateBlock = Math.max(candidateBlock, block[source] - loss);
                                candidateSky = Math.max(candidateSky, sky[source] - loss);
                            }
                        }
                        offerCandidate(index, candidateBlock, candidateSky);
                        work++;
                    }
                    case RELAXING -> {
                        if (increases == null || increases.size == 0) {
                            if (spillCount != 0) {
                                work += refillSpills(maxWork - work);
                                continue;
                            }
                            assert size == 0 : "Unprocessed light decrease queue";
                            result = new Result(terrain, block, sky, direct);
                            block = null; sky = null; direct = null; queue = null; previous = null; increases = null;
                            stage = Stage.COMPLETE; continue;
                        }
                        int entry = increases.poll(), index = entry & INDEX_MASK;
                        int level = (entry >>> 24) & 15, layer = (entry & (1 << 28)) == 0 ? 1 : 2;
                        int actual = layer == 1 ? block[index] : sky[index];
                        assert actual >= level : "Decrease entered monotone propagation";
                        if (actual == level) spreadCandidates(index, layer);
                        else staleEntries++;
                        work++;
                    }
                    case COMPLETE -> { }
                }
            }
            totalWork += work;
            return new Progress(stage, work, totalWork, size + (increases == null ? 0 : increases.size) + spillCount, changedBlock, changedSky);
        }
        /** Monotone propagation after all old-field decreases have completed. */
        private void spreadCandidates(int index, int layers) {
            int sourceBlock = (layers & 1) == 0 ? 0 : block[index];
            int sourceSky = (layers & 2) == 0 ? 0 : sky[index];
            for (int face = 0; face < 6; face++) {
                int target = terrain.neighbor(index, face);
                if (target < 0 || sourceBlock <= block[target] + 1 && sourceSky <= sky[target] + 1) continue;
                if ((terrain.blocked[index] & FACE[face]) != 0) continue;
                int loss = Math.max(1, terrain.opacity[target]);
                offerCandidate(target, sourceBlock - loss, sourceSky - loss);
            }
        }
        private void offerCandidate(int index, int proposedBlock, int proposedSky) {
            if (proposedBlock > block[index]) {
                block[index] = (byte)proposedBlock; changedBlock++;
                if (proposedBlock > 1) queueIncrease(index, 1, proposedBlock);
            }
            if (proposedSky > sky[index]) {
                sky[index] = (byte)proposedSky; changedSky++;
                if (proposedSky > 1) queueIncrease(index, 2, proposedSky);
            }
        }
        private void queueIncrease(int index, int layer, int level) {
            if (increases == null) increases = new CandidateQueue(Math.min(pageLimit,
                    (terrain.cells + INCREASE_PAGE_SIZE - 1) / INCREASE_PAGE_SIZE));
            BitSet spill = layer == 1 ? spillBlock : spillSky;
            if (increases.offer(encode(index, layer, level), level)) {
                // The admitted entry carries the current value and supersedes any older
                // spilled obligation for this exact layer. Other layers remain untouched.
                if (spill != null && spill.get(index)) { spill.clear(index); spillCount--; }
            } else {
                spillOffers++;
                if (spill == null) {
                    spill = new BitSet(terrain.cells);
                    if (layer == 1) spillBlock = spill; else spillSky = spill;
                }
                if (!spill.get(index)) { spill.set(index); spillCount++; }
            }
            peakIncreasePages = Math.max(peakIncreasePages, increases.allocatedPages);
        }
        private static int encode(int index, int layer, int level) {
            return index | (level << 24) | (layer == 2 ? 1 << 28 : 0);
        }
        /** Refill only from current values; old queued levels never consume spill bits. */
        private int refillSpills(int budget) {
            int work = 0;
            while (work < budget && spillCount != 0) {
                boolean useSky = (spillLayer++ & 1) != 0;
                BitSet spill = useSky ? spillSky : spillBlock;
                if (spill == null || spill.isEmpty()) { useSky = !useSky; spill = useSky ? spillSky : spillBlock; }
                int cursor = useSky ? spillSkyCursor : spillBlockCursor;
                int index = spill.nextSetBit(cursor);
                if (index < 0) index = spill.nextSetBit(0);
                int level = useSky ? sky[index] : block[index];
                if (level > 1 && !increases.offer(encode(index, useSky ? 2 : 1, level), level)) break;
                spill.clear(index); spillCount--; work++;
                if (useSky) spillSkyCursor = index + 1; else spillBlockCursor = index + 1;
                peakIncreasePages = Math.max(peakIncreasePages, increases.allocatedPages);
            }
            return work;
        }
        /**
         * Equal direct-column ends form contiguous runs in each horizontal plane. Set each
         * run with bulk array/bit-set ranges, never one BitSet lookup per sunlit cell. Runs
         * may span z rows because the storage order is a complete horizontal plane.
         */
        /** Keep full field scan compilation separate from incremental propagation. */
        private int scanIndexedSources(int budget) {
            int work = 0;
            while (work < budget && indexedColumn < terrain.plane) {
                int column = indexedColumn++;
                int cut = terrain.skyColumns.get(column) ? terrain.firstDirectY[column] : terrain.height;
                firstDirectY[column] = cut;
                fullFillCeiling = Math.max(fullFillCeiling, cut);
                work++;
            }
            while (work < budget) {
                if (indexedCell >= 0) {
                    int count = Math.min(indexedCellEnd - indexedCell, budget - work);
                    System.arraycopy(terrain.emission, indexedCell, block, indexedCell, count);
                    for (int index = indexedCell; index < indexedCell + count; index++)
                        if (block[index] > 1) emitters.set(index);
                    indexedCell += count; work += count;
                    if (indexedCell == indexedCellEnd) indexedCell = -1;
                } else if (indexedRows != 0) {
                    int bit = Long.numberOfTrailingZeros(indexedRows);
                    indexedRows &= indexedRows - 1;
                    indexedCell = (((indexedWord - 1) << 6) + bit) << 4;
                    indexedCellEnd = indexedCell + 16;
                } else if (indexedWord < indexedEmitterWords.length) {
                    // Every inspected word consumes work, even if all64 rows are empty.
                    // A step(1) therefore never hides an unbounded empty-BitSet scan.
                    indexedRows = indexedEmitterWords[indexedWord++];
                    work++;
                } else {
                    indexedEmitterWords = null; position = 0;
                    fullTailIndex = fullFillCeiling * terrain.plane;
                    stage = Stage.DIRECT_SKY;
                    break;
                }
            }
            return work;
        }

        private int scanFullSources(int budget) {
            int count = Math.min(position, budget), from = position - count;
            System.arraycopy(terrain.emission, from, block, from, count);
            int column = position == 0 ? 0 : (position - 1) % terrain.plane;
            for (int index = position - 1; index >= from; index--) {
                if (block[index] > 1) emitters.set(index);
                // Once a column's highest obstruction is known, no lower optical
                // value can change its direct-sky interval. In particular, a single
                // open column must not force a traversal of every buried stop bit.
                if (unresolvedColumns != 0 && firstDirectY[column] < 0) {
                    boolean stopsHere = terrain.opacity[index] != 0 || terrain.stops.get(index);
                    if (stopsHere || (terrain.blocked[index] & 4) != 0) {
                        firstDirectY[column] = index / terrain.plane + (stopsHere ? 1 : 0);
                        unresolvedColumns--;
                    }
                }
                if (--column < 0) column = terrain.plane - 1;
            }
            position = from;
            if (position == 0) finishFullSources();
            return count;
        }
        private void finishFullSources() {
            for (int column = 0; column < terrain.plane; column++) {
                firstDirectY[column] = Math.max(0, firstDirectY[column]);
                fullFillCeiling = Math.max(fullFillCeiling, firstDirectY[column]);
            }
            // Above the highest proved start every column is directly lit. Keep
            // that shared sky contiguous instead of filling it once per rough-ground
            // horizontal run. A column without sky makes the common tail empty.
            fullTailIndex = fullFillCeiling * terrain.plane;
            stage = Stage.DIRECT_SKY;
        }
        private int fillDirectIntervals(int budget) {
            int work = 0;
            if (fullTailIndex < terrain.cells) {
                int count = Math.min(terrain.cells - fullTailIndex, budget);
                Arrays.fill(sky, fullTailIndex, fullTailIndex + count, (byte)15);
                direct.set(fullTailIndex, fullTailIndex + count);
                fullTailIndex += count; work += count;
            }
            while (work < budget && position < terrain.plane) {
                if (fullRunEnd == position) {
                    int low = firstDirectY[position];
                    fullRunEnd = position + 1;
                    while (fullRunEnd < terrain.plane && firstDirectY[fullRunEnd] == low) fullRunEnd++;
                    fullRunY = low;
                    if (low == fullFillCeiling) { position = fullRunEnd; work++; continue; }
                    fullFillIndex = position + fullRunY * terrain.plane;
                }
                int end = fullRunEnd + fullRunY * terrain.plane;
                int count = Math.min(end - fullFillIndex, budget - work);
                Arrays.fill(sky, fullFillIndex, fullFillIndex + count, (byte)15);
                direct.set(fullFillIndex, fullFillIndex + count);
                fullFillIndex += count; work += count;
                if (fullFillIndex == end) {
                    if (++fullRunY == fullFillCeiling) position = fullRunEnd;
                    else fullFillIndex = position + fullRunY * terrain.plane;
                }
            }
            if (position == terrain.plane) { position = 0; stage = Stage.INITIAL_FRONTIER; }
            return work;
        }
        /** Direct columns are intervals. Only their downward end and horizontal differences emit diffuse sky. */
        private void seedDirectFrontier() {
            int column = position / 5, face = position % 5, low = firstDirectY[column];
            if (face == 4) {
                if (low > 0 && low < terrain.height) {
                    int source = column + low * terrain.plane;
                    if ((terrain.blocked[source] & 4) == 0 && terrain.opacity[source - terrain.plane] < 15)
                        offerCandidate(source - terrain.plane, 0, 15 - Math.max(1, terrain.opacity[source - terrain.plane]));
                }
                position++; return;
            }
            int direction = face < 2 ? face : face + 2;
            int neighbor = terrain.neighbor(column, direction);
            if (neighbor < 0 || low >= firstDirectY[neighbor]) { position++; return; }
            if (frontierY < 0) frontierY = low;
            int source = column + frontierY * terrain.plane, target = neighbor + frontierY * terrain.plane;
            if ((terrain.blocked[source] & FACE[direction]) == 0 && terrain.opacity[target] < 15)
                offerCandidate(target, 0, 15 - Math.max(1, terrain.opacity[target]));
            if (++frontierY == firstDirectY[neighbor]) { frontierY = -1; position++; }
        }
        private void invalidate(int index, int layers) {
            boolean added = false;
            if ((layers & 1) != 0 && !invalidBlock.get(index)) {
                invalidBlock.set(index); pendingBlock.set(index); added = true;
            }
            if ((layers & 2) != 0 && !invalidSky.get(index)) {
                invalidSky.set(index); pendingSky.set(index); added = true;
            }
            if (added) { affected.set(index); enqueue(index); }
        }
        private void enqueue(int index) {
            if (queued.get(index)) return;
            queued.set(index); append(index);
        }
        private void append(int value) {
            if (size == queue.length) {
                int[] larger = new int[Math.min(terrain.cells, queue.length * 2)];
                int first = Math.min(size, queue.length - head);
                System.arraycopy(queue, head, larger, 0, first);
                System.arraycopy(queue, 0, larger, first, size - first);
                queue = larger; head = 0; tail = size;
            }
            queue[tail++] = value; if (tail == queue.length) tail = 0;
            size++;
        }
        private int poll() {
            int value = queue[head++]; if (head == queue.length) head = 0;
            size--; return value;
        }
    }

    /**
     * One checked working-memory bound for field admission and host rectangle planning.
     * This is an estimate only; it does not relax the solver's geometry or cell limits.
     */
    public static long estimatedWorkingBytes(long width, int height, long depth) {
        if (width <= 0 || depth <= 0 || height <= 0 || height % 16 != 0)
            throw new IllegalArgumentException("Positive dimensions and a multiple-of-16 height are required");
        try {
            long plane = Math.multiplyExact(width, depth);
            long cells = Math.multiplyExact(plane, height);
            long field = Math.addExact(Math.addExact(4096L, Math.multiplyExact(18L, cells)), plane);
            // Checked-builder metadata can coexist with an old cached snapshot and
            // the full session's emitter-word cursor. Explicitly reserve both column
            // arrays and three row-word copies rather than hiding them in cell slack.
            long sourceIndex = Math.addExact(256L + Math.multiplyExact(8L, plane),
                    Math.multiplyExact(24L, cells / 1024 + (cells % 1024 == 0 ? 0 : 1)));
            return Math.addExact(Math.addExact(field, sourceIndex), extraIncreaseWorkingBytes(cells));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Optical working estimate exceeds signed bytes", overflow);
        }
    }

    /** Bounded increase queues and spill storage, additional to the field's other scratch. */
    public static long extraIncreaseWorkingBytes(int cells) {
        if (cells < 0) throw new IllegalArgumentException("Negative cell count");
        return extraIncreaseWorkingBytes((long)cells);
    }

    private static long extraIncreaseWorkingBytes(long cells) {
        long pages = Math.min(MAX_INCREASE_PAGES, cells / INCREASE_PAGE_SIZE +
                (cells % INCREASE_PAGE_SIZE == 0 ? 0 : 1));
        long words = cells / 64 + (cells % 64 == 0 ? 0 : 1);
        // Two spill bit sets, page arrays+objects, queue heads/tails and bookkeeping.
        return Math.addExact(Math.addExact(512L, Math.multiplyExact(16L, words)),
                Math.multiplyExact(pages, 4L * INCREASE_PAGE_SIZE + 64L));
    }
    /** A shared page pool places a strict cap on all sixteen buckets combined. */
    private static final class CandidateQueue {
        private static final class Page {
            final int[] entries = new int[INCREASE_PAGE_SIZE];
            int read, write;
            Page next;
        }
        final Page[] heads = new Page[16], tails = new Page[16];
        final int maxPages;
        Page free;
        int allocatedPages, size, highest;
        CandidateQueue(int maxPages) {
            if (maxPages < 1 || maxPages > MAX_INCREASE_PAGES) throw new IllegalArgumentException("Invalid light queue page cap");
            this.maxPages = maxPages;
        }
        boolean offer(int entry, int level) {
            Page tail = tails[level];
            if (tail == null || tail.write == INCREASE_PAGE_SIZE) {
                Page page;
                if (free != null) { page = free; free = page.next; page.next = null; page.read = page.write = 0; }
                else {
                    if (allocatedPages == maxPages) return false;
                    page = new Page(); allocatedPages++;
                }
                if (tail == null) heads[level] = page;
                else tail.next = page;
                tails[level] = page; tail = page;
            }
            tail.entries[tail.write++] = entry; size++; highest = Math.max(highest, level); return true;
        }
        int poll() {
            if (size == 0) throw new IllegalStateException("Empty light queue");
            while (heads[highest] == null) highest--;
            Page head = heads[highest]; int entry = head.entries[head.read++]; size--;
            if (head.read == head.write) {
                heads[highest] = head.next;
                if (head.next == null) tails[highest] = null;
                head.next = free; free = head;
            }
            return entry;
        }
    }

    public static Result solve(Snapshot snapshot) {
        Session session = new Session(snapshot);
        while (!session.isComplete()) session.step(65_536);
        return session.result();
    }
    private record CopiedValues(byte[] values, int relevantEnd) { }
    private static CopiedValues copyValues(byte[] source, int size, int maximum, int relevantMask, String name) {
        Objects.requireNonNull(source, name);
        if (source.length != size) throw new IllegalArgumentException("Wrong " + name + " length");
        byte[] copy = source.clone();
        return new CopiedValues(copy, validateValues(copy, maximum, relevantMask, name));
    }
    private static int validateValues(byte[] values, int maximum, int relevantMask, String name) {
        int end = 0;
        for (int index = 0; index < values.length; index++) {
            int value = values[index];
            if (value < 0 || value > maximum) throw new IllegalArgumentException("Invalid " + name);
            if ((value & relevantMask) != 0) end = index + 1;
        }
        return end;
    }
    private static BitSet copyBits(BitSet source, int maximum, String name) {
        Objects.requireNonNull(source, name);
        if (source.length() > maximum) throw new IllegalArgumentException("Invalid " + name);
        return (BitSet)source.clone();
    }
    private static void checkBudget(Snapshot snapshot, long maxWorkingBytes) {
        if (maxWorkingBytes < snapshot.estimatedWorkingBytes()) throw new IllegalArgumentException("Light working budget exceeded");
    }
    private GlobalLightField() { }
}
