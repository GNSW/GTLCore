package org.gtlcore.gtlcore.mixin.toolbelt;

import org.gtlcore.gtlcore.common.data.GTLMachines;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Pseudo
@Mixin(targets = "dev.gigaherz.toolbelt.belt.ToolBeltItem", remap = false)
public abstract class ToolBeltItemMixin {

    // Both names support the optional mod in development and in a Forge production installation.
    @Inject(method = { "appendHoverText", "m_7373_" }, at = @At("RETURN"))
    private void gtlcore$showExpandedCapacity(net.minecraft.world.item.ItemStack stack,
                                              net.minecraft.world.level.Level level,
                                              List<Component> tooltip,
                                              net.minecraft.world.item.TooltipFlag flag,
                                              CallbackInfo ci) {
        if (tooltip.isEmpty()) return;
        int index = tooltip.size() - 1;
        Component line = tooltip.get(index);
        if (line.getContents() instanceof TranslatableContents contents &&
                contents.getKey().equals("text.toolbelt.tooltip")) {
            Object[] args = contents.getArgs();
            if (args.length == 2 && Integer.valueOf(9).equals(args[1])) {
                tooltip.set(index, Component.translatable(contents.getKey(), args[0], 18).withStyle(line.getStyle()));
            }
        }
        GTLMachines.GTL_MODIFY.accept(stack, tooltip);
    }
}
