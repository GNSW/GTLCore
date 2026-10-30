/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.lighting;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;

/**
 * Detached optical solve over complete-height columns and a horizontal terrain halo.
 * No pre-edit neighbor light enters the problem: the external boundary is dark/closed.
 * Each diffuse edge loses at least one level, so an entire 16-block halo excludes every
 * exterior light contribution to a cropped interior chunk. Direct sky is separately
 * supplied by the host's full vertical terrain proof. The host must validate every
 * captured terrain column and fence older native light writes before publishing.
 * Exterior reconciliation may be omitted only when the installed footprint includes
 * the complete horizontal influence of every change represented by that publication;
 * otherwise the host must also reconcile its outside installation cut.
 */
public final class GeneralChunkLighting {
    public static final int MAX_HORIZONTAL_SIZE = 256;
    public static final int MAX_CELLS = 16_777_216;
    public static final long DEFAULT_WORKING_BYTES = 256L * 1024 * 1024;
    private static final int INITIAL_QUEUE_CAPACITY = 65_536;
    public enum Stage { INITIALIZING, SKY_SEEDING, SKY, BLOCK_SEEDING, BLOCK, PACKING, COMPLETE }

    /**
     * Immutable scalar optics. Cell index is x + width * (z + depth * y), and a vertical
     * column's index is x + width * z. Each opacity/emission value is 0..15. Optional
     * blockedOutgoingFaces contains six bits in ClearedChunkLighting.Face ordinal order:
     * WEST, EAST, DOWN, UP, NORTH, SOUTH. A bit blocks the edge FROM its cell, incorporating
     * the host's combined source/destination shape test. It need not be symmetric.
     *
     * <p>stopDirectSky means direct sky cannot ENTER the cell. Any nonzero opacity also
     * stops direct sky. Blocked internal downward edges automatically stop direct sky
     * at their lower destination, not their upper source. The stop mask does not suppress diffuse edges.
     * directSkyColumns proves a direct level-15 sky source immediately above the captured
     * full-height column; it must not be inferred from an old neighbor-light nibble.
     */
    public static final class Snapshot {
        private final int width, height, depth, plane, cells;
        private final byte[] opacity, emission, blockedOutgoingFaces;
        private final BitSet directSkyStops, directSkyColumns;
        private final boolean blockDark;

        public Snapshot(int width, int height, int depth, byte[] opacity, byte[] emission,
                byte[] blockedOutgoingFaces, BitSet stopDirectSky, BitSet directSkyColumns) {
            if (width < 48 || width > MAX_HORIZONTAL_SIZE || width % 16 != 0 ||
                    depth < 48 || depth > MAX_HORIZONTAL_SIZE || depth % 16 != 0)
                throw new IllegalArgumentException("Light width/depth must be multiples of 16 in [48,256]");
            if (height < 16 || height > ClearedChunkLighting.MAX_HEIGHT || height % 16 != 0)
                throw new IllegalArgumentException("Light height must be a multiple of 16 in [16,4096]");
            long count = (long)width * depth * height;
            if (count > MAX_CELLS) throw new IllegalArgumentException("Optical region exceeds the cell limit");
            this.width = width; this.height = height; this.depth = depth;
            plane = width * depth; cells = (int)count;
            this.opacity = copyCells(opacity, cells, 15, "opacity");
            this.emission = copyCells(emission, cells, 15, "emission");
            this.blockedOutgoingFaces = blockedOutgoingFaces == null ? null :
                    copyCells(blockedOutgoingFaces, cells, 63, "blocked outgoing faces");
            this.directSkyStops = copyBits(stopDirectSky, cells, "direct sky stops");
            this.directSkyColumns = copyBits(directSkyColumns, plane, "direct sky columns");
            boolean emitting = false;
            for (int index = 0; index < cells; index++) {
                if (this.opacity[index] > 0) this.directSkyStops.set(index);
                if (index >= plane && this.blockedOutgoingFaces != null &&
                        (this.blockedOutgoingFaces[index] & 4) != 0)
                    this.directSkyStops.set(index - plane);
                emitting |= this.emission[index] > 0;
            }
            blockDark = !emitting;
        }
        public int width() { return width; }
        public int height() { return height; }
        public int depth() { return depth; }
        public long estimatedWorkingBytes() { return 4096L + 12L * cells + plane; }
        private static byte[] copyCells(byte[] source, int size, int maximum, String name) {
            Objects.requireNonNull(source, name);
            if (source.length != size) throw new IllegalArgumentException("Wrong " + name + " dimensions");
            byte[] copy = source.clone();
            for (byte value : copy) if (value < 0 || value > maximum)
                throw new IllegalArgumentException(name + " values must be in [0," + maximum + "]");
            return copy;
        }
        private static BitSet copyBits(BitSet source, int size, String name) {
            Objects.requireNonNull(source, name);
            if (source.length() > size) throw new IllegalArgumentException(name + " exceeds its domain");
            return (BitSet)source.clone();
        }
    }

    /** Immutable solution. Only interior column crops are approved for world installation. */
    public static final class Result {
        private final int width, height, depth;
        private final byte[] sky, block;
        private final int uniformSky, uniformBlock;
        private Result(int width, int height, int depth, byte[] sky, byte[] block, int uniformSky, int uniformBlock) {
            this.width = width; this.height = height; this.depth = depth;
            this.sky = sky; this.block = block; this.uniformSky = uniformSky; this.uniformBlock = uniformBlock;
        }
        public int width() { return width; }
        public int height() { return height; }
        public int depth() { return depth; }
        public long estimatedBytes() { return 80L + (sky == null ? 0 : sky.length) + (block == null ? 0 : block.length); }
        /** Local closed-boundary problem value; the outer halo is not an installation result. */
        public int sky(int x, int y, int z) { return value(sky, uniformSky, index(x,y,z)); }
        /** Local closed-boundary problem value; the outer halo is not an installation result. */
        public int block(int x, int y, int z) { return value(block, uniformBlock, index(x,y,z)); }
        public ClearedChunkLighting.Result cropColumn(int chunkXIndex, int chunkZIndex) {
            if (chunkXIndex < 1 || chunkXIndex >= width / 16 - 1 ||
                    chunkZIndex < 1 || chunkZIndex >= depth / 16 - 1)
                throw new IllegalArgumentException("Column must retain a complete 16-block horizontal halo");
            return new ClearedChunkLighting.Result(height,
                    crop(sky, chunkXIndex, chunkZIndex), crop(block, chunkXIndex, chunkZIndex), uniformSky, uniformBlock);
        }
        private byte[] crop(byte[] source, int chunkX, int chunkZ) {
            if (source == null) return null;
            byte[] result = new byte[height * 128];
            int out = 0;
            for (int y = 0; y < height; y++) for (int z = 0; z < 16; z++) {
                int input = (chunkX * 16 + width * (chunkZ * 16 + z + depth * y)) >>> 1;
                System.arraycopy(source, input, result, out, 8); out += 8;
            }
            return result;
        }
        private int index(int x, int y, int z) {
            if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth)
                throw new IndexOutOfBoundsException("Outside optical region");
            return x + width * (z + depth * y);
        }
        private static int value(byte[] data, int uniform, int index) {
            return data == null ? uniform : (data[index >>> 1] >>> (4 * (index & 1))) & 15;
        }
    }

    public record Progress(Stage stage, int work, long totalWork, int queued) { }

    /** Resumable bounded-memory wavefront; also suitable for detached worker execution. */
    public static final class Session {
        private final Snapshot input;
        private final BitSet skyOpen;
        private final boolean directSkyFrontier;
        private int[] lowestDirectY;
        private byte[] sky, block, packedSky, packedBlock;
        private int[] queue;
        private BitSet queued;
        private int head, tail, size, position;
        private int frontierY = -1;
        private long totalWork;
        private Stage stage;
        private Result result;

        public Session(Snapshot snapshot) { this(snapshot, DEFAULT_WORKING_BYTES); }
        public Session(Snapshot snapshot, long maxWorkingBytes) {
            this(snapshot, maxWorkingBytes, true);
        }
        /** The false mode retains the full direct-source queue for independent algorithm comparisons. */
        public Session(Snapshot snapshot, long maxWorkingBytes, boolean directSkyFrontier) {
            input = Objects.requireNonNull(snapshot, "snapshot");
            this.directSkyFrontier = directSkyFrontier;
            if (maxWorkingBytes < snapshot.estimatedWorkingBytes())
                throw new IllegalArgumentException("Optical solver memory budget exceeded");
            skyOpen = (BitSet)snapshot.directSkyColumns.clone();
            if (snapshot.blockDark && (skyOpen.isEmpty() ||
                    snapshot.directSkyStops.isEmpty() && skyOpen.cardinality() == snapshot.plane)) {
                result = new Result(snapshot.width, snapshot.height, snapshot.depth,
                        null, null, skyOpen.isEmpty() ? 0 : 15, 0);
                stage = Stage.COMPLETE;
            } else {
                sky = new byte[snapshot.cells]; block = new byte[snapshot.cells];
                queue = new int[Math.min(snapshot.cells, INITIAL_QUEUE_CAPACITY)]; queued = new BitSet(snapshot.cells);
                if (directSkyFrontier) {
                    lowestDirectY = new int[snapshot.plane];
                    Arrays.fill(lowestDirectY, snapshot.height);
                    for (int column = skyOpen.nextSetBit(0); column >= 0; column = skyOpen.nextSetBit(column + 1))
                        lowestDirectY[column] = 0;
                }
                stage = Stage.INITIALIZING;
            }
        }
        public boolean isComplete() { return stage == Stage.COMPLETE; }
        public Result result() {
            if (!isComplete()) throw new IllegalStateException("Optical solution is not complete");
            return result;
        }
        public Progress step(int maxWork) {
            if (maxWork < 0) throw new IllegalArgumentException("Negative optical work budget");
            int work = 0;
            while (work < maxWork && stage != Stage.COMPLETE) {
                switch (stage) {
                    case INITIALIZING -> {
                        int index = input.cells - 1 - position++, column = index % input.plane;
                        if (input.directSkyStops.get(index) && skyOpen.get(column)) {
                            skyOpen.clear(column);
                            if (directSkyFrontier) lowestDirectY[column] = index / input.plane + 1;
                        }
                        block[index] = input.emission[index];
                        if (skyOpen.get(column)) {
                            sky[index] = 15;
                            if (!directSkyFrontier) enqueue(index);
                        }
                        work++;
                        if (position == input.cells) {
                            stage = directSkyFrontier ? Stage.SKY_SEEDING : Stage.SKY; position = 0;
                        }
                    }
                    case SKY_SEEDING -> {
                        seedSkyFrontier(); work++;
                        if (position == input.plane * 5) { lowestDirectY = null; stage = Stage.SKY; position = 0; }
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
                        packedSky[position >>> 1] |= (byte)(sky[position] << (4 * (position & 1)));
                        packedBlock[position >>> 1] |= (byte)(block[position] << (4 * (position & 1)));
                        position++; work++;
                        if (position == input.cells) {
                            result = new Result(input.width, input.height, input.depth, packedSky, packedBlock, -1, -1);
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
        /**
         * Only edges leaving the direct-15 set can improve diffuse light. Its vertical extent
         * is one interval per column, so visit the downward endpoint and the four differences
         * between neighboring column intervals instead of queuing every exposed air cell.
         * Each call charges one directed edge, or one empty interval, to the work budget.
         */
        private void seedSkyFrontier() {
            int column = position / 5, direction = position % 5;
            int lowest = lowestDirectY[column];
            if (direction == 4) {
                if (lowest > 0 && lowest < input.height) {
                    int source = column + input.plane * lowest;
                    if (input.blockedOutgoingFaces == null || (input.blockedOutgoingFaces[source] & 4) == 0)
                        offer(sky, source - input.plane, 15);
                }
                position++; return;
            }
            int x = column % input.width, neighbor, mask;
            switch (direction) {
                case 0 -> { neighbor = x == 0 ? -1 : column - 1; mask = 1; }
                case 1 -> { neighbor = x == input.width - 1 ? -1 : column + 1; mask = 2; }
                case 2 -> { neighbor = column < input.width ? -1 : column - input.width; mask = 16; }
                case 3 -> { neighbor = column >= input.plane - input.width ? -1 : column + input.width; mask = 32; }
                default -> throw new IllegalStateException("Invalid frontier direction");
            }
            if (neighbor < 0 || lowest >= lowestDirectY[neighbor]) { position++; return; }
            if (frontierY < 0) frontierY = lowest;
            int source = column + input.plane * frontierY;
            if (input.blockedOutgoingFaces == null || (input.blockedOutgoingFaces[source] & mask) == 0)
                offer(sky, neighbor + input.plane * frontierY, 15);
            if (++frontierY == lowestDirectY[neighbor]) { frontierY = -1; position++; }
        }
        private void propagate(byte[] values) {
            int source = queue[head++]; if (head == queue.length) head = 0;
            size--; queued.clear(source);
            int light = values[source]; if (light <= 1) return;
            int x = source % input.width, column = source % input.plane;
            int blocked = input.blockedOutgoingFaces == null ? 0 : input.blockedOutgoingFaces[source];
            if (x > 0 && (blocked & 1) == 0) offer(values, source - 1, light);
            if (x < input.width - 1 && (blocked & 2) == 0) offer(values, source + 1, light);
            if (source >= input.plane && (blocked & 4) == 0) offer(values, source - input.plane, light);
            if (source < input.cells - input.plane && (blocked & 8) == 0) offer(values, source + input.plane, light);
            if (column >= input.width && (blocked & 16) == 0) offer(values, source - input.width, light);
            if (column < input.plane - input.width && (blocked & 32) == 0) offer(values, source + input.width, light);
        }
        private void offer(byte[] values, int target, int sourceLight) {
            int level = sourceLight - Math.max(1, input.opacity[target]);
            if (values[target] >= level) return;
            values[target] = (byte)level; enqueue(target);
        }
        private void enqueue(int index) {
            if (queued.get(index)) return;
            if (size == queue.length) growQueue();
            queue[tail++] = index; if (tail == queue.length) tail = 0;
            size++; queued.set(index);
        }
        private void growQueue() {
            if (queue.length == input.cells) throw new IllegalStateException("Optical queue invariant violated");
            int[] expanded = new int[Math.min(input.cells, queue.length * 2)];
            int first = Math.min(size, queue.length - head);
            System.arraycopy(queue, head, expanded, 0, first);
            System.arraycopy(queue, 0, expanded, first, size - first);
            queue = expanded; head = 0; tail = size;
        }
    }

    public static Result solve(Snapshot snapshot) {
        Session session = new Session(snapshot);
        while (!session.isComplete()) session.step(65_536);
        return session.result();
    }
    private GeneralChunkLighting() { }
}
