// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Minimal knapsack-cover separation, as used in SCIP/OR-Tools cutting planes.
 * A set of Boolean choices whose combined minimum consumption exceeds a row's
 * capacity cannot all be taken. Only cuts violated by the exact LP point are
 * retained; the original weighted rows and all integer choices stay present.
 */
final class CountCoverCuts implements AutoCloseable {

    private static final class Stop extends RuntimeException {

        private static final Stop INSTANCE = new Stop();

        private Stop() {
            super(null, null, false, false);
        }
    }

    private record Literal(int id, int sign, BigInteger weight, BigInteger offset, ExactRational value) {}

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final ExactRational[] point;
    private final PlanningBudget budget;
    private final List<ExactLinearProgram.Constraint> cuts = new ArrayList<>();
    private final Set<ExactLinearProgram.Constraint> known = new HashSet<>();
    private final Set<ExactLinearProgram.Constraint> lifted = new HashSet<>();
    private final long allowance;
    private final boolean fair;
    private final List<Integer> eligible = new ArrayList<>();
    private final Map<ExactLinearProgram.Constraint, ExactRational> scores = new HashMap<>();
    private long work, memory;
    private long rowUntil;
    private int cursor;
    private boolean complete, liftingPass;

    CountCoverCuts(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                   ExactRational[] point, PlanningBudget budget) {
        this.rows = rows;
        this.lower = lower;
        this.upper = upper;
        this.point = point;
        this.budget = budget;
        allowance = Math.min(32768, budget.remainingWork() / 32);
        if (lower.length > 512 || rows.size() > 1024 || allowance < 1024) {
            fair = false;
            complete = true;
            return;
        }
        long bytes = 2048 + 256L * lower.length + 192L * rows.size() +
                (lower.length > 64 ? 2048L + 32L * rows.size() : 0);
        if (!budget.tryReserve(bytes)) {
            fair = false;
            complete = true;
            return;
        }
        memory = bytes;
        try {
            known.addAll(rows);
            long estimated = 0;
            if (lower.length > 64) for (int i = 0; i < rows.size(); i++) {
                budget.check();
                work++;
                int size = rows.get(i).terms().size();
                if (size < 2 || size > 256) continue;
                eligible.add(i);
                estimated += (long) size * size;
            }
            // Preserve the established small-model order. Wide rows can spend
            // the entire slice lifting one early cover, before later resources.
            fair = lower.length > 64 && estimated > allowance / 4;
            if (work >= allowance) complete = true;
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    boolean step() {
        try {
            return advance();
        } catch (Stop ignored) {
            return finish();
        }
    }

    private boolean advance() {
        if (complete) return true;
        charge();
        if (work >= allowance || !fair && (cursor == rows.size() || cuts.size() >= 8)) return finish();
        if (fair && cursor == eligible.size()) {
            if (liftingPass) return finish();
            // Basic separation gets the first sweep. Lifting then shares the
            // remaining work across rows, rather than renewing a row's quota.
            liftingPass = true;
            cursor = 0;
        }
        if (fair && eligible.isEmpty()) return finish();
        if (fair && liftingPass) rowUntil = Math.min(allowance,
                work + Math.max(0, (allowance - work) / (eligible.size() - cursor)));
        var row = rows.get(fair ? eligible.get(cursor++) : cursor++);
        if (row.terms().size() < 2 || row.terms().size() > 256) return false;
        BigInteger minimum = BigInteger.ZERO;
        List<Literal> literals = new ArrayList<>();
        for (var term : row.terms().entrySet()) {
            charge();
            int id = term.getKey(), sign = term.getValue().signum();
            if (sign == 0) continue;
            BigInteger endpoint = sign > 0 ? lower[id] : upper[id];
            if (endpoint == null) return false;
            minimum = minimum.add(term.getValue().multiply(endpoint));
            if (upper[id] == null || !upper[id].subtract(lower[id]).equals(BigInteger.ONE)) continue;
            BigInteger offset = sign > 0 ? lower[id].negate() : upper[id];
            ExactRational value = point[id].multiply(ExactRational.of(BigInteger.valueOf(sign))).add(ExactRational.of(offset));
            if (value.compareTo(ExactRational.ZERO) < 0 || value.compareTo(ExactRational.ONE) > 0) return false;
            literals.add(new Literal(id, sign, term.getValue().abs(), offset, value));
        }
        BigInteger capacity = row.upper().subtract(minimum);
        if (capacity.signum() < 0 || literals.size() < 2) return false;
        for (int pass = 0; pass < 3 && work < allowance && (fair || cuts.size() < 8); pass++) {
            if (pass == 0) literals.sort((a, b) -> {
                int c = ExactRational.ONE.subtract(a.value()).multiply(ExactRational.of(b.weight()))
                        .compareTo(ExactRational.ONE.subtract(b.value()).multiply(ExactRational.of(a.weight())));
                return c != 0 ? c : Integer.compare(a.id(), b.id());
            });
            else if (pass == 1) literals.sort(Comparator.comparing(Literal::value).reversed().thenComparingInt(Literal::id));
            else literals.sort(Comparator.comparing(Literal::weight).reversed().thenComparingInt(Literal::id));
            List<Literal> cover = new ArrayList<>();
            BigInteger weight = BigInteger.ZERO;
            for (Literal literal : literals) {
                charge();
                cover.add(literal);
                weight = weight.add(literal.weight());
                if (weight.compareTo(capacity) > 0) break;
            }
            if (weight.compareTo(capacity) <= 0) continue;
            cover.sort(Comparator.comparing(Literal::value).thenComparing(Literal::weight));
            for (var it = cover.iterator(); it.hasNext();) {
                charge();
                var literal = it.next();
                if (weight.subtract(literal.weight()).compareTo(capacity) > 0) {
                    weight = weight.subtract(literal.weight());
                    it.remove();
                }
            }
            // The exact sum, not a floating-point tolerance, separates the cut.
            ExactRational activity = ExactRational.ZERO;
            BigInteger bound = BigInteger.valueOf(cover.size() - 1L);
            Map<Integer, BigInteger> terms = new LinkedHashMap<>();
            for (Literal literal : cover) {
                charge();
                activity = activity.add(literal.value());
                bound = bound.subtract(literal.offset());
                terms.put(literal.id(), BigInteger.valueOf(literal.sign()));
            }
            var cut = new ExactLinearProgram.Constraint(terms, bound);
            ExactRational violation = activity.subtract(ExactRational.of(BigInteger.valueOf(cover.size() - 1L)));
            if ((!fair || !liftingPass) && violation.signum() > 0) offer(cut, violation);
            if ((!fair && cuts.size() < 8 || fair && liftingPass) && work < allowance) lift(row, literals, cover, capacity);
        }
        return false;
    }

    /** Sequential lifting uses profit-indexed DP, so long capacities are never enumerated. */
    private void lift(ExactLinearProgram.Constraint source, List<Literal> all, List<Literal> cover, BigInteger capacity) {
        int rhs = cover.size() - 1;
        if (rhs <= 0 || cover.size() > 64 || all.size() > 96) return;
        Map<Literal, Integer> coefficients = new LinkedHashMap<>();
        cover.forEach(literal -> coefficients.put(literal, 1));
        int total = cover.size();
        boolean changed = false;
        for (Literal extra : all) {
            charge();
            if (coefficients.containsKey(extra)) continue;
            long effort = (long) (total + 1) * (coefficients.size() + 1);
            if (total > 8192 || effort > (fair ? Math.min(rowUntil, allowance) : allowance) - work) break;
            long bytes = 96L * (total + 1);
            if (!budget.tryReserve(bytes)) break;
            int maximum = -1;
            try {
                BigInteger[] minimum = new BigInteger[total + 1];
                minimum[0] = BigInteger.ZERO;
                int used = 0;
                for (var term : coefficients.entrySet()) {
                    int profit = term.getValue();
                    for (int p = used; p >= 0; p--) {
                        charge();
                        if (minimum[p] == null) continue;
                        BigInteger weight = minimum[p].add(term.getKey().weight());
                        if (minimum[p + profit] == null || weight.compareTo(minimum[p + profit]) < 0) minimum[p + profit] = weight;
                    }
                    used += profit;
                }
                BigInteger available = capacity.subtract(extra.weight());
                for (int p = total; p >= 0; p--) {
                    charge();
                    if (minimum[p] != null && minimum[p].compareTo(available) <= 0) {
                        maximum = p;
                        break;
                    }
                }
            } finally {
                budget.release(bytes);
            }
            // An individually impossible choice may receive any coefficient;
            // rhs+1 suffices and remains easy for the independent checker.
            int coefficient = rhs - maximum;
            if (coefficient > 0) {
                coefficients.put(extra, coefficient);
                total += coefficient;
                changed = true;
            }
        }
        if (!changed) return;
        BigInteger limit = BigInteger.valueOf(rhs);
        ExactRational activity = ExactRational.ZERO;
        Map<Integer, BigInteger> terms = new LinkedHashMap<>();
        for (var term : coefficients.entrySet()) {
            charge();
            Literal literal = term.getKey();
            BigInteger weight = BigInteger.valueOf(term.getValue());
            activity = activity.add(literal.value().multiply(ExactRational.of(weight)));
            limit = limit.subtract(literal.offset().multiply(weight));
            terms.put(literal.id(), weight.multiply(BigInteger.valueOf(literal.sign())));
        }
        var cut = new ExactLinearProgram.Constraint(terms, limit);
        ExactRational violation = activity.subtract(ExactRational.of(BigInteger.valueOf(rhs)));
        if (violation.signum() <= 0 || !offer(cut, violation)) return;
        lifted.add(cut);
        if (budget.proofJournal() != null) budget.proofJournal().add(new CountProof.Knapsack("sequential_cover_lifting", lower.length,
                CountProof.row(source), Arrays.asList(lower), Arrays.asList(upper), CountProof.row(cut)));
    }

    private void charge() {
        if (fair && work >= allowance) throw Stop.INSTANCE;
        budget.check();
        work++;
    }

    private boolean offer(ExactLinearProgram.Constraint cut, ExactRational violation) {
        if (known.contains(cut)) return false;
        if (fair) {
            BigInteger squared = BigInteger.ZERO;
            for (var coefficient : cut.terms().values()) {
                charge();
                squared = squared.add(coefficient.multiply(coefficient));
            }
            if (squared.signum() == 0) return false;
            // Efficacy only selects among already proved, exactly violated
            // cuts. Retain at most eight; scoring never proves infeasibility.
            ExactRational score = violation.multiply(violation).divide(ExactRational.of(squared));
            if (cuts.size() == 8) {
                var weakest = cuts.get(0);
                for (var old : cuts) if (scores.get(old).compareTo(scores.get(weakest)) < 0) weakest = old;
                if (score.compareTo(scores.get(weakest)) <= 0) return false;
                cuts.remove(weakest);
                known.remove(weakest);
                scores.remove(weakest);
                lifted.remove(weakest);
            }
            scores.put(cut, score);
        }
        known.add(cut);
        cuts.add(cut);
        return true;
    }

    private boolean finish() {
        complete = true;
        if (!cuts.isEmpty()) {
            if (budget.proofJournal() != null) {
                var scope = new ArrayList<>(rows);
                for (int i = 0; i < lower.length; i++) {
                    scope.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), lower[i].negate()));
                    if (upper[i] != null) scope.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), upper[i]));
                }
                List<CountConflict> forbidden = new ArrayList<>();
                for (var cut : cuts) {
                    if (lifted.contains(cut)) continue;
                    Map<Integer, BigInteger> opposite = new LinkedHashMap<>();
                    cut.terms().forEach((id, value) -> opposite.put(id, value.negate()));
                    forbidden.add(new CountConflict(List.of(new ExactLinearProgram.Constraint(opposite, cut.upper().negate().subtract(BigInteger.ONE)))));
                }
                budget.proofJournal().add(CountProof.certificate("knapsack_cover", lower.length, scope, forbidden, null, false));
            }
            budget.note("count_cover_cuts", "violated_covers=" + cuts.size() + "; lifted=" + lifted.size() + "; work=" + work);
        }
        return true;
    }

    List<ExactLinearProgram.Constraint> cuts() {
        return List.copyOf(cuts);
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
