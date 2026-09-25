package org.gtlcore.gtlcore.integration.ae2.graph;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.function.Supplier;

/** Carries graph's stricter production preflight through a pattern relay. */
public final class GraphDispatchContext {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Batch> BATCH = new ThreadLocal<>();

    private GraphDispatchContext() {}

    public static boolean allowed(ICraftingProvider provider, IPatternDetails pattern) {
        return !ACTIVE.get() || !(provider instanceof GraphDispatchPreflight check) || check.gtlcore$canProduceGraphPattern(pattern);
    }

    public static boolean active() {
        return ACTIVE.get();
    }

    public static long operations() {
        return BATCH.get() == null ? 1 : BATCH.get().operations();
    }

    /** Exact selected quantities for one operation, also carried through relays. */
    public static @Nullable Map<AEKey, Long> selectedInputs() {
        Batch batch = BATCH.get();
        return ACTIVE.get() && batch != null ? batch.inputs() : null;
    }

    public static long capacity(ICraftingProvider provider, IPatternDetails pattern, long requested) {
        Batch batch = BATCH.get();
        if (!ACTIVE.get() || batch == null || !(provider instanceof GraphDispatchPreflight check)) return requested;
        return Math.max(0, Math.min(requested, check.gtlcore$graphCapacity(pattern, batch.inputs(), requested)));
    }

    public static <T> T call(Map<AEKey, Long> inputs, long operations, Supplier<T> action) {
        Batch previous = BATCH.get();
        BATCH.set(new Batch(inputs, operations));
        try {
            return call(action);
        } finally {
            if (previous == null) BATCH.remove();
            else BATCH.set(previous);
        }
    }

    public static <T> T call(Supplier<T> action) {
        boolean previous = ACTIVE.get();
        ACTIVE.set(true);
        try {
            return action.get();
        } finally {
            if (previous) ACTIVE.set(true);
            else ACTIVE.remove();
        }
    }

    private record Batch(Map<AEKey, Long> inputs, long operations) {}
}
