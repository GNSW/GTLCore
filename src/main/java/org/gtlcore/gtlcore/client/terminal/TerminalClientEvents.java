package org.gtlcore.gtlcore.client.terminal;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TerminalClientEvents {

    private static final int MAX_BUTTON_WIDTH = 180;
    private static final int BUTTON_HEIGHT = 20;
    private static boolean initialOpenHandled;

    private TerminalClientEvents() {}

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof TitleScreen)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!initialOpenHandled) {
            initialOpenHandled = true;
            if (!TerminalState.hasCompleted(minecraft)) {
                event.setNewScreen(new TerminalScreen(true, null));
            }
        }
    }

    @SubscribeEvent
    public static void onRenderTitleScreen(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof TitleScreen)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!initialOpenHandled) {
            initialOpenHandled = true;
            if (!TerminalState.hasCompleted(minecraft)) {
                minecraft.execute(() -> minecraft.setScreen(new TerminalScreen(true, null)));
                return;
            }
        }

        GuiGraphics graphics = event.getGuiGraphics();
        int buttonWidth = titleButtonWidth(screen);
        int x = (screen.width - buttonWidth) / 2;
        int y = titleButtonY(screen);
        boolean hovered = inside(event.getMouseX(), event.getMouseY(), x, y, buttonWidth, BUTTON_HEIGHT);
        int cyan = hovered ? 0xFF8AF4FF : 0xFF4AD8EE;
        int fill = hovered ? 0xE20B2740 : 0xD2071A2C;

        graphics.fillGradient(x, y, x + buttonWidth, y + BUTTON_HEIGHT, fill, 0xD9041322);
        graphics.fill(x, y, x + buttonWidth, y + 1, cyan);
        graphics.fill(x, y + BUTTON_HEIGHT - 1, x + buttonWidth, y + BUTTON_HEIGHT, 0x9940A8C2);
        graphics.fill(x, y, x + 1, y + BUTTON_HEIGHT, 0x9940A8C2);
        graphics.fill(x + buttonWidth - 1, y, x + buttonWidth, y + BUTTON_HEIGHT, 0x9940A8C2);

        graphics.drawString(
                minecraft.font,
                Component.translatable("gtlcore.terminal.title_button"),
                x + 12,
                y + 6,
                hovered ? 0xFFE7FCFF : 0xFFA9EFFF,
                false);
    }

    @SubscribeEvent
    public static void onTitleScreenClick(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof TitleScreen) || event.getButton() != 0) {
            return;
        }

        int buttonWidth = titleButtonWidth(screen);
        int x = (screen.width - buttonWidth) / 2;
        int y = titleButtonY(screen);
        if (!inside(event.getMouseX(), event.getMouseY(), x, y, buttonWidth, BUTTON_HEIGHT)) {
            return;
        }

        event.setCanceled(true);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreen(new TerminalScreen(false, screen)));
    }

    private static int titleButtonWidth(Screen screen) {
        return Math.min(MAX_BUTTON_WIDTH, Math.max(1, screen.width - 16));
    }

    private static int titleButtonY(Screen screen) {
        return screen.height - BUTTON_HEIGHT - 7;
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
