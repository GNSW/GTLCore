package org.gtlcore.gtlcore.client.monitor;

import org.gtlcore.gtlcore.integration.monitor.MachineMonitorPackets;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

final class MachineMonitorScreen extends Screen {

    private static final int VIEW_TOP = 8;
    private final BlockPos master;
    private final BlockPos clicked;
    private int inputStart;
    private int outputStart;
    private int scrollPage = -1;
    private boolean draggingScroll;
    private int dragSide;

    MachineMonitorScreen(BlockPos master, BlockPos clicked) {
        super(Component.translatable("block.gtlcore.machine_monitor_panel"));
        this.master = master;
        this.clicked = clicked;
    }

    @Override
    protected void init() {
        int y = height - 27;
        addRenderableWidget(Button.builder(Component.translatable("monitor.gtlcore.machine.unlink"), button -> MachineMonitorPackets.action(clicked, 0, true)).bounds(width / 2 - 94, y, 90, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("monitor.gtlcore.machine.close"), button -> onClose()).bounds(width / 2 + 4, y, 90, 20).build());
    }

    private void syncScroll(MachineMonitorClient.Display display) {
        int page = display.state.getInt("Page");
        if (scrollPage != page) {
            scrollPage = page;
            inputStart = outputStart = page * MachineMonitorPackets.ROWS_PER_PAGE;
        }
        inputStart = clampStart(inputStart, display.inputs.size());
        outputStart = clampStart(outputStart, display.outputs.size());
    }

    private static int clampStart(int start, int count) {
        return Math.max(0, Math.min(start, Math.max(0, count - MachineMonitorPackets.ROWS_PER_PAGE)));
    }

    private float viewScale() {
        return Math.min((width - 20) / MachineMonitorView.WIDTH, (height - 45) / MachineMonitorView.HEIGHT);
    }

    private float viewLeft(float scale) {
        return (width - MachineMonitorView.WIDTH * scale) / 2;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            var display = MachineMonitorClient.display(master);
            if (display != null && display.state.getBoolean("HasRecipe")) {
                float scale = viewScale();
                double x = (mouseX - viewLeft(scale)) / scale;
                double y = (mouseY - VIEW_TOP) / scale;
                int trackY = MachineMonitorView.ROWS_TOP;
                int trackH = MachineMonitorPackets.ROWS_PER_PAGE * MachineMonitorView.ROW_HEIGHT;
                int side = Math.abs(x - 148) <= 3 ? 0 : (Math.abs(x - 295) <= 3 ? 1 : -1);
                if (side >= 0 && y >= trackY - 2 && y <= trackY + trackH) {
                    draggingScroll = true;
                    dragSide = side;
                    applyScrollDrag(display, y);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScroll && button == 0) {
            var display = MachineMonitorClient.display(master);
            if (display != null) {
                float scale = viewScale();
                applyScrollDrag(display, (mouseY - VIEW_TOP) / scale);
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingScroll = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void applyScrollDrag(MachineMonitorClient.Display display, double y) {
        int trackY = MachineMonitorView.ROWS_TOP;
        int trackH = MachineMonitorPackets.ROWS_PER_PAGE * MachineMonitorView.ROW_HEIGHT;
        double fraction = Math.max(0, Math.min(1, (y - trackY) / trackH));
        int count = dragSide == 0 ? display.inputs.size() : display.outputs.size();
        int maxStart = Math.max(0, count - MachineMonitorPackets.ROWS_PER_PAGE);
        int start = (int) Math.round(fraction * maxStart);
        if (dragSide == 0) {
            inputStart = start;
        } else {
            outputStart = start;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        var display = MachineMonitorClient.display(master);
        if (display == null || !display.state.getBoolean("HasRecipe") || delta == 0) return super.mouseScrolled(mouseX, mouseY, delta);
        float scale = viewScale();
        double x = (mouseX - viewLeft(scale)) / scale;
        double y = (mouseY - VIEW_TOP) / scale - MachineMonitorView.HEADER_HEIGHT;
        if (y < 81 || y >= 194) return super.mouseScrolled(mouseX, mouseY, delta);
        syncScroll(display);
        int step = delta > 0 ? -1 : 1;
        if (x >= 7 && x < 146) {
            inputStart = clampStart(inputStart + step, display.inputs.size());
            return true;
        }
        if (x >= 154 && x < 293) {
            outputStart = clampStart(outputStart + step, display.outputs.size());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void tick() {
        if (minecraft.player == null || MachineMonitorClient.display(master) == null) {
            onClose();
            return;
        }
        double reach = minecraft.player.getBlockReach() + 1;
        if (minecraft.player.distanceToSqr(clicked.getCenter()) > reach * reach) onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        var display = MachineMonitorClient.display(master);
        if (display != null) {
            syncScroll(display);
            float scale = viewScale();
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(viewLeft(scale), VIEW_TOP, 0);
            pose.scale(scale, scale, 1);
            MachineMonitorView.draw(new MachineMonitorView.GuiCanvas(graphics), display, inputStart, outputStart);
            pose.popPose();
            int count = Math.max(display.inputs.size(), display.outputs.size());
            String page = (display.state.getInt("Page") + 1) + " / " + Math.max(1, (count + MachineMonitorPackets.ROWS_PER_PAGE - 1) / MachineMonitorPackets.ROWS_PER_PAGE);
            graphics.drawString(font, page, 8, height - 20, 0x90969F, false);
            float localX = (mouseX - viewLeft(scale)) / scale;
            float localY = (mouseY - VIEW_TOP) / scale;
            tooltip(graphics, display, localX, localY, mouseX, mouseY);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void tooltip(GuiGraphics graphics, MachineMonitorClient.Display display, float x, float y, int mouseX, int mouseY) {
        if (y >= 7 && y < MachineMonitorView.HEADER_HEIGHT + 1 && x >= 7 && x < 293) {
            graphics.renderComponentTooltip(font, List.of(MachineMonitorView.machineName(display.state), MachineMonitorView.location(display.state)), mouseX, mouseY);
            return;
        }
        float bodyY = y - MachineMonitorView.HEADER_HEIGHT;
        if (bodyY >= 7 && bodyY < 24 && x >= 7 && x < 146) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("monitor.gtlcore.machine.state." + display.state.getString("Status")));
            String reason = display.state.getString(display.state.getBoolean("Working") ? "work_reason" : "reason");
            if (!reason.isEmpty()) {
                Component text = Component.Serializer.fromJson(reason);
                if (text != null) lines.add(text);
            }
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }
        if (display.state.getBoolean("HasRecipe") && bodyY >= 30 && bodyY < 74 && x >= 154 && x < 293) {
            graphics.renderComponentTooltip(font, List.of(MachineMonitorView.energy(display.state, false)), mouseX, mouseY);
            return;
        }
        int rowsTop = MachineMonitorView.ROWS_TOP - 1;
        if (!display.state.getBoolean("HasRecipe") || y < rowsTop || y >= rowsTop + MachineMonitorPackets.ROWS_PER_PAGE * MachineMonitorView.ROW_HEIGHT) return;
        List<MachineMonitorView.Row> rows;
        int start;
        if (x >= 11 && x <= 142) {
            rows = display.inputs;
            start = inputStart;
        } else if (x >= 158 && x <= 289) {
            rows = display.outputs;
            start = outputStart;
        } else return;
        int index = start + (int) ((y - rowsTop) / MachineMonitorView.ROW_HEIGHT);
        if (index >= rows.size()) return;
        var row = rows.get(index);
        List<Component> lines = new ArrayList<>();
        lines.add(row.name());
        lines.add(Component.translatable("monitor.gtlcore.machine.amount", row.amount(false)));
        if (row.nonConsumed) lines.add(Component.translatable("monitor.gtlcore.machine.non_consumed"));
        else if (row.chance > 0 && row.chance < row.maxChance) {
            lines.add(Component.translatable("monitor.gtlcore.machine.variable_chance"));
        } else if (!display.state.getBoolean("Computation") || row.tick) {
            lines.add(Component.translatable("monitor.gtlcore.machine.rate", row.rate(display.state, false)));
        }
        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
    }
}
