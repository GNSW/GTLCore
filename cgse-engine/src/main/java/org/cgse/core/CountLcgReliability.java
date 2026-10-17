// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.List;
import java.util.function.BiConsumer;

/** Per-request propagation pseudocosts. Observations affect ordering only. */
final class CountLcgReliability implements AutoCloseable {

    private final PlanningBudget budget;
    private final Runnable charge;
    private final BiConsumer<BigInteger, BigInteger> integerCost;
    private final double[][] mean, deviation;
    private final int[][] samples;
    private final int[] lastProbe;
    private long memory, observations, reliableChoices, probeWork, probes;

    CountLcgReliability(int n, PlanningBudget budget, Runnable charge, BiConsumer<BigInteger, BigInteger> integerCost) {
        this.budget = budget;
        this.charge = charge;
        this.integerCost = integerCost;
        long bytes = 256L + 192L * n;
        if (!budget.tryReserve(bytes)) {
            mean = deviation = null;
            samples = null;
            lastProbe = null;
            return;
        }
        memory = bytes;
        try {
            mean = new double[n][2];
            deviation = new double[n][2];
            samples = new int[n][2];
            lastProbe = new int[n];
        } catch (RuntimeException | Error failed) {
            budget.release(memory);
            memory = 0;
            throw failed;
        }
    }

    void observe(int id, boolean up, double point, int implications, boolean conflict) {
        if (mean == null) return;
        charge.run();
        int side = up ? 1 : 0;
        double distance = distance(point, up);
        // A conflict earns one closed branch, not an invented objective bound.
        double value = (1 + Math.max(0, implications) + (conflict ? Math.sqrt(mean.length) : 0)) / distance;
        int count = Math.min(31, samples[id][side]);
        double old = mean[id][side];
        mean[id][side] = old + (value - old) / (count + 1);
        deviation[id][side] += ((value - old) * (value - mean[id][side]) - deviation[id][side]) / (count + 1);
        samples[id][side] = count + 1;
        observations++;
    }

    private boolean reliable(int id) {
        if (mean == null) return false;
        for (int side = 0; side < 2; side++) {
            int n = samples[id][side];
            if (n < 3 || n < 8 && Math.sqrt(Math.max(0, deviation[id][side]) / n) > mean[id][side]) return false;
        }
        return true;
    }

    double factor(int id, int baseline, double[] point) {
        if (!reliable(id) || !reliable(baseline)) return 1;
        charge.run();
        double a = score(id, point[id], -3), b = score(baseline, point[baseline], 3);
        // Promote only when the estimated gain intervals separate. The old
        // ordering wins noisy or tied estimates, even after many observations.
        return Math.max(1, Math.min(1.25, Math.sqrt(a / Math.max(1e-9, b))));
    }

    private double score(int id, double point, int errors) {
        double down = Math.max(0, mean[id][0] + errors * Math.sqrt(Math.max(0, deviation[id][0]) / samples[id][0])) * distance(point, false);
        double up = Math.max(0, mean[id][1] + errors * Math.sqrt(Math.max(0, deviation[id][1]) / samples[id][1])) * distance(point, true);
        return Math.min(down, up) + 0.125 * Math.max(down, up);
    }

    void chosen(int chosen, int baseline) {
        if (chosen != baseline) reliableChoices++;
    }

    /** Small, detached propagation probes never mutate the live trail or prove a cut. */
    void probe(int id, double point, int decision, long ordinaryWork,
               List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {
        if (mean == null || reliable(id) || decision - lastProbe[id] < 32) return;
        long allowance = Math.min(4096, Math.min(budget.remainingWork() / 128, ordinaryWork / 64 - probeWork));
        if (allowance < 512) return;
        lastProbe[id] = decision;
        long bytes = 256L + 64L * low.length;
        if (!budget.tryReserve(bytes)) return;
        long start = budget.threadWork();
        try {
            for (int side = 0; side < 2; side++) {
                if (samples[id][side] >= 3) continue;
                BigInteger[] lo = low.clone(), hi = high.clone();
                for (int v = 0; v < lo.length; v++) check(start, allowance);
                if (side == 0) hi[id] = BigInteger.ZERO;
                else lo[id] = BigInteger.ONE;
                int implications = 0;
                boolean changed = true, conflict = false;
                while (changed && !conflict) {
                    changed = false;
                    for (var row : rows) {
                        BigInteger minimum = BigInteger.ZERO;
                        for (var term : row.terms().entrySet()) {
                            check(start, allowance);
                            int v = term.getKey();
                            BigInteger a = term.getValue();
                            if ((a.signum() > 0 ? lo[v] : hi[v]).signum() != 0) {
                                checkInteger(start, allowance, minimum, a);
                                minimum = minimum.add(a);
                            }
                        }
                        checkInteger(start, allowance, row.upper(), minimum);
                        BigInteger slack = row.upper().subtract(minimum);
                        if (slack.signum() < 0) {
                            conflict = true;
                            break;
                        }
                        for (var term : row.terms().entrySet()) {
                            check(start, allowance);
                            int v = term.getKey();
                            if (lo[v].equals(hi[v])) continue;
                            checkInteger(start, allowance, slack, term.getValue());
                            if (slack.compareTo(term.getValue().abs()) >= 0) continue;
                            if (term.getValue().signum() > 0) hi[v] = BigInteger.ZERO;
                            else lo[v] = BigInteger.ONE;
                            implications++;
                            changed = true;
                        }
                    }
                }
                check(start, allowance);
                observe(id, side != 0, point, implications, conflict);
                probes++;
            }
        } catch (Stop ignored) {
            // An unfinished probe provides no observation or mathematical fact.
        } finally {
            probeWork += budget.threadWork() - start;
            budget.release(bytes);
        }
    }

    private void check(long start, long allowance) {
        if (budget.threadWork() - start + 1 >= allowance) throw new Stop();
        charge.run();
    }

    private void checkInteger(long start, long allowance, BigInteger a, BigInteger b) {
        long words = Math.max(1, Math.min(32, (Math.max(a.bitLength(), b.bitLength()) + 63L) / 64));
        if (budget.threadWork() - start + (words + 1) / 2 >= allowance) throw new Stop();
        integerCost.accept(a, b);
    }

    private static double distance(double point, boolean up) {
        if (!Double.isFinite(point)) return 0.5;
        return Math.max(0.05, Math.min(1, up ? 1 - point : point));
    }

    String diagnostic() {
        return "observations=" + observations + "; choices=" + reliableChoices + "; probes=" + probes + "; probe_work=" + probeWork;
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }
}
