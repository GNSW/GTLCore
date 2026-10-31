package org.gtlcore.gtlcore.client.socketpreview;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.api.machine.multiblock.GTLPartAbility;

import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Helpers for recognising GregTech part items.
 *
 * <p>
 * Rendering uses {@link #partMachine(ItemStack)} only: every non-controller
 * {@link MetaMachineItem} is considered. A single-block machine never matches a multiblock
 * pattern cell, while custom addon parts (including parts that are not registered to a
 * {@link PartAbility}) still work.
 *
 * <p>
 * The tooltip uses the stricter {@link #isRegisteredPart(ItemStack)} check. Abilities are
 * collected reflectively so parts added by GTCEu, GTLCore and other addons are covered without
 * hard-coding every ability constant.
 */
public final class PartItems {

    private static volatile Set<PartAbility> abilities;

    private PartItems() {}

    /**
     * @return the machine item when the stack is a non-controller GregTech machine item, otherwise
     *         {@code null}.
     */
    public static MetaMachineItem partMachine(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Item item = stack.getItem();
        if (!(item instanceof MetaMachineItem machineItem)) {
            return null;
        }
        MachineDefinition definition;
        try {
            definition = machineItem.getDefinition();
        } catch (Throwable ignored) {
            return null;
        }
        if (definition == null || definition instanceof MultiblockMachineDefinition) {
            return null;
        }
        return machineItem;
    }

    public static Block blockOf(MetaMachineItem item) {
        return ((BlockItem) item).getBlock();
    }

    /**
     * True for a non-controller machine item whose block is registered to at least one
     * {@link PartAbility}. This keeps the placement tooltip off ordinary single-block machines.
     */
    public static boolean isRegisteredPart(ItemStack stack) {
        MetaMachineItem machineItem = partMachine(stack);
        if (machineItem == null) {
            return false;
        }
        Block block = blockOf(machineItem);
        for (PartAbility ability : abilities()) {
            try {
                if (ability.isApplicable(block)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // A broken addon ability must not break the tooltip.
            }
        }
        return false;
    }

    private static Set<PartAbility> abilities() {
        Set<PartAbility> result = abilities;
        if (result != null) {
            return result;
        }
        synchronized (PartItems.class) {
            result = abilities;
            if (result != null) {
                return result;
            }
            Set<PartAbility> loaded = new LinkedHashSet<>();
            collect(PartAbility.class, loaded);
            collect(GTLPartAbility.class, loaded);
            abilities = result = Set.copyOf(loaded);
            return result;
        }
    }

    private static void collect(Class<?> holder, Set<PartAbility> result) {
        try {
            for (Field field : holder.getFields()) {
                if (!Modifier.isStatic(field.getModifiers()) ||
                        !PartAbility.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    Object value = field.get(null);
                    if (value instanceof PartAbility ability) {
                        result.add(ability);
                    }
                } catch (Throwable ignored) {
                    // Skip inaccessible or not-yet-initialised fields.
                }
            }
        } catch (Throwable failure) {
            GTLCore.LOGGER.debug("Could not enumerate part abilities on {}", holder.getName(), failure);
        }
    }
}
