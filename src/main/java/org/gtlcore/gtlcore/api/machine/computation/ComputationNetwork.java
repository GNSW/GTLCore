package org.gtlcore.gtlcore.api.machine.computation;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.api.recipe.RecipeEscrow;

import com.gregtechceu.gtceu.api.capability.IOpticalComputationProvider;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared routing for wired, wireless and cloud entry points. Never follows a route recursively. */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID)
public final class ComputationNetwork {

    private static final Map<MinecraftServer, Runtime> SERVERS = new IdentityHashMap<>();

    private ComputationNetwork() {}

    public static long tick(Level level) {
        return level instanceof ServerLevel serverLevel ? serverLevel.getServer().overworld().getGameTime() :
                level == null ? 0 : level.getGameTime();
    }

    public static List<IOpticalComputationProvider> link(IOpticalComputationProvider provider) {
        return provider == null ? List.of() : List.of(provider);
    }

    public static long capacity(Collection<? extends IOpticalComputationProvider> roots) {
        Runtime runtime = runtime();
        if (runtime == null) return 0;
        long result = 0;
        for (ComputationSource source : runtime.resolve(roots).sources())
            result = ComputationMath.add(result, Math.max(0, source.gtlcore$computationCapacity()));
        return result;
    }

    public static boolean canBridge(Collection<? extends IOpticalComputationProvider> roots) {
        Runtime runtime = runtime();
        if (runtime == null) return false;
        for (ComputationSource source : runtime.resolve(roots).sources())
            if (source.gtlcore$canBridgeComputation()) return true;
        return false;
    }

    public static long available(Object owner, Collection<? extends IOpticalComputationProvider> roots) {
        Runtime runtime = runtime();
        if (runtime == null) return 0;
        return runtime.scheduler.available(owner, runtime.resolve(roots).sources());
    }

    public static long offer(Object owner, Collection<? extends IOpticalComputationProvider> roots,
                             long minimum, long maximum) {
        Runtime runtime = runtime();
        if (runtime == null) return 0;
        return runtime.scheduler.offer(owner, runtime.resolve(roots).sources(), minimum, maximum);
    }

    public static ComputationScheduler.Transaction reserve(Object owner,
                                                           Collection<? extends IOpticalComputationProvider> roots,
                                                           long minimum, long maximum) {
        Runtime runtime = runtime();
        if (runtime == null) return null;
        Resolution resolution = runtime.resolve(roots);
        long granted = runtime.scheduler.offer(owner, resolution.sources(), minimum, maximum);
        if (granted < minimum || granted == 0) return null;
        var transaction = runtime.scheduler.withdraw(owner, granted);
        if (transaction != null) {
            transaction.onCommit(() -> {
                runtime.waiters.remove(owner);
                for (ComputationNode node : resolution.nodes()) node.gtlcore$computationTransferred(granted);
            });
        }
        return transaction;
    }

    /** Old capability calls share physical ledgers, but do not reserve future work for anonymous queries. */
    public static int request(IOpticalComputationProvider root, int amount, boolean simulate) {
        return ComputationMath.toInt(request(List.of(root), amount, simulate));
    }

    public static long request(Collection<? extends IOpticalComputationProvider> roots, long amount, boolean simulate) {
        if (amount <= 0) return 0;
        Object anonymous = new Object();
        long available = Math.min(amount, available(anonymous, roots));
        if (simulate || available == 0) return available;
        try (var transaction = reserve(anonymous, roots, available, available)) {
            if (transaction == null) return 0;
            transaction.commit();
            return available;
        } finally {
            forget(anonymous);
        }
    }

    public static void forget(Object owner) {
        Runtime runtime = runtime();
        if (runtime != null) {
            runtime.scheduler.forget(owner);
            runtime.waiters.remove(owner);
        }
    }

    public static void await(Object owner, Collection<? extends IOpticalComputationProvider> roots,
                             long minimum, long maximum) {
        Runtime runtime = runtime();
        if (runtime != null && owner instanceof IRecipeLogicMachine machine && minimum > 0)
            runtime.waiters.put(machine, new Waiting(List.copyOf(roots), minimum, maximum));
    }

    public static boolean isWaiting(Object owner) {
        var server = ServerLifecycleHooks.getCurrentServer();
        Runtime runtime = server == null ? null : SERVERS.get(server);
        return runtime != null && runtime.waiters.containsKey(owner);
    }

    public static void invalidate() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) return;
        Runtime runtime = SERVERS.get(server);
        if (runtime != null) {
            runtime.links.clear();
            runtime.scheduler.invalidate();
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        RecipeEscrow.retryAll();
        Runtime runtime = runtime();
        if (runtime != null && runtime.tick % 5 == 0) runtime.wakeWaiting();
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        SERVERS.remove(event.getServer());
    }

    private static Runtime runtime() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) return null;
        Runtime runtime = SERVERS.computeIfAbsent(server, ignored -> new Runtime());
        runtime.tick = server.overworld().getGameTime();
        runtime.scheduler.advance(runtime.tick);
        return runtime;
    }

    private static final class Runtime {

        private final ComputationScheduler scheduler = new ComputationScheduler();
        private final Map<ComputationNode, Links> links = new IdentityHashMap<>();
        private final Map<IRecipeLogicMachine, Waiting> waiters = new IdentityHashMap<>();
        private int waiterCursor;
        private long tick;

        private void wakeWaiting() {
            if (waiters.isEmpty()) return;
            var pending = new ArrayList<>(waiters.entrySet());
            int count = Math.min(256, pending.size());
            for (int i = 0; i < count; i++) {
                var entry = pending.get((waiterCursor + i) % pending.size());
                var owner = entry.getKey();
                var logic = owner.getRecipeLogic();
                if (!ComputationConnections.loaded(owner.self()) || !owner.isRecipeLogicAvailable() || logic.isSuspend()) {
                    scheduler.forget(owner);
                    waiters.remove(owner);
                    continue;
                }
                var waiting = entry.getValue();
                if (scheduler.offer(owner, resolve(waiting.roots()).sources(), waiting.minimum(), waiting.maximum()) >= waiting.minimum())
                    logic.updateTickSubscription();
            }
            waiterCursor = (waiterCursor + count) % pending.size();
        }

        private Resolution resolve(Collection<? extends IOpticalComputationProvider> roots) {
            var pending = new ArrayDeque<Visit>();
            for (var root : roots) if (root != null) pending.addLast(new Visit(root, false));
            Map<IOpticalComputationProvider, Integer> visited = new IdentityHashMap<>();
            Set<ComputationSource> found = Collections.newSetFromMap(new IdentityHashMap<>());
            List<ComputationSource> sources = new ArrayList<>();
            List<ComputationNode> nodes = new ArrayList<>();
            while (!pending.isEmpty()) {
                Visit visit = pending.removeFirst();
                var provider = visit.provider();
                int bit = visit.bridge() ? 2 : 1;
                int flags = visited.getOrDefault(provider, 0);
                if ((flags & bit) != 0) continue;
                visited.put(provider, flags | bit);
                if (provider instanceof ComputationSource source) {
                    if ((!visit.bridge() || source.gtlcore$canBridgeComputation()) && found.add(source)) sources.add(source);
                } else if (provider instanceof ComputationNode node && node.gtlcore$computationOnline()) {
                    if (flags == 0) nodes.add(node);
                    for (ComputationSource source : node.gtlcore$localComputationSources())
                        if ((!visit.bridge() || source.gtlcore$canBridgeComputation()) && found.add(source)) sources.add(source);
                    Links cached = links.get(node);
                    // Bound stale third-party routes even if an addon omitted an invalidation callback.
                    if (cached == null || tick < cached.tick() || tick - cached.tick() >= 20) {
                        cached = new Links(tick, List.copyOf(node.gtlcore$computationLinks()));
                        if (links.size() >= 16384) links.clear();
                        links.put(node, cached);
                    }
                    for (var next : cached.providers())
                        pending.addLast(new Visit(next, visit.bridge() || node.gtlcore$requiresComputationBridge()));
                }
            }
            return new Resolution(List.copyOf(sources), nodes);
        }
    }

    private record Visit(IOpticalComputationProvider provider, boolean bridge) {}

    private record Links(long tick, List<IOpticalComputationProvider> providers) {}

    private record Resolution(List<ComputationSource> sources, List<ComputationNode> nodes) {}

    private record Waiting(List<IOpticalComputationProvider> roots, long minimum, long maximum) {}
}
