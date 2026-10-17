package org.gtlcore.gtlcore.integration.ae2.crafting;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.core.AEConfig;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Resolve only requested keys, on the server, without an all-inventory permission scan. */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID)
public final class InventorySimulationAccess {

    private static final ArrayBlockingQueue<Query> PENDING = new ArrayBlockingQueue<>(1024);
    private final MinecraftServer server;
    private final MEStorage storage;
    private final IActionSource source;
    private final ManualCraftingInventoryLock.AvailabilityContext context;
    private final boolean simulation;
    // Owned by one calculation worker, never by the server query callback.
    private final Object2LongOpenHashMap<AEKey> checked;

    public InventorySimulationAccess(MEStorage storage, IActionSource source) {
        this.storage = storage;
        this.source = source;
        this.simulation = AEConfig.instance().isCraftingSimulatedExtraction() ||
                ManualCraftingInventoryLock.hasReservations(storage);
        this.server = simulation ? ServerLifecycleHooks.getCurrentServer() : null;
        this.context = simulation ? ManualCraftingInventoryLock.captureAvailabilityContext() : null;
        this.checked = simulation ? new Object2LongOpenHashMap<>() : null;
    }

    public boolean requiresSimulation() {
        return simulation;
    }

    public long available(AEKey key, long capturedAmount) {
        if (capturedAmount <= 0) return 0;
        if (!simulation) return capturedAmount;
        if (checked.containsKey(key)) return checked.getLong(key);
        if (server == null) throw new IllegalStateException("Crafting extraction requires a running server");
        long amount;
        if (server.isSameThread()) {
            amount = simulate(key, capturedAmount);
        } else {
            var future = new CompletableFuture<Long>();
            var query = new Query(server, () -> simulate(key, capturedAmount), future);
            try {
                // Backpressure is paid by the worker, never by the server.
                ensureRunning();
                while (!PENDING.offer(query, 1, TimeUnit.SECONDS)) ensureRunning();
                while (true) {
                    try {
                        amount = future.get(1, TimeUnit.SECONDS);
                        break;
                    } catch (TimeoutException ignored) {
                        ensureRunning();
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Crafting inventory query interrupted");
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                if (failure.getCause() instanceof Error error) throw error;
                throw new IllegalStateException("Crafting inventory query failed", failure.getCause());
            } finally {
                if (!future.isDone()) future.cancel(false);
                PENDING.remove(query);
            }
        }
        checked.put(key, amount);
        return amount;
    }

    private void ensureRunning() {
        if (!server.isRunning()) throw new CancellationException("Crafting server stopped");
    }

    private long simulate(AEKey key, long amount) {
        return context.call(() -> Math.max(0, Math.min(amount,
                storage.extract(key, amount, Actionable.SIMULATE, source))));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        // Bound total additional server work, independent of inventory size and
        // solver budgets. A single storage callback cannot be preempted.
        long deadline = System.nanoTime() + 1_000_000L;
        for (int count = 0; count < 64 && System.nanoTime() < deadline; count++) {
            Query query = PENDING.poll();
            if (query == null) break;
            if (query.future.isDone()) continue;
            if (query.server != event.getServer()) {
                query.future.cancel(false);
                continue;
            }
            try {
                query.future.complete(query.action.getAsLong());
            } catch (Throwable failure) {
                query.future.completeExceptionally(failure);
            }
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        PENDING.removeIf(query -> {
            if (query.server != event.getServer()) return false;
            query.future.cancel(false);
            return true;
        });
    }

    private record Query(MinecraftServer server, java.util.function.LongSupplier action,
                         CompletableFuture<Long> future) {}
}
