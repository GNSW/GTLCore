package org.gtlcore.gtlcore.integration.jade.provider;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.integration.gtmt.monitor.EnergyMonitorBlock;
import org.gtlcore.gtlcore.integration.gtmt.monitor.EnergyMonitorBlockEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import com.hepdd.gtmthings.utils.TeamUtil;
import snownee.jade.api.*;
import snownee.jade.api.config.IPluginConfig;

public final class EnergyMonitorProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    private static final String TAG = "gtlcore_energy_monitor_binding";

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof EnergyMonitorBlockEntity monitor)) return;
        CompoundTag binding = new CompoundTag();
        var account = monitor.account();
        if (account != null) {
            binding.putUUID("account", account);
            binding.putString("team", Component.Serializer.toJson(TeamUtil.GetName(accessor.getLevel(), account)));
        }
        data.put(TAG, binding);
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (!(accessor.getBlockEntity() instanceof EnergyMonitorBlockEntity)) return;
        if (accessor.getServerData().contains(TAG)) {
            CompoundTag binding = accessor.getServerData().getCompound(TAG);
            if (binding.hasUUID("account")) {
                Component team = Component.Serializer.fromJson(binding.getString("team"));
                tooltip.add(Component.translatable("monitor.gtlcore.bound_team", team == null ? Component.literal("?") : team));
                tooltip.add(Component.translatable("monitor.gtlcore.bound_uuid", binding.getUUID("account").toString()));
            } else tooltip.add(Component.translatable("monitor.gtlcore.unbound"));
        }
        var facing = accessor.getBlockState().getValue(EnergyMonitorBlock.FACING);
        tooltip.add(Component.translatable("monitor.gtlcore.facing", Component.translatable("monitor.gtlcore.direction." + facing.getName())));
    }

    @Override
    public ResourceLocation getUid() {
        return GTLCore.id("energy_monitor_binding");
    }
}
