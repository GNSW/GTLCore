package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** Exact integer substitutions shared by DP, Boolean search and rational relaxation. */
final class CountReduction implements AutoCloseable {

    record Equality(int eliminated, int retained, BigInteger multiplier, BigInteger offset,
                    ExactLinearProgram.Constraint first, ExactLinearProgram.Constraint second) {}

    private final List<ExactLinearProgram.Constraint> source;
    private final PlanningBudget budget;
    private final BigInteger[] sourceLower, sourceUpper;
    private final int[] root;
    private final BigInteger[] factor, offset;
    private final List<Equality> proof = new ArrayList<>();
    private final Map<Map<Integer, BigInteger>, ExactLinearProgram.Constraint> known = new LinkedHashMap<>();
    private final int[] partners;
    private final BigInteger[] conserved;
    private BigInteger conservedUpper = BigInteger.ZERO;
    private List<ExactLinearProgram.Constraint> rows;
    private BigInteger[] lower, upper;
    private int[] representatives;
    private long memory, work, allowance;
    private int cursor, saturatedPairs;
    private boolean complete, changed, saturated, implicationsDone;
    private CountImplications implications;
    private CountHermite hermite;
    private CountHall hall;
    private CountBounds finalBounds;
    private boolean compiled, hermiteDone, hallDone;
    private final boolean strengthening;

    CountReduction(List<ExactLinearProgram.Constraint> source, BigInteger[] lower, BigInteger[] upper, PlanningBudget budget) {
        this(source, lower, upper, budget, false);
    }

    CountReduction(List<ExactLinearProgram.Constraint> source, BigInteger[] lower, BigInteger[] upper, PlanningBudget budget, boolean strengthening) {
        this.strengthening = strengthening;
        this.source = new ArrayList<>(source);
        this.sourceLower = lower.clone();
        this.sourceUpper = upper.clone();
        this.budget = budget;
        root = new int[lower.length];
        factor = new BigInteger[lower.length];
        offset = new BigInteger[lower.length];
        partners = new int[lower.length];
        Arrays.fill(partners, -1);
        conserved = new BigInteger[lower.length];
        Arrays.fill(conserved, BigInteger.ZERO);
        for (int i = 0; i < lower.length; i++) {
            root[i] = lower[i].equals(upper[i]) ? -1 : i;
            factor[i] = BigInteger.ONE;
            offset[i] = root[i] < 0 ? lower[i] : BigInteger.ZERO;
            // Elimination must retain the domains of eliminated variables too.
            this.source.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), lower[i].negate()));
            if (upper[i] != null) this.source.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), upper[i]));
        }
        long entries = this.source.stream().mapToLong(row -> row.terms().size()).sum();
        long bytes = 1024 + 384L * lower.length + 192L * this.source.size() + 192L * entries;
        allowance = Math.min(131_072, budget.remainingWork() / 8);
        if (allowance < 1024 || !budget.tryReserve(bytes)) identity();
        else memory = bytes;
    }

    boolean step() {
        if (complete) return true;
        budget.check();
        if (compiled) {
            if (!strengthening) {
                complete = true;
                return true;
            }
            if (!hallDone) {
                if (hall == null) hall = new CountHall(rows, lower, upper, budget);
                if (!hall.step()) return false;
                var extra = hall.cuts();
                long bytes = 384L * extra.size();
                if (!extra.isEmpty() && budget.tryReserve(bytes)) {
                    memory += bytes;
                    rows.addAll(extra);
                }
                hall.close();
                hall = null;
                hallDone = true;
            }
            if (!hermiteDone) {
                if (hermite == null) hermite = new CountHermite(rows, lower.length, budget);
                if (!hermite.step()) return false;
                var extra = hermite.cuts();
                long bytes = 192L * extra.size() + 192L * extra.stream().mapToLong(r -> r.terms().size()).sum();
                if (!extra.isEmpty() && budget.tryReserve(bytes)) {
                    memory += bytes;
                    rows.addAll(extra);
                    finalBounds = new CountBounds(lower.length, rows, budget);
                }
                hermite.close();
                hermite = null;
                hermiteDone = true;
            }
            if (finalBounds != null) {
                if (!finalBounds.step()) return false;
                if (finalBounds.blocked()) rows.add(new ExactLinearProgram.Constraint(Map.of(), BigInteger.ONE.negate()));
                else {
                    lower = finalBounds.lowerBounds();
                    upper = finalBounds.upperBounds();
                }
                finalBounds.close();
                finalBounds = null;
            }
            complete = true;
            return true;
        }
        if (!implicationsDone) {
            if (implications == null) implications = new CountImplications(source, sourceLower, sourceUpper, budget);
            if (!implications.step()) return false;
            var extra = implications.cuts();
            if (!extra.isEmpty() && budget.tryReserve(384L * extra.size())) {
                memory += 384L * extra.size();
                source.addAll(extra);
            }
            implications.close();
            implications = null;
            implicationsDone = true;
        }
        if (work >= allowance) {
            // Keep all choices when compilation runs out of its local budget.
            identity();
            return true;
        }
        if (!saturated) {
            saturate();
            return false;
        }
        if (cursor == source.size()) {
            if (changed) {
                changed = false;
                known.clear();
                cursor = 0;
                // Substitution can expose a shared capacity or cancel a
                // produced resource. Reuse those consequences before handing
                // the reduced model to another search strategy.
                if (work < allowance / 2) {
                    saturateWeightedCovers();
                    saturateSums();
                }
                return false;
            }
            finish();
            return false;
        }
        var row = normalize(project(source.get(cursor++)));
        if (row.terms().isEmpty() && row.upper().signum() >= 0) return false;
        var same = known.get(row.terms());
        if (same != null && same.upper().compareTo(row.upper()) <= 0) return false;
        known.put(row.terms(), row);
        if (row.terms().size() == 1 && row.upper().signum() == 0) {
            var term = row.terms().entrySet().iterator().next();
            if (term.getValue().signum() > 0) fixZero(term.getKey());
        }
        if (row.terms().size() != 2) return false;
        Map<Integer, BigInteger> opposite = new HashMap<>();
        row.terms().forEach((key, value) -> opposite.put(key, value.negate()));
        var other = known.get(opposite);
        if (other == null || !row.upper().equals(other.upper().negate())) return false;
        var ids = row.terms().keySet().stream().sorted().toList();
        int keep = ids.get(0), remove = ids.get(1);
        BigInteger a = row.terms().get(remove), b = row.terms().get(keep);
        // After GCD normalization a unit pivot gives an exact integer affine
        // map, including ratios such as x = 1000*y + c. Never round a rational
        // substitution: doing so would lose residue classes and feasible plans.
        if (!a.abs().equals(BigInteger.ONE)) {
            if (!b.abs().equals(BigInteger.ONE)) return false;
            int swap = remove;
            remove = keep;
            keep = swap;
            BigInteger coefficient = a;
            a = b;
            b = coefficient;
        }
        BigInteger direction = b.negate().divide(a);
        BigInteger shift = row.upper().divide(a);
        if (!safeSubstitution(remove, direction, shift)) return false;
        proof.add(new Equality(remove, keep, direction, shift, row, other));
        for (int i = 0; i < root.length; i++) if (root[i] == remove) {
            charge();
            offset[i] = offset[i].add(shift.multiply(factor[i]));
            factor[i] = factor[i].multiply(direction);
            root[i] = keep;
        }
        changed = true;
        return false;
    }

    private boolean safeSubstitution(int remove, BigInteger multiplier, BigInteger shift) {
        // Doubleton substitution cannot introduce extra nonzeros. It can grow
        // coefficients, however; keep the original model if composing a ratio
        // would amplify arithmetic excessively. Unit substitutions retain the
        // existing wide-domain path.
        if (multiplier.abs().equals(BigInteger.ONE)) return true;
        for (int i = 0; i < root.length; i++) if (root[i] == remove) {
            charge();
            if (factor[i].bitLength() + multiplier.bitLength() > 256 ||
                    offset[i].add(shift.multiply(factor[i])).bitLength() > Math.max(256, sourceLower[i].bitLength() + 32))
                return false;
        }
        return true;
    }

    /**
     * A nonnegative sum of necessary rows can saturate disjoint at-most-one
     * pairs. Each pair attaining a strictly negative minimum must select one
     * source. Export that equality to every downstream solver, without fixing
     * any trial count or assuming the other coupled rows are independent.
     */
    private void saturate() {
        if (cursor < source.size()) {
            var row = normalize(project(source.get(cursor++)));
            if (row.upper().signum() < 0 && row.terms().values().stream().allMatch(v -> v.signum() < 0)) {
                conservedUpper = conservedUpper.add(row.upper());
                for (var term : row.terms().entrySet()) {
                    charge();
                    int id = term.getKey();
                    conserved[id] = conserved[id].add(term.getValue());
                }
            }
            if (row.terms().size() != 2 || !row.upper().equals(BigInteger.ONE)) return;
            var ids = row.terms().keySet().iterator();
            int a = ids.next(), b = ids.next();
            if (partners[a] >= 0 || partners[b] >= 0 || !binary(a) || !binary(b) ||
                    !row.terms().get(a).equals(BigInteger.ONE) || !row.terms().get(b).equals(BigInteger.ONE))
                return;
            partners[a] = b;
            partners[b] = a;
            return;
        }
        saturated = true;
        cursor = 0;
        saturateCovers();
        saturateWeightedCovers();
        saturateSums();
        saturateLocalPairs();
        BigInteger minimum = BigInteger.ZERO;
        for (int i = 0; i < root.length; i++) {
            charge();
            if (root[i] < 0 || partners[i] >= 0 && partners[i] < i) continue;
            if (partners[i] >= 0) minimum = minimum.add(conserved[i].min(conserved[partners[i]]).min(BigInteger.ZERO));
            else if (conserved[i].signum() != 0) {
                if (sourceUpper[i] == null) return;
                minimum = minimum.add(conserved[i].multiply(sourceUpper[i]));
            }
        }
        if (!minimum.equals(conservedUpper)) return;
        for (int i = 0; i < root.length; i++) if (partners[i] > i && conserved[i].min(conserved[partners[i]]).signum() < 0) {
            charge();
            source.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate(), partners[i], BigInteger.ONE.negate()),
                    BigInteger.ONE.negate()));
            saturatedPairs++;
        }
    }

    private boolean binary(int id) {
        return sourceLower[id].signum() == 0 && BigInteger.ONE.equals(sourceUpper[id]);
    }

    /** Cancel shared production in combined demand/capacity rows, retaining every loss term. */
    private void saturateWeightedCovers() {
        if (root.length > 64 || source.size() > 192) return;
        var needs = new ArrayList<ExactLinearProgram.Constraint>();
        var shiftedNeeds = new ArrayList<ExactLinearProgram.Constraint>();
        var capacities = new ArrayList<ExactLinearProgram.Constraint>();
        for (var original : source) {
            var row = normalize(project(original));
            if (row.terms().size() < 2 || row.terms().size() > 16) continue;
            if (row.terms().values().stream().allMatch(v -> v.signum() > 0)) capacities.add(row);
            else if (row.upper().signum() < 0 && row.terms().values().stream().filter(v -> v.signum() < 0).count() >= 2) needs.add(row);
            // Eliminating x + y = n can turn a negative demand bound into a
            // positive one. Its negative terms still combine with capacity
            // rows; the sign of the constant is not an applicability test.
            else if (row.terms().values().stream().anyMatch(v -> v.signum() < 0)) shiftedNeeds.add(row);
        }
        if (needs.size() > 16 || capacities.size() > 32) return;
        for (var row : shiftedNeeds) if (needs.size() < 16 && !needs.contains(row)) needs.add(row);
        long started = work;
        Set<ExactLinearProgram.Constraint> distinct = new HashSet<>(source);
        for (int a = 0; a < needs.size(); a++) for (int b = a + 1; b < needs.size(); b++) {
            if (work - started >= 16_384 || work >= allowance / 2) return;
            BigInteger firstScale = BigInteger.ONE, secondScale = BigInteger.ONE;
            for (var term : needs.get(a).terms().entrySet()) {
                charge();
                var other = needs.get(b).terms().get(term.getKey());
                if (other == null || other.signum() == term.getValue().signum() || other.abs().equals(term.getValue().abs())) continue;
                var gcd = other.gcd(term.getValue());
                firstScale = other.abs().divide(gcd);
                secondScale = term.getValue().abs().divide(gcd);
                break;
            }
            // Individually normalized rows may use different units. Retain
            // the ordinary sum, then try a positive integer rescaling that
            // cancels a shared term. Both are necessary consequences.
            int variants = firstScale.equals(BigInteger.ONE) && secondScale.equals(BigInteger.ONE) ? 1 : 2;
            for (int variant = 0; variant < variants; variant++) {
                if (work - started >= 16_384 || work >= allowance / 2) return;
                var firstFactor = variant == 0 ? BigInteger.ONE : firstScale;
                var secondFactor = variant == 0 ? BigInteger.ONE : secondScale;
                Map<Integer, BigInteger> terms = new LinkedHashMap<>();
                for (var term : needs.get(a).terms().entrySet()) {
                    charge();
                    terms.put(term.getKey(), term.getValue().multiply(firstFactor));
                }
                for (var term : needs.get(b).terms().entrySet()) {
                    charge();
                    terms.merge(term.getKey(), term.getValue().multiply(secondFactor), BigInteger::add);
                }
                BigInteger bound = needs.get(a).upper().multiply(firstFactor).add(needs.get(b).upper().multiply(secondFactor));
                for (var cap : capacities) {
                    BigInteger numerator = null, denominator = null;
                    int cancelled = 0;
                    boolean matches = true;
                    for (var term : cap.terms().entrySet()) {
                        charge();
                        BigInteger value = terms.getOrDefault(term.getKey(), BigInteger.ZERO);
                        if (value.signum() >= 0) continue;
                        cancelled++;
                        if (numerator == null) {
                            numerator = value.negate();
                            denominator = term.getValue();
                        } else if (!value.negate().multiply(denominator).equals(term.getValue().multiply(numerator))) {
                            matches = false;
                            break;
                        }
                    }
                    if (!matches || cancelled < 2) continue;
                    BigInteger gcd = numerator.gcd(denominator), multiply = denominator.divide(gcd), add = numerator.divide(gcd);
                    terms.replaceAll((id, value) -> value.multiply(multiply));
                    for (var term : cap.terms().entrySet()) {
                        charge();
                        terms.merge(term.getKey(), term.getValue().multiply(add), BigInteger::add);
                    }
                    terms.values().removeIf(v -> v.signum() == 0);
                    bound = bound.multiply(multiply).add(cap.upper().multiply(add));
                    if (bound.bitLength() > 256 || terms.values().stream().anyMatch(v -> v.bitLength() > 256)) break;
                }
                // Two mixed rows may cancel without any capacity row being used.
                // A cancelled coefficient proves nothing about that variable.
                terms.values().removeIf(value -> value.signum() == 0);
                if (terms.isEmpty() || terms.values().stream().anyMatch(v -> v.signum() < 0)) continue;
                if (bound.signum() > 0) {
                    BigInteger available = bound;
                    if (terms.values().stream().noneMatch(value -> value.compareTo(available) > 0)) continue;
                }
                // This row is a nonnegative combination of necessary rows. With
                // insufficient allowance for even one execution, a loss count is
                // zero. Preserve any allowance for the other counts in the row.
                var consequence = normalize(new ExactLinearProgram.Constraint(terms, bound));
                if (!distinct.add(consequence)) continue;
                source.add(consequence);
                if (bound.signum() >= 0) for (var term : terms.entrySet())
                    if (term.getValue().compareTo(bound) > 0) fixZero(term.getKey());
                budget.note("count_weighted_cover", "necessary_loss_bound=" + consequence);
            }
        }
    }

    private void fixZero(int id) {
        if (root[id] != id || sourceLower[id].signum() != 0) return;
        sourceUpper[id] = BigInteger.ZERO;
        // Every alias uses this same representative; its constant offset is
        // retained when the shared representative becomes zero.
        for (int i = 0; i < root.length; i++) if (root[i] == id) {
            charge();
            root[i] = -1;
        }
        int partner = partners[id];
        partners[id] = -1;
        if (partner >= 0) partners[partner] = -1;
        changed = true;
    }

    /** If three necessary rows sum exactly to 0 <= 0, each has zero slack. */
    private void saturateSums() {
        Map<Map<Integer, BigInteger>, ExactLinearProgram.Constraint> unique = new LinkedHashMap<>();
        for (var original : source) {
            if (work >= allowance / 2) return;
            var row = normalize(project(original));
            if (row.terms().size() < 2 || row.terms().size() > 16) continue;
            unique.merge(row.terms(), row, (a, b) -> a.upper().compareTo(b.upper()) <= 0 ? a : b);
            Map<Integer, BigInteger> negative = new LinkedHashMap<>();
            BigInteger limit = row.upper();
            for (var term : row.terms().entrySet()) {
                charge();
                if (term.getValue().signum() < 0) negative.put(term.getKey(), term.getValue());
                else limit = limit.subtract(term.getValue().multiply(sourceLower[term.getKey()]));
            }
            if (negative.size() > 1 && negative.size() < row.terms().size()) {
                var relaxed = normalize(new ExactLinearProgram.Constraint(negative, limit));
                unique.merge(relaxed.terms(), relaxed, (a, b) -> a.upper().compareTo(b.upper()) <= 0 ? a : b);
            }
        }
        if (unique.size() > 128) return;
        var needs = unique.values().stream().filter(row -> row.terms().values().stream().allMatch(v -> v.signum() < 0)).toList();
        Set<ExactLinearProgram.Constraint> distinct = new HashSet<>(source);
        int added = 0;
        for (int a = 0; a < needs.size() && work < allowance / 2; a++)
            for (int b = a + 1; b < needs.size() && work < allowance / 2; b++) {
                var first = needs.get(a);
                var second = needs.get(b);
                Map<Integer, BigInteger> sum = new LinkedHashMap<>(first.terms());
                for (var term : second.terms().entrySet()) {
                    charge();
                    sum.merge(term.getKey(), term.getValue(), BigInteger::add);
                }
                BigInteger bound = first.upper().add(second.upper()), gcd = BigInteger.ZERO;
                for (var value : sum.values()) gcd = gcd.gcd(value);
                // Integer rounding may tighten the sum without making its
                // individual slacks zero. Only an exact division is usable.
                if (bound.remainder(gcd).signum() != 0) continue;
                BigInteger divisor = gcd;
                Map<Integer, BigInteger> opposite = new LinkedHashMap<>();
                sum.forEach((id, value) -> opposite.put(id, value.divide(divisor).negate()));
                var third = unique.get(opposite);
                if (third == null || !third.upper().equals(bound.divide(gcd).negate())) continue;
                for (var row : List.of(first, second, third)) {
                    if (distinct.add(row)) {
                        source.add(row);
                        added++;
                    }
                    Map<Integer, BigInteger> reverse = new LinkedHashMap<>();
                    row.terms().forEach((id, value) -> reverse.put(id, value.negate()));
                    var exact = new ExactLinearProgram.Constraint(reverse, row.upper().negate());
                    if (distinct.add(exact)) {
                        source.add(exact);
                        added++;
                    }
                }
            }
        if (added > 0) budget.note("count_saturation", "exact_sum_rows=" + added);
    }

    /** Keep tight local material pools visible even when another pool has slack. */
    private void saturateLocalPairs() {
        if (Arrays.stream(partners).noneMatch(id -> id >= 0)) return;
        List<ExactLinearProgram.Constraint> needs = new ArrayList<>();
        for (var original : source) {
            if (work >= allowance / 2 || needs.size() >= 128) break;
            var row = project(original);
            Map<Integer, BigInteger> negative = new LinkedHashMap<>();
            BigInteger bound = row.upper();
            for (var term : row.terms().entrySet()) {
                charge();
                if (term.getValue().signum() < 0) negative.put(term.getKey(), term.getValue());
                else bound = bound.subtract(term.getValue().multiply(sourceLower[term.getKey()]));
            }
            // Replacing positive terms by proved lower bounds is a relaxation,
            // not a trial assignment. Every inferred pair must hold originally.
            if (bound.signum() < 0 && !negative.isEmpty())
                needs.add(normalize(new ExactLinearProgram.Constraint(negative, bound)));
        }
        Set<ExactLinearProgram.Constraint> distinct = new HashSet<>(source);
        for (int a = 0; a < needs.size() && work < allowance / 2; a++) {
            forcePairs(needs.get(a), distinct);
            for (int b = a + 1; b < needs.size() && work < allowance / 2; b++) {
                Map<Integer, BigInteger> terms = new LinkedHashMap<>(needs.get(a).terms());
                for (var term : needs.get(b).terms().entrySet()) {
                    charge();
                    terms.merge(term.getKey(), term.getValue(), BigInteger::add);
                }
                forcePairs(new ExactLinearProgram.Constraint(terms, needs.get(a).upper().add(needs.get(b).upper())), distinct);
            }
        }
    }

    private void forcePairs(ExactLinearProgram.Constraint row, Set<ExactLinearProgram.Constraint> distinct) {
        Map<Integer, BigInteger> minimums = new LinkedHashMap<>();
        BigInteger minimum = BigInteger.ZERO;
        for (var term : row.terms().entrySet()) {
            charge();
            int id = term.getKey(), partner = partners[id];
            if (partner >= 0) {
                int group = Math.min(id, partner);
                if (minimums.containsKey(group)) continue;
                BigInteger value = term.getValue().min(row.terms().getOrDefault(partner, BigInteger.ZERO));
                minimums.put(group, value);
                minimum = minimum.add(value);
            } else {
                if (sourceUpper[id] == null) return;
                minimum = minimum.add(term.getValue().multiply(sourceUpper[id]));
            }
        }
        BigInteger slack = row.upper().subtract(minimum);
        if (slack.signum() < 0) return; // Ordinary propagation handles contradictions.
        for (var group : minimums.entrySet()) if (group.getValue().negate().compareTo(slack) > 0) {
            int id = group.getKey();
            var equalityHalf = new ExactLinearProgram.Constraint(Map.of(id, BigInteger.ONE.negate(), partners[id], BigInteger.ONE.negate()), BigInteger.ONE.negate());
            if (distinct.add(equalityHalf)) {
                source.add(equalityHalf);
                saturatedPairs++;
            }
        }
    }

    /**
     * When a demanded cardinality exhausts a sum of resource capacities,
     * every contributing capacity is tight, including overlapping covers.
     */
    private void saturateCovers() {
        if (source.size() > 2048 || root.length > 512) return;
        List<ExactLinearProgram.Constraint> capacities = new ArrayList<>(), needs = new ArrayList<>();
        for (var sourceRow : source) {
            if (work >= allowance / 2) return;
            var row = normalize(project(sourceRow));
            if (row.terms().size() < 2) continue;
            if (row.upper().equals(BigInteger.ONE) && row.terms().values().stream().allMatch(BigInteger.ONE::equals)) capacities.add(row);
            else if (row.upper().signum() < 0 && row.terms().values().stream().allMatch(BigInteger.ONE.negate()::equals)) needs.add(row);
        }
        Set<ExactLinearProgram.Constraint> distinct = new HashSet<>(source);
        for (var need : needs) {
            if (work >= allowance / 2) return;
            Map<Integer, BigInteger> total = new HashMap<>();
            List<ExactLinearProgram.Constraint> used = new ArrayList<>();
            for (var cap : capacities) {
                charge();
                if (cap.terms().size() >= need.terms().size() || !need.terms().keySet().containsAll(cap.terms().keySet())) continue;
                used.add(cap);
                for (int id : cap.terms().keySet()) {
                    charge();
                    total.merge(id, BigInteger.ONE, BigInteger::add);
                }
            }
            if (!total.keySet().equals(need.terms().keySet())) continue;
            BigInteger factor = total.values().iterator().next();
            if (total.values().stream().anyMatch(v -> !v.equals(factor)) || !factor.multiply(need.upper().negate()).equals(BigInteger.valueOf(used.size()))) continue;
            for (var cap : used) {
                Map<Integer, BigInteger> terms = new LinkedHashMap<>();
                cap.terms().forEach((key, value) -> terms.put(key, value.negate()));
                var reverse = new ExactLinearProgram.Constraint(terms, BigInteger.ONE.negate());
                if (distinct.add(reverse)) source.add(reverse);
            }
            budget.note("count_cover", "saturated_capacities=" + used.size());
        }
    }

    private ExactLinearProgram.Constraint project(ExactLinearProgram.Constraint row) {
        Map<Integer, BigInteger> terms = new LinkedHashMap<>();
        BigInteger rhs = row.upper();
        for (var term : row.terms().entrySet()) {
            charge();
            int id = term.getKey();
            rhs = rhs.subtract(term.getValue().multiply(offset[id]));
            if (root[id] >= 0) terms.merge(root[id], term.getValue().multiply(factor[id]), BigInteger::add);
        }
        terms.values().removeIf(value -> value.signum() == 0);
        return new ExactLinearProgram.Constraint(terms, rhs);
    }

    static ExactLinearProgram.Constraint normalize(ExactLinearProgram.Constraint row) {
        if (row.terms().values().stream().anyMatch(value -> value.signum() == 0)) {
            Map<Integer, BigInteger> sparse = new LinkedHashMap<>(row.terms());
            sparse.values().removeIf(value -> value.signum() == 0);
            row = new ExactLinearProgram.Constraint(sparse, row.upper());
        }
        BigInteger gcd = BigInteger.ZERO;
        for (BigInteger value : row.terms().values()) gcd = gcd.gcd(value);
        if (gcd.compareTo(BigInteger.ONE) <= 0) return row;
        Map<Integer, BigInteger> terms = new LinkedHashMap<>();
        BigInteger divisor = gcd;
        row.terms().forEach((key, value) -> terms.put(key, value.divide(divisor)));
        BigInteger[] divided = row.upper().divideAndRemainder(divisor);
        return new ExactLinearProgram.Constraint(terms, divided[1].signum() < 0 ? divided[0].subtract(BigInteger.ONE) : divided[0]);
    }

    private void finish() {
        representatives = Arrays.stream(root).filter(id -> id >= 0).distinct().sorted().toArray();
        Map<Integer, Integer> ids = new HashMap<>();
        for (int i = 0; i < representatives.length; i++) ids.put(representatives[i], i);
        rows = new ArrayList<>();
        for (var row : known.values()) {
            Map<Integer, BigInteger> terms = new LinkedHashMap<>();
            row.terms().forEach((key, value) -> terms.put(ids.get(key), value));
            rows.add(new ExactLinearProgram.Constraint(terms, row.upper()));
        }
        lower = new BigInteger[representatives.length];
        upper = new BigInteger[representatives.length];
        Arrays.fill(lower, BigInteger.ZERO);
        for (int i = 0; i < root.length; i++) if (root[i] >= 0) {
            int id = ids.get(root[i]);
            boolean positive = factor[i].signum() > 0;
            BigInteger magnitude = factor[i].abs();
            BigInteger low = positive ? ceil(sourceLower[i].subtract(offset[i]), magnitude) :
                    sourceUpper[i] == null ? null : ceil(offset[i].subtract(sourceUpper[i]), magnitude);
            BigInteger high = positive ? sourceUpper[i] == null ? null : floor(sourceUpper[i].subtract(offset[i]), magnitude) :
                    floor(offset[i].subtract(sourceLower[i]), magnitude);
            if (low != null) lower[id] = lower[id].max(low);
            if (high != null) upper[id] = upper[id] == null ? high : upper[id].min(high);
        }
        compiled = true;
        budget.note("count_compile", "variables=" + root.length + "->" + representatives.length +
                "; rows=" + source.size() + "->" + rows.size() + "; equalities=" + proof.size() + "; saturated_pairs=" + saturatedPairs);
    }

    private void identity() {
        known.clear();
        proof.clear();
        for (int i = 0; i < root.length; i++) {
            root[i] = i;
            factor[i] = BigInteger.ONE;
            offset[i] = BigInteger.ZERO;
        }
        representatives = root.clone();
        rows = List.copyOf(source);
        lower = sourceLower.clone();
        upper = sourceUpper.clone();
        complete = true;
    }

    BigInteger[] expand(BigInteger[] values) {
        if (values == null) return null;
        BigInteger[] result = offset.clone();
        for (int i = 0; i < result.length; i++) if (root[i] >= 0)
            result[i] = result[i].add(values[Arrays.binarySearch(representatives, root[i])].multiply(factor[i]));
        return result;
    }

    ExactRational[] expand(ExactRational[] values) {
        if (values == null) return null;
        ExactRational[] result = new ExactRational[root.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = ExactRational.of(offset[i]);
            if (root[i] >= 0) result[i] = result[i].add(values[Arrays.binarySearch(representatives, root[i])].multiply(ExactRational.of(factor[i])));
        }
        return result;
    }

    BigInteger[] objective(BigInteger[] original) {
        BigInteger[] result = new BigInteger[representatives.length];
        Arrays.fill(result, BigInteger.ZERO);
        for (int i = 0; i < root.length; i++) if (root[i] >= 0) {
            int id = Arrays.binarySearch(representatives, root[i]);
            result[id] = result[id].add(original[i].multiply(factor[i]));
        }
        return result;
    }

    /** Exact affine interval lower bound; correlations can only make it weaker. */
    BigInteger minimum(BigInteger[] coefficients) {
        Map<Integer, BigInteger> sparse = new LinkedHashMap<>();
        for (int i = 0; i < coefficients.length; i++) if (coefficients[i].signum() != 0) sparse.put(i, coefficients[i]);
        return minimum(sparse);
    }

    BigInteger minimum(Map<Integer, BigInteger> coefficients) {
        BigInteger result = BigInteger.ZERO;
        Map<Integer, BigInteger> projected = new HashMap<>();
        for (var term : coefficients.entrySet()) {
            charge();
            int i = term.getKey();
            result = result.add(term.getValue().multiply(offset[i]));
            if (root[i] >= 0) projected.merge(Arrays.binarySearch(representatives, root[i]), term.getValue().multiply(factor[i]), BigInteger::add);
        }
        for (var term : projected.entrySet()) if (term.getValue().signum() != 0) {
            int i = term.getKey();
            BigInteger value = term.getValue().signum() > 0 ? lower[i] : upper[i];
            if (value == null) return null;
            result = result.add(term.getValue().multiply(value));
        }
        return result;
    }

    boolean sameCoordinates(CountReduction other) {
        return other != null && Arrays.equals(root, other.root) && Arrays.equals(factor, other.factor) && Arrays.equals(offset, other.offset);
    }

    record Coordinates(List<Integer> roots, List<BigInteger> factors, List<BigInteger> offsets) {}

    Coordinates coordinates() {
        return new Coordinates(Arrays.stream(root).boxed().toList(), List.of(factor), List.of(offset));
    }

    private static BigInteger floor(BigInteger numerator, BigInteger denominator) {
        BigInteger[] qr = numerator.divideAndRemainder(denominator);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }

    private static BigInteger ceil(BigInteger numerator, BigInteger denominator) {
        return floor(numerator.negate(), denominator).negate();
    }

    List<ExactLinearProgram.Constraint> rows() {
        return rows;
    }

    BigInteger[] lower() {
        return lower.clone();
    }

    BigInteger[] upper() {
        return upper.clone();
    }

    List<Equality> proof() {
        return List.copyOf(proof);
    }

    int variables() {
        return representatives.length;
    }

    int[] representatives() {
        return representatives.clone();
    }

    private void charge() {
        budget.check();
        work++;
    }

    @Override
    public void close() {
        if (hall != null) hall.close();
        hall = null;
        if (hermite != null) hermite.close();
        hermite = null;
        if (finalBounds != null) finalBounds.close();
        finalBounds = null;
        if (implications != null) implications.close();
        implications = null;
        budget.release(memory);
        memory = 0;
    }
}
