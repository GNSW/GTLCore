package org.gtlcore.gtlcore.integration.gtmt.monitor;

import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;

public final class EnergyMonitorBlock extends BaseEntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public EnergyMonitorBlock() {
        super(Properties.copy(Blocks.IRON_BLOCK));
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING);
    }

    public BlockState getStateForPlacement(BlockPlaceContext c) {
        return defaultBlockState().setValue(FACING, c.getHorizontalDirection().getOpposite());
    }

    public RenderShape getRenderShape(BlockState s) {
        return RenderShape.MODEL;
    }

    public BlockEntity newBlockEntity(BlockPos p, BlockState s) {
        return new EnergyMonitorBlockEntity(p, s);
    }

    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l, BlockState s, BlockEntityType<T> t) {
        return l.isClientSide ? null : createTickerHelper(t, EnergyMonitorContent.ENTITY.get(), EnergyMonitorBlockEntity::tick);
    }

    public void setPlacedBy(Level l, BlockPos p, BlockState s, LivingEntity entity, ItemStack stack) {
        super.setPlacedBy(l, p, s, entity, stack);
        if (!l.isClientSide && entity instanceof Player player && l.getBlockEntity(p) instanceof EnergyMonitorBlockEntity be) be.setOwner(player.getUUID());
    }

    public InteractionResult use(BlockState s, Level l, BlockPos p, Player player, InteractionHand hand, BlockHitResult hit) {
        if (l.getBlockEntity(p) instanceof EnergyMonitorBlockEntity be) {
            EnergyMonitorBlockEntity center = be.center();
            if (center != null) {
                if (l.isClientSide) net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> org.gtlcore.gtlcore.client.monitor.EnergyMonitorScreen.open(center));
            } else if (!l.isClientSide) player.displayClientMessage(Component.translatable("monitor.gtlcore.incomplete"), true);
        }
        return InteractionResult.sidedSuccess(l.isClientSide);
    }
}
