/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.runtime;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * One synchronous, identity-matched reentry permit. The hook consumes it before
 * entering the original body, so that body's recursive dependency calls are not
 * bypassed. Nested fallbacks have independent permits; exceptions always unwind
 * their scope. Returning a future does not extend the permit to its continuations.
 *
 * <p>Confined to its constructing thread. This is a recursion guard, not a lock,
 * asynchronous admission token or authorization to call owner-thread APIs.
 */
public final class ScopedReentryPermit<H, S> {
    private final Thread owner = Thread.currentThread();
    private Scope<H, S> active;

    /** Invokes a fallback whose single exact hook reentry may consume this permit. */
    public <T> T call(H holder, S stage, Supplier<T> original) {
        requireOwner();
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(original, "original");
        Scope<H, S> previous = active;
        active = new Scope<>(holder, stage);
        try { return original.get(); }
        finally { active = previous; }
    }

    /** Uses reference identity; other holders/stages and subsequent calls remain hooked. */
    public boolean consume(H holder, S stage) {
        requireOwner();
        Scope<H, S> scope = active;
        if (scope == null || scope.consumed || scope.holder != holder || scope.stage != stage) return false;
        scope.consumed = true;
        return true;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Reentry permits are confined to their owner thread");
    }

    private static final class Scope<H, S> {
        final H holder;
        final S stage;
        boolean consumed;
        Scope(H holder, S stage) { this.holder = holder; this.stage = stage; }
    }
}
