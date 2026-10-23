package org.gtlcore.gtlcore.client.ftbquests;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.glfw.GLFW;

/** A context visit returns to the same workbench instead of losing its filters and draft. */
final class QuestTranslationNavigation {

    private static Screen context;
    private static Screen back;
    private static boolean registered;

    private QuestTranslationNavigation() {}

    static void returnFrom(Screen screen, Screen returnScreen) {
        if (!registered) {
            registered = true;
            MinecraftForge.EVENT_BUS.addListener(QuestTranslationNavigation::opening);
            MinecraftForge.EVENT_BUS.addListener(QuestTranslationNavigation::key);
            MinecraftForge.EVENT_BUS.addListener(QuestTranslationNavigation::logout);
        }
        context = screen;
        back = returnScreen;
    }

    private static void key(ScreenEvent.KeyPressed.Pre event) {
        if (event.getScreen() == context && event.getKeyCode() == GLFW.GLFW_KEY_ESCAPE) {
            Screen target = back;
            context = back = null;
            Minecraft.getInstance().setScreen(target);
            event.setCanceled(true);
        }
    }

    private static void opening(ScreenEvent.Opening event) {
        if (back == null) return;
        if (event.getNewScreen() == back) context = back = null;
        else if (Minecraft.getInstance().screen == context && event.getNewScreen() == null) {
            event.setNewScreen(back);
            context = back = null;
        }
    }

    private static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        context = back = null;
    }
}
