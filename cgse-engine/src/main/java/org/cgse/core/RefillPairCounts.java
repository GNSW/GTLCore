// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

/**
 * Positive-only two-source cover hints. Exact intersections expose intermediate
 * mixtures without enumerating firing counts. Neither this finite set of rounded
 * corners nor its failure proves anything about the complete refill problem.
 */
final class RefillPairCounts<K> implements AutoCloseable {
    record Choice<K>(K key, GraphRecipe<K> first, GraphRecipe<K> second) {}
    private record Row(BigInteger a, BigInteger b, BigInteger need) {}
    private static final int MAX_PORTS = 32, MAX_ROWS = 16, MAX_BITS = 4096;
    private final Choice<K> choice;
    private final Function<K, BigInteger> deficit;
    private final PlanningBudget budget;
    private final List<Row> rows = new ArrayList<>();
    private Iterator<K> keys;
    private boolean second, done;
    private int i, j = 1, rounding, checking = -1;
    private BigInteger x, y, cornerX, cornerY, bestX, bestY, bestSize;
    private SummaryComputation<K> computation;
    private PlanStep word;
    private long memory;

    RefillPairCounts(Choice<K> choice, Function<K, BigInteger> deficit, PlanningBudget budget) {
        this.choice = choice; this.deficit = deficit; this.budget = budget;
        var a = choice.first(); var b = choice.second();
        if (a.id().equals(b.id()) || a.batchSensitiveInputs() || b.batchSensitiveInputs() ||
                a.inputs().size() + a.outputs().size() > MAX_PORTS ||
                b.inputs().size() + b.outputs().size() > MAX_PORTS) { done = true; return; }
        // Includes the bounded rows, exact integer scratch and returned word.
        long bytes = 8192L + 1024L * (a.inputs().size() + a.outputs().size() + b.inputs().size() + b.outputs().size());
        if (!budget.tryReserve(bytes)) { done = true; return; }
        memory = bytes;
        keys = a.outputs().keySet().iterator();
    }

    boolean step() {
        budget.checkpoint();
        if (done) return true;
        if (computation != null) {
            if (!computation.step()) return false;
            done = true; return true;
        }
        for (int n = 0; n < 32; n++) {
            budget.check();
            if (keys != null) {
                if (!keys.hasNext()) {
                    if (!second) { second = true; keys = choice.second().outputs().keySet().iterator(); continue; }
                    keys = null;
                    if (rows.size() < 2) { done = true; return true; }
                    // Interior mixtures only. Whole-source alternatives already
                    // received their original service before this continuation.
                    rows.add(new Row(BigInteger.ONE, BigInteger.ZERO, BigInteger.ONE));
                    rows.add(new Row(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE));
                    continue;
                }
                K key = keys.next();
                if (second && choice.first().outputs().containsKey(key)) continue;
                BigInteger a = gain(choice.first(), key), b = gain(choice.second(), key);
                // Cross-consumption requires another candidate representation;
                // a signed row cannot use this monotone corner rounding.
                if (a.signum() < 0 || b.signum() < 0) { done = true; return true; }
                BigInteger need = deficit.apply(key);
                if (a.signum() == 0 && b.signum() == 0 || need.signum() <= 0) continue;
                if (rows.size() == MAX_ROWS || need.bitLength() > MAX_BITS) { done = true; return true; }
                rows.add(new Row(a, b, need));
            } else if (checking >= 0) {
                if (checking == rows.size()) {
                    var size = x.add(y);
                    // Firing count ranks hints only; terminal supply vectors
                    // determine publication and are never replaced by this sum.
                    if (bestSize == null || size.compareTo(bestSize) < 0) {
                        bestX = x; bestY = y; bestSize = size;
                    }
                    checking = -1; continue;
                }
                var row = rows.get(checking++);
                if (row.a.multiply(x).add(row.b.multiply(y)).compareTo(row.need) < 0) checking = -1;
            } else if (cornerX != null) {
                if (rounding == 4) { cornerX = cornerY = null; continue; }
                x = cornerX.add((rounding & 1) == 0 ? BigInteger.ZERO : BigInteger.ONE);
                y = cornerY.add((rounding & 2) == 0 ? BigInteger.ZERO : BigInteger.ONE);
                rounding++;
                if (x.signum() > 0 && y.signum() > 0) checking = 0;
            } else {
                if (j == rows.size()) { i++; j = i + 1; }
                if (j >= rows.size()) {
                    if (bestSize == null) { done = true; return true; }
                    word = new PlanStep.Sequence(List.of(PlanStep.batch(choice.first().id(), bestX),
                            PlanStep.batch(choice.second().id(), bestY)));
                    computation = new SummaryComputation<>(word,
                            Map.of(choice.first().id(), choice.first(), choice.second().id(), choice.second()), budget);
                    return false;
                }
                var a = rows.get(i); var b = rows.get(j++);
                BigInteger determinant = a.a.multiply(b.b).subtract(b.a.multiply(a.b));
                if (determinant.signum() == 0) continue;
                BigInteger nx = a.need.multiply(b.b).subtract(b.need.multiply(a.b));
                BigInteger ny = a.a.multiply(b.need).subtract(b.a.multiply(a.need));
                if (determinant.signum() < 0) { determinant = determinant.negate(); nx = nx.negate(); ny = ny.negate(); }
                if (nx.signum() < 0 || ny.signum() < 0) continue;
                cornerX = nx.divide(determinant); cornerY = ny.divide(determinant); rounding = 0;
            }
        }
        return false;
    }

    private static <K> BigInteger gain(GraphRecipe<K> recipe, K key) {
        return BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L)).subtract(BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L)));
    }
    Choice<K> choice() { return choice; }
    PlanStep word() { return word; }
    BigInteger firstCount() { return bestX; }
    SequenceSummary<K> summary() { return computation == null || !done ? null : computation.result(); }
    @Override public void close() {
        if (computation != null) { computation.close(); computation = null; }
        budget.release(memory); memory = 0;
    }
}
