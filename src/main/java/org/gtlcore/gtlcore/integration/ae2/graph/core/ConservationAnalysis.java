package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** Optimistic integer supply bounds and independently checked, exact resource certificates. */
final class ConservationAnalysis<K> implements AutoCloseable {

    private final GraphCompiler<K> compiler;
    private final Set<String> excluded;
    private final long allowance;

    private final List<GraphRecipe<K>> recipes;
    private final Map<K, Long> stock, goals;
    private final Set<K> external;
    private final PlanningBudget budget;
    private final K target;
    private final BigInteger amount;
    private final Map<K, Integer> ids = new LinkedHashMap<>(), producers = new HashMap<>();
    private final Map<K, List<Integer>> consumers = new HashMap<>();
    private final Map<K, BigInteger> upper = new HashMap<>(), produced = new HashMap<>();
    private final ArrayDeque<Integer> ready = new ArrayDeque<>();
    private final List<BigInteger[]> basis = new ArrayList<>();
    private List<K> keys;
    private int[] waiting, pivots;
    private BigInteger[][] matrix;
    private int phase, index, rank, column, row, free;
    private boolean blocked;
    private long memory, eliminationWork;

    ConservationAnalysis(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock, Set<K> external,
                     Map<K, Long> required, Set<String> excluded, PlanningBudget budget) {
        recipes = compiler.catalog().stream().filter(r -> !excluded.contains(r.id())).toList();
        this.compiler = compiler;
        this.excluded = excluded;
        allowance = Math.min(1_000_000, budget.remainingWork() / 4);
        this.stock = stock;
        this.external = external;
        this.budget = budget;
        goals = new LinkedHashMap<>(required);
        // Keep delivery separate from a same-key seed, without long addition.
        this.target = target;
        this.amount = BigInteger.valueOf(amount);
        ids.put(target, 0);
        waiting = new int[recipes.size()];
        reserve(128L + 40L * recipes.size());
    }

    boolean step() {
        long before = budget.nodes();
        boolean eliminating = phase >= 2 && phase <= 5;
        try {
            if (eliminating && eliminationWork >= allowance) return finish(false);
            return advance();
        } finally {
            if (eliminating) eliminationWork += budget.nodes() - before;
        }
    }

    private boolean advance() {
        budget.check();
        if (phase == 0) {
            if (index < recipes.size()) {
                int id = index++;
                GraphRecipe<K> recipe = recipes.get(id);
                for (K key : recipe.outputs().keySet()) {
                    budget.check();
                    ids.computeIfAbsent(key, k -> ids.size());
                    producers.merge(key, 1, Integer::sum);
                    reserve(96);
                }
                for (var input : recipe.inputs().entrySet()) {
                    budget.check();
                    ids.computeIfAbsent(input.getKey(), k -> ids.size());
                    if (input.getValue().longValue() == recipe.configurationInputs().getOrDefault(input.getKey(), 0L)) continue;
                    waiting[id]++;
                    consumers.computeIfAbsent(input.getKey(), k -> new ArrayList<>()).add(id);
                    reserve(96);
                }
                return false;
            }
            keys = new ArrayList<>(ids.keySet());
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
                // No consumed inputs means unbounded production in this relaxation.
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
            // Dense exact elimination is only a bounded aid for small coupled
            // regions. A skipped/exhausted aid never proves infeasibility.
            if (keys.size() > 192 || recipes.size() > 192) return finish(false);
            long workspace = 576L * keys.size() * (recipes.size() + 16L);
            if (!budget.tryReserve(workspace)) return finish(false);
            memory += workspace;
            matrix = new BigInteger[recipes.size()][keys.size()];
            pivots = new int[Math.min(keys.size(), recipes.size())];
            index = 0;
            phase = 2;
            return false;
        }
        if (phase == 2) {
            if (index < recipes.size()) {
                BigInteger[] values = matrix[index];
                Arrays.fill(values, BigInteger.ZERO);
                GraphRecipe<K> recipe = recipes.get(index++);
                recipe.outputs().forEach((k, n) -> values[ids.get(k)] = BigInteger.valueOf(n));
                recipe.inputs().forEach((k, n) -> values[ids.get(k)] = values[ids.get(k)].subtract(BigInteger.valueOf(n - recipe.configurationInputs().getOrDefault(k, 0L))));
                return false;
            }
            row = -1;
            phase = 3;
        }
        if (phase == 3) {
            if (row >= 0 && row < matrix.length) {
                BigInteger[] values = matrix[row++], pivot = matrix[rank];
                BigInteger factor = values[column];
                if (factor.signum() == 0) return false;
                BigInteger divisor = pivot[column], gcd = BigInteger.ZERO;
                for (int j = column; j < keys.size(); j++) {
                    budget.check();
                    values[j] = values[j].multiply(divisor).subtract(pivot[j].multiply(factor));
                    if (values[j].bitLength() > 4096) return finish(false);
                    gcd = gcd.gcd(values[j]);
                }
                if (gcd.signum() > 0) for (int j = column; j < keys.size(); j++) values[j] = values[j].divide(gcd);
                return false;
            }
            if (row >= 0) {
                pivots[rank++] = column++;
                row = -1;
            }
            if (column >= keys.size() || rank == matrix.length) {
                if (keys.size() - rank > 16) return finish(false);
                phase = 4;
                return false;
            }
            int found = rank;
            while (found < matrix.length && matrix[found][column].signum() == 0) found++;
            if (found == matrix.length) column++;
            else {
                BigInteger[] swap = matrix[rank];
                matrix[rank] = matrix[found];
                matrix[found] = swap;
                row = rank + 1;
            }
            return false;
        }
        if (phase == 4) {
            if (free < keys.size()) {
                int choice = free++;
                for (int i = 0; i < rank; i++) if (pivots[i] == choice) return false;
                BigInteger[] vector = new BigInteger[keys.size()];
                Arrays.fill(vector, BigInteger.ZERO);
                vector[choice] = BigInteger.ONE;
                for (int i = rank - 1; i >= 0; i--) {
                    int pivot = pivots[i];
                    BigInteger sum = BigInteger.ZERO;
                    for (int j = pivot + 1; j < keys.size(); j++) {
                        budget.check();
                        sum = sum.add(matrix[i][j].multiply(vector[j]));
                    }
                    BigInteger scale = matrix[i][pivot].abs().divide(matrix[i][pivot].gcd(sum));
                    for (int j = 0; j < vector.length; j++) {
                        vector[j] = vector[j].multiply(scale);
                        if (vector[j].bitLength() > 4096) return finish(false);
                    }
                    vector[pivot] = sum.multiply(scale).negate().divide(matrix[i][pivot]);
                }
                basis.add(vector);
                return false;
            }
            phase = 5;
            index = 0;
        }
        if (phase == 5) {
            if (index == basis.size()) return finish(false);
            BigInteger[] vector = basis.get(index++).clone();
            if (vector[ids.get(target)].signum() < 0) for (int i = 0; i < vector.length; i++) vector[i] = vector[i].negate();
            // Positive conservation rays can repair negative catalyst weights
            // without invalidating already nonnegative coordinates.
            for (int i = 0; i < vector.length; i++) if (vector[i].signum() < 0) {
                for (BigInteger[] ray : basis) if (ray[i].signum() > 0 && Arrays.stream(ray).allMatch(v -> v.signum() >= 0)) {
                    BigInteger copies = CheckedAmounts.ceilDiv(vector[i].negate(), ray[i]);
                    for (int j = 0; j < vector.length; j++) {
                        vector[j] = vector[j].add(ray[j].multiply(copies));
                        if (vector[j].bitLength() > 4096) return false;
                    }
                    break;
                }
            }
            if (certificate(vector)) {
                Map<K, BigInteger> weights = new LinkedHashMap<>();
                for (int i = 0; i < keys.size(); i++) if (vector[i].signum() > 0) weights.put(keys.get(i), vector[i]);
                compiler.rememberQuantityCertificate(excluded, weights);
                return finish(true);
            }
            return false;
        }
        return true;
    }

    private boolean certificate(BigInteger[] weights) {
        BigInteger available = BigInteger.ZERO, required = BigInteger.ZERO;
        for (int i = 0; i < keys.size(); i++) {
            budget.check();
            K key = keys.get(i);
            BigInteger weight = weights[i];
            if (weight.signum() < 0 || external.contains(key) && weight.signum() != 0) return false;
            available = available.add(weight.multiply(BigInteger.valueOf(stock.getOrDefault(key, 0L))));
            required = required.add(weight.multiply(goal(key)));
        }
        if (available.compareTo(required) >= 0) return false;
        // Verify against every allowed original recipe, not the transformed
        // matrix or a single selected source graph. Never use floating point.
        for (GraphRecipe<K> recipe : recipes) {
            BigInteger delta = BigInteger.ZERO;
            for (var output : recipe.outputs().entrySet()) {
                budget.check();
                delta = delta.add(weights[ids.get(output.getKey())].multiply(BigInteger.valueOf(output.getValue())));
            }
            for (var input : recipe.inputs().entrySet()) {
                budget.check();
                delta = delta.subtract(weights[ids.get(input.getKey())].multiply(BigInteger.valueOf(input.getValue() - recipe.configurationInputs().getOrDefault(input.getKey(), 0L))));
            }
            if (delta.signum() > 0) return false;
        }
        return true;
    }

    private BigInteger goal(K key) {
        BigInteger value = BigInteger.valueOf(goals.getOrDefault(key, 0L));
        return key.equals(target) ? value.add(amount) : value;
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
        phase = 6;
        budget.release(memory);
        memory = 0;
        return true;
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }

    boolean blocked() {
        if (phase != 6) throw new IllegalStateException("Quantity analysis is incomplete");
        return blocked;
    }
}
