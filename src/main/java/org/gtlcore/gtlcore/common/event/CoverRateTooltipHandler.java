package org.gtlcore.gtlcore.common.event;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.data.GTLCovers;

import com.gregtechceu.gtceu.api.GTValues;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Locale;

@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CoverRateTooltipHandler {

    private static final String ITEM_RATE_KEY = "gtceu.universal.tooltip.item_transfer_rate_stacks";
    private static final String FLUID_RATE_KEY = "gtceu.universal.tooltip.fluid_transfer_rate";

    private CoverRateTooltipHandler() {}

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        Integer tier = coverTier(id.getPath());
        if (tier == null) return;
        List<Component> lines = event.getToolTip();
        for (int i = 0; i < lines.size(); i++) {
            if (!(lines.get(i).getContents() instanceof TranslatableContents contents)) continue;
            if (ITEM_RATE_KEY.equals(contents.getKey())) {
                lines.set(i, Component.translatable(ITEM_RATE_KEY, GTLCovers.itemTransferRate(tier) / 64));
            } else if (FLUID_RATE_KEY.equals(contents.getKey())) {
                lines.set(i, Component.translatable(FLUID_RATE_KEY, GTLCovers.fluidTransferRate(tier)));
            }
        }
    }

    private static Integer coverTier(String path) {
        if (!(path.endsWith("conveyor_module") || path.endsWith("robot_arm") ||
                path.endsWith("electric_pump") || path.endsWith("fluid_regulator"))) {
            return null;
        }
        if (path.startsWith("primitive_")) return GTValues.ULV;
        for (int tier = 0; tier < GTValues.VN.length; tier++) {
            if (path.startsWith(GTValues.VN[tier].toLowerCase(Locale.ROOT) + "_")) return tier;
        }
        return null;
    }
}
