package org.gtlcore.gtlcore.mixin.ae2.crafting;

import org.gtlcore.gtlcore.integration.ae2.crafting.CraftingPlanSummaryCraftTimes;
import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingPlanSummaryEntry;
import org.gtlcore.gtlcore.integration.ae2.graph.AeGraphPlan;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphPlanSummary;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphPlanSummaryView;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphSeedStatus;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphSummaryContext;

import net.minecraft.network.FriendlyByteBuf;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import org.cgse.core.GraphPlan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

@Mixin(CraftingPlanSummary.class)
public class CraftingPlanSummaryMixin implements GraphPlanSummaryView {

    @Unique
    private UUID gtlcore$graphId;

    @Unique
    private GraphPlan.SeedOptimality gtlcore$seedProof;

    @Unique
    private boolean gtlcore$fallback;

    @Unique
    private boolean gtlcore$conditionalPreview;

    @Override
    public boolean gtlcore$conditionalPreview() {
        return gtlcore$conditionalPreview;
    }

    @Override
    public void gtlcore$conditionalPreview(boolean conditional) {
        gtlcore$conditionalPreview = conditional;
    }

    @Override
    public boolean gtlcore$fallback() {
        return gtlcore$fallback;
    }

    @Override
    public void gtlcore$fallback(boolean fallback) {
        gtlcore$fallback = fallback;
    }

    @Override
    public GraphPlan.SeedOptimality gtlcore$seedOptimality() {
        return gtlcore$seedProof;
    }

    @Override
    public void gtlcore$seedOptimality(GraphPlan.SeedOptimality proof) {
        gtlcore$seedProof = proof;
    }

    @Override
    public UUID gtlcore$graphPlanId() {
        return gtlcore$graphId;
    }

    @Override
    public void gtlcore$graphPlanId(UUID id) {
        gtlcore$graphId = id;
    }

    @WrapMethod(method = "write", remap = false)
    private void gtlcore$writeGraphIdentity(FriendlyByteBuf buffer, Operation<Void> original) {
        original.call(buffer);
        buffer.writeBoolean(gtlcore$graphId != null);
        if (gtlcore$graphId != null) {
            buffer.writeUUID(gtlcore$graphId);
            GraphSeedStatus.write(buffer, gtlcore$seedProof);
            buffer.writeBoolean(gtlcore$fallback);
            buffer.writeBoolean(gtlcore$conditionalPreview);
        }
    }

    @WrapMethod(method = "read", remap = false)
    private static CraftingPlanSummary gtlcore$readGraphIdentity(FriendlyByteBuf buffer, Operation<CraftingPlanSummary> original) {
        CraftingPlanSummary summary = original.call(buffer);
        if (buffer.readBoolean()) {
            var view = (GraphPlanSummaryView) summary;
            view.gtlcore$graphPlanId(buffer.readUUID());
            view.gtlcore$seedOptimality(GraphSeedStatus.read(buffer));
            view.gtlcore$fallback(buffer.readBoolean());
            view.gtlcore$conditionalPreview(buffer.readBoolean());
        }
        return summary;
    }

    @WrapMethod(method = "fromJob", remap = false)
    private static CraftingPlanSummary gtlcore$graphSummary(IGrid grid, IActionSource actionSource,
                                                            ICraftingPlan job, Operation<CraftingPlanSummary> original) {
        if (!(job instanceof AeGraphPlan graph)) return original.call(grid, actionSource, job);
        // Keep addon packet envelopes intact, but skip AE2CT's redundant tree for
        // this graph view only. Legacy plans, including nested calls, retain their data.
        // The menu and CPU continue to hold the original graph plan.
        // AE's legacy summary adds long used/missing counters and samples the
        // live inventory again. Both lose the graph's exact, captured quantities.
        // Retain addon hooks/envelopes; populate entries from the native plan below.
        var graphView = new CraftingPlan(graph.finalOutput(), graph.bytes(), graph.simulation(), graph.multiplePaths(),
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
        CraftingPlanSummary summary = GraphSummaryContext.withGraphPlan(graphView, graph,
                () -> original.call(grid, actionSource, graphView));
        ((GraphPlanSummaryView) summary).gtlcore$graphPlanId(graph.id());
        ((GraphPlanSummaryView) summary).gtlcore$seedOptimality(graph.graph().seedOptimality());
        ((GraphPlanSummaryView) summary).gtlcore$fallback(graph.fallback());
        ((GraphPlanSummaryView) summary).gtlcore$conditionalPreview(graph.graph().conditionalPreview());
        return summary;
    }

    @Inject(method = "fromJob", at = @At(value = "INVOKE", target = "Ljava/util/Collections;sort(Ljava/util/List;)V"), remap = false)
    private static void injectCraftTimes(IGrid grid, IActionSource actionSource, ICraftingPlan job, CallbackInfoReturnable<CraftingPlanSummary> cir, @Local ArrayList<CraftingPlanSummaryEntry> entries) {
        var graph = GraphSummaryContext.graphPlan(job);
        if (graph != null) {
            entries.addAll(GraphPlanSummary.entries(graph));
            return;
        }
        var craftTimesByOutput = CraftingPlanSummaryCraftTimes.aggregateByOutput(job.patternTimes());
        for (var entry : entries) {
            ((ICraftingPlanSummaryEntry) entry).gtlcore$setCraftTimes(craftTimesByOutput.getLong(entry.getWhat()));
        }
    }
}
