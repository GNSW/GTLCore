package org.gtlcore.gtlcore.common.event;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.data.GTLCovers;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.utils.FormattingUtil;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CoverRateTooltipHandler {

    private static final String ITEM_RATE_KEY = "gtceu.universal.tooltip.item_transfer_rate_stacks";
    private static final String ITEM_KEY = "gtceu.universal.tooltip.item_transfer_rate";
    private static final String FLUID_RATE_KEY = "gtceu.universal.tooltip.fluid_transfer_rate";
    private static final Pattern RATE_LABEL = Pattern.compile("传输速率|Transfer Rate|传输速度");
    private static final Pattern BIG_NUMBER = Pattern.compile("\\d{4,}");

    private CoverRateTooltipHandler() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onItemTooltip(ItemTooltipEvent event) {
        List<Component> lines = event.getToolTip();
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i).getString();
            if (!RATE_LABEL.matcher(text).find()) continue;
            if (lines.get(i).getContents() instanceof TranslatableContents contents) {
                String key = contents.getKey();
                if (FLUID_RATE_KEY.equals(key)) {
                    String amount = fluidAmount(event);
                    if (amount != null) {
                        text = template(key, amount);
                    }
                } else if (ITEM_RATE_KEY.equals(key) || ITEM_KEY.equals(key)) {
                    String[] itemLine = itemLine(event);
                    if (itemLine != null) {
                        text = template(itemLine[0], itemLine[1]);
                    }
                }
            }
            text = text.replace("Format error: ", "");
            Matcher matcher = BIG_NUMBER.matcher(text);
            if (matcher.find()) {
                StringBuilder builder = new StringBuilder();
                do {
                    matcher.appendReplacement(builder, Matcher.quoteReplacement(FormattingUtil.formatNumbers(Long.parseLong(matcher.group()))));
                } while (matcher.find());
                matcher.appendTail(builder);
                text = builder.toString();
            }
            lines.set(i, Component.literal(text));
        }
    }

    private static String[] itemLine(ItemTooltipEvent event) {
        Integer tier = tierOf(event);
        if (tier == null) return null;
        int rate = GTLCovers.itemTransferRate(tier);
        if (rate >= 64) {
            return new String[] { ITEM_RATE_KEY, FormattingUtil.formatNumbers(rate / 64) };
        }
        return new String[] { ITEM_KEY, FormattingUtil.formatNumbers(rate) };
    }

    private static String fluidAmount(ItemTooltipEvent event) {
        Integer tier = tierOf(event);
        if (tier == null) return null;
        return FormattingUtil.formatNumbers(GTLCovers.fluidTransferRate(tier));
    }

    private static Integer tierOf(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return null;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return coverTier(id.getPath());
    }

    private static String template(String key, String amount) {
        return I18n.get(key).replace("%d", amount).replace("%s", amount);
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
