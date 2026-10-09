// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Optional integer witness for independent binary choices. Fixed counts are
 * substituted exactly; a few positive counts can be tried at their lower bounds.
 * A conservation bound can then saturate disjoint at-most-one pairs.
 * Every remaining nontrivial row must constrain the same weighted sum. Failure
 * or a local cutoff never declares the original problem infeasible.
 */
final class CountPartition implements AutoCloseable {

    private static final int MAX_RANGE = 262_144;
    private final List<ExactLinearProgram.Constraint> rows, reduced = new ArrayList<>();
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private final int[] partners, groups;
    private final List<Integer> representatives = new ArrayList<>();
    private final long allowance;
    private Map<Integer, BigInteger> vector;
    private BigInteger minimum, maximum;
    private BigInteger[] conserved;
    private BigInteger conservedUpper = BigInteger.ZERO;
    private BigInteger[] weights, counts;
    private boolean[] flipped;
    private long[] reachable;
    private int[] parent;
    private long memory, work;
    private int phase, rowIndex, pairs, choice, word, cap, floor, selected = -1;
    private int pinned;
    private boolean complete;

    CountPartition(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                   BigInteger[] upper, PlanningBudget budget) {
        this.rows = rows;
        this.lower = lower.clone();
        this.upper = upper.clone();
        this.budget = budget;
        partners = new int[lower.length];
        groups = new int[lower.length];
        Arrays.fill(partners, -1);
        Arrays.fill(groups, -1);
        allowance = Math.min(524_288L, budget.remainingWork() / 8);
        if (!binaryChoices(lower, upper, 1) || allowance < 1024) {
            complete = true;
            return;
        }
        // This is only a candidate face. Neither these trial equalities nor a
        // failed DP become constraints or conflict evidence in the caller.
        for (int i = 0; i < lower.length; i++) if (lower[i].signum() > 0 && !lower[i].equals(upper[i])) {
            this.upper[i] = lower[i];
            pinned++;
        }
        long entries = 0;
        for (var row : rows) entries += row.terms().size();
        long bytes = 512L + 192L * lower.length + 128L * rows.size() + 128L * entries;
        if (!budget.tryReserve(bytes)) complete = true;
        else memory = bytes;
    }

    static boolean binaryChoices(BigInteger[] lower, BigInteger[] upper, int minimumChoices) {
        // This classifies domains, not solver size. In particular the sparse
        // front end must recognize choices before complementary sources merge.
        int choices = 0, trials = 0;
        for (int i = 0; i < lower.length; i++) {
            if (upper[i] == null || lower[i].signum() < 0 || lower[i].compareTo(upper[i]) > 0) return false;
            if (lower[i].equals(upper[i])) continue;
            if (lower[i].signum() > 0) {
                if (++trials > 4) return false;
                continue;
            }
            if (lower[i].signum() != 0 || !upper[i].equals(BigInteger.ONE)) return false;
            choices++;
        }
        return choices >= minimumChoices;
    }

    boolean step() {
        if (complete) return true;
        charge();
        if (work >= allowance) return finish(null);
        switch (phase) {
            case 0 -> {
                if (rowIndex == rows.size()) {
                    rowIndex = 0;
                    phase = 1;
                    return false;
                }
                var row = rows.get(rowIndex++);
                var terms = new LinkedHashMap<Integer, BigInteger>();
                BigInteger limit = row.upper();
                for (var term : row.terms().entrySet()) {
                    charge();
                    int id = term.getKey();
                    if (lower[id].equals(upper[id])) limit = limit.subtract(term.getValue().multiply(lower[id]));
                    else if (term.getValue().signum() != 0) terms.put(id, term.getValue());
                }
                reduced.add(new ExactLinearProgram.Constraint(terms, limit));
            }
            case 1 -> {
                if (rowIndex == reduced.size()) {
                    conserved = new BigInteger[lower.length];
                    Arrays.fill(conserved, BigInteger.ZERO);
                    rowIndex = 0;
                    phase = 5;
                    return false;
                }
                var row = reduced.get(rowIndex++);
                if (row.terms().size() != 2) return false;
                var it = row.terms().entrySet().iterator();
                var first = it.next();
                var second = it.next();
                if (first.getValue().signum() > 0 && first.getValue().equals(second.getValue()) &&
                        row.upper().equals(first.getValue()) && partners[first.getKey()] < 0 && partners[second.getKey()] < 0) {
                    partners[first.getKey()] = second.getKey();
                    partners[second.getKey()] = first.getKey();
                    pairs++;
                }
            }
            case 2 -> {
                if (rowIndex == reduced.size()) return prepare();
                var row = reduced.get(rowIndex++);
                var terms = new TreeMap<Integer, BigInteger>();
                BigInteger limit = row.upper();
                for (var term : row.terms().entrySet()) {
                    charge();
                    int id = term.getKey();
                    BigInteger value = term.getValue();
                    if (partners[id] >= 0 && partners[id] < id) {
                        limit = limit.subtract(value);
                        value = value.negate();
                    }
                    terms.merge(groups[id], value, BigInteger::add);
                }
                terms.values().removeIf(value -> value.signum() == 0);
                BigInteger least = BigInteger.ZERO, most = BigInteger.ZERO, gcd = BigInteger.ZERO;
                for (BigInteger value : terms.values()) {
                    charge();
                    least = least.add(value.min(BigInteger.ZERO));
                    most = most.add(value.max(BigInteger.ZERO));
                    gcd = gcd.gcd(value);
                }
                if (least.compareTo(limit) > 0) return finish(null);
                if (most.compareTo(limit) <= 0) return false;
                boolean positive = terms.firstEntry().getValue().signum() > 0;
                BigInteger divisor = positive ? gcd : gcd.negate();
                terms.replaceAll((id, value) -> value.divide(divisor));
                if (vector == null) vector = terms;
                else if (!vector.equals(terms)) return finish(null); // Shared constraints cannot be dropped.
                if (positive) {
                    BigInteger bound = floorDiv(limit, gcd);
                    maximum = maximum == null ? bound : maximum.min(bound);
                } else {
                    BigInteger bound = floorDiv(limit, gcd).negate();
                    minimum = minimum == null ? bound : minimum.max(bound);
                }
            }
            case 3 -> {
                if (choice == weights.length) return finish(null);
                BigInteger weight = weights[choice];
                if (weight.signum() == 0 || weight.compareTo(BigInteger.valueOf(cap)) > 0 || word < 0) {
                    choice++;
                    word = reachable.length - 1;
                    return false;
                }
                int shift = weight.intValue(), source = word - (shift >>> 6), bits = shift & 63;
                long moved = source < 0 ? 0 : reachable[source] << bits;
                if (bits != 0 && source > 0) moved |= reachable[source - 1] >>> (64 - bits);
                if (word == reachable.length - 1) moved &= -1L >>> (63 - (cap & 63));
                long added = moved & ~reachable[word];
                reachable[word] |= moved;
                while (added != 0) {
                    charge();
                    int sum = (word << 6) + Long.numberOfTrailingZeros(added);
                    parent[sum] = choice + 1;
                    if (sum >= floor && selected < 0) selected = sum;
                    added &= added - 1;
                }
                word--;
                if (selected >= 0) recover();
            }
            case 4 -> {
                if (rowIndex == rows.size()) {
                    budget.note("count_partition", "witness; choices=" + weights.length + "; pairs=" + pairs +
                            "; trial_counts=" + pinned + "; normalized_range=" + cap + "; work=" + work);
                    return finish(counts);
                }
                var row = rows.get(rowIndex++);
                BigInteger value = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) {
                    charge();
                    value = value.add(term.getValue().multiply(counts[term.getKey()]));
                }
                if (value.compareTo(row.upper()) > 0) return finish(null);
            }
            case 5 -> {
                if (rowIndex < reduced.size()) {
                    var row = reduced.get(rowIndex++);
                    if (row.upper().signum() < 0 && row.terms().values().stream().allMatch(value -> value.signum() <= 0)) {
                        conservedUpper = conservedUpper.add(row.upper());
                        for (var term : row.terms().entrySet()) {
                            charge();
                            int id = term.getKey();
                            conserved[id] = conserved[id].add(term.getValue());
                        }
                    }
                    return false;
                }
                // A nonnegative sum of necessary rows reaches its minimum
                // only when each contributing exclusive pair uses one source.
                // Without this proof keep BOTH boolean variables, including
                // the option to use neither; do not force needless production.
                BigInteger least = BigInteger.ZERO;
                for (int i = 0; i < lower.length; i++) if (partners[i] < 0 || partners[i] > i)
                    least = least.add(partners[i] < 0 ? conserved[i] : conserved[i].min(conserved[partners[i]]));
                for (int i = 0; i < lower.length; i++) if (partners[i] > i &&
                        (!least.equals(conservedUpper) || conserved[i].min(conserved[partners[i]]).signum() == 0)) {
                            partners[partners[i]] = -1;
                            partners[i] = -1;
                            pairs--;
                        }
                for (int i = 0; i < lower.length; i++) if (!lower[i].equals(upper[i]) && (partners[i] < 0 || partners[i] > i)) {
                    groups[i] = representatives.size();
                    if (partners[i] >= 0) groups[partners[i]] = groups[i];
                    representatives.add(i);
                }
                rowIndex = 0;
                phase = 2;
            }
            default -> throw new IllegalStateException("Invalid partition phase");
        }
        return false;
    }

    private boolean prepare() {
        weights = new BigInteger[representatives.size()];
        flipped = new boolean[weights.length];
        BigInteger offset = BigInteger.ZERO, total = BigInteger.ZERO;
        for (int i = 0; i < weights.length; i++) {
            charge();
            BigInteger value = vector == null ? BigInteger.ZERO : vector.getOrDefault(i, BigInteger.ZERO);
            weights[i] = value.abs();
            flipped[i] = value.signum() < 0;
            offset = offset.add(value.min(BigInteger.ZERO));
            total = total.add(weights[i]);
        }
        BigInteger low = minimum == null ? BigInteger.ZERO : minimum.subtract(offset).max(BigInteger.ZERO);
        BigInteger high = maximum == null ? total : maximum.subtract(offset).min(total);
        if (low.compareTo(high) > 0) return finish(null);
        // Complement all choices when that gives a smaller exact DP domain.
        if (total.subtract(low).compareTo(high) < 0) {
            BigInteger before = low;
            low = total.subtract(high);
            high = total.subtract(before);
            for (int i = 0; i < flipped.length; i++) flipped[i] = !flipped[i];
        }
        if (high.compareTo(BigInteger.valueOf(MAX_RANGE)) > 0) {
            budget.note("count_partition", "skipped; normalized_range=" + high + "; limit=" + MAX_RANGE);
            return finish(null);
        }
        cap = high.intValueExact();
        floor = low.intValueExact();
        long bytes = 64L + 4L * (cap + 1) + 8L * ((cap >>> 6) + 1);
        if (!budget.tryReserve(bytes)) return finish(null);
        memory += bytes;
        parent = new int[cap + 1];
        reachable = new long[(cap >>> 6) + 1];
        reachable[0] = 1;
        word = reachable.length - 1;
        phase = 3;
        if (floor == 0) {
            selected = 0;
            recover();
        }
        return false;
    }

    private void recover() {
        boolean[] chosen = new boolean[weights.length];
        for (int sum = selected; sum != 0;) {
            charge();
            int id = parent[sum] - 1;
            if (id < 0 || chosen[id]) throw new IllegalStateException("Invalid partition witness");
            chosen[id] = true;
            sum -= weights[id].intValueExact();
        }
        counts = lower.clone();
        for (int i = 0; i < chosen.length; i++) {
            int id = representatives.get(i);
            counts[id] = chosen[i] != flipped[i] ? BigInteger.ONE : BigInteger.ZERO;
            if (partners[id] >= 0) counts[partners[id]] = BigInteger.ONE.subtract(counts[id]);
        }
        rowIndex = 0;
        phase = 4;
    }

    private static BigInteger floorDiv(BigInteger value, BigInteger divisor) {
        BigInteger[] parts = value.divideAndRemainder(divisor);
        return parts[1].signum() < 0 ? parts[0].subtract(BigInteger.ONE) : parts[0];
    }

    private void charge() {
        budget.check();
        work++;
    }

    private boolean finish(BigInteger[] result) {
        counts = result;
        complete = true;
        close();
        return true;
    }

    BigInteger[] counts() {
        return complete && counts != null ? counts.clone() : null;
    }

    @Override
    public void close() {
        reachable = null;
        parent = null;
        reduced.clear();
        budget.release(memory);
        memory = 0;
    }
}
