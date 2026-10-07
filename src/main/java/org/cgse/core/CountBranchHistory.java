package org.cgse.core;

import java.math.BigInteger;

/** Per-order reliability pseudocosts for propagation gain, never pruning facts. */
final class CountBranchHistory implements AutoCloseable {

    private final PlanningBudget budget;
    private double[][] gain;
    private int[][] samples;
    private long memory;

    CountBranchHistory(int variables, PlanningBudget budget) {
        this.budget = budget;
        long bytes = 128 + 64L * variables;
        if (budget.tryReserve(bytes)) {
            memory = bytes;
            gain = new double[variables][2];
            samples = new int[variables][2];
        }
    }

    synchronized void observe(int id, int side, ExactRational point, double improvement) {
        if (gain == null) return;
        double scaled = improvement / distance(point, side);
        int count = samples[id][side];
        // Forget gradually so one old outlier cannot monopolize future choices.
        if (count >= 32) {
            gain[id][side] *= 0.5;
            samples[id][side] /= 2;
        }
        gain[id][side] += Math.min(1e9, scaled);
        samples[id][side]++;
    }

    synchronized boolean reliable(int id) {
        return samples != null && samples[id][0] >= 3 && samples[id][1] >= 3;
    }

    synchronized double score(int id, ExactRational point) {
        if (!reliable(id)) return -1;
        double down = gain[id][0] / samples[id][0] * distance(point, 0);
        double up = gain[id][1] / samples[id][1] * distance(point, 1);
        return Math.min(down, up) + 0.125 * Math.max(down, up);
    }

    private static double distance(ExactRational point, int side) {
        BigInteger denominator = point.denominator();
        BigInteger numerator = point.numerator().subtract(point.floor().multiply(denominator));
        if (side != 0) numerator = denominator.subtract(numerator);
        int shift = Math.max(0, denominator.bitLength() - 53);
        return Math.max(1e-6, numerator.shiftRight(shift).doubleValue() / denominator.shiftRight(shift).doubleValue());
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
