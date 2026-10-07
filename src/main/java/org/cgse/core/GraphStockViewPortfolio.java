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
        neighborhood = new GraphSupportNeighborhood<>(target, amount, stock, external, seeds, preserve, force, budget, started);
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
        if (complete || paused) return true;
        if (jointActive) {
            long before = budget.threadSearchWork();
            try {
                if (!neighborhood.step()) return false;
                jointActive = false;
                result = neighborhood.result();
                if (result != null) return complete = true;
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
                done.set(active);
                selected.live--;
                views.get(active).close();
                if (result != null) return complete = true;
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
        // Newly admitted source heuristics share one exploration allowance.
        // Their cheap repair estimates must not displace the continuation of
        // an established view. Credit grows with actual original-view work;
        // once those views finish, the new family can use the entire remainder.
        if (done.get(active) || selected.turn >= 32_768) {
            selected.next();
            if (original.live == 0 && quantitative.live == 0) {
                viewsComplete = true;
                return false;
            }
            selected = quantitative.live > 0 && (original.live == 0 || quantitative.work < original.work / 8) ?
                    quantitative : original;
            return false;
        }
        return false;
    }

    GraphPlan<K> result() {
        return result;
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
