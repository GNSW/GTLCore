// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Small quantity subproblem portfolio. A stopped backend is never an infeasibility proof. */
final class CountQuickSolve implements AutoCloseable {

    private final PlanningBudget budget;
    private final List<ExactLinearProgram.Constraint> rows;
    private final int variables;
    private final boolean factor;
    private final long matchingAllowance;
    private CountBounds bounds;
    private CountReduction reduction;
    private CountPartition partition;
    private CountComponents components;
    private CountSeparator separator;
    private CountNetwork network;
    private CountMeetInMiddle matching;
    private CountBoolean binary;
    private CountDomainSearch domainSearch;
    private CountDecisionDiagram diagram;
    private ExactLinearProgram linear;
    private CountLatticeRepair repair;
    private CountDiving diving;
    private boolean divingAttempted;
    private CountDiophantine diophantine;
    private CountStructureSearch structural;
    private boolean structureAttempted;
    private CountCongruence congruence;
    private boolean congruenceAttempted;
    private CountLpSearch lpScout;
    private boolean lpAttempted, lpActive;
    private long matchingSinceLp;
    private boolean equationAttempted;
    private ExactRational[] point;
    private BigInteger[] counts;
    private int phase, attempts;
    private boolean complete, infeasible;
    private boolean trial;
    private long memory;
    private final List<CountConflict> learned = new ArrayList<>();

    /** Optional exact-demand faces; extra raw stock need not be spent. */
    static CountQuickSolve sourceFace(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                                      BigInteger[] upper, PlanningBudget budget, boolean upperFace) {
        if (lower.length > 512 || rows.size() > 1024) return null;
        Map<Map<Integer, BigInteger>, BigInteger> known = new HashMap<>();
        for (var row : rows) known.merge(row.terms(), row.upper(), BigInteger::min);
        List<ExactLinearProgram.Constraint> candidate = new ArrayList<>(rows);
        BitSet used = new BitSet();
        for (var row : rows) {
            budget.check();
            if (row.terms().size() < 2 || row.terms().size() > 8 || row.upper().signum() >= 0 ||
                    row.terms().entrySet().stream().anyMatch(e -> !e.getValue().equals(BigInteger.ONE.negate()) || used.get(e.getKey())) ||
                    row.terms().keySet().stream().noneMatch(id -> upper[id] != null && upper[id].subtract(lower[id]).compareTo(BigInteger.ONE) > 0))
                continue;
            Map<Integer, BigInteger> reverse = new LinkedHashMap<>();
            row.terms().keySet().forEach(id -> reverse.put(id, BigInteger.ONE));
            BigInteger existing = known.get(reverse), bound = row.upper().negate();
            if (existing != null && existing.compareTo(bound) <= 0) continue;
            if (upperFace) {
                if (existing == null) continue;
                candidate.add(new ExactLinearProgram.Constraint(row.terms(), existing.negate()));
            } else candidate.add(new ExactLinearProgram.Constraint(reverse, bound));
            row.terms().keySet().forEach(used::set);
        }
        if (candidate.size() == rows.size()) return null;
        var solving = new CountQuickSolve(candidate, lower, upper, budget, false, true, 65536);
        solving.trial = true;
        budget.note("count_source_face", "candidate_equalities=" + (candidate.size() - rows.size()) + "; upper=" + upperFace + "; original_domain_retained");
        return solving;
    }

    CountQuickSolve(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, PlanningBudget budget) {
        this(rows, lower, upper, budget, false);
    }

    CountQuickSolve(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, PlanningBudget budget, boolean saturate) {
        this(rows, lower, upper, budget, saturate, true);
    }

    CountQuickSolve(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, PlanningBudget budget, boolean saturate, boolean factor) {
        this(rows, lower, upper, budget, saturate, factor, 262144);
    }

    CountQuickSolve(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, PlanningBudget budget,
                    boolean saturate, boolean factor, long matchingAllowance) {
        this.factor = factor;
        this.matchingAllowance = matchingAllowance;
        this.budget = budget;
        variables = lower.length;
        this.rows = new ArrayList<>(rows);
        long bytes = 1024 + 384L * variables + 16L * rows.size();
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        for (int i = 0; i < variables; i++) {
            this.rows.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), lower[i].negate()));
            if (upper[i] != null) this.rows.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), upper[i]));
        }
        try {
            if (saturate) {
                BitSet taken = new BitSet();
                for (var row : rows) {
                    budget.check();
                    if (row.terms().size() < 2 || row.terms().size() > 8 || !row.upper().equals(BigInteger.ONE) ||
                            row.terms().entrySet().stream().anyMatch(e -> !e.getValue().equals(BigInteger.ONE) ||
                                    lower[e.getKey()].signum() != 0 || !BigInteger.ONE.equals(upper[e.getKey()]) || taken.get(e.getKey())))
                        continue;
                    Map<Integer, BigInteger> reverse = new LinkedHashMap<>();
                    row.terms().keySet().forEach(id -> {
                        reverse.put(id, BigInteger.ONE.negate());
                        taken.set(id);
                    });
                    this.rows.add(new ExactLinearProgram.Constraint(reverse, BigInteger.ONE.negate()));
                    trial = true;
                }
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    boolean step() {
        if (complete) return true;
        budget.check();
        if (phase == 0) {
            if (bounds == null) bounds = new CountBounds(variables, rows, budget);
            if (!bounds.step()) return false;
            if (bounds.blocked()) return finish(null, true);
            reduction = new CountReduction(rows, bounds.lowerBounds(), bounds.upperBounds(), budget);
            bounds.close();
            bounds = null;
            phase++;
        }
        if (phase == 1) {
            if (!reduction.step()) return false;
            if (!congruenceAttempted) {
                if (congruence == null) congruence = new CountCongruence(reduction.rows(), reduction.variables(), budget);
                if (!congruence.step()) return false;
                boolean impossible = congruence.infeasible();
                congruence.close();
                congruence = null;
                congruenceAttempted = true;
                if (impossible) return finish(null, true);
            }
            if (!lpAttempted) {
                if (lpScout == null) lpScout = CountLpSearch.create(reduction, variables, budget);
                if (lpScout != null) {
                    if (!lpScout.step()) return false;
                    var value = lpScout.counts();
                    boolean impossible = lpScout.infeasible();
                    if (!lpScout.retained()) {
                        lpScout.close();
                        lpScout = null;
                    }
                    // CountLpSearch already restores the original coordinates
                    // of this subproblem; do not expand through reduction twice.
                    if (value != null || impossible) {
                        counts = value;
                        infeasible = impossible && !trial;
                        return complete = true;
                    }
                }
                lpAttempted = true;
            }
            if (!equationAttempted) {
                if (diophantine == null) diophantine = new CountDiophantine(reduction.rows(), reduction.lower(), reduction.upper(), budget);
                if (!diophantine.step()) return false;
                var value = diophantine.counts();
                diophantine.close();
                diophantine = null;
                equationAttempted = true;
                if (value != null) return finish(value, false);
            }
            if (!structureAttempted) {
                // A quick witness attempt has a shared local quota. Leave room
                // for matching/Boolean search after the first structural visit.
                if (structural == null) structural = new CountStructureSearch(reduction.rows(), reduction.lower(), reduction.upper(), budget,
                        Math.min(65536, matchingAllowance / 4));
                if (!structural.step()) return false;
                var value = structural.counts();
                boolean impossible = structural.infeasible();
                structural.close();
                structural = null;
                structureAttempted = true;
                if (value != null || impossible) return finish(value, impossible);
            }
            if (factor) {
                if (components == null) components = new CountComponents(reduction.rows(), reduction.lower(), reduction.upper(), budget);
                if (!components.step()) return false;
                var value = components.counts();
                boolean impossible = components.infeasible();
                if (!trial) memory += CountMapping.retain(learned, CountMapping.representatives(reduction.representatives()).conflicts(components.learnedConflicts(), budget), budget);
                components.close();
                components = null;
                if (value != null || impossible) return finish(value, impossible);
            }
            separator = new CountSeparator(reduction.rows(), reduction.lower(), reduction.upper(), budget);
            phase = 8;
        }
        if (phase == 8) {
            if (!separator.step()) return false;
            var value = separator.counts();
            boolean impossible = separator.infeasible();
            separator.close();
            separator = null;
            if (value != null || impossible) return finish(value, impossible);
            network = new CountNetwork(reduction.rows(), reduction.lower(), reduction.upper(), budget);
            phase = 9;
        }
        if (phase == 9) {
            if (!network.step()) return false;
            var value = network.counts();
            boolean impossible = network.infeasible();
            network.close();
            network = null;
            if (value != null || impossible) return finish(value, impossible);
            partition = new CountPartition(reduction.rows(), reduction.lower(), reduction.upper(), budget);
            phase = 2;
        }
        if (phase == 2) {
            if (!partition.step()) return false;
            var value = partition.counts();
            partition.close();
            partition = null;
            if (value != null) return finish(value, false);
            boolean weighted = reduction.rows().stream().flatMap(row -> row.terms().values().stream()).anyMatch(v -> v.abs().compareTo(BigInteger.ONE) > 0);
            // A quick subproblem must leave room for propagation and simplex.
            // The full branch strategy retains its larger, resumable matching
            // table; this local cutoff establishes no infeasibility result.
            if (weighted) matching = new CountMeetInMiddle(reduction.rows(), reduction.lower(), reduction.upper(), budget, matchingAllowance);
            phase++;
        }
        if (phase == 3) {
            if (matching != null) {
                // Keep the small weighted LP alive alongside the finite table.
                // Scaling can expose either structure; table enumeration must
                // not silently discard an unfinished, reusable LP basis.
                if (lpScout != null && (lpActive || matchingSinceLp >= 65536)) {
                    if (!lpActive) {
                        lpScout.resume(32768);
                        lpActive = true;
                        matchingSinceLp = 0;
                    }
                    if (!lpScout.step()) return false;
                    var value = lpScout.counts();
                    boolean impossible = lpScout.infeasible();
                    if (!lpScout.retained()) {
                        lpScout.close();
                        lpScout = null;
                    }
                    lpActive = false;
                    if (value != null || impossible) {
                        counts = value;
                        infeasible = impossible && !trial;
                        return complete = true;
                    }
                    return false;
                }
                long before = budget.threadWork();
                boolean done;
                try { done = matching.step(); }
                finally { matchingSinceLp += budget.threadWork() - before; }
                if (!done) return false;
                var value = matching.counts();
                boolean impossible = matching.infeasible();
                matching.close();
                matching = null;
                if (value != null || impossible) return finish(value, impossible);
            }
            binary = new CountBoolean(reduction.rows(), reduction.lower(), reduction.upper(), budget);
            phase++;
        }
        if (phase == 4) {
            if (binary != null) {
                if (!binary.step()) return false;
                var value = binary.counts();
                boolean impossible = binary.infeasible();
                if (!trial) memory += CountMapping.retain(learned, CountMapping.representatives(reduction.representatives()).conflicts(binary.learnedConflicts(), budget), budget);
                binary.close();
                binary = null;
                if (value != null || impossible) return finish(value, impossible);
            }
            if ((reduction.variables() > 48 || reduction.rows().size() > 192) &&
                    !ExactRevisedProgram.extendsDenseAdmission(reduction.variables(), reduction.rows())) {
                phase = 7;
                return false;
            }
            BigInteger[] objective = new BigInteger[reduction.variables()];
            Arrays.fill(objective, BigInteger.ONE.negate());
            linear = new ExactLinearProgram(objective.length, reduction.rows(), objective, budget);
            phase++;
        }
        if (phase == 5) {
            if (!linear.step()) return false;
            var status = linear.result();
            point = linear.point();
            linear.close();
            linear = null;
            if (status == ExactLinearProgram.Result.INFEASIBLE) return finish(null, true);
            if (status != ExactLinearProgram.Result.OPTIMAL) {
                phase = 7;
                return false;
            }
            if (Arrays.stream(point).allMatch(ExactRational::integral))
                return finish(Arrays.stream(point).map(ExactRational::numerator).toArray(BigInteger[]::new), false);
            phase++;
        }
        if (phase == 7) {
            if (domainSearch == null) domainSearch = new CountDomainSearch(reduction.rows(), reduction.lower(), reduction.upper(), budget, 65536);
            if (!domainSearch.step()) return false;
            var value = domainSearch.counts();
            boolean impossible = domainSearch.infeasible();
            if (!trial) memory += CountMapping.retain(learned, CountMapping.representatives(reduction.representatives()).conflicts(domainSearch.learnedConflicts(), budget), budget);
            domainSearch.close();
            domainSearch = null;
            if (value != null || impossible) return finish(value, impossible);
            diagram = new CountDecisionDiagram(reduction.rows(), reduction.lower(), reduction.upper(), budget, 32768);
            phase = 10;
            return false;
        }
        if (phase == 10) {
            if (!diagram.step()) return false;
            var value = diagram.counts();
            boolean impossible = diagram.infeasible();
            diagram.close();
            diagram = null;
            return finish(value, impossible);
        }
        if (!divingAttempted) {
            if (diving == null) diving = new CountDiving(reduction.rows(), reduction.lower(), reduction.upper(), point, budget);
            if (!diving.step()) return false;
            var value = diving.counts();
            diving.close();
            diving = null;
            divingAttempted = true;
            if (value != null) return finish(value, false);
        }
        if (repair == null) repair = new CountLatticeRepair(reduction.rows(), reduction.lower(), reduction.upper(), point, attempts, budget);
        if (!repair.step()) return false;
        var value = repair.counts();
        repair.close();
        repair = null;
        if (value != null) return finish(value, false);
        if (++attempts >= 4) phase = 7;
        return false;
    }

    private boolean finish(BigInteger[] value, boolean impossible) {
        counts = value == null ? null : reduction.expand(value);
        infeasible = impossible && !trial;
        complete = true;
        return true;
    }

    BigInteger[] counts() {
        return counts;
    }

    long finiteContinuationWork() {
        long estimate = matching == null ? 0 : matching.continuationWork();
        // LP gets one short turn per two matching turns while both remain live.
        return lpScout == null ? estimate : estimate + estimate / 2;
    }

    boolean finiteProbing() {
        return matching != null && matching.probing();
    }

    boolean infeasible() {
        return infeasible;
    }

    List<CountConflict> learnedConflicts() {
        return List.copyOf(learned);
    }

    @Override
    public void close() {
        if (structural != null) structural.close();
        structural = null;
        if (diophantine != null) diophantine.close();
        if (bounds != null) bounds.close();
        if (reduction != null) reduction.close();
        if (partition != null) partition.close();
        if (components != null) components.close();
        if (separator != null) separator.close();
        if (network != null) network.close();
        if (matching != null) matching.close();
        if (binary != null) binary.close();
        if (domainSearch != null) domainSearch.close();
        if (diagram != null) diagram.close();
        if (linear != null) linear.close();
        if (repair != null) repair.close();
        if (diving != null) diving.close();
        if (congruence != null) congruence.close();
        if (lpScout != null) lpScout.close();
        lpScout = null;
        budget.release(memory);
        memory = 0;
    }
}
