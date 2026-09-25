package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One owned, resumable subtree. No mutable solver state is shared between workers. */
final class IntegerCountBranch<K> implements AutoCloseable {

    enum State {
        OPEN,
        SPLIT,
        DEAD,
        UNRESOLVED,
        FOUND,
        PRUNED
    }

    final RecipeCountModel<K> model;
    final K target;
    final long amount, started;
    final Map<K, Long> stock, seeds;
    final Set<K> external;
    final boolean preserve, force;
    final PlanningBudget budget;
    final List<ExactLinearProgram.Constraint> current;
    final List<List<ExactLinearProgram.Constraint>> children = new ArrayList<>();
    final Set<ExactLinearProgram.Constraint> learnedMaterials = new LinkedHashSet<>();
    final List<SupportConflict> supportConflicts = new ArrayList<>();
    List<ExactLinearProgram.Constraint> linearConstraints;
    CountBounds propagating;
    ExactLinearProgram linear;
    CountSchedule<K> scheduling;
    CountProgram<K> program;
    AllocationSearch.Candidate<K> assembling;
    PlanVerification<K> verifying;
    BigInteger[] counts, lower;
    GraphPlan<K> plan;
    PlanPreference<K> preference, lowerCost;
    State state = State.OPEN;
    PlanningBudget.Exhausted limit;
    boolean initialized, partitioned, rescue, retried;
    long memory, workspace, work, schedulingWork;
    int globalRows;

    IntegerCountBranch(RecipeCountModel<K> model, K target, long amount, Map<K, Long> stock,
                       Map<K, Long> seeds, Set<K> external, boolean preserve, boolean force,
                       PlanningBudget budget, long started, List<ExactLinearProgram.Constraint> current) {
        this.model = model;
        this.target = target;
        this.amount = amount;
        this.stock = stock;
        this.seeds = seeds;
        this.external = external;
        this.preserve = preserve;
        this.force = force;
        this.budget = budget;
        this.started = started;
        this.current = List.copyOf(current);
        long bytes = 512L + 192L * current.size();
        if (budget.tryReserve(bytes)) memory = bytes;
        else state = State.UNRESOLVED;
    }

    void run(long quantum, List<ExactLinearProgram.Constraint> materials, List<SupportConflict> support,
             PlanPreference<K> incumbent, BooleanSupplier stopped) {
        long before = budget.threadWork();
        try {
            if (state != State.OPEN) return;
            if (workspace == 0) {
                if (!budget.tryReserve(2L << 20)) {
                    state = State.UNRESOLVED;
                    return;
                }
                workspace = 2L << 20;
            }
            if (!initialized) {
                initialized = true;
                supportConflicts.addAll(support);
                linearConstraints = new ArrayList<>(model.constraints);
                linearConstraints.addAll(materials);
                globalRows = linearConstraints.size();
                linearConstraints.addAll(current);
                propagating = new CountBounds(model.recipes.size(), linearConstraints, budget);
            }
            do {
                if (stopped.getAsBoolean()) return;
                budget.check();
                if (incumbent != null && lowerCost != null && lowerCost.cannotImprove(incumbent)) {
                    state = State.PRUNED;
                    return;
                }
                long scheduleBefore = budget.threadWork();
                boolean inSchedule = scheduling != null;
                advance();
                if (inSchedule) schedulingWork += budget.threadWork() - scheduleBefore;
                if (scheduling != null && !partitioned && schedulingWork >= 8192) {
                    // Keep this exact scheduling continuation, while other counts
                    // get disjoint sibling subspaces and their own work slices.
                    partitionCounts();
                    return;
                }
            } while (state == State.OPEN && budget.threadWork() - before < quantum);
        } catch (ExactRational.PrecisionLimit precision) {
            state = State.UNRESOLVED;
        } catch (PlanningBudget.Exhausted exhausted) {
            limit = exhausted;
            state = State.UNRESOLVED;
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private void advance() {
        if (verifying != null) {
            if (!verifying.step()) return;
            for (BigInteger initial : plan.initialExact().values()) {
                budget.check();
                if (initial.compareTo(ExactAmounts.LONG_MAX) > 0) {
                    unresolved();
                    return;
                }
            }
            preference = PlanPreference.of(plan, assembling.summary,
                    Arrays.stream(counts).reduce(BigInteger.ZERO, BigInteger::add));
            partitionCounts();
            state = State.FOUND;
            return;
        }
        if (assembling != null) {
            if (!assembling.step()) return;
            plan = assembling.plan;
            if (plan == null) {
                unresolved();
                return;
            }
            verifying = new PlanVerification<>(plan, budget);
            return;
        }
        if (program != null) {
            if (!program.step()) return;
            if (!program.available()) {
                state = State.UNRESOLVED;
                return;
            }
            beginAssembly(program.program());
            program.close();
            program = null;
            return;
        }
        if (scheduling != null) {
            if (!scheduling.step()) return;
            CountSchedule.Result status = scheduling.result();
            if (status == CountSchedule.Result.WITNESS) beginAssembly(scheduling.witness());
            scheduling.close();
            scheduling = null;
            if (status == CountSchedule.Result.DEAD) {
                partitionCounts();
                state = State.DEAD;
            } else if (status == CountSchedule.Result.UNKNOWN) unresolved();
            return;
        }
        if (rescue) {
            program = new CountProgram<>(model.recipes, counts, budget);
            return;
        }
        if (propagating != null) {
            if (!propagating.step()) return;
            boolean blocked = propagating.blocked();
            lower = propagating.lowerBounds();
            var tightened = propagating.tightened();
            propagating.close();
            propagating = null;
            if (blocked) {
                state = State.DEAD;
                return;
            }
            linearConstraints.addAll(tightened);
            linear = new ExactLinearProgram(model.recipes.size(), linearConstraints, model.objective(true), budget);
            return;
        }
        if (!linear.step()) return;
        var status = linear.result();
        ExactRational[] point = linear.point();
        if (status == ExactLinearProgram.Result.INFEASIBLE) learnMaterialConflict(linear.certificate());
        linear.close();
        linear = null;
        if (status == ExactLinearProgram.Result.INFEASIBLE) {
            state = State.DEAD;
            return;
        }
        if (status != ExactLinearProgram.Result.OPTIMAL) {
            state = State.UNRESOLVED;
            return;
        }
        ExactRational operations = ExactRational.ZERO;
        for (ExactRational value : point) operations = operations.add(value);
        lowerCost = PlanPreference.lowerBound(model, lower, seeds, operations.ceil(), budget);
        for (SupportConflict conflict : supportConflicts) if (violates(conflict, point)) {
            branch(conflict);
            state = State.SPLIT;
            return;
        }
        for (int i = 0; i < point.length; i++) if (!point[i].integral()) {
            enqueue(current, bound(i, point[i].floor(), false));
            enqueue(current, bound(i, point[i].ceil(), true));
            state = State.SPLIT;
            return;
        }
        counts = Arrays.stream(point).map(ExactRational::numerator).toArray(BigInteger[]::new);
        if (refineSupport()) {
            state = State.SPLIT;
            return;
        }
        scheduling = new CountSchedule<>(model, counts, budget);
    }

    private void beginAssembly(PlanStep witness) {
        Map<String, GraphRecipe<K>> recipes = new LinkedHashMap<>();
        model.recipes.forEach(recipe -> recipes.put(recipe.id(), recipe));
        assembling = new AllocationSearch.Candidate<>(witness, recipes, target, amount, stock, seeds, external,
                preserve, force, false, budget, started);
    }

    private void unresolved() {
        plan = null;
        partitionCounts();
        state = State.UNRESOLVED;
    }

    boolean resume() {
        if (retried || counts == null || memory == 0 || limit != null) return false;
        releaseWorkspace();
        initialized = true;
        retried = rescue = true;
        state = State.OPEN;
        return true;
    }

    void releaseWorkspace() {
        if (linear != null) linear.close();
        if (propagating != null) propagating.close();
        if (scheduling != null) scheduling.close();
        if (program != null) program.close();
        linear = null;
        propagating = null;
        scheduling = null;
        program = null;
        assembling = null;
        verifying = null;
        budget.release(workspace);
        workspace = 0;
    }

    private boolean refineSupport() {
        Set<K> reachable = new HashSet<>(external);
        stock.forEach((key, value) -> { if (value > 0) reachable.add(key); });
        BitSet fired = new BitSet();
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < counts.length; i++) {
                budget.check();
                if (counts[i].signum() == 0 || fired.get(i)) continue;
                GraphRecipe<K> recipe = model.recipes.get(i);
                if (consumedKeys(recipe).stream().anyMatch(key -> !reachable.contains(key))) continue;
                reachable.addAll(recipe.outputs().keySet());
                fired.set(i);
                changed = true;
            }
        } while (changed);
        for (int blocked = 0; blocked < counts.length; blocked++) if (counts[blocked].signum() > 0 && !fired.get(blocked)) {
            // If this transition is used, a first producer must introduce an
            // initially absent place without itself requiring an absent place.
            // Keep both possibilities: avoid the transition, or include a repair.
            Map<Integer, BigInteger> repairs = new LinkedHashMap<>();
            for (int i = 0; i < counts.length; i++) {
                budget.check();
                GraphRecipe<K> recipe = model.recipes.get(i);
                if (consumedKeys(recipe).stream().allMatch(reachable::contains) &&
                        recipe.outputs().keySet().stream().anyMatch(key -> model.ids.containsKey(key) && !reachable.contains(key)))
                    repairs.put(i, BigInteger.ONE.negate());
            }
            SupportConflict conflict = new SupportConflict(blocked, new ExactLinearProgram.Constraint(repairs, BigInteger.ONE.negate()));
            if (!supportConflicts.contains(conflict)) supportConflicts.add(conflict);
            branch(conflict);
            return true;
        }
        return false;
    }

    private Set<K> consumedKeys(GraphRecipe<K> recipe) {
        Set<K> keys = new LinkedHashSet<>(recipe.inputs().keySet());
        keys.removeIf(key -> recipe.inputs().get(key).longValue() == recipe.configurationInputs().getOrDefault(key, 0L));
        return keys;
    }

    private void partitionCounts() {
        if (partitioned) return;
        partitioned = true;
        // Disjoint lexicographic siblings cover every OTHER integer vector.
        // An undecided fixed vector stays owned by this retained branch.
        var prefix = new ArrayList<>(current);
        for (int i = 0; i < counts.length; i++) {
            if (counts[i].signum() > 0) enqueue(prefix, bound(i, counts[i].subtract(BigInteger.ONE), false));
            enqueue(prefix, bound(i, counts[i].add(BigInteger.ONE), true));
            prefix.add(bound(i, counts[i], false));
            prefix.add(bound(i, counts[i], true));
        }
    }

    record SupportConflict(int blocked, ExactLinearProgram.Constraint repair) {}

    private boolean violates(SupportConflict conflict, ExactRational[] point) {
        if (point[conflict.blocked()].signum() == 0) return false;
        ExactRational repairs = ExactRational.ZERO;
        for (int variable : conflict.repair().terms().keySet()) repairs = repairs.add(point[variable]);
        return repairs.compareTo(ExactRational.ONE) < 0;
    }

    private void branch(SupportConflict conflict) {
        enqueue(current, bound(conflict.blocked(), BigInteger.ZERO, false));
        if (!conflict.repair().terms().isEmpty()) {
            var required = new ArrayList<>(current);
            required.add(bound(conflict.blocked(), BigInteger.ONE, true));
            enqueue(required, conflict.repair());
        }
    }

    private void learnMaterialConflict(ExactRational[] proof) {
        if (learnedMaterials.size() >= 64) return;
        // Combine only globally valid material rows. The other Farkas terms
        // describe this branch; dropping those terms leaves a reusable resource
        // inequality that is valid for all branches of THIS inventory snapshot.
        BigInteger scale = BigInteger.ONE;
        for (int i = 0; i < globalRows; i++) {
            scale = scale.divide(scale.gcd(proof[i].denominator())).multiply(proof[i].denominator());
            if (scale.bitLength() > 2048) return;
        }
        Map<Integer, BigInteger> terms = new LinkedHashMap<>();
        BigInteger upper = BigInteger.ZERO;
        for (int i = 0; i < globalRows; i++) {
            BigInteger weight = proof[i].numerator().multiply(scale.divide(proof[i].denominator()));
            if (weight.signum() < 0) return;
            if (weight.signum() == 0) continue;
            upper = upper.add(linearConstraints.get(i).upper().multiply(weight));
            for (var term : linearConstraints.get(i).terms().entrySet()) {
                budget.check();
                terms.merge(term.getKey(), term.getValue().multiply(weight), BigInteger::add);
            }
        }
        terms.values().removeIf(value -> value.signum() == 0);
        if (terms.isEmpty()) return;
        BigInteger common = upper.abs();
        for (BigInteger coefficient : terms.values()) common = common.gcd(coefficient);
        BigInteger divisor = common;
        if (divisor.signum() > 0) {
            terms.replaceAll((key, value) -> value.divide(divisor));
            upper = upper.divide(divisor);
        }
        if (upper.bitLength() > 2048 || terms.values().stream().anyMatch(value -> value.bitLength() > 2048)) return;
        learnedMaterials.add(new ExactLinearProgram.Constraint(terms, upper));
    }

    private void enqueue(List<ExactLinearProgram.Constraint> prefix, ExactLinearProgram.Constraint constraint) {
        var next = new ArrayList<>(prefix);
        next.add(constraint);
        children.add(List.copyOf(next));
    }

    private static ExactLinearProgram.Constraint bound(int variable, BigInteger value, boolean lower) {
        return new ExactLinearProgram.Constraint(Map.of(variable, lower ? BigInteger.ONE.negate() : BigInteger.ONE), lower ? value.negate() : value);
    }

    @Override
    public void close() {
        releaseWorkspace();
        budget.release(memory);
        memory = 0;
    }
}
