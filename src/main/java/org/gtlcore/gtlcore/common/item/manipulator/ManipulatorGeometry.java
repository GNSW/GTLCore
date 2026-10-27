package org.gtlcore.gtlcore.common.item.manipulator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashMap;
import java.util.Map;

/** Integer transforms are shared by preview, material planning and execution. */
public final class ManipulatorGeometry {

    private ManipulatorGeometry() {}

    public static BlockPos local(long index, BlockPos size) {
        return new BlockPos((int) (index % size.getX()), (int) (index / size.getX() % size.getY()),
                (int) (index / size.getX() / size.getY()));
    }

    public static BlockPos vector(BlockPos p, ManipulatorSettings s) {
        int x = s.mirror[0] ? -p.getX() : p.getX();
        int y = s.mirror[1] ? -p.getY() : p.getY();
        int z = s.mirror[2] ? -p.getZ() : p.getZ();
        for (int i = 0; i < s.rotation[0]; i++) {
            int old = y;
            y = -z;
            z = old;
        }
        for (int i = 0; i < s.rotation[1]; i++) {
            int old = x;
            x = z;
            z = -old;
        }
        for (int i = 0; i < s.rotation[2]; i++) {
            int old = x;
            x = -y;
            y = old;
        }
        return new BlockPos(x, y, z);
    }

    public static Direction direction(Direction d, ManipulatorSettings s) {
        BlockPos v = vector(BlockPos.ZERO.relative(d), s);
        return Direction.fromDelta(v.getX(), v.getY(), v.getZ());
    }

    public static BlockPos transformedMin(ManipulatorSettings s) {
        BlockPos size = s.size().offset(-1, -1, -1);
        BlockPos min = null;
        for (int x : new int[] { 0, size.getX() }) for (int y : new int[] { 0, size.getY() }) for (int z : new int[] { 0, size.getZ() }) {
            BlockPos v = vector(new BlockPos(x, y, z), s);
            min = min == null ? v : new BlockPos(Math.min(min.getX(), v.getX()), Math.min(min.getY(), v.getY()), Math.min(min.getZ(), v.getZ()));
        }
        return min;
    }

    public static BlockPos extent(ManipulatorSettings s) {
        BlockPos v = vector(s.size().offset(-1, -1, -1), s);
        return new BlockPos(Math.abs(v.getX()) + 1, Math.abs(v.getY()) + 1, Math.abs(v.getZ()) + 1);
    }

    public static BlockPos target(BlockPos local, int array, ManipulatorSettings s) {
        BlockPos repeat = local(array, new BlockPos(s.repeat[0], s.repeat[1], s.repeat[2]));
        BlockPos extent = extent(s);
        BlockPos transformed = vector(local, s).subtract(transformedMin(s));
        return new BlockPos(Math.toIntExact((long) s.anchor.getX() + transformed.getX() + (long) repeat.getX() * ((long) extent.getX() + s.spacing[0])),
                Math.toIntExact((long) s.anchor.getY() + transformed.getY() + (long) repeat.getY() * ((long) extent.getY() + s.spacing[1])),
                Math.toIntExact((long) s.anchor.getZ() + transformed.getZ() + (long) repeat.getZ() * ((long) extent.getZ() + s.spacing[2])));
    }

    /** -1 excludes a cell; 0/1/2/3 identify corner/edge/surface/interior materials. */
    public static int material(BlockPos p, ManipulatorSettings s) {
        BlockPos size = s.size();
        if (s.shape == ManipulatorSettings.Shape.LINE) {
            BlockPos relativeA = s.a.subtract(s.min());
            BlockPos relativeB = s.b.subtract(s.min());
            int steps = Math.max(size.getX(), Math.max(size.getY(), size.getZ())) - 1;
            int axis = size.getX() >= size.getY() && size.getX() >= size.getZ() ? 0 : size.getY() >= size.getZ() ? 1 : 2;
            int t = axis == 0 ? p.getX() : axis == 1 ? p.getY() : p.getZ();
            int start = axis == 0 ? relativeA.getX() : axis == 1 ? relativeA.getY() : relativeA.getZ();
            if (start > 0) t = steps - t;
            for (int i = 0; i < 3; i++) {
                int av = i == 0 ? relativeA.getX() : i == 1 ? relativeA.getY() : relativeA.getZ();
                int bv = i == 0 ? relativeB.getX() : i == 1 ? relativeB.getY() : relativeB.getZ();
                int pv = i == 0 ? p.getX() : i == 1 ? p.getY() : p.getZ();
                if (pv != av + (steps == 0 ? 0 : (int) Math.round((bv - av) * (double) t / steps))) return -1;
            }
            return 1;
        }
        if (s.shape == ManipulatorSettings.Shape.BOX) {
            int boundary = (p.getX() == 0 || p.getX() == size.getX() - 1 ? 1 : 0) +
                    (p.getY() == 0 || p.getY() == size.getY() - 1 ? 1 : 0) +
                    (p.getZ() == 0 || p.getZ() == size.getZ() - 1 ? 1 : 0);
            return 3 - boundary;
        }
        if (!inside(p, size, s.shape)) return -1;
        int boundary = 0;
        for (Direction.Axis axis : Direction.Axis.values()) {
            boolean edge = false;
            for (Direction d : Direction.values()) if (d.getAxis() == axis && !inside(p.relative(d), size, s.shape)) edge = true;
            if (edge) boundary++;
        }
        return 3 - boundary;
    }

    private static boolean inside(BlockPos p, BlockPos size, ManipulatorSettings.Shape shape) {
        if (p.getX() < 0 || p.getX() >= size.getX() || p.getY() < 0 || p.getY() >= size.getY() || p.getZ() < 0 || p.getZ() >= size.getZ()) return false;
        double x = (2.0 * p.getX() + 1 - size.getX()) / size.getX();
        double y = (2.0 * p.getY() + 1 - size.getY()) / size.getY();
        double z = (2.0 * p.getZ() + 1 - size.getZ()) / size.getZ();
        return x * x + z * z + (shape == ManipulatorSettings.Shape.SPHERE ? y * y : 0) <= 1;
    }

    public static BlockState state(BlockState original, ManipulatorSettings s) {
        if (s.rotation[0] == 0 && s.rotation[2] == 0 && !s.mirror[1]) {
            BlockState result = original;
            if (s.mirror[0]) result = result.mirror(net.minecraft.world.level.block.Mirror.FRONT_BACK);
            if (s.mirror[2]) result = result.mirror(net.minecraft.world.level.block.Mirror.LEFT_RIGHT);
            for (int i = 0; i < s.rotation[1]; i++) result = result.rotate(net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90);
            return result;
        }
        BlockState result = original;
        Map<String, Comparable<?>> values = new HashMap<>();
        for (Property<?> property : original.getProperties()) {
            Comparable<?> value = original.getValue(property);
            String name = property.getName();
            // Vanilla asymmetric state schemas need a dedicated adapter for vertical rotations.
            if (property == net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF ||
                    property == net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF ||
                    property == net.minecraft.world.level.block.state.properties.BlockStateProperties.STAIRS_SHAPE ||
                    property == net.minecraft.world.level.block.state.properties.BlockStateProperties.ROTATION_16)
                throw new IllegalArgumentException("mm.error.rotation");
            Direction side = Direction.byName(name);
            if (value instanceof Direction d) value = direction(d, s);
            else if (value instanceof Direction.Axis axis) value = direction(Direction.get(Direction.AxisDirection.POSITIVE, axis), s).getAxis();
            if (side != null) name = direction(side, s).getName();
            values.put(name, value);
        }
        for (Property<?> property : original.getProperties()) result = set(result, property, values.get(property.getName()));
        if (original.getBlock() instanceof appeng.block.AEBaseBlock) {
            var orientation = appeng.api.orientation.BlockOrientation.get(original);
            var strategy = appeng.api.orientation.IOrientationStrategy.get(original);
            Direction front = direction(orientation.getSide(appeng.api.orientation.RelativeSide.FRONT), s);
            Direction top = direction(orientation.getSide(appeng.api.orientation.RelativeSide.TOP), s);
            result = strategy.setOrientation(result, front, top);
        }
        return result;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static BlockState set(BlockState state, Property property, Comparable value) {
        if (!property.getPossibleValues().contains(value)) throw new IllegalArgumentException("mm.error.rotation");
        return state.setValue(property, value);
    }
}
