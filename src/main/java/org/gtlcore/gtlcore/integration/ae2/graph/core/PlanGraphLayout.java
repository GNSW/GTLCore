package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.util.*;

/** Immutable display geometry. Lay out the condensation DAG, then expand each SCC into a ring. */
public final class PlanGraphLayout<K> {

    public record Point(double x, double y) {}

    public record Box(double x, double y, double width, double height) {

        public boolean intersects(Box other) {
            return x <= other.x + other.width && x + width >= other.x &&
                    y <= other.y + other.height && y + height >= other.y;
        }

        public Box union(Box other) {
            double left = Math.min(x, other.x), top = Math.min(y, other.y);
            return new Box(left, top, Math.max(x + width, other.x + other.width) - left,
                    Math.max(y + height, other.y + other.height) - top);
        }
    }

    public record Link(PlanTopology.Edge edge, List<Point> path, boolean cyclic, Box bounds) {}

    public record Ring(int group, Box bounds) {}

    private final List<Point> points;
    private final List<Link> links;
    private final List<Ring> rings;
    private final Box bounds;
    private final Index nodeIndex, linkIndex, ringIndex;

    public PlanGraphLayout(PlanTopology<K> topology) {
        int count = topology.groups().size();
        int[] indegree = new int[count], rank = new int[count];
        List<Set<Integer>> outgoing = new ArrayList<>();
        for (int i = 0; i < count; i++) outgoing.add(new LinkedHashSet<>());
        for (var edge : topology.edges()) {
            int from = topology.groupOf(edge.from()), to = topology.groupOf(edge.to());
            if (from != to && outgoing.get(from).add(to)) indegree[to]++;
        }
        Deque<Integer> ready = new ArrayDeque<>();
        for (int i = 0; i < count; i++) if (indegree[i] == 0) ready.add(i);
        int maxRank = 0;
        while (!ready.isEmpty()) {
            int from = ready.removeFirst();
            for (int to : outgoing.get(from)) {
                rank[to] = Math.max(rank[to], rank[from] + 1);
                maxRank = Math.max(maxRank, rank[to]);
                if (--indegree[to] == 0) ready.addLast(to);
            }
        }
        double[] widths = new double[maxRank + 1], heights = new double[maxRank + 1];
        double[] radii = new double[count];
        for (var group : topology.groups()) {
            double radius = group.cyclic() ? Math.max(46, group.nodes().size() * 34.0 / (2 * Math.PI)) : 0;
            radii[group.id()] = radius;
            widths[rank[group.id()]] = Math.max(widths[rank[group.id()]], radius * 2 + 56);
            heights[rank[group.id()]] += radius * 2 + 64;
        }
        double maxHeight = Arrays.stream(heights).max().orElse(0);
        double[] columnX = new double[widths.length], cursorY = new double[widths.length];
        for (int i = 0; i < widths.length; i++) {
            columnX[i] = i == 0 ? 0 : columnX[i - 1] + widths[i - 1] + 44;
            cursorY[i] = (maxHeight - heights[i]) / 2;
        }
        Point[] positions = new Point[topology.nodes().size()];
        List<Ring> loops = new ArrayList<>();
        for (var group : topology.groups()) {
            int column = rank[group.id()];
            double radius = radii[group.id()], cx = columnX[column] + widths[column] / 2;
            double cy = cursorY[column] + radius + 32;
            cursorY[column] += radius * 2 + 64;
            // Resource and recipe vertices remain distinct even for self-replication.
            for (int i = 0; i < group.nodes().size(); i++) {
                double angle = Math.PI + 2 * Math.PI * i / group.nodes().size();
                positions[group.nodes().get(i)] = new Point(cx + radius * Math.cos(angle), cy + radius * Math.sin(angle));
            }
            if (group.cyclic()) loops.add(new Ring(group.id(), new Box(cx - radius - 22, cy - radius - 22, radius * 2 + 44, radius * 2 + 44)));
        }
        points = List.of(positions);
        List<Link> paths = new ArrayList<>();
        for (var edge : topology.edges()) {
            Point from = points.get(edge.from()), to = points.get(edge.to());
            boolean cycle = topology.groupOf(edge.from()) == topology.groupOf(edge.to());
            List<Point> path = new ArrayList<>();
            if (cycle) {
                double dx = to.x - from.x, dy = to.y - from.y, length = Math.hypot(dx, dy);
                // Directed quadratic arcs separate A->recipe and recipe->A instead of
                // drawing two arrows on the same segment. The direction determines the side.
                double bend = Math.min(36, length * 0.45);
                double cx = (from.x + to.x) / 2 - dy / length * bend;
                double cy = (from.y + to.y) / 2 + dx / length * bend;
                for (int i = 0; i <= 16; i++) {
                    double t = i / 16.0, u = 1 - t;
                    path.add(new Point(u * u * from.x + 2 * u * t * cx + t * t * to.x,
                            u * u * from.y + 2 * u * t * cy + t * t * to.y));
                }
            } else {
                double middle = (from.x + to.x) / 2;
                path.add(from);
                path.add(new Point(middle, from.y));
                path.add(new Point(middle, to.y));
                path.add(to);
            }
            // Keep lines out of the item/recipe slot, preserving their arrow direction.
            while (path.size() > 2 && distance(path.get(1), from) < 14) path.remove(1);
            while (path.size() > 2 && distance(path.get(path.size() - 2), to) < 14) path.remove(path.size() - 2);
            path.set(0, inset(from, path.get(1), 13));
            path.set(path.size() - 1, inset(to, path.get(path.size() - 2), 13));
            Box box = new Box(from.x, from.y, 0, 0);
            for (Point p : path) box = box.union(new Box(p.x, p.y, 0, 0));
            paths.add(new Link(edge, List.copyOf(path), cycle, box));
        }
        links = List.copyOf(paths);
        rings = List.copyOf(loops);
        nodeIndex = new Index(points.stream().map(p -> new Box(p.x - 24, p.y - 16, 48, 40)).toList());
        linkIndex = new Index(links.stream().map(Link::bounds).toList());
        ringIndex = new Index(rings.stream().map(Ring::bounds).toList());
        bounds = nodeIndex.bounds();
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static Point inset(Point from, Point toward, double amount) {
        double length = distance(from, toward);
        if (length == 0) return from;
        double scale = Math.min(amount / length, 1);
        return new Point(from.x + (toward.x - from.x) * scale, from.y + (toward.y - from.y) * scale);
    }

    public List<Point> points() {
        return points;
    }

    public List<Link> links() {
        return links;
    }

    public List<Ring> rings() {
        return rings;
    }

    public Box bounds() {
        return bounds;
    }

    public List<Integer> visibleNodes(Box viewport) {
        return nodeIndex.query(viewport);
    }

    public List<Integer> visibleLinks(Box viewport) {
        return linkIndex.query(viewport);
    }

    public List<Integer> visibleRings(Box viewport) {
        return ringIndex.query(viewport);
    }

    /** Bounding-volume tree: rendering work follows the viewport, not total plan size. */
    private static final class Index {

        private record Branch(Box box, Branch left, Branch right, List<Integer> items) {}

        private final List<Box> boxes;
        private final Branch root;

        Index(List<Box> boxes) {
            this.boxes = boxes;
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < boxes.size(); i++) ids.add(i);
            root = ids.isEmpty() ? null : build(ids);
        }

        private Branch build(List<Integer> ids) {
            Box box = boxes.get(ids.get(0));
            for (int id : ids) box = box.union(boxes.get(id));
            if (ids.size() <= 8) return new Branch(box, null, null, List.copyOf(ids));
            boolean horizontal = box.width >= box.height;
            ids.sort(Comparator.comparingDouble(id -> horizontal ? boxes.get(id).x + boxes.get(id).width / 2 : boxes.get(id).y + boxes.get(id).height / 2));
            int half = ids.size() / 2;
            return new Branch(box, build(ids.subList(0, half)), build(ids.subList(half, ids.size())), List.of());
        }

        Box bounds() {
            return root == null ? new Box(0, 0, 1, 1) : root.box;
        }

        List<Integer> query(Box view) {
            List<Integer> result = new ArrayList<>();
            if (root == null) return result;
            Deque<Branch> pending = new ArrayDeque<>();
            pending.push(root);
            while (!pending.isEmpty()) {
                Branch branch = pending.pop();
                if (!branch.box.intersects(view)) continue;
                if (branch.left != null) {
                    pending.push(branch.left);
                    pending.push(branch.right);
                } else for (int id : branch.items) if (boxes.get(id).intersects(view)) result.add(id);
            }
            return result;
        }
    }
}
