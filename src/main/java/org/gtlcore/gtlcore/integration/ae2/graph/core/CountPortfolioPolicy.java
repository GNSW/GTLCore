package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/** Deterministic effort-normalized feedback; scheduling observations are never proofs. */
final class CountPortfolioPolicy {

    static final long MIN_QUANTUM = 4096, MAX_QUANTUM = 32768;

    static final class Arm {

        final long startupCost;
        long work;
        int selections, idleSlices, waiting;
        double reward, rewardVariance;
        boolean retired;
        long bestProgress, bestSpent = 1;
        long observations, progressObservations, pendingWork;
        double costMean, costM2, progressCostMean, progressCostM2;

        Arm(long startupCost) {
            this.startupCost = startupCost;
        }
    }

    private final List<Arm> arms = new ArrayList<>();
    private long turn;

    Arm add(long startupCost) {
        Arm arm = new Arm(startupCost);
        arms.add(arm);
        return arm;
    }

    Arm select() {
        Arm fresh = null, overdue = null, best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int live = (int) arms.stream().filter(arm -> !arm.retired).count();
        for (Arm arm : arms) {
            if (arm.retired) continue;
            if (arm.selections == 0) {
                if (fresh == null || arm.startupCost < fresh.startupCost) fresh = arm;
                continue;
            }
            if (arm.waiting >= 2L * live && (overdue == null || arm.waiting > overdue.waiting)) overdue = arm;
            // Progress rates have comparable units after per-arm normalization.
            // Use observed reward variation and shrinking sample uncertainty
            // for exploration; aging still gives every live arm another turn.
            double uncertainty = StrictMath.sqrt(arm.rewardVariance + 1.0 / arm.selections);
            double exploration = uncertainty * StrictMath.sqrt(StrictMath.log(turn + 1.0) / arm.selections);
            double score = arm.reward + exploration;
            if (best == null || score > bestScore || score == bestScore && arm.work < best.work) {
                best = arm;
                bestScore = score;
            }
        }
        return fresh != null ? fresh : overdue != null ? overdue : best;
    }

    void selected(Arm arm) {
        if (turn < Long.MAX_VALUE) turn++;
        if (arm.selections < Integer.MAX_VALUE) arm.selections++;
        // Bounded ages remain meaningful even if lifetime counters saturate.
        for (Arm other : arms) if (!other.retired && other.selections > 0) {
            if (other == arm) other.waiting = 1;
            else if (other.waiting < Integer.MAX_VALUE) other.waiting++;
        }
    }

    long quantum(Arm arm, long remaining) {
        long live = arms.stream().filter(next -> !next.retired).count();
        // Estimate the effort of a completed observation and of reaching useful
        // progress. The standard error leaves room for uncertain costs; recent
        // reward contracts stale allocations instead of retaining a large batch.
        double estimated = arm.observations == 0 ? MIN_QUANTUM : upperCost(arm.costMean, arm.costM2, arm.observations);
        if (arm.progressObservations > 0) estimated = Math.max(estimated,
                upperCost(arm.progressCostMean, arm.progressCostM2, arm.progressObservations));
        long requested = live <= 1 ? MAX_QUANTUM : Math.max(MIN_QUANTUM,
                (long) Math.min(MAX_QUANTUM, StrictMath.ceil(estimated * arm.reward)));
        return Math.min(requested, remaining);
    }

    void feedback(Arm arm, long spent, long progress) {
        if (spent < 0) throw new IllegalArgumentException("Negative completed effort");
        long positive = Math.max(0, progress), divisor = Math.max(1, spent);
        long bestProgress = arm.bestProgress, bestSpent = arm.bestSpent;
        double sample = 0;
        if (positive > 0) {
            if (bestProgress == 0) {
                bestProgress = positive;
                bestSpent = divisor;
                sample = 1;
            } else {
                // Conflicts, bound reductions and local-search improvements have
                // different units. Compare each arm's rate with its own best;
                // changing its progress unit must not change scheduling scores.
                BigInteger numerator = BigInteger.valueOf(positive).multiply(BigInteger.valueOf(bestSpent));
                BigInteger denominator = BigInteger.valueOf(bestProgress).multiply(BigInteger.valueOf(divisor));
                if (numerator.compareTo(denominator) >= 0) {
                    bestProgress = positive;
                    bestSpent = divisor;
                    sample = 1;
                } else sample = new BigDecimal(numerator).divide(new BigDecimal(denominator), MathContext.DECIMAL64).doubleValue();
            }
        }
        // Publish only a completed observation. EWMA forgets old success once
        // a continuation stops helping; aging still retains every live arm.
        double reward = arm.selections == 1 ? sample : 0.75 * arm.reward + 0.25 * sample;
        double rewardVariance = arm.selections == 1 ? 0 : 0.75 * arm.rewardVariance + 0.25 * (sample - arm.reward) * (sample - reward);
        long nextWork = spent > Long.MAX_VALUE - arm.work ? Long.MAX_VALUE : arm.work + spent;

        // Welford statistics use actual completed work, including atomic-step
        // overshoot. Pending work measures the interval between useful updates.
        long observations = arm.observations == Long.MAX_VALUE ? Long.MAX_VALUE : arm.observations + 1;
        double delta = spent - arm.costMean;
        double costMean = arm.costMean + delta / observations;
        double costM2 = Math.max(0, arm.costM2 + delta * (spent - costMean));
        long pending = spent > Long.MAX_VALUE - arm.pendingWork ? Long.MAX_VALUE : arm.pendingWork + spent;
        long progressObservations = arm.progressObservations;
        double progressCostMean = arm.progressCostMean, progressCostM2 = arm.progressCostM2;
        if (positive > 0) {
            if (progressObservations < Long.MAX_VALUE) progressObservations++;
            double progressDelta = pending - progressCostMean;
            progressCostMean += progressDelta / progressObservations;
            progressCostM2 = Math.max(0, progressCostM2 + progressDelta * (pending - progressCostMean));
            pending = 0;
        }
        arm.observations = observations;
        arm.costMean = costMean;
        arm.costM2 = costM2;
        arm.progressObservations = progressObservations;
        arm.progressCostMean = progressCostMean;
        arm.progressCostM2 = progressCostM2;
        arm.pendingWork = pending;
        arm.work = nextWork;
        arm.bestProgress = bestProgress;
        arm.bestSpent = bestSpent;
        arm.reward = reward;
        arm.rewardVariance = rewardVariance;
        arm.idleSlices = positive > 0 ? 0 : Math.min(4, arm.idleSlices + 1);
    }

    private static double upperCost(double mean, double m2, long observations) {
        return mean + (observations <= 1 ? 0 : StrictMath.sqrt(m2 / (observations - 1) / observations));
    }
}
