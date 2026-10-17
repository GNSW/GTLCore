package org.gtlcore.gtlcore.mixin.ae2.gui;

import org.gtlcore.gtlcore.integration.ae2.common.IConfirmStartMenu;
import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingPlanSummaryEntry;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphPlanSummaryView;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphSeedStatus;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.crafting.AbstractTableRenderer;
import appeng.client.gui.me.crafting.CraftConfirmTableRenderer;
import appeng.menu.me.crafting.CraftConfirmMenu;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(CraftConfirmTableRenderer.class)
public abstract class CraftConfirmTableRendererMixin extends AbstractTableRenderer<CraftingPlanSummaryEntry> {

    protected CraftConfirmTableRendererMixin(AEBaseScreen<?> screen, int x, int y, int rows) {
        super(screen, x, y, rows);
    }

    @Inject(method = "getEntryDescription(Lappeng/menu/me/crafting/CraftingPlanSummaryEntry;)Ljava/util/List;", at = @At("TAIL"), cancellable = true, remap = false)
    private void getEntryDescription(CraftingPlanSummaryEntry entry, CallbackInfoReturnable<List<Component>> cir) {
        var lines = new ArrayList<>(cir.getReturnValue());

        var craftTimes = ((ICraftingPlanSummaryEntry) entry).gtlcore$getCraftTimes();
        if (craftTimes > 1) {
            var color = craftTimes < 500 ? ChatFormatting.DARK_GREEN :
                    craftTimes < 2000 ? ChatFormatting.GOLD :
                            craftTimes < 6000 ? ChatFormatting.RED :
                                    ChatFormatting.DARK_RED;
            var timesText = Component.literal(String.format("%d", craftTimes)).withStyle(color);
            lines.add(Component.translatable("gtlcore.ae.craft.create_times", timesText));
        }

        // 菜单端带缓存，仅在实时库存变化时重建
        var liveStored = screen.getMenu() instanceof IConfirmStartMenu me ? me.gtlcore$getLiveStored() : null;
        if (liveStored != null && entry.getMissingAmount() == 0) {
            var storedTotal = liveStored.get(entry.getWhat());
            if (entry.getStoredAmount() > storedTotal) {
                // 算料后库存被其他任务消耗，实际已不足
                lines.add(Component.translatable("gtlcore.ae.craft.stock_taken").withStyle(ChatFormatting.DARK_RED));
            } else if (storedTotal > 0) {
                var storedPercent = Math.min((float) entry.getStoredAmount() / storedTotal, 1.0f);
                var color = storedPercent < 0.25f ? ChatFormatting.DARK_GREEN :
                        storedPercent < 0.5f ? ChatFormatting.GOLD :
                                storedPercent < 0.75f ? ChatFormatting.RED :
                                        ChatFormatting.DARK_RED;
                var percentText = Component.literal(gtlcore$formatNumber2Places(storedPercent * 100) + "%").withStyle(color);
                lines.add(Component.translatable("gtlcore.ae.craft.used_percent", percentText));
            }
        }

        cir.setReturnValue(lines);
    }

    @Inject(method = "getEntryTooltip(Lappeng/menu/me/crafting/CraftingPlanSummaryEntry;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true,
            remap = false)
    private void gtlcore$seedTooltip(CraftingPlanSummaryEntry entry, CallbackInfoReturnable<List<Component>> cir) {
        // AE cells are only 22px high: four half-scale lines already take 21px.
        // Recovery details belong in the unconstrained tooltip, not in another cell row.
        var graphEntry = (ICraftingPlanSummaryEntry) entry;
        if (graphEntry.gtlcore$getGraphSeed() <= 0 && !graphEntry.gtlcore$isMissingGraphSeed()) return;
        var lines = new ArrayList<>(cir.getReturnValue());
        if (graphEntry.gtlcore$getGraphSeed() > 0)
            lines.add(Component.translatable("gtlcore.ae.graph.preserved_seed", graphEntry.gtlcore$getGraphSeed()).withStyle(ChatFormatting.AQUA));
        if (graphEntry.gtlcore$isMissingGraphSeed())
            lines.add(Component.translatable("gtlcore.ae.graph.missing_seed").withStyle(ChatFormatting.RED));
        if (screen.getMenu() instanceof CraftConfirmMenu menu && menu.getPlan() instanceof GraphPlanSummaryView view)
            lines.addAll(GraphSeedStatus.tooltip(view.gtlcore$seedOptimality()));
        cir.setReturnValue(lines);
    }

    @Unique
    private String gtlcore$formatNumber2Places(double number) {
        if (number == (long) number) {
            return String.format("%d", (long) number);
        } else {
            return String.format("%.2f", number);
        }
    }
}
