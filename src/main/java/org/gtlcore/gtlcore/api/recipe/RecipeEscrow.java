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
 * retries on every server tick. Entries are capped so a broken network cannot
 * grow the buffer forever.
 */
public final class RecipeEscrow {

    private static final int MAX_PENDING = 256;
    private static final List<Entry> PENDING = new ArrayList<>();

    private RecipeEscrow() {}

    public static void add(MEStorage network, IActionSource source, AEKey key, long amount) {
        if (network == null || key == null || amount <= 0L) {
            return;
        }
        synchronized (PENDING) {
            if (PENDING.size() >= MAX_PENDING) {
                GTLCore.LOGGER.error("[RecipeEscrow] buffer full, dropping {} x {}", amount, key);
                return;
            }
            PENDING.add(new Entry(network, source, key, amount));
        }
        GTLCore.LOGGER.warn("[RecipeEscrow] holding {} x {}", amount, key);
    }

    public static void retryAll() {
        synchronized (PENDING) {
            if (PENDING.isEmpty()) {
                return;
            }
            for (int i = PENDING.size() - 1; i >= 0; i--) {
                Entry entry = PENDING.get(i);
                long returned = entry.network.insert(entry.key, entry.amount, Actionable.MODULATE, entry.source);
                if (returned >= entry.amount) {
                    PENDING.remove(i);
                } else if (returned > 0L) {
                    PENDING.set(i, new Entry(entry.network, entry.source, entry.key, entry.amount - returned));
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
