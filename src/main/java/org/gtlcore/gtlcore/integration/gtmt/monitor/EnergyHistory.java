package org.gtlcore.gtlcore.integration.gtmt.monitor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Energy is arbitrary precision; only time and chart coordinates use primitive numbers. */
public final class EnergyHistory {

    public static final long[] WINDOWS = { 1200, 6000, 72000, 1728000 };
    private final NavigableMap<Long, BigInteger> seconds = new TreeMap<>();
    private final NavigableMap<Long, BigInteger> minutes = new TreeMap<>();

    public void sample(long tick, BigInteger energy) {
        seconds.put(tick, energy);
        if (minutes.isEmpty() || tick - minutes.lastKey() >= 1200) minutes.put(tick, energy);
        seconds.headMap(tick - WINDOWS[1] - 20, false).clear();
        if (minutes.size() > 4096) {
            var iterator = minutes.headMap(tick - WINDOWS[3] - 1200, false).entrySet().iterator();
            boolean remove = false;
            while (iterator.hasNext()) {
                iterator.next();
                if (remove) iterator.remove();
                remove = !remove;
            }
        }
    }

    public BigDecimal average(long tick, long window) {
        BigInteger start = energyAt(tick - window);
        BigInteger end = energyAt(tick);
        return start == null || end == null ? null : new BigDecimal(end.subtract(start))
                .divide(BigDecimal.valueOf(window), 2, RoundingMode.HALF_UP);
    }

    public BigInteger energyAt(long tick) {
        Map.Entry<Long, BigInteger> low = nearest(seconds.floorEntry(tick), minutes.floorEntry(tick), true);
        Map.Entry<Long, BigInteger> high = nearest(seconds.ceilingEntry(tick), minutes.ceilingEntry(tick), false);
        if (low == null || high == null) return null;
        if (low.getKey().equals(high.getKey())) return low.getValue();
        return low.getValue().add(high.getValue().subtract(low.getValue())
                .multiply(BigInteger.valueOf(tick - low.getKey()))
                .divide(BigInteger.valueOf(high.getKey() - low.getKey())));
    }

    private static Map.Entry<Long, BigInteger> nearest(Map.Entry<Long, BigInteger> a,
                                                       Map.Entry<Long, BigInteger> b, boolean floor) {
        if (a == null) return b;
        if (b == null) return a;
        return (floor ? a.getKey() > b.getKey() : a.getKey() < b.getKey()) ? a : b;
    }

    public List<Point> chart(long tick, long window) {
        List<Point> result = new ArrayList<>();
        for (int i = 0; i <= 160; i++) {
            long time = tick - window + window * i / 160;
            BigInteger value = energyAt(time);
            if (value != null) result.add(new Point(time, value));
        }
        return List.copyOf(result);
    }

    public List<Point> fullChart(long tick) {
        if (minutes.isEmpty()) return List.of();
        long start = minutes.firstKey();
        if (tick <= start) return List.of(new Point(start, minutes.firstEntry().getValue()));
        return chart(tick, tick - start);
    }

    public List<Point> savedPoints() {
        TreeMap<Long, BigInteger> all = new TreeMap<>(minutes);
        all.putAll(seconds);
        return all.entrySet().stream().map(e -> new Point(e.getKey(), e.getValue())).toList();
    }

    public void restore(List<Point> points) {
        seconds.clear();
        minutes.clear();
        points.stream().sorted(java.util.Comparator.comparingLong(Point::tick))
                .forEach(p -> sample(p.tick(), p.energy()));
    }

    public record Point(long tick, BigInteger energy) {}
}
