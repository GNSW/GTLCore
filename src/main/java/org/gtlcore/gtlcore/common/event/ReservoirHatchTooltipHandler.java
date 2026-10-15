package org.gtlcore.gtlcore.common.event;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.data.GTLMachines;

import com.gregtechceu.gtceu.common.data.GTMachines;
import com.gregtechceu.gtceu.utils.FormattingUtil;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ReservoirHatchTooltipHandler {

    private static final String CAPACITY_KEY = "gtceu.universal.tooltip.fluid_storage_capacity";
    private static final String MODIFY_KEY = "gtlcore.registry.modify";

    private ReservoirHatchTooltipHandler() {}

    private static boolean reservoirHatchPatched;

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        if (reservoirHatchPatched) return;
        reservoirHatchPatched = true;
        GTMachines.RESERVOIR_HATCH.setTooltipBuilder(GTMachines.RESERVOIR_HATCH.getTooltipBuilder().andThen((stack, components) -> {
            groupCapacity(components);
            GTLMachines.GTL_MODIFY.accept(stack, components);
        }));
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String path = id.getPath();
        boolean reservoir = path.contains("reservoir_hatch");
        if (!reservoir && !path.contains("infinite_water_cover")) return;
        List<Component> lines = event.getToolTip();
        if (reservoir) {
            groupCapacity(lines);
        }
        String modify = Component.translatable(MODIFY_KEY).getString();
        if (lines.stream().noneMatch(line -> modify.equals(line.getString()))) {
            GTLMachines.GTL_MODIFY.accept(stack, lines);
        }
    }

    public static void groupCapacity(List<Component> lines) {
        String grouped = FormattingUtil.formatNumbers(Long.MAX_VALUE);
        String template = Component.translatable(CAPACITY_KEY).getString();
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            if (line.getContents() instanceof TranslatableContents contents && CAPACITY_KEY.equals(contents.getKey())) {
                lines.set(i, Component.literal(template.replaceAll("%[,0-9]*[sd]", grouped)));
            }
        }
    }
}
