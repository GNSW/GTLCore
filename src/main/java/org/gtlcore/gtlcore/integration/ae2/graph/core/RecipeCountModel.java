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
    final Set<K> external;
    final List<ExactLinearProgram.Constraint> constraints = new ArrayList<>();
    final List<K> rowKeys = new ArrayList<>();
    final PlanningBudget budget;
    private long memory;

    static <K> RecipeCountModel<K> create(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
                                          Map<K, Long> seeds, Set<K> external, Set<String> excluded, boolean force,
                                          PlanningBudget budget) {
        return create(compiler, target, amount, stock, seeds, external, excluded, force, budget, 128, 192);
    }

    static <K> RecipeCountModel<K> forBounds(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
                                            Map<K, Long> seeds, Set<K> external, Set<String> excluded, PlanningBudget budget) {
        return create(compiler, target, amount, stock, seeds, external, excluded, false, budget, 8192, 8192);
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
            if (!budget.tryReserve(sparseBytes)) {
                budget.note("count_model", "skipped; sparse_bytes=" + sparseBytes + "; insufficient memory");
                return null;
            }
            workspace += sparseBytes;
            var model = new RecipeCountModel<K>(List.copyOf(recipes.values()), List.copyOf(keys), target, amount, stock, seeds, external, force, budget);
            model.memory = workspace;
            workspace = 0;
            return model;
        } finally {
            budget.release(workspace);
        }
    }

    private RecipeCountModel(List<GraphRecipe<K>> recipes, List<K> keys, K target, long amount, Map<K, Long> stock,
                             Map<K, Long> seeds, Set<K> external, boolean force, PlanningBudget budget) {
        this(recipes, keys, stock, external, budget, goals(target, amount, stock, seeds, force));
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

    private static <K> Map<K, BigInteger> goals(K target, long amount, Map<K, Long> stock, Map<K, Long> seeds, boolean force) {
        Map<K, BigInteger> goals = new LinkedHashMap<>();
        seeds.forEach((key, value) -> goals.put(key, BigInteger.valueOf(value)));
        BigInteger reserve = BigInteger.valueOf(seeds.getOrDefault(target, 0L));
        if (force) reserve = reserve.max(BigInteger.valueOf(stock.getOrDefault(target, 0L)));
        goals.put(target, reserve.add(BigInteger.valueOf(amount)));
        return goals;
    }

    private RecipeCountModel(List<GraphRecipe<K>> recipes, List<K> keys, Map<K, Long> stock,
                             Set<K> external, PlanningBudget budget, Map<K, BigInteger> goals) {
        this.recipes = recipes;
        this.keys = keys;
        this.stock = stock;
        this.external = external;
        this.budget = budget;
        ids = new LinkedHashMap<>();
        this.goals = new LinkedHashMap<>(goals);
        for (K key : keys) ids.put(key, ids.size());
        Map<K, Map<Integer, BigInteger>> rows = new LinkedHashMap<>();
        for (K key : keys) if (!external.contains(key)) rows.put(key, new LinkedHashMap<>());
        // Visit actual incidences instead of every material/recipe pair. The
        // sparse bound precheck also serves catalogs too large for local LP.
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
