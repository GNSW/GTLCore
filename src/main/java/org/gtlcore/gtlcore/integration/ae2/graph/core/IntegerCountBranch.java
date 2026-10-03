package org.gtlcore.gtlcore.integration.ae2.graph.core;

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
    CountGroups groups;
    CountConditioning conditioning;
    CountRecovery<K> recovery;
    CountScale scaling;
    CountDiophantine diophantine;
    CountCongruence congruence;
    CountPacking packing;
    CountJump jumping;
    CountProbing probing;
    boolean probed, jumpCandidate, jumpLate;
    CountCoverCuts covering;
    CountCliques cliques;
    CountRoundingCuts rounding;
    boolean roundingTried;
    ExactRational[] coverPoint, coverFallback;
    final List<ExactLinearProgram.Constraint> relaxationCuts = new ArrayList<>();
    int coverRounds;
    CountQuickSolve sourceFace;
    long sourceFaceWork;
    int sourceFaceAttempt;
    boolean compileRecovery = true;
    CountMeetInMiddle matching;
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
    int viewStage, viewCandidateStage;
    CountLcg auxiliaryLcg;
    int auxiliaryMode;
    long auxiliaryWork;
    CountDecisionDiagram diagram;
    CountObbt obbt;
    boolean gomoryTried;
    Boolean feedbackRegion;
    boolean obbtTried;
    ExactRational[] obbtPoint;
    boolean triedCdcl, domainCandidate;
    CountNeighborhood neighborhood;
    BigInteger[] incumbentCounts;
    boolean incumbentTried;
    ExactRational[] neighborhoodPoint;
    boolean neighborhoodCandidate;
    CountBranchProbe branchProbe;
    CountBranchHistory branchHistory;
    ExactRational[] branchProbePoint;
    ExactLinearProgram linear;
    ExactLinearProgram.Basis inheritedBasis, sharedBasis;
    CountReduction.Coordinates inheritedCoordinates;
    CountSchedule<K> scheduling;
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
        this.started = started;
        this.current = List.copyOf(current);
        long bytes = 512L + current.stream().mapToLong(row -> 192L + 48L * row.terms().size()).sum();
        if (budget.tryReserve(bytes)) memory = bytes;
        else state = State.UNRESOLVED;
    }

    void run(long quantum, List<ExactLinearProgram.Constraint> materials, List<CountGuard> support, List<CountConflict> choices,
             PlanPreference<K> incumbent, BooleanSupplier stopped) {
        long before = budget.threadWork();
        try {
            if (state != State.OPEN) return;
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
                if (auxiliaryMode != 0 && auxiliaryWork + budget.threadWork() - before >= 262144) {
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
                        advance();
                    } finally {
                        budget.strategy(strategy, budget.threadWork() - scheduleBefore, System.nanoTime() - start);
                    }
                } else advance();
                if (inSchedule) schedulingWork += budget.threadWork() - scheduleBefore;
                if (scheduling != null && (jumpCandidate || neighborhoodCandidate || domainCandidate || separatorCandidate || viewCandidateStage != 0) && schedulingWork >= 8192) {
                    scheduling.close();
                    scheduling = null;
                    counts = null;
                    resumeSpeculativeCandidate();
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
            if (auxiliaryMode != 0) auxiliaryWork += spent;
        }
    }

    private void advance() {
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
            state = State.FOUND;
            return;
        }
        if (assembling != null) {
            if (!assembling.step()) return;
            plan = assembling.plan;
            if (plan == null) {
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
            if (status == CountSchedule.Result.WITNESS) beginAssembly(scheduling.witness());
            scheduling.close();
            scheduling = null;
            if ((jumpCandidate || neighborhoodCandidate || domainCandidate || separatorCandidate || viewCandidateStage != 0) && status != CountSchedule.Result.WITNESS) {
                // Failure to schedule a local-search candidate must leave the
                // original count domain and all the other strategies available.
                counts = null;
                resumeSpeculativeCandidate();
                return;
            }
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
        if (viewStage != 0) {
            if (!viewSearch.step()) return;
            int continuation = viewStage;
            viewStage = 0;
            counts = viewSearch.counts();
            if (counts != null) {
                viewCandidateStage = continuation;
                schedulingWork = 0;
                scheduling = new CountSchedule<>(model, counts, budget);
            } else if (viewSearch.infeasible()) {
                learnedChoices.add(new CountConflict(current));
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
            } else diophantine = new CountDiophantine(reduction.rows(), reduction.lower(), reduction.upper(), budget);
            return;
        }
        if (diophantine != null) {
            if (!diophantine.step()) return;
            counts = reduction.expand(diophantine.counts());
            diophantine.close();
            diophantine = null;
            if (counts != null) scheduling = new CountSchedule<>(model, counts, budget);
            else if (current.isEmpty()) {
                sourceFace = CountQuickSolve.sourceFace(reduction.rows(), reduction.lower(), reduction.upper(), budget, false);
                sourceFaceAttempt = 1;
                if (sourceFace == null) packing = new CountPacking(reduction.rows(), reduction.lower(), reduction.upper(), budget);
            } else dispatchCompiledStrategies();
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
            if (counts != null) scheduling = new CountSchedule<>(model, counts, budget);
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
            if (counts != null) scheduling = new CountSchedule<>(model, counts, budget);
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
        if (neighborhood != null) {
            if (!neighborhood.step()) return;
            counts = reduction.expand(neighborhood.counts());
            neighborhood.close();
            neighborhood = null;
            if (counts != null) {
                neighborhoodCandidate = true;
                schedulingWork = 0;
                scheduling = new CountSchedule<>(model, counts, budget);
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
                scheduling = new CountSchedule<>(model, counts, budget);
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
            scaling.close();
            scaling = null;
            if (counts != null) scheduling = new CountSchedule<>(model, counts, budget);
            else groups = new CountGroups(reduction.rows(), reduction.lower(), reduction.upper(), budget);
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
                else scheduling = new CountSchedule<>(model, counts, budget);
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
                else scheduling = new CountSchedule<>(model, counts, budget);
            } else if (impossible) {
                learnedChoices.add(new CountConflict(current));
                state = State.DEAD;
            } else {
                separatorPartial = partial;
                separator = new CountSeparator(reduction.rows(), reduction.lower(), reduction.upper(), budget);
            }
            return;
        }
        if (separator != null) {
            if (!separator.step()) return;
            counts = reduction.expand(separator.counts());
            boolean impossible = separator.infeasible();
            separator.close();
            separator = null;
            if (counts != null) {
                separatorCandidate = true;
                schedulingWork = 0;
                scheduling = new CountSchedule<>(model, counts, budget);
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
                schedulingWork = 0;
                scheduling = new CountSchedule<>(model, counts, budget);
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
                else scheduling = new CountSchedule<>(model, counts, budget);
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
                else scheduling = new CountSchedule<>(model, counts, budget);
            } else if (weightedChoices()) matching = new CountMeetInMiddle(reduction.rows(), reduction.lower(), reduction.upper(), budget);
            else beginBoolean();
            return;
        }
        if (matching != null) {
            if (!matching.step()) return;
            counts = reduction.expand(matching.counts());
            boolean impossible = matching.infeasible();
            matching.close();
            matching = null;
            if (counts != null) {
                if (!preprocessingOnly && refineSupport()) state = State.SPLIT;
                else scheduling = new CountSchedule<>(model, counts, budget);
            } else if (impossible) {
                learnedChoices.add(new CountConflict(current));
                state = State.DEAD;
            } else beginBoolean();
            return;
        }
        if (binary != null) {
            if (!binary.step()) return;
            counts = reduction.expand(binary.counts());
            boolean impossible = binary.infeasible();
            importReducedConflicts(binary.learnedConflicts());
            binary.close();
            binary = null;
            if (counts != null) {
                if (!preprocessingOnly && refineSupport()) state = State.SPLIT;
                else scheduling = new CountSchedule<>(model, counts, budget);
            } else if (impossible) {
                learnedChoices.add(new CountConflict(current));
                state = State.DEAD;
            } else if (current.isEmpty() && !triedCdcl) {
                triedCdcl = true;
                if (viewSearch != null && viewSearch.retained()) {
                    viewSearch.resume(1_048_576);
                    viewStage = 3;
                } else cdcl = new CountDomainSearch(reduction.rows(), reduction.lower(), reduction.upper(), budget, 1_048_576);
            } else afterBoolean();
            return;
        }
        if (auxiliaryLcg != null) {
            if (!auxiliaryLcg.step()) return;
            counts = reduction.expand(auxiliaryLcg.counts());
            boolean impossible = auxiliaryLcg.infeasible();
            importReducedConflicts(auxiliaryLcg.learnedConflicts());
            auxiliaryLcg.close();
            auxiliaryLcg = null;
            if (counts != null) {
                domainCandidate = true;
                schedulingWork = 0;
                scheduling = new CountSchedule<>(model, counts, budget);
            } else if (impossible) {
                learnedChoices.add(new CountConflict(current));
                state = State.DEAD;
            } else state = State.UNRESOLVED;
            return;
        }
        if (cdcl != null) {
            if (!cdcl.step()) return;
            counts = reduction.expand(cdcl.counts());
            boolean impossible = cdcl.infeasible();
            importReducedConflicts(cdcl.learnedConflicts());
            cdcl.close();
            cdcl = null;
            if (counts != null) {
                domainCandidate = true;
                schedulingWork = 0;
                scheduling = new CountSchedule<>(model, counts, budget);
            } else if (impossible) {
                learnedChoices.add(new CountConflict(current));
                state = State.DEAD;
            } else if (auxiliaryMode != 0) state = State.UNRESOLVED;
            else diagram = new CountDecisionDiagram(reduction.rows(), reduction.lower(), reduction.upper(), budget, 65536);
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
                schedulingWork = 0;
                scheduling = new CountSchedule<>(model, counts, budget);
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
            diagram.close();
            diagram = null;
            if (counts != null) {
                domainCandidate = true;
                schedulingWork = 0;
                scheduling = new CountSchedule<>(model, counts, budget);
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
                else scheduling = new CountSchedule<>(model, counts, budget);
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
                if (conflict != null) {
                    List<ExactLinearProgram.Constraint> used = new ArrayList<>();
                    for (int i = conflict.nextSetBit(0); i >= 0; i = conflict.nextSetBit(i + 1)) used.add(current.get(i));
                    learnedChoices.add(new CountConflict(used));
                }
                state = State.DEAD;
                return;
            }
            linearConstraints.addAll(tightened);
            reduction = new CountReduction(linearConstraints, lower, upper, budget, auxiliaryMode == 3);
            if (current.isEmpty() && auxiliaryMode == 0) {
                modelViews = CountModelViews.create(linearConstraints, lower, upper, budget);
                if (modelViews != null) {
                    viewSearch = new CountViewSearch(modelViews, budget);
                    if (!CountBoolean.preferred(linearConstraints, lower, upper, budget)) {
                        viewSearch.resume(32768);
                        viewStage = 1;
                    }
                }
            }
            return;
        }
        if (!compiled) {
            if (!reduction.step()) return;
            compiled = true;
            preprocessingOnly = (reduction.variables() > 64 || reduction.rows().size() > 512) &&
                    !ExactRevisedProgram.extendsDenseAdmission(reduction.variables(), reduction.rows());
            lowerCost = PlanPreference.compiledLowerBound(model, reduction, lower, seeds, budget);
            if (auxiliaryMode == 1) cdcl = new CountDomainSearch(reduction.rows(), reduction.lower(), reduction.upper(), budget, 131072, CountCdcl.Branching.LEARNING_RATE);
            else if (auxiliaryMode >= 2) auxiliaryLcg = new CountLcg(reduction.rows(), reduction.lower(), reduction.upper(), budget, 131072);
            else {
                if (modelViews != null) {
                    modelViews.compileLight();
                    modelViews.addReduced(reduction);
                    // Preserve the specialized Boolean path on its preferred
                    // structure. Other domains get a retained no-LP portfolio.
                    if (!CountBoolean.preferred(reduction.rows(), reduction.lower(), reduction.upper(), budget)) {
                        viewSearch.resume(262144);
                        viewStage = 2;
                        return;
                    }
                }
                congruence = new CountCongruence(reduction.rows(), reduction.variables(), budget);
            }
            return;
        }
        if (!linear.step()) return;
        var status = linear.result();
        ExactRational[] point = reducedLinear ? reduction.expand(linear.point()) : linear.point();
        if (status == ExactLinearProgram.Result.INFEASIBLE && !reducedLinear && !linear.hot() && relaxationCuts.isEmpty()) learnMaterialConflict(linear.certificate());
        if (sharedBasis != null) sharedBasis.close();
        sharedBasis = linear.takeBasis();
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
        if (covering != null || cliques != null || rounding != null) return "count_cuts";
        if (probing != null || branchProbe != null) return "count_probing";
        if (neighborhood != null || repair != null || jumping != null) return "count_heuristics";
        if (recovery != null || scaling != null) return "count_recovery";
        if (components != null || groups != null || conditioning != null || sourceFace != null) return "count_decompose";
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
            inheritedCoordinates = reduction.coordinates();
            beginLinear();
        }
    }

    private boolean earlyCutDomain() {
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

    private void beginCompiledStrategies() {
        if (current.isEmpty() && compileRecovery) recovery = new CountRecovery<>(this);
        else if (current.isEmpty()) beginGroups();
        else if (preprocessingOnly) state = State.UNRESOLVED;
        else beginLinear();
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
        scaling = new CountScale(reduction.rows(), reduction.lower(), reduction.upper(), budget);
    }

    private void beginBoolean() {
        // A local cutoff falls through to other representations, but revisiting
        // the identical rows and bounds would merely repeat the same search.
        if (triedBinary) {
            if (preprocessingOnly) finishCountSearch();
            else beginLinear();
        } else {
            triedBinary = true;
            binary = new CountBoolean(reduction.rows(), reduction.lower(), reduction.upper(), budget);
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
        scheduling = new CountSchedule<>(model, counts, budget);
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
        reducedLinear = reuse && reduction.variables() != model.recipes.size();
        linear = new ExactLinearProgram(reducedLinear ? reduction.variables() : model.recipes.size(),
                relaxationRows(),
                reducedLinear ? reduction.objective(model.objective(true)) : model.objective(true), budget,
                reuse && reduction.coordinates().equals(inheritedCoordinates) ? inheritedBasis : null, reuse);
        if (inheritedBasis != null) {
            inheritedBasis.close();
            inheritedBasis = null;
        }
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
    }

    private void unresolved() {
        plan = null;
        partitionCounts();
        state = State.UNRESOLVED;
    }

    boolean resume() {
        if (limit == null && viewSearch != null && viewSearch.retained()) {
            // Other strategies have had their turn. Continue the same model
            // searches rather than silently closing their unfinished domains.
            counts = null;
            viewSearch.resume(262144);
            viewStage = 4;
            state = State.OPEN;
            return true;
        }
        if (retried || counts == null || memory == 0 || limit != null) return false;
        releaseWorkspace();
        initialized = true;
        retried = rescue = true;
        state = State.OPEN;
        return true;
    }

    void releaseWorkspace() {
        closeViews();
        if (diophantine != null) diophantine.close();
        diophantine = null;
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
        if (neighborhood != null) neighborhood.close();
        neighborhood = null;
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
        Set<K> reachable = new HashSet<>(external);
        stock.forEach((key, value) -> { if (value > 0) reachable.add(key); });
        BitSet fired = new BitSet();
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < counts.length; i++) {
                budget.check();
                if (counts[i].signum() == 0 || fired.get(i)) continue;
                GraphRecipe<K> recipe = model.recipes.get(i);
                if (consumedKeys(recipe).stream().anyMatch(key -> !reachable.contains(key))) continue;
                reachable.addAll(recipe.outputs().keySet());
                fired.set(i);
                changed = true;
            }
        } while (changed);
        for (int blocked = 0; blocked < counts.length; blocked++) if (counts[blocked].signum() > 0 && !fired.get(blocked)) {
            // If this transition is used, a first producer must introduce an
            // initially absent place without itself requiring an absent place.
            // Keep both possibilities: avoid the transition, or include a repair.
            Map<Integer, BigInteger> repairs = new LinkedHashMap<>();
            for (int i = 0; i < counts.length; i++) {
                budget.check();
                GraphRecipe<K> recipe = model.recipes.get(i);
                if (consumedKeys(recipe).stream().allMatch(reachable::contains) &&
                        recipe.outputs().keySet().stream().anyMatch(key -> model.ids.containsKey(key) && !reachable.contains(key)))
                    repairs.put(i, BigInteger.ONE.negate());
            }
            CountGuard conflict = new CountGuard(blocked, new ExactLinearProgram.Constraint(repairs, BigInteger.ONE.negate()));
            learn(conflict);
            branch(conflict);
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

    private Set<K> consumedKeys(GraphRecipe<K> recipe) {
        Set<K> keys = new LinkedHashSet<>(recipe.inputs().keySet());
        keys.removeIf(key -> recipe.inputs().get(key).longValue() == recipe.configurationInputs().getOrDefault(key, 0L));
        return keys;
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
        if (learnedMaterials.size() >= 64) return;
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
        else if (continuation == 3) diagram = new CountDecisionDiagram(reduction.rows(), reduction.lower(), reduction.upper(), budget, 65536);
        else if (continuation == 4) state = State.UNRESOLVED;
        // Stage 1 resumes compilation, preserving the untouched original model.
    }

    private void closeViews() {
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
