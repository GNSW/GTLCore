package com.wcwen.gtmbsockets.client;

import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Small helpers for recognising GregTech part items.
 *
 * <p>Rendering intentionally uses {@link #partMachine(ItemStack)} only: it accepts every
 * non-controller {@link MetaMachineItem}.  A single-block machine never matches a multiblock
 * pattern cell, so it produces no outlines, while custom addon parts (including parts that are not
 * registered to any {@link PartAbility}) still work.
 *
 * <p>Tooltips use the stricter {@link #isRegisteredPart(ItemStack)} check.  Part abilities are
 * collected reflectively so GTCEu, GTLCore and other addons that put their abilities in public
 * static fields are covered without a compile-time dependency on those addons.
 */
public final class PartItems {

    private static final Logger LOG = LoggerFactory.getLogger("gtmbsockets");

    private static volatile Set<PartAbility> abilities;

    private PartItems() {
    }

    /**
     * @return the machine item when the stack is a non-controller GregTech machine item, otherwise
     *         {@code null}.
     */
    public static MetaMachineItem partMachine(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return null;
        }
        Item item = stack.m_41720_();
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
        return ((BlockItem) item).m_40614_();
    }

    /**
     * True for a non-controller machine item whose block is registered to at least one
     * {@link PartAbility}.  This keeps the placement tooltip off ordinary single-block machines.
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
            // GTLCore keeps its four custom abilities in its own holder class.
            try {
                collect(Class.forName("org.gtlcore.gtlcore.api.machine.multiblock.GTLPartAbility"), loaded);
            } catch (Throwable ignored) {
                // GTLCore is optional.
            }
            abilities = result = Set.copyOf(loaded);
            return result;
        }
    }

    private static void collect(Class<?> holder, Set<PartAbility> result) {
        try {
            for (Field field : holder.getFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        || !PartAbility.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    Object value = field.get(null);
                    if (value instanceof PartAbility ability) {
                        result.add(ability);
                    }
                } catch (Throwable ignored) {
                    // Skip inaccessible/uninitialised fields.
                }
            }
        } catch (Throwable t) {
            LOG.debug("[gtmbsockets] could not enumerate part abilities on {}", holder.getName(), t);
        }
    }
}
