package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Exact, bounded weighted hitting sets for soft cores; costs never pass through floating point. */
final class CountHittingSet implements AutoCloseable {

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private record Node(long chosen, long excluded, BigInteger cost, BigInteger bound) {}

    private static final class Component {

        final List<Long> cores;
        final Deque<Node> open = new ArrayDeque<>();
        long best;
        BigInteger cost, lower = BigInteger.ZERO;
        boolean seeded;

        Component(List<Long> cores, long best, BigInteger cost) {
            this.cores = cores;
            this.best = best;
            this.cost = cost;
            open.push(new Node(0, 0, BigInteger.ZERO, BigInteger.ZERO));
        }
    }

    private final PlanningBudget budget;
    private final BigInteger[] weights;
    private final long allowance;
    private final List<Component> components = new ArrayList<>();
    private long memory, work, branches, bounds;
    private int current;
    private boolean ready, complete, infeasible, optimal;

    CountHittingSet(List<BitSet> input, BigInteger[] weights, PlanningBudget budget, long maximumWork) {
        this.weights = weights.clone();
        this.budget = budget;
        allowance = Math.min(maximumWork, budget.remainingWork() / 16);
        if (weights.length > 64 || input.size() > 128 || allowance < 256) {
            complete = true;
            return;
        }
        for (var w : weights) if (w == null || w.signum() <= 0) throw new IllegalArgumentException("Nonpositive hitting-set weight");
        long bytes = 4096L + 2048L * weights.length + 256L * input.size();
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        try {
            var cores = new ArrayList<Long>();
            for (var bits : input) {
                charge();
                if (bits.length() > weights.length) throw new IllegalArgumentException("Hitting-set element outside domain");
                if (bits.isEmpty()) {
                    infeasible = true;
                    complete = true;
                    return;
                }
                long core = bits.toLongArray()[0];
                boolean subsumed = false;
                for (long old : cores) {
                    charge();
                    if ((old & core) == old) {
                        subsumed = true;
                        break;
                    }
                }
                if (subsumed) continue;
                cores.removeIf(old -> (old & core) == core);
                cores.add(core);
            }
            // Components are separated by SHARED SELECTORS, not recipe SCCs.
            // No constraint or objective term crosses the resulting boundary.
            while (!cores.isEmpty()) {
                long union = cores.remove(cores.size() - 1);
                var group = new ArrayList<Long>();
                group.add(union);
                boolean changed;
                do {
                    changed = false;
                    for (int j = cores.size() - 1; j >= 0; j--) {
                        charge();
                        long core = cores.get(j);
                        if ((core & union) == 0) continue;
                        group.add(core);
                        union |= core;
                        cores.remove(j);
                        changed = true;
                    }
                } while (changed);
                group.sort(Comparator.comparingInt(Long::bitCount));
                components.add(new Component(List.copyOf(group), union, cost(union)));
            }
            ready = true;
        } catch (Stop stopped) {
            complete = true;
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    boolean step() {
        if (complete) return true;
        try {
            charge();
            if (current == components.size()) {
                optimal = true;
                return finish("optimal");
            }
            Component part = components.get(current);
            if (!part.seeded) {
                long greedy = 0;
                while (uncovered(part, greedy)) greedy |= 1L << choose(part, greedy, 0, -1L);
                improve(part, greedy, cost(greedy));
                part.seeded = true;
            }
            Node node = part.open.peek();
            if (node == null) {
                part.lower = part.cost;
                current++;
                return false;
            }
            long chosen = node.chosen, excluded = node.excluded;
            BigInteger cost = node.cost;
            boolean dead = false;
            for (long core : part.cores) {
                charge();
                if ((core & chosen) != 0) continue;
                long rest = core & ~excluded;
                if (rest == 0) {
                    dead = true;
                    break;
                }
                if (Long.bitCount(rest) == 1) {
                    chosen |= rest;
                    cost = cost.add(weights[Long.numberOfTrailingZeros(rest)]);
                }
            }
            long branch = 0;
            if (!dead && cost.compareTo(part.cost) < 0) {
                for (long core : part.cores) {
                    charge();
                    if ((core & chosen) != 0) continue;
                    long rest = core & ~excluded;
                    if (branch == 0 || Long.bitCount(rest) < Long.bitCount(branch)) branch = rest;
                }
                if (branch == 0) improve(part, chosen, cost);
            }
            BigInteger bound = node.bound;
            int id = -1;
            if (!dead && branch != 0 && cost.compareTo(part.cost) < 0) {
                bound = bound.max(cost.add(packing(part, chosen, excluded)));
                if (bound.compareTo(part.cost) < 0) id = choose(part, chosen, excluded, branch);
            }
            // Keep the current node on the frontier until all metered work is
            // done, so interruption cannot accidentally certify a closed tree.
            part.open.pop();
            if (id >= 0) {
                long bit = 1L << id;
                part.open.push(new Node(chosen, excluded | bit, cost, bound));
                part.open.push(new Node(chosen | bit, excluded, cost.add(weights[id]), bound));
                branches++;
            }
            part.lower = part.cost;
            for (Node pending : part.open) part.lower = part.lower.min(pending.bound);
            return false;
        } catch (Stop stopped) {
            return finish("local_limit");
        }
    }

    /** A feasible dual packing, with independently recomputed capacity checks. */
    private BigInteger packing(Component part, long chosen, long excluded) {
        var remaining = weights.clone();
        var amounts = new ArrayList<BigInteger>();
        var sets = new ArrayList<Long>();
        for (long core : part.cores) {
            charge();
            if ((core & chosen) != 0) continue;
            long rest = core & ~excluded;
            BigInteger amount = null;
            for (long bits = rest; bits != 0; bits &= bits - 1) {
                charge();
                var value = remaining[Long.numberOfTrailingZeros(bits)];
                amount = amount == null ? value : amount.min(value);
            }
            if (amount == null) throw new IllegalStateException("Empty uncovered core");
            for (long bits = rest; bits != 0; bits &= bits - 1) {
                charge();
                int id = Long.numberOfTrailingZeros(bits);
                remaining[id] = remaining[id].subtract(amount);
            }
            sets.add(rest);
            amounts.add(amount);
        }
        BigInteger total = BigInteger.ZERO;
        var used = new BigInteger[weights.length];
        Arrays.fill(used, BigInteger.ZERO);
        for (int j = 0; j < sets.size(); j++) {
            BigInteger amount = amounts.get(j);
            if (amount.signum() < 0) throw new IllegalStateException("Negative core packing");
            total = total.add(amount);
            for (long bits = sets.get(j); bits != 0; bits &= bits - 1) {
                charge();
                int id = Long.numberOfTrailingZeros(bits);
                used[id] = used[id].add(amount);
                if (used[id].compareTo(weights[id]) > 0) throw new IllegalStateException("Core packing exceeds objective capacity");
            }
        }
        bounds++;
        return total;
    }

    private int choose(Component part, long chosen, long excluded, long allowed) {
        int[] cover = new int[weights.length];
        for (long core : part.cores) {
            charge();
            if ((core & chosen) != 0) continue;
            for (long bits = core & ~excluded & allowed; bits != 0; bits &= bits - 1) {
                charge();
                cover[Long.numberOfTrailingZeros(bits)]++;
            }
        }
        int best = -1;
        for (int i = 0; i < cover.length; i++) if (cover[i] > 0 && (best < 0 ||
                weights[i].multiply(BigInteger.valueOf(cover[best])).compareTo(weights[best].multiply(BigInteger.valueOf(cover[i]))) < 0))
            best = i;
        if (best < 0) throw new IllegalStateException("No uncovered hitting-set element");
        return best;
    }

    private boolean uncovered(Component part, long chosen) {
        for (long core : part.cores) {
            charge();
            if ((core & chosen) == 0) return true;
        }
        return false;
    }

    private BigInteger cost(long chosen) {
        BigInteger result = BigInteger.ZERO;
        for (long bits = chosen; bits != 0; bits &= bits - 1) {
            charge();
            result = result.add(weights[Long.numberOfTrailingZeros(bits)]);
        }
        return result;
    }

    private void improve(Component part, long chosen, BigInteger cost) {
        if (cost.compareTo(part.cost) < 0) {
            if (uncovered(part, chosen)) throw new IllegalStateException("Invalid hitting-set incumbent");
            part.best = chosen;
            part.cost = cost;
        }
    }

    private void charge() {
        budget.check();
        if (++work > allowance) throw new Stop();
    }

    private boolean finish(String detail) {
        complete = true;
        budget.note("count_hitting_set", detail + "; components=" + components.size() + "; branches=" + branches +
                "; packing_bounds=" + bounds + "; lower=" + lowerBound() + "; cost=" + cost() + "; work=" + work);
        return true;
    }

    BigInteger[] counts() {
        if (!ready || infeasible) return null;
        long chosen = 0;
        for (var part : components) chosen |= part.best;
        var result = new BigInteger[weights.length];
        for (int i = 0; i < result.length; i++) result[i] = (chosen & 1L << i) == 0 ? BigInteger.ZERO : BigInteger.ONE;
        return result;
    }

    BigInteger cost() {
        if (!ready || infeasible) return null;
        return components.stream().map(c -> c.cost).reduce(BigInteger.ZERO, BigInteger::add);
    }

    BigInteger lowerBound() {
        return ready ? components.stream().map(c -> c.lower).reduce(BigInteger.ZERO, BigInteger::add) : BigInteger.ZERO;
    }

    boolean optimal() {
        return optimal;
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
