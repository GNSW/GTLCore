package org.gtlcore.gtlcore.integration.ae2.graph;

public interface GraphPlanMenu {

    void gtlcore$retryPlanning();

    AeGraphPlan gtlcore$graphPlan();

    default String gtlcore$planningFailure() {
        return "";
    }
}
