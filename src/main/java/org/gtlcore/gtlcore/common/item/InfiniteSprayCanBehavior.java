package org.gtlcore.gtlcore.common.item;

import com.gregtechceu.gtceu.api.item.component.IAddInformation;
import com.gregtechceu.gtceu.api.item.component.IInteractionItem;
import com.gregtechceu.gtceu.common.item.ColorSprayBehaviour;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import appeng.api.util.AEColor;
import appeng.blockentity.networking.CableBusBlockEntity;

import java.util.List;

public class InfiniteSprayCanBehavior implements IInteractionItem, IAddInformation {

    public static final int MODE_COUNT = 17;
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

    private static ColorSprayBehaviour behavior(ItemStack stack) {
        int mode = getMode(stack);
        return mode < 16 ? COLORS[mode] : SOLVENT;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        int mode = getMode(stack);
        Player player = context.getPlayer();
        if (player != null && context.getLevel().getBlockEntity(context.getClickedPos()) instanceof CableBusBlockEntity cableBus) {
            AEColor color = mode < 16 ? AEColor.fromDye(DyeColor.byId(mode)) : AEColor.TRANSPARENT;
            if (cableBus.recolourBlock(context.getClickedFace(), color, player)) {
                return InteractionResult.SUCCESS;
            }
        }
        ColorSprayBehaviour behavior = behavior(stack);
        ColorSprayBehaviour.setUsesLeft(stack, behavior.totalUses);
        InteractionResult result = behavior.useOn(context);
        ColorSprayBehaviour.setUsesLeft(stack, behavior.totalUses);
        return result;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.gtlcore.infinite_spray_can.mode." + getMode(stack)));
    }
}
