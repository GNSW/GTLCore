package org.gtlcore.gtlcore.client.monitor;

import org.gtlcore.gtlcore.integration.gtmt.monitor.EnergyMonitorBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;

import java.util.*;

public final class EnergyMonitorScreen extends Screen {

    private final EnergyMonitorBlockEntity monitor;
    private int page, filter;
    private int lastPage;

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (delta == 0) return super.mouseScrolled(x, y, delta);
        page = Math.max(0, Math.min(lastPage, page + (delta < 0 ? 1 : -1)));
        visibleRows.clear();
        return true;
    }

    private final List<CompoundTag> visibleRows = new ArrayList<>();

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && x >= 12 && x < width - 12 && y >= 51) {
            int index = (int) ((y - 51) / 34);
            if (index < visibleRows.size() && y < 51 + index * 34 + 30) {
                CompoundTag row = visibleRows.get(index);
                String dimension = row.getString("dimension");
                org.gtlcore.gtlcore.integration.gtmt.WirelessEnergyLocator.highlightOnClient(
                        "gtlcore:wireless-energy-locator:" + dimension + "|" + row.getString("pos"));
                if (minecraft.player != null) minecraft.player.displayClientMessage(
                        Component.literal(dimension + " [" + row.getString("pos") + "]"), true);
                onClose();
                return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }

    private EnergyMonitorScreen(EnergyMonitorBlockEntity be) {
        super(Component.translatable("monitor.gtlcore.formed_title"));
        monitor = be;
    }

    public static void open(EnergyMonitorBlockEntity be) {
        Minecraft.getInstance().setScreen(new EnergyMonitorScreen(be));
    }

    protected void init() {
        int y = height - 27;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> page = Math.max(0, page - 1)).bounds(12, y, 35, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> page++).bounds(52, y, 35, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("monitor.gtlcore.filter"), b -> {
            filter = (filter + 1) % 3;
            page = 0;
        }).bounds(96, y, 110, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("monitor.gtlcore.exit"), b -> onClose()).bounds(width - 75, y, 65, 20).build());
    }

    public void tick() {
        if (monitor.isRemoved() || !monitor.formed() || minecraft.player == null || minecraft.player.distanceToSqr(monitor.getBlockPos().getCenter()) > 144) onClose();
    }

    public boolean isPauseScreen() {
        return false;
    }

    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        g.fill(6, 6, width - 6, height - 6, 0xEE101820);
        g.drawString(font, title, 12, 12, 0xFFFFFF, false);
        g.drawString(font, Component.translatable("monitor.gtlcore.details"), 12, 29, 0xAAAAAA, false);
        List<CompoundTag> rows = new ArrayList<>();
        for (Tag t : monitor.display().getList("rows", Tag.TAG_COMPOUND)) {
            CompoundTag r = (CompoundTag) t;
            int sign = new java.math.BigDecimal(r.getString("power")).signum();
            if (filter == 0 || (filter == 1 ? sign > 0 : sign < 0)) rows.add(r);
        }
        int size = Math.max(1, (height - 105) / 34);
        lastPage = Math.max(0, (rows.size() - 1) / size);
        page = Math.min(page, lastPage);
        visibleRows.clear();
        for (int i = page * size; i < Math.min(rows.size(), (page + 1) * size); i++) {
            CompoundTag r = rows.get(i);
            visibleRows.add(r.copy());
            int y = 51 + (i - page * size) * 34;
            Component name = Component.Serializer.fromJson(r.getString("name"));
            String value = r.getString("power");
            String line = (name == null ? "?" : name.getString()) + "  " + EnergyMonitorRenderer.gtPower(value);
            g.drawString(font, font.plainSubstrByWidth(line, width - 28), 14, y, EnergyMonitorRenderer.color(value), false);
            g.drawString(font, font.plainSubstrByWidth(r.getString("dimension") + " [" + r.getString("pos") + "]", width - 28), 14, y + 13, 0xBBBBBB, false);
            if (my >= y && my < y + 30 && mx >= 12 && mx < width - 12) g.renderTooltip(font, Component.literal(EnergyMonitorRenderer.compact(value) + " EU/t"), mx, my);
        }
        g.drawString(font, (page + 1) + " / " + Math.max(1, (rows.size() + size - 1) / size) + "  " + Component.translatable("monitor.gtlcore.filter" + filter).getString() + "  " + rows.size() + " / " + monitor.display().getInt("machines"), 12, height - 43, 0xCCCCCC, false);
        super.render(g, mx, my, partial);
    }
}
