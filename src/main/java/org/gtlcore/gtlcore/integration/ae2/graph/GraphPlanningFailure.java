package org.gtlcore.gtlcore.integration.ae2.graph;

import org.cgse.core.GraphPlan;
import org.cgse.core.PlanningFailure;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;

/** A completed planning outcome, distinct from a crashed planner. */
public final class GraphPlanningFailure extends PlanningFailure {

    public static final String INVENTORY_PRECISION_LIMIT = "inventory_precision_limit";

    public GraphPlanningFailure(GraphPlan.Result result, String detail) {
        super(result, detail);
    }

    public static String messageKey(Throwable error) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof PlanningFailure failure && INVENTORY_PRECISION_LIMIT.equals(failure.detail())) {
                return "gtlcore.ae.graph.failure." + INVENTORY_PRECISION_LIMIT;
            }
        }
        var result = PlanningFailure.result(error);
        return result == null ? null : "gtlcore.ae.graph.failure." + result.name().toLowerCase(Locale.ROOT);
    }
}
