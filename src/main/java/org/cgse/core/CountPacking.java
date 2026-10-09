// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Optional bounded 0/1 packing witness with an exact surrogate fractional bound. */
final class CountPacking implements AutoCloseable {

    private final PlanningBudget budget;
    private final List<ExactLinearProgram.Constraint> original;
    private final BigInteger[] lower, upper;
    private final long allowance;
    private long work, memory, nodes;
    private boolean complete;
    private int[] variables, order, stage;
    private boolean[] selected;
    private BigInteger[] profit, cost, capacity, earned, room, counts;
    private BigInteger[][] weights, remaining;
    private BigInteger goal;
    private double[][] approximate;
    private double[] prices, multipliers, bestMultipliers;
    private double bestPrice = Double.POSITIVE_INFINITY;
    private int priceSweeps;
    private int coordinate, depth;

    CountPacking(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, PlanningBudget budget) {
        this.budget = budget;
        this.original = rows;
        this.lower = lower;
        this.upper = upper;
        allowance = Math.min(2_000_000, budget.remainingWork() / 6);
        if (lower.length > 256 || rows.size() > 1024 || allowance < 4096) {
            complete = true;
            return;
        }
        var free = new ArrayList<Integer>();
        for (int i = 0; i < lower.length; i++) {
            if (lower[i].equals(upper[i])) continue;
            // An extra unit of stock may permit more executions. Keeping a
            // 0/1 witness face is still valid; its failure proves nothing about
            // the larger domain, which remains in the other strategies.
            if (lower[i].signum() != 0 || upper[i] == null || upper[i].compareTo(BigInteger.ONE) < 0) {
                complete = true;
                return;
            }
            free.add(i);
        }
        if (free.size() < 4 || free.size() > 128) {
            complete = true;
            return;
        }
        // Equivalent sources need not create another binary branch. This is
        // only a witness face: multiple uses of a shared column remain in the
        // original integer model. Unary rows are already represented by bounds.
        Set<List<BigInteger>> columns = new HashSet<>();
        var representatives = new ArrayList<Integer>();
        for (int id : free) {
            List<BigInteger> column = new ArrayList<>();
            for (var row : rows) if (row.terms().size() > 1) {
                charge();
                column.add(row.terms().getOrDefault(id, BigInteger.ZERO));
            }
            if (columns.add(column)) representatives.add(id);
        }
        free = representatives;
        variables = free.stream().mapToInt(Integer::intValue).toArray();
        int[] indices = new int[lower.length];
        Arrays.fill(indices, -1);
        for (int i = 0; i < variables.length; i++) indices[variables[i]] = i;
        Map<Map<Integer, BigInteger>, BigInteger> unique = new LinkedHashMap<>();
        for (var row : rows) {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            BigInteger rhs = row.upper(), maximum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                charge();
                int id = indices[term.getKey()];
                if (id < 0) rhs = rhs.subtract(term.getValue().multiply(lower[term.getKey()]));
                else {
                    terms.put(id, term.getValue());
                    maximum = maximum.add(term.getValue().max(BigInteger.ZERO));
                }
            }
            if (maximum.compareTo(rhs) <= 0) continue;
            var normalized = CountReduction.normalize(new ExactLinearProgram.Constraint(terms, rhs));
            unique.merge(normalized.terms(), normalized.upper(), BigInteger::min);
        }
        var capacities = new ArrayList<BigInteger[]>();
        var limits = new ArrayList<BigInteger>();
        for (var entry : unique.entrySet()) {
            boolean positive = entry.getKey().values().stream().allMatch(v -> v.signum() > 0);
            boolean negative = entry.getKey().values().stream().allMatch(v -> v.signum() < 0);
            if (entry.getKey().isEmpty() || !positive && !negative) {
                complete = true;
                return;
            }
            BigInteger[] row = new BigInteger[variables.length];
            Arrays.fill(row, BigInteger.ZERO);
            entry.getKey().forEach((id, value) -> row[id] = value.abs());
            if (negative) {
                if (profit != null) {
                    complete = true;
                    return;
                }
                profit = row;
                goal = entry.getValue().negate();
            } else {
                if (entry.getValue().signum() < 0) {
                    complete = true;
                    return;
                }
                capacities.add(row);
                limits.add(entry.getValue());
            }
        }
        if (profit == null || goal.signum() <= 0 || capacities.isEmpty() || capacities.size() > 64) {
            complete = true;
            return;
        }
        var indicesByTightness = new ArrayList<Integer>();
        var totals = new ArrayList<BigInteger>();
        var signatures = new ArrayList<List<BigInteger>>();
        for (int r = 0; r < capacities.size(); r++) {
            indicesByTightness.add(r);
            totals.add(Arrays.stream(capacities.get(r)).reduce(BigInteger.ZERO, BigInteger::add));
            List<Integer> ids = new ArrayList<>();
            for (int j = 0; j < variables.length; j++) ids.add(j);
            var vector = capacities.get(r);
            ids.sort(Comparator.comparing((Integer j) -> profit[j]).thenComparing(j -> vector[j]));
            List<BigInteger> signature = new ArrayList<>();
            for (int j : ids) {
                signature.add(profit[j]);
                signature.add(vector[j]);
            }
            signatures.add(signature);
        }
        indicesByTightness.sort((a, b) -> {
            int c = limits.get(a).multiply(totals.get(b)).compareTo(limits.get(b).multiply(totals.get(a)));
            if (c != 0) return c;
            for (int i = 0; i < signatures.get(a).size(); i++) {
                c = signatures.get(a).get(i).compareTo(signatures.get(b).get(i));
                if (c != 0) return c;
            }
            return limits.get(a).compareTo(limits.get(b));
        });
        weights = indicesByTightness.stream().map(capacities::get).toArray(BigInteger[][]::new);
        capacity = indicesByTightness.stream().map(limits::get).toArray(BigInteger[]::new);
        long bytes = 4096 + 256L * variables.length * (weights.length + 1L) + 128L * rows.size();
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        approximate = new double[weights.length][variables.length];
        for (int r = 0; r < weights.length; r++) for (int j = 0; j < variables.length; j++)
            approximate[r][j] = weights[r][j].doubleValue();
        prices = new double[variables.length];
        multipliers = new double[weights.length];
        priceSweeps = Arrays.stream(profit).allMatch(v -> v.compareTo(BigInteger.ONE) <= 0) ? 8 : 32;
        budget.note("count_packing", "candidate; choices=" + variables.length + "; capacities=" + weights.length);
    }

    boolean step() {
        if (complete) return true;
        if (work >= allowance) return finish("work_limit");
        if (coordinate < 3 * priceSweeps * weights.length) {
            int round = coordinate / (priceSweeps * weights.length), position = coordinate++ % weights.length;
            proposePrices(round == 0 ? position : round == 1 ? weights.length - 1 - position : (position + weights.length / 2) % weights.length);
            if (coordinate % (priceSweeps * weights.length) == 0) {
                double score = 0;
                for (int r = 0; r < weights.length; r++) score += multipliers[r] * capacity[r].doubleValue();
                for (int j = 0; j < variables.length; j++) score += Math.max(0, profit[j].doubleValue() - prices[j]);
                if (Double.isFinite(score) && score < bestPrice) {
                    bestPrice = score;
                    bestMultipliers = multipliers.clone();
                }
                Arrays.fill(multipliers, 0);
                Arrays.fill(prices, 0);
                if (round == 2 && bestMultipliers != null) multipliers = bestMultipliers;
            }
            return false;
        }
        if (order == null) {
            prepareSearch();
            return false;
        }
        if (depth < 0) return finish("exhausted_candidate");
        charge();
        if (stage[depth] == 0) {
            nodes++;
            if (earned[depth].compareTo(goal) >= 0) {
                BigInteger[] value = lower.clone();
                for (int i = 0; i < depth; i++) if (selected[i]) value[variables[order[i]]] = BigInteger.ONE;
                if (valid(value)) {
                    counts = value;
                    return finish("witness");
                }
                return finish("unresolved_candidate");
            }
            BigInteger maximum = earned[depth], available = room[depth];
            for (int i = depth; i < order.length; i++) {
                charge();
                int id = order[i];
                if (cost[id].compareTo(available) <= 0) {
                    available = available.subtract(cost[id]);
                    maximum = maximum.add(profit[id]);
                } else {
                    maximum = maximum.add(profit[id].multiply(available).divide(cost[id]));
                    break;
                }
            }
            if (maximum.compareTo(goal) < 0 || depth == order.length) {
                depth--;
                return false;
            }
            stage[depth] = 1;
        }
        int id = order[depth];
        if (stage[depth] == 1) {
            stage[depth] = 2;
            boolean fits = true;
            for (int r = 0; r < weights.length; r++) {
                charge();
                if (weights[r][id].compareTo(remaining[depth][r]) > 0) {
                    fits = false;
                    break;
                }
            }
            if (fits) {
                remaining[depth + 1] = new BigInteger[weights.length];
                for (int r = 0; r < weights.length; r++) remaining[depth + 1][r] = remaining[depth][r].subtract(weights[r][id]);
                earned[depth + 1] = earned[depth].add(profit[id]);
                room[depth + 1] = room[depth].subtract(cost[id]);
                selected[depth] = true;
                stage[++depth] = 0;
                return false;
            }
        }
        if (stage[depth] == 2) {
            stage[depth] = 3;
            selected[depth] = false;
            remaining[depth + 1] = remaining[depth];
            earned[depth + 1] = earned[depth];
            room[depth + 1] = room[depth];
            stage[++depth] = 0;
        } else depth--;
        return false;
    }

    private void proposePrices(int r) {
        // Floating point proposes nonnegative resource weights only. Every
        // subsequent ordering, bound, branch and accepted vector is exact.
        List<Integer> choices = new ArrayList<>();
        double[] ratios = new double[variables.length];
        for (int j = 0; j < variables.length; j++) {
            charge();
            if (approximate[r][j] <= 0) continue;
            double residual = Math.max(0, profit[j].doubleValue() - prices[j] + multipliers[r] * approximate[r][j]);
            ratios[j] = residual / approximate[r][j];
            if (!Double.isFinite(ratios[j])) return;
            choices.add(j);
        }
        choices.sort(Comparator.comparingDouble((Integer j) -> ratios[j]).reversed());
        double used = 0, next = 0;
        for (int j : choices) {
            used += approximate[r][j];
            if (used >= capacity[r].doubleValue()) {
                next = ratios[j];
                break;
            }
        }
        double change = next - multipliers[r];
        multipliers[r] = next;
        for (int j = 0; j < variables.length; j++) {
            charge();
            prices[j] += change * approximate[r][j];
        }
    }

    private void prepareSearch() {
        BigInteger[] multipliers = new BigInteger[weights.length];
        boolean positive = false;
        for (int r = 0; r < weights.length; r++) {
            long value = Math.round(Math.min(1_000_000_000, Math.max(0, this.multipliers[r] * 10000)));
            multipliers[r] = BigInteger.valueOf(value);
            positive |= value > 0;
        }
        if (!positive) Arrays.fill(multipliers, BigInteger.ONE);
        cost = new BigInteger[variables.length];
        Arrays.fill(cost, BigInteger.ZERO);
        BigInteger total = BigInteger.ZERO;
        for (int r = 0; r < weights.length; r++) {
            total = total.add(multipliers[r].multiply(capacity[r]));
            for (int j = 0; j < variables.length; j++) {
                charge();
                cost[j] = cost[j].add(multipliers[r].multiply(weights[r][j]));
            }
        }
        Integer[] sorted = new Integer[variables.length];
        Arrays.setAll(sorted, i -> i);
        Arrays.sort(sorted, (a, b) -> {
            // Zero-profit coordinates never help the witness. In particular,
            // 0/0 must not compare equal to every positive ratio: that breaks
            // comparator transitivity and the fractional upper bound's order.
            if (profit[a].signum() == 0 || profit[b].signum() == 0)
                return profit[a].signum() == profit[b].signum() ? Integer.compare(a, b) :
                        profit[a].signum() == 0 ? 1 : -1;
            int comparison = profit[b].multiply(cost[a]).compareTo(profit[a].multiply(cost[b]));
            return comparison == 0 ? Integer.compare(a, b) : comparison;
        });
        order = Arrays.stream(sorted).mapToInt(Integer::intValue).toArray();
        stage = new int[order.length + 1];
        selected = new boolean[order.length];
        earned = new BigInteger[order.length + 1];
        room = new BigInteger[earned.length];
        remaining = new BigInteger[earned.length][];
        earned[0] = BigInteger.ZERO;
        room[0] = total;
        remaining[0] = capacity;
    }

    private boolean valid(BigInteger[] value) {
        for (int i = 0; i < value.length; i++) if (value[i].compareTo(lower[i]) < 0 || value[i].compareTo(upper[i]) > 0) return false;
        for (var row : original) {
            BigInteger total = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                charge();
                total = total.add(term.getValue().multiply(value[term.getKey()]));
            }
            if (total.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private void charge() {
        budget.check();
        work++;
    }

    private boolean finish(String reason) {
        complete = true;
        budget.note("count_packing", reason + "; nodes=" + nodes + "; work=" + work + "; original_domain_retained");
        return true;
    }

    BigInteger[] counts() {
        return counts;
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
