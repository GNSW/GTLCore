package org.cgse.core;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * Polls suffix planning on the caller's thread. Host callbacks retain provider
 * resolution, event subscriptions and physical storage transactions on that thread.
 */
public final class ReplanCoordinator<K, P> {

    public enum Update {
        IDLE,
        WAITING,
        INSTALLED,
        FAILED,
        STALE,
        RETRY
    }

    public record Failure(int attempts, int delay, boolean shouldLog) {}

    public interface Host<K, P> {

        /** Return null only after the replacement has been installed. */
        String install(P plan);

        /** Replace any prior watch and invoke changed when a relevant dependency changes. */
        void watch(Set<K> dependencies, Runnable changed);

        default void failed(String diagnostic, Failure failure) {}
    }

    private final ReplanRetry retry = new ReplanRetry();
    private PlanningRequest<K, P> request;
    private GraphJobRuntime.ReplanCheckpoint<K> checkpoint;
    private boolean dependencyChanged;

    public GraphJobRuntime.ReplanCheckpoint<K> checkpoint() {
        return checkpoint;
    }

    public boolean hasRequest() {
        return request != null;
    }

    public boolean waitingForDependency() {
        return checkpoint != null && request == null;
    }

    public void dependencyChanged() {
        dependencyChanged = true;
    }

    public boolean begin(GraphJobRuntime<K> runtime,
                         Function<GraphJobRuntime.ReplanCheckpoint<K>, ? extends PlanningRequest<K, P>> planner) {
        if (runtime.remainingDelivery() <= 0) return false;
        checkpoint = runtime.beginReplan();
        request = planner.apply(checkpoint);
        return true;
    }

    public void clear(boolean resetFailures) {
        if (request != null) request.cancel(false);
        request = null;
        checkpoint = null;
        dependencyChanged = false;
        if (resetFailures) retry.reset();
    }

    /** The host clears its watch after INSTALLED/STALE/RETRY and starts a new request after RETRY. */
    public Update update(GraphJobRuntime<K> runtime, long tick, Host<K, P> host) {
        if (runtime.state() != GraphJobRuntime.State.RUNNING) return Update.STALE;
        if (request != null) {
            if (!request.isDone()) return Update.WAITING;
            PlanningRequest<K, P> completed = request;
            request = null;
            if (!runtime.replanCurrent(checkpoint.epoch())) return Update.STALE;
            String diagnostic;
            try {
                diagnostic = host.install(completed.join());
                if (diagnostic == null) return Update.INSTALLED;
            } catch (RuntimeException e) {
                Throwable cause = e;
                while (cause.getCause() != null) cause = cause.getCause();
                diagnostic = "REPLAN_" + cause.getClass().getSimpleName() + ": " + cause.getMessage();
            }
            Set<K> dependencies = new LinkedHashSet<>(completed.dependencies());
            // A completed snapshot includes the new suffix and recovery roots.
            // Old committed recipes must not keep waking it. Failed snapshot
            // collection still needs a conservative fallback.
            if (dependencies.isEmpty()) for (String id : runtime.pendingRuns().keySet()) {
                var recipe = runtime.plan().recipes().get(id);
                dependencies.addAll(recipe.inputs().keySet());
                dependencies.addAll(recipe.outputs().keySet());
            }
            dependencies.add(runtime.plan().target());
            dependencies.addAll(checkpoint.recoverySeeds().keySet());
            dependencyChanged = false;
            host.watch(dependencies, this::dependencyChanged);
            runtime.waitForReplanDependency(checkpoint.epoch(), diagnostic);
            int delay = retry.failed(tick);
            host.failed(diagnostic, new Failure(retry.failures(), delay, retry.shouldLog(tick, diagnostic)));
            return Update.FAILED;
        }
        if (checkpoint != null) {
            // Keep one event latched across cooldown and suspension. A busy
            // network cannot force one solve per tick.
            if (runtime.suspended() || !dependencyChanged || !retry.ready(tick)) return Update.WAITING;
            runtime.abortReplan(checkpoint.epoch(), "");
            clear(false);
            return Update.RETRY;
        }
        return Update.IDLE;
    }
}
