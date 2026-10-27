package org.gtlcore.gtlcore.common.item.manipulator;

import org.gtlcore.gtlcore.common.item.MatterManipulatorBehavior;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.manipulator.ManipulatorNetwork;

import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.UITemplate;

import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.gtlcore.gtlcore.common.item.manipulator.ManipulatorRadialMenuWidget.Entry;

public final class ManipulatorUI extends WidgetGroup {

    private static final int WIDTH = 384;
    private static final int HEIGHT = 354;
    private static final int PANEL_Y = 27;
    private static final int PANEL_HEIGHT = 280;

    private final ItemStack tool;
    private final Player player;
    private final ManipulatorRadialMenuWidget radial;
    private final WidgetGroup shortcuts = new WidgetGroup(0, 291, WIDTH, 18);
    private final List<WidgetGroup> panels = new ArrayList<>();
    private final WidgetGroup coordinates;
    private final WidgetGroup array;
    private final WidgetGroup inventory;
    private ManipulatorSettings.Mode mode;

    private ManipulatorUI(ItemStack tool, Player player) {
        super(0, 0, WIDTH, HEIGHT);
        this.tool = tool;
        this.player = player;
        mode = settings().mode;
        radial = new ManipulatorRadialMenuWidget(tool);
        radial.setSelfPosition(32, 60);
        addWidget(radial);
        coordinates = coordinates();
        array = array();
        inventory = inventory();
        addWidget(shortcuts);
        navigationButton(shortcuts, 8, 0, 90, "mm.materials", () -> openPanel(inventory, this::showModes));
        navigationButton(shortcuts, 101, 0, 90, "mm.menu.network", this::showNetwork);
        navigationButton(shortcuts, 194, 0, 90, "mm.menu.plans", this::showPlans);
        navigationButton(shortcuts, 287, 0, 89, "mm.menu.task", this::showTask);
        addWidget(new LabelWidget(8, 316, () -> MatterManipulatorBehavior.statusKey(tool)));
        addWidget(new LabelWidget(8, 333, () -> MatterManipulatorBehavior.statusDetail(tool)));
        showModes();
    }

    public static ModularUI create(HeldItemUIFactory.HeldItemHolder holder, Player player) {
        return new ModularUI(new ManipulatorUI(holder.getHeld(), player), holder, player);
    }

    private ManipulatorSettings settings() {
        return ManipulatorSettings.read(tool);
    }

    private void server(Runnable action) {
        if (!player.level().isClientSide) action.run();
    }

    private void edit(Consumer<ManipulatorSettings> action) {
        server(() -> {
            var settings = settings();
            action.accept(settings);
            settings.save(tool);
            player.getInventory().setChanged();
        });
    }

    private Entry action(String key, Runnable action) {
        return new Entry(key, () -> server(action));
    }

    private Entry editEntry(String key, Consumer<ManipulatorSettings> action) {
        return new Entry(key, () -> edit(action));
    }

    private void command(MatterManipulatorBehavior.Action action) {
        server(() -> MatterManipulatorBehavior.action((ServerPlayer) player, tool, action, -1));
    }

    private void show(String title, List<Entry> entries, Runnable back) {
        for (WidgetGroup panel : panels) panel.setVisible(false).setActive(false);
        radial.setVisible(true).setActive(true);
        shortcuts.setVisible(true).setActive(true);
        radial.showMenu(title, entries, back);
    }

    private void showModes() {
        List<Entry> entries = new ArrayList<>();
        for (var value : ManipulatorSettings.Mode.values()) {
            entries.add(new Entry(key("mm.mode.", value), () -> {
                mode = value;
                edit(settings -> {
                    settings.mode = value;
                    settings.paste = false;
                });
                showMode();
            }, () -> settings().mode == value));
        }
        entries.add(new Entry("mm.menu.removal", () -> showRemoval(this::showModes)));
        show("mm.menu.modes", entries, null);
    }

    private void showMode() {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("mm.menu.coordinates", () -> openPanel(coordinates, this::showMode)));
        switch (mode) {
            case BUILD -> {
                entries.add(new Entry("mm.menu.shape", this::showShape));
                entries.add(new Entry("mm.menu.material", this::showMaterials));
            }
            case REMOVE -> entries.add(new Entry("mm.menu.removal", () -> showRemoval(this::showMode)));
            case REPLACE -> {
                entries.add(new Entry("mm.menu.whitelist", this::showWhitelist));
                entries.add(new Entry("mm.menu.material", this::showMaterials));
                entries.add(new Entry("mm.menu.shape", this::showShape));
            }
            case COPY, MOVE -> {
                entries.add(new Entry("mm.menu.source", () -> {
                    command(mode == ManipulatorSettings.Mode.COPY ? MatterManipulatorBehavior.Action.COPY : MatterManipulatorBehavior.Action.CUT);
                    server(player::closeContainer);
                }, () -> !settings().paste));
                entries.add(new Entry("mm.menu.paste", () -> {
                    command(MatterManipulatorBehavior.Action.PASTE);
                    server(player::closeContainer);
                }, () -> settings().paste));
                entries.add(new Entry("mm.menu.transform", this::showTransform));
                entries.add(new Entry("mm.menu.array", () -> openPanel(array, this::showMode)));
            }
        }
        entries.add(new Entry("mm.materials", () -> openPanel(inventory, this::showMode)));
        entries.add(new Entry("mm.clear", () -> command(MatterManipulatorBehavior.Action.CLEAR)));
        show(key("mm.mode.", mode), entries, this::showModes);
    }

    private void showRemoval(Runnable back) {
        List<Entry> entries = new ArrayList<>();
        for (var value : ManipulatorSettings.Removal.values()) {
            entries.add(new Entry(key("mm.removal.", value), () -> edit(settings -> settings.removal = value), () -> settings().removal == value));
        }
        show("mm.menu.removal", entries, back);
    }

    private void showShape() {
        List<Entry> entries = new ArrayList<>();
        for (var value : ManipulatorSettings.Shape.values()) {
            entries.add(new Entry(key("mm.shape.", value), () -> edit(settings -> settings.shape = value), () -> settings().shape == value));
        }
        show("mm.menu.shape", entries, this::showMode);
    }

    private void showMaterials() {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < settings().materials.length; i++) {
            int slot = i;
            entries.add(new Entry(() -> Component.translatable("mm.menu.material." + slot).append("\n").append(settings().materials[slot].getBlock().getName()),
                    () -> setMaterial(slot, true), () -> false));
        }
        show("mm.menu.material", entries, this::showMode);
    }

    private void showWhitelist() {
        show("mm.menu.whitelist", List.of(
                action("mm.whitelist_cursor", this::whitelistCursor),
                editEntry("mm.whitelist_clear", settings -> settings.whitelist.clear())), this::showMode);
    }

    private void showTransform() {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < settings().rotation.length; i++) {
            int axis = i;
            entries.add(new Entry(() -> Component.translatable("mm.rotate." + axis).append("\n" + settings().rotation[axis] * 90 + "°"),
                    () -> edit(settings -> settings.rotation[axis] = (settings.rotation[axis] + 1) % 4), () -> settings().rotation[axis] != 0));
            entries.add(new Entry("mm.mirror." + axis, () -> edit(settings -> settings.mirror[axis] = !settings.mirror[axis]), () -> settings().mirror[axis]));
        }
        entries.add(new Entry("mm.reset", () -> command(MatterManipulatorBehavior.Action.RESET)));
        show("mm.menu.transform", entries, this::showMode);
    }

    private void showNetwork() {
        show("mm.menu.network", List.of(
                action("mm.bind", () -> {
                    tool.getOrCreateTag().putBoolean("ManipulatorBindNext", true);
                    player.closeContainer();
                }),
                action("mm.unbind", () -> ManipulatorNetwork.LINK.unlink(tool))), this::showModes);
    }

    private void showPlans() {
        List<Entry> entries = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            int plan = i;
            entries.add(action("mm.plan." + plan, () -> {
                var server = (ServerPlayer) player;
                var data = ManipulatorSavedData.get(server.server);
                var task = data.task(server, tool);
                if (plan <= 4) data.start(server, tool, plan);
                else if (task != null) {
                    if (plan == 5) task.plans.clearManual();
                    else task.cancelOrders();
                }
            }));
        }
        show("mm.menu.plans", entries, this::showModes);
    }

    private void showTask() {
        show("mm.menu.task", List.of(
                action("mm.undo", () -> {
                    var server = (ServerPlayer) player;
                    var task = ManipulatorSavedData.get(server.server).task(server, tool);
                    if (task == null) player.displayClientMessage(Component.translatable("mm.error.undo_empty"), true);
                    else task.requestUndo(server);
                }),
                action("mm.pause", () -> {
                    var server = (ServerPlayer) player;
                    var task = ManipulatorSavedData.get(server.server).task(server, tool);
                    if (task != null) {
                        task.paused = true;
                        task.status("mm.status.paused");
                        task.changed();
                    }
                }),
                action("mm.cancel", () -> {
                    var server = (ServerPlayer) player;
                    var task = ManipulatorSavedData.get(server.server).task(server, tool);
                    if (task != null) task.cancel();
                }),
                new Entry("mm.clear", () -> command(MatterManipulatorBehavior.Action.CLEAR)),
                new Entry("mm.reset", () -> command(MatterManipulatorBehavior.Action.RESET))), this::showModes);
    }

    private WidgetGroup panel(String title) {
        WidgetGroup panel = new WidgetGroup(0, PANEL_Y, WIDTH, PANEL_HEIGHT);
        panel.setBackground(GuiTextures.BACKGROUND);
        panel.addWidget(new LabelWidget(8, 9, title));
        var back = new ButtonWidget(WIDTH - 72, 4, 64, 18, new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture("mm.menu.back")), click -> radial.backFromClient());
        back.setClientSideWidget();
        panel.addWidget(back);
        panels.add(panel);
        addWidget(panel);
        return panel;
    }

    private void openPanel(WidgetGroup target, Runnable back) {
        for (WidgetGroup panel : panels) panel.setVisible(panel == target).setActive(panel == target);
        radial.setVisible(false).setActive(false);
        shortcuts.setVisible(false).setActive(false);
        radial.setBack(back);
    }

    private WidgetGroup coordinates() {
        WidgetGroup panel = panel("mm.menu.coordinates");
        for (int row = 0; row < 3; row++) {
            int point = row;
            int y = 47 + row * 58;
            panel.addWidget(new LabelWidget(8, y, "mm.point." + row));
            for (int i = 0; i < 3; i++) {
                int axis = i;
                panel.addWidget(new LabelWidget(8 + axis * 102, y + 20, axisName(axis)));
                panel.addWidget(new TextFieldWidget(21 + axis * 102, y + 15, 84, 18, () -> {
                    BlockPos pos = point(settings(), point);
                    return pos == null ? "" : Integer.toString(pos.get(Direction.Axis.values()[axis]));
                }, text -> {
                    if (text.isEmpty() || text.equals("-")) return;
                    edit(settings -> {
                        BlockPos old = point(settings, point);
                        if (old == null) old = player.blockPosition();
                        int value = Integer.parseInt(text);
                        setPoint(settings, point, new BlockPos(axis == 0 ? value : old.getX(), axis == 1 ? value : old.getY(), axis == 2 ? value : old.getZ()));
                    });
                }).setNumbersOnly(-Level.MAX_LEVEL_SIZE, Level.MAX_LEVEL_SIZE));
            }
            button(panel, 318, y + 15, 58, "mm.here", () -> edit(settings -> setPoint(settings, point, player.blockPosition())));
        }
        button(panel, 8, 239, 118, "mm.clear", () -> command(MatterManipulatorBehavior.Action.CLEAR));
        return panel;
    }

    private WidgetGroup array() {
        WidgetGroup panel = panel("mm.menu.array");
        panel.addWidget(new LabelWidget(49, 39, "mm.menu.count"));
        panel.addWidget(new LabelWidget(199, 39, "mm.menu.spacing"));
        for (int i = 0; i < settings().repeat.length; i++) {
            int axis = i;
            int y = 65 + i * 47;
            panel.addWidget(new LabelWidget(14, y + 5, axisName(axis)));
            panel.addWidget(new TextFieldWidget(49, y, 130, 18, () -> Integer.toString(settings().repeat[axis]), text -> {
                if (!text.isEmpty()) edit(settings -> settings.repeat[axis] = Integer.parseInt(text));
            }).setNumbersOnly(1, ConfigHolder.INSTANCE.manipulator.maxBlocks).setHoverTooltips("mm.repeat"));
            panel.addWidget(new TextFieldWidget(199, y, 130, 18, () -> Integer.toString(settings().spacing[axis]), text -> {
                if (!text.isEmpty() && !text.equals("-")) edit(settings -> settings.spacing[axis] = Integer.parseInt(text));
            }).setNumbersOnly(-Level.MAX_LEVEL_SIZE, Level.MAX_LEVEL_SIZE).setHoverTooltips("mm.spacing"));
        }
        button(panel, 8, 239, 118, "mm.reset", () -> command(MatterManipulatorBehavior.Action.RESET));
        return panel;
    }

    private WidgetGroup inventory() {
        WidgetGroup panel = panel("mm.materials");
        panel.addWidget(new LabelWidget(8, 27, "mm.material_help"));
        for (int i = 0; i < settings().materials.length; i++) {
            int slot = i;
            button(panel, 8 + i * 93, 43, 90, "mm.material." + i, () -> setMaterial(slot, false));
            panel.addWidget(new LabelWidget(10 + i * 93, 65, () -> settings().materials[slot].getBlock().getName().getString()));
        }
        button(panel, 8, 81, 177, "mm.whitelist_cursor", this::whitelistCursor);
        button(panel, 191, 81, 185, "mm.whitelist_clear", () -> edit(settings -> settings.whitelist.clear()));
        panel.addWidget(UITemplate.bindPlayerInventory(player.getInventory(), GuiTextures.SLOT, 103, 105, true));
        panel.addWidget(new LabelWidget(8, 190, "mm.requirements"));
        int[] materialPage = { 0 };
        button(panel, 245, 186, 62, "mm.previous", () -> materialPage[0] = Math.max(0, materialPage[0] - 1));
        button(panel, 311, 186, 62, "mm.next", () -> {
            var server = (ServerPlayer) player;
            var task = ManipulatorSavedData.get(server.server).task(server, tool);
            if (task != null && (materialPage[0] + 1) * 10 < task.remaining.size()) materialPage[0]++;
        });
        for (int i = 0; i < 10; i++) {
            int row = i;
            panel.addWidget(new LabelWidget(8 + i / 5 * 186, 210 + i % 5 * 11, () -> {
                if (!(player instanceof ServerPlayer server)) return "";
                var task = ManipulatorSavedData.get(server.server).task(server, tool);
                int index = materialPage[0] * 10 + row;
                if (task == null || index >= task.remaining.size()) return "";
                var entry = task.remaining.entrySet().stream().skip(index).findFirst().orElseThrow();
                return entry.getKey().getDisplayName().getString() + " × " + entry.getValue();
            }));
        }
        panel.addWidget(new LabelWidget(8, 267, () -> {
            var electric = GTCapabilityHelper.getElectricItem(tool);
            return electric.getCharge() + " / " + electric.getMaxCharge() + " EU / " + Component.translatable(tool.getOrCreateTag().contains("ManipulatorNetwork") ? "mm.network_linked" : "mm.network_unlinked").getString();
        }));
        return panel;
    }

    private void setMaterial(int slot, boolean useOffhand) {
        edit(settings -> {
            ItemStack carried = player.containerMenu.getCarried();
            ItemStack material = useOffhand && carried.isEmpty() ? player.getOffhandItem() : carried;
            settings.materials[slot] = material.getItem() instanceof BlockItem block ? block.getBlock().defaultBlockState() : Blocks.AIR.defaultBlockState();
        });
    }

    private void whitelistCursor() {
        edit(settings -> {
            ItemStack carried = player.containerMenu.getCarried();
            var state = carried.getItem() instanceof BlockItem block ? block.getBlock().defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (!settings.whitelist.contains(state)) settings.whitelist.add(state);
        });
    }

    private static BlockPos point(ManipulatorSettings settings, int point) {
        return point == 0 ? settings.a : point == 1 ? settings.b : settings.anchor;
    }

    private void setPoint(ManipulatorSettings settings, int point, BlockPos pos) {
        if (point == 0) settings.a = pos;
        else if (point == 1) settings.b = pos;
        else settings.anchor = pos;
        settings.dimension = player.level().dimension().location().toString();
    }

    private static String axisName(int axis) {
        return Direction.Axis.values()[axis].getName().toUpperCase(Locale.ROOT);
    }

    private static String key(String prefix, Enum<?> value) {
        return prefix + value.name().toLowerCase(Locale.ROOT);
    }

    private void button(WidgetGroup group, int x, int y, int width, String key, Runnable action) {
        navigationButton(group, x, y, width, key, () -> server(action));
    }

    private void navigationButton(WidgetGroup group, int x, int y, int width, String key, Runnable action) {
        group.addWidget(new ButtonWidget(x, y, width, 18, new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(key)), click -> action.run()));
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) return radial.backFromClient();
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
