/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.lighting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.voxelregion.lighting.LightBatchPlanner.Rect;

/**
 * Shares fresh full-field solves without extending their required terrain or
 * publication domains. This bounded local heuristic does not claim an optimal
 * partition. Cached/incremental fields are intentionally outside its contract.
 */
public final class FreshLightBatchPlanner {
    private static final int MAX_OUTPUT_SIDE = GeneralChunkLighting.MAX_HORIZONTAL_SIZE / 16 - 2;
    private static final Comparator<Rect> ORDER = Comparator.comparingInt(Rect::z)
            .thenComparingInt(Rect::x).thenComparingInt(Rect::depth).thenComparingInt(Rect::width);

    /**
     * {@code field} is the numerical output bounding box; its sample includes a
     * one-column halo. Only {@code outputs} are to be published, never holes in
     * that bounding box. The original rectangles are retained without splitting.
     */
    public record Group(Rect field, List<Rect> outputs) {
        public Group {
            Objects.requireNonNull(field, "field");
            outputs = List.copyOf(outputs);
        }
    }

    private static final class Node {
        final int id;
        final Group group;
        Node(int id, Group group) { this.id = id; this.group = group; }
    }

    private record Merge(Rect field, List<Node> members, long savedArea) { }

    private FreshLightBatchPlanner() { }

    /**
     * Returns immutable, deterministically ordered groups. Inputs must be
     * disjoint, unique rectangles fitting the given positive side limits;
     * overlaps and duplicates are rejected. Limits cannot exceed the optical
     * kernel's horizontal limit minus its halo (currently 14 columns).
     *
     * <p>A merge fits the kernel's cell limit and {@code maxWorkingBytes}, samples
     * strictly fewer columns than its members, and requires no terrain outside
     * their existing sampling halos. Unmerged inputs are preserved even if their
     * individual working estimates exceed this budget: admission of an original
     * job, simultaneous jobs and host-side capture memory remain the caller's
     * responsibility. Height must be a supported positive multiple of 16.
     *
     * <p>The spatial index covers original output columns. Candidate searches and
     * halo proofs are bounded by the side limits, not total input size. Only
     * neighborhoods of successful merges are reconsidered; no all-pairs scan or
     * repeated whole-input convergence pass is used.
     */
    public static List<Group> plan(Collection<Rect> outputs, int maxWidth, int maxDepth,
                                   int height, long maxWorkingBytes) {
        Objects.requireNonNull(outputs, "outputs");
        if (maxWidth <= 0 || maxDepth <= 0 || maxWidth > MAX_OUTPUT_SIDE || maxDepth > MAX_OUTPUT_SIDE)
            throw new IllegalArgumentException("Output side limits exceed the optical kernel");
        if (height < 16 || height > ClearedChunkLighting.MAX_HEIGHT || height % 16 != 0)
            throw new IllegalArgumentException("Unsupported optical height");
        if (maxWorkingBytes <= 0) throw new IllegalArgumentException("Positive working budget required");
        List<Rect> ordered = new ArrayList<>(outputs);
        for (Rect rectangle : ordered) {
            Objects.requireNonNull(rectangle, "output rectangle");
            if (rectangle.width() > maxWidth || rectangle.depth() > maxDepth)
                throw new IllegalArgumentException("Input rectangle exceeds output side limits");
        }
        ordered.sort(ORDER);
        Map<Long, Node> owners = new HashMap<>();
        Node[] active = new Node[ordered.size()];
        TreeSet<Integer> pending = new TreeSet<>();
        for (int id = 0; id < ordered.size(); id++) {
            Rect rectangle = ordered.get(id);
            Node node = new Node(id, new Group(rectangle, List.of(rectangle)));
            active[id] = node;
            for (int z = 0; z < rectangle.depth(); z++) for (int x = 0; x < rectangle.width(); x++) {
                long key = key(rectangle.x() + x, rectangle.z() + z);
                if (owners.putIfAbsent(key, node) != null)
                    throw new IllegalArgumentException("Overlapping or duplicate output rectangles");
            }
            pending.add(id);
        }
        while (!pending.isEmpty()) {
            Node anchor = active[pending.pollFirst()];
            if (anchor == null) continue;
            List<Node> neighbors = neighbors(anchor.group.field, owners, maxWidth, maxDepth);
            Merge best = bestMerge(anchor, neighbors, maxWidth, maxDepth, height, maxWorkingBytes);
            if (best == null) continue;
            int id = Integer.MAX_VALUE;
            List<Rect> combined = new ArrayList<>();
            for (Node member : best.members) {
                id = Math.min(id, member.id);
                combined.addAll(member.group.outputs);
                active[member.id] = null;
                pending.remove(member.id);
            }
            combined.sort(ORDER);
            Node replacement = new Node(id, new Group(best.field, combined));
            active[id] = replacement;
            for (Rect rectangle : combined)
                for (int z = 0; z < rectangle.depth(); z++) for (int x = 0; x < rectangle.width(); x++)
                    owners.put(key(rectangle.x() + x, rectangle.z() + z), replacement);
            for (Node neighbor : neighbors(best.field, owners, maxWidth, maxDepth)) pending.add(neighbor.id);
        }
        List<Group> result = new ArrayList<>();
        for (Node node : active) if (node != null) result.add(node.group);
        result.sort(Comparator.comparing(Group::field, ORDER)
                .thenComparing(group -> group.outputs.get(0), ORDER));
        return List.copyOf(result);
    }

    private static Merge bestMerge(Node anchor, List<Node> neighbors, int maxWidth, int maxDepth,
                                   int height, long budget) {
        Merge best = null;
        Set<Rect> considered = new HashSet<>();
        for (Node other : neighbors) {
            if (other == anchor) continue;
            Rect field = bounds(anchor.group.field, other.group.field, maxWidth, maxDepth);
            if (field == null || !considered.add(field)) continue;
            long area = sampleArea(field);
            if (area * 256L * height > GeneralChunkLighting.MAX_CELLS
                    || field.estimatedWorkingBytes(height) > budget) continue;
            List<Node> members = new ArrayList<>();
            long originalArea = 0;
            // Gather a whole local group, rather than requiring each intermediate
            // pair to cover the bounding halo (a ring can require all its sides).
            for (Node member : neighbors) if (contains(field, member.group.field)) {
                members.add(member);
                originalArea += sampleArea(member.group.field);
            }
            long saving = originalArea - area;
            if (members.size() < 2 || saving <= 0 || !covered(field, members)) continue;
            if (best == null || saving > best.savedArea
                    || saving == best.savedArea && ORDER.compare(field, best.field) < 0)
                best = new Merge(field, members, saving);
        }
        return best;
    }

    private static List<Node> neighbors(Rect field, Map<Long, Node> owners, int maxWidth, int maxDepth) {
        TreeMap<Integer, Node> found = new TreeMap<>();
        long minX = Math.max(Integer.MIN_VALUE, (long)field.x() - maxWidth + 1);
        long minZ = Math.max(Integer.MIN_VALUE, (long)field.z() - maxDepth + 1);
        long maxX = Math.min(Integer.MAX_VALUE, (long)field.x() + field.width() + maxWidth - 2);
        long maxZ = Math.min(Integer.MAX_VALUE, (long)field.z() + field.depth() + maxDepth - 2);
        for (long z = minZ; z <= maxZ; z++) for (long x = minX; x <= maxX; x++) {
            Node node = owners.get(key((int)x, (int)z));
            if (node != null) found.put(node.id, node);
        }
        return List.copyOf(found.values());
    }

    private static Rect bounds(Rect first, Rect second, int maxWidth, int maxDepth) {
        int x = Math.min(first.x(), second.x()), z = Math.min(first.z(), second.z());
        long width = Math.max((long)first.x() + first.width(), (long)second.x() + second.width()) - x;
        long depth = Math.max((long)first.z() + first.depth(), (long)second.z() + second.depth()) - z;
        return width <= maxWidth && depth <= maxDepth ? new Rect(x, z, (int)width, (int)depth) : null;
    }

    private static boolean contains(Rect outer, Rect inner) {
        return outer.x() <= inner.x() && outer.z() <= inner.z()
                && (long)outer.x() + outer.width() >= (long)inner.x() + inner.width()
                && (long)outer.z() + outer.depth() >= (long)inner.z() + inner.depth();
    }

    private static boolean covered(Rect field, List<Node> members) {
        int stride = field.sampleWidth();
        boolean[] covered = new boolean[stride * field.sampleDepth()];
        for (Node member : members) {
            Rect sample = member.group.field;
            // Each member is inside field, so its complete halo is also inside.
            int x = sample.x() - field.x(), z = sample.z() - field.z();
            for (int row = 0; row < sample.sampleDepth(); row++) {
                int start = (z + row) * stride + x;
                Arrays.fill(covered, start, start + sample.sampleWidth(), true);
            }
        }
        for (boolean value : covered) if (!value) return false;
        return true;
    }

    private static long sampleArea(Rect rectangle) {
        return (long)rectangle.sampleWidth() * rectangle.sampleDepth();
    }

    private static long key(int x, int z) { return (long)x << 32 | z & 0xffffffffL; }
}
