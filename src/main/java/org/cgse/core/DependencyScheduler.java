package org.cgse.core;

import java.util.*;
import java.util.function.ToLongFunction;

/** Fair ready queue over independent material lanes; each lane retains its verified prefix. */
final class DependencyScheduler<K> {

    private final List<PipelineScheduler<K>> lanes = new ArrayList<>();
    private final Map<K, Set<Integer>> affected = new HashMap<>();
    private final Map<K, Set<Integer>> suppliers = new HashMap<>();
    private final Map<String, GraphRecipe<K>> recipes;
    private final Deque<Integer> ready = new ArrayDeque<>();
    private final PriorityQueue<Retry> retries = new PriorityQueue<>(Comparator.comparingLong(Retry::tick));
    private final boolean[] queued, done;
    private int unfinished, active = -1;
    private String activeRecipe;

    DependencyScheduler(GraphPlan<K> plan, PlanStep remaining) {
        recipes = plan.recipes();
        // Always derive dependencies from the original selected witness. Saved
        // lane suffixes are concatenated for storage, not a new serial witness.
        List<PlanStep> branches = plan.executionDependencies().partition(remaining);
        queued = new boolean[branches.size()];
        done = new boolean[branches.size()];
        for (PlanStep branch : branches) {
            int lane = lanes.size();
            lanes.add(new PipelineScheduler<>(new PlanCursor(branch), plan.recipes(), List.of()));
            for (String id : PlanCountComputation.of(branch).keySet()) {
                GraphRecipe<K> recipe = plan.recipes().get(id);
                for (K key : recipe.inputs().keySet()) affected.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(lane);
                for (K key : recipe.outputs().keySet()) {
                    affected.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(lane);
                    suppliers.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(lane);
                }
            }
            enqueue(lane);
        }
        unfinished = lanes.size();
    }

    PlanStep.Batch poll(long tick, ToLongFunction<K> forecast) {
        while (!retries.isEmpty() && retries.peek().tick <= tick) enqueue(retries.remove().lane);
        active = -1;
        // Yield even if a very large job initially has thousands of sleeping
        // branches. The remaining ready queue is resumed on the next CPU tick.
        for (int checked = 0; checked < PipelineScheduler.WINDOW && !ready.isEmpty(); checked++) {
            int lane = ready.removeFirst();
            queued[lane] = false;
            if (done[lane]) continue;
            PlanStep.Batch batch = lanes.get(lane).poll(tick, forecast);
            if (batch != null) {
                active = lane;
                activeRecipe = batch.recipe();
                enqueue(lane);
                return batch;
            }
        }
        return null;
    }

    long limit(long requested, ToLongFunction<K> forecast) {
        return active < 0 ? 0 : lanes.get(active).limit(requested, forecast);
    }

    void accepted(long runs) {
        PipelineScheduler<K> lane = lanes.get(active);
        lane.accepted(runs);
        if (lane.finished()) {
            done[active] = true;
            unfinished--;
        } else enqueue(active);
        // A consumer in another lane can free output headroom without ever
        // returning this key. Wake its suppliers' prefix reservations too.
        // Raw-material consumers need no wakeup merely because stock fell.
        for (K key : recipes.get(activeRecipe).inputs().keySet()) {
            for (int supplier : suppliers.getOrDefault(key, Set.of())) wake(supplier, key);
        }
    }

    void retry(long tick) {
        if (active >= 0) {
            lanes.get(active).retry(tick);
            retries.add(new Retry(tick, active));
        }
    }

    void resourceChanged(K key) {
        for (int lane : affected.getOrDefault(key, Set.of())) {
            wake(lane, key);
        }
    }

    private void wake(int lane, K key) {
        if (done[lane]) return;
        lanes.get(lane).resourceChanged(key);
        enqueue(lane);
    }

    private void enqueue(int lane) {
        if (done[lane] || queued[lane]) return;
        queued[lane] = true;
        ready.addLast(lane);
    }

    boolean finished() {
        return unfinished == 0;
    }

    PlanStep snapshot() {
        List<PlanStep> remaining = new ArrayList<>();
        for (int lane = 0; lane < lanes.size(); lane++) if (!done[lane]) remaining.add(lanes.get(lane).remainingSteps());
        return remaining.size() == 1 ? remaining.get(0) : new PlanStep.Sequence(remaining);
    }

    private record Retry(long tick, int lane) {}
}
