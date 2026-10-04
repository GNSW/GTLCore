package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** Sparse state equation of a bounded backward closure, including every source. */
final class RecipeCountModel<K> implements AutoCloseable {

    final List<GraphRecipe<K>> recipes;
    final List<K> keys;
    final Map<K, Integer> ids;
    final Map<K, Long> stock;
    final Map<K, BigInteger> goals;
    final Map<K, BigInteger> productionGoals = new LinkedHashMap<>();
    final Set<K> external;
    final List<ExactLinearProgram.Constraint> constraints = new ArrayList<>();
    final List<K> rowKeys = new ArrayList<>();
    final PlanningBudget budget;
    private long memory;

    static <K> RecipeCountModel<K> create(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
                                          Map<K, Long> seeds, Set<K> external, Set<String> excluded, boolean force,
                                          PlanningBudget budget) {
        // Sparse preprocessing is sized by incidences, independently of the
        // much smaller dense-simplex limits in the integer branch strategy.
        return create(compiler, target, amount, stock, seeds, external, excluded, force, budget, 8192, 8192);
    }

    static <K> RecipeCountModel<K> forBounds(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
                                             Map<K, Long> seeds, Set<K> external, Set<String> excluded, PlanningBudget budget) {
        return create(compiler, target, amount, stock, seeds, external, excluded, false, budget, 8192, 8192);
    }

    static <K> RecipeCountModel<K> forProofs(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
                                             Map<K, Long> seeds, Set<K> external, Set<String> excluded, boolean force,
                                             PlanningBudget budget) {
        return create(compiler, target, amount, stock, seeds, external, excluded, force, budget, 8192, 8192);
    }

    private static <K> RecipeCountModel<K> create(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
                                                  Map<K, Long> seeds, Set<K> external, Set<String> excluded, boolean force,
                                                  PlanningBudget budget, int maxKeys, int maxRecipes) {
        // Limits apply to this aid, never to the engine's large compiled DAGs.
        long workspace = 512L * 1024;
        if (!budget.tryReserve(workspace)) {
            budget.note("count_model", "skipped; workspace_bytes=" + workspace + "; insufficient memory");
            return null;
        }
        try {
            var cached = compiler.countCatalog(target, seeds.keySet(), excluded, external);
            if (cached != null && cached.keys.size() <= maxKeys && cached.recipes.size() <= maxRecipes) {
                budget.check();
                long bytes = cached.sparseBytes();
                if (force && !external.contains(target)) bytes += 128L + 96L * cached.recipes.size();
                if (!budget.tryReserve(bytes)) {
                    budget.note("count_model", "skipped; sparse_bytes=" + bytes + "; insufficient memory");
                    return null;
                }
                workspace += bytes;
                var model = new RecipeCountModel<>(cached, stock, external, budget, goals(target, amount, seeds));
                if (force && !external.contains(target)) model.requireProduction(target, amount);
                model.memory = workspace;
                workspace = 0;
                budget.note("count_catalog", "reused; recipes=" + cached.recipes.size() + "; materials=" + cached.keys.size());
                return model;
            }
            var recipes = new LinkedHashMap<String, GraphRecipe<K>>();
            var keys = new LinkedHashSet<K>();
            var pending = new ArrayDeque<K>();
            pending.add(target);
            pending.addAll(seeds.keySet());
            while (!pending.isEmpty()) {
                budget.check();
                K key = pending.removeFirst();
                if (!keys.add(key)) continue;
                if (keys.size() > maxKeys) {
                    budget.note("count_model", "skipped; closure_keys=" + keys.size() + "; local_limit=" + maxKeys);
                    return null;
                }
                for (GraphRecipe<K> recipe : compiler.producers(key)) {
                    budget.check();
                    if (excluded.contains(recipe.id()) || recipes.putIfAbsent(recipe.id(), recipe) != null) continue;
                    if (recipes.size() > maxRecipes) {
                        budget.note("count_model", "skipped; closure_recipes=" + recipes.size() + "; local_limit=" + maxRecipes);
                        return null;
                    }
                    pending.addAll(recipe.inputs().keySet());
                }
            }
            long entries = 0;
            for (GraphRecipe<K> recipe : recipes.values()) entries += recipe.inputs().size() + recipe.outputs().size();
            long sparseBytes = 128L * entries + 128L * (keys.size() + recipes.size());
            if (force && !external.contains(target)) sparseBytes += 128L + 96L * recipes.size();
            if (!budget.tryReserve(sparseBytes)) {
                budget.note("count_model", "skipped; sparse_bytes=" + sparseBytes + "; insufficient memory");
                return null;
            }
            workspace += sparseBytes;
            var model = new RecipeCountModel<>(List.copyOf(recipes.values()), List.copyOf(keys), stock, external, budget, goals(target, amount, seeds));
            if (force && !external.contains(target)) model.requireProduction(target, amount);
            if (compiler.reuseCountCatalogs() &&
                    GraphCompiler.cacheableCountCatalog(entries + keys.size() + recipes.size(), seeds.size(), excluded.size(), external.size()))
                compiler.rememberCountCatalog(target, seeds.keySet(), excluded, external, new CountCatalog<>(model, entries, budget));
            model.memory = workspace;
            workspace = 0;
            return model;
        } finally {
            budget.release(workspace);
        }
    }

    /** Selected region only: upstream inputs are left for backward propagation. */
    static <K> RecipeCountModel<K> region(List<GraphRecipe<K>> recipes, Map<K, BigInteger> goals,
                                          Map<K, Long> stock, Set<K> external, PlanningBudget budget) {
        if (recipes.size() > 192) return null;
        Set<K> keys = new LinkedHashSet<>(), produced = new LinkedHashSet<>();
        long entries = 0;
        for (GraphRecipe<K> recipe : recipes) {
            budget.check();
            keys.addAll(recipe.inputs().keySet());
            keys.addAll(recipe.outputs().keySet());
            produced.addAll(recipe.outputs().keySet());
            entries += recipe.inputs().size() + recipe.outputs().size();
        }
        if (keys.size() > 256) return null;
        long bytes = (512L << 10) + 128L * entries;
        if (!budget.tryReserve(bytes)) return null;
        try {
            Set<K> supplied = new LinkedHashSet<>(keys);
            supplied.removeAll(produced);
            supplied.addAll(external);
            var model = new RecipeCountModel<>(recipes, List.copyOf(keys), stock, Set.copyOf(supplied), budget, goals);
            model.memory = bytes;
            bytes = 0;
            return model;
        } finally {
            budget.release(bytes);
        }
    }

    private static <K> Map<K, BigInteger> goals(K target, long amount, Map<K, Long> seeds) {
        Map<K, BigInteger> goals = new LinkedHashMap<>();
        seeds.forEach((key, value) -> goals.put(key, BigInteger.valueOf(value)));
        BigInteger reserve = BigInteger.valueOf(seeds.getOrDefault(target, 0L));
        goals.put(target, reserve.add(BigInteger.valueOf(amount)));
        return goals;
    }

    /** Optional suffix view; the caller checks restored counts and the original execution. */
    static <K> RecipeCountModel<K> forShell(List<GraphRecipe<K>> recipes, Map<K, BigInteger> goals,
                                            Map<K, Long> stock, Set<K> external, PlanningBudget budget) {
        Set<K> keys = new LinkedHashSet<>(goals.keySet());
        long entries = 0;
        for (var recipe : recipes) {
            budget.check();
            keys.addAll(recipe.inputs().keySet());
            keys.addAll(recipe.outputs().keySet());
            entries += recipe.inputs().size() + recipe.outputs().size();
        }
        long bytes = 1024L + 256L * entries + 256L * (keys.size() + recipes.size());
        for (BigInteger goal : goals.values()) bytes += 64L + (goal.bitLength() + 7L) / 8;
        if (!budget.tryReserve(bytes)) return null;
        try {
            var result = new RecipeCountModel<>(List.copyOf(recipes), List.copyOf(keys), stock, external, budget, goals);
            result.memory = bytes;
            bytes = 0;
            return result;
        } finally {
            budget.release(bytes);
        }
    }

    /**
     * Necessary production bound, not permission to execute a turnover loop.
     * Target stock may fund startup, so retaining all of it in the final goal
     * would incorrectly prove valid recycling orders impossible. Every count
     * witness still passes the independent scheduling and candidate checks.
     */
    private void requireProduction(K target, long amount) {
        Map<Integer, BigInteger> terms = new LinkedHashMap<>();
        for (int i = 0; i < recipes.size(); i++) {
            budget.check();
            long output = recipes.get(i).executionOutputs().getOrDefault(target, 0L);
            if (output != 0) terms.put(i, BigInteger.valueOf(output).negate());
        }
        productionGoals.put(target, BigInteger.valueOf(amount));
        constraints.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(amount).negate()));
    }

    private RecipeCountModel(List<GraphRecipe<K>> recipes, List<K> keys, Map<K, Long> stock,
                             Set<K> external, PlanningBudget budget, Map<K, BigInteger> goals) {
        this.recipes = recipes;
        this.keys = keys;
        this.stock = stock;
        this.external = external;
        this.budget = budget;
        this.goals = new LinkedHashMap<>(goals);
        var indices = new LinkedHashMap<K, Integer>();
        for (K key : keys) indices.put(key, indices.size());
        ids = Collections.unmodifiableMap(indices);
        Map<K, Map<Integer, BigInteger>> rows = new LinkedHashMap<>();
        for (K key : keys) if (!external.contains(key)) rows.put(key, new LinkedHashMap<>());
        // Cold construction and unshared regions keep the original sparse
        // incidence pass. A completed request may lend its immutable row terms
        // to the catalog cache; no second matrix is constructed for that cache.
        for (int i = 0; i < recipes.size(); i++) {
            GraphRecipe<K> recipe = recipes.get(i);
            Set<K> used = new LinkedHashSet<>(recipe.inputs().keySet());
            used.addAll(recipe.outputs().keySet());
            for (K key : used) {
                budget.check();
                Map<Integer, BigInteger> terms = rows.get(key);
                if (terms == null) continue;
                BigInteger value = delta(recipe, key).negate();
                if (value.signum() != 0) terms.put(i, value);
            }
        }
        for (var row : rows.entrySet()) {
            K key = row.getKey();
            constraints.add(new ExactLinearProgram.Constraint(row.getValue(), BigInteger.valueOf(stock.getOrDefault(key, 0L)).subtract(goal(key))));
            rowKeys.add(key);
        }
    }

    private RecipeCountModel(CountCatalog<K> structure, Map<K, Long> stock,
                             Set<K> external, PlanningBudget budget, Map<K, BigInteger> goals) {
        this.recipes = structure.recipes;
        this.keys = structure.keys;
        this.stock = stock;
        this.external = external;
        this.budget = budget;
        ids = structure.ids;
        this.goals = new LinkedHashMap<>(goals);
        for (int i = 0; i < keys.size(); i++) {
            K key = keys.get(i);
            if (external.contains(key)) continue;
            budget.charge(1L + (structure.terms.get(i).size() + 3L) / 4);
            constraints.add(new ExactLinearProgram.Constraint(structure.terms.get(i), BigInteger.valueOf(stock.getOrDefault(key, 0L)).subtract(goal(key))));
            rowKeys.add(key);
        }
    }

    static <K> BigInteger delta(GraphRecipe<K> recipe, K key) {
        // Configurations are charged per real push, so omitting their consumption
        // is a safe relaxation for impossibility proofs. Witnesses retain them.
        return BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L)).subtract(BigInteger.valueOf(
                recipe.inputs().getOrDefault(key, 0L) - recipe.configurationInputs().getOrDefault(key, 0L) + recipe.reusableInputs().getOrDefault(key, 0L)));
    }

    BigInteger goal(K key) {
        return goals.getOrDefault(key, BigInteger.ZERO);
    }

    BigInteger[] objective(boolean minimize) {
        BigInteger[] result = new BigInteger[recipes.size()];
        Arrays.fill(result, minimize ? BigInteger.ONE.negate() : BigInteger.ZERO);
        return result;
    }

    Map<K, BigInteger> certificate(ExactRational[] values) {
        // A physical-production row is not a conserved material and cannot be
        // published as a resource-weight certificate for another request.
        if (values.length != rowKeys.size()) return Map.of();
        BigInteger scale = BigInteger.ONE;
        for (ExactRational value : values) {
            scale = scale.divide(scale.gcd(value.denominator())).multiply(value.denominator());
            if (scale.bitLength() > 2048) return Map.of();
        }
        Map<K, BigInteger> weights = new LinkedHashMap<>();
        BigInteger common = BigInteger.ZERO;
        for (int i = 0; i < values.length; i++) {
            BigInteger value = values[i].numerator().multiply(scale.divide(values[i].denominator()));
            if (value.signum() != 0) weights.put(rowKeys.get(i), value);
            common = common.gcd(value);
        }
        BigInteger divisor = common;
        if (divisor.signum() > 0) weights.replaceAll((key, value) -> value.divide(divisor));
        return weights;
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
