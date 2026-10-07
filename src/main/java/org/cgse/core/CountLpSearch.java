package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Retained Boolean LP/LCG search with its own reversible coordinate snapshot. */
final class CountLpSearch implements AutoCloseable {

    private final PlanningBudget budget;
    private final CountCanonicalModel canonical;
    private final CountLcg search;
    private final CountReduction.Coordinates coordinates;
    private final int[] representatives;
    private long memory, work, until;
    private boolean complete;
    private CountModelViews.View sharingView;
    private int publishedCuts;

    private CountLpSearch(PlanningBudget budget, CountCanonicalModel canonical, CountLcg search,
                          CountReduction.Coordinates coordinates, int[] representatives, long memory) {
        this.budget = budget;
        this.canonical = canonical;
        this.search = search;
        this.coordinates = coordinates;
        this.representatives = representatives;
        this.memory = memory;
        long remaining = budget.remainingWork();
        until = remaining - remaining / 4;
    }

    static CountLpSearch create(CountReduction reduction, int originalVariables, PlanningBudget budget) {
        int variables = reduction.variables();
        var original = reduction.rows();
        if (variables < 2 || variables > 128 || original.size() > 512 || originalVariables < variables) return null;
        long preparationStarted = budget.threadWork();
        long preparationLimit = Math.min(65536, budget.remainingWork() / 16);
        if (preparationLimit < 1024) return null;
        long bytes = 4096L + 256L * variables + 128L * original.size() + 96L * originalVariables;
        if (!budget.tryReserve(bytes)) return null;
        CountCanonicalModel canonical = null;
        CountLcg search = null;
        try {
            BigInteger[] lower = reduction.lower(), upper = reduction.upper();
            for (int i = 0; i < variables; i++) {
                budget.check();
                if (lower[i].signum() < 0 || upper[i] == null || upper[i].compareTo(BigInteger.ONE) > 0) return null;
            }
            var source = new ArrayList<ExactLinearProgram.Constraint>();
            int[] degrees = new int[variables];
            boolean weighted = false;
            for (var row : original) {
                if (row.upper().bitLength() > 1024) return null;
                BigInteger maximum = BigInteger.ZERO;
                int free = 0;
                boolean rowWeighted = false;
                for (var term : row.terms().entrySet()) {
                    if (budget.threadWork() - preparationStarted >= preparationLimit) return null;
                    budget.check();
                    int id = term.getKey();
                    if (term.getValue().bitLength() > 1024) return null;
                    BigInteger endpoint = term.getValue().signum() > 0 ? upper[id] : lower[id];
                    if (endpoint.signum() != 0) maximum = maximum.add(term.getValue());
                    if (!lower[id].equals(upper[id])) {
                        free++;
                        rowWeighted |= term.getValue().abs().compareTo(BigInteger.ONE) > 0;
                    }
                }
                // Domain-tautological rows need not be copied into every LP.
                if (maximum.compareTo(row.upper()) <= 0) continue;
                source.add(row);
                if (free > 1) {
                    weighted |= rowWeighted;
                    for (int id : row.terms().keySet()) if (!lower[id].equals(upper[id])) degrees[id]++;
                }
            }
            int overlap = 0;
            for (int degree : degrees) if (degree > 1) overlap++;
            long estimate = (variables + 2L) * (source.size() + variables + 2L);
            // Prefer this route for intersecting weighted choices. Pure unit
            // structure and isolated rows retain the cheaper existing solvers.
            if (!weighted || overlap < 2 || budget.remainingWork() < Math.max(16384L, 8 * estimate)) return null;
            canonical = CountCanonicalModel.create(source, lower, upper, budget);
            if (canonical == null) return null;
            var coordinates = reduction.coordinates();
            if (coordinates.roots().size() != originalVariables) throw new IllegalArgumentException("Coordinate length mismatch");
            search = new CountLcg(canonical.rows(), canonical.lower(), canonical.upper(), budget,
                    Math.min(262144, budget.remainingWork() / 4)).learnedRelaxation();
            if (!search.learnedRelaxationEnabled()) return null;
            var result = new CountLpSearch(budget, canonical, search, coordinates, reduction.representatives(), bytes);
            canonical = null;
            search = null;
            bytes = 0;
            budget.note("count_lp_search", "weighted_overlap; variables=" + variables + "; rows=" + source.size());
            return result;
        } finally {
            if (search != null) search.close();
            if (canonical != null) canonical.close();
            budget.release(bytes);
        }
    }

    boolean step() {
        if (complete || work >= until) return true;
        long before = budget.threadWork();
        try {
            if (search.paused()) search.resume(Math.min(262144, until - work));
            if (!search.step()) return false;
            complete = !search.paused();
            return complete || work + budget.threadWork() - before >= until;
        } finally {
            work += budget.threadWork() - before;
        }
    }

    boolean retained() {
        return !complete;
    }

    void shareRows() {
        search.shareRows();
    }

    void publishCuts(CountModelViews models, CountReduction reduction) {
        if (models == null) return;
        var cuts = search.sharedRows();
        if (publishedCuts >= cuts.size()) return;
        if (sharingView == null) sharingView = models.registerLp(reduction, canonical);
        models.publishCuts(sharingView, cuts, publishedCuts, this);
        publishedCuts = cuts.size();
    }

    void resume(long allowance) {
        if (!retained() || allowance <= 0) throw new IllegalStateException("No retained LP search");
        until = work + Math.min(allowance, budget.remainingWork());
    }

    boolean infeasible() {
        return search.infeasible();
    }

    BigInteger[] counts() {
        var values = canonical.restore(search.counts());
        if (values == null) return null;
        var result = coordinates.offsets().toArray(BigInteger[]::new);
        for (int i = 0; i < result.length; i++) {
            budget.check();
            int root = coordinates.roots().get(i);
            if (root >= 0) result[i] = result[i].add(values[Arrays.binarySearch(representatives, root)].multiply(coordinates.factors().get(i)));
        }
        return result;
    }

    @Override
    public void close() {
        search.close();
        canonical.close();
        budget.release(memory);
        memory = 0;
    }
}
