package org.gtlcore.gtlcore.api.recipe;

import org.gtlcore.gtlcore.GTLCore;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds recipe materials that could not be returned to their network, and
 * retries on every server tick. Entries are merged per network/key and are
 * never dropped: {@link #isFull()} lets new recipe transactions back off
 * before extracting anything.
 */
public final class RecipeEscrow {

    private static final int MAX_PENDING = 256;
    private static final List<Entry> PENDING = new ArrayList<>();

    private RecipeEscrow() {}

    public static boolean isFull() {
        synchronized (PENDING) {
            return PENDING.size() >= MAX_PENDING;
        }
    }

    public static void add(MEStorage network, IActionSource source, AEKey key, long amount) {
        if (network == null || key == null || amount <= 0L) {
            return;
        }
        synchronized (PENDING) {
            for (int i = 0; i < PENDING.size(); i++) {
                Entry existing = PENDING.get(i);
                if (existing.network == network && existing.key.equals(key)) {
                    PENDING.set(i, new Entry(network, source, key, existing.amount + amount));
                    return;
                }
            }
            if (PENDING.size() >= MAX_PENDING) {
                GTLCore.LOGGER.warn("[RecipeEscrow] buffer full, {} x {} stays pending until retry", amount, key);
            }
            PENDING.add(new Entry(network, source, key, amount));
        }
        GTLCore.LOGGER.warn("[RecipeEscrow] holding {} x {}", amount, key);
    }

    public static void retryAll() {
        synchronized (PENDING) {
            for (int i = PENDING.size() - 1; i >= 0; i--) {
                Entry entry = PENDING.get(i);
                try {
                    long returned = entry.network.insert(entry.key, entry.amount, Actionable.MODULATE, entry.source);
                    if (returned >= entry.amount) {
                        PENDING.remove(i);
                    } else if (returned > 0L) {
                        PENDING.set(i, new Entry(entry.network, entry.source, entry.key, entry.amount - returned));
                    }
                } catch (RuntimeException | Error failure) {
                    GTLCore.LOGGER.warn("[RecipeEscrow] retry failed for {} x {}", entry.amount, entry.key);
                }
            }
        }
    }

    public static int pendingCount() {
        synchronized (PENDING) {
            return PENDING.size();
        }
    }

    private record Entry(MEStorage network, IActionSource source, AEKey key, long amount) {}
}
