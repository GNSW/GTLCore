// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.function.LongConsumer;

/** Optional recovery/burn allocation over verified, raw-fed programs. Witness only. */
final class CountRecoveryFuel<K> {

    private record Route<K>(String id, GraphRecipe<K> returned, GraphRecipe<K> burned,
                            K catalyst, long catalystUnits, K fuel, long fuelUnits) {}

    private final RecipeCountModel<K> original;
    private final PlanningBudget budget;
    private final List<Route<K>> routes = new ArrayList<>();
    private final Map<String, GraphRecipe<K>> recipes = new LinkedHashMap<>();

    private CountRecoveryFuel(RecipeCountModel<K> original, PlanningBudget budget) {
        this.original = original;
        this.budget = budget;
    }

    static <K> CountRecoveryFuel<K> compile(RecipeCountModel<K> model, Collection<GraphRecipe<K>> programs, PlanningBudget budget) {
        var view = new CountRecoveryFuel<>(model, budget);
        return view.prepare(programs) ? view : null;
    }

    private boolean prepare(Collection<GraphRecipe<K>> programs) {
        long started = budget.threadWork(), allowance = Math.min(65536, budget.remainingWork() / 16);
        Set<K> produced = new HashSet<>();
        Map<Map<K, Long>, List<GraphRecipe<K>>> byInputs = new HashMap<>();
        for (var recipe : programs) {
            budget.check();
            recipes.put(recipe.id(), recipe);
            produced.addAll(recipe.outputs().keySet());
            if (ordinary(recipe)) byInputs.computeIfAbsent(recipe.inputs(), unused -> new ArrayList<>()).add(recipe);
        }
        Set<String> taken = new HashSet<>();
        Set<K> catalysts = new HashSet<>(), fuels = new HashSet<>();
        for (var returned : programs) {
            budget.check();
            if (!ordinary(returned) || taken.contains(returned.id())) continue;
            for (K cost : returned.inputs().keySet()) {
                budget.check();
                Map<K, Long> inputs = new LinkedHashMap<>(returned.inputs());
                long costUnits = inputs.remove(cost);
                for (var burned : byInputs.getOrDefault(inputs, List.of())) {
                    budget.check();
                    if (budget.threadWork() - started >= allowance) return false;
                    if (taken.contains(burned.id())) continue;
                    Map<K, Long> difference = new LinkedHashMap<>(returned.outputs());
                    if (burned.outputs().entrySet().stream().anyMatch(e -> !e.getValue().equals(difference.remove(e.getKey()))) || difference.size() != 1) continue;
                    K cat = difference.keySet().iterator().next();
                    long catUnits = difference.get(cat);
                    if (cat.equals(cost) || inputs.getOrDefault(cat, 0L) != catUnits || produced.contains(cost) ||
                            original.external.contains(cat) || original.external.contains(cost) ||
                            inputs.keySet().stream().anyMatch(key -> !key.equals(cat) && produced.contains(key)))
                        continue;
                    String id = "@recovery_fuel/" + routes.size();
                    while (recipes.containsKey(id)) id += "/";
                    recipes.put(id, new GraphRecipe<>(id, id, burned.slots(), returned.outputs()));
                    taken.add(returned.id());
                    taken.add(burned.id());
                    recipes.remove(returned.id());
                    recipes.remove(burned.id());
                    catalysts.add(cat);
                    fuels.add(cost);
                    routes.add(new Route<>(id, returned, burned, cat, catUnits, cost, costUnits));
                    break;
                }
                if (taken.contains(returned.id())) break;
            }
        }
        if (routes.size() < 2 || !Collections.disjoint(catalysts, fuels)) return false;
        // Account resources are isolated from all remaining programs. No future
        // catalyst/fuel production is used to fund the recovered schedule.
        Set<K> accounts = new HashSet<>(catalysts);
        accounts.addAll(fuels);
        for (var route : routes) {
            budget.check();
            if (accounts.stream().anyMatch(key -> !key.equals(route.catalyst) &&
                    (route.burned.inputs().containsKey(key) || route.burned.outputs().containsKey(key))))
                return false;
        }
        for (var recipe : programs) if (!taken.contains(recipe.id())) {
            budget.check();
            if (accounts.stream().anyMatch(key -> recipe.inputs().containsKey(key) || recipe.outputs().containsKey(key))) return false;
        }
        budget.note("count_recovery_fuel", "routes=" + routes.size() + "; accounts=" + accounts.size() +
                "; recipes=" + programs.size() + "->" + recipes.size());
        return true;
    }

    private static boolean ordinary(GraphRecipe<?> recipe) {
        return recipe.configurationInputs().isEmpty() && recipe.reusableInputs().isEmpty();
    }

    Collection<GraphRecipe<K>> recipes() {
        return recipes.values();
    }

    Collection<GraphRecipe<K>> pricedRecipes() {
        Map<String, GraphRecipe<K>> priced = new LinkedHashMap<>(recipes);
        for (var route : routes) {
            budget.check();
            priced.put(route.id, new GraphRecipe<>(route.id, route.id, route.returned.slots(), route.returned.outputs()));
        }
        budget.note("count_recovery_fuel", "priced_candidate; bounded_burn_credit; original_accounts_verified_on_lift");
        return priced.values();
    }

    /** Optimistic fuel credit preserves the small source model while guiding it away from expensive choices. */
    Map<K, Long> pricedStock() {
        Map<K, Map<K, Route<K>>> rates = new LinkedHashMap<>();
        for (var route : routes) {
            budget.check();
            rates.computeIfAbsent(route.fuel, unused -> new LinkedHashMap<>()).merge(route.catalyst, route, (a, b) -> BigInteger.valueOf(a.fuelUnits).multiply(BigInteger.valueOf(b.catalystUnits)).compareTo(
                    BigInteger.valueOf(b.fuelUnits).multiply(BigInteger.valueOf(a.catalystUnits))) >= 0 ? a : b);
        }
        Map<K, Long> stock = new LinkedHashMap<>(original.stock);
        rates.forEach((fuel, sources) -> {
            BigInteger credit = BigInteger.ZERO;
            for (var route : sources.values()) {
                budget.check();
                credit = credit.add(available(route.catalyst).max(BigInteger.ZERO).multiply(BigInteger.valueOf(route.fuelUnits))
                        .divide(BigInteger.valueOf(route.catalystUnits)));
            }
            // The stock API is long. A capped candidate is still witness-only,
            // and cannot disprove the full model or the unpriced candidate.
            stock.put(fuel, BigInteger.valueOf(stock.getOrDefault(fuel, 0L)).add(credit).min(ExactAmounts.LONG_MAX).longValueExact());
        });
        return stock;
    }

    PlanStep lift(GraphPlan<K> plan, LongConsumer retain) {
        Map<String, BigInteger> counts = plan.patternTimesExact();
        Map<K, BigInteger> fuelNeed = new LinkedHashMap<>(), catAvailable = new LinkedHashMap<>();
        for (var route : routes) {
            budget.check();
            BigInteger n = counts.getOrDefault(route.id, BigInteger.ZERO);
            fuelNeed.merge(route.fuel, n.multiply(BigInteger.valueOf(route.fuelUnits)), BigInteger::add);
            catAvailable.put(route.catalyst, available(route.catalyst));
        }
        if (catAvailable.values().stream().anyMatch(value -> value.signum() < 0)) return null;
        for (var entry : fuelNeed.entrySet()) {
            BigInteger available = available(entry.getKey());
            if (available.signum() < 0) return null;
            entry.setValue(entry.getValue().subtract(available).max(BigInteger.ZERO));
        }
        Map<String, BigInteger> burns = allocateBurns(counts, fuelNeed, catAvailable);
        if (burns == null) return null;
        List<PlanStep> first = new ArrayList<>(), last = new ArrayList<>();
        Set<String> removed = new HashSet<>();
        BigInteger total = BigInteger.ZERO, burned = BigInteger.ZERO;
        for (var route : routes) {
            budget.check();
            removed.add(route.id);
            BigInteger n = counts.getOrDefault(route.id, BigInteger.ZERO), burn = burns.getOrDefault(route.id, BigInteger.ZERO), recover = n.subtract(burn);
            // The free-fuel model never proves the presence of a physical seed.
            if (recover.signum() > 0 && original.stock.getOrDefault(route.catalyst, 0L) < route.catalystUnits) return null;
            if (recover.signum() > 0) first.add(PlanStep.repeat(new PlanStep.Batch(route.returned.id(), 1), recover));
            if (burn.signum() > 0) last.add(PlanStep.repeat(new PlanStep.Batch(route.burned.id(), 1), burn));
            total = total.add(n);
            burned = burned.add(burn);
        }
        first.addAll(last);
        first.add(PlanRewrite.batches(plan.steps(), batch -> removed.contains(batch.recipe()) ? new PlanStep.Sequence(List.of()) : batch, budget, retain));
        budget.note("count_recovery_fuel", "lifted; runs=" + total + "; burns=" + burned);
        return new PlanStep.Sequence(first);
    }

    private BigInteger available(K key) {
        return BigInteger.valueOf(original.stock.getOrDefault(key, 0L)).subtract(original.goal(key));
    }

    /** Bounded witness construction; failure says nothing about the original choice space. */
    private Map<String, BigInteger> allocateBurns(Map<String, BigInteger> counts, Map<K, BigInteger> need, Map<K, BigInteger> available) {
        List<Route<K>> ordered = new ArrayList<>(routes);
        ordered.sort((a, b) -> BigInteger.valueOf(b.fuelUnits).multiply(BigInteger.valueOf(a.catalystUnits))
                .compareTo(BigInteger.valueOf(a.fuelUnits).multiply(BigInteger.valueOf(b.catalystUnits))));
        List<K> fuels = new ArrayList<>(need.keySet());
        for (int attempt = 0; attempt < Math.min(4, fuels.size() + 1); attempt++) {
            Map<K, BigInteger> remaining = new HashMap<>(available);
            Map<String, BigInteger> burns = new HashMap<>();
            boolean fits = true;
            for (int index = 0; index < fuels.size(); index++) {
                K fuel = fuels.get((index + attempt) % fuels.size());
                BigInteger deficit = need.get(fuel);
                for (var route : ordered) {
                    budget.check();
                    if (deficit.signum() <= 0) break;
                    if (!route.fuel.equals(fuel)) continue;
                    BigInteger cost = BigInteger.valueOf(route.fuelUnits), cat = BigInteger.valueOf(route.catalystUnits);
                    BigInteger capacity = remaining.get(route.catalyst).divide(cat).min(counts.getOrDefault(route.id, BigInteger.ZERO));
                    BigInteger burn = capacity.min(deficit.add(cost).subtract(BigInteger.ONE).divide(cost));
                    if (burn.signum() == 0) continue;
                    burns.put(route.id, burn);
                    remaining.put(route.catalyst, remaining.get(route.catalyst).subtract(cat.multiply(burn)));
                    deficit = deficit.subtract(cost.multiply(burn));
                }
                if (deficit.signum() > 0) {
                    fits = false;
                    break;
                }
            }
            if (fits) return burns;
        }
        return allocateExact(counts, need, available);
    }

    /** Only the remaining burn/return choices are integer variables; never one variable per run. */
    private Map<String, BigInteger> allocateExact(Map<String, BigInteger> counts, Map<K, BigInteger> need, Map<K, BigInteger> available) {
        if (routes.size() > 128 || need.size() + available.size() > 128) return null;
        BigInteger[] lower = new BigInteger[routes.size()], upper = new BigInteger[routes.size()];
        Arrays.fill(lower, BigInteger.ZERO);
        Map<K, Map<Integer, BigInteger>> catalysts = new LinkedHashMap<>(), fuels = new LinkedHashMap<>();
        for (int i = 0; i < routes.size(); i++) {
            budget.check();
            var route = routes.get(i);
            upper[i] = counts.getOrDefault(route.id, BigInteger.ZERO).min(available.get(route.catalyst).divide(BigInteger.valueOf(route.catalystUnits)));
            catalysts.computeIfAbsent(route.catalyst, unused -> new LinkedHashMap<>()).put(i, BigInteger.valueOf(route.catalystUnits));
            fuels.computeIfAbsent(route.fuel, unused -> new LinkedHashMap<>()).put(i, BigInteger.valueOf(route.fuelUnits).negate());
        }
        List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
        catalysts.forEach((key, terms) -> rows.add(new ExactLinearProgram.Constraint(terms, available.get(key))));
        fuels.forEach((key, terms) -> rows.add(new ExactLinearProgram.Constraint(terms, need.get(key).negate())));
        long started = budget.threadWork(), allowance = Math.min(131072, budget.remainingWork() / 32);
        try (var work = new CountQuickSolve(rows, lower, upper, budget, false, false, allowance / 2)) {
            while (budget.threadWork() - started < allowance && !work.step()) { /* bounded optional allocation */ }
            BigInteger[] values = work.counts();
            if (values == null) return null; // Even a local proof applies only to these fixed program counts.
            for (int i = 0; i < values.length; i++) if (values[i].signum() < 0 || values[i].compareTo(upper[i]) > 0) return null;
            for (var row : rows) {
                BigInteger total = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) total = total.add(term.getValue().multiply(values[term.getKey()]));
                if (total.compareTo(row.upper()) > 0) return null;
            }
            Map<String, BigInteger> result = new HashMap<>();
            for (int i = 0; i < values.length; i++) result.put(routes.get(i).id, values[i]);
            budget.note("count_recovery_fuel", "exact_account_allocation; variables=" + values.length + "; work=" + (budget.threadWork() - started));
            return result;
        }
    }
}
