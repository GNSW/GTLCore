package org.gtlcore.voxelregion.verification;

import org.gtlcore.gtlcore.GTLCore;

import com.gregtechceu.gtceu.api.data.worldgen.generator.indicators.SurfaceIndicatorGenerator;
import com.gregtechceu.gtceu.api.data.worldgen.ores.OreIndicatorPlacer;
import com.mojang.datafixers.util.Either;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real transformed GT placement code, isolated regions and sections; never touches shared world chunks. */
@GameTestHolder(GTLCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SurfaceIndicatorPlacementGameTests {

    @GameTest(template = "vre_empty", batch = "gtl_ore_indicator_context", timeoutTicks = 200)
    public static void cachedIndicatorsUseTheirCallingRegion(GameTestHelper helper) throws Exception {
        verify(helper.getLevel());
        helper.succeed();
    }

    public static void verify(ServerLevel level) throws Exception {
        var state = Blocks.STONE.defaultBlockState();
        var generator = new SurfaceIndicatorGenerator(Either.left(state), ConstantInt.of(0), ConstantFloat.of(1),
                SurfaceIndicatorGenerator.IndicatorPlacement.SURFACE);
        Method original = SurfaceIndicatorGenerator.class.getDeclaredMethod("lambda$createPlacer$10", List.class,
                WorldGenLevel.class, BlockState.class, BulkSectionAccess.class);
        original.setAccessible(true);
        Method factory = SurfaceIndicatorGenerator.class.getDeclaredMethod("createPlacer", WorldGenLevel.class, List.class, BlockState.class);
        factory.setAccessible(true);
        List<BlockPos> positions = List.of(new BlockPos(2, 0, 3), new BlockPos(4, 0, 5));

        // The unmodified body is the oracle for a valid same-region invocation, including early return.
        for (boolean obstructed : new boolean[] { false, true }) {
            WorldGenLevel region = region(6, new AtomicInteger());
            var expected = new IsolatedAccess(level, region);
            var actual = new IsolatedAccess(level, region);
            if (obstructed) {
                expected.section.setBlockState(2, 6, 3, Blocks.DIRT.defaultBlockState(), false);
                actual.section.setBlockState(2, 6, 3, Blocks.DIRT.defaultBlockState(), false);
            }
            original.invoke(generator, positions, region, state, expected);
            placer(factory, generator, region, positions, state).placeIndicators(actual);
            equal(expected.section, actual.section, "Same-region placement changed");
        }

        // Cached by origin A, consumed through B: no query may be made against the captured region A.
        WorldGenLevel expired = expiredRegion();
        OreIndicatorPlacer cached = placer(factory, generator, expired, positions, state);
        AtomicInteger queries = new AtomicInteger();
        var current = new IsolatedAccess(level, region(9, queries));
        cached.placeIndicators(current);
        check(current.section.getBlockState(2, 9, 3) == state && current.section.getBlockState(4, 9, 5) == state,
                "Cached indicator used the wrong region height");
        check(queries.get() == 2, "Placement did not query its own region once per position");

        // Reusing one cached placer concurrently must not share a bound region or mutable section access.
        var first = new IsolatedAccess(level, region(3, new AtomicInteger()));
        var second = new IsolatedAccess(level, region(12, new AtomicInteger()));
        var workers = Executors.newFixedThreadPool(2);
        try {
            var left = workers.submit(() -> cached.placeIndicators(first));
            var right = workers.submit(() -> cached.placeIndicators(second));
            left.get(10, TimeUnit.SECONDS);
            right.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
        check(first.section.getBlockState(2, 3, 3) == state && first.section.getBlockState(2, 12, 3).isAir(),
                "First concurrent region used another call's context");
        check(second.section.getBlockState(2, 12, 3) == state && second.section.getBlockState(2, 3, 3).isAir(),
                "Second concurrent region used another call's context");

        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(7, 0, 8);
        OreIndicatorPlacer frozen = placer(factory, generator, expired, List.of(mutable), state);
        mutable.set(14, 0, 14);
        var stable = new IsolatedAccess(level, region(5, new AtomicInteger()));
        frozen.placeIndicators(stable);
        check(stable.section.getBlockState(7, 5, 8) == state && stable.section.getBlockState(14, 5, 14).isAir(),
                "Cached placer retained a mutable position");
        System.out.println("SurfaceIndicatorPlacementChecks passed (native equivalence, early return, current region, parallel isolation, immutable positions)");
    }

    private static OreIndicatorPlacer placer(Method factory, SurfaceIndicatorGenerator generator, WorldGenLevel region,
                                             List<BlockPos> positions, BlockState state) throws Exception {
        return (OreIndicatorPlacer) factory.invoke(generator, region, positions, state);
    }

    private static WorldGenLevel expiredRegion() {
        return (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] { WorldGenLevel.class },
                (proxy, method, args) -> { throw new AssertionError("Read from cached, expired generation region: " + method); });
    }

    private static WorldGenLevel region(int height, AtomicInteger queries) {
        return (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] { WorldGenLevel.class },
                (proxy, method, args) -> {
                    Class<?>[] parameters = method.getParameterTypes();
                    // Match descriptors, since this fixture also runs against production SRG names.
                    if (parameters.length == 3 && parameters[0] == Heightmap.Types.class && method.getReturnType() == int.class) {
                        queries.incrementAndGet();
                        return height;
                    }
                    if (parameters.length == 1 && parameters[0] == BlockPos.class && method.getReturnType() == boolean.class) {
                        int y = ((BlockPos) args[0]).getY();
                        return y < 0 || y >= 16;
                    }
                    throw new AssertionError("Unexpected region access: " + method);
                });
    }

    private static void equal(LevelChunkSection expected, LevelChunkSection actual, String message) {
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            check(expected.getBlockState(x, y, z) == actual.getBlockState(x, y, z), message + " at " + x + "," + y + "," + z);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class IsolatedAccess extends BulkSectionAccess {

        final LevelChunkSection section;

        IsolatedAccess(ServerLevel registrySource, WorldGenLevel region) {
            super(region);
            section = new LevelChunkSection(registrySource.registryAccess().registryOrThrow(Registries.BIOME));
        }

        @Override
        public LevelChunkSection getSection(BlockPos position) {
            check(position.getX() >= 0 && position.getX() < 16 && position.getY() >= 0 && position.getY() < 16
                    && position.getZ() >= 0 && position.getZ() < 16, "Placement escaped its isolated section");
            return section;
        }
    }

    private SurfaceIndicatorPlacementGameTests() {}
}
