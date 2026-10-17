// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Search in one proposed Boolean face. Neither its fixed exterior nor its
 * distance row is an original-model fact; local failures and clauses stay here. */
final class CountPackingNeighborhood implements AutoCloseable {
    enum Mode { PROPAGATION, LEARNING, EXCHANGE, EXCHANGE_FOUR }

    private final PlanningBudget budget;
    private CountPackingSearch propagation;
    private CountPackingExchange exchange;
    private CountCdcl learning;
    private long memory, assignment;
    private boolean complete, feasible;

    CountPackingNeighborhood(long[][] coefficients, long[] bounds, long seed,
                             PlanningBudget budget, boolean learned) {
        this(coefficients, bounds, seed, budget, learned ? Mode.LEARNING : Mode.PROPAGATION);
    }
    CountPackingNeighborhood(long[][] coefficients, long[] bounds, long seed, PlanningBudget budget, Mode mode) {
        this(coefficients, bounds, seed, budget, mode, 0);
    }
    CountPackingNeighborhood(long[][] coefficients, long[] bounds, long seed, PlanningBudget budget, Mode mode, long flexible) {
        this.budget = budget;
        if (mode == Mode.EXCHANGE || mode == Mode.EXCHANGE_FOUR) {
            exchange = new CountPackingExchange(coefficients, bounds, seed, budget, mode == Mode.EXCHANGE_FOUR ? 4 : 3, flexible);
            return;
        }
        if (mode == Mode.PROPAGATION) {
            propagation = new CountPackingSearch(coefficients, bounds, seed, budget);
            return;
        }
        if (coefficients.length == 0) throw new IllegalArgumentException("Empty packing neighborhood");
        int n = coefficients[0].length;
        if (n < 1 || n > 64 || coefficients.length != bounds.length || bounds.length > 35)
            throw new IllegalArgumentException("Packing neighborhood shape");
        // Reserve the sparse reconstruction before allocating it. The PB
        // solver separately owns its watches, explanations and learned clauses.
        long bytes = 2048L + 128L * n + (256L + 128L * n) * bounds.length;
        if (!budget.tryReserve(bytes)) { complete = true; return; }
        memory = bytes;
        try {
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int r = 0; r < coefficients.length; r++) {
                if (coefficients[r].length != n) throw new IllegalArgumentException("Packing row width");
                var terms = new TreeMap<Integer, BigInteger>();
                for (int i = 0; i < n; i++) {
                    budget.check();
                    if (coefficients[r][i] != 0) terms.put(i, BigInteger.valueOf(coefficients[r][i]));
                }
                rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(bounds[r])));
            }
            var lower = new BigInteger[n]; var upper = new BigInteger[n];
            Arrays.fill(lower, BigInteger.ZERO); Arrays.fill(upper, BigInteger.ONE);
            learning = new CountCdcl(rows, lower, upper, budget, 262144).retained();
            learning.preferPhases(seed);
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    boolean step() {
        if (complete) return true;
        if (propagation != null) return propagation.step();
        if (exchange != null) return exchange.step();
        if (!learning.step()) return false;
        if (learning.paused()) {
            learning.resume(262144);
            return false;
        }
        var values = learning.counts();
        feasible = values != null;
        if (values != null) {
            budget.operations(PlanningBudget.Operation.SCAN, 0, values.length);
            for (int i = 0; i < values.length; i++) if (values[i].signum() != 0) assignment |= 1L << i;
        }
        complete = true;
        return true;
    }

    boolean feasible() { return exchange != null ? exchange.feasible() : propagation != null ? propagation.feasible() : feasible; }
    long assignment() {
        if (!feasible()) throw new IllegalStateException("No neighborhood witness");
        return exchange != null ? exchange.assignment() : propagation != null ? propagation.assignment() : assignment;
    }
    long forcedValues() { return propagation == null ? 0 : propagation.forcedValues(); }

    @Override public void close() {
        if (propagation != null) propagation.close();
        propagation = null;
        if (exchange != null) exchange.close();
        exchange = null;
        if (learning != null) learning.close();
        learning = null;
        budget.release(memory); memory = 0;
    }
}
