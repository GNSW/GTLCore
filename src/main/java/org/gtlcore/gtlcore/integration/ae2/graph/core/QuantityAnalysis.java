package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** Integer supply bounds, exact resource certificates, and continuous prechecks. */
final class QuantityAnalysis<K> {

    private final GraphCompiler<K> compiler;
    private final List<GraphRecipe<K>> recipes;
    private final Map<K, Long> stock, goals;
    private final Set<K> external;
    private final Set<String> excluded;
    private final PlanningBudget budget;
    private final K target;
    private final long amount;
    private final Map<K, Integer> producers = new HashMap<>();
    private final Map<K, List<Integer>> consumers = new HashMap<>();
    private final Set<K> keys = new LinkedHashSet<>();
    private final Map<K, BigInteger> upper = new HashMap<>(), produced = new HashMap<>();
    private final ArrayDeque<Integer> ready = new ArrayDeque<>();
    private final int[] waiting;
    private int phase = -1, index;
    private boolean blocked;
    private long memory;
    private RecipeCountModel<K> model;
    private ExactLinearProgram linear;
    private ContinuousCoverability<K> continuous;
    private ConservationAnalysis<K> conservation;
    private CountBounds bounds;
    private MissingStockAnalysis<K> startup;
    private final boolean forceCraft;

    QuantityAnalysis(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock, Set<K> external,
                     Map<K, Long> required, Set<String> excluded, PlanningBudget budget) {
        this(compiler, target, amount, stock, external, required, excluded, budget, false);
    }

    QuantityAnalysis(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock, Set<K> external,
                     Map<K, Long> required, Set<String> excluded, PlanningBudget budget, boolean forceCraft) {
        this.compiler = compiler;
        recipes = compiler.catalog().stream().filter(r -> !excluded.contains(r.id())).toList();
        this.stock = stock;
        this.external = external;
        this.excluded = excluded;
        this.budget = budget;
        goals = new LinkedHashMap<>(required);
        this.target = target;
        this.amount = amount;
        this.forceCraft = forceCraft;
        keys.add(target);
        keys.addAll(required.keySet());
        waiting = new int[recipes.size()];
        reserve(128L + 40L * recipes.size());
    }

    boolean step() {
        budget.check();
        if (phase == -1) {
            for (var certificate : compiler.quantityCertificates(excluded)) if (deficit(certificate.weights())) return finish(true);
            phase = 0;
        }
        if (phase == 0) {
            if (index < recipes.size()) {
                int id = index++;
                GraphRecipe<K> recipe = recipes.get(id);
                for (K key : recipe.outputs().keySet()) {
                    budget.check();
                    keys.add(key);
                    producers.merge(key, 1, Integer::sum);
                    reserve(96);
                }
                for (var input : recipe.inputs().entrySet()) {
                    budget.check();
                    keys.add(input.getKey());
                    if (input.getValue().longValue() == recipe.configurationInputs().getOrDefault(input.getKey(), 0L)) continue;
                    waiting[id]++;
                    consumers.computeIfAbsent(input.getKey(), k -> new ArrayList<>()).add(id);
                    reserve(96);
                }
                return false;
            }
            for (K key : keys) if (!producers.containsKey(key) && !external.contains(key)) publish(key, BigInteger.valueOf(stock.getOrDefault(key, 0L)));
            phase = 1;
            return false;
        }
        if (phase == 1) {
            if (!ready.isEmpty()) {
                GraphRecipe<K> recipe = recipes.get(ready.removeFirst());
                BigInteger runs = null;
                for (var input : recipe.inputs().entrySet()) {
                    budget.check();
                    long consumed = input.getValue() - recipe.configurationInputs().getOrDefault(input.getKey(), 0L);
                    if (consumed == 0) continue;
                    BigInteger bound = upper.get(input.getKey()).divide(BigInteger.valueOf(consumed));
                    runs = runs == null ? bound : runs.min(bound);
                }
                if (runs == null) return false;
                for (var output : recipe.outputs().entrySet()) {
                    budget.check();
                    K key = output.getKey();
                    produced.merge(key, runs.multiply(BigInteger.valueOf(output.getValue())), BigInteger::add);
                    if (producers.merge(key, -1, Integer::sum) == 0 && !external.contains(key))
                        publish(key, produced.get(key).add(BigInteger.valueOf(stock.getOrDefault(key, 0L))));
                }
                return false;
            }
            for (K key : keys) if (upper.containsKey(key) && upper.get(key).compareTo(goal(key)) < 0) return finish(true);
            // Sparse conservation chains often expose a deficit much sooner
            // than simplex. Keep that proven fast path ahead of the general LP.
            conservation = new ConservationAnalysis<>(compiler, target, amount, stock, external, goals, excluded, budget);
            phase = 5;
            return false;
        }
        if (phase == 5) {
            if (!conservation.step()) return false;
            if (conservation.blocked()) return finish(true);
            conservation = null;
            model = RecipeCountModel.forBounds(compiler, target, amount, stock, goals, external, excluded, budget);
            if (model == null) return finish(false);
            startup = new MissingStockAnalysis<>(compiler, target, stock, external, goals.keySet(), excluded, forceCraft, budget);
            phase = 7;
            return false;
        }
        if (phase == 7) {
            if (!startup.step()) return false;
            if (startup.blocked()) return finish(true);
            Set<String> unreachable = startup.unreachableRecipes();
            startup = null;
            List<ExactLinearProgram.Constraint> rows = new ArrayList<>(model.constraints);
            // A returned credential or catalyst has zero net consumption but
            // still has to exist before its first use. Transitions unreachable
            // even without consuming any input must execute zero times.
            for (int i = 0; i < model.recipes.size(); i++) {
                budget.check();
                if (unreachable.contains(model.recipes.get(i).id()))
                    rows.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), BigInteger.ZERO));
            }
            if (forceCraft && !external.contains(target)) {
                // Stored target items do not fulfill a fresh crafting order.
                // Require gross physical production, not a stronger net-gain
                // assumption that could exclude valid target-recycling routes.
                Map<Integer, BigInteger> fresh = new LinkedHashMap<>();
                for (int i = 0; i < model.recipes.size(); i++) {
                    budget.check();
                    long output = model.recipes.get(i).executionOutputs().getOrDefault(target, 0L);
                    if (output != 0) fresh.put(i, BigInteger.valueOf(output).negate());
                }
                rows.add(new ExactLinearProgram.Constraint(fresh, BigInteger.valueOf(amount).negate()));
            }
            bounds = new CountBounds(model.recipes.size(), rows, budget);
            phase = 6;
            return false;
        }
        if (phase == 6) {
            if (!bounds.step()) return false;
            if (bounds.blocked()) {
                budget.note("quantity_bounds", "proven_blocked; recipes=" + model.recipes.size() + "; keys=" + model.keys.size());
                return finish(true);
            }
            bounds.close();
            bounds = null;
            if (model.recipes.size() > 192 || model.keys.size() > 128) return finish(false);
            // Keep the exact model, but let the caller try cheap executable
            // witnesses before paying for simplex and continuous coverability.
            phase = 8;
            return false;
        }
        if (phase == 8) {
            linear = new ExactLinearProgram(model.recipes.size(), model.constraints, model.objective(false), budget);
            phase = 2;
            return false;
        }
        if (phase == 2) {
            if (!linear.step()) return false;
            if (linear.result() == ExactLinearProgram.Result.INFEASIBLE) {
                var weights = model.certificate(linear.certificate());
                if (deficit(weights) && valid(weights)) {
                    compiler.rememberQuantityCertificate(excluded, weights);
                    return finish(true);
                }
                return finish(false);
            }
            if (linear.result() != ExactLinearProgram.Result.OPTIMAL) return finish(false);
            continuous = new ContinuousCoverability<>(model, budget);
            phase = 3;
            return false;
        }
        if (phase == 3) {
            if (!continuous.step()) return false;
            return finish(continuous.result() == ContinuousCoverability.Result.BLOCKED);
        }
        return true;
    }

    private boolean deficit(Map<K, BigInteger> weights) {
        BigInteger available = BigInteger.ZERO, required = BigInteger.ZERO;
        for (var entry : weights.entrySet()) {
            budget.check();
            K key = entry.getKey();
            BigInteger weight = entry.getValue();
            if (weight.signum() < 0 || external.contains(key) && weight.signum() != 0) return false;
            available = available.add(weight.multiply(BigInteger.valueOf(stock.getOrDefault(key, 0L))));
            required = required.add(weight.multiply(goal(key)));
        }
        return available.compareTo(required) < 0;
    }

    private boolean valid(Map<K, BigInteger> weights) {
        // Check every allowed effective recipe, including sources outside the
        // projected model. The certificate is independent of the simplex basis.
        for (GraphRecipe<K> recipe : recipes) {
            BigInteger delta = BigInteger.ZERO;
            for (var weight : weights.entrySet()) {
                budget.check();
                delta = delta.add(weight.getValue().multiply(RecipeCountModel.delta(recipe, weight.getKey())));
            }
            if (delta.signum() > 0) return false;
        }
        return true;
    }

    private BigInteger goal(K key) {
        BigInteger value = BigInteger.valueOf(goals.getOrDefault(key, 0L));
        return key.equals(target) ? value.add(BigInteger.valueOf(amount)) : value;
    }

    private void publish(K key, BigInteger value) {
        upper.put(key, value);
        for (int consumer : consumers.getOrDefault(key, List.of())) if (--waiting[consumer] == 0) ready.add(consumer);
    }

    private void reserve(long bytes) {
        budget.reserve(bytes);
        memory += bytes;
    }

    private boolean finish(boolean value) {
        blocked = value;
        phase = 4;
        if (linear != null) linear.close();
        if (continuous != null) continuous.close();
        if (conservation != null) conservation.close();
        if (model != null) model.close();
        if (bounds != null) bounds.close();
        budget.release(memory);
        memory = 0;
        return true;
    }

    boolean blocked() {
        if (phase != 4) throw new IllegalStateException("Quantity analysis is incomplete");
        return blocked;
    }

    boolean readyForHeavyAnalysis() {
        return phase == 8;
    }

    void discard() {
        if (phase != 4) finish(false);
    }
}
