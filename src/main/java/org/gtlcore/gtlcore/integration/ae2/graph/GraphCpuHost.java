package org.gtlcore.gtlcore.integration.ae2.graph;

import net.minecraft.world.level.Level;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.inv.ListCraftingInventory;

public interface GraphCpuHost {

    ICraftingCPU cpu();

    IGrid grid();

    Level level();

    IActionSource source();

    boolean active();

    long dispatchCapacity();

    ListCraftingInventory orphanInventory();

    void dirty();

    void changed(AEKey key);

    void output(GenericStack stack);

    default void requesting(AEKey key, boolean requested) {}
}
