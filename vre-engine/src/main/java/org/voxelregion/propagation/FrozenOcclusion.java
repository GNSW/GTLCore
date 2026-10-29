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

/**
 * Immutable, bounded obstacle snapshot. AXIS_6 shadows include the blocker and
 * extend away from the blast center with a fixed transverse cross section.
 * Ties choose X, then Y, then Z. If the center is inside any blocker, the whole
 * explosion bounds are shadowed (conservative fail-closed behavior).
 *
 * The host must freeze all relevant obstacles before writes begin. This class
 * does not discover obstacles or read worlds. AXIS_26 quantizes to the closest
 * of 26 directions and continuously extrudes half-open blocker boxes. Refined
 * raycasting tests exact center-to-voxel-center segment visibility through a
 * frozen blocker BVH; it is not blast-energy or fluid simulation. The existing
 * AXIS_6 path still emits box ranges directly. Advanced modes use at most 4096
 * bounded BVH point/ray queries for a partial section; runtime depends on the
 * supplied obstacle budget and is not a strict per-tick time guarantee.
 */
public final class FrozenOcclusion {
    public enum Mode { NONE, AXIS_6, AXIS_26, RAYCAST_REFINED }

    private static final FrozenOcclusion NONE = new FrozenOcclusion(Mode.NONE, null, 0, 0);
    private final Mode mode;
    private final Node root;
    private final int sourceBlockers;
    private final int shadowBoxes;
    private final ShadowField advanced;

    private FrozenOcclusion(Mode mode, Node root, int sourceBlockers, int shadowBoxes) {
        this.mode = mode;
        this.root = root;
        this.sourceBlockers = sourceBlockers;
        this.shadowBoxes = shadowBoxes;
        this.advanced = null;
    }

    private FrozenOcclusion(Mode mode, ShadowField advanced) {
        this.mode = mode;
        this.root = null;
        this.sourceBlockers = advanced.sourceBlockerCount();
        this.shadowBoxes = advanced.indexedPrimitiveCount();
        this.advanced = advanced;
    }

    public static FrozenOcclusion none() { return NONE; }

    /**
     * Returns the exact union of two face-adjacent boxes only when replacing
     * them preserves this mode's shadow field; otherwise returns null. The
     * other two axis intervals must match exactly. Quantized modes additionally
     * require both sources and their union to have the same signed direction.
     * Source-containing boxes are conservatively retained separately in those
     * modes. This pure O(1) predicate is suitable for bounded streaming capture.
     */
    public static IntBox mergeAdjacent(Mode mode, Point3 center, IntBox a, IntBox b) {
        Objects.requireNonNull(mode, "mode"); Objects.requireNonNull(center, "center");
        Objects.requireNonNull(a, "a"); Objects.requireNonNull(b, "b");
        if (a.isEmpty() || b.isEmpty()) return null;
        boolean x = a.minY() == b.minY() && a.maxY() == b.maxY() && a.minZ() == b.minZ() && a.maxZ() == b.maxZ()
                && (a.maxX() == b.minX() || b.maxX() == a.minX());
        boolean y = a.minX() == b.minX() && a.maxX() == b.maxX() && a.minZ() == b.minZ() && a.maxZ() == b.maxZ()
                && (a.maxY() == b.minY() || b.maxY() == a.minY());
        boolean z = a.minX() == b.minX() && a.maxX() == b.maxX() && a.minY() == b.minY() && a.maxY() == b.maxY()
                && (a.maxZ() == b.minZ() || b.maxZ() == a.minZ());
        if (!x && !y && !z) return null;
        IntBox union = unionBounds(a, b);
        if (mode == Mode.NONE || mode == Mode.RAYCAST_REFINED) return union;
        // AXIS_6 historically ignores source boxes outside the requested domain
        // before applying source-blocked behavior. With no domain parameter a
        // merge through the source cannot be proven equivalent for every domain.
        if (containsCenter(a, center) || containsCenter(b, center)) return null;
        int direction = mode == Mode.AXIS_6 ? axis6Direction(center, a) : ProjectedShadows.direction(center, a);
        int second = mode == Mode.AXIS_6 ? axis6Direction(center, b) : ProjectedShadows.direction(center, b);
        int combined = mode == Mode.AXIS_6 ? axis6Direction(center, union) : ProjectedShadows.direction(center, union);
        return direction == second && direction == combined ? union : null;
    }

    /**
     * Fixed-cross-section face/edge/corner extrusion. Closest angular direction
     * is chosen by exact squared-dot/length comparison; ties use ascending axis
     * bit masks (X=1,Y=2,Z=4). This is a documented visual approximation.
     */
    public static FrozenOcclusion axis26(Point3 center, IntBox explosionBounds,
                                         List<IntBox> blockers, int maxBlockers) {
        return new FrozenOcclusion(Mode.AXIS_26,
                new ProjectedShadows(center, explosionBounds, blockers, maxBlockers, false));
    }

    /**
     * Exact line-of-sight refinement against the supplied frozen half-open
     * blocker boxes. Segment endpoints are included; blocker maximum faces are
     * excluded. Uses rational comparisons and a sparse BVH, without stepping
     * through every empty voxel or allocating a global ray priority queue.
     */
    public static FrozenOcclusion raycastRefined(Point3 center, IntBox explosionBounds,
                                                 List<IntBox> blockers, int maxBlockers) {
        return new FrozenOcclusion(Mode.RAYCAST_REFINED,
                new ProjectedShadows(center, explosionBounds, blockers, maxBlockers, true));
    }

    public static FrozenOcclusion axis6(Point3 center, IntBox explosionBounds,
                                        List<IntBox> blockers, int maxBlockers) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(explosionBounds, "explosionBounds");
        Objects.requireNonNull(blockers, "blockers");
        if (maxBlockers < 1) throw new IllegalArgumentException("maxBlockers must be positive");
        if (blockers.size() > maxBlockers) throw new IllegalArgumentException("Obstacle snapshot exceeds its budget");
        List<IntBox> frozen = List.copyOf(blockers);
        if (frozen.size() > maxBlockers) throw new IllegalArgumentException("Obstacle snapshot exceeds its budget");
        if (explosionBounds.isEmpty()) return new FrozenOcclusion(Mode.AXIS_6, null, frozen.size(), 0);
        ArrayList<IntBox> shadows = new ArrayList<>(frozen.size());
        for (IntBox blocker : frozen) {
            if (blocker.isEmpty() || !blocker.intersects(explosionBounds)) continue;
            if (containsCenter(blocker, center)) {
                return new FrozenOcclusion(Mode.AXIS_6, new Node(explosionBounds, null, null), frozen.size(), 1);
            }
            int direction = axis6Direction(center, blocker);
            IntBox shadow;
            if (Math.abs(direction) == 1) {
                shadow = new IntBox(direction > 0 ? blocker.minX() : explosionBounds.minX(),
                        blocker.minY(), blocker.minZ(),
                        direction > 0 ? explosionBounds.maxX() : blocker.maxX(), blocker.maxY(), blocker.maxZ());
            } else if (Math.abs(direction) == 2) {
                shadow = new IntBox(blocker.minX(), direction > 0 ? blocker.minY() : explosionBounds.minY(),
                        blocker.minZ(), blocker.maxX(),
                        direction > 0 ? explosionBounds.maxY() : blocker.maxY(), blocker.maxZ());
            } else {
                shadow = new IntBox(blocker.minX(), blocker.minY(),
                        direction > 0 ? blocker.minZ() : explosionBounds.minZ(),
                        blocker.maxX(), blocker.maxY(), direction > 0 ? explosionBounds.maxZ() : blocker.maxZ());
            }
            IntBox clipped = shadow.intersection(explosionBounds);
            if (!clipped.isEmpty()) shadows.add(clipped);
        }
        return new FrozenOcclusion(Mode.AXIS_6, build(shadows, 0, shadows.size()), frozen.size(), shadows.size());
    }

    public Mode mode() { return mode; }
    public int sourceBlockerCount() { return sourceBlockers; }
    /** Indexed bounds count: shadow boxes/extrusions, or original boxes in refined mode. */
    public int shadowBoxCount() { return shadowBoxes; }
    public long estimatedBytes() { return advanced == null ? 64L + 512L * shadowBoxes : advanced.estimatedBytes(); }

    /** Conservative classification: multiple overlapping shadows may return PARTIAL. */
    public Coverage classify(IntBox box) {
        Objects.requireNonNull(box, "box");
        if (box.isEmpty()) return Coverage.OUTSIDE;
        if (advanced != null) return advanced.classify(box);
        return classify(root, box);
    }

    public VoxelMask shadowMask(SectionKey section) {
        Objects.requireNonNull(section, "section");
        if (advanced != null) return advanced.shadowMask(section);
        IntBox bounds = section.bounds();
        Coverage coverage = classify(bounds);
        if (coverage == Coverage.OUTSIDE) return VoxelMask.EMPTY;
        if (coverage == Coverage.INSIDE) return VoxelMask.ALL;
        VoxelMask.Builder result = VoxelMask.builder();
        emit(root, bounds, result);
        return result.build();
    }

    private static boolean containsCenter(IntBox box, Point3 point) {
        return 2L * box.minX() <= point.x2() && point.x2() < 2L * box.maxX()
                && 2L * box.minY() <= point.y2() && point.y2() < 2L * box.maxY()
                && 2L * box.minZ() <= point.z2() && point.z2() < 2L * box.maxZ();
    }

    private static int axis6Direction(Point3 center, IntBox box) {
        long dx = (long) box.minX() + box.maxX() - center.x2();
        long dy = (long) box.minY() + box.maxY() - center.y2();
        long dz = (long) box.minZ() + box.maxZ() - center.z2();
        long ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ax >= ay && ax >= az) return dx >= 0 ? 1 : -1;
        if (ay >= az) return dy >= 0 ? 2 : -2;
        return dz >= 0 ? 3 : -3;
    }

    private static Coverage classify(Node node, IntBox box) {
        if (node == null || !node.bounds.intersects(box)) return Coverage.OUTSIDE;
        if (node.left == null) return node.bounds.contains(box) ? Coverage.INSIDE : Coverage.PARTIAL;
        Coverage left = classify(node.left, box);
        if (left == Coverage.INSIDE) return left;
        Coverage right = classify(node.right, box);
        if (right == Coverage.INSIDE) return right;
        return left == Coverage.OUTSIDE && right == Coverage.OUTSIDE ? Coverage.OUTSIDE : Coverage.PARTIAL;
    }

    private static void emit(Node node, IntBox section, VoxelMask.Builder mask) {
        if (node == null || !node.bounds.intersects(section)) return;
        if (node.left != null) {
            emit(node.left, section, mask);
            emit(node.right, section, mask);
            return;
        }
        IntBox clipped = node.bounds.intersection(section);
        int minY = clipped.minY() - section.minY(), maxY = clipped.maxY() - section.minY();
        for (int x = clipped.minX(); x < clipped.maxX(); x++) {
            for (int z = clipped.minZ(); z < clipped.maxZ(); z++) {
                mask.setRange(x - section.minX(), z - section.minZ(), minY, maxY);
            }
        }
    }

    private static Node build(ArrayList<IntBox> boxes, int from, int to) {
        if (from == to) return null;
        IntBox bounds = boxes.get(from);
        for (int i = from + 1; i < to; i++) bounds = unionBounds(bounds, boxes.get(i));
        if (to - from == 1) return new Node(bounds, null, null);
        long dx = (long) bounds.maxX() - bounds.minX();
        long dy = (long) bounds.maxY() - bounds.minY();
        long dz = (long) bounds.maxZ() - bounds.minZ();
        int axis = dx >= dy && dx >= dz ? 0 : dy >= dz ? 1 : 2;
        Comparator<IntBox> order = Comparator.comparingLong(box -> centerSum(box, axis));
        boxes.subList(from, to).sort(order);
        int middle = (from + to) >>> 1;
        return new Node(bounds, build(boxes, from, middle), build(boxes, middle, to));
    }

    private static long centerSum(IntBox box, int axis) {
        return switch (axis) {
            case 0 -> (long) box.minX() + box.maxX();
            case 1 -> (long) box.minY() + box.maxY();
            default -> (long) box.minZ() + box.maxZ();
        };
    }

    private static IntBox unionBounds(IntBox a, IntBox b) {
        return new IntBox(Math.min(a.minX(), b.minX()), Math.min(a.minY(), b.minY()), Math.min(a.minZ(), b.minZ()),
                Math.max(a.maxX(), b.maxX()), Math.max(a.maxY(), b.maxY()), Math.max(a.maxZ(), b.maxZ()));
    }

    private record Node(IntBox bounds, Node left, Node right) { }
}
