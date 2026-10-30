package org.gtlcore.gtlcore.common.item.spray;

import org.gtlcore.gtlcore.common.item.InfiniteSprayCanBehavior;
import org.gtlcore.gtlcore.common.item.manipulator.ManipulatorRadialMenuWidget;
import org.gtlcore.gtlcore.common.item.manipulator.ManipulatorRadialMenuWidget.Entry;

import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class InfiniteSprayCanUI extends WidgetGroup {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 219;

    private final ItemStack tool;
    private final Player player;
    private final ManipulatorRadialMenuWidget radial;

    private InfiniteSprayCanUI(ItemStack tool, Player player) {
        super(0, 0, WIDTH, HEIGHT);
        this.tool = tool;
        this.player = player;
        radial = new ManipulatorRadialMenuWidget(tool);
        radial.setSelfPosition(0, 0);
        addWidget(radial);
        showColors();
    }

    public static ModularUI create(HeldItemUIFactory.HeldItemHolder holder, Player player) {
        return new ModularUI(new InfiniteSprayCanUI(holder.getHeld(), player), holder, player);
    }

    private void showColors() {
        List<Entry> entries = new ArrayList<>();
        for (int mode = 0; mode < InfiniteSprayCanBehavior.MODE_COUNT; mode++) {
            int selectedMode = mode;
            entries.add(new Entry(() -> Component.translatable("item.gtlcore.infinite_spray_can.mode." + selectedMode),
                    () -> select(selectedMode),
                    () -> InfiniteSprayCanBehavior.getMode(tool) == selectedMode));
        }
        radial.showMenu("item.gtlcore.infinite_spray_can.menu", entries, this::close);
    }

    private void select(int mode) {
        if (player.level().isClientSide) return;
        InfiniteSprayCanBehavior.setMode(tool, mode);
        player.getInventory().setChanged();
        close();
    }

    private void close() {
        if (player instanceof ServerPlayer serverPlayer) serverPlayer.closeContainer();
    }
}
