package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** A closed wait is a resource-ownership proof, never a wall-clock threshold. */
public final class WaitAnalysis {

    private WaitAnalysis() {}

    public enum Result {
        CLOSED_WAIT,
        OPEN_OR_UNKNOWN
    }

    public record Owner<K>(String id, Map<K, Long> held, Map<K, Long> waits,
                           boolean completeVisibility, boolean inFlightOrExternalEvent) {

        public Owner {
            held = GraphRecipe.amounts(held);
            waits = GraphRecipe.amounts(waits);
        }
    }

    public record Diagnosis<K>(Result result, Set<String> owners, Map<K, Set<String>> resourceOwners) {}

    public static <K> Diagnosis<K> inspect(List<Owner<K>> owners, Map<K, Long> freelyAvailable, PlanningBudget budget) {
        Map<String, Owner<K>> byId = new LinkedHashMap<>();
        Map<K, List<Owner<K>>> holders = new HashMap<>();
        for (Owner<K> owner : owners) {
            budget.check();
            if (byId.putIfAbsent(owner.id(), owner) != null) throw new IllegalArgumentException("Duplicate wait owner");
            for (K key : owner.held().keySet()) holders.computeIfAbsent(key, ignored -> new ArrayList<>()).add(owner);
        }
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (Owner<K> owner : owners) {
            budget.check();
            if (!owner.completeVisibility() || owner.inFlightOrExternalEvent() || owner.waits().isEmpty()) continue;
            Set<String> waitsFor = new LinkedHashSet<>();
            boolean closed = true;
            for (var wait : owner.waits().entrySet()) {
                budget.check();
                if (freelyAvailable.getOrDefault(wait.getKey(), 0L) > 0) {
                    closed = false;
                    break;
                }
                BigInteger heldElsewhere = BigInteger.ZERO;
                for (Owner<K> holder : holders.getOrDefault(wait.getKey(), List.of())) {
                    if (holder.id().equals(owner.id())) continue;
                    waitsFor.add(holder.id());
                    heldElsewhere = heldElsewhere.add(BigInteger.valueOf(holder.held().get(wait.getKey())));
                }
                if (heldElsewhere.compareTo(BigInteger.valueOf(wait.getValue())) < 0) {
                    closed = false;
                    break;
                }
            }
            if (closed && !waitsFor.isEmpty()) edges.put(owner.id(), waitsFor);
        }
        // Remove every owner with a possible outgoing release. What remains is
        // nonempty, closed, and every member needs another blocked member's stock.
        Map<String, Set<String>> dependents = new HashMap<>();
        Deque<String> open = new ArrayDeque<>();
        edges.forEach((id, dependencies) -> {
            for (String other : dependencies) {
                dependents.computeIfAbsent(other, ignored -> new LinkedHashSet<>()).add(id);
                if (!edges.containsKey(other)) open.add(id);
            }
        });
        while (!open.isEmpty()) {
            budget.check();
            String id = open.removeFirst();
            if (edges.remove(id) != null) open.addAll(dependents.getOrDefault(id, Set.of()));
        }
        if (edges.isEmpty()) return new Diagnosis<>(Result.OPEN_OR_UNKNOWN, Set.of(), Map.of());
        Map<K, Set<String>> resources = new LinkedHashMap<>();
        for (String id : edges.keySet()) for (K key : byId.get(id).held().keySet())
            resources.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(id);
        resources.replaceAll((key, values) -> Set.copyOf(values));
        return new Diagnosis<>(Result.CLOSED_WAIT, Set.copyOf(edges.keySet()), Map.copyOf(resources));
    }
}
