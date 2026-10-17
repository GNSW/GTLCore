// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Candidate-only decomposition of bounded interchangeable allocations into
 * scalar subset sums. Every bin uses the same item weights and shares the
 * remaining item multiplicities. Closing one chosen decomposition is never a
 * proof about the original model. The complete candidate is checked exactly.
 */
final class CountGroupedPartition implements CountContinuation {
    private static final int MAX_RANGE = 8_388_608;
    static final int MAX_CAPACITY = 64;
    private record Group(int[] variables, BigInteger capacity) {}
    private static final class Bin {
        final Map<Integer, BigInteger> vector;
        BigInteger low = BigInteger.ZERO, high;
        int[] variables;
        Bin(Map<Integer, BigInteger> vector) { this.vector = vector; }
    }
    private record Chunk(int group, int quantity, int weight) {}
    private static final class Declined extends RuntimeException {
        Declined() { super(null, null, false, false); }
    }

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private final boolean wide;
    private final List<Group> groups = new ArrayList<>();
    private final List<Bin> bins = new ArrayList<>();
    private final List<Chunk> chunks = new ArrayList<>();
    private BigInteger[] weights, counts;
    private BigInteger[] left;
    private int[] parent, used, itemOrder;
    private CountDiophantine scalar;
    private int[] scalarOrder;
    private long scalarMemory;
    private long[] reachable;
    private long[][] predecessorMasks;
    private long memory, tableMemory, work, until, maximumWork, prepareUntil, signatures;
    private int bin, attempt, chunk, word, cap, floor, selected = -1, verifyRow;
    private boolean complete, paused, complement, verifying;

    static CountGroupedPartition create(List<ExactLinearProgram.Constraint> rows, BigInteger[] low,
                                        BigInteger[] high, PlanningBudget budget, long quantum) {
        return create(rows, low, high, budget, quantum, false, false);
    }

    static CountGroupedPartition createMultiway(List<ExactLinearProgram.Constraint> rows, BigInteger[] low,
                                                BigInteger[] high, PlanningBudget budget, long quantum) {
        // A single scalar partition already has cheap retained exact paths.
        // Reserve the extra portfolio arm for coupled allocation partitions.
        return create(rows, low, high, budget, quantum, true, false);
    }

    /** Preserve capacity groups before elimination; scalar faces use exact arithmetic, not a capacity-sized table. */
    static CountGroupedPartition createWide(List<ExactLinearProgram.Constraint> rows, BigInteger[] low,
                                             BigInteger[] high, PlanningBudget budget, long quantum) {
        return create(rows, low, high, budget, quantum, true, true);
    }

    private static CountGroupedPartition create(List<ExactLinearProgram.Constraint> rows, BigInteger[] low,
                                                BigInteger[] high, PlanningBudget budget, long quantum,
                                                boolean multiway, boolean wide) {
        if (low.length < 4 || low.length > 512 || low.length != high.length || rows.size() > 2048 ||
                quantum <= 0 || budget.remainingWork() < 32768) return null;
        for (int i = 0; i < low.length; i++) {
            budget.check();
            if (high[i] == null || low[i].bitLength() > 1024 || high[i].bitLength() > 1024 ||
                    high[i].compareTo(low[i]) < 0 ||
                    !wide && high[i].subtract(low[i]).compareTo(BigInteger.valueOf(MAX_CAPACITY)) > 0) return null;
        }
        long entries = 0;
        int possibleGroups = 0;
        for (var row : rows) {
            budget.check();
            entries += row.terms().size();
            if (entries > 32768) return null;
            if (row.terms().size() >= (multiway ? 3 : 2) && row.terms().size() <= 8) {
                var a = row.terms().values().iterator().next();
                if (a.signum() > 0 && row.terms().values().stream().allMatch(a::equals)) possibleGroups++;
            }
        }
        if (possibleGroups < 2) return null;
        long bytes = 4096L + 256L * low.length + 192L * rows.size() + 256L * entries;
        if (bytes > budget.availableBytes() / 8 || !budget.tryReserve(bytes)) return null;
        var result = new CountGroupedPartition(rows, low, high, budget, bytes, quantum, wide);
        try {
            result.prepare();
            if (multiway && result.bins.size() < 3 || wide && (result.groups.size() > 256 ||
                    result.groups.stream().noneMatch(g -> g.capacity.compareTo(BigInteger.valueOf(MAX_CAPACITY)) > 0)))
                throw new Declined();
            result.prepareUntil = Long.MAX_VALUE;
            result.until = CountContinuation.deadline(result.work, quantum, budget);
            return result;
        } catch (Declined ignored) {
            result.close();
            return null;
        } catch (RuntimeException | Error failure) {
            result.close();
            throw failure;
        }
    }

    private CountGroupedPartition(List<ExactLinearProgram.Constraint> rows, BigInteger[] low,
                                  BigInteger[] high, PlanningBudget budget, long bytes, long quantum, boolean wide) {
        this.rows = List.copyOf(rows);
        lower = low.clone(); upper = high.clone();
        this.budget = budget;
        this.wide = wide;
        memory = bytes;
        maximumWork = Math.min(4_194_304, budget.remainingWork() / 8);
        prepareUntil = Math.min(32768, maximumWork / 8);
        until = quantum;
    }

    private void prepare() {
        var reduced = new ArrayList<ExactLinearProgram.Constraint>();
        int[] owner = new int[lower.length];
        Arrays.fill(owner, -1);
        for (int i = 0; i < lower.length; i++) {
            charge();
            if (upper[i] == null || lower[i].bitLength() > 1024 || upper[i].bitLength() > 1024 ||
                    lower[i].compareTo(upper[i]) > 0) throw new Declined();
        }
        for (var row : rows) {
            charge();
            if (row.upper().bitLength() > 1024) throw new Declined();
            var bound = row.upper();
            var terms = new TreeMap<Integer, BigInteger>();
            for (var term : row.terms().entrySet()) {
                charge();
                int id = term.getKey();
                var a = term.getValue();
                if (id < 0 || id >= lower.length || a.bitLength() > 1024) throw new Declined();
                integer(a, lower[id], bound);
                bound = bound.subtract(a.multiply(lower[id]));
                if (!lower[id].equals(upper[id]) && a.signum() != 0) terms.put(id, a);
            }
            if (terms.isEmpty()) {
                if (bound.signum() < 0) throw new Declined();
                continue;
            }
            reduced.add(new ExactLinearProgram.Constraint(terms, bound));
        }
        // Select disjoint uniform capacity rows. Saturating them is a witness
        // face, not an inferred equality: all other rows are still validated.
        for (var row : reduced) {
            charge();
            if (row.terms().size() < 2 || row.terms().size() > 8) continue;
            var a = row.terms().values().iterator().next();
            if (a.signum() <= 0 || row.upper().signum() < 0) continue;
            var capacity = row.upper().divide(a);
            if (capacity.signum() <= 0 || !wide && capacity.compareTo(BigInteger.valueOf(MAX_CAPACITY)) > 0) continue;
            boolean valid = true;
            for (var term : row.terms().entrySet()) {
                charge();
                int id = term.getKey();
                valid &= owner[id] < 0 && term.getValue().equals(a) && upper[id].subtract(lower[id]).equals(capacity);
            }
            if (!valid) continue;
            var group = new Group(row.terms().keySet().stream().mapToInt(Integer::intValue).toArray(), capacity);
            for (int id : group.variables) owner[id] = groups.size();
            groups.add(group);
        }
        if (groups.size() < 2) throw new Declined();
        for (int i = 0; i < lower.length; i++) {
            charge();
            if (!lower[i].equals(upper[i]) && owner[i] < 0) throw new Declined();
        }
        for (var row : reduced) {
            charge();
            if (row.terms().size() == 1) continue;
            int first = owner[row.terms().keySet().iterator().next()];
            boolean uniformGroup = row.terms().size() == groups.get(first).variables.length;
            var a = row.terms().values().iterator().next();
            var gcd = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                charge();
                uniformGroup &= owner[term.getKey()] == first && term.getValue().equals(a);
                if (term.getValue().signum() != a.signum()) throw new Declined();
                integer(gcd, term.getValue());
                gcd = gcd.gcd(term.getValue().abs());
            }
            if (uniformGroup) continue;
            if (row.terms().size() != groups.size()) throw new Declined();
            var vector = new TreeMap<Integer, BigInteger>();
            var occupied = new BitSet(groups.size());
            for (var term : row.terms().entrySet()) {
                charge();
                int g = owner[term.getKey()];
                if (occupied.get(g)) throw new Declined();
                occupied.set(g);
                integer(term.getValue(), gcd);
                vector.put(term.getKey(), term.getValue().abs().divide(gcd));
            }
            Bin target = null;
            for (Bin candidate : bins) if (candidate.vector.equals(vector)) { target = candidate; break; }
            if (target == null) { target = new Bin(vector); bins.add(target); }
            var quotient = floorDiv(row.upper(), gcd);
            if (a.signum() > 0) target.high = target.high == null ? quotient : target.high.min(quotient);
            else target.low = target.low.max(quotient.negate());
        }
        if (bins.size() < 2 || bins.size() > 8) throw new Declined();
        weights = new BigInteger[groups.size()];
        var seen = new BitSet(lower.length);
        for (Bin target : bins) {
            target.variables = new int[groups.size()];
            for (var term : target.vector.entrySet()) {
                charge();
                int id = term.getKey(), g = owner[id];
                if (seen.get(id)) throw new Declined();
                seen.set(id);
                target.variables[g] = id;
                if (weights[g] == null) weights[g] = term.getValue();
                else if (!weights[g].equals(term.getValue())) throw new Declined();
            }
        }
        for (int g = 0; g < groups.size(); g++) {
            charge();
            if (groups.get(g).variables.length != bins.size()) throw new Declined();
        }
        // Predecessor choice must depend on the allocation structure, rather
        // than the order in which recipes or material rows were discovered.
        var order = new Integer[groups.size()];
        for (int g = 0; g < order.length; g++) { charge(); order[g] = g; }
        Arrays.sort(order, (a, b) -> {
            charge(); integer(weights[a], weights[b]);
            int cmp = weights[a].compareTo(weights[b]);
            return cmp != 0 ? cmp : groups.get(a).capacity.compareTo(groups.get(b).capacity);
        });
        itemOrder = Arrays.stream(order).mapToInt(Integer::intValue).toArray();
        bins.sort((a, b) -> {
            charge(); integer(a.low, b.low);
            int cmp = a.low.compareTo(b.low);
            if (cmp != 0) return cmp;
            if (a.high == null || b.high == null) return a.high == b.high ? 0 : a.high == null ? 1 : -1;
            integer(a.high, b.high);
            return a.high.compareTo(b.high);
        });
        left = new BigInteger[groups.size()]; used = new int[groups.size()];
        restart();
    }

    @Override public boolean step() {
        if (complete || paused) return true;
        budget.checkpoint();
        if (work >= maximumWork) return finish("local_limit");
        if (work >= until) { paused = true; return true; }
        try {
            charge();
            if (verifying) {
                if (verifyRow == rows.size()) return finish("verified_candidate");
                var row = rows.get(verifyRow++);
                var sum = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) {
                    charge();
                    integer(term.getValue(), counts[term.getKey()], sum);
                    sum = sum.add(term.getValue().multiply(counts[term.getKey()]));
                }
                if (sum.compareTo(row.upper()) > 0) retry();
                return complete;
            }
            if (scalar != null) { stepScalar(); return complete; }
            if (reachable == null) { beginBin(); return complete; }
            if (selected >= 0) { recover(); return complete; }
            if (chunk == chunks.size()) { retry(); return complete; }
            var item = chunks.get(chunk);
            if (word < 0) { chunk++; word = reachable.length - 1; return false; }
            int source = word - (item.weight >>> 6), bits = item.weight & 63;
            long moved = source < 0 ? 0 : reachable[source] << bits;
            if (bits != 0 && source > 0) moved |= reachable[source - 1] >>> (64 - bits);
            if (word == reachable.length - 1) moved &= -1L >>> (63 - (cap & 63));
            long added = moved & ~reachable[word];
            reachable[word] |= moved;
            if (predecessorMasks != null) {
                // One ownership bit per newly reached value. Recover through
                // chunk masks later instead of writing a predecessor per bit.
                predecessorMasks[chunk][word] = added;
                signatures += Long.bitCount(added);
                long eligible = word < (floor >>> 6) ? 0 : word == (floor >>> 6) ? added & (-1L << (floor & 63)) : added;
                if (selected < 0 && eligible != 0) selected = (word << 6) + Long.numberOfTrailingZeros(eligible);
                word--;
                return false;
            }
            while (added != 0) {
                charge();
                int sum = (word << 6) + Long.numberOfTrailingZeros(added);
                parent[sum] = chunk + 1;
                signatures++;
                if (sum >= floor && selected < 0) selected = sum;
                added &= added - 1;
            }
            word--;
            return false;
        } catch (Declined ignored) { return finish("range_declined"); }
    }

    private void beginBin() {
        Bin target = bins.get((bin + attempt) % bins.size());
        if (bin == bins.size() - 1) {
            for (int g = 0; g < groups.size(); g++) {
                charge();
                counts[target.variables[g]] = lower[target.variables[g]].add(left[g]);
            }
            verifying = true; verifyRow = 0;
            return;
        }
        var total = BigInteger.ZERO;
        for (int g = 0; g < groups.size(); g++) {
            charge();
            integer(total, weights[g]);
            total = total.add(weights[g].multiply(left[g]));
        }
        var lo = target.low;
        var hi = target.high == null ? total : target.high.min(total);
        var otherLow = BigInteger.ZERO; var otherHigh = BigInteger.ZERO;
        for (int k = bin + 1; k < bins.size(); k++) {
            charge();
            Bin next = bins.get((k + attempt) % bins.size());
            otherLow = otherLow.add(next.low);
            otherHigh = otherHigh.add(next.high == null ? total : next.high);
        }
        lo = lo.max(total.subtract(otherHigh)); hi = hi.min(total.subtract(otherLow));
        if (lo.compareTo(hi) > 0) { retry(); return; }
        if (wide) { beginScalar(lo, hi); return; }
        complement = total.subtract(lo).compareTo(hi) < 0;
        if (complement) { var saved = lo; lo = total.subtract(hi); hi = total.subtract(saved); }
        if (hi.compareTo(BigInteger.valueOf(MAX_RANGE)) > 0) throw new Declined();
        floor = lo.intValueExact(); cap = hi.intValueExact();
        chunks.clear();
        for (int i = 0; i < groups.size(); i++) {
            // Explore bin order before changing predecessor order as well.
            // Rotating both together repeatedly misses distinct allocations.
            int g = itemOrder[(i + attempt / bins.size()) % groups.size()], remaining = left[g].intValueExact();
            for (int size = 1; remaining > 0; size *= 2) {
                charge();
                int take = Math.min(size, remaining); remaining -= take;
                var weight = weights[g].multiply(BigInteger.valueOf(take));
                if (weight.compareTo(BigInteger.valueOf(cap)) <= 0) chunks.add(new Chunk(g, take, weight.intValueExact()));
            }
        }
        int words = (cap >>> 6) + 1;
        long maskBytes = 128L + 8L * words + chunks.size() * (32L + 8L * words);
        boolean masks = groups.size() >= 32 && cap >= 65536 && maskBytes <= budget.availableBytes() / 4;
        long bytes = masks ? maskBytes : 64L + 4L * (cap + 1L) + 8L * words;
        if (bytes > budget.availableBytes() / 4 || !budget.tryReserve(bytes)) throw new Declined();
        tableMemory = bytes;
        if (masks) predecessorMasks = new long[chunks.size()][words];
        else parent = new int[cap + 1];
        reachable = new long[words]; reachable[0] = 1;
        chunk = 0; word = reachable.length - 1; selected = floor == 0 ? 0 : -1;
    }

    private void recover() {
        Arrays.fill(used, 0);
        for (int sum = selected; sum > 0;) {
            charge();
            int id;
            if (predecessorMasks == null) id = parent[sum] - 1;
            else {
                id = Math.min(chunk, chunks.size() - 1);
                while (id >= 0 && (predecessorMasks[id][sum >>> 6] & (1L << (sum & 63))) == 0) { charge(); id--; }
            }
            if (id < 0 || id >= chunks.size()) throw new IllegalStateException("Invalid grouped partition predecessor");
            Chunk item = chunks.get(id);
            used[item.group] += item.quantity;
            sum -= item.weight;
        }
        Bin target = bins.get((bin + attempt) % bins.size());
        for (int g = 0; g < groups.size(); g++) {
            charge();
            int remaining = left[g].intValueExact();
            int value = complement ? remaining - used[g] : used[g];
            if (value < 0 || value > remaining) throw new IllegalStateException("Reused partition stock");
            counts[target.variables[g]] = lower[target.variables[g]].add(BigInteger.valueOf(value));
            left[g] = left[g].subtract(BigInteger.valueOf(value));
        }
        clearTable(); bin++;
    }

    private void beginScalar(BigInteger lo, BigInteger hi) {
        long bytes = 2048L + 1024L * groups.size();
        if (!budget.tryReserve(bytes)) throw new Declined();
        scalarMemory = bytes;
        scalarOrder = new int[groups.size()];
        var low = new BigInteger[groups.size()];
        var high = new BigInteger[groups.size()];
        var positive = new LinkedHashMap<Integer, BigInteger>();
        var negative = new LinkedHashMap<Integer, BigInteger>();
        for (int i = 0; i < groups.size(); i++) {
            charge();
            int g = itemOrder[(i + attempt / bins.size()) % groups.size()];
            scalarOrder[i] = g;
            low[i] = BigInteger.ZERO; high[i] = left[g];
            positive.put(i, weights[g]); negative.put(i, weights[g].negate());
        }
        // The interval includes all other bins' remaining demands. This trial
        // still chooses only one distribution; failure is never a global cut.
        scalar = new CountDiophantine(List.of(new ExactLinearProgram.Constraint(positive, hi),
                new ExactLinearProgram.Constraint(negative, lo.negate())), low, high, budget, 4096).retained();
    }

    private void stepScalar() {
        long before = budget.threadWork(), previousWork = work;
        try {
            if (!scalar.step()) return;
            if (scalar.paused()) {
                scalar.resume(Math.max(1, Math.min(4096, maximumWork - work)));
                return;
            }
            var values = scalar.counts();
            if (values == null) { retry(); return; }
            Bin target = bins.get((bin + attempt) % bins.size());
            for (int i = 0; i < values.length; i++) {
                budget.check();
                int g = scalarOrder[i];
                if (values[i].signum() < 0 || values[i].compareTo(left[g]) > 0)
                    throw new IllegalStateException("Reused scalar allocation stock");
                counts[target.variables[g]] = lower[target.variables[g]].add(values[i]);
                left[g] = left[g].subtract(values[i]);
            }
            signatures++;
            clearTable(); bin++;
        } finally { work = previousWork + budget.threadWork() - before; }
    }

    private void retry() {
        clearTable();
        if (++attempt >= 4) { finish("decompositions_unresolved"); return; }
        restart();
    }
    private void restart() {
        counts = lower.clone();
        for (int i = 0; i < left.length; i++) { charge(); left[i] = groups.get(i).capacity; }
        bin = 0; verifying = false;
    }
    private boolean finish(String reason) {
        complete = true; paused = false;
        if (!reason.equals("verified_candidate")) counts = null;
        budget.note("count_grouped_partition", reason + "; groups=" + groups.size() + "; bins=" + bins.size() +
                "; attempts=" + Math.min(4, attempt + 1) + "; scalar=" + wide + "; work=" + work);
        clearTable();
        return true;
    }
    private void charge() {
        if (work >= prepareUntil) throw new Declined();
        budget.check(); work++;
    }
    private void integer(BigInteger... values) {
        if (work >= prepareUntil) throw new Declined();
        int bits = 0;
        for (var value : values) bits = Math.max(bits, value.bitLength());
        long units = Math.max(1, (bits + 63L) / 64);
        budget.charge(units); work += units;
    }
    private static BigInteger floorDiv(BigInteger value, BigInteger divisor) {
        var qr = value.divideAndRemainder(divisor);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }
    private void clearTable() {
        if (scalar != null) { scalar.close(); scalar = null; }
        scalarOrder = null;
        budget.release(scalarMemory); scalarMemory = 0;
        reachable = null; parent = null; predecessorMasks = null; chunks.clear();
        budget.release(tableMemory); tableMemory = 0;
    }
    @Override public boolean paused() { return paused; }
    @Override public void resume(long quantum) {
        if (complete) throw new IllegalStateException("Completed partition search");
        until = CountContinuation.deadline(work, quantum, budget); paused = false;
    }
    @Override public BigInteger[] counts() { return complete && counts != null ? counts.clone() : null; }
    @Override public boolean matches(List<ExactLinearProgram.Constraint> other, BigInteger[] low, BigInteger[] high) {
        return rows.equals(other) && Arrays.equals(lower, low) && Arrays.equals(upper, high);
    }
    @Override public long work() { return work; }
    @Override public long progress() { return signatures; }
    @Override public void close() { clearTable(); budget.release(memory); memory = 0; }
}
