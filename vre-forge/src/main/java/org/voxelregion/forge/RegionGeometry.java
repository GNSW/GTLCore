/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.voxelregion.geometry.*;

/** Persistable, immutable geometry for the Forge adapter. Coordinates retain the core's exact half-block convention. */
public sealed interface RegionGeometry {
    Volume volume();
    CompoundTag save();
    String canonical();
    /** Continuous entity test, separate from the core's block-center sampling. */
    default boolean containsPoint(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return false;
        if (this instanceof Ball b) {
            double dx = x * 2 - b.center.x2(), dy = y * 2 - b.center.y2(), dz = z * 2 - b.center.z2();
            return dx * dx + dy * dy + dz * dz <= (double)b.radius2 * b.radius2;
        }
        if (this instanceof Column c) {
            double dx = x * 2 - c.center.x2(), dz = z * 2 - c.center.z2();
            return y >= c.minY && y < c.maxY && dx * dx + dz * dz <= (double)c.radius2 * c.radius2;
        }
        if (this instanceof Box b) return x >= b.bounds.minX() && x < b.bounds.maxX()
                && y >= b.bounds.minY() && y < b.bounds.maxY() && z >= b.bounds.minZ() && z < b.bounds.maxZ();
        Combined c = (Combined)this;
        return switch (c.operation) {
            case UNION -> c.left.containsPoint(x, y, z) || c.right.containsPoint(x, y, z);
            case INTERSECTION -> c.left.containsPoint(x, y, z) && c.right.containsPoint(x, y, z);
            case DIFFERENCE -> c.left.containsPoint(x, y, z) && !c.right.containsPoint(x, y, z);
        };
    }

    record Ball(Point3 center, long radius2) implements RegionGeometry {
        public Ball { new Sphere(Objects.requireNonNull(center), radius2); }
        public Volume volume() { return new Sphere(center, radius2); }
        public CompoundTag save() {
            CompoundTag t = base("sphere"); point(t, center); t.putLong("radius2", radius2); return t;
        }
        public String canonical() { return "sphere:" + center.x2() + ":" + center.y2() + ":" + center.z2() + ":" + radius2; }
    }
    record Box(IntBox bounds) implements RegionGeometry {
        public Box { Objects.requireNonNull(bounds); }
        public Volume volume() { return new BoxVolume(bounds); }
        public CompoundTag save() {
            CompoundTag t = base("box");
            t.putIntArray("bounds", new int[]{bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ()});
            return t;
        }
        public String canonical() { return "box:" + bounds.minX() + ":" + bounds.minY() + ":" + bounds.minZ() + ":" + bounds.maxX() + ":" + bounds.maxY() + ":" + bounds.maxZ(); }
    }
    record Column(Point3 center, long radius2, int minY, int maxY) implements RegionGeometry {
        public Column { new Cylinder(Objects.requireNonNull(center), radius2, minY, maxY); }
        public Volume volume() { return new Cylinder(center, radius2, minY, maxY); }
        public CompoundTag save() {
            CompoundTag t = base("cylinder"); point(t, center); t.putLong("radius2", radius2);
            t.putInt("minY", minY); t.putInt("maxY", maxY); return t;
        }
        public String canonical() { return "cylinder:" + center.x2() + ":" + center.y2() + ":" + center.z2() + ":" + radius2 + ":" + minY + ":" + maxY; }
    }
    enum BooleanOperation { UNION, INTERSECTION, DIFFERENCE }
    record Combined(BooleanOperation operation, RegionGeometry left, RegionGeometry right) implements RegionGeometry {
        public Combined {
            Objects.requireNonNull(operation); Objects.requireNonNull(left); Objects.requireNonNull(right);
            if (depth(left) >= 32 || depth(right) >= 32 || nodes(left) + nodes(right) >= 256)
                throw new IllegalArgumentException("Geometry expression exceeds depth/node budget");
        }
        public Volume volume() {
            Volume a = left.volume(), b = right.volume();
            return switch (operation) {
                case UNION -> Volumes.union(a, b); case INTERSECTION -> Volumes.intersection(a, b); case DIFFERENCE -> Volumes.difference(a, b);
            };
        }
        public CompoundTag save() {
            CompoundTag t = base(operation.name()); t.put("left", left.save()); t.put("right", right.save()); return t;
        }
        public String canonical() { return operation.name() + "(" + left.canonical() + "," + right.canonical() + ")"; }
    }
    static RegionGeometry load(CompoundTag tag) { return load(tag, 0, new int[1]); }
    private static RegionGeometry load(CompoundTag t, int depth, int[] nodes) {
        if (depth >= 32 || ++nodes[0] > 256 || t.getInt("schema") != 1)
            throw new IllegalArgumentException("Invalid or oversized geometry schema");
        return switch (t.getString("type")) {
            case "sphere" -> new Ball(point(t), requiredLong(t, "radius2"));
            case "cylinder" -> {
                if (!t.contains("minY", Tag.TAG_INT) || !t.contains("maxY", Tag.TAG_INT)) throw new IllegalArgumentException("Missing cylinder bounds");
                yield new Column(point(t), requiredLong(t, "radius2"), t.getInt("minY"), t.getInt("maxY"));
            }
            case "box" -> {
                int[] b = t.getIntArray("bounds"); if (b.length != 6) throw new IllegalArgumentException("Expected six box bounds");
                yield new Box(new IntBox(b[0], b[1], b[2], b[3], b[4], b[5]));
            }
            case "UNION", "INTERSECTION", "DIFFERENCE" -> new Combined(BooleanOperation.valueOf(t.getString("type")),
                    load(t.getCompound("left"), depth + 1, nodes), load(t.getCompound("right"), depth + 1, nodes));
            default -> throw new IllegalArgumentException("Unknown geometry kind");
        };
    }
    private static int depth(RegionGeometry g) { return g instanceof Combined c ? 1 + Math.max(depth(c.left), depth(c.right)) : 1; }
    private static int nodes(RegionGeometry g) { return g instanceof Combined c ? 1 + nodes(c.left) + nodes(c.right) : 1; }
    private static CompoundTag base(String type) { CompoundTag t = new CompoundTag(); t.putInt("schema", 1); t.putString("type", type); return t; }
    private static void point(CompoundTag tag, Point3 p) { tag.putLong("x2", p.x2()); tag.putLong("y2", p.y2()); tag.putLong("z2", p.z2()); }
    private static Point3 point(CompoundTag tag) { return new Point3(requiredLong(tag, "x2"), requiredLong(tag, "y2"), requiredLong(tag, "z2")); }
    private static long requiredLong(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LONG)) throw new IllegalArgumentException("Missing geometry field: " + key);
        return tag.getLong(key);
    }
}
