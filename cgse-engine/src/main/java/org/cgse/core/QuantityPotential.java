// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Bounded resource-weight pricing. Only an exact full-catalog check publishes a certificate. */
final class QuantityPotential<K> implements AutoCloseable {
    private static final int MAX_COLUMNS = 384, MAX_ROUNDS = 32;
    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }

    private final RecipeCountModel<K> model;
    private final List<GraphRecipe<K>> recipes;
    private final PlanningBudget budget;
    private final long allowance;
    private final int targetRow;
    private final BitSet selected = new BitSet();
    private final CountNumericRelaxation.Session numerical = new CountNumericRelaxation.Session(true);
    private List<Map<Integer, BigInteger>> full;
    private BigInteger[] objective, costs;
    private List<ExactLinearProgram.Constraint> rows;
    private List<Integer> rowIds;
    private int[] columns, inverse;
    private CountNumericRelaxation.Result candidate;
    private Map<K, BigInteger> proposed, certificate = Map.of();
    private int phase, index, rounds, precision;
    private long memory, work, visit;

    static <K> boolean applicable(RecipeCountModel<K> model, K target, PlanningBudget budget) {
        return model.rowKeys.size() == model.constraints.size() && model.ids.containsKey(target) &&
                !model.external.contains(target) && model.goal(target).compareTo(BigInteger.valueOf(model.stock.getOrDefault(target, 0L))) > 0 &&
                model.constraints.size() <= 8192 && model.recipes.size() <= 8192 && budget.remainingWork() >= 131_072;
    }

    static long workAllowance(PlanningBudget budget) { return Math.min(1_500_000, budget.remainingWork() / 8); }

    QuantityPotential(RecipeCountModel<K> model, K target, List<GraphRecipe<K>> recipes, PlanningBudget budget) {
        this.model = model;
        this.recipes = recipes;
        this.budget = budget;
        budget.charge(model.rowKeys.size());
        targetRow = model.rowKeys.indexOf(target);
        allowance = workAllowance(budget);
        if (!applicable(model, target, budget)) phase = 6;
    }

    boolean step() {
        if (phase == 6) return true;
        visit = budget.threadWork();
        try {
            check();
            return advance();
        } catch (Stop | ExactRational.PrecisionLimit limit) {
            return finish();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        } finally {
            work += budget.threadWork() - visit;
        }
    }

    private boolean advance() {
        if (phase == 0) {
            if (full == null) {
                long terms = 0;
                for (var row : model.constraints) { check(); terms += row.terms().size(); }
                if (terms > 65536) return finish();
                // Original transpose, one projected matrix, pricing vectors and
                // bounded integer proposals. The numeric session owns its bases.
                long bytes = 4096L + 384L * (terms + model.constraints.size() + model.recipes.size());
                if (!budget.tryReserve(bytes)) return finish();
                memory = bytes;
                full = new ArrayList<>();
                for (var recipe : model.recipes) { check(); full.add(new LinkedHashMap<>()); }
                objective = new BigInteger[model.constraints.size()];
            }
            for (int end = Math.min(model.constraints.size(), index + 32); index < end; index++) {
                check();
                objective[index] = BigInteger.valueOf(model.stock.getOrDefault(model.rowKeys.get(index), 0L)).negate();
                for (var term : model.constraints.get(index).terms().entrySet()) {
                    check();
                    if (term.getValue().signum() != 0) full.get(term.getKey()).put(index, term.getValue().negate());
                }
            }
            if (index < model.constraints.size()) return false;
            // Normalizing the target avoids a tiny numerical point when stock
            // and demand are long-sized. The objective only proposes weights;
            // all current delivery and seed goals enter exact validation below.
            full.add(Map.of(targetRow, BigInteger.ONE.negate()));
            full.add(Map.of(targetRow, BigInteger.ONE));
            selected.set(targetRow);
            phase = 1;
            index = 0;
        }
        if (phase == 1) {
            if (rows == null) {
                if (rounds >= MAX_ROUNDS || selected.cardinality() > MAX_COLUMNS) return finish();
                columns = selected.stream().toArray();
                inverse = new int[objective.length];
                budget.charge(inverse.length);
                Arrays.fill(inverse, -1);
                costs = new BigInteger[columns.length];
                for (int i = 0; i < columns.length; i++) { check(); inverse[columns[i]] = i; costs[i] = objective[columns[i]]; }
                rows = new ArrayList<>();
                rowIds = new ArrayList<>();
                rounds++;
            }
            for (int end = Math.min(full.size(), index + 32); index < end; index++) {
                check();
                Map<Integer, BigInteger> projected = new LinkedHashMap<>();
                for (var term : full.get(index).entrySet()) {
                    check();
                    if (inverse[term.getKey()] >= 0) projected.put(inverse[term.getKey()], term.getValue());
                }
                if (projected.isEmpty()) continue;
                rowIds.add(index);
                rows.add(new ExactLinearProgram.Constraint(projected, index == full.size() - 2 ? BigInteger.ONE.negate() :
                        index == full.size() - 1 ? BigInteger.ONE : BigInteger.ZERO));
                if (rows.size() > 2048) return finish();
            }
            if (index < full.size()) return false;
            phase = 2;
        }
        if (phase == 2) {
            long remaining = allowance - work - (budget.threadWork() - visit);
            if (remaining < 1024) return finish();
            candidate = numerical.solve(columns.length, rows, costs, budget, Math.min(16_384, remaining));
            if (candidate == null) return numerical.pending() ? false : finish();
            precision = 0;
            phase = candidate.phaseOneInfeasible() ? 4 : 3;
            return false;
        }
        if (phase == 3) {
            if (precision == 3) { phase = 4; return false; }
            proposed = weights(new double[]{1e-8, 1e-10, 1e-12}[precision++]);
            if (proposed != null && deficit(proposed)) {
                index = 0;
                phase = 5;
            }
            return false;
        }
        if (phase == 4) {
            double[] prices = new double[objective.length];
            for (int i = 0; i < rowIds.size(); i++) {
                check();
                double weight = candidate.dual()[i];
                if (!Double.isFinite(weight) || weight < -1e-8) return finish();
                if (weight <= 0) continue;
                for (var term : full.get(rowIds.get(i)).entrySet()) {
                    check();
                    prices[term.getKey()] += weight * term.getValue().doubleValue();
                }
            }
            var improving = new ArrayList<Integer>();
            for (int i = 0; i < prices.length; i++) {
                check();
                if (!candidate.phaseOneInfeasible()) prices[i] -= objective[i].doubleValue();
                if (!selected.get(i) && Double.isFinite(prices[i]) && prices[i] < -1e-10) improving.add(i);
            }
            improving.sort((a, b) -> {
                check();
                int compared = Double.compare(prices[a], prices[b]);
                return compared != 0 ? compared : Integer.compare(a, b);
            });
            if (improving.isEmpty()) return finish();
            for (int i : improving.subList(0, Math.min(16, improving.size()))) selected.set(i);
            numerical.close();
            candidate = null;
            rows = null;
            rowIds = null;
            proposed = null;
            phase = 1;
            index = 0;
            return false;
        }
        if (phase == 5) {
            // A projected optimum is only a proposal. Include every allowed
            // original producer, even one absent from this count closure.
            for (int end = Math.min(recipes.size(), index + 32); index < end; index++) {
                var recipe = recipes.get(index);
                BigInteger delta = BigInteger.ZERO;
                for (var output : recipe.outputs().entrySet()) {
                    check();
                    BigInteger weight = proposed.get(output.getKey());
                    if (weight != null) delta = delta.add(product(weight, output.getValue()));
                }
                for (var input : recipe.inputs().entrySet()) {
                    check();
                    BigInteger weight = proposed.get(input.getKey());
                    if (weight != null) delta = delta.subtract(product(weight, input.getValue() -
                            recipe.configurationInputs().getOrDefault(input.getKey(), 0L) +
                            recipe.reusableInputs().getOrDefault(input.getKey(), 0L)));
                }
                if (delta.signum() > 0) { phase = 3; return false; }
            }
            if (index < recipes.size()) return false;
            certificate = Map.copyOf(proposed);
            return finish();
        }
        return phase == 6;
    }

    private Map<K, BigInteger> weights(double tolerance) {
        var fractions = new LinkedHashMap<K, long[]>();
        BigInteger denominator = BigInteger.ONE;
        for (int i = 0; i < columns.length; i++) {
            check();
            double value = candidate.point()[i];
            if (!Double.isFinite(value) || value < -1e-8 || value > 1e9) return null;
            // Clamping numerical noise is only part of the proposal. All rows
            // and the stock deficit are checked again with exact integers.
            long[] fraction = fraction(Math.max(0, value), tolerance);
            if (fraction == null) return null;
            if (fraction[0] == 0) continue;
            fractions.put(model.rowKeys.get(columns[i]), fraction);
            BigInteger next = BigInteger.valueOf(fraction[1]);
            denominator = denominator.divide(denominator.gcd(next)).multiply(next);
            if (fractions.size() > 128 || denominator.bitLength() > 512) return null;
        }
        var result = new LinkedHashMap<K, BigInteger>();
        BigInteger gcd = BigInteger.ZERO;
        for (var entry : fractions.entrySet()) {
            check();
            long[] fraction = entry.getValue();
            BigInteger value = denominator.divide(BigInteger.valueOf(fraction[1])).multiply(BigInteger.valueOf(fraction[0]));
            result.put(entry.getKey(), value);
            gcd = gcd.gcd(value);
        }
        if (gcd.signum() > 0) for (var entry : result.entrySet()) { check(); entry.setValue(entry.getValue().divide(gcd)); }
        return result;
    }

    private long[] fraction(double value, double tolerance) {
        double remainder = value;
        long p0 = 0, p1 = 1, q0 = 1, q1 = 0;
        for (int i = 0; i < 32; i++) {
            check();
            double whole = Math.floor(remainder);
            if (!Double.isFinite(whole) || whole > 1e9) return null;
            long part = (long) whole;
            if (q1 != 0 && part > (1_000_000 - q0) / q1 || part != 0 && p1 > (Long.MAX_VALUE - p0) / part) return null;
            long p = part * p1 + p0, q = part * q1 + q0;
            if (Math.abs(value - (double) p / q) <= tolerance * Math.max(1, value)) return new long[]{p, q};
            p0 = p1; p1 = p; q0 = q1; q1 = q;
            remainder = 1 / (remainder - whole);
        }
        return null;
    }

    private boolean deficit(Map<K, BigInteger> weights) {
        BigInteger available = BigInteger.ZERO, required = BigInteger.ZERO;
        for (var entry : weights.entrySet()) {
            check();
            if (entry.getValue().signum() < 0 || model.external.contains(entry.getKey())) return false;
            available = available.add(product(entry.getValue(), model.stock.getOrDefault(entry.getKey(), 0L)));
            budget.charge((entry.getValue().bitLength() + 63L) / 64);
            required = required.add(entry.getValue().multiply(model.goal(entry.getKey())));
        }
        return available.compareTo(required) < 0;
    }

    private BigInteger product(BigInteger weight, long amount) {
        budget.charge((weight.bitLength() + 63L) / 64);
        return weight.multiply(BigInteger.valueOf(amount));
    }

    private void check() {
        budget.check();
        if (work + budget.threadWork() - visit >= allowance) throw new Stop();
    }

    private boolean finish() {
        budget.note("quantity_potential", "certified=" + !certificate.isEmpty() + "; materials=" + certificate.size() +
                "; selected=" + selected.cardinality() + "; rounds=" + rounds + "; resumes=" + numerical.resumed() +
                "; work=" + (work + budget.threadWork() - visit));
        close();
        return true;
    }

    Map<K, BigInteger> certificate() { return certificate; }

    @Override
    public void close() {
        numerical.close();
        full = null;
        rows = null;
        rowIds = null;
        objective = costs = null;
        columns = inverse = null;
        candidate = null;
        proposed = null;
        budget.release(memory);
        memory = 0;
        phase = 6;
    }
}
