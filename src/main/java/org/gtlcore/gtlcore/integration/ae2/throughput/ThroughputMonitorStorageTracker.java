package org.gtlcore.gtlcore.integration.ae2.throughput;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.utils.NumberUtils;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.MachineTrait;
import com.gregtechceu.gtceu.integration.ae2.machine.feature.IGridConnectedMachine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.hooks.ticking.TickHandler;
import appeng.me.storage.NetworkStorage;
import appeng.parts.AEBasePart;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ThroughputMonitorStorageTracker {

    private static final Object LOCK = new Object();
    private static final Map<MEStorage, List<WeakReference<Listener>>> LISTENERS = new WeakHashMap<>();
    private static final Map<MEStorage, List<WeakReference<AllListener>>> ALL_LISTENERS = new WeakHashMap<>();
    private static final Map<MEStorage, List<WeakReference<MEStorage>>> VISIBLE_PARENTS = new WeakHashMap<>();
    private static final Map<MEStorage, List<WeakReference<MEStorage>>> VISIBLE_CHILDREN = new WeakHashMap<>();
    private static final ThreadLocal<Deque<StorageOperation>> OPERATIONS = new ThreadLocal<>();

    // Values must not retain the weak storage keys or listeners.
    private static final Map<MEStorage, Dispatch> DISPATCHES = new WeakHashMap<>();
    private static volatile boolean trackingActive;
    private static int maintenanceTicks;
    private static final EventType DIAGNOSTIC_TYPE = EventType.getEventType(ThroughputTickEvent.class);
    private static ThroughputTickEvent diagnostic;

    @Name("gtlcore.ThroughputTick")
    @Label("Throughput tracking per tick")
    @Category("GTLCore")
    @StackTrace(false)
    public static final class ThroughputTickEvent extends Event {

        public long operations;
        public long changes;
        public long dispatchBuilds;
        public long callbacks;
        public long abortedOperations;
        public long droppedKeys;
        public long droppedSources;
        public int monitoredStorages;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            if (diagnostic != null) {
                diagnostic.monitoredStorages = LISTENERS.size() + ALL_LISTENERS.size();
                diagnostic.commit();
                diagnostic = null;
            }
            return;
        }
        diagnostic = DIAGNOSTIC_TYPE.isEnabled() ? new ThroughputTickEvent() : null;
        synchronized (LOCK) {
            DISPATCHES.clear();
            if (++maintenanceTicks % 20 != 0) return;
            LISTENERS.values().forEach(list -> list.removeIf(reference -> reference.get() == null));
            ALL_LISTENERS.values().forEach(list -> list.removeIf(reference -> reference.get() == null));
            LISTENERS.values().removeIf(List::isEmpty);
            ALL_LISTENERS.values().removeIf(List::isEmpty);
            refreshTrackingState();
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        synchronized (LOCK) {
            LISTENERS.clear();
            ALL_LISTENERS.clear();
            VISIBLE_PARENTS.clear();
            VISIBLE_CHILDREN.clear();
            DISPATCHES.clear();
            trackingActive = false;
            maintenanceTicks = 0;
            diagnostic = null;
        }
        OPERATIONS.remove();
    }

    public static void onTopologyChanged(MEStorage storage) {
        if (!trackingActive) return;
        synchronized (LOCK) {
            if (LISTENERS.containsKey(storage) || ALL_LISTENERS.containsKey(storage) || VISIBLE_PARENTS.containsKey(storage)) {
                refreshVisibleStorageLinks(storage);
            }
        }
    }

    public static void abortOperation() {
        if (diagnostic != null) diagnostic.abortedOperations++;
        // An exceptional return has no trustworthy amount; discard the entire nested observation.
        Deque<StorageOperation> stack = OPERATIONS.get();
        if (stack != null) stack.clear();
        OPERATIONS.remove();
    }

    static void recordDroppedKey() {
        if (diagnostic != null) diagnostic.droppedKeys++;
    }

    static void recordDroppedSource() {
        if (diagnostic != null) diagnostic.droppedSources++;
    }

    private ThroughputMonitorStorageTracker() {}

    public static void register(MEStorage storage, Listener listener) {
        synchronized (LOCK) {
            var listeners = LISTENERS.computeIfAbsent(storage, ignored -> new ArrayList<>());
            boolean found = false;
            for (Iterator<WeakReference<Listener>> iterator = listeners.iterator(); iterator.hasNext();) {
                Listener registered = iterator.next().get();
                if (registered == null) {
                    iterator.remove();
                } else if (registered == listener) {
                    found = true;
                }
            }
            if (!found) {
                listeners.add(new WeakReference<>(listener));
            }
            DISPATCHES.clear();
            trackingActive = true;
        }
    }

    public static void unregister(Listener listener) {
        synchronized (LOCK) {
            for (Iterator<List<WeakReference<Listener>>> mapIterator = LISTENERS.values().iterator(); mapIterator.hasNext();) {
                List<WeakReference<Listener>> listeners = mapIterator.next();
                removeListener(listeners, listener);
                if (listeners.isEmpty()) {
                    mapIterator.remove();
                }
            }
            DISPATCHES.clear();
            refreshTrackingState();
        }
    }

    public static void registerAll(MEStorage storage, AllListener listener) {
        synchronized (LOCK) {
            var listeners = ALL_LISTENERS.computeIfAbsent(storage, ignored -> new ArrayList<>());
            boolean found = false;
            for (Iterator<WeakReference<AllListener>> iterator = listeners.iterator(); iterator.hasNext();) {
                AllListener registered = iterator.next().get();
                if (registered == null) {
                    iterator.remove();
                } else if (registered == listener) {
                    found = true;
                }
            }
            if (!found) {
                listeners.add(new WeakReference<>(listener));
            }
            DISPATCHES.clear();
            trackingActive = true;
        }
    }

    public static void unregisterAll(AllListener listener) {
        synchronized (LOCK) {
            for (Iterator<List<WeakReference<AllListener>>> mapIterator = ALL_LISTENERS.values().iterator(); mapIterator.hasNext();) {
                List<WeakReference<AllListener>> listeners = mapIterator.next();
                removeAllListener(listeners, listener);
                if (listeners.isEmpty()) {
                    mapIterator.remove();
                }
            }
            DISPATCHES.clear();
            refreshTrackingState();
        }
    }

    public static boolean isTrackingActive() {
        return trackingActive;
    }

    public static boolean hasPendingOperation() {
        Deque<StorageOperation> stack = OPERATIONS.get();
        return stack != null && !stack.isEmpty();
    }

    public static void beginInsert(MEStorage storage, IActionSource source) {
        beginOperation(storage, OperationKind.INSERT, source);
    }

    public static void endInsert(MEStorage storage, AEKey what, long amount, IActionSource source) {
        endOperation(storage, what, amount, OperationKind.INSERT, source);
    }

    public static void beginExtraction(MEStorage storage, IActionSource source) {
        beginOperation(storage, OperationKind.EXTRACT, source);
    }

    public static void endExtraction(MEStorage storage, AEKey what, long amount, IActionSource source) {
        endOperation(storage, what, amount, OperationKind.EXTRACT, source);
    }

    public static long topologyVersion(MEStorage storage) {
        return storage instanceof ThroughputStorageView view ? view.gtlcore$getTopologyVersion() : 0L;
    }

    public static int refreshVisibleStorageLinks(MEStorage root) {
        if (root == null) {
            return 0;
        }

        int linkedStorages = 0;
        Deque<MEStorage> pending = new ArrayDeque<>();
        Set<MEStorage> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(root);
        seen.add(root);

        while (!pending.isEmpty()) {
            MEStorage parent = pending.removeFirst();
            unlinkVisibleChildren(parent);
            Collection<MEStorage> children = childStorages(parent);
            for (MEStorage child : children) {
                if (child == null || child == parent) {
                    continue;
                }

                // A storage bus may expose another grid through an interface. Do not subscribe
                // to that grid's independent IO, even when it handles the same resource.
                // Successful cross-grid operations still reach the caller through the active
                // operation stack, after the storage bus has applied its access/filter rules.
                if (child instanceof NetworkStorage) {
                    continue;
                }

                linkVisibleStorage(parent, child);
                linkedStorages++;
                if (seen.add(child)) {
                    pending.addLast(child);
                }
            }
        }

        return linkedStorages;
    }

    private static Collection<MEStorage> childStorages(MEStorage storage) {
        return storage instanceof ThroughputStorageView view ? view.gtlcore$getChildStorages() : List.of();
    }

    private static void beginOperation(MEStorage storage, OperationKind kind, IActionSource source) {
        Deque<StorageOperation> stack = OPERATIONS.get();
        if (!trackingActive && (stack == null || stack.isEmpty())) {
            return;
        }
        if (stack == null) {
            stack = new ArrayDeque<>();
            OPERATIONS.set(stack);
        }
        if (diagnostic != null) diagnostic.operations++;
        stack.push(new StorageOperation(storage, kind, source));
    }

    private static void endOperation(MEStorage storage, AEKey what, long amount, OperationKind kind,
                                     @Nullable IActionSource actionSource) {
        Deque<StorageOperation> stack = OPERATIONS.get();
        if (stack == null) {
            return;
        }
        StorageOperation operation = popOperation(stack, storage, kind);
        if (operation == null) {
            OPERATIONS.remove();
            return;
        }
        long changedAmount = Math.max(0L, amount);
        long residualAmount = operation.residualAmount(what, changedAmount);

        StorageOperation parent = stack.peek();
        if (parent != null && parent.kind == kind) {
            parent.recordNestedAmount(what, changedAmount);
        }

        if (residualAmount > 0L) {
            recordChange(
                    storage,
                    what,
                    kind.applySign(residualAmount),
                    actionSource == null ? operation.source : actionSource);
        }

        // Keep only the empty deque for reuse. No storage/source references remain in it.
    }

    private static @Nullable StorageOperation popOperation(Deque<StorageOperation> stack, MEStorage storage,
                                                           OperationKind kind) {
        StorageOperation operation = stack.poll();
        if (operation != null && operation.storage == storage && operation.kind == kind) {
            return operation;
        }

        stack.clear();
        return null;
    }

    private static void recordChange(MEStorage storage, AEKey what, long amountDelta,
                                     @Nullable IActionSource actionSource) {
        if (!trackingActive || amountDelta == 0 || what == null) {
            return;
        }

        long tick = TickHandler.instance().getCurrentTick();
        if (tick <= 0) {
            return;
        }

        if (diagnostic != null) diagnostic.changes++;
        Dispatch dispatch;
        synchronized (LOCK) {
            dispatch = dispatchFor(storage);
            Deque<StorageOperation> stack = OPERATIONS.get();
            if (stack != null && !stack.isEmpty()) {
                // A nested call is an observation, not a permanent topology edge.
                // Include active ancestors so their residual subtraction cannot lose this change.
                Set<Listener> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                Set<AllListener> allSeen = Collections.newSetFromMap(new IdentityHashMap<>());
                List<WeakReference<Listener>> listeners = new ArrayList<>();
                List<WeakReference<AllListener>> allListeners = new ArrayList<>();
                appendDispatch(dispatch, seen, allSeen, listeners, allListeners);
                for (StorageOperation operation : stack) {
                    appendDispatch(dispatchFor(operation.storage), seen, allSeen, listeners, allListeners);
                }
                dispatch = new Dispatch(listeners, allListeners);
            }
        }
        for (WeakReference<Listener> reference : dispatch.listeners()) {
            Listener listener = reference.get();
            if (listener != null && what.equals(listener.getTrackedKey())) {
                try {
                    if (diagnostic != null) diagnostic.callbacks++;
                    listener.recordThroughput(amountDelta, tick);
                } catch (RuntimeException failure) {
                    unregister(listener);
                    GTLCore.LOGGER.error("Disabled failing throughput listener", failure);
                }
            }
        }
        SourceLocation source = null;
        boolean sourceResolved = false;
        for (WeakReference<AllListener> reference : dispatch.allListeners()) {
            AllListener listener = reference.get();
            if (listener != null) {
                try {
                    if (!sourceResolved) {
                        source = resolveSource(actionSource);
                        sourceResolved = true;
                    }
                    if (diagnostic != null) diagnostic.callbacks++;
                    listener.recordThroughput(what, amountDelta, tick, source);
                } catch (RuntimeException failure) {
                    unregisterAll(listener);
                    GTLCore.LOGGER.error("Disabled failing throughput collector", failure);
                }
            }
        }
    }

    private static Dispatch dispatchFor(MEStorage storage) {
        Dispatch dispatch = DISPATCHES.get(storage);
        if (dispatch == null) {
            dispatch = createDispatch(storage);
            DISPATCHES.put(storage, dispatch);
        }
        return dispatch;
    }

    private static void appendDispatch(Dispatch dispatch, Set<Listener> seen, Set<AllListener> allSeen,
                                       List<WeakReference<Listener>> listeners,
                                       List<WeakReference<AllListener>> allListeners) {
        for (WeakReference<Listener> reference : dispatch.listeners()) {
            Listener listener = reference.get();
            if (listener != null && seen.add(listener)) listeners.add(reference);
        }
        for (WeakReference<AllListener> reference : dispatch.allListeners()) {
            AllListener listener = reference.get();
            if (listener != null && allSeen.add(listener)) allListeners.add(reference);
        }
    }

    private static Dispatch createDispatch(MEStorage storage) {
        if (diagnostic != null) diagnostic.dispatchBuilds++;
        Set<Listener> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<AllListener> allSeen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<WeakReference<Listener>> listeners = new ArrayList<>();
        List<WeakReference<AllListener>> allListeners = new ArrayList<>();
        for (MEStorage target : visibleDispatchStorages(storage)) {
            for (WeakReference<Listener> reference : LISTENERS.getOrDefault(target, List.of())) {
                Listener listener = reference.get();
                if (listener != null && seen.add(listener)) listeners.add(reference);
            }
            for (WeakReference<AllListener> reference : ALL_LISTENERS.getOrDefault(target, List.of())) {
                AllListener listener = reference.get();
                if (listener != null && allSeen.add(listener)) allListeners.add(reference);
            }
        }
        return new Dispatch(List.copyOf(listeners), List.copyOf(allListeners));
    }

    private record Dispatch(List<WeakReference<Listener>> listeners,
                            List<WeakReference<AllListener>> allListeners) {}

    private static void refreshTrackingState() {
        trackingActive = !LISTENERS.isEmpty() || !ALL_LISTENERS.isEmpty();
        if (!trackingActive) {
            VISIBLE_PARENTS.clear();
            VISIBLE_CHILDREN.clear();
        }
    }

    private static @Nullable SourceLocation resolveSource(@Nullable IActionSource actionSource) {
        if (actionSource == null) {
            return null;
        }

        IActionHost actionHost = actionSource.machine().orElse(null);
        if (actionHost != null) {
            IGridNode node = actionHost.getActionableNode();
            if (node != null) {
                SourceLocation ownerLocation = resolveOwner(node.getOwner());
                if (ownerLocation != null) {
                    return ownerLocation;
                }
            }
        }

        BlockEntity contextBlockEntity = actionSource.context(BlockEntity.class).orElse(null);
        SourceLocation contextLocation = resolveOwner(contextBlockEntity);
        if (contextLocation != null) {
            return contextLocation;
        }
        MetaMachine contextMachine = actionSource.context(MetaMachine.class).orElse(null);
        contextLocation = resolveOwner(contextMachine);
        if (contextLocation != null) {
            return contextLocation;
        }

        Player player = actionSource.player().orElse(null);
        return player == null ? null : new SourceLocation(
                player.level().dimension().location(),
                player.blockPosition(),
                player.getDirection());
    }

    private static @Nullable SourceLocation resolveOwner(@Nullable Object owner) {
        if (owner instanceof AEBasePart part && part.getLevel() != null) {
            return new SourceLocation(
                    part.getLevel().dimension().location(),
                    part.getHost().getLocation().getPos(),
                    part.getSide());
        }
        if (owner instanceof BlockEntity blockEntity && blockEntity.getLevel() != null) {
            return new SourceLocation(
                    blockEntity.getLevel().dimension().location(),
                    blockEntity.getBlockPos(),
                    null);
        }
        if (owner instanceof MetaMachine machine && machine.getLevel() != null) {
            return new SourceLocation(
                    machine.getLevel().dimension().location(),
                    machine.getPos(),
                    machine.getFrontFacing());
        }
        if (owner instanceof IMachineBlockEntity machineBlockEntity) {
            return resolveOwner(machineBlockEntity.self());
        }
        if (owner instanceof IGridConnectedMachine gridConnectedMachine) {
            return resolveOwner(gridConnectedMachine.self());
        }
        if (owner instanceof MachineTrait machineTrait) {
            return resolveOwner(machineTrait.getMachine());
        }
        return null;
    }

    private static Set<MEStorage> visibleDispatchStorages(MEStorage storage) {
        Set<MEStorage> storages = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<MEStorage> pending = new ArrayDeque<>();
        storages.add(storage);
        pending.add(storage);

        while (!pending.isEmpty()) {
            MEStorage child = pending.removeFirst();
            List<WeakReference<MEStorage>> parents = VISIBLE_PARENTS.get(child);
            if (parents == null) {
                continue;
            }

            for (Iterator<WeakReference<MEStorage>> iterator = parents.iterator(); iterator.hasNext();) {
                MEStorage parent = iterator.next().get();
                if (parent == null) {
                    iterator.remove();
                } else if (storages.add(parent)) {
                    pending.addLast(parent);
                }
            }
            if (parents.isEmpty()) {
                VISIBLE_PARENTS.remove(child);
            }
        }

        return storages;
    }

    private static void linkVisibleStorage(MEStorage parent, MEStorage child) {
        if (parent == null || child == null || parent == child) {
            return;
        }

        synchronized (LOCK) {
            List<WeakReference<MEStorage>> parents = VISIBLE_PARENTS.computeIfAbsent(child, ignored -> new ArrayList<>());
            for (Iterator<WeakReference<MEStorage>> iterator = parents.iterator(); iterator.hasNext();) {
                MEStorage existing = iterator.next().get();
                if (existing == null) {
                    iterator.remove();
                } else if (existing == parent) {
                    return;
                }
            }
            DISPATCHES.clear();
            parents.add(new WeakReference<>(parent));
            VISIBLE_CHILDREN.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(new WeakReference<>(child));
        }
    }

    private static void unlinkVisibleChildren(MEStorage parent) {
        synchronized (LOCK) {
            DISPATCHES.clear();
            List<WeakReference<MEStorage>> children = VISIBLE_CHILDREN.remove(parent);
            if (children == null) {
                return;
            }

            for (WeakReference<MEStorage> childReference : children) {
                MEStorage child = childReference.get();
                if (child != null) {
                    removeVisibleParent(child, parent);
                }
            }
        }
    }

    private static void removeVisibleParent(MEStorage child, MEStorage parent) {
        List<WeakReference<MEStorage>> parents = VISIBLE_PARENTS.get(child);
        if (parents == null) {
            return;
        }

        for (Iterator<WeakReference<MEStorage>> iterator = parents.iterator(); iterator.hasNext();) {
            MEStorage existing = iterator.next().get();
            if (existing == null || existing == parent) {
                iterator.remove();
            }
        }
        if (parents.isEmpty()) {
            VISIBLE_PARENTS.remove(child);
        }
    }

    private static void removeListener(List<WeakReference<Listener>> listeners, Listener listener) {
        for (Iterator<WeakReference<Listener>> iterator = listeners.iterator(); iterator.hasNext();) {
            Listener registered = iterator.next().get();
            if (registered == null || registered == listener) {
                iterator.remove();
            }
        }
    }

    private static void removeAllListener(List<WeakReference<AllListener>> listeners, AllListener listener) {
        for (Iterator<WeakReference<AllListener>> iterator = listeners.iterator(); iterator.hasNext();) {
            AllListener registered = iterator.next().get();
            if (registered == null || registered == listener) {
                iterator.remove();
            }
        }
    }

    private enum OperationKind {

        INSERT {

            @Override
            long applySign(long amount) {
                return amount;
            }
        },
        EXTRACT {

            @Override
            long applySign(long amount) {
                return -amount;
            }
        };

        abstract long applySign(long amount);
    }

    private static final class StorageOperation {

        private final MEStorage storage;
        private final OperationKind kind;
        private final IActionSource source;
        private @Nullable Map<AEKey, Long> nestedAmounts;

        private StorageOperation(MEStorage storage, OperationKind kind, @Nullable IActionSource source) {
            this.storage = storage;
            this.kind = kind;
            this.source = source;
        }

        private void recordNestedAmount(AEKey key, long amount) {
            if (key == null || amount <= 0L) {
                return;
            }

            if (this.nestedAmounts == null) {
                this.nestedAmounts = new HashMap<>();
            }
            this.nestedAmounts.merge(key, amount, NumberUtils::saturatedAdd);
        }

        private long residualAmount(AEKey key, long totalAmount) {
            long nestedAmount = key == null || this.nestedAmounts == null ?
                    0L : this.nestedAmounts.getOrDefault(key, 0L);
            return totalAmount > nestedAmount ? totalAmount - nestedAmount : 0L;
        }
    }

    public interface Listener {

        AEKey getTrackedKey();

        void recordThroughput(long amountDelta, long tick);
    }

    public interface AllListener {

        void recordThroughput(AEKey key, long amountDelta, long tick, @Nullable SourceLocation source);
    }

    public record SourceLocation(ResourceLocation dimension, BlockPos pos, @Nullable Direction side) {}
}
