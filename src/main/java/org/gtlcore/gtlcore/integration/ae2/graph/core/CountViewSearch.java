package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Fair, deterministic slices over equivalent representations. Search states
 * survive local handoffs; only a checked witness or a full-domain contradiction
 * is conclusive. No cutoff or view-local clause is exported as a global proof.
 */
final class CountViewSearch implements AutoCloseable {

    private static final long QUANTUM = 32768;
    private final CountModelViews models;
    private final PlanningBudget budget;
    private final List<Search> searches = new ArrayList<>();
    private Search active;
    private long work, until;
    private int turn;
    private boolean infeasible;
    private BigInteger[] counts;

    private static final class Search {

        final CountModelViews.View view;
        CountLcg solver;
        long work, progress;
        int lastTurn = -1, slices, idleSlices, nextTurn;
        boolean done, productive;

        Search(CountModelViews.View view) {
            this.view = view;
        }
    }

    CountViewSearch(CountModelViews models, PlanningBudget budget) {
        this.models = models;
        this.budget = budget;
    }

    void resume(long allowance) {
        if (allowance <= 0 || infeasible) throw new IllegalStateException("Invalid view search continuation");
        counts = null;
        until = work + Math.min(allowance, budget.remainingWork() / 4);
        for (var view : models.available()) if (searches.stream().noneMatch(search -> search.view == view)) searches.add(new Search(view));
    }

    boolean step() {
        if (counts != null || infeasible || work >= until) return true;
        long before = budget.threadWork();
        try {
            if (active == null) {
                active = select();
                if (active == null) return true;
                long quantum = Math.min(QUANTUM, until - work);
                if (active.solver == null && quantum < 1024) {
                    active = null;
                    return true;
                }
                if (active.solver == null) {
                    var v = active.view;
                    active.solver = new CountLcg(v.rows(), v.lower(), v.upper(), budget, quantum);
                } else active.solver.resume(quantum);
                active.lastTurn = turn++;
                active.slices++;
            }
            if (!active.solver.step()) return false;
            long progress = active.solver.progress();
            active.productive = progress > active.progress;
            active.progress = progress;
            // Repeatedly fruitless slices get a bounded cooldown, as opposed
            // to permanently excluding the representation. Learning resumes
            // its normal frequency; all state remains local to this search.
            active.idleSlices = active.productive ? 0 : Math.min(4, active.idleSlices + 1);
            active.nextTurn = turn + (active.productive ? 0 : (1 << active.idleSlices) - 1);
            BigInteger[] candidate = active.solver.counts();
            infeasible = active.solver.infeasible();
            if (candidate != null) counts = models.restoreAndCheck(active.view, candidate);
            if (!active.solver.paused()) {
                active.done = true;
                active.solver.close();
                active.solver = null;
            }
            budget.note("count_view", active.view.name() + "; slices=" + active.slices + "; witness=" + (counts != null) +
                    "; proven_infeasible=" + infeasible + "; retained=" + !active.done);
            // Let complementary arithmetic/source strategies run when every
            // live representation has stalled. A later resume keeps the exact
            // queues and clauses; this handoff is neither failure nor closure.
            boolean stalled = searches.stream().allMatch(search -> search.done || search.idleSlices >= 2);
            return counts != null || infeasible || stalled;
        } finally {
            long spent = budget.threadWork() - before;
            work += spent;
            if (active != null) {
                active.work += spent;
                if (active.done || active.solver.paused()) active = null;
            }
        }
    }

    private Search select() {
        Search best = null;
        int earliest = Integer.MAX_VALUE;
        for (Search next : searches) {
            if (next.done) continue;
            earliest = Math.min(earliest, next.nextTurn);
            if (next.nextTurn > turn) continue;
            if (best == null || score(next) < score(best)) best = next;
        }
        // Cooldowns are a relative scheduling preference, never a reason to
        // stop when every other view is sleeping or has already finished.
        if (best == null && earliest != Integer.MAX_VALUE) {
            turn = earliest;
            return select();
        }
        return best;
    }

    private long score(Search search) {
        // Every representation gets a first look; cheaper structure breaks
        // ties. Aging bounds starvation even if another view keeps learning.
        if (search.slices == 0) return Long.MIN_VALUE / 2 + search.view.shape().cost();
        if (turn - search.lastTurn > 2 * searches.size()) return Long.MIN_VALUE / 4 + search.lastTurn;
        // Reward propagation/learning mildly, not objective improvement: this
        // scheduler looks for the first executable witness, not an incumbent.
        return search.work / (search.productive ? 2 : 1);
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
        for (var search : searches) if (search.solver != null) search.solver.close();
        searches.clear();
        active = null;
    }
}
