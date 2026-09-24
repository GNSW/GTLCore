package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.util.*;

/** Read-only topology of selected recipes. Shared resources have one stable node. */
public final class PlanTopology<K> {

    public record Node<K>(int id, K resource, String recipe) {}

    public record Edge(int from, int to, long perRun) {}

    public record Group(int id, List<Integer> nodes, boolean cyclic) {}

    private final List<Node<K>> nodes;
    private final List<Edge> edges;
    private final List<Group> groups;
    private final int[] groupOf;

    public PlanTopology(List<GraphRecipe<K>> selected) {
        List<Node<K>> vertices = new ArrayList<>();
        List<Edge> links = new ArrayList<>();
        Map<K, Integer> resources = new LinkedHashMap<>();
        Set<String> identities = new HashSet<>();
        for (var recipe : selected) {
            if (!identities.add(recipe.id())) throw new IllegalArgumentException("Duplicate selected recipe");
            int recipeNode = vertices.size();
            vertices.add(new Node<>(recipeNode, null, recipe.id()));
            recipe.inputs().forEach((key, count) -> links.add(new Edge(resource(vertices, resources, key), recipeNode, count)));
            recipe.outputs().forEach((key, count) -> links.add(new Edge(recipeNode, resource(vertices, resources, key), count)));
        }
        nodes = List.copyOf(vertices);
        edges = List.copyOf(links);
        int size = nodes.size();
        int[] degree = new int[size], reverseDegree = new int[size];
        for (var edge : edges) {
            degree[edge.from()]++;
            reverseDegree[edge.to()]++;
        }
        int[][] forward = new int[size][], reverse = new int[size][];
        for (int i = 0; i < size; i++) {
            forward[i] = new int[degree[i]];
            reverse[i] = new int[reverseDegree[i]];
        }
        Arrays.fill(degree, 0);
        Arrays.fill(reverseDegree, 0);
        for (var edge : edges) {
            forward[edge.from()][degree[edge.from()]++] = edge.to();
            reverse[edge.to()][reverseDegree[edge.to()]++] = edge.from();
        }
        boolean[] seen = new boolean[size];
        int[] stack = new int[size], cursors = new int[size], order = new int[size];
        int finished = 0;
        for (int root = 0; root < size; root++) {
            if (seen[root]) continue;
            int top = 0;
            stack[0] = root;
            cursors[0] = 0;
            seen[root] = true;
            while (top >= 0) {
                int node = stack[top];
                if (cursors[top] < forward[node].length) {
                    int next = forward[node][cursors[top]++];
                    if (!seen[next]) {
                        seen[next] = true;
                        stack[++top] = next;
                        cursors[top] = 0;
                    }
                } else {
                    order[finished++] = node;
                    top--;
                }
            }
        }
        groupOf = new int[size];
        Arrays.fill(groupOf, -1);
        List<Group> components = new ArrayList<>();
        for (int i = finished - 1; i >= 0; i--) {
            int root = order[i];
            if (groupOf[root] >= 0) continue;
            int id = components.size(), top = 0;
            List<Integer> members = new ArrayList<>();
            stack[0] = root;
            groupOf[root] = id;
            while (top >= 0) {
                int node = stack[top--];
                members.add(node);
                for (int next : reverse[node]) if (groupOf[next] < 0) {
                    groupOf[next] = id;
                    stack[++top] = next;
                }
            }
            components.add(new Group(id, List.copyOf(members), members.size() > 1));
        }
        groups = List.copyOf(components);
    }

    private static <K> int resource(List<Node<K>> nodes, Map<K, Integer> ids, K key) {
        Integer old = ids.get(key);
        if (old != null) return old;
        int id = nodes.size();
        ids.put(key, id);
        nodes.add(new Node<>(id, key, null));
        return id;
    }

    public List<Node<K>> nodes() {
        return nodes;
    }

    public List<Edge> edges() {
        return edges;
    }

    public List<Group> groups() {
        return groups;
    }

    public int groupOf(int node) {
        return groupOf[node];
    }
}
