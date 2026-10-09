// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Equivalent bounded simplices with identical external columns share an integer transport domain. */
final class CountGroups implements AutoCloseable {

    private record Group(int[] variables, BigInteger capacity, boolean exact) {}

    private record Shape(boolean exact, List<List<BigInteger>> columns) {}

    private record Family(List<Group> groups, int[] variables) {}

    private final List<ExactLinearProgram.Constraint> original;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private final List<Family> families = new ArrayList<>();
    private CountQuickSolve solving;
    private int[] mapping;
    private BigInteger[] counts;
    private long memory;
    private boolean prepared, complete, infeasible;
    private final List<CountConflict> learned = new ArrayList<>();

    CountGroups(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, PlanningBudget budget) {
        original = rows;
        this.lower = lower;
        this.upper = upper;
        this.budget = budget;
    }

    boolean step() {
        if (complete) return true;
        budget.check();
        if (!prepared) {
            prepared = true;
            if (!prepare()) {
                complete = true;
                return true;
            }
        }
        if (!solving.step()) return false;
        var result = solving.counts();
        infeasible = solving.infeasible();
        var columns = new ArrayList<List<Integer>>();
        for (int i = 0; i <= Arrays.stream(mapping).max().orElse(-1); i++) columns.add(new ArrayList<>());
        for (int i = 0; i < mapping.length; i++) columns.get(mapping[i]).add(i);
        BigInteger[] shifts = new BigInteger[lower.length];
        Arrays.fill(shifts, BigInteger.ZERO);
        memory += CountMapping.retain(learned, CountMapping.sums(columns, shifts).conflicts(solving.learnedConflicts(), budget), budget);
        solving.close();
        solving = null;
        complete = true;
        if (result == null) return true;
        var lifted = new BigInteger[lower.length];
        for (int i = 0; i < mapping.length; i++) if (mapping[i] >= 0) lifted[i] = result[mapping[i]];
        for (var family : families) {
            BigInteger[] left = Arrays.stream(family.variables).mapToObj(i -> result[i]).toArray(BigInteger[]::new);
            for (var group : family.groups) {
                BigInteger capacity = group.capacity;
                for (int option = 0; option < left.length; option++) {
                    budget.check();
                    BigInteger used = left[option].min(capacity);
                    lifted[group.variables[option]] = used;
                    left[option] = left[option].subtract(used);
                    capacity = capacity.subtract(used);
                }
                if (group.exact && capacity.signum() != 0) throw new IllegalStateException("Unfilled exact source group");
            }
            if (Arrays.stream(left).anyMatch(v -> v.signum() != 0)) throw new IllegalStateException("Unliftable source group counts");
        }
        for (int i = 0; i < lifted.length; i++) if (lifted[i].compareTo(lower[i]) < 0 || upper[i] != null && lifted[i].compareTo(upper[i]) > 0)
            throw new IllegalStateException("Group lifting exceeds domain");
        for (var row : original) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.check();
                sum = sum.add(term.getValue().multiply(lifted[term.getKey()]));
            }
            if (sum.compareTo(row.upper()) > 0) throw new IllegalStateException("Group lifting violates original row");
        }
        counts = lifted;
        budget.note("count_groups", "lifted_witness; families=" + families.size());
        return true;
    }

    private boolean prepare() {
        if (lower.length > 512 || original.size() > 1024) return false;
        var groups = new ArrayList<Group>();
        Map<Integer, Integer> internal = new HashMap<>();
        Map<Map<Integer, BigInteger>, Integer> rows = new HashMap<>();
        for (int r = 0; r < original.size(); r++) rows.put(original.get(r).terms(), r);
        BitSet taken = new BitSet();
        for (int r = 0; r < original.size(); r++) {
            budget.check();
            var row = original.get(r);
            if (row.terms().size() < 2 || row.terms().size() > 8 || row.upper().signum() <= 0 ||
                    row.terms().entrySet().stream().anyMatch(e -> !e.getValue().equals(BigInteger.ONE) || taken.get(e.getKey()) ||
                            lower[e.getKey()].signum() != 0 || upper[e.getKey()] != null && upper[e.getKey()].compareTo(row.upper()) < 0))
                continue;
            Map<Integer, BigInteger> opposite = new LinkedHashMap<>();
            row.terms().keySet().forEach(id -> opposite.put(id, BigInteger.ONE.negate()));
            Integer reverse = rows.get(opposite);
            boolean exact = reverse != null && original.get(reverse).upper().equals(row.upper().negate());
            int index = groups.size();
            internal.put(r, index);
            if (exact) internal.put(reverse, index);
            int[] ids = row.terms().keySet().stream().mapToInt(i -> i).sorted().toArray();
            for (int id : ids) taken.set(id);
            groups.add(new Group(ids, row.upper(), exact));
        }
        if (groups.size() < 2) return false;
        long entries = original.stream().mapToLong(row -> row.terms().size()).sum();
        long bytes = 2048 + 1024L * lower.length + 256L * entries + 16L * lower.length * original.size();
        if (!budget.tryReserve(bytes)) return false;
        memory = bytes;
        BitSet domainRows = new BitSet();
        for (int r = 0; r < original.size(); r++) {
            var row = original.get(r);
            if (row.terms().size() != 1) continue;
            var term = row.terms().entrySet().iterator().next();
            BigInteger end = term.getValue().signum() > 0 ? upper[term.getKey()] : lower[term.getKey()];
            if (end != null && term.getValue().multiply(end).compareTo(row.upper()) <= 0) domainRows.set(r);
        }
        Map<Shape, List<Group>> identical = new LinkedHashMap<>();
        for (var group : groups) {
            Map<Integer, List<BigInteger>> columns = new HashMap<>();
            for (int id : group.variables) {
                List<BigInteger> column = new ArrayList<>();
                for (int r = 0; r < original.size(); r++) if (!internal.containsKey(r) && !domainRows.get(r)) {
                    budget.check();
                    column.add(original.get(r).terms().getOrDefault(id, BigInteger.ZERO));
                }
                columns.put(id, column);
            }
            var order = Arrays.stream(group.variables).boxed().sorted((a, b) -> {
                var x = columns.get(a);
                var y = columns.get(b);
                for (int i = 0; i < x.size(); i++) {
                    int c = x.get(i).compareTo(y.get(i));
                    if (c != 0) return c;
                }
                return Integer.compare(a, b);
            }).toList();
            var shape = new Shape(group.exact, order.stream().map(columns::get).toList());
            identical.computeIfAbsent(shape, unused -> new ArrayList<>()).add(new Group(order.stream().mapToInt(i -> i).toArray(), group.capacity, group.exact));
        }
        mapping = new int[lower.length];
        Arrays.fill(mapping, -1);
        List<BigInteger> low = new ArrayList<>(), high = new ArrayList<>();
        BitSet merged = new BitSet();
        for (var family : identical.values()) if (family.size() > 1) {
            BigInteger capacity = family.stream().map(Group::capacity).reduce(BigInteger.ZERO, BigInteger::add);
            int[] variables = new int[family.get(0).variables.length];
            for (int option = 0; option < variables.length; option++) {
                variables[option] = low.size();
                low.add(BigInteger.ZERO);
                high.add(capacity);
                for (var group : family) {
                    mapping[group.variables[option]] = variables[option];
                    merged.set(group.variables[option]);
                }
            }
            families.add(new Family(family, variables));
        }
        if (families.isEmpty()) return false;
        for (int i = 0; i < mapping.length; i++) if (mapping[i] < 0) {
            mapping[i] = low.size();
            low.add(lower[i]);
            high.add(upper[i]);
        }
        var projected = new ArrayList<ExactLinearProgram.Constraint>();
        for (int r = 0; r < original.size(); r++) {
            budget.check();
            var row = original.get(r);
            if (row.terms().keySet().stream().anyMatch(merged::get) && (internal.containsKey(r) || domainRows.get(r))) continue;
            Map<Integer, BigInteger> terms = new LinkedHashMap<>();
            for (var term : row.terms().entrySet()) {
                int id = mapping[term.getKey()];
                BigInteger prior = terms.putIfAbsent(id, term.getValue());
                if (prior != null && !prior.equals(term.getValue())) throw new IllegalStateException("Non-equivalent source columns");
            }
            projected.add(new ExactLinearProgram.Constraint(terms, row.upper()));
        }
        for (var family : families) {
            Map<Integer, BigInteger> positive = new LinkedHashMap<>(), negative = new LinkedHashMap<>();
            for (int id : family.variables) {
                positive.put(id, BigInteger.ONE);
                negative.put(id, BigInteger.ONE.negate());
            }
            BigInteger capacity = high.get(family.variables[0]);
            projected.add(new ExactLinearProgram.Constraint(positive, capacity));
            if (family.groups.get(0).exact) projected.add(new ExactLinearProgram.Constraint(negative, capacity.negate()));
        }
        budget.note("count_groups", "variables=" + lower.length + "->" + low.size() + "; families=" + families.size());
        solving = new CountQuickSolve(projected, low.toArray(BigInteger[]::new), high.toArray(BigInteger[]::new), budget);
        return true;
    }

    BigInteger[] counts() {
        return counts;
    }

    boolean infeasible() {
        return infeasible;
    }

    List<CountConflict> learnedConflicts() {
        return List.copyOf(learned);
    }

    @Override
    public void close() {
        if (solving != null) solving.close();
        budget.release(memory);
        memory = 0;
    }
}
