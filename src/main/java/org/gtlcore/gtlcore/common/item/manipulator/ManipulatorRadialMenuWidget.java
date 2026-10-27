package org.gtlcore.gtlcore.common.item.manipulator;

import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.utils.Position;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.BitSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class ManipulatorRadialMenuWidget extends Widget {

    public record Entry(Supplier<Component> text, Runnable action, BooleanSupplier selected) {

        public Entry(String key, Runnable action) {
            this(key, action, () -> false);
        }

        public Entry(String key, Runnable action, BooleanSupplier selected) {
            this(() -> Component.translatable(key), action, selected);
        }
    }

    private static final int SELECT_ACTION = 1;
    private static final int BACK_ACTION = 2;
    private static final int STATE_UPDATE = 1;
    private static final int CENTER_RADIUS = 28;
    private static final int RADIUS = 94;
    private static final int TEXT_PADDING = 2;
    private static final int ICON_SIZE = 24;
    private static final int TITLE_Y = 3;
    private static final int HINT_Y = 207;
    private static final int NORMAL_COLOR = 0xEF15191D;
    private static final int HOVER_COLOR = 0xFF426D85;
    private static final int TEXT_COLOR = 0xFFE5E9EB;
    private static final int ACTIVE_TEXT_COLOR = 0xFF8EE8FF;
    private static final int HOVER_TEXT_COLOR = 0xFFFFE49C;

    private final ItemStackTexture icon;
    private final ColorRectTexture circle = new ColorRectTexture(NORMAL_COLOR).setRadius(RADIUS);
    private final ColorRectTexture center = new ColorRectTexture(0xFF353F47).setRadius(CENTER_RADIUS);
    private final ColorRectTexture centerHover = new ColorRectTexture(HOVER_COLOR).setRadius(CENTER_RADIUS);
    private List<Entry> entries = List.of();
    private List<Component> labels = List.of();
    private BitSet selected = new BitSet();
    private String title = "";
    private Runnable back;
    private boolean needsSync;

    public ManipulatorRadialMenuWidget(ItemStack tool) {
        super(0, 0, 320, 219);
        icon = new ItemStackTexture(tool.copy());
    }

    public void showMenu(String title, List<Entry> entries, Runnable back) {
        this.title = title;
        this.entries = List.copyOf(entries);
        this.labels = entries.stream().map(entry -> entry.text().get()).toList();
        this.selected = selection();
        this.back = back;
        needsSync = true;
    }

    public void setBack(Runnable back) {
        this.back = back;
    }

    private BitSet selection() {
        BitSet result = new BitSet(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).selected().getAsBoolean()) result.set(i);
        }
        return result;
    }

    @Override
    public void writeInitialData(FriendlyByteBuf buffer) {
        super.writeInitialData(buffer);
        labels = entries.stream().map(entry -> entry.text().get()).toList();
        selected = selection();
        writeState(buffer);
    }

    private void writeState(FriendlyByteBuf buffer) {
        buffer.writeUtf(title);
        buffer.writeCollection(labels, FriendlyByteBuf::writeComponent);
        buffer.writeBitSet(selected);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void readInitialData(FriendlyByteBuf buffer) {
        super.readInitialData(buffer);
        readState(buffer);
    }

    @OnlyIn(Dist.CLIENT)
    private void readState(FriendlyByteBuf buffer) {
        String menuTitle = buffer.readUtf();
        List<Component> menuLabels = buffer.readList(FriendlyByteBuf::readComponent);
        BitSet menuSelection = buffer.readBitSet();
        if (menuTitle.equals(title)) {
            labels = menuLabels;
            selected = menuSelection;
        }
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        List<Component> nextLabels = entries.stream().map(entry -> entry.text().get()).toList();
        BitSet nextSelection = selection();
        if (needsSync || !labels.equals(nextLabels) || !selected.equals(nextSelection)) {
            labels = nextLabels;
            selected = nextSelection;
            needsSync = false;
            writeUpdateInfo(STATE_UPDATE, this::writeState);
        }
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
        if (id == STATE_UPDATE) readState(buffer);
        else super.readUpdateInfo(id, buffer);
    }

    @Override
    public void handleClientAction(int id, FriendlyByteBuf buffer) {
        if (id == SELECT_ACTION) select(buffer.readVarInt());
        else if (id == BACK_ACTION) {
            if (back != null) back.run();
        } else super.handleClientAction(id, buffer);
    }

    private void select(int index) {
        if (index >= 0 && index < entries.size()) entries.get(index).action().run();
    }

    @OnlyIn(Dist.CLIENT)
    public boolean backFromClient() {
        if (back == null) return false;
        writeClientAction(BACK_ACTION, buffer -> {});
        back.run();
        playButtonClickSound();
        return true;
    }

    private Position centerPosition() {
        return getPosition().add(getSize().width / 2, getSize().height / 2);
    }

    private double angleStep() {
        return Math.PI * 2 / entries.size();
    }

    private double startAngle() {
        return -Math.PI / 2 - angleStep() / 2;
    }

    private int hovered(double mouseX, double mouseY) {
        Position position = centerPosition();
        double dx = mouseX - position.x;
        double dy = mouseY - position.y;
        double radius = Math.hypot(dx, dy);
        if (radius <= CENTER_RADIUS) return -1;
        if (radius > RADIUS || entries.isEmpty()) return -2;
        double angle = (Math.atan2(dy, dx) - startAngle() + Math.PI * 2) % (Math.PI * 2);
        return (int) (angle / angleStep());
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        int hovered = hovered(mouseX, mouseY);
        if (hovered == -1) return backFromClient();
        if (hovered < 0) return false;
        writeClientAction(SELECT_ACTION, buffer -> buffer.writeVarInt(hovered));
        select(hovered);
        playButtonClickSound();
        return true;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return keyCode == GLFW.GLFW_KEY_ESCAPE && backFromClient();
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void drawInBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        Position position = centerPosition();
        var font = Minecraft.getInstance().font;
        graphics.drawCenteredString(font, Component.translatable(title), position.x, getPosition().y + TITLE_Y, TEXT_COLOR);
        int hovered = hovered(mouseX, mouseY);
        float middleRadius = (RADIUS + CENTER_RADIUS) / 2f;
        float halfWidth = (RADIUS - CENTER_RADIUS) / 2f;
        graphics.flush();
        circle.draw(graphics, mouseX, mouseY, position.x - RADIUS, position.y - RADIUS, RADIUS * 2, RADIUS * 2);
        for (int i = 0; i < labels.size(); i++) {
            if (hovered == i || selected.get(i)) drawSector(graphics, position, i);
        }
        (hovered == -1 && back != null ? centerHover : center).draw(graphics, mouseX, mouseY, position.x - CENTER_RADIUS, position.y - CENTER_RADIUS, CENTER_RADIUS * 2, CENTER_RADIUS * 2);
        for (int i = 0; i < labels.size(); i++) {
            double angle = -Math.PI / 2 + i * angleStep();
            int x = position.x + (int) (Math.cos(angle) * middleRadius);
            int y = position.y + (int) (Math.sin(angle) * middleRadius);
            int width = (int) Math.min(halfWidth * 2 - TEXT_PADDING * 2, 2 * middleRadius * Math.sin(angleStep() / 2) - TEXT_PADDING * 2);
            List<FormattedCharSequence> lines = font.split(labels.get(i), width);
            float scale = Math.min(1, (halfWidth * 2 - TEXT_PADDING * 2) / (lines.size() * font.lineHeight));
            graphics.pose().pushPose();
            graphics.pose().translate(x, y, 0);
            graphics.pose().scale(scale, scale, 1);
            for (int line = 0; line < lines.size(); line++) {
                graphics.drawCenteredString(font, lines.get(line), 0, line * font.lineHeight - lines.size() * font.lineHeight / 2, hovered == i ? HOVER_TEXT_COLOR : selected.get(i) ? ACTIVE_TEXT_COLOR : TEXT_COLOR);
            }
            graphics.pose().popPose();
        }
        icon.draw(graphics, mouseX, mouseY, position.x - ICON_SIZE / 2f, position.y - ICON_SIZE / 2f - font.lineHeight / 2f, ICON_SIZE, ICON_SIZE);
        graphics.drawCenteredString(font, Component.translatable(back == null ? "mm.menu.modes" : "mm.menu.back"), position.x, position.y + ICON_SIZE / 2, TEXT_COLOR);
        graphics.drawCenteredString(font, Component.translatable("mm.menu.hint"), position.x, getPosition().y + HINT_Y, TEXT_COLOR);
    }

    @OnlyIn(Dist.CLIENT)
    private void drawSector(GuiGraphics graphics, Position position, int index) {
        // Derive the arc from the hit-test geometry, with at most one GUI pixel per segment.
        int segments = (int) Math.ceil(angleStep() * RADIUS);
        var matrix = graphics.pose().last().pose();
        var shader = RenderSystem.getShader();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i <= segments; i++) {
            double angle = startAngle() + (index + (double) i / segments) * angleStep();
            float x = (float) Math.cos(angle);
            float y = (float) Math.sin(angle);
            buffer.vertex(matrix, position.x + x * RADIUS, position.y + y * RADIUS, 0).color(HOVER_COLOR).endVertex();
            buffer.vertex(matrix, position.x + x * CENTER_RADIUS, position.y + y * CENTER_RADIUS, 0).color(HOVER_COLOR).endVertex();
        }
        try {
            BufferUploader.drawWithShader(buffer.end());
        } finally {
            RenderSystem.setShader(() -> shader);
        }
    }
}
