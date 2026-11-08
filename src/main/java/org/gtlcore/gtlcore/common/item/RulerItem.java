package org.gtlcore.gtlcore.common.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public class RulerItem extends Item {

    private static final String START = "RulerStart";
    private static final String END = "RulerEnd";
    private static final String DIMENSION = "RulerDimension";

    public RulerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        return useOn(context);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;

        ItemStack stack = context.getItemInHand();
        CompoundTag tag = stack.getOrCreateTag();
        BlockPos pos = context.getClickedPos();
        String dimension = level.dimension().location().toString();

        if (player.isShiftKeyDown()) {
            tag.remove(START);
            tag.remove(END);
            tag.remove(DIMENSION);
            player.displayClientMessage(Component.translatable("message.gtlcore.ruler.clear")
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.SUCCESS;
        }

        boolean sameDimension = dimension.equals(tag.getString(DIMENSION));
        if (!tag.contains(START) || !sameDimension || tag.contains(END)) {
            tag.putLong(START, pos.asLong());
            tag.putString(DIMENSION, dimension);
            tag.remove(END);
            player.displayClientMessage(Component.translatable("message.gtlcore.ruler.start", format(pos))
                    .withStyle(ChatFormatting.AQUA), true);
            return InteractionResult.SUCCESS;
        }

        BlockPos start = BlockPos.of(tag.getLong(START));
        tag.putLong(END, pos.asLong());
        double distance = measure(start, pos);
        player.displayClientMessage(Component.translatable("message.gtlcore.ruler.measured",
                String.format(Locale.ROOT, "%.2f", distance)).withStyle(ChatFormatting.GREEN), true);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.gtlcore.ruler.help").withStyle(ChatFormatting.GRAY));
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(START)) return;

        BlockPos start = BlockPos.of(tag.getLong(START));
        tooltip.add(Component.translatable("tooltip.gtlcore.ruler.start", format(start))
                .withStyle(ChatFormatting.AQUA));
        if (!tag.contains(END)) return;

        BlockPos end = BlockPos.of(tag.getLong(END));
        tooltip.add(Component.translatable("tooltip.gtlcore.ruler.end", format(end))
                .withStyle(ChatFormatting.AQUA));
        double distance = measure(start, end);
        tooltip.add(Component.translatable("tooltip.gtlcore.ruler.distance",
                String.format(Locale.ROOT, "%.2f", distance)).withStyle(ChatFormatting.GREEN));
    }

    private static double measure(BlockPos start, BlockPos end) {
        int dx = Math.max(0, Math.abs(end.getX() - start.getX()) - 1);
        int dy = Math.max(0, Math.abs(end.getY() - start.getY()) - 1);
        int dz = Math.max(0, Math.abs(end.getZ() - start.getZ()) - 1);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String format(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
