package org.cgse.core;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

/** Portable scoped integer certificates and an independent arithmetic checker. */
public final class CountProof {

    private CountProof() {}

    public record Row(Map<Integer, BigInteger> terms, BigInteger upper) {

        public Row {
            terms = Collections.unmodifiableMap(new TreeMap<>(terms));
        }
    }

    public record Fraction(BigInteger numerator, BigInteger denominator) {

        public Fraction {
            if (denominator.signum() <= 0) throw new IllegalArgumentException("Nonpositive denominator");
        }

        Fraction add(Fraction other) {
            BigInteger n = numerator.multiply(other.denominator).add(other.numerator.multiply(denominator));
            BigInteger d = denominator.multiply(other.denominator), gcd = n.gcd(d);
            return new Fraction(n.divide(gcd), d.divide(gcd));
        }

        Fraction multiply(BigInteger value) {
            return new Fraction(numerator.multiply(value), denominator);
        }
    }

    /** Axioms describe the exact scope, including branch/domain assumptions, never an implicit whole-order claim. */
    public record Certificate(String scope, int variables, List<Row> axioms, List<List<Row>> forbidden,
                              List<Fraction> farkas, boolean closed, List<Combination> derived) {

        public Certificate(String scope, int variables, List<Row> axioms, List<List<Row>> forbidden,
                           List<Fraction> farkas, boolean closed) {
            this(scope, variables, axioms, forbidden, farkas, closed, List.of());
        }

        public Certificate {
            axioms = List.copyOf(axioms);
            forbidden = forbidden.stream().map(List::copyOf).toList();
            farkas = List.copyOf(farkas);
            derived = List.copyOf(derived);
        }
    }

    public enum Verdict {
        VERIFIED,
        INVALID,
        INCOMPLETE
    }

    /** Parents index axioms followed by earlier consequences; no forward references. */
    public record Combination(Map<Integer, BigInteger> parents, BigInteger divisor, Row consequence) {

        public Combination {
            parents = Collections.unmodifiableMap(new TreeMap<>(parents));
        }
    }

    public record Derivation(String scope, int variables, List<Row> axioms, List<Combination> steps) {

        public Derivation {
            axioms = List.copyOf(axioms);
            steps = List.copyOf(steps);
        }
    }

    /** A lifted cover, checked by an independent profit-indexed knapsack DP. */
    public record Knapsack(String scope, int variables, Row source, List<BigInteger> lower, List<BigInteger> upper, Row consequence) {

        public Knapsack {
            lower = List.copyOf(lower);
            upper = Collections.unmodifiableList(new ArrayList<>(upper));
        }
    }

    /** Frontier overapproximations for a completely closed integer decision diagram. */
    public record Diagram(String scope, int variables, List<Row> axioms, List<BigInteger> lower, List<BigInteger> upper,
                          List<Integer> order, List<List<List<BigInteger>>> layers) {

        public Diagram {
            axioms = List.copyOf(axioms);
            lower = List.copyOf(lower);
            upper = List.copyOf(upper);
            order = List.copyOf(order);
            layers = layers.stream().map(layer -> layer.stream().map(List::copyOf).toList()).toList();
        }
    }

    /** Boolean lex leaders preserve at least the least assignment in every orbit. */
    public record Symmetry(String scope, int variables, List<Row> axioms, List<List<Integer>> permutations, List<Row> leaders) {

        public Symmetry {
            axioms = List.copyOf(axioms);
            permutations = permutations.stream().map(List::copyOf).toList();
            leaders = List.copyOf(leaders);
        }
    }

    public static Verdict verify(Symmetry proof, long maximumWork) {
        int n = proof.variables;
        if (n < 1 || n > 192 || proof.axioms.size() > 1024 || proof.permutations.size() > 16 ||
                proof.permutations.size() != proof.leaders.size() || maximumWork <= 0)
            return Verdict.INVALID;
        long[] work = { maximumWork };
        try {
            for (Row row : proof.axioms) if (!valid(row, n)) return Verdict.INVALID;
            Set<Row> original = new HashSet<>(proof.axioms);
            for (int k = 0; k < proof.permutations.size(); k++) {
                var permutation = proof.permutations.get(k);
                if (permutation.size() != n || new HashSet<>(permutation).size() != n ||
                        permutation.stream().anyMatch(i -> i < 0 || i >= n))
                    return Verdict.INVALID;
                for (Row row : proof.axioms) {
                    Map<Integer, BigInteger> transformed = new TreeMap<>();
                    for (var e : row.terms.entrySet()) {
                        tick(work);
                        transformed.put(permutation.get(e.getKey()), e.getValue());
                    }
                    if (!original.contains(new Row(transformed, row.upper))) return Verdict.INVALID;
                }
                Map<Integer, BigInteger> terms = new TreeMap<>();
                BigInteger weight = BigInteger.ONE;
                for (int i = n - 1; i >= 0; i--) {
                    tick(work);
                    terms.merge(i, weight, BigInteger::add);
                    terms.merge(permutation.get(i), weight.negate(), BigInteger::add);
                    weight = weight.shiftLeft(1);
                }
                terms.values().removeIf(v -> v.signum() == 0);
                if (!proof.leaders.get(k).equals(new Row(terms, BigInteger.ZERO))) return Verdict.INVALID;
            }
            return Verdict.VERIFIED;
        } catch (CheckLimit limit) {
            return Verdict.INCOMPLETE;
        }
    }

    public static Verdict verify(Diagram proof, long maximumWork) {
        int n = proof.variables, m = proof.axioms.size();
        if (n < 0 || n > 128 || m > 512 || maximumWork <= 0 || proof.lower.size() != n || proof.upper.size() != n ||
                proof.order.size() != n || new HashSet<>(proof.order).size() != n || proof.layers.isEmpty() || proof.layers.size() > n + 1 ||
                proof.layers.get(0).size() != 1)
            return Verdict.INVALID;
        long[] work = { maximumWork };
        try {
            for (Row row : proof.axioms) if (!valid(row, n)) return Verdict.INVALID;
            for (var layer : proof.layers) for (var state : layer) if (state.size() != m || state.stream().anyMatch(Objects::isNull)) return Verdict.INVALID;
            int[] width = new int[n];
            for (int id = 0; id < n; id++) {
                tick(work);
                if (proof.order.get(id) < 0 || proof.order.get(id) >= n) return Verdict.INVALID;
                BigInteger span = proof.upper.get(id).subtract(proof.lower.get(id));
                if (span.signum() < 0 || span.compareTo(BigInteger.valueOf(64)) > 0) return Verdict.INVALID;
                width[id] = span.intValueExact();
            }
            BigInteger[][] suffixMin = new BigInteger[n + 1][m];
            Arrays.fill(suffixMin[n], BigInteger.ZERO);
            for (int k = n - 1; k >= 0; k--) for (int r = 0; r < m; r++) {
                tick(work);
                int id = proof.order.get(k);
                BigInteger extent = proof.axioms.get(r).terms.getOrDefault(id, BigInteger.ZERO).multiply(BigInteger.valueOf(width[id]));
                suffixMin[k][r] = suffixMin[k + 1][r].add(extent.min(BigInteger.ZERO));
            }
            for (int r = 0; r < m; r++) {
                Row row = proof.axioms.get(r);
                BigInteger residual = row.upper;
                for (var term : row.terms.entrySet()) {
                    tick(work);
                    residual = residual.subtract(term.getValue().multiply(proof.lower.get(term.getKey())));
                }
                if (!residual.equals(proof.layers.get(0).get(0).get(r))) return Verdict.INVALID;
            }
            for (int k = 0; k + 1 < proof.layers.size(); k++) {
                int id = proof.order.get(k);
                for (var state : proof.layers.get(k)) for (int value = 0; value <= width[id]; value++) {
                    List<BigInteger> successor = new ArrayList<>();
                    boolean blocked = false;
                    for (int r = 0; r < m; r++) {
                        tick(work);
                        BigInteger coefficient = proof.axioms.get(r).terms.getOrDefault(id, BigInteger.ZERO);
                        BigInteger capacity = state.get(r).subtract(coefficient.multiply(BigInteger.valueOf(value)));
                        if (capacity.compareTo(suffixMin[k + 1][r]) < 0) {
                            blocked = true;
                            break;
                        }
                        // Values beyond the maximum suffix activity impose no
                        // restriction. Recompute it here, independently of the
                        // solver's canonical-state construction.
                        BigInteger maximum = BigInteger.ZERO;
                        for (int j = k + 1; j < n; j++) {
                            tick(work);
                            int other = proof.order.get(j);
                            BigInteger extent = proof.axioms.get(r).terms.getOrDefault(other, BigInteger.ZERO).multiply(BigInteger.valueOf(width[other]));
                            maximum = maximum.add(extent.max(BigInteger.ZERO));
                        }
                        successor.add(capacity.min(maximum));
                    }
                    if (blocked) continue;
                    boolean covered = false;
                    for (var next : proof.layers.get(k + 1)) {
                        boolean dominates = true;
                        for (int r = 0; r < m; r++) {
                            tick(work);
                            if (next.get(r).compareTo(successor.get(r)) < 0) {
                                dominates = false;
                                break;
                            }
                        }
                        if (dominates) {
                            covered = true;
                            break;
                        }
                    }
                    if (!covered) return Verdict.INVALID;
                }
            }
            var last = proof.layers.get(proof.layers.size() - 1);
            return last.isEmpty() || proof.layers.size() == n + 1 && last.stream().allMatch(state -> state.stream().anyMatch(v -> v.signum() < 0)) ?
                    Verdict.VERIFIED : Verdict.INVALID;
        } catch (CheckLimit limit) {
            return Verdict.INCOMPLETE;
        }
    }

    public static Verdict verify(Knapsack proof, long maximumWork) {
        if (proof.variables < 0 || proof.variables > 16384 || maximumWork <= 0 ||
                proof.lower.size() != proof.variables || proof.upper.size() != proof.variables ||
                !valid(proof.source, proof.variables) || !valid(proof.consequence, proof.variables))
            return Verdict.INVALID;
        long[] work = { maximumWork };
        try {
            BigInteger capacity = proof.source.upper, rhs = proof.consequence.upper;
            for (var term : proof.source.terms.entrySet()) {
                tick(work);
                int id = term.getKey();
                BigInteger low = proof.lower.get(id), high = proof.upper.get(id);
                if (low == null || high != null && high.compareTo(low) < 0) return Verdict.INVALID;
                BigInteger endpoint = term.getValue().signum() >= 0 ? low : high;
                if (endpoint == null) return Verdict.INVALID;
                capacity = capacity.subtract(term.getValue().multiply(endpoint));
            }
            if (capacity.signum() < 0) return Verdict.VERIFIED;
            List<BigInteger> weights = new ArrayList<>();
            List<Integer> profits = new ArrayList<>();
            int total = 0;
            for (var term : proof.consequence.terms.entrySet()) {
                tick(work);
                int id = term.getKey();
                BigInteger weight = proof.source.terms.get(id);
                if (weight == null || weight.signum() != term.getValue().signum() || proof.upper.get(id) == null ||
                        !proof.upper.get(id).subtract(proof.lower.get(id)).equals(BigInteger.ONE))
                    return Verdict.INVALID;
                BigInteger profit = term.getValue().abs();
                if (profit.bitLength() > 17 || total + profit.intValue() > 131072) return Verdict.INCOMPLETE;
                total += profit.intValue();
                profits.add(profit.intValue());
                weights.add(weight.abs());
                rhs = rhs.subtract(term.getValue().multiply(weight.signum() > 0 ? proof.lower.get(id) : proof.upper.get(id)));
            }
            BigInteger[] minimum = new BigInteger[total + 1];
            minimum[0] = BigInteger.ZERO;
            int used = 0;
            for (int i = 0; i < profits.size(); i++) {
                int profit = profits.get(i);
                for (int p = used; p >= 0; p--) {
                    tick(work);
                    if (minimum[p] == null) continue;
                    BigInteger value = minimum[p].add(weights.get(i));
                    if (minimum[p + profit] == null || value.compareTo(minimum[p + profit]) < 0) minimum[p + profit] = value;
                }
                used += profit;
            }
            for (int p = total; p >= 0; p--) {
                tick(work);
                if (minimum[p] != null && minimum[p].compareTo(capacity) <= 0)
                    return BigInteger.valueOf(p).compareTo(rhs) <= 0 ? Verdict.VERIFIED : Verdict.INVALID;
            }
            return Verdict.INVALID;
        } catch (CheckLimit limit) {
            return Verdict.INCOMPLETE;
        }
    }

    /** Exact nonnegative elimination and integral division, checked without the search kernel. */
    public static Verdict verify(Derivation proof, long maximumWork) {
        return verify(proof, maximumWork, ignored -> {});
    }

    static Verdict verify(Derivation proof, long maximumWork, java.util.function.LongConsumer charged) {
        if (proof.variables < 0 || proof.variables > 16384 || maximumWork <= 0) return Verdict.INVALID;
        long[] work = { maximumWork };
        try {
            List<Row> available = new ArrayList<>(proof.axioms);
            for (Row row : available) if (!valid(row, proof.variables)) return Verdict.INVALID;
            return derive(proof.variables, available, proof.steps, work) ? Verdict.VERIFIED : Verdict.INVALID;
        } catch (CheckLimit limit) {
            return Verdict.INCOMPLETE;
        } finally {
            charged.accept(maximumWork - work[0]);
        }
    }

    private static boolean derive(int variables, List<Row> available, List<Combination> steps, long[] work) {
        for (Combination step : steps) {
            tick(work);
            if (step.divisor == null || step.divisor.signum() <= 0 || !valid(step.consequence, variables)) return false;
            Map<Integer, BigInteger> sum = new TreeMap<>();
            BigInteger bound = BigInteger.ZERO;
            for (var parent : step.parents.entrySet()) {
                tick(work);
                if (parent.getKey() < 0 || parent.getKey() >= available.size() || parent.getValue() == null || parent.getValue().signum() < 0) return false;
                Row row = available.get(parent.getKey());
                bound = bound.add(parent.getValue().multiply(row.upper));
                for (var term : row.terms.entrySet()) {
                    tick(work);
                    sum.merge(term.getKey(), parent.getValue().multiply(term.getValue()), BigInteger::add);
                }
            }
            Map<Integer, BigInteger> divided = new TreeMap<>();
            for (var term : sum.entrySet()) {
                tick(work);
                var qr = term.getValue().divideAndRemainder(step.divisor);
                if (qr[1].signum() != 0) return false;
                if (qr[0].signum() != 0) divided.put(term.getKey(), qr[0]);
            }
            if (!new Row(divided, floor(bound, step.divisor)).equals(step.consequence)) return false;
            available.add(step.consequence);
        }
        return true;
    }

    /** Signed combinations require both directions of every used equality. */
    public record Divisibility(String scope, int variables, List<Row> axioms, List<BigInteger> multipliers) {

        public Divisibility {
            axioms = List.copyOf(axioms);
            multipliers = List.copyOf(multipliers);
        }
    }

    public enum RoundingKind {
        FLOOR,
        MIR,
        TABLEAU
    }

    /** Scoped rounding or an integer-tableau disjunction after an explicit lower-bound shift. */
    public record Rounding(String scope, int variables, List<Row> axioms, List<BigInteger> multipliers,
                           BigInteger divisor, List<BigInteger> lower, Row consequence, RoundingKind kind) {

        public Rounding(String scope, int variables, List<Row> axioms, List<BigInteger> multipliers,
                        BigInteger divisor, List<BigInteger> lower, Row consequence) {
            this(scope, variables, axioms, multipliers, divisor, lower, consequence, RoundingKind.FLOOR);
        }

        public Rounding(String scope, int variables, List<Row> axioms, List<BigInteger> multipliers,
                        BigInteger divisor, List<BigInteger> lower, Row consequence, boolean mixedInteger) {
            this(scope, variables, axioms, multipliers, divisor, lower, consequence, mixedInteger ? RoundingKind.MIR : RoundingKind.FLOOR);
        }

        public boolean mixedInteger() {
            return kind == RoundingKind.MIR;
        }

        public Rounding {
            axioms = List.copyOf(axioms);
            multipliers = List.copyOf(multipliers);
            lower = List.copyOf(lower);
        }
    }

    /** Each pair of distinct binary literals has an explicit conflicting row. */
    public record Clique(String scope, int variables, List<Row> axioms, List<BigInteger> lower,
                         List<BigInteger> upper, List<Integer> literals, List<Integer> witnesses, Row consequence) {

        public Clique {
            axioms = List.copyOf(axioms);
            lower = List.copyOf(lower);
            upper = Collections.unmodifiableList(new ArrayList<>(upper));
            literals = List.copyOf(literals);
            witnesses = List.copyOf(witnesses);
        }
    }

    public static Verdict verify(Clique proof, long maximumWork) {
        if (proof.variables < 0 || proof.variables > 16384 || maximumWork <= 0 ||
                proof.lower.size() != proof.variables || proof.upper.size() != proof.variables ||
                proof.literals.size() < 2 || proof.literals.size() > 256 ||
                proof.witnesses.size() != proof.literals.size() * (proof.literals.size() - 1) / 2)
            return Verdict.INVALID;
        long[] work = { maximumWork };
        try {
            Set<Row> known = new HashSet<>(proof.axioms);
            for (Row row : proof.axioms) if (!valid(row, proof.variables)) return Verdict.INVALID;
            for (int id = 0; id < proof.variables; id++) {
                tick(work);
                var low = proof.lower.get(id);
                var high = proof.upper.get(id);
                if (low == null || !known.contains(new Row(Map.of(id, BigInteger.ONE.negate()), low.negate())) ||
                        high != null && (!known.contains(new Row(Map.of(id, BigInteger.ONE), high)) || high.compareTo(low) < 0))
                    return Verdict.INVALID;
            }
            Map<Integer, BigInteger> terms = new TreeMap<>();
            BigInteger limit = BigInteger.ONE;
            for (int literal : proof.literals) {
                tick(work);
                int id = literal / 2;
                if (literal < 0 || id >= proof.variables || terms.containsKey(id) || proof.upper.get(id) == null ||
                        !proof.upper.get(id).subtract(proof.lower.get(id)).equals(BigInteger.ONE))
                    return Verdict.INVALID;
                terms.put(id, (literal & 1) == 1 ? BigInteger.ONE : BigInteger.ONE.negate());
                limit = (literal & 1) == 1 ? limit.add(proof.lower.get(id)) : limit.subtract(proof.upper.get(id));
            }
            if (!new Row(terms, limit).equals(proof.consequence)) return Verdict.INVALID;
            int cursor = 0;
            for (int i = 0; i < proof.literals.size(); i++) for (int j = i + 1; j < proof.literals.size(); j++) {
                tick(work);
                int index = proof.witnesses.get(cursor++);
                if (index < 0 || index >= proof.axioms.size()) return Verdict.INVALID;
                Row row = proof.axioms.get(index);
                BigInteger minimum = BigInteger.ZERO;
                int a = proof.literals.get(i), b = proof.literals.get(j);
                for (var term : row.terms.entrySet()) {
                    tick(work);
                    int id = term.getKey();
                    BigInteger endpoint = term.getValue().signum() >= 0 ? proof.lower.get(id) : proof.upper.get(id);
                    if (id == a / 2) endpoint = (a & 1) == 1 ? proof.upper.get(id) : proof.lower.get(id);
                    if (id == b / 2) endpoint = (b & 1) == 1 ? proof.upper.get(id) : proof.lower.get(id);
                    if (endpoint == null) return Verdict.INVALID;
                    minimum = minimum.add(term.getValue().multiply(endpoint));
                }
                if (minimum.compareTo(row.upper) <= 0) return Verdict.INVALID;
            }
            return Verdict.VERIFIED;
        } catch (CheckLimit limit) {
            return Verdict.INCOMPLETE;
        }
    }

    public static Verdict verify(Rounding proof, long maximumWork) {
        if (proof.variables < 0 || proof.variables > 16384 || maximumWork <= 0 ||
                proof.axioms.size() != proof.multipliers.size() || proof.lower.size() != proof.variables ||
                proof.divisor == null || proof.divisor.signum() <= 0 || proof.kind == null || !valid(proof.consequence, proof.variables))
            return Verdict.INVALID;
        long[] work = { maximumWork };
        try {
            Set<Row> known = new HashSet<>(proof.axioms);
            Map<Integer, BigInteger> sum = new TreeMap<>();
            BigInteger constant = BigInteger.ZERO;
            for (int i = 0; i < proof.axioms.size(); i++) {
                tick(work);
                var row = proof.axioms.get(i);
                var weight = proof.multipliers.get(i);
                if (!valid(row, proof.variables) || weight == null || weight.signum() < 0 && proof.kind != RoundingKind.TABLEAU) return Verdict.INVALID;
                if (weight.signum() == 0) continue;
                constant = constant.add(row.upper.multiply(weight));
                for (var term : row.terms.entrySet()) {
                    tick(work);
                    sum.merge(term.getKey(), term.getValue().multiply(weight), BigInteger::add);
                }
            }
            for (int id = 0; id < proof.variables; id++) {
                tick(work);
                BigInteger low = proof.lower.get(id);
                if (low == null || !known.contains(new Row(Map.of(id, BigInteger.ONE.negate()), low.negate()))) return Verdict.INVALID;
                constant = constant.subtract(sum.getOrDefault(id, BigInteger.ZERO).multiply(low));
            }
            BigInteger remainder = constant.mod(proof.divisor);
            if (proof.kind != RoundingKind.FLOOR && remainder.signum() == 0) return Verdict.INVALID;
            if (proof.kind == RoundingKind.TABLEAU) return verifyTableauRounding(proof, sum, remainder, work);
            BigInteger factor = proof.mixedInteger() ? proof.divisor.subtract(remainder) : BigInteger.ONE;
            BigInteger bound = floor(constant, proof.divisor).multiply(factor);
            Map<Integer, BigInteger> rounded = new TreeMap<>();
            for (var term : sum.entrySet()) {
                tick(work);
                BigInteger value = floor(term.getValue(), proof.divisor).multiply(factor);
                if (proof.mixedInteger()) value = value.add(term.getValue().mod(proof.divisor).subtract(remainder).max(BigInteger.ZERO));
                if (value.signum() != 0) rounded.put(term.getKey(), value);
                bound = bound.add(value.multiply(proof.lower.get(term.getKey())));
            }
            return new Row(rounded, bound).equals(proof.consequence) ? Verdict.VERIFIED : Verdict.INVALID;
        } catch (CheckLimit limit) {
            return Verdict.INCOMPLETE;
        }
    }

    private static Verdict verifyTableauRounding(Rounding proof, Map<Integer, BigInteger> sum, BigInteger remainder, long[] work) {
        // Reconstruct the disjunction independently of the separator. Integral
        // counts and integral row slacks have nonnegative shifted coordinates.
        BigInteger d = proof.divisor, complement = d.subtract(remainder);
        BigInteger bound = remainder.multiply(complement).negate();
        Map<Integer, BigInteger> result = new TreeMap<>();
        for (var term : sum.entrySet()) {
            tick(work);
            BigInteger residue = term.getValue().mod(d);
            BigInteger value = residue.compareTo(remainder) <= 0 ? residue.multiply(complement) : remainder.multiply(d.subtract(residue));
            result.put(term.getKey(), value.negate());
        }
        for (int i = 0; i < proof.axioms.size(); i++) {
            tick(work);
            BigInteger residue = proof.multipliers.get(i).mod(d);
            BigInteger weight = residue.compareTo(remainder) <= 0 ? residue.multiply(complement) : remainder.multiply(d.subtract(residue));
            if (weight.signum() == 0) continue;
            Row row = proof.axioms.get(i);
            BigInteger rhs = row.upper;
            for (var term : row.terms.entrySet()) {
                tick(work);
                rhs = rhs.subtract(term.getValue().multiply(proof.lower.get(term.getKey())));
                result.merge(term.getKey(), term.getValue().multiply(weight), BigInteger::add);
            }
            bound = bound.add(weight.multiply(rhs));
        }
        result.values().removeIf(value -> value.signum() == 0);
        for (var term : result.entrySet()) {
            tick(work);
            bound = bound.add(term.getValue().multiply(proof.lower.get(term.getKey())));
        }
        return new Row(result, bound).equals(proof.consequence) ? Verdict.VERIFIED : Verdict.INVALID;
    }

    public static Verdict verify(Divisibility proof, long maximumWork) {
        if (proof.variables < 0 || proof.variables > 16384 || maximumWork <= 0 ||
                proof.axioms.size() != proof.multipliers.size())
            return Verdict.INVALID;
        long[] work = { maximumWork };
        try {
            Set<Row> known = new HashSet<>(proof.axioms);
            Map<Integer, BigInteger> sum = new HashMap<>();
            BigInteger constant = BigInteger.ZERO;
            for (int i = 0; i < proof.axioms.size(); i++) {
                tick(work);
                var row = proof.axioms.get(i);
                var weight = proof.multipliers.get(i);
                if (!valid(row, proof.variables) || weight == null) return Verdict.INVALID;
                if (weight.signum() == 0) continue;
                Map<Integer, BigInteger> opposite = new HashMap<>();
                for (var term : row.terms.entrySet()) {
                    tick(work);
                    opposite.put(term.getKey(), term.getValue().negate());
                    sum.merge(term.getKey(), term.getValue().multiply(weight), BigInteger::add);
                }
                if (!known.contains(new Row(opposite, row.upper.negate()))) return Verdict.INVALID;
                constant = constant.add(row.upper.multiply(weight));
            }
            BigInteger gcd = BigInteger.ZERO;
            for (var value : sum.values()) {
                tick(work);
                gcd = gcd.gcd(value);
            }
            return (gcd.signum() == 0 ? constant.signum() != 0 : constant.remainder(gcd).signum() != 0) ?
                    Verdict.VERIFIED : Verdict.INVALID;
        } catch (CheckLimit limit) {
            return Verdict.INCOMPLETE;
        }
    }

    /** Opt-in diagnostic archive. Truncation is explicit and cannot be confused with a complete proof. */
    public static final class Journal {

        private final long maximumBytes;
        private long bytes;
        private boolean truncated;
        private final List<Certificate> entries = new ArrayList<>();
        private final List<ExecutionProof.Certificate> executions = new ArrayList<>();
        private final List<Divisibility> divisibility = new ArrayList<>();
        private final List<Rounding> rounding = new ArrayList<>();
        private final List<Clique> cliques = new ArrayList<>();
        private final List<Derivation> derivations = new ArrayList<>();
        private final List<Knapsack> knapsacks = new ArrayList<>();
        private final List<Diagram> diagrams = new ArrayList<>();
        private final List<Symmetry> symmetries = new ArrayList<>();
        private final List<CountInduction.Proof> inductions = new ArrayList<>();
        private final List<CountAffineProof.Certificate> affine = new ArrayList<>();
        private final List<CountAffineConflictProof.Certificate> affineConflicts = new ArrayList<>();

        public Journal(long maximumBytes) {
            if (maximumBytes <= 0) throw new IllegalArgumentException("Nonpositive proof archive size");
            this.maximumBytes = maximumBytes;
        }

        public synchronized void add(Certificate certificate) {
            if (!retain(ArchiveSize.certificate(certificate))) return;
            entries.add(certificate);
        }

        public synchronized List<Certificate> entries() {
            return List.copyOf(entries);
        }

        public synchronized List<ExecutionProof.Certificate> executions() {
            return List.copyOf(executions);
        }

        public synchronized List<Divisibility> divisibility() {
            return List.copyOf(divisibility);
        }

        public synchronized List<Rounding> rounding() {
            return List.copyOf(rounding);
        }

        public synchronized List<Clique> cliques() {
            return List.copyOf(cliques);
        }

        public synchronized List<Derivation> derivations() {
            return List.copyOf(derivations);
        }

        public synchronized List<CountAffineProof.Certificate> affine() {
            return List.copyOf(affine);
        }

        public synchronized List<CountAffineConflictProof.Certificate> affineConflicts() {
            return List.copyOf(affineConflicts);
        }

        synchronized boolean add(CountAffineConflictProof.Certificate proof) {
            if (!retain(CountAffineConflictProof.bytes(proof))) return false;
            affineConflicts.add(proof);
            return true;
        }

        synchronized boolean add(CountAffineProof.Certificate proof) {
            if (!retain(CountAffineProof.bytes(proof))) return false;
            affine.add(proof);
            return true;
        }

        public synchronized List<Knapsack> knapsacks() {
            return List.copyOf(knapsacks);
        }

        public synchronized List<Diagram> diagrams() {
            return List.copyOf(diagrams);
        }

        public synchronized List<Symmetry> symmetries() {
            return List.copyOf(symmetries);
        }

        synchronized List<CountInduction.Proof> inductions() {
            return List.copyOf(inductions);
        }

        synchronized void add(CountInduction.Proof proof) {
            if (!retain(ArchiveSize.induction(proof))) return;
            inductions.add(proof);
        }

        public synchronized void add(Symmetry proof) {
            if (!retain(ArchiveSize.symmetry(proof))) return;
            symmetries.add(proof);
        }

        public synchronized void add(Diagram proof) {
            if (!retain(ArchiveSize.diagram(proof))) return;
            diagrams.add(proof);
        }

        public synchronized void add(Knapsack proof) {
            if (!retain(ArchiveSize.knapsack(proof))) return;
            knapsacks.add(proof);
        }

        public synchronized void add(Derivation proof) {
            if (!retain(ArchiveSize.derivation(proof))) return;
            derivations.add(proof);
        }

        public synchronized void add(Clique proof) {
            if (!retain(ArchiveSize.clique(proof))) return;
            cliques.add(proof);
        }

        public synchronized void add(Rounding proof) {
            if (!retain(ArchiveSize.rounding(proof))) return;
            rounding.add(proof);
        }

        public synchronized void add(Divisibility proof) {
            if (!retain(ArchiveSize.divisibility(proof))) return;
            divisibility.add(proof);
        }

        private boolean retain(long size) {
            // Include the archive's growing entry array as well as its payload.
            size = ArchiveSize.add(size, 32);
            if (size == Long.MAX_VALUE || size > maximumBytes - bytes) {
                truncated = true;
                return false;
            }
            bytes += size;
            return true;
        }

        public synchronized boolean truncated() {
            return truncated;
        }

        synchronized void markIncomplete() {
            truncated = true;
        }

        public synchronized void add(ExecutionProof.Certificate proof) {
            if (!retain(ArchiveSize.execution(proof))) return;
            executions.add(proof);
        }

        public synchronized void write(Path path) throws IOException {
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
                int version = !symmetries.isEmpty() ? 0x43475039 : !diagrams.isEmpty() ? 0x43475038 : !knapsacks.isEmpty() ? 0x43475037 : !derivations.isEmpty() ? 0x43475036 : !cliques.isEmpty() ? 0x43475035 : rounding.isEmpty() ? 0x43475033 : 0x43475034;
                if (!inductions.isEmpty()) version = 0x43475041;
                if (rounding.stream().anyMatch(proof -> proof.kind != RoundingKind.FLOOR)) version = 0x43475042;
                if (entries.stream().anyMatch(proof -> !proof.derived.isEmpty()) ||
                        inductions.stream().anyMatch(proof -> !proof.base().derived.isEmpty() || !proof.induction().derived.isEmpty()))
                    version = 0x43475043;
                if (!affine.isEmpty()) version = 0x43475044;
                if (!affineConflicts.isEmpty()) version = 0x43475045;
                output.writeInt(version);
                output.writeBoolean(truncated);
                output.writeInt(entries.size());
                for (var proof : entries) certificate(output, proof, version);
                output.writeInt(executions.size());
                for (var proof : executions) {
                    output.writeUTF(proof.scope());
                    output.writeInt(proof.kind().ordinal());
                    vectors(output, List.of(proof.initial(), proof.goal()));
                    vectors(output, proof.inputs());
                    vectors(output, proof.outputs());
                    vectors(output, proof.states());
                    output.writeInt(proof.marked().size());
                    for (int id : new TreeSet<>(proof.marked())) output.writeInt(id);
                }
                output.writeInt(divisibility.size());
                for (var proof : divisibility) {
                    output.writeUTF(proof.scope);
                    output.writeInt(proof.variables);
                    rows(output, proof.axioms);
                    output.writeInt(proof.multipliers.size());
                    for (var value : proof.multipliers) integer(output, value);
                }
                if (version >= 0x43475034) {
                    output.writeInt(rounding.size());
                    for (var proof : rounding) {
                        output.writeUTF(proof.scope);
                        output.writeInt(proof.variables);
                        rows(output, proof.axioms);
                        output.writeInt(proof.multipliers.size());
                        for (var value : proof.multipliers) integer(output, value);
                        integer(output, proof.divisor);
                        output.writeInt(proof.lower.size());
                        for (var value : proof.lower) integer(output, value);
                        rows(output, List.of(proof.consequence));
                        if (version >= 0x43475042) output.writeByte(proof.kind.ordinal());
                    }
                }
                if (version >= 0x43475035) {
                    output.writeInt(cliques.size());
                    for (var proof : cliques) {
                        output.writeUTF(proof.scope);
                        output.writeInt(proof.variables);
                        rows(output, proof.axioms);
                        for (int id = 0; id < proof.variables; id++) {
                            integer(output, proof.lower.get(id));
                            output.writeBoolean(proof.upper.get(id) != null);
                            if (proof.upper.get(id) != null) integer(output, proof.upper.get(id));
                        }
                        output.writeInt(proof.literals.size());
                        for (int literal : proof.literals) output.writeInt(literal);
                        for (int witness : proof.witnesses) output.writeInt(witness);
                        rows(output, List.of(proof.consequence));
                    }
                }
                if (version >= 0x43475036) {
                    output.writeInt(derivations.size());
                    for (var proof : derivations) {
                        output.writeUTF(proof.scope);
                        output.writeInt(proof.variables);
                        rows(output, proof.axioms);
                        output.writeInt(proof.steps.size());
                        for (var step : proof.steps) {
                            output.writeInt(step.parents.size());
                            for (var parent : step.parents.entrySet()) {
                                output.writeInt(parent.getKey());
                                integer(output, parent.getValue());
                            }
                            integer(output, step.divisor);
                            rows(output, List.of(step.consequence));
                        }
                    }
                }
                if (version >= 0x43475037) {
                    output.writeInt(knapsacks.size());
                    for (var proof : knapsacks) {
                        output.writeUTF(proof.scope);
                        output.writeInt(proof.variables);
                        rows(output, List.of(proof.source, proof.consequence));
                        for (int id = 0; id < proof.variables; id++) {
                            integer(output, proof.lower.get(id));
                            output.writeBoolean(proof.upper.get(id) != null);
                            if (proof.upper.get(id) != null) integer(output, proof.upper.get(id));
                        }
                    }
                }
                if (version >= 0x43475038) {
                    output.writeInt(diagrams.size());
                    for (var proof : diagrams) {
                        output.writeUTF(proof.scope);
                        output.writeInt(proof.variables);
                        rows(output, proof.axioms);
                        for (int id = 0; id < proof.variables; id++) {
                            integer(output, proof.lower.get(id));
                            integer(output, proof.upper.get(id));
                            output.writeInt(proof.order.get(id));
                        }
                        output.writeInt(proof.layers.size());
                        for (var layer : proof.layers) vectors(output, layer);
                    }
                }
                if (version >= 0x43475039) {
                    output.writeInt(symmetries.size());
                    for (var proof : symmetries) {
                        output.writeUTF(proof.scope);
                        output.writeInt(proof.variables);
                        rows(output, proof.axioms);
                        output.writeInt(proof.permutations.size());
                        for (var permutation : proof.permutations) for (int id : permutation) output.writeInt(id);
                        rows(output, proof.leaders);
                    }
                }
                if (version >= 0x43475041) {
                    output.writeInt(inductions.size());
                    for (var proof : inductions) {
                        vectors(output, List.of(proof.problem().initial(), proof.problem().goal()));
                        vectors(output, proof.problem().inputs());
                        vectors(output, proof.problem().outputs());
                        output.writeInt(proof.depth());
                        certificate(output, proof.base(), version);
                        certificate(output, proof.induction(), version);
                    }
                }
                if (version >= 0x43475044) {
                    output.writeInt(affine.size());
                    for (var proof : affine) CountAffineProof.write(output, proof);
                }
                if (version >= 0x43475045) {
                    output.writeInt(affineConflicts.size());
                    for (var proof : affineConflicts) CountAffineConflictProof.write(output, proof);
                }
            }
        }
    }

    /** Conservative retained payload estimates; shared values may be counted more than once. */
    private static final class ArchiveSize {

        static long add(long left, long right) {
            return left < 0 || right < 0 || left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
        }

        static long multiply(long count, long bytes) {
            return count < 0 || bytes < 0 || bytes != 0 && count > Long.MAX_VALUE / bytes ?
                    Long.MAX_VALUE : count * bytes;
        }

        static long sum(long... values) {
            long result = 0;
            for (long value : values) result = add(result, value);
            return result;
        }

        static long integer(BigInteger value) {
            // Include the integer object, its magnitude array and alignment.
            // One extra sign bit also covers negative powers of two, whose
            // bitLength is smaller than their unsigned magnitude length.
            return value == null ? 0 : add(80, multiply(((long) value.bitLength() + 32) / 32, 4));
        }

        static long text(String value) {
            return value == null ? 0 : add(64, multiply(value.length(), 2));
        }

        static long list(int count) {
            return add(96, multiply(count, 16));
        }

        static long map(int count) {
            // Tree/hash nodes, boxed indices, references, backing storage.
            return add(128, multiply(count, 96));
        }

        static long indices(Collection<Integer> values) {
            return add(list(values.size()), multiply(values.size(), 32));
        }

        static long vector(List<BigInteger> values) {
            long result = list(values.size());
            for (var value : values) result = add(result, integer(value));
            return result;
        }

        static long vectors(List<List<BigInteger>> values) {
            long result = list(values.size());
            for (var value : values) result = add(result, vector(value));
            return result;
        }

        static long row(Row row) {
            long result = sum(128, map(row.terms.size()), integer(row.upper));
            for (var coefficient : row.terms.values()) result = add(result, integer(coefficient));
            return result;
        }

        static long rows(List<Row> rows) {
            long result = list(rows.size());
            for (var row : rows) result = add(result, row(row));
            return result;
        }

        static long combination(Combination value) {
            long result = sum(128, map(value.parents.size()), integer(value.divisor), row(value.consequence));
            for (var multiplier : value.parents.values()) result = add(result, integer(multiplier));
            return result;
        }

        static long combinations(List<Combination> values) {
            long result = list(values.size());
            for (var value : values) result = add(result, combination(value));
            return result;
        }

        static long certificate(Certificate value) {
            long result = sum(128, text(value.scope), rows(value.axioms), list(value.forbidden.size()),
                    list(value.farkas.size()), combinations(value.derived));
            for (var clause : value.forbidden) result = add(result, rows(clause));
            for (var fraction : value.farkas)
                result = add(result, sum(64, integer(fraction.numerator), integer(fraction.denominator)));
            return result;
        }

        static long derivation(Derivation value) {
            return sum(128, text(value.scope), rows(value.axioms), combinations(value.steps));
        }

        static long divisibility(Divisibility value) {
            return sum(128, text(value.scope), rows(value.axioms), vector(value.multipliers));
        }

        static long rounding(Rounding value) {
            return sum(128, text(value.scope), rows(value.axioms), vector(value.multipliers),
                    integer(value.divisor), vector(value.lower), row(value.consequence));
        }

        static long clique(Clique value) {
            return sum(128, text(value.scope), rows(value.axioms), vector(value.lower), vector(value.upper),
                    indices(value.literals), indices(value.witnesses), row(value.consequence));
        }

        static long knapsack(Knapsack value) {
            return sum(128, text(value.scope), row(value.source), row(value.consequence),
                    vector(value.lower), vector(value.upper));
        }

        static long diagram(Diagram value) {
            long result = sum(128, text(value.scope), rows(value.axioms), vector(value.lower), vector(value.upper),
                    indices(value.order), list(value.layers.size()));
            for (var layer : value.layers) result = add(result, vectors(layer));
            return result;
        }

        static long symmetry(Symmetry value) {
            long result = sum(128, text(value.scope), rows(value.axioms), rows(value.leaders), list(value.permutations.size()));
            for (var permutation : value.permutations) result = add(result, indices(permutation));
            return result;
        }

        static long execution(ExecutionProof.Certificate value) {
            return sum(128, text(value.scope()), vector(value.initial()), vector(value.goal()),
                    vectors(value.inputs()), vectors(value.outputs()), vectors(value.states()), map(value.marked().size()));
        }

        static long induction(CountInduction.Proof value) {
            var problem = value.problem();
            return sum(256, certificate(value.base()), certificate(value.induction()), vector(problem.initial()),
                    vector(problem.goal()), vectors(problem.inputs()), vectors(problem.outputs()));
        }
    }

    static Certificate certificate(String scope, int variables, List<ExactLinearProgram.Constraint> rows,
                                   List<CountConflict> conflicts, ExactRational[] weights, boolean closed) {
        return new Certificate(scope, variables, rows.stream().map(CountProof::row).toList(),
                conflicts.stream().map(c -> c.assumptions().stream().map(CountProof::row).toList()).toList(),
                weights == null ? List.of() : Arrays.stream(weights).map(w -> new Fraction(w.numerator(), w.denominator())).toList(), closed);
    }

    static Row row(ExactLinearProgram.Constraint row) {
        return new Row(row.terms(), row.upper());
    }

    /** No planner, presolver, LP tableau or conflict-analysis routine is used here. */
    public static Verdict verify(Certificate proof, long maximumWork) {
        return verify(proof, maximumWork, unused -> {});
    }

    static Verdict verify(Certificate proof, long maximumWork, java.util.function.LongConsumer charged) {
        if (proof.variables < 0 || proof.variables > 16384 || maximumWork <= 0) return Verdict.INVALID;
        for (var row : proof.axioms) if (!valid(row, proof.variables)) return Verdict.INVALID;
        for (var clause : proof.forbidden) for (var row : clause) if (!valid(row, proof.variables)) return Verdict.INVALID;
        long[] work = { maximumWork };
        try {
            // Learned linear rows are consequences, never additional axioms.
            // Check their parent chain before allowing the clause checker to
            // use them, including in a closed infeasibility certificate.
            List<Row> available = new ArrayList<>(proof.axioms);
            if (!derive(proof.variables, available, proof.derived, work)) return Verdict.INVALID;
            List<List<Row>> established = new ArrayList<>();
            for (var clause : proof.forbidden) {
                List<Row> assumed = new ArrayList<>(available);
                assumed.addAll(clause);
                // Most leaf exclusions follow directly from a material row.
                // Avoid scanning the entire accumulated clause archive for
                // each such tuple before even propagating its assumptions.
                if (!contradiction(proof.variables, assumed, List.of(), work) &&
                        !contradiction(proof.variables, assumed, established, work))
                    return Verdict.INVALID;
                established.add(clause);
            }
            if (!proof.farkas.isEmpty()) {
                if (proof.farkas.size() != available.size()) return Verdict.INVALID;
                Fraction total = new Fraction(BigInteger.ZERO, BigInteger.ONE);
                Map<Integer, Fraction> columns = new HashMap<>();
                for (int i = 0; i < available.size(); i++) {
                    tick(work);
                    var weight = proof.farkas.get(i);
                    if (weight.numerator.signum() < 0) return Verdict.INVALID;
                    var row = available.get(i);
                    total = total.add(weight.multiply(row.upper));
                    for (var term : row.terms.entrySet()) {
                        tick(work);
                        columns.merge(term.getKey(), weight.multiply(term.getValue()), Fraction::add);
                    }
                }
                if (total.numerator.signum() >= 0 || columns.values().stream().anyMatch(v -> v.numerator.signum() < 0)) return Verdict.INVALID;
                return Verdict.VERIFIED;
            }
            return !proof.closed || contradiction(proof.variables, available, established, work) ? Verdict.VERIFIED : Verdict.INVALID;
        } catch (CheckLimit limit) {
            return Verdict.INCOMPLETE;
        } finally {
            charged.accept(maximumWork - work[0]);
        }
    }

    private static boolean valid(Row row, int variables) {
        return row != null && row.upper != null && row.terms.entrySet().stream().allMatch(e -> e.getKey() >= 0 && e.getKey() < variables && e.getValue() != null);
    }

    private static boolean contradiction(int variables, List<Row> axioms, List<List<Row>> clauses, long[] work) {
        BigInteger[] low = new BigInteger[variables], high = new BigInteger[variables];
        Arrays.fill(low, BigInteger.ZERO);
        boolean changed;
        do {
            changed = false;
            List<Row> rows = new ArrayList<>(axioms);
            for (var clause : clauses) {
                Row pending = null;
                boolean ignored = false;
                for (var premise : clause) {
                    BigInteger min = endpoint(premise, low, high, false, work);
                    if (min != null && min.compareTo(premise.upper) > 0) {
                        ignored = true;
                        break;
                    }
                    BigInteger max = endpoint(premise, low, high, true, work);
                    if (max == null || max.compareTo(premise.upper) > 0) {
                        if (pending != null) {
                            ignored = true;
                            break;
                        }
                        pending = premise;
                    }
                }
                if (ignored) continue;
                if (pending == null) return true;
                Map<Integer, BigInteger> opposite = new HashMap<>();
                pending.terms.forEach((key, value) -> opposite.put(key, value.negate()));
                rows.add(new Row(opposite, pending.upper.negate().subtract(BigInteger.ONE)));
            }
            for (var row : rows) {
                tick(work);
                BigInteger known = BigInteger.ZERO;
                int unknown = 0;
                for (var term : row.terms.entrySet()) {
                    tick(work);
                    if (term.getValue().signum() == 0) continue;
                    BigInteger bound = term.getValue().signum() > 0 ? low[term.getKey()] : high[term.getKey()];
                    if (bound == null) unknown++;
                    else known = known.add(bound.multiply(term.getValue()));
                }
                if (unknown == 0 && known.compareTo(row.upper) > 0) return true;
                for (var term : row.terms.entrySet()) {
                    tick(work);
                    int id = term.getKey();
                    BigInteger coefficient = term.getValue();
                    if (coefficient.signum() == 0) continue;
                    BigInteger old = coefficient.signum() > 0 ? low[id] : high[id];
                    if (unknown - (old == null ? 1 : 0) != 0) continue;
                    BigInteger remainder = row.upper.subtract(known).add(old == null ? BigInteger.ZERO : old.multiply(coefficient));
                    if (coefficient.signum() > 0) {
                        BigInteger bound = floor(remainder, coefficient);
                        if (high[id] == null || bound.compareTo(high[id]) < 0) {
                            high[id] = bound;
                            changed = true;
                        }
                    } else {
                        BigInteger bound = floor(remainder, coefficient.negate()).negate();
                        if (bound.compareTo(low[id]) > 0) {
                            low[id] = bound;
                            changed = true;
                        }
                    }
                    if (high[id] != null && low[id].compareTo(high[id]) > 0) return true;
                }
            }
        } while (changed);
        return false;
    }

    private static BigInteger endpoint(Row row, BigInteger[] low, BigInteger[] high, boolean maximum, long[] work) {
        BigInteger result = BigInteger.ZERO;
        for (var term : row.terms.entrySet()) {
            tick(work);
            if (term.getValue().signum() == 0) continue;
            BigInteger bound = (term.getValue().signum() > 0) == maximum ? high[term.getKey()] : low[term.getKey()];
            if (bound == null) return null;
            result = result.add(bound.multiply(term.getValue()));
        }
        return result;
    }

    private static BigInteger floor(BigInteger numerator, BigInteger positive) {
        BigInteger[] result = numerator.divideAndRemainder(positive);
        return result[1].signum() < 0 ? result[0].subtract(BigInteger.ONE) : result[0];
    }

    private static final class CheckLimit extends RuntimeException {

        CheckLimit() {
            super(null, null, false, false);
        }
    }

    private static void tick(long[] work) {
        if (--work[0] < 0) throw new CheckLimit();
    }

    private static void integer(DataOutputStream out, BigInteger value) throws IOException {
        byte[] bytes = value.toByteArray();
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static BigInteger integer(DataInputStream in) throws IOException {
        int size = length(in, 65536);
        if (size == 0) throw new IOException("Empty integer");
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) throw new EOFException();
        return new BigInteger(bytes);
    }

    static int length(DataInputStream in, int max) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > max) throw new IOException("Certificate size exceeds limit");
        return length;
    }

    static void rows(DataOutputStream out, List<Row> rows) throws IOException {
        out.writeInt(rows.size());
        for (var row : rows) {
            out.writeInt(row.terms.size());
            for (var term : row.terms.entrySet()) {
                out.writeInt(term.getKey());
                integer(out, term.getValue());
            }
            integer(out, row.upper);
        }
    }

    static List<Row> rows(DataInputStream in) throws IOException {
        List<Row> rows = new ArrayList<>();
        for (int remaining = length(in, 65536); remaining > 0; remaining--) {
            Map<Integer, BigInteger> terms = new LinkedHashMap<>();
            for (int count = length(in, 16384); count > 0; count--) {
                int key = in.readInt();
                if (terms.put(key, integer(in)) != null) throw new IOException("Duplicate column");
            }
            rows.add(new Row(terms, integer(in)));
        }
        return rows;
    }

    private static void vectors(DataOutputStream out, List<List<BigInteger>> vectors) throws IOException {
        out.writeInt(vectors.size());
        for (var vector : vectors) {
            out.writeInt(vector.size());
            for (var value : vector) integer(out, value);
        }
    }

    private static List<List<BigInteger>> vectors(DataInputStream in) throws IOException {
        List<List<BigInteger>> result = new ArrayList<>();
        for (int n = length(in, 65536); n > 0; n--) {
            List<BigInteger> vector = new ArrayList<>();
            for (int m = length(in, 16384); m > 0; m--) vector.add(integer(in));
            result.add(vector);
        }
        return result;
    }

    static void certificate(DataOutputStream output, Certificate proof, int version) throws IOException {
        output.writeUTF(proof.scope);
        output.writeInt(proof.variables);
        rows(output, proof.axioms);
        output.writeInt(proof.forbidden.size());
        for (var clause : proof.forbidden) rows(output, clause);
        output.writeInt(proof.farkas.size());
        for (var weight : proof.farkas) {
            integer(output, weight.numerator);
            integer(output, weight.denominator);
        }
        output.writeBoolean(proof.closed);
        if (version >= 0x43475043) {
            output.writeInt(proof.derived.size());
            for (var step : proof.derived) {
                output.writeInt(step.parents.size());
                for (var parent : step.parents.entrySet()) {
                    output.writeInt(parent.getKey());
                    integer(output, parent.getValue());
                }
                integer(output, step.divisor);
                rows(output, List.of(step.consequence));
            }
        }
    }

    static Certificate certificate(DataInputStream input, int version) throws IOException {
        String scope = input.readUTF();
        int variables = length(input, 16384);
        List<Row> axioms = rows(input);
        List<List<Row>> forbidden = new ArrayList<>();
        for (int n = length(input, 8192); n > 0; n--) forbidden.add(rows(input));
        List<Fraction> weights = new ArrayList<>();
        for (int n = length(input, 65536); n > 0; n--) weights.add(new Fraction(integer(input), integer(input)));
        boolean closed = input.readBoolean();
        List<Combination> derived = new ArrayList<>();
        if (version >= 0x43475043) for (int count = length(input, 8192); count > 0; count--) {
            Map<Integer, BigInteger> parents = new TreeMap<>();
            for (int n = length(input, 65536); n > 0; n--) {
                int id = input.readInt();
                if (parents.put(id, integer(input)) != null) throw new IOException("Duplicate proof parent");
            }
            BigInteger divisor = integer(input);
            List<Row> consequence = rows(input);
            if (consequence.size() != 1) throw new IOException("Expected one derived row");
            derived.add(new Combination(parents, divisor, consequence.get(0)));
        }
        return new Certificate(scope, variables, axioms, forbidden, weights, closed, derived);
    }

    public static Journal read(Path path) throws IOException {
        if (Files.size(path) > 64L << 20) throw new IOException("Certificate archive too large");
        Journal journal = new Journal(128L << 20);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            int version = input.readInt();
            if ((version < 0x43475032 || version > 0x43475039) && (version < 0x43475041 || version > 0x43475045)) throw new IOException("Unsupported certificate format");
            journal.truncated = input.readBoolean();
            for (int remaining = length(input, 8192); remaining > 0; remaining--) journal.add(certificate(input, version));
            for (int n = length(input, 8192); n > 0; n--) {
                String scope = input.readUTF();
                int kind = length(input, ExecutionProof.Kind.values().length - 1);
                var boundaries = vectors(input);
                if (boundaries.size() != 2) throw new IOException("Invalid execution boundaries");
                var inputs = vectors(input);
                var outputs = vectors(input);
                var states = vectors(input);
                Set<Integer> marked = new LinkedHashSet<>();
                for (int m = length(input, 16384); m > 0; m--) marked.add(input.readInt());
                journal.add(new ExecutionProof.Certificate(scope, ExecutionProof.Kind.values()[kind], boundaries.get(0), boundaries.get(1), inputs, outputs, states, marked));
            }
            if (version >= 0x43475033) for (int n = length(input, 8192); n > 0; n--) {
                String scope = input.readUTF();
                int variables = length(input, 16384);
                List<Row> axioms = rows(input);
                List<BigInteger> weights = new ArrayList<>();
                for (int m = length(input, 65536); m > 0; m--) weights.add(integer(input));
                journal.add(new Divisibility(scope, variables, axioms, weights));
            }
            if (version >= 0x43475034) for (int n = length(input, 8192); n > 0; n--) {
                String scope = input.readUTF();
                int variables = length(input, 16384);
                List<Row> axioms = rows(input);
                List<BigInteger> weights = new ArrayList<>();
                for (int m = length(input, 65536); m > 0; m--) weights.add(integer(input));
                BigInteger divisor = integer(input);
                List<BigInteger> low = new ArrayList<>();
                for (int m = length(input, 16384); m > 0; m--) low.add(integer(input));
                List<Row> consequence = rows(input);
                if (consequence.size() != 1) throw new IOException("Invalid rounding consequence");
                int kind = version >= 0x43475042 ? input.readUnsignedByte() : 0;
                if (kind >= RoundingKind.values().length) throw new IOException("Invalid rounding rule");
                journal.add(new Rounding(scope, variables, axioms, weights, divisor, low, consequence.get(0), RoundingKind.values()[kind]));
            }
            if (version >= 0x43475035) for (int n = length(input, 8192); n > 0; n--) {
                String scope = input.readUTF();
                int variables = length(input, 16384);
                List<Row> axioms = rows(input);
                List<BigInteger> low = new ArrayList<>(), high = new ArrayList<>();
                for (int id = 0; id < variables; id++) {
                    low.add(integer(input));
                    high.add(input.readBoolean() ? integer(input) : null);
                }
                int size = length(input, 256);
                List<Integer> literals = new ArrayList<>(), witnesses = new ArrayList<>();
                for (int i = 0; i < size; i++) literals.add(input.readInt());
                for (int i = 0; i < size * (size - 1) / 2; i++) witnesses.add(input.readInt());
                List<Row> consequence = rows(input);
                if (consequence.size() != 1) throw new IOException("Invalid clique consequence");
                journal.add(new Clique(scope, variables, axioms, low, high, literals, witnesses, consequence.get(0)));
            }
            if (version >= 0x43475036) for (int n = length(input, 8192); n > 0; n--) {
                String scope = input.readUTF();
                int variables = length(input, 16384);
                List<Row> axioms = rows(input);
                List<Combination> steps = new ArrayList<>();
                for (int m = length(input, 65536); m > 0; m--) {
                    Map<Integer, BigInteger> parents = new TreeMap<>();
                    for (int k = length(input, 65536); k > 0; k--) {
                        int id = input.readInt();
                        if (parents.put(id, integer(input)) != null) throw new IOException("Duplicate derivation parent");
                    }
                    BigInteger divisor = integer(input);
                    List<Row> consequence = rows(input);
                    if (consequence.size() != 1) throw new IOException("Invalid derivation consequence");
                    steps.add(new Combination(parents, divisor, consequence.get(0)));
                }
                journal.add(new Derivation(scope, variables, axioms, steps));
            }
            if (version >= 0x43475037) for (int n = length(input, 8192); n > 0; n--) {
                String scope = input.readUTF();
                int variables = length(input, 16384);
                List<Row> rows = rows(input);
                if (rows.size() != 2) throw new IOException("Invalid knapsack proof rows");
                List<BigInteger> low = new ArrayList<>(), high = new ArrayList<>();
                for (int id = 0; id < variables; id++) {
                    low.add(integer(input));
                    high.add(input.readBoolean() ? integer(input) : null);
                }
                journal.add(new Knapsack(scope, variables, rows.get(0), low, high, rows.get(1)));
            }
            if (version >= 0x43475038) for (int n = length(input, 8192); n > 0; n--) {
                String scope = input.readUTF();
                int variables = length(input, 128);
                List<Row> axioms = rows(input);
                List<BigInteger> low = new ArrayList<>(), high = new ArrayList<>();
                List<Integer> order = new ArrayList<>();
                for (int id = 0; id < variables; id++) {
                    low.add(integer(input));
                    high.add(integer(input));
                    order.add(input.readInt());
                }
                List<List<List<BigInteger>>> layers = new ArrayList<>();
                for (int k = length(input, 129); k > 0; k--) layers.add(vectors(input));
                journal.add(new Diagram(scope, variables, axioms, low, high, order, layers));
            }
            if (version >= 0x43475039) for (int n = length(input, 8192); n > 0; n--) {
                String scope = input.readUTF();
                int variables = length(input, 192);
                List<Row> axioms = rows(input);
                List<List<Integer>> permutations = new ArrayList<>();
                for (int k = length(input, 16); k > 0; k--) {
                    List<Integer> permutation = new ArrayList<>();
                    for (int id = 0; id < variables; id++) permutation.add(input.readInt());
                    permutations.add(permutation);
                }
                journal.add(new Symmetry(scope, variables, axioms, permutations, rows(input)));
            }
            if (version >= 0x43475041) for (int n = length(input, 8192); n > 0; n--) {
                var boundaries = vectors(input);
                if (boundaries.size() != 2) throw new IOException("Invalid induction boundaries");
                var problem = new CountInduction.Problem(boundaries.get(0), boundaries.get(1), vectors(input), vectors(input));
                int depth = length(input, 6);
                journal.add(new CountInduction.Proof(problem, depth, certificate(input, version), certificate(input, version)));
            }
            if (version >= 0x43475044) for (int n = length(input, 8192); n > 0; n--) journal.add(CountAffineProof.read(input));
            if (version >= 0x43475045) for (int n = length(input, 8192); n > 0; n--) journal.add(CountAffineConflictProof.read(input));
            if (input.read() != -1) throw new IOException("Trailing certificate bytes");
        }
        return journal;
    }

    public static void main(String[] args) throws IOException {
        Journal journal = read(Path.of(args[0]));
        boolean valid = !journal.truncated();
        for (var proof : journal.entries()) {
            Verdict result = verify(proof, 20_000_000);
            System.out.println(proof.scope + ": " + result + "; closed=" + proof.closed);
            valid &= result == Verdict.VERIFIED;
        }
        for (var proof : journal.executions()) {
            Verdict result = ExecutionProof.verify(proof, 20_000_000);
            System.out.println(proof.scope() + ": " + result + "; kind=" + proof.kind());
            valid &= result == Verdict.VERIFIED;
        }
        for (var proof : journal.divisibility()) {
            Verdict result = verify(proof, 20_000_000);
            System.out.println(proof.scope + ": " + result + "; integer_divisibility");
            valid &= result == Verdict.VERIFIED;
        }
        for (var proof : journal.rounding()) {
            Verdict result = verify(proof, 20_000_000);
            System.out.println(proof.scope + ": " + result + "; integer_rounding");
            valid &= result == Verdict.VERIFIED;
        }
        for (var proof : journal.cliques()) {
            Verdict result = verify(proof, 20_000_000);
            System.out.println(proof.scope + ": " + result + "; mutual_exclusion_clique");
            valid &= result == Verdict.VERIFIED;
        }
        for (var proof : journal.derivations()) {
            Verdict result = verify(proof, 20_000_000);
            System.out.println(proof.scope + ": " + result + "; integer_derivation");
            valid &= result == Verdict.VERIFIED;
        }
        for (var proof : journal.knapsacks()) {
            Verdict result = verify(proof, 20_000_000);
            System.out.println(proof.scope + ": " + result + "; lifted_cover");
            valid &= result == Verdict.VERIFIED;
        }
        for (var proof : journal.diagrams()) {
            Verdict result = verify(proof, 20_000_000);
            System.out.println(proof.scope + ": " + result + "; decision_diagram");
            valid &= result == Verdict.VERIFIED;
        }
        for (var proof : journal.symmetries()) {
            Verdict result = verify(proof, 20_000_000);
            System.out.println(proof.scope + ": " + result + "; Boolean_orbit_lex_leaders");
            valid &= result == Verdict.VERIFIED;
        }
        for (var proof : journal.inductions()) {
            Verdict result = CountInduction.verify(proof, 20_000_000, unused -> {});
            System.out.println("token_sum_k_induction: " + result + "; depth=" + proof.depth());
            valid &= result == Verdict.VERIFIED;
        }
        for (var proof : journal.affine()) {
            Verdict result = CountAffineProof.verify(proof, 20_000_000);
            System.out.println("affine_cut_transfer: " + result);
            valid &= result == Verdict.VERIFIED;
        }
        for (var proof : journal.affineConflicts()) {
            Verdict result = CountAffineConflictProof.verify(proof, 20_000_000);
            System.out.println("affine_conflict_transfer: " + result);
            valid &= result == Verdict.VERIFIED;
        }
        if (!valid) throw new IllegalStateException("Incomplete or invalid proof archive");
    }
}
