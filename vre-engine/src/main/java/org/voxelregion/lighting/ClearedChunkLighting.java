/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.lighting;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;

/**
 * Detached light solution for a 16x16 column with fixed optical properties.
 * It solves the supplied fixed boundary problem, not an unbounded world.
 * A native host must validate terrain/boundary evidence before
 * installation and reconcile exterior light after installation. Old neighbor
 * light is not proof that the global post-edit field has already converged.
 *
 * <p>Both diffuse layers lose max(1, destination opacity) per open edge.
 * Emitters provide block-light seeds even when their own voxel is opaque.
 * Direct sky is 15 only in columns proved open above, until a host-proved
 * direct-sky stop. Hosts resolve partial shapes to directed edge masks before
 * detaching this input; the solver never invokes world or block-state code.
 */
public final class ClearedChunkLighting {
    public static final int MAX_HEIGHT = 4096;
    public static final long DEFAULT_WORKING_BYTES = 16L * 1024 * 1024;
    public enum Face { WEST, EAST, DOWN, UP, NORTH, SOUTH }
    public enum Stage { INITIALIZING, SKY, BLOCK_SEEDING, BLOCK, PACKING, COMPLETE }

    /**
     * Immutable local-coordinate input. Index is x | z&lt;&lt;4 | y&lt;&lt;8.
     * WEST/EAST face index is z+16*y; NORTH/SOUTH is x+16*y; DOWN/UP is x+16*z.
     * Face arrays contain actual neighbor light, before the destination entry
     * loss. An open-face bit proves the crossing is not shape-occluded. Direct
     * sky requires a separate vertical-sky proof, not merely a value of 15.
     */
    public static final class Snapshot {
        private final int height, cells;
        private final BitSet opaque, directSkyColumns, directSkyStops;
        private final byte[] opacity, emission, blockedOutgoingFaces;
        private final byte[][] blockOutside, skyOutside;
        private final BitSet[] openFaces;
        private final boolean blockDark, skyDark;

        public Snapshot(int height, BitSet opaque, byte[][] blockOutside, byte[][] skyOutside,
                BitSet[] openFaces, BitSet directSkyColumns) {
            this(height, opaque, null, null, null, opaque, blockOutside, skyOutside, openFaces, directSkyColumns);
        }

        /**
         * General optical input, detached with defensive copies. Opacity and emission each contain
         * one 0..15 value per cell. A set bit {@code 1 << Face.ordinal()} in blockedOutgoingFaces
         * forbids propagation FROM that cell toward that face; hosts must include both endpoint
         * shapes in this edge proof. The array may be null when all internal edges are open.
         *
         * <p>A stopDirectSky bit means direct sky cannot ENTER that cell, rather than merely being
         * blocked below it. Nonzero opacity is always a stop, even when omitted from this mask.
         * Blocked internal downward edges also stop direct sky at their lower destination,
         * automatically. This stop mask does not replace blockedOutgoingFaces for diffuse propagation. Boundary
         * openFaces separately prove crossings from the exterior into this column.
         */
        public Snapshot(int height, byte[] opacity, byte[] emission, byte[] blockedOutgoingFaces,
                BitSet stopDirectSky, byte[][] blockOutside, byte[][] skyOutside,
                BitSet[] openFaces, BitSet directSkyColumns) {
            this(height, null, Objects.requireNonNull(opacity, "opacity"),
                    Objects.requireNonNull(emission, "emission"), blockedOutgoingFaces,
                    stopDirectSky, blockOutside, skyOutside, openFaces, directSkyColumns);
        }

        private Snapshot(int height, BitSet opaque, byte[] opacity, byte[] emission,
                byte[] blockedOutgoingFaces, BitSet stopDirectSky, byte[][] blockOutside,
                byte[][] skyOutside, BitSet[] openFaces, BitSet directSkyColumns) {
            if (height < 16 || height > MAX_HEIGHT || height % 16 != 0)
                throw new IllegalArgumentException("Light column height must be a multiple of 16 in [16,4096]");
            this.height = height; cells = height * 256;
            this.opaque = opaque == null ? new BitSet(cells) : copyBits(opaque, cells, "opaque");
            this.opacity = opacity == null ? null : copyCells(opacity, cells, 15, "opacity");
            this.emission = emission == null ? null : copyCells(emission, cells, 15, "emission");
            this.blockedOutgoingFaces = blockedOutgoingFaces == null ? null :
                    copyCells(blockedOutgoingFaces, cells, 63, "blocked outgoing faces");
            this.directSkyStops = copyBits(stopDirectSky, cells, "direct sky stops");
            boolean anyEmission = false;
            if (this.opacity != null) {
                for (int index = 0; index < cells; index++) {
                    if (this.opacity[index] > 0) this.directSkyStops.set(index);
                    if (this.opacity[index] == 15) this.opaque.set(index);
                    if (index >= 256 && this.blockedOutgoingFaces != null &&
                            (this.blockedOutgoingFaces[index] & 4) != 0)
                        this.directSkyStops.set(index - 256);
                    anyEmission |= this.emission[index] > 0;
                }
            }
            this.directSkyColumns = copyBits(directSkyColumns, 256, "direct sky");
            this.blockOutside = copyFaces(blockOutside, height);
            this.skyOutside = copyFaces(skyOutside, height);
            Objects.requireNonNull(openFaces, "openFaces");
            if (openFaces.length != 6) throw new IllegalArgumentException("Expected six open-face masks");
            this.openFaces = new BitSet[6];
            boolean anyBlock = anyEmission, anySky = !this.directSkyColumns.isEmpty();
            for (Face face : Face.values()) {
                int f = face.ordinal();
                this.openFaces[f] = copyBits(openFaces[f], faceSize(height, face), "open face");
                for (int i = this.openFaces[f].nextSetBit(0); i >= 0; i = this.openFaces[f].nextSetBit(i + 1)) {
                    anyBlock |= this.blockOutside[f][i] > 0;
                    anySky |= this.skyOutside[f][i] > 0;
                }
            }
            for (int column = this.directSkyColumns.nextSetBit(0); column >= 0;
                    column = this.directSkyColumns.nextSetBit(column + 1))
                if (!this.openFaces[Face.UP.ordinal()].get(column) || this.skyOutside[Face.UP.ordinal()][column] != 15)
                    throw new IllegalArgumentException("Direct sky requires an open upper face with sky level 15");
            blockDark = !anyBlock; skyDark = !anySky;
        }
        public int height() { return height; }
        public int sectionCount() { return height / 16; }
        public long estimatedWorkingBytes() {
            return 2048L + 9L * cells + 2L * (64L * height + 512) +
                    (opacity == null ? 0 : 2L * cells) + (blockedOutgoingFaces == null ? 0 : cells);
        }
        private int loss(int index) { return opacity == null ? opaque.get(index) ? 15 : 1 : Math.max(1, opacity[index]); }
        private static byte[] copyCells(byte[] source, int cells, int maximum, String name) {
            if (source.length != cells) throw new IllegalArgumentException("Wrong " + name + " dimensions");
            byte[] result = source.clone();
            for (byte value : result) if (value < 0 || value > maximum)
                throw new IllegalArgumentException(name + " values must be in [0," + maximum + "]");
            return result;
        }
        private static BitSet copyBits(BitSet source, int size, String name) {
            Objects.requireNonNull(source, name);
            if (source.length() > size) throw new IllegalArgumentException(name + " exceeds its domain");
            return (BitSet) source.clone();
        }
        private static byte[][] copyFaces(byte[][] source, int height) {
            Objects.requireNonNull(source, "face light");
            if (source.length != 6) throw new IllegalArgumentException("Expected six light faces");
            byte[][] result = new byte[6][];
            for (Face face : Face.values()) {
                int f = face.ordinal();
                if (source[f] == null || source[f].length != faceSize(height, face))
                    throw new IllegalArgumentException("Wrong light-face dimensions");
                result[f] = source[f].clone();
                for (byte light : result[f]) if (light < 0 || light > 15)
                    throw new IllegalArgumentException("Light values must be in [0,15]");
            }
            return result;
        }
    }

    public static int faceSize(int height, Face face) {
        Objects.requireNonNull(face, "face");
        if (height < 16 || height > MAX_HEIGHT || height % 16 != 0) throw new IllegalArgumentException("Invalid height");
        return face == Face.DOWN || face == Face.UP ? 256 : height * 16;
    }

    /** Immutable packed output using the same nibble/index order as a 16-cubed section. */
    public static final class Result {
        private final int height;
        private final byte[] sky, block;
        private final int uniformSky, uniformBlock;
        Result(int height, byte[] sky, byte[] block, int uniformSky, int uniformBlock) {
            this.height = height; this.sky = sky; this.block = block;
            this.uniformSky = uniformSky; this.uniformBlock = uniformBlock;
        }
        public int height() { return height; }
        public int sectionCount() { return height / 16; }
        public int sky(int x, int y, int z) { return value(sky, uniformSky, index(x, y, z)); }
        public int block(int x, int y, int z) { return value(block, uniformBlock, index(x, y, z)); }
        public byte[] skySection(int sectionIndex) { return section(sky, uniformSky, sectionIndex); }
        public byte[] blockSection(int sectionIndex) { return section(block, uniformBlock, sectionIndex); }
        /** Returns -1 for a mixed section; otherwise the single 0..15 value. */
        public int uniformSky(int sectionIndex) { return uniform(sky, uniformSky, sectionIndex); }
        public int uniformBlock(int sectionIndex) { return uniform(block, uniformBlock, sectionIndex); }
        public long estimatedBytes() { return 64L + (sky == null ? 0 : sky.length) + (block == null ? 0 : block.length); }
        private int index(int x, int y, int z) {
            if (x < 0 || x >= 16 || z < 0 || z >= 16 || y < 0 || y >= height)
                throw new IndexOutOfBoundsException("Outside local light column");
            return x | z << 4 | y << 8;
        }
        private static int value(byte[] data, int uniform, int index) {
            return data == null || (index >>> 1) >= data.length ? uniform : (data[index >>> 1] >>> ((index & 1) * 4)) & 15;
        }
        private byte[] section(byte[] data, int uniform, int section) {
            checkSection(section);
            if (data != null && section * 2048 < data.length) return Arrays.copyOfRange(data, section * 2048, (section + 1) * 2048);
            byte[] result = new byte[2048]; Arrays.fill(result, (byte) (uniform | uniform << 4)); return result;
        }
        private int uniform(byte[] data, int uniform, int section) {
            checkSection(section);
            if (data == null || section * 2048 >= data.length) return uniform;
            int start = section * 2048, value = data[start] & 15;
            byte expected = (byte) (value | value << 4);
            for (int i = start; i < start + 2048; i++) if (data[i] != expected) return -1;
            return value;
        }
        private void checkSection(int section) {
            if (section < 0 || section >= sectionCount()) throw new IndexOutOfBoundsException("Invalid light section");
        }
    }

    public record Progress(Stage stage, int work, long totalWork, int queued) { }

    /** Bounded array work queue. Each step charges initialization, queue pops and result packing. */
    public static final class Session {
        private final Snapshot input;
        private final BitSet skyOpen;
        private byte[] sky, block, packedSky, packedBlock;
        private int[] queue;
        private BitSet queued;
        private int head, tail, size, position;
        private long totalWork;
        private Stage stage;
        private Result result;

        public Session(Snapshot snapshot) { this(snapshot, DEFAULT_WORKING_BYTES); }
        public Session(Snapshot snapshot, long maxWorkingBytes) {
            input = Objects.requireNonNull(snapshot, "snapshot");
            if (maxWorkingBytes < snapshot.estimatedWorkingBytes()) throw new IllegalArgumentException("Light solver memory budget exceeded");
            skyOpen = (BitSet) snapshot.directSkyColumns.clone();
            if (snapshot.blockDark && (snapshot.skyDark || snapshot.directSkyStops.isEmpty() && skyOpen.cardinality() == 256)) {
                result = new Result(snapshot.height, null, null, snapshot.skyDark ? 0 : 15, 0);
                stage = Stage.COMPLETE;
            } else {
                sky = new byte[snapshot.cells]; block = new byte[snapshot.cells];
                queue = new int[snapshot.cells]; queued = new BitSet(snapshot.cells);
                stage = Stage.INITIALIZING;
            }
        }
        public boolean isComplete() { return stage == Stage.COMPLETE; }
        public long estimatedBytes() { return input.estimatedWorkingBytes(); }
        public Result result() {
            if (!isComplete()) throw new IllegalStateException("Light solution is not complete");
            return result;
        }
        public Progress step(int maxWork) {
            if (maxWork < 0) throw new IllegalArgumentException("Negative light work budget");
            int work = 0;
            while (work < maxWork && stage != Stage.COMPLETE) {
                switch (stage) {
                    case INITIALIZING -> {
                        initialize(input.cells - 1 - position++); work++;
                        if (position == input.cells) { stage = Stage.SKY; position = 0; }
                    }
                    case SKY -> {
                        if (size == 0) { stage = Stage.BLOCK_SEEDING; position = 0; }
                        else { propagate(sky); work++; }
                    }
                    case BLOCK_SEEDING -> {
                        if (block[position] > 0) enqueue(position);
                        position++; work++;
                        if (position == input.cells) { stage = Stage.BLOCK; position = 0; }
                    }
                    case BLOCK -> {
                        if (size == 0) {
                            stage = Stage.PACKING; position = 0;
                            packedSky = new byte[input.cells / 2]; packedBlock = new byte[input.cells / 2];
                        } else { propagate(block); work++; }
                    }
                    case PACKING -> {
                        packedSky[position >>> 1] |= (byte) (sky[position] << ((position & 1) * 4));
                        packedBlock[position >>> 1] |= (byte) (block[position] << ((position & 1) * 4));
                        position++; work++;
                        if (position == input.cells) {
                            result = new Result(input.height, packedSky, packedBlock, -1, -1);
                            sky = null; block = null; queue = null; queued = null;
                            packedSky = null; packedBlock = null; stage = Stage.COMPLETE;
                        }
                    }
                    case COMPLETE -> { }
                }
            }
            totalWork += work;
            return new Progress(stage, work, totalWork, size);
        }
        private void initialize(int index) {
            int x = index & 15, z = index >>> 4 & 15, y = index >>> 8, column = index & 255;
            if (input.directSkyStops.get(index)) skyOpen.clear(column);
            if (input.emission != null) block[index] = input.emission[index];
            if (input.opaque.get(index)) return;
            if (skyOpen.get(column)) sky[index] = 15;
            if (x == 0) seed(index, Face.WEST, z + y * 16);
            if (x == 15) seed(index, Face.EAST, z + y * 16);
            if (z == 0) seed(index, Face.NORTH, x + y * 16);
            if (z == 15) seed(index, Face.SOUTH, x + y * 16);
            if (y == 0) seed(index, Face.DOWN, column);
            if (y == input.height - 1) seed(index, Face.UP, column);
            if (sky[index] > 0) enqueue(index);
        }
        private void seed(int index, Face face, int faceIndex) {
            int f = face.ordinal();
            if (!input.openFaces[f].get(faceIndex)) return;
            int loss = input.loss(index);
            sky[index] = (byte) Math.max(sky[index], input.skyOutside[f][faceIndex] - loss);
            block[index] = (byte) Math.max(block[index], input.blockOutside[f][faceIndex] - loss);
        }
        private void propagate(byte[] values) {
            int index = queue[head++]; if (head == queue.length) head = 0;
            size--; queued.clear(index);
            int level = values[index];
            if (level <= 1) return;
            int x = index & 15, z = index >>> 4 & 15, y = index >>> 8;
            if (input.opacity == null) {
                // Preserve the air/opaque hot path: no scalar array or directed-edge reads.
                int next = level - 1;
                if (x > 0) offerOpaque(values, index - 1, next);
                if (x < 15) offerOpaque(values, index + 1, next);
                if (z > 0) offerOpaque(values, index - 16, next);
                if (z < 15) offerOpaque(values, index + 16, next);
                if (y > 0) offerOpaque(values, index - 256, next);
                if (y < input.height - 1) offerOpaque(values, index + 256, next);
            } else {
                int blocked = input.blockedOutgoingFaces == null ? 0 : input.blockedOutgoingFaces[index];
                if (x > 0 && (blocked & 1) == 0) offerOptical(values, index - 1, level);
                if (x < 15 && (blocked & 2) == 0) offerOptical(values, index + 1, level);
                if (y > 0 && (blocked & 4) == 0) offerOptical(values, index - 256, level);
                if (y < input.height - 1 && (blocked & 8) == 0) offerOptical(values, index + 256, level);
                if (z > 0 && (blocked & 16) == 0) offerOptical(values, index - 16, level);
                if (z < 15 && (blocked & 32) == 0) offerOptical(values, index + 16, level);
            }
        }
        private void offerOpaque(byte[] values, int index, int value) {
            if (values[index] >= value || input.opaque.get(index)) return;
            values[index] = (byte) value; enqueue(index);
        }
        private void offerOptical(byte[] values, int index, int sourceLevel) {
            int value = sourceLevel - input.loss(index);
            if (values[index] >= value) return;
            values[index] = (byte) value; enqueue(index);
        }
        private void enqueue(int index) {
            if (queued.get(index)) return;
            if (size == queue.length) throw new IllegalStateException("Light queue invariant violated");
            queue[tail++] = index; if (tail == queue.length) tail = 0;
            size++; queued.set(index);
        }
    }

    public static Result solve(Snapshot snapshot) {
        // An open sky column with no incoming block light only needs voxel work below its
        // highest retained solid. All complete sections above that band are exactly sky=15,
        // block=0. In particular a five-layer bedrock floor must not scan 384 levels of AIR.
        if (snapshot.opacity == null && snapshot.blockDark && snapshot.directSkyColumns.cardinality() == 256 && !snapshot.opaque.isEmpty()) {
            int bandHeight = Math.max(16, (((snapshot.opaque.length() + 255) / 256 + 15) / 16) * 16);
            if (bandHeight < snapshot.height) {
                byte[][] sky = new byte[6][], block = new byte[6][];
                BitSet[] open = new BitSet[6];
                for (Face face : Face.values()) {
                    int f = face.ordinal(), length = faceSize(bandHeight, face);
                    block[f] = new byte[length];
                    sky[f] = Arrays.copyOf(snapshot.skyOutside[f], length);
                    open[f] = snapshot.openFaces[f].get(0, length);
                }
                Arrays.fill(sky[Face.UP.ordinal()], (byte)15);
                open[Face.UP.ordinal()].set(0, 256);
                Result lower = solve(new Snapshot(bandHeight, snapshot.opaque, block, sky, open, snapshot.directSkyColumns));
                return new Result(snapshot.height, lower.sky, null, 15, 0);
            }
        }
        Session session = new Session(snapshot);
        while (!session.isComplete()) session.step(65_536);
        return session.result();
    }
    private ClearedChunkLighting() { }
}
