package org.gtlcore.gtlcore.client.preview;

import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.pattern.MultiblockPatternEncoding;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;

import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import appeng.api.stacks.GenericStack;
import appeng.client.Point;
import appeng.client.gui.Icon;
import appeng.client.gui.layout.SlotGridLayout;
import appeng.client.gui.me.common.StackSizeRenderer;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.SlotPosition;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.Scrollbar;
import appeng.menu.SlotSemantics;
import appeng.parts.encoding.EncodingMode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Uses AE2's processing editor texture, slot layout, item rendering and scrollbar. */
public final class PreviewPatternPanel extends WidgetGroup {

    // The processing region used by AE2's ProcessingEncodingPanel.
    private static final Blitter EDITOR = Blitter.texture("guis/pattern_modes.png").src(0, 70, 126, 68);
    private static final Blitter SLOT = Icon.SLOT_BACKGROUND.getBlitter();
    private static final int COLUMNS = 3;
    private static final int ROWS = 3;
    private static final int SLOT_PITCH = 18;
    private static final int ITEM_SIZE = 16;
    private static final int SLOT_BORDER = (SLOT_PITCH - ITEM_SIZE) / 2;
    private static final int PADDING = 5;
    private static final int HEADER_HEIGHT = 22;
    private static final int FOOTER_HEIGHT = 22;
    private final Scrollbar scrollbar = new Scrollbar(Scrollbar.SMALL);
    private final SlotGridLayout inputGrid;
    private final Blitter editorBackground;
    private final Blitter editorArrow;
    private final Point inputOrigin;
    private final Point outputOrigin;
    private final Point scrollbarOrigin;
    private final List<GenericStack> outputs;
    private List<GenericStack> inputs = List.of();
    private GenericStack hovered;
    private AEKey selected;
    private boolean hoveredInput;
    private final Consumer<GenericStack> onSelect;
    private final ButtonWidget transferButton;
    private BooleanSupplier canTransfer = () -> false;
    private Runnable transfer;
    private boolean scrolling;
    private double wheelTarget;
    private float previousScroll;
    private float currentScroll;
    private float renderedScroll;

    public PreviewPatternPanel(MultiblockMachineDefinition definition, Runnable onClose, Consumer<GenericStack> onSelect) {
        super(0, 0, EDITOR.getSrcWidth() + PADDING * 2, HEADER_HEIGHT + EDITOR.getSrcHeight() + FOOTER_HEIGHT);
        this.onSelect = onSelect;
        setClientSideWidget();
        setBackground(GuiTextures.BACKGROUND);
        var style = StyleManager.loadStyleDoc("/screens/terminals/pattern_encoding_terminal.json");
        var terminal = style.getTerminalStyle();
        var bounds = new Rect2i(0, 0, terminal.getScreenWidth(), terminal.getBottom().getSrcHeight());
        var editorOrigin = style.getWidget("modePanel" + EncodingMode.PROCESSING.ordinal()).resolve(bounds);
        SlotPosition inputStyle = style.getSlots().get(SlotSemantics.PROCESSING_INPUTS.id());
        inputGrid = inputStyle.getGrid();
        inputOrigin = relative(inputStyle.resolve(bounds), editorOrigin);
        var firstInput = inputGrid.getPosition(inputOrigin.getX(), inputOrigin.getY(), 0);
        outputOrigin = relative(style.getSlots().get(SlotSemantics.PROCESSING_OUTPUTS.id()).resolve(bounds), editorOrigin);
        var scrollbarStyle = style.getWidget("processingPatternModeScrollbar");
        scrollbarOrigin = relative(scrollbarStyle.resolve(bounds), editorOrigin);
        // Resource packs can move the baked grid independently of the slot layout.
        // Keep the editor's background and arrow, and draw slots from AE2's standalone sprite.
        editorBackground = EDITOR.copy().srcWidth(scrollbarOrigin.getX() - SLOT_BORDER);
        int arrowLeft = firstInput.getX() - SLOT_BORDER + COLUMNS * SLOT_PITCH;
        int arrowRight = outputOrigin.getX() - SLOT_BORDER;
        editorArrow = EDITOR.copy().src(EDITOR.getSrcX() + arrowLeft, EDITOR.getSrcY(),
                arrowRight - arrowLeft, EDITOR.getSrcHeight());
        scrollbar.setHeight(scrollbarStyle.getHeight());
        scrollbar.setCaptureMouseWheel(false);
        outputs = MultiblockPatternEncoding.outputs(definition);
        addWidget(new ButtonWidget(getSizeWidth() - PADDING - 14, PADDING, 14, 14,
                new GuiTextureGroup(GuiTextures.BUTTON, GuiTextures.CLOSE_ICON), click -> onClose.run())
                .setHoverTooltips(Component.translatable("gui.gtlcore.preview.hide_pattern_materials")));
        transferButton = new ButtonWidget(PADDING, HEADER_HEIGHT + EDITOR.getSrcHeight() + PADDING,
                14, 14, new GuiTextureGroup(GuiTextures.BUTTON, PreviewButtonTexture.PATTERN_MATERIALS),
                click -> {
                    if (canTransfer.getAsBoolean()) transfer.run();
                });
        transferButton.setHoverTooltips(Component.translatable("gui.gtlcore.preview.transfer_pattern_hint"));
        transferButton.setVisible(false);
        transferButton.setActive(false);
        addWidget(transferButton);
        setVisible(false);
        setActive(false);
    }

    private static Point relative(Point point, Point origin) {
        return point.move(-origin.getX(), -origin.getY());
    }

    public void setInputs(List<List<GenericStack>> ingredients) {
        inputs = ingredients.stream().map(options -> options.get(0)).toList();
        int maximum = maximumScroll();
        scrollbar.setRange(0, maximum, ROWS * SLOT_PITCH);
        wheelTarget = Mth.clamp(wheelTarget, 0, maximum);
        previousScroll = Mth.clamp(previousScroll, 0, maximum);
        currentScroll = Mth.clamp(currentScroll, 0, maximum);
        renderedScroll = Mth.clamp(renderedScroll, 0, maximum);
    }

    private int maximumScroll() {
        return Math.max(0, ((inputs.size() + COLUMNS - 1) / COLUMNS - ROWS) * SLOT_PITCH);
    }

    public void selectMaterial(AEKey key) {
        selected = key;
    }

    public void setTransfer(BooleanSupplier available, Runnable transfer) {
        canTransfer = available;
        this.transfer = transfer;
    }

    public void close() {
        setVisible(false);
        setActive(false);
        inputs = List.of();
        hovered = null;
        scrollbar.setCurrentScroll(0);
        endScroll();
    }

    private void endScroll() {
        scrollbar.onMouseUp(Point.ZERO, 0);
        scrolling = false;
        syncScroll();
    }

    private void syncScroll() {
        wheelTarget = scrollbar.getCurrentScroll();
        previousScroll = currentScroll = renderedScroll = scrollbar.getCurrentScroll();
    }

    private Rect2i editorBounds() {
        return new Rect2i(getPositionX() + PADDING, getPositionY() + HEADER_HEIGHT,
                EDITOR.getSrcWidth(), EDITOR.getSrcHeight());
    }

    private void positionScrollbar() {
        var editor = editorBounds();
        scrollbar.setPosition(scrollbarOrigin.move(editor.getX(), editor.getY()));
    }

    private Rect2i inputBounds() {
        var editor = editorBounds();
        var origin = inputGrid.getPosition(editor.getX() + inputOrigin.getX(), editor.getY() + inputOrigin.getY(), 0);
        return new Rect2i(origin.getX() - SLOT_BORDER, origin.getY() - SLOT_BORDER,
                COLUMNS * SLOT_PITCH, ROWS * SLOT_PITCH);
    }

    private int inputAt(double mouseX, double mouseY) {
        var bounds = inputBounds();
        if (mouseX < bounds.getX() || mouseX >= bounds.getX() + bounds.getWidth() ||
                mouseY < bounds.getY() || mouseY >= bounds.getY() + bounds.getHeight())
            return -1;
        double x = mouseX - bounds.getX();
        double y = mouseY - bounds.getY() + renderedScroll;
        if (x % SLOT_PITCH < SLOT_BORDER || x % SLOT_PITCH >= SLOT_BORDER + ITEM_SIZE ||
                y % SLOT_PITCH < SLOT_BORDER || y % SLOT_PITCH >= SLOT_BORDER + ITEM_SIZE)
            return -1;
        int index = (int) (y / SLOT_PITCH) * COLUMNS + (int) (x / SLOT_PITCH);
        return index < inputs.size() ? index : -1;
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
        var mc = Minecraft.getInstance();
        var title = Component.translatable("gui.gtlcore.preview.pattern_materials");
        graphics.drawString(mc.font, mc.font.plainSubstrByWidth(title.getString(), getSizeWidth() - PADDING * 3 - 14),
                getPositionX() + PADDING, getPositionY() + 8, ChatFormatting.DARK_GRAY.getColor(), false);
        var editor = editorBounds();
        editorBackground.copy().dest(editor).blit(graphics);
        editorArrow.copy().dest(editor.getX() + editorArrow.getSrcX() - EDITOR.getSrcX(), editor.getY()).blit(graphics);
        positionScrollbar();
        var track = scrollbar.getBounds();
        GuiTextures.SLOT.draw(graphics, mouseX, mouseY, track.getX() - SLOT_BORDER, track.getY() - SLOT_BORDER,
                track.getWidth() + SLOT_BORDER * 2, track.getHeight() + SLOT_BORDER * 2);
        hovered = null;
        hoveredInput = false;
        renderedScroll = scrolling || !ConfigHolder.INSTANCE.graphics.enableSmoothAnimations ? (float) wheelTarget :
                Mth.lerp(partialTicks, previousScroll, currentScroll);
        int firstRow = (int) (renderedScroll / SLOT_PITCH);
        var inputBounds = inputBounds();
        graphics.enableScissor(inputBounds.getX(), inputBounds.getY(),
                inputBounds.getX() + inputBounds.getWidth(), inputBounds.getY() + inputBounds.getHeight());
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(0, -(renderedScroll - firstRow * SLOT_PITCH), 0);
            // The extra row fills the viewport while either edge is between rows.
            for (int slot = 0; slot < COLUMNS * (ROWS + 1); slot++) {
                var position = inputGrid.getPosition(editor.getX() + inputOrigin.getX(), editor.getY() + inputOrigin.getY(), slot);
                SLOT.copy().dest(position.getX() - SLOT_BORDER, position.getY() - SLOT_BORDER).blit(graphics);
                int index = firstRow * COLUMNS + slot;
                if (index >= inputs.size()) continue;
                var stack = inputs.get(index);
                if (stack.what().equals(selected)) {
                    GuiTextures.SELECT_BOX.draw(graphics, mouseX, mouseY,
                            position.getX() - SLOT_BORDER, position.getY() - SLOT_BORDER, SLOT_PITCH, SLOT_PITCH);
                }
                drawStack(graphics, stack, position);
            }
        } finally {
            graphics.pose().popPose();
            graphics.disableScissor();
        }
        int hoveredIndex = inputAt(mouseX, mouseY);
        if (hoveredIndex >= 0) {
            hovered = inputs.get(hoveredIndex);
            hoveredInput = true;
        }
        for (int slot = 0; slot < ROWS; slot++) {
            var position = outputOrigin.move(editor.getX(), editor.getY() + slot * SLOT_PITCH);
            SLOT.copy().dest(position.getX() - SLOT_BORDER, position.getY() - SLOT_BORDER).blit(graphics);
            if (slot >= outputs.size()) continue;
            var stack = outputs.get(slot);
            drawStack(graphics, stack, position);
            if (new Point(mouseX, mouseY).isIn(new Rect2i(position.getX(), position.getY(), ITEM_SIZE, ITEM_SIZE))) {
                hovered = stack;
                hoveredInput = false;
            }
        }
        scrollbar.drawForegroundLayer(graphics, editor, new Point(mouseX, mouseY));
        if (transferButton.isVisible()) {
            graphics.drawString(mc.font, Component.translatable("gui.gtlcore.preview.transfer_pattern"),
                    transferButton.getPositionX() + SLOT_PITCH, transferButton.getPositionY() + 3,
                    ChatFormatting.DARK_GRAY.getColor(), false);
        }
    }

    private void drawStack(GuiGraphics graphics, GenericStack stack, Point position) {
        var mc = Minecraft.getInstance();
        AEKeyRendering.drawInGui(mc, graphics, position.getX(), position.getY(), stack.what());
        StackSizeRenderer.renderSizeLabel(graphics, mc.font, position.getX(), position.getY(),
                stack.what().formatAmount(stack.amount(), AmountFormat.SLOT));
    }

    @Override
    public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
        if (hovered != null) {
            var tips = new ArrayList<>(AEKeyRendering.getTooltip(hovered.what()));
            tips.add(Component.translatable("gui.gtlcore.preview.material_amount",
                    hovered.what().formatAmount(hovered.amount(), AmountFormat.FULL)));
            if (hoveredInput) tips.add(Component.translatable("gui.gtlcore.preview.edit_material"));
            var gui = getGui().getModularUIGui();
            gui.tooltipTexts = tips;
            gui.tooltipStack = hovered.what().wrapForDisplayOrFilter();
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        scrollbar.tick();
        if (scrolling) syncScroll();
        else {
            previousScroll = currentScroll;
            currentScroll = (float) wheelTarget;
        }
        boolean available = canTransfer.getAsBoolean();
        transferButton.setVisible(available);
        transferButton.setActive(available);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        positionScrollbar();
        var mouse = new Point((int) mouseX, (int) mouseY);
        if (button == 0 && mouse.isIn(scrollbar.getBounds())) {
            scrolling = scrollbar.onMouseDown(mouse, button);
            if (scrolling) syncScroll();
        }
        if (button == 0 && !scrolling) {
            int index = inputAt(mouseX, mouseY);
            if (index >= 0) {
                onSelect.accept(inputs.get(index));
                return true;
            }
        }
        return isMouseOverElement(mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (scrolling && button == 0) {
            scrollbar.onMouseDrag(new Point((int) mouseX, (int) mouseY), button);
            syncScroll();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (scrolling && button == 0) {
            endScroll();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseWheelMove(double mouseX, double mouseY, double delta) {
        if (!isMouseOverElement(mouseX, mouseY)) return false;
        wheelTarget = Mth.clamp(wheelTarget - delta * SLOT_PITCH, 0, maximumScroll());
        scrollbar.setCurrentScroll((int) Math.round(wheelTarget));
        return true;
    }
}
