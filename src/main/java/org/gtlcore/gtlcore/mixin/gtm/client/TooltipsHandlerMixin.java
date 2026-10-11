package org.gtlcore.gtlcore.mixin.gtm.client;

import org.gtlcore.gtlcore.common.data.source_tooltip.SourceTooltip;
import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAeNetworkRuntime;

import com.gregtechceu.gtceu.client.TooltipsHandler;
import com.gregtechceu.gtceu.common.data.GTItems;
import com.gregtechceu.gtceu.utils.FormattingUtil;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

@Mixin(TooltipsHandler.class)
public abstract class TooltipsHandlerMixin {

    private static final ResourceLocation ME_PATTERN_BUFFER_ID = new ResourceLocation("gtceu", "me_pattern_buffer");
    private static final String QUICK_CONNECT_TOOLTIP_KEY = "tooltip.gtlcore.wireless_bookmark.quick_connect";
    private static final Set<String> MULTIBLOCK_SHARING_TOOLTIP_KEYS = Set.of(
            "gtceu.universal.enabled",
            "gtceu.universal.disabled",
            "gtmthings.universal.disabled");

    @Inject(method = "appendTooltips",
            at = @At("HEAD"),
            remap = false)
    private static void appendCustomItemTooltips(ItemStack stack, TooltipFlag flag, List<Component> tooltips, CallbackInfo ci) {
        SourceTooltip.append(stack.getItem(), tooltips::add);
        if (stack.is(GTItems.COVER_INFINITE_WATER.get())) {
            for (int i = 0; i < tooltips.size(); i++) {
                Component line = tooltips.get(i);
                if (line.getContents() instanceof TranslatableContents contents &&
                        "gtceu.universal.tooltip.produces_fluid".equals(contents.getKey())) {
                    tooltips.set(i, Component.translatable("tooltip.gtlcore.infinite_water_cover.production",
                            FormattingUtil.formatNumbers(Integer.MAX_VALUE)).setStyle(line.getStyle()));
                }
            }
        }
        if (ME_PATTERN_BUFFER_ID.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()))) {
            tooltips.add(Component.translatable("gtlcore.machine.pattern_quick_upload.tooltip"));
        }
    }

    @Inject(method = "appendTooltips",
            at = @At("RETURN"),
            remap = false)
    private static void appendWirelessQuickConnectTooltip(ItemStack stack, TooltipFlag flag, List<Component> tooltips, CallbackInfo ci) {
        if (WirelessAeNetworkRuntime.shouldShowQuickConnectTooltip(ForgeRegistries.ITEMS.getKey(stack.getItem()))) {
            tooltips.add(findMultiblockSharingTooltipIndex(tooltips),
                    Component.translatable(QUICK_CONNECT_TOOLTIP_KEY).withStyle(ChatFormatting.GOLD));
        }
    }

    private static int findMultiblockSharingTooltipIndex(List<Component> tooltips) {
        for (int i = 0; i < tooltips.size(); i++) {
            if (tooltips.get(i).getContents() instanceof TranslatableContents contents &&
                    MULTIBLOCK_SHARING_TOOLTIP_KEYS.contains(contents.getKey())) {
                return i;
            }
        }
        return tooltips.size();
    }

    @Inject(method = "appendFluidTooltips",
            at = @At("RETURN"),
            remap = false)
    private static void appendCustomFluidTooltips(Fluid fluid, long amount, Consumer<Component> tooltips, TooltipFlag flag, CallbackInfo ci) {
        SourceTooltip.append(fluid, tooltips);
    }
}
