package org.cgse.core;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;

/** A completed planning outcome, distinct from a crashed planner or host localization. */
public class PlanningFailure extends RuntimeException {

    private final GraphPlan.Result result;
    private final String detail;

    public PlanningFailure(GraphPlan.Result result, String detail) {
        super("Graph crafting: " + result + (detail.isEmpty() ? "" : " (" + detail + ")"));
        this.result = Objects.requireNonNull(result);
        this.detail = detail;
    }

    public GraphPlan.Result result() {
        return result;
    }

    public String detail() {
        return detail;
    }

    /** Structured outcome for a cause chain; null leaves unexpected errors on the host error path. */
    public static GraphPlan.Result result(Throwable error) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        while (error != null && seen.add(error)) {
            if (error instanceof PlanningFailure failure) return failure.result();
            if (error instanceof PlanningBudget.Exhausted exhausted)
                return GraphPlan.Result.valueOf(exhausted.limit().name());
            error = error.getCause();
        }
        return null;
    }
}
