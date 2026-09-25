package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Bounded branch search with retained continuations, shared proofs and verified incumbents. */
final class IntegerCountSearch<K> implements AutoCloseable {

    private final RecipeCountModel<K> model;
    private final K target;
    private final long amount, started, allowance;
    private final Map<K, Long> stock, seeds;
    private final Set<K> external;
    private final boolean preserve, force;
    private final PlanningBudget budget;
    private final Deque<IntegerCountBranch<K>> pending = new ArrayDeque<>(), deferred = new ArrayDeque<>();
    private final Set<ExactLinearProgram.Constraint> materialConflicts = new LinkedHashSet<>();
    private final Set<IntegerCountBranch.SupportConflict> supportConflicts = new LinkedHashSet<>();
    private final List<Incumbent<K>> frontier = new ArrayList<>();
    private final AtomicBoolean stopped = new AtomicBoolean(), released = new AtomicBoolean();
    private CompletableFuture<List<IntegerCountBranch<K>>> running;
    private List<IntegerCountBranch<K>> dispatched = List.of();
    private Incumbent<K> best;
    private boolean complete, infeasible, unresolved;
    private long work, improvementUntil = Long.MAX_VALUE;
    private int branches, rounds, suspensions, boundPrunes, peakWidth;

    private record Incumbent<K>(GraphPlan<K> plan, PlanPreference<K> cost, long memory) {}

    IntegerCountSearch(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
                       Map<K, Long> seeds, Set<K> external, Set<String> excluded, boolean preserve, boolean force,
                       PlanningBudget budget, long started) {
        this.target = target;
        this.amount = amount;
        this.stock = Map.copyOf(stock);
        this.seeds = Map.copyOf(seeds);
        this.external = Set.copyOf(external);
        this.preserve = preserve;
        this.force = force;
        this.budget = budget;
        this.started = started;
        allowance = Math.min(2_000_000, budget.remainingWork() / 4);
        model = RecipeCountModel.create(compiler, target, amount, this.stock, this.seeds, this.external, excluded, force, budget);
        if (model == null || model.recipes.size() > 64 || model.keys.size() > 96) {
            if (model != null) budget.note("integer_counts", "skipped; recipes=" + model.recipes.size() + "/64; keys=" + model.keys.size() + "/96");
            complete = true;
            close();
            return;
        }
        enqueue(List.of());
    }

    boolean step() {
        return step(null);
    }

    boolean step(PlanningScheduler.Slice slice) {
        if (complete) return true;
        if (running != null) {
            if (!running.isDone()) return false;
            harvest(true);
        }
        budget.check();
        if (work >= allowance || work >= improvementUntil) return finish(false);
        if (pending.isEmpty() && deferred.isEmpty()) return finish(!unresolved && best == null);
        int width = slice == null ? 1 : Math.min(16, slice.parallelism());
        int residentLimit = Math.max(4, width);
        List<IntegerCountBranch<K>> wave = take(width, residentLimit);
        if (wave.isEmpty()) return finish(false);
        peakWidth = Math.max(peakWidth, wave.size());
        long quantum = Math.max(1, Math.min(4096, (Math.min(allowance, improvementUntil) - work) / wave.size()));
        var materials = List.copyOf(materialConflicts);
        var support = List.copyOf(supportConflicts);
        PlanPreference<K> incumbent = best == null ? null : best.cost();
        List<Supplier<IntegerCountBranch<K>>> partitions = new ArrayList<>();
        for (var branch : wave) partitions.add(() -> {
            branch.run(quantum, materials, support, incumbent, stopped::get);
            return branch;
        });
        dispatched = wave;
        rounds++;
        if (slice != null && wave.size() > 1) {
            running = slice.fork(PlanningBudget.Phase.SOLVE, partitions);
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

    private List<IntegerCountBranch<K>> take(int width, int residentLimit) {
        List<IntegerCountBranch<K>> selected = new ArrayList<>();
        long resident = pending.stream().filter(branch -> branch.initialized).count();
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
            if (!continueSearch) {
                if (branch.state == IntegerCountBranch.State.FOUND) retain(branch);
                else unresolved = true;
                branch.close();
                continue;
            }
            if (materialConflicts.size() < 64) for (var conflict : branch.learnedMaterials) {
                if (materialConflicts.size() == 64) break;
                materialConflicts.add(conflict);
            }
            if (supportConflicts.size() < 64) for (var conflict : branch.supportConflicts) {
                if (supportConflicts.size() == 64) break;
                supportConflicts.add(conflict);
            }
            for (var child : branch.children) enqueue(child);
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
                    if (branch.resume()) {
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
            improvementUntil = Math.min(allowance, work + Math.min(16_384L, Math.max(0, (allowance - work) / 8)));
        } else if (replaces) best = candidate;
        // Incomparable materials remain separate candidates. This bound affects
        // optimization only; it is never used to assert infeasibility.
        frontier.add(candidate);
    }

    private void enqueue(List<ExactLinearProgram.Constraint> constraints) {
        if (branches >= 256 || pending.size() + deferred.size() >= 256) {
            unresolved = true;
            return;
        }
        var branch = new IntegerCountBranch<>(model, target, amount, stock, seeds, external, preserve, force, budget, started, constraints);
        branches++;
        if (branch.state != IntegerCountBranch.State.OPEN) {
            unresolved = true;
            branch.close();
        } else pending.addLast(branch);
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
                "; bound_prunes=" + boundPrunes + "; unresolved=" + unresolved + "; witness=" + (best != null));
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
        if (model != null) model.close();
        frontier.forEach(candidate -> budget.release(candidate.memory()));
        frontier.clear();
    }
}
