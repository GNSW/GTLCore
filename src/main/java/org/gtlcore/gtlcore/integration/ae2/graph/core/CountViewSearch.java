package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Fair, deterministic slices over equivalent representations. Search states
 * survive local handoffs; only a checked witness or a full-domain contradiction
 * is conclusive. No cutoff or view-local clause is exported as a global proof.
 */
final class CountViewSearch implements AutoCloseable {

    private final CountModelViews models;
    private final PlanningBudget budget;
    private final List<Search> searches = new ArrayList<>();
    private final CountPortfolioPolicy policy = new CountPortfolioPolicy();
    private Search active;
    private long work, until;
    private boolean infeasible;
    private BigInteger[] counts;

    private static final class Search {

        final CountModelViews.View view;
        final CountPortfolioPolicy.Arm scheduling;
        final Engine engine;
        CountLcg solver;
        CountCdcl cdcl;
        CountJump jump;
        long sliceWork, progress;
        boolean done;

        Search(CountModelViews.View view, CountPortfolioPolicy.Arm scheduling, Engine engine) {
            this.view = view;
            this.scheduling = scheduling;
            this.engine = engine;
        }

        boolean started() {
            return solver != null || cdcl != null || jump != null;
        }

        boolean paused() {
            return solver != null ? solver.paused() : cdcl != null ? cdcl.paused() : jump != null && jump.paused();
        }

        void resume(long quantum, PlanningBudget budget) {
            if (engine == Engine.PB) {
                if (cdcl == null) cdcl = new CountCdcl(view.rows(), view.lower(), view.upper(), budget, quantum).retained();
                else cdcl.resume(quantum);
            } else if (engine == Engine.JUMP) {
                if (jump == null) jump = new CountJump(view.rows(), view.lower(), view.upper(), budget, quantum).retained();
                else jump.resume(quantum);
            } else {
                if (solver == null) {
                    solver = new CountLcg(view.rows(), view.lower(), view.upper(), budget, quantum);
                    if (engine == Engine.LOCKS) solver.lockBranching();
                } else solver.resume(quantum);
            }
        }

        void close() {
            if (solver != null) solver.close();
            if (cdcl != null) cdcl.close();
            if (jump != null) jump.close();
            solver = null;
            cdcl = null;
            jump = null;
        }

        boolean step() {
            return cdcl != null ? cdcl.step() : jump != null ? jump.step() : solver.step();
        }

        long progress() {
            return cdcl != null ? cdcl.progress() : jump != null ? jump.progress() : solver.progress();
        }

        BigInteger[] counts() {
            return cdcl != null ? cdcl.counts() : jump != null ? jump.counts() : solver.counts();
        }

        boolean infeasible() {
            return cdcl != null ? cdcl.infeasible() : solver != null && solver.infeasible();
        }
    }

    private enum Engine {
        LCG,
        PB,
        LOCKS,
        JUMP
    }

    CountViewSearch(CountModelViews models, PlanningBudget budget) {
        this.models = models;
        this.budget = budget;
    }

    void resume(long allowance) {
        if (allowance <= 0 || infeasible) throw new IllegalStateException("Invalid view search continuation");
        counts = null;
        until = work + Math.min(allowance, budget.remainingWork() / 4);
        for (var view : models.available()) if (searches.stream().noneMatch(search -> search.view == view)) {
            boolean binary = binary(view);
            searches.add(new Search(view, policy.add(view.shape().cost()), Engine.LCG));
            // The weighted Boolean engine has different propagation, phase
            // saving and conflicts. Retain that complementary search too;
            // repeatedly restarting a short PB attempt discards its learning.
            if (binary) {
                searches.add(new Search(view, policy.add(view.shape().cost()), Engine.PB));
                searches.add(new Search(view, policy.add(view.shape().cost()), Engine.LOCKS));
                searches.add(new Search(view, policy.add(view.shape().cost()), Engine.JUMP));
            }
        }
    }

    private boolean binary(CountModelViews.View view) {
        if (view.lower().length > 1024 || view.rows().size() > 4096 || view.shape().terms() > 65536) return false;
        for (int i = 0; i < view.lower().length; i++) {
            budget.check();
            if (view.upper()[i] == null || view.upper()[i].subtract(view.lower()[i]).compareTo(BigInteger.ONE) > 0) return false;
        }
        return true;
    }

    boolean step() {
        if (counts != null || infeasible || work >= until) return true;
        long before = budget.threadWork();
        boolean completedSlice = false;
        long gained = 0;
        try {
            if (active == null) {
                active = select();
                if (active == null) return true;
                // The parent allowance controls when step() yields, not when a
                // retained solver finishes its batch. Carry unfinished batches
                // across parent handoffs and publish feedback only on completion.
                long quantum = policy.quantum(active.scheduling, budget.remainingWork());
                // A local walk crosses temporary violation barriers before
                // establishing a new best point. Give it a full bounded
                // batch; the common aging policy still schedules every arm.
                if (active.engine == Engine.JUMP) quantum = Math.min(CountPortfolioPolicy.MAX_QUANTUM, budget.remainingWork());
                if (quantum <= 0) {
                    // Another worker can consume the shared last unit between
                    // parent handoffs. Report budget exhaustion, not an invalid
                    // resume(0) on a retained solver.
                    budget.check();
                    active = null;
                    return true;
                }
                if (!active.started() && Math.min(quantum, until - work) < 1024) {
                    active = null;
                    return true;
                }
                policy.selected(active.scheduling);
                active.sliceWork = 0;
                active.resume(quantum, budget);
            }
            if (!active.step()) return false;
            long progress = active.progress();
            gained = Math.max(0, progress - active.progress);
            active.progress = progress;
            completedSlice = true;
            BigInteger[] candidate = active.counts();
            infeasible = active.infeasible();
            if (candidate != null) counts = models.restoreAndCheck(active.view, candidate);
            if (!active.paused()) {
                active.done = true;
                active.scheduling.retired = true;
                active.close();
            }
            budget.note("count_view", active.view.name() + "; engine=" + active.engine.name().toLowerCase(Locale.ROOT) + "; slices=" + active.scheduling.selections + "; progress=" + gained + "; witness=" + (counts != null) +
                    "; proven_infeasible=" + infeasible + "; retained=" + !active.done);
            // Let complementary arithmetic/source strategies run when every
            // live representation has stalled. A later resume keeps the exact
            // queues and clauses; this handoff is neither failure nor closure.
            boolean stalled = true;
            for (Search search : searches) {
                int idle = search == active ? gained > 0 ? 0 : search.scheduling.idleSlices + 1 : search.scheduling.idleSlices;
                if (!search.done && idle < 2) stalled = false;
            }
            if (stalled && counts == null && !infeasible && addIntegerJump()) stalled = false;
            return counts != null || infeasible || stalled;
        } finally {
            long spent = budget.threadWork() - before;
            work += spent;
            if (active != null) {
                active.sliceWork += spent;
                if (completedSlice) policy.feedback(active.scheduling, active.sliceWork, gained);
                if (active.done || !active.started() || active.paused()) active = null;
            }
        }
    }

    private boolean addIntegerJump() {
        // General integer local search complements stalled proof search. It
        // must not displace an exact engine that is still making progress, or
        // duplicate the same walk on every equivalent representation.
        if (searches.stream().anyMatch(search -> search.engine == Engine.JUMP)) return false;
        var view = models.available().stream().filter(candidate -> candidate.shape().variables() <= 1024 &&
                candidate.rows().size() <= 4096 && candidate.shape().terms() <= 65536)
                .min(Comparator.comparingLong(candidate -> candidate.shape().cost())).orElse(null);
        if (view == null) return false;
        searches.add(new Search(view, policy.add(view.shape().cost()), Engine.JUMP));
        return true;
    }

    private Search select() {
        var chosen = policy.select();
        return searches.stream().filter(search -> search.scheduling == chosen).findFirst().orElse(null);
    }

    BigInteger[] counts() {
        return counts == null ? null : counts.clone();
    }

    boolean infeasible() {
        return infeasible;
    }

    boolean retained() {
        return searches.stream().anyMatch(search -> !search.done) ||
                models.available().stream().anyMatch(view -> searches.stream().noneMatch(search -> search.view == view));
    }

    @Override
    public void close() {
        for (var search : searches) search.close();
        searches.clear();
        active = null;
    }
}
