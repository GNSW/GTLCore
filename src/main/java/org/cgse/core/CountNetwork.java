package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Exact capacitated flow for signed network-matrix subproblems, including
 * bipartite allocation/Hall constraints. A multi-resource hyperedge is refused.
 * Infeasible cuts are translated back into independently checked Farkas weights.
 */
final class CountNetwork implements AutoCloseable {

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private static final class Edge {

        final int to, reverse;
        final BigInteger initial;
        BigInteger residual;
        BigInteger cost = BigInteger.ZERO;

        Edge(int to, int reverse, BigInteger capacity) {
            this.to = to;
            this.reverse = reverse;
            initial = residual = capacity;
        }
    }

    private final List<ExactLinearProgram.Constraint> original, rows = new ArrayList<>();
    private final BitSet equalities = new BitSet();
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private final List<List<Edge>> graph = new ArrayList<>();
    private Edge[] variables;
    private int[] orientation, level, next, path;
    private BigInteger[] pathFlow;
    private int source, sink, ground;
    private BigInteger required, delivered = BigInteger.ZERO;
    private BigInteger[] counts;
    private long memory, work;
    private final long allowance;
    private boolean initialized, complete, infeasible;
    private BigInteger[] costs;
    private boolean optimized;
    private int cancelledCycles;
    private long optimizationStart;

    /** Costs are a caller-defined objective in this model, never a sum of unlike material units. */
    CountNetwork minimize(BigInteger[] value) {
        if (value.length != lower.length) throw new IllegalArgumentException("Network objective dimension");
        // The feasibility construction caps redundant circulation at the total
        // demand. With nonnegative costs a minimum has a cycle-free witness.
        if (Arrays.stream(value).allMatch(v -> v.signum() >= 0)) costs = value.clone();
        return this;
    }

    CountNetwork(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, PlanningBudget budget) {
        original = rows;
        this.lower = lower.clone();
        this.upper = upper.clone();
        this.budget = budget;
        allowance = Math.min(131072, budget.remainingWork() / 16);
        long terms = rows.stream().mapToLong(row -> row.terms().size()).sum();
        long bytes = 4096 + 768L * lower.length + 512L * rows.size() + 256L * terms;
        if (lower.length > 1024 || rows.size() > 2048 || allowance < 2048 || !budget.tryReserve(bytes)) complete = true;
        else memory = bytes;
    }

    boolean step() {
        if (complete) return true;
        try {
            charge();
            if (!initialized) {
                initialized = true;
                prepare();
                return complete;
            }
            if (delivered.equals(required)) {
                if (costs != null && !optimized) {
                    if (optimizationStart == 0) optimizationStart = work;
                    if (cancelledCycles < 32 && work - optimizationStart < Math.min(16384, allowance / 4)) {
                        if (cancelNegativeCycle()) {
                            cancelledCycles++;
                            return false;
                        }
                    }
                    optimized = true;
                }
                capture();
                return finish("verified_witness; cost_cycles=" + cancelledCycles);
            }
            if (!breadth()) {
                certifyCut();
                return finish(infeasible ? "certified_min_cut" : "uncertified_cut");
            }
            Arrays.fill(next, 0);
            BigInteger sent;
            while ((sent = send(source, required.subtract(delivered))).signum() > 0) delivered = delivered.add(sent);
            return false;
        } catch (Stop stopped) {
            // The current flow is still feasible after every cycle update.
            // Keep the last exactly validated witness on an optimization cutoff.
            return finish(counts == null ? "work_limit" : "verified_witness; optimization_limit");
        }
    }

    private void capture() {
        BigInteger[] candidate = lower.clone();
        for (int i = 0; i < variables.length; i++) if (variables[i] != null)
            candidate[i] = candidate[i].add(variables[i].initial.subtract(variables[i].residual));
        for (var row : original) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                charge();
                sum = sum.add(term.getValue().multiply(candidate[term.getKey()]));
            }
            if (sum.compareTo(row.upper()) > 0) throw new IllegalStateException("Network witness violates original constraints");
        }
        counts = candidate;
    }

    /** Bellman-Ford cycle cancellation augments an exact bottleneck, never one unit at a time. */
    private boolean cancelNegativeCycle() {
        if (counts == null) capture();
        int n = ground + 1, changed = -1;
        BigInteger[] distance = new BigInteger[n];
        Arrays.fill(distance, BigInteger.ZERO);
        int[] parent = new int[n];
        Edge[] edgeTo = new Edge[n];
        for (int round = 0; round < n; round++) {
            changed = -1;
            for (int from = 0; from < n; from++) for (var edge : graph.get(from)) {
                charge();
                if (edge.to >= n || edge.residual.signum() == 0) continue;
                BigInteger candidate = distance[from].add(edge.cost);
                if (candidate.compareTo(distance[edge.to]) < 0) {
                    distance[edge.to] = candidate;
                    parent[edge.to] = from;
                    edgeTo[edge.to] = edge;
                    changed = edge.to;
                }
            }
            if (changed < 0) return false;
        }
        int start = changed;
        for (int i = 0; i < n; i++) {
            charge();
            start = parent[start];
        }
        List<Edge> cycle = new ArrayList<>();
        BigInteger delta = null, cost = BigInteger.ZERO;
        int at = start;
        do {
            charge();
            Edge edge = edgeTo[at];
            if (edge == null || cycle.size() > n) return false;
            cycle.add(edge);
            cost = cost.add(edge.cost);
            delta = delta == null ? edge.residual : delta.min(edge.residual);
            at = parent[at];
        } while (at != start);
        if (cost.signum() >= 0 || delta.signum() <= 0) return false;
        // No yields or local-limit checks during this atomic circulation update.
        for (var edge : cycle) {
            edge.residual = edge.residual.subtract(delta);
            var reverse = graph.get(edge.to).get(edge.reverse);
            reverse.residual = reverse.residual.add(delta);
        }
        capture();
        return true;
    }

    private void prepare() {
        for (var row : original) {
            charge();
            var normalized = CountReduction.normalize(row);
            if (normalized.terms().size() == 1) {
                var e = normalized.terms().entrySet().iterator().next();
                if (e.getValue().equals(BigInteger.ONE)) upper[e.getKey()] = upper[e.getKey()] == null ? normalized.upper() : upper[e.getKey()].min(normalized.upper());
                else lower[e.getKey()] = lower[e.getKey()].max(normalized.upper().negate());
            } else rows.add(normalized);
        }
        for (int i = 0; i < lower.length; i++) if (upper[i] != null && upper[i].compareTo(lower[i]) < 0) {
            finish("empty_domain_deferred_to_bounds");
            return;
        }
        // Identical rows are the same constraint, not parallel incidences.
        Map<Map<Integer, BigInteger>, BigInteger> distinct = new LinkedHashMap<>();
        for (var row : rows) distinct.merge(row.terms(), row.upper(), BigInteger::min);
        rows.clear();
        Set<Map<Integer, BigInteger>> used = new HashSet<>();
        for (var entry : distinct.entrySet()) {
            charge();
            if (!used.add(entry.getKey())) continue;
            Map<Integer, BigInteger> opposite = new LinkedHashMap<>();
            for (var term : entry.getKey().entrySet()) {
                charge();
                opposite.put(term.getKey(), term.getValue().negate());
            }
            if (entry.getValue().negate().equals(distinct.get(opposite))) {
                equalities.set(rows.size());
                used.add(opposite);
            }
            rows.add(new ExactLinearProgram.Constraint(entry.getKey(), entry.getValue()));
        }
        List<List<Integer>> incidence = new ArrayList<>();
        for (int i = 0; i < lower.length; i++) incidence.add(new ArrayList<>());
        for (int r = 0; r < rows.size(); r++) for (var term : rows.get(r).terms().entrySet()) {
            charge();
            if (!term.getValue().abs().equals(BigInteger.ONE)) {
                finish("non_network_coefficients");
                return;
            }
            if (!lower[term.getKey()].equals(upper[term.getKey()])) incidence.get(term.getKey()).add(r);
        }
        for (var list : incidence) if (list.size() > 2) {
            finish("shared_hyperedge");
            return;
        }
        orientation = new int[rows.size()];
        for (int start = 0; start < rows.size(); start++) if (orientation[start] == 0) {
            orientation[start] = 1;
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                int a = queue.removeFirst();
                for (int variable : rows.get(a).terms().keySet()) {
                    charge();
                    if (incidence.get(variable).size() != 2) continue;
                    int b = incidence.get(variable).get(0) == a ? incidence.get(variable).get(1) : incidence.get(variable).get(0);
                    int sign = -orientation[a] * rows.get(a).terms().get(variable).signum() * rows.get(b).terms().get(variable).signum();
                    if (orientation[b] != 0 && orientation[b] != sign) {
                        finish("non_network_signs");
                        return;
                    }
                    if (orientation[b] == 0) {
                        orientation[b] = sign;
                        queue.addLast(b);
                    }
                }
            }
        }
        ground = rows.size();
        source = ground + 1;
        sink = source + 1;
        for (int i = 0; i <= sink; i++) graph.add(new ArrayList<>());
        level = new int[graph.size()];
        next = new int[graph.size()];
        path = new int[graph.size()];
        pathFlow = new BigInteger[graph.size()];
        variables = new Edge[lower.length];
        BigInteger[] demands = new BigInteger[ground + 1];
        Arrays.fill(demands, BigInteger.ZERO);
        for (int r = 0; r < rows.size(); r++) {
            var row = rows.get(r);
            BigInteger value = row.upper();
            for (var term : row.terms().entrySet()) {
                charge();
                value = value.subtract(term.getValue().multiply(lower[term.getKey()]));
            }
            demands[r] = value.multiply(BigInteger.valueOf(orientation[r]));
            demands[ground] = demands[ground].subtract(demands[r]);
        }
        required = BigInteger.ZERO;
        for (BigInteger demand : demands) if (demand.signum() > 0) required = required.add(demand);
        for (int id = 0; id < lower.length; id++) {
            charge();
            if (incidence.get(id).isEmpty()) continue;
            int from = ground, to = ground;
            for (int r : incidence.get(id)) {
                if (orientation[r] * rows.get(r).terms().get(id).signum() > 0) to = r;
                else from = r;
            }
            BigInteger capacity = upper[id] == null ? required : upper[id].subtract(lower[id]).min(required);
            variables[id] = add(from, to, capacity);
            if (costs != null) {
                variables[id].cost = costs[id];
                graph.get(to).get(variables[id].reverse).cost = costs[id].negate();
            }
        }
        for (int r = 0; r < rows.size(); r++)
            if (!equalities.get(r)) add(orientation[r] > 0 ? ground : r, orientation[r] > 0 ? r : ground, required);
        for (int r = 0; r < demands.length; r++) {
            if (demands[r].signum() > 0) add(r, sink, demands[r]);
            else if (demands[r].signum() < 0) add(source, r, demands[r].negate());
        }
    }

    private Edge add(int from, int to, BigInteger capacity) {
        Edge forward = new Edge(to, graph.get(to).size(), capacity);
        Edge reverse = new Edge(from, graph.get(from).size(), BigInteger.ZERO);
        graph.get(from).add(forward);
        graph.get(to).add(reverse);
        return forward;
    }

    private boolean breadth() {
        Arrays.fill(level, -1);
        level[source] = 0;
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(source);
        while (!queue.isEmpty()) {
            int node = queue.removeFirst();
            for (var edge : graph.get(node)) {
                charge();
                if (edge.residual.signum() > 0 && level[edge.to] < 0) {
                    level[edge.to] = level[node] + 1;
                    queue.addLast(edge.to);
                }
            }
        }
        return level[sink] >= 0;
    }

    private BigInteger send(int node, BigInteger flow) {
        if (flow.signum() == 0) return flow;
        // A long admissible path must not consume the Java call stack.
        int depth = 0;
        path[0] = node;
        pathFlow[0] = flow;
        while (depth >= 0) {
            charge();
            node = path[depth];
            if (node == sink) {
                BigInteger sent = pathFlow[depth];
                for (int i = 0; i < depth; i++) {
                    charge();
                    Edge edge = graph.get(path[i]).get(next[path[i]]);
                    edge.residual = edge.residual.subtract(sent);
                    Edge reverse = graph.get(edge.to).get(edge.reverse);
                    reverse.residual = reverse.residual.add(sent);
                }
                return sent;
            }
            boolean advanced = false;
            while (next[node] < graph.get(node).size()) {
                charge();
                Edge edge = graph.get(node).get(next[node]);
                if (edge.residual.signum() > 0 && level[edge.to] == level[node] + 1) {
                    path[depth + 1] = edge.to;
                    pathFlow[depth + 1] = pathFlow[depth].min(edge.residual);
                    depth++;
                    advanced = true;
                    break;
                }
                next[node]++;
            }
            if (!advanced && --depth >= 0) next[path[depth]]++;
        }
        return BigInteger.ZERO;
    }

    private void certifyCut() {
        var axioms = new ArrayList<ExactLinearProgram.Constraint>();
        var weights = new ArrayList<ExactRational>();
        BigInteger[] coefficients = new BigInteger[lower.length];
        Arrays.fill(coefficients, BigInteger.ZERO);
        for (int r = 0; r < rows.size(); r++) {
            int multiplier = orientation[r] * ((level[r] >= 0 ? 1 : 0) - (level[ground] >= 0 ? 1 : 0));
            if (multiplier < 0 && !equalities.get(r)) return;
            var row = rows.get(r);
            if (multiplier < 0) {
                // An equality may use either original inequality direction.
                // Keep Farkas multipliers nonnegative in the exported proof.
                Map<Integer, BigInteger> opposite = new LinkedHashMap<>();
                row.terms().forEach((id, coefficient) -> opposite.put(id, coefficient.negate()));
                axioms.add(new ExactLinearProgram.Constraint(opposite, row.upper().negate()));
            } else axioms.add(row);
            weights.add(ExactRational.of(BigInteger.valueOf(Math.abs(multiplier))));
            for (var term : rows.get(r).terms().entrySet()) {
                charge();
                coefficients[term.getKey()] = coefficients[term.getKey()].add(term.getValue().multiply(BigInteger.valueOf(multiplier)));
            }
        }
        for (int id = 0; id < lower.length; id++) {
            charge();
            if (coefficients[id].signum() >= 0) {
                axioms.add(new ExactLinearProgram.Constraint(Map.of(id, BigInteger.ONE.negate()), lower[id].negate()));
                weights.add(ExactRational.of(coefficients[id]));
            } else {
                if (upper[id] == null) return;
                axioms.add(new ExactLinearProgram.Constraint(Map.of(id, BigInteger.ONE), upper[id]));
                weights.add(ExactRational.of(coefficients[id].negate()));
            }
        }
        var proof = CountProof.certificate("network_min_cut", lower.length, axioms, List.of(), weights.toArray(ExactRational[]::new), true);
        if (CountProof.verify(proof, 1_000_000) != CountProof.Verdict.VERIFIED) return;
        infeasible = true;
        if (budget.proofJournal() != null) budget.proofJournal().add(proof);
    }

    private void charge() {
        budget.check();
        if (++work > allowance) throw new Stop();
    }

    private boolean finish(String reason) {
        complete = true;
        budget.note("count_network", reason + "; rows=" + rows.size() + "; work=" + work);
        return true;
    }

    BigInteger[] counts() {
        return counts == null ? null : counts.clone();
    }

    boolean infeasible() {
        return infeasible;
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
