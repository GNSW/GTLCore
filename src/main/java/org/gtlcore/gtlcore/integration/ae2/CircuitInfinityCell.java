/*
 * Derived from More AE2 Additions (mae2a) - com.easterfg.mae2a.common.items.InfiniteDyesCellItem.
 * Copyright (C) EasterFG and contributors.
 * Licensed under the GNU General Public License v3.0 (full text: META-INF/licenses/LICENSE-GPL-3.0.txt).
 * Modified by the GTLCore contributors.
 */
package org.gtlcore.gtlcore.integration.ae2;

import com.gregtechceu.gtceu.client.util.TooltipHelper;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;

import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import appeng.api.config.FuzzyMode;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.hooks.AEToolItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

public class CircuitInfinityCell extends Item implements ICellWorkbenchItem, AEToolItem {

    public CircuitInfinityCell() {
        super(new Properties().stacksTo(1).fireResistant());
    }

    @Override
    public boolean isEditable(ItemStack is) {
        return false;
    }

    @Override
    public FuzzyMode getFuzzyMode(final ItemStack is) {
        final String fz = is.getOrCreateTag().getString("FuzzyMode");
        if (fz.isEmpty()) {
            return FuzzyMode.IGNORE_ALL;
        }
        try {
            return FuzzyMode.valueOf(fz);
        } catch (final Throwable t) {
            return FuzzyMode.IGNORE_ALL;
        }
    }

    @Override
    public void setFuzzyMode(final ItemStack is, final FuzzyMode fzMode) {
        is.getOrCreateTag().putString("FuzzyMode", fzMode.name());
    }

    @Override
    public IUpgradeInventory getUpgrades(ItemStack is) {
        return UpgradeInventories.forItem(is, 2);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level world, List<Component> tooltip, TooltipFlag context) {
        tooltip.add(Component.translatable("item.gtlcore.circuit_infinity_cell.tooltip")
                .withStyle(style -> style.withColor(TooltipHelper.RAINBOW.getCurrent())));
    }

    @Override
    public Optional<TooltipComponent> getTooltipImage(ItemStack stack) {
        return Optional.of(TOOLTIP_IMAGE);
    }

    private static final int PREVIEW_COUNT = 6;

    private static final int TOTAL_COUNT = 33;

    private static final List<ItemStack> CIRCUIT_STACKS = buildCircuitStacks();

    private static final CircuitTooltipComponent TOOLTIP_IMAGE = new CircuitTooltipComponent(CIRCUIT_STACKS, PREVIEW_COUNT < TOTAL_COUNT);

    private static List<ItemStack> buildCircuitStacks() {
        var stacks = new ArrayList<ItemStack>(PREVIEW_COUNT);
        for (int i = 0; i < PREVIEW_COUNT; i++) {
            stacks.add(IntCircuitBehaviour.stack(i));
        }
        return List.copyOf(stacks);
    }
}
