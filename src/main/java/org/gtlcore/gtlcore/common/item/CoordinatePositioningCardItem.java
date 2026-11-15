package org.gtlcore.gtlcore.common.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 坐标定位卡：右键方块记录坐标，潜行 + 右键清空。
 */
public class CoordinatePositioningCardItem extends Item {

    private static final String TAG_POS = "CoordinatePos";

    public CoordinatePositioningCardItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        setCoordinate(context.getItemInHand(), pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isSecondaryUseActive()) {
            clearCoordinate(stack);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        BlockPos pos = getCoordinate(stack);
        if (pos != null) {
            tooltip.add(Component.translatable(
                    "tooltip.quantum_chromodynamic_charge.coordinate_positioning",
                    pos.getX(), pos.getY(), pos.getZ()).withStyle(ChatFormatting.GRAY));
        }
        super.appendHoverText(stack, level, tooltip, flag);
    }

    public static void setCoordinate(ItemStack stack, BlockPos pos) {
        stack.getOrCreateTag().putLong(TAG_POS, pos.asLong());
    }

    public static void clearCoordinate(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove(TAG_POS);
        }
    }

    /** 未设置（或 0,0,0 哨兵）时返回 null，与原模组语义一致。 */
    @Nullable
    public static BlockPos getCoordinate(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_POS)) return null;
        BlockPos pos = BlockPos.of(tag.getLong(TAG_POS));
        return pos.equals(BlockPos.ZERO) ? null : pos;
    }
}
