package org.gtlcore.gtlcore.integration.ae2.async;

import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;

import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.IntProviderIngredient;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import appeng.api.stacks.*;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Accepted output belongs to the machine immediately; only draining its ledger is asynchronous. */
public final class AEWriteService implements AutoCloseable {

    public static final AEWriteService INSTANCE = new AEWriteService();
    public static int THREAD_PRIORITY = Thread.NORM_PRIORITY - 1;
    public static int TIME_OUT = 10;
    /** Kept for addon binary compatibility. Saving no longer depends on a worker timeout. */
    public static long FLUSH_TIMEOUT = 500;

    private volatile ThreadPoolExecutor executor;

    private ThreadPoolExecutor executor() {
        ThreadPoolExecutor current = executor;
        if (current != null && !current.isShutdown()) return current;
        synchronized (this) {
            current = executor;
            if (current == null || current.isShutdown()) {
                current = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(4096), this::createWorker, (task, pool) -> task.run());
                executor = current;
            }
            return current;
        }
    }

    private Thread createWorker(Runnable r) {
        Thread t = new Thread(r, "AE-Writer");
        t.setDaemon(true);
        t.setPriority(Math.max(Thread.MIN_PRIORITY, Math.min(Thread.MAX_PRIORITY, THREAD_PRIORITY)));
        return t;
    }

    private void execute(Runnable task) {
        try {
            executor().execute(task);
        } catch (RejectedExecutionException e) {
            task.run();
        }
    }

    public void submitIngredientLeft(WeakReference<AEAccumulator> accRef, List<Ingredient> left) {
        if (left == null || left.isEmpty()) return;
        var acc = accRef.get();
        if (acc == null) throw new IllegalStateException("Output owner disappeared before accepting items");
        if (left.size() == 1) {
            GenericStack output = itemOutput(left.get(0));
            if (output != null) acc.add(output.what(), output.amount());
            return;
        }
        // Resolve quantities, random providers and NBT before acknowledging the
        // recipe. No worker may retain mutable ingredient objects or sample again.
        Object2LongOpenHashMap<AEKey> accepted = new Object2LongOpenHashMap<>();
        for (Ingredient ingredient : left) {
            GenericStack output = itemOutput(ingredient);
            if (output != null) accepted.addTo(output.what(), output.amount());
        }
        synchronized (acc) {
            accepted.object2LongEntrySet().fastForEach(entry -> acc.add(entry.getKey(), entry.getLongValue()));
        }
    }

    private static GenericStack itemOutput(Ingredient ingredient) {
        if (ingredient instanceof IntProviderIngredient intProvider) {
            intProvider.setItemStacks(null);
            intProvider.setSampledCount(null);
        }
        ItemStack[] items = ingredient.getItems();
        if (items.length == 0 || items[0].isEmpty()) return null;
        ItemStack output = items[0];
        return new GenericStack(AEItemKey.of(output),
                ingredient instanceof LongIngredient longIngredient ? longIngredient.getActualAmount() : output.getCount());
    }

    public void submitFluidIngredientLeft(WeakReference<AEAccumulator> accRef, List<FluidIngredient> left) {
        if (left == null || left.isEmpty()) return;
        var acc = accRef.get();
        if (acc == null) throw new IllegalStateException("Output owner disappeared before accepting fluids");
        if (left.size() == 1) {
            GenericStack output = fluidOutput(left.get(0));
            if (output != null) acc.add(output.what(), output.amount());
            return;
        }
        Object2LongOpenHashMap<AEKey> accepted = new Object2LongOpenHashMap<>();
        for (FluidIngredient ingredient : left) {
            GenericStack output = fluidOutput(ingredient);
            if (output != null) accepted.addTo(output.what(), output.amount());
        }
        synchronized (acc) {
            accepted.object2LongEntrySet().fastForEach(entry -> acc.add(entry.getKey(), entry.getLongValue()));
        }
    }

    private static GenericStack fluidOutput(FluidIngredient ingredient) {
        if (ingredient.isEmpty()) return null;
        FluidStack[] fluids = ingredient.getStacks();
        if (fluids.length == 0 || fluids[0].isEmpty()) return null;
        FluidStack output = fluids[0];
        return new GenericStack(AEFluidKey.of(output.getFluid(), output.getTag()), output.getAmount());
    }

    public void prepareDrainedData(WeakReference<AEAccumulator> accRef,
                                   Queue<Object2LongOpenHashMap<AEKey>> targetQueue,
                                   AtomicBoolean drainRequested) {
        execute(() -> {
            try {
                drainNow(accRef, targetQueue);
            } finally {
                drainRequested.set(false);
            }
        });
    }

    private void drainNow(WeakReference<AEAccumulator> accRef, Queue<Object2LongOpenHashMap<AEKey>> targetQueue) {
        var acc = accRef.get();
        if (acc == null) return;
        // Transfer and publication share one monitor. A save cannot observe the
        // gap where the ledger is empty but a worker still owns unpublished data.
        synchronized (acc) {
            if (acc.isEmpty()) return;
            Object2LongOpenHashMap<AEKey> drained = new Object2LongOpenHashMap<>();
            acc.drainTo(drained);
            if (!drained.isEmpty()) targetQueue.add(drained);
        }
    }

    /** Materialize this machine's accepted output without waiting for unrelated writers. */
    public boolean flushBlocking(WeakReference<AEAccumulator> accRef,
                                 Queue<Object2LongOpenHashMap<AEKey>> targetQueue,
                                 AtomicBoolean drainRequested, long timeoutMs) {
        drainNow(accRef, targetQueue);
        // An already queued drain may still run. Leave its flag set until that
        // task finishes, so repeated saves do not enqueue duplicate work.
        return true;
    }

    public void shutDownGracefully() {
        ThreadPoolExecutor pool = executor;
        if (pool == null) return;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(TIME_OUT, TimeUnit.SECONDS))
                pool.shutdownNow().forEach(Runnable::run);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow().forEach(Runnable::run);
        }
    }

    @Override
    public void close() {
        shutDownGracefully();
    }
}
