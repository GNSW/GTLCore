package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** Exact two-row lattice repair around a relaxation, including trial faces of bounded intervals. */
final class CountLatticeRepair implements AutoCloseable {

    private record Entry(int code, BigInteger a, BigInteger b) {}

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper, base;
    private final PlanningBudget budget;
    private final Map<List<BigInteger>, List<Entry>> left = new HashMap<>();
    private final long allowance;
    private int[] free, sizes;
    private BigInteger[] a, b;
    private BigInteger determinant, rhsA, rhsB, sumA = BigInteger.ZERO, sumB = BigInteger.ZERO;
    private BigInteger[] counts;
    private Iterator<Entry> matches;
    private int first, second, split, leftStates, rightStates, index, phase;
    private long work, memory;
    private boolean complete;

    CountLatticeRepair(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                       ExactRational[] point, int attempt, PlanningBudget budget) {
        this(rows, lower, upper, point, attempt, budget, null);
    }

    CountLatticeRepair(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                       ExactRational[] point, int attempt, PlanningBudget budget, CountLatticeStructure structure) {
        this.rows = rows;
        this.lower = lower;
        this.upper = upper;
        this.budget = budget;
        allowance = Math.min(1_000_000, budget.remainingWork() / 8);
        base = new BigInteger[lower.length];
        if (point == null || lower.length > 64 || allowance < 1024) {
            complete = true;
            return;
        }
        long before = budget.threadWork();
        CountLatticeStructure owned = null;
        try {
            for (int i = 0; i < upper.length; i++) {
                budget.check();
                if (upper[i] == null) {
                    complete = true;
                    return;
                }
            }
            if (structure == null) structure = owned = CountLatticeStructure.create(rows, budget);
            else if (!structure.matches(rows, budget)) throw new IllegalArgumentException("Foreign lattice structure");
            if (structure == null || !structure.usable()) {
                complete = true;
                return;
            }
            long bytes = structure.workspaceBytes(lower, upper);
            if (!budget.tryReserve(bytes)) {
                complete = true;
                return;
            }
            memory = bytes;
            initialize(structure, point, attempt);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        } finally {
            if (owned != null) owned.close();
            work = budget.threadWork() - before;
        }
    }

    private void initialize(CountLatticeStructure structure, ExactRational[] point, int attempt) {
        for (int i = 0; i < base.length; i++) {
            charge();
            base[i] = point[i].floor().max(lower[i]).min(upper[i]);
        }
        // A small amount of allowed surplus must not disable lattice repair.
        // Pick an integer face INSIDE each proved interval. These equalities
        // are candidate restrictions only; failure never certifies infeasibility.
        var equations = structure.equations(point, attempt);
        if (equations.size() < 2) {
            complete = true;
            return;
        }
        var x = equations.get(0);
        CountLatticeStructure.Equation y = null;
        // Prefer the fractional basis coordinates, retaining all other bounds.
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < lower.length; i++) {
            charge();
            if (!lower[i].equals(upper[i])) candidates.add(i);
        }
        candidates.sort((i, j) -> {
            charge();
            return Boolean.compare(point[i].integral(), point[j].integral());
        });
        outer:
        for (int i : candidates) for (int j : candidates) if (i != j) for (int r = 1; r < equations.size(); r++) {
            charge();
            var next = equations.get(r);
            BigInteger det = coefficient(x, i).multiply(coefficient(next, j)).subtract(coefficient(x, j).multiply(coefficient(next, i)));
            if (det.signum() != 0) {
                first = i;
                second = j;
                determinant = det;
                y = next;
                break outer;
            }
        }
        if (y == null) {
            complete = true;
            return;
        }
        a = new BigInteger[lower.length];
        b = new BigInteger[lower.length];
        for (int i = 0; i < lower.length; i++) {
            charge();
            a[i] = coefficient(x, i).multiply(coefficient(y, second)).subtract(coefficient(y, i).multiply(coefficient(x, second)));
            b[i] = coefficient(y, i).multiply(coefficient(x, first)).subtract(coefficient(x, i).multiply(coefficient(y, first)));
        }
        rhsA = x.upper().multiply(coefficient(y, second)).subtract(y.upper().multiply(coefficient(x, second)));
        rhsB = y.upper().multiply(coefficient(x, first)).subtract(x.upper().multiply(coefficient(y, first)));
        List<Integer> lhs = new ArrayList<>(), rhs = new ArrayList<>();
        Map<Integer, Integer> widths = new HashMap<>();
        long nl = 1, nr = 1;
        if (attempt > 0) {
            budget.charge(candidates.size());
            Collections.rotate(candidates, attempt);
        }
        for (int i : candidates) {
            charge();
            if (i == first || i == second) continue;
            BigInteger start = lower[i], end = upper[i];
            if (attempt == 1 || attempt == 2) {
                BigInteger radius = BigInteger.valueOf(attempt == 1 ? 4 : 16);
                start = start.max(base[i].subtract(radius));
                end = end.min(base[i].add(radius));
            }
            BigInteger size = end.subtract(start).add(BigInteger.ONE);
            if (size.compareTo(BigInteger.valueOf(4096)) > 0) continue;
            int width = size.intValueExact();
            if (nl <= nr && nl * width <= 65536) {
                lhs.add(i);
                nl *= width;
                base[i] = start;
                widths.put(i, width);
            } else if (nr * width <= 65536) {
                rhs.add(i);
                nr *= width;
                base[i] = start;
                widths.put(i, width);
            }
        }
        split = lhs.size();
        lhs.addAll(rhs);
        free = lhs.stream().mapToInt(Integer::intValue).toArray();
        sizes = Arrays.stream(free).map(widths::get).toArray();
        leftStates = (int) nl;
        rightStates = (int) nr;
        for (int i = 0; i < base.length; i++) if (i != first && i != second) {
            charge();
            rhsA = rhsA.subtract(a[i].multiply(base[i]));
            rhsB = rhsB.subtract(b[i].multiply(base[i]));
        }
        BigInteger maxA = BigInteger.ZERO, maxB = BigInteger.ZERO;
        for (int i = 0; i < split; i++) {
            charge();
            BigInteger extent = BigInteger.valueOf(sizes[i] - 1L);
            maxA = maxA.add(a[free[i]].abs().multiply(extent));
            maxB = maxB.add(b[free[i]].abs().multiply(extent));
        }
        long entryBytes = 256L + (maxA.bitLength() + maxB.bitLength() + 14L) / 8 + (determinant.bitLength() + 7L) / 4;
        long bytes = 2048L + entryBytes * leftStates;
        if (!budget.tryReserve(bytes)) complete = true;
        else memory += bytes;
    }

    boolean step() {
        if (complete) return true;
        charge();
        if (work >= allowance) return finish("work_limit");
        if (phase == 0) {
            left.computeIfAbsent(key(sumA, sumB), unused -> new ArrayList<>()).add(new Entry(index, sumA, sumB));
            if (++index == leftStates) {
                index = 0;
                sumA = sumB = BigInteger.ZERO;
                phase = 1;
            } else advance(index - 1, 0, split);
            return false;
        }
        if (matches == null) matches = left.getOrDefault(key(rhsA.subtract(sumA), rhsB.subtract(sumB)), List.of()).iterator();
        if (matches.hasNext()) {
            Entry entry = matches.next();
            BigInteger[] u = rhsA.subtract(sumA).subtract(entry.a()).divideAndRemainder(determinant);
            BigInteger[] v = rhsB.subtract(sumB).subtract(entry.b()).divideAndRemainder(determinant);
            if (u[1].signum() != 0 || v[1].signum() != 0) throw new IllegalStateException("Invalid lattice residue");
            if (u[0].compareTo(lower[first]) < 0 || u[0].compareTo(upper[first]) > 0 || v[0].compareTo(lower[second]) < 0 || v[0].compareTo(upper[second]) > 0) return false;
            BigInteger[] candidate = base.clone();
            candidate[first] = u[0];
            candidate[second] = v[0];
            decode(candidate, entry.code(), 0, split);
            decode(candidate, index, split, free.length);
            for (var row : rows) {
                BigInteger sum = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) {
                    charge();
                    sum = sum.add(term.getValue().multiply(candidate[term.getKey()]));
                }
                if (sum.compareTo(row.upper()) > 0) return false;
            }
            counts = candidate;
            return finish("verified_witness");
        }
        matches = null;
        if (++index == rightStates) return finish("face_unresolved");
        advance(index - 1, split, free.length);
        return false;
    }

    private static BigInteger coefficient(CountLatticeStructure.Equation row, int id) {
        return row.terms().getOrDefault(id, BigInteger.ZERO);
    }

    private List<BigInteger> key(BigInteger x, BigInteger y) {
        return List.of(x.mod(determinant.abs()), y.mod(determinant.abs()));
    }

    private void advance(int previous, int start, int end) {
        for (int i = start; i < end; i++) {
            charge();
            int digit = previous % sizes[i];
            previous /= sizes[i];
            int delta = digit + 1 == sizes[i] ? -digit : 1;
            sumA = sumA.add(a[free[i]].multiply(BigInteger.valueOf(delta)));
            sumB = sumB.add(b[free[i]].multiply(BigInteger.valueOf(delta)));
            if (delta == 1) break;
        }
    }

    private void decode(BigInteger[] values, int code, int start, int end) {
        for (int i = start; i < end; i++) {
            charge();
            values[free[i]] = base[free[i]].add(BigInteger.valueOf(code % sizes[i]));
            code /= sizes[i];
        }
    }

    private void charge() {
        budget.check();
        work++;
    }

    private boolean finish(String detail) {
        complete = true;
        budget.note("count_lattice", detail + "; free=" + free.length + "; left_states=" + leftStates + "; right_states=" + rightStates + "; work=" + work);
        return true;
    }

    BigInteger[] counts() {
        return counts == null ? null : counts.clone();
    }

    @Override
    public void close() {
        left.clear();
        budget.release(memory);
        memory = 0;
    }
}
