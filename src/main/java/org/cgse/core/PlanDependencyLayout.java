// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.function.Predicate;

/** A bounded, navigable projection, not a second material or recipe plan. */
public final class PlanDependencyLayout<K> {

    public static final int MAX_NODES = 384;
    public static final int MAX_DEPTH = 8;
    public static final int CHILDREN_PER_PAGE = 16;

    public enum Kind {
        NORMAL,
        SHARED,
        CYCLE,
        COLLAPSED,
        MORE
    }

    public record Entry(int node, int parent, int edge, int depth, Kind kind, int offset,
                        PlanGraphLayout.Point point) {}

    public static final class View {

        private final List<Entry> entries;
        private final PlanGraphLayout.Box bounds;
        private final PlanGraphLayout.Index nodes, links;

        View(List<Entry> entries, PlanGraphLayout.Box bounds) {
            this.entries = List.copyOf(entries);
            this.bounds = bounds;
            nodes = new PlanGraphLayout.Index(entries.stream().map(e -> new PlanGraphLayout.Box(e.point().x() - 18, e.point().y() - 18, 36, 40)).toList());
            links = new PlanGraphLayout.Index(entries.stream().map(e -> {
                var p = e.point();
                var parent = e.parent() < 0 ? p : entries.get(e.parent()).point();
                return new PlanGraphLayout.Box(Math.min(p.x(), parent.x()) - 2, Math.min(p.y(), parent.y()) - 2,
                        Math.abs(p.x() - parent.x()) + 4, Math.abs(p.y() - parent.y()) + 4);
            }).toList());
        }

        public List<Entry> entries() {
            return entries;
        }

        public PlanGraphLayout.Box bounds() {
            return bounds;
        }

        public List<Integer> visibleEntries(PlanGraphLayout.Box viewport) {
            return nodes.query(viewport);
        }

        public List<Integer> visibleEntries(PlanGraphLayout.Box viewport, int limit) {
            return nodes.query(viewport, limit, id -> true);
        }

        public List<Integer> visibleConnections(PlanGraphLayout.Box viewport) {
            return links.query(viewport);
        }
    }

    private final PlanTopology<K> topology;
    private final int[][] incoming;

    public PlanDependencyLayout(PlanTopology<K> topology) {
        this.topology = topology;
        int[] sizes = new int[topology.nodes().size()];
        for (var edge : topology.edges()) sizes[edge.to()]++;
        incoming = new int[sizes.length][];
        for (int i = 0; i < sizes.length; i++) incoming[i] = new int[sizes[i]];
        Arrays.fill(sizes, 0);
        for (int i = 0; i < topology.edges().size(); i++) {
            int to = topology.edges().get(i).to();
            incoming[to][sizes[to]++] = i;
        }
    }

    public boolean hasDependencies(int node) {
        return node >= 0 && node < incoming.length && incoming[node].length > 0;
    }

    /**
     * Every reachable selected dependency, with shared/cyclic nodes expanded once.
     * Size depends on graph edges, never order quantity or the number of graph paths.
     */
    public View complete(int root, Predicate<K> missing, boolean compact) {
        if (root < 0 || root >= incoming.length) return new View(List.of(), new PlanGraphLayout.Box(0, 0, 1, 1));
        boolean[] keep = new boolean[incoming.length];
        if (missing == null) Arrays.fill(keep, true);
        else {
            List<List<Integer>> outgoing = new ArrayList<>();
            for (int i = 0; i < keep.length; i++) outgoing.add(new ArrayList<>());
            for (var edge : topology.edges()) outgoing.get(edge.from()).add(edge.to());
            Deque<Integer> pending = new ArrayDeque<>();
            for (var node : topology.nodes()) if (node.resource() != null && missing.test(node.resource())) {
                keep[node.id()] = true;
                pending.addLast(node.id());
            }
            while (!pending.isEmpty()) for (int next : outgoing.get(pending.removeFirst())) if (!keep[next]) {
                keep[next] = true;
                pending.addLast(next);
            }
        }
        if (!keep[root]) return new View(List.of(), new PlanGraphLayout.Box(0, 0, 1, 1));
        List<Entry> entries = new ArrayList<>();
        List<List<Integer>> children = new ArrayList<>();
        boolean[] seen = new boolean[incoming.length];
        entries.add(new Entry(root, -1, -1, 0, Kind.NORMAL, 0, null));
        children.add(new ArrayList<>());
        seen[root] = true;
        for (int cursor = 0; cursor < entries.size(); cursor++) {
            Entry parent = entries.get(cursor);
            if (parent.kind() != Kind.NORMAL) continue;
            for (int edgeId : incoming[parent.node()]) {
                int node = topology.edges().get(edgeId).from();
                if (!keep[node]) continue;
                Kind kind = !seen[node] ? Kind.NORMAL : topology.groupOf(node) == topology.groupOf(parent.node()) ? Kind.CYCLE : Kind.SHARED;
                seen[node] = true;
                children.get(cursor).add(entries.size());
                entries.add(new Entry(node, cursor, edgeId, parent.depth() + 1, kind, 0, null));
                children.add(new ArrayList<>());
            }
        }
        return arrange(entries, children, compact);
    }

    public View focus(int root, int offset) {
        if (root < 0 || root >= incoming.length) return new View(List.of(), new PlanGraphLayout.Box(0, 0, 1, 1));
        List<Entry> entries = new ArrayList<>();
        List<List<Integer>> children = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        entries.add(new Entry(root, -1, -1, 0, Kind.NORMAL, 0, null));
        children.add(new ArrayList<>());
        seen.add(root);
        for (int cursor = 0; cursor < entries.size(); cursor++) {
            Entry parent = entries.get(cursor);
            if (parent.kind() != Kind.NORMAL) continue;
            int[] edges = incoming[parent.node()];
            if (edges.length == 0) continue;
            int first = cursor == 0 ? Math.max(0, Math.min(offset, edges.length - 1)) : 0;
            if (parent.depth() >= MAX_DEPTH || entries.size() + Math.min(CHILDREN_PER_PAGE, edges.length - first) + 1 > MAX_NODES) {
                entries.set(cursor, new Entry(parent.node(), parent.parent(), parent.edge(), parent.depth(), Kind.COLLAPSED, first, null));
                continue;
            }
            int end = Math.min(edges.length, first + CHILDREN_PER_PAGE);
            for (int i = first; i < end; i++) {
                int edge = edges[i], node = topology.edges().get(edge).from();
                Kind kind = Kind.NORMAL;
                if (!seen.add(node)) {
                    kind = Kind.SHARED;
                    for (int ancestor = cursor; ancestor >= 0; ancestor = entries.get(ancestor).parent()) {
                        if (entries.get(ancestor).node() == node) {
                            kind = Kind.CYCLE;
                            break;
                        }
                    }
                }
                children.get(cursor).add(entries.size());
                entries.add(new Entry(node, cursor, edge, parent.depth() + 1, kind, 0, null));
                children.add(new ArrayList<>());
            }
            if (end < edges.length) {
                children.get(cursor).add(entries.size());
                entries.add(new Entry(parent.node(), cursor, -1, parent.depth() + 1, Kind.MORE, end, null));
                children.add(new ArrayList<>());
            }
        }
        return arrange(entries, children, true);
    }

    private static View arrange(List<Entry> entries, List<List<Integer>> children, boolean compact) {
        // Postorder widths, then preorder placement: no recursive Java stack, no crossed branches.
        double[] widths = new double[entries.size()], left = new double[entries.size()];
        for (int i = entries.size() - 1; i >= 0; i--) {
            widths[i] = compact ? 36 : 56;
            if (!children.get(i).isEmpty()) {
                widths[i] = 0;
                for (int child : children.get(i)) widths[i] += widths[child];
            }
        }
        int depth = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            depth = Math.max(depth, entry.depth());
            entries.set(i, new Entry(entry.node(), entry.parent(), entry.edge(), entry.depth(), entry.kind(), entry.offset(),
                    new PlanGraphLayout.Point(left[i] + widths[i] / 2, entry.depth() * (compact ? 40 : 56))));
            double x = left[i];
            for (int child : children.get(i)) {
                left[child] = x;
                x += widths[child];
            }
        }
        return new View(List.copyOf(entries), new PlanGraphLayout.Box(0, -16, widths[0], depth * (compact ? 40 : 56) + 44));
    }
}
