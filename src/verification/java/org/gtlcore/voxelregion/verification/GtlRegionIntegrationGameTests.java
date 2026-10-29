package org.gtlcore.voxelregion.verification;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.util.BlockStateWatcher;
import org.gtlcore.gtlcore.integration.voxelregion.GtlRegionExplosions;

import com.gregtechceu.gtceu.api.block.OreBlock;
import com.gregtechceu.gtceu.api.data.tag.TagPrefix;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import com.gregtechceu.gtceu.api.pattern.MultiblockWorldSavedData;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import org.voxelregion.forge.ForgeRegionEngine;
import org.voxelregion.forge.HostIntegration;
import org.voxelregion.forge.RegionGeometry;
import org.voxelregion.geometry.IntBox;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Real host API and native batch, plus the registered GTL/GTCEu observer bridges. Verification sources only. */
@GameTestHolder(GTLCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GtlRegionIntegrationGameTests {

    private static final TicketType<UUID> HOLD = TicketType.create("gtl_vre_integration_test", Comparator.<UUID>naturalOrder());

    @GameTest(template = "vre_empty", batch = "gtl_vre_host_observers", timeoutTicks = 6000)
    public static void hostBatchPreservesProtectionAndNotifiesOnlyChangedWatchedPositions(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        var cleanupActions = new ArrayList<Runnable>();
        boolean[] cleaned = { false };
        Runnable cleanup = () -> {
            if (cleaned[0]) return;
            cleaned[0] = true;
            for (Runnable action : cleanupActions) action.run();
        };
        cleanupOnCompletion(helper, cleanup);
        ChunkPos chunk = new ChunkPos(2700, 2700);
        UUID holder = UUID.randomUUID();
        level.getChunkSource().addRegionTicket(HOLD, chunk, 0, holder);
        cleanupActions.add(() -> level.getChunkSource().removeRegionTicket(HOLD, chunk, 0, holder));
        level.getChunk(chunk.x, chunk.z);
        BlockPos origin = new BlockPos(chunk.getMinBlockX(), 128, chunk.getMinBlockZ());
        BlockPos watched = origin.offset(3, 3, 3), protectedPos = origin.offset(4, 3, 3), orePos = origin.offset(5, 3, 3);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++)
            level.setBlock(origin.offset(x, y, z), Blocks.STONE.defaultBlockState(), flags);
        Block ore = ForgeRegistries.BLOCKS.getValues().stream().filter(block -> block.getClass() == OreBlock.class)
                .filter(block -> TagPrefix.ORES.get(((OreBlock) block).tagPrefix) != null
                        && !TagPrefix.ORES.get(((OreBlock) block).tagPrefix).isSand())
                .filter(block -> block.defaultBlockState().getLightEmission(level, orePos) == 0
                        && block.defaultBlockState().getFluidState().isEmpty()).findFirst().orElseThrow();
        level.setBlock(protectedPos, Blocks.BEDROCK.defaultBlockState(), flags);
        level.setBlock(orePos, ore.defaultBlockState(), flags);
        helper.assertTrue(HostIntegration.supportsBulk(level), "Actual Core and GTCEu global mutation observers allow batching");

        int[] notifications = { 0, 0, 0 };
        AtomicReference<BlockStateWatcher.WatcherHandle> selfRemoving = new AtomicReference<>();
        selfRemoving.set(BlockStateWatcher.addWatcher(level, watched, state -> {
            if (state.isAir()) notifications[0]++;
            selfRemoving.get().remove();
        }));
        cleanupActions.add(() -> selfRemoving.get().remove());
        var second = BlockStateWatcher.addWatcher(level, watched, state -> { if (state.isAir()) notifications[1]++; });
        cleanupActions.add(second::remove);
        var protectedWatcher = BlockStateWatcher.addWatcher(level, protectedPos, state -> notifications[2]++);
        cleanupActions.add(protectedWatcher::remove);
        var structure = new ObservedStructure(level, origin.offset(1, 1, 1));
        structure.addPosCache(watched);
        structure.addPosCache(orePos);
        var multiblocks = MultiblockWorldSavedData.getOrCreate(level);
        multiblocks.addMapping(structure);
        cleanupActions.add(() -> multiblocks.removeMapping(structure));
        AtomicReference<ForgeRegionEngine.Handle> handle = new AtomicReference<>();
        cleanupActions.add(() -> {
            if (handle.get() != null) GtlRegionExplosions.control(level, handle.get().id(), ForgeRegionEngine.Control.CANCEL);
        });
        handle.set(GtlRegionExplosions.submit(level, UUID.randomUUID(), new RegionGeometry.Box(new IntBox(
                origin.getX(), origin.getY(), origin.getZ(), origin.getX() + 16, origin.getY() + 16, origin.getZ() + 16)),
                new ForgeRegionEngine.Options(0, false, true)));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(GtlRegionExplosions.status(level, handle.get().id()).orElseThrow().state()
                        == ForgeRegionEngine.State.WRITTEN, "Integrated Core operation completed its native persistence fences"))
                .thenExecute(() -> {
                    var status = GtlRegionExplosions.status(level, handle.get().id()).orElseThrow();
                    helper.assertTrue(status.bulkSections() >= 1, "Vanilla terrain and the audited GT ore were actually batched in the Core host");
                    helper.assertTrue(status.changed() == 4095 && level.getBlockState(protectedPos).is(Blocks.BEDROCK)
                            && level.getBlockState(watched).isAir() && level.getBlockState(orePos).isAir(),
                            "Exact section change count retains bedrock and removes safe ore");
                    helper.assertTrue(notifications[0] == 1 && notifications[1] == 1 && notifications[2] == 0,
                            "Registered changed watchers receive one update, self-removal is safe, and protected watchers receive none");
                    helper.assertTrue(structure.changes == 1 && structure.lastState.isAir(),
                            "Two changed positions in one cached section cause one complete-structure revalidation notification");
                    GTLCore.LOGGER.info("VRE_CORE_NATIVE_GATE_PASS changed={} bulkSections={} ore={} watchers={}/{}/{} structureNotifications={}",
                            status.changed(), status.bulkSections(), ForgeRegistries.BLOCKS.getKey(ore),
                            notifications[0], notifications[1], notifications[2], structure.changes);
                    cleanup.run();
                }).thenSucceed();
    }

    private static final class ObservedStructure extends MultiblockState {
        private final IMultiController controller;
        int changes;
        BlockState lastState;

        ObservedStructure(ServerLevel level, BlockPos position) {
            super(level, position);
            clean(); // GT initializes the pattern cache when beginning a real pattern check.
            controller = (IMultiController) Proxy.newProxyInstance(IMultiController.class.getClassLoader(),
                    new Class<?>[] { IMultiController.class }, (proxy, method, args) -> {
                        if (method.getName().equals("isFormed")) return true;
                        if (method.getName().equals("toString")) return "VRE observer route fixture";
                        throw new IllegalStateException("Unexpected observer fixture controller method: " + method.getName());
                    });
        }

        @Override public IMultiController getController() { return controller; }
        @Override public void onBlockStateChanged(BlockPos position, BlockState state) { changes++; lastState = state; }
    }

    private static void cleanupOnCompletion(GameTestHelper helper, Runnable cleanup) {
        try {
            for (Field field : GameTestHelper.class.getDeclaredFields()) if (field.getType() == GameTestInfo.class) {
                field.setAccessible(true);
                ((GameTestInfo) field.get(helper)).addListener(new GameTestListener() {
                    public void testStructureLoaded(GameTestInfo info) {}
                    public void testPassed(GameTestInfo info) { cleanup.run(); }
                    public void testFailed(GameTestInfo info) { cleanup.run(); }
                });
                return;
            }
            throw new IllegalStateException("GameTest cleanup listener unavailable");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private GtlRegionIntegrationGameTests() {}
}
