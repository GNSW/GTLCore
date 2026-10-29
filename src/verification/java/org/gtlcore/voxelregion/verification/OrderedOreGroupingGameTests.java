package org.gtlcore.voxelregion.verification;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.worldgen.OrderedOreGrouping;

import com.gregtechceu.gtceu.api.data.worldgen.WorldGeneratorUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.lang.reflect.Field;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Compares the actual transformed GT collector and replacement using identical key/value objects. */
@GameTestHolder(GTLCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OrderedOreGroupingGameTests {

    @GameTest(template = "vre_empty", batch = "gtl_ore_grouping", timeoutTicks = 200)
    public static void preservesNativeOreGroupOrder(GameTestHelper helper) throws Exception {
        verify();
        helper.succeed();
    }

    public static void verify() throws Exception {
        int checked = 0;
        for (Supplier<Map<BlockPos, Object>> factory : List.<Supplier<Map<BlockPos, Object>>>of(
                Object2ObjectOpenHashMap::new, HashMap::new, LinkedHashMap::new)) {
            for (int count : new int[] { 0, 1, 23, 24, 25, 47, 48, 49, 95, 96, 97, 192, 193, 1024, 4097 }) {
                Map<BlockPos, Object> input = factory.get();
                for (int i = 0; i < count; i++)
                    input.put(new BlockPos((i & 15) - 16, (i >>> 8) - 64, ((i >>> 4) & 15) - 16), new Object());
                compare(input);
                checked++;
            }
            // Exercise outer-map growth, negative chunk keys, and mixed inner-map growth histories.
            for (int count : new int[] { 1, 23, 24, 25, 48, 49, 96, 97, 193 }) {
                Map<BlockPos, Object> input = factory.get();
                for (int i = 0; i < count; i++) for (int k = 0; k < 1 + i % 51; k++)
                    input.put(new BlockPos((i - 100) * 16 + (k & 15), k >>> 4, ((i % 7) - 4) * 16), new Object());
                compare(input);
                checked++;
            }
            Map<BlockPos, Object> colliding = factory.get();
            // Vec3i.hashCode is (y + z * 31) * 31 + x: all these positions collide.
            for (int i = 0; i < 257; i++) colliding.put(new BlockPos(31 * i, -i, 0), new Object());
            compare(colliding);
            checked++;
            Map<BlockPos, Object> bounds = factory.get();
            for (int x : new int[] { Integer.MIN_VALUE, -17, -16, -1, 0, 15, 16, Integer.MAX_VALUE })
                for (int z : new int[] { Integer.MIN_VALUE, -1, 0, 16, Integer.MAX_VALUE })
                    bounds.put(new BlockPos(x, -64, z), new Object());
            compare(bounds);
            checked++;
        }
        // A deliberately unusual entry set tests the collector's first-wins merge contract.
        BlockPos duplicate = new BlockPos(1, 2, 3);
        Object first = new Object(), second = new Object();
        Set<Map.Entry<BlockPos, Object>> entries = new LinkedHashSet<>();
        entries.add(new AbstractMap.SimpleImmutableEntry<>(duplicate, first));
        entries.add(new AbstractMap.SimpleImmutableEntry<>(duplicate, second));
        Map<BlockPos, Object> repeated = new AbstractMap<>() {

            @Override
            public Set<Entry<BlockPos, Object>> entrySet() {
                return entries;
            }
        };
        compare(repeated);
        Map<BlockPos, Object> nullValue = new HashMap<>();
        nullValue.put(BlockPos.ZERO, null);
        sameFailure(nullValue);
        Map<BlockPos, Object> nullKey = new HashMap<>();
        nullKey.put(null, first);
        sameFailure(nullKey);
        sameFailure(null);
        System.out.println("OrderedOreGroupingChecks passed (" + (checked + 1) + " nonempty/empty layout cases, " +
                "first-wins, null failures, native iterator/stream/forEach order, value identity, original capacities)");
    }

    /** Reuse with one actual native-generated placer map; input objects are neither copied nor changed. */
    @SuppressWarnings("unchecked")
    public static void verifyMap(Map<BlockPos, ?> source) throws Exception {
        compare((Map<BlockPos, Object>) source);
    }

    private static void compare(Map<BlockPos, Object> input) throws Exception {
        long before = OrderedOreGrouping.completedCallsOnCurrentThread();
        Map<ChunkPos, Map<BlockPos, Object>> expected = nativeGroup(input, true);
        check(OrderedOreGrouping.completedCallsOnCurrentThread() == before, "Original path did not bypass replacement");
        Map<ChunkPos, Map<BlockPos, Object>> actual = nativeGroup(input, false);
        check(OrderedOreGrouping.completedCallsOnCurrentThread() == before + 1, "Fixture did not execute replacement");
        sameLayout(expected, actual, false);
        for (ChunkPos key : expected.keySet()) sameLayout(expected.get(key), actual.get(key), true);
    }

    private static <K, V> void sameLayout(Map<K, V> expected, Map<K, V> actual, boolean identity) throws Exception {
        check(expected.getClass() == actual.getClass(), "Map implementation changed");
        Field capacity = Object2ObjectOpenHashMap.class.getDeclaredField("n");
        capacity.setAccessible(true);
        check(capacity.getInt(expected) == capacity.getInt(actual), "Map capacity/rehash history changed");
        for (int mode = 0; mode < 3; mode++) {
            List<Map.Entry<K, V>> left = entries(expected, mode), right = entries(actual, mode);
            check(left.size() == right.size(), "Map entry count changed");
            for (int i = 0; i < left.size(); i++) {
                var a = left.get(i);
                var b = right.get(i);
                check(a.getKey().equals(b.getKey()), "Traversal order changed in mode " + mode + " at " + i);
                if (identity) check(a.getKey() == b.getKey() && a.getValue() == b.getValue(), "Input key/value identity changed");
            }
        }
    }

    private static <K, V> List<Map.Entry<K, V>> entries(Map<K, V> map, int mode) {
        var result = new ArrayList<Map.Entry<K, V>>();
        if (mode == 0) for (var e : map.entrySet()) result.add(new AbstractMap.SimpleImmutableEntry<>(e));
        else if (mode == 1) map.entrySet().stream().forEachOrdered(e -> result.add(new AbstractMap.SimpleImmutableEntry<>(e)));
        else map.forEach((key, value) -> result.add(new AbstractMap.SimpleImmutableEntry<>(key, value)));
        return result;
    }

    private static Map<ChunkPos, Map<BlockPos, Object>> nativeGroup(Map<BlockPos, Object> source, boolean original) {
        String previous = System.getProperty(OrderedOreGrouping.DISABLE_PROPERTY);
        try {
            System.setProperty(OrderedOreGrouping.DISABLE_PROPERTY, Boolean.toString(original));
            return WorldGeneratorUtils.groupByChunks(source);
        } finally {
            if (previous == null) System.clearProperty(OrderedOreGrouping.DISABLE_PROPERTY);
            else System.setProperty(OrderedOreGrouping.DISABLE_PROPERTY, previous);
        }
    }

    private static void sameFailure(Map<BlockPos, Object> source) {
        Throwable expected = failure(source, true), actual = failure(source, false);
        check(expected instanceof NullPointerException && actual instanceof NullPointerException, "Null rejection changed");
    }

    private static Throwable failure(Map<BlockPos, Object> source, boolean original) {
        try {
            nativeGroup(source, original);
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private OrderedOreGroupingGameTests() {}
}
