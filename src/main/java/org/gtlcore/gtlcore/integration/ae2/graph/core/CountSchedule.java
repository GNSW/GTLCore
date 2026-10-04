package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** Compressed scheduling of fixed integer counts, with exact small-multiset fallback. */
final class CountSchedule<K> implements AutoCloseable {

    enum Result {
        WITNESS,
        DEAD,
        UNKNOWN
    }

    private final RecipeCountModel<K> model;
    private final PlanningBudget budget;
    private final BigInteger[] original;
    private final boolean balancedFirst;
    private final Map<String, GraphRecipe<K>> recipes = new LinkedHashMap<>();
    private final List<SequenceSummary<K>> summaries = new ArrayList<>();
    private final Map<K, List<Integer>> consumers = new HashMap<>();
    private final Map<K, BigInteger> held = new LinkedHashMap<>();
    private final List<PlanStep> program = new ArrayList<>(), pass = new ArrayList<>();
    private final Deque<State> pending = new ArrayDeque<>();
    private final Map<List<BigInteger>, List<BitSet>> seen = new HashMap<>();
    private PartialOrder<K> partialOrder;
    private int labels;
    private BigInteger[] remaining, used;
    private SummaryComputation<K> summarizing;
    private PlanStep passBody, witness;
    private int attempt, cursor, passes;
    private boolean exact, startupChecked;
    private int[] components;
    private int independentComponents;
    private long orderedAway;
    private Result result;
    private CountRecurrence<K> recurrence;
    private CountBoundedSchedule<K> bounded;
    private boolean boundedTried;
    private int[] repairOrder;
    private boolean repairTried, repairingOrder;
    private boolean preferEnabled;
    private List<SequenceSummary<K>> exactActions;
    private boolean reverseExact, reverseTried;
    private long memory, labelMemory, programMemory;
    private CountScheduleOrder<K> guidedOrder;
    private final BitSet visited = new BitSet();
    private boolean guidedTried, guiding;

    private enum WitnessSource {
        RECURRENCE,
        GREEDY,
        EXACT,
        BOUNDED
    }

    private WitnessSource witnessSource;
    private boolean rejectedWitness, retainWitness;

    CountSchedule(RecipeCountModel<K> model, BigInteger[] counts, PlanningBudget budget) {
        this(model, counts, budget, false);
    }

    CountSchedule(RecipeCountModel<K> model, BigInteger[] counts, PlanningBudget budget, boolean balancedFirst) {
        this.model = model;
        this.budget = budget;
        this.balancedFirst = balancedFirst;
        original = counts.clone();
        long incidences = model.recipes.stream().mapToLong(r -> r.inputs().size() + r.outputs().size()).sum();
        long bytes = 4096 + 512L * model.recipes.size() + 512L * incidences + 128L * model.keys.size();
        if (!budget.tryReserve(bytes)) {
            result = Result.UNKNOWN;
            return;
        }
        memory = bytes;
        try {
            for (GraphRecipe<K> recipe : model.recipes) {
                recipes.put(recipe.id(), recipe);
                SequenceSummary<K> summary = SequenceSummary.recipe(recipe);
                int index = summaries.size();
                summary.delta().forEach((key, amount) -> {
                    if (amount.signum() < 0 && !model.external.contains(key))
                        consumers.computeIfAbsent(key, unused -> new ArrayList<>()).add(index);
                });
                summaries.add(summary);
            }
            reset();
        } catch (RuntimeException | Error error) {
            close();
            throw error;
        }
    }

    boolean step() {
        budget.check();
        if (result != null) return true;
        if (bounded != null) {
            if (!bounded.step()) return false;
            witness = bounded.witness();
            witnessSource = WitnessSource.BOUNDED;
            Result outcome = bounded.result();
            bounded.close();
            bounded = null;
            return finish(outcome);
        }
        if (!startupChecked) {
            startupChecked = true;
            if (blockedStartup()) return finish(Result.DEAD);
            recurrence = new CountRecurrence<>(model, original, summaries, budget);
        }
        if (recurrence != null) {
            if (!recurrence.step()) return false;
            witness = recurrence.witness();
            recurrence.close();
            recurrence = null;
            if (witness != null) {
                witnessSource = WitnessSource.RECURRENCE;
                return finish(Result.WITNESS);
            }
        }
        if (exact) return exactStep();
        if (summarizing != null) {
            if (!summarizing.step()) return false;
            SequenceSummary<K> summary = summarizing.result();
            summarizing = null;
            BigInteger extra = null;
            for (int i = 0; i < used.length; i++) if (used[i].signum() > 0) {
                BigInteger bound = remaining[i].divide(used[i]);
                extra = extra == null ? bound : extra.min(bound);
            }
            extra = limit(summary, extra == null ? BigInteger.ZERO : extra);
            if (extra.signum() > 0) {
                apply(summary, extra);
                for (int i = 0; i < used.length; i++) remaining[i] = remaining[i].subtract(used[i].multiply(extra));
            }
            if (!reserveProgram(extra)) return finish(Result.UNKNOWN);
            program.add(PlanStep.repeat(passBody, extra.add(BigInteger.ONE)));
            pass.clear();
            Arrays.fill(used, BigInteger.ZERO);
            cursor = 0;
            visited.clear();
            if (done()) {
                witness = new PlanStep.Sequence(program);
                witnessSource = WitnessSource.GREEDY;
                return finish(Result.WITNESS);
            }
            if (++passes >= (repairingOrder || guiding ? 8 : 512)) return nextAttempt();
            return false;
        }
        if (cursor < model.recipes.size()) {
            int count = model.recipes.size(), orders = Math.min(4, count);
            int variant = attempt % (2 * orders), mode = attempt / (2 * orders);
            if (!balancedFirst && mode < 3) mode = (mode + 2) % 3;
            int rotation = variant % orders;
            int recipe = (cursor++ + rotation) % count;
            if (variant >= orders) recipe = count - 1 - recipe;
            if (repairingOrder) recipe = repairOrder[cursor - 1];
            if (guiding) {
                recipe = guidedOrder.choose(held, remaining, visited);
                visited.set(recipe);
            }
            BigInteger runs = limit(summaries.get(recipe), remaining[recipe]);
            // Maximal batches can drain a shared cycle resource into one branch
            // before its competing producer runs. Try leaving some funded work
            // for that branch; only the resulting exact witness is accepted.
            if (mode == 0 && runs.signum() > 0) runs = share(recipe, runs);
            else if (mode == 1 && runs.signum() > 0) runs = runs.divide(BigInteger.TWO).max(BigInteger.ONE);
            else if (mode == 3) runs = runs.min(BigInteger.ONE);
            if (guiding && runs.signum() > 0) runs = guidedOrder.batch(recipe, runs, held, remaining);
            if (runs.signum() > 0) {
                if (!reserveProgram(runs)) return finish(Result.UNKNOWN);
                apply(summaries.get(recipe), runs);
                remaining[recipe] = remaining[recipe].subtract(runs);
                used[recipe] = used[recipe].add(runs);
                pass.add(PlanStep.batch(model.recipes.get(recipe).id(), runs));
            }
            return false;
        }
        if (pass.isEmpty()) {
            if (done()) {
                witness = new PlanStep.Sequence(program);
                witnessSource = WitnessSource.GREEDY;
                return finish(Result.WITNESS);
            }
            if (!guiding && !repairTried && prepareRepairOrder()) {
                repairingOrder = true;
                reset();
                return false;
            }
            return nextAttempt();
        }
        passBody = new PlanStep.Sequence(pass);
        summarizing = new SummaryComputation<>(passBody, recipes, budget);
        return false;
    }

    private BigInteger limit(SequenceSummary<K> summary, BigInteger maximum) {
        if (maximum.signum() == 0) return maximum;
        for (K key : summary.keys()) if (!model.external.contains(key)) {
            budget.check();
            BigInteger available = held.getOrDefault(key, BigInteger.ZERO);
            if (available.compareTo(summary.required(key)) < 0) return BigInteger.ZERO;
            if (summary.delta(key).signum() < 0)
                maximum = maximum.min(available.subtract(summary.required(key)).divide(summary.delta(key).negate()).add(BigInteger.ONE));
        }
        return maximum;
    }

    private boolean reserveProgram(BigInteger copies) {
        long bytes = 256L + 96L * (copies.bitLength() / 63);
        if (!budget.tryReserve(bytes)) return false;
        memory += bytes;
        programMemory += bytes;
        return true;
    }

    private BigInteger share(int recipe, BigInteger maximum) {
        for (var delta : summaries.get(recipe).delta().entrySet()) {
            if (delta.getValue().signum() >= 0) continue;
            List<Integer> peers = consumers.getOrDefault(delta.getKey(), List.of());
            if (peers.size() < 2) continue;
            BigInteger consumption = BigInteger.ZERO;
            for (int peer : peers) {
                budget.check();
                consumption = consumption.subtract(summaries.get(peer).delta(delta.getKey()).multiply(remaining[peer]));
            }
            if (consumption.signum() > 0) {
                BigInteger share = held.getOrDefault(delta.getKey(), BigInteger.ZERO).multiply(remaining[recipe]).divide(consumption);
                maximum = maximum.min(share.max(BigInteger.ONE));
            }
        }
        return maximum;
    }

    private void apply(SequenceSummary<K> summary, BigInteger copies) {
        for (var delta : summary.delta().entrySet()) if (!model.external.contains(delta.getKey())) {
            budget.check();
            held.merge(delta.getKey(), delta.getValue().multiply(copies), BigInteger::add);
        }
    }

    private void reset() {
        remaining = original.clone();
        used = new BigInteger[original.length];
        Arrays.fill(used, BigInteger.ZERO);
        held.clear();
        model.stock.forEach((key, amount) -> held.put(key, BigInteger.valueOf(amount)));
        program.clear();
        pass.clear();
        budget.release(programMemory);
        memory -= programMemory;
        programMemory = 0;
        passes = cursor = 0;
        visited.clear();
    }

    private boolean done() {
        return Arrays.stream(remaining).allMatch(value -> value.signum() == 0);
    }

    private boolean blockedStartup() {
        if (model.recipes.stream().anyMatch(GraphRecipe::batchSensitiveInputs)) return false;
        Map<K, BigInteger> upper = new HashMap<>();
        model.stock.forEach((key, value) -> upper.put(key, BigInteger.valueOf(value)));
        BitSet available = new BitSet();
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < original.length; i++) {
                budget.check();
                if (original[i].signum() == 0 || available.get(i)) continue;
                GraphRecipe<K> recipe = model.recipes.get(i);
                if (recipe.inputs().entrySet().stream().anyMatch(e -> !model.external.contains(e.getKey()) &&
                        upper.getOrDefault(e.getKey(), BigInteger.ZERO).compareTo(BigInteger.valueOf(e.getValue())) < 0))
                    continue;
                available.set(i);
                BigInteger count = original[i];
                // Optimistically grant ALL outputs without consuming any input.
                // A transition still unreachable here cannot occur in any
                // ordering of this fixed multiset, even with enormous counts.
                recipe.outputs().forEach((key, value) -> upper.merge(key, BigInteger.valueOf(value).multiply(count), BigInteger::add));
                changed = true;
            }
        } while (changed);
        for (int i = 0; i < original.length; i++) if (original[i].signum() > 0 && !available.get(i)) return true;
        return false;
    }

    private boolean nextAttempt() {
        if (!guidedTried && !smallMultiset()) {
            guidedTried = true;
            guidedOrder = new CountScheduleOrder<>(model, summaries, budget);
            if (guidedOrder.available()) {
                guiding = true;
                repairingOrder = false;
                reset();
                budget.note("count_schedule_order", "ready_unlock_and_return_pools; candidate_only");
                return false;
            }
        }
        guiding = false;
        if (guidedOrder != null) {
            guidedOrder.close();
            guidedOrder = null;
        }
        // A guided attempt is additional. It does not replace any of the old
        // rotations, batching modes or exact scheduling continuations.
        repairingOrder = false;
        boolean early = ++attempt < 8 * Math.min(4, model.recipes.size());
        if (early && (rejectedWitness || attempt != 2 || !smallMultiset())) {
            reset();
            return false;
        }
        // Only exhaustive exploration may turn a scheduling failure into a
        // counterexample. A greedy failure, depth cap, or memory cap never does.
        if (model.recipes.stream().anyMatch(GraphRecipe::batchSensitiveInputs)) return finish(Result.UNKNOWN);
        components = independentComponents();
        BitSet activeComponents = new BitSet();
        for (int i = 0; i < original.length; i++) if (original[i].signum() > 0) {
            activeComponents.set(components[i]);
        }
        independentComponents = activeComponents.cardinality();
        long bytes = 4096 + 64L * original.length + (long) original.length * original.length / 4;
        if (!budget.tryReserve(bytes)) {
            if (!early) return finish(Result.UNKNOWN);
            independentComponents = 0;
            reset();
            return false;
        }
        memory += bytes;
        partialOrder = new PartialOrder<>(summaries, budget);
        exactActions = summaries;
        long incidences = summaries.stream().mapToLong(summary -> summary.required().size()).sum();
        preferEnabled = original.length <= 64 && model.keys.size() <= 256 &&
                original.length * incidences <= 4096 && !smallMultiset();
        List<BigInteger> counts = List.copyOf(Arrays.asList(original));
        if (!remember(counts, new BitSet())) return finish(Result.UNKNOWN);
        pending.add(new State(counts, new BitSet(), null, -1));
        exact = true;
        return false;
    }

    private boolean prepareRepairOrder() {
        repairTried = true;
        if (original.length > 256 || model.keys.size() > 1024 || budget.remainingWork() < 4096) return false;
        // The existing exact fallback is cheaper on small multisets. A failed
        // initial marking also offers no consumed-resource ordering feedback.
        if (program.isEmpty() || smallMultiset()) return false;
        int scans = 0;
        Set<K> missing = new LinkedHashSet<>();
        for (int i = 0; i < remaining.length; i++) if (remaining[i].signum() > 0) {
            for (var input : summaries.get(i).required().entrySet()) {
                if (++scans > 2048) return false;
                budget.check();
                if (!model.external.contains(input.getKey()) &&
                        held.getOrDefault(input.getKey(), BigInteger.ZERO).compareTo(input.getValue()) < 0)
                    missing.add(input.getKey());
            }
        }
        if (missing.isEmpty()) return false;
        // Retain the failed marking's resource region as an ordering hint.
        // Trace suppliers backwards, including already consumed producers:
        // they may have been run too early or drained by an unrelated sink.
        // No absence or heuristic closure here is an infeasibility proof.
        var suppliers = new HashMap<K, List<Integer>>();
        for (int i = 0; i < original.length; i++) if (original[i].signum() > 0) {
            for (var output : summaries.get(i).delta().entrySet()) {
                if (++scans > 2048) return false;
                budget.check();
                if (output.getValue().signum() > 0)
                    suppliers.computeIfAbsent(output.getKey(), ignored -> new ArrayList<>()).add(i);
            }
        }
        BitSet preferred = new BitSet();
        var pendingKeys = new ArrayDeque<>(missing);
        while (!pendingKeys.isEmpty()) for (int i : suppliers.getOrDefault(pendingKeys.removeFirst(), List.of())) {
            if (++scans > 2048) return false;
            budget.check();
            if (preferred.get(i)) continue;
            preferred.set(i);
            for (K input : summaries.get(i).required().keySet()) {
                if (++scans > 2048) return false;
                budget.check();
                if (!model.external.contains(input) && missing.add(input)) pendingKeys.addLast(input);
            }
        }
        if (preferred.isEmpty()) return false;
        repairOrder = new int[original.length];
        int at = 0;
        for (int i = 0; i < original.length; i++) if (preferred.get(i)) repairOrder[at++] = i;
        for (int i = 0; i < original.length; i++) if (!preferred.get(i)) repairOrder[at++] = i;
        boolean changed = false;
        int orders = Math.min(4, original.length), variant = attempt % (2 * orders);
        for (int i = 0; i < repairOrder.length; i++) {
            int previous = (i + variant % orders) % original.length;
            if (variant >= orders) previous = original.length - 1 - previous;
            changed |= repairOrder[i] != previous;
        }
        if (changed) budget.note("count_schedule_repair", "preferred_recipes=" + preferred.cardinality() + "; resources=" + missing.size());
        return changed;
    }

    private boolean smallMultiset() {
        if (model.recipes.stream().anyMatch(GraphRecipe::batchSensitiveInputs)) return false;
        BigInteger states = BigInteger.ONE, total = BigInteger.ZERO;
        int active = 0;
        for (BigInteger count : original) if (count.signum() > 0) {
            budget.check();
            states = states.multiply(count.add(BigInteger.ONE));
            total = total.add(count);
            if (++active > 12 || total.compareTo(BigInteger.valueOf(24)) > 0 || states.compareTo(BigInteger.valueOf(2048)) > 0) return false;
        }
        return true;
    }

    /** A bounded supply-region hint in this model's recipe coordinates; never a cut. */
    BitSet failureRegion() {
        BitSet region = new BitSet();
        if (reverseExact || remaining == null || summaries.size() != original.length || original.length > 256 || model.keys.size() > 1024)
            return region;
        int scans = 0;
        Set<K> missing = new LinkedHashSet<>();
        for (int i = 0; i < remaining.length; i++) if (remaining[i].signum() > 0) {
            for (var input : summaries.get(i).required().entrySet()) {
                if (++scans > 4096) return region;
                budget.check();
                if (!model.external.contains(input.getKey()) && held.getOrDefault(input.getKey(), BigInteger.ZERO).compareTo(input.getValue()) < 0) {
                    missing.add(input.getKey());
                    region.set(i);
                }
            }
        }
        if (missing.isEmpty()) return region;
        Map<K, List<Integer>> suppliers = new HashMap<>();
        // Include unused sources. Restricting this to the failed support would
        // prevent the neighborhood from acquiring a missing startup seed.
        for (int i = 0; i < original.length; i++) for (var delta : summaries.get(i).delta().entrySet()) {
            if (++scans > 4096) return region;
            budget.check();
            if (delta.getValue().signum() > 0 && !model.external.contains(delta.getKey()))
                suppliers.computeIfAbsent(delta.getKey(), unused -> new ArrayList<>()).add(i);
        }
        BitSet traced = new BitSet();
        var pendingKeys = new ArrayDeque<>(missing);
        while (!pendingKeys.isEmpty()) for (int i : suppliers.getOrDefault(pendingKeys.removeFirst(), List.of())) {
            if (++scans > 4096) return region;
            budget.check();
            if (traced.get(i)) continue;
            traced.set(i);
            region.set(i);
            for (K key : summaries.get(i).required().keySet()) {
                if (++scans > 4096) return region;
                budget.check();
                if (!model.external.contains(key) && missing.add(key)) pendingKeys.addLast(key);
            }
        }
        return region;
    }

    private boolean exactStep() {
        if (pending.isEmpty()) return finish(Result.DEAD);
        State state = pending.removeLast();
        if (state.counts.stream().allMatch(value -> value.signum() == 0)) {
            var path = new ArrayList<PlanStep>();
            for (State current = state; current.parent != null; current = current.parent)
                path.add(new PlanStep.Batch(model.recipes.get(current.recipe).id(), 1));
            if (!reverseExact) Collections.reverse(path);
            witness = new PlanStep.Sequence(path);
            witnessSource = WitnessSource.EXACT;
            return finish(Result.WITNESS);
        }
        held.clear();
        model.stock.forEach((key, value) -> held.put(key, BigInteger.valueOf(value)));
        // Undoing from the unique final marking is equivalent for a fixed
        // sequential multiset. It often exposes a forced last firing that is
        // hidden among many possible first firings.
        for (int i = 0; i < original.length; i++)
            apply(summaries.get(i), reverseExact ? state.counts.get(i) : original[i].subtract(state.counts.get(i)));
        int component = -1;
        for (int i = 0; i < original.length; i++) if (state.counts.get(i).signum() > 0) {
            component = components[i];
            break;
        }
        BitSet sleeping = (BitSet) state.sleeping.clone();
        BitSet available = new BitSet(), active = new BitSet();
        for (int i = 0; i < original.length; i++) if (state.counts.get(i).signum() > 0 && components[i] == component) {
            active.set(i);
            if (limit(exactActions.get(i), BigInteger.ONE).signum() > 0) available.set(i);
        }
        BitSet persistent = partialOrder.persistent(active, available, held, model.external);
        var children = new ArrayList<State>();
        var order = new ArrayList<Integer>();
        for (int i = 0; i < original.length; i++) if (state.counts.get(i).signum() > 0) order.add(i);
        if (preferEnabled && available.cardinality() > 1) {
            int[] scores = new int[original.length];
            for (int i = available.nextSetBit(0); i >= 0; i = available.nextSetBit(i + 1))
                scores[i] = enabledScore(i, state.counts);
            order.sort(Comparator.<Integer>comparingInt(i -> -scores[i]).thenComparingInt(i -> i));
        }
        for (int i : order) {
            if (components[i] != component) {
                orderedAway++;
                continue;
            }
            if (!available.get(i)) continue;
            if (sleeping.get(i) || !persistent.get(i)) {
                orderedAway++;
                continue;
            }
            var next = new ArrayList<>(state.counts);
            next.set(i, next.get(i).subtract(BigInteger.ONE));
            var frozen = List.copyOf(next);
            BitSet childSleep = partialOrder.after(sleeping, i);
            sleeping.set(i);
            if (remember(frozen, childSleep)) children.add(new State(frozen, childSleep, state, i));
            if (labels >= 8192 || result != null) return finish(Result.UNKNOWN);
        }
        // Explore the earlier alternative before its sleeping equivalents.
        for (int i = children.size() - 1; i >= 0; i--) pending.addLast(children.get(i));
        return false;
    }

    /** Prefer firings that keep the remaining startup requirements funded. No order is excluded. */
    private int enabledScore(int action, List<BigInteger> counts) {
        int score = 0;
        for (int other = 0; other < counts.size(); other++) {
            if (counts.get(other).signum() == 0 || other == action && counts.get(other).equals(BigInteger.ONE)) continue;
            int support = 1024;
            for (var input : exactActions.get(other).required().entrySet()) {
                budget.check();
                if (model.external.contains(input.getKey())) continue;
                BigInteger amount = held.getOrDefault(input.getKey(), BigInteger.ZERO).add(exactActions.get(action).delta(input.getKey()));
                if (amount.compareTo(input.getValue()) < 0)
                    support = Math.min(support, amount.max(BigInteger.ZERO).multiply(BigInteger.valueOf(1024)).divide(input.getValue()).intValue());
            }
            score += support;
        }
        return score;
    }

    private boolean remember(List<BigInteger> counts, BitSet sleeping) {
        List<BitSet> previous = seen.get(counts);
        if (previous != null && previous.stream().anyMatch(old -> PartialOrder.subset(old, sleeping))) return false;
        long bytes = 160 + 48L * original.length;
        if (!budget.tryReserve(bytes)) {
            result = Result.UNKNOWN;
            return false;
        }
        memory += bytes;
        labelMemory += bytes;
        labels++;
        // A marking reached with MORE sleeping actions must not suppress a
        // later arrival that can explore additional orders.
        if (previous == null) seen.put(counts, previous = new ArrayList<>());
        previous.removeIf(old -> PartialOrder.subset(sleeping, old));
        previous.add((BitSet) sleeping.clone());
        return true;
    }

    private record State(List<BigInteger> counts, BitSet sleeping, State parent, int recipe) {}

    private int[] independentComponents() {
        int[] root = new int[original.length];
        for (int i = 0; i < root.length; i++) root[i] = i;
        Map<K, Integer> touched = new HashMap<>();
        for (int i = 0; i < original.length; i++) if (original[i].signum() > 0) {
            Set<K> keys = new HashSet<>(model.recipes.get(i).inputs().keySet());
            keys.addAll(model.recipes.get(i).outputs().keySet());
            for (K key : keys) {
                budget.check();
                Integer other = touched.putIfAbsent(key, i);
                if (other != null) {
                    int a = component(root, i), b = component(root, other);
                    root[Math.max(a, b)] = Math.min(a, b);
                }
            }
        }
        for (int i = 0; i < root.length; i++) root[i] = component(root, i);
        return root;
    }

    private static int component(int[] root, int id) {
        while (root[id] != id) {
            root[id] = root[root[id]];
            id = root[id];
        }
        return id;
    }

    PlanStep witness() {
        return witness;
    }

    Result result() {
        return result;
    }

    boolean resumableIn(RecipeCountModel<K> owner, PlanningBudget request) {
        return model == owner && budget == request && !balancedFirst && result == null && memory != 0;
    }

    /** Exact original coordinates; the owner keeps the immutable model and request scope. */
    boolean sameCounts(BigInteger[] counts) {
        if (counts.length != original.length) return false;
        for (int i = 0; i < original.length; i++) {
            budget.check();
            if (!original[i].equals(counts[i])) return false;
        }
        return true;
    }

    /** Assembly can reject an executable ordering without rejecting its count vector. */
    CountSchedule<K> retainWitnessForAssembly() {
        retainWitness = true;
        return this;
    }

    /** Assembly can reject an executable ordering without rejecting its count vector. */
    boolean retryAfterRejectedWitness() {
        if (result != Result.WITNESS || memory == 0) return false;
        rejectedWitness = true;
        result = null;
        witness = null;
        budget.note("count_schedule_order", "assembly_rejected; same_counts_alternative_order");
        switch (witnessSource) {
            case RECURRENCE -> reset();
            case GREEDY -> nextAttempt();
            case EXACT -> {
                // Continue remaining prefixes. Marking/POR deduplication is
                // exact for executability, not for inferred seed requirements.
            }
            case BOUNDED -> finish(Result.UNKNOWN);
        }
        return result == null;
    }

    private boolean finish(Result value) {
        // Once any ordering was executable, exhausting other orderings must
        // not create a count-wide impossibility certificate. Seed/force checks
        // may distinguish prefixes collapsed by the scheduling state cache.
        if (value == Result.DEAD && rejectedWitness) value = Result.UNKNOWN;
        if (value == Result.UNKNOWN && exact && preferEnabled && !reverseExact) {
            // Ordering is a hint, not a replacement for the old DFS. If its
            // local state cap is hit, release only its labels and retry the
            // original order with fresh sleep sets. No failed prefix is cached.
            budget.note("count_schedule_order", "preferred_cutoff; labels=" + labels + "; original_order_retained");
            preferEnabled = false;
            if (restartExact()) return false;
        }
        if (value == Result.UNKNOWN && exact && !reverseTried && prepareReverse()) return false;
        if (value == Result.UNKNOWN && !boundedTried) {
            boundedTried = true;
            bounded = new CountBoundedSchedule<>(model, original, budget);
            if (bounded.result() == null) {
                result = null;
                return false;
            }
            bounded.close();
            bounded = null;
        }
        if (exact) budget.note("count_schedule_por", "components=" + independentComponents +
                "; states=" + seen.size() + "; sleep_labels=" + labels + "; independent_interleavings_skipped=" + orderedAway +
                "; reverse=" + reverseExact + "; result=" + value);
        result = value;
        // The caller may reject this prefix after exact seed/production
        // assembly. Keep its reservation until accepted, retried, or closed.
        if (value != Result.WITNESS || !retainWitness) close();
        return true;
    }

    private boolean restartExact() {
        pending.clear();
        seen.clear();
        budget.release(labelMemory);
        memory -= labelMemory;
        labelMemory = 0;
        labels = 0;
        result = null;
        List<BigInteger> counts = List.copyOf(Arrays.asList(original));
        if (!remember(counts, new BitSet())) return false;
        pending.add(new State(counts, new BitSet(), null, -1));
        return true;
    }

    private boolean prepareReverse() {
        reverseTried = true;
        if (original.length > 64 || model.keys.size() > 256 || budget.remainingWork() < 32768) return false;
        long incidences = summaries.stream().mapToLong(summary -> summary.required().size() + summary.delta().size()).sum();
        if (original.length * incidences > 8192) return false;
        long bytes = 1024L + 384L * incidences;
        if (!budget.tryReserve(bytes)) return false;
        memory += bytes;
        var actions = new ArrayList<SequenceSummary<K>>();
        for (var summary : summaries) {
            Map<K, BigInteger> need = new LinkedHashMap<>(), delta = new LinkedHashMap<>(), peak = new LinkedHashMap<>();
            for (K key : summary.keys()) {
                budget.check();
                BigInteger change = summary.delta(key).negate();
                BigInteger required = summary.required(key).add(summary.delta(key));
                if (required.signum() > 0) need.put(key, required);
                delta.put(key, change);
                peak.put(key, change.max(BigInteger.ZERO));
            }
            actions.add(new SequenceSummary<>(need, delta, peak));
        }
        exactActions = actions;
        reverseExact = preferEnabled = true;
        partialOrder = new PartialOrder<>(actions, budget);
        budget.note("count_schedule_order", "reverse_fixed_multiset; original_prefix_verification_required");
        return restartExact();
    }

    @Override
    public void close() {
        if (guidedOrder != null) {
            guidedOrder.close();
            guidedOrder = null;
        }
        if (bounded != null) {
            bounded.close();
            bounded = null;
        }
        if (summarizing != null) summarizing.close();
        summarizing = null;
        if (recurrence != null) {
            recurrence.close();
            recurrence = null;
        }
        budget.release(memory);
        memory = 0;
        labelMemory = 0;
        programMemory = 0;
    }
}
