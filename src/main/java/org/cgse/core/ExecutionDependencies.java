package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Material dependencies of the selected witness, without expanding repeated operations. */
final class ExecutionDependencies<K> {

    private final Map<String, Integer> laneOf = new LinkedHashMap<>();
    private final List<List<String>> lanes = new ArrayList<>();

    ExecutionDependencies(GraphPlan<K> plan) {
        List<String> ids = List.copyOf(plan.patternTimesExact().keySet());
        List<PlanStep> order = postorder(plan.steps());
        Map<PlanStep, BigInteger> lengths = new IdentityHashMap<>();
        for (PlanStep step : order) {
            BigInteger length;
            if (step instanceof PlanStep.Batch batch) length = batch.runs() == 0 ? BigInteger.ZERO : BigInteger.ONE;
            else if (step instanceof PlanStep.Repeat repeat) length = lengths.get(repeat.body()).multiply(BigInteger.valueOf(repeat.times()));
            else {
                length = BigInteger.ZERO;
                for (PlanStep child : ((PlanStep.Sequence) step).children()) length = length.add(lengths.get(child));
            }
            lengths.put(step, length);
        }
        Map<PlanStep, Span> positions = new IdentityHashMap<>();
        Map<String, Span> occurrences = new HashMap<>();
        positions.put(plan.steps(), new Span(BigInteger.ZERO, BigInteger.ZERO));
        for (int i = order.size() - 1; i >= 0; i--) {
            PlanStep step = order.get(i);
            Span span = positions.get(step);
            if (span == null || lengths.get(step).signum() == 0) continue;
            if (step instanceof PlanStep.Batch batch) occurrences.merge(batch.recipe(), span, Span::union);
            else if (step instanceof PlanStep.Repeat repeat) {
                BigInteger offset = lengths.get(repeat.body()).multiply(BigInteger.valueOf(repeat.times() - 1));
                positions.merge(repeat.body(), new Span(span.first, span.last.add(offset)), Span::union);
            } else {
                BigInteger offset = BigInteger.ZERO;
                for (PlanStep child : ((PlanStep.Sequence) step).children()) {
                    positions.merge(child, new Span(span.first.add(offset), span.last.add(offset)), Span::union);
                    offset = offset.add(lengths.get(child));
                }
            }
        }

        Set<K> conserved = DagScheduler.conserved(ids.stream().map(plan.recipes()::get).toList());
        Map<K, Uses> resources = new LinkedHashMap<>();
        int[] parent = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            parent[i] = i;
            GraphRecipe<K> recipe = plan.recipes().get(ids.get(i));
            Span span = occurrences.get(recipe.id());
            for (K key : recipe.inputs().keySet()) {
                Uses uses = resources.computeIfAbsent(key, ignored -> new Uses());
                uses.firstInput = uses.firstInput == null ? span.first : uses.firstInput.min(span.first);
                uses.recipes.add(i);
            }
            for (K key : recipe.outputs().keySet()) {
                Uses uses = resources.computeIfAbsent(key, ignored -> new Uses());
                uses.lastOutput = uses.lastOutput == null ? span.last : uses.lastOutput.max(span.last);
                uses.recipes.add(i);
            }
        }
        resources.forEach((key, uses) -> {
            if (conserved.contains(key) || uses.firstInput == null || uses.lastOutput == null ||
                    uses.lastOutput.compareTo(uses.firstInput) < 0)
                return;
            // A regenerated resource can be a restart seed, or require reserved
            // output headroom. Keep ALL its suppliers and consumers together,
            // including a downstream consumer of a seed outside the SCC.
            int first = root(parent, uses.recipes.get(0));
            for (int index : uses.recipes) parent[root(parent, index)] = first;
        });
        Map<Integer, Integer> groups = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            int lane = groups.computeIfAbsent(root(parent, i), ignored -> {
                lanes.add(new ArrayList<>());
                return lanes.size() - 1;
            });
            lanes.get(lane).add(ids.get(i));
            laneOf.put(ids.get(i), lane);
        }
    }

    /**
     * Cross-lane keys have all suppliers before all consumers in the witness.
     * Actual stock therefore enables those dependencies without a whole-loop barrier.
     */
    List<PlanStep> partition(PlanStep remaining) {
        Map<String, BigInteger> counts = PlanCountComputation.of(remaining);
        for (String id : counts.keySet()) if (!laneOf.containsKey(id)) throw new IllegalArgumentException("Unknown pending recipe");
        List<PlanStep> order = postorder(remaining);
        Map<PlanStep, Integer> owners = new IdentityHashMap<>();
        for (PlanStep step : order) {
            int owner = -1;
            if (step instanceof PlanStep.Batch batch) owner = batch.runs() == 0 ? -1 : laneOf.getOrDefault(batch.recipe(), -1);
            else if (step instanceof PlanStep.Repeat repeat) owner = repeat.times() == 0 ? -1 : owners.get(repeat.body());
            else for (PlanStep child : ((PlanStep.Sequence) step).children()) {
                int next = owners.get(child);
                if (next == -1) continue;
                owner = owner == -1 ? next : owner == next ? owner : -2;
            }
            owners.put(step, owner);
        }
        List<PlanStep> result = new ArrayList<>();
        for (int lane = 0; lane < lanes.size(); lane++) {
            List<String> members = lanes.get(lane);
            if (members.size() == 1) {
                String id = members.get(0);
                BigInteger count = counts.getOrDefault(id, BigInteger.ZERO);
                if (count.signum() > 0) result.add(PlanStep.batch(id, count));
                continue;
            }
            Map<PlanStep, PlanStep> projected = new IdentityHashMap<>();
            for (PlanStep step : order) {
                int owner = owners.get(step);
                if (owner == lane) projected.put(step, step);
                else if (owner == -2) {
                    if (step instanceof PlanStep.Repeat repeat) {
                        PlanStep body = projected.get(repeat.body());
                        if (body != null) projected.put(step, new PlanStep.Repeat(body, repeat.times()));
                    } else {
                        List<PlanStep> children = new ArrayList<>();
                        for (PlanStep child : ((PlanStep.Sequence) step).children()) {
                            PlanStep part = projected.get(child);
                            if (part != null) children.add(part);
                        }
                        if (!children.isEmpty()) projected.put(step, children.size() == 1 ? children.get(0) : new PlanStep.Sequence(children));
                    }
                }
            }
            PlanStep branch = projected.get(remaining);
            if (branch != null) result.add(branch);
        }
        return result;
    }

    private static int root(int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    private static List<PlanStep> postorder(PlanStep root) {
        List<PlanStep> order = new ArrayList<>();
        Set<PlanStep> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Visit> stack = new ArrayList<>();
        seen.add(root);
        stack.add(new Visit(root));
        while (!stack.isEmpty()) {
            Visit visit = stack.get(stack.size() - 1);
            PlanStep child = null;
            if (visit.step instanceof PlanStep.Sequence sequence && visit.next < sequence.children().size()) child = sequence.children().get(visit.next++);
            else if (visit.step instanceof PlanStep.Repeat repeat && visit.next++ == 0) child = repeat.body();
            if (child == null) {
                order.add(visit.step);
                stack.remove(stack.size() - 1);
            } else if (seen.add(child)) stack.add(new Visit(child));
        }
        return order;
    }

    private record Span(BigInteger first, BigInteger last) {

        Span union(Span other) {
            return new Span(first.min(other.first), last.max(other.last));
        }
    }

    private static final class Uses {

        BigInteger firstInput, lastOutput;
        final List<Integer> recipes = new ArrayList<>();
    }

    private static final class Visit {

        final PlanStep step;
        int next;

        Visit(PlanStep step) {
            this.step = step;
        }
    }
}
