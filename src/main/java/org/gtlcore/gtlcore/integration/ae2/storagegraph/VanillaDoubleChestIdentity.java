package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoubleBlockCombiner;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraftforge.items.wrapper.InvWrapper;

import appeng.me.storage.ExternalStorageFacade;

/** Only recognize a proven, complete vanilla double-chest view. */
public final class VanillaDoubleChestIdentity {

    private VanillaDoubleChestIdentity() {}

    public static void register(ServerLevel level, BlockPos target,
                                Object handler, ExternalStorageFacade facade) {
        if (level == null || target == null || facade == null || handler == null || handler.getClass() != InvWrapper.class || !level.hasChunkAt(target)) return;
        InvWrapper wrapper = (InvWrapper) handler;
        if (!(wrapper.getInv() instanceof CompoundContainer compound) || compound.getClass() != CompoundContainer.class) return;
        var state = level.getBlockState(target);
        if (!state.is(Blocks.CHEST) && !state.is(Blocks.TRAPPED_CHEST)) return;
        if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return;
        BlockPos partner = target.relative(ChestBlock.getConnectedDirection(state));
        if (!level.hasChunkAt(partner)) return;
        ChestBlock chest = (ChestBlock) state.getBlock();
        Long id = chest.combine(state, level, target, true).apply(
                new DoubleBlockCombiner.Combiner<ChestBlockEntity, Long>() {

                    @Override
                    public Long acceptDouble(ChestBlockEntity a, ChestBlockEntity b) {
                        if (a == b || a.isRemoved() || b.isRemoved() || !compound.contains(a) || !compound.contains(b) || compound.getContainerSize() != a.getContainerSize() + b.getContainerSize()) {
                            return null;
                        }
                        // Both halves share one canonical id through the interner; no manual hash mixing.
                        long id = ExternalIdentityKeyRegistry.internPair(a, b, facade.getKeyType());
                        return id == 0L ? null : id;
                    }

                    @Override
                    public Long acceptSingle(ChestBlockEntity single) {
                        return null;
                    }

                    @Override
                    public Long acceptNone() {
                        return null;
                    }
                });
        if (id != null && id != 0L) ExternalInventoryIdentity.put(facade, id);
    }
}
