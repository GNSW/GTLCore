// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Candidate decomposition of interchangeable allocations with several conserved
 * quantities. Each bin reuses exact affine/kernel search and reconsiders finite
 * matching after previous allocations shrink the domain. A failed bin says
 * nothing about another decomposition.
 */
final class CountVectorPartition implements CountContinuation {
    private static final int FIRST_BIN_ENUMERATION_GROUPS = 32;
    private record Group(int[] variables, BigInteger capacity) {}
    private static final class Interval {
        BigInteger low = BigInteger.ZERO, high;
    }
    private static final class Bin {
        final int[] variables;
        final Map<List<BigInteger>, Interval> dimensions = new LinkedHashMap<>();
        BigInteger[] target;
        Bin(int[] variables) { this.variables = variables; }
    }
    private static final class Declined extends RuntimeException {
        Declined() { super(null, null, false, false); }
    }

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private final List<Group> groups = new ArrayList<>();
    private final List<Bin> bins = new ArrayList<>();
    private List<List<BigInteger>> dimensions;
    private BigInteger[] remaining, candidate, counts;
    private int[] order;
    private CountAffineLattice affine;
    private CountAffineLattice alternatives;
    private record Deferred(CountAffineLattice search, int attempt, boolean next) {}
    private final Deque<Deferred> deferred = new ArrayDeque<>();
    private final Set<BigInteger> rejectedResiduals = new HashSet<>();
    private BigInteger residualKey;
    private boolean deferredActive;
    private long alternativeRemaining = 32768;
    private boolean repairing, alternativesPriced;
    private CountMeetInMiddle matching;
    private CountVectorNeighborhood neighborhood;
    private boolean neighborhoodTried;
    private boolean centeredTried, centeredAllocation;
    private boolean wideCapacity;
    private int interiorCompletionDepth;
    private long neighborhoodAt;
    private List<ExactLinearProgram.Constraint> projected;
    private BigInteger[] binLower, binUpper;
    private long matchingAt;
    private boolean matchingTried, matchingEntered;
    private long memory, work, until, maximumWork, completedProgress, prepareStarted;
    private int bin, attempt, verified;
    private boolean complete, paused, verifying;

    static CountVectorPartition create(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                                       BigInteger[] upper, PlanningBudget budget, long quantum) {
        if (lower.length < 4 || lower.length > 384 || upper.length != lower.length || rows.size() > 2048 ||
                quantum <= 0 || budget.remainingWork() < 262144) return null;
        long terms = 0;
        for (var row : rows) {
            budget.check(); terms += row.terms().size();
            if (terms > 32768) return null;
        }
        long bytes = 4096L + 512L * lower.length + 192L * rows.size() + 384L * terms;
        if (bytes > budget.availableBytes() / 8 || !budget.tryReserve(bytes)) return null;
        CountVectorPartition result = null;
        try {
            result = new CountVectorPartition(rows, lower, upper, budget, bytes);
            long before = budget.threadWork();
            try { result.prepare(); }
            finally { result.work += budget.threadWork() - before; }
            result.until = CountContinuation.deadline(result.work, quantum, budget);
            budget.note("count_vector_partition", "admitted; groups=" + result.groups.size() +
                    "; bins=" + result.bins.size() + "; dimensions=" + result.dimensions.size());
            return result;
        } catch (Declined ignored) {
            if (result == null) budget.release(bytes); else result.close();
            return null;
        } catch (RuntimeException | Error failure) {
            if (result == null) budget.release(bytes); else result.close();
            throw failure;
        }
    }

    private CountVectorPartition(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                                  BigInteger[] upper, PlanningBudget budget, long bytes) {
        this.rows = List.copyOf(rows); this.lower = lower.clone(); this.upper = upper.clone();
        this.budget = budget; memory = bytes;
        maximumWork = Math.min(8_388_608, budget.remainingWork() / 4);
        prepareStarted = budget.threadWork();
    }

    private void prepare() {
        var reduced = new ArrayList<ExactLinearProgram.Constraint>();
        int[] owner = new int[lower.length]; Arrays.fill(owner, -1);
        for (int i = 0; i < lower.length; i++) {
            prepareCheck();
            if (upper[i] == null || lower[i].bitLength() > 1024 || upper[i].bitLength() > 1024 ||
                    upper[i].compareTo(lower[i]) < 0 || upper[i].subtract(lower[i]).bitLength() > 30)
                throw new Declined();
            wideCapacity |= upper[i].subtract(lower[i]).compareTo(BigInteger.valueOf(64)) > 0;
        }
        for (var row : rows) {
            prepareCheck();
            if (row.upper().bitLength() > 1024) throw new Declined();
            var rhs = row.upper(); var terms = new TreeMap<Integer, BigInteger>();
            for (var term : row.terms().entrySet()) {
                prepareCheck(); int id = term.getKey(); var a = term.getValue();
                if (id < 0 || id >= lower.length || a.bitLength() > 1024) throw new Declined();
                integer(a, lower[id], rhs); rhs = rhs.subtract(a.multiply(lower[id]));
                if (a.signum() != 0 && !lower[id].equals(upper[id])) terms.put(id, a);
            }
            if (terms.isEmpty()) { if (rhs.signum() < 0) throw new Declined(); }
            else reduced.add(new ExactLinearProgram.Constraint(terms, rhs));
        }
        for (var row : reduced) {
            prepareCheck();
            if (row.terms().size() < 2 || row.terms().size() > 8) continue;
            var a = row.terms().values().iterator().next();
            if (a.signum() <= 0 || row.upper().signum() <= 0) continue;
            var capacity = row.upper().divide(a); boolean valid = capacity.signum() > 0;
            for (var term : row.terms().entrySet()) {
                prepareCheck(); int id = term.getKey();
                valid &= owner[id] < 0 && term.getValue().equals(a) && upper[id].subtract(lower[id]).equals(capacity);
            }
            if (!valid) continue;
            int[] variables = row.terms().keySet().stream().mapToInt(Integer::intValue).toArray();
            for (int id : variables) owner[id] = groups.size();
            groups.add(new Group(variables, capacity));
        }
        // Wide bins use a small restricted repair, without building a dense
        // lattice over every group. Admission is not a domain restriction.
        if (groups.size() < 4 || groups.size() > 128) throw new Declined();
        for (int i = 0; i < lower.length; i++) {
            prepareCheck(); if (!lower[i].equals(upper[i]) && owner[i] < 0) throw new Declined();
        }
        for (var row : reduced) {
            prepareCheck();
            if (row.terms().size() == 1) continue;
            int first = owner[row.terms().keySet().iterator().next()];
            var a = row.terms().values().iterator().next();
            boolean uniform = row.terms().size() == groups.get(first).variables.length;
            var gcd = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                prepareCheck(); uniform &= owner[term.getKey()] == first && term.getValue().equals(a);
                if (term.getValue().signum() != a.signum()) throw new Declined();
                integer(gcd, term.getValue()); gcd = gcd.gcd(term.getValue().abs());
            }
            if (uniform) continue;
            if (row.terms().size() != groups.size()) throw new Declined();
            var vector = new BigInteger[groups.size()]; var variables = new int[groups.size()];
            Arrays.fill(variables, -1);
            for (var term : row.terms().entrySet()) {
                prepareCheck(); int g = owner[term.getKey()];
                if (variables[g] >= 0) throw new Declined();
                variables[g] = term.getKey(); vector[g] = term.getValue().abs().divide(gcd);
            }
            Bin target = null;
            for (var existing : bins) if (Arrays.equals(existing.variables, variables)) { target = existing; break; }
            if (target == null) { target = new Bin(variables); bins.add(target); }
            var interval = target.dimensions.computeIfAbsent(List.of(vector), ignored -> new Interval());
            var bound = floor(row.upper(), gcd);
            if (a.signum() > 0) interval.high = interval.high == null ? bound : interval.high.min(bound);
            else interval.low = interval.low.max(bound.negate());
        }
        if (bins.size() < 2 || bins.size() > 8) throw new Declined();
        dimensions = new ArrayList<>(bins.get(0).dimensions.keySet());
        if (dimensions.size() < 2 || dimensions.size() > 10) throw new Declined();
        for (var target : bins) if (!target.dimensions.keySet().equals(bins.get(0).dimensions.keySet())) throw new Declined();
        for (int g = 0; g < groups.size(); g++) {
            prepareCheck();
            var variables = new HashSet<Integer>(); for (var target : bins) variables.add(target.variables[g]);
            if (variables.size() != bins.size() || groups.get(g).variables.length != bins.size()) throw new Declined();
        }
        for (var target : bins) target.target = new BigInteger[dimensions.size()];
        for (int d = 0; d < dimensions.size(); d++) {
            var vector = dimensions.get(d); var total = BigInteger.ZERO;
            for (int g = 0; g < groups.size(); g++) {
                prepareCheck(); integer(total, vector.get(g)); total = total.add(vector.get(g).multiply(groups.get(g).capacity));
            }
            for (var target : bins) {
                var own = target.dimensions.get(vector); var lo = own.low;
                var hi = own.high == null ? total : own.high.min(total);
                var otherLow = BigInteger.ZERO; var otherHigh = BigInteger.ZERO;
                for (var other : bins) if (other != target) {
                    prepareCheck(); var interval = other.dimensions.get(vector);
                    otherLow = otherLow.add(interval.low); otherHigh = otherHigh.add(interval.high == null ? total : interval.high);
                }
                lo = lo.max(total.subtract(otherHigh)); hi = hi.min(total.subtract(otherLow));
                // Exact targets avoid treating arbitrary slack endpoints as
                // the only feasible allocation. Saturation itself stays local.
                if (!lo.equals(hi) || lo.signum() < 0) throw new Declined();
                target.target[d] = lo;
            }
        }
        var sorted = new Integer[groups.size()]; for (int g = 0; g < sorted.length; g++) sorted[g] = g;
        Arrays.sort(sorted, (a, b) -> {
            prepareCheck();
            for (var vector : dimensions) { int cmp = vector.get(a).compareTo(vector.get(b)); if (cmp != 0) return cmp; }
            return groups.get(a).capacity.compareTo(groups.get(b).capacity);
        });
        order = Arrays.stream(sorted).mapToInt(Integer::intValue).toArray();
        restart();
    }

    @Override public boolean step() {
        if (complete || paused) return true;
        budget.checkpoint();
        if (work >= maximumWork) return finish("local_limit");
        if (work >= until) { paused = true; return true; }
        long before = budget.threadWork();
        boolean repairVisit = repairing;
        try {
            budget.check();
            if (repairing && alternativeRemaining <= 0) {
                // This extra branch has no live ordinary frontier to replace:
                // the previous residual already failed. Resume its pending
                // allocation-order retry, without renewing the scout's quota.
                deferAlternatives();
                clearChildren();
                repairing = false;
                retry();
                return complete;
            }
            if (verifying) {
                if (verified == rows.size()) { counts = candidate.clone(); return finish("verified_candidate"); }
                var row = rows.get(verified++); var total = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) {
                    budget.check(); integer(total, term.getValue(), candidate[term.getKey()]);
                    total = total.add(term.getValue().multiply(candidate[term.getKey()]));
                }
                if (total.compareTo(row.upper()) > 0) retry();
                return complete;
            }
            if (bin == bins.size() - 1) {
                var target = currentBin();
                for (int g = 0; g < groups.size(); g++) { budget.check(); candidate[target.variables[g]] = lower[target.variables[g]].add(remaining[g]); }
                verifying = true; return false;
            }
            if (affine == null) {
                residualKey = deferredActive && bin > 0 ? residualKey() : null;
                if (residualKey != null && rejectedResiduals.contains(residualKey)) {
                    budget.note("count_vector_residual", "exact_conflict_reused; bin=" + bin);
                    retry(); return complete;
                }
                beginBin(); return false;
            }
            if (neighborhood == null && matching == null && !matchingTried && work >= matchingAt) beginMatching();
            if (neighborhood == null && matching == null && !matchingEntered && projected != null && affine.boxSearchMissed()) {
                // The unpruned Cartesian estimate can reject a small reachable
                // table. After a failed box scout, let exact matching prune the
                // actual targets once. Its nonretained cap cannot be renewed;
                // failure leaves the ordinary affine/kernel frontier intact.
                matchingEntered = true;
                long grant = Math.min(262144, maximumWork - work);
                budget.note("count_vector_matching", "after_box_miss; bin=" + bin + "; allowance=" + grant);
                matching = new CountMeetInMiddle(projected, binLower, binUpper, budget,
                        grant);
            }
            if (neighborhood == null && !neighborhoodTried && matching == null && work >= neighborhoodAt) {
                // A successful interior allocation leaves a related residual.
                // Try that geometry first there, while retaining LP and ordinary
                // affine/matching fallbacks. Retry resets the preference.
                if (centeredAllocation && !centeredTried) {
                    beginCenteredNeighborhood();
                } else {
                    neighborhoodTried = true;
                    neighborhood = CountVectorNeighborhood.create(projected, binLower, binUpper, budget, 32768);
                }
            }
            if (!(neighborhood != null ? neighborhood.step() : matching == null ? affine.step() : matching.step())) return false;
            if (childPaused()) {
                long available = Math.min(maximumWork, until) - work - (budget.threadWork() - before);
                if (available > 0) resumeChild(Math.min(262144, available));
                return false;
            }
            BigInteger[] found;
            boolean fromNeighborhood = neighborhood != null;
            if (neighborhood != null) {
                found = neighborhood.counts();
                if (found != null && neighborhood.isCentered()) centeredAllocation = true;
                completedProgress += neighborhood.progress(); neighborhood.close(); neighborhood = null;
                if (found == null) {
                    if (!centeredTried) {
                        // Preserve existing LP successes. A failed restricted
                        // vertex search is not a failed full bin: spend one
                        // finite interior repair inside this parent's old cap.
                        beginCenteredNeighborhood();
                    }
                    return false;
                }
            } else if (matching != null) {
                found = matching.counts();
                boolean impossible = matching.infeasible();
                completedProgress += matching.progress(); matching.close(); matching = null;
                if (impossible && residualKey != null && rejectedResiduals.size() < 512 &&
                        !rejectedResiduals.contains(residualKey)) {
                    long bytes = 192L + (wideCapacity ? 8L : 4L) * groups.size();
                    if (budget.tryReserve(bytes)) { memory += bytes; rejectedResiduals.add(residualKey); }
                }
                // Exhaustion refutes only this residual allocation, not the
                // original problem. Refusal leaves the affine child available.
                if (found == null && !impossible) return false;
            } else {
                found = affine.counts();
                if (found == null && !matchingTried) {
                    beginMatching();
                    if (matching != null) return false;
                }
            }
            if (found != null && bin == 0 && (!fromNeighborhood || affine.counts() != null) &&
                    (alternativeRemaining > 0 || canDefer())) {
                // Keep the completed basis while validating the residual. A
                // local failure can reuse it instead of eliminating again.
                alternatives = affine;
            } else {
                completedProgress += affine.progress(); affine.close();
            }
            affine = null;
            projected = null; binLower = binUpper = null;
            if (found == null) { retry(); return complete; }
            var target = currentBin();
            for (int i = 0; i < order.length; i++) {
                budget.check(); int g = groupAt(i);
                if (found[i].signum() < 0 || found[i].compareTo(remaining[g]) > 0) throw new IllegalStateException("Allocation outside multiplicity");
                candidate[target.variables[g]] = lower[target.variables[g]].add(found[i]); remaining[g] = remaining[g].subtract(found[i]);
            }
            bin++; completedProgress++; return false;
        } finally {
            long spent = budget.threadWork() - before;
            work += spent;
            if (repairVisit || repairing) alternativeRemaining -= spent;
        }
    }

    private Bin currentBin() { return bins.get((bin + attempt) % bins.size()); }
    private void beginCenteredNeighborhood() {
        centeredTried = true;
        neighborhood = CountVectorNeighborhood.centered(projected, binLower, binUpper, budget, 32768);
        // A checked prefix followed by a newly admitted residual repair is
        // distinct from more slices of the same failed first allocation.
        // Depth is monotone across retries, so reordering cannot renew service.
        if (neighborhood != null) interiorCompletionDepth = Math.max(interiorCompletionDepth, bin);
    }
    int interiorCompletionDepth() { return interiorCompletionDepth; }
    long remainingAllowance() { return complete ? 0 : Math.max(0, maximumWork - work); }
    private int groupAt(int i) { return order[(i + attempt / bins.size()) % order.length]; }
    private void beginBin() {
        projected = new ArrayList<>(); var target = currentBin();
        var lo = new BigInteger[order.length]; var hi = new BigInteger[order.length]; Arrays.fill(lo, BigInteger.ZERO);
        for (int i = 0; i < order.length; i++) { budget.check(); hi[i] = remaining[groupAt(i)]; }
        for (int d = 0; d < dimensions.size(); d++) for (int sign = 1; sign >= -1; sign -= 2) {
            var terms = new TreeMap<Integer, BigInteger>();
            for (int i = 0; i < order.length; i++) {
                budget.check(); var a = dimensions.get(d).get(groupAt(i)); terms.put(i, sign > 0 ? a : a.negate());
            }
            projected.add(new ExactLinearProgram.Constraint(terms, sign > 0 ? target.target[d] : target.target[d].negate()));
        }
        binLower = lo; binUpper = hi;
        neighborhoodTried = order.length <= 32;
        centeredTried = false;
        neighborhoodAt = work + (order.length > 48 ? 0 : 3_145_728);
        affine = new CountAffineLattice(projected, lo, hi, budget, 32768).retained();
        // Broad first allocations make the sphere tree too expensive. Smaller
        // first bins and stalled residuals may reuse their reduced basis;
        // inexpensive matching and the ordinary kernel keep their frontiers.
        if (bin > 0 || order.length <= FIRST_BIN_ENUMERATION_GROUPS) affine.enumerateBox();
        long estimate = bin == 0 ? 0 : CountMeetInMiddle.scoutWork(projected, lo, hi, budget);
        matchingTried = estimate <= 0 || estimate > maximumWork - work;
        matchingEntered = false;
        // Preserve cheap rounding/kernel successes before paying for a table.
        // Price that scout against the residual matcher, with bounded headroom:
        // an expensive table gives the lattice longer to avoid building it.
        // Parent handoffs never renew this one-time opportunity.
        // The deferred branch is enumerating different first allocations after
        // ordinary decompositions failed. An affordable exact residual check
        // avoids repeating the same affine warmup for every rejected candidate.
        matchingAt = work + (deferredActive ? 0 : Math.min(131072, Math.max(32768, estimate / 8)));
    }
    private void beginMatching() {
        matchingTried = true;
        // A completed bin may shrink the next domain by orders of magnitude.
        // Re-estimate the residual instead of blindly running another lattice
        // walk. The existing matcher owns its table across all handoffs.
        long estimate = CountMeetInMiddle.scoutWork(projected, binLower, binUpper, budget);
        if (estimate > 0 && estimate <= maximumWork - work) {
            matchingEntered = true;
            matching = new CountMeetInMiddle(projected, binLower, binUpper, budget, 32768).retained();
        }
    }
    private boolean childPaused() { return neighborhood != null ? neighborhood.paused() : matching == null ? affine.paused() : matching.paused(); }
    private void resumeChild(long quantum) {
        if (neighborhood != null) neighborhood.resume(quantum);
        else if (matching == null) affine.resume(quantum); else matching.resume(quantum);
    }
    private void retry() {
        if (alternatives != null) {
            if (!alternativesPriced) {
                // A cheap failed decomposition must not buy a large extra
                // search. Charge the whole branch, including residual checks,
                // against one small request-owned opportunity.
                alternativeRemaining = Math.min(alternativeRemaining, work / 2);
                alternativesPriced = true;
            }
            if (alternativeRemaining > 0 && alternatives.nextCandidate()) {
                restart();
                repairing = true;
                affine = alternatives; alternatives = null; matchingTried = true;
                return;
            }
            if (canDefer() && alternatives.nextCandidate()) deferred.addLast(new Deferred(alternatives, attempt, false));
            else { completedProgress += alternatives.progress(); alternatives.close(); }
            alternatives = null;
        }
        repairing = false;
        if (deferredActive) { resumeDeferred(); return; }
        if (++attempt >= 2 * bins.size()) {
            if (!deferred.isEmpty() && maximumWork - work >= 32768) {
                // All ordinary decompositions have failed. Continue the first
                // interrupted alternative search without rebuilding its basis
                // or consuming the allowance of an earlier successful path.
                deferredActive = true;
                alternativeRemaining = Math.min(1_048_576, maximumWork - work);
                resumeDeferred();
                return;
            }
            finish("decompositions_unresolved"); return;
        }
        restart();
    }
    private boolean canDefer() {
        // Retained bases already own their reservations. Keep ample headroom
        // for the ordinary paths and never retain another tail-search branch.
        return !deferredActive && deferred.size() < 2 * bins.size() &&
                budget.reservedBytes() <= budget.availableBytes() / 4;
    }
    private void deferAlternatives() {
        if (!canDefer()) return;
        if (bin == 0 && affine != null) {
            deferred.addLast(new Deferred(affine, attempt, false)); affine = null;
        } else if (alternatives != null) {
            deferred.addLast(new Deferred(alternatives, attempt, true)); alternatives = null;
        }
    }
    private void resumeDeferred() {
        while (!deferred.isEmpty() && alternativeRemaining > 0) {
            var pending = deferred.removeFirst();
            affine = pending.search;
            affine.widenAlternativeTargets();
            if (pending.next && !affine.nextCandidate()) {
                completedProgress += affine.progress(); affine.close(); affine = null;
                continue;
            }
            attempt = pending.attempt;
            restart(); repairing = true; matchingTried = true;
            budget.note("count_vector_deferred", "basis_resumed; attempt=" + attempt + "; allowance=" + alternativeRemaining);
            return;
        }
        finish("alternatives_unresolved");
    }
    private BigInteger residualKey() {
        // Encode the complete multiplicity. Include the actual destination and
        // original group coordinates, independent of a retry's column order.
        // Only exact matching exhaustion inserts keys; a numeric miss cannot.
        int width = wideCapacity ? 4 : 1;
        byte[] packed = new byte[width * remaining.length + 1];
        for (int g = 0; g < remaining.length; g++) {
            budget.check(); int value = remaining[g].intValueExact();
            for (int b = 0; b < width; b++) packed[width * g + b] = (byte) (value >>> (8 * b));
        }
        packed[packed.length - 1] = (byte) ((bin + attempt) % bins.size());
        return new BigInteger(1, packed);
    }
    private void restart() {
        centeredAllocation = false;
        candidate = lower.clone(); remaining = new BigInteger[groups.size()];
        for (int g = 0; g < groups.size(); g++) { budget.check(); remaining[g] = groups.get(g).capacity; }
        bin = 0; verifying = false; verified = 0;
        residualKey = null;
    }
    private void prepareCheck() {
        budget.check(); if (budget.threadWork() - prepareStarted > 32768) throw new Declined();
    }
    private void integer(BigInteger... values) {
        int bits = 1; for (var value : values) bits = Math.max(bits, value.bitLength());
        budget.operation(PlanningBudget.Operation.INTEGER, bits);
    }
    private static BigInteger floor(BigInteger a, BigInteger b) {
        var qr = a.divideAndRemainder(b); return a.signum() < 0 && qr[1].signum() != 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }
    private boolean finish(String reason) {
        complete = true; paused = false;
        budget.note("count_vector_partition", reason + "; groups=" + groups.size() + "; bins=" + bins.size() +
                "; completed_bins=" + bin + "; attempts=" + (attempt + 1) + "; work=" + work);
        return true;
    }
    @Override public boolean paused() { return paused; }
    @Override public void resume(long quantum) {
        until = CountContinuation.deadline(work, quantum, budget); paused = false;
        // Do not spend a one-unit owner visit repeatedly observing a paused
        // child. Renew its existing frontier before the next charged step.
        if (affine != null && childPaused()) resumeChild(Math.min(262144, until - work));
    }
    @Override public BigInteger[] counts() { return counts == null ? null : counts.clone(); }
    @Override public long work() { return work; }
    @Override public long progress() {
        long progress = completedProgress + (matching == null ? 0 : matching.progress()) +
                (affine == null ? 0 : affine.progress()) + (alternatives == null ? 0 : alternatives.progress());
        progress += neighborhood == null ? 0 : neighborhood.progress();
        for (var pending : deferred) progress += pending.search.progress();
        return progress;
    }
    @Override public boolean matches(List<ExactLinearProgram.Constraint> other, BigInteger[] low, BigInteger[] high) {
        return rows.equals(other) && Arrays.equals(lower, low) && Arrays.equals(upper, high);
    }
    @Override public void close() {
        clearChildren();
        while (!deferred.isEmpty()) { var child = deferred.removeFirst().search; completedProgress += child.progress(); child.close(); }
        rejectedResiduals.clear(); residualKey = null;
        budget.release(memory); memory = 0;
    }
    private void clearChildren() {
        completedProgress += (matching == null ? 0 : matching.progress()) + (affine == null ? 0 : affine.progress()) +
                (alternatives == null ? 0 : alternatives.progress());
        if (matching != null) matching.close(); matching = null;
        if (affine != null) affine.close(); affine = null;
        if (alternatives != null) alternatives.close(); alternatives = null;
        if (neighborhood != null) { completedProgress += neighborhood.progress(); neighborhood.close(); neighborhood = null; }
        projected = null; binLower = binUpper = null;
    }
}
