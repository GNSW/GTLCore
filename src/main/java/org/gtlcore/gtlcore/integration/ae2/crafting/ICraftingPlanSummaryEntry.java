package org.gtlcore.gtlcore.integration.ae2.crafting;

import appeng.api.stacks.AEKey;

import java.util.List;

public interface ICraftingPlanSummaryEntry {

    long gtlcore$getCraftTimes();

    void gtlcore$setCraftTimes(long craftTimes);

    long gtlcore$getGraphSeed();

    void gtlcore$setGraphSeed(long amount);

    boolean gtlcore$isMissingGraphSeed();

    void gtlcore$setMissingGraphSeed(boolean missing);

    int gtlcore$getGroupedToolStates();

    void gtlcore$setGroupedToolStates(int states);

    List<AEKey> gtlcore$getMissingKeys();

    void gtlcore$setMissingKeys(List<AEKey> keys);
}
