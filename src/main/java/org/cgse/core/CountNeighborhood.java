package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Relaxation-enforced neighborhoods, following SCIP's RENS heuristic. Integral
 * LP coordinates are initially fixed, fractional ones get floor/ceil domains.
 * Later neighborhoods release connected coordinates. Only verified witnesses
 * escape; failure of a restricted neighborhood proves nothing about the order.
 */
final class CountNeighborhood implements AutoCloseable {

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final ExactRational[] point;
    private final PlanningBudget budget;
    private final List<Integer> release = new ArrayList<>();
    private final List<Integer> relax = new ArrayList<>();
    private final long allowance;
    private CountCdcl search;
    private CountLcg relaxedSearch;
    private CountFeasibilityPump pump;
    private CountDomainSearch incumbentSearch;
    private CountSoftSearch softSearch;
    private BigInteger[] incumbent, operationCosts;
    private int incumbentAttempt;
    private long memory, work;
    private int attempt;
    private int relaxedAttempt;
    private BigInteger[] counts;
    private boolean complete;
    private boolean allowPump = true;
    private boolean wideDomains;
    private CountPortfolioPolicy policy;
    private CountPortfolioPolicy.Arm binaryArm, relaxedArm, pumpArm, activeArm;
    private long sliceWork, sliceLimit, sliceProgress;
    private BitSet failureRegion;
    private final List<Integer> repairMoves = new ArrayList<>();
    private int repairMove, repairCandidates;
    private CountLcg failureSearch;

    /** Candidate-only neighborhoods of a failed executable ordering, in the caller's coordinates. */
    CountNeighborhood failureRegion(BitSet region) {
        if (complete) return this;
        long before = budget.threadWork();
        try {
            failureRegion = (BitSet) region.clone();
            failureRegion.clear(lower.length, Math.max(lower.length, failureRegion.length()));
            // New seed suppliers first, then decreasing/increasing used sources.
            for (int pass = 0; pass < 2; pass++) for (int i = failureRegion.nextSetBit(0); i >= 0; i = failureRegion.nextSetBit(i + 1)) {
                budget.check();
                if (!point[i].integral()) throw new IllegalArgumentException("Nonintegral failed schedule");
                BigInteger value = point[i].numerator();
                if ((value.signum() == 0) != (pass == 0)) continue;
                if (value.compareTo(lower[i]) > 0) repairMoves.add(-i - 1);
                if (upper[i] == null || value.compareTo(upper[i]) < 0) repairMoves.add(i + 1);
            }
            allowPump = false;
            return this;
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        } finally {
            work += budget.threadWork() - before;
        }
    }

    /** The proposed count vector failed downstream; keep the other local moves available. */
    void rejectFailureCandidate() {
        if (failureRegion == null || counts == null) throw new IllegalStateException("No failure-region candidate");
        counts = null;
        complete = false;
    }

    private void initializePortfolio() {
        policy = new CountPortfolioPolicy();
        long setup = lower.length + (long) rows.size();
        binaryArm = policy.add(setup);
        if (wideDomains) relaxedArm = policy.add(setup);
        if (allowPump) pumpArm = policy.add(setup + lower.length);
    }

    CountNeighborhood pump(boolean enabled) {
        allowPump = enabled;
        return this;
    }

    CountNeighborhood incumbent(BigInteger[] values, BigInteger[] costs) {
        if (values != null && values.length == lower.length) {
            incumbent = values.clone();
            operationCosts = costs.clone();
        }
        return this;
    }

    CountNeighborhood(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                      ExactRational[] point, PlanningBudget budget) {
        this.rows = rows;
        this.lower = lower;
        this.upper = upper;
        this.point = point;
        this.budget = budget;
        allowance = Math.min(131_072, budget.remainingWork() / 16);
        if (point == null || lower.length > 256 || rows.size() > 1024 || allowance < 4096) {
            complete = true;
            return;
        }
        // Includes the three optional scheduling arms and their retained observations.
        long bytes = 2048 + 384L * lower.length;
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        long before = budget.threadWork();
        try {
            int[] connections = new int[lower.length];
            for (var row : rows) {
                boolean fractional = false;
                for (int id : row.terms().keySet()) {
                    if (!initializeStep(before)) return;
                    fractional |= !point[id].integral();
                }
                for (int id : row.terms().keySet()) {
                    if (!initializeStep(before)) return;
                    connections[id] += fractional ? 4 : 1;
                }
            }
            for (int i = 0; i < point.length; i++) {
                if (!initializeStep(before)) return;
                if (lower[i].equals(upper[i])) continue;
                relax.add(i);
                wideDomains |= upper[i] == null || upper[i].subtract(lower[i]).compareTo(BigInteger.ONE) > 0;
                if (point[i].integral()) release.add(i);
            }
            release.sort(Comparator.<Integer>comparingInt(i -> -connections[i]).thenComparingInt(i -> i));
            relax.sort(Comparator.<Integer>comparingInt(i -> -connections[i])
                    .thenComparingInt(i -> point[i].integral() ? 1 : 0).thenComparingInt(i -> i));
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private boolean initializeStep(long started) {
        // Building a dense neighborhood is part of its local allowance, not
        // free preprocessing before the first cooperative search step.
        if (budget.threadWork() - started >= allowance) {
            complete = true;
            return false;
        }
        budget.check();
        return true;
    }

    boolean step() {
        if (complete) return true;
        boolean completedStep = false;
        long before = budget.threadWork();
        try {
            budget.check();
            if (work >= allowance) return finish("neighborhoods_unresolved");
            if (failureRegion != null) return repairFailure();
            if (incumbent != null && incumbentAttempt < 3) return improveIncumbent();
            if (policy == null) initializePortfolio();
            if (activeArm == null) {
                activeArm = policy.select();
                if (activeArm == null) return finish("neighborhoods_unresolved");
                policy.selected(activeArm);
                sliceWork = sliceProgress = 0;
                sliceLimit = policy.quantum(activeArm, allowance - work);
            }
            if (activeArm == binaryArm) stepBinary();
            else if (activeArm == relaxedArm) relaxDomains();
            else stepPump();
            completedStep = true;
            return complete;
        } finally {
            long spent = budget.threadWork() - before;
            work += spent;
            if (activeArm != null) {
                sliceWork += spent;
                if ((completedStep || complete) && (complete || activeArm.retired || sliceWork >= sliceLimit)) {
                    // Only a completed local observation is scored. A proved
                    // restricted neighborhood improves generator feedback; it
                    // is never a contradiction of the original count model.
                    policy.feedback(activeArm, sliceWork, sliceProgress);
                    activeArm = null;
                }
            }
        }
    }

    private boolean repairFailure() {
        if (failureSearch == null) {
            long started = budget.threadWork();
            if (repairMove >= Math.min(16, repairMoves.size()) || repairCandidates >= 4 || allowance - work < 1024)
                return finish("failure_region_unresolved; moves=" + repairMove);
            BigInteger[] low = lower.clone(), high = upper.clone();
            BitSet fixed = new BitSet(low.length);
            for (int i = 0; i < low.length; i++) {
                budget.check();
                if (point[i].numerator().compareTo(low[i]) < 0 || high[i] != null && point[i].numerator().compareTo(high[i]) > 0)
                    return finish("point_outside_domain");
                if (!failureRegion.get(i)) {
                    low[i] = high[i] = point[i].numerator();
                    fixed.set(i);
                }
            }
            int move = repairMoves.get(repairMove++), id = Math.abs(move) - 1;
            // This speculative bound guarantees a DIFFERENT candidate, without
            // learning an exclusion for the old vector or the original request.
            if (move > 0) low[id] = point[id].numerator().add(BigInteger.ONE);
            else high[id] = point[id].numerator().subtract(BigInteger.ONE);
            int released = CountRepairDomains.widen(rows, lower, upper, low, high, fixed, budget,
                    Math.min(8192, Math.max(0, allowance - work) / 8));
            if (released != 0) budget.note("count_repair_domains", "released=" + released + "; scope=restricted_candidate");
            long remaining = allowance - work - (budget.threadWork() - started);
            if (remaining < 1024) return finish("failure_region_repair_limit");
            failureSearch = new CountLcg(rows, low, high, budget, Math.min(8192, remaining));
        }
        if (!failureSearch.step()) return false;
        counts = failureSearch.counts();
        failureSearch.close();
        failureSearch = null;
        if (counts != null) {
            repairCandidates++;
            return finish("failure_region_candidate; released=" + failureRegion.cardinality() + "; move=" + repairMove);
        }
        // A restricted UNSAT or cutoff has no negative meaning outside this move.
        return false;
    }

    private void stepBinary() {
        if (search == null) {
            BigInteger[] low = lower.clone(), high = upper.clone();
            Set<Integer> free = new HashSet<>(release.subList(0, Math.min(release.size(), attempt * 8)));
            for (int i = 0; i < low.length; i++) {
                budget.check();
                BigInteger l = point[i].floor(), h = point[i].ceil();
                if (point[i].integral() && free.contains(i)) {
                    boolean canUp = upper[i] == null || h.compareTo(upper[i]) < 0;
                    boolean canDown = l.compareTo(lower[i]) > 0;
                    if (canUp && (!canDown || attempt % 2 == 1)) h = h.add(BigInteger.ONE);
                    else if (canDown) l = l.subtract(BigInteger.ONE);
                }
                low[i] = l.max(lower[i]);
                high[i] = upper[i] == null ? h : h.min(upper[i]);
                if (high[i].compareTo(low[i]) < 0) {
                    finish("point_outside_domain");
                    return;
                }
            }
            search = new CountCdcl(rows, low, high, budget, Math.min(32768, allowance - work));
        }
        if (!search.step()) return;
        counts = search.counts();
        if (counts != null || search.infeasible()) sliceProgress++;
        search.close();
        search = null;
        attempt++;
        binaryArm.retired = attempt == 4;
        if (counts != null) finish("verified_witness");
    }

    private void stepPump() {
        if (!allowPump) {
            pumpArm.retired = true;
            return;
        }
        if (pump == null) pump = new CountFeasibilityPump(rows, lower, upper, point, budget, Math.min(32768, allowance - work));
        if (!pump.step()) return;
        counts = pump.counts();
        pump.close();
        pump = null;
        pumpArm.retired = true;
        if (counts != null) {
            sliceProgress++;
            finish("verified_pump_witness");
        }
    }

    private boolean relaxDomains() {
        // Floor/ceil boxes can contain NO integer solution even when one is
        // only a few steps away. This retained portfolio member releases
        // selected connected coordinates to their actual domains.
        // This includes fractional coordinates, not only integral LP values.
        // Both trials share this heuristic's remaining work and memory budget.
        if (relaxedAttempt >= 2 || allowance - work < 1024 || !wideDomains) {
            relaxedArm.retired = true;
            return false;
        }
        if (relaxedSearch == null) {
            BigInteger[] low = lower.clone(), high = upper.clone();
            BitSet free = new BitSet(low.length);
            int size = Math.min(relax.size(), relaxedAttempt == 0 ? 8 : 32);
            for (int i = 0; i < size; i++) free.set(relax.get(i));
            for (int i = 0; i < low.length; i++) {
                budget.check();
                if (free.get(i)) continue;
                low[i] = lower[i].max(point[i].floor());
                high[i] = upper[i] == null ? point[i].ceil() : upper[i].min(point[i].ceil());
                if (high[i].compareTo(low[i]) < 0) return finish("point_outside_domain");
            }
            relaxedSearch = new CountLcg(rows, low, high, budget, Math.min(16384, allowance - work));
        }
        if (!relaxedSearch.step()) return false;
        counts = relaxedSearch.counts();
        if (counts != null || relaxedSearch.infeasible()) sliceProgress++;
        if (counts != null) return finish("verified_relaxed_neighborhood; stage=" + relaxedAttempt);
        // Reuse a paused full-domain search instead of reconstructing the same
        // small neighborhood. A larger neighborhood needs its own fresh state.
        if (relaxedAttempt++ == 0 && relax.size() <= 8 && relaxedSearch.paused() && allowance - work >= 2048) {
            relaxedSearch.resume(Math.min(16384, allowance - work));
            return false;
        }
        relaxedSearch.close();
        relaxedSearch = null;
        relaxedArm.retired = relaxedAttempt >= 2;
        // Failure concerns only this neighborhood. No contradiction escapes.
        return false;
    }

    private boolean improveIncumbent() {
        if (incumbentSearch == null && softSearch == null) {
            var constraints = new ArrayList<>(rows);
            var objective = new LinkedHashMap<Integer, BigInteger>();
            BigInteger old = BigInteger.ZERO;
            BigInteger[] low = lower.clone(), high = upper.clone();
            Set<Integer> free = new HashSet<>(release.subList(0, Math.min(8, release.size())));
            for (int i = 0; i < low.length; i++) {
                budget.check();
                if (operationCosts[i].signum() != 0) objective.put(i, operationCosts[i]);
                old = old.add(operationCosts[i].multiply(incumbent[i]));
                // Open-ended counts get a local, explicitly speculative domain.
                if (high[i] == null) high[i] = low[i].max(incumbent[i]).add(BigInteger.valueOf(32));
                boolean fix = incumbentAttempt == 0 ? point[i].integral() && point[i].numerator().equals(incumbent[i]) : incumbentAttempt == 1 && !free.contains(i);
                if (fix && incumbent[i].compareTo(low[i]) >= 0 && incumbent[i].compareTo(high[i]) <= 0) low[i] = high[i] = incumbent[i];
            }
            constraints.add(new ExactLinearProgram.Constraint(objective, old.subtract(BigInteger.ONE)));
            if (incumbentAttempt < 2) incumbentSearch = new CountDomainSearch(constraints, low, high, budget, Math.min(16384, allowance - work));
            else {
                List<CountSoftSearch.Soft> preferences = new ArrayList<>();
                for (int i = 0; i < low.length; i++) {
                    preferences.add(new CountSoftSearch.Soft(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), incumbent[i]), BigInteger.ONE));
                    preferences.add(new CountSoftSearch.Soft(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), incumbent[i].negate()), BigInteger.ONE));
                }
                softSearch = new CountSoftSearch(constraints, low, high, preferences, budget, Math.min(32768, allowance - work));
            }
        }
        if (incumbentSearch != null) {
            if (!incumbentSearch.step()) return false;
            counts = incumbentSearch.counts();
            incumbentSearch.close();
            incumbentSearch = null;
        } else {
            if (!softSearch.step()) return false;
            counts = softSearch.counts();
            softSearch.close();
            softSearch = null;
        }
        incumbentAttempt++;
        if (counts != null) return finish("verified_incumbent_neighborhood; stage=" + incumbentAttempt);
        // UNSAT here concerns only a RINS/LNS/soft neighborhood, never the order.
        return false;
    }

    private boolean finish(String detail) {
        complete = true;
        budget.note("count_neighborhood", detail + "; attempts=" + attempt + "; work=" + work + "; original_domain_retained");
        return true;
    }

    BigInteger[] counts() {
        return counts == null ? null : counts.clone();
    }

    @Override
    public void close() {
        if (failureSearch != null) failureSearch.close();
        failureSearch = null;
        if (incumbentSearch != null) incumbentSearch.close();
        if (softSearch != null) softSearch.close();
        incumbentSearch = null;
        softSearch = null;
        if (search != null) search.close();
        if (relaxedSearch != null) relaxedSearch.close();
        relaxedSearch = null;
        if (pump != null) pump.close();
        pump = null;
        search = null;
        budget.release(memory);
        memory = 0;
    }
}
