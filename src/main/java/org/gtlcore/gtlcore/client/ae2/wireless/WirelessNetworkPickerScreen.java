package org.gtlcore.gtlcore.client.ae2.wireless;

import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAePackets;
import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessNetworkPickerMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Network list for the binding tool, opened by right-clicking the air with it.
 * <p>
 * Clicking a network stores it in the tool, clicking the saved one clears it. The screen never closes
 * itself so several networks can be tried in a row; use <kbd>Esc</kbd> to leave.
 */
public class WirelessNetworkPickerScreen extends AbstractContainerScreen<WirelessNetworkPickerMenu> {

    private static final int IMAGE_WIDTH = 236;
    private static final int IMAGE_HEIGHT = 196;
    private static final int CONTENT_X = 14;
    private static final int TITLE_Y = 8;
    private static final int STATUS_Y = 30;
    private static final int LIST_Y = 54;
    private static final int ROW_HEIGHT = 24;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int SCROLLBAR_GAP = 4;

    private UUID savedNetwork;
    private String savedName;
    private int scrollOffset;
    private boolean draggingScrollbar;

    public WirelessNetworkPickerScreen(WirelessNetworkPickerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = IMAGE_WIDTH;
        this.imageHeight = IMAGE_HEIGHT;
        this.savedNetwork = menu.getSavedNetwork();
        this.savedName = findSavedName(this.savedNetwork);
    }

    @Override
    protected void init() {
        super.init();
        clampScrollOffset();
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        WirelessAeStyle.drawPanel(graphics, this.leftPos, this.topPos, this.imageWidth, this.imageHeight);
        WirelessAeStyle.drawInsetPanel(
                graphics,
                this.leftPos + 10,
                this.topPos + 24,
                this.imageWidth - 20,
                this.imageHeight - 34);
        drawNetworkRows(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, CONTENT_X, TITLE_Y, WirelessAeStyle.TEXT, false);
        WirelessAeStyle.drawStatusLight(graphics, CONTENT_X, STATUS_Y - 1, this.savedNetwork != null);
        WirelessAeStyle.drawTrimmedString(
                graphics,
                this.font,
                this.savedName == null ? Component.translatable("label.gtlcore.wireless_target.current_disconnected") : Component.translatable("label.gtlcore.wireless_target.current_connected", this.savedName),
                CONTENT_X + 14,
                STATUS_Y,
                this.imageWidth - CONTENT_X * 2 - 14,
                this.savedName == null ? WirelessAeStyle.MUTED_TEXT : WirelessAeStyle.ONLINE_TEXT);

        if (this.menu.getNetworks().isEmpty()) {
            WirelessAeStyle.drawTrimmedString(
                    graphics,
                    this.font,
                    Component.translatable("label.gtlcore.wireless_target.no_networks"),
                    CONTENT_X,
                    LIST_Y + 4,
                    this.imageWidth - CONTENT_X * 2,
                    WirelessAeStyle.WARNING_TEXT);
        }
    }

    private String findSavedName(UUID network) {
        if (network == null) {
            return null;
        }
        for (WirelessNetworkPickerMenu.Entry entry : this.menu.getNetworks()) {
            if (entry.frequency().equals(network)) {
                return entry.name();
            }
        }
        return null;
    }

    private void drawNetworkRows(GuiGraphics graphics, int mouseX, int mouseY) {
        int visibleRows = getVisibleRows();
        int totalRows = this.menu.getNetworks().size();
        this.scrollOffset = WirelessAeStyle.clampScrollOffset(this.scrollOffset, totalRows, visibleRows);
        boolean hasScrollbar = WirelessAeStyle.needsScrollbar(totalRows, visibleRows);
        int listX = getListX();
        int listY = getListY();
        int listWidth = getListWidth(hasScrollbar);

        int rows = Math.min(visibleRows, Math.max(0, totalRows - this.scrollOffset));
        for (int i = 0; i < rows; i++) {
            WirelessNetworkPickerMenu.Entry entry = this.menu.getNetworks().get(this.scrollOffset + i);
            int rowY = listY + i * ROW_HEIGHT;
            boolean selected = entry.frequency().equals(this.savedNetwork);
            boolean hovered = isInsideRect(mouseX, mouseY, listX, rowY, listWidth, BUTTON_HEIGHT);
            WirelessAeStyle.drawButtonBackground(graphics, listX, rowY, listWidth, BUTTON_HEIGHT,
                    true, selected, false, hovered);
            WirelessAeStyle.drawTrimmedString(
                    graphics,
                    this.font,
                    Component.literal(entry.name()),
                    listX + 10,
                    rowY + 5,
                    Math.max(8, listWidth - 20 - (selected ? 24 : 0)),
                    WirelessAeStyle.TEXT);
        }

        if (hasScrollbar) {
            WirelessAeStyle.drawScrollbar(
                    graphics,
                    getScrollbarX(),
                    listY,
                    getScrollbarHeight(),
                    totalRows,
                    visibleRows,
                    this.scrollOffset);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && handleScrollbarClick(mouseX, mouseY)) {
            return true;
        }
        if (button == 0 && handleNetworkClick(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingScrollbar) {
            updateScrollOffsetFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.draggingScrollbar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (isInsideRect(mouseX, mouseY, getListX(), getListY(), this.imageWidth - CONTENT_X * 2, getScrollbarHeight()) &&
                scrollBy(delta)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private boolean handleScrollbarClick(double mouseX, double mouseY) {
        int visibleRows = getVisibleRows();
        int totalRows = this.menu.getNetworks().size();
        if (!WirelessAeStyle.needsScrollbar(totalRows, visibleRows) ||
                !isInsideRect(mouseX, mouseY, getScrollbarX(), getListY(), SCROLLBAR_WIDTH, getScrollbarHeight())) {
            return false;
        }
        this.draggingScrollbar = true;
        updateScrollOffsetFromMouse(mouseY);
        return true;
    }

    private boolean handleNetworkClick(double mouseX, double mouseY) {
        int listX = getListX();
        int listY = getListY();
        int visibleRows = getVisibleRows();
        boolean hasScrollbar = WirelessAeStyle.needsScrollbar(this.menu.getNetworks().size(), visibleRows);
        if (!isInsideRect(mouseX, mouseY, listX, listY, getListWidth(hasScrollbar), visibleRows * ROW_HEIGHT)) {
            return false;
        }

        double relativeY = mouseY - listY;
        if ((int) relativeY % ROW_HEIGHT >= BUTTON_HEIGHT) {
            return true;
        }
        int index = this.scrollOffset + (int) (relativeY / ROW_HEIGHT);
        if (index >= 0 && index < this.menu.getNetworks().size()) {
            WirelessNetworkPickerMenu.Entry entry = this.menu.getNetworks().get(index);
            boolean clearing = entry.frequency().equals(this.savedNetwork);
            this.savedNetwork = clearing ? null : entry.frequency();
            this.savedName = clearing ? null : entry.name();
            WirelessAePackets.CHANNEL.sendToServer(
                    new WirelessAePackets.PickBindingToolNetworkPacket(this.menu.containerId, entry.frequency()));
        }
        return true;
    }

    private boolean scrollBy(double delta) {
        int visibleRows = getVisibleRows();
        int totalRows = this.menu.getNetworks().size();
        int nextOffset = WirelessAeStyle.clampScrollOffset(
                this.scrollOffset - (int) Math.signum(delta),
                totalRows,
                visibleRows);
        if (nextOffset == this.scrollOffset) {
            return false;
        }
        this.scrollOffset = nextOffset;
        return true;
    }

    private void updateScrollOffsetFromMouse(double mouseY) {
        this.scrollOffset = WirelessAeStyle.scrollbarOffsetFromMouse(
                mouseY,
                getListY(),
                getScrollbarHeight(),
                this.menu.getNetworks().size(),
                getVisibleRows());
    }

    private void clampScrollOffset() {
        this.scrollOffset = WirelessAeStyle.clampScrollOffset(
                this.scrollOffset,
                this.menu.getNetworks().size(),
                getVisibleRows());
    }

    private int getListX() {
        return this.leftPos + CONTENT_X;
    }

    private int getListY() {
        return this.topPos + LIST_Y;
    }

    private int getListWidth(boolean hasScrollbar) {
        return this.imageWidth - CONTENT_X * 2 - (hasScrollbar ? SCROLLBAR_WIDTH + SCROLLBAR_GAP : 0);
    }

    private int getScrollbarX() {
        return this.leftPos + this.imageWidth - CONTENT_X - SCROLLBAR_WIDTH;
    }

    private int getScrollbarHeight() {
        return getVisibleRows() * ROW_HEIGHT - 4;
    }

    private int getVisibleRows() {
        return Math.max(1, (this.topPos + this.imageHeight - 16 - getListY()) / ROW_HEIGHT);
    }

    private static boolean isInsideRect(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
    }
}
