package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.util.*;

/** Stock-guided source ranking for the main graph planner. */
final class GraphSourceRanking<K> implements AutoCloseable {

    private final GraphCompiler<K> compiler;
    private final PlanningBudget budget;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final K target;
    private final boolean force;
    private final long preparationAllowance;
    private boolean local;
    private boolean quantitative;
    private final Set<K> growing = new HashSet<>();
    private final Map<K, Integer> distance = new HashMap<>();
    private final Map<K, Double> cost = new HashMap<>();
    private final Map<K, List<GraphRecipe<K>>> near = new HashMap<>(), cheap = new HashMap<>();
    private long memory, started, globalTicks;

    private GraphSourceRanking(GraphCompiler<K> compiler, Map<K, Long> stock, Set<K> external,
                               K target, boolean force, PlanningBudget budget, long maximumWork) {
        this.compiler = compiler;
        this.stock = stock;
        this.external = external;
        this.target = target;
        this.force = force;
        this.budget = budget;
        preparationAllowance = Math.min(maximumWork, Math.min(393_216, budget.remainingWork() / 8));
    }

    static <K> GraphSourceRanking<K> create(GraphCompiler<K> compiler, Map<K, Long> stock,
                                            Set<K> external, K target, boolean force, PlanningBudget budget) {
        return create(compiler, stock, external, target, force, budget, 393_216);
    }

    static <K> GraphSourceRanking<K> create(GraphCompiler<K> compiler, Map<K, Long> stock,
                                            Set<K> external, K target, boolean force, PlanningBudget budget, long maximumWork) {
        return create(compiler, stock, external, target, force, budget, maximumWork, false);
    }

    static <K> GraphSourceRanking<K> createQuantitative(GraphCompiler<K> compiler, Map<K, Long> stock,
                                                        Set<K> external, K target, boolean force, PlanningBudget budget, long maximumWork) {
        return create(compiler, stock, external, target, force, budget, maximumWork, true);
    }

    private static <K> GraphSourceRanking<K> create(GraphCompiler<K> compiler, Map<K, Long> stock,
                                                    Set<K> external, K target, boolean force, PlanningBudget budget,
                                                    long maximumWork, boolean quantitative) {
        if (budget.remainingWork() < 24_576 || maximumWork < 1024) return null;
        var result = new GraphSourceRanking<>(compiler, stock, external, target, force, budget, maximumWork);
        result.quantitative = quantitative;
        if (!result.reserve(1024)) return null;
        result.started = budget.threadSearchWork();
        try {
            if (!result.prepareGlobal()) result.prepare();
            return result;
        } catch (RuntimeException | Error failure) {
            result.close();
            throw failure;
        }
    }

    static <K> GraphSourceRanking<K> createLocal(GraphCompiler<K> compiler, Map<K, Long> stock,
                                                 Set<K> external, K target, boolean force, PlanningBudget budget,
                                                 long maximumWork) {
        if (maximumWork < 1024 || budget.remainingWork() < 24_576) return null;
        var result = new GraphSourceRanking<>(compiler, stock, external, target, force, budget, Math.min(8192, maximumWork));
        result.local = true;
        if (!result.reserve(1024)) return null;
        result.started = budget.threadSearchWork();
        try {
            result.prepare();
            return result;
        } catch (RuntimeException | Error failure) {
            result.close();
            throw failure;
        }
    }

    private boolean tick(long limit) {
        if (budget.threadSearchWork() - started >= limit) return false;
        budget.check();
        return true;
    }

    private boolean scan(long limit) {
        if (PlanningBudget.units(globalTicks) >= limit) return false;
        globalTicks += budget.operation(PlanningBudget.Operation.SCAN, 1);
        return true;
    }

    /**
     * Forward AND reachability is linear in input incidences, including paths
     * outside a small backward cone. Only the adjacency is shared; every order
     * rebuilds its stock labels, depths and relaxed unit costs.
     */
    private boolean prepareGlobal() {
        if (preparationAllowance < 16_384) return false;
        long indexAllowance = preparationAllowance / 3;
        long allowance = preparationAllowance - indexAllowance;
        var index = GraphSourceIndex.create(compiler, budget, indexAllowance);
        if (index == null) return false;
        // Give labels their own fixed share: seeded growth cycles need not
        // converge, so the useful longer relaxation must be available to both
        // cold and cached requests. The two shares still fit the caller's total
        // allowance; caching only saves actual index work, never invents work.
        long setupWork = (index.recipes.size() + 3L) / 4;
        if (setupWork >= allowance) return false;
        long workspace = index.bytes + 1024L + 40L * index.recipes.size();
        if (!budget.tryReserve(workspace)) return false;
        try {
            budget.charge(setupWork);
            globalTicks += setupWork * PlanningBudget.WORK_SCALE;
            int[] remaining = index.required.clone();
            var pending = new ArrayDeque<K>();
            var announced = new HashSet<K>();
            if (quantitative) {
                for (int id = 0; id < remaining.length; id++) {
                    if (!scan(allowance)) return true;
                    int deficient = 0;
                    for (var input : index.recipes.get(id).inputs().entrySet()) {
                        if (!scan(allowance)) return true;
                        if (!external.contains(input.getKey()) && stock.getOrDefault(input.getKey(), 0L) < input.getValue()) deficient++;
                    }
                    remaining[id] = deficient;
                    if (deficient == 0 && !announce(index.recipes.get(id), pending, allowance)) return true;
                }
            } else {
                for (K key : index.consumers.keySet()) {
                    if (!scan(allowance)) return true;
                    if (seeded(key)) pending.addLast(key);
                }
                for (int id : index.unconditional) {
                    if (!scan(allowance) || !announce(index.recipes.get(id), pending, allowance)) return true;
                }
            }
            while (!pending.isEmpty()) {
                if (!scan(allowance)) return true;
                K key = pending.removeFirst();
                if (!announced.add(key)) continue;
                int[] consumers = index.consumers.get(key);
                if (consumers == null) continue;
                for (int id : consumers) {
                    if (!scan(allowance)) return true;
                    if (quantitative && (external.contains(key) || stock.getOrDefault(key, 0L) >= index.recipes.get(id).inputs().get(key))) continue;
                    if (--remaining[id] == 0 && !announce(index.recipes.get(id), pending, allowance)) return true;
                }
            }
            // Revisit only enabled recipes whose input cost has improved. A
            // seeded growth cycle may keep reducing its relaxed unit cost, so
            // this remains bounded and never supplies a negative conclusion.
            var enabled = new ArrayDeque<Integer>();
            boolean[] queued = new boolean[remaining.length];
            for (int id = 0; id < remaining.length; id++) {
                if (!scan(allowance)) return true;
                if (remaining[id] == 0) {
                    enabled.addLast(id);
                    queued[id] = true;
                }
            }
            while (!enabled.isEmpty()) {
                if (!scan(allowance)) return true;
                int id = enabled.removeFirst();
                queued[id] = false;
                var recipe = index.recipes.get(id);
                double total = 0;
                for (var input : recipe.inputs().entrySet()) {
                    if (!scan(allowance)) return true;
                    total += input.getValue() * cost(input.getKey());
                }
                if (!Double.isFinite(total)) continue;
                for (var output : recipe.executionOutputs().entrySet()) {
                    if (!scan(allowance)) return true;
                    K key = output.getKey();
                    double unit = total / output.getValue();
                    if (!(unit < cost(key) * (1 - 1e-9))) continue;
                    if (!cost.containsKey(key) && !distance.containsKey(key) && !reserve(128)) return true;
                    cost.put(key, unit);
                    int[] consumers = index.consumers.get(key);
                    if (consumers == null) continue;
                    for (int next : consumers) {
                        if (!scan(allowance)) return true;
                        if (remaining[next] == 0 && !queued[next]) {
                            queued[next] = true;
                            enabled.addLast(next);
                        }
                    }
                }
            }
            return true;
        } finally {
            budget.release(workspace);
            budget.note("source_ranking", "global_labels; recipes=" + index.recipes.size() + "; reached=" + distance.size() +
                    "; quantitative=" + quantitative + "; work=" + (budget.threadSearchWork() - started));
        }
    }

    private boolean announce(GraphRecipe<K> recipe, Deque<K> pending, long allowance) {
        int depth = 0;
        double total = 0;
        for (var input : recipe.inputs().entrySet()) {
            if (!scan(allowance)) return false;
            Integer value = distance(input.getKey());
            if (value == null) return true;
            depth = Math.max(depth, value);
            total += input.getValue() * cost(input.getKey());
        }
        for (var output : recipe.executionOutputs().entrySet()) {
            if (!scan(allowance)) return false;
            K key = output.getKey();
            if (distance(key) == null) {
                if (!reserve(256)) return false;
                distance.put(key, depth + 1);
                if (!quantitative) pending.addLast(key);
            }
            if (quantitative && !external.contains(key) && !growing.contains(key)) {
                long consumed = recipe.inputs().getOrDefault(key, 0L) - recipe.configurationInputs().getOrDefault(key, 0L) + recipe.reusableInputs().getOrDefault(key, 0L);
                if (recipe.outputs().getOrDefault(key, 0L) > consumed) {
                    if (!reserve(96)) return false;
                    growing.add(key);
                    pending.addLast(key);
                }
            }
            double unit = total / output.getValue();
            if (unit < cost(key)) {
                // Stock keys can improve before they ever need a depth label.
                if (!cost.containsKey(key) && !distance.containsKey(key) && !reserve(128)) return false;
                cost.put(key, unit);
            }
        }
        return true;
    }

    /**
     * Visit the requested cone, stopping at stocked keys. This is only a source
     * ranking: finite stock, omitted producers and unfinished traversal cannot
     * establish unreachability. Unknown sources are always retained below.
     */
    private void prepare() {
        long discoveryEnd = Math.min(preparationAllowance, budget.threadSearchWork() - started + 4096);
        long relaxationEnd = Math.min(preparationAllowance, budget.threadSearchWork() - started + 8192);
        var pending = new ArrayDeque<K>();
        var keys = new HashSet<K>();
        var ids = new HashSet<String>();
        var recipes = new ArrayList<GraphRecipe<K>>();
        pending.add(target);
        keys.add(target);
        discovery:
        while (!pending.isEmpty()) {
            if (!tick(discoveryEnd)) break;
            K key = pending.removeFirst();
            if (seeded(key) && !(force && target.equals(key))) continue;
            for (var recipe : compiler.producers(key)) {
                if (!tick(discoveryEnd) || !reserve(128)) break discovery;
                if (!ids.add(recipe.id())) continue;
                recipes.add(recipe);
                for (K input : recipe.inputs().keySet()) {
                    if (!tick(discoveryEnd) || !reserve(128)) break discovery;
                    if (keys.add(input) && !seeded(input)) pending.addLast(input);
                }
            }
        }
        // Reverse discovery usually propagates funded leaf costs in one pass.
        // Repeated passes also handle alternative paths and seeded cycles;
        // their deliberately relaxed quantities are never used as a proof.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = recipes.size() - 1; i >= 0; i--) {
                var recipe = recipes.get(i);
                double value = 0;
                int depth = 0;
                for (var input : recipe.inputs().entrySet()) {
                    if (!tick(relaxationEnd)) return;
                    Integer d = distance(input.getKey());
                    if (d == null) {
                        value = Double.POSITIVE_INFINITY;
                        break;
                    }
                    depth = Math.max(depth, d);
                    value += input.getValue() * cost(input.getKey());
                }
                if (!Double.isFinite(value)) continue;
                for (var output : recipe.outputs().entrySet()) {
                    if (!tick(relaxationEnd)) return;
                    K key = output.getKey();
                    double unit = value / output.getValue();
                    Integer oldDepth = distance(key);
                    if (oldDepth != null && oldDepth <= depth + 1 && unit >= cost(key)) continue;
                    if (!distance.containsKey(key) && !reserve(256)) return;
                    if (oldDepth == null || depth + 1 < oldDepth) distance.put(key, depth + 1);
                    if (unit < cost(key)) cost.put(key, unit);
                    changed = true;
                }
            }
        }
    }

    private boolean seeded(K key) {
        // Forced crafting still permits target stock to seed a productive loop.
        // This label ranks inputs; it never discharges the production goal.
        return external.contains(key) || !(local && force && target.equals(key)) && stock.getOrDefault(key, 0L) > 0;
    }

    private Integer distance(K key) {
        return seeded(key) ? Integer.valueOf(0) : distance.get(key);
    }

    private double cost(K key) {
        if (external.contains(key)) return 0;
        double initial = seeded(key) ? 1.0 / stock.get(key) : Double.POSITIVE_INFINITY;
        return Math.min(initial, cost.getOrDefault(key, Double.POSITIVE_INFINITY));
    }

    private boolean reserve(long bytes) {
        if (!budget.tryReserve(bytes)) return false;
        memory += bytes;
        return true;
    }

    List<GraphRecipe<K>> sources(K key, boolean byCost) {
        return sources(key, byCost, 8192);
    }

    /** An unfinished ranking retains the complete provider order. */
    List<GraphRecipe<K>> sources(K key, boolean byCost, long maximumWork) {
        var cache = byCost ? cheap : near;
        var cached = cache.get(key);
        if (cached != null) return cached;
        var original = compiler.producers(key);
        long available = Math.min(8192, maximumWork);
        if (original.size() < 2 || available <= 0) return original;
        long bytes = 128L + 128L * original.size();
        if (!reserve(bytes)) return original;
        long firstWork = budget.threadSearchWork();
        boolean retained = false;
        try {
            var scored = new ArrayList<Source<K>>();
            for (var recipe : original) {
                rankingCheck(firstWork, available);
                double score = 0;
                double depth = 0;
                for (var input : recipe.inputs().entrySet()) {
                    rankingCheck(firstWork, available);
                    Integer d = distance(input.getKey());
                    if (d == null || quantitative && !growing.contains(input.getKey()) && !external.contains(input.getKey()) &&
                            stock.getOrDefault(input.getKey(), 0L) < input.getValue()) {
                        score = Double.POSITIVE_INFINITY;
                        depth = Double.POSITIVE_INFINITY;
                        break;
                    }
                    depth += d;
                    score += byCost ? input.getValue() * cost(input.getKey()) : d;
                }
                long gain = recipe.executionOutputs().getOrDefault(key, 0L) - recipe.inputs().getOrDefault(key, 0L);
                scored.add(new Source<>(recipe, byCost ? score / (local ? recipe.outputs().getOrDefault(key, 1L) : Math.max(1, gain)) : score,
                        local || gain > 0, local ? 0 : depth));
            }
            // Stable ties retain the provider's original priority order. An
            // unknown path is last, never excluded by partial reachability.
            scored.sort((a, b) -> {
                rankingCheck(firstWork, available);
                // A returned container or catalyst cannot close a deficit of
                // that material by itself. Keep it available for joint routes,
                // after sources that actually add units of the requested key.
                int productive = Boolean.compare(b.productive(), a.productive());
                if (productive != 0) return productive;
                int score = Double.compare(a.score(), b.score());
                return score != 0 ? score : Double.compare(a.depth(), b.depth());
            });
            var ordered = new ArrayList<GraphRecipe<K>>(scored.size());
            for (var source : scored) {
                rankingCheck(firstWork, available);
                ordered.add(source.recipe());
            }
            var result = List.copyOf(ordered);
            cache.put(key, result);
            retained = true;
            return result;
        } catch (RankingStopped ignored) {
            return original;
        } finally {
            if (!retained) {
                budget.release(bytes);
                memory -= bytes;
            }
        }
    }

    /** Batch rounding matters when a request is smaller than an output lot. */
    List<GraphRecipe<K>> sources(K key, long needed, long maximumWork) {
        var original = compiler.producers(key);
        long available = Math.min(8192, maximumWork);
        if (original.size() < 2 || available <= 0) return original;
        long bytes = 128L + 128L * original.size();
        if (!budget.tryReserve(bytes)) return original;
        long firstWork = budget.threadSearchWork();
        try {
            var scored = new ArrayList<Source<K>>();
            for (var recipe : original) {
                rankingCheck(firstWork, available);
                long gain = recipe.executionOutputs().getOrDefault(key, 0L) - recipe.inputs().getOrDefault(key, 0L);
                long output = Math.max(1, gain);
                long runs = needed / output + (needed % output == 0 ? 0 : 1);
                double score = 0, depth = 0;
                for (var input : recipe.inputs().entrySet()) {
                    rankingCheck(firstWork, available);
                    Integer d = distance(input.getKey());
                    if (d == null || quantitative && !growing.contains(input.getKey()) && !external.contains(input.getKey()) &&
                            stock.getOrDefault(input.getKey(), 0L) < input.getValue()) {
                        score = depth = Double.POSITIVE_INFINITY;
                        break;
                    }
                    depth += d;
                    score += runs * (double) input.getValue() * cost(input.getKey());
                }
                scored.add(new Source<>(recipe, score, gain > 0, depth));
            }
            scored.sort((a, b) -> {
                rankingCheck(firstWork, available);
                int productive = Boolean.compare(b.productive(), a.productive());
                if (productive != 0) return productive;
                int score = Double.compare(a.score(), b.score());
                return score != 0 ? score : Double.compare(a.depth(), b.depth());
            });
            return scored.stream().map(Source::recipe).toList();
        } catch (RankingStopped ignored) {
            return original;
        } finally {
            budget.release(bytes);
        }
    }

    private void rankingCheck(long firstWork, long allowance) {
        budget.checkpoint();
        if (budget.threadSearchWork() - firstWork >= allowance) throw RankingStopped.INSTANCE;
        budget.check();
    }

    private static final class RankingStopped extends RuntimeException {

        private static final RankingStopped INSTANCE = new RankingStopped();

        private RankingStopped() {
            super(null, null, false, false);
        }
    }

    private record Source<K>(GraphRecipe<K> recipe, double score, boolean productive, double depth) {}

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
