package org.gtlcore.gtlcore.common.item;

import org.gtlcore.gtlcore.mixin.gtm.ColorSprayBehaviourAccessor;
import org.gtlcore.gtlcore.network.InfiniteSprayCanPackets;

import com.gregtechceu.gtceu.api.item.component.IAddInformation;
import com.gregtechceu.gtceu.api.item.component.IInteractionItem;
import com.gregtechceu.gtceu.common.item.ColorSprayBehaviour;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.util.AEColor;
import appeng.blockentity.networking.CableBusBlockEntity;

import java.util.List;

public class InfiniteSprayCanBehavior implements IInteractionItem, IAddInformation {

    public static final int MODE_COUNT = 17;
    public static final int MAX_RANGE_VOLUME = 4096;
    private static final String RANGE_MODE_TAG = "SprayRangeMode";
    private static final String RANGE_START_TAG = "SprayRangeStart";
    private static final String RANGE_DIMENSION_TAG = "SprayRangeDimension";
    private static final String MODE_TAG = "SprayMode";
    private static final ColorSprayBehaviour[] COLORS = new ColorSprayBehaviour[16];
    private static final ColorSprayBehaviour SOLVENT = new ColorSprayBehaviour(() -> ItemStack.EMPTY, 1024, -1);

    static {
        for (int i = 0; i < COLORS.length; i++) {
            COLORS[i] = new ColorSprayBehaviour(() -> ItemStack.EMPTY, 512, i);
        }
    }

    public static int getMode(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return 0;
        if (tag.contains(MODE_TAG)) return Math.floorMod(tag.getInt(MODE_TAG), MODE_COUNT);
        return Math.floorMod(tag.getInt("CustomModelData"), MODE_COUNT);
    }

    public static void setMode(ItemStack stack, int mode) {
        int normalized = Math.floorMod(mode, MODE_COUNT);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putInt(MODE_TAG, normalized);
        tag.putInt("CustomModelData", normalized);
    }

    public static boolean isRangeMode(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(RANGE_MODE_TAG);
    }

    public static boolean toggleRangeMode(ItemStack stack) {
        boolean rangeMode = !isRangeMode(stack);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putBoolean(RANGE_MODE_TAG, rangeMode);
        clearRangeStart(tag);
        return rangeMode;
    }

    public static BlockPos getRangeStart(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(RANGE_START_TAG) ? BlockPos.of(tag.getLong(RANGE_START_TAG)) : null;
    }

    public static String getRangeDimension(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null ? tag.getString(RANGE_DIMENSION_TAG) : "";
    }

    private static void setRangeStart(ItemStack stack, BlockPos pos, Level level) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putLong(RANGE_START_TAG, pos.asLong());
        tag.putString(RANGE_DIMENSION_TAG, level.dimension().location().toString());
    }

    private static void clearRangeStart(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        clearRangeStart(tag);
    }

    private static void clearRangeStart(CompoundTag tag) {
        tag.remove(RANGE_START_TAG);
        tag.remove(RANGE_DIMENSION_TAG);
    }

    private static ColorSprayBehaviour behavior(ItemStack stack) {
        int mode = getMode(stack);
        return mode < 16 ? COLORS[mode] : SOLVENT;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        Player player = context.getPlayer();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return player == null ? InteractionResult.PASS : InteractionResult.SUCCESS;
        }
        if (isRangeMode(stack)) return paintRange(serverPlayer, context);
        return paintSingle(serverPlayer, context);
    }

    private static InteractionResult paintSingle(ServerPlayer player, UseOnContext context) {
        boolean painted;
        try {
            painted = paintTarget(player, context.getLevel(), context.getClickedPos(),
                    context.getClickedFace(), context.getItemInHand());
        } catch (RuntimeException ignored) {
            // Modded targets may fail to resolve state data; do not break the interaction.
            return InteractionResult.PASS;
        }
        if (!painted) return InteractionResult.PASS;
        playSpraySound(player);
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult paintRange(ServerPlayer player, UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        Level level = context.getLevel();
        BlockPos clickedPos = context.getClickedPos();
        String dimension = level.dimension().location().toString();
        BlockPos start = getRangeStart(stack);

        if (start == null || !dimension.equals(getRangeDimension(stack))) {
            setRangeStart(stack, clickedPos, level);
            player.displayClientMessage(Component.translatable(
                    "message.gtlcore.infinite_spray_can.range.start",
                    clickedPos.getX(), clickedPos.getY(), clickedPos.getZ()), true);
            InfiniteSprayCanPackets.syncSelection(player, stack);
            return InteractionResult.SUCCESS;
        }

        BlockPos min = new BlockPos(
                Math.min(start.getX(), clickedPos.getX()),
                Math.min(start.getY(), clickedPos.getY()),
                Math.min(start.getZ(), clickedPos.getZ()));
        BlockPos max = new BlockPos(
                Math.max(start.getX(), clickedPos.getX()),
                Math.max(start.getY(), clickedPos.getY()),
                Math.max(start.getZ(), clickedPos.getZ()));
        long volume = ((long) max.getX() - min.getX() + 1) *
                ((long) max.getY() - min.getY() + 1) *
                ((long) max.getZ() - min.getZ() + 1);
        if (volume > MAX_RANGE_VOLUME) {
            player.displayClientMessage(Component.translatable(
                    "message.gtlcore.infinite_spray_can.range.too_large", MAX_RANGE_VOLUME), true);
            return InteractionResult.FAIL;
        }

        Direction face = context.getClickedFace();
        int painted = 0;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.isLoaded(pos)) continue;
            try {
                if (paintTarget(player, level, pos, face, stack)) painted++;
            } catch (RuntimeException ignored) {
                // Modded targets may fail to resolve state data; skip them and continue the range.
            }
        }

        clearRangeStart(stack);
        InfiniteSprayCanPackets.syncSelection(player, stack);
        if (painted == 0) {
            player.displayClientMessage(Component.translatable(
                    "message.gtlcore.infinite_spray_can.range.empty"), true);
            return InteractionResult.FAIL;
        }
        playSpraySound(player);
        player.displayClientMessage(Component.translatable(
                "message.gtlcore.infinite_spray_can.range.painted", painted), true);
        return InteractionResult.SUCCESS;
    }

    private static boolean paintTarget(ServerPlayer player, Level level, BlockPos pos, Direction face, ItemStack stack) {
        if (!level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;

        int mode = getMode(stack);
        if (level.getBlockEntity(pos) instanceof CableBusBlockEntity cableBus) {
            AEColor color = mode < 16 ? AEColor.fromDye(DyeColor.byId(mode)) : AEColor.TRANSPARENT;
            if (cableBus.recolourBlock(face, color, player)) return true;
        }

        ColorSprayBehaviour behavior = behavior(stack);
        return behavior instanceof ColorSprayBehaviourAccessor accessor &&
                accessor.gtlcore$tryPaintBlock(player, level, pos, face);
    }

    private static void playSpraySound(ServerPlayer player) {
        com.gregtechceu.gtceu.common.data.GTSoundEntries.SPRAY_CAN_TOOL.play(
                player.level(), player, player.position(), 1.0F, 1.0F);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.gtlcore.infinite_spray_can.mode." + getMode(stack)));
        tooltip.add(Component.translatable(isRangeMode(stack) ?
                "item.gtlcore.infinite_spray_can.tooltip.mode.range" :
                "item.gtlcore.infinite_spray_can.tooltip.mode.single").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.gtlcore.infinite_spray_can.tooltip.mode_switch",
                Component.keybind("key.gtlcore.infinite_spray_can.mode")).withStyle(ChatFormatting.GRAY));
        if (isRangeMode(stack)) {
            tooltip.add(Component.translatable("item.gtlcore.infinite_spray_can.tooltip.range")
                    .withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("item.gtlcore.infinite_spray_can.tooltip.configure",
                Component.keybind("key.sneak"), Component.keybind("key.use"))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.gtlcore.infinite_spray_can.tooltip.locked")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
