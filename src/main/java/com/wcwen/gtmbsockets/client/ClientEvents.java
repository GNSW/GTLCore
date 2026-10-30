package com.wcwen.gtmbsockets.client;

import com.wcwen.gtmbsockets.GtmBSockets;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge event glue for the socket overlay.  This class is client-only.
 */
@Mod.EventBusSubscriber(modid = GtmBSockets.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            SocketOverlay.tick();
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            SocketOverlay.render(event);
        }
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        try {
            if (!PartItems.isRegisteredPart(event.getItemStack())) {
                return;
            }
            event.getToolTip().add(Component.m_237115_("tooltip.gtmbsockets.placement")
                    .m_130940_(ChatFormatting.AQUA));
            event.getToolTip().add(Component.m_237115_("tooltip.gtmbsockets.occupied")
                    .m_130940_(ChatFormatting.GOLD));
        } catch (Throwable ignored) {
            // Tooltips must never break the inventory.
        }
    }
}
