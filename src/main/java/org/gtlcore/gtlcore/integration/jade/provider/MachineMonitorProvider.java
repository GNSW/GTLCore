package org.gtlcore.gtlcore.integration.jade.provider;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.integration.monitor.MachineMonitorBlock;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

public final class MachineMonitorProvider implements IBlockComponentProvider {

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        var facing = accessor.getBlockState().getValue(MachineMonitorBlock.FACING);
        tooltip.add(Component.translatable("monitor.gtlcore.facing", Component.translatable("monitor.gtlcore.direction." + facing.getName())));
    }

    @Override
    public ResourceLocation getUid() {
        return GTLCore.id("machine_monitor_facing");
    }
}
