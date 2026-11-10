package org.gtlcore.gtlcore.client.ae2.wireless;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAePackets;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/**
 * Opens the wireless network picker with a key press, so the binding tool can be used while it sits
 * in the Curios slot instead of the main hand.
 */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class WirelessBindingPickerKeyMapping {

    public static final KeyMapping OPEN_PICKER = new KeyMapping(
            "key.gtlcore.wireless_binding_picker", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, "key.categories.gtlcore");

    private WirelessBindingPickerKeyMapping() {}

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN_PICKER);
    }

    @Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, value = Dist.CLIENT)
    static final class TickHandler {

        private TickHandler() {}

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            while (OPEN_PICKER.consumeClick()) {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.player != null && minecraft.screen == null) {
                    WirelessAePackets.CHANNEL.sendToServer(new WirelessAePackets.OpenBindingPickerPacket());
                }
            }
        }
    }
}
