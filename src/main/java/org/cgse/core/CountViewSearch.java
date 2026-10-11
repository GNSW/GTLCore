// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Fair, deterministic slices over equivalent representations. Search states
 * survive local handoffs; only a checked witness or a full-domain contradiction
 * is conclusive. No cutoff or view-local clause is exported as a global proof.
 */
final class CountViewSearch implements AutoCloseable {

    private final CountModelViews models;
    private final PlanningBudget budget;
    private final List<Search> searches = new ArrayList<>();
    private final CountPortfolioPolicy policy = new CountPortfolioPolicy();
    private Search active;
    private long work, until;
    private boolean infeasible;
    private BigInteger[] counts;
    private long candidateSequence;
    private CandidateOrigin candidateOrigin;
    private Search candidateSource;
    private boolean strideScoutAttempted;
    private boolean integerLpAttempted;
    private long commonWork, reportedCommonWork, restorationWork;
    private CountPortfolioPolicy.Mode candidateMode;

    void mode(CountPortfolioPolicy.Mode mode) {
        CountPortfolioPolicy.Mode previous = policy.mode();
        policy.mode(mode);
        if (previous == CountPortfolioPolicy.Mode.PROOF && mode != CountPortfolioPolicy.Mode.PROOF)
            for (var view : models.available())
                if (searches.stream().anyMatch(search -> search.view == view) && binary(view)) addSearch(view, Engine.JUMP);
        for (Search search : searches) search.scheduling.eligible = mode != CountPortfolioPolicy.Mode.PROOF || search.engine.provesInfeasibility();
        // A selected candidate-only batch can be retained across a goal change,
        // but cannot consume a proof-only task's allowance.
        if (active != null && !active.scheduling.eligible) active = null;
    }

    void commonWork(long work) {
        if (work < 0) throw new IllegalArgumentException("Negative common work");
        commonWork = work > Long.MAX_VALUE - commonWork ? Long.MAX_VALUE : commonWork + work;
    }

    record CandidateOrigin(long id, String view, String engine) {}

    enum CandidateOutcome {
        SCHEDULE_WITNESS,
        SCHEDULE_DEAD,
        SCHEDULE_UNKNOWN,
        VERIFIED,
        REJECTED,
        UNRESOLVED
    }

    private static final class Search {

        final CountModelViews.View view;
        final CountPortfolioPolicy.Arm scheduling;
        final Engine engine;
        CountLcg solver;
        CountCdcl cdcl;
        CountJump jump;
        CountContinuation specialist;
        BigInteger[] proposal;
        PlanningBudget proposalBudget;
        long proposalBytes;
        long sliceWork, progress, work, candidates, verified, dead, unknown, downstreamWork;
        long continuationLimit, continuationProgress, continuationCheckpoint;
        long publishedRoots;
        long importedBounds;
        int publishedConflicts, importedConflicts;
        int importedCuts, publishedCuts;
        CountModelViews.Domains scope;
        boolean done, batchPending, integerJump;

        Search(CountModelViews.View view, CountPortfolioPolicy.Arm scheduling, Engine engine) {
            this.view = view;
            this.scheduling = scheduling;
            this.engine = engine;
        }

        boolean started() {
            return solver != null || cdcl != null || jump != null || specialist != null || proposal != null;
        }

        boolean paused() {
            return specialist != null ? specialist.paused() : solver != null ? solver.paused() : cdcl != null ? cdcl.paused() : jump != null && jump.paused();
        }

        void resume(long quantum, PlanningBudget budget, CountModelViews models) {
            if (proposal != null) return;
            var domains = started() ? null : models.domains(view);
            if (domains != null) scope = domains;
            if (domains != null && domains.version() > 0)
                budget.note("count_view_facts", "destination=" + view.name() + "; engine=" + engine + "; imported_version=" + domains.version());
            if (specialist != null) specialist.resume(quantum);
            else if (engine == Engine.SEPARATOR) {
                specialist = new CountSeparator(view.rows(), domains.lower(), domains.upper(), budget, quantum).retained();
            } else if (engine == Engine.MITM) {
                specialist = new CountMeetInMiddle(view.rows(), domains.lower(), domains.upper(), budget, quantum).retained();
            } else if (engine == Engine.PB) {
                if (cdcl == null) cdcl = new CountCdcl(view.rows(), domains.lower(), domains.upper(), budget, quantum).retained();
                else cdcl.resume(quantum);
                int imported = models.importConflicts(view, importedConflicts, this, cdcl);
                importedConflicts = models.conflictVersion();
                if (imported > 0) budget.note("count_view_conflicts", "destination=" + view.name() + "; engine=" + engine + "; imported=" + imported);
            } else if (engine == Engine.JUMP) {
                if (jump == null) jump = new CountJump(view.rows(), domains.lower(), domains.upper(), budget, quantum).retained();
                else jump.resume(quantum);
            } else {
                if (solver == null) {
                    solver = new CountLcg(view.rows(), domains.lower(), domains.upper(), budget, quantum);
                    if (models.sharesCuts(view)) solver.shareRows();
                    if (engine == Engine.LOCKS) solver.lockBranching();
                    if (engine == Engine.INTEGER_LP) solver.integerRelaxation();
                } else solver.resume(quantum);
                int bounds = models.importProofBounds(view, importedBounds, solver);
                importedBounds = models.boundVersion();
                if (bounds > 0) budget.note("count_view_facts", "destination=" + view.name() + "; engine=" + engine + "; certified_bounds=" + bounds);
                int imported = models.importConflicts(view, importedConflicts, this, solver);
                importedConflicts = models.conflictVersion();
                if (imported > 0) budget.note("count_view_conflicts", "destination=" + view.name() + "; engine=" + engine + "; imported=" + imported);
                int linear = models.importCuts(view, importedCuts, this, solver);
                importedCuts = models.cutVersion();
                if (linear > 0) budget.note("count_view_cuts", "destination=" + view.name() + "; engine=" + engine + "; imported=" + linear);
            }
        }

        void publish(CountModelViews models) {
            var learned = solver != null ? solver.learnedConflicts() : cdcl != null ? cdcl.learnedConflicts() : List.<CountConflict>of();
            models.publishConflicts(view, scope, learned, publishedConflicts, this);
            publishedConflicts = learned.size();
            if (solver != null) {
                var cuts = solver.sharedRows();
                models.publishCuts(view, cuts, publishedCuts, this);
                publishedCuts = cuts.size();
            }
        }

        void close() {
            if (solver != null) solver.close();
            if (cdcl != null) cdcl.close();
            if (jump != null) jump.close();
            if (specialist != null) specialist.close();
            if (proposalBudget != null) proposalBudget.release(proposalBytes);
            proposalBudget = null;
            proposalBytes = 0;
            proposal = null;
            solver = null;
            cdcl = null;
            jump = null;
            specialist = null;
        }

        boolean step() {
            if (proposal != null) return true;
            if (specialist != null) return specialist.step();
            return cdcl != null ? cdcl.step() : jump != null ? jump.step() : solver.step();
        }

        long progress() {
            if (proposal != null) return 1;
            if (specialist != null) return specialist.progress();
            return cdcl != null ? cdcl.progress() : jump != null ? jump.progress() : solver.progress();
        }

        BigInteger[] counts() {
            if (proposal != null) return proposal;
            if (specialist != null) return specialist.counts();
            return cdcl != null ? cdcl.counts() : jump != null ? jump.counts() : solver.counts();
        }

        boolean infeasible() {
            // These brief scouts only propose original-coordinate candidates.
            // Their cutoff or local proof never displaces the retained engines.
            if (specialist != null) return false;
            return cdcl != null ? cdcl.infeasible() : solver != null && solver.infeasible();
        }
    }

    private enum Engine {

        LCG,
        INTEGER_LP,
        INTEGER_LP_HINT,
        PB,
        LOCKS,
        JUMP,
        SEPARATOR,
        MITM,
        DOMAIN,
        DIAGRAM,
        BOOLEAN,
        DIOPHANTINE;

        boolean provesInfeasibility() {
            return this == LCG || this == INTEGER_LP || this == PB || this == LOCKS;
        }
    }

    CountViewSearch(CountModelViews models, PlanningBudget budget) {
        this.models = models;
        this.budget = budget;
    }

    void resume(long allowance) {
        if (allowance <= 0 || infeasible) throw new IllegalStateException("Invalid view search continuation");
        counts = null;
        until = work + Math.min(allowance, budget.remainingWork() / 4);
        // An adopted specialist must not suppress the proving engines' first
        // visit. Once those exist, do not rescan domains at every handoff.
        for (var view : models.available()) if (searches.stream().noneMatch(search -> search.view == view && search.engine == Engine.LCG)) {
            boolean binary = binary(view);
            addSearch(view, Engine.LCG);
            // The weighted Boolean engine has different propagation, phase
            // saving and conflicts. Retain that complementary search too;
            // repeatedly restarting a short PB attempt discards its learning.
            if (binary) {
                addSearch(view, Engine.PB);
                addSearch(view, Engine.LOCKS);
                if (policy.mode() != CountPortfolioPolicy.Mode.PROOF) addSearch(view, Engine.JUMP);
            }
        }
        addStrideScout();
    }

    /** Do not displace cheap compilation or existing engines' initial visits with another LCG. */
    private boolean addIntegerLp() {
        if (integerLpAttempted || !models.compiledAlternatives()) return false;
        integerLpAttempted = true;
        var candidates = new ArrayList<>(models.available());
        candidates.sort(Comparator.comparingLong(view -> view.shape().cost()));
        for (var view : candidates) if (integerLp(view)) {
            long limit = Math.min(16384, budget.remainingWork() / 32);
            if (limit < 4096) return false;
            var domains = models.domains(view);
            long started = budget.threadWork();
            try (var probe = new CountIntegerLpLearning();
                 var result = probe.solve(view.rows(), domains.lower(), domains.upper(), -1, budget, limit)) {
                if (result == null) return false;
                boolean added = false;
                // An exact count witness is useful even when no new cut exists.
                // Checking it consumes the scout's existing allowance, and the
                // one-shot proposal cannot prove infeasibility or restart LCG.
                if (policy.mode() != CountPortfolioPolicy.Mode.PROOF)
                    added = retainLpProposal(view, domains, result.integerPoint, limit - (budget.threadWork() - started));
                if (result.cut != null && !view.rows().contains(result.cut.row())) {
                    addSearch(view, Engine.INTEGER_LP);
                    models.publishCuts(view, List.of(result.cut), 0, this);
                    budget.note("count_integer_lp_scout", "certified_cut; retained_arm; view=" + view.name());
                    added = true;
                }
                return added;
            }
        }
        return false;
    }

    /** Complete missing coordinates only as a candidate, then check every original view row and domain. */
    private boolean retainLpProposal(CountModelViews.View view, CountModelViews.Domains domains,
                                     BigInteger[] suggested, long limit) {
        if (suggested == null || suggested.length != domains.lower().length || limit <= 0) return false;
        long started = budget.threadWork(), bytes = 256L + 8L * suggested.length;
        for (int i = 0; i < suggested.length; i++) {
            if (budget.threadWork() - started >= limit) return false;
            budget.check();
            var value = suggested[i] == null ? domains.lower()[i] : suggested[i];
            bytes += 48L + (value.bitLength() + 7L) / 8;
        }
        if (bytes > budget.availableBytes() / 8 || !budget.tryReserve(bytes)) return false;
        try {
            var point = suggested.clone();
            for (int i = 0; i < point.length; i++) {
                if (budget.threadWork() - started >= limit) return false;
                budget.check();
                if (point[i] == null) point[i] = domains.lower()[i];
                if (point[i].compareTo(domains.lower()[i]) < 0 ||
                        domains.upper()[i] != null && point[i].compareTo(domains.upper()[i]) > 0) return false;
            }
            for (var row : view.rows()) {
                if (budget.threadWork() - started >= limit) return false;
                budget.check();
                var total = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) {
                    if (budget.threadWork() - started >= limit) return false;
                    budget.check();
                    var value = point[term.getKey()];
                    budget.operation(PlanningBudget.Operation.INTEGER, Math.max(value.bitLength(), term.getValue().bitLength()));
                    total = total.add(value.multiply(term.getValue()));
                }
                if (total.compareTo(row.upper()) > 0) return false;
            }
            Search search = new Search(view, policy.add(view.shape().cost(), Engine.INTEGER_LP_HINT), Engine.INTEGER_LP_HINT);
            search.scope = domains;
            search.proposal = point;
            search.proposalBudget = budget;
            search.proposalBytes = bytes;
            searches.add(search);
            bytes = 0;
            budget.note("count_integer_lp_scout", "checked_point; candidate_only; view=" + view.name());
            return true;
        } finally { budget.release(bytes); }
    }

    /** One complementary arm, admitted by bounded row support rather than the whole LP matrix. */
    private boolean integerLp(CountModelViews.View view) {
        long start = budget.threadWork(), limit = Math.min(16384, budget.remainingWork() / 64);
        if (limit < 1024 || budget.remainingWork() < 65536) return false;
        for (var row : view.rows()) {
            if (row.terms().size() < 2 || row.terms().size() > 128) continue;
            boolean bounded = row.upper().bitLength() <= 1024, integer = false, weighted = false;
            for (var term : row.terms().entrySet()) {
                if (budget.threadWork() - start >= limit) return false;
                budget.check();
                int id = term.getKey();
                var high = view.upper()[id];
                var low = view.lower()[id];
                if (high == null || high.bitLength() > 1024 || low.bitLength() > 1024 || term.getValue().bitLength() > 1024) {
                    bounded = false;
                    break;
                }
                if (!low.equals(high)) {
                    integer |= high.compareTo(BigInteger.ONE) > 0 || low.signum() < 0;
                    weighted |= term.getValue().abs().compareTo(BigInteger.ONE) > 0;
                }
            }
            if (bounded && integer && weighted) return true;
        }
        return false;
    }

    /** Transfer an already-paid frontier, only in its unchanged reduced coordinate scope. */
    boolean retain(CountReduction reduction, CountContinuation frontier) {
        if (!frontier.paused()) return false;
        var view = models.reduced();
        if (view == null) {
            models.addReduced(reduction);
            view = models.reduced();
        }
        if (view == null || !frontier.matches(view.rows(), view.lower(), view.upper())) return false;
        Engine engine = frontier instanceof CountSeparator ? Engine.SEPARATOR :
                frontier instanceof CountMeetInMiddle ? Engine.MITM :
                frontier instanceof CountDomainSearch ? Engine.DOMAIN :
                frontier instanceof CountDecisionDiagram ? Engine.DIAGRAM :
                frontier instanceof CountDiophantine ? Engine.DIOPHANTINE :
                frontier instanceof CountBoolean ? Engine.BOOLEAN : null;
        if (engine == null) throw new IllegalArgumentException("Unknown count continuation");
        for (Search search : searches) if (search.view == view && search.engine == engine) return false;
        Search search = new Search(view, policy.add(view.shape().cost(), engine), engine);
        search.specialist = frontier;
        search.scope = new CountModelViews.Domains(view.lower(), view.upper(), 0);
        search.progress = frontier.progress();
        search.work = frontier.work();
        search.scheduling.eligible = policy.mode() != CountPortfolioPolicy.Mode.PROOF;
        policy.selected(search.scheduling);
        policy.feedback(search.scheduling, frontier.work(), frontier.progress());
        searches.add(search);
        budget.note("count_continuation", "adopted; engine=" + engine.name().toLowerCase(Locale.ROOT) +
                "; work=" + frontier.work() + "; same_model_and_domains");
        return true;
    }

    private void addSearch(CountModelViews.View view, Engine engine) {
        if (searches.stream().noneMatch(search -> search.view == view && search.engine == engine))
            searches.add(new Search(view, policy.add(view.shape().cost(), engine), engine));
    }

    private void addStrideScout() {
        if (policy.mode() == CountPortfolioPolicy.Mode.PROOF) return;
        if (strideScoutAttempted) return;
        var views = models.available();
        var stride = views.stream().filter(view -> view.name().equals("stride")).findFirst().orElse(null);
        if (stride == null) return;
        strideScoutAttempted = true;
        // Compare with the coordinates the existing specialist paths consume,
        // rather than treating ordinary affine elimination as a stride benefit.
        var reduced = models.reduced();
        if (reduced == null) return;
        Engine engine = null;
        if (separatorDomains(stride) && !separatorDomains(reduced)) engine = Engine.SEPARATOR;
        else if (CountMeetInMiddle.scoutWork(stride.rows(), stride.lower(), stride.upper(), budget) > 0 &&
                CountMeetInMiddle.scoutWork(reduced.rows(), reduced.lower(), reduced.upper(), budget) == 0)
            engine = Engine.MITM;
        if (engine != null) {
            searches.add(new Search(stride, policy.add(stride.shape().cost(), engine), engine));
            budget.note("count_stride_scout", "new_domain_admission; engine=" + engine.name().toLowerCase(Locale.ROOT) + "; bounded_candidate_only");
        }
    }

    private boolean separatorDomains(CountModelViews.View view) {
        if (view.lower().length > 256 || view.rows().size() > 2048) return false;
        for (int i = 0; i < view.lower().length; i++) {
            budget.check();
            if (view.upper()[i] == null) return false;
            BigInteger width = view.upper()[i].subtract(view.lower()[i]);
            if (width.signum() < 0 || width.compareTo(BigInteger.valueOf(31)) > 0) return false;
        }
        return true;
    }

    private boolean binary(CountModelViews.View view) {
        if (view.lower().length > 1024 || view.rows().size() > 4096 || view.shape().terms() > 65536) return false;
        for (int i = 0; i < view.lower().length; i++) {
            budget.check();
            if (view.upper()[i] == null || view.upper()[i].subtract(view.lower()[i]).compareTo(BigInteger.ONE) > 0) return false;
        }
        return true;
    }

    boolean step() {
        if (counts != null || infeasible || work >= until) return true;
        long before = budget.threadWork();
        boolean completedSlice = false;
        boolean rejectedRestoration = false;
        long gained = 0;
        long integerScoutWork = 0;
        try {
            if (active == null) {
                active = select();
                if (active == null) return true;
            }
            if (!active.batchPending) {
                // The parent allowance controls when step() yields, not when a
                // retained solver finishes its batch. Carry unfinished batches
                // across parent handoffs and publish feedback only on completion.
                long quantum = policy.quantum(active.scheduling, budget.remainingWork());
                // The Boolean walk crosses temporary violation barriers before
                // establishing a new best point. Keep its full bounded batch.
                // A later general-integer scout starts with the policy's normal
                // sample; bypassing that limit can displace established exact
                // searches under a tight request budget.
                if (active.engine == Engine.JUMP && !active.integerJump) quantum = Math.min(CountPortfolioPolicy.MAX_QUANTUM, budget.remainingWork());
                if (quantum <= 0) {
                    // Another worker can consume the shared last unit between
                    // parent handoffs. Report budget exhaustion, not an invalid
                    // resume(0) on a retained solver.
                    budget.check();
                    active = null;
                    return true;
                }
                if (!active.started() && Math.min(quantum, until - work) < 1024) {
                    active = null;
                    return true;
                }
                policy.selected(active.scheduling);
                active.sliceWork = 0;
                active.resume(quantum, budget, models);
                active.batchPending = true;
            }
            if (!active.step()) return false;
            long progress = active.progress();
            gained = Math.max(0, progress - active.progress);
            active.progress = progress;
            completedSlice = true;
            BigInteger[] candidate = active.counts();
            infeasible = active.infeasible() && active.view.semantics().transfersProof();
            if (!active.infeasible() && active.solver != null && active.solver.rootVersion() > active.publishedRoots && models.sharesBounds(active.view)) {
                models.publishBounds(active.view, active.solver.rootLower(), active.solver.rootUpper());
                active.publishedRoots = active.solver.rootVersion();
            }
            if (!infeasible) active.publish(models);
            if (candidate != null) {
                long restoreStarted = budget.threadWork();
                counts = models.restoreAndCheck(active.view, candidate);
                restorationWork = budget.threadWork() - restoreStarted;
                if (counts != null) {
                    candidateMode = active.scheduling.selectedMode;
                    candidateSource = active;
                    candidateOrigin = new CandidateOrigin(++candidateSequence, active.view.name(), active.engine.name().toLowerCase(Locale.ROOT));
                    active.candidates++;
                } else rejectedRestoration = true;
            }
            if (!active.paused()) {
                active.done = true;
                active.scheduling.retired = true;
                active.close();
            }
            budget.note("count_view", active.view.name() + "; engine=" + active.engine.name().toLowerCase(Locale.ROOT) + "; slices=" + policy.selections(active.scheduling) + "; progress=" + gained + "; witness=" + (counts != null) +
                    "; proven_infeasible=" + infeasible + "; retained=" + !active.done);
            // Let complementary arithmetic/source strategies run when every
            // live representation has stalled. A later resume keeps the exact
            // queues and clauses; this handoff is neither failure nor closure.
            boolean stalled = true, sampled = true;
            for (Search search : searches) {
                int idle = search == active && search.scheduling.selectedMode == policy.mode() ? gained > 0 ? 0 : policy.idleSlices(search.scheduling) + 1 : policy.idleSlices(search.scheduling);
                if (!search.done && search.scheduling.eligible && idle < 2) stalled = false;
                if (!search.done && search.scheduling.eligible && policy.selections(search.scheduling) < 2) sampled = false;
            }
            if ((stalled || sampled) && counts == null && !infeasible) {
                long scoutStarted = budget.threadWork();
                boolean added;
                try { added = addIntegerLp(); }
                finally {
                    integerScoutWork = budget.threadWork() - scoutStarted;
                    commonWork(integerScoutWork);
                }
                if (added) stalled = false;
                else if (addIntegerJump()) stalled = false;
            }
            return counts != null || infeasible || stalled;
        } finally {
            long spent = budget.threadWork() - before;
            work += spent;
            if (active != null) {
                // A new engine's admission/proof preparation is common work,
                // not a cost penalty on the engine whose slice just completed.
                active.work += spent - integerScoutWork;
                active.sliceWork += spent - integerScoutWork;
                if (completedSlice) policy.feedback(active.scheduling, active.sliceWork, gained);
                // Include the decisive solver step; publishing before this
                // accounting made a one-step proof appear to cost no work.
                if (completedSlice && infeasible) {
                    long common = takeCommonWork();
                    policy.candidateFeedback(CountPortfolioPolicy.Mode.PROOF, active.scheduling, active.work, common, true, true);
                    budget.note("count_candidate_cost", "mode=PROOF; solver_work=" + active.work + "; common_work=" + common + "; outcome=PROVEN_INFEASIBLE");
                } else if (rejectedRestoration) {
                    long common = takeCommonWork();
                    policy.candidateFeedback(active.scheduling.selectedMode, active.scheduling,
                            Math.max(0, active.work - restorationWork), saturatedAdd(common, restorationWork), true, false);
                    budget.note("count_candidate_cost", "mode=" + active.scheduling.selectedMode + "; common_work=" + common + "; restore_work=" + restorationWork + "; outcome=RESTORE_REJECTED");
                }
                if (active.done || !active.started() || active.paused()) {
                    active.batchPending = false;
                    active = null;
                }
            }
        }
    }

    private boolean addIntegerJump() {
        if (policy.mode() == CountPortfolioPolicy.Mode.PROOF) return false;
        // Give general integer local search one bounded initial opportunity
        // after the exact views have each received an initial and a resumed
        // sample, so initialization alone does not trigger the extra arm.
        // Learned conflicts do not establish that a first witness is close;
        // requiring every view
        // to stop learning at the same time could postpone this arm forever.
        // Later work shares the usual fair policy; do not duplicate the walk
        // on every equivalent representation.
        if (searches.stream().anyMatch(search -> search.engine == Engine.JUMP)) return false;
        var view = models.available().stream().filter(candidate -> candidate.shape().variables() <= 1024 &&
                candidate.rows().size() <= 4096 && candidate.shape().terms() <= 65536)
                .min(Comparator.comparingLong(candidate -> candidate.shape().cost())).orElse(null);
        if (view == null) return false;
        Search search = new Search(view, policy.add(view.shape().cost(), Engine.JUMP), Engine.JUMP);
        search.integerJump = true;
        searches.add(search);
        return true;
    }

    private Search select() {
        // Pay each eligible engine's first visit before extending an already
        // productive exact-elimination path. A learned conflict alone never
        // turns a witness task into a proof task or disables other engines.
        boolean sampled = searches.stream().noneMatch(search -> !search.done && search.scheduling.eligible &&
                policy.selections(search.scheduling) == 0);
        if (sampled) for (Search search : searches) {
            if (search.done || !search.scheduling.eligible || !(search.specialist instanceof CountDiophantine numeric) ||
                    !numeric.hasCoupledElimination()) continue;
            if (search.continuationLimit == 0) {
                long remaining = budget.remainingWork();
                search.continuationLimit = saturatedAdd(search.work, remaining - remaining / 8);
                search.continuationCheckpoint = search.work;
                search.continuationProgress = search.progress;
            }
            if (search.progress > search.continuationProgress) {
                search.continuationCheckpoint = search.work;
                search.continuationProgress = search.progress;
            }
            // The one-time work ceiling and a charged-work stagnation window
            // bound this preference. Ordinary selection resumes after either;
            // the retained state and its undecided domain are never discarded.
            if (search.work < search.continuationLimit && search.work - search.continuationCheckpoint < 262144)
                return search;
        }
        var chosen = policy.select();
        return searches.stream().filter(search -> search.scheduling == chosen).findFirst().orElse(null);
    }

    BigInteger[] counts() {
        return counts == null ? null : counts.clone();
    }

    CandidateOrigin candidateOrigin() {
        return candidateOrigin;
    }

    /** Request-local selection feedback; proof domains and mandatory exploration are unchanged. */
    void feedback(CandidateOrigin origin, CandidateOutcome outcome, long downstreamWork) {
        feedback(origin, outcome, downstreamWork, true);
    }

    void feedback(CandidateOrigin origin, CandidateOutcome outcome, long downstreamWork, boolean improved) {
        if (!Objects.equals(origin, candidateOrigin) || candidateSource == null) return;
        Search source = candidateSource;
        boolean terminal = outcome != CandidateOutcome.SCHEDULE_WITNESS;
        if (outcome == CandidateOutcome.VERIFIED) source.verified++;
        else if (outcome == CandidateOutcome.SCHEDULE_DEAD) source.dead++;
        else if (outcome == CandidateOutcome.SCHEDULE_UNKNOWN || outcome == CandidateOutcome.UNRESOLVED) source.unknown++;
        if (terminal) source.downstreamWork += downstreamWork;
        // Attribute each common compilation charge once. Dividing the full
        // lifetime total by successive candidate ids counted it harmonically.
        long sharedCost = terminal ? takeCommonWork() : 0;
        long overhead = saturatedAdd(sharedCost, saturatedAdd(restorationWork, downstreamWork));
        if (terminal) policy.candidateFeedback(candidateMode, source.scheduling, Math.max(0, source.work - restorationWork), overhead,
                candidateMode != CountPortfolioPolicy.Mode.PROOF && (outcome == CandidateOutcome.VERIFIED || outcome == CandidateOutcome.SCHEDULE_DEAD || outcome == CandidateOutcome.REJECTED),
                candidateMode != CountPortfolioPolicy.Mode.PROOF && outcome == CandidateOutcome.VERIFIED &&
                        (candidateMode != CountPortfolioPolicy.Mode.IMPROVEMENT || improved));
        budget.note("count_candidate", "id=" + origin.id() + "; view=" + origin.view() + "; engine=" + origin.engine() +
                "; outcome=" + outcome + "; solver_work=" + source.work + "; downstream_work=" + downstreamWork +
                "; candidates=" + source.candidates + "; verified=" + source.verified + "; dead=" + source.dead +
                "; unknown=" + source.unknown + "; total_downstream_work=" + source.downstreamWork +
                "; selection_efficiency=" + policy.candidateEfficiency(source.scheduling));
        if (terminal) budget.note("count_candidate_cost", "mode=" + candidateMode + "; common_work=" + sharedCost + "; restore_work=" + restorationWork + "; downstream_work=" + downstreamWork);
        if (terminal) {
            candidateOrigin = null;
            candidateSource = null;
        }
    }

    private static long saturatedAdd(long a, long b) {
        return b > Long.MAX_VALUE - a ? Long.MAX_VALUE : a + b;
    }

    private long takeCommonWork() {
        long pending = commonWork - reportedCommonWork;
        reportedCommonWork = commonWork;
        return pending;
    }

    boolean infeasible() {
        return infeasible;
    }

    boolean retained() {
        return searches.stream().anyMatch(search -> !search.done && search.scheduling.eligible) ||
                models.available().stream().anyMatch(view -> searches.stream().noneMatch(search -> search.view == view));
    }

    @Override
    public void close() {
        for (var search : searches) search.close();
        searches.clear();
        active = null;
    }
}
