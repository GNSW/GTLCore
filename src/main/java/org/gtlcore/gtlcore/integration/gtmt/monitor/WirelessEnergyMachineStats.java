package org.gtlcore.gtlcore.integration.gtmt.monitor;

import org.gtlcore.gtlcore.GTLCore;

import com.gregtechceu.gtceu.api.machine.MetaMachine;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.hepdd.gtmthings.utils.TeamUtil;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Successful transactions aggregated by dimension and position, never by narrowed long amounts. */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID)
public final class WirelessEnergyMachineStats {

    private static final Map<MinecraftServer, Runtime> SERVERS = new WeakHashMap<>();

    private WirelessEnergyMachineStats() {}

    public static void record(UUID user, BigInteger energy, MetaMachine machine) {
        if (user == null || machine == null || energy.signum() == 0 ||
                !(machine.getLevel() instanceof ServerLevel level))
            return;
        MinecraftServer server = level.getServer();
        if (!server.isSameThread()) return;
        Runtime runtime = SERVERS.computeIfAbsent(server, ignored -> new Runtime());
        UUID account = TeamUtil.getTeamUUID(user);
        Key key = new Key(account, level.dimension().location().toString(), machine.getPos().immutable());
        Row old = runtime.pending.get(key);
        BigInteger sum = energy.add(old == null ? BigInteger.ZERO : old.energy());
        runtime.pending.put(key, new Row(Component.translatable(machine.getBlockState().getBlock().getDescriptionId()),
                key.dimension(), key.pos(), sum, 20));
    }

    public static void publish(MinecraftServer server, long tick) {
        Runtime runtime = SERVERS.computeIfAbsent(server, ignored -> new Runtime());
        long elapsed = runtime.lastTick < 0 ? 20 : Math.max(1, tick - runtime.lastTick);
        runtime.published = new HashMap<>();
        runtime.pending.forEach((key, row) -> runtime.published.put(key,
                new Row(row.name(), row.dimension(), row.pos(), row.energy(), elapsed)));
        runtime.pending.clear();
        runtime.lastTick = tick;
    }

    public static List<Row> rows(MinecraftServer server, UUID account, int filter) {
        Runtime runtime = SERVERS.get(server);
        if (runtime == null) return List.of();
        return runtime.published.entrySet().stream()
                .filter(e -> e.getKey().account().equals(account))
                .map(Map.Entry::getValue)
                .filter(r -> filter == 0 || (filter == 1 ? r.energy().signum() > 0 : r.energy().signum() < 0))
                .sorted(Comparator.comparing((Row r) -> r.energy().abs()).reversed()
                        .thenComparing(Row::dimension).thenComparingLong(r -> r.pos().asLong()))
                .toList();
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        SERVERS.remove(event.getServer());
    }

    private record Key(UUID account, String dimension, BlockPos pos) {}

    public record Row(Component name, String dimension, BlockPos pos, BigInteger energy, long ticks) {

        public BigDecimal power() {
            return new BigDecimal(energy).divide(BigDecimal.valueOf(Math.max(1, ticks)), 2, RoundingMode.HALF_UP);
        }
    }

    private static final class Runtime {

        private final Map<Key, Row> pending = new HashMap<>();
        private Map<Key, Row> published = Map.of();
        private long lastTick = -1;
    }
}
