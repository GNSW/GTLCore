package org.gtlcore.gtlcore.integration.ae2.graph;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import org.cgse.core.GraphPlan;

import java.util.ArrayList;
import java.util.List;

/** Describes a verified route's refill boundary, never a claim that every route needs this item. */
public final class GraphMissingStatus {

    private GraphMissingStatus() {}

    public static List<Component> tooltip(int mask) {
        List<Component> lines = new ArrayList<>();
        for (var source : GraphPlan.MissingSource.values()) if ((mask & (1 << source.ordinal())) != 0) {
            String label = switch (source) {
                case CYCLE_BOUNDARY -> "cycle_refill";
                case TERMINAL_UNDER_CURRENT_COVERAGE -> "terminal_refill";
                default -> "unexpanded_refill";
            };
            lines.add(Component.translatable("gtlcore.ae.graph." + label).withStyle(ChatFormatting.GOLD));
        }
        return lines;
    }
}
