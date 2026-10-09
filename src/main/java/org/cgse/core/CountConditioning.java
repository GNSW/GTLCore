// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Construct a candidate through tightly constrained proper subsets; never proves failure. */
final class CountConditioning implements AutoCloseable {

    private final List<ExactLinearProgram.Constraint> original;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private CountQuickSolve solving;
    private int[] selected;
    private BigInteger[] counts;
    private boolean complete;
    private long memory;
    private int stages;

    CountConditioning(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, BigInteger[] partial, PlanningBudget budget) {
        original = rows;
        this.lower = lower.clone();
        this.upper = upper.clone();
        this.budget = budget;
        long bytes = 2048 + 384L * lower.length + 256L * rows.size() + 256L * rows.stream().mapToLong(row -> row.terms().size()).sum();
        if (lower.length > 512 || rows.size() > 2048 || !budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        // Mandatory counts at their lower boundary are merely a candidate.
        // The caller keeps the original domains and all alternative counts.
        for (int i = 0; i < lower.length; i++) if (this.lower[i].signum() > 0) this.upper[i] = this.lower[i];
        if (partial != null) for (int i = 0; i < partial.length; i++) if (partial[i] != null) this.lower[i] = this.upper[i] = partial[i];
    }

    boolean step() {
        if (complete) return true;
        budget.check();
        if (solving != null) {
            if (!solving.step()) return false;
            var values = solving.counts();
            solving.close();
            solving = null;
            if (values == null) {
                complete = true;
                return true;
            }
            for (int i = 0; i < selected.length; i++) lower[selected[i]] = upper[selected[i]] = values[i];
            stages++;
        }
        var live = new LinkedHashSet<Integer>();
        for (int i = 0; i < lower.length; i++) if (!lower[i].equals(upper[i])) live.add(i);
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (var row : original) {
            Map<Integer, BigInteger> terms = new LinkedHashMap<>();
            BigInteger bound = row.upper(), maximum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.check();
                int id = term.getKey();
                BigInteger a = term.getValue();
                if (!live.contains(id)) bound = bound.subtract(a.multiply(lower[id]));
                else {
                    terms.put(id, a);
                    BigInteger endpoint = a.signum() > 0 ? upper[id] : lower[id];
                    maximum = maximum == null || endpoint == null ? null : maximum.add(a.multiply(endpoint));
                }
            }
            if (maximum != null && maximum.compareTo(bound) <= 0) continue;
            if (terms.isEmpty()) {
                complete = true;
                return true;
            }
            rows.add(CountReduction.normalize(new ExactLinearProgram.Constraint(terms, bound)));
        }
        if (live.isEmpty()) {
            counts = lower.clone();
            complete = true;
            budget.note("count_conditioning", "witness; stages=" + stages);
            return true;
        }
        Set<Integer> subset = live;
        var capacities = new ArrayList<Set<Integer>>();
        for (var row : rows) if (row.terms().size() > 1 && row.terms().size() <= 8 && row.upper().equals(BigInteger.ONE) &&
                row.terms().entrySet().stream().allMatch(e -> e.getValue().equals(BigInteger.ONE) && lower[e.getKey()].signum() == 0 && BigInteger.ONE.equals(upper[e.getKey()])))
            capacities.add(row.terms().keySet());
        int richness = 0;
        for (var row : rows) {
            budget.check();
            var support = new LinkedHashSet<>(row.terms().keySet());
            boolean changed;
            do {
                changed = false;
                for (var capacity : capacities) {
                    budget.check();
                    if (!Collections.disjoint(capacity, support)) changed |= support.addAll(capacity);
                }
            } while (changed);
            if (support.size() < 2 || support.size() > 64 || support.size() >= live.size()) continue;
            Set<Map<Integer, BigInteger>> directions = new HashSet<>();
            // Prefer several independent equations over a lone capacity row.
            for (var other : rows) {
                budget.check();
                if (other.terms().size() < 2 || !support.containsAll(other.terms().keySet())) continue;
                int first = other.terms().keySet().stream().min(Integer::compareTo).orElseThrow();
                BigInteger sign = BigInteger.valueOf(other.terms().get(first).signum());
                Map<Integer, BigInteger> direction = new LinkedHashMap<>();
                other.terms().forEach((id, value) -> direction.put(id, value.multiply(sign)));
                directions.add(direction);
            }
            int same = directions.size();
            if (same >= 2 && (same * subset.size() > richness * support.size() || subset == live)) {
                subset = support;
                richness = same;
            }
        }
        if (subset == live && stages == 0) {
            complete = true;
            return true;
        }
        selected = subset.stream().mapToInt(i -> i).sorted().toArray();
        Map<Integer, Integer> ids = new HashMap<>();
        BigInteger[] low = new BigInteger[selected.length], high = new BigInteger[selected.length];
        for (int i = 0; i < selected.length; i++) {
            ids.put(selected[i], i);
            low[i] = lower[selected[i]];
            high[i] = upper[selected[i]];
        }
        var local = new ArrayList<ExactLinearProgram.Constraint>();
        for (var row : rows) if (subset.containsAll(row.terms().keySet())) {
            Map<Integer, BigInteger> terms = new LinkedHashMap<>();
            row.terms().forEach((id, value) -> terms.put(ids.get(id), value));
            local.add(new ExactLinearProgram.Constraint(terms, row.upper()));
        }
        budget.note("count_conditioning", "stage=" + stages + "; variables=" + selected.length + "/" + live.size() + "; rows=" + local.size());
        solving = new CountQuickSolve(local, low, high, budget, true);
        return false;
    }

    BigInteger[] counts() {
        return counts;
    }

    @Override
    public void close() {
        if (solving != null) solving.close();
        budget.release(memory);
        memory = 0;
    }
}
