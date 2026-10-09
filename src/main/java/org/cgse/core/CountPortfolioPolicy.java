// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Deterministic effort-normalized feedback; scheduling observations are never proofs. */
final class CountPortfolioPolicy {

    static final long MIN_QUANTUM = 4096, MAX_QUANTUM = 32768;

    enum Mode {
        FIRST_WITNESS,
        PROOF,
        IMPROVEMENT
    }

    static class Statistics {

        long work;
        int selections, idleSlices, waiting;
        double reward, rewardVariance;
        long bestProgress, bestSpent = 1;
        long observations;
        double costMean, costM2;
    }

    static final class Arm extends Statistics {

        final long startupCost;
        final Object family;
        final Map<Mode, Statistics> modes = new EnumMap<>(Mode.class);
        Mode selectedMode = Mode.FIRST_WITNESS;
        boolean retired;
        boolean eligible = true;

        Arm(long startupCost, Object family) {
            this.startupCost = startupCost;
            this.family = family == null ? this : family;
        }
    }

    private final List<Arm> arms = new ArrayList<>();
    private final Map<Mode, Map<Object, CandidateCosts>> candidateCosts = new EnumMap<>(Mode.class);
    private final Map<Mode, CandidateScale> candidateScales = new EnumMap<>(Mode.class);
    private Mode mode = Mode.FIRST_WITNESS;
    private long turn;
    private final long[] modeTurns = new long[Mode.values().length];

    private Statistics statistics(Arm arm, Mode goal) {
        // The common first-witness path retains its allocation-free counters.
        return goal == Mode.FIRST_WITNESS ? arm : arm.modes.computeIfAbsent(goal, ignored -> new Statistics());
    }

    int selections(Arm arm) {
        return statistics(arm, mode).selections;
    }

    int idleSlices(Arm arm) {
        return statistics(arm, mode).idleSlices;
    }

    void mode(Mode value) {
        mode = value;
    }

    Mode mode() {
        return mode;
    }

    Arm add(long startupCost) {
        return add(startupCost, null);
    }

    Arm add(long startupCost, Object family) {
        Arm arm = new Arm(startupCost, family);
        arms.add(arm);
        return arm;
    }

    Arm select() {
        Arm fresh = null, overdue = null, best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int live = (int) arms.stream().filter(arm -> !arm.retired && arm.eligible).count();
        for (Arm arm : arms) {
            if (arm.retired || !arm.eligible) continue;
            Statistics state = statistics(arm, mode);
            if (state.selections == 0) {
                if (fresh == null || arm.startupCost < fresh.startupCost) fresh = arm;
                continue;
            }
            if (state.waiting >= 2L * live && (overdue == null || state.waiting > statistics(overdue, mode).waiting)) overdue = arm;
            // Progress rates have comparable units after per-arm normalization.
            // Use observed reward variation and shrinking sample uncertainty
            // for exploration; aging still gives every live arm another turn.
            double uncertainty = StrictMath.sqrt(state.rewardVariance + 1.0 / state.selections);
            long goalTurn = mode == Mode.FIRST_WITNESS ? turn : modeTurns[mode.ordinal()];
            double exploration = uncertainty * StrictMath.sqrt(StrictMath.log(goalTurn + 1.0) / state.selections);
            double score = (state.reward + exploration) * candidateEfficiency(arm);
            if (best == null || score > bestScore || score == bestScore && state.work < statistics(best, mode).work) {
                best = arm;
                bestScore = score;
            }
        }
        return fresh != null ? fresh : overdue != null ? overdue : best;
    }

    void selected(Arm arm) {
        if (mode == Mode.FIRST_WITNESS) {
            if (turn < Long.MAX_VALUE) turn++;
        } else if (modeTurns[mode.ordinal()] < Long.MAX_VALUE) modeTurns[mode.ordinal()]++;
        arm.selectedMode = mode;
        Statistics state = statistics(arm, mode);
        if (state.selections < Integer.MAX_VALUE) state.selections++;
        // Bounded ages remain meaningful even if lifetime counters saturate.
        for (Arm other : arms) if (!other.retired && other.eligible) {
            Statistics next = statistics(other, mode);
            if (next.selections == 0) continue;
            if (other == arm) next.waiting = 1;
            else if (next.waiting < Integer.MAX_VALUE) next.waiting++;
        }
    }

    long quantum(Arm arm, long remaining) {
        Statistics state = statistics(arm, mode);
        long live = arms.stream().filter(next -> !next.retired && next.eligible).count();
        // Estimate one completed batch, including its atomic-step overshoot.
        // Time spent in earlier unproductive batches is already charged to
        // work/reward; using it again as the next batch's cost would reward a
        // stalled arm with larger slices whenever it reports sparse progress.
        // The standard error leaves room for uncertain actual batch costs.
        double estimated = state.observations == 0 ? MIN_QUANTUM : upperCost(state.costMean, state.costM2, state.observations);
        long requested = live <= 1 ? MAX_QUANTUM : Math.max(MIN_QUANTUM,
                (long) Math.min(MAX_QUANTUM, StrictMath.ceil(estimated * state.reward)));
        return Math.min(requested, remaining);
    }

    void feedback(Arm arm, long spent, long progress) {
        if (spent < 0) throw new IllegalArgumentException("Negative completed effort");
        // A retained batch can finish after the parent changes goals. Attribute
        // it to the mode that selected it, not whichever mode is current now.
        Statistics state = statistics(arm, arm.selectedMode);
        long positive = Math.max(0, progress), divisor = Math.max(1, spent);
        long bestProgress = state.bestProgress, bestSpent = state.bestSpent;
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
        double reward = state.selections == 1 ? sample : 0.75 * state.reward + 0.25 * sample;
        double rewardVariance = state.selections == 1 ? 0 : 0.75 * state.rewardVariance + 0.25 * (sample - state.reward) * (sample - reward);
        long nextWork = spent > Long.MAX_VALUE - state.work ? Long.MAX_VALUE : state.work + spent;

        // Welford statistics use actual completed work, including atomic-step
        // overshoot. Every completed batch contributes exactly once.
        long observations = state.observations == Long.MAX_VALUE ? Long.MAX_VALUE : state.observations + 1;
        double delta = spent - state.costMean;
        double costMean = state.costMean + delta / observations;
        double costM2 = Math.max(0, state.costM2 + delta * (spent - costMean));
        state.observations = observations;
        state.costMean = costMean;
        state.costM2 = costM2;
        state.work = nextWork;
        state.bestProgress = bestProgress;
        state.bestSpent = bestSpent;
        state.reward = reward;
        state.rewardVariance = rewardVariance;
        state.idleSlices = positive > 0 ? 0 : Math.min(4, state.idleSlices + 1);
    }

    /** Completed candidate pipeline observations, separate from solver-internal progress. */
    void candidateFeedback(Arm source, long upstream, long downstream, boolean resolved, boolean verified) {
        candidateFeedback(mode, source, upstream, downstream, resolved, verified);
    }

    void candidateFeedback(Mode goal, Arm source, long upstream, long downstream, boolean resolved, boolean verified) {
        if (upstream < 0 || downstream < 0 || verified && !resolved) throw new IllegalArgumentException("Invalid candidate observation");
        CandidateCosts costs = candidateCosts.computeIfAbsent(goal, ignored -> new HashMap<>()).computeIfAbsent(source.family, ignored -> new CandidateCosts());
        if (costs.observations < Long.MAX_VALUE) costs.observations++;
        // Convert before adding so two legal long work totals cannot overflow.
        double completedWork = Math.max(1.0, (double) upstream + downstream);
        costs.totalWork += completedWork;
        if (resolved && costs.resolved < Long.MAX_VALUE) costs.resolved++;
        if (verified && costs.verified < Long.MAX_VALUE) costs.verified++;
        CandidateScale scale = candidateScales.computeIfAbsent(goal, ignored -> new CandidateScale());
        if (scale.observations < Long.MAX_VALUE) scale.observations++;
        scale.meanWork += (completedWork - scale.meanWork) / scale.observations;
    }

    double candidateEfficiency(Arm arm) {
        CandidateCosts costs = candidateCosts.getOrDefault(mode, Map.of()).get(arm.family);
        if (costs == null) return 1;
        // Completed contributions per FULL pipeline work. Splitting the same
        // effort between solving and validation must not change its value, and
        // cheaper solving at identical downstream cost must never be penalized.
        // One successful observation at this mode's mean cost is a deterministic
        // prior. UNKNOWN/cutoffs add cost without claiming a failed proof.
        double reference = Math.max(1.0, candidateScales.get(mode).meanWork);
        double inverseRate = (costs.totalWork + reference) / (reference * (costs.verified + 1.0));
        // Normalize around the prior's rate without unbounded amplification.
        // Mandatory first visits and aging remain independent of this score.
        return 2.0 / (1.0 + inverseRate);
    }

    private static final class CandidateCosts {

        long observations, resolved, verified;
        double totalWork;
    }

    private static final class CandidateScale {

        long observations;
        double meanWork;
    }

    private static double upperCost(double mean, double m2, long observations) {
        return mean + (observations <= 1 ? 0 : StrictMath.sqrt(m2 / (observations - 1) / observations));
    }
}
