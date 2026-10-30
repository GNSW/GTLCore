/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.voxelregion.lighting.GeneralChunkLighting;

/** Main-thread palette capture; workers only see detached indices and immutable optical shapes. */
final class TerrainLightSnapshot {
    static final class UnsupportedOptics extends RuntimeException {
        final String stateId, blockClass, reason;
        UnsupportedOptics(BlockState state) {
            super("Context-dependent optics lack a tracked mutation proof: " + state
                    + " class=" + state.getBlock().getClass().getName());
            stateId = BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "#" + Block.getId(state);
            blockClass = state.getBlock().getClass().getName();
            boolean shape = state.getBlock().hasDynamicShape();
            boolean emission = !DEFAULT_EMISSION.get(state.getBlock().getClass());
            reason = shape && emission ? "dynamic_shape_and_contextual_emission"
                    : shape ? "dynamic_shape" : "contextual_emission";
        }
    }
    private static final Direction[] FACES = { Direction.WEST, Direction.EAST, Direction.DOWN,
            Direction.UP, Direction.NORTH, Direction.SOUTH };
    private static final ClassValue<Boolean> DEFAULT_EMISSION = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("getLightEmission", BlockState.class, BlockGetter.class, BlockPos.class)
                        .getDeclaringClass().getName().equals("net.minecraftforge.common.extensions.IForgeBlock");
            } catch (ReflectiveOperationException failure) { return false; }
        }
    };

    private record Optical(int opacity, int emission, VoxelShape[] faces) { }
    private record OpticalKey(int opacity, int emission, VoxelShape west, VoxelShape east,
            VoxelShape down, VoxelShape up, VoxelShape north, VoxelShape south) { }
    private record Section(NativePaletteImage image, char[] palette, char[] direct) { }
    private final ServerLevel level;
    private final List<LevelChunk> chunks;
    private final int columnsX, columnsZ, sectionCount, minY;
    private final Section[] sections;
    private final List<Optical> optical = new ArrayList<>();
    private final Map<BlockState, Integer> staticStates = new IdentityHashMap<>();
    private final Map<OpticalKey, Integer> capturedOptics = new HashMap<>();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private int nextSection;

    TerrainLightSnapshot(ServerLevel level, List<LevelChunk> chunks, int columnsX, int columnsZ) {
        this.level = level; this.chunks = List.copyOf(chunks);
        this.columnsX = columnsX; this.columnsZ = columnsZ;
        if (chunks.size() != columnsX * columnsZ) throw new IllegalArgumentException("Incomplete light rectangle");
        sectionCount = level.getSectionsCount(); minY = level.getMinBuildHeight();
        sections = new Section[chunks.size() * sectionCount];
        optical.add(new Optical(0, 0, null));
    }

    /** Returns only after all palette copies are owned; a caller must validate all chunk revisions afterwards. */
    boolean capture(long deadline) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Optical capture requires world owner");
        while (nextSection < sections.length && System.nanoTime() < deadline) {
            int index = nextSection++, column = index / sectionCount, sectionY = index % sectionCount;
            var chunk = chunks.get(column); var section = chunk.getSection(sectionY);
            if (section.hasOnlyAir() && !section.maybeHas(state -> !state.is(Blocks.AIR)
                    && !state.is(Blocks.CAVE_AIR) && !state.is(Blocks.VOID_AIR))) continue;
            NativePaletteImage image = NativePaletteImage.capture(section.getStates());
            cursor.set(chunk.getPos().getMinBlockX(), minY + sectionY * 16, chunk.getPos().getMinBlockZ());
            if (image == null) {
                char[] direct = new char[4096];
                for (int i = 0; i < 4096; i++) {
                    cursor.set(chunk.getPos().getMinBlockX() + (i & 15), minY + sectionY * 16 + (i >>> 8),
                            chunk.getPos().getMinBlockZ() + ((i >>> 4) & 15));
                    direct[i] = (char) material(section.getBlockState(i & 15, i >>> 8, (i >>> 4) & 15));
                }
                sections[index] = new Section(null, null, direct); continue;
            }
            char[] palette = new char[image.states.size()];
            boolean dynamic = false;
            for (int i = 0; i < palette.length; i++) {
                var state = image.states.get(i);
                if (constant(state)) palette[i] = (char) material(state);
                else { palette[i] = Character.MAX_VALUE; dynamic = true; }
            }
            char[] direct = null;
            if (dynamic) {
                direct = new char[4096];
                for (int i = 0; i < 4096; i++) {
                    int id = image.indices.get(i);
                    if (palette[id] != Character.MAX_VALUE) direct[i] = palette[id];
                    else {
                        cursor.set(chunk.getPos().getMinBlockX() + (i & 15), minY + sectionY * 16 + (i >>> 8),
                                chunk.getPos().getMinBlockZ() + ((i >>> 4) & 15));
                        direct[i] = (char) material(image.states.get(id));
                    }
                }
            }
            sections[index] = new Section(image, palette, direct);
        }
        return nextSection == sections.length;
    }

    private static boolean constant(BlockState state) {
        return !state.getBlock().hasDynamicShape() && DEFAULT_EMISSION.get(state.getBlock().getClass());
    }

    private int material(BlockState state) {
        boolean constant = constant(state);
        // A block entity can change light/shape without changing BlockState or the chunk revision.
        // Position-by-position owner capture alone cannot prove that context stays fixed on a worker.
        if (!constant) throw new UnsupportedOptics(state);
        Integer old = constant ? staticStates.get(state) : null;
        if (old != null) return old;
        int opacity = Math.max(0, Math.min(15, state.getLightBlock(level, cursor)));
        int emission = Math.max(0, Math.min(15, state.getLightEmission(level, cursor)));
        VoxelShape[] faces = null;
        if (state.useShapeForLightOcclusion()) {
            faces = new VoxelShape[6];
            for (int face = 0; face < 6; face++) faces[face] = state.getFaceOcclusionShape(level, cursor, FACES[face]);
        }
        int id;
        if (opacity == 0 && emission == 0 && faces == null) id = 0;
        else {
            // Contextual blocks often return the same optical values at many positions. They
            // still get evaluated on the owner at every position; only the captured value is shared.
            var key = new OpticalKey(opacity, emission, faces == null ? null : faces[0], faces == null ? null : faces[1],
                    faces == null ? null : faces[2], faces == null ? null : faces[3],
                    faces == null ? null : faces[4], faces == null ? null : faces[5]);
            Integer existing = capturedOptics.get(key);
            if (existing != null) id = existing;
            else {
                if (optical.size() >= Character.MAX_VALUE) throw new IllegalStateException("Too many contextual optical values");
                id = optical.size(); optical.add(new Optical(opacity, emission, faces)); capturedOptics.put(key, id);
            }
        }
        if (constant) staticStates.put(state, id);
        return id;
    }

    /** Called on a worker. This method does not invoke any block or world methods. */
    GeneralChunkLighting.Snapshot assemble(boolean hasSky) {
        if (nextSection != sections.length) throw new IllegalStateException("Incomplete optical capture");
        int width = columnsX * 16, depth = columnsZ * 16, height = sectionCount * 16;
        int area = width * depth, cells = area * height;
        byte[] opacity = new byte[cells], emission = new byte[cells], blocked = new byte[cells];
        char[] materials = new char[cells]; BitSet shaped = new BitSet(cells);
        Optical[] values = optical.toArray(Optical[]::new);
        for (int column = 0; column < chunks.size(); column++) {
            int baseX = (column % columnsX) * 16, baseZ = (column / columnsX) * 16;
            for (int sectionY = 0; sectionY < sectionCount; sectionY++) {
                Section section = sections[column * sectionCount + sectionY];
                if (section == null) continue;
                for (int i = 0; i < 4096; i++) {
                    int id = section.direct != null ? section.direct[i] : section.palette[section.image.indices.get(i)];
                    if (id == 0) continue;
                    int index = baseX + (i & 15) + width * (baseZ + ((i >>> 4) & 15)) + area * (sectionY * 16 + (i >>> 8));
                    Optical value = values[id]; materials[index] = (char) id;
                    opacity[index] = (byte) value.opacity; emission[index] = (byte) value.emission;
                    if (value.faces != null) shaped.set(index);
                }
            }
        }
        int[] offsets = { -1, 1, -area, area, -width, width };
        for (int index = shaped.nextSetBit(0); index >= 0; index = shaped.nextSetBit(index + 1)) {
            int x = index % width, z = index / width % depth, y = index / area;
            for (int face = 0; face < 6; face++) {
                if (face == 0 && x == 0 || face == 1 && x + 1 == width || face == 2 && y == 0
                        || face == 3 && y + 1 == height || face == 4 && z == 0 || face == 5 && z + 1 == depth) continue;
                int neighbor = index + offsets[face], opposite = face ^ 1;
                Optical a = values[materials[index]], b = values[materials[neighbor]];
                VoxelShape first = a.faces == null ? Shapes.empty() : a.faces[face];
                VoxelShape second = b.faces == null ? Shapes.empty() : b.faces[opposite];
                if (Shapes.faceShapeOccludes(first, second)) {
                    blocked[index] |= (byte) (1 << face); blocked[neighbor] |= (byte) (1 << opposite);
                }
            }
        }
        BitSet stopDirect = new BitSet(cells), direct = new BitSet(area);
        if (hasSky) direct.set(0, area);
        for (int index = 0; index < cells; index++) {
            if (opacity[index] != 0) stopDirect.set(index);
            if ((blocked[index] & 4) != 0 && index >= area) stopDirect.set(index - area);
        }
        return new GeneralChunkLighting.Snapshot(width, height, depth, opacity, emission, blocked, stopDirect, direct);
    }
}
