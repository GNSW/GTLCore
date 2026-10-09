package org.gtlcore.gtlcore.integration.ae2.graph;

import org.cgse.core.GraphPlan;

import java.util.UUID;

/** The summary exposes identity only; details are fetched from the player's current menu. */
public interface GraphPlanSummaryView {

    UUID gtlcore$graphPlanId();

    void gtlcore$graphPlanId(UUID id);

    GraphPlan.SeedOptimality gtlcore$seedOptimality();

    void gtlcore$seedOptimality(GraphPlan.SeedOptimality proof);

    boolean gtlcore$fallback();

    void gtlcore$fallback(boolean fallback);
}
