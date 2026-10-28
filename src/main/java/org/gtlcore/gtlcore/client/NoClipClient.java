package org.gtlcore.gtlcore.client;

import org.gtlcore.gtlcore.common.player.NoClipManager;
import org.gtlcore.gtlcore.network.GTLNetworkHandler;
import org.gtlcore.gtlcore.network.packet.CSetNoClip;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

public final class NoClipClient {

    private static final String KEY_NAME = "key.gtlcore.toggle_no_clip";
    private static final String KEY_CATEGORY = "key.categories.gtlcore";
    private static KeyMapping toggleKey;

    private NoClipClient() {}

    public static void registerKeyMapping(RegisterKeyMappingsEvent event) {
        toggleKey = new KeyMapping(
                KEY_NAME,
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_SEMICOLON,
                KEY_CATEGORY);
        event.register(toggleKey);
    }

    public static void clientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        while (toggleKey.consumeClick()) {
            if (minecraft.screen != null) {
                continue;
            }
            boolean requested = !NoClipManager.isEnabled(player);
            NoClipManager.setEnabled(player, requested);
            boolean enabled = NoClipManager.isEnabled(player);
            if (minecraft.getConnection() != null) {
                GTLNetworkHandler.INSTANCE.sendToServer(new CSetNoClip(enabled));
            }
            player.displayClientMessage(
                    Component.translatable(
                            enabled ? "message.gtlcore.no_clip.enabled" :
                                    requested ? "message.gtlcore.no_clip.armor_required" :
                                            "message.gtlcore.no_clip.disabled"),
                    true);
        }
    }

    public static void reset(LocalPlayer player) {
        if (player != null) {
            NoClipManager.setEnabled(player, false);
        }
    }
}
