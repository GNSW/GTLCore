// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One owned, resumable subtree. No mutable solver state is shared between workers. */
final class IntegerCountBranch<K> implements AutoCloseable {

    enum State {
        OPEN,
        SPLIT,
        DEAD,
        UNRESOLVED,
        FOUND,
        PRUNED
    }

    final RecipeCountModel<K> model;
    final CountExecution<K> execution;
    final K target;
    final long amount, started;
    final Map<K, Long> stock, seeds;
    final Set<K> external;
    final boolean preserve, force;
    boolean preprocessingOnly;
    final PlanningBudget budget;
    final List<ExactLinearProgram.Constraint> current;
    final List<List<ExactLinearProgram.Constraint>> children = new ArrayList<>();
    final Set<ExactLinearProgram.Constraint> learnedMaterials = new LinkedHashSet<>();
    final Set<ExactLinearProgram.Constraint> usedMaterials = new LinkedHashSet<>();
    final List<CountGuard> supportConflicts = new ArrayList<>();
    final Set<CountConflict> learnedChoices = new LinkedHashSet<>();
    private List<CountConflict> knownChoices = List.of();
    private List<ExactLinearProgram.Constraint> knownMaterials = List.of();
    List<ExactLinearProgram.Constraint> linearConstraints;
    CountBounds propagating;
    CountBounds.Seed inheritedBounds, sharedBounds;
    CountReduction reduction;
    boolean compiled, reducedLinear;
    CountPartition partition;
    CountComponents components;
    CountSeparator separator;
    CountNetwork network;
    BigInteger[] separatorPartial;
    boolean separatorCandidate;
    boolean structuralCandidate;
    CountGroups groups;
    CountConditioning conditioning;
    CountRecovery<K> recovery;
    CountScale scaling;
    CountScale parkedScaling;
    boolean scalingResuming, preferScaleResume = true;
    boolean splitWitness;
    CountDiophantine diophantine;
    CountStructureSearch structural;
    CountStructureSearch parkedStructural;
    boolean structureScouted, structureEarly, structureResuming, preferStructureResume;
    CountCongruence congruence;
    CountPacking packing;
    CountJump jumping;
    CountProbing probing;
    boolean probed, jumpCandidate, jumpLate;
    CountCoverCuts covering;
    CountCliques cliques;
    CountRoundingCuts rounding;
    boolean roundingTried;
    CountZeroHalfCuts zeroHalf;
    ExactRational[] coverPoint, coverFallback;
    final List<ExactLinearProgram.Constraint> relaxationCuts = new ArrayList<>();
    int coverRounds;
    CountQuickSolve sourceFace;
    long sourceFaceWork;
    int sourceFaceAttempt;
    boolean compileRecovery = true;
    CountMeetInMiddle matching;
    boolean matchingScout, matchingScouted;
    CountRepairPortfolio repair;
    ExactRational[] repairPoint;
    ExactRational[] uncutRepairPoint;
    int repairAttempts;
    long repairWork;
    CountBoolean binary;
    boolean earlyBinary, triedBinary;
    CountDomainSearch cdcl;
    CountModelViews modelViews;
    CountViewSearch viewSearch;
    CountLpSearch lpSearch;
    boolean lpTried, lpActive, lpResuming, preferLpResume = true;
    int viewStage, viewCandidateStage;
    CountLcg auxiliaryLcg;
    int auxiliaryMode;
    long auxiliaryWork;
    long auxiliaryUntil = 262144;
    boolean auxiliaryCompared, auxiliaryLive;
    boolean proofTask, proofContradiction;
    CountDecisionDiagram diagram;
    CountObbt obbt;
    boolean gomoryTried;
    Boolean feedbackRegion;
    boolean obbtTried;
    ExactRational[] obbtPoint;
    boolean triedCdcl, domainCandidate;
    CountNeighborhood neighborhood;
    CountNeighborhood failureNeighborhood;
    boolean failureNeighborhoodTried, failureCandidate;
    BigInteger[] incumbentCounts;
    boolean incumbentTried;
    ExactRational[] neighborhoodPoint;
    boolean neighborhoodCandidate;
    CountBranchProbe branchProbe;
    CountBranchHistory branchHistory;
    ExactRational[] branchProbePoint;
    ExactLinearProgram linear;
    ExactLinearProgram.Basis inheritedBasis, sharedBasis;
    boolean inheritedBasisOriginal, sharedBasisOriginal;
    CountReduction.Coordinates inheritedCoordinates;
    CountSchedule<K> scheduling;
    private CountSchedule<K> witnessSchedule;
    private CountScheduleContinuations<K> scheduleContinuations;
    private boolean sharedSchedules;
    private PlanPreference<K> feedbackIncumbent;
    private CountPortfolioPolicy.Mode portfolioMode = CountPortfolioPolicy.Mode.FIRST_WITNESS;
    long commonCompilationWork;
    CountSupportSearch<K> supportSearch;
    CountProgram<K> program;
    AllocationSearch.Candidate<K> assembling;
    PlanVerification<K> verifying;
    BigInteger[] counts, lower, upper;
    GraphPlan<K> plan;
    PlanPreference<K> preference, lowerCost;
    State state = State.OPEN;
    PlanningBudget.Exhausted limit;
    boolean initialized, partitioned, rescue, retried, choicePruned, needsRepair;
    private boolean candidateOnly;
    private CountViewSearch.CandidateOrigin candidateOrigin;
    private long candidateStarted, runStarted, observedWork;
    private boolean measuringRun;
    long memory, workspace, work, schedulingWork;
    int globalRows, checkedChoices;
    final Set<CountConflict> usedChoices = new LinkedHashSet<>();

    IntegerCountBranch(RecipeCountModel<K> model, CountExecution<K> execution, K target, long amount, Map<K, Long> stock,
                       Map<K, Long> seeds, Set<K> external, boolean preserve, boolean force,
                       PlanningBudget budget, long started, List<ExactLinearProgram.Constraint> current) {
        this.model = model;
        this.execution = execution;
        this.target = target;
        this.amount = amount;
        this.stock = stock;
        this.seeds = seeds;
        this.external = external;
        this.preserve = preserve;
        this.force = force;
        preprocessingOnly = model.recipes.size() > 64 || model.keys.size() > 96;
        this.budget = budget;
        scheduleContinuations = new CountScheduleContinuations<>(model, budget);
        this.started = started;
        this.current = List.copyOf(current);
        long bytes = 512L + current.stream().mapToLong(row -> 192L + 48L * row.terms().size()).sum();
        if (budget.tryReserve(bytes)) memory = bytes;
        else state = State.UNRESOLVED;
    }

    void run(long quantum, List<ExactLinearProgram.Constraint> materials, List<CountGuard> support, List<CountConflict> choices,
             PlanPreference<K> incumbent, BooleanSupplier stopped) {
        run(quantum, materials, support, choices, incumbent, incumbent == null ? CountPortfolioPolicy.Mode.FIRST_WITNESS : CountPortfolioPolicy.Mode.IMPROVEMENT, stopped);
    }

    void proveRoot(long allowance) {
        if (initialized || !current.isEmpty() || allowance <= 0) throw new IllegalArgumentException("Invalid root proof task");
        proofTask = true;
        auxiliaryMode = 3;
        auxiliaryUntil = allowance;
        partitioned = true;
    }

    void run(long quantum, List<ExactLinearProgram.Constraint> materials, List<CountGuard> support, List<CountConflict> choices,
             PlanPreference<K> incumbent, CountPortfolioPolicy.Mode goal, BooleanSupplier stopped) {
        Objects.requireNonNull(goal);
        if (goal == CountPortfolioPolicy.Mode.IMPROVEMENT && incumbent == null)
            throw new IllegalArgumentException("Plan improvement requires an executable incumbent");
        long before = budget.threadWork();
        runStarted = before;
        measuringRun = true;
        try {
            if (state != State.OPEN) return;
            if (proofTask && incumbent != null) {
                // The original-domain feasibility question is already settled.
                // Preserve witnesses completed by other members of the wave.
                state = State.PRUNED;
                return;
            }
            feedbackIncumbent = incumbent;
            // Learned clauses describe pruning progress, not a change in what
            // this task is seeking. Only the caller's explicit goal may select
            // proof-only feedback; ordinary orders still seek their first plan.
            portfolioMode = goal;
            if (viewSearch != null) viewSearch.mode(portfolioMode);
            scheduleContinuations.trim();
            if (!knownChoices.equals(choices)) checkedChoices = 0;
            knownChoices = choices;
            knownMaterials = materials;
            if (!initialized && rejectsChoices()) {
                choicePruned = true;
                state = State.DEAD;
                return;
            }
            if (workspace == 0) {
                if (!budget.tryReserve(2L << 20)) {
                    state = State.UNRESOLVED;
                    return;
                }
                workspace = 2L << 20;
            }
            if (!initialized) {
                initialized = true;
                supportConflicts.addAll(support);
                linearConstraints = new ArrayList<>(model.constraints);
                for (int i = 0; i < model.recipes.size(); i++) {
                    var recipe = model.recipes.get(i);
                    budget.check();
                    // A deterministic identity firing leaves the marking
                    // unchanged. Delete it from any witness without changing
                    // the enabledness of the surrounding program. Canonicalize
                    // its count to zero so no-good partitioning cannot spend
                    // branches adding arbitrarily many useless firings.
                    if (recipe.configurationInputs().isEmpty() && recipe.reusableInputs().isEmpty() &&
                            recipe.inputs().equals(recipe.outputs()))
                        linearConstraints.add(bound(i, BigInteger.ZERO, false));
                }
                linearConstraints.addAll(materials);
                globalRows = linearConstraints.size();
                linearConstraints.addAll(current);
                propagating = new CountBounds(model.recipes.size(), linearConstraints, budget, globalRows, inheritedBounds);
                if (inheritedBounds != null) {
                    inheritedBounds.close();
                    inheritedBounds = null;
                }
            }
            if (propagating != null) propagating.learn(choices);
            do {
                if (stopped.getAsBoolean()) return;
                if (auxiliaryMode != 0 && auxiliaryWork + budget.threadWork() - before >= auxiliaryUntil) {
                    state = State.UNRESOLVED;
                    return;
                }
                budget.check();
                if (incumbent != null && lowerCost != null && lowerCost.cannotImprove(incumbent)) {
                    state = State.PRUNED;
                    return;
                }
                long scheduleBefore = budget.threadWork();
                boolean inSchedule = scheduling != null;
                if (budget.metricsEnabled()) {
                    String strategy = activeStrategy();
                    long start = System.nanoTime();
                    try {
                        advance(before, quantum, stopped);
                    } finally {
                        budget.strategy(strategy, budget.threadWork() - scheduleBefore, System.nanoTime() - start);
                    }
                } else advance(before, quantum, stopped);
                if (inSchedule) schedulingWork += budget.threadWork() - scheduleBefore;
                if (scheduling != null && speculativeCandidate() && schedulingWork >= 8192) {
                    rejectSpeculativeCandidate(true);
                    return;
                }
                if (scheduling != null && !partitioned && schedulingWork >= 8192) {
                    // Keep this exact scheduling continuation, while other counts
                    // get disjoint sibling subspaces and their own work slices.
                    partitionCounts();
                    return;
                }
            } while (state == State.OPEN && budget.threadWork() - before < quantum);
        } catch (ExactRational.PrecisionLimit precision) {
            state = State.UNRESOLVED;
        } catch (PlanningBudget.Exhausted exhausted) {
            limit = exhausted;
            state = State.UNRESOLVED;
        } finally {
            long spent = budget.threadWork() - before;
            work += spent;
            observedWork += spent;
            measuringRun = false;
            if (auxiliaryMode != 0) auxiliaryWork += spent;
        }
    }

    private void advance(long runStart, long quantum, BooleanSupplier stopped) {
        if (upper != null) while (checkedChoices < knownChoices.size()) {
            var conflict = knownChoices.get(checkedChoices++);
            if (conflict.impliedBy(lower, upper, budget)) {
                usedChoices.add(conflict);
                choicePruned = true;
                state = State.DEAD;
                return;
            }
        }
        if (verifying != null) {
            if (!verifying.step()) return;
            for (BigInteger initial : plan.initialExact().values()) {
                budget.check();
                if (initial.compareTo(ExactAmounts.LONG_MAX) > 0) {
                    if (retryScheduleOrder()) return;
                    candidateFeedback(CountViewSearch.CandidateOutcome.REJECTED);
                    unresolved();
                    return;
                }
            }
            preference = PlanPreference.of(plan, assembling.summary,
                    Arrays.stream(counts).reduce(BigInteger.ZERO, BigInteger::add));
            if (lowerCost != null && lowerCost.cannotImprove(preference)) {
                partitioned = true;
                budget.note("count_cost_bound", "witness_attains_componentwise_lower_bound; no_improvement_branches");
            } else partitionCounts();
            if (witnessSchedule != null) {
                scheduleContinuations.remember(counts, witnessSchedule.witness());
                witnessSchedule.close();
                witnessSchedule = null;
            }
            candidateFeedback(CountViewSearch.CandidateOutcome.VERIFIED);
            state = State.FOUND;
            return;
        }
        if (assembling != null) {
            if (!assembling.step()) return;
            plan = assembling.plan;
            if (plan == null) {
                if (retryScheduleOrder()) return;
                candidateFeedback(CountViewSearch.CandidateOutcome.REJECTED);
                unresolved();
                return;
            }
            verifying = new PlanVerification<>(plan, budget);
            return;
        }
        if (program != null) {
            if (!program.step()) return;
            if (!program.available()) {
                state = State.UNRESOLVED;
                return;
            }
            beginAssembly(program.program());
            program.close();
            program = null;
            return;
        }
        if (scheduling != null) {
            if (!scheduling.step()) return;
            CountSchedule.Result status = scheduling.result();
            candidateFeedback(switch (status) {
                case WITNESS -> CountViewSearch.CandidateOutcome.SCHEDULE_WITNESS;
                case DEAD -> CountViewSearch.CandidateOutcome.SCHEDULE_DEAD;
                case UNKNOWN -> CountViewSearch.CandidateOutcome.SCHEDULE_UNKNOWN;
            });
            if (speculativeCandidate() && status != CountSchedule.Result.WITNESS) {
                // Failure to schedule a local-search candidate must leave the
                // original count domain and all the other strategies available.
                rejectSpeculativeCandidate();
                return;
            }
            if (status == CountSchedule.Result.WITNESS) {
                witnessSchedule = scheduling;
                beginAssembly(scheduling.witness());
            } else scheduling.close();
            scheduling = null;
            if (status == CountSchedule.Result.DEAD) {
                execution.refine(model);
                for (var proof : execution.proofs()) if (!supportConflicts.contains(proof.guard())) supportConflicts.add(proof.guard());
                // This fixed vector was exhausted or rejected by a checked
                // optimistic startup proof. Share the exact exclusion; an
                // UNKNOWN scheduling result must never create this clause.
                var fixed = new ArrayList<ExactLinearProgram.Constraint>();
                for (int i = 0; i < counts.length; i++) {
                    fixed.add(bound(i, counts[i], false));
                    if (counts[i].signum() > 0) fixed.add(bound(i, counts[i], true));
                }
                learnedChoices.add(new CountConflict(fixed));
                needsRepair = true;
                supportSearch = new CountSupportSearch<>(model, counts, budget);
            } else if (status == CountSchedule.Result.UNKNOWN) unresolved();
            return;
        }
        if (failureNeighborhood != null && !failureCandidate) {
            if (!failureNeighborhood.step()) return;
            counts = failureNeighborhood.counts();
            if (counts != null) {
                failureCandidate = true;
                beginScheduling();
            } else {
                failureNeighborhood.close();
                failureNeighborhood = null;
                resumeSpeculativeCandidate();
            }
            return;
        }
        if (supportSearch != null) {
            if (!supportSearch.step()) return;
            var status = supportSearch.result();
            if (status == CountSupportSearch.Result.WITNESS) {
                // This witness may use different counts than the dead candidate.
                // It is checked against the immutable order, not accepted as a
                // solution of this branch's incidental count assumptions.
                counts = supportSearch.counts();
                lowerCost = null;
                beginAssembly(supportSearch.witness());
            } else if (status == CountSupportSearch.Result.CLOSED) {
                var cut = supportSearch.cut();
                learnedMaterials.add(cut);
                // The checked closed set rules out the entire old support.
                // All remaining solutions introduce at least one outside source.
                enqueue(current, cut);
                partitioned = true;
                state = State.SPLIT;
            } else {
                partitionCounts();
                state = preprocessingOnly ? State.UNRESOLVED : State.DEAD;
            }
            supportSearch.close();
            supportSearch = null;
            return;
        }
        if (rescue) {
            program = new CountProgram<>(model.recipes, counts, budget);
            return;
        }
        if (lpActive) {
            if (!lpSearch.step()) return;
            lpSearch.publishCuts(modelViews, reduction);
            lpActive = false;
            counts = lpSearch.counts();
            boolean impossible = lpSearch.infeasible();
            if (!lpSearch.retained()) {
                lpSearch.close();
                lpSearch = null;
            }
            // The initial LP turn precedes registration of reduced views.
            // Return through the portfolio entrance before ordinary strategies;
            // a retained LP search must not hide those equivalent coordinates.
            int continuation = lpResuming ? 4 : 5;
            if (counts != null) {
                viewCandidateStage = continuation;
                beginScheduling();
            } else if (impossible) {
                learnedChoices.add(new CountConflict(current));
                proofContradiction = current.isEmpty();
                state = State.DEAD;
            } else afterViewSearch(continuation);
            return;
        }
        if (viewStage != 0) {
            if (!viewSearch.step()) return;
            int continuation = viewStage;
            viewStage = 0;
            counts = viewSearch.counts();
            if (counts != null) {
                candidateOrigin = viewSearch.candidateOrigin();
                candidateStarted = measuredWork();
                viewCandidateStage = continuation;
                beginScheduling();
            } else if (viewSearch.infeasible()) {
                learnedChoices.add(new CountConflict(current));
                proofContradiction = current.isEmpty();
                state = State.DEAD;
            } else afterViewSearch(continuation);
            return;
        }
        if (congruence != null) {
            if (!congruence.step()) return;
            boolean impossible = congruence.infeasible();
            congruence.close();
            congruence = null;
            if (impossible) {
                learnedChoices.add(new CountConflict(current));
                state = State.DEAD;
            } else diophantine = new CountDiophantine(reduction.rows(), reduction.lower(), reduction.upper(), budget).retained();
            return;
        }
        if (diophantine != null) {
            if (!diophantine.step()) return;
            counts = reduction.expand(diophantine.counts());
            if (viewSearch == null || !viewSearch.retain(reduction, diophantine)) diophantine.close();
            diophantine = null;
            if (counts != null) beginScheduling();
            else if (current.isEmpty() && !structureScouted) structural = new CountStructureSearch(reduction.rows(), reduction.lower(), reduction.upper(), budget, 4_500_000);
            else if (current.isEmpty()) afterStructure();
            else dispatchCompiledStrategies();
            return;
        }
        if (structural != null) {
            if (!structural.step()) return;
            counts = reduction.expand(structural.counts());
            boolean impossible = structural.infeasible();
            if (structural.paused()) {
                parkedStructural = structural;
                preferStructureResume = !structureResuming;
                budget.note("count_structure", "frontier_retained; same_rows_and_domains");
            } else structural.close();
            structural = null;
            if (counts != null) {
                structuralCandidate = true;
                beginScheduling();
            } else if (impossible) {
                learnedChoices.add(new CountConflict(current));
                // The retained structural frontier still covers this entire
                // count domain. Earlier unresolved children cannot invalidate
                // its completed root proof; guarded branches stay local.
                proofContradiction = current.isEmpty();
                state = State.DEAD;
            } else if (structureEarly) {
                // Retaining the early structural frontier must not skip the
                // remaining root strategies. Resume it only after those have
                // had their first turn, including the reduced-coordinate view.
                structureEarly = false;
                structureResuming = false;
                beginCompiledPortfolio();
            } else if (structureResuming) state = State.UNRESOLVED;
            else afterStructure();
            return;
        }
        if (sourceFace != null) {
            long before = budget.threadWork();
            boolean complete;
            try {
                complete = sourceFaceWork >= 262_144 || sourceFace.step();
            } finally {
                sourceFaceWork += budget.threadWork() - before;
            }
            if (!complete) return;
            counts = reduction.expand(sourceFace.counts());
            sourceFace.close();
            sourceFace = null;
            // A trial face cannot export its negative conclusions or conflicts.
            if (counts != null) beginScheduling();
            else {
                if (sourceFaceAttempt++ == 1) {
                    sourceFaceWork = 0;
                    sourceFace = CountQuickSolve.sourceFace(reduction.rows(), reduction.lower(), reduction.upper(), budget, true);
                }
                if (sourceFace == null) packing = new CountPacking(reduction.rows(), reduction.lower(), reduction.upper(), budget);
            }
            return;
        }
        if (packing != null) {
            if (!packing.step()) return;
            counts = reduction.expand(packing.counts());
            packing.close();
            packing = null;
            if (counts != null) beginScheduling();
            else jumping = new CountJump(reduction.rows(), reduction.lower(), reduction.upper(), budget, 32_768);
            return;
        }
        if (covering != null) {
            if (!covering.step()) return;
            var cuts = covering.cuts();
            covering.close();
            covering = null;
            if (cuts.isEmpty() && !roundingTried) {
                ExactRational[] local = reducedLinear ? Arrays.stream(reduction.representatives()).mapToObj(i -> coverPoint[i]).toArray(ExactRational[]::new) : coverPoint;
                cliques = new CountCliques(relaxationRows(), reducedLinear ? reduction.lower() : lower,
                        reducedLinear ? reduction.upper() : upper, local, budget);
            } else acceptCuts(cuts);
            return;
        }
        if (cliques != null) {
            if (!cliques.step()) return;
            var cuts = cliques.cuts();
            cliques.close();
            cliques = null;
            if (cuts.isEmpty() && !roundingTried) {
                roundingTried = true;
                ExactRational[] local = reducedLinear ? Arrays.stream(reduction.representatives()).mapToObj(i -> coverPoint[i]).toArray(ExactRational[]::new) : coverPoint;
                rounding = new CountRoundingCuts(relaxationRows(), reducedLinear ? reduction.lower() : lower, local, budget);
            } else acceptCuts(cuts);
            return;
        }
        if (rounding != null) {
            if (!rounding.step()) return;
            var cuts = rounding.cuts();
            rounding.close();
            rounding = null;
            acceptCuts(cuts);
            return;
        }
        if (zeroHalf != null) {
            if (!zeroHalf.step()) return;
            var cuts = zeroHalf.cuts();
            zeroHalf.close();
            zeroHalf = null;
            long bytes = cuts.stream().mapToLong(row -> 192L + 128L * row.terms().size()).sum();
            if (!cuts.isEmpty() && budget.tryReserve(bytes)) {
                workspace += bytes;
                linearConstraints.addAll(cuts);
                learnedMaterials.addAll(cuts);
            }
            beginReduction();
            return;
        }
        if (neighborhood != null) {
            if (!neighborhood.step()) return;
            counts = reduction.expand(neighborhood.counts());
            neighborhood.close();
            neighborhood = null;
            if (counts != null) {
                neighborhoodCandidate = true;
                beginScheduling();
            } else resumeSpeculativeCandidate();
            return;
        }
        if (branchProbe != null) {
            if (!branchProbe.step()) return;
            int chosen = reduction.representatives()[branchProbe.chosen()];
            branchProbe.close();
            branchProbe = null;
            splitPoint(branchProbePoint, chosen);
            branchProbePoint = null;
            return;
        }
        if (jumping != null) {
            if (!jumping.step()) return;
            counts = reduction.expand(jumping.counts());
            jumping.close();
            jumping = null;
            if (counts != null) {
                jumpCandidate = true;
                beginScheduling();
            } else afterJump();
            return;
        }
        if (probing != null) {
            if (!probing.step()) return;
            var cuts = probing.cuts();
            boolean impossible = probing.infeasible();
            importReducedConflicts(probing.learnedConflicts());
            probing.close();
            probing = null;
            if (impossible) {
                learnedChoices.add(new CountConflict(current));
                state = State.DEAD;
            } else if (!cuts.isEmpty() && budget.tryReserve(256L * cuts.size())) {
                workspace += 256L * cuts.size();
                var mapping = CountMapping.representatives(reduction.representatives());
                for (var cut : cuts) {
                    var row = mapping.row(cut, budget);
                    linearConstraints.add(row);
                    var term = row.terms().entrySet().iterator().next();
                    int id = term.getKey();
                    if (term.getValue().signum() < 0) lower[id] = lower[id].max(row.upper().negate());
                    else upper[id] = upper[id] == null ? row.upper() : upper[id].min(row.upper());
                }
                closeViews();
                if (parkedStructural != null) parkedStructural.close();
                parkedStructural = null;
                structureScouted = structureEarly = structureResuming = false;
                reduction.close();
                reduction = new CountReduction(linearConstraints, lower, upper, budget);
                compiled = false;
                triedBinary = false;
                triedCdcl = false;
            } else dispatchCompiledStrategies();
            return;
        }
        if (recovery != null) {
            if (!recovery.step()) return;
            var body = recovery.witness();
            recovery.close();
            recovery = null;
            if (body != null) {
                Map<String, BigInteger> used = PlanCountComputation.of(body);
                counts = model.recipes.stream().map(r -> used.getOrDefault(r.id(), BigInteger.ZERO)).toArray(BigInteger[]::new);
                beginAssembly(body);
            } else beginGroups();
            return;
        }
        if (scaling != null) {
            if (!scaling.step()) return;
            counts = reduction.expand(scaling.counts());
            if (scaling.paused()) parkedScaling = scaling;
            else scaling.close();
            scaling = null;
            int continuation = scalingResuming ? 4 : 7;
            if (counts != null) {
                viewCandidateStage = continuation;
                beginScheduling();
            } else afterViewSearch(continuation);
            return;
        }
        if (groups != null) {
            if (!groups.step()) return;
            counts = reduction.expand(groups.counts());
            boolean impossible = groups.infeasible();
            importReducedConflicts(groups.learnedConflicts());
            groups.close();
            groups = null;
            if (counts != null) {
                if (!preprocessingOnly && refineSupport()) state = State.SPLIT;
                else beginScheduling();
            } else if (impossible) {
                learnedChoices.add(new CountConflict(current));
                state = State.DEAD;
            } else components = new CountComponents(reduction.rows(), reduction.lower(), reduction.upper(), budget);
            return;
        }
        if (components != null) {
            if (!components.step()) return;
            counts = reduction.expand(components.counts());
            boolean impossible = components.infeasible();
            importReducedConflicts(components.learnedConflicts());
            BigInteger[] partial = components.partial();
            components.close();
            components = null;
            if (counts != null) {
                if (!preprocessingOnly && refineSupport()) state = State.SPLIT;
                else beginScheduling();
            } else if (impossible) {
                learnedChoices.add(new CountConflict(current));
                state = State.DEAD;
            } else {
                separatorPartial = partial;
                separator = new CountSeparator(reduction.rows(), reduction.lower(), reduction.upper(), budget).retained();
            }
            return;
        }
        if (separator != null) {
            if (!separator.step()) return;
            counts = reduction.expand(separator.counts());
            boolean impossible = separator.infeasible();
            if (viewSearch == null || !viewSearch.retain(reduction, separator)) separator.close();
            separator = null;
            if (counts != null) {
                separatorCandidate = true;
                beginScheduling();
            } else if (impossible) {
                learnedChoices.add(new CountConflict(current));
                state = State.DEAD;
            } else network = new CountNetwork(reduction.rows(), reduction.lower(), reduction.upper(), budget)
                    .minimize(Arrays.stream(reduction.objective(model.objective(true))).map(BigInteger::negate).toArray(BigInteger[]::new));
            return;
        }
        if (network != null) {
            if (!network.step()) return;
            counts = reduction.expand(network.counts());
            boolean impossible = network.infeasible();
            network.close();
            network = null;
            if (counts != null) {
                separatorCandidate = true;
                beginScheduling();
            } else if (impossible) {
                learnedChoices.add(new CountConflict(current));
                state = State.DEAD;
            } else {
                conditioning = new CountConditioning(reduction.rows(), reduction.lower(), reduction.upper(), separatorPartial, budget);
                separatorPartial = null;
            }
            return;
        }
        if (conditioning != null) {
            if (!conditioning.step()) return;
            counts = reduction.expand(conditioning.counts());
            conditioning.close();
            conditioning = null;
            if (counts != null) {
                if (!preprocessingOnly && refineSupport()) state = State.SPLIT;
                else beginScheduling();
            } else partition = new CountPartition(reduction.rows(), reduction.lower(), reduction.upper(), budget);
            return;
        }
        if (partition != null) {
            if (!partition.step()) return;
            counts = reduction.expand(partition.counts());
            partition.close();
            partition = null;
            if (counts != null) {
                if (!preprocessingOnly && refineSupport()) state = State.SPLIT;
                else beginScheduling();
            } else if (weightedChoices()) matching = new CountMeetInMiddle(reduction.rows(), reduction.lower(), reduction.upper(), budget).retained();
            else beginBoolean();
            return;
        }
        if (matching != null) {
            if (!matching.step()) return;
            counts = reduction.expand(matching.counts());
            boolean impossible = matching.infeasible();
            if (viewSearch == null || !viewSearch.retain(reduction, matching)) matching.close();
            matching = null;
            boolean scout = matchingScout;
            matchingScout = false;
            if (counts != null) {
                if (scout) {
                    viewCandidateStage = 5;
                    beginScheduling();
                } else if (!preprocessingOnly && refineSupport()) state = State.SPLIT;
                else beginScheduling();
            } else if (impossible) {
                learnedChoices.add(new CountConflict(current));
                state = State.DEAD;
            } else if (scout) beginCompiledPortfolio();
            else beginBoolean();
            return;
        }
        if (binary != null) {
            if (!binary.step()) return;
            counts = reduction.expand(binary.counts());
            boolean impossible = binary.infeasible();
            importReducedConflicts(binary.learnedConflicts());
            if (viewSearch == null || !viewSearch.retain(reduction, binary)) binary.close();
            binary = null;
            if (counts != null) {
                if (!preprocessingOnly && refineSupport()) state = State.SPLIT;
                else beginScheduling();
            } else if (impossible) {
                learnedChoices.add(new CountConflict(current));
                state = State.DEAD;
            } else if (current.isEmpty() && !triedCdcl) {
                triedCdcl = true;
                if (viewSearch != null && viewSearch.retained()) {
                    viewSearch.resume(1_048_576);
                    viewStage = 3;
                } else cdcl = new CountDomainSearch(reduction.rows(), reduction.lower(), reduction.upper(), budget, 1_048_576).retained();
            } else afterBoolean();
            return;
        }
        if (auxiliaryLcg != null) {
            // Keep the current worker slice inside the same owned LCG. Its
            // atomic steps retain their work/cancellation checks; only repeated
            // outer dispatch, incumbent checks and timing samples are avoided.
            // Yield at the original worker quantum and auxiliary allowance.
            long allowance = auxiliaryMode == 0 ? quantum : Math.min(quantum, auxiliaryUntil - auxiliaryWork);
            while (!auxiliaryLcg.step()) {
                if (stopped.getAsBoolean() || budget.threadWork() - runStart >= allowance) return;
            }
            counts = reduction.expand(auxiliaryLcg.counts());
            boolean impossible = auxiliaryLcg.infeasible();
            if (counts == null && !impossible && auxiliaryLcg.paused()) {
                auxiliaryLive = true;
                state = State.UNRESOLVED;
                return;
            }
            importReducedConflicts(auxiliaryLcg.learnedConflicts());
            auxiliaryLcg.close();
            auxiliaryLcg = null;
            auxiliaryLive = false;
            if (counts != null) {
                domainCandidate = true;
                beginScheduling();
            } else if (impossible) {
                learnedChoices.add(new CountConflict(current));
                state = State.DEAD;
            } else state = State.UNRESOLVED;
            return;
        }
        if (cdcl != null) {
            if (!cdcl.step()) return;
            if (auxiliaryMode == 1 && cdcl.paused()) {
                state = State.UNRESOLVED;
                return;
            }
            counts = reduction.expand(cdcl.counts());
            boolean impossible = cdcl.infeasible();
            importReducedConflicts(cdcl.learnedConflicts());
            if (viewSearch == null || !viewSearch.retain(reduction, cdcl)) cdcl.close();
            cdcl = null;
            if (counts != null) {
                domainCandidate = true;
                beginScheduling();
            } else if (impossible) {
                learnedChoices.add(new CountConflict(current));
                state = State.DEAD;
            } else if (auxiliaryMode != 0) state = State.UNRESOLVED;
            else diagram = new CountDecisionDiagram(reduction.rows(), reduction.lower(), reduction.upper(), budget, 65536).retained();
            return;
        }
        if (obbt != null) {
            if (!obbt.step()) return;
            boolean impossible = obbt.infeasible();
            var cuts = obbt.cuts();
            counts = reduction.expand(obbt.counts());
            obbt.close();
            obbt = null;
            if (impossible) {
                learnedChoices.add(new CountConflict(current));
                state = State.DEAD;
            } else if (counts != null) {
                domainCandidate = true;
                beginScheduling();
            } else if (!cuts.isEmpty()) {
                if (!reducedLinear) {
                    CountMapping mapping = CountMapping.representatives(reduction.representatives());
                    cuts = cuts.stream().map(c -> mapping.row(c, budget)).toList();
                }
                coverPoint = obbtPoint;
                acceptCuts(cuts);
            } else branchPoint(obbtPoint);
            obbtPoint = null;
            return;
        }
        if (diagram != null) {
            if (!diagram.step()) return;
            counts = reduction.expand(diagram.counts());
            boolean impossible = diagram.infeasible();
            if (viewSearch == null || !viewSearch.retain(reduction, diagram)) diagram.close();
            diagram = null;
            if (counts != null) {
                domainCandidate = true;
                beginScheduling();
            } else if (impossible) {
                learnedChoices.add(new CountConflict(current));
                state = State.DEAD;
            } else afterBoolean();
            return;
        }
        if (repair != null) {
            long before = budget.threadWork();
            boolean repaired;
            try {
                repaired = repair.step();
            } finally {
                repairWork += budget.threadWork() - before;
            }
            if (!repaired) return;
            counts = reduction.expand(repair.counts());
            repair.close();
            repair = null;
            if (counts != null) {
                repairPoint = null;
                if (refineSupport()) state = State.SPLIT;
                else beginScheduling();
            } else {
                repairAttempts = 4;
                neighborhoodPoint = repairPoint;
                ExactRational[] local = Arrays.stream(reduction.representatives()).mapToObj(i -> repairPoint[i]).toArray(ExactRational[]::new);
                neighborhood = new CountNeighborhood(reduction.rows(), reduction.lower(), reduction.upper(), local, budget).pump(repairWork < 262144);
                repairPoint = null;
            }
            return;
        }
        if (propagating != null) {
            if (!propagating.step()) return;
            boolean blocked = propagating.blocked();
            lower = propagating.lowerBounds();
            upper = propagating.upperBounds();
            BitSet conflict = propagating.conflictingAssumptions();
            var tightened = propagating.tightened();
            usedChoices.addAll(propagating.usedConflicts());
            if (!blocked) sharedBounds = propagating.snapshot();
            propagating.close();
            propagating = null;
            if (blocked) {
                proofContradiction = proofTask && current.isEmpty();
                if (proofContradiction) learnedChoices.add(new CountConflict(List.of()));
                if (conflict != null) {
                    List<ExactLinearProgram.Constraint> used = new ArrayList<>();
                    for (int i = conflict.nextSetBit(0); i >= 0; i = conflict.nextSetBit(i + 1)) used.add(current.get(i));
                    learnedChoices.add(new CountConflict(used));
                }
                state = State.DEAD;
                return;
            }
            linearConstraints.addAll(tightened);
            if (current.isEmpty() && auxiliaryMode == 0)
                zeroHalf = CountZeroHalfCuts.pairs(linearConstraints, lower, upper, budget);
            if (zeroHalf == null) beginReduction();
            return;
        }
        if (!compiled) {
            long reductionStarted = budget.threadWork();
            boolean reduced = reduction.step();
            if (viewSearch != null) viewSearch.commonWork(budget.threadWork() - reductionStarted);
            if (!reduced) return;
            compiled = true;
            preprocessingOnly = (reduction.variables() > 64 || reduction.rows().size() > 512) &&
                    !ExactRevisedProgram.extendsDenseAdmission(reduction.variables(), reduction.rows()) && !mediumBooleanLp();
            if (!proofTask) lowerCost = PlanPreference.compiledLowerBound(model, reduction, lower, seeds, budget);
            if (proofTask) {
                modelViews = CountModelViews.create(linearConstraints, lower, upper, budget);
                if (modelViews == null) {
                    state = State.UNRESOLVED;
                    return;
                }
                modelViews.compileLight();
                modelViews.addReduced(reduction);
                viewSearch = new CountViewSearch(modelViews, budget);
                viewSearch.mode(CountPortfolioPolicy.Mode.PROOF);
                // This task owns its root propagation/reduction; charge that
                // cost once rather than recharging order-wide compilation.
                viewSearch.commonWork(observedWork + budget.threadWork() - runStarted);
                viewSearch.resume(Math.max(1, auxiliaryUntil - auxiliaryWork - (budget.threadWork() - runStarted)));
                viewStage = 6;
            } else if (auxiliaryMode == 1) cdcl = new CountDomainSearch(reduction.rows(), reduction.lower(), reduction.upper(), budget, 131072, CountCdcl.Branching.LEARNING_RATE).retained();
            else if (auxiliaryMode >= 2) auxiliaryLcg = new CountLcg(reduction.rows(), reduction.lower(), reduction.upper(), budget, 131072);
            else {
                if (!matchingScouted && current.isEmpty()) {
                    matchingScouted = true;
                    long allowance = CountMeetInMiddle.scoutWork(reduction.rows(), reduction.lower(), reduction.upper(), budget);
                    if (allowance > 0) {
                        matchingScout = true;
                        matching = new CountMeetInMiddle(reduction.rows(), reduction.lower(), reduction.upper(), budget, allowance).retained();
                        return;
                    }
                }
                beginCompiledPortfolio();
            }
            return;
        }
        if (!linear.step()) return;
        var status = linear.result();
        ExactRational[] point = reducedLinear ? reduction.expand(linear.point()) : linear.point();
        if (status == ExactLinearProgram.Result.INFEASIBLE && !reducedLinear && !linear.hot() && relaxationCuts.isEmpty()) learnMaterialConflict(linear.certificate());
        if (sharedBasis != null) sharedBasis.close();
        sharedBasis = linear.takeBasis();
        sharedBasisOriginal = !reducedLinear;
        linear.close();
        linear = null;
        if (status == ExactLinearProgram.Result.INFEASIBLE) {
            learnedChoices.add(new CountConflict(current));
            state = State.DEAD;
            return;
        }
        if (status != ExactLinearProgram.Result.OPTIMAL) {
            if (coverFallback != null) {
                var previous = coverFallback;
                coverFallback = null;
                useRelaxation(previous);
            } else finishCountSearch();
            return;
        }
        coverFallback = null;
        if (current.isEmpty() && coverRounds < 2 && fractionalChoice(point) >= 0) {
            ExactRational[] local = reducedLinear ? Arrays.stream(reduction.representatives()).mapToObj(i -> point[i]).toArray(ExactRational[]::new) : point;
            covering = new CountCoverCuts(relaxationRows(), reducedLinear ? reduction.lower() : lower,
                    reducedLinear ? reduction.upper() : upper, local, budget);
            coverPoint = point;
            coverRounds++;
            return;
        }
        useRelaxation(point);
    }

    private String activeStrategy() {
        if (verifying != null) return "count_verify";
        if (assembling != null || program != null) return "count_assemble";
        if (scheduling != null || supportSearch != null) return "count_schedule";
        if (matching != null) return "count_mitm";
        if (binary != null) return "count_boolean";
        if (viewStage != 0) return "count_views";
        if (cdcl != null) return "count_cdcl";
        if (auxiliaryLcg != null) return "count_portfolio_lcg";
        if (linear != null) return "count_lp";
        if (propagating != null) return "count_bounds";
        if (separator != null) return "count_separator";
        if (network != null) return "count_network";
        if (covering != null || cliques != null || rounding != null || zeroHalf != null) return "count_cuts";
        if (probing != null || branchProbe != null) return "count_probing";
        if (neighborhood != null || repair != null || jumping != null) return "count_heuristics";
        if (recovery != null || scaling != null) return "count_recovery";
        if (components != null || groups != null || conditioning != null || sourceFace != null) return "count_decompose";
        if (structural != null) return "count_structure";
        if (partition != null || packing != null) return "count_dp";
        return "count_compile";
    }

    private void useRelaxation(ExactRational[] point) {
        ExactRational operations = ExactRational.ZERO;
        for (ExactRational value : point) operations = operations.add(value);
        lowerCost = PlanPreference.lowerBound(model, lower, seeds, operations.ceil(), budget);
        if (refineMaterials(point)) {
            state = State.SPLIT;
            return;
        }
        for (CountGuard conflict : supportConflicts) if (conflict.violated(point, budget)) {
            branch(conflict);
            state = State.SPLIT;
            return;
        }
        if (current.isEmpty() && repairAttempts < 4 && fractionalChoice(point) >= 0) {
            repairPoint = point;
            beginRepair();
            return;
        }
        branchPoint(point);
    }

    private void acceptCuts(List<ExactLinearProgram.Constraint> cuts) {
        // Small finite or strongly eliminated allocation domains benefit from early tableau cuts.
        // Keep the original LP point as a separate, resumable repair view.
        if (cuts.isEmpty() && sharedBasis != null && current.isEmpty() && repairAttempts == 0 &&
                earlyCutDomain()) {
            ExactRational[] local = reducedLinear ? Arrays.stream(reduction.representatives()).mapToObj(i -> coverPoint[i]).toArray(ExactRational[]::new) : coverPoint;
            cuts = CountGomory.separate(sharedBasis, local, budget);
            if (!cuts.isEmpty()) {
                if (uncutRepairPoint == null) uncutRepairPoint = coverPoint;
                gomoryTried = true;
            }
        }
        long bytes = cuts.stream().mapToLong(row -> 192L + 128L * row.terms().size()).sum();
        if (cuts.isEmpty() || !budget.tryReserve(bytes)) {
            var point = coverPoint;
            coverPoint = null;
            useRelaxation(point);
        } else {
            workspace += bytes;
            relaxationCuts.addAll(cuts);
            CountMapping mapping = CountMapping.representatives(reduction.representatives());
            for (var cut : cuts) learnedMaterials.add(reducedLinear ? mapping.row(cut, budget) : cut);
            coverFallback = coverPoint;
            coverPoint = null;
            if (inheritedBasis != null) inheritedBasis.close();
            inheritedBasis = sharedBasis == null ? null : sharedBasis.retain();
            inheritedBasisOriginal = sharedBasisOriginal;
            inheritedCoordinates = reduction.coordinates();
            beginLinear();
        }
    }

    private boolean earlyCutDomain() {
        if (mediumBooleanLp()) return true;
        if (reduction.variables() > 64) return false;
        BigInteger[] low = reduction.lower(), high = reduction.upper();
        boolean eliminated = 2 * reduction.variables() <= model.recipes.size();
        for (int i = 0; i < low.length; i++) if (high[i] == null ||
                !eliminated && high[i].subtract(low[i]).compareTo(BigInteger.valueOf(8)) > 0)
            return false;
        return true;
    }

    private void afterBoolean() {
        if (earlyBinary) {
            if (!probed) beginProbing();
            else beginCompiledStrategies();
        } else if (preprocessingOnly) finishCountSearch();
        else beginLinear();
        earlyBinary = false;
    }

    private void resumeSpeculativeCandidate() {
        if (structuralCandidate) {
            structuralCandidate = false;
            if (structureEarly) {
                structureEarly = false;
                structureResuming = false;
                beginCompiledPortfolio();
            } else if (structureResuming) state = State.UNRESOLVED;
            else afterStructure();
            return;
        }
        if (viewCandidateStage != 0) {
            int continuation = viewCandidateStage;
            viewCandidateStage = 0;
            afterViewSearch(continuation);
            return;
        }
        if (auxiliaryMode != 0) {
            state = State.UNRESOLVED;
            return;
        }
        if (separatorCandidate) {
            separatorCandidate = false;
            conditioning = new CountConditioning(reduction.rows(), reduction.lower(), reduction.upper(), separatorPartial, budget);
            separatorPartial = null;
        } else if (domainCandidate) {
            domainCandidate = false;
            afterBoolean();
        } else if (neighborhoodPoint != null) {
            neighborhoodCandidate = false;
            var point = neighborhoodPoint;
            neighborhoodPoint = null;
            branchPoint(point);
        } else {
            jumpCandidate = false;
            afterJump();
        }
    }

    private void beginRepair() {
        ExactRational[] reduced = Arrays.stream(reduction.representatives()).mapToObj(i -> repairPoint[i]).toArray(ExactRational[]::new);
        ExactRational[] alternative = uncutRepairPoint == null ? null : Arrays.stream(reduction.representatives()).mapToObj(i -> uncutRepairPoint[i]).toArray(ExactRational[]::new);
        repair = new CountRepairPortfolio(reduction.rows(), reduction.lower(), reduction.upper(), reduced, alternative, budget);
        uncutRepairPoint = null;
    }

    private void beginReduction() {
        reduction = new CountReduction(linearConstraints, lower, upper, budget, auxiliaryMode == 3);
        if (current.isEmpty()) reduction.strengthenCapacities();
        if (proofTask) reduction.retainStrideView();
        if (current.isEmpty() && auxiliaryMode == 0) {
            modelViews = CountModelViews.create(linearConstraints, lower, upper, budget);
            if (modelViews != null) {
                reduction.retainStrideView();
                viewSearch = new CountViewSearch(modelViews, budget);
                viewSearch.mode(portfolioMode);
                viewSearch.commonWork(commonCompilationWork);
                if (!CountBoolean.preferred(linearConstraints, lower, upper, budget)) {
                    viewSearch.resume(32768);
                    viewStage = 1;
                }
            }
        }
    }

    private void beginCompiledStrategies() {
        if (current.isEmpty() && compileRecovery) recovery = new CountRecovery<>(this);
        else if (current.isEmpty()) beginGroups();
        else if (preprocessingOnly) state = State.UNRESOLVED;
        else beginLinear();
    }

    private void beginCompiledPortfolio() {
        if (!structureScouted && current.isEmpty()) {
            structureScouted = structureEarly = true;
            structural = new CountStructureSearch(reduction.rows(), reduction.lower(), reduction.upper(), budget, 262144).retained();
            return;
        }
        if (!lpTried && current.isEmpty()) {
            lpTried = true;
            lpSearch = CountLpSearch.create(reduction, model.recipes.size(), budget);
            if (lpSearch != null) {
                if (modelViews != null) lpSearch.shareRows();
                lpActive = true;
                return;
            }
        }
        if (modelViews != null) {
            long compilationStarted = budget.threadWork();
            modelViews.compileLight();
            modelViews.addReduced(reduction);
            viewSearch.commonWork(budget.threadWork() - compilationStarted);
            if (!CountBoolean.preferred(reduction.rows(), reduction.lower(), reduction.upper(), budget)) {
                viewSearch.resume(262144);
                viewStage = 2;
                return;
            }
        }
        congruence = new CountCongruence(reduction.rows(), reduction.variables(), budget);
    }

    private void afterStructure() {
        sourceFace = CountQuickSolve.sourceFace(reduction.rows(), reduction.lower(), reduction.upper(), budget, false);
        sourceFaceAttempt = 1;
        if (sourceFace == null) packing = new CountPacking(reduction.rows(), reduction.lower(), reduction.upper(), budget);
    }

    private void dispatchCompiledStrategies() {
        if (current.isEmpty() && !triedBinary && CountBoolean.preferred(reduction.rows(), reduction.lower(), reduction.upper(), budget)) {
            earlyBinary = true;
            beginBoolean();
            budget.note("count_dispatch", "unit_boolean_first; variables=" + reduction.variables());
        } else beginCompiledStrategies();
    }

    private void afterJump() {
        if (jumpLate) state = State.UNRESOLVED;
        // Preserve the canonical Boolean search on its preferred unit rows.
        // Only probe after that cheap attempt fails: valid domain deductions can
        // otherwise change its branching order before it sees an easy witness.
        else if (!probed && (triedBinary || !CountBoolean.preferred(reduction.rows(), reduction.lower(), reduction.upper(), budget))) beginProbing();
        else dispatchCompiledStrategies();
    }

    private void beginProbing() {
        probed = true;
        probing = new CountProbing(reduction.rows(), reduction.lower(), reduction.upper(), budget);
    }

    private void finishCountSearch() {
        if (current.isEmpty() && !jumpLate) {
            jumpLate = true;
            jumping = new CountJump(reduction.rows(), reduction.lower(), reduction.upper(), budget, 524_288);
        } else state = State.UNRESOLVED;
    }

    private void beginGroups() {
        scaling = new CountScale(reduction.rows(), reduction.lower(), reduction.upper(), budget, true, true);
        scalingResuming = false;
    }

    private void beginBoolean() {
        // A local cutoff falls through to other representations, but revisiting
        // the identical rows and bounds would merely repeat the same search.
        if (triedBinary) {
            if (preprocessingOnly) finishCountSearch();
            else beginLinear();
        } else {
            triedBinary = true;
            binary = new CountBoolean(reduction.rows(), reduction.lower(), reduction.upper(), budget).retained();
        }
    }

    private void branchPoint(ExactRational[] point) {
        if (!incumbentTried && incumbentCounts != null && reduction != null && fractionalChoice(point) >= 0) {
            incumbentTried = true;
            neighborhoodPoint = point;
            int[] representatives = reduction.representatives();
            neighborhood = new CountNeighborhood(reduction.rows(), reduction.lower(), reduction.upper(),
                    Arrays.stream(representatives).mapToObj(i -> point[i]).toArray(ExactRational[]::new), budget)
                    .incumbent(Arrays.stream(representatives).mapToObj(i -> incumbentCounts[i]).toArray(BigInteger[]::new),
                            Arrays.stream(reduction.objective(model.objective(true))).map(BigInteger::negate).toArray(BigInteger[]::new));
            return;
        }
        int chosen = fractionalChoice(point);
        if (chosen >= 0) {
            // Retain the uncut relaxation for lattice repair and primal
            // neighborhoods first. An equally valid, stronger relaxation can
            // put those heuristics on a much harder face of the polytope.
            if (current.isEmpty() && !gomoryTried && sharedBasis != null && repairWork < 262144) {
                gomoryTried = true;
                ExactRational[] local = reducedLinear ? Arrays.stream(reduction.representatives()).mapToObj(i -> point[i]).toArray(ExactRational[]::new) : point;
                var cuts = CountGomory.separate(sharedBasis, local, budget);
                if (!cuts.isEmpty()) {
                    coverPoint = point;
                    acceptCuts(cuts);
                    return;
                }
            }
            if (feedbackRegion == null) feedbackRegion = CountBranchProbe.feedback(model, budget);
            // In small recycle regions inference exposes seed competition;
            // objective probes often favor a cheaper but unstartable count
            // vector. Leave the original inference search its opportunity.
            if (current.isEmpty() && !obbtTried && !feedbackRegion && repairWork < 262144) {
                obbtTried = true;
                obbtPoint = point;
                ExactRational[] local = Arrays.stream(reduction.representatives()).mapToObj(i -> point[i]).toArray(ExactRational[]::new);
                obbt = new CountObbt(reduction.rows(), reduction.lower(), reduction.upper(), local, budget);
                return;
            }
            if (current.size() <= 3) {
                int[] representatives = reduction.representatives();
                int localChoice = Arrays.binarySearch(representatives, chosen);
                if (localChoice < 0) {
                    // An uncompressed LP may violate an integer-only affine
                    // relation. Split its ORIGINAL coordinate, not a nonexistent
                    // reduced variable, and retain both integer subdomains.
                    splitPoint(point, chosen);
                    return;
                }
                ExactRational[] local = Arrays.stream(representatives).mapToObj(i -> point[i]).toArray(ExactRational[]::new);
                branchProbePoint = point;
                branchProbe = new CountBranchProbe(reduction.rows(), reduction.lower(), reduction.upper(), local, localChoice, budget,
                        feedbackRegion).history(branchHistory, representatives);
                if (!feedbackRegion) branchProbe.objective(reduction.objective(model.objective(true)));
            } else splitPoint(point, chosen);
            return;
        }
        counts = Arrays.stream(point).map(ExactRational::numerator).toArray(BigInteger[]::new);
        if (refineSupport()) {
            state = State.SPLIT;
            return;
        }
        beginScheduling();
    }

    private void splitPoint(ExactRational[] point, int chosen) {
        enqueue(current, bound(chosen, point[chosen].floor(), false));
        enqueue(current, bound(chosen, point[chosen].ceil(), true));
        state = State.SPLIT;
    }

    private void beginLinear() {
        // Tiny tableaux are cheap to rebuild. Keep their canonical recipe
        // ordering instead of paying for basis reuse and changing degenerate ties.
        boolean reuse = (model.recipes.size() + 2L) * (model.constraints.size() + 2L) > 256;
        // Fixing a Boolean variable in each child must not renumber every
        // surviving column and discard its parent's exact simplex basis.
        reducedLinear = reuse && !mediumBooleanLp() && reduction.variables() != model.recipes.size();
        linear = new ExactLinearProgram(reducedLinear ? reduction.variables() : model.recipes.size(),
                relaxationRows(),
                reducedLinear ? reduction.objective(model.objective(true)) : model.objective(true), budget,
                reuse && (reducedLinear ? !inheritedBasisOriginal && reduction.coordinates().equals(inheritedCoordinates) : inheritedBasisOriginal) ? inheritedBasis : null, reuse);
        if (inheritedBasis != null) {
            inheritedBasis.close();
            inheritedBasis = null;
        }
    }

    private boolean speculativeCandidate() {
        return failureCandidate || jumpCandidate || neighborhoodCandidate || domainCandidate || separatorCandidate || structuralCandidate || viewCandidateStage != 0;
    }

    private void rejectSpeculativeCandidate() {
        rejectSpeculativeCandidate(false);
    }

    private void rejectSpeculativeCandidate(boolean retainSchedule) {
        if (failureCandidate) {
            clearCandidate(retainSchedule);
            failureCandidate = false;
            counts = null;
            failureNeighborhood.rejectFailureCandidate();
            return;
        }
        if (!failureNeighborhoodTried && scheduling != null && counts != null && lower != null &&
                model.recipes.size() <= 256 && linearConstraints.size() <= 1024 && budget.remainingWork() >= 65536) {
            failureNeighborhoodTried = true;
            BitSet region = scheduling.failureRegion();
            if (!region.isEmpty()) {
                var point = Arrays.stream(counts).map(ExactRational::of).toArray(ExactRational[]::new);
                failureNeighborhood = new CountNeighborhood(linearConstraints, lower, upper, point, budget);
                failureNeighborhood.failureRegion(region);
                clearCandidate(retainSchedule);
                counts = null;
                return;
            }
        }
        clearCandidate(retainSchedule);
        counts = null;
        resumeSpeculativeCandidate();
    }

    private void beginScheduling() {
        schedulingWork = 0;
        PlanStep reused = scheduleContinuations.witness(counts);
        if (reused != null) {
            beginAssembly(reused);
            return;
        }
        scheduling = scheduleContinuations.acquire(counts);
    }

    private boolean retryScheduleOrder() {
        if (witnessSchedule == null) return false;
        if (!witnessSchedule.retryAfterRejectedWitness()) {
            witnessSchedule.close();
            witnessSchedule = null;
            return false;
        }
        if (assembling != null) assembling.close();
        if (verifying != null) verifying.close();
        assembling = null;
        verifying = null;
        plan = null;
        preference = null;
        scheduling = witnessSchedule;
        witnessSchedule = null;
        return true;
    }

    IntegerCountBranch<K> shareSchedules(CountScheduleContinuations<K> pool) {
        if (initialized || sharedSchedules) throw new IllegalStateException("Late schedule pool handoff");
        scheduleContinuations.close();
        scheduleContinuations = pool;
        sharedSchedules = true;
        return this;
    }

    /** Drop only the candidate pipeline, retaining the searches that proposed it. */
    private void clearCandidate() {
        clearCandidate(false);
    }

    private void clearCandidate(boolean retainSchedule) {
        candidateFeedback(CountViewSearch.CandidateOutcome.UNRESOLVED);
        if (scheduling != null && (!retainSchedule || !scheduleContinuations.retain(scheduling))) scheduling.close();
        if (witnessSchedule != null) witnessSchedule.close();
        if (program != null) program.close();
        if (assembling != null) assembling.close();
        if (verifying != null) verifying.close();
        scheduling = null;
        witnessSchedule = null;
        program = null;
        assembling = null;
        verifying = null;
        plan = null;
        preference = null;
        schedulingWork = 0;
    }

    private long measuredWork() {
        // The coordinator drains work after each slice. Observations need a
        // separate lifetime clock across those handoffs, including worker moves.
        return observedWork + (measuringRun ? budget.threadWork() - runStarted : 0);
    }

    private void candidateFeedback(CountViewSearch.CandidateOutcome outcome) {
        if (candidateOrigin == null) return;
        viewSearch.feedback(candidateOrigin, outcome, Math.max(0, measuredWork() - candidateStarted),
                feedbackIncumbent == null || preference != null && preference.preferredTo(feedbackIncumbent));
        if (outcome != CountViewSearch.CandidateOutcome.SCHEDULE_WITNESS) candidateOrigin = null;
    }

    private boolean mediumBooleanLp() {
        if (model.recipes.size() < 64 || model.recipes.size() > 128 || reduction.rows().size() > 512) return false;
        BigInteger[] low = reduction.lower(), high = reduction.upper();
        for (int i = 0; i < low.length; i++) {
            budget.check();
            if (low[i].signum() < 0 || high[i] == null || high[i].compareTo(BigInteger.ONE) > 0) return false;
        }
        return true;
    }

    private List<ExactLinearProgram.Constraint> relaxationRows() {
        var rows = new ArrayList<>(reducedLinear ? reduction.rows() : linearConstraints);
        rows.addAll(relaxationCuts);
        return rows;
    }

    private boolean weightedChoices() {
        // Cardinality/clause systems are handled directly by Boolean
        // propagation. Building a multidimensional equality index adds no
        // useful structure there; reserve it for genuinely weighted choices.
        for (var row : reduction.rows()) for (var weight : row.terms().values()) {
            budget.check();
            if (weight.abs().compareTo(BigInteger.ONE) > 0) return true;
        }
        return false;
    }

    private int fractionalChoice(ExactRational[] point) {
        int best = -1, bestActivity = -1;
        for (int id : reduction.representatives()) if (!point[id].integral()) {
            int activity = 0;
            int weight = knownChoices.size();
            for (var conflict : knownChoices) {
                for (var row : conflict.assumptions()) {
                    budget.check();
                    if (row.terms().containsKey(id)) activity += weight;
                }
                weight--;
            }
            if (activity > bestActivity) {
                best = id;
                bestActivity = activity;
            }
        }
        // Integer presolve may have rounded rows before discovering an
        // equality. The raw relaxation need not satisfy that equality, so
        // integral representatives alone do not certify an integer vector.
        if (best < 0) for (int id = 0; id < point.length; id++) if (!point[id].integral()) return id;
        return best;
    }

    private boolean rejectsChoices() {
        if (knownChoices.isEmpty()) return false;
        BigInteger[] low = new BigInteger[model.recipes.size()], high = new BigInteger[model.recipes.size()];
        Arrays.fill(low, BigInteger.ZERO);
        // Cheap decision bounds can reuse a learned core before rebuilding
        // propagation or simplex. General assumptions remain in the full model.
        for (var row : current) {
            budget.check();
            if (row.terms().size() != 1) continue;
            var term = row.terms().entrySet().iterator().next();
            int id = term.getKey();
            if (term.getValue().equals(BigInteger.ONE)) high[id] = high[id] == null ? row.upper() : high[id].min(row.upper());
            else if (term.getValue().equals(BigInteger.ONE.negate())) low[id] = low[id].max(row.upper().negate());
        }
        for (var conflict : knownChoices) if (conflict.impliedBy(low, high, budget)) {
            usedChoices.add(conflict);
            return true;
        }
        return false;
    }

    private void beginAssembly(PlanStep witness) {
        Map<String, GraphRecipe<K>> recipes = new LinkedHashMap<>();
        model.recipes.forEach(recipe -> recipes.put(recipe.id(), recipe));
        assembling = new AllocationSearch.Candidate<>(witness, recipes, target, amount, stock, seeds, external,
                preserve, force, false, budget, started);
        assembling.sharedSummaries = scheduleContinuations.programs;
    }

    /** A lifted optional kernel exports a witness, never a proof or search exclusion. */
    void compiledCandidate(BigInteger[] candidate, PlanStep witness) {
        initialized = partitioned = candidateOnly = true;
        compileRecovery = false;
        counts = candidate;
        beginAssembly(witness);
    }

    private void unresolved() {
        if (candidateOnly) {
            plan = null;
            state = State.UNRESOLVED;
            return;
        }
        if (speculativeCandidate()) {
            // A schedulable vector can still fail the exact force-crafting or
            // seed checks. Preserve the same continuation as scheduling failure.
            rejectSpeculativeCandidate();
            return;
        }
        plan = null;
        partitionCounts();
        state = State.UNRESOLVED;
    }

    boolean resume() {
        // A split root keeps its already-paid searches. Its children cover the
        // full domain; this continuation must never create the same children
        // again or make a local cutoff invalidate their coverage.
        if (proofTask) {
            if (limit != null || auxiliaryWork >= auxiliaryUntil || viewSearch == null || !viewSearch.retained()) return false;
            clearCandidate();
            counts = null;
            viewCandidateStage = 0;
            viewSearch.resume(auxiliaryUntil - auxiliaryWork);
            viewStage = 6;
            state = State.OPEN;
            return true;
        }
        if (auxiliaryMode != 0) {
            if (limit != null) return false;
            if (auxiliaryMode == 1) {
                if (cdcl == null) return false;
                if (cdcl.paused()) cdcl.resume(131072);
            } else {
                if (auxiliaryLcg == null) return false;
                if (auxiliaryLcg.paused()) auxiliaryLcg.resume(131072);
            }
            auxiliaryUntil = auxiliaryWork + Math.min(262144, budget.remainingWork());
            state = State.OPEN;
            return true;
        }
        if (limit == null && parkedScaling != null && (preferScaleResume || parkedStructural == null &&
                (lpSearch == null || !lpSearch.retained()) && (viewSearch == null || !viewSearch.retained()))) {
            return resumeScaling();
        }
        if (limit == null && parkedStructural != null &&
                (preferStructureResume || (lpSearch == null || !lpSearch.retained()) && (viewSearch == null || !viewSearch.retained()))) {
            clearCandidate();
            jumpCandidate = neighborhoodCandidate = domainCandidate = separatorCandidate = structuralCandidate = false;
            counts = null;
            viewCandidateStage = 0;
            structural = parkedStructural;
            parkedStructural = null;
            structural.resume(262144);
            structureResuming = true;
            preferStructureResume = false;
            if ((lpSearch == null || !lpSearch.retained()) && (viewSearch == null || !viewSearch.retained())) preferScaleResume = true;
            state = State.OPEN;
            return true;
        }
        if (limit == null && lpSearch != null && lpSearch.retained() &&
                (preferLpResume || viewSearch == null || !viewSearch.retained())) {
            clearCandidate();
            jumpCandidate = neighborhoodCandidate = domainCandidate = separatorCandidate = structuralCandidate = false;
            viewCandidateStage = 0;
            counts = null;
            lpSearch.resume(262144);
            lpActive = lpResuming = true;
            preferLpResume = false;
            preferStructureResume = true;
            if (viewSearch == null || !viewSearch.retained()) preferScaleResume = true;
            state = State.OPEN;
            return true;
        }
        if (limit == null && viewSearch != null && viewSearch.retained()) {
            // Other strategies have had their turn. Continue the same model
            // searches rather than silently closing their unfinished domains.
            // A rejected assembly must not run again ahead of the resumed view.
            clearCandidate();
            jumpCandidate = neighborhoodCandidate = domainCandidate = separatorCandidate = structuralCandidate = false;
            viewCandidateStage = 0;
            counts = null;
            viewSearch.resume(262144);
            viewStage = 4;
            preferLpResume = true;
            preferStructureResume = true;
            preferScaleResume = true;
            state = State.OPEN;
            return true;
        }
        if (splitWitness || retried || counts == null || memory == 0 || limit != null) return false;
        releaseWorkspace();
        initialized = true;
        retried = rescue = true;
        state = State.OPEN;
        return true;
    }

    boolean retainSplitWitness() {
        if (state != State.SPLIT || proofTask || auxiliaryMode != 0 || limit != null) return false;
        boolean rootSearch = current.isEmpty() && (parkedStructural != null ||
                lpSearch != null && lpSearch.retained() || viewSearch != null && viewSearch.retained());
        if (parkedScaling == null && !rootSearch) return false;
        splitWitness = true;
        partitioned = true;
        budget.note("count_split_continuation", "owned_frontiers_retained; children_cover_domain");
        return resume();
    }

    boolean finiteProbing() {
        return matching != null && matching.probing() || scaling != null && scaling.finiteProbing() ||
                parkedScaling != null && parkedScaling.finiteProbing();
    }

    private boolean resumeScaling() {
        clearCandidate();
        jumpCandidate = neighborhoodCandidate = domainCandidate = separatorCandidate = structuralCandidate = false;
        counts = null;
        viewCandidateStage = 0;
        scaling = parkedScaling;
        parkedScaling = null;
        scaling.resume(262144);
        scalingResuming = true;
        preferScaleResume = false;
        state = State.OPEN;
        return true;
    }

    /** Same original recipe coordinates and exact domain, not just a similar matrix. */
    boolean sameAuxiliarySearch(IntegerCountBranch<K> other) {
        if (other == this || auxiliaryMode < 2 || other.auxiliaryMode < 2 || model != other.model ||
                auxiliaryLcg == null || other.auxiliaryLcg == null || limit != null || other.limit != null ||
                !auxiliaryLive || !other.auxiliaryLive || memory == 0 || workspace == 0 ||
                other.memory == 0 || other.workspace == 0 || other.state != State.OPEN ||
                state != State.OPEN && state != State.UNRESOLVED ||
                !current.equals(other.current) || reduction == null || other.reduction == null ||
                reduction.variables() != other.reduction.variables())
            return false;
        var rows = reduction.rows();
        var otherRows = other.reduction.rows();
        if (rows.size() != otherRows.size()) return false;
        // Bounds accessors clone their arrays; this optional comparison owns
        // that temporary storage and declines when it does not fit.
        long bytes = 256L + 64L * reduction.variables();
        if (!budget.tryReserve(bytes)) return false;
        try {
            for (int i = 0; i < model.recipes.size(); i++) {
                budget.check();
                budget.check();
                budget.check();
            }
            if (!reduction.sameCoordinates(other.reduction)) return false;
            var low = reduction.lower();
            var high = reduction.upper();
            var otherLow = other.reduction.lower();
            var otherHigh = other.reduction.upper();
            for (int i = 0; i < low.length; i++) {
                budget.check();
                if (!low[i].equals(otherLow[i]) || !Objects.equals(high[i], otherHigh[i])) return false;
            }
            for (int r = 0; r < rows.size(); r++) {
                budget.check();
                var row = rows.get(r);
                var next = otherRows.get(r);
                if (!row.upper().equals(next.upper()) || row.terms().size() != next.terms().size()) return false;
                for (var term : row.terms().entrySet()) {
                    budget.check();
                    if (!term.getValue().equals(next.terms().get(term.getKey()))) return false;
                }
            }
            return true;
        } finally {
            budget.release(bytes);
        }
    }

    void releaseWorkspace() {
        if (!sharedSchedules) scheduleContinuations.close();
        closeViews();
        if (lpSearch != null) lpSearch.close();
        lpSearch = null;
        lpActive = false;
        if (diophantine != null) diophantine.close();
        diophantine = null;
        if (structural != null) structural.close();
        structural = null;
        if (parkedStructural != null) parkedStructural.close();
        parkedStructural = null;
        if (repair != null) repair.close();
        repair = null;
        repairPoint = null;
        if (linear != null) linear.close();
        if (obbt != null) obbt.close();
        obbt = null;
        if (diagram != null) diagram.close();
        diagram = null;
        if (propagating != null) propagating.close();
        if (partition != null) partition.close();
        if (components != null) components.close();
        if (separator != null) separator.close();
        if (network != null) network.close();
        if (groups != null) groups.close();
        if (conditioning != null) conditioning.close();
        if (recovery != null) recovery.close();
        if (scaling != null) scaling.close();
        scaling = null;
        if (parkedScaling != null) parkedScaling.close();
        parkedScaling = null;
        if (packing != null) packing.close();
        if (jumping != null) jumping.close();
        jumping = null;
        if (probing != null) probing.close();
        probing = null;
        if (covering != null) covering.close();
        if (cliques != null) cliques.close();
        covering = null;
        if (rounding != null) rounding.close();
        rounding = null;
        if (zeroHalf != null) zeroHalf.close();
        zeroHalf = null;
        if (neighborhood != null) neighborhood.close();
        neighborhood = null;
        if (failureNeighborhood != null) failureNeighborhood.close();
        failureNeighborhood = null;
        failureCandidate = false;
        if (branchProbe != null) branchProbe.close();
        branchProbe = null;
        if (cdcl != null) cdcl.close();
        cdcl = null;
        branchProbePoint = neighborhoodPoint = null;
        coverPoint = coverFallback = null;
        if (congruence != null) congruence.close();
        if (sourceFace != null) sourceFace.close();
        if (matching != null) matching.close();
        if (binary != null) binary.close();
        if (scheduling != null) scheduling.close();
        if (witnessSchedule != null) witnessSchedule.close();
        if (supportSearch != null) supportSearch.close();
        if (program != null) program.close();
        if (assembling != null) assembling.close();
        if (verifying != null) verifying.close();
        linear = null;
        propagating = null;
        partition = null;
        components = null;
        groups = null;
        conditioning = null;
        recovery = null;
        matching = null;
        binary = null;
        scheduling = null;
        witnessSchedule = null;
        supportSearch = null;
        program = null;
        assembling = null;
        verifying = null;
        budget.release(workspace);
        workspace = 0;
    }

    private boolean refineSupport() {
        var point = Arrays.stream(counts).map(ExactRational::of).toArray(ExactRational[]::new);
        if (refineMaterials(point)) return true;
        CountGuard condition = execution.violated(point);
        if (condition != null) {
            learn(condition);
            branch(condition);
            return true;
        }
        var startup = CountStartup.separateAll(model, counts, budget);
        if (!startup.isEmpty()) {
            for (var proof : startup) learn(proof.guard());
            branch(startup.get(0).guard());
            return true;
        }
        return false;
    }

    private boolean refineMaterials(ExactRational[] point) {
        // A sibling can certify a new boundary while this LP is suspended.
        // Apply that shared proof before branching on a stale relaxation.
        for (var row : knownMaterials) {
            if (linearConstraints.contains(row)) continue;
            ExactRational sum = ExactRational.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.check();
                sum = sum.add(point[term.getKey()].multiply(ExactRational.of(term.getValue())));
            }
            if (sum.compareTo(ExactRational.of(row.upper())) > 0) {
                usedMaterials.add(row);
                enqueue(current, row);
                return true;
            }
        }
        return false;
    }

    private void partitionCounts() {
        if (partitioned) return;
        partitioned = true;
        // Large models get optional arithmetic preprocessing, not an enlarged
        // general branch tree. An undecided candidate remains UNKNOWN.
        if (preprocessingOnly) return;
        // Disjoint lexicographic siblings cover every OTHER integer vector.
        // An undecided fixed vector stays owned by this retained branch.
        var prefix = new ArrayList<>(current);
        int[] coordinates = compiled ? reduction.representatives() : java.util.stream.IntStream.range(0, counts.length).toArray();
        for (int i : coordinates) {
            if (counts[i].compareTo(lower[i]) > 0) enqueue(prefix, bound(i, counts[i].subtract(BigInteger.ONE), false));
            if (upper[i] == null || counts[i].compareTo(upper[i]) < 0) enqueue(prefix, bound(i, counts[i].add(BigInteger.ONE), true));
            prefix.add(bound(i, counts[i], false));
            prefix.add(bound(i, counts[i], true));
        }
    }

    private void learn(CountGuard condition) {
        if (!supportConflicts.contains(condition)) supportConflicts.add(condition);
        learnedChoices.add(condition.conflict());
    }

    private void importReducedConflicts(List<CountConflict> values) {
        CountMapping mapping = CountMapping.representatives(reduction.representatives());
        for (var conflict : values) {
            List<ExactLinearProgram.Constraint> guarded = new ArrayList<>(current);
            guarded.addAll(mapping.conflict(conflict, budget).assumptions());
            learnedChoices.add(new CountConflict(guarded));
        }
        if (!values.isEmpty()) budget.note("count_view_conflicts", "imported=" + values.size() + "; branch_guards=" + current.size());
    }

    List<CountConflict> knownChoices() {
        return knownChoices;
    }

    private void branch(CountGuard conflict) {
        enqueue(current, bound(conflict.recipe(), BigInteger.ZERO, false));
        if (!conflict.required().terms().isEmpty()) {
            var required = new ArrayList<>(current);
            required.add(bound(conflict.recipe(), BigInteger.ONE, true));
            enqueue(required, conflict.required());
        }
    }

    private void learnMaterialConflict(ExactRational[] proof) {
        if (proof == null || proof.length < globalRows || learnedMaterials.size() >= 64) return;
        // Combine only globally valid material rows. The other Farkas terms
        // describe this branch; dropping those terms leaves a reusable resource
        // inequality that is valid for all branches of THIS inventory snapshot.
        BigInteger scale = BigInteger.ONE;
        for (int i = 0; i < globalRows; i++) {
            scale = scale.divide(scale.gcd(proof[i].denominator())).multiply(proof[i].denominator());
            if (scale.bitLength() > 2048) return;
        }
        Map<Integer, BigInteger> terms = new LinkedHashMap<>();
        BigInteger upper = BigInteger.ZERO;
        for (int i = 0; i < globalRows; i++) {
            BigInteger weight = proof[i].numerator().multiply(scale.divide(proof[i].denominator()));
            if (weight.signum() < 0) return;
            if (weight.signum() == 0) continue;
            upper = upper.add(linearConstraints.get(i).upper().multiply(weight));
            for (var term : linearConstraints.get(i).terms().entrySet()) {
                budget.check();
                terms.merge(term.getKey(), term.getValue().multiply(weight), BigInteger::add);
            }
        }
        terms.values().removeIf(value -> value.signum() == 0);
        if (terms.isEmpty()) return;
        BigInteger common = upper.abs();
        for (BigInteger coefficient : terms.values()) common = common.gcd(coefficient);
        BigInteger divisor = common;
        if (divisor.signum() > 0) {
            terms.replaceAll((key, value) -> value.divide(divisor));
            upper = upper.divide(divisor);
        }
        if (upper.bitLength() > 2048 || terms.values().stream().anyMatch(value -> value.bitLength() > 2048)) return;
        learnedMaterials.add(new ExactLinearProgram.Constraint(terms, upper));
    }

    private void enqueue(List<ExactLinearProgram.Constraint> prefix, ExactLinearProgram.Constraint constraint) {
        var next = new ArrayList<>(prefix);
        next.add(constraint);
        children.add(List.copyOf(next));
    }

    private void afterViewSearch(int continuation) {
        if (continuation == 2) congruence = new CountCongruence(reduction.rows(), reduction.variables(), budget);
        else if (continuation == 3) diagram = new CountDecisionDiagram(reduction.rows(), reduction.lower(), reduction.upper(), budget, 65536).retained();
        else if (continuation == 4 || continuation == 6) state = State.UNRESOLVED;
        else if (continuation == 5) beginCompiledPortfolio();
        else if (continuation == 7) groups = new CountGroups(reduction.rows(), reduction.lower(), reduction.upper(), budget);
        // Stage 1 resumes compilation, preserving the untouched original model.
    }

    private void closeViews() {
        candidateFeedback(CountViewSearch.CandidateOutcome.UNRESOLVED);
        if (viewSearch != null) viewSearch.close();
        if (modelViews != null) modelViews.close();
        viewSearch = null;
        modelViews = null;
        viewStage = viewCandidateStage = 0;
    }

    private static ExactLinearProgram.Constraint bound(int variable, BigInteger value, boolean lower) {
        return new ExactLinearProgram.Constraint(Map.of(variable, lower ? BigInteger.ONE.negate() : BigInteger.ONE), lower ? value.negate() : value);
    }

    @Override
    public void close() {
        candidateFeedback(CountViewSearch.CandidateOutcome.UNRESOLVED);
        if (auxiliaryLcg != null) auxiliaryLcg.close();
        auxiliaryLcg = null;
        releaseWorkspace();
        if (inheritedBounds != null) {
            inheritedBounds.close();
            inheritedBounds = null;
        }
        if (sharedBounds != null) {
            sharedBounds.close();
            sharedBounds = null;
        }
        if (inheritedBasis != null) {
            inheritedBasis.close();
            inheritedBasis = null;
        }
        if (sharedBasis != null) {
            sharedBasis.close();
            sharedBasis = null;
        }
        if (reduction != null) {
            reduction.close();
            reduction = null;
        }
        budget.release(memory);
        memory = 0;
    }
}
