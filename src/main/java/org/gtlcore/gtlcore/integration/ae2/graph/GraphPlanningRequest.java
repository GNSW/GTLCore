package org.gtlcore.gtlcore.integration.ae2.graph;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import org.cgse.core.PlanningBudget;
import org.cgse.core.PlanningRequest;

/** Keeps progress/cancellation attached to the exact future owned by the AE menu. */
public final class GraphPlanningRequest extends PlanningRequest<AEKey, ICraftingPlan> {

    GraphPlanningRequest(PlanningBudget budget) {
        super(budget);
    }
}
