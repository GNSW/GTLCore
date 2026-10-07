package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** Exact small Boolean cardinality search, with a coloring bound for conflict graphs. */
final class CountCardinalitySearch implements AutoCloseable {

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private record Row(long positive, long negative, int capacity) {}

    private record Weight(long positive, long negative, long amount) {}

    private record Weighted(List<Weight> terms, long capacity) {}

    private record Node(long one, long zero) {}

    private static final class CliqueNode {

        long available, chosen;
        int[] order, colors;
        int cursor;

        CliqueNode(long available, long chosen) {
            this.available = available;
            this.chosen = chosen;
        }
    }

    private final List<ExactLinearProgram.Constraint> original;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private long allowance;
    private final List<Row> rows = new ArrayList<>();
    private final List<Weighted> weighted = new ArrayList<>();
    private final Deque<Node> open = new ArrayDeque<>();
    private final Deque<CliqueNode> cliques = new ArrayDeque<>();
    private int[] variables;
    private long[] compatible;
    private long[] conditionalConflicts;
    private long boundVariables;
    private int boundRequired;
    private boolean boundOnes;
    private long coloringPrunes;
    private long all, memory, work, nodes;
    private int goal;
    private boolean complete, infeasible;
    private boolean retaining, paused;
    private BigInteger[] counts;

    CountCardinalitySearch(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                           BigInteger[] upper, PlanningBudget budget, long maximumWork) {
        original = rows;
        this.lower = lower;
        this.upper = upper;
        this.budget = budget;
        allowance = Math.min(maximumWork, budget.remainingWork());
        if (lower.length > 512 || rows.size() > 2048 || allowance < 1024) {
            complete = true;
            return;
        }
        long bytes = 4096L + 256L * lower.length + 1024L * rows.size() + 64L * 64 * 32;
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        try {
            int[] indices = new int[lower.length];
            Arrays.fill(indices, -1);
            var free = new ArrayList<Integer>();
            for (int i = 0; i < lower.length; i++) {
                charge();
                if (upper[i] == null || upper[i].subtract(lower[i]).signum() < 0 ||
                        upper[i].subtract(lower[i]).compareTo(BigInteger.ONE) > 0) {
                    finish(false, "unsupported_domain");
                    return;
                }
                if (!lower[i].equals(upper[i])) {
                    indices[i] = free.size();
                    free.add(i);
                }
            }
            if (free.size() > 63 || free.isEmpty()) {
                finish(false, "unsupported_size");
                return;
            }
            variables = free.stream().mapToInt(Integer::intValue).toArray();
            all = -1L >>> (64 - variables.length);
            for (var source : rows) {
                BigInteger bound = source.upper(), scale = null, complemented = BigInteger.ZERO;
                BigInteger gcd = BigInteger.ZERO, total = BigInteger.ZERO;
                Map<BigInteger, long[]> coefficients = new LinkedHashMap<>();
                long positive = 0, negative = 0;
                for (var term : source.terms().entrySet()) {
                    charge();
                    int id = term.getKey();
                    BigInteger value = term.getValue();
                    bound = bound.subtract(value.multiply(lower[id]));
                    if (indices[id] < 0 || value.signum() == 0) continue;
                    gcd = gcd.gcd(value);
                    total = total.add(value.abs());
                    coefficients.computeIfAbsent(value.abs(), ignored -> new long[2])[value.signum() > 0 ? 0 : 1] |= 1L << indices[id];
                    scale = scale == null ? value.abs() : scale.min(value.abs());
                    if (value.signum() > 0) positive |= 1L << indices[id];
                    else {
                        negative |= 1L << indices[id];
                        complemented = complemented.add(value.abs());
                    }
                }
                if (scale == null) {
                    if (bound.signum() < 0) {
                        finish(true, "constant_conflict");
                        return;
                    }
                    continue;
                }
                // After complementing negative coefficients every literal has
                // a nonnegative weight. Replacing each weight by the minimum
                // yields a necessary cardinality row, including mixed rows
                // introduced by the crafting finish-count embedding.
                BigInteger[] qr = bound.add(complemented).divideAndRemainder(scale);
                BigInteger capacity = qr[0].subtract(qr[1].signum() < 0 ? BigInteger.ONE : BigInteger.ZERO);
                if (capacity.signum() < 0) {
                    finish(true, "cardinality_conflict");
                    return;
                }
                if (coefficients.size() > 1 && coefficients.size() <= 8 &&
                        total.divide(gcd).bitLength() <= 60 && bound.add(complemented).compareTo(total) < 0) {
                    List<Weight> terms = new ArrayList<>();
                    for (var entry : coefficients.entrySet()) {
                        charge();
                        terms.add(new Weight(entry.getValue()[0], entry.getValue()[1], entry.getKey().divide(gcd).longValueExact()));
                    }
                    weighted.add(new Weighted(terms, bound.add(complemented).divide(gcd).longValueExact()));
                }
                if (capacity.compareTo(BigInteger.valueOf(Long.bitCount(positive | negative))) >= 0) continue;
                this.rows.add(new Row(positive, negative, capacity.intValueExact()));
            }
            this.rows.sort(Comparator.comparingInt(r -> Long.bitCount(r.positive | r.negative)));
            compatible = new long[variables.length];
            for (int i = 0; i < compatible.length; i++) compatible[i] = all & ~(1L << i);
            int edges = 0;
            long goalVariables = 0;
            for (Row row : this.rows) {
                // Dropping the nonnegative positive literals is a relaxation:
                // at least |negative|-capacity complemented choices must be
                // selected, even when an auxiliary count occurs positively.
                int required = Long.bitCount(row.negative) - row.capacity;
                if (required > goal || required == goal && required > 0 &&
                        Long.bitCount(row.negative) < Long.bitCount(goalVariables)) {
                    goal = required;
                    goalVariables = row.negative;
                }
                if (row.negative != 0 || row.capacity != 1) continue;
                for (long rest = row.positive; rest != 0; rest &= rest - 1) {
                    charge();
                    int id = Long.numberOfTrailingZeros(rest);
                    compatible[id] &= ~row.positive;
                    edges++;
                }
            }
            if (goal > 0 && edges > 0) cliques.push(new CliqueNode(goalVariables, 0));
            else {
                compatible = null;
                prepareConditionalBound();
                open.push(new Node(0, 0));
            }
        } catch (Stop stopped) {
            finish(false, "local_limit");
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    boolean step() {
        if (complete || paused) return true;
        if (retaining && work >= allowance) return paused = true;
        try {
            charge();
            return compatible == null ? cardinalityStep() : cliqueStep();
        } catch (Stop stopped) {
            return finish(false, "local_limit");
        }
    }

    private boolean cardinalityStep() {
        if (open.isEmpty()) return finish(true, "exhausted");
        Node node = open.peek();
        long one = node.one, zero = node.zero;
        nodes++;
        boolean changed;
        do {
            long before = one | zero;
            for (Row row : rows) {
                charge();
                int fixed = Long.bitCount(row.positive & one) + Long.bitCount(row.negative & zero);
                if (fixed > row.capacity) {
                    open.pop();
                    return false;
                }
                if (fixed == row.capacity) {
                    long free = (row.positive | row.negative) & ~(one | zero);
                    zero |= row.positive & free;
                    one |= row.negative & free;
                }
            }
            // Equal-weight literals share a popcount. This retains exact
            // propagation for a few coefficient classes without rescanning a
            // dense row once per variable at every search node.
            for (Weighted row : weighted) {
                long used = 0;
                for (Weight term : row.terms) {
                    charge();
                    used += term.amount * (Long.bitCount(term.positive & one) + Long.bitCount(term.negative & zero));
                }
                if (used > row.capacity) {
                    open.pop();
                    return false;
                }
                long slack = row.capacity - used;
                for (Weight term : row.terms) {
                    charge();
                    if (term.amount <= slack) continue;
                    long free = (term.positive | term.negative) & ~(one | zero);
                    zero |= term.positive & free;
                    one |= term.negative & free;
                }
            }
            changed = before != (one | zero);
        } while (changed);
        if ((one | zero) == all) return witness(one);
        if (conditionalConflicts != null && conditionalBound(one, zero)) {
            coloringPrunes++;
            open.pop();
            return false;
        }
        int[] score = new int[variables.length];
        for (Row row : rows) {
            charge();
            int slack = row.capacity - Long.bitCount(row.positive & one) - Long.bitCount(row.negative & zero);
            long free = (row.positive | row.negative) & ~(one | zero);
            if (Long.bitCount(free) <= slack) continue;
            for (long rest = free; rest != 0; rest &= rest - 1) {
                charge();
                score[Long.numberOfTrailingZeros(rest)] += slack == 1 ? 4 : 1;
            }
        }
        int best = -1;
        for (long rest = all & ~(one | zero); rest != 0; rest &= rest - 1) {
            charge();
            int id = Long.numberOfTrailingZeros(rest);
            if (best < 0 || score[id] > score[best]) best = id;
        }
        // Remove a frontier node only after all interruptible work has completed.
        open.pop();
        long bit = 1L << best;
        if (conditionalConflicts != null && !boundOnes) {
            open.push(new Node(one | bit, zero));
            open.push(new Node(one, zero | bit));
        } else {
            open.push(new Node(one, zero | bit));
            open.push(new Node(one | bit, zero));
        }
        return false;
    }

    private void prepareConditionalBound() {
        // A covering row also bounds the number of complementary choices.
        // Prefer a goal whose literals participate in small restrictive rows:
        // after assignments, a hyperedge can become a pairwise conflict even
        // when there was no conflict graph at the root.
        long best = 0;
        for (Row candidate : rows) {
            charge();
            if (candidate.positive != 0 && candidate.negative != 0) continue;
            boolean ones = candidate.positive == 0;
            long members = candidate.positive | candidate.negative;
            int size = Long.bitCount(members), required = size - candidate.capacity;
            if (size < 8 || required < 3) continue;
            long score = 0;
            for (Row row : rows) {
                charge();
                int shared = Long.bitCount(members & (ones ? row.positive : row.negative));
                if (shared >= 2 && row.capacity > 0 && row.capacity < shared && shared <= 8)
                    score += shared - row.capacity;
            }
            if (score > best && score >= size) {
                best = score;
                boundVariables = members;
                boundRequired = required;
                boundOnes = ones;
            }
        }
        if (best > 0) conditionalConflicts = new long[variables.length];
    }

    private boolean conditionalBound(long one, long zero) {
        int required = boundRequired - Long.bitCount(boundVariables & (boundOnes ? one : zero));
        if (required <= 1) return false;
        long free = boundVariables & ~(one | zero);
        Arrays.fill(conditionalConflicts, 0);
        for (Row row : rows) {
            charge();
            int used = Long.bitCount(row.positive & one) + Long.bitCount(row.negative & zero);
            if (row.capacity - used != 1) continue;
            long members = free & (boundOnes ? row.positive : row.negative);
            for (long rest = members; rest != 0; rest &= rest - 1) {
                charge();
                int id = Long.numberOfTrailingZeros(rest);
                conditionalConflicts[id] |= members & ~(1L << id);
            }
        }
        // Partition the remaining goal literals into conflict cliques. At most
        // one literal in each clique can be true under THIS node's assignments.
        // These conditional edges never escape the node or become root facts.
        int colors = 0;
        for (long left = free; left != 0;) {
            if (++colors >= required) return false;
            for (long rest = left; rest != 0;) {
                charge();
                int id = Long.numberOfTrailingZeros(rest);
                left &= ~(1L << id);
                rest &= conditionalConflicts[id];
            }
        }
        return true;
    }

    private boolean cliqueStep() {
        if (cliques.isEmpty()) return finish(true, "coloring_exhausted");
        CliqueNode node = cliques.peek();
        if (node.order == null) {
            nodes++;
            int size = Long.bitCount(node.available);
            int[] order = new int[size], colors = new int[size];
            int next = 0, color = 0;
            long left = node.available;
            // Each color class is independent in the compatibility graph, so
            // a clique can contain at most one member of each completed class.
            while (left != 0) {
                color++;
                long rest = left;
                while (rest != 0) {
                    charge();
                    int id = Long.numberOfTrailingZeros(rest);
                    long bit = 1L << id;
                    order[next] = id;
                    colors[next++] = color;
                    left &= ~bit;
                    rest &= ~bit & ~compatible[id];
                }
            }
            node.order = order;
            node.colors = colors;
            node.cursor = size - 1;
            return false;
        }
        int selected = Long.bitCount(node.chosen);
        if (node.cursor < 0 || selected + node.colors[node.cursor] < goal) {
            cliques.pop();
            return false;
        }
        int id = node.order[node.cursor--];
        long bit = 1L << id, chosen = node.chosen | bit;
        if (selected + 1 >= goal) return witness(chosen);
        long next = node.available & compatible[id];
        node.available &= ~bit;
        cliques.push(new CliqueNode(next, chosen));
        return false;
    }

    private boolean witness(long selected) {
        BigInteger[] result = lower.clone();
        for (long rest = selected; rest != 0; rest &= rest - 1)
            result[variables[Long.numberOfTrailingZeros(rest)]] = result[variables[Long.numberOfTrailingZeros(rest)]].add(BigInteger.ONE);
        for (var row : original) {
            BigInteger total = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                charge();
                total = total.add(term.getValue().multiply(result[term.getKey()]));
            }
            if (total.compareTo(row.upper()) > 0) {
                // A relaxed graph witness may violate additional cardinality
                // or weighted rows. Retain the same total quota and enumerate
                // the full cardinality space before making a negative claim.
                if (compatible != null) {
                    compatible = null;
                    cliques.clear();
                    open.push(new Node(0, 0));
                } else open.pop();
                return false;
            }
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
        budget.note("count_cardinality_search", reason + "; nodes=" + nodes + "; work=" + work + "; conditional_color_prunes=" + coloringPrunes);
        return true;
    }

    BigInteger[] counts() {
        return counts;
    }

    boolean infeasible() {
        return infeasible;
    }

    CountCardinalitySearch retained() {
        retaining = true;
        return this;
    }

    boolean paused() {
        return paused;
    }

    void resume(long quantum) {
        if (!retaining || complete || quantum <= 0) throw new IllegalStateException("Cardinality search cannot resume");
        if (budget.remainingWork() == 0) budget.check();
        allowance = work + Math.min(quantum, budget.remainingWork());
        paused = false;
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
        open.clear();
        cliques.clear();
    }
}
