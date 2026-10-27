package org.gtlcore.gtlcore.client.preview;

import org.gtlcore.gtlcore.mixin.ldlib.client.PreviewSceneAccessor;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.editor.Icons;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.SceneWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Position;
import com.lowdragmc.lowdraglib.utils.Size;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public final class PreviewControls {

    private static final int WINDOW_MARGIN = 8;
    private static final int CONTENT_TOP = 32;
    private static final int TOOLBAR_TOP = 12;
    private static final int BUTTON_SIZE = 14;
    private static final int BUTTON_SPACING = 16;
    private static final int CONTROL_WIDTH = 40;
    private static final int CONTROL_GAP = 2;
    private static final int ORIGIN_AXIS_LENGTH = BUTTON_SIZE;
    private static final int ORIGIN_TEXT_GAP = 2;
    private final WidgetGroup widget;
    private final SceneWidget scene;
    private final IntSupplier height;
    private final Supplier<PreviewHeight.Dimensions> dimensions;
    private final Supplier<BlockPos> controllerPosition;
    private final ButtonWidget expand;
    private final PreviewCandidatePanel candidatePanel;
    private final ButtonWidget candidateToggle;
    private final ButtonWidget locateController;
    private final PreviewPatternPanel patternPanel;
    private final ButtonWidget patternToggle;
    private final ButtonWidget originToggle;
    private final ButtonWidget replaceableToggle;
    private final Supplier<List<List<GenericStack>>> patternInputs;
    private final ImageWidget info;
    private final Component title;
    private final ItemStack titleItem;
    private final Map<Widget, Geometry> original = new IdentityHashMap<>();
    private Position parentPosition;
    private Geometry rootGeometry;
    private Camera camera;
    private boolean sceneDraggable;
    private boolean fullscreen;
    private boolean showCandidates = true;
    private boolean showPattern;
    private boolean showRotationOrigin = true;
    private boolean highlightReplaceable;
    private int width;
    private int screenHeight;
    private int toolbarLeft;

    public PreviewControls(WidgetGroup widget, SceneWidget scene, MultiblockMachineDefinition definition, IntSupplier height,
                           Supplier<PreviewHeight.Dimensions> dimensions, Supplier<BlockPos> controllerPosition, Runnable focusController,
                           Supplier<List<List<GenericStack>>> patternInputs, Consumer<GenericStack> selectMaterial,
                           Consumer<ItemStack> replaceMaterial) {
        this.widget = widget;
        this.scene = scene;
        this.height = height;
        this.dimensions = dimensions;
        this.controllerPosition = controllerPosition;
        this.patternInputs = patternInputs;
        title = Component.translatable("gui.gtlcore.preview.title", Component.translatable(definition.getDescriptionId()));
        titleItem = definition.asStack();
        expand = new ButtonWidget(138, 10, 18, 18, PreviewButtonTexture.EXPAND, click -> {
            var mc = Minecraft.getInstance();
            if (mc.screen instanceof FullscreenPreviewScreen screen && screen.controls() == this) {
                screen.onClose();
            } else if (!fullscreen && mc.screen != null) {
                mc.setScreen(new FullscreenPreviewScreen(mc.screen, this));
            }
        });
        expand.setHoverTooltips(Component.translatable("gui.gtlcore.preview.fullscreen"));
        widget.addWidget(expand);
        candidatePanel = new PreviewCandidatePanel(this::toggleCandidates, replaceMaterial);
        candidateToggle = new ButtonWidget(0, 0, BUTTON_SIZE, BUTTON_SIZE,
                org.gtlcore.gtlcore.api.gui.GuiTextures.BUTTON_VISIBLE.getSubTexture(0, 0, 1, 0.5),
                click -> toggleCandidates());
        candidateToggle.setHoverTooltips(Component.translatable("gui.gtlcore.preview.candidates"));
        candidateToggle.setVisible(false);
        candidateToggle.setActive(false);
        info = new ImageWidget(0, 0, BUTTON_SIZE, BUTTON_SIZE,
                new GuiTextureGroup(GuiTextures.BUTTON, PreviewButtonTexture.INFO));
        info.setVisible(false);
        info.setActive(false);
        locateController = new ButtonWidget(0, 0, BUTTON_SIZE, BUTTON_SIZE,
                new GuiTextureGroup(GuiTextures.BUTTON, new ItemStackTexture(titleItem)),
                click -> {
                    scene.getRenderer().getWorldUp().set(0, 1, 0);
                    focusController.run();
                });
        locateController.setHoverTooltips(Component.translatable("gui.gtlcore.preview.locate_controller"));
        locateController.setVisible(false);
        locateController.setActive(false);
        patternPanel = new PreviewPatternPanel(definition, this::togglePattern, selectMaterial);
        patternToggle = new ButtonWidget(0, 0, BUTTON_SIZE, BUTTON_SIZE,
                new GuiTextureGroup(GuiTextures.BUTTON, PreviewButtonTexture.PATTERN_MATERIALS),
                click -> togglePattern());
        patternToggle.setHoverTooltips(Component.translatable("gui.gtlcore.preview.pattern_materials"));
        patternToggle.setVisible(false);
        patternToggle.setActive(false);
        originToggle = new ButtonWidget(0, 0, BUTTON_SIZE, BUTTON_SIZE,
                new GuiTextureGroup(GuiTextures.BUTTON, PreviewButtonTexture.ROTATION_ORIGIN),
                click -> {
                    showRotationOrigin = !showRotationOrigin;
                    updateOriginButton();
                });
        updateOriginButton();
        originToggle.setVisible(false);
        originToggle.setActive(false);
        replaceableToggle = new ButtonWidget(0, 0, BUTTON_SIZE, BUTTON_SIZE,
                new GuiTextureGroup(GuiTextures.BUTTON, Icons.EDIT_OFF),
                click -> {
                    highlightReplaceable = !highlightReplaceable;
                    updateReplaceableButton();
                });
        updateReplaceableButton();
        replaceableToggle.setVisible(false);
        replaceableToggle.setActive(false);
    }

    private void updateReplaceableButton() {
        replaceableToggle.setButtonTexture(GuiTextures.BUTTON, highlightReplaceable ? Icons.EDIT_ON : Icons.EDIT_OFF);
        replaceableToggle.setHoverTooltips(Component.translatable(highlightReplaceable ?
                "gui.gtlcore.preview.hide_replaceable" : "gui.gtlcore.preview.highlight_replaceable"));
    }

    private void updateOriginButton() {
        originToggle.setButtonTexture(GuiTextures.BUTTON, showRotationOrigin ?
                PreviewButtonTexture.ROTATION_ORIGIN : PreviewButtonTexture.ROTATION_ORIGIN_HIDDEN);
        originToggle.setHoverTooltips(Component.translatable(showRotationOrigin ?
                "gui.gtlcore.preview.hide_rotation_origin" : "gui.gtlcore.preview.show_rotation_origin"));
    }

    public WidgetGroup widget() {
        return widget;
    }

    public SceneWidget scene() {
        return scene;
    }

    public ButtonWidget expandButton() {
        return expand;
    }

    public int controllerHeight() {
        return height.getAsInt();
    }

    public PreviewHeight.Dimensions dimensions() {
        return dimensions.get();
    }

    public boolean fullscreen() {
        return fullscreen;
    }

    public boolean highlightingReplaceable() {
        return fullscreen && highlightReplaceable;
    }

    public void setCandidates(List<PreviewCandidatePanel.Group> groups) {
        candidatePanel.setGroups(groups);
    }

    public void showCandidates() {
        showCandidates = true;
        layout();
    }

    public void selectPatternMaterial(AEKey key) {
        patternPanel.selectMaterial(key);
    }

    public void refreshPatternInputs() {
        if (showPattern) patternPanel.setInputs(patternInputs.get());
    }

    public void setPatternTransfer(BooleanSupplier available, Runnable transfer) {
        patternPanel.setTransfer(available, transfer);
    }

    private void toggleCandidates() {
        showCandidates = !showCandidates;
        layout();
    }

    private void togglePattern() {
        showPattern = !showPattern;
        if (showPattern) refreshPatternInputs();
        else patternPanel.close();
        layout();
    }

    public boolean canNavigate(double x, double y) {
        if (!fullscreen || !scene.isMouseOverElement(x, y) || ((PreviewSceneAccessor) scene).gtlcore$dragging()) return false;
        for (var child : widget.widgets) {
            if (child != scene && child.isVisible() && child.isActive() && child.isMouseOverElement(x, y)) return false;
        }
        return true;
    }

    public void enter(int width, int height) {
        if (!fullscreen) {
            rootGeometry = Geometry.of(widget);
            parentPosition = widget.getParentPosition();
            camera = Camera.of(scene);
            sceneDraggable = ((PreviewSceneAccessor) scene).gtlcore$draggable();
            scene.setDraggable(false);
            fullscreen = true;
            widget.addWidget(candidatePanel);
            widget.addWidget(candidateToggle);
            widget.addWidget(info);
            widget.addWidget(locateController);
            widget.addWidget(patternPanel);
            widget.addWidget(patternToggle);
            widget.addWidget(originToggle);
            widget.addWidget(replaceableToggle);
            resetInteraction();
            expand.setButtonTexture(GuiTextures.BUTTON, GuiTextures.CLOSE_ICON);
            expand.setHoverTooltips(Component.translatable("gui.gtlcore.preview.restore"));
        }
        this.width = width;
        screenHeight = height;
        layout();
    }

    public void sceneChanged() {
        if (showPattern) patternPanel.setInputs(patternInputs.get());
        // Page/layer/module changes establish a new camera framing to return to.
        if (fullscreen) camera = Camera.of(scene);
    }

    public void layout() {
        if (!fullscreen) return;
        // Save only live children. Page/module changes replace the material strip.
        original.keySet().removeIf(child -> !widget.widgets.contains(child));
        for (var child : widget.widgets) {
            if (!fullscreenOnly(child)) original.computeIfAbsent(child, Geometry::of);
        }
        widget.setParentPosition(Position.ORIGIN);
        widget.setSelfPosition(0, 0);
        widget.setSize(width, screenHeight);
        candidatePanel.setVisible(showCandidates);
        candidatePanel.setActive(showCandidates);
        candidatePanel.setSelfPosition(WINDOW_MARGIN * 2, CONTENT_TOP);
        int availableWidth = width - (showPattern ? patternPanel.getSizeWidth() + WINDOW_MARGIN : 0);
        candidatePanel.layout(Math.min(PreviewCandidatePanel.preferredWidth(), availableWidth / 2 - WINDOW_MARGIN * 2),
                Math.max(1, screenHeight - CONTENT_TOP - WINDOW_MARGIN * 2));
        patternPanel.setSelfPosition(width - WINDOW_MARGIN * 2 - patternPanel.getSizeWidth(), CONTENT_TOP);
        patternPanel.setVisible(showPattern);
        patternPanel.setActive(showPattern);
        int sceneLeft = showCandidates ? candidatePanel.getPositionX() + candidatePanel.getSizeWidth() + WINDOW_MARGIN : WINDOW_MARGIN + 4;
        int sceneRight = showPattern ? patternPanel.getPositionX() - WINDOW_MARGIN : width - WINDOW_MARGIN - 4;
        int buttonX = width - WINDOW_MARGIN - 4 - BUTTON_SIZE;
        candidateToggle.setSelfPosition(buttonX - BUTTON_SPACING, TOOLBAR_TOP);
        candidateToggle.setButtonTexture(org.gtlcore.gtlcore.api.gui.GuiTextures.BUTTON_VISIBLE
                .getSubTexture(0, showCandidates ? 0 : 0.5, 1, 0.5));
        candidateToggle.setVisible(true);
        candidateToggle.setActive(true);
        info.setSelfPosition(buttonX - BUTTON_SPACING * 2, TOOLBAR_TOP);
        info.setHoverTooltips(title, dimensionsText(), heightText(), Component.translatable("gui.gtlcore.preview.controls"));
        info.setVisible(true);
        info.setActive(true);
        locateController.setSelfPosition(buttonX - BUTTON_SPACING * 3, TOOLBAR_TOP);
        locateController.setVisible(true);
        locateController.setActive(true);
        patternToggle.setSelfPosition(buttonX - BUTTON_SPACING * 4, TOOLBAR_TOP);
        patternToggle.setHoverTooltips(Component.translatable(showPattern ?
                "gui.gtlcore.preview.hide_pattern_materials" : "gui.gtlcore.preview.pattern_materials"));
        patternToggle.setVisible(true);
        patternToggle.setActive(true);
        originToggle.setSelfPosition(buttonX - BUTTON_SPACING * 5, TOOLBAR_TOP);
        originToggle.setVisible(true);
        originToggle.setActive(true);
        replaceableToggle.setSelfPosition(buttonX - BUTTON_SPACING * 6, TOOLBAR_TOP);
        replaceableToggle.setVisible(true);
        replaceableToggle.setActive(true);
        long controlCount = widget.widgets.stream()
                .filter(child -> child instanceof ButtonWidget && child != expand && !fullscreenOnly(child) && original.get(child).visible)
                .count();
        toolbarLeft = replaceableToggle.getPositionX() - (int) controlCount * (CONTROL_WIDTH + CONTROL_GAP);
        int controlX = toolbarLeft;
        for (var child : widget.widgets) {
            if (fullscreenOnly(child)) continue;
            if (child == scene) {
                child.setSelfPosition(sceneLeft, CONTENT_TOP);
                child.setSize(Math.max(1, sceneRight - sceneLeft),
                        Math.max(1, screenHeight - CONTENT_TOP - WINDOW_MARGIN - 4));
            } else if (child == expand) {
                child.setSelfPosition(buttonX, TOOLBAR_TOP);
                child.setSize(BUTTON_SIZE, BUTTON_SIZE);
            } else if (child instanceof ButtonWidget && original.get(child).visible) {
                child.setSelfPosition(controlX, TOOLBAR_TOP);
                child.setSize(CONTROL_WIDTH, BUTTON_SIZE);
                child.setVisible(true);
                child.setActive(original.get(child).active);
                controlX += CONTROL_WIDTH + CONTROL_GAP;
            } else if (child instanceof ButtonWidget || child instanceof DraggableScrollableWidgetGroup || child instanceof SlotWidget ||
                    child instanceof ImageWidget image && image.getImage() instanceof TextTexture) {
                        child.setVisible(false);
                        child.setActive(false);
                    }
        }
    }

    private boolean fullscreenOnly(Widget child) {
        return child == candidatePanel || child == candidateToggle || child == info || child == locateController ||
                child == patternPanel || child == patternToggle || child == originToggle || child == replaceableToggle;
    }

    public void exit() {
        if (!fullscreen) return;
        fullscreen = false;
        resetInteraction();
        candidatePanel.close();
        patternPanel.close();
        showPattern = false;
        highlightReplaceable = false;
        updateReplaceableButton();
        widget.removeWidget(candidatePanel);
        widget.removeWidget(candidateToggle);
        widget.removeWidget(info);
        widget.removeWidget(locateController);
        widget.removeWidget(patternPanel);
        widget.removeWidget(patternToggle);
        widget.removeWidget(originToggle);
        widget.removeWidget(replaceableToggle);
        rootGeometry.restore(widget);
        widget.setParentPosition(parentPosition);
        for (var child : widget.widgets) {
            var saved = original.get(child);
            if (saved != null) saved.restore(child);
        }
        candidateToggle.setVisible(false);
        candidateToggle.setActive(false);
        info.setVisible(false);
        info.setActive(false);
        locateController.setVisible(false);
        locateController.setActive(false);
        patternToggle.setVisible(false);
        patternToggle.setActive(false);
        originToggle.setVisible(false);
        originToggle.setActive(false);
        replaceableToggle.setVisible(false);
        replaceableToggle.setActive(false);
        original.clear();
        camera.restore(scene);
        scene.setDraggable(sceneDraggable);
        expand.setButtonTexture(PreviewButtonTexture.EXPAND);
        expand.setHoverTooltips(Component.translatable("gui.gtlcore.preview.fullscreen"));
    }

    private void resetInteraction() {
        var access = (PreviewSceneAccessor) scene;
        access.gtlcore$dragging(false);
        access.gtlcore$clickPosFace(null);
        var gui = widget.getGui().getModularUIGui();
        if (gui.lastFocus != null) gui.lastFocus.setFocus(false);
        gui.focused = false;
    }

    private Component heightText() {
        int distance = controllerHeight();
        return distance < 0 ? Component.translatable("gui.gtlcore.preview.height_unknown") : Component.translatable("gui.gtlcore.preview.height", distance);
    }

    private Component dimensionsText() {
        var size = dimensions();
        return size.known() ? Component.translatable("gui.gtlcore.preview.dimensions",
                size.width(), size.depth(), size.height()) : Component.translatable("gui.gtlcore.preview.dimensions_unknown");
    }

    public void drawRotationOrigin(GuiGraphics graphics) {
        if (!fullscreen || !showRotationOrigin) return;
        // Navigation orbits around lookAt, which projects to the viewport center in both projections.
        var renderer = scene.getRenderer();
        var origin = renderer.getLookAt();
        var relativeOrigin = new Vec3(origin).subtract(Vec3.atCenterOf(controllerPosition.get()));
        float x = scene.getPositionX() + scene.getSizeWidth() / 2f;
        float y = scene.getPositionY() + scene.getSizeHeight() / 2f;
        var font = Minecraft.getInstance().font;
        graphics.flush();
        graphics.enableScissor(scene.getPositionX(), scene.getPositionY(),
                scene.getPositionX() + scene.getSizeWidth(), scene.getPositionY() + scene.getSizeHeight());
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        try {
            var modelView = RenderSystem.getModelViewStack();
            modelView.pushPose();
            try {
                modelView.mulPoseMatrix(graphics.pose().last().pose());
                modelView.translate(x, y, 0);
                // Use the preview's full view basis, including orbiting past the poles, in GUI Y-down coordinates.
                modelView.scale(1, -1, 1);
                modelView.mulPoseMatrix(new Matrix4f().lookAlong(new Vector3f(origin).sub(renderer.getEyePos()), renderer.getWorldUp()));
                RenderSystem.applyModelViewMatrix();
                // The vanilla F3 marker includes black outlines for all three colored axes.
                RenderSystem.renderCrosshair(ORIGIN_AXIS_LENGTH);
            } finally {
                modelView.popPose();
                RenderSystem.applyModelViewMatrix();
                // Vanilla restores depth writes after drawing its axes; keep this overlay read-only.
                RenderSystem.depthMask(false);
            }
            int textY = Math.round(y + ORIGIN_AXIS_LENGTH) + ORIGIN_TEXT_GAP;
            graphics.drawCenteredString(font, Component.translatable("gui.gtlcore.preview.rotation_origin"),
                    Math.round(x), textY, ColorPattern.YELLOW.color);
            var coordinates = Component.translatable("gui.gtlcore.preview.rotation_origin_coordinates",
                    String.format(Locale.ROOT, "%.2f", relativeOrigin.x()), String.format(Locale.ROOT, "%.2f", relativeOrigin.y()),
                    String.format(Locale.ROOT, "%.2f", relativeOrigin.z()));
            graphics.drawCenteredString(font, coordinates, Math.round(x), textY + font.lineHeight + ORIGIN_TEXT_GAP,
                    ColorPattern.WHITE.color);
        } finally {
            graphics.flush();
            graphics.disableScissor();
        }
    }

    public void drawInfo(GuiGraphics graphics) {
        var font = Minecraft.getInstance().font;
        if (fullscreen) {
            graphics.renderItem(titleItem, WINDOW_MARGIN + 5, TOOLBAR_TOP - 1);
            int textX = WINDOW_MARGIN + 24;
            graphics.drawString(font, font.plainSubstrByWidth(title.getString(), Math.max(0, toolbarLeft - textX - 4)),
                    textX, TOOLBAR_TOP + (BUTTON_SIZE - font.lineHeight) / 2, ChatFormatting.DARK_GRAY.getColor(), false);
            return;
        }
        int available = widget.getSizeWidth();
        var text = heightText();
        var lines = new java.util.ArrayList<>(font.split(text, available));
        int y = widget.getPositionY() - 5 - lines.size() * font.lineHeight;
        int center = widget.getPositionX() + widget.getSizeWidth() / 2;
        if (Minecraft.getInstance().screen instanceof PreviewHeaderBounds header) {
            float screenY = graphics.pose().last().pose().transformPosition(0, y, 0, new Vector3f()).y;
            if (screenY < header.gtlcore$headerBottom() + 5) {
                lines = new java.util.ArrayList<>(font.split(text, Math.max(20, available - 28)));
                y = widget.getPositionY() + 16;
                center -= 12;
            }
        }
        for (var line : lines) {
            graphics.drawString(font, line, center - font.width(line) / 2, y, 0xFFE4E4E4, true);
            y += font.lineHeight;
        }
    }

    private record Geometry(Position position, Size size, boolean visible, boolean active) {

        static Geometry of(Widget widget) {
            return new Geometry(widget.getSelfPosition(), widget.getSize(), widget.isVisible(), widget.isActive());
        }

        void restore(Widget widget) {
            widget.setSelfPosition(position);
            widget.setSize(size);
            widget.setVisible(visible);
            widget.setActive(active);
        }
    }

    private record Camera(Vector3f center, Vector3f up, float zoom, float yaw, float pitch, boolean ortho) {

        static Camera of(SceneWidget scene) {
            return new Camera(new Vector3f(scene.getCenter()), new Vector3f(scene.getRenderer().getWorldUp()),
                    scene.getZoom(), scene.getRotationYaw(), scene.getRotationPitch(),
                    ((PreviewSceneAccessor) scene).gtlcore$ortho());
        }

        void restore(SceneWidget scene) {
            scene.getRenderer().getWorldUp().set(up);
            scene.useOrtho(ortho);
            scene.setCenter(center);
            scene.setZoom(zoom);
            scene.setCameraYawAndPitch(yaw, pitch);
        }
    }
}
