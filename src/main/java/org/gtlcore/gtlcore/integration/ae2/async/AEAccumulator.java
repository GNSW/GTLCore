package org.gtlcore.gtlcore.integration.ae2.async;

import appeng.api.stacks.AEKey;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import lombok.Getter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class AEAccumulator {

    @Getter
    private final ConcurrentHashMap<AEKey, LongAdder> acc = new ConcurrentHashMap<>();

    public synchronized void add(AEKey key, long delta) {
        if (key == null || delta == 0) return;
        acc.compute(key, (k, adder) -> {
            if (adder == null) adder = new LongAdder();
            adder.add(delta);
            return adder;
        });
    }

    public synchronized void drainTo(Object2LongOpenHashMap<AEKey> buffer) {
        for (AEKey key : acc.keySet()) {
            LongAdder adder = acc.get(key);
            if (adder == null) continue;
            long drained = adder.sumThenReset();
            if (drained != 0) buffer.addTo(key, drained);
            // 与 add 走同一把 bin 锁：期间没有新数据才移除，否则留给下一次排空
            acc.computeIfPresent(key, (k, current) -> current.sum() == 0 ? null : current);
        }
    }

    public synchronized void clear() {
        acc.clear();
    }

    public synchronized boolean isEmpty() {
        return acc.isEmpty();
    }
}
