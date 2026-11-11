// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Inventory-scoped necessary conditions at the first/last firing of a recipe. */
final class CountExecution<K> implements AutoCloseable {

    record Proof<K>(int recipe, List<K> materials, boolean last, CountGuard guard) {}

    private record Use(int recipe, BigInteger delta, BigInteger residual) {}

    private record Pool<K>(List<K> materials, BigInteger stock, Map<Integer, BigInteger> gains, List<Use> uses) {}

    private final List<Pool<K>> pools = new ArrayList<>();
    private final PlanningBudget budget;
    private long memory;
    private boolean separated;

    CountExecution(RecipeCountModel<K> model, PlanningBudget budget) {
        this.budget = budget;
        // Reusable tokens are exact read arcs. Consumed configuration inputs
        // depend on push grouping and cannot use a per-firing absence proof.
        if (model.recipes.stream().anyMatch(GraphRecipe::batchSensitiveInputs)) return;
        long incidences = model.recipes.stream().mapToLong(r -> r.inputs().size() + r.outputs().size()).sum();
        long bytes = 256L + 192L * incidences;
        if (!budget.tryReserve(bytes)) return;
        memory = bytes;
        try {
            var keys = model.keys.stream().filter(key -> !model.external.contains(key)).toList();
            Set<K> returned = new LinkedHashSet<>();
            for (var recipe : model.recipes) for (K key : recipe.inputs().keySet()) {
                budget.check();
                if (!model.external.contains(key) && recipe.outputs().containsKey(key)) returned.add(key);
            }
            if (model.keys.size() > 192 || model.recipes.size() > 256) compileSingletonPools(model, returned, incidences);
            else for (K key : returned) if (!compile(model, List.of(key))) return;
            // Different forms of a catalyst can individually appear obtainable
            // while their shared pool is too small. A nonnegative sum of places
            // obeys the same first/last-firing argument. Keep this optional
            // family small; omitted pools never imply infeasibility.
            if (keys.size() > 2 && keys.size() <= 16) for (K omitted : keys) {
                if (!compile(model, keys.stream().filter(key -> !key.equals(omitted)).toList())) return;
            }
            compileFeedbackPools(model, keys);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private static final class SinglePool {
        final BigInteger stock;
        final Map<Integer, BigInteger> gains = new LinkedHashMap<>();
        final List<Use> uses = new ArrayList<>();
        SinglePool(BigInteger stock) { this.stock = stock; }

        void add(int recipe, long in, long out) {
            BigInteger input = BigInteger.valueOf(in), output = BigInteger.valueOf(out);
            BigInteger delta = output.subtract(input), residual = input.min(output);
            if (delta.signum() > 0) gains.put(recipe, delta);
            if (residual.signum() > 0 && (delta.signum() < 0 || stock.compareTo(residual) < 0))
                uses.add(new Use(recipe, delta, residual));
        }
    }

    /** Build all single-material pools in one full-source incidence scan. A
     * truncated scan cannot publish a pool: an unvisited source may fund it. */
    private void compileSingletonPools(RecipeCountModel<K> model, Set<K> returned, long incidences) {
        if (returned.isEmpty()) return;
        long started = budget.threadWork(), allowance = Math.min(262144, budget.remainingWork() / 16);
        if (allowance < 4096) return;
        long bytes = 512L + 256L * returned.size() + 160L * incidences;
        if (!budget.tryReserve(bytes)) return;
        try {
            var builders = new LinkedHashMap<K, SinglePool>();
            for (K key : returned) {
                poolWork(started, allowance);
                builders.put(key, new SinglePool(PlanningInventory.exactAmount(model.stock, key)));
            }
            for (int i = 0; i < model.recipes.size(); i++) {
                var recipe = model.recipes.get(i);
                for (var input : recipe.inputs().entrySet()) {
                    poolWork(started, allowance); var builder = builders.get(input.getKey());
                    if (builder != null) builder.add(i, input.getValue(), recipe.outputs().getOrDefault(input.getKey(), 0L));
                }
                for (var output : recipe.outputs().entrySet()) {
                    poolWork(started, allowance); var builder = builders.get(output.getKey());
                    if (builder != null && !recipe.inputs().containsKey(output.getKey())) builder.add(i, 0, output.getValue());
                }
            }
            for (var entry : builders.entrySet()) {
                poolWork(started, allowance); var builder = entry.getValue();
                if (builder.uses.isEmpty()) continue;
                long terms = builder.gains.size() + (long) builder.uses.size();
                if (budget.threadWork() - started + terms >= allowance) return;
                budget.charge(terms);
                long retained = 272L + 128L * terms;
                if (!budget.tryReserve(retained)) return;
                memory += retained;
                pools.add(new Pool<>(List.of(entry.getKey()), builder.stock,
                        Map.copyOf(builder.gains), List.copyOf(builder.uses)));
            }
        } catch (PoolLimit ignored) {
            // Fully scanned, already published pools remain sound. Before
            // full-source completion none of the partial builders escapes.
        } finally { budget.release(bytes); }
    }

    /**
     * Intermediate catalyst forms need not fit in the former sixteen-key
     * complement enumeration. SCCs only suggest pools; the first/last-firing
     * inequality is then rebuilt from ALL original recipes, including exits
     * and optional replenishment. No SCC membership is a seed requirement.
     */
    private void compileFeedbackPools(RecipeCountModel<K> model, List<K> keys) {
        if (keys.size() < 3) return;
        if (keys.size() > 192 || model.recipes.size() > 256) {
            compileSparseFeedbackPools(model, keys);
            return;
        }
        long started = budget.threadWork(), allowance = Math.min(32768, budget.remainingWork() / 32);
        if (allowance < 2048) return;
        long bytes = 1024 + 256L * keys.size() + 8L * keys.size() * ((keys.size() + 63) / 64);
        if (!budget.tryReserve(bytes)) return;
        try {
            Map<K, Integer> ids = new HashMap<>();
            BitSet[] reachable = new BitSet[keys.size()];
            for (int i = 0; i < keys.size(); i++) {
                ids.put(keys.get(i), i);
                reachable[i] = new BitSet();
            }
            for (var recipe : model.recipes) for (K input : recipe.inputs().keySet()) for (K output : recipe.outputs().keySet()) {
                budget.check();
                if (budget.threadWork() - started > allowance) return;
                Integer a = ids.get(input), b = ids.get(output);
                if (a != null && b != null) reachable[a].set(b);
            }
            for (int through = 0; through < keys.size(); through++) for (int from = 0; from < keys.size(); from++) {
                budget.check();
                if (budget.threadWork() - started > allowance) return;
                if (reachable[from].get(through)) reachable[from].or(reachable[through]);
            }
            BitSet grouped = new BitSet();
            int added = 0;
            for (int first = 0; first < keys.size(); first++) if (!grouped.get(first)) {
                List<K> members = new ArrayList<>();
                for (int next = reachable[first].nextSetBit(0); next >= 0; next = reachable[first].nextSetBit(next + 1)) {
                    budget.check();
                    if (reachable[next].get(first)) {
                        members.add(keys.get(next));
                        grouped.set(next);
                    }
                }
                if (members.size() < 2 || members.size() > 64 || members.size() == keys.size()) continue;
                if (budget.threadWork() - started + (long) members.size() * model.recipes.size() > allowance) return;
                int before = pools.size();
                if (!compile(model, members)) return;
                added += pools.size() - before;
            }
            if (added > 0) budget.note("count_startup_pools", "feedback_pools=" + added + "; all_sources_checked; work=" + (budget.threadWork() - started));
        } finally {
            budget.release(bytes);
        }
    }

    private static final class PoolLimit extends RuntimeException {
        PoolLimit() { super(null, null, false, false); }
    }

    private void poolWork(long started, long allowance) {
        budget.check();
        if (budget.threadWork() - started >= allowance) throw new PoolLimit();
    }

    /** Large catalogs use incidence edges, not an input/output cross product or
     * a dense transitive-closure matrix. Components only nominate nonnegative
     * material sums; every resulting inequality still checks every recipe. */
    private void compileSparseFeedbackPools(RecipeCountModel<K> model, List<K> keys) {
        long started = budget.threadWork(), allowance = Math.min(131072, budget.remainingWork() / 32);
        if (allowance < 4096) return;
        int size = keys.size() + model.recipes.size();
        long incidences = 0;
        for (var recipe : model.recipes) {
            budget.check();
            incidences += recipe.inputs().size() + (long) recipe.outputs().size();
        }
        if (size > 16384 || incidences > 131072) return;
        long bytes = 2048L + 256L * keys.size() + 64L * size + 16L * incidences;
        if (!budget.tryReserve(bytes)) return;
        try {
            var ids = new HashMap<K, Integer>();
            for (int i = 0; i < keys.size(); i++) {
                poolWork(started, allowance); ids.put(keys.get(i), i);
            }
            int[] offsets = new int[size + 1], reverseOffsets = new int[size + 1];
            for (int i = 0; i < model.recipes.size(); i++) {
                int node = keys.size() + i;
                for (K key : model.recipes.get(i).inputs().keySet()) {
                    poolWork(started, allowance); Integer id = ids.get(key);
                    if (id != null) { offsets[id + 1]++; reverseOffsets[node + 1]++; }
                }
                for (K key : model.recipes.get(i).outputs().keySet()) {
                    poolWork(started, allowance); Integer id = ids.get(key);
                    if (id != null) { offsets[node + 1]++; reverseOffsets[id + 1]++; }
                }
            }
            for (int i = 1; i <= size; i++) {
                poolWork(started, allowance);
                offsets[i] += offsets[i - 1]; reverseOffsets[i] += reverseOffsets[i - 1];
            }
            int[] edges = new int[offsets[size]], reverseEdges = new int[reverseOffsets[size]];
            int[] next = offsets.clone(), reverseNext = reverseOffsets.clone();
            for (int i = 0; i < model.recipes.size(); i++) {
                int node = keys.size() + i;
                for (K key : model.recipes.get(i).inputs().keySet()) {
                    poolWork(started, allowance); Integer id = ids.get(key);
                    if (id != null) { edges[next[id]++] = node; reverseEdges[reverseNext[node]++] = id; }
                }
                for (K key : model.recipes.get(i).outputs().keySet()) {
                    poolWork(started, allowance); Integer id = ids.get(key);
                    if (id != null) { edges[next[node]++] = id; reverseEdges[reverseNext[id]++] = node; }
                }
            }
            // Iterative Kosaraju traversal avoids recursion on long chains.
            var seen = new BitSet(size);
            int[] stack = new int[size], finish = new int[size];
            int finished = 0;
            System.arraycopy(offsets, 0, next, 0, size);
            for (int root = 0; root < size; root++) {
                poolWork(started, allowance);
                if (seen.get(root)) continue;
                int depth = 0; stack[depth++] = root; seen.set(root);
                while (depth > 0) {
                    poolWork(started, allowance); int node = stack[depth - 1];
                    if (next[node] < offsets[node + 1]) {
                        int child = edges[next[node]++];
                        if (!seen.get(child)) { seen.set(child); stack[depth++] = child; }
                    } else { depth--; finish[finished++] = node; }
                }
            }
            int[] component = new int[size]; Arrays.fill(component, -1);
            int groups = 0;
            while (finished > 0) {
                poolWork(started, allowance); int root = finish[--finished];
                if (component[root] >= 0) continue;
                int depth = 0; stack[depth++] = root; component[root] = groups;
                while (depth > 0) {
                    poolWork(started, allowance); int node = stack[--depth];
                    for (int i = reverseOffsets[node]; i < reverseOffsets[node + 1]; i++) {
                        poolWork(started, allowance); int child = reverseEdges[i];
                        if (component[child] < 0) { component[child] = groups; stack[depth++] = child; }
                    }
                }
                groups++;
            }
            var members = new LinkedHashMap<Integer, List<K>>();
            for (int i = 0; i < keys.size(); i++) {
                poolWork(started, allowance);
                members.computeIfAbsent(component[i], ignored -> new ArrayList<>()).add(keys.get(i));
            }
            int added = 0;
            for (var pool : members.values()) {
                poolWork(started, allowance);
                if (pool.size() < 2 || pool.size() == keys.size()) continue;
                // Full-source evaluation is bounded too. An omitted pool is
                // simply absent feedback, never evidence of infeasibility.
                long cost = incidences + model.recipes.size() + 2L * pool.size();
                if (budget.threadWork() - started + cost >= allowance) break;
                int before = pools.size();
                if (!compile(model, pool, true)) break;
                added += pools.size() - before;
            }
            if (added > 0) budget.note("count_startup_pools", "sparse_feedback_pools=" + added +
                    "; all_sources_checked; work=" + (budget.threadWork() - started));
        } catch (PoolLimit ignored) {
            // Preserve already checked pools; this bounded separator proves
            // nothing about components it did not finish inspecting.
        } finally { budget.release(bytes); }
    }

    private boolean compile(RecipeCountModel<K> model, List<K> keys) {
        return compile(model, keys, false);
    }

    private boolean compile(RecipeCountModel<K> model, List<K> keys, boolean sparse) {
        BigInteger stock = BigInteger.ZERO;
        for (K key : keys) {
            if (sparse) budget.check();
            stock = stock.add(PlanningInventory.exactAmount(model.stock, key));
        }
        // The sparse caller reserves scratch space for this membership set.
        Set<K> members = sparse ? new HashSet<>(keys) : null;
        Map<Integer, BigInteger> gains = new LinkedHashMap<>();
        List<Use> uses = new ArrayList<>();
        for (int i = 0; i < model.recipes.size(); i++) {
            BigInteger input = BigInteger.ZERO, output = BigInteger.ZERO;
            if (sparse) {
                for (var entry : model.recipes.get(i).inputs().entrySet()) {
                    budget.check();
                    if (members.contains(entry.getKey())) input = input.add(BigInteger.valueOf(entry.getValue()));
                }
                for (var entry : model.recipes.get(i).outputs().entrySet()) {
                    budget.check();
                    if (members.contains(entry.getKey())) output = output.add(BigInteger.valueOf(entry.getValue()));
                }
            } else for (K key : keys) {
                budget.check();
                input = input.add(BigInteger.valueOf(model.recipes.get(i).inputs().getOrDefault(key, 0L)));
                output = output.add(BigInteger.valueOf(model.recipes.get(i).outputs().getOrDefault(key, 0L)));
            }
            BigInteger delta = output.subtract(input), residual = input.min(output);
            if (delta.signum() > 0) gains.put(i, delta);
            if (residual.signum() > 0 && (delta.signum() < 0 || stock.compareTo(residual) < 0)) uses.add(new Use(i, delta, residual));
        }
        if (uses.isEmpty()) return true;
        long bytes = 256L + 16L * keys.size() + 128L * (gains.size() + uses.size());
        if (!budget.tryReserve(bytes)) return false;
        memory += bytes;
        pools.add(new Pool<>(List.copyOf(keys), stock, Map.copyOf(gains), List.copyOf(uses)));
        return true;
    }

    private Proof<K> proof(Pool<K> pool, Use use) {
        Map<Integer, BigInteger> terms = new LinkedHashMap<>();
        pool.gains().forEach((key, value) -> { if (key != use.recipe()) terms.put(key, value.negate()); });
        if (use.delta().signum() < 0) terms.put(use.recipe(), use.delta().negate());
        // Before the FIRST use, this recipe cannot fund its own input. Before
        // its LAST use, its earlier net losses must also be funded. All OTHER
        // net losses are ignored and all other gains granted at once.
        // x_i>0 => -sum(other positive delta*x)-min(delta_i,0)*x_i
        // <= stock-min(input_i,output_i).
        var guard = new CountGuard(use.recipe(), new ExactLinearProgram.Constraint(terms, pool.stock().subtract(use.residual())));
        return new Proof<>(use.recipe(), pool.materials(), use.delta().signum() < 0, guard);
    }

    synchronized List<Proof<K>> proofs() {
        var result = new ArrayList<Proof<K>>();
        for (var pool : pools) for (var use : pool.uses()) result.add(proof(pool, use));
        return List.copyOf(result);
    }

    synchronized CountGuard violated(ExactRational[] point) {
        for (var pool : pools) {
            ExactRational funded = ExactRational.of(pool.stock());
            for (var gain : pool.gains().entrySet()) {
                budget.check();
                funded = funded.add(point[gain.getKey()].multiply(ExactRational.of(gain.getValue())));
            }
            for (var use : pool.uses()) {
                budget.check();
                if (point[use.recipe()].signum() == 0) continue;
                if (funded.subtract(point[use.recipe()].multiply(ExactRational.of(use.delta().abs())))
                        .compareTo(ExactRational.of(use.residual())) >= 0)
                    continue;
                Proof<K> proof = proof(pool, use);
                budget.note("count_execution", (proof.last() ? "last" : "first") + "_firing; recipe=" + proof.recipe() +
                        "; materials=" + proof.materials() + "; condition=" + proof.guard().required());
                return proof.guard();
            }
        }
        return null;
    }

    /** Difficult scheduling can request richer place sets without charging every easy order. */
    synchronized void refine(RecipeCountModel<K> model) {
        if (separated || model.recipes.stream().anyMatch(GraphRecipe::batchSensitiveInputs)) return;
        separated = true;
        for (var pool : CountPlaceSets.separate(model, budget)) {
            if (pools.stream().anyMatch(old -> new HashSet<>(old.materials()).equals(new HashSet<>(pool)))) continue;
            if (!compile(model, pool)) break;
        }
    }

    @Override
    public synchronized void close() {
        budget.release(memory);
        memory = 0;
    }
}
