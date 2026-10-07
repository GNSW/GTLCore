package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Bounded equality saturation over execution words. A batch is an indivisible atom. */
final class PlanEGraph implements AutoCloseable {

    private record Node(String recipe, long times, List<Integer> children) {

        boolean batch() {
            return recipe != null;
        }

        boolean repeat() {
            return recipe == null && times >= 0;
        }
    }

    private record Visit(PlanStep step, boolean ready) {}

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private final PlanningBudget budget;
    private final long allowance;
    private final List<Integer> parents = new ArrayList<>();
    private final List<List<Node>> classes = new ArrayList<>();
    private final Map<Node, Integer> intern = new HashMap<>();
    private long work, memory;
    private int unions;

    private PlanEGraph(PlanningBudget budget) {
        this.budget = budget;
        allowance = Math.min(8192, budget.remainingWork() / 64);
    }

    static PlanStep optimize(PlanStep original, PlanningBudget budget) {
        if (original instanceof PlanStep.Batch || budget.remainingWork() < 16384) return original;
        if (irreducibleFlat(original, budget)) return original;
        try (var graph = new PlanEGraph(budget)) {
            try {
                int root = graph.importProgram(original);
                for (int pass = 0; pass < 4; pass++) {
                    int before = graph.unions, size = graph.classes.size();
                    for (int id = 0; id < size; id++) if (graph.find(id) == id)
                        for (var node : List.copyOf(graph.classes.get(id))) graph.rewrite(id, node);
                    graph.rebuild();
                    if (before == graph.unions) break;
                }
                PlanStep result = graph.extract(root);
                budget.note("plan_egraph", "classes=" + graph.classes.size() + "; equalities=" + graph.unions + "; work=" + graph.work);
                return result;
            } catch (Stop limit) {
                return original;
            }
        }
    }

    /** Unique atomic words have no identity, association or repetition to rewrite. */
    private static boolean irreducibleFlat(PlanStep program, PlanningBudget budget) {
        PlanStep body = program;
        if (program instanceof PlanStep.Repeat repeat) {
            if (repeat.times() <= 1) return false;
            body = repeat.body();
            // A repeated batch stays atomic; saturation must not merge dispatches.
            if (body instanceof PlanStep.Batch) return true;
        }
        if (!(body instanceof PlanStep.Sequence sequence) || sequence.children().size() < 2 || sequence.children().size() > 4096) return false;
        long bytes = 64L + 64L * sequence.children().size();
        if (!budget.tryReserve(bytes)) return false;
        try {
            var seen = new HashSet<PlanStep.Batch>();
            for (var child : sequence.children()) {
                budget.check();
                if (!(child instanceof PlanStep.Batch batch) || !seen.add(batch)) return false;
            }
            return true;
        } finally {
            budget.release(bytes);
        }
    }

    static <K> GraphPlan<K> optimize(GraphPlan<K> plan, PlanningBudget budget) {
        PlanStep program = optimize(plan.steps(), budget);
        if (program == plan.steps()) return plan;
        return new GraphPlan<>(plan.target(), plan.amount(), plan.preserveSeeds(), program, plan.recipes(),
                plan.initialExact(), plan.seeds(), plan.missingExact(), plan.result(), plan.searchNodes(), plan.planningNanos())
                .withSeedOptimality(plan.seedOptimality()).withAlternatives(plan.alternatives());
    }

    private int importProgram(PlanStep root) {
        var known = new IdentityHashMap<PlanStep, Integer>();
        var stack = new ArrayDeque<Visit>();
        stack.push(new Visit(root, false));
        while (!stack.isEmpty()) {
            charge();
            var visit = stack.pop();
            var step = visit.step();
            if (known.containsKey(step)) continue;
            List<PlanStep> children = step instanceof PlanStep.Sequence s ? s.children() : step instanceof PlanStep.Repeat r ? List.of(r.body()) : List.of();
            if (!visit.ready()) {
                stack.push(new Visit(step, true));
                for (int i = children.size() - 1; i >= 0; i--) if (!known.containsKey(children.get(i))) stack.push(new Visit(children.get(i), false));
            } else {
                List<Integer> ids = new ArrayList<>();
                for (var child : children) {
                    charge();
                    ids.add(known.get(child));
                }
                Node node = step instanceof PlanStep.Batch b ? new Node(b.recipe(), b.runs(), List.of()) :
                        new Node(null, step instanceof PlanStep.Repeat r ? r.times() : -1, List.copyOf(ids));
                known.put(step, add(node));
            }
        }
        return known.get(root);
    }

    private int find(int id) {
        int root = id;
        while (parents.get(root) != root) root = parents.get(root);
        while (id != root) {
            int next = parents.get(id);
            parents.set(id, root);
            id = next;
        }
        return root;
    }

    private Node canonical(Node node) {
        var children = new ArrayList<Integer>();
        for (int id : node.children()) {
            charge();
            children.add(find(id));
        }
        return new Node(node.recipe(), node.times(), List.copyOf(children));
    }

    private int add(Node value) {
        charge();
        Node node = canonical(value);
        Integer old = intern.get(node);
        if (old != null) return find(old);
        if (classes.size() >= 2048) throw new Stop();
        long bytes = 384L + node.children().size() * 32L;
        if (!budget.tryReserve(bytes)) throw new Stop();
        memory += bytes;
        int id = classes.size();
        parents.add(id);
        classes.add(new ArrayList<>(List.of(node)));
        intern.put(node, id);
        return id;
    }

    private void union(int left, int right) {
        charge();
        left = find(left);
        right = find(right);
        if (left == right) return;
        if (classes.get(left).size() < classes.get(right).size()) {
            int tmp = left;
            left = right;
            right = tmp;
        }
        parents.set(right, left);
        classes.get(left).addAll(classes.get(right));
        classes.get(right).clear();
        unions++;
    }

    private boolean empty(int id) {
        for (Node node : classes.get(find(id))) if (!node.batch() && !node.repeat() && node.children().isEmpty()) return true;
        return false;
    }

    private void rewrite(int id, Node value) {
        charge();
        Node node = canonical(value);
        if (node.batch()) return;
        if (node.repeat()) {
            if (node.times() == 0 || empty(node.children().get(0))) union(id, add(new Node(null, -1, List.of())));
            else if (node.times() == 1) union(id, node.children().get(0));
            else for (Node body : List.copyOf(classes.get(find(node.children().get(0))))) if (body.repeat()) {
                BigInteger count = BigInteger.valueOf(node.times()).multiply(BigInteger.valueOf(body.times()));
                if (count.compareTo(ExactAmounts.LONG_MAX) <= 0) union(id, add(new Node(null, count.longValueExact(), body.children())));
            }
            return;
        }
        List<Integer> children = new ArrayList<>();
        for (int child : node.children()) {
            charge();
            if (!empty(child)) children.add(find(child));
        }
        if (children.size() == 1) union(id, children.get(0));
        if (!children.equals(node.children())) union(id, add(new Node(null, -1, List.copyOf(children))));
        if (children.size() > 256) return;
        // Associativity is explored without destroying the original grouping.
        for (int at = 0; at < children.size(); at++) {
            for (Node child : List.copyOf(classes.get(find(children.get(at))))) {
                charge();
                if (child.batch() || child.repeat() || child.children().isEmpty() || child.children().size() > 32) continue;
                var flat = new ArrayList<>(children.subList(0, at));
                flat.addAll(child.children());
                flat.addAll(children.subList(at + 1, children.size()));
                if (flat.size() <= 256) union(id, add(new Node(null, -1, List.copyOf(flat))));
                break;
            }
        }
        // Factoring repeated words is safe for every input mode: no batch is
        // fused, no operation commuted, and no source choice is replaced.
        for (int period = 1; period <= Math.min(8, children.size() / 2); period++) {
            var factored = new ArrayList<Integer>();
            boolean changed = false;
            for (int at = 0; at < children.size();) {
                int copies = 1;
                while (at + (copies + 1) * period <= children.size()) {
                    boolean equal = true;
                    for (int k = 0; k < period; k++) {
                        charge();
                        if (find(children.get(at + k)) != find(children.get(at + copies * period + k))) {
                            equal = false;
                            break;
                        }
                    }
                    if (!equal) break;
                    copies++;
                }
                if (copies > 1) {
                    int body = period == 1 ? children.get(at) : add(new Node(null, -1, List.copyOf(children.subList(at, at + period))));
                    factored.add(add(new Node(null, copies, List.of(body))));
                    at += copies * period;
                    changed = true;
                } else factored.add(children.get(at++));
            }
            if (changed) union(id, factored.size() == 1 ? factored.get(0) : add(new Node(null, -1, List.copyOf(factored))));
        }
    }

    private void rebuild() {
        intern.clear();
        for (int id = 0; id < classes.size(); id++) if (find(id) == id) {
            var nodes = List.copyOf(classes.get(id));
            for (Node value : nodes) {
                charge();
                Node node = canonical(value);
                Integer old = intern.putIfAbsent(node, find(id));
                if (old != null) union(id, old);
            }
        }
    }

    private PlanStep extract(int root) {
        long[] costs = new long[classes.size()];
        Arrays.fill(costs, Long.MAX_VALUE);
        Node[] chosen = new Node[costs.length];
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int id = 0; id < classes.size(); id++) if (find(id) == id) for (Node node : classes.get(id)) {
                charge();
                long cost = 1;
                for (int child : node.children()) {
                    long childCost = costs[find(child)];
                    if (childCost == Long.MAX_VALUE) {
                        cost = Long.MAX_VALUE;
                        break;
                    }
                    cost = Math.min(Integer.MAX_VALUE, cost + childCost);
                }
                if (cost < costs[id]) {
                    costs[id] = cost;
                    chosen[id] = node;
                    changed = true;
                }
            }
        }
        root = find(root);
        if (chosen[root] == null) throw new Stop();
        var built = new HashMap<Integer, PlanStep>();
        var stack = new ArrayDeque<Integer>();
        stack.push(root);
        while (!stack.isEmpty()) {
            charge();
            int id = stack.peek();
            Node node = chosen[id];
            boolean ready = true;
            for (int child : node.children()) if (!built.containsKey(find(child))) {
                stack.push(find(child));
                ready = false;
                break;
            }
            if (!ready) continue;
            PlanStep result = node.batch() ? new PlanStep.Batch(node.recipe(), node.times()) : node.repeat() ?
                    new PlanStep.Repeat(built.get(find(node.children().get(0))), node.times()) :
                    new PlanStep.Sequence(node.children().stream().map(child -> built.get(find(child))).toList());
            built.put(id, result);
            stack.pop();
        }
        return built.get(root);
    }

    private void charge() {
        budget.check();
        if (++work > allowance) throw new Stop();
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
