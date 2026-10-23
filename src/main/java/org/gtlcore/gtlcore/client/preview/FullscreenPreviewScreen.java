package org.gtlcore.gtlcore.client.preview;

import org.gtlcore.gtlcore.mixin.ldlib.client.PreviewSceneAccessor;

import com.gregtechceu.gtceu.api.gui.GuiTextures;

import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

/** Reuses the preview world and renderer with a fullscreen window and candidate palette. */
public final class FullscreenPreviewScreen extends Screen {

    private final Screen parent;
    private final PreviewControls controls;
    private final PreviewNavigation navigation;
    private boolean navigating;
    private boolean selectingScene;

    public FullscreenPreviewScreen(Screen parent, PreviewControls controls) {
        super(Component.translatable("gui.gtlcore.preview.fullscreen"));
        this.parent = parent;
        this.controls = controls;
        navigation = new PreviewNavigation(controls.scene());
    }

    public PreviewControls controls() {
        return controls;
    }

    @Override
    protected void init() {
        navigating = false;
        selectingScene = false;
        controls.enter(width, height);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        GuiTextures.BACKGROUND.draw(graphics, mouseX, mouseY, 8, 8, width - 16, height - 16);
        var widget = controls.widget();
        var gui = widget.getGui().getModularUIGui();
        gui.tooltipTexts = null;
        gui.tooltipComponent = null;
        gui.tooltipFont = null;
        gui.tooltipStack = ItemStack.EMPTY;
        try {
            widget.drawInBackground(graphics, mouseX, mouseY, partialTick);
            widget.drawInForeground(graphics, mouseX, mouseY, partialTick);
            controls.drawRotationOrigin(graphics);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            if (gui.tooltipTexts != null && !gui.tooltipTexts.isEmpty()) {
                graphics.renderTooltip(gui.tooltipFont == null ? font : gui.tooltipFont, gui.tooltipTexts,
                        Optional.ofNullable(gui.tooltipComponent), mouseX, mouseY);
            }
        } finally {
            graphics.flush();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.setShaderColor(1, 1, 1, 1);
        }
    }

    @Override
    public void tick() {
        controls.widget().updateScreen();
    }

    private WidgetGroup inputWidgets() {
        // LDLib resets this per input event before allowing a widget to acquire focus.
        var widget = controls.widget();
        widget.getGui().getModularUIGui().focused = false;
        return widget;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (navigating) return true;
        boolean overScene = controls.canNavigate(x, y);
        if (overScene && button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            ((PreviewSceneAccessor) controls.scene()).gtlcore$clickPosFace(null);
            navigation.begin();
            navigating = true;
            selectingScene = false;
            return true;
        }
        if (overScene && button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        selectingScene = overScene && button == GLFW.GLFW_MOUSE_BUTTON_LEFT;
        return inputWidgets().mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (navigating) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) navigating = false;
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) selectingScene = false;
        return inputWidgets().mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (navigating) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                if (hasShiftDown()) navigation.pan(dx, dy);
                else navigation.rotate(dx, dy);
            }
            return true;
        }
        if (selectingScene && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            ((PreviewSceneAccessor) controls.scene()).gtlcore$clickPosFace(null);
            return true;
        }
        return inputWidgets().mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (controls.canNavigate(x, y)) {
            navigation.zoom(delta);
            return true;
        }
        return inputWidgets().mouseWheelMove(x, y, delta);
    }

    @Override
    public void mouseMoved(double x, double y) {
        inputWidgets().mouseMoved(x, y);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_E || minecraft.options.keyInventory.matches(key, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers) || inputWidgets().keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int key, int scanCode, int modifiers) {
        return inputWidgets().keyReleased(key, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        return inputWidgets().charTyped(character, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        navigating = false;
        selectingScene = false;
        controls.exit();
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        navigating = false;
        selectingScene = false;
        controls.exit();
    }
}
