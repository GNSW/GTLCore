package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Exact multiple-choice packing relaxation with interchangeable-bin state dominance. */
final class CountBinPackingSearch implements AutoCloseable {

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private record Node(int depth, int[] room, long profit, Node parent, int bin) {}

    private record State(int depth, List<Integer> room) {}

    private record Item(int weight, long profit, int[] variables) {}

    private final List<ExactLinearProgram.Constraint> original;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private long allowance;
    private final Deque<Node> open = new ArrayDeque<>();
    private final Map<State, Long> reached = new HashMap<>();
    private Item[] items;
    private long goal, work, memory, nodes, dominated;
    private boolean complete, infeasible;
    private boolean retaining, paused;
    private BigInteger[] counts;

    CountBinPackingSearch(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                          BigInteger[] upper, PlanningBudget budget, long maximumWork) {
        original = rows;
        this.lower = lower;
        this.upper = upper;
        this.budget = budget;
        allowance = Math.min(maximumWork, budget.remainingWork());
        if (lower.length > 512 || rows.size() > 1024 || allowance < 1024) {
            complete = true;
            return;
        }
        long entries = rows.stream().mapToLong(row -> row.terms().size()).sum();
        if (entries > 32768) {
            complete = true;
            return;
        }
        long bytes = 8192L + 1024L * lower.length + 256L * rows.size() + 384L * entries;
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        try {
            prepare(rows);
        } catch (Stop stopped) {
            finish(false, "local_limit");
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private void prepare(List<ExactLinearProgram.Constraint> source) {
        BitSet free = new BitSet();
        for (int i = 0; i < lower.length; i++) {
            charge();
            if (upper[i] == null || upper[i].compareTo(lower[i]) < 0 ||
                    !upper[i].equals(lower[i]) && (!lower[i].equals(BigInteger.ZERO) || !upper[i].equals(BigInteger.ONE))) {
                finish(false, "unsupported_domain");
                return;
            }
            if (!upper[i].equals(lower[i])) free.set(i);
        }
        List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
        for (var row : source) {
            BigInteger bound = row.upper();
            Map<Integer, BigInteger> terms = new TreeMap<>();
            for (var term : row.terms().entrySet()) {
                charge();
                if (free.get(term.getKey())) {
                    if (term.getValue().signum() != 0) terms.put(term.getKey(), term.getValue());
                } else bound = bound.subtract(term.getValue().multiply(lower[term.getKey()]));
            }
            if (terms.isEmpty()) {
                if (bound.signum() < 0) {
                    finish(true, "constant_conflict");
                    return;
                }
            } else rows.add(CountReduction.normalize(new ExactLinearProgram.Constraint(terms, bound)));
        }
        // Disjoint at-most-one rows describe complete item choices. A singleton
        // fallback would be sound, but declines here to keep this strategy small.
        int[] owner = new int[lower.length];
        Arrays.fill(owner, -1);
        List<List<Integer>> groups = new ArrayList<>();
        for (var row : rows) {
            charge();
            if (!row.upper().equals(BigInteger.ONE) || row.terms().size() < 2 ||
                    row.terms().values().stream().anyMatch(v -> !v.equals(BigInteger.ONE)) ||
                    row.terms().keySet().stream().anyMatch(id -> owner[id] >= 0))
                continue;
            int id = groups.size();
            List<Integer> members = new ArrayList<>(row.terms().keySet());
            groups.add(members);
            members.forEach(j -> owner[j] = id);
        }
        if (groups.size() < 2 || groups.size() > 128) {
            finish(false, "unsupported_groups");
            return;
        }
        for (int id = free.nextSetBit(0); id >= 0; id = free.nextSetBit(id + 1))
            if (owner[id] < 0) {
                finish(false, "ungrouped_column");
                return;
            }
        int[] bin = new int[lower.length], weight = new int[lower.length];
        Arrays.fill(bin, -1);
        List<Integer> capacities = new ArrayList<>();
        for (var row : rows) {
            charge();
            if (row.upper().signum() < 0 || row.upper().bitLength() > 20 ||
                    row.terms().values().stream().anyMatch(v -> v.signum() <= 0 || v.bitLength() > 20))
                continue;
            BitSet covered = new BitSet();
            boolean eligible = true;
            for (int id : row.terms().keySet()) {
                charge();
                if (bin[id] >= 0 || covered.get(owner[id])) {
                    eligible = false;
                    break;
                }
                covered.set(owner[id]);
            }
            if (!eligible || covered.cardinality() != groups.size()) continue;
            int next = capacities.size();
            capacities.add(row.upper().intValueExact());
            for (var term : row.terms().entrySet()) {
                charge();
                bin[term.getKey()] = next;
                weight[term.getKey()] = term.getValue().intValueExact();
            }
        }
        if (capacities.size() < 2 || capacities.size() > 16) {
            finish(false, "unsupported_bins");
            return;
        }
        ExactLinearProgram.Constraint objective = null;
        for (var row : rows) {
            charge();
            if (row.upper().signum() >= 0 || row.terms().size() != free.cardinality() ||
                    row.terms().values().stream().anyMatch(v -> v.signum() >= 0 || v.bitLength() > 31))
                continue;
            objective = row;
            break;
        }
        if (objective == null || objective.upper().bitLength() > 40) {
            finish(false, "unsupported_goal");
            return;
        }
        goal = objective.upper().negate().longValueExact();
        List<Item> ordered = new ArrayList<>();
        for (var group : groups) {
            charge();
            if (group.size() != capacities.size()) {
                finish(false, "incomplete_choices");
                return;
            }
            int[] ids = new int[capacities.size()];
            Arrays.fill(ids, -1);
            int w = weight[group.get(0)];
            long p = objective.terms().get(group.get(0)).negate().longValueExact();
            for (int id : group) {
                charge();
                if (bin[id] < 0 || ids[bin[id]] >= 0 || weight[id] != w ||
                        !objective.terms().get(id).negate().equals(BigInteger.valueOf(p))) {
                    finish(false, "asymmetric_item");
                    return;
                }
                ids[bin[id]] = id;
            }
            ordered.add(new Item(w, p, ids));
        }
        ordered.sort((a, b) -> {
            // Admission bounds imply both cross products fit in a signed long.
            int c = Long.compare(b.profit * a.weight, a.profit * b.weight);
            if (c == 0) c = Integer.compare(b.weight, a.weight);
            return c;
        });
        items = ordered.toArray(Item[]::new);
        long stackBytes = 256L * (items.length + 1) * (capacities.size() + 1) * (capacities.size() + 1);
        if (!budget.tryReserve(stackBytes)) {
            finish(false, "memory_limit");
            return;
        }
        memory += stackBytes;
        open.push(new Node(0, capacities.stream().mapToInt(Integer::intValue).toArray(), 0, null, -1));
    }

    boolean step() {
        if (complete || paused) return true;
        if (retaining && work >= allowance) return paused = true;
        try {
            charge();
            if (open.isEmpty()) return finish(true, "exhausted");
            Node node = open.peek();
            nodes++;
            if (node.profit >= goal) return witness(node);
            if (node.depth == items.length) {
                open.pop();
                return false;
            }
            int[] sorted = node.room.clone();
            for (int ignored : sorted) charge();
            Arrays.sort(sorted);
            List<Integer> canonical = new ArrayList<>(sorted.length);
            long room = 0;
            for (int value : sorted) {
                canonical.add(value);
                room += value;
            }
            State state = new State(node.depth, canonical);
            Long previous = reached.get(state);
            if (previous != null && previous >= node.profit) {
                dominated++;
                open.pop();
                return false;
            }
            long maximum = node.profit;
            for (int i = node.depth; i < items.length; i++) {
                charge();
                Item item = items[i];
                if (room >= item.weight) {
                    room -= item.weight;
                    maximum += item.profit;
                } else {
                    maximum += item.profit * room / item.weight;
                    break;
                }
            }
            if (maximum < goal) {
                open.pop();
                return false;
            }
            if (previous == null) {
                long bytes = 256L + 32L * sorted.length;
                if (reached.size() >= 65536 || !budget.tryReserve(bytes)) return finish(false, "state_memory_limit");
                memory += bytes;
            }
            reached.put(state, node.profit);
            Item item = items[node.depth];
            List<Node> children = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (int b = 0; b < node.room.length; b++) {
                charge();
                if (node.room[b] < item.weight || !seen.add(node.room[b])) continue;
                int[] next = node.room.clone();
                next[b] -= item.weight;
                children.add(new Node(node.depth + 1, next, node.profit + item.profit, node, b));
            }
            open.pop();
            open.push(new Node(node.depth + 1, node.room, node.profit, node, -1));
            for (int i = children.size() - 1; i >= 0; i--) open.push(children.get(i));
            return false;
        } catch (Stop stopped) {
            return finish(false, "local_limit");
        }
    }

    private boolean witness(Node node) {
        BigInteger[] result = lower.clone();
        for (Node next = node; next.parent != null; next = next.parent)
            if (next.bin >= 0) result[items[next.depth - 1].variables[next.bin]] = BigInteger.ONE;
        for (var row : original) {
            BigInteger total = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                charge();
                total = total.add(term.getValue().multiply(result[term.getKey()]));
            }
            if (total.compareTo(row.upper()) > 0) return finish(false, "relaxed_witness");
        }
        counts = result;
        return finish(false, "witness");
    }

    private void charge() {
        if (!retaining && work >= allowance) throw new Stop();
        budget.check();
        work++;
    }

    private boolean finish(boolean impossible, String reason) {
        complete = true;
        infeasible = impossible;
        budget.note("count_bin_packing", reason + "; nodes=" + nodes + "; dominated=" + dominated + "; work=" + work);
        return true;
    }

    BigInteger[] counts() {
        return counts;
    }

    boolean infeasible() {
        return infeasible;
    }

    CountBinPackingSearch retained() {
        retaining = true;
        return this;
    }

    boolean paused() {
        return paused;
    }

    void resume(long quantum) {
        if (!retaining || complete || quantum <= 0) throw new IllegalStateException("Packing search cannot resume");
        if (budget.remainingWork() == 0) budget.check();
        allowance = work + Math.min(quantum, budget.remainingWork());
        paused = false;
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
        reached.clear();
        open.clear();
    }
}
