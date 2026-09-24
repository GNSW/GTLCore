package org.gtlcore.gtlcore.integration.ae2.graph;

import net.minecraft.world.level.Level;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.cluster.implementations.CraftingCPUCluster;

public record NativeGraphCpuHost(CraftingCPUCluster cluster, ListCraftingInventory orphanInventory,
                                 GraphCpuAccess access)
        implements GraphCpuHost {

    @Override
    public ICraftingCPU cpu() {
        return cluster;
    }

    @Override
    public IGrid grid() {
        return cluster.getGrid();
    }

    @Override
    public Level level() {
        return cluster.getLevel();
    }

    @Override
    public IActionSource source() {
        return cluster.getSrc();
    }

    @Override
    public boolean active() {
        return cluster.isActive();
    }

    @Override
    public long dispatchCapacity() {
        return (long) cluster.getCoProcessors() + 1;
    }

    @Override
    public void dirty() {
        cluster.markDirty();
    }

    @Override
    public void changed(AEKey key) {
        access.gtlcore$postGraphChange(key);
    }

    @Override
    public void output(GenericStack stack) {
        cluster.updateOutput(stack);
    }
}
