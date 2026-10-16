package org.gtlcore.gtlcore.integration.ae2;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public record CircuitTooltipComponent(List<ItemStack> stacks, boolean more) implements TooltipComponent {}
