package org.gtlcore.gtlcore.integration.ae2.crafting;

import appeng.api.config.Actionable;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Keeps manually planned crafting ingredients unavailable to other network extractions. */
public final class ManualCraftingInventoryLock {

    private static final Map<MEStorage, KeyCounter> RESERVED = new WeakHashMap<>();
    private static final ThreadLocal<Reservation> ACTIVE_SUBMISSION = new ThreadLocal<>();
    // Availability simulation can re-enter the same network through a mounted inventory.
    private static final ThreadLocal<IdentityHashMap<MEStorage, Boolean>> AVAILABILITY_QUERIES = new ThreadLocal<>();
    private static final AtomicLong NEXT_RESERVATION_ID = new AtomicLong();

    private ManualCraftingInventoryLock() {}

    public static @Nullable Reservation tryAcquire(MEStorage storage, KeyCounter requested, IActionSource source) {
        var owned = new KeyCounter();
        for (var entry : requested) {
            if (entry.getLongValue() > 0) owned.add(entry.getKey(), entry.getLongValue());
        }
        var probes = new KeyCounter();
        synchronized (RESERVED) {
            var reserved = RESERVED.get(storage);
            for (var entry : owned) {
                long alreadyReserved = reserved == null ? 0 : reserved.get(entry.getKey());
                long amount = entry.getLongValue();
                probes.add(entry.getKey(), amount > Long.MAX_VALUE - alreadyReserved ? Long.MAX_VALUE : amount + alreadyReserved);
            }
        }
        var available = new KeyCounter();
        // Advertised keys are a display view: providers can accept aliases that
        // they do not list, and visible amounts can be stale or inaccessible.
        // Probe only the requested keys through the same extraction path as
        // submission. Bound each query by this claim plus existing reservations.
        // World callbacks run outside the reservation monitor; recheck all
        // current claims below before atomically publishing the new owner.
        for (var entry : probes) {
            long count = simulatePhysical(storage, entry.getKey(), entry.getLongValue(), source);
            if (count > 0) available.add(entry.getKey(), Math.min(count, entry.getLongValue()));
        }

        Reservation reservation = null;
        AEKey conflictKey = null;
        long conflictRequested = 0;
        long conflictAvailable = 0;
        long conflictReserved = 0;
        synchronized (RESERVED) {
            var reserved = RESERVED.get(storage);
            for (var entry : owned) {
                long alreadyReserved = reserved == null ? 0 : reserved.get(entry.getKey());
                long availableToReserve = subtractClamped(available.get(entry.getKey()), alreadyReserved);
                if (entry.getLongValue() > availableToReserve) {
                    conflictKey = entry.getKey();
                    conflictRequested = entry.getLongValue();
                    conflictAvailable = availableToReserve;
                    conflictReserved = alreadyReserved;
                    break;
                }
            }

            if (conflictKey == null) {
                if (!owned.isEmpty()) {
                    if (reserved == null) {
                        reserved = new KeyCounter();
                        RESERVED.put(storage, reserved);
                    }
                    reserved.addAll(owned);
                }
                reservation = new Reservation(NEXT_RESERVATION_ID.incrementAndGet(), storage, owned);
            }
        }

        if (reservation == null) {
            ManualCraftingInventoryLockLogger.conflict(
                    storage, conflictKey, conflictRequested, conflictAvailable, conflictReserved, source);
        } else {
            ManualCraftingInventoryLockLogger.acquired(reservation.id, storage, reservation.amounts, source);
        }
        return reservation;
    }

    public static boolean hasReservations(MEStorage storage) {
        synchronized (RESERVED) {
            var reserved = RESERVED.get(storage);
            return reserved != null && !reserved.isEmpty();
        }
    }

    /** Carry only the owning reservation across a deferred server-side query. */
    public static AvailabilityContext captureAvailabilityContext() {
        return new AvailabilityContext(ACTIVE_SUBMISSION.get());
    }

    public static final class AvailabilityContext {

        private final Reservation owner;

        private AvailabilityContext(Reservation owner) {
            this.owner = owner;
        }

        public long call(java.util.function.LongSupplier query) {
            var previous = ACTIVE_SUBMISSION.get();
            // limitExtraction also checks that the captured owner is still active.
            if (owner == null) ACTIVE_SUBMISSION.remove();
            else ACTIVE_SUBMISSION.set(owner);
            try {
                return query.getAsLong();
            } finally {
                if (previous == null) ACTIVE_SUBMISSION.remove();
                else ACTIVE_SUBMISSION.set(previous);
            }
        }
    }

    public static long limitExtraction(MEStorage storage, AEKey what, long requested, IActionSource source) {
        if (requested <= 0) {
            return requested;
        }
        var availabilityQueries = AVAILABILITY_QUERIES.get();
        if (availabilityQueries != null && availabilityQueries.containsKey(storage)) {
            return requested;
        }

        long reservedForOthers = reservedForOthers(storage, what);
        if (reservedForOthers <= 0) {
            return requested;
        }

        long available = getPhysicalAvailableAmount(storage, what, source);
        long extractable = subtractClamped(available, reservedForOthers);
        long allowed = Math.min(requested, extractable);
        if (allowed < requested) {
            ManualCraftingInventoryLockLogger.extractionLimited(
                    storage, what, requested, allowed, available, reservedForOthers, source);
        }
        return allowed;
    }

    /** Reservation adjustment for an exact planning read; honors the active submit owner. */
    public static long reservedForOthers(MEStorage storage, AEKey what) {
        synchronized (RESERVED) {
            var reserved = RESERVED.get(storage);
            if (reserved == null) {
                return 0;
            }

            long totalReserved = reserved.get(what);
            var active = ACTIVE_SUBMISSION.get();
            long owned = active != null && active.isActiveFor(storage) ? active.amounts.get(what) : 0;
            return subtractClamped(totalReserved, owned);
        }
    }

    /** Read through security and extraction filters, without subtracting this root's reservation twice. */
    public static long simulatePhysical(MEStorage storage, AEKey what, IActionSource source) {
        return simulatePhysical(storage, what, Long.MAX_VALUE, source);
    }

    private static long simulatePhysical(MEStorage storage, AEKey what, long amount, IActionSource source) {
        var queries = AVAILABILITY_QUERIES.get();
        if (queries == null) {
            queries = new IdentityHashMap<>();
            AVAILABILITY_QUERIES.set(queries);
        }
        Boolean previous = queries.put(storage, Boolean.TRUE);
        try {
            return storage.extract(what, amount, Actionable.SIMULATE, source);
        } finally {
            if (previous == null) queries.remove(storage);
            else queries.put(storage, previous);
            if (queries.isEmpty()) AVAILABILITY_QUERIES.remove();
        }
    }

    private static long getPhysicalAvailableAmount(MEStorage storage, AEKey what, IActionSource source) {
        var availabilityQueries = AVAILABILITY_QUERIES.get();
        if (availabilityQueries == null) {
            availabilityQueries = new IdentityHashMap<>();
            AVAILABILITY_QUERIES.set(availabilityQueries);
        }
        availabilityQueries.put(storage, Boolean.TRUE);
        try {
            if (storage instanceof AvailabilityView view) {
                return view.gtlcore$getAvailableAmount(what, source);
            }
            return storage.extract(what, Long.MAX_VALUE, Actionable.SIMULATE, source);
        } finally {
            availabilityQueries.remove(storage);
            if (availabilityQueries.isEmpty()) {
                AVAILABILITY_QUERIES.remove();
            }
        }
    }

    private static long subtractClamped(long value, long deduction) {
        return value <= deduction ? 0 : value - deduction;
    }

    public interface AvailabilityView {

        long gtlcore$getAvailableAmount(AEKey what, IActionSource source);
    }

    public static final class Reservation implements AutoCloseable {

        private final long id;
        private final MEStorage storage;
        private final KeyCounter amounts;
        private boolean active = true;

        private Reservation(long id, MEStorage storage, KeyCounter amounts) {
            this.id = id;
            this.storage = storage;
            this.amounts = amounts;
        }

        public ICraftingSubmitResult submit(Supplier<ICraftingSubmitResult> action) {
            if (!this.active) {
                return action.get();
            }

            var previous = ACTIVE_SUBMISSION.get();
            ACTIVE_SUBMISSION.set(this);
            try {
                ICraftingSubmitResult result = action.get();
                ManualCraftingInventoryLockLogger.submitted(this.id, result);
                return result;
            } catch (RuntimeException | Error exception) {
                ManualCraftingInventoryLockLogger.submissionFailed(this.id, exception);
                throw exception;
            } finally {
                if (previous == null) {
                    ACTIVE_SUBMISSION.remove();
                } else {
                    ACTIVE_SUBMISSION.set(previous);
                }
            }
        }

        private boolean isActiveFor(MEStorage storage) {
            return this.active && this.storage == storage;
        }

        @Override
        public void close() {
            boolean released = false;
            synchronized (RESERVED) {
                if (!this.active) {
                    return;
                }
                this.active = false;

                var reserved = RESERVED.get(this.storage);
                if (reserved == null) {
                    released = true;
                } else {
                    reserved.removeAll(this.amounts);
                    reserved.removeZeros();
                    if (reserved.isEmpty()) {
                        RESERVED.remove(this.storage);
                    }
                    released = true;
                }
            }
            if (released) {
                ManualCraftingInventoryLockLogger.released(this.id, this.storage, this.amounts);
            }
        }
    }
}
