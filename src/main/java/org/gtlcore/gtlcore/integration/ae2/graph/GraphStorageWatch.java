package org.gtlcore.gtlcore.integration.ae2.graph;

import appeng.api.networking.IStackWatcher;
import appeng.api.networking.storage.IStorageWatcherNode;
import appeng.api.stacks.AEKey;
import appeng.me.helpers.InterestManager;
import appeng.me.helpers.StackWatcher;

import java.util.Set;

/** A real AE storage interest: changes wake only tasks depending on those keys. */
public interface GraphStorageWatch {

    Subscription gtlcore$watchGraphResources(Set<AEKey> keys, Runnable changed);

    static Subscription subscribe(InterestManager<StackWatcher<IStorageWatcherNode>> interests, Set<AEKey> keys, Runnable changed) {
        var watcher = new StackWatcher<>(interests, new IStorageWatcherNode() {

            @Override
            public void updateWatcher(IStackWatcher replacement) {}

            @Override
            public void onStackChange(AEKey key, long amount) {
                changed.run();
            }
        });
        keys.forEach(watcher::add);
        return watcher::destroy;
    }

    interface Subscription extends AutoCloseable {

        @Override
        void close();
    }
}
