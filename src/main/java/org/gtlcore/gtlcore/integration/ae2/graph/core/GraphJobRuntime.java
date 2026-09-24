package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Single owner of a graph task. All methods are called on the server thread. */
public final class GraphJobRuntime<K> {

    public enum State {
        RUNNING,
        PAUSED,
        SETTLING,
        COMPLETED,
        CANCELLING,
        CANCELLED,
        NEEDS_ATTENTION
    }

    public enum Outcome {
        ACCEPTED,
        REJECTED,
        BLOCKED,
        IN_DOUBT
    }

    public interface Adapter<K> {

        /** Zero means blocked; must not transfer inputs. */
        long capacity(GraphRecipe<K> recipe, long requested);

        /** Inputs are in escrow. False/rejected must mean no input-transfer side effects. */
        Outcome push(GraphRecipe<K> recipe, long runs, Map<K, Long> inputs);

        long deliver(K key, long amount);

        long refund(K key, long amount);
    }

    private GraphPlan<K> plan;
    private final ResourceLedger<K> owned;
    private PlanCursor cursor;
    private DagScheduler<K> dag;
    private PipelineScheduler<K> pipeline;
    private final Map<K, Long> expected = new LinkedHashMap<>();
    private final OutputObligations<K> obligations;
    private final String recoveryOwner;
    private long recoveryStage;
    private final Map<String, Long> acceptedRuns = new LinkedHashMap<>();
    private final Map<String, Long> committedHistory = new LinkedHashMap<>();
    private boolean replanning;
    private long replanEpoch;
    private final Map<String, Long> pendingRuns = new LinkedHashMap<>();
    private final Map<K, Long> pendingOutputs = new LinkedHashMap<>();
    private final Set<K> changedKeys = new LinkedHashSet<>();
    private Map<K, Long> uncertainInputs = Map.of();
    private Map<K, Long> preparedOutputs;
    private Map<K, Long> preparedInputs;
    private Map<K, Long> synchronousReturns;
    private String preparingRecipe;
    private long preparingRuns;
    private Map<K, Long> preparingBatchOutputs;
    private Map<K, Long> settlementEscrow;
    private Deque<K> refundQueue;
    private State state = State.RUNNING;
    private boolean suspended;
    private boolean cancelRequested;
    private String reason = "";
    private long remainingDelivery;
    private long dispatches, rejections, checks;
    private long version;

    public GraphJobRuntime(GraphPlan<K> plan, Map<K, Long> initial, Map<K, Long> emitted) {
        PlanVerifier.verify(plan);
        this.plan = plan;
        this.owned = new ResourceLedger<>(initial);
        this.cursor = new PlanCursor(plan.steps());
        this.dag = DagScheduler.create(plan, Map.of());
        this.pipeline = dag == null ? new PipelineScheduler<>(cursor, plan.recipes(), List.of()) : null;
        this.expected.putAll(GraphRecipe.amounts(emitted));
        this.obligations = new OutputObligations<>(emitted);
        recoveryOwner = UUID.randomUUID().toString();
        plan.initial().forEach((key, amount) -> {
            if (CheckedAmounts.add(initial.getOrDefault(key, 0L), emitted.getOrDefault(key, 0L)) != amount)
                throw new IllegalArgumentException("Initial material ownership mismatch");
        });
        remainingDelivery = plan.amount();
        initializePending();
    }

    public GraphJobRuntime(Snapshot<K> saved) {
        this.plan = saved.plan();
        PlanVerifier.verify(plan);
        this.owned = new ResourceLedger<>(saved.owned());
        this.cursor = new PlanCursor(plan.steps(), saved.cursor());
        expected.putAll(GraphRecipe.amounts(saved.expected()));
        obligations = new OutputObligations<>(saved.obligations());
        if (!obligations.all().equals(GraphRecipe.amounts(expected))) throw new IllegalArgumentException("Output accounts disagree");
        recoveryOwner = saved.recovery().owner();
        recoveryStage = saved.recovery().stage();
        committedHistory.putAll(saved.committedHistory());
        if (!saved.recovery().seeds().equals(plan.seeds())) throw new IllegalArgumentException("Recovery contract changed");
        uncertainInputs = GraphRecipe.amounts(saved.uncertainInputs());
        Map<String, Long> plannedRuns = plan.patternTimes();
        saved.acceptedRuns().forEach((id, count) -> {
            if (count < 0 || count > plannedRuns.getOrDefault(id, 0L)) throw new IllegalArgumentException("Invalid accepted count");
            acceptedRuns.put(id, count);
        });
        Map<String, Long> remainingCounts = new LinkedHashMap<>(plannedRuns);
        acceptedRuns.forEach((id, count) -> remainingCounts.compute(id, (key, amount) -> amount - count));
        remainingCounts.values().removeIf(value -> value == 0);
        this.dag = DagScheduler.create(plan, acceptedRuns);
        this.pipeline = dag == null ? new PipelineScheduler<>(cursor, plan.recipes(), saved.pipeline()) : null;
        if (dag == null && !remainingCounts.equals(pipeline.remainingCounts())) throw new IllegalArgumentException("Cursor and accepted batch counts disagree");
        if (dag != null && (!saved.cursor().isEmpty() || !saved.pipeline().isEmpty())) throw new IllegalArgumentException("Unexpected serial cursor for DAG");
        remainingDelivery = CheckedAmounts.nonNegative(saved.remainingDelivery());
        if (remainingDelivery > plan.amount()) throw new IllegalArgumentException("Invalid delivery remainder");
        state = saved.state();
        suspended = saved.suspended();
        reason = saved.reason();
        if ((!uncertainInputs.isEmpty() || obligations.ambiguous()) && state != State.CANCELLING && state != State.CANCELLED && state != State.COMPLETED) {
            state = State.NEEDS_ATTENTION;
            if (reason.isEmpty()) reason = "RECOVERED_IN_DOUBT";
        }
        initializePending();
    }

    private void initializePending() {
        if (state == State.CANCELLING || finished()) return;
        plan.patternTimes().forEach((id, count) -> {
            long remaining = count - acceptedRuns.getOrDefault(id, 0L);
            if (remaining == 0) return;
            pendingRuns.put(id, remaining);
            plan.recipes().get(id).outputs().forEach((key, amount) -> pendingOutputs.merge(key,
                    CheckedAmounts.multiply(amount, remaining), CheckedAmounts::add));
        });
        changedKeys.addAll(owned.snapshot().keySet());
        changedKeys.addAll(expected.keySet());
    }

    public int tick(Adapter<K> adapter, long tick, int workBudget) {
        if (preparedOutputs != null || settlementEscrow != null) return 0;
        if (state == State.SETTLING || state == State.CANCELLING) {
            settle(adapter, workBudget);
            return 0;
        }
        if (state != State.RUNNING || suspended || replanning) return 0;
        int pushed = 0;
        long deadline = System.nanoTime() + 2_000_000L;
        int checkBudget = (int) Math.min(4096L, Math.max(0L, workBudget) + PipelineScheduler.WINDOW);
        for (int work = 0; pushed < workBudget && work < checkBudget && state == State.RUNNING; work++) {
            if (work > 0 && System.nanoTime() - deadline >= 0) break;
            checks++;
            PlanStep.Batch step = dag == null ? pipeline.poll(tick) : dag.poll(tick);
            if (step == null) {
                if (expected.isEmpty() && (dag == null ? pipeline.finished() : dag.finished())) {
                    if (!seedsHeld()) {
                        state = State.NEEDS_ATTENTION;
                        reason = "RECOVERY_UNFUNDED";
                        changed();
                        break;
                    }
                    state = State.SETTLING;
                    changed();
                    settle(adapter, workBudget - pushed);
                }
                break;
            }
            GraphRecipe<K> recipe = plan.recipes().get(step.recipe());
            if (!obligations.capacityAvailable()) {
                reason = "WAIT_IN_FLIGHT_LIMIT";
                if (dag != null) dag.retry(tick + 5);
                else pipeline.retry(tick + 5);
                break;
            }
            long batch = step.runs();
            Map<K, Long> recipeInputs = recipe.inputs();
            for (var input : recipeInputs.entrySet()) {
                long fixed = recipe.configurationInputs().getOrDefault(input.getKey(), 0L);
                long available = owned.get(input.getKey());
                if (available < fixed) batch = 0;
                else if (input.getValue() > fixed) batch = Math.min(batch, (available - fixed) / (input.getValue() - fixed));
            }
            boolean missingInput = batch == 0;
            for (var output : recipe.outputs().entrySet()) {
                batch = Math.min(batch, Long.MAX_VALUE / output.getValue());
                long fixed = recipe.configurationInputs().getOrDefault(output.getKey(), 0L);
                long net = output.getValue() - (recipeInputs.getOrDefault(output.getKey(), 0L) - fixed);
                if (net > 0) {
                    long future = CheckedAmounts.add(owned.get(output.getKey()), expected.getOrDefault(output.getKey(), 0L));
                    batch = Math.min(batch, java.math.BigInteger.valueOf(Long.MAX_VALUE - future).add(java.math.BigInteger.valueOf(fixed))
                            .divide(java.math.BigInteger.valueOf(net)).min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).longValueExact());
                }
            }
            if (batch == 0) {
                reason = "WAIT_INPUT";
                if (dag != null) {
                    // Actual input insertion already wakes precisely its consumers.
                    // Only capacity/provider waits need polling without a callback.
                    if (!missingInput) dag.retry(tick + 5);
                } else if (!missingInput) pipeline.retry(tick + 5);
                continue;
            }
            if (pipeline != null) {
                batch = pipeline.limit(batch, key -> CheckedAmounts.add(owned.get(key), expected.getOrDefault(key, 0L)));
                if (batch == 0) {
                    reason = "WAIT_PREFIX_RESERVATION";
                    continue;
                }
            }
            try {
                batch = Math.min(batch, Math.max(0, adapter.capacity(recipe, batch)));
            } catch (RuntimeException e) {
                state = State.NEEDS_ATTENTION;
                reason = "PREFLIGHT_FAILED: " + e.getClass().getSimpleName();
                changed();
                break;
            }
            if (batch == 0) {
                reason = "WAIT_PROVIDER_OR_ENERGY";
                if (dag != null) dag.retry(tick + 5);
                else pipeline.retry(tick + 5);
                continue;
            }
            Map<K, Long> outputs = new LinkedHashMap<>();
            for (var output : recipe.outputs().entrySet()) outputs.put(output.getKey(), CheckedAmounts.multiply(output.getValue(), batch));
            Map<K, Long> combined = new LinkedHashMap<>(expected);
            outputs.forEach((key, amount) -> combined.merge(key, amount, CheckedAmounts::add));
            Map<K, Long> escrow = owned.take(recipe.dispatchInputs(batch), 1);
            changedKeys.addAll(escrow.keySet());
            changedKeys.addAll(outputs.keySet());
            preparedOutputs = combined;
            preparedInputs = escrow;
            synchronousReturns = new LinkedHashMap<>();
            preparingRecipe = recipe.id();
            preparingRuns = batch;
            preparingBatchOutputs = outputs;
            Outcome outcome;
            try {
                outcome = adapter.push(recipe, batch, escrow);
            } catch (RuntimeException e) {
                outcome = Outcome.IN_DOUBT;
                reason = "DISPATCH_IN_DOUBT: " + e.getClass().getSimpleName();
            }
            if (outcome == Outcome.REJECTED || outcome == Outcome.BLOCKED) {
                // Returns owed by an older in-flight DAG batch can arrive during
                // an unrelated rejected push. Only excess callbacks are ambiguous.
                for (var returned : synchronousReturns.entrySet()) if (returned.getValue() > expected.getOrDefault(returned.getKey(), 0L)) outcome = Outcome.IN_DOUBT;
            }
            if (outcome == Outcome.ACCEPTED) {
                obligations.dispatch(recipe.id(), batch, outputs, false);
                synchronousReturns.forEach(obligations::returned);
                recoveryStage = CheckedAmounts.add(recoveryStage, 1);
                if (dag == null) pipeline.accepted(batch);
                else dag.accepted(batch);
                acceptedRuns.merge(recipe.id(), batch, CheckedAmounts::add);
                long undispatched = pendingRuns.get(recipe.id()) - batch;
                if (undispatched == 0) pendingRuns.remove(recipe.id());
                else pendingRuns.put(recipe.id(), undispatched);
                for (var output : recipe.outputs().entrySet()) {
                    long remaining = pendingOutputs.get(output.getKey()) - CheckedAmounts.multiply(output.getValue(), batch);
                    if (remaining == 0) pendingOutputs.remove(output.getKey());
                    else pendingOutputs.put(output.getKey(), remaining);
                }
                expected.clear();
                expected.putAll(preparedOutputs);
                owned.restore(synchronousReturns);
                dispatches++;
                pushed++;
                reason = "";
            } else if (outcome == Outcome.IN_DOUBT) {
                obligations.dispatch(recipe.id(), batch, outputs, true);
                synchronousReturns.forEach(obligations::returned);
                uncertainInputs = escrow; // evidence of a possible transfer, NOT held inventory
                expected.clear();
                expected.putAll(preparedOutputs);
                owned.restore(synchronousReturns);
                state = State.NEEDS_ATTENTION;
                reason = "DISPATCH_IN_DOUBT";
            } else {
                synchronousReturns.forEach(obligations::returned);
                synchronousReturns.forEach((key, amount) -> expected.compute(key, (ignored, value) -> value - amount));
                owned.restore(synchronousReturns);
                owned.restore(escrow);
                rejections++;
                reason = outcome.name();
                if (dag != null) dag.retry(tick + 5);
                else pipeline.retry(tick + 5);
            }
            preparedOutputs = null;
            preparedInputs = null;
            synchronousReturns = null;
            preparingRecipe = null;
            preparingBatchOutputs = null;
            expected.values().removeIf(value -> value == 0);
            changed();
            if (cancelRequested) cancel();
        }
        return pushed;
    }

    public long accept(K key, long amount, boolean simulate) {
        if (amount <= 0 || state == State.CANCELLED || state == State.COMPLETED || state == State.CANCELLING) return 0;
        Map<K, Long> obligations = preparedOutputs == null ? expected : preparedOutputs;
        long requested = obligations.getOrDefault(key, 0L);
        long alreadyBuffered = synchronousReturns == null ? 0 : synchronousReturns.getOrDefault(key, 0L);
        long accepted = Math.min(Math.min(amount, requested), Long.MAX_VALUE - owned.get(key) - alreadyBuffered);
        if (accepted <= 0 || simulate) return Math.max(0, accepted);
        if (accepted == requested) obligations.remove(key);
        else obligations.put(key, requested - accepted);
        if (synchronousReturns == null) {
            this.obligations.returned(key, accepted);
            owned.add(key, accepted);
        } else synchronousReturns.merge(key, accepted, CheckedAmounts::add);
        changedKeys.add(key);
        if (dag != null) dag.resourceChanged(key);
        else pipeline.resourceChanged(key);
        changed();
        return accepted;
    }

    private void settle(Adapter<K> adapter, int budget) {
        if (budget <= 0) return;
        if (state == State.SETTLING && remainingDelivery > 0) {
            long available = owned.get(plan.target());
            if (available < remainingDelivery) {
                state = State.NEEDS_ATTENTION;
                reason = "UNFUNDED_DELIVERY";
                changed();
                return;
            }
            long delivered = transfer(adapter, plan.target(), remainingDelivery, true);
            if (delivered < 0) return;
            changedKeys.add(plan.target());
            remainingDelivery -= delivered;
            if (delivered > 0) changed();
            if (cancelRequested) {
                cancel();
                return;
            }
            if (remainingDelivery != 0) return;
        }
        if (refundQueue == null) refundQueue = new ArrayDeque<>(owned.snapshot().keySet());
        int attempts = Math.min(budget, refundQueue.size());
        for (int i = 0; i < attempts; i++) {
            K key = refundQueue.removeFirst();
            long amount = owned.get(key);
            if (amount == 0) continue;
            long refunded = transfer(adapter, key, amount, false);
            if (refunded < 0) return;
            if (owned.get(key) > 0) refundQueue.addLast(key);
            changedKeys.add(key);
            if (refunded > 0) changed();
            if (cancelRequested) {
                cancel();
                return;
            }
        }
        if (owned.isEmpty()) {
            state = state == State.CANCELLING ? State.CANCELLED : State.COMPLETED;
            changed();
        }
    }

    private static long validTransfer(long accepted, long offered) {
        if (accepted < 0 || accepted > offered) throw new IllegalStateException("Invalid external transfer result");
        return accepted;
    }

    private long transfer(Adapter<K> adapter, K key, long offered, boolean delivery) {
        owned.remove(key, offered);
        settlementEscrow = Map.of(key, offered);
        try {
            long accepted = validTransfer(delivery ? adapter.deliver(key, offered) : adapter.refund(key, offered), offered);
            owned.add(key, offered - accepted);
            return accepted;
        } catch (RuntimeException e) {
            // The requester/storage might already have accepted material before
            // throwing. The handoff is not a rollback-capable transaction.
            uncertainInputs = settlementEscrow;
            state = State.NEEDS_ATTENTION;
            reason = "SETTLEMENT_IN_DOUBT: " + e.getClass().getSimpleName();
            changed();
            return -1;
        } finally {
            settlementEscrow = null;
        }
    }

    public void cancel() {
        if (preparedOutputs != null || settlementEscrow != null) {
            cancelRequested = true;
            return;
        }
        cancelRequested = false;
        replanning = false;
        replanEpoch++;
        if (state == State.COMPLETED || state == State.CANCELLED || state == State.CANCELLING) return;
        refundQueue = null;
        // Late machine outputs now bypass this task and enter the network normally.
        changedKeys.addAll(expected.keySet());
        changedKeys.addAll(pendingOutputs.keySet());
        expected.clear();
        obligations.clear();
        pendingOutputs.clear();
        pendingRuns.clear();
        state = State.CANCELLING;
        reason = "CANCELLED";
        changed();
    }

    public void suspend(boolean value) {
        if (suspended != value) {
            suspended = value;
            changed();
        }
    }

    public boolean suspended() {
        return suspended;
    }

    /** Freeze only future dispatch; existing physical returns remain receivable. */
    public ReplanCheckpoint<K> beginReplan() {
        if (state != State.RUNNING || replanning || preparedOutputs != null || settlementEscrow != null || !uncertainInputs.isEmpty())
            throw new IllegalStateException("Task cannot be replanned during an ambiguous handoff");
        replanning = true;
        reason = "REPLANNING";
        changed();
        return new ReplanCheckpoint<>(++replanEpoch, plan.target(), remainingDelivery, plan.seeds(), forecastInventory());
    }

    public Map<K, Long> forecastInventory() {
        Map<K, Long> forecast = new LinkedHashMap<>(owned.snapshot());
        expected.forEach((key, count) -> forecast.merge(key, count, CheckedAmounts::add));
        return GraphRecipe.amounts(forecast);
    }

    public boolean replanCurrent(long epoch) {
        return replanning && epoch == replanEpoch && state == State.RUNNING;
    }

    public void abortReplan(long epoch, String reason) {
        if (!replanCurrent(epoch)) return;
        replanning = false;
        this.reason = reason;
        changed();
    }

    public void waitForReplanDependency(long epoch, String diagnostic) {
        if (!replanCurrent(epoch)) return;
        reason = diagnostic;
        changed();
    }

    /** Replace the uncommitted program while keeping actual inventory and every accepted output owner. */
    public boolean replaceSuffix(long epoch, GraphPlan<K> replacement, Map<K, Long> extraHeld, Map<K, Long> extraExternal) {
        if (!replanCurrent(epoch)) return false;
        if (!replacement.target().equals(plan.target()) || replacement.amount() != remainingDelivery)
            throw new IllegalArgumentException("Replan changed the order target");
        for (var seed : plan.seeds().entrySet())
            if (replacement.seeds().getOrDefault(seed.getKey(), 0L) < seed.getValue()) throw new IllegalArgumentException("Replan dropped recovery ownership");
        PlanVerifier.verify(replacement);
        Map<K, Long> projected = new LinkedHashMap<>(forecastInventory());
        extraHeld.forEach((key, count) -> projected.merge(key, CheckedAmounts.nonNegative(count), CheckedAmounts::add));
        extraExternal.forEach((key, count) -> projected.merge(key, CheckedAmounts.nonNegative(count), CheckedAmounts::add));
        for (var required : replacement.initial().entrySet())
            if (projected.getOrDefault(required.getKey(), 0L) < required.getValue()) throw new IllegalArgumentException("Unfunded replacement suffix");
        // Include surplus from the old prefix in the long/peak check, even if the
        // replacement no longer consumes it. It remains physically owned until refund.
        PlanVerifier.verify(new GraphPlan<>(replacement.target(), replacement.amount(), replacement.preserveSeeds(), replacement.steps(),
                replacement.recipes(), projected, replacement.seeds(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0));
        Map<String, Long> history = new LinkedHashMap<>(committedHistory);
        acceptedRuns.forEach((id, count) -> history.merge(id, count, CheckedAmounts::add));
        var newCursor = new PlanCursor(replacement.steps());
        var newDag = DagScheduler.create(replacement, Map.of());
        var newPipeline = newDag == null ? new PipelineScheduler<>(newCursor, replacement.recipes(), List.of()) : null;
        owned.restore(extraHeld);
        obligations.addExternal(extraExternal);
        extraExternal.forEach((key, count) -> expected.merge(key, count, CheckedAmounts::add));
        committedHistory.clear();
        committedHistory.putAll(history);
        acceptedRuns.clear();
        changedKeys.addAll(pendingOutputs.keySet());
        pendingRuns.clear();
        pendingOutputs.clear();
        plan = replacement;
        cursor = newCursor;
        dag = newDag;
        pipeline = newPipeline;
        initializePending();
        replanning = false;
        reason = "";
        changed();
        return true;
    }

    public Map<String, Long> committedRuns() {
        Map<String, Long> all = new LinkedHashMap<>(committedHistory);
        acceptedRuns.forEach((id, count) -> all.merge(id, count, CheckedAmounts::add));
        return Map.copyOf(all);
    }

    public record ReplanCheckpoint<K>(long epoch, K target, long remaining, Map<K, Long> recoverySeeds, Map<K, Long> forecast) {}

    public State state() {
        return state;
    }

    public String reason() {
        return reason;
    }

    public GraphPlan<K> plan() {
        return plan;
    }

    public Map<K, Long> owned() {
        return owned.snapshot();
    }

    public long held(K key) {
        return owned.get(key);
    }

    public Map<K, Long> expected() {
        return GraphRecipe.amounts(expected);
    }

    public Map<K, Long> externalWaiting() {
        return obligations.external();
    }

    public long externalWaiting(K key) {
        return obligations.external(key);
    }

    public Map<K, Long> inFlight() {
        return obligations.inFlight();
    }

    public long inFlight(K key) {
        return obligations.inFlight(key);
    }

    private boolean seedsHeld() {
        return plan.seeds().entrySet().stream().allMatch(seed -> owned.get(seed.getKey()) >= seed.getValue());
    }

    public RecoveryObligation<K> recovery() {
        RecoveryObligation.Status status;
        if (state == State.CANCELLING || state == State.CANCELLED) status = RecoveryObligation.Status.ABANDONED;
        else if (state == State.SETTLING || state == State.COMPLETED) status = RecoveryObligation.Status.RESTORED;
        else if (seedsHeld()) status = RecoveryObligation.Status.READY;
        else status = obligations.hasFlights() ? RecoveryObligation.Status.IN_FLIGHT : RecoveryObligation.Status.INTERMEDIATE;
        return new RecoveryObligation<>(recoveryOwner, "order", plan.seeds(), recoveryStage, status);
    }

    public long waiting(K key) {
        return (preparedOutputs == null ? expected : preparedOutputs).getOrDefault(key, 0L);
    }

    public long remainingDelivery() {
        return remainingDelivery;
    }

    public long version() {
        return version;
    }

    public long dispatches() {
        return dispatches;
    }

    public long rejections() {
        return rejections;
    }

    public long checks() {
        return checks;
    }

    public long pendingOutput(K key) {
        return pendingOutputs.getOrDefault(key, 0L);
    }

    public Set<K> pendingKeys() {
        return Set.copyOf(pendingOutputs.keySet());
    }

    public Set<K> drainChangedKeys() {
        Set<K> keys = Set.copyOf(changedKeys);
        changedKeys.clear();
        return keys;
    }

    public boolean finished() {
        return state == State.COMPLETED || state == State.CANCELLED;
    }

    /** Explicit ownership transfer to AE's destruction/drop inventory after cancellation. */
    public Map<K, Long> detachCancelledInventory() {
        if (state != State.CANCELLING && state != State.CANCELLED) throw new IllegalStateException("Task still active");
        Map<K, Long> detached = owned.snapshot();
        detached.forEach(owned::remove);
        state = State.CANCELLED;
        changed();
        return detached;
    }

    public Map<String, Long> pendingRuns() {
        if (state == State.CANCELLING || finished()) return Map.of();
        return Map.copyOf(pendingRuns);
    }

    private void changed() {
        version++;
    }

    public Snapshot<K> snapshot() {
        if (settlementEscrow != null) {
            return new Snapshot<>(plan, owned.snapshot(), GraphRecipe.amounts(expected), settlementEscrow,
                    Map.copyOf(acceptedRuns), dag == null ? cursor.snapshot() : List.of(), pipeline == null ? List.of() : pipeline.snapshot(), remainingDelivery,
                    State.NEEDS_ATTENTION, suspended, "SETTLEMENT_IN_DOUBT_SAVED_DURING_HANDOFF", obligations.snapshot(), recovery(), Map.copyOf(committedHistory));
        }
        if (preparedOutputs != null) {
            Map<K, Long> held = new LinkedHashMap<>(owned.snapshot());
            synchronousReturns.forEach((key, amount) -> held.merge(key, amount, CheckedAmounts::add));
            var preparing = new OutputObligations<>(obligations.snapshot());
            preparing.dispatch(preparingRecipe, preparingRuns, preparingBatchOutputs, true);
            synchronousReturns.forEach(preparing::returned);
            // A provider may cause a synchronous save. Its handoff has not
            // returned, so persist an ambiguous ticket rather than a retryable
            // pre-dispatch state or a duplicate copy of escrow as held material.
            return new Snapshot<>(plan, GraphRecipe.amounts(held), GraphRecipe.amounts(preparedOutputs), preparedInputs,
                    Map.copyOf(acceptedRuns), dag == null ? cursor.snapshot() : List.of(), pipeline == null ? List.of() : pipeline.snapshot(), remainingDelivery,
                    State.NEEDS_ATTENTION, suspended, "DISPATCH_IN_DOUBT_SAVED_DURING_HANDOFF", preparing.snapshot(), recovery(), Map.copyOf(committedHistory));
        }
        return new Snapshot<>(plan, owned.snapshot(), GraphRecipe.amounts(expected), uncertainInputs,
                Map.copyOf(acceptedRuns), dag == null ? cursor.snapshot() : List.of(), pipeline == null ? List.of() : pipeline.snapshot(), remainingDelivery, state, suspended, reason,
                obligations.snapshot(), recovery(), Map.copyOf(committedHistory));
    }

    public record Snapshot<K>(GraphPlan<K> plan, Map<K, Long> owned, Map<K, Long> expected,
                              Map<K, Long> uncertainInputs, Map<String, Long> acceptedRuns,
                              List<PlanCursor.Position> cursor, List<PlanStep.Batch> pipeline, long remainingDelivery, State state,
                              boolean suspended, String reason, OutputObligations.Snapshot<K> obligations,
                              RecoveryObligation<K> recovery, Map<String, Long> committedHistory) {}
}
