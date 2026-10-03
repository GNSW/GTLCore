package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** Exact equality residues tighten intervals without changing count coordinates. */
final class CountResiduePresolve implements AutoCloseable {

    private final List<ExactLinearProgram.Constraint> source;
    private final PlanningBudget budget;
    private final BigInteger[] initialLower, initialUpper, lower, upper, modulus, residue;
    private final List<ExactLinearProgram.Constraint> equations = new ArrayList<>(), cuts = new ArrayList<>();
    private final BitSet touched = new BitSet();
    private final long allowance;
    private long memory, work;
    private int cursor, rounds, tightened, merged;
    private boolean indexed, changed, complete, blocked;

    CountResiduePresolve(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, PlanningBudget budget) {
        source = rows;
        this.budget = budget;
        initialLower = low.clone();
        initialUpper = high.clone();
        lower = low.clone();
        upper = high.clone();
        modulus = new BigInteger[low.length];
        residue = new BigInteger[low.length];
        Arrays.fill(modulus, BigInteger.ONE);
        Arrays.fill(residue, BigInteger.ZERO);
        allowance = Math.min(16_384, budget.remainingWork() / 32);
        long terms = rows.stream().mapToLong(row -> row.terms().size()).sum();
        long bytes = 1024L + 768L * low.length + 192L * rows.size() + 160L * terms;
        if (low.length > 2048 || rows.size() > 8192 || terms > 32768 || allowance < 256 || !budget.tryReserve(bytes)) complete = true;
        else memory = bytes;
    }

    boolean step() {
        if (complete) return true;
        try {
            charge();
            if (!indexed) {
                index();
                indexed = true;
                return false;
            }
            if (cursor == equations.size()) {
                if (!changed || ++rounds == 8) return finish();
                cursor = 0;
                changed = false;
            }
            if (cursor < equations.size()) propagate(equations.get(cursor++));
            return blocked ? finish() : false;
        } catch (LocalLimit ignored) {
            // A partial pass contains only proved consequences. Its unfinished
            // work is never an inconsistency and leaves every other value free.
            return finish();
        }
    }

    private void index() {
        Map<Map<Integer, BigInteger>, ExactLinearProgram.Constraint> known = new LinkedHashMap<>();
        for (var row : source) {
            boolean small = row.upper().bitLength() <= 512;
            for (var coefficient : row.terms().values()) {
                charge();
                small &= coefficient.bitLength() <= 512;
            }
            if (!small) continue;
            var normalized = CountReduction.normalize(row);
            var previous = known.get(normalized.terms());
            if (previous == null || normalized.upper().compareTo(previous.upper()) < 0) known.put(normalized.terms(), normalized);
        }
        Set<Map<Integer, BigInteger>> used = new HashSet<>();
        for (var row : known.values()) {
            charge();
            if (row.terms().isEmpty() || used.contains(row.terms())) continue;
            Map<Integer, BigInteger> opposite = new LinkedHashMap<>();
            for (var term : row.terms().entrySet()) {
                charge();
                opposite.put(term.getKey(), term.getValue().negate());
            }
            var reverse = known.get(opposite);
            if (reverse == null || !row.upper().negate().equals(reverse.upper())) continue;
            equations.add(row);
            used.add(row.terms());
            used.add(opposite);
        }
    }

    private void propagate(ExactLinearProgram.Constraint row) {
        List<Integer> ids = new ArrayList<>();
        List<BigInteger> coefficients = new ArrayList<>();
        BigInteger rhs = row.upper();
        for (var term : row.terms().entrySet()) {
            charge();
            int id = term.getKey();
            if (lower[id].equals(upper[id])) rhs = rhs.subtract(term.getValue().multiply(lower[id]));
            else if (term.getValue().signum() != 0) {
                ids.add(id);
                coefficients.add(term.getValue());
            }
        }
        if (ids.isEmpty()) {
            blocked = rhs.signum() != 0;
            return;
        }
        int n = ids.size();
        BigInteger[] prefix = new BigInteger[n + 1], suffix = new BigInteger[n + 1];
        prefix[0] = suffix[n] = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            charge();
            prefix[i + 1] = prefix[i].gcd(coefficients.get(i));
            suffix[n - i - 1] = suffix[n - i].gcd(coefficients.get(n - i - 1));
        }
        for (int i = 0; i < n; i++) {
            charge();
            int id = ids.get(i);
            BigInteger a = coefficients.get(i), g = prefix[i].gcd(suffix[i + 1]);
            if (g.signum() == 0) {
                BigInteger[] qr = rhs.divideAndRemainder(a);
                if (qr[1].signum() != 0 || qr[0].compareTo(lower[id]) < 0 || upper[id] != null && qr[0].compareTo(upper[id]) > 0 ||
                        !qr[0].subtract(residue[id]).mod(modulus[id]).equals(BigInteger.ZERO)) {
                    blocked = true;
                    return;
                }
                tighten(id, qr[0], qr[0]);
                continue;
            }
            BigInteger d = a.gcd(g);
            if (rhs.remainder(d).signum() != 0) {
                blocked = true;
                return;
            }
            BigInteger m = g.divide(d);
            if (m.equals(BigInteger.ONE)) continue;
            BigInteger r = rhs.divide(d).multiply(a.divide(d).mod(m).modInverse(m)).mod(m);
            merge(id, m, r);
            if (blocked) return;
        }
    }

    private void merge(int id, BigInteger nextModulus, BigInteger nextResidue) {
        BigInteger old = modulus[id], gcd = old.gcd(nextModulus), difference = nextResidue.subtract(residue[id]);
        if (difference.remainder(gcd).signum() != 0) {
            blocked = true;
            return;
        }
        BigInteger quotient = nextModulus.divide(gcd), combined = old.multiply(quotient);
        if (combined.bitLength() > 512) return;
        if (!quotient.equals(BigInteger.ONE)) {
            BigInteger multiplier = difference.divide(gcd).multiply(old.divide(gcd).mod(quotient).modInverse(quotient)).mod(quotient);
            residue[id] = residue[id].add(old.multiply(multiplier)).mod(combined);
            modulus[id] = combined;
            changed = true;
            merged++;
        }
        BigInteger lo = lower[id].add(residue[id].subtract(lower[id]).mod(combined));
        BigInteger hi = upper[id] == null ? null : upper[id].subtract(upper[id].subtract(residue[id]).mod(combined));
        if (hi != null && lo.compareTo(hi) > 0) blocked = true;
        else tighten(id, lo, hi);
    }

    private void tighten(int id, BigInteger lo, BigInteger hi) {
        if (!lo.equals(lower[id]) || !Objects.equals(hi, upper[id])) {
            lower[id] = lo;
            upper[id] = hi;
            changed = true;
            tightened++;
            touched.set(id);
        }
    }

    private void charge() {
        if (work >= allowance) throw LocalLimit.INSTANCE;
        budget.check();
        work++;
    }

    private boolean finish() {
        complete = true;
        if (blocked) cuts.add(new ExactLinearProgram.Constraint(Map.of(), BigInteger.ONE.negate()));
        else for (int i = touched.nextSetBit(0); i >= 0; i = touched.nextSetBit(i + 1)) {
            budget.check();
            if (lower[i].compareTo(initialLower[i]) > 0) cuts.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), lower[i].negate()));
            if (upper[i] != null && (initialUpper[i] == null || upper[i].compareTo(initialUpper[i]) < 0))
                cuts.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), upper[i]));
        }
        if (!cuts.isEmpty()) {
            if (budget.proofJournal() != null) {
                var scope = new ArrayList<>(source);
                for (int i = 0; i < lower.length; i++) {
                    scope.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), initialLower[i].negate()));
                    if (initialUpper[i] != null) scope.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), initialUpper[i]));
                }
                var forbidden = new ArrayList<CountConflict>();
                for (var cut : cuts) {
                    Map<Integer, BigInteger> opposite = new LinkedHashMap<>();
                    cut.terms().forEach((id, coefficient) -> opposite.put(id, coefficient.negate()));
                    forbidden.add(new CountConflict(List.of(new ExactLinearProgram.Constraint(opposite, cut.upper().negate().subtract(BigInteger.ONE)))));
                }
                budget.proofJournal().add(CountProof.certificate("equality_residue_bounds", lower.length, scope, forbidden, null, false));
            }
            budget.note("count_residue", "equations=" + equations.size() + "; merged=" + merged + "; tightened=" + tightened +
                    "; blocked=" + blocked + "; work=" + work);
        }
        return true;
    }

    List<ExactLinearProgram.Constraint> cuts() {
        return List.copyOf(cuts);
    }

    BigInteger[] lower() {
        return lower.clone();
    }

    BigInteger[] upper() {
        return upper.clone();
    }

    private static final class LocalLimit extends RuntimeException {

        private static final LocalLimit INSTANCE = new LocalLimit();

        private LocalLimit() {
            super(null, null, false, false);
        }
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
