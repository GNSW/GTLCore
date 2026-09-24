package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Native bounded allocation search after the selected-region fast path. Each edge
 * is an executable, compressed batch. Branches share immutable recipes and roll
 * back only changed ledger entries; no branch clones a crafting tree or inventory.
 * Failure here is UNKNOWN, never a proof that the network lacks ingredients.
 */
final class AllocationSearch<K> {

    private final GraphCompiler<K> compiler;
    private final K target;
    private final long amount, started;
    private final Map<K, Long> stock;
    private final Map<K, Long> requiredSeeds;
    private final Set<K> external;
    private final boolean preserve, force;
    private final PlanningBudget budget;
    private final Set<String> excluded;
    private final Map<K, BigInteger> goals = new LinkedHashMap<>();
    private final Deque<K> pending = new ArrayDeque<>();
    private final Set<K> discovered = new HashSet<>();
    private final Map<String, GraphRecipe<K>> relevant = new LinkedHashMap<>();
    private final List<GraphRecipe<K>> recipes = new ArrayList<>();
    private final List<SequenceSummary<K>> summaries = new ArrayList<>();
    // This search models the available network plus hypothetical recipe deltas,
    // not the CPU's physical inventory. Stock at Long.MAX_VALUE must not forbid
    // a recipe with a positive byproduct: the final witness borrows only its
    // required prefix and independently checks all actual long-sized balances.
    private final Map<K, BigInteger> held = new LinkedHashMap<>();
    private final List<Change<K>> undo = new ArrayList<>();
    private final List<PlanStep> path = new ArrayList<>();
    private final Deque<Frame> stack = new ArrayDeque<>();
    private final Set<Stamp> visited = new HashSet<>();
    private final Map<K, Integer> keyIds = new HashMap<>();
    private Iterator<GraphRecipe<K>> discovering;
    private K discoveringKey;
    private int phase, index;
    private long hash1, hash2, memory;
    private Candidate checking;
    private GraphPlan<K> result;

    AllocationSearch(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock, Set<K> external, Map<K, Long> requiredSeeds,
                     boolean preserve, boolean force, Set<String> excluded, PlanningBudget budget, long started) {
        this.compiler = compiler;
        this.target = target;
        this.amount = amount;
        this.stock = stock;
        this.requiredSeeds = requiredSeeds;
        this.external = external;
        this.preserve = preserve;
        this.force = force;
        this.excluded = excluded;
        this.budget = budget;
        this.started = started;
        goals.put(target, BigInteger.valueOf(amount));
        pending.add(target);
        requiredSeeds.forEach((key, count) -> goals.merge(key, BigInteger.valueOf(count), BigInteger::add));
        pending.addAll(requiredSeeds.keySet());
    }

    boolean step() {
        budget.check();
        budget.phase(PlanningBudget.Phase.SOLVE);
        if (phase == 0) {
            discover();
            return false;
        }
        if (phase == 1) {
            if (index < recipes.size()) {
                var recipe = recipes.get(index++);
                summaries.add(SequenceSummary.recipe(recipe));
                for (K key : recipe.inputs().keySet()) keyIds.computeIfAbsent(key, ignored -> keyIds.size());
                for (K key : recipe.outputs().keySet()) keyIds.computeIfAbsent(key, ignored -> keyIds.size());
                reserve(256L + 128L * (recipe.inputs().size() + recipe.outputs().size()));
            } else {
                keyIds.computeIfAbsent(target, ignored -> keyIds.size());
                for (K key : keyIds.keySet()) {
                    long available = stock.getOrDefault(key, 0L);
                    // A finite, explicitly permitted external requirement for this
                    // candidate. This is not physical stock or a MAX_VALUE sentinel.
                    if (external.contains(key)) available = Math.max(available, CheckedAmounts.amount(goals.getOrDefault(key, BigInteger.ZERO)));
                    set(key, BigInteger.valueOf(available), false);
                }
                stack.push(new Frame(0, 0));
                visit();
                phase = 2;
            }
            return false;
        }
        if (phase == 3) return true;
        if (checking != null) {
            if (!checking.step()) return false;
            result = checking.plan;
            checking = null;
            if (result != null) {
                finish();
                return true;
            }
        }
        if (stack.isEmpty()) {
            finish();
            return true;
        }
        Frame frame = stack.peek();
        if (!frame.checkedGoal) {
            frame.checkedGoal = true;
            BigInteger goal = BigInteger.valueOf(amount);
            if (force) goal = goal.add(BigInteger.valueOf(stock.getOrDefault(target, 0L)));
            if (held.getOrDefault(target, BigInteger.ZERO).compareTo(goal) >= 0) {
                checking = new Candidate();
                return false;
            }
        }
        if (frame.recipe >= recipes.size()) {
            rollback(frame.mark);
            while (path.size() > frame.pathSize) path.remove(path.size() - 1);
            if (stack.size() > 1) {
                memory -= 96;
                budget.release(96);
            }
            stack.pop();
            return false;
        }
        int recipeIndex = frame.recipe;
        if (frame.nextRuns == 0) {
            frame.maximum = maximum(recipeIndex);
            frame.nextRuns = frame.maximum;
        }
        long runs = frame.nextRuns;
        // Enumerate all small allocations, and the useful maximum / half / one
        // breakpoints for large ones. The latter is deliberately not exhaustive.
        if (runs <= 1) {
            frame.recipe++;
            frame.nextRuns = 0;
        } else frame.nextRuns = frame.maximum <= 32 ? runs - 1 : runs > 2 ? runs / 2 : 1;
        if (runs == 0) return false;
        int mark = undo.size(), size = path.size();
        var summary = summaries.get(recipeIndex);
        for (var entry : summary.delta().entrySet()) {
            budget.check();
            BigInteger next = held.getOrDefault(entry.getKey(), BigInteger.ZERO)
                    .add(entry.getValue().multiply(BigInteger.valueOf(runs)));
            set(entry.getKey(), next, true);
        }
        if (!visit()) {
            rollback(mark);
            return false;
        }
        path.add(new PlanStep.Batch(recipes.get(recipeIndex).id(), runs));
        reserve(96);
        if (stack.size() >= 4096) throw new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT);
        stack.push(new Frame(mark, size));
        return false;
    }

    private void discover() {
        if (discovering != null) {
            if (!discovering.hasNext()) {
                discovering = null;
                return;
            }
            GraphRecipe<K> recipe = discovering.next();
            if (excluded.contains(recipe.id())) return;
            relevant.putIfAbsent(recipe.id(), recipe);
            BigInteger count = CheckedAmounts.ceilDiv(goals.get(discoveringKey), BigInteger.valueOf(recipe.outputs().get(discoveringKey)));
            for (var input : recipe.inputs().entrySet()) {
                budget.check();
                goals.merge(input.getKey(), BigInteger.valueOf(input.getValue()).multiply(count), BigInteger::max);
                if (!discovered.contains(input.getKey())) pending.add(input.getKey());
            }
            return;
        }
        if (pending.isEmpty()) {
            recipes.addAll(relevant.values());
            phase = 1;
            return;
        }
        discoveringKey = pending.removeFirst();
        if (discovered.add(discoveringKey)) discovering = compiler.producers(discoveringKey).iterator();
    }

    private long maximum(int index) {
        SequenceSummary<K> summary = summaries.get(index);
        BigInteger bound = BigInteger.valueOf(Long.MAX_VALUE), useful = BigInteger.ZERO;
        for (K key : summary.keys()) {
            budget.check();
            BigInteger current = held.getOrDefault(key, BigInteger.ZERO);
            if (current.compareTo(summary.required(key)) < 0) return 0;
            BigInteger delta = summary.delta(key);
            if (delta.signum() < 0) bound = bound.min(current.subtract(summary.required(key)).divide(delta.negate()).add(BigInteger.ONE));
            else if (delta.signum() > 0) {
                BigInteger goal = goals.getOrDefault(key, BigInteger.ZERO);
                if (force && key.equals(target)) goal = goal.max(BigInteger.valueOf(stock.getOrDefault(target, 0L)).add(BigInteger.valueOf(amount)));
                useful = useful.max(CheckedAmounts.ceilDiv(goal.subtract(current), delta));
            }
        }
        // A transformation with an identically zero vector cannot help a material
        // objective. A catalyst-returning productive recipe has nonzero other keys.
        if (summary.delta().values().stream().allMatch(value -> value.signum() == 0)) return 0;
        return CheckedAmounts.amount(bound.min(useful.max(BigInteger.ONE)));
    }

    private void set(K key, BigInteger value, boolean record) {
        if (value.signum() < 0) throw new IllegalArgumentException("Negative search inventory");
        BigInteger old = held.getOrDefault(key, BigInteger.ZERO);
        if (old.equals(value)) return;
        if (record) {
            undo.add(new Change<>(key, old));
            reserve(96);
        }
        long id = keyIds.get(key) + 1L;
        hash1 ^= mix(id * 0x9e3779b97f4a7c15L ^ old.longValue()) ^ mix(id * 0x9e3779b97f4a7c15L ^ value.longValue());
        hash2 ^= mix(id * 0xd6e8feb86659fd93L + old.hashCode()) ^ mix(id * 0xd6e8feb86659fd93L + value.hashCode());
        if (value.signum() == 0) held.remove(key);
        else held.put(key, value);
    }

    private void rollback(int mark) {
        while (undo.size() > mark) {
            budget.check();
            Change<K> change = undo.remove(undo.size() - 1);
            set(change.key(), change.previous(), false);
            memory -= 96;
            budget.release(96);
        }
    }

    private boolean visit() {
        // This is only a negative search heuristic. Neither a hash nor search
        // exhaustion is ever accepted as a material witness or an impossibility proof.
        if (!visited.add(new Stamp(hash1, hash2))) return false;
        reserve(64);
        return true;
    }

    private static long mix(long value) {
        value = (value ^ value >>> 30) * 0xbf58476d1ce4e5b9L;
        value = (value ^ value >>> 27) * 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }

    private void reserve(long bytes) {
        budget.reserve(bytes);
        memory += bytes;
    }

    private void finish() {
        phase = 3;
        budget.release(memory);
        memory = 0;
    }

    GraphPlan<K> result() {
        if (phase != 3) throw new IllegalStateException("Allocation search incomplete");
        return result;
    }

    private record Change<K>(K key, BigInteger previous) {}

    private record Stamp(long first, long second) {}

    private static final class Frame {

        final int mark, pathSize;
        int recipe;
        long maximum, nextRuns;
        boolean checkedGoal;

        Frame(int mark, int pathSize) {
            this.mark = mark;
            this.pathSize = pathSize;
        }
    }

    private final class Candidate {

        final PlanStep witness = new PlanStep.Sequence(path);
        final Map<String, GraphRecipe<K>> used = new LinkedHashMap<>();
        final Map<K, List<GraphRecipe<K>>> producers = new HashMap<>();
        final Map<K, Long> seeds = new LinkedHashMap<>(), initial = new LinkedHashMap<>();
        final Deque<K> todo = new ArrayDeque<>();
        final Set<K> walked = new HashSet<>();
        SummaryComputation<K> computation;
        SequenceSummary<K> summary;
        Iterator<K> keys;
        K checkingKey;
        int stage, stepIndex;
        GraphPlan<K> plan;

        Candidate() {
            seeds.putAll(requiredSeeds);
        }

        boolean step() {
            budget.check();
            if (stage == 0) {
                if (stepIndex < path.size()) {
                    String id = ((PlanStep.Batch) path.get(stepIndex++)).recipe();
                    GraphRecipe<K> recipe = relevant.get(id);
                    if (used.putIfAbsent(id, recipe) == null)
                        for (K key : recipe.outputs().keySet()) producers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(recipe);
                } else {
                    computation = new SummaryComputation<>(witness, used, budget);
                    stage = 1;
                }
            } else if (stage == 1) {
                if (!computation.step()) return false;
                summary = computation.result();
                if (force && summary.delta(target).compareTo(BigInteger.valueOf(amount)) < 0) return true;
                keys = summary.required().keySet().iterator();
                stage = 2;
            } else if (stage == 2) {
                if (!preserve) {
                    stage = 3;
                    return false;
                }
                if (checkingKey != null) {
                    if (todo.isEmpty()) {
                        checkingKey = null;
                        return false;
                    }
                    K key = todo.removeFirst();
                    if (!walked.add(key)) return false;
                    for (GraphRecipe<K> recipe : producers.getOrDefault(key, List.of())) for (K input : recipe.inputs().keySet()) {
                        budget.check();
                        if (input.equals(checkingKey)) {
                            seeds.merge(checkingKey, CheckedAmounts.amount(summary.required(checkingKey)), Math::max);
                            checkingKey = null;
                            todo.clear();
                            return false;
                        }
                        if (!walked.contains(input)) todo.add(input);
                    }
                } else if (keys.hasNext()) {
                    K key = keys.next();
                    if (summary.required(key).signum() > 0 && summary.delta(key).signum() >= 0) {
                        checkingKey = key;
                        todo.add(key);
                        walked.clear();
                    }
                } else stage = 3;
            } else if (stage == 3) {
                var all = summary.keys();
                all.add(target);
                all.addAll(seeds.keySet());
                keys = all.iterator();
                stage = 4;
            } else if (stage == 4) {
                if (keys.hasNext()) {
                    K key = keys.next();
                    BigInteger goal = BigInteger.valueOf(seeds.getOrDefault(key, 0L));
                    if (key.equals(target)) goal = goal.add(BigInteger.valueOf(amount));
                    long required = CheckedAmounts.amount(summary.required(key).max(goal.subtract(summary.delta(key))));
                    if (!external.contains(key) && required > stock.getOrDefault(key, 0L)) return true;
                    if (required > 0) initial.put(key, required);
                } else {
                    plan = new GraphPlan<>(target, amount, preserve, witness, used, initial, seeds, Map.of(),
                            GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, budget.nodes(), System.nanoTime() - started);
                    return true;
                }
            }
            return false;
        }
    }
}
