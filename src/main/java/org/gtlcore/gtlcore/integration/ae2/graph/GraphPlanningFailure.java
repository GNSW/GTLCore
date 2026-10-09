package org.gtlcore.gtlcore.integration.ae2.graph;

import org.cgse.core.GraphPlan;
import org.cgse.core.PlanningFailure;

import java.util.Locale;

/** A completed planning outcome, distinct from a crashed planner. */
public final class GraphPlanningFailure extends PlanningFailure {

    public GraphPlanningFailure(GraphPlan.Result result, String detail) {
        super(result, detail);
    }

    public static String messageKey(Throwable error) {
        var result = PlanningFailure.result(error);
        return result == null ? null : "gtlcore.ae.graph.failure." + result.name().toLowerCase(Locale.ROOT);
    }
}
