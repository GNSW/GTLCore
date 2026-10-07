package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** SCIP-style dynamic rounding locks; these preferences never restrict a domain. */
final class CountLockBranch {

    record Decision(int variable, boolean up) {}

    static Decision choose(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                           double[] activity, PlanningBudget budget) {
        long bytes = 256L + 24L * low.length;
        if (!budget.tryReserve(bytes)) return null;
        try {
            int[] up = new int[low.length], down = new int[low.length];
            double[] degree = new double[low.length];
            for (var row : rows) {
                BigInteger maximum = BigInteger.ZERO;
                boolean finite = true;
                int unfixed = 0;
                for (var term : row.terms().entrySet()) {
                    budget.operation(PlanningBudget.Operation.SCAN, 0);
                    if (term.getValue().signum() != 0 && !low[term.getKey()].equals(high[term.getKey()])) unfixed++;
                    BigInteger endpoint = term.getValue().signum() > 0 ? high[term.getKey()] : low[term.getKey()];
                    if (endpoint == null) finite = false;
                    else {
                        budget.operation(PlanningBudget.Operation.INTEGER, Math.max(term.getValue().bitLength(), endpoint.bitLength()));
                        maximum = maximum.add(term.getValue().multiply(endpoint));
                    }
                }
                if (finite && maximum.compareTo(row.upper()) <= 0) continue;
                for (var term : row.terms().entrySet()) {
                    budget.operation(PlanningBudget.Operation.SCAN, 0);
                    int id = term.getKey();
                    if (low[id].equals(high[id]) || term.getValue().signum() == 0) continue;
                    // Count only still active coordinates. Fixed auxiliaries
                    // and fulfilled domain rows must not distort this score.
                    degree[id] += 1.0 / unfixed;
                    if (term.getValue().signum() > 0) up[id]++;
                    else if (term.getValue().signum() < 0) down[id]++;
                }
            }
            int best = -1;
            for (int i = 0; i < low.length; i++) {
                budget.operation(PlanningBudget.Operation.SCAN, 0);
                double score = (up[i] + down[i]) * (1 + activity[i] + degree[i]);
                if (!low[i].equals(high[i]) && (best < 0 ||
                        score > (up[best] + down[best]) * (1 + activity[best] + degree[best])))
                    best = i;
            }
            return best < 0 ? null : new Decision(best, down[best] >= up[best]);
        } finally {
            budget.release(bytes);
        }
    }
}
