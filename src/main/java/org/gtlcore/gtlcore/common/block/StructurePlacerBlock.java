package org.gtlcore.gtlcore.common.block;

import com.lowdragmc.lowdraglib.gui.factory.BlockEntityUIFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 结构放置器方块。库存与设置存入掉落物，重新放置时恢复。
 */
public class StructurePlacerBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public StructurePlacerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StructurePlacerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        return level.isClientSide ? null : StructurePlacerBlockEntity::tick;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hitResult) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            if (level.getBlockEntity(pos) instanceof StructurePlacerBlockEntity placer) {
                placer.markChanged();
                BlockEntityUIFactory.INSTANCE.openUI(placer, serverPlayer);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;
        if (!(level.getBlockEntity(pos) instanceof StructurePlacerBlockEntity be)) return;
        CompoundTag tag = getBlockEntityTag(stack);
        if (tag != null) {
            be.load(tag);
            be.markChanged();
        }
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        BlockEntity be = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (be instanceof StructurePlacerBlockEntity placer) {
            ItemStack drop = new ItemStack(this);
            setBlockEntityTag(drop, placer.saveWithoutMetadata());
            return List.of(drop);
        }
        return super.getDrops(state, params);
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, BlockGetter level,
                                       BlockPos pos, Player player) {
        if (level.getBlockEntity(pos) instanceof StructurePlacerBlockEntity placer) {
            ItemStack clone = new ItemStack(this);
            setBlockEntityTag(clone, placer.saveWithoutMetadata());
            return clone;
        }
        return super.getCloneItemStack(state, target, level, pos, player);
    }

    /** 用物品自带的 BlockEntityTag 保存/恢复放置器状态。 */
    private static final String TAG_BLOCK_ENTITY = "BlockEntityTag";

    private static void setBlockEntityTag(ItemStack stack, CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return;
        stack.getOrCreateTag().put(TAG_BLOCK_ENTITY, tag);
    }

    @Nullable
    private static CompoundTag getBlockEntityTag(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_BLOCK_ENTITY)) return null;
        return tag.getCompound(TAG_BLOCK_ENTITY);
    }
}
