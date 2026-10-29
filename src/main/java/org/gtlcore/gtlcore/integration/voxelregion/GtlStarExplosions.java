package org.gtlcore.gtlcore.integration.voxelregion;

import com.gregtechceu.gtceu.common.data.GTItems;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

public final class GtlStarExplosions {

    private static final Item UNSTABLE_STAR = BuiltInRegistries.ITEM.get(new ResourceLocation("kubejs", "unstable_star"));

    private GtlStarExplosions() {}

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getSide().isServer() || event.isCanceled()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var stack = event.getItemStack();
        var description = level.getBlockState(event.getPos()).getBlock().getDescriptionId();
        int radius;
        if (stack.is(GTItems.QUANTUM_STAR.asItem()) && description.contains("naquadria_charge")) {
            radius = 125;
        } else if (stack.is(GTItems.GRAVI_STAR.asItem()) && description.contains("leptonic_charge")) {
            radius = 250;
        } else if (stack.is(UNSTABLE_STAR) && description.contains("quantum_chromodynamic_charge")) {
            radius = 500;
        } else {
            return;
        }
        Player player = event.getEntity();
        if (!GtlRegionExplosions.submitSphereDefault(level, player.getUUID(), event.getPos(), radius)) return;
        if (!player.isCreative()) stack.shrink(1);
    }

    public static void onItemTooltip(ItemTooltipEvent event) {
        var id = event.getItemStack().getItem().getDescriptionId();
        if (id.contains("naquadria_charge")) {
            event.getToolTip().add(Component.translatable("tooltip.gtlcore.item.explosion.0"));
        } else if (id.contains("leptonic_charge")) {
            event.getToolTip().add(Component.translatable("tooltip.gtlcore.item.explosion.1"));
        } else if (id.contains("quantum_chromodynamic_charge")) {
            event.getToolTip().add(Component.translatable("tooltip.gtlcore.item.explosion.2"));
        }
    }
}
