package org.gtlcore.gtlcore.integration.ae2.graph;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Display-only grouping. Packet rows and the executable program keep their exact identities. */
public final class GraphRingProjection {

    private GraphRingProjection() {}

    public record View(List<GraphRingView.Row> rows, Map<String, String> aliases) {}

    private record Shape(Map<AEKey, BigInteger> inputs, Map<AEKey, BigInteger> outputs) {}

    public static View of(List<GraphRingView.Row> rows, AEKey target) {
        Map<AEKey, AEKey> representatives = new LinkedHashMap<>();
        representatives.put(target, target);
        // Preserve a real initial stack as the icon, rather than inventing a full-durability tool.
        for (var row : rows) if (row.kind() == GraphRingView.Kind.RESOURCE) key(row.icon().what(), target, representatives);
        Map<AEKey, GraphRingView.Row> resources = new LinkedHashMap<>();
        Map<Shape, GraphRingView.Row> recipes = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        for (var row : rows) {
            if (row.kind() == GraphRingView.Kind.RESOURCE) {
                AEKey resource = key(row.icon().what(), target, representatives);
                var old = resources.get(resource);
                resources.put(resource, new GraphRingView.Row(row.kind(), row.id(), new GenericStack(resource, 1),
                        row.count().add(old == null ? BigInteger.ZERO : old.count()),
                        org.cgse.core.ExactAmounts.capped(BigInteger.valueOf(row.seed()).add(BigInteger.valueOf(old == null ? 0 : old.seed()))),
                        row.missing().add(old == null ? BigInteger.ZERO : old.missing()), List.of(), List.of(), row.parent()));
            } else if (row.kind() == GraphRingView.Kind.RECIPE) {
                var inputs = amounts(row.inputs(), target, representatives);
                var outputs = amounts(row.outputs(), target, representatives);
                var shape = new Shape(inputs, outputs);
                var old = recipes.get(shape);
                String id = old == null ? row.id() : old.id();
                aliases.put(row.id(), id);
                recipes.put(shape, new GraphRingView.Row(row.kind(), id,
                        row.icon() == null ? null : new GenericStack(key(row.icon().what(), target, representatives), row.icon().amount()),
                        row.count().add(old == null ? BigInteger.ZERO : old.count()), 0, BigInteger.ZERO,
                        stacks(inputs), stacks(outputs), row.parent()));
            }
        }
        List<GraphRingView.Row> projected = new ArrayList<>(resources.values());
        projected.addAll(recipes.values());
        return new View(List.copyOf(projected), Map.copyOf(aliases));
    }

    private static AEKey key(AEKey key, AEKey target, Map<AEKey, AEKey> representatives) {
        if (key.equals(target)) return target;
        AEKey identity = GraphPlanSummary.toolIdentity(key);
        // A requested exact state has its own node, even when another state normalizes to it.
        if (identity.equals(target)) return representatives.computeIfAbsent(key, ignored -> key);
        return representatives.computeIfAbsent(identity, ignored -> key);
    }

    private static Map<AEKey, BigInteger> amounts(List<GenericStack> values, AEKey target, Map<AEKey, AEKey> representatives) {
        Map<AEKey, BigInteger> result = new LinkedHashMap<>();
        for (var value : values) result.merge(key(value.what(), target, representatives), BigInteger.valueOf(value.amount()), BigInteger::add);
        return result;
    }

    private static List<GenericStack> stacks(Map<AEKey, BigInteger> values) {
        return values.entrySet().stream().map(entry -> new GenericStack(entry.getKey(), org.cgse.core.ExactAmounts.capped(entry.getValue()))).toList();
    }
}
