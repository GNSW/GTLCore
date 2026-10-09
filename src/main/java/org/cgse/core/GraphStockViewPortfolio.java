// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded, retained demand views; each source heuristic gets a turn. */
final class GraphStockViewPortfolio<K> implements AutoCloseable {

    private final List<GraphStockViewWork<K>> views = new ArrayList<>();
    private final java.util.BitSet done = new java.util.BitSet();
    private final PlanningBudget budget;
    private final long allowance, continuationAllowance;
    private GraphSourceRanking<K> ranking;
    private GraphSourceRanking<K> localRanking;
    private GraphSourceRanking<K> quantitativeRanking;
    private GraphPlan<K> result;
    private int proposalView = -1;
    private final Family original = new Family(false), quantitative = new Family(true);
    private Family selected = original;
    private long work, until;
    private boolean continuing, paused, complete;
    private final GraphSupportNeighborhood<K> neighborhood;
    private boolean jointActive, jointTried, viewsComplete;
    private final java.util.function.IntFunction<GraphStockViewWork<K>> viewFactory;
    private boolean quantitativeAdded;
    private int pausedMissingKeys = Integer.MAX_VALUE;

    GraphStockViewPortfolio(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
                            Set<K> external, Map<K, Long> seeds, Set<String> excluded, boolean preserve,
                            boolean force, CatalystPolicy policy, PlanningBudget budget, long started) {
        this.budget = budget;
        neighborhood = new GraphSupportNeighborhood<>(compiler, target, amount, stock, external, seeds, excluded, preserve, force, budget, started);
        allowance = Math.min(4_194_304, budget.remainingWork() / 4);
        continuationAllowance = Math.min(8_388_608, budget.remainingWork() / 2);
        until = allowance;
        viewFactory = variant -> new GraphStockViewWork<>(compiler, target, amount,
                stock, external, seeds, excluded, preserve, force, policy, budget, started, variant, () -> {
                    if (variant >= 8) {
                        if (quantitativeRanking == null) quantitativeRanking = GraphSourceRanking.createQuantitative(compiler, stock, external, target,
                                force, budget, Math.max(0, until - work));
                        return quantitativeRanking;
                    }
                    if (variant >= 4) {
                        if (localRanking == null) localRanking = GraphSourceRanking.createLocal(compiler, stock, external, target,
                                force, budget, Math.max(0, until - work));
                        return localRanking;
                    }
                    if (ranking == null) ranking = GraphSourceRanking.create(compiler, stock, external, target, force,
                            budget, Math.max(0, until - work));
                    return ranking;
                }).neighborhood(neighborhood);
        for (int variant : new int[] { 0, 4, 5, 3, 2, 1 }) views.add(viewFactory.apply(variant));
        original.live = views.size();
    }

    boolean step() {
        budget.checkpoint();
        if (complete || paused || result != null) return true;
        if (jointActive) {
            long before = budget.threadSearchWork();
            try {
                if (!neighborhood.step()) return false;
                jointActive = false;
                result = neighborhood.result();
                if (result != null) {
                    proposalView = -1;
                    return true;
                }
            } finally {
                work += budget.threadSearchWork() - before;
            }
        }
        if (work >= until || viewsComplete) {
            if (!jointTried) {
                jointTried = true;
                if (neighborhood.beginTurn(Math.min(262144, budget.remainingWork() / 16))) {
                    jointActive = true;
                    return false;
                }
            }
            if (viewsComplete) {
                if (addQuantitativeViews()) return false;
                if (neighborhood.retained()) pause();
                else complete = true;
                return true;
            }
            int live = 0;
            boolean refined = false;
            for (int i = 0; i < views.size(); i++) if (!done.get(i)) {
                live++;
                refined |= views.get(i).refined();
            }
            // When the other source views have finished, let an advancing
            // demand boundary use their remaining share instead of discarding
            // a nearly completed witness at an arbitrary portfolio cutoff.
            if (!continuing && live == 1 && refined && work < continuationAllowance) {
                continuing = true;
                until = continuationAllowance;
                budget.note("stock_view", "continue_refined_frontier; work=" + work);
            } else {
                // A local quota hands control back; it is not exhaustion of
                // these source choices. Pause only between complete steps so
                // partial graph construction and repair retain their state.
                pause();
                return true;
            }
        }
        int active = selected.active;
        long before = budget.threadSearchWork();
        try {
            boolean finished = views.get(active).step();
            var fork = views.get(active).takeFork();
            if (fork != null) {
                views.add(fork);
                if (fork.quantitative()) quantitative.live++;
                else original.live++;
            }
            if (finished) {
                result = views.get(active).result();
                if (result != null) {
                    proposalView = active;
                    return true;
                }
                done.set(active);
                selected.live--;
                views.get(active).close();
            }
        } finally {
            long used = budget.threadSearchWork() - before;
            work += used;
            selected.work += used;
            selected.turn += used;
            if (selected == quantitative) {
                // The original continuation owns the granted local quota.
                // Exploration has its own bounded share; charging it against
                // that quota would hand control back before the old view gets
                // its turn. Every unit still consumes the request's hard cap.
                until = until > Long.MAX_VALUE - used ? Long.MAX_VALUE : until + used;
            }
        }
        // Newly admitted source heuristics share an exploration allowance.
        // Observed refinement may earn additional turns, bounded by charged
        // work so an established continuation keeps running. Once the original
        // views finish, the new family can use the entire remainder.
        if (done.get(active) || selected.turn >= 32_768) {
            selected.next();
            if (original.live == 0 && quantitative.live == 0) {
                viewsComplete = true;
                return false;
            }
            selected = quantitative.live > 0 && (original.live == 0 || quantitative.work < original.work / 8 || quantitativePromising()) ?
                    quantitative : original;
            return false;
        }
        return false;
    }

    /**
     * Exploration has a guaranteed small share, but a productive new family
     * must not stay confined to it while older views expand increasingly costly
     * branches. Compare observed repair costs after refinement, and stop granting
     * extra turns once charged work catches up with the still-live original
     * family. An atomic step may overshoot; the shared hard budget still applies.
     * These estimates select a turn; they cannot discard a source or prove that
     * the lower-cost proposal is executable.
     */
    private boolean quantitativePromising() {
        if (quantitative.work >= original.work) return false;
        double originalCost = Double.POSITIVE_INFINITY, quantitativeCost = Double.POSITIVE_INFINITY;
        for (int i = 0; i < views.size(); i++) if (!done.get(i) && views.get(i).refined()) {
            var view = views.get(i);
            if (view.quantitative()) quantitativeCost = Math.min(quantitativeCost, view.estimatedRepairCost());
            else originalCost = Math.min(originalCost, view.estimatedRepairCost());
        }
        return quantitativeCost < originalCost;
    }

    GraphPlan<K> result() {
        return result;
    }

    boolean supportProposal() {
        return result != null && proposalView < 0;
    }

    int proposalView() {
        if (result == null || complete) throw new IllegalStateException("No candidate awaiting validation");
        return proposalView;
    }

    /** Work is already charged to the request; attribute it to the proposing frontier too. */
    void validationWork(int view, long used) {
        if (used < 0) throw new IllegalArgumentException("Negative validation work");
        if (complete) return;
        work = add(work, used);
        if (view >= 0) {
            var source = views.get(view);
            Family family = source.quantitative() ? quantitative : original;
            family.work = add(family.work, used);
            // Feedback can already have rotated the cursor. Never charge its
            // successor's turn for the previous witness's final validation.
            if (result != null && proposalView == view) family.turn = add(family.turn, used);
            source.validationWork(used);
            if (family == quantitative) until = add(until, used);
        }
    }

    private static long add(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    void feedback(CandidateFeedback feedback) {
        if (result == null || complete) throw new IllegalStateException("No portfolio candidate awaiting validation");
        budget.checkpoint();
        budget.note("stock_view", "validation=" + feedback + "; view=" + proposalView + "; work=" + work);
        if (feedback == CandidateFeedback.ACCEPTED) {
            close();
            return;
        }
        if (proposalView >= 0) {
            views.get(proposalView).feedback(feedback);
            // The rejected view retains its repair state; its siblings also
            // retain their turns. A proposal is not portfolio completion.
            selected.next();
            selected = quantitative.live > 0 && (original.live == 0 || quantitative.work < original.work / 8) ?
                    quantitative : original;
        } else neighborhood.rejectCandidate();
        result = null;
        proposalView = -1;
    }

    boolean compilationActive() {
        return !jointActive && !viewsComplete && views.get(selected.active).compilationActive();
    }

    boolean paused() {
        return paused;
    }

    int missingKeys() {
        return pausedMissingKeys;
    }

    private void pause() {
        paused = true;
        pausedMissingKeys = Integer.MAX_VALUE;
        for (int i = 0; i < views.size(); i++)
            if (!done.get(i)) pausedMissingKeys = Math.min(pausedMissingKeys, views.get(i).missingKeys());
    }

    void resume(long quota) {
        if (!paused || complete || quota <= 0) throw new IllegalStateException("No paused stock frontier");
        long extra = Math.min(quota, budget.remainingWork());
        until = work > Long.MAX_VALUE - extra ? Long.MAX_VALUE : work + extra;
        continuing = true;
        paused = false;
        jointTried = false;
        addQuantitativeViews();
        budget.note("stock_view", "resume_retained_frontier; work=" + work + "; quota=" + extra);
    }

    private boolean addQuantitativeViews() {
        if (quantitativeAdded) return false;
        quantitativeAdded = true;
        quantitative.active = views.size();
        quantitative.fairCursor = quantitative.active;
        quantitative.live = 2;
        views.add(viewFactory.apply(8));
        views.add(viewFactory.apply(9));
        viewsComplete = false;
        selected = quantitative;
        budget.note("stock_view", "quantity_views_after_initial_scout; work=" + work);
        return true;
    }

    /** Independent fair cursors keep new views from reordering old continuations. */
    private final class Family {

        private final boolean quantity;
        private int active, fairCursor, dispatches, live;
        private long work, turn;

        private Family(boolean quantity) {
            this.quantity = quantity;
        }

        private boolean eligible(int i) {
            return !done.get(i) && views.get(i).quantitative() == quantity;
        }

        private void next() {
            turn = 0;
            if (live == 0) return;
            int fair = -1;
            for (int next = 1; next <= views.size(); next++) {
                int candidate = (fairCursor + next) % views.size();
                if (eligible(candidate)) {
                    fair = candidate;
                    break;
                }
            }
            if (fair < 0) throw new IllegalStateException("Missing live source view");
            boolean sampled = true;
            for (int i = 0; i < views.size(); i++) if (eligible(i) && !views.get(i).sampled()) sampled = false;
            int preferred = fair;
            if (sampled && ++dispatches % 4 != 0) {
                for (int i = 0; i < views.size(); i++) if (eligible(i) &&
                        views.get(i).estimatedRepairCost() < views.get(preferred).estimatedRepairCost())
                    preferred = i;
            }
            if (preferred == fair) fairCursor = fair;
            active = preferred;
        }
    }

    @Override
    public void close() {
        complete = true;
        paused = false;
        for (var view : views) view.close();
        if (ranking != null) ranking.close();
        ranking = null;
        if (localRanking != null) localRanking.close();
        localRanking = null;
        if (quantitativeRanking != null) quantitativeRanking.close();
        quantitativeRanking = null;
        neighborhood.close();
    }
}
