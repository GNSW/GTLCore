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
    private final Set<K> producedKeys = new HashSet<>();
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
    private Candidate<K> checking;
    private DemandExpansion<K> expansion;
    private LinearMacroCompilation<K> macros;
    private GraphPlan<K> result;
    private final boolean preview;
    private final List<GraphCompiler.QuantityCertificate<K>> certificates;
    private long allocationStarted;

    AllocationSearch(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock, Set<K> external, Map<K, Long> requiredSeeds,
                     boolean preserve, boolean force, Set<String> excluded, PlanningBudget budget, long started) {
        this(compiler, target, amount, stock, external, requiredSeeds, preserve, force, excluded, budget, started, false);
    }

    AllocationSearch(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock, Set<K> external, Map<K, Long> requiredSeeds,
                     boolean preserve, boolean force, Set<String> excluded, PlanningBudget budget, long started, boolean preview) {
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
        this.preview = preview;
        certificates = preview ? List.of() : compiler.quantityCertificates(excluded);
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
                producedKeys.addAll(recipe.executionOutputs().keySet());
                for (K key : recipe.inputs().keySet()) keyIds.computeIfAbsent(key, ignored -> keyIds.size());
                for (K key : recipe.outputs().keySet()) keyIds.computeIfAbsent(key, ignored -> keyIds.size());
                reserve(256L + 128L * (recipe.inputs().size() + recipe.outputs().size()));
            } else {
                keyIds.computeIfAbsent(target, ignored -> keyIds.size());
                K refill = null;
                BigInteger refillGap = null;
                if (preview) for (K key : keyIds.keySet()) if (!producedKeys.contains(key) && !external.contains(key)) {
                    BigInteger gap = goals.getOrDefault(key, BigInteger.ZERO).subtract(BigInteger.valueOf(stock.getOrDefault(key, 0L)));
                    if (refillGap == null || gap.compareTo(refillGap) > 0) {
                        refill = key;
                        refillGap = gap;
                    }
                }
                for (K key : keyIds.keySet()) {
                    BigInteger available = BigInteger.valueOf(stock.getOrDefault(key, 0L));
                    // A one-unit diagnostic refill avoids fragmenting a large
                    // compressed loop at its very last missing raw input. This
                    // is hypothetical only: Candidate derives every borrowed
                    // unit again from the independently summarized program.
                    if (preview && key.equals(refill)) available = available.add(BigInteger.ONE);
                    // A finite, explicitly permitted external requirement for this
                    // candidate. This is not physical stock or a MAX_VALUE sentinel.
                    if (external.contains(key)) available = available.max(goals.getOrDefault(key, BigInteger.ZERO));
                    set(key, available, false);
                }
                stack.push(new Frame(0, 0));
                visit();
                Map<K, BigInteger> required = new LinkedHashMap<>();
                BigInteger targetGoal = BigInteger.valueOf(amount).add(BigInteger.valueOf(force ?
                        Math.max(stock.getOrDefault(target, 0L), requiredSeeds.getOrDefault(target, 0L)) : requiredSeeds.getOrDefault(target, 0L)));
                required.put(target, targetGoal);
                requiredSeeds.forEach((key, count) -> required.merge(key, BigInteger.valueOf(count), BigInteger::max));
                expansion = new DemandExpansion<>(relevant, held, required, budget, preview);
                phase = 4;
            }
            return false;
        }
        if (phase == 5) {
            if (!macros.step()) return false;
            relevant.clear();
            relevant.putAll(macros.recipes());
            recipes.addAll(relevant.values());
            phase = 1;
            return false;
        }
        if (phase == 3) return true;
        if (phase == 4) {
            if (!expansion.step()) return false;
            if (expansion.result() != null) checking = candidate(expansion.result());
            else {
                expansion.close();
                expansion = null;
            }
            phase = 2;
            allocationStarted = budget.nodes();
            return false;
        }
        if (checking != null) {
            if (!checking.step()) return false;
            result = checking.plan;
            checking = null;
            if (expansion != null) {
                expansion.close();
                expansion = null;
            }
            if (result != null) {
                finish();
                return true;
            }
        }
        if (preview && budget.nodes() - allocationStarted > 32_768L + 512L * recipes.size()) {
            finish();
            return true;
        }
        if (stack.isEmpty()) {
            finish();
            return true;
        }
        Frame frame = stack.peek();
        if (!frame.checkedGoal) {
            frame.checkedGoal = true;
            if (provenResourceConflict()) frame.recipe = recipes.size();
            BigInteger goal = BigInteger.valueOf(amount);
            if (force) goal = goal.add(BigInteger.valueOf(stock.getOrDefault(target, 0L)));
            if (held.getOrDefault(target, BigInteger.ZERO).compareTo(goal) >= 0) {
                checking = candidate(new PlanStep.Sequence(path));
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
        if (stack.size() >= 4096) {
            // This bounded strategy has not proved failure. Let the owning
            // planner try other source selections instead of aborting the order
            // long before its cumulative work/time/memory budget is exhausted.
            finish();
            return true;
        }
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
            if (relevant.size() >= 32) {
                macros = new LinearMacroCompilation<>(relevant, target, requiredSeeds.keySet(), budget);
                phase = 5;
            } else {
                recipes.addAll(relevant.values());
                phase = 1;
            }
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
        return ExactAmounts.capped(bound.min(useful.max(BigInteger.ONE)));
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

    /** Retain the compiled frontier while another bounded strategy tries its counts. */
    boolean readyForCountSearch() {
        return phase == 2 && checking == null && expansion == null;
    }

    void discard() {
        if (expansion != null) expansion.close();
        finish();
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

    private Candidate<K> candidate(PlanStep witness) {
        return new Candidate<>(macros == null ? witness : macros.expand(witness), relevant, target, amount, stock,
                requiredSeeds, external, preserve, force, preview, budget, started);
    }

    private boolean provenResourceConflict() {
        for (var certificate : certificates) {
            BigInteger available = BigInteger.ZERO, required = BigInteger.ZERO;
            boolean applicable = true;
            for (var entry : certificate.weights().entrySet()) {
                budget.check();
                K key = entry.getKey();
                if (external.contains(key) && entry.getValue().signum() != 0) {
                    applicable = false;
                    break;
                }
                BigInteger goal = BigInteger.valueOf(requiredSeeds.getOrDefault(key, 0L));
                if (key.equals(target)) {
                    if (force) goal = goal.max(BigInteger.valueOf(stock.getOrDefault(key, 0L)));
                    goal = goal.add(BigInteger.valueOf(amount));
                }
                required = required.add(entry.getValue().multiply(goal));
                available = available.add(entry.getValue().multiply(held.getOrDefault(key, BigInteger.ZERO)));
            }
            if (applicable && available.compareTo(required) < 0) return true;
        }
        return false;
    }

    /** Shared material/seed assembly for every native executable witness. */
    static final class Candidate<K> {

        final Map<String, GraphRecipe<K>> relevant;
        final K target;
        final long amount, started;
        final Map<K, Long> stock;
        final Set<K> external;
        final boolean preserve, force, preview;
        final PlanningBudget budget;

        final PlanStep witness;
        final Deque<PlanStep> collecting = new ArrayDeque<>();
        final Map<String, GraphRecipe<K>> used = new LinkedHashMap<>();
        final Map<K, List<GraphRecipe<K>>> producers = new HashMap<>();
        final Map<K, Long> seeds = new LinkedHashMap<>();
        final Map<K, BigInteger> initial = new LinkedHashMap<>();
        final Map<K, BigInteger> missing = new LinkedHashMap<>();
        final Deque<K> todo = new ArrayDeque<>();
        final Set<K> walked = new HashSet<>();
        SummaryComputation<K> computation;
        SequenceSummary<K> summary;
        Iterator<K> keys;
        K checkingKey;
        int stage;
        GraphPlan<K> plan;

        Candidate(PlanStep witness, Map<String, GraphRecipe<K>> relevant, K target, long amount, Map<K, Long> stock,
                  Map<K, Long> requiredSeeds, Set<K> external, boolean preserve, boolean force, boolean preview,
                  PlanningBudget budget, long started) {
            this.witness = witness;
            this.relevant = relevant;
            this.target = target;
            this.amount = amount;
            this.stock = stock;
            this.external = external;
            this.preserve = preserve;
            this.force = force;
            this.preview = preview;
            this.budget = budget;
            this.started = started;
            collecting.push(this.witness);
            seeds.putAll(requiredSeeds);
        }

        boolean step() {
            budget.check();
            if (stage == 0) {
                if (!collecting.isEmpty()) {
                    PlanStep step = collecting.pop();
                    if (step instanceof PlanStep.Repeat repeat) {
                        if (repeat.times() > 0) collecting.push(repeat.body());
                        return false;
                    }
                    if (step instanceof PlanStep.Sequence sequence) {
                        for (int i = sequence.children().size() - 1; i >= 0; i--) {
                            budget.check();
                            collecting.push(sequence.children().get(i));
                        }
                        return false;
                    }
                    String id = ((PlanStep.Batch) step).recipe();
                    GraphRecipe<K> recipe = relevant.get(id);
                    if (used.putIfAbsent(id, recipe) == null)
                        for (K key : recipe.executionOutputs().keySet()) producers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(recipe);
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
                    BigInteger required = summary.required(key).max(goal.subtract(summary.delta(key)));
                    if (!external.contains(key) && required.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) > 0) {
                        if (!preview) return true;
                        missing.put(key, required.subtract(BigInteger.valueOf(stock.getOrDefault(key, 0L))));
                    }
                    if (required.signum() > 0) initial.put(key, required);
                } else {
                    plan = new GraphPlan<>(target, amount, preserve, witness, used, initial, seeds, missing,
                            missing.isEmpty() ? GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL : GraphPlan.Result.MISSING_INPUT,
                            budget.nodes(), System.nanoTime() - started);
                    return true;
                }
            }
            return false;
        }
    }
}
