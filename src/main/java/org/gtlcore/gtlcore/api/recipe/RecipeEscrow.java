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
 * before extracting anything. Entries whose refund threw with an unknown
 * outcome are kept but not retried automatically.
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
        add(network, source, key, amount, false);
    }

    public static void addUncertain(MEStorage network, IActionSource source, AEKey key, long amount) {
        add(network, source, key, amount, true);
    }

    private static void add(MEStorage network, IActionSource source, AEKey key, long amount, boolean uncertain) {
        if (network == null || key == null || amount <= 0L) {
            return;
        }
        synchronized (PENDING) {
            for (int i = 0; i < PENDING.size(); i++) {
                Entry existing = PENDING.get(i);
                if (existing.network == network && existing.key.equals(key) && existing.uncertain == uncertain) {
                    PENDING.set(i, new Entry(network, source, key, existing.amount + amount,
                            existing.uncertain || uncertain));
                    return;
                }
            }
            if (PENDING.size() >= MAX_PENDING) {
                GTLCore.LOGGER.warn("[RecipeEscrow] buffer full, {} x {} stays pending until retry", amount, key);
            }
            PENDING.add(new Entry(network, source, key, amount, uncertain));
        }
        if (uncertain) {
            GTLCore.LOGGER.error("[RecipeEscrow] refund outcome unknown for {} x {}, not retrying", amount, key);
        } else {
            GTLCore.LOGGER.warn("[RecipeEscrow] holding {} x {}", amount, key);
        }
    }

    public static void retryAll() {
        synchronized (PENDING) {
            for (int i = PENDING.size() - 1; i >= 0; i--) {
                Entry entry = PENDING.get(i);
                if (entry.uncertain) {
                    continue;
                }
                try {
                    long returned = entry.network.insert(entry.key, entry.amount, Actionable.MODULATE, entry.source);
                    if (returned >= entry.amount) {
                        PENDING.remove(i);
                    } else if (returned > 0L) {
                        PENDING.set(i, new Entry(entry.network, entry.source, entry.key, entry.amount - returned,
                                false));
                    }
                } catch (RuntimeException | Error failure) {
                    GTLCore.LOGGER.error("[RecipeEscrow] retry failed for {} x {}, keeping as uncertain",
                            entry.amount, entry.key);
                    PENDING.set(i, new Entry(entry.network, entry.source, entry.key, entry.amount, true));
                }
            }
        }
    }

    public static int pendingCount() {
        synchronized (PENDING) {
            return PENDING.size();
        }
    }

    private record Entry(MEStorage network, IActionSource source, AEKey key, long amount, boolean uncertain) {}
}
