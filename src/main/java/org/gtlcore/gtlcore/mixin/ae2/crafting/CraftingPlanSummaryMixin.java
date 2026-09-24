package org.gtlcore.gtlcore.mixin.ae2.crafting;

import org.gtlcore.gtlcore.integration.ae2.crafting.CraftingPlanSummaryCraftTimes;
import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingPlanSummaryEntry;
import org.gtlcore.gtlcore.integration.ae2.graph.AeGraphPlan;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphPlanSummaryView;
import org.gtlcore.gtlcore.integration.ae2.graph.core.GraphPlan;

import net.minecraft.network.FriendlyByteBuf;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.UUID;

@Mixin(CraftingPlanSummary.class)
public class CraftingPlanSummaryMixin implements GraphPlanSummaryView {

    @Unique
    private UUID gtlcore$graphId;

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
        if (gtlcore$graphId != null) buffer.writeUUID(gtlcore$graphId);
    }

    @WrapMethod(method = "read", remap = false)
    private static CraftingPlanSummary gtlcore$readGraphIdentity(FriendlyByteBuf buffer, Operation<CraftingPlanSummary> original) {
        CraftingPlanSummary summary = original.call(buffer);
        if (buffer.readBoolean()) ((GraphPlanSummaryView) summary).gtlcore$graphPlanId(buffer.readUUID());
        return summary;
    }

    @WrapMethod(method = "fromJob", remap = false)
    private static CraftingPlanSummary gtlcore$graphSummary(IGrid grid, IActionSource actionSource,
                                                            ICraftingPlan job, Operation<CraftingPlanSummary> original) {
        if (!(job instanceof AeGraphPlan graph)) return original.call(grid, actionSource, job);
        // Keep addon summary/packet hooks intact, including AE2CT's CraftingPlan cast.
        // Only the nested UI call sees this view; the menu and CPU retain the graph plan.
        CraftingPlanSummary summary = original.call(grid, actionSource, graph.summaryView());
        ((GraphPlanSummaryView) summary).gtlcore$graphPlanId(graph.id());
        for (var entry : summary.getEntries()) {
            ((ICraftingPlanSummaryEntry) entry).gtlcore$setGraphSeed(graph.graph().seeds().getOrDefault(entry.getWhat(), 0L));
            ((ICraftingPlanSummaryEntry) entry).gtlcore$setMissingGraphSeed(
                    graph.graph().result() == GraphPlan.Result.MISSING_SEED &&
                            graph.graph().missing().containsKey(entry.getWhat()) &&
                            graph.graph().recipes().values().stream().anyMatch(recipe -> recipe.outputs().containsKey(entry.getWhat())));
        }
        return summary;
    }

    @Inject(method = "fromJob", at = @At(value = "INVOKE", target = "Ljava/util/Collections;sort(Ljava/util/List;)V"), remap = false)
    private static void injectCraftTimes(IGrid grid, IActionSource actionSource, ICraftingPlan job, CallbackInfoReturnable<CraftingPlanSummary> cir, @Local ArrayList<CraftingPlanSummaryEntry> entries) {
        var craftTimesByOutput = CraftingPlanSummaryCraftTimes.aggregateByOutput(job.patternTimes());
        for (var entry : entries) {
            ((ICraftingPlanSummaryEntry) entry).gtlcore$setCraftTimes(craftTimesByOutput.getLong(entry.getWhat()));
        }
    }
}
