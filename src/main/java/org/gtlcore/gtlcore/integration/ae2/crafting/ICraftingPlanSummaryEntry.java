package org.gtlcore.gtlcore.integration.ae2.crafting;

public interface ICraftingPlanSummaryEntry {

    long gtlcore$getCraftTimes();

    void gtlcore$setCraftTimes(long craftTimes);

    long gtlcore$getGraphSeed();

    void gtlcore$setGraphSeed(long amount);

    boolean gtlcore$isMissingGraphSeed();

    void gtlcore$setMissingGraphSeed(boolean missing);
}
