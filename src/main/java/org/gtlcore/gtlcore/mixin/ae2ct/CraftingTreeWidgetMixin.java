package org.gtlcore.gtlcore.mixin.ae2ct;

import org.gtlcore.gtlcore.integration.ae2.graph.CraftingTreeCycleNode;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import appeng.api.client.AEKeyRendering;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Pseudo
@Mixin(targets = "com.neuvillette.ae2ct.gui.CraftingTreeWidget", remap = false)
public abstract class CraftingTreeWidgetMixin {

    @Shadow
    private int outputX;
    @Shadow
    private int outputY;
    @Shadow
    private int spacingX;
    @Shadow
    private int spacingY;
    @Unique
    private CraftingTreeCycleNode gtlcore$hoveredCycle;

    @Inject(method = "drawNode", at = @At("RETURN"), remap = false)
    private void gtlcore$drawCycleMarker(GuiGraphics graphics, @Coerce Object node, CallbackInfo ci) {
        if (node instanceof CraftingTreeCycleNode cycle && cycle.gtlcore$cycleDistance() > 0) {
            var point = cycle.gtlcore$treePoint();
            graphics.drawString(Minecraft.getInstance().font, Component.translatable("gtlcore.ae.graph.cycle_marker"),
                    point.x * spacingX + outputX - 2, point.y * spacingY + outputY - 8, 0x55FFFF, true);
        }
    }

    @ModifyExpressionValue(method = "draw", at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"), remap = false)
    private Object gtlcore$trackHoveredCycle(Object node) {
        gtlcore$hoveredCycle = node instanceof CraftingTreeCycleNode cycle && cycle.gtlcore$cycleDistance() > 0 ? cycle : null;
        return node;
    }

    @ModifyArg(method = "draw", at = @At(value = "INVOKE", target = "Lappeng/client/gui/AEBaseScreen;drawTooltipWithHeader(Lnet/minecraft/client/gui/GuiGraphics;IILjava/util/List;)V"), index = 3, remap = false)
    private List<Component> gtlcore$describeCycle(List<Component> lines) {
        var cycle = gtlcore$hoveredCycle;
        if (cycle == null) return lines;
        // AE2CT otherwise describes every leaf as fresh input. A repeated node is
        // a reference, not another independent material demand.
        var result = new ArrayList<>(AEKeyRendering.getTooltip(cycle.gtlcore$treeStack().what()));
        result.add(Component.translatable("gtlcore.ae.graph.cycle_reference", cycle.gtlcore$cycleDistance()).withStyle(ChatFormatting.AQUA));
        result.add(Component.translatable("gtlcore.ae.graph.cycle_reference_info").withStyle(ChatFormatting.GRAY));
        return result;
    }
}
