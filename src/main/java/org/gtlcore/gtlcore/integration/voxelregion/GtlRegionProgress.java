package org.gtlcore.gtlcore.integration.voxelregion;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;

import org.voxelregion.forge.ForgeRegionEngine;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Sphere;
import org.voxelregion.spatial.ChunkKey;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Command-only observation; it never loads chunks or changes an operation's scheduling. */
final class GtlRegionProgress {

    private static final long RATE_WINDOW_NANOS = 5_000_000_000L;
    private static final Map<MinecraftServer, Watches> SERVERS = new IdentityHashMap<>();

    static void install() {
        MinecraftForge.EVENT_BUS.addListener(GtlRegionProgress::tick);
        MinecraftForge.EVENT_BUS.addListener(GtlRegionProgress::loggedOut);
        MinecraftForge.EVENT_BUS.addListener(GtlRegionProgress::stopped);
    }

    static void watch(CommandSourceStack source, ForgeRegionEngine.Handle handle, BlockPos center, int radius, long started) {
        MinecraftServer server = source.getServer();
        Watches watches = SERVERS.computeIfAbsent(server, ignored -> new Watches());
        UUID player = source.getEntity() instanceof ServerPlayer viewer ? viewer.getUUID() : null;
        // A second command replaces only this player's displayed bar; previous work continues.
        if (player != null) {
            for (Watch existing : watches.operations.values()) {
                if (player.equals(existing.player)) existing.hideBar();
            }
        }
        Sphere sphere = Sphere.of(center.getX() + .5, center.getY() + .5, center.getZ() + .5, radius);
        int minY = source.getLevel().getMinBuildHeight(), maxY = source.getLevel().getMaxBuildHeight();
        Future<Long> count = watches.counter.submit(() -> countChunks(sphere, minY, maxY));
        Watch watch = new Watch(handle.id(), source.getLevel().dimension(), player, count, started, server.getTickCount());
        watches.operations.put(handle.id(), watch);
        if (player != null) {
            watch.bar = new ServerBossEvent(Component.literal("区域清理 · 统计范围…"), BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.PROGRESS);
            watch.bar.addPlayer((ServerPlayer) source.getEntity());
        }
    }

    private static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 10 != 0) return;
        MinecraftServer server = event.getServer();
        Watches watches = SERVERS.get(server);
        if (watches == null) return;
        long now = System.nanoTime();
        var iterator = watches.operations.values().iterator();
        while (iterator.hasNext()) {
            Watch watch = iterator.next();
            ServerPlayer player = watch.player == null ? null : server.getPlayerList().getPlayer(watch.player);
            if (watch.player != null && player == null) {
                watch.close();
                iterator.remove();
                continue;
            }
            var level = server.getLevel(watch.dimension);
            var status = level == null ? null : GtlRegionExplosions.status(level, watch.id).orElse(null);
            if (status == null) {
                report(server, player, "区域清理 " + shortId(watch.id) + " 状态不可用，已停止进度跟踪。");
                watch.close();
                iterator.remove();
                continue;
            }
            watch.readTotal();
            watch.samples.addLast(new Sample(now, server.getTickCount(), status.completedChunkPhases()));
            while (watch.samples.size() > 2) {
                Sample first = watch.samples.removeFirst();
                if (now - watch.samples.getFirst().nanos() < RATE_WINDOW_NANOS) {
                    watch.samples.addFirst(first);
                    break;
                }
            }
            Sample first = watch.samples.getFirst();
            long completed = status.completedChunkPhases(), skipped = status.deniedChunks() + status.unloadedChunks();
            long recent = Math.max(0, completed - first.completed());
            double perTick = recent / (double) Math.max(1, server.getTickCount() - first.tick());
            double perSecond = recent * 1_000_000_000.0 / Math.max(1L, now - first.nanos());
            String elapsed = duration(now - watch.started);
            boolean settled = settled(status.progress());
            if (status.terminal() && settled) {
                double seconds = Math.max(1L, now - watch.started) / 1_000_000_000.0;
                report(server, player, String.format(Locale.ROOT,
                        "区域清理 %s %s：实际完成 %d%s 区块，跳过 %d，用时 %s，平均 %.2f 区块/t、%.1f 区块/s。%s",
                        shortId(watch.id), stateName(status.state()), completed, watch.total >= 0 ? "/" + watch.total : "",
                        skipped, elapsed, completed / (double) Math.max(1, server.getTickCount() - watch.startedTick), completed / seconds,
                        skipped > 0 ? "跳过范围未清理。" : ""));
                watch.close();
                iterator.remove();
                continue;
            }
            if (watch.bar == null || player == null) continue;
            if (!watch.bar.getPlayers().contains(player)) {
                watch.bar.removeAllPlayers();
                watch.bar.addPlayer(player);
            }
            double processed = watch.total > 0 ? Math.min(1.0, (completed + skipped) / (double) watch.total) : 0;
            // The final fraction is not acknowledged until all light/save work has settled.
            watch.bar.setProgress((float) Math.min(.999, processed));
            watch.bar.setColor(status.state().name().startsWith("PAUSED") ? BossEvent.BossBarColor.YELLOW : BossEvent.BossBarColor.BLUE);
            String amount = watch.total >= 0 ? completed + "/" + watch.total : completed + (watch.total == -1 ? "/统计范围…" : "/总量未知");
            String percent = watch.total > 0 ? String.format(Locale.ROOT, " %.1f%%", Math.min(99.9, processed * 100)) : "";
            String skippedText = skipped > 0 ? " +跳过" + skipped : "";
            String state = status.terminal() ? stateName(status.state()) + "收尾" : stateName(status.state());
            watch.bar.setName(Component.literal(String.format(Locale.ROOT, "%s %s%s%s · %s · 近5秒 %.2f区块/t · %.1f区块/s",
                    state, amount, skippedText, percent, elapsed, perTick, perSecond)));
        }
    }

    private static boolean settled(ForgeRegionEngine.Progress progress) {
        return progress.writingChunks() == 0 && progress.loadingChunks() == 0 && progress.pendingLighting() == 0 && progress.pendingSaves() == 0 && progress.pendingPackets() == 0 && progress.retainedChunks() == 0;
    }

    private static String stateName(ForgeRegionEngine.State state) {
        return switch (state) {
            case FUSE -> "等待";
            case RUNNING -> "清理";
            case SETTLING -> "收尾";
            case RECOVERING -> "恢复中";
            case PAUSED, PAUSED_UNLOADED, PAUSED_PROTECTION, PAUSED_RECOVERY -> "已暂停";
            case FAILED -> "失败";
            case CANCELLED -> "已取消";
            case WRITTEN -> "已完成";
        };
    }

    private static String duration(long nanos) {
        long seconds = Math.max(0, nanos / 1_000_000_000L);
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) :
                String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static void report(MinecraftServer server, ServerPlayer player, String text) {
        Component message = Component.literal(text);
        if (player != null) player.sendSystemMessage(message);
        else server.sendSystemMessage(message);
    }

    private static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Watches watches = SERVERS.get(player.getServer());
        if (watches == null) return;
        watches.operations.values().removeIf(watch -> {
            if (!player.getUUID().equals(watch.player)) return false;
            watch.close();
            return true;
        });
    }

    private static void stopped(ServerStoppedEvent event) {
        Watches watches = SERVERS.remove(event.getServer());
        if (watches == null) return;
        watches.operations.values().forEach(Watch::close);
        watches.operations.clear();
        watches.counter.shutdownNow();
    }

    /** Exact sphere/chunk selection, O(rows * log(columns)); runs on immutable geometry only. */
    static long countChunks(Sphere sphere, int minY, int maxY) {
        IntBox bounds = sphere.bounds().intersection(new IntBox(IntBox.WORLD_MIN, minY, IntBox.WORLD_MIN,
                IntBox.WORLD_MAX, maxY, IntBox.WORLD_MAX));
        if (bounds.isEmpty()) return 0;
        int minX = Math.floorDiv(bounds.minX(), 16), maxX = Math.floorDiv(bounds.maxX() - 1, 16);
        int minZ = Math.floorDiv(bounds.minZ(), 16), maxZ = Math.floorDiv(bounds.maxZ() - 1, 16);
        int pivot = Math.max(minX, Math.min(maxX, (int) Math.floorDiv(sphere.center().x2(), 32)));
        long total = 0;
        for (int z = minZ; z <= maxZ; z++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (!selected(sphere, pivot, z, bounds)) continue;
            int low = minX, high = pivot;
            while (low < high) {
                int middle = low + (high - low) / 2;
                if (selected(sphere, middle, z, bounds)) high = middle;
                else low = middle + 1;
            }
            int first = low;
            low = pivot;
            high = maxX;
            while (low < high) {
                int middle = low + (high - low + 1) / 2;
                if (selected(sphere, middle, z, bounds)) low = middle;
                else high = middle - 1;
            }
            total += low - first + 1L;
        }
        return total;
    }

    private static boolean selected(Sphere sphere, int x, int z, IntBox bounds) {
        return sphere.classify(new ChunkKey(x, z).bounds(bounds.minY(), bounds.maxY())) != Coverage.OUTSIDE;
    }

    private static final class Watches {

        final Map<UUID, Watch> operations = new HashMap<>();
        final ExecutorService counter = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "gtl-region-progress");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static final class Watch {

        final UUID id;
        final ResourceKey<Level> dimension;
        final UUID player;
        final Future<Long> totalFuture;
        final long started;
        final int startedTick;
        final ArrayDeque<Sample> samples = new ArrayDeque<>();
        long total = -1;
        ServerBossEvent bar;

        Watch(UUID id, ResourceKey<Level> dimension, UUID player, Future<Long> totalFuture, long started, int startedTick) {
            this.id = id;
            this.dimension = dimension;
            this.player = player;
            this.totalFuture = totalFuture;
            this.started = started;
            this.startedTick = startedTick;
            samples.addLast(new Sample(started, startedTick, 0));
        }

        void readTotal() {
            if (total != -1 || !totalFuture.isDone()) return;
            try {
                total = totalFuture.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                total = -2;
            } catch (ExecutionException | CancellationException failure) {
                total = -2;
            }
        }

        void hideBar() {
            if (bar != null) bar.removeAllPlayers();
            bar = null;
        }

        void close() {
            hideBar();
            totalFuture.cancel(true);
        }
    }

    private record Sample(long nanos, int tick, long completed) {}

    private GtlRegionProgress() {}
}
