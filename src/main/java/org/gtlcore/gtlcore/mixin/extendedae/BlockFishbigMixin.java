package org.gtlcore.gtlcore.mixin.extendedae;

import org.gtlcore.gtlcore.common.block.PlushieBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import com.glodblock.github.extendedae.common.blocks.BlockFishbig;
import org.spongepowered.asm.mixin.Mixin;

/** Adds the same animated block entity contract to the original ExtendedAE fish. */
@Mixin(value = BlockFishbig.class, remap = false)
public abstract class BlockFishbigMixin implements EntityBlock {

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PlushieBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        return null;
    }
}
