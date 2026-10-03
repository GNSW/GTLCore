package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Bounded branch search with retained continuations, shared proofs and verified incumbents. */
final class IntegerCountSearch<K> implements AutoCloseable {

    private final RecipeCountModel<K> model;
    private final GraphCompiler<K> compiler;
    private final CountExecution<K> execution;
    private final K target;
    private final long amount, started, preprocessingAllowance;
    private long allowance, scoutMaximum;
    private final Map<K, Long> stock, seeds;
    private final Set<K> external;
    private final boolean preserve, force;
    private final boolean compileRecovery;
    private final PlanningBudget budget;
    private final Deque<IntegerCountBranch<K>> pending = new ArrayDeque<>(), deferred = new ArrayDeque<>();
    private final CountCutPool materialConflicts;
    private final Set<CountGuard> supportConflicts = new LinkedHashSet<>();
    private final CountConflictPool choiceConflicts;
    private final OrderProofs<K> proofs;
    private CountBranchHistory branchHistory;
    private final List<Incumbent<K>> frontier = new ArrayList<>();
    private final AtomicBoolean stopped = new AtomicBoolean(), released = new AtomicBoolean();
    private CompletableFuture<List<IntegerCountBranch<K>>> running;
    private List<IntegerCountBranch<K>> dispatched = List.of();
    private Incumbent<K> best;
    private boolean complete, infeasible, unresolved, repairScheduled, paused;
    private boolean scouting;
    private boolean repairScoutExtended;
    private boolean portfolioScheduled;
    private long work, improvementUntil = Long.MAX_VALUE, firstWitnessWork = -1, firstWitnessNanos;
    private int branches, rounds, suspensions, boundPrunes, choicePrunes, peakWidth;

    private record Incumbent<K>(GraphPlan<K> plan, PlanPreference<K> cost, long memory) {}

    IntegerCountSearch(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
                       Map<K, Long> seeds, Set<K> external, Set<String> excluded, boolean preserve, boolean force,
                       PlanningBudget budget, long started) {
        this(compiler, target, amount, stock, seeds, external, excluded, preserve, force, budget, started, null);
    }

    IntegerCountSearch(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
                       Map<K, Long> seeds, Set<K> external, Set<String> excluded, boolean preserve, boolean force,
                       PlanningBudget budget, long started, OrderProofs<K> proofs) {
        this(compiler, target, amount, stock, seeds, external, excluded, preserve, force, budget, started, proofs, true);
    }

    IntegerCountSearch(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
                       Map<K, Long> seeds, Set<K> external, Set<String> excluded, boolean preserve, boolean force,
                       PlanningBudget budget, long started, OrderProofs<K> proofs, boolean compileRecovery) {
        this.compileRecovery = compileRecovery;
        this.compiler = compiler;
        this.target = target;
        this.amount = amount;
        this.stock = Map.copyOf(stock);
        this.seeds = Map.copyOf(seeds);
        this.external = Set.copyOf(external);
        this.preserve = preserve;
        this.force = force;
        this.budget = budget;
        this.proofs = proofs;
        choiceConflicts = new CountConflictPool(budget);
        materialConflicts = new CountCutPool(budget);
        this.started = started;
        allowance = Math.min(2_000_000, budget.remainingWork() / 4);
        preprocessingAllowance = Math.min(16_000_000, budget.remainingWork() / 5 * 4);
        model = RecipeCountModel.create(compiler, target, amount, this.stock, this.seeds, this.external, excluded, force, budget);
        try {
            execution = model == null ? null : new CountExecution<>(model, budget);
        } catch (RuntimeException | Error failure) {
            if (model != null) model.close();
            choiceConflicts.close();
            throw failure;
        }
        if (model == null) {
            complete = true;
            close();
            return;
        }
        if (proofs != null) {
            proofs.adopt(model);
            choiceConflicts.add(proofs.forModel(model));
        }
        try {
            branchHistory = new CountBranchHistory(model.recipes.size(), budget);
            choiceConflicts.add(compiler.countSessions.reuse(model, budget));
            enqueue(List.of());
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    boolean step() {
        return step(null);
    }

    /** Pull original order clauses into an optional program view. No reverse inference is made. */
    void importProgramConflicts(RecipeCountModel<K> original, Map<String, PlanStep> programs, List<CountConflict> conflicts) {
        if (model == null || conflicts.isEmpty() || work != 0) return;
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < original.recipes.size(); i++) positions.put(original.recipes.get(i).id(), i);
        List<Map<Integer, BigInteger>> terms = new ArrayList<>();
        for (int i = 0; i < original.recipes.size(); i++) terms.add(new LinkedHashMap<>());
        for (int i = 0; i < model.recipes.size(); i++) {
            String id = model.recipes.get(i).id();
            Map<String, BigInteger> counts = programs.containsKey(id) ? PlanCountComputation.of(programs.get(id)) : Map.of(id, BigInteger.ONE);
            for (var entry : counts.entrySet()) {
                budget.check();
                Integer position = positions.get(entry.getKey());
                if (position == null) throw new IllegalArgumentException("Unmapped program recipe " + entry.getKey());
                terms.get(position).put(i, entry.getValue());
            }
        }
        CountMapping mapping = new CountMapping(terms.stream().map(row -> new CountMapping.Expression(row, BigInteger.ZERO)).toList());
        choiceConflicts.add(mapping.conflicts(conflicts, budget));
        budget.note("count_program_conflicts", "pulled=" + conflicts.size() + "; scope=optional_program_view");
    }

    boolean step(PlanningScheduler.Slice slice) {
        if (complete || paused) return true;
        if (running != null) {
            if (!running.isDone()) return false;
            harvest(true);
        }
        budget.check();
        // A live finite-domain enumeration retains its table and prefix stack.
        // Let it use another bounded slice of the same order budget instead of
        // discarding it at the general branch-search quota and redoing sources.
        if (scouting && work >= allowance && allowance < scoutMaximum && hasIndependentProgress()) {
            allowance += Math.min(524_288, scoutMaximum - allowance);
            budget.note("integer_counts_scout", "independent_components_progress; allowance=" + allowance);
        }
        if (scouting && !repairScoutExtended && work >= allowance && allowance < scoutMaximum &&
                pending.size() == 1 && deferred.isEmpty() && pending.peekFirst().repair != null && pending.peekFirst().repair.competingViews()) {
            repairScoutExtended = true;
            allowance += Math.min(262144, Math.min(scoutMaximum - allowance, budget.remainingWork() / 16));
            budget.note("integer_counts_scout", "retained_relaxation_views; allowance=" + allowance);
        }
        if (!scouting && work >= allowance && best == null && pending.size() == 1 && pending.peekFirst().matching != null)
            allowance = preprocessingAllowance;
        if (work >= allowance || work >= improvementUntil) {
            if (best == null && (!pending.isEmpty() || !deferred.isEmpty())) {
                paused = true;
                if (proofs != null) proofs.publish(model, choiceConflicts.snapshot());
                budget.note("integer_counts_pause", "work=" + work + "; branches=" + branches + "; pending=" + (pending.size() + deferred.size()));
                return true;
            }
            return finish(false);
        }
        if (pending.isEmpty() && deferred.isEmpty()) {
            if (unresolved && best == null && !portfolioScheduled) enqueuePortfolio();
            if (pending.isEmpty()) return finish(!unresolved && best == null);
        }
        // Tiny count models benefit from immediate sibling feedback. Issuing
        // many simultaneous branches delays that feedback and can spend the
        // entire order budget before reaching the cheap sequential witness.
        // Keep their search grain serial; the scheduler still runs other orders
        // and larger independent models on the shared worker pool.
        int width = slice == null || model.recipes.size() <= 16 ? 1 : Math.min(16, slice.parallelism());
        if (!scouting && best == null && !portfolioScheduled && work >= 131072 && width > 1)
            enqueuePortfolio();
        if (best == null && !repairScheduled && work >= 32_768) enqueueRepair();
        int batchWidth = width == 1 ? 1 : width * 2;
        int residentLimit = Math.max(4, batchWidth);
        List<IntegerCountBranch<K>> wave = take(batchWidth, residentLimit);
        if (wave.isEmpty()) return finish(false);
        peakWidth = Math.max(peakWidth, wave.size());
        long quantum = Math.max(1, Math.min(4096, (Math.min(allowance, improvementUntil) - work) / wave.size()));
        var materials = materialConflicts.snapshot();
        var support = List.copyOf(supportConflicts);
        var choices = choiceConflicts.snapshot();
        PlanPreference<K> incumbent = best == null ? null : best.cost();
        List<Supplier<IntegerCountBranch<K>>> partitions = new ArrayList<>();
        BigInteger[] incumbentCounts = best == null ? null : model.recipes.stream()
                .map(recipe -> best.plan().patternTimesExact().getOrDefault(recipe.id(), BigInteger.ZERO)).toArray(BigInteger[]::new);
        for (var branch : wave) partitions.add(() -> {
            branch.incumbentCounts = incumbentCounts;
            branch.run(quantum, materials, support, choices, incumbent, stopped::get);
            return branch;
        });
        dispatched = wave;
        rounds++;
        if (slice != null && wave.size() > 1) {
            running = slice.forkStealing(PlanningBudget.Phase.SOLVE, partitions);
            return false;
        }
        for (var partition : partitions) partition.get();
        merge(wave, true);
        dispatched = List.of();
        return false;
    }

    CompletableFuture<?> waitingFor() {
        return running;
    }

    boolean paused() {
        return paused;
    }

    /** Budget scheduling hint only; partial quantities are not an executable plan. */
    boolean hasIndependentProgress() {
        if (running != null || pending.size() != 1 || !deferred.isEmpty()) return false;
        var branch = pending.peekFirst();
        return branch.components != null && branch.components.hasSolvedComponent() ||
                branch.recovery != null && branch.recovery.hasIndependentProgress();
    }

    /** A retained scaled subproblem can finish without the original large-number relaxation. */
    boolean hasScaledProgress() {
        if (running != null || pending.size() != 1 || !deferred.isEmpty()) return false;
        return pending.peekFirst().scaling != null;
    }

    boolean hasRetainedViews() {
        if (running != null) return false;
        return pending.stream().anyMatch(branch -> branch.viewSearch != null && branch.viewSearch.retained()) ||
                deferred.stream().anyMatch(branch -> branch.viewSearch != null && branch.viewSearch.retained());
    }

    /** A short first attempt keeps its full frontier for the normal continuation. */
    void scout(long maxWork) {
        if (work != 0 || paused || running != null || maxWork <= 0) throw new IllegalStateException("Count search already started");
        scouting = true;
        scoutMaximum = Math.min(allowance, maxWork <= Long.MAX_VALUE / 4 ? maxWork * 4 : Long.MAX_VALUE);
        allowance = Math.min(allowance, maxWork);
    }

    /** The coordinator returns unused order work, never refunds work already charged. */
    void resume() {
        if (!paused || complete) throw new IllegalStateException("Count search is not suspended");
        allowance = work + Math.max(1, Math.min(2_000_000, budget.remainingWork() / 2));
        scouting = false;
        paused = false;
        if (proofs != null) choiceConflicts.add(proofs.forModel(model));
        budget.note("integer_counts_resume", "work=" + work + "; allowance=" + allowance + "; branches=" + branches);
    }

    private List<IntegerCountBranch<K>> take(int width, int residentLimit) {
        List<IntegerCountBranch<K>> selected = new ArrayList<>();
        long resident = pending.stream().filter(branch -> branch.initialized).count() +
                deferred.stream().filter(branch -> branch.initialized).count();
        // A retained continuation must not wait for an exponentially growing
        // fresh frontier to empty. Reserve one turn in four; the rest still
        // explores fresh branches and shares their proofs deterministically.
        if (!deferred.isEmpty() && (rounds & 3) == 3) selected.add(deferred.removeFirst());
        int scanned = pending.size();
        while (selected.size() < width && scanned-- > 0) {
            var branch = pending.removeFirst();
            if (!branch.initialized && resident >= residentLimit) {
                pending.addLast(branch);
                continue;
            }
            if (!branch.initialized) resident++;
            selected.add(branch);
        }
        while (selected.size() < width && !deferred.isEmpty()) selected.add(deferred.removeFirst());
        return selected;
    }

    private void harvest(boolean continueSearch) {
        var future = running;
        running = null;
        try {
            if (future.isCompletedExceptionally()) {
                // allOf completes only after every child has stopped. Recover
                // independently verified siblings even if another hit a limit.
                merge(dispatched, false);
                future.join();
            } else merge(future.join(), continueSearch);
        } catch (RuntimeException | Error failure) {
            dispatched.stream().filter(branch -> branch.state == IntegerCountBranch.State.FOUND).forEach(this::retain);
            dispatched.forEach(IntegerCountBranch::close);
            Throwable cause = failure;
            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof PlanningBudget.Exhausted limit) throw limit;
            throw failure;
        } finally {
            dispatched = List.of();
        }
    }

    private void merge(List<IntegerCountBranch<K>> wave, boolean continueSearch) {
        for (var branch : wave) {
            work += branch.work;
            branch.work = 0;
            if (branch.choicePruned) choicePrunes++;
            if (!continueSearch) {
                if (branch.state == IntegerCountBranch.State.FOUND) retain(branch);
                else unresolved = true;
                branch.close();
                continue;
            }
            if (branch.needsRepair && !repairScheduled) enqueueRepair();
            for (var conflict : branch.learnedMaterials) {
                if (materialConflicts.offer(conflict)) {
                    Map<Integer, BigInteger> opposite = new LinkedHashMap<>();
                    conflict.terms().forEach((key, value) -> opposite.put(key, value.negate()));
                    choiceConflicts.add(List.of(new CountConflict(List.of(new ExactLinearProgram.Constraint(
                            opposite, conflict.upper().negate().subtract(BigInteger.ONE))))));
                }
            }
            materialConflicts.used(branch.usedMaterials);
            branch.usedMaterials.clear();
            branch.learnedMaterials.clear();
            if (supportConflicts.size() < 64) for (var conflict : branch.supportConflicts) {
                if (supportConflicts.size() == 64) break;
                supportConflicts.add(conflict);
            }
            // Partial auxiliary conflicts do not change the primary branch's
            // established decision order. Only a closed
            // root proof is promoted immediately; incumbent plans are shared
            // after the same original-recipe execution verification as before.
            if (branch.auxiliaryMode == 0) choiceConflicts.add(branch.learnedChoices);
            else choiceConflicts.add(branch.learnedChoices.stream().filter(c -> c.assumptions().isEmpty()).toList());
            choiceConflicts.used(branch.usedChoices);
            branch.usedChoices.clear();
            for (var child : branch.children) enqueue(child, branch);
            branch.children.clear();
            switch (branch.state) {
                case OPEN -> {
                    suspensions++;
                    pending.addLast(branch);
                }
                case FOUND -> {
                    retain(branch);
                    branch.close();
                }
                case UNRESOLVED -> {
                    if (branch.auxiliaryMode != 0) branch.close();
                    else if (branch.resume()) {
                        suspensions++;
                        deferred.addLast(branch);
                    } else {
                        unresolved = true;
                        branch.close();
                    }
                }
                case PRUNED -> {
                    boundPrunes++;
                    branch.close();
                }
                default -> branch.close();
            }
        }
    }

    private void retain(IntegerCountBranch<K> branch) {
        if (branch.workspace == 0) return; // Already transferred or disposed.
        if (frontier.stream().anyMatch(old -> old.cost().dominates(branch.preference))) return;
        boolean replaces = best == null || branch.preference.preferredTo(best.cost());
        for (var iterator = frontier.iterator(); iterator.hasNext();) {
            var old = iterator.next();
            if (branch.preference.dominates(old.cost())) {
                budget.release(old.memory());
                iterator.remove();
            }
        }
        if (frontier.size() == 16) {
            if (!replaces) return;
            budget.release(frontier.remove(frontier.size() - 1).memory());
        }
        // Transfer the branch's existing workspace reservation with the retained
        // program. Harvesting a verified result needs no new deadline-sensitive
        // allocation, and discarded alternatives release their own reservation.
        Incumbent<K> candidate = new Incumbent<>(branch.plan, branch.preference, branch.workspace);
        branch.workspace = 0;
        if (best == null) {
            best = candidate;
            firstWitnessWork = work;
            firstWitnessNanos = System.nanoTime();
            budget.note("integer_counts_first", "work=" + work + "; elapsed_ms=" + (firstWitnessNanos - started) / 1_000_000.0 +
                    "; branches=" + branches + "; result=" + branch.plan.result());
            // Cheap witnesses should not pay a fixed, larger optimization bill.
            // This caps optional improvement only; a verified incumbent is kept.
            long improvement = Math.min(16_384L, Math.max(1024L, work / 16));
            improvementUntil = Math.min(allowance, work + Math.min(improvement, Math.max(0, (allowance - work) / 8)));
        } else if (replaces) best = candidate;
        // Incomparable materials remain separate candidates. This bound affects
        // optimization only; it is never used to assert infeasibility.
        frontier.add(candidate);
    }

    private void enqueue(List<ExactLinearProgram.Constraint> constraints) {
        enqueue(constraints, null);
    }

    private void enqueueRepair() {
        repairScheduled = true;
        if (model.keys.size() > 16 || model.recipes.size() > 32) return;
        long before = budget.threadWork();
        var branch = new IntegerCountBranch<>(model, execution, target, amount, stock, seeds, external, preserve, force, budget, started, List.of());
        branch.compileRecovery = compileRecovery;
        try {
            if (branch.state != IntegerCountBranch.State.OPEN) return;
            branch.initialized = true;
            branch.partitioned = true;
            branch.supportSearch = CountSupportSearch.allSources(model, budget);
            if (branch.supportSearch.result() == CountSupportSearch.Result.UNKNOWN) return;
            // This is an optional witness/proof aid. The existing count branches
            // retain their original subspaces and prove nothing from its cutoff.
            pending.addFirst(branch);
            branches++;
            branch = null;
        } finally {
            if (branch != null) branch.close();
            work += budget.threadWork() - before;
        }
    }

    private void enqueuePortfolio() {
        portfolioScheduled = true;
        if (model.recipes.size() < 8 || model.recipes.size() > 192 || budget.remainingWork() < 262144) return;
        for (int mode = 1; mode <= 3; mode++) {
            var branch = new IntegerCountBranch<>(model, execution, target, amount, stock, seeds, external,
                    preserve, force, budget, started, List.of());
            if (branch.state != IntegerCountBranch.State.OPEN) {
                branch.close();
                continue;
            }
            branch.auxiliaryMode = mode;
            branch.partitioned = true;
            // Distinct algorithms, same explicit root scope. Their certified
            // conflicts and checked witnesses use the existing merge protocol;
            // a local cutoff cannot close the primary search's pending space.
            pending.addLast(branch);
            branches++;
        }
        budget.note("count_portfolio", "admitted=learning_rate_order,lazy_integer,hermite_hall; shared_order_budget; original_view_retained");
    }

    private void enqueue(List<ExactLinearProgram.Constraint> constraints, IntegerCountBranch<K> parent) {
        // Pending assumptions are lightweight and explicitly memory charged.
        // Only a bounded number of workspaces are resident; the cumulative
        // number of already closed branches is not a reason to drop siblings.
        var branch = new IntegerCountBranch<>(model, execution, target, amount, stock, seeds, external, preserve, force, budget, started, constraints);
        branch.branchHistory = branchHistory;
        branch.compileRecovery = compileRecovery;
        branches++;
        if (branch.state != IntegerCountBranch.State.OPEN) {
            unresolved = true;
            branch.close();
        } else {
            if (parent != null && parent.sharedBounds != null) branch.inheritedBounds = parent.sharedBounds.retain();
            if (parent != null && parent.sharedBasis != null) {
                branch.inheritedBasis = parent.sharedBasis.retain();
                branch.inheritedCoordinates = parent.reduction.coordinates();
            }
            pending.addLast(branch);
        }
    }

    GraphPlan<K> result() {
        // A global deadline can expire between parallel completion and the next
        // coordinator slice. Recover already verified witnesses before cleanup.
        if (running != null && running.isDone()) {
            try {
                harvest(false);
            } catch (PlanningBudget.Exhausted ignored) { /* An incumbent remains valid after the limit. */ }
        }
        if (running == null) dispatched.stream().filter(branch -> branch.state == IntegerCountBranch.State.FOUND).forEach(this::retain);
        return best == null ? null : best.plan();
    }

    boolean infeasible() {
        return infeasible;
    }

    private boolean finish(boolean proved) {
        complete = true;
        infeasible = proved && !unresolved && best == null;
        budget.note("integer_counts", "branches=" + branches + "; slices=" + rounds + "; suspended=" + suspensions +
                "; peak_width=" + peakWidth + "; work=" + work + "/" + allowance + "; frontier=" + frontier.size() +
                "; bound_prunes=" + boundPrunes + "; unresolved=" + unresolved + "; witness=" + (best != null) +
                "; first_work=" + firstWitnessWork + "; improvement_work=" + (firstWitnessWork < 0 ? 0 : work - firstWitnessWork) +
                "; improvement_ms=" + (firstWitnessWork < 0 ? 0 : (System.nanoTime() - firstWitnessNanos) / 1_000_000.0));
        choiceConflicts.report();
        materialConflicts.report();
        if (model != null) compiler.countSessions.remember(model, choiceConflicts.snapshot(), budget);
        if (proofs != null) proofs.publish(model, choiceConflicts.snapshot());
        close();
        return true;
    }

    @Override
    public void close() {
        stopped.set(true);
        CompletableFuture<?> future = running;
        if (future != null && !future.isDone()) future.whenComplete((value, failure) -> release());
        else release();
    }

    private void release() {
        if (!released.compareAndSet(false, true)) return;
        pending.forEach(IntegerCountBranch::close);
        deferred.forEach(IntegerCountBranch::close);
        dispatched.forEach(IntegerCountBranch::close);
        pending.clear();
        deferred.clear();
        dispatched = List.of();
        if (model != null && (proofs == null || proofs.model != model)) model.close();
        if (execution != null) execution.close();
        choiceConflicts.close();
        materialConflicts.close();
        if (branchHistory != null) branchHistory.close();
        frontier.forEach(candidate -> budget.release(candidate.memory()));
        frontier.clear();
    }
}
