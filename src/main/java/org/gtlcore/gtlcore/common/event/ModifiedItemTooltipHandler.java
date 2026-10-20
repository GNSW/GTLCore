package org.gtlcore.gtlcore.common.event;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.data.GTLMachines;

import com.gregtechceu.gtceu.api.item.tool.GTToolItem;
import com.gregtechceu.gtceu.api.item.tool.GTToolType;

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

import appeng.items.storage.BasicStorageCell;
import appeng.items.tools.powered.PortableCellItem;

import java.util.List;

@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ModifiedItemTooltipHandler {

    private static final String ADD_KEY = "gtlcore.registry.add";
    private static final String MODIFY_KEY = "gtlcore.registry.modify";

    private ModifiedItemTooltipHandler() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        List<Component> lines = event.getToolTip();
        String added = Component.translatable(ADD_KEY).getString();
        String modifiedText = Component.translatable(MODIFY_KEY).getString();
        // Additions take precedence, including items registered under an external mod namespace.
        if (lines.stream().anyMatch(line -> isMarker(line, ADD_KEY, added))) {
            lines.removeIf(line -> isMarker(line, MODIFY_KEY, modifiedText));
            return;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id.getNamespace().equals(GTLCore.MOD_ID) &&
                (stack.getItem() instanceof BasicStorageCell || stack.getItem() instanceof PortableCellItem)) {
            lines.removeIf(line -> isMarker(line, MODIFY_KEY, modifiedText));
            GTLMachines.GTL_ADD.accept(stack, lines);
            return;
        }
        boolean modified = ModifiedItemTooltipRules.isModified(id.getNamespace(), id.getPath());
        // M027-M028 also affect addon cells using these same inventories. Spatial/view cells are excluded.
        modified |= stack.getItem() instanceof BasicStorageCell || stack.getItem() instanceof PortableCellItem;
        // M057-M058: use the tool type, so material variants work without matching tool heads.
        if (stack.getItem() instanceof GTToolItem tool) {
            modified |= tool.getToolType() == GTToolType.SOFT_MALLET || tool.getToolType() == GTToolType.WIRE_CUTTER;
        }
        if (!modified) return;
        if (lines.stream().noneMatch(line -> isMarker(line, MODIFY_KEY, modifiedText))) {
            GTLMachines.GTL_MODIFY.accept(stack, lines);
        }
    }

    private static boolean isMarker(Component line, String key, String translated) {
        return line.getContents() instanceof TranslatableContents contents && key.equals(contents.getKey()) ||
                translated.equals(line.getString());
    }
}
