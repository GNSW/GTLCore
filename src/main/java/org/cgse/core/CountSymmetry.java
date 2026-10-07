package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Bounded colored-incidence automorphism search and exact Boolean lex leaders. */
final class CountSymmetry implements AutoCloseable {

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private record Edge(int color, BigInteger coefficient) {}

    private record Color(Object previous, List<Edge> edges) {}

    private final PlanningBudget budget;
    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] low, high;
    private final List<List<Integer>> incident = new ArrayList<>();
    private final List<int[]> generators = new ArrayList<>();
    private final List<ExactLinearProgram.Constraint> leaders = new ArrayList<>();
    private int[] colors, rowColors, image, inverse, order, cursor;
    private int depth, anchor, target, attempts;
    private long work, memory;
    private CountCdcl search;
    private BigInteger[] counts;
    private boolean complete, infeasible, prepared, solving;
    private final long allowance;

    CountSymmetry(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, PlanningBudget budget, long allowance) {
        this.rows = rows;
        this.low = low;
        this.high = high;
        this.budget = budget;
        this.allowance = Math.min(allowance, budget.remainingWork() / 8);
        if (low.length > 192 || rows.size() > 1024 || low.length < 2 || this.allowance < 4096) {
            complete = true;
            return;
        }
        for (int i = 0; i < low.length; i++) if (!BigInteger.ZERO.equals(low[i]) || !BigInteger.ONE.equals(high[i])) {
            complete = true;
            return;
        }
        long terms = rows.stream().mapToLong(r -> r.terms().size()).sum();
        long bytes = 2048 + 512L * low.length + 128L * rows.size() + 128L * terms;
        if (terms > 8192 || !budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
    }

    boolean step() {
        if (complete) return true;
        if (solving) {
            if (!search.step()) return false;
            counts = search.counts();
            infeasible = search.infeasible();
            if (counts != null) for (var row : rows) {
                var value = BigInteger.ZERO;
                for (var e : row.terms().entrySet()) {
                    budget.check();
                    value = value.add(e.getValue().multiply(counts[e.getKey()]));
                }
                if (value.compareTo(row.upper()) > 0) throw new IllegalStateException("Symmetry witness violates original row");
            }
            search.close();
            search = null;
            complete = true;
            budget.note("count_symmetry", "generators=" + generators.size() + "; witness=" + (counts != null) + "; proven_infeasible=" + infeasible);
            return true;
        }
        try {
            charge();
            if (!prepared) {
                refine();
                prepared = true;
            }
            if (image == null && !start()) return beginSolve();
            if (depth == low.length) {
                if (automorphism(image)) {
                    var terms = new TreeMap<Integer, BigInteger>();
                    for (int i = 0; i < image.length; i++) {
                        charge();
                        var weight = BigInteger.ONE.shiftLeft(image.length - i - 1);
                        terms.merge(i, weight, BigInteger::add);
                        terms.merge(image[i], weight.negate(), BigInteger::add);
                    }
                    terms.values().removeIf(v -> v.signum() == 0);
                    if (!terms.isEmpty()) {
                        generators.add(image.clone());
                        leaders.add(new ExactLinearProgram.Constraint(terms, BigInteger.ZERO));
                    }
                    image = null;
                    if (generators.size() == 4) return beginSolve();
                    return false;
                }
                undo();
            }
            int source = order[depth];
            while (cursor[depth] < low.length) {
                charge();
                int position = cursor[depth]++;
                // Identity first minimizes unrelated motion in a generator.
                int destination = position == 0 ? source : position <= source ? position - 1 : position;
                if (inverse[destination] >= 0 || colors[source] != colors[destination]) continue;
                image[source] = destination;
                inverse[destination] = source;
                if (compatible(source)) {
                    depth++;
                    if (depth < low.length) {
                        order[depth] = choose();
                        cursor[depth] = 0;
                    }
                    return false;
                }
                image[source] = -1;
                inverse[destination] = -1;
            }
            if (depth == 1) image = null;
            else undo();
            return false;
        } catch (Stop stopped) {
            return beginSolve();
        }
    }

    private void refine() {
        int n = low.length, m = rows.size();
        colors = new int[n];
        rowColors = new int[m];
        for (int i = 0; i < n; i++) incident.add(new ArrayList<>());
        for (int r = 0; r < m; r++) for (int i : rows.get(r).terms().keySet()) {
            charge();
            incident.get(i).add(r);
        }
        Comparator<Edge> sorted = Comparator.comparingInt(Edge::color).thenComparing(Edge::coefficient);
        for (int round = 0; round < Math.min(n + m, 16); round++) {
            var palette = new HashMap<Object, Integer>();
            int[] nextRows = new int[m];
            for (int r = 0; r < m; r++) {
                var edges = new ArrayList<Edge>();
                for (var e : rows.get(r).terms().entrySet()) {
                    charge();
                    edges.add(new Edge(colors[e.getKey()], e.getValue()));
                }
                edges.sort(sorted);
                var color = new Color(List.of(rowColors[r], rows.get(r).upper()), edges);
                nextRows[r] = palette.computeIfAbsent(color, k -> palette.size());
            }
            palette.clear();
            int[] next = new int[n];
            for (int i = 0; i < n; i++) {
                var edges = new ArrayList<Edge>();
                for (int r : incident.get(i)) {
                    charge();
                    edges.add(new Edge(nextRows[r], rows.get(r).terms().get(i)));
                }
                edges.sort(sorted);
                var color = new Color(colors[i], edges);
                next[i] = palette.computeIfAbsent(color, k -> palette.size());
            }
            boolean stable = Arrays.equals(next, colors) && Arrays.equals(nextRows, rowColors);
            colors = next;
            rowColors = nextRows;
            if (stable) break;
        }
    }

    private boolean start() {
        while (anchor < low.length) {
            charge();
            if (++target >= low.length) {
                anchor++;
                target = anchor;
                continue;
            }
            if (colors[anchor] != colors[target]) continue;
            if (++attempts > 8) return false;
            image = new int[low.length];
            inverse = new int[low.length];
            order = new int[low.length];
            cursor = new int[low.length];
            Arrays.fill(image, -1);
            Arrays.fill(inverse, -1);
            image[anchor] = target;
            inverse[target] = anchor;
            order[0] = anchor;
            depth = 1;
            order[1] = choose();
            return true;
        }
        return false;
    }

    private int choose() {
        int best = -1, bestScore = -1;
        for (int i = 0; i < low.length; i++) if (image[i] < 0) {
            int score = 0;
            for (int r : incident.get(i)) for (int j : rows.get(r).terms().keySet()) {
                charge();
                if (image[j] >= 0) score++;
            }
            if (score > bestScore) {
                best = i;
                bestScore = score;
            }
        }
        return best;
    }

    private boolean compatible(int source) {
        for (int r : incident.get(source)) {
            boolean found = false;
            var row = rows.get(r);
            for (int s : incident.get(image[source])) {
                charge();
                if (rowColors[r] != rowColors[s]) continue;
                var other = rows.get(s);
                boolean matches = true;
                for (var e : row.terms().entrySet()) {
                    charge();
                    int mapped = image[e.getKey()];
                    if (mapped >= 0 && !e.getValue().equals(other.terms().get(mapped))) {
                        matches = false;
                        break;
                    }
                }
                if (matches) for (var e : other.terms().entrySet()) {
                    charge();
                    int previous = inverse[e.getKey()];
                    if (previous >= 0 && !e.getValue().equals(row.terms().get(previous))) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private boolean automorphism(int[] permutation) {
        var original = new HashSet<>(rows);
        for (var row : rows) {
            var mapped = new TreeMap<Integer, BigInteger>();
            for (var e : row.terms().entrySet()) {
                charge();
                mapped.put(permutation[e.getKey()], e.getValue());
            }
            if (!original.contains(new ExactLinearProgram.Constraint(mapped, row.upper()))) return false;
        }
        return true;
    }

    private void undo() {
        depth--;
        int source = order[depth];
        inverse[image[source]] = -1;
        image[source] = -1;
    }

    private boolean beginSolve() {
        if (leaders.isEmpty()) {
            complete = true;
            return true;
        }
        var proof = new CountProof.Symmetry("count_symmetry", low.length, rows.stream().map(CountProof::row).toList(),
                generators.stream().map(p -> Arrays.stream(p).boxed().toList()).toList(), leaders.stream().map(CountProof::row).toList());
        long checking = 1024L + 8L * generators.size() * (low.length + rows.stream().mapToLong(r -> r.terms().size()).sum());
        if (checking > budget.remainingWork() / 16) {
            complete = true;
            return true;
        }
        budget.charge(checking);
        if (CountProof.verify(proof, checking) != CountProof.Verdict.VERIFIED) {
            complete = true;
            return true;
        }
        if (budget.proofJournal() != null) budget.proofJournal().add(proof);
        var input = new ArrayList<>(rows);
        input.addAll(leaders);
        search = new CountCdcl(input, low, high, budget, allowance);
        solving = true;
        return false;
    }

    private void charge() {
        budget.check();
        if (++work > Math.min(65536, allowance / 4)) throw new Stop();
    }

    BigInteger[] counts() {
        return counts == null ? null : counts.clone();
    }

    boolean infeasible() {
        return infeasible;
    }

    @Override
    public void close() {
        if (search != null) search.close();
        search = null;
        budget.release(memory);
        memory = 0;
    }
}
