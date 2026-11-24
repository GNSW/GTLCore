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
 * 坐标定位卡：右键方块设起点，潜行 + 右键方块设终点，潜行 + 右键空气清空。
 * <p>
 * 两个点都存在时，手持该卡会在世界中高亮框选区域（颜色与结构放置器的放置边界不同）。
 * 不可堆叠，一张卡就够导出一个区域。
 * </p>
 */
public class CoordinatePositioningCardItem extends Item {

    private static final String TAG_START = "CoordinateStart";
    private static final String TAG_END = "CoordinateEnd";
    /** 旧版单点字段，读取时当作起点兼容。 */
    private static final String TAG_LEGACY = "CoordinatePos";

    public CoordinatePositioningCardItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        ItemStack stack = context.getItemInHand();
        Player player = context.getPlayer();
        if (player != null && player.isSecondaryUseActive()) {
            setEnd(stack, pos);
        } else {
            setStart(stack, pos);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isSecondaryUseActive()) {
            clear(stack);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.gtlcore.coordinate_positioning_card.help")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(pointLine("tooltip.gtlcore.coordinate_positioning_card.start", getStart(stack)));
        tooltip.add(pointLine("tooltip.gtlcore.coordinate_positioning_card.end", getEnd(stack)));
        if (getStart(stack) == null && getEnd(stack) == null) {
            tooltip.add(Component.translatable("tooltip.gtlcore.coordinate_positioning_card.unset")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        super.appendHoverText(stack, level, tooltip, flag);
    }

    private static Component pointLine(String langKey, @Nullable BlockPos pos) {
        if (pos == null) {
            return Component.translatable(langKey, "-").withStyle(ChatFormatting.DARK_GRAY);
        }
        return Component.translatable(langKey, pos.getX() + ", " + pos.getY() + ", " + pos.getZ())
                .withStyle(ChatFormatting.GRAY);
    }

    // -------------------------------------------------------------------------
    // 存取
    // -------------------------------------------------------------------------

    public static void setStart(ItemStack stack, BlockPos pos) {
        stack.getOrCreateTag().putLong(TAG_START, pos.asLong());
    }

    public static void setEnd(ItemStack stack, BlockPos pos) {
        stack.getOrCreateTag().putLong(TAG_END, pos.asLong());
    }

    public static void clear(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return;
        tag.remove(TAG_START);
        tag.remove(TAG_END);
        tag.remove(TAG_LEGACY);
    }

    @Nullable
    public static BlockPos getStart(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return null;
        if (tag.contains(TAG_START)) return decode(tag.getLong(TAG_START));
        if (tag.contains(TAG_LEGACY)) return decode(tag.getLong(TAG_LEGACY));
        return null;
    }

    @Nullable
    public static BlockPos getEnd(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(TAG_END) ? decode(tag.getLong(TAG_END)) : null;
    }

    /** 起点与终点都已设置。 */
    public static boolean hasRegion(ItemStack stack) {
        return getStart(stack) != null && getEnd(stack) != null;
    }

    private static BlockPos decode(long packed) {
        BlockPos pos = BlockPos.of(packed);
        return pos.equals(BlockPos.ZERO) ? null : pos;
    }
}
