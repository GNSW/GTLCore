// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Optional factorized stock view for a fixed number of firings. Only its positive allocations
 * escape, as ordinary canonical variants; it cannot prove the crafting request
 * impossible. All slots spend from the same physical resource row.
 */
final class CapturedInputAllocation<K> implements AutoCloseable {

    private record Variable(int slot, int candidate) {}

    private final CapturedRecipe<K> recipe;
    private final CapturedRecipeDomain<K> domain;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final PlanningBudget budget;
    private final long firings;
    private final List<Variable> variables = new ArrayList<>();
    private final List<BigInteger> upper = new ArrayList<>();
    private final List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
    private final Map<K, Map<Integer, BigInteger>> resources = new LinkedHashMap<>();
    private Map<Integer, BigInteger> slotTerms = new LinkedHashMap<>();
    private CountLcg search;
    private int slot, candidate;
    private long memory;
    private boolean built, done;
    private List<BigInteger> results = List.of();
    private CapturedAllocationRecovery<K> recovery;

    CapturedInputAllocation(CapturedRecipe<K> recipe, CapturedRecipeDomain<K> domain, Map<K, Long> stock,
                           Set<K> external, PlanningBudget budget) {
        this(recipe, domain, stock, external, 1, budget);
    }

    CapturedInputAllocation(CapturedRecipe<K> recipe, CapturedRecipeDomain<K> domain, Map<K, Long> stock,
                           Set<K> external, long firings, PlanningBudget budget) {
        if (firings <= 0) throw new IllegalArgumentException("Non-positive firing count");
        this.recipe = recipe;
        this.domain = domain;
        this.stock = stock;
        this.external = external;
        this.budget = budget;
        this.firings = firings;
        if (recipe.inputs().size() > 256 || budget.remainingWork() < 8192 || !reserve(512)) done = true;
    }

    boolean step() {
        if (done) return true;
        if (recovery != null) {
            if (recovery.step()) {
                results = recovery.results(); recovery.close(); recovery = null; done = true;
            }
        } else if (search != null) {
            budget.phase(PlanningBudget.Phase.SOLVE);
            if (!search.step()) return false;
            var counts = search.counts();
            if (counts != null) {
                if (!reserve(256L * recipe.inputs().size() + 128L * counts.length)) { done = true; return true; }
                var slots = new ArrayList<Map<Integer, BigInteger>>();
                for (int i = 0; i < recipe.inputs().size(); i++) {
                    budget.compilationCheck(); slots.add(new LinkedHashMap<>());
                }
                for (int i = 0; i < counts.length; i++) {
                    budget.compilationCheck(); var variable = variables.get(i);
                    slots.get(variable.slot()).put(variable.candidate(), counts[i]);
                }
                search.close(); search = null;
                recovery = new CapturedAllocationRecovery<>(recipe, domain, BigInteger.valueOf(firings), slots, budget);
            } else done = true;
        } else if (!built) {
            budget.compilationCheck();
            if (slot < recipe.inputs().size()) buildSlot();
            else {
                for (var e : resources.entrySet()) {
                    budget.compilationCheck();
                    rows.add(new ExactLinearProgram.Constraint(e.getValue(), BigInteger.valueOf(stock.getOrDefault(e.getKey(), 0L))));
                }
                built = true;
            }
        } else {
            budget.phase(PlanningBudget.Phase.SOLVE);
            BigInteger[] low = new BigInteger[variables.size()];
            Arrays.fill(low, BigInteger.ZERO);
            search = new CountLcg(rows, low, upper.toArray(BigInteger[]::new), budget,
                    Math.min(32768, budget.remainingWork() / 8));
        }
        return done;
    }

    private void buildSlot() {
        var input = recipe.inputs().get(slot);
        boolean mix = !recipe.external() && input.multiplier() <= 9;
        if (candidate < input.candidates().size()) {
            int at = candidate++;
            var value = input.candidates().get(at);
            // Configuration consumption follows dispatches rather than counts;
            // read arcs and any coupled semantics stay in the canonical path.
            if (value.configuration() || value.reusable()) { done = true; return; }
            long copies = mix ? 1 : input.multiplier();
            BigInteger amount = BigInteger.valueOf(value.stack().amount()).multiply(BigInteger.valueOf(copies));
            if (amount.signum() <= 0 || amount.bitLength() > 63) return;
            if (!external.contains(value.stack().what()) && amount.compareTo(BigInteger.valueOf(stock.getOrDefault(value.stack().what(), 0L))) > 0) return;
            if (variables.size() >= 1024 || !reserve(768)) { done = true; return; }
            int id = variables.size();
            variables.add(new Variable(slot, at));
            BigInteger maximum = BigInteger.valueOf(mix ? input.multiplier() : 1).multiply(BigInteger.valueOf(firings));
            if (!external.contains(value.stack().what())) maximum = maximum.min(BigInteger.valueOf(stock.getOrDefault(value.stack().what(), 0L)).divide(amount));
            upper.add(maximum);
            slotTerms.put(id, BigInteger.ONE);
            if (!external.contains(value.stack().what())) resources.computeIfAbsent(value.stack().what(), ignored -> new LinkedHashMap<>()).put(id, amount);
        } else {
            if (slotTerms.isEmpty() || !reserve(256)) { done = true; return; }
            BigInteger total = BigInteger.valueOf(mix ? input.multiplier() : 1).multiply(BigInteger.valueOf(firings));
            rows.add(new ExactLinearProgram.Constraint(slotTerms, total));
            Map<Integer, BigInteger> negative = new LinkedHashMap<>();
            slotTerms.forEach((id, coefficient) -> negative.put(id, coefficient.negate()));
            rows.add(new ExactLinearProgram.Constraint(negative, total.negate()));
            slotTerms = new LinkedHashMap<>();
            slot++;
            candidate = 0;
        }
    }

    BigInteger result() { return results.isEmpty() ? null : results.get(0); }

    List<BigInteger> results() { return List.copyOf(results); }

    private boolean reserve(long bytes) {
        if (!budget.tryReserve(bytes)) return false;
        memory += bytes;
        return true;
    }

    @Override
    public void close() {
        if (search != null) search.close();
        search = null;
        if (recovery != null) recovery.close();
        recovery = null;
        budget.release(memory);
        memory = 0;
        done = true;
        variables.clear();
        upper.clear();
        rows.clear();
        resources.clear();
        slotTerms.clear();
    }
}
