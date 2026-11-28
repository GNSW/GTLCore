package org.gtlcore.gtlcore.api.recipe;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Stages real extractions for one recipe input attempt. When a scope is shared
 * between capabilities, a failure in any of them returns everything taken so
 * far instead of leaving part of the materials behind.
 */
public final class RecipeExtractionScope implements AutoCloseable {

    private static final ThreadLocal<RecipeExtractionScope> CURRENT = new ThreadLocal<>();

    private final MEStorage defaultNetwork;
    private final IActionSource defaultSource;
    private final List<Staged> staged = new ArrayList<>();
    private boolean committed;
    private boolean closed;
    private int depth;

    private RecipeExtractionScope(@Nullable MEStorage defaultNetwork, @Nullable IActionSource defaultSource) {
        this.defaultNetwork = defaultNetwork;
        this.defaultSource = defaultSource;
    }

    public static @Nullable RecipeExtractionScope begin(MEStorage network, IActionSource source) {
        if (network == null || source == null) {
            return null;
        }
        return new RecipeExtractionScope(network, source);
    }

    public static RecipeExtractionScope beginShared() {
        RecipeExtractionScope existing = CURRENT.get();
        if (existing != null) {
            existing.depth++;
            return existing;
        }
        RecipeExtractionScope scope = new RecipeExtractionScope(null, null);
        scope.depth = 1;
        CURRENT.set(scope);
        return scope;
    }

    public static @Nullable RecipeExtractionScope current() {
        return CURRENT.get();
    }

    public int checkpoint() {
        return staged.size();
    }

    public void rollbackTo(int checkpoint) {
        for (int i = staged.size() - 1; i >= checkpoint; i--) {
            Staged entry = staged.remove(i);
            returnOne(entry);
        }
    }

    public long extract(AEKey key, long amount) {
        return extract(defaultNetwork, defaultSource, key, amount);
    }

    public long extract(MEStorage network, IActionSource source, AEKey key, long amount) {
        if (network == null || source == null || key == null || amount <= 0L) {
            return 0L;
        }
        long got = network.extract(key, amount, Actionable.MODULATE, source);
        if (got > 0L) {
            staged.add(new Staged(network, source, key, got));
        }
        return got;
    }

    public void commit() {
        if (depth <= 1) {
            committed = true;
        }
    }

    @Override
    public void close() {
        if (depth > 1) {
            depth--;
            return;
        }
        depth = 0;
        if (CURRENT.get() == this) {
            CURRENT.remove();
        }
        if (closed) {
            return;
        }
        closed = true;
        if (committed) {
            staged.clear();
            return;
        }
        for (int i = staged.size() - 1; i >= 0; i--) {
            Staged entry = staged.remove(i);
            returnOne(entry);
        }
    }

    private static void returnOne(Staged entry) {
        try {
            long returned = entry.network.insert(entry.key, entry.amount, Actionable.MODULATE, entry.source);
            if (returned < entry.amount) {
                RecipeEscrow.add(entry.network, entry.source, entry.key, entry.amount - returned);
            }
        } catch (RuntimeException | Error failure) {
            RecipeEscrow.addUncertain(entry.network, entry.source, entry.key, entry.amount);
        }
    }

    private record Staged(MEStorage network, IActionSource source, AEKey key, long amount) {}
}
