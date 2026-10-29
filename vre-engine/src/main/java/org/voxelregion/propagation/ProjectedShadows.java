/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.propagation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Point3;
import org.voxelregion.geometry.VoxelMask;
import org.voxelregion.spatial.SectionKey;

/** Bounded immutable BVH for quantized extrusions or exact snapshot visibility. */
final class ProjectedShadows implements ShadowField {
    private final Point3 center;
    private final IntBox domain;
    private final boolean raycast;
    private final boolean sourceBlocked;
    private final Node root;
    private final int sourceCount;
    private final int primitiveCount;

    ProjectedShadows(Point3 center, IntBox domain, List<IntBox> blockers, int maxBlockers, boolean raycast) {
        this.center = Objects.requireNonNull(center, "center");
        this.domain = Objects.requireNonNull(domain, "explosionBounds");
        Objects.requireNonNull(blockers, "blockers");
        if (maxBlockers < 1) throw new IllegalArgumentException("maxBlockers must be positive");
        if (blockers.size() > maxBlockers) throw new IllegalArgumentException("Obstacle snapshot exceeds its budget");
        List<IntBox> frozen = List.copyOf(blockers);
        if (frozen.size() > maxBlockers) throw new IllegalArgumentException("Obstacle snapshot exceeds its budget");
        this.raycast = raycast;
        sourceCount = frozen.size();
        ArrayList<Primitive> primitives = new ArrayList<>(frozen.size());
        boolean blocked = false;
        if (!domain.isEmpty()) {
            for (IntBox box : frozen) {
                if (box.isEmpty()) continue;
                if (ExactShadowMath.containsCenter(box, center)) {
                    blocked = true;
                    break;
                }
                if (raycast) {
                    // An occluder between a center outside the requested domain
                    // and a target inside it can still be relevant.
                    primitives.add(new Primitive(box, box, 0, 0, 0));
                } else {
                    int direction = direction(center, box);
                    int dx = (direction & 1) == 0 ? 0 : (direction & 8) == 0 ? 1 : -1;
                    int dy = (direction & 2) == 0 ? 0 : (direction & 16) == 0 ? 1 : -1;
                    int dz = (direction & 4) == 0 ? 0 : (direction & 32) == 0 ? 1 : -1;
                    IntBox bounds = new IntBox(dx < 0 ? Math.min(box.minX(), domain.minX()) : box.minX(),
                            dy < 0 ? Math.min(box.minY(), domain.minY()) : box.minY(),
                            dz < 0 ? Math.min(box.minZ(), domain.minZ()) : box.minZ(),
                            dx > 0 ? Math.max(box.maxX(), domain.maxX()) : box.maxX(),
                            dy > 0 ? Math.max(box.maxY(), domain.maxY()) : box.maxY(),
                            dz > 0 ? Math.max(box.maxZ(), domain.maxZ()) : box.maxZ()).intersection(domain);
                    if (!bounds.isEmpty()) primitives.add(new Primitive(box, bounds, dx, dy, dz));
                }
            }
        }
        sourceBlocked = blocked;
        root = blocked ? null : build(primitives, 0, primitives.size());
        primitiveCount = blocked ? 1 : primitives.size();
    }

    @Override public int sourceBlockerCount() { return sourceCount; }
    @Override public int indexedPrimitiveCount() { return primitiveCount; }
    @Override public long estimatedBytes() { return 128L + 512L * primitiveCount; }

    @Override
    public Coverage classify(IntBox box) {
        Objects.requireNonNull(box, "box");
        if (!domain.intersects(box)) return Coverage.OUTSIDE;
        IntBox clipped = domain.intersection(box);
        Coverage result = sourceBlocked ? Coverage.INSIDE : classify(root, clipped);
        return result == Coverage.INSIDE && !domain.contains(box) ? Coverage.PARTIAL : result;
    }

    @Override
    public VoxelMask shadowMask(SectionKey section) {
        Objects.requireNonNull(section, "section");
        IntBox bounds = section.bounds();
        Coverage coverage = classify(bounds);
        if (coverage == Coverage.OUTSIDE) return VoxelMask.EMPTY;
        if (coverage == Coverage.INSIDE) return VoxelMask.ALL;
        IntBox clipped = domain.intersection(bounds);
        VoxelMask.Builder mask = VoxelMask.builder();
        for (int x = clipped.minX(); x < clipped.maxX(); x++) {
            for (int z = clipped.minZ(); z < clipped.maxZ(); z++) {
                int start = clipped.minY();
                boolean emitting = false;
                for (int y = clipped.minY(); y < clipped.maxY(); y++) {
                    boolean blocked = sourceBlocked || contains(root, x, y, z);
                    if (blocked && !emitting) {
                        start = y;
                        emitting = true;
                    }
                    if (!blocked && emitting) {
                        mask.setRange(x - bounds.minX(), z - bounds.minZ(), start - bounds.minY(), y - bounds.minY());
                        emitting = false;
                    }
                }
                if (emitting) mask.setRange(x - bounds.minX(), z - bounds.minZ(),
                        start - bounds.minY(), clipped.maxY() - bounds.minY());
            }
        }
        return mask.build();
    }

    private Coverage classify(Node node, IntBox box) {
        if (node == null) return Coverage.OUTSIDE;
        if (raycast ? !fanMayIntersect(node.bounds, box) : !node.bounds.intersects(box)) return Coverage.OUTSIDE;
        if (node.primitive != null) {
            Primitive p = node.primitive;
            if (!raycast && !ExactShadowMath.extrusionHits(p.blocker, p.dx, p.dy, p.dz,
                    2L * box.minX() + 1, 2L * box.minY() + 1, 2L * box.minZ() + 1,
                    2L * box.maxX() - 1, 2L * box.maxY() - 1, 2L * box.maxZ() - 1)) return Coverage.OUTSIDE;
            // Both a swept convex box and a box's perspective shadow are convex.
            // One primitive containing all eight corners proves full coverage.
            for (int corner = 0; corner < 8; corner++) {
                int x = (corner & 1) == 0 ? box.minX() : box.maxX() - 1;
                int y = (corner & 2) == 0 ? box.minY() : box.maxY() - 1;
                int z = (corner & 4) == 0 ? box.minZ() : box.maxZ() - 1;
                if (!contains(p, x, y, z)) return Coverage.PARTIAL;
            }
            return Coverage.INSIDE;
        }
        Coverage left = classify(node.left, box);
        if (left == Coverage.INSIDE) return left;
        Coverage right = classify(node.right, box);
        if (right == Coverage.INSIDE) return right;
        return left == Coverage.OUTSIDE && right == Coverage.OUTSIDE ? Coverage.OUTSIDE : Coverage.PARTIAL;
    }

    private boolean contains(Node node, int x, int y, int z) {
        if (node == null) return false;
        if (raycast ? !ExactShadowMath.rayHits(center, 2L * x + 1, 2L * y + 1, 2L * z + 1, node.bounds)
                : !node.bounds.contains(x, y, z)) return false;
        if (node.primitive != null) return contains(node.primitive, x, y, z);
        return contains(node.left, x, y, z) || contains(node.right, x, y, z);
    }

    private boolean contains(Primitive primitive, int x, int y, int z) {
        long px = 2L * x + 1, py = 2L * y + 1, pz = 2L * z + 1;
        return raycast ? ExactShadowMath.rayHits(center, px, py, pz, primitive.blocker)
                : ExactShadowMath.extrusionHits(primitive.blocker, primitive.dx, primitive.dy, primitive.dz,
                        px, py, pz, px, py, pz);
    }

    private boolean fanMayIntersect(IntBox blocker, IntBox targets) {
        for (int axis = 0; axis < 3; axis++) {
            long lo = Math.min(ExactShadowMath.coordinate(center, axis), 2L * ExactShadowMath.min(targets, axis) + 1);
            long hi = Math.max(ExactShadowMath.coordinate(center, axis), 2L * ExactShadowMath.max(targets, axis) - 1);
            // Closed coarse test deliberately retains grazing candidates.
            if (hi < 2L * ExactShadowMath.min(blocker, axis) || lo > 2L * ExactShadowMath.max(blocker, axis)) return false;
        }
        return true;
    }

    /** Active axes plus their negative sign bits; shared with shadow-preserving box compaction. */
    static int direction(Point3 center, IntBox box) {
        int axes = quantize(center, box), result = axes;
        if ((axes & 1) != 0 && (long) box.minX() + box.maxX() < center.x2()) result |= 8;
        if ((axes & 2) != 0 && (long) box.minY() + box.maxY() < center.y2()) result |= 16;
        if ((axes & 4) != 0 && (long) box.minZ() + box.maxZ() < center.z2()) result |= 32;
        return result;
    }

    /** Closest angular direction; exact score (dot^2 / length^2), ties X,Y,Z mask order. */
    private static int quantize(Point3 center, IntBox box) {
        long ax = Math.abs((long) box.minX() + box.maxX() - center.x2());
        long ay = Math.abs((long) box.minY() + box.maxY() - center.y2());
        long az = Math.abs((long) box.minZ() + box.maxZ() - center.z2());
        long bestScore = -1;
        int bestLength = 1, bestMask = 1;
        for (int mask = 1; mask < 8; mask++) {
            if (((mask & 1) != 0 && ax == 0) || ((mask & 2) != 0 && ay == 0) || ((mask & 4) != 0 && az == 0)) continue;
            long dot = ((mask & 1) != 0 ? ax : 0) + ((mask & 2) != 0 ? ay : 0) + ((mask & 4) != 0 ? az : 0);
            long score = dot * dot;
            int length = Integer.bitCount(mask);
            if (score * bestLength > bestScore * length) {
                bestScore = score;
                bestLength = length;
                bestMask = mask;
            }
        }
        return bestMask;
    }

    private static int sign(long value) { return value < 0 ? -1 : 1; }

    private static Node build(ArrayList<Primitive> primitives, int from, int to) {
        if (from == to) return null;
        IntBox bounds = primitives.get(from).bounds;
        for (int i = from + 1; i < to; i++) bounds = union(bounds, primitives.get(i).bounds);
        if (to - from == 1) return new Node(bounds, primitives.get(from), null, null);
        int sx = bounds.maxX() - bounds.minX(), sy = bounds.maxY() - bounds.minY(), sz = bounds.maxZ() - bounds.minZ();
        int axis = sx >= sy && sx >= sz ? 0 : sy >= sz ? 1 : 2;
        primitives.subList(from, to).sort(Comparator.comparingLong(p -> (long) ExactShadowMath.min(p.bounds, axis) + ExactShadowMath.max(p.bounds, axis)));
        int mid = (from + to) >>> 1;
        return new Node(bounds, null, build(primitives, from, mid), build(primitives, mid, to));
    }

    private static IntBox union(IntBox a, IntBox b) {
        return new IntBox(Math.min(a.minX(), b.minX()), Math.min(a.minY(), b.minY()), Math.min(a.minZ(), b.minZ()),
                Math.max(a.maxX(), b.maxX()), Math.max(a.maxY(), b.maxY()), Math.max(a.maxZ(), b.maxZ()));
    }

    private record Primitive(IntBox blocker, IntBox bounds, int dx, int dy, int dz) { }
    private record Node(IntBox bounds, Primitive primitive, Node left, Node right) { }
}
