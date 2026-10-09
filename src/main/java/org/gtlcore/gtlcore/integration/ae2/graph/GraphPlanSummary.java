package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingPlanSummaryEntry;

import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import org.cgse.core.ExactAmounts;
import org.cgse.core.PlanSummary;

import java.util.ArrayList;
import java.util.List;

/** The confirmation table and graph browser describe the same immutable plan. */
public final class GraphPlanSummary {

    private GraphPlanSummary() {}

    public static List<CraftingPlanSummaryEntry> entries(AeGraphPlan plan) {
        var summary = PlanSummary.entries(plan.graph(), plan.emittedExact());
        List<CraftingPlanSummaryEntry> entries = new ArrayList<>(summary.size());
        for (var row : summary) {
            // Clamp only at the UI boundary, after all exact arithmetic. Adding
            // separately capped used/missing amounts can wrap even a tiny stock
            // into a negative extraction request for a Long.MAX_VALUE order.
            var entry = new CraftingPlanSummaryEntry(row.key(), ExactAmounts.capped(row.missing()), ExactAmounts.capped(row.stored()),
                    ExactAmounts.capped(row.crafted()));
            ((ICraftingPlanSummaryEntry) entry).gtlcore$setCraftTimes(ExactAmounts.capped(row.runs()));
            entries.add(entry);
        }
        return entries;
    }
}
