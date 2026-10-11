package org.gtlcore.gtlcore.integration.gtmt.monitor;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import com.hepdd.gtmthings.api.misc.GlobalVariableStorage;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID)
public final class WirelessEnergyHistoryData extends SavedData {

    private final Map<UUID, EnergyHistory> histories = new HashMap<>();

    public static WirelessEnergyHistoryData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                WirelessEnergyHistoryData::load, WirelessEnergyHistoryData::new, "gtlcore_wireless_energy_history");
    }

    public EnergyHistory track(UUID account) {
        if (!histories.containsKey(account)) {
            histories.put(account, new EnergyHistory());
            setDirty();
        }
        return histories.get(account);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || event.phase != TickEvent.Phase.END) return;
        long tick = server.overworld().getGameTime();
        if (tick % 20 != 0) return;
        WirelessEnergyHistoryData data = get(server);
        data.histories.forEach((account, history) -> history.sample(tick,
                GlobalVariableStorage.GlobalEnergy.getOrDefault(account, BigInteger.ZERO).max(BigInteger.ZERO)));
        if (!data.histories.isEmpty()) data.setDirty();
        WirelessEnergyMachineStats.publish(server, tick);
    }

    private static WirelessEnergyHistoryData load(CompoundTag tag) {
        WirelessEnergyHistoryData data = new WirelessEnergyHistoryData();
        for (Tag element : tag.getList("accounts", Tag.TAG_COMPOUND)) {
            CompoundTag account = (CompoundTag) element;
            if (!account.hasUUID("id")) continue;
            ArrayList<EnergyHistory.Point> points = new ArrayList<>();
            for (Tag sample : account.getList("samples", Tag.TAG_COMPOUND)) {
                CompoundTag point = (CompoundTag) sample;
                byte[] bytes = point.getByteArray("energy");
                if (bytes.length > 0) points.add(new EnergyHistory.Point(point.getLong("tick"), new BigInteger(bytes)));
            }
            data.track(account.getUUID("id")).restore(points);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag accounts = new ListTag();
        histories.forEach((id, history) -> {
            CompoundTag account = new CompoundTag();
            account.putUUID("id", id);
            ListTag samples = new ListTag();
            for (EnergyHistory.Point point : history.savedPoints()) {
                CompoundTag sample = new CompoundTag();
                sample.putLong("tick", point.tick());
                sample.putByteArray("energy", point.energy().toByteArray());
                samples.add(sample);
            }
            account.put("samples", samples);
            accounts.add(account);
        });
        tag.put("accounts", accounts);
        return tag;
    }
}
