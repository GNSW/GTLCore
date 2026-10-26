package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.integration.ae2.crafting.ManualCraftingInventoryLock;
import org.gtlcore.gtlcore.integration.ae2.storage.PreciseStorageAmount;
import org.gtlcore.gtlcore.integration.ae2.throughput.ThroughputStorageView;
import org.gtlcore.gtlcore.mixin.ae2.storage.MEInventoryHandlerDisplayAccessor;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.me.storage.CompositeStorage;
import appeng.me.storage.DelegatingMEInventory;
import appeng.me.storage.DriveWatcher;
import appeng.me.storage.MEInventoryHandler;
import appeng.me.storage.NetworkStorage;
import org.cgse.core.PlanningBudget;

import java.math.BigInteger;
import java.util.*;

/** Server-thread, incremental read for saturated keys. Never transfers or invents stock. */
final class ExactInventoryRead implements AutoCloseable {

    private final MEStorage root;
    private final AEKey key;
    private final IActionSource source;
    private final PlanningBudget budget;
    private final Set<MEStorage> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Deque<Frame> pending = new ArrayDeque<>();
    private BigInteger amount = BigInteger.ZERO;
    private long memory;
    private boolean complete, unlimited, exact = true;

    ExactInventoryRead(MEStorage root, AEKey key, IActionSource source, PlanningBudget budget) {
        this.root = root;
        this.key = key;
        this.source = source;
        this.budget = budget;
        pending.push(new Frame(List.of(root).iterator(), null));
    }

    private static final class Frame {

        final Iterator<MEStorage> children;
        final BigInteger limit;
        BigInteger amount = BigInteger.ZERO;
        boolean unlimited;

        Frame(Iterator<MEStorage> children, BigInteger limit) {
            this.children = children;
            this.limit = limit;
        }
    }

    /** One storage node or one completed frame per call, inside the snapshot tick slice. */
    boolean step() {
        if (complete) return true;
        budget.compilationCheck();
        Frame frame = pending.peek();
        if (!frame.children.hasNext()) {
            pending.pop();
            BigInteger sum = frame.limit == null ? frame.amount : frame.amount.min(frame.limit);
            if (pending.isEmpty()) {
                unlimited = frame.unlimited && frame.limit == null;
                // Keep the currently extractable physical window separate from
                // unbounded future supply, including the root's active locks.
                amount = unlimited ? BigInteger.valueOf(root.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, source)) :
                        sum.subtract(BigInteger.valueOf(ManualCraftingInventoryLock.reservedForOthers(root, key))).max(BigInteger.ZERO);
                complete = true;
            } else {
                pending.peek().amount = pending.peek().amount.add(sum);
                pending.peek().unlimited |= frame.unlimited && frame.limit == null;
            }
            return complete;
        }
        MEStorage storage = frame.children.next();
        if (storage == null || visited.contains(storage)) return false;
        if (visited.size() >= 4096 || pending.size() > 64)
            throw new PlanningBudget.Exhausted(PlanningBudget.Limit.GRAPH_LIMIT, "exact_inventory_topology");
        budget.reserve(128);
        memory += 128;
        visited.add(storage);
        // Check the actual extraction path before looking behind any wrapper.
        // The root reservation is subtracted once, after the exact physical sum.
        long accessible = storage == root ? ManualCraftingInventoryLock.simulatePhysical(storage, key, source) :
                storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, source);
        if (accessible <= 0) return false;
        String typeName = storage.getClass().getName();
        // These providers generate the selected resource without decrementing stock.
        // They are external supply, not a finite pile of Long.MAX_VALUE items.
        // Admit only the known implementation, after the actual extraction check.
        if (accessible == Long.MAX_VALUE &&
                (typeName.equals("com.glodblock.github.extendedae.common.inventory.InfinityCellInventory") ||
                        typeName.equals("org.gtlcore.gtlcore.integration.ae2.storage.CircuitInfinityCellInventory") ||
                        typeName.equals("org.gtlcore.gtlcore.common.machine.VirtualIngredientSupplyMachine"))) {
            frame.unlimited = true;
            frame.amount = frame.amount.add(BigInteger.valueOf(accessible));
            exact = false;
            return false;
        }
        if (storage instanceof PreciseStorageAmount precise) {
            BigInteger stored = precise.getExactStoredAmount(key);
            if (stored.signum() < 0) throw new IllegalStateException("Negative exact inventory");
            frame.amount = frame.amount.add(accessible == Long.MAX_VALUE ? stored : stored.min(BigInteger.valueOf(accessible)));
            return false;
        }
        Class<?> type = storage.getClass();
        boolean transparent = type == NetworkStorage.class || type == CompositeStorage.class ||
                type == DelegatingMEInventory.class || type == MEInventoryHandler.class || type == DriveWatcher.class ||
                typeName.equals("appeng.parts.storagebus.StorageBusPart$StorageBusInventory");
        if (transparent && storage instanceof ThroughputStorageView view) {
            if (storage instanceof MEInventoryHandlerDisplayAccessor filter &&
                    (!filter.gtlcore$allowsDisplayExtraction() ||
                            filter.gtlcore$filtersDisplayExtraction() && !filter.gtlcore$canDisplay(key)))
                return false;
            pending.push(new Frame(view.gtlcore$getChildStorages().iterator(),
                    accessible == Long.MAX_VALUE ? null : BigInteger.valueOf(accessible)));
        } else {
            // An unknown saturated inventory is a lower bound, never an exact MAX.
            frame.amount = frame.amount.add(BigInteger.valueOf(accessible));
            if (accessible == Long.MAX_VALUE) exact = false;
        }
        return false;
    }

    BigInteger amount() {
        if (!complete) throw new IllegalStateException("Inventory read is incomplete");
        return amount;
    }

    boolean exact() {
        return complete && exact;
    }

    boolean unlimited() {
        return complete && unlimited;
    }

    public void close() {
        budget.release(memory);
        memory = 0;
        visited.clear();
        pending.clear();
    }
}
