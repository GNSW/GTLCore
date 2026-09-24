package org.gtlcore.gtlcore.integration.ae2.graph;

import java.util.UUID;

/** The summary exposes identity only; details are fetched from the player's current menu. */
public interface GraphPlanSummaryView {

    UUID gtlcore$graphPlanId();

    void gtlcore$graphPlanId(UUID id);
}
